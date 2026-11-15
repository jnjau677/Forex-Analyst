package com.devcode940.ForexAnalyst.data.provider

import com.devcode940.ForexAnalyst.MutableClock
import com.devcode940.ForexAnalyst.data.model.AppError
import com.devcode940.ForexAnalyst.data.model.CandleBatch
import com.devcode940.ForexAnalyst.data.model.CandleSource
import com.devcode940.ForexAnalyst.data.model.CandleStick
import com.devcode940.ForexAnalyst.data.model.MarketDataException
import com.devcode940.ForexAnalyst.data.model.MarketDataProvider
import com.devcode940.ForexAnalyst.data.model.MarketDataPreference
import com.devcode940.ForexAnalyst.data.model.PairSymbol
import com.devcode940.ForexAnalyst.data.model.Timeframe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Covers provider selection, failover and the fallback contract.
 *
 * WHY routing gets its own suite: the failure modes here are silent. A feed that is
 * never chosen, a fallback that loses its reason, or a suspension that never expires
 * all produce a working-looking chart with the wrong prices behind it. Nothing throws.
 */
class MarketDataRoutingTest {

    private val clock = MutableClock()
    private val pair = PairSymbol("EURUSD")
    private val timeframe = Timeframe("H1")

    private val oneCandle = listOf(
        CandleStick(
            time = Instant.parse("2024-06-01T11:00:00Z"),
            open = 1.0850,
            high = 1.0860,
            low = 1.0840,
            close = 1.0855
        )
    )

    // ---------- preference resolution ----------

    @Test
    fun `automatic prefers FCS when both keys exist`() {
        assertEquals(
            MarketDataProvider.FCS_API,
            MarketDataPreference.resolve(MarketDataPreference.AUTO, hasFcsKey = true, hasAlphaVantageKey = true)
        )
    }

    @Test
    fun `automatic falls through to Alpha Vantage when FCS has no key`() {
        assertEquals(
            MarketDataProvider.ALPHA_VANTAGE,
            MarketDataPreference.resolve(MarketDataPreference.AUTO, hasFcsKey = false, hasAlphaVantageKey = true)
        )
    }

    @Test
    fun `automatic with no keys resolves to the simulator`() {
        assertEquals(
            MarketDataProvider.SIMULATED,
            MarketDataPreference.resolve(MarketDataPreference.AUTO, hasFcsKey = false, hasAlphaVantageKey = false)
        )
    }

    @Test
    fun `offline always resolves to the simulator even with keys present`() {
        assertEquals(
            MarketDataProvider.SIMULATED,
            MarketDataPreference.resolve(MarketDataPreference.OFFLINE, hasFcsKey = true, hasAlphaVantageKey = true)
        )
        assertTrue(MarketDataPreference.candidates(MarketDataPreference.OFFLINE, true, true).isEmpty())
    }

    @Test
    fun `an explicit choice without its key does not fall through to the other feed`() {
        // WHY: the user picked one feed, possibly because the other is the reason their
        // prices look wrong. Overriding an explicit choice makes the setting meaningless.
        assertEquals(
            MarketDataProvider.SIMULATED,
            MarketDataPreference.resolve(MarketDataPreference.FCS_API, hasFcsKey = false, hasAlphaVantageKey = true)
        )
        assertTrue(
            MarketDataPreference.candidates(MarketDataPreference.FCS_API, hasFcsKey = false, hasAlphaVantageKey = true).isEmpty()
        )
    }

    @Test
    fun `automatic offers both feeds as candidates so one can fail over to the other`() {
        assertEquals(
            listOf(MarketDataProvider.FCS_API, MarketDataProvider.ALPHA_VANTAGE),
            MarketDataPreference.candidates(MarketDataPreference.AUTO, hasFcsKey = true, hasAlphaVantageKey = true)
        )
    }

