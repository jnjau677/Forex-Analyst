package com.devcode940.ForexAnalyst.data.repository

import android.util.Log
import com.devcode940.ForexAnalyst.data.db.ChartDrawingEntity
import com.devcode940.ForexAnalyst.data.db.CustomAlertEntity
import com.devcode940.ForexAnalyst.data.db.ForexDatabase
import com.devcode940.ForexAnalyst.data.db.HistoricalCandleEntity
import com.devcode940.ForexAnalyst.data.db.SavedSignalEntity
import com.devcode940.ForexAnalyst.data.db.WatchlistItemEntity
import com.devcode940.ForexAnalyst.data.model.AlertConditionType
import com.devcode940.ForexAnalyst.data.model.AlertId
import com.devcode940.ForexAnalyst.data.model.AppError
import com.devcode940.ForexAnalyst.data.model.CandleStick
import com.devcode940.ForexAnalyst.data.model.ChartDrawing
import com.devcode940.ForexAnalyst.data.model.ChartPoint
import com.devcode940.ForexAnalyst.data.model.CandleSource
import com.devcode940.ForexAnalyst.data.model.CustomAlert
import com.devcode940.ForexAnalyst.data.model.DataOrigin
import com.devcode940.ForexAnalyst.data.model.DrawingId
import com.devcode940.ForexAnalyst.data.model.DrawingStyle
import com.devcode940.ForexAnalyst.data.model.MarketDataProvider
import com.devcode940.ForexAnalyst.data.model.PairSymbol
import com.devcode940.ForexAnalyst.data.model.Result
import com.devcode940.ForexAnalyst.data.model.SignalId
import com.devcode940.ForexAnalyst.data.model.SignalType
import com.devcode940.ForexAnalyst.data.model.Timeframe
import com.devcode940.ForexAnalyst.data.model.TradeSide
import com.devcode940.ForexAnalyst.data.model.TradeSignal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Room-backed implementation of [MarketDataRepository].
 *
 * WHY: Entities use plain String/Long for Room+KAPT compatibility; the domain
 * layer uses value classes and [Instant]. All conversion is funnelled through
 * the mappers at the bottom of this file so no other layer touches raw DB
 * types. Mappers are defensive (mapNotNull + log): one corrupt row must never
 * crash an entire Flow.
 *
 * WHY [clock] is injected: cache-freshness and retention windows become
 * deterministically testable instead of depending on the wall clock.
 */
