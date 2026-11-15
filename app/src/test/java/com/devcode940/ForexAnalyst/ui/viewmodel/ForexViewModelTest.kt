package com.devcode940.ForexAnalyst.ui.viewmodel

import com.devcode940.ForexAnalyst.data.model.AlertConditionType
import com.devcode940.ForexAnalyst.data.model.AlertId
import com.devcode940.ForexAnalyst.data.model.AppError
import com.devcode940.ForexAnalyst.data.model.CustomAlert
import com.devcode940.ForexAnalyst.data.model.PairSymbol
import com.devcode940.ForexAnalyst.data.model.Result
import com.devcode940.ForexAnalyst.data.model.SignalId
import com.devcode940.ForexAnalyst.data.model.SignalType
import com.devcode940.ForexAnalyst.data.model.Timeframe
import com.devcode940.ForexAnalyst.data.model.TradeSignal
import com.devcode940.ForexAnalyst.data.repository.FakeMarketDataRepository
import com.devcode940.ForexAnalyst.data.repository.PriceGenerator
import com.devcode940.ForexAnalyst.data.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.random.Random

/**
 * Unit tests for [ForexViewModel] state transitions.
 *
 * WHY: These are the tests the old code made impossible — the ViewModel used to
 * construct `ForexRepository(ForexDatabase.getInstance(application))` itself,
 * so exercising it required a real Application and a real SQLite file. With
 * constructor injection and [FakeMarketDataRepository], the same coverage runs
 * on the JVM in milliseconds.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ForexViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var repository: FakeMarketDataRepository
    private lateinit var settings: MutableStateFlow<AppSettings>

    private val clock: Clock = Clock.fixed(Instant.parse("2024-06-01T12:00:00Z"), ZoneOffset.UTC)

    @Before
    fun setUp() {
        // WHY: viewModelScope uses Dispatchers.Main, which does not exist in a
        // plain JVM test — installing a test dispatcher replaces it.
        Dispatchers.setMain(testDispatcher)
        repository = FakeMarketDataRepository()
        settings = MutableStateFlow(AppSettings())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(): ForexViewModel = ForexViewModel(repository, settings)

    private fun seededCandles(count: Int = 300) =
        PriceGenerator.generateCandles(count = count, random = Random(42), clock = clock)

    @Test
    fun `loads candles for the default pair and timeframe on init`() = runTest {
        repository.candleResult = Result.Success(seededCandles())

        val viewModel = viewModel()

        assertEquals(300, viewModel.candles.value.size)
        // WHY (regression): the repository used to hardcode "H1" and the
        // engine used to hardcode EURUSD — assert both are the UI's choice.
        assertEquals(listOf(PairSymbol("EURUSD")), repository.fetchedPairs)
        assertEquals(listOf(Timeframe("H1")), repository.fetchedTimeframes)
        assertFalse(viewModel.isLoading.value)
        assertNull(viewModel.errorMessage.value)
    }

    @Test
    fun `computes indicators aligned with the candle list`() = runTest {
        val candles = seededCandles(300)
        repository.candleResult = Result.Success(candles)

        val viewModel = viewModel()
        val indicators = viewModel.indicators.value

        // WHY: index alignment is the contract the chart canvas relies on —
        // values[i] is drawn at candles[i].
        assertEquals(candles.size, indicators.ema20?.size)
        assertEquals(candles.size, indicators.ema50?.size)
        assertEquals(candles.size, indicators.ema200?.size)
        assertEquals(candles.size, indicators.rsi?.size)
        assertTrue((indicators.macd?.histogram?.size ?: -1) > 0)
    }

    @Test
    fun `uses the persisted indicator periods instead of hardcoded ones`() = runTest {
        settings.value = AppSettings(emaFast = 5, emaMedium = 10, emaSlow = 30, rsiPeriod = 7)
        repository.candleResult = Result.Success(seededCandles(120))

        val viewModel = viewModel()
        val indicators = viewModel.indicators.value

        // WHY (regression): with the old hardcoded EMA(200) a 120-candle
        // series produced an all-null overlay; EMA(30) must have real values.
        val emaSlow = indicators.ema200
        assertEquals(120, emaSlow?.size)
        assertTrue(emaSlow?.take(29)?.all { it == null } == true)
        assertTrue(emaSlow?.get(29) != null)
        val rsi = indicators.rsi
        assertTrue(rsi?.take(7)?.all { it == null } == true)
        assertTrue(rsi?.get(7) != null)
    }

    @Test
    fun `surfaces a safe actionable message when loading fails`() = runTest {
        repository.candleResult = Result.Failure(AppError.Network)

        val viewModel = viewModel()

        assertEquals(AppError.Network.userMessage, viewModel.errorMessage.value)
        assertTrue(viewModel.candles.value.isEmpty())
        // WHY (regression): the spinner used to stay on forever if the load
        // threw before the finally block was added.
        assertFalse(viewModel.isLoading.value)
    }

    @Test
    fun `never exposes internal exception text to the ui`() = runTest {
        repository.candleResult = Result.Failure(AppError.Unknown.of(RuntimeException("SQLITE_CORRUPT at /data/db")))

        val viewModel = viewModel()
        val message = viewModel.errorMessage.value ?: ""

        assertFalse(message.contains("SQLITE_CORRUPT"))
        assertFalse(message.contains("/data/db"))
        assertEquals(AppError.Unknown.userMessage, message)
    }

    @Test
    fun `selecting a timeframe reloads with that timeframe`() = runTest {
        repository.candleResult = Result.Success(seededCandles(50))
        val viewModel = viewModel()

        viewModel.selectTimeframe(Timeframe("M15"))

        assertEquals(listOf(Timeframe("H1"), Timeframe("M15")), repository.fetchedTimeframes)
        assertEquals(Timeframe("M15"), viewModel.selectedTimeframe.value)
    }

    @Test
    fun `selecting the same timeframe twice does not refetch`() = runTest {
        repository.candleResult = Result.Success(seededCandles(50))
        val viewModel = viewModel()

        viewModel.selectTimeframe(Timeframe("H1"))
        viewModel.selectTimeframe(Timeframe("H1"))

        // WHY: redundant reloads waste CPU on indicator math and cause a
        // visible chart flicker.
        assertEquals(1, repository.fetchedTimeframes.size)
    }

    @Test
    fun `selecting a pair reloads that pair`() = runTest {
        repository.candleResult = Result.Success(seededCandles(50))
        val viewModel = viewModel()

        viewModel.selectPair(PairSymbol.of("gbpusd"))

        assertEquals(listOf(PairSymbol("EURUSD"), PairSymbol("GBPUSD")), repository.fetchedPairs)
    }

    @Test
    fun `switching pair resubscribes the signal stream to the new pair`() = runTest {
        repository.candleResult = Result.Success(emptyList())
        val viewModel = viewModel()
        val eur = PairSymbol("EURUSD")
        val gbp = PairSymbol("GBPUSD")

        repository.emitSignals(eur, listOf(testSignal(eur)))
        assertEquals(1, viewModel.signals.value.size)

        // WHY: flatMapLatest must cancel the EURUSD subscription, otherwise a
        // late EURUSD emission would overwrite the GBPUSD list.
        viewModel.selectPair(gbp)
        repository.emitSignals(gbp, listOf(testSignal(gbp), testSignal(gbp)))
        assertEquals(2, viewModel.signals.value.size)

        repository.emitSignals(eur, emptyList())
        assertEquals(2, viewModel.signals.value.size)
    }

    @Test
    fun `watchlist emissions reach the ui state`() = runTest {
        val viewModel = viewModel()
        repository.emitWatchlist(listOf(PairSymbol("EURUSD"), PairSymbol("USDJPY")))
        assertEquals(2, viewModel.watchlist.value.size)
    }

    @Test
    fun `watchlist write failure is reported instead of silently logged`() = runTest {
        val viewModel = viewModel()
        repository.watchlistResult = Result.Failure(AppError.Storage)

        viewModel.addToWatchlist(PairSymbol("XAUUSD"))

        assertEquals(AppError.Storage.userMessage, viewModel.errorMessage.value)
        assertEquals(listOf(PairSymbol("XAUUSD")), repository.watchlistAdditions)
    }

    @Test
    fun `successful write clears a previous error`() = runTest {
        val viewModel = viewModel()
        repository.watchlistResult = Result.Failure(AppError.Storage)
        viewModel.addToWatchlist(PairSymbol("XAUUSD"))
        assertEquals(AppError.Storage.userMessage, viewModel.errorMessage.value)

        repository.watchlistResult = Result.Success(Unit)
        viewModel.removeFromWatchlist(PairSymbol("XAUUSD"))

        assertNull(viewModel.errorMessage.value)
    }

    @Test
    fun `alerts are persisted through the repository`() = runTest {
        val viewModel = viewModel()
        val alert = CustomAlert(
            id = AlertId.generate(),
            pairSymbol = PairSymbol("EURUSD"),
            conditionType = AlertConditionType.PRICE_BELOW,
            targetValue = 1.05,
            isTriggered = false,
            isActive = true,
            note = "dip buy",
            createdAt = clock.instant()
        )

        viewModel.saveAlert(alert)
        viewModel.deleteAlert(alert.id)

        assertEquals(listOf(alert), repository.savedAlerts)
        assertEquals(listOf(alert.id), repository.deletedAlertIds)
    }

    @Test
    fun `consume error clears the banner`() = runTest {
        repository.candleResult = Result.Failure(AppError.Network)
        val viewModel = viewModel()

        viewModel.consumeError()

        assertNull(viewModel.errorMessage.value)
    }

    @Test
    fun `empty candle list yields an empty indicator state`() = runTest {
        repository.candleResult = Result.Success(emptyList())

        val viewModel = viewModel()

        assertNull(viewModel.indicators.value.ema20)
        assertTrue(viewModel.candles.value.isEmpty())
    }

    private fun testSignal(pair: PairSymbol) = TradeSignal(
        id = SignalId.generate(),
        pairSymbol = pair,
        signalType = SignalType.BUY,
        entryPrice = 1.08,
        stopLoss = 1.077,
        takeProfit1 = 1.085,
        takeProfit2 = 1.09,
        riskRewardRatio = 1.6,
        confidenceScore = 0.7,
        timeframe = Timeframe("H1"),
        summaryRationale = "test",
        timestamp = clock.instant()
    )
}