    @Test
    fun `provider and preference wire spellings are frozen`() {
        // WHY pinned: both are persisted in preferences. Renaming a constant or dropping
        // a @SerializedName silently orphans every stored value, and Gson then reads
        // null into a non-null Kotlin field on the next launch.
        val gson = com.google.gson.Gson()
        assertEquals("\"simulated\"", gson.toJson(MarketDataProvider.SIMULATED))
        assertEquals("\"fcs_api\"", gson.toJson(MarketDataProvider.FCS_API))
        assertEquals("\"alpha_vantage\"", gson.toJson(MarketDataProvider.ALPHA_VANTAGE))

        assertEquals("\"auto\"", gson.toJson(MarketDataPreference.AUTO))
        assertEquals("\"fcs_api\"", gson.toJson(MarketDataPreference.FCS_API))
        assertEquals("\"alpha_vantage\"", gson.toJson(MarketDataPreference.ALPHA_VANTAGE))
        assertEquals("\"offline\"", gson.toJson(MarketDataPreference.OFFLINE))
    }

    @Test
    fun `an unrecognised stored provider name degrades to the simulator instead of crashing`() {
        // WHY: these strings are persisted. A downgrade, or a hand-edited backup, must
        // not crash on launch - Enum.valueOf would.
        assertSame(MarketDataProvider.SIMULATED, MarketDataProvider.fromWire("SOMETHING_NEW"))
        assertSame(MarketDataProvider.SIMULATED, MarketDataProvider.fromWire(null))
        assertSame(MarketDataProvider.SIMULATED, MarketDataProvider.fromWire("  "))
    }

    // ---------- symbol splitting ----------

    @Test
    fun `every catalogued FX, metal and crypto symbol splits into two currency codes`() {
        val checked = mutableListOf<String>()
        com.devcode940.ForexAnalyst.data.model.InstrumentCatalog.ALL.forEach { instrument ->
            val category = instrument.category
            if (category == com.devcode940.ForexAnalyst.data.model.InstrumentCategory.INDICES ||
                category == com.devcode940.ForexAnalyst.data.model.InstrumentCategory.ENERGY
            ) {
                return@forEach
            }
            val split = InstrumentSplit.of(instrument.symbol)
            assertNotNull("${instrument.symbol.value} should be servable", split)
            split!!
            assertTrue(
                "${instrument.symbol.value} split into '$split' does not rebuild the symbol",
                split.base + split.quote == instrument.symbol.value
            )
            checked += instrument.symbol.value
        }
        // WHY assert the sweep actually covered the catalog: a filter that silently
        // matched nothing would make this test pass while testing nothing.
        assertTrue("expected the whole servable catalog, checked ${checked.size}", checked.size >= 55)
        assertTrue("variable-length crypto bases must be covered", checked.containsAll(listOf("DOGEUSD", "MATICUSD")))
    }

    @Test
    fun `crypto bases longer than three characters are not truncated`() {
        // WHY this is its own case: `substring(0, 3)` reads DOGEUSD as DOG/EUSD, which
        // is a plausible-looking request the provider answers with "unknown symbol".
        assertEquals(InstrumentSplit("DOGE", "USD"), InstrumentSplit.of(PairSymbol("DOGEUSD")))
        assertEquals(InstrumentSplit("MATIC", "USD"), InstrumentSplit.of(PairSymbol("MATICUSD")))
        assertEquals(InstrumentSplit("BTC", "USD"), InstrumentSplit.of(PairSymbol("BTCUSD")))
    }

    @Test
    fun `indices and energy are not servable by an FX feed`() {
        listOf("SPX500", "NAS100", "GER40", "JPN225", "AUS200", "UK100", "US30", "NATGAS", "USOIL", "UKOIL")
            .forEach { symbol ->
                assertNull("$symbol has no ISO base/quote split", InstrumentSplit.of(PairSymbol(symbol)))
            }
    }

    @Test
    fun `an unknown symbol is not servable`() {
        assertNull(InstrumentSplit.of(PairSymbol("ZZZZZZ")))
        assertNull(InstrumentSplit.of(PairSymbol("EU")))
    }

    @Test
    fun `the slash form matches the spelling both feeds document`() {
        assertEquals("EUR/USD", InstrumentSplit.of(PairSymbol("EURUSD"))?.slashForm)
    }

    // ---------- failover ----------

    @Test
    fun `the first candidate that can serve the instrument wins`() = runBlockingTest {
        val fcs = FakeClient(MarketDataProvider.FCS_API, result = oneCandle)
        val alpha = FakeClient(MarketDataProvider.ALPHA_VANTAGE, result = oneCandle)

        val batch = source(fcs, alpha, providers = listOf(MarketDataProvider.FCS_API, MarketDataProvider.ALPHA_VANTAGE))
            .load(pair, timeframe, 100, clock)

        assertSame(MarketDataProvider.FCS_API, batch.source)
        assertEquals(1, fcs.calls)
        assertEquals("the winning feed must stop the search", 0, alpha.calls)
        assertNull(batch.degradedReason)
        assertTrue(batch.isLiveAndDirect)
    }