class ForexRepository(
    private val database: ForexDatabase,
    private val candleSource: CandleSource = SimulatedCandleSource(),
    private val clock: Clock = Clock.systemUTC()
) : MarketDataRepository {

    private val signalDao = database.signalDao()
    private val watchlistDao = database.watchlistDao()
    private val alertDao = database.alertDao()
    private val candleDao = database.historicalCandleDao()
    private val drawingDao = database.chartDrawingDao()

    // WHY: fetchHistoricalCandles is check-then-insert; without a Mutex two
    // concurrent callers (rapid pair switching) could both miss the cache
    // and double-generate/insert 500 candles.
    private val candleFetchMutex = Mutex()

    /**
     * Which feed produced the rows currently cached for each chart, keyed
     * `"$pair|$timeframe"`.
     *
     * WHY in memory and not a column: `historical_candles` has no `source` field, and
     * adding one means a Room migration that this sandbox cannot exercise with
     * `MigrationTestHelper` (no JDK, no Gradle, no Android SDK). The corner case this
     * leaves - rows cached by one provider and served while another is selected - is
     * bounded by the one-hour TTL and reported as a cache hit either way, so the user
     * is told the prices are not fresh. Recording it properly is noted in the README.
     *
     * WHY [ConcurrentHashMap]: the chart and the alert monitor read this from
     * different coroutines.
     */
    private val cachedSources = ConcurrentHashMap<String, MarketDataProvider>()

    private val _dataOrigin = MutableStateFlow(DataOrigin.UNKNOWN)

    override val dataOrigin: StateFlow<DataOrigin> = _dataOrigin.asStateFlow()

    // ---------- Signals ----------

    override fun observeSignals(pairSymbol: PairSymbol): Flow<List<TradeSignal>> {
        return signalDao.observeSignalsByPair(pairSymbol.value)
            .map { list -> list.mapNotNull { entity -> entity.toDomainOrNull() } }
    }

    override fun observeBookmarkedSignals(): Flow<List<TradeSignal>> {
        return signalDao.observeBookmarkedSignals()
            .map { list -> list.mapNotNull { entity -> entity.toDomainOrNull() } }
    }

    override suspend fun saveSignals(signals: List<TradeSignal>): Result<Unit> {
        // WHY: Early return keeps the no-op case allocation-free and avoids
        // opening a transaction for an empty batch.
        if (signals.isEmpty()) return Result.Success(Unit)
        return guarded("saveSignals") {
            // WHY: One batch insert (single transaction) instead of N inserts.
            // Idempotent by construction — unique fingerprint index + IGNORE
            // means retries/rescans never create duplicates, so no racy
            // "count then insert" check is needed (the old isSignalDuplicate
            // did exactly that and was never even called).
            signalDao.insertSignals(signals.map { signal -> signal.toEntity() })
        }
    }

    // ---------- Watchlist ----------

    override fun observeWatchlist(): Flow<List<PairSymbol>> {
        return watchlistDao.observeAllItems()
            .map { list -> list.mapNotNull { item -> runCatching { PairSymbol(item.symbol) }.getOrNull() } }
    }

    override suspend fun getActiveWatchlistPairs(): Result<List<PairSymbol>> = guarded("getActiveWatchlistPairs") {
        watchlistDao.getAllSymbols().mapNotNull { value ->
            runCatching { PairSymbol(value) }.getOrNull()
        }
    }

    override suspend fun addToWatchlist(pairSymbol: PairSymbol): Result<Unit> = guarded("addToWatchlist") {
        // WHY: REPLACE strategy — adding twice is a no-op, not a duplicate row.
        watchlistDao.insertItem(WatchlistItemEntity(pairSymbol.value, clock.instant()))
    }

    override suspend fun removeFromWatchlist(pairSymbol: PairSymbol): Result<Unit> =
        guarded("removeFromWatchlist") {
            watchlistDao.deleteItem(pairSymbol.value)
        }

    // ---------- Candles ----------

    override suspend fun fetchHistoricalCandles(
        pairSymbol: PairSymbol,
        timeframe: Timeframe,
        limit: Int
    ): Result<List<CandleStick>> = guarded("fetchHistoricalCandles") {
        // WHY: A negative LIMIT in SQLite means "unlimited" — clamp it.
        val safeLimit = limit.coerceIn(1, MarketDataRepository.MAX_CANDLE_LIMIT)
        val now = clock.instant()

        candleFetchMutex.withLock {
            // WHY: ONE query serves both the data and the freshness check —
            // the newest row is simply the first element of a DESC result.
            // The old code ran getLatestCandles + a separate MAX(timestamp).
            val cachedDesc = candleDao.getLatestCandles(pairSymbol.value, timeframe.value, safeLimit)
            val newest = cachedDesc.firstOrNull()?.timestamp
            if (newest != null && newest.isAfter(now.minusSeconds(CACHE_TTL_SECONDS))) {
                publishOrigin(
                    provider = cachedSources[originKey(pairSymbol, timeframe)]
                        ?: MarketDataProvider.SIMULATED,
                    isCached = true,
                    notice = null
                )
                // WHY: DAO returns DESC (newest first) for the LIMIT; asReversed
                // is an O(1) view + O(n) copy, cheaper than a full SQL sort.
                return@withLock cachedDesc.asReversed().mapNotNull { it.toDomainOrNull() }
            }

            // WHY the source is injected rather than called directly: this used to be
            // `PriceGenerator.generateCandles(...)`, which made the simulator the only
            // possible answer and buried "where do prices come from" inside the class
            // that owns the cache. Everything below - insert, prune, TTL - is unchanged.
            val batch = candleSource.load(
                pairSymbol = pairSymbol,
                timeframe = timeframe,
                limit = safeLimit,
                clock = clock
            )
            candleDao.insertCandles(batch.candles.map { it.toEntity(pairSymbol.value, timeframe.value) })
            cachedSources[originKey(pairSymbol, timeframe)] = batch.source
            publishOrigin(
                provider = batch.source,
                isCached = false,
                notice = batch.degradedReason?.userMessage
            )
            // WHY: Bound table growth — without pruning, every cache miss
            // appends 500 rows forever. Retention (30 days) comfortably
            // covers the generated span (500 x 1h ~= 21 days).
            candleDao.deleteOlderThan(
                pairSymbol.value,
                timeframe.value,
                now.minusSeconds(RETENTION_SECONDS)
            )
            batch.candles
        }
    }

    override suspend fun invalidateCandleCache(): Result<Unit> = guarded("invalidateCandleCache") {
        // WHY clear the provenance map too: leaving it would label the next cache hit
        // with a feed whose rows were just deleted.
        cachedSources.clear()
        candleDao.deleteAllCandles()
        Unit
    }

    private fun originKey(pairSymbol: PairSymbol, timeframe: Timeframe): String =
        "${pairSymbol.value}|${timeframe.value}"

    /**
     * Publishes where the current chart's prices came from.
     *
     * WHY a single global value rather than a per-chart map: the UI shows one badge on
     * one chart at a time, and a map would have to be diffed by the collector to work
     * out whether *its* chart changed. The last fetch wins, which is the chart on
     * screen.
     */
    private fun publishOrigin(
        provider: MarketDataProvider,
        isCached: Boolean,
        notice: String?
    ) {
        // WHY the pair and timeframe are not part of DataOrigin: the badge renders
        // inside a chart header that already states both, so carrying them here would
        // only invite a collector to filter on them and miss an update.
        _dataOrigin.value = DataOrigin(provider = provider, isCached = isCached, notice = notice)
    }

    // ---------- Alerts ----------

    override suspend fun getActiveAlerts(): Result<List<CustomAlert>> = guarded("getActiveAlerts") {
        alertDao.getActiveAlerts().mapNotNull { entity -> entity.toDomainOrNull() }
    }

    override fun observeAlerts(pairSymbol: PairSymbol): Flow<List<CustomAlert>> {
        return alertDao.observeActiveAlertsByPair(pairSymbol.value)
            .map { list -> list.mapNotNull { entity -> entity.toDomainOrNull() } }
    }

    override suspend fun saveAlert(alert: CustomAlert): Result<Unit> = guarded("saveAlert") {
        // WHY: REPLACE keyed by the alert's UUID — saving an edited alert is
        // idempotent, so a retry cannot create a second alert.
        alertDao.insertAlert(alert.toEntity())
    }

    override suspend fun deleteAlert(alertId: AlertId): Result<Unit> = guarded("deleteAlert") {
        alertDao.deleteAlert(alertId.value.toString())
    }

    /**
     * Runs [block], classifying failures and logging the real cause.
     *
     * WHY: classification lives in exactly ONE place ([Result.of]); this
     * wrapper only adds observability. Duplicating the catch ladder here would
     * let the two ladders drift apart (the classic "handled differently in two
     * layers" bug). What leaves this class is always an [AppError] with a safe,
     * actionable message — internal text (SQLite errors, stack traces) is
     * logged, never rendered.
     */
    private suspend fun <T> guarded(operation: String, block: suspend () -> T): Result<T> {
        val result = Result.of(block)
        if (result is Result.Failure) {
            val cause = (result.error as? AppError.Unknown)?.cause
            if (cause != null) {
                Log.e(TAG, "$operation failed", cause)
            } else {
                // WHY: classified errors (validation, network) are expected
                // outcomes — log at WARN without a stack trace to keep logcat
                // readable and avoid leaking details into bug reports.
                Log.w(TAG, "$operation failed: ${result.error.code}")
            }
        }
        return result
    }

    // ---------- Drawings ----------

    override fun observeDrawings(pairSymbol: PairSymbol, timeframe: Timeframe): Flow<List<ChartDrawing>> {
        return drawingDao
            .observeDrawings(pairSymbol.value, timeframe.value)
            .map { rows -> rows.mapNotNull { entity -> entity.toDomainOrNull() } }
    }

    override suspend fun saveDrawing(drawing: ChartDrawing): Result<Unit> = guarded("saveDrawing") {
        // WHY REPLACE keyed by the drawing's UUID: dragging an anchor re-saves the
        // same object, so a retry or a repeated commit updates one row instead of
        // duplicating it.
        drawingDao.insertDrawing(drawing.toEntity())
    }

    override suspend fun saveDrawings(drawings: List<ChartDrawing>): Result<Unit> = guarded("saveDrawings") {
        if (drawings.isEmpty()) return@guarded
        drawingDao.insertDrawings(drawings.map { it.toEntity() })
    }

    override suspend fun deleteDrawing(drawingId: DrawingId): Result<Unit> = guarded("deleteDrawing") {
        drawingDao.deleteDrawing(drawingId.value.toString())
    }

    override suspend fun deleteDrawingsForChart(
        pairSymbol: PairSymbol,
        timeframe: Timeframe
    ): Result<Unit> = guarded("deleteDrawingsForChart") {
        drawingDao.deleteDrawingsForChart(pairSymbol.value, timeframe.value)
    }

    override suspend fun replaceDrawingsForChart(
        pairSymbol: PairSymbol,
        timeframe: Timeframe,
        drawings: List<ChartDrawing>
    ): Result<Unit> = guarded("replaceDrawingsForChart") {
        // WHY filter here as well as in the DAO query: a snapshot can legitimately
        // contain objects from another chart (the store is global), and writing those
        // under this chart's key would duplicate them.
        val scoped = drawings.filter { it.pairSymbol == pairSymbol && it.timeframe == timeframe }
        drawingDao.replaceDrawingsForChart(pairSymbol.value, timeframe.value, scoped.map { it.toEntity() })
    }

    companion object {
        private const val TAG = "ForexRepository"
        private const val CACHE_TTL_SECONDS = 3_600L
        private const val RETENTION_SECONDS = 30L * 24 * 60 * 60 // 30 days
    }
}

