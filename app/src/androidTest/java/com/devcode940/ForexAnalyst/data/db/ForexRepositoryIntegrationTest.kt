package com.devcode940.ForexAnalyst.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.devcode940.ForexAnalyst.data.model.AlertConditionType
import com.devcode940.ForexAnalyst.data.model.AlertId
import com.devcode940.ForexAnalyst.data.model.CustomAlert
import com.devcode940.ForexAnalyst.data.model.PairSymbol
import com.devcode940.ForexAnalyst.data.model.Result
import com.devcode940.ForexAnalyst.data.model.SignalId
import com.devcode940.ForexAnalyst.data.model.SignalType
import com.devcode940.ForexAnalyst.data.model.Timeframe
import com.devcode940.ForexAnalyst.data.model.TradeSignal
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
        repository = ForexRepository(database, clock)
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

    /**
     * Unwraps a [Result.Success] or fails the test with the classified error.
     *
     * WHY: an explicit `as Result.Success` cast would erase the generic type
     * (star projection -> Any?) and produce an unchecked-cast warning; this
     * keeps the element type AND reports the actual AppError on failure.
     */
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
