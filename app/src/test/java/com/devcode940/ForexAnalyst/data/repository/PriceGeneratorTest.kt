package com.devcode940.ForexAnalyst.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.random.Random

/**
 * Unit tests for the simulated market-data source.
 *
 * WHY: [PriceGenerator] now takes an injectable [Random] and [Clock], which is
 * exactly what makes these assertions possible — the old version used
 * `kotlin.random.Random.Default` and `System.currentTimeMillis()`, so its
 * output could never be pinned down by a test.
 */
class PriceGeneratorTest {

    private val fixedClock: Clock = Clock.fixed(Instant.parse("2024-06-01T12:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `same seed produces identical candles`() {
        val a = PriceGenerator.generateCandles(count = 50, random = Random(42), clock = fixedClock)
        val b = PriceGenerator.generateCandles(count = 50, random = Random(42), clock = fixedClock)
        assertEquals(a, b)
    }

    @Test
    fun `different seed produces different candles`() {
        val a = PriceGenerator.generateCandles(count = 50, random = Random(42), clock = fixedClock)
        val b = PriceGenerator.generateCandles(count = 50, random = Random(7), clock = fixedClock)
        assertNotEquals(a, b)
    }

    @Test
    fun `generates the requested number of candles`() {
        assertEquals(250, PriceGenerator.generateCandles(count = 250, random = Random(1), clock = fixedClock).size)
    }

    @Test
    fun `last candle is stamped at the injected clock`() {
        // WHY (regression): the old formula `now - (count - i) * interval`
        // left the newest candle one full interval in the past, so the chart
        // always looked stale and the cache TTL was effectively shortened.
        val candles = PriceGenerator.generateCandles(
            count = 10,
            intervalMs = 3_600_000L,
            random = Random(3),
            clock = fixedClock
        )
        assertEquals(fixedClock.millis(), candles.last().timeMillis)
        assertEquals(fixedClock.millis() - 9 * 3_600_000L, candles.first().timeMillis)
    }

    @Test
    fun `timestamps are strictly ascending and evenly spaced`() {
        val candles = PriceGenerator.generateCandles(
            count = 100,
            intervalMs = 900_000L,
            random = Random(5),
            clock = fixedClock
        )
        candles.zipWithNext { previous, next ->
            assertEquals(900_000L, next.timeMillis - previous.timeMillis)
        }
    }

    @Test
    fun `every candle satisfies the ohlc invariants`() {
        val candles = PriceGenerator.generateCandles(
            count = 500,
            basePrice = 150.0,
            volatility = 0.004,
            random = Random(11),
            clock = fixedClock
        )
        candles.forEach { candle ->
            // WHY: CandleStick's init block already enforces these; asserting
            // them here proves the generator never relies on that check
            // throwing (which would surface as a crash in production).
            assertTrue(candle.high >= candle.low)
            assertTrue(candle.high >= maxOf(candle.open, candle.close))
            assertTrue(candle.low <= minOf(candle.open, candle.close))
            assertTrue(candle.close > 0.0)
            assertTrue(candle.volume in 1_000L..9_999L)
        }
    }

    @Test
    fun `zero volatility produces a flat but valid series`() {
        val candles = PriceGenerator.generateCandles(
            count = 20,
            basePrice = 1.1,
            volatility = 0.0,
            random = Random(9),
            clock = fixedClock
        )
        candles.forEach { candle ->
            assertEquals(1.1, candle.open, 1e-12)
            assertEquals(1.1, candle.close, 1e-12)
            assertEquals(1.1, candle.high, 1e-12)
            assertEquals(1.1, candle.low, 1e-12)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects zero count`() {
        PriceGenerator.generateCandles(count = 0, random = Random(1), clock = fixedClock)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects negative count`() {
        PriceGenerator.generateCandles(count = -5, random = Random(1), clock = fixedClock)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects non positive base price`() {
        PriceGenerator.generateCandles(count = 10, basePrice = 0.0, random = Random(1), clock = fixedClock)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects nan base price`() {
        // WHY: NaN in one candle poisons every downstream EMA/RSI/MACD value.
        PriceGenerator.generateCandles(count = 10, basePrice = Double.NaN, random = Random(1), clock = fixedClock)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects negative volatility`() {
        PriceGenerator.generateCandles(count = 10, volatility = -0.001, random = Random(1), clock = fixedClock)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects non positive interval`() {
        PriceGenerator.generateCandles(count = 10, intervalMs = 0L, random = Random(1), clock = fixedClock)
    }
}