// ---------------------------------------------------------------------------
// Mappers: Entity (String) <-> Domain (value classes)
// ---------------------------------------------------------------------------

/**
 * WHY: valueOf()/UUID.fromString()/value-class constructors all throw on
 * corrupt or legacy DB values. Skipping the row (with a log) is strictly
 * better than crashing the whole list.
 */
private fun SavedSignalEntity.toDomainOrNull(): TradeSignal? = runCatching {
    TradeSignal(
        id = SignalId(UUID.fromString(id)),
        pairSymbol = PairSymbol(pairSymbol),
        signalType = SignalType.valueOf(signalType),
        entryPrice = entryPrice,
        stopLoss = stopLoss,
        takeProfit1 = takeProfit1,
        takeProfit2 = takeProfit2,
        riskRewardRatio = riskRewardRatio,
        confidenceScore = confidenceScore,
        timeframe = Timeframe(timeframe),
        summaryRationale = summaryRationale,
        timestamp = timestamp,
        isBookmarked = isBookmarked
    )
}.onFailure { Log.w("ForexRepository", "Skipping corrupt signal row id=$id: ${it.message}") }.getOrNull()

private fun TradeSignal.toEntity(): SavedSignalEntity {
    return SavedSignalEntity(
        id = id.value.toString(),
        pairSymbol = pairSymbol.value,
        signalType = signalType.name,
        entryPrice = entryPrice,
        stopLoss = stopLoss,
        takeProfit1 = takeProfit1,
        takeProfit2 = takeProfit2,
        riskRewardRatio = riskRewardRatio,
        confidenceScore = confidenceScore,
        timeframe = timeframe.value,
        summaryRationale = summaryRationale,
        timestamp = timestamp,
        isBookmarked = isBookmarked,
        fingerprint = buildFingerprint()
    )
}

