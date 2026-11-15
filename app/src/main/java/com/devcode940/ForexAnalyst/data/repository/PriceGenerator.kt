package com.devcode940.ForexAnalyst.data.repository

import com.devcode940.ForexAnalyst.data.model.CandleStick
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
}
