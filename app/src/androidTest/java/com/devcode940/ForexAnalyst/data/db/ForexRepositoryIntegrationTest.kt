package com.devcode940.ForexAnalyst.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.devcode940.ForexAnalyst.data.model.AlertConditionType
import com.devcode940.ForexAnalyst.data.model.AlertId
import com.devcode940.ForexAnalyst.data.model.AppError
import com.devcode940.ForexAnalyst.data.model.CandleBatch
import com.devcode940.ForexAnalyst.data.model.CandleSource
import com.devcode940.ForexAnalyst.data.model.CandleStick
import com.devcode940.ForexAnalyst.data.model.ChartDrawing
import com.devcode940.ForexAnalyst.data.model.ChartPoint
import com.devcode940.ForexAnalyst.data.model.CustomAlert
import com.devcode940.ForexAnalyst.data.model.DrawingId
import com.devcode940.ForexAnalyst.data.model.DrawingPalette
import com.devcode940.ForexAnalyst.data.model.DrawingStyle
import com.devcode940.ForexAnalyst.data.model.MarketDataProvider
import com.devcode940.ForexAnalyst.data.model.PairSymbol
import com.devcode940.ForexAnalyst.data.model.Result
import com.devcode940.ForexAnalyst.data.model.SignalId
import com.devcode940.ForexAnalyst.data.model.SignalType
import com.devcode940.ForexAnalyst.data.model.Timeframe
import com.devcode940.ForexAnalyst.data.model.TradeSignal
import com.devcode940.ForexAnalyst.data.model.TradeSide
import com.devcode940.ForexAnalyst.data.repository.ForexRepository
import com.devcode940.ForexAnalyst.data.repository.MarketDataRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * Integration test for the Room-backed repository.
 *
 * WHY in-memory Room and not Testcontainers: this module is an Android app
 * whose database is SQLite embedded in the process. Testcontainers manages
 * *server* databases over a network (PostgreSQL, MySQL, Mongo) inside Docker,
 * which an instrumented Android test cannot reach and does not need. The
 * equivalent guarantee here is a REAL database engine (SQLite), REAL DAO
 * queries, REAL TypeConverters and REAL unique indexes, on an ephemeral
 * per-test instance — which is exactly what
 * `Room.inMemoryDatabaseBuilder` provides. (See
 * `docs/testing/IntegrationTests.md` for the Testcontainers version of this
 * test, for the server-side variant of the same repository contract.)
 */
@RunWith(AndroidJUnit4::class)
class ForexRepositoryIntegrationTest {