/**
 * WHY: SHA-256 (not String.hashCode) — 32-bit hashes collide, and a
 * collision silently drops a legitimate signal via the unique index.
 * Identity = pair + timeframe + type + candle time, so rescans of the
 * same bar are idempotent. Locale.US because "%.5f" uses the default
 * locale (comma vs dot) and must be identical on every device.
 *
 * internal (not private) so unit tests can assert stability.
 */
internal fun TradeSignal.buildFingerprint(): String {
    val raw = "${pairSymbol.value}|${timeframe.value}|${signalType.name}|${timestamp.toEpochMilli()}"
    val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { byte -> String.format(Locale.US, "%02x", byte.toInt() and 0xFF) }
}

private fun CustomAlertEntity.toDomainOrNull(): CustomAlert? = runCatching {
    CustomAlert(
        id = AlertId(UUID.fromString(id)),
        pairSymbol = PairSymbol(pairSymbol),
        conditionType = AlertConditionType.valueOf(conditionType),
        targetValue = targetValue,
        isTriggered = isTriggered,
        isActive = isActive,
        note = note,
        createdAt = createdAt
    )
}.onFailure { Log.w("ForexRepository", "Skipping corrupt alert row id=$id: ${it.message}") }.getOrNull()

private fun CustomAlert.toEntity(): CustomAlertEntity {
    return CustomAlertEntity(
        id = id.value.toString(),
        pairSymbol = pairSymbol.value,
        conditionType = conditionType.name,
        targetValue = targetValue,
        isTriggered = isTriggered,
        isActive = isActive,
        note = note,
        createdAt = createdAt
    )
}

