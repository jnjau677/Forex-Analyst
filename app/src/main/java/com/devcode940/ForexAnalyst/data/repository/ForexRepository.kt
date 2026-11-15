package com.devcode940.ForexAnalyst.data.repository

import android.util.Log
import com.devcode940.ForexAnalyst.data.db.CustomAlertEntity
import com.devcode940.ForexAnalyst.data.db.ForexDatabase
import com.devcode940.ForexAnalyst.data.db.HistoricalCandleEntity
import com.devcode940.ForexAnalyst.data.db.SavedSignalEntity
import com.devcode940.ForexAnalyst.data.db.WatchlistItemEntity
import com.devcode940.ForexAnalyst.data.model.AlertConditionType
import com.devcode940.ForexAnalyst.data.model.AlertId
import com.devcode940.ForexAnalyst.data.model.AppError
import com.devcode940.ForexAnalyst.data.model.CandleStick
import com.devcode940.ForexAnalyst.data.model.CustomAlert
import com.devcode940.ForexAnalyst.data.model.PairSymbol
import com.devcode940.ForexAnalyst.data.model.Result
import com.devcode940.ForexAnalyst.data.model.SignalId
import com.devcode940.ForexAnalyst.data.model.SignalType
import com.devcode940.ForexAnalyst.data.model.Timeframe
import com.devcode940.ForexAnalyst.data.model.TradeSignal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.Locale
import java.util.UUID

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
    private val clock: Clock = Clock.systemUTC()
) : MarketDataRepository {

    private val signalDao = database.signalDao()
    private val watchlistDao = database.watchlistDao()
    private val alertDao = database.alertDao()
    private val candleDao = database.historicalCandleDao()

    // WHY: fetchHistoricalCandles is check-then-insert; without a Mutex two
    // concurrent callers (rapid pair switching) could both miss the cache
    // and double-generate/insert 500 candles.
    private val candleFetchMutex = Mutex()

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
                // WHY: DAO returns DESC (newest first) for the LIMIT; asReversed
                // is an O(1) view + O(n) copy, cheaper than a full SQL sort.
                return@withLock cachedDesc.asReversed().mapNotNull { it.toDomainOrNull() }
            }

            val generated = PriceGenerator.generateCandles(
                count = safeLimit,
                basePrice = basePriceFor(pairSymbol.value),
                clock = clock
            )
            candleDao.insertCandles(generated.map { it.toEntity(pairSymbol.value, timeframe.value) })
            // WHY: Bound table growth — without pruning, every cache miss
            // appends 500 rows forever. Retention (30 days) comfortably
            // covers the generated span (500 x 1h ~= 21 days).
            candleDao.deleteOlderThan(
                pairSymbol.value,
                timeframe.value,
                now.minusSeconds(RETENTION_SECONDS)
            )
            generated
        }
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

    private fun basePriceFor(symbol: String): Double {
        return when {
            symbol.contains("JPY") -> 150.0
            symbol.contains("XAU") -> 2000.0
            symbol.contains("XAG") -> 24.0
            symbol.contains("BTC") -> 65000.0
            symbol.contains("ETH") -> 3200.0
            else -> 1.1000
        }
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