    private lateinit var database: ForexDatabase
    private lateinit var repository: ForexRepository
    private val clock: Clock = Clock.fixed(Instant.parse("2024-06-01T12:00:00Z"), ZoneOffset.UTC)

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            ForexDatabase::class.java
        )
            // WHY: lets assertions read straight after a write without
            // threading a dispatcher through every test.
            .allowMainThreadQueries()
            .build()
        // WHY the clock is named: ForexRepository gained a candleSource parameter between
        // the database and the clock, so a positional clock no longer compiles - and a
        // silent parameter reorder is exactly the break a named argument prevents.
        repository = ForexRepository(database, clock = clock)
    }

    @After
    fun tearDown() {
        // WHY: an in-memory DB lives as long as its handle; closing it
        // guarantees no state leaks between tests.
        database.close()
    }

    @Test
    fun savesSignalsAndReadsThemBackThroughSqlite() = runBlocking {
        val signal = signal(PairSymbol("EURUSD"), Timeframe("H1"))

        val saved = repository.saveSignals(listOf(signal))
        val observed = repository.observeSignals(PairSymbol("EURUSD")).first()

        assertTrue(saved is Result.Success)
        assertEquals(1, observed.size)
        assertEquals(signal.id, observed.first().id)
        assertEquals(signal.entryPrice, observed.first().entryPrice, 1e-12)
        assertEquals(signal.timestamp, observed.first().timestamp)
    }

    @Test
    fun savingTheSameSignalTwiceIsIdempotent() = runBlocking {
        // WHY: this is the retry-safety guarantee. Identity is the content
        // fingerprint + UNIQUE index, so a rescan of the same candle cannot
        // create a second row even though the UUID differs.
        val first = signal(PairSymbol("EURUSD"), Timeframe("H1"))
        val rescan = first.copy(id = SignalId.generate(), summaryRationale = "recomputed")

        repository.saveSignals(listOf(first))
        repository.saveSignals(listOf(rescan))

        assertEquals(1, repository.observeSignals(PairSymbol("EURUSD")).first().size)
    }

    @Test
    fun signalsForDifferentPairsAreIsolated() = runBlocking {
        repository.saveSignals(
            listOf(
                signal(PairSymbol("EURUSD"), Timeframe("H1")),
                signal(PairSymbol("GBPUSD"), Timeframe("H1")),
                signal(PairSymbol("EURUSD"), Timeframe("H4"))
            )
        )

        val eurH1 = repository.observeSignals(PairSymbol("EURUSD")).first()
        val gbp = repository.observeSignals(PairSymbol("GBPUSD")).first()

        assertEquals(2, eurH1.size)
        assertEquals(1, gbp.size)
    }

    @Test
    fun corruptRowIsSkippedInsteadOfCrashingTheStream() = runBlocking {
        // WHY (regression): SignalType.valueOf("MAYBE") used to throw inside
        // the Flow mapper, killing the whole signal list for the pair.
        database.signalDao().insertSignals(
            listOf(
                rawEntity(signalType = "BUY", id = UUID.randomUUID().toString(), fingerprint = "a".repeat(64)),
                rawEntity(signalType = "MAYBE", id = UUID.randomUUID().toString(), fingerprint = "b".repeat(64)),
                rawEntity(signalType = "SELL", id = "not-a-uuid", fingerprint = "c".repeat(64))
            )
        )

        val observed = repository.observeSignals(PairSymbol("EURUSD")).first()

        assertEquals(1, observed.size)
        assertEquals(SignalType.BUY, observed.first().signalType)
    }

    @Test
    fun fetchesCandlesOnceAndServesThemFromCache() = runBlocking {
        val pair = PairSymbol("USDJPY")
        val timeframe = Timeframe("H1")

        val firstCall = repository.fetchHistoricalCandles(pair, timeframe, limit = 100)
        assertTrue(firstCall is Result.Success)
        val firstCandles = firstCall.requireData()
        assertEquals(100, firstCandles.size)

        val storedRows = database.historicalCandleDao()
            .getLatestCandles(pair.value, timeframe.value, 100)
        assertEquals(100, storedRows.size)

        // WHY: the second call is inside the TTL, so it must be served from
        // SQLite (no regeneration, no duplicate rows).
        val secondCall = repository.fetchHistoricalCandles(pair, timeframe, limit = 100)
        val secondCandles = secondCall.requireData()
        assertEquals(firstCandles, secondCandles)
        assertEquals(
            100,
            database.historicalCandleDao().getLatestCandles(pair.value, timeframe.value, 500).size
        )
    }

    @Test
    fun candlesAreStoredPerTimeframeAndNeverCollide() = runBlocking {
        // WHY (regression): the repository used to hardcode "H1", so switching
        // timeframe silently returned H1 data.
        val pair = PairSymbol("EURUSD")
        val h1 = repository.fetchHistoricalCandles(pair, Timeframe("H1"), limit = 50)
        val h4 = repository.fetchHistoricalCandles(pair, Timeframe("H4"), limit = 50)

        val h1Rows = database.historicalCandleDao().getLatestCandles(pair.value, "H1", 100)
        val h4Rows = database.historicalCandleDao().getLatestCandles(pair.value, "H4", 100)

        assertTrue(h1 is Result.Success)
        assertTrue(h4 is Result.Success)
        assertEquals(50, h1Rows.size)
        assertEquals(50, h4Rows.size)
        assertTrue(h1Rows.all { it.timeframe == "H1" })
        assertTrue(h4Rows.all { it.timeframe == "H4" })
    }

    @Test
    fun candlesAreReturnedInAscendingTimeOrder() = runBlocking {
        val candles = repository
            .fetchHistoricalCandles(PairSymbol("EURUSD"), Timeframe("M15"), limit = 200)
            .requireData()

        assertEquals(200, candles.size)
        candles.zipWithNext { previous, next ->
            assertTrue(previous.time.isBefore(next.time))
        }
    }

    @Test
    fun candleLimitIsClampedToASafeRange() = runBlocking {
        // WHY: SQLite treats a negative LIMIT as "no limit" — an unbounded
        // query on a high-traffic path is a memory bomb.
        val clamped = repository.fetchHistoricalCandles(
            PairSymbol("EURUSD"), Timeframe("H1"), limit = MarketDataRepository.MAX_CANDLE_LIMIT + 5_000
        )
        val rows = clamped.requireData()
        assertEquals(MarketDataRepository.MAX_CANDLE_LIMIT, rows.size)
    }

    @Test
    fun watchlistAddIsIdempotentAndRemoveWorks() = runBlocking {
        val pair = PairSymbol("XAUUSD")

        repository.addToWatchlist(pair)
        repository.addToWatchlist(pair)
        assertEquals(1, repository.observeWatchlist().first().size)

        repository.removeFromWatchlist(pair)
        assertEquals(0, repository.observeWatchlist().first().size)
    }

    @Test
    fun watchlistSymbolsRoundTripAsValueTypes() = runBlocking {
        repository.addToWatchlist(PairSymbol("EURUSD"))
        repository.addToWatchlist(PairSymbol("USDJPY"))

        val pairs = repository.observeWatchlist().first()
        assertEquals(listOf(PairSymbol("USDJPY"), PairSymbol("EURUSD")).toSet(), pairs.toSet())
    }

    @Test
    fun alertsAreSavedReplacedAndDeleted() = runBlocking {
        val alert = alert(PairSymbol("EURUSD"), targetValue = 1.09)

        repository.saveAlert(alert)
        assertEquals(1, repository.observeAlerts(PairSymbol("EURUSD")).first().size)

        // WHY: REPLACE on the same UUID makes "edit alert" idempotent.
        repository.saveAlert(alert.copy(targetValue = 1.12, note = "moved higher"))
        val afterEdit = repository.observeAlerts(PairSymbol("EURUSD")).first()
        assertEquals(1, afterEdit.size)
        assertEquals(1.12, afterEdit.first().targetValue, 1e-12)

        repository.deleteAlert(alert.id)
        assertEquals(0, repository.observeAlerts(PairSymbol("EURUSD")).first().size)
    }

    @Test
    fun activeAndPendingAlertQueriesHaveDifferentSemantics() = runBlocking {
        repository.saveAlert(alert(PairSymbol("EURUSD"), isActive = true, isTriggered = false))
        repository.saveAlert(alert(PairSymbol("EURUSD"), isActive = false, isTriggered = false))
        repository.saveAlert(alert(PairSymbol("EURUSD"), isActive = true, isTriggered = true))

        // WHY: observeAlerts is the user-facing list — an alert that already
        // fired must stay visible so the user knows it triggered. Only
        // disabled alerts are hidden.
        assertEquals(2, repository.observeAlerts(PairSymbol("EURUSD")).first().size)

        // WHY: getActiveAlerts is what an alert *engine* would poll — only
        // alerts that are enabled AND still pending.
        assertEquals(1, repository.getActiveAlerts().requireData().size)
    }

    @Test
    fun oldCandlesArePrunedToBoundTableGrowth() = runBlocking {
        val pair = PairSymbol("EURUSD")
        val timeframe = Timeframe("H1")
        val ancient = clock.instant().minusSeconds(60L * 60 * 24 * 400) // 400 days old

        database.historicalCandleDao().insertCandles(
            listOf(
                HistoricalCandleEntity(pair.value, timeframe.value, ancient, 1.1, 1.2, 1.0, 1.15, 100),
                HistoricalCandleEntity(pair.value, timeframe.value, ancient.plusSeconds(60), 1.1, 1.2, 1.0, 1.15, 100)
            )
        )

        // WHY: a cache miss triggers generation AND pruning in the same
        // critical section, so the table cannot grow without bound.
        repository.fetchHistoricalCandles(pair, timeframe, limit = 10)

        val remaining = database.historicalCandleDao().getLatestCandles(pair.value, timeframe.value, 1_000)
        assertTrue(remaining.none { it.timestamp == ancient })
        assertTrue(remaining.none { it.timestamp == ancient.plusSeconds(60) })
    }

    @Test
    fun instantTypeConverterRoundTripsThroughSqlite() = runBlocking {
        // WHY: Instant is stored as epoch millis by Converters; this pins the
        // round trip so a converter change cannot silently shift timestamps.
        val exact = Instant.parse("2024-02-29T23:59:59.123Z")
        val alert = alert(PairSymbol("EURUSD")).copy(createdAt = exact)

        repository.saveAlert(alert)
        val stored = repository.observeAlerts(PairSymbol("EURUSD")).first().first()

        assertEquals(exact, stored.createdAt)
    }

    // ------------------------------------------------- chart drawings

    @Test
    fun drawingsRoundTripThroughSqliteWithEveryColumnIntact() = runBlocking {
        // WHY every subtype in one test: each owns different columns, and a mapper that
        // drops one (a missing extend flag, a null riskPercent) still returns an object —
        // it just returns the wrong one, forever, on every launch after the first.
        val pair = PairSymbol("EURUSD")
        val timeframe = Timeframe("H1")
        val drawings = listOf(
            ChartDrawing.HorizontalLine(
                id = DrawingId.generate(), pairSymbol = pair, timeframe = timeframe,
                style = DrawingStyle.DASHED, colorArgb = DrawingPalette.DEFAULT,
                createdAt = clock.instant(), label = "Daily high", price = 1.0925
            ),
            ChartDrawing.VerticalLine(
                id = DrawingId.generate(), pairSymbol = pair, timeframe = timeframe,
                style = DrawingStyle.DOTTED, colorArgb = DrawingPalette.SHORT,
                createdAt = clock.instant().plusMillis(1), label = "NFP",
                time = Instant.parse("2024-06-01T09:00:00Z")
            ),
            ChartDrawing.TrendLine(
                id = DrawingId.generate(), pairSymbol = pair, timeframe = timeframe,
                style = DrawingStyle.SOLID, colorArgb = DrawingPalette.LONG,
                createdAt = clock.instant().plusMillis(2), label = "",
                start = ChartPoint(Instant.parse("2024-06-01T09:00:00Z"), 1.0800),
                end = ChartPoint(Instant.parse("2024-06-01T15:00:00Z"), 1.0950),
                extendLeft = true, extendRight = true
            ),
            ChartDrawing.FibRetracement(
                id = DrawingId.generate(), pairSymbol = pair, timeframe = timeframe,
                style = DrawingStyle.SOLID, colorArgb = DrawingPalette.DEFAULT,
                createdAt = clock.instant().plusMillis(3), label = "Swing",
                start = ChartPoint(Instant.parse("2024-06-01T09:00:00Z"), 1.0800),
                end = ChartPoint(Instant.parse("2024-06-01T15:00:00Z"), 1.0950),
                levels = listOf(0.0, 0.236, 0.5, 1.0)
            ),
            ChartDrawing.FibExtension(
                id = DrawingId.generate(), pairSymbol = pair, timeframe = timeframe,
                style = DrawingStyle.SOLID, colorArgb = DrawingPalette.SWATCHES[4].argb,
                createdAt = clock.instant().plusMillis(4), label = "",
                start = ChartPoint(Instant.parse("2024-06-01T09:00:00Z"), 1.0800),
                end = ChartPoint(Instant.parse("2024-06-01T15:00:00Z"), 1.0950),
                levels = listOf(0.0, 1.0, 1.618)
            ),
            ChartDrawing.Rectangle(
                id = DrawingId.generate(), pairSymbol = pair, timeframe = timeframe,
                style = DrawingStyle.SOLID, colorArgb = DrawingPalette.DEFAULT,
                createdAt = clock.instant().plusMillis(5), label = "Consolidation",
                start = ChartPoint(Instant.parse("2024-06-01T09:00:00Z"), 1.0800),
                end = ChartPoint(Instant.parse("2024-06-01T15:00:00Z"), 1.0950)
            ),
            ChartDrawing.Measurement(
                id = DrawingId.generate(), pairSymbol = pair, timeframe = timeframe,
                style = DrawingStyle.DASHED, colorArgb = DrawingPalette.DEFAULT,
                createdAt = clock.instant().plusMillis(6), label = "",
                start = ChartPoint(Instant.parse("2024-06-01T09:00:00Z"), 1.0800),
                end = ChartPoint(Instant.parse("2024-06-01T15:00:00Z"), 1.0950)
            ),
            ChartDrawing.Position(
                id = DrawingId.generate(), pairSymbol = pair, timeframe = timeframe,
                style = DrawingStyle.SOLID, colorArgb = DrawingPalette.LONG,
                createdAt = clock.instant().plusMillis(7), label = "London break",
                side = TradeSide.LONG, entryPrice = 1.0850, stopPrice = 1.0810,
                targetPrice = 1.0930, riskPercent = 1.5
            )
        )

        repository.saveDrawings(drawings).requireData()
        val stored = repository.observeDrawings(pair, timeframe).first()

        assertEquals(drawings.size, stored.size)
        // WHY set equality on ids plus element equality: the DAO orders by
        // (createdAt, rowid), which this fixture satisfies, so the lists must match
        // element-for-element — a reordering here would change paint order on screen.
        assertEquals(drawings.map { it.id }.toSet(), stored.map { it.id }.toSet())
        assertEquals(drawings, stored)
        assertEquals(drawings.size, database.chartDrawingDao().countDrawings())
    }

    @Test
    fun drawingsAreScopedByPairAndTimeframe() = runBlocking {
        // WHY both dimensions: a level drawn on EURUSD H1 describes those bars and no
        // others. Leaking it onto the D1 chart of the same pair, or onto the H1 chart of
        // another pair, puts an object on bars the user never measured.
        val eurH1 = drawing(PairSymbol("EURUSD"), Timeframe("H1"), price = 1.08)
        val eurD1 = drawing(PairSymbol("EURUSD"), Timeframe("D1"), price = 1.09)
        val jpyH1 = drawing(PairSymbol("USDJPY"), Timeframe("H1"), price = 151.5)
        repository.saveDrawings(listOf(eurH1, eurD1, jpyH1)).requireData()

        assertEquals(listOf(eurH1), repository.observeDrawings(PairSymbol("EURUSD"), Timeframe("H1")).first())
        assertEquals(listOf(eurD1), repository.observeDrawings(PairSymbol("EURUSD"), Timeframe("D1")).first())
        assertEquals(listOf(jpyH1), repository.observeDrawings(PairSymbol("USDJPY"), Timeframe("H1")).first())
        assertTrue(repository.observeDrawings(PairSymbol("GBPUSD"), Timeframe("H1")).first().isEmpty())
        assertEquals(3, database.chartDrawingDao().countDrawings())
    }

    @Test
    fun replacingAChartsDrawingsIsAtomicAndLeavesOtherChartsAlone() = runBlocking {
        // WHY this is the undo path: restoring a snapshot usually *removes* rows, which
        // no single-row insert or delete can express. It must be one transaction, or a
        // crash between the delete and the insert leaves the user with an empty chart.
        val pair = PairSymbol("EURUSD")
        val timeframe = Timeframe("H1")
        val kept = drawing(pair, timeframe, price = 1.08)
        val dropped = drawing(pair, timeframe, price = 1.09)
        val other = drawing(PairSymbol("GBPUSD"), timeframe, price = 1.27)
        repository.saveDrawings(listOf(kept, dropped, other)).requireData()

        repository.replaceDrawingsForChart(pair, timeframe, listOf(kept)).requireData()

        assertEquals(listOf(kept), repository.observeDrawings(pair, timeframe).first())
        // The other chart is not part of this transaction and must be untouched.
        assertEquals(listOf(other), repository.observeDrawings(PairSymbol("GBPUSD"), timeframe).first())
        assertEquals(2, database.chartDrawingDao().countDrawings())

        // Undoing back to "nothing on this chart" must actually delete the rows.
        repository.replaceDrawingsForChart(pair, timeframe, emptyList()).requireData()
        assertTrue(repository.observeDrawings(pair, timeframe).first().isEmpty())
        assertEquals(listOf(other), repository.observeDrawings(PairSymbol("GBPUSD"), timeframe).first())
        assertEquals(1, database.chartDrawingDao().countDrawings())
    }

    @Test
    fun savingTheSameDrawingTwiceReplacesRatherThanDuplicates() = runBlocking {
        val pair = PairSymbol("EURUSD")
        val timeframe = Timeframe("H1")
        val original = drawing(pair, timeframe, price = 1.08, label = "before")

        repository.saveDrawing(original).requireData()
        val edited = original.copy(label = "after", price = 1.085)
        repository.saveDrawing(edited).requireData()

        // WHY: the primary key is the drawing id and the insert strategy is REPLACE, so
        // an edit is one row, not two objects stacked on the same price.
        assertEquals(listOf(edited), repository.observeDrawings(pair, timeframe).first())
        assertEquals(1, database.chartDrawingDao().countDrawings())
    }

    @Test
    fun deletingOneDrawingLeavesTheRestOfTheChartIntact() = runBlocking {
        val pair = PairSymbol("EURUSD")
        val timeframe = Timeframe("H1")
        val first = drawing(pair, timeframe, price = 1.08)
        val second = drawing(pair, timeframe, price = 1.09)
        repository.saveDrawings(listOf(first, second)).requireData()

        repository.deleteDrawing(first.id).requireData()

        assertEquals(listOf(second), repository.observeDrawings(pair, timeframe).first())

        repository.deleteDrawingsForChart(pair, timeframe).requireData()
        assertTrue(repository.observeDrawings(pair, timeframe).first().isEmpty())
        assertEquals(0, database.chartDrawingDao().countDrawings())
    }

    @Test
    fun corruptDrawingRowIsSkippedInsteadOfCrashingTheStream() = runBlocking {
        // WHY: a row written by an older build, or by hand, can carry a kind or a column
        // the current code cannot reconstruct. One unreadable object must never take down
        // the whole chart's drawing list — the mapper returns null and the stream filters.
        val pair = PairSymbol("EURUSD")
        val timeframe = Timeframe("H1")
        val good = drawing(pair, timeframe, price = 1.08)
        repository.saveDrawing(good).requireData()

        val dao = database.chartDrawingDao()
        dao.insertDrawing(
            rawDrawingEntity(kind = "NOT_A_TOOL", id = UUID.randomUUID().toString())
        )
        dao.insertDrawing(
            // A long whose stop sits above its entry: valid columns, impossible object.
            rawDrawingEntity(
                kind = "POSITION",
                id = UUID.randomUUID().toString(),
                side = "LONG",
                entryPrice = 1.0,
                stopPrice = 1.5,
                targetPrice = 0.5,
                riskPercent = 1.0
            )
        )
        dao.insertDrawing(
            // A trend line missing its end anchor.
            rawDrawingEntity(kind = "TREND_LINE", id = UUID.randomUUID().toString(), startTime = 1L, startPrice = 1.0)
        )
        assertEquals(4, dao.countDrawings())

        val stored = repository.observeDrawings(pair, timeframe).first()
        assertEquals(listOf(good), stored)
    }

    @Test
    fun drawingObservationIsBoundedAndOldestFirst() = runBlocking {
        // WHY bounded: an unbounded table would re-emit the entire history on every
        // insert, and the renderer would diff hundreds of objects for a change to one.
        val pair = PairSymbol("EURUSD")
        val timeframe = Timeframe("H1")
        val dao = database.chartDrawingDao()
        val total = ChartDrawingDao.MAX_ROWS + 25
        repeat(total) { index ->
            dao.insertDrawing(
                rawDrawingEntity(
                    kind = "HORIZONTAL_LINE",
                    id = UUID.randomUUID().toString(),
                    startPrice = 1.0 + index * 0.0001,
                    createdAt = clock.instant().plusMillis(index.toLong())
                )
            )
        }
        assertEquals(total, dao.countDrawings())

        val stored = repository.observeDrawings(pair, timeframe).first()
        assertEquals(ChartDrawingDao.MAX_ROWS, stored.size)
        // Oldest first, so the renderer paints the newest object on top and the
        // hit-tester (which picks the last match) selects what the user sees on top.
        val times = stored.map { it.createdAt }
        assertEquals(times.sorted(), times)
        assertTrue(times.first() < times.last())
    }

    /**
     * Unwraps a [Result.Success] or fails the test with the classified error.
     *
     * WHY: an explicit `as Result.Success` cast would erase the generic type
     * (star projection -> Any?) and produce an unchecked-cast warning; this
     * keeps the element type AND reports the actual AppError on failure.
     */
    // ------------------------------------------------------------------
    // The candle-source seam (live market data behind the same cache)
    // ------------------------------------------------------------------

    @Test
    fun theInjectedCandleSourceSuppliesTheRowsThatGetCached() = runBlocking {
        // WHY: this is the whole point of the seam. If the repository still called the
        // simulator directly, a live feed could never reach the cache and the chart.
        val source = RecordingCandleSource(MarketDataProvider.FCS_API)
        val live = ForexRepository(database, candleSource = source, clock = clock)
        val pair = PairSymbol("EURUSD")
        val timeframe = Timeframe("H1")

        val candles = live.fetchHistoricalCandles(pair, timeframe, limit = 120).requireData()

        assertEquals("the source must be asked exactly once", 1, source.calls)
        assertEquals(120, source.lastLimit)
        assertEquals(120, candles.size)
        assertEquals(
            "the source's own bars must be what comes back",
            source.produced.single().last(),
            candles.last()
        )
        assertEquals(
            120,
            database.historicalCandleDao().getLatestCandles(pair.value, timeframe.value, 500).size
        )
    }

    @Test
    fun aCacheHitInsideTheTtlDoesNotCallTheSourceAgain() = runBlocking {
        // WHY: every call to a live source spends metered quota. The cache is what keeps
        // a chart switch or a screen rotation free.
        val source = RecordingCandleSource(MarketDataProvider.ALPHA_VANTAGE)
        val live = ForexRepository(database, candleSource = source, clock = clock)
        val pair = PairSymbol("GBPUSD")
        val timeframe = Timeframe("M15")

        val first = live.fetchHistoricalCandles(pair, timeframe, limit = 80).requireData()
        val second = live.fetchHistoricalCandles(pair, timeframe, limit = 80).requireData()

        assertEquals(1, source.calls)
        assertEquals(first, second)
    }

    @Test
    fun invalidatingTheCacheForcesTheNextFetchToReachTheSourceAgain() = runBlocking {
        // WHY: the TTL is an hour, so without an explicit invalidation pasting an API key
        // (or switching provider) would appear to do nothing for up to an hour - which
        // reads as "the key does not work".
        val source = RecordingCandleSource(MarketDataProvider.FCS_API)
        val live = ForexRepository(database, candleSource = source, clock = clock)
        val pair = PairSymbol("EURUSD")
        val timeframe = Timeframe("H1")

        live.fetchHistoricalCandles(pair, timeframe, limit = 50).requireData()
        assertEquals(1, source.calls)

        live.invalidateCandleCache().requireData()
        assertEquals(
            "the cache table must actually be empty",
            0,
            database.historicalCandleDao().getLatestCandles(pair.value, timeframe.value, 500).size
        )

        live.fetchHistoricalCandles(pair, timeframe, limit = 50).requireData()
        assertEquals(2, source.calls)
    }

    @Test
    fun invalidatingTheCacheEmptiesEveryChartNotJustTheCurrentOne() = runBlocking {
        // WHY: switching provider or adding a key is a global decision. An invalidation
        // scoped to the chart on screen would leave every other pair serving rows from
        // the old feed for up to an hour, so the badge would disagree between screens.
        val source = RecordingCandleSource(MarketDataProvider.FCS_API)
        val live = ForexRepository(database, candleSource = source, clock = clock)
        val dao = database.historicalCandleDao()

        live.fetchHistoricalCandles(PairSymbol("EURUSD"), Timeframe("H1"), limit = 40).requireData()
        live.fetchHistoricalCandles(PairSymbol("USDJPY"), Timeframe("M15"), limit = 40).requireData()
        assertEquals(2, source.calls)

        live.invalidateCandleCache().requireData()

        assertEquals(0, dao.getLatestCandles("EURUSD", "H1", 500).size)
        assertEquals(0, dao.getLatestCandles("USDJPY", "M15", 500).size)
    }

    @Test
    fun dataOriginReportsTheFeedAndWhetherTheRowsCameFromCache() = runBlocking {
        // WHY this is asserted through SQLite and not a fake: the value has to survive a
        // cache hit, which is a database read on a different code path from the fetch.
        val source = RecordingCandleSource(MarketDataProvider.FCS_API)
        val live = ForexRepository(database, candleSource = source, clock = clock)
        val pair = PairSymbol("USDJPY")
        val timeframe = Timeframe("H1")

        live.fetchHistoricalCandles(pair, timeframe, limit = 60).requireData()
        val afterFetch = live.dataOrigin.value
        assertEquals(MarketDataProvider.FCS_API, afterFetch.provider)
        assertEquals(false, afterFetch.isCached)
        assertEquals(null, afterFetch.notice)

        live.fetchHistoricalCandles(pair, timeframe, limit = 60).requireData()
        val afterHit = live.dataOrigin.value
        assertEquals(
            "a cache hit must still name the feed that produced the rows",
            MarketDataProvider.FCS_API,
            afterHit.provider
        )
        assertEquals(true, afterHit.isCached)
    }

    @Test
    fun aDegradedBatchPublishesItsReasonAsASafeNotice() = runBlocking {
        // WHY: the chart badge has to explain a fallback, and the explanation must be the
        // provider's *safe* message rather than anything that could carry a key.
        val source = RecordingCandleSource(
            provider = MarketDataProvider.SIMULATED,
            degradedReason = AppError.RateLimited
        )
        val live = ForexRepository(database, candleSource = source, clock = clock)

        live.fetchHistoricalCandles(PairSymbol("EURUSD"), Timeframe("H1"), limit = 30).requireData()

        val origin = live.dataOrigin.value
        assertEquals(MarketDataProvider.SIMULATED, origin.provider)
        assertEquals(AppError.RateLimited.userMessage, origin.notice)
    }

    /**
     * A [CandleSource] double that records how it was used and emits recognisable bars.
     *
     * WHY the bars are recognisable rather than realistic: the assertion that matters is
     * "the rows in SQLite came from the source, not from the simulator", so each bar has
     * to be distinguishable from anything `PriceGenerator` would produce.
     */
    private class RecordingCandleSource(
        private val provider: MarketDataProvider,
        private val degradedReason: AppError? = null
    ) : CandleSource {
        var calls: Int = 0
            private set
        var lastLimit: Int = -1
            private set
        val produced = mutableListOf<List<CandleStick>>()

        override suspend fun load(
            pairSymbol: PairSymbol,
            timeframe: Timeframe,
            limit: Int,
            clock: Clock
        ): CandleBatch {
            calls++
            lastLimit = limit
            val now = clock.instant()
            val candles = (0 until limit).map { index ->
                val price = 2.50000 + index * 0.00001
                CandleStick(
                    time = now.minusMillis((limit - 1 - index) * timeframe.intervalMillis),
                    open = price,
                    high = price + 0.00001,
                    low = price - 0.00001,
                    close = price,
                    volume = 4_242L
                )
            }
            produced += candles
            return CandleBatch(
                candles = candles,
                source = provider,
                fetchedAt = now,
                degradedReason = degradedReason
            )
        }
    }

    private fun <T> Result<T>.requireData(): T = when (this) {
        is Result.Success -> data
        is Result.Failure -> throw AssertionError("Expected Success, got ${error.code}: ${error.userMessage}")
    }

    // ------------------------------------------------------------ fixtures

    private fun signal(pair: PairSymbol, timeframe: Timeframe) = TradeSignal(
        id = SignalId.generate(),
        pairSymbol = pair,
        signalType = SignalType.BUY,
        entryPrice = 1.08421,
        stopLoss = 1.08201,
        takeProfit1 = 1.08801,
        takeProfit2 = 1.09201,
        riskRewardRatio = 1.72,
        confidenceScore = 0.75,
        timeframe = timeframe,
        summaryRationale = "integration fixture",
        timestamp = clock.instant(),
        isBookmarked = false
    )

    private fun alert(pair: PairSymbol, targetValue: Double = 1.09, isActive: Boolean = true, isTriggered: Boolean = false) =
        CustomAlert(
            id = AlertId.generate(),
            pairSymbol = pair,
            conditionType = AlertConditionType.PRICE_ABOVE,
            targetValue = targetValue,
            isTriggered = isTriggered,
            isActive = isActive,
            note = "integration fixture",
            createdAt = clock.instant()
        )

    private fun drawing(
        pair: PairSymbol,
        timeframe: Timeframe,
        price: Double,
        label: String = ""
    ) = ChartDrawing.HorizontalLine(
        id = DrawingId.generate(),
        pairSymbol = pair,
        timeframe = timeframe,
        style = DrawingStyle.SOLID,
        colorArgb = DrawingPalette.DEFAULT,
        createdAt = clock.instant(),
        label = label,
        price = price
    )

    /**
     * Bypasses the mapper so a row the domain would reject can be inserted.
     *
     * WHY: the point of the corrupt-row test is that the *database* holds something the
     * domain cannot represent, which no repository call can produce.
     */
    private fun rawDrawingEntity(
        kind: String,
        id: String,
        pairSymbol: String = "EURUSD",
        timeframe: String = "H1",
        style: String = DrawingStyle.SOLID.name,
        colorArgb: Int = DrawingPalette.DEFAULT,
        createdAt: Instant = clock.instant(),
        label: String = "",
        startTime: Long? = null,
        startPrice: Double? = null,
        endTime: Long? = null,
        endPrice: Double? = null,
        extendLeft: Boolean = false,
        extendRight: Boolean = false,
        levelsCsv: String? = null,
        side: String? = null,
        entryPrice: Double? = null,
        stopPrice: Double? = null,
        targetPrice: Double? = null,
        riskPercent: Double? = null
    ) = ChartDrawingEntity(
        id = id,
        pairSymbol = pairSymbol,
        timeframe = timeframe,
        kind = kind,
        style = style,
        colorArgb = colorArgb,
        createdAt = createdAt,
        label = label,
        startTime = startTime,
        startPrice = startPrice,
        endTime = endTime,
        endPrice = endPrice,
        extendLeft = extendLeft,
        extendRight = extendRight,
        levelsCsv = levelsCsv,
        side = side,
        entryPrice = entryPrice,
        stopPrice = stopPrice,
        targetPrice = targetPrice,
        riskPercent = riskPercent
    )

    /** Bypasses the repository so a deliberately corrupt row can be inserted. */
    private fun rawEntity(signalType: String, id: String, fingerprint: String) = SavedSignalEntity(
        id = id,
        pairSymbol = "EURUSD",
        signalType = signalType,
        entryPrice = 1.0,
        stopLoss = 0.99,
        takeProfit1 = 1.01,
        takeProfit2 = 1.02,
        riskRewardRatio = 1.0,
        confidenceScore = 0.5,
        timeframe = "H1",
        summaryRationale = "corrupt fixture",
        timestamp = clock.instant(),
        isBookmarked = false,
        fingerprint = fingerprint
    )
}