private fun HistoricalCandleEntity.toDomainOrNull(): CandleStick? = runCatching {
    CandleStick(
        time = timestamp,
        open = open,
        high = high,
        low = low,
        close = close,
        volume = volume
    )
}.onFailure { Log.w("ForexRepository", "Skipping corrupt candle row: ${it.message}") }.getOrNull()

private fun CandleStick.toEntity(symbol: String, timeframe: String): HistoricalCandleEntity {
    return HistoricalCandleEntity(
        symbol = symbol,
        timeframe = timeframe,
        timestamp = time,
        open = open,
        high = high,
        low = low,
        close = close,
        volume = volume
    )
}

// ---------------------------------------------------------------------------
// Mappers: ChartDrawing (sealed domain) <-> ChartDrawingEntity (one table)
// ---------------------------------------------------------------------------

/**
 * Discriminator values stored in `chart_drawings.kind`.
 *
 * WHY explicit constants rather than `this::class.simpleName`: a rename or a move of a
 * domain subtype would silently change the stored discriminator and orphan every
 * existing row — the user's drawings would simply stop loading, with no error
 * anywhere. Freezing the spelling turns the mapping into a contract that a refactor
 * cannot break by accident.
 */
private object DrawingKind {
    const val HORIZONTAL_LINE = "HORIZONTAL_LINE"
    const val VERTICAL_LINE = "VERTICAL_LINE"
    const val TREND_LINE = "TREND_LINE"
    const val FIB_RETRACEMENT = "FIB_RETRACEMENT"
    const val FIB_EXTENSION = "FIB_EXTENSION"
    const val RECTANGLE = "RECTANGLE"
    const val MEASUREMENT = "MEASUREMENT"
    const val POSITION = "POSITION"
}

/**
 * Serializes fib levels.
 *
 * WHY `Double.toString()` / `toDouble()` and not a locale-aware formatter: both are
 * specified to use `.` as the decimal separator regardless of the device locale, and
 * `toString` emits the shortest representation that round-trips exactly. A
 * `String.format("%.6f")` would (a) break on a German locale without `Locale.US` and
 * (b) silently round 0.236 into a level that no longer matches the picker.
 */
// WHY `internal` and not `private` for the two mapping directions below: the
// entity↔domain round trip is the contract that keeps a drawing readable after a
// restart, and a JVM unit test in the same module is the only cheap way to cover all
// eight subtypes — the instrumented Room test covers the database, not the mapping.
// `internal` still keeps them out of the app's public surface.
internal fun List<Double>.toLevelsCsv(): String = joinToString(separator = ",") { it.toString() }