    @Test
    fun `a feed that cannot serve the instrument hands over to the next one`() = runBlockingTest {
        val fcs = FakeClient(MarketDataProvider.FCS_API, result = oneCandle, supported = false)
        val alpha = FakeClient(MarketDataProvider.ALPHA_VANTAGE, result = oneCandle)

        val batch = source(fcs, alpha, providers = listOf(MarketDataProvider.FCS_API, MarketDataProvider.ALPHA_VANTAGE))
            .load(pair, timeframe, 100, clock)

        assertSame(MarketDataProvider.ALPHA_VANTAGE, batch.source)
        assertEquals("an unsupported feed must not spend a request", 0, fcs.calls)
        assertEquals(1, alpha.calls)
        assertNull(batch.degradedReason)
    }

    @Test
    fun `a feed that has exhausted its quota hands over to the next one`() = runBlockingTest {
        val fcs = FakeClient(MarketDataProvider.FCS_API, result = oneCandle)
        val alpha = FakeClient(MarketDataProvider.ALPHA_VANTAGE, result = oneCandle)
        // One request per minute for FCS: the second load in the same minute is denied.
        val budget = RequestBudget(
            limits = mapOf(
                MarketDataProvider.FCS_API to listOf(
                    RequestBudget.Window(Duration.ofMinutes(1), 1, "minute")
                )
            ),
            clock = clock
        )

        val live = LiveCandleSource(
            clients = mapOf(
                MarketDataProvider.FCS_API to fcs,
                MarketDataProvider.ALPHA_VANTAGE to alpha
            ),
            fallback = FakeSimulator(oneCandle),
            budget = budget,
            orderedProviders = { listOf(MarketDataProvider.FCS_API, MarketDataProvider.ALPHA_VANTAGE) }
        )

        assertSame(MarketDataProvider.FCS_API, live.load(pair, timeframe, 100, clock).source)
        val second = live.load(pair, timeframe, 100, clock)
        assertSame(MarketDataProvider.ALPHA_VANTAGE, second.source)
        assertNull("failing over to a working feed is not a degradation", second.degradedReason)
    }

    @Test
    fun `when every feed fails the simulator answers and says why`() = runBlockingTest {
        val fcs = FakeClient(MarketDataProvider.FCS_API, error = AppError.RateLimited)
        val alpha = FakeClient(MarketDataProvider.ALPHA_VANTAGE, error = AppError.Network)

        val batch = source(fcs, alpha, providers = listOf(MarketDataProvider.FCS_API, MarketDataProvider.ALPHA_VANTAGE))
            .load(pair, timeframe, 100, clock)

        assertSame(MarketDataProvider.SIMULATED, batch.source)
        assertNotNull("a fallback without a reason is indistinguishable from offline mode", batch.degradedReason)
        // WHY the *last* failure: it is the one that decided the outcome, and the first
        // is usually "this feed does not cover that instrument", which is not the news.
        assertSame(AppError.Network, batch.degradedReason)
        assertEquals(oneCandle, batch.candles)
    }

    @Test
    fun `with no candidate feeds the simulator answers without claiming a failure`() = runBlockingTest {
        val simulator = FakeSimulator(oneCandle)
        val live = LiveCandleSource(
            clients = emptyMap(),
            fallback = simulator,
            budget = RequestBudget(emptyMap(), clock),
            orderedProviders = { emptyList() }
        )

        val batch = live.load(pair, timeframe, 100, clock)

        assertSame(MarketDataProvider.SIMULATED, batch.source)
        assertNull(
            "offline mode is a choice, not a degradation - a warning here would be noise",
            batch.degradedReason
        )
        assertEquals(1, simulator.calls)
    }

