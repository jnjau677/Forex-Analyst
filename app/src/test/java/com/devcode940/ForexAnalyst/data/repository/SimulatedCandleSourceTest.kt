package com.devcode940.ForexAnalyst.data.repository

import com.devcode940.ForexAnalyst.MutableClock
import com.devcode940.ForexAnalyst.data.model.InstrumentCatalog
import com.devcode940.ForexAnalyst.data.model.MarketDataProvider
import com.devcode940.ForexAnalyst.data.model.PairSymbol
import com.devcode940.ForexAnalyst.data.model.Timeframe
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

/**
 * Covers [SimulatedCandleSource], the offline [com.devcode940.ForexAnalyst.data.model.CandleSource].
 *
 * WHY the simulator gets its own suite now that it is one implementation among several:
 * it is the fallback every live feed degrades to, and it is what the app shows with no
 * key at all. If it regresses, the failure appears as "live data looks wrong" and the
 * investigation starts in the network layer, which is the wrong place entirely.
 */
class SimulatedCandleSourceTest {

    private val clock = MutableClock()
    private val source = SimulatedCandleSource()

    private fun load(
        pair: String = "EURUSD",
        timeframe: String = "H1",
        limit: Int = 100
    ) = runBlocking {
        source.load(PairSymbol(pair), Timeframe(timeframe), limit, clock)
    }

    @Test
    fun `the batch is labelled simulated and carries no degradation`() {
        // WHY the absent reason matters as much as the label: `LiveCandleSource` attaches
        // a reason when a feed fails and falls back here. A batch that arrives with no
        // reason means offline-by-choice, and the chart badge must not warn about it.
        val batch = load()

        assertSame(MarketDataProvider.SIMULATED, batch.source)
        assertNull(batch.degradedReason)
        assertFalse("a simulation must never claim to be live", batch.isLiveAndDirect)
        assertEquals(clock.instant(), batch.fetchedAt)
    }

    @Test
    fun `the requested number of bars is produced in ascending order`() {
        val batch = load(limit = 250)

        assertEquals(250, batch.candles.size)
        val times = batch.candles.map { it.time }
        assertEquals(times.sorted(), times)
    }

    @Test
    fun `the newest bar is stamped now`() {
        // WHY: the repository's cache-freshness check compares the newest row against the
        // TTL. A series ending in the past would look stale the moment it was written and
        // be regenerated on every single fetch.
        val batch = load(limit = 50)

        assertEquals(clock.instant(), batch.candles.last().time)
    }

    @Test
    fun `bars are spaced by the timeframe's own interval`() {
        // WHY (regression): the generator once used a fixed one-hour interval for every
        // timeframe, so an M5 chart was populated with hourly bars while labelled
        // "5 minutes" - the axis, the session shading and every signal timestamp
        // disagreed with the chart's own title.
        listOf("M1", "M5", "M15", "H1", "H4", "D1", "W1").forEach { value ->
            val timeframe = Timeframe(value)
            val batch = load(timeframe = value, limit = 10)
            val gaps = batch.candles.zipWithNext { a, b -> Duration.between(a.time, b.time).toMillis() }

            assertTrue("$value produced no gaps", gaps.isNotEmpty())
            assertEquals("$value bars are not one $value apart", gaps.distinct(), listOf(timeframe.intervalMillis))
        }
    }

    @Test
    fun `each instrument simulates around its own reference price`() {
        // WHY (regression): a single hardcoded ladder knew six of the 71 instruments, so
        // everything else simulated as if it were EURUSD at 1.10 - a BTCUSD chart that
        // moved 0.1% an hour and quoted five decimals.
        val cases = listOf("EURUSD" to 1.0850, "USDJPY" to 151.50, "XAUUSD" to 2350.0, "BTCUSD" to 67500.0)

        cases.forEach { (symbol, expected) ->
            val batch = load(pair = symbol, limit = 1)
            assertEquals(
                "$symbol should start from its catalogue reference price",
                expected,
                batch.candles.single().open,
                expected * 1e-9
            )
        }
    }

    @Test
    fun `the same chart simulates identically across calls`() {
        // WHY: the simulator re-runs whenever the cache expires. Without a stable seed the
        // same pair and timeframe came back with a different price history, so the chart
        // "jumped" overnight and every drawing the user had placed against the old bars
        // pointed at unrelated prices.
        val first = load(pair = "GBPUSD", timeframe = "H4", limit = 80)
        val second = load(pair = "GBPUSD", timeframe = "H4", limit = 80)

        assertEquals(first.candles, second.candles)
    }

    @Test
    fun `a different chart simulates differently`() {
        val a = load(pair = "EURUSD", limit = 40).candles
        val b = load(pair = "EURGBP", limit = 40).candles

        assertTrue(
            "two charts must not share one simulation",
            a.map { it.close } != b.map { it.close }
        )
    }

    @Test
    fun `the seed matches the one the generator documents`() {
        // WHY pinned: PriceGenerator.seedFor is shared by the offline source and the live
        // source's fallback. If the two ever diverged, one chart would show two different
        // invented histories depending on whether the network was up.
        val pair = PairSymbol("XAUUSD")
        val timeframe = Timeframe("D1")

        assertEquals(
            pair.value.hashCode().toLong() * 31L + timeframe.value.hashCode().toLong(),
            expectedSeed(pair, timeframe)
        )
        // Drawing from the same seed twice must give the same first value.
        val a = PriceGenerator.seedFor(pair, timeframe).nextDouble()
        val b = PriceGenerator.seedFor(pair, timeframe).nextDouble()
        assertEquals(a, b, 0.0)
    }

    @Test
    fun `every catalogue instrument produces valid candles`() {
        // WHY sweep all 71: CandleStick's invariants (finite prices, high >= low,
        // high >= max(open, close)) throw rather than degrade, so one instrument with a
        // bad reference price or volatility would crash the chart for that symbol only -
        // the kind of bug that survives review and reaches a user.
        InstrumentCatalog.ALL.forEach { instrument ->
            val batch = load(pair = instrument.symbol.value, limit = 30)
            assertEquals(instrument.symbol.value, batch.candles.size, 30)
        }
    }

    private fun expectedSeed(pair: PairSymbol, timeframe: Timeframe): Long =
        pair.value.hashCode().toLong() * 31L + timeframe.value.hashCode().toLong()
}