internal fun String?.toLevelsOrNull(): List<Double>? =
    this?.split(",")?.map { it.trim().toDouble() }?.takeIf { it.isNotEmpty() }

/** An entity pre-filled with the columns every drawing shares. */
private fun ChartDrawing.newEntity(kind: String) = ChartDrawingEntity(
    id = id.value.toString(),
    pairSymbol = pairSymbol.value,
    timeframe = timeframe.value,
    kind = kind,
    style = style.name,
    colorArgb = colorArgb,
    createdAt = createdAt,
    label = label,
    startTime = null,
    startPrice = null,
    endTime = null,
    endPrice = null,
    extendLeft = false,
    extendRight = false,
    levelsCsv = null,
    side = null,
    entryPrice = null,
    stopPrice = null,
    targetPrice = null,
    riskPercent = null
)

/**
 * WHY a horizontal line stores its price in `startPrice` and a vertical line stores
 * its time in `startTime`: those are the same two anchor columns every two-point tool
 * uses. Adding `horizontalPrice` and `verticalTime` columns would leave 7 of 8 kinds
 * with two more nulls each and no gain in clarity.
 */
internal fun ChartDrawing.toEntity(): ChartDrawingEntity = when (this) {
    is ChartDrawing.HorizontalLine -> newEntity(DrawingKind.HORIZONTAL_LINE).copy(startPrice = price)

    is ChartDrawing.VerticalLine ->
        newEntity(DrawingKind.VERTICAL_LINE).copy(startTime = time.toEpochMilli())

    is ChartDrawing.TrendLine -> newEntity(DrawingKind.TREND_LINE).copy(
        startTime = start.time.toEpochMilli(),
        startPrice = start.price,
        endTime = end.time.toEpochMilli(),
        endPrice = end.price,
        extendLeft = extendLeft,
        extendRight = extendRight
    )

    is ChartDrawing.FibRetracement -> newEntity(DrawingKind.FIB_RETRACEMENT).copy(
        startTime = start.time.toEpochMilli(),
        startPrice = start.price,
        endTime = end.time.toEpochMilli(),
        endPrice = end.price,
        levelsCsv = levels.toLevelsCsv()
    )

    is ChartDrawing.FibExtension -> newEntity(DrawingKind.FIB_EXTENSION).copy(
        startTime = start.time.toEpochMilli(),
        startPrice = start.price,
        endTime = end.time.toEpochMilli(),
        endPrice = end.price,
        levelsCsv = levels.toLevelsCsv()
    )

    is ChartDrawing.Rectangle -> newEntity(DrawingKind.RECTANGLE).copy(
        startTime = start.time.toEpochMilli(),
        startPrice = start.price,
        endTime = end.time.toEpochMilli(),
        endPrice = end.price
    )

    is ChartDrawing.Measurement -> newEntity(DrawingKind.MEASUREMENT).copy(
        startTime = start.time.toEpochMilli(),
        startPrice = start.price,
        endTime = end.time.toEpochMilli(),
        endPrice = end.price
    )

    is ChartDrawing.Position -> newEntity(DrawingKind.POSITION).copy(
        side = side.name,
        entryPrice = entryPrice,
        stopPrice = stopPrice,
        targetPrice = targetPrice,
        riskPercent = riskPercent
    )
}

private fun ChartDrawingEntity.startAnchor(): ChartPoint = ChartPoint(
    time = Instant.ofEpochMilli(startTime ?: error("row $id has no start time")),
    price = startPrice ?: error("row $id has no start price")
)

private fun ChartDrawingEntity.endAnchor(): ChartPoint = ChartPoint(
    time = Instant.ofEpochMilli(endTime ?: error("row $id has no end time")),
    price = endPrice ?: error("row $id has no end price")
)