    @Test
    fun `a rejected credential suspends that feed instead of retrying every chart switch`() = runBlockingTest {
        val fcs = FakeClient(MarketDataProvider.FCS_API, error = AppError.Unauthorized)

        val live = LiveCandleSource(
            clients = mapOf(MarketDataProvider.FCS_API to fcs),
            fallback = FakeSimulator(oneCandle),
            budget = RequestBudget(emptyMap(), clock),
            orderedProviders = { listOf(MarketDataProvider.FCS_API) }
        )

        live.load(pair, timeframe, 100, clock)
        assertEquals(1, fcs.calls)

        // WHY the second call must not reach the network: a wrong key does not become
        // right by being sent again, and repeated failed credentials can lock an account.
        val second = live.load(pair, timeframe, 100, clock)
        assertEquals("a suspended feed must not be called again", 1, fcs.calls)
        assertSame(MarketDataProvider.SIMULATED, second.source)
        assertSame(AppError.Unauthorized, second.degradedReason)

        // After the suspension the feed is tried once more.
        clock.advanceMillis(LiveCandleSource.AUTH_FAILURE_SUSPENSION.toMillis() + 1)
        live.load(pair, timeframe, 100, clock)
        assertEquals(2, fcs.calls)
    }

    @Test
    fun `saving a new key clears the suspension`() = runBlockingTest {
        val fcs = FakeClient(MarketDataProvider.FCS_API, error = AppError.Unauthorized)
        val live = LiveCandleSource(
            clients = mapOf(MarketDataProvider.FCS_API to fcs),
            fallback = FakeSimulator(oneCandle),
            budget = RequestBudget(emptyMap(), clock),
            orderedProviders = { listOf(MarketDataProvider.FCS_API) }
        )

        live.load(pair, timeframe, 100, clock)
        assertEquals(1, fcs.calls)

        fcs.error = null
        fcs.result = oneCandle
        live.resetSuspensions()

        val batch = live.load(pair, timeframe, 100, clock)
        assertEquals(2, fcs.calls)
        assertSame(MarketDataProvider.FCS_API, batch.source)
        assertNull(batch.degradedReason)
    }

    @Test
    fun `a network failure refunds the quota charge`() = runBlockingTest {
        val fcs = FakeClient(MarketDataProvider.FCS_API, error = AppError.Network)
        val budget = RequestBudget(
            limits = mapOf(
                MarketDataProvider.FCS_API to listOf(
                    RequestBudget.Window(Duration.ofMinutes(1), 1, "minute")
                )
            ),
            clock = clock
        )
        val live = LiveCandleSource(
            clients = mapOf(MarketDataProvider.FCS_API to fcs),
            fallback = FakeSimulator(oneCandle),
            budget = budget,
            orderedProviders = { listOf(MarketDataProvider.FCS_API) }
        )

        live.load(pair, timeframe, 100, clock)
        assertEquals(
            "a request that never reached the provider consumed nothing",
            0,
            budget.usedIn(MarketDataProvider.FCS_API, RequestBudget.Window(Duration.ofMinutes(1), 1, "minute"))
        )
        // The refund is what lets the retry below through inside the same minute.
        live.load(pair, timeframe, 100, clock)
        assertEquals(2, fcs.calls)
    }

    @Test
    fun `a provider refusal is not refunded`() = runBlockingTest {
        // WHY: refunding refusals would be right for FCS and wrong for Alpha Vantage,
        // which counts every call. Getting it wrong in the optimistic direction is how a
        // key gets suspended, so the guard stays conservative.
        val alpha = FakeClient(MarketDataProvider.ALPHA_VANTAGE, error = AppError.RateLimited)
        val window = RequestBudget.Window(Duration.ofMinutes(1), 5, "minute")
        val budget = RequestBudget(
            limits = mapOf(MarketDataProvider.ALPHA_VANTAGE to listOf(window)),
            clock = clock
        )
        val live = LiveCandleSource(
            clients = mapOf(MarketDataProvider.ALPHA_VANTAGE to alpha),
            fallback = FakeSimulator(oneCandle),
            budget = budget,
            orderedProviders = { listOf(MarketDataProvider.ALPHA_VANTAGE) }
        )

        live.load(pair, timeframe, 100, clock)

        assertEquals(1, budget.usedIn(MarketDataProvider.ALPHA_VANTAGE, window))
    }

