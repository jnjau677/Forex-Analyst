package com.devcode940.ForexAnalyst.data.repository

import com.devcode940.ForexAnalyst.data.model.CandleStick
import com.devcode940.ForexAnalyst.data.model.PairSymbol
import com.devcode940.ForexAnalyst.data.model.Timeframe
import java.time.Clock
import java.time.Instant
import kotlin.random.Random

/**
 * Mock candle generator (simulated data source).
 *
 * WHY: [random] and [clock] are injectable so unit tests can seed both and
 * assert exact output — System.currentTimeMillis() and the default Random
 * are untestable.
 */
object PriceGenerator {

    fun generateCandles(
        count: Int,
        basePrice: Double = 1.1000,
        volatility: Double = 0.001,
        intervalMs: Long = 3_600_000L,
        random: Random = Random.Default,
        clock: Clock = Clock.systemUTC()
    ): List<CandleStick> {
        // WHY: Fail fast on nonsense input instead of generating NaN candles
        // that poison indicator math downstream.
        require(count in 1..10_000) { "count must be 1..10000 (got $count)" }
        require(basePrice.isFinite() && basePrice > 0.0) { "basePrice must be finite and > 0 (got $basePrice)" }
        require(volatility.isFinite() && volatility >= 0.0) { "volatility must be finite and >= 0 (got $volatility)" }
        require(intervalMs > 0L) { "intervalMs must be > 0 (got $intervalMs)" }

        val now = clock.millis()
        val candles = ArrayList<CandleStick>(count)
        var price = basePrice
        for (i in 0 until count) {
            val open = price
            val change = (random.nextDouble() - 0.5) * 2 * volatility * price
            val close = open + change
            val high = maxOf(open, close) + random.nextDouble() * volatility * price
            val low = minOf(open, close) - random.nextDouble() * volatility * price
            // WHY: (count - 1 - i) so the LAST candle is stamped "now" —
            // the old formula left it one interval in the past.
            val time = Instant.ofEpochMilli(now - (count - 1 - i) * intervalMs)
            candles.add(CandleStick(time, open, high, low, close, random.nextLong(1000, 10_000)))
            price = close
        }
        return candles
    }

    /**
     * Deterministic generator seed for one chart.
     *
     * WHY it lives here and not in the repository: two callers now need it — the
     * offline source and the live source's fallback path — and the formula has to be
     * identical in both, or the same chart would simulate differently depending on
     * which route reached it (a visible price jump when the network drops).
     *
     * WHY seeded at all: the simulator re-runs whenever the cache expires. With an
     * unseeded `Random` the same pair/timeframe came back with a completely different
     * price history, so the chart "jumped" overnight and every drawing the user had
     * placed against the old candles pointed at unrelated prices.
     *
     * WHY `String.hashCode()` is acceptable *here* (and not for the signal
     * fingerprint): the seed is never persisted and never compared for equality — it
     * only has to be stable within a process for the same inputs. A collision merely
     * makes two charts share a simulation, which is invisible; the fingerprint case was
     * different because a collision silently swallowed a saved signal.
     */
    fun seedFor(pairSymbol: PairSymbol, timeframe: Timeframe): Random {
        val seed = pairSymbol.value.hashCode().toLong() * 31L + timeframe.value.hashCode().toLong()
        return Random(seed)
    }
}