/**
 * WHY defensive per row: a drawing's `init` block enforces real invariants (a long's
 * stop must sit below its entry, fib levels must be ascending and in range). A row
 * written by an older build, or corrupted storage, can violate them — and one bad row
 * must never take down the whole chart's object list.
 */
internal fun ChartDrawingEntity.toDomainOrNull(): ChartDrawing? = runCatching {
    val drawingId = DrawingId(UUID.fromString(id))
    val pair = PairSymbol(pairSymbol)
    val tf = Timeframe(timeframe)
    val drawingStyle = DrawingStyle.valueOf(style)

    when (kind) {
        DrawingKind.HORIZONTAL_LINE -> ChartDrawing.HorizontalLine(
            id = drawingId,
            pairSymbol = pair,
            timeframe = tf,
            style = drawingStyle,
            colorArgb = colorArgb,
            createdAt = createdAt,
            label = label,
            price = startPrice ?: error("row $id has no price")
        )

        DrawingKind.VERTICAL_LINE -> ChartDrawing.VerticalLine(
            id = drawingId,
            pairSymbol = pair,
            timeframe = tf,
            style = drawingStyle,
            colorArgb = colorArgb,
            createdAt = createdAt,
            label = label,
            time = Instant.ofEpochMilli(startTime ?: error("row $id has no time"))
        )

        DrawingKind.TREND_LINE -> ChartDrawing.TrendLine(
            id = drawingId,
            pairSymbol = pair,
            timeframe = tf,
            style = drawingStyle,
            colorArgb = colorArgb,
            createdAt = createdAt,
            label = label,
            start = startAnchor(),
            end = endAnchor(),
            extendLeft = extendLeft,
            extendRight = extendRight
        )

        DrawingKind.FIB_RETRACEMENT -> ChartDrawing.FibRetracement(
            id = drawingId,
            pairSymbol = pair,
            timeframe = tf,
            style = drawingStyle,
            colorArgb = colorArgb,
            createdAt = createdAt,
            label = label,
            start = startAnchor(),
            end = endAnchor(),
            levels = levelsCsv.toLevelsOrNull() ?: ChartDrawing.FibRetracement.DEFAULT_LEVELS
        )

        DrawingKind.FIB_EXTENSION -> ChartDrawing.FibExtension(
            id = drawingId,
            pairSymbol = pair,
            timeframe = tf,
            style = drawingStyle,
            colorArgb = colorArgb,
            createdAt = createdAt,
            label = label,
            start = startAnchor(),
            end = endAnchor(),
            levels = levelsCsv.toLevelsOrNull() ?: ChartDrawing.FibExtension.DEFAULT_LEVELS
        )

        DrawingKind.RECTANGLE -> ChartDrawing.Rectangle(
            id = drawingId,
            pairSymbol = pair,
            timeframe = tf,
            style = drawingStyle,
            colorArgb = colorArgb,
            createdAt = createdAt,
            label = label,
            start = startAnchor(),
            end = endAnchor()
        )

        DrawingKind.MEASUREMENT -> ChartDrawing.Measurement(
            id = drawingId,
            pairSymbol = pair,
            timeframe = tf,
            style = drawingStyle,
            colorArgb = colorArgb,
            createdAt = createdAt,
            label = label,
            start = startAnchor(),
            end = endAnchor()
        )

        DrawingKind.POSITION -> ChartDrawing.Position(
            id = drawingId,
            pairSymbol = pair,
            timeframe = tf,
            style = drawingStyle,
            colorArgb = colorArgb,
            createdAt = createdAt,
            label = label,
            side = TradeSide.valueOf(side ?: error("row $id has no side")),
            entryPrice = entryPrice ?: error("row $id has no entry price"),
            stopPrice = stopPrice ?: error("row $id has no stop price"),
            targetPrice = targetPrice ?: error("row $id has no target price"),
            riskPercent = riskPercent ?: ChartDrawing.Position.DEFAULT_RISK_PERCENT
        )

        else -> error("unknown drawing kind '$kind'")
    }
}.onFailure { Log.w("ForexRepository", "Skipping corrupt drawing row id=$id: ${it.message}") }.getOrNull()