    @Test
    fun `an unexpected exception is classified rather than escaping`() = runBlockingTest {
        val fcs = FakeClient(MarketDataProvider.FCS_API, rawFailure = IllegalStateException("boom apikey=SECRET"))
        val live = LiveCandleSource(
            clients = mapOf(MarketDataProvider.FCS_API to fcs),
            fallback = FakeSimulator(oneCandle),
            budget = RequestBudget(emptyMap(), clock),
            orderedProviders = { listOf(MarketDataProvider.FCS_API) }
        )

        val batch = live.load(pair, timeframe, 100, clock)

        assertSame(MarketDataProvider.SIMULATED, batch.source)
        val error = batch.degradedReason
        assertTrue("expected ProviderRejected, got $error", error is AppError.ProviderRejected)
        assertTrue(
            "the reason must not read 'Something went wrong': ${(error as AppError.ProviderRejected).reason}",
            error.reason.contains("boom")
        )
    }

    @Test
    fun `the feed order is read per call so a settings change takes effect at once`() = runBlockingTest {
        val fcs = FakeClient(MarketDataProvider.FCS_API, result = oneCandle)
        val alpha = FakeClient(MarketDataProvider.ALPHA_VANTAGE, result = oneCandle)
        var order = listOf(MarketDataProvider.FCS_API)

        val live = LiveCandleSource(
            clients = mapOf(
                MarketDataProvider.FCS_API to fcs,
                MarketDataProvider.ALPHA_VANTAGE to alpha
            ),
            fallback = FakeSimulator(oneCandle),
            budget = RequestBudget(emptyMap(), clock),
            orderedProviders = { order }
        )

        assertSame(MarketDataProvider.FCS_API, live.load(pair, timeframe, 100, clock).source)

        order = listOf(MarketDataProvider.ALPHA_VANTAGE)
        assertSame(MarketDataProvider.ALPHA_VANTAGE, live.load(pair, timeframe, 100, clock).source)
    }

    @Test
    fun `a configured feed with no client registered is skipped, not fatal`() = runBlockingTest {
        val alpha = FakeClient(MarketDataProvider.ALPHA_VANTAGE, result = oneCandle)
        val live = LiveCandleSource(
            clients = mapOf(MarketDataProvider.ALPHA_VANTAGE to alpha),
            fallback = FakeSimulator(oneCandle),
            budget = RequestBudget(emptyMap(), clock),
            orderedProviders = { listOf(MarketDataProvider.FCS_API, MarketDataProvider.ALPHA_VANTAGE) }
        )

        assertSame(MarketDataProvider.ALPHA_VANTAGE, live.load(pair, timeframe, 100, clock).source)
    }

    // ---------- doubles ----------

    private fun source(
        vararg clients: MarketDataClient,
        providers: List<MarketDataProvider>
    ): LiveCandleSource = LiveCandleSource(
        clients = clients.associateBy { it.provider },
        fallback = FakeSimulator(oneCandle),
        budget = RequestBudget(emptyMap(), clock),
        orderedProviders = { providers }
    )

    /**
     * Runs a suspend block on the calling thread.
     *
     * WHY not `kotlinx.coroutines.test.runTest`: nothing here suspends on a dispatcher,
     * and `runTest` skips delays by advancing a virtual clock - which would make the
     * suspension-window test pass for the wrong reason.
     */
    private fun runBlockingTest(block: suspend () -> Unit) = kotlinx.coroutines.runBlocking { block() }

    private class FakeClient(
        override val provider: MarketDataProvider,
        var result: List<CandleStick> = emptyList(),
        var error: AppError? = null,
        var rawFailure: Throwable? = null,
        var supported: Boolean = true
    ) : MarketDataClient {
        var calls: Int = 0
            private set

        override fun supports(pair: PairSymbol, timeframe: Timeframe): Boolean = supported

        override suspend fun fetchCandles(
            pair: PairSymbol,
            timeframe: Timeframe,
            limit: Int,
            clock: Clock
        ): List<CandleStick> {
            calls++
            rawFailure?.let { throw it }
            error?.let { throw MarketDataException(it) }
            return result
        }
    }

    private class FakeSimulator(private val candles: List<CandleStick>) : CandleSource {
        var calls: Int = 0
            private set

        override suspend fun load(
            pairSymbol: PairSymbol,
            timeframe: Timeframe,
            limit: Int,
            clock: Clock
        ): CandleBatch {
            calls++
            return CandleBatch(
                candles = candles,
                source = MarketDataProvider.SIMULATED,
                fetchedAt = clock.instant()
            )
        }
    }
}
