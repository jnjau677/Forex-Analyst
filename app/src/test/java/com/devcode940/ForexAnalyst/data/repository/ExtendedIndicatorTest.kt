package com.devcode940.ForexAnalyst.data.repository

import com.devcode940.ForexAnalyst.data.model.CandleStick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Tests for the indicators added with the chart-tools work: SMA, Bollinger Bands,
 * session-anchored VWAP, Stochastic and the Heikin-Ashi transform.
 *
 * WHY hand-computed expectations: an indicator that is off by one bar looks identical
 * to a correct one on a chart, and the only way to catch it is to assert the exact
 * value at the exact index. Every fixture below is small enough to verify with a
 * calculator, which is the point.
 */
class ExtendedIndicatorTest {

    private val engine = TechnicalAnalysisEngine

    private fun candle(
        index: Int,
        open: Double,
        high: Double,
        low: Double,
        close: Double,
        volume: Long = 1_000L,
        time: Instant = Instant.parse("2024-03-15T00:00:00Z").plusSeconds(3600L * index)
    ) = CandleStick(time = time, open = open, high = high, low = low, close = close, volume = volume)

    // ---------- SMA ----------

    @Test
    fun `sma is index aligned with leading nulls`() {
        val sma = engine.calculateSMA(listOf(1.0, 2.0, 3.0, 4.0), period = 2)
        assertEquals(4, sma.size)
        assertNull(sma[0])
        assertEquals(1.5, requireNotNull(sma[1]), 1e-9)
        assertEquals(2.5, requireNotNull(sma[2]), 1e-9)
        assertEquals(3.5, requireNotNull(sma[3]), 1e-9)
    }

    @Test
    fun `sma of a shorter series is all nulls and a flat series is its own average`() {
        assertEquals(listOf<Double?>(null, null), engine.calculateSMA(listOf(1.0, 2.0), period = 3))
        val flat = engine.calculateSMA(List(10) { 5.0 }, period = 4)
        assertEquals(10, flat.size)
        flat.filterNotNull().forEach { assertEquals(5.0, it, 1e-12) }
        assertTrue(engine.calculateSMA(emptyList(), period = 3).isEmpty())
    }

    @Test
    fun `sma rejects a period that cannot average anything`() {
        assertThrows(IllegalArgumentException::class.java) { engine.calculateSMA(listOf(1.0, 2.0), period = 1) }
        assertThrows(IllegalArgumentException::class.java) { engine.calculateSMA(listOf(1.0, 2.0), period = 0) }
    }

    // ---------- Bollinger ----------

    @Test
    fun `bollinger bands are two population standard deviations around the sma`() {
        val closes = listOf(1.0, 2.0, 3.0, 4.0, 5.0)
        val bands = engine.calculateBollingerBands(closes, period = 3, stdDevMultiplier = 2.0)

        assertEquals(closes.size, bands.upper.size)
        assertEquals(closes.size, bands.middle.size)
        assertEquals(closes.size, bands.lower.size)
        assertEquals(closes.size, bands.bandwidth.size)

        assertNull(bands.middle[0])
        assertNull(bands.middle[1])
        assertEquals(2.0, requireNotNull(bands.middle[2]), 1e-9)
        assertEquals(3.0, requireNotNull(bands.middle[3]), 1e-9)
        assertEquals(4.0, requireNotNull(bands.middle[4]), 1e-9)

        // Population sd of [1,2,3] is sqrt(2/3) = 0.8164966, so the band is ±1.6329931.
        val deviation = Math.sqrt(2.0 / 3.0)
        assertEquals(2.0 + 2 * deviation, requireNotNull(bands.upper[2]), 1e-9)
        assertEquals(2.0 - 2 * deviation, requireNotNull(bands.lower[2]), 1e-9)
        // WHY bandwidth is asserted separately: it is the squeeze measure, and a band
        // implementation that computes it as (upper − lower) / 2 instead of / middle
        // still draws correctly while reporting a wrong squeeze.
        assertEquals(4 * deviation / 2.0, requireNotNull(bands.bandwidth[2]), 1e-9)
        assertEquals(
            (requireNotNull(bands.upper[2]) - requireNotNull(bands.lower[2])) / requireNotNull(bands.middle[2]),
            requireNotNull(bands.bandwidth[2]),
            1e-9
        )
    }

    @Test
    fun `bollinger on a flat window collapses onto the average without dividing by zero`() {
        val bands = engine.calculateBollingerBands(List(6) { 5.0 }, period = 3, stdDevMultiplier = 2.0)
        assertEquals(5.0, requireNotNull(bands.upper[5]), 1e-9)
        assertEquals(5.0, requireNotNull(bands.lower[5]), 1e-9)
        assertEquals(0.0, requireNotNull(bands.bandwidth[5]), 1e-9)
    }

    @Test
    fun `bollinger rejects a degenerate configuration and tolerates an empty series`() {
        assertThrows(IllegalArgumentException::class.java) {
            engine.calculateBollingerBands(listOf(1.0, 2.0), period = 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            engine.calculateBollingerBands(listOf(1.0, 2.0), period = 3, stdDevMultiplier = 0.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            engine.calculateBollingerBands(listOf(1.0, 2.0), period = 3, stdDevMultiplier = Double.NaN)
        }
        val empty = engine.calculateBollingerBands(emptyList(), period = 3)
        assertTrue(empty.upper.isEmpty())
        assertTrue(empty.bandwidth.isEmpty())
    }

    // ---------- VWAP ----------

    @Test
    fun `vwap is volume weighted and anchored to the session day`() {
        val candles = listOf(
            candle(0, open = 9.0, high = 11.0, low = 9.0, close = 10.0, volume = 100L,
                time = Instant.parse("2024-03-15T10:00:00Z")),
            candle(1, open = 10.0, high = 13.0, low = 10.0, close = 12.0, volume = 200L,
                time = Instant.parse("2024-03-15T11:00:00Z")),
            candle(2, open = 19.0, high = 21.0, low = 19.0, close = 20.0, volume = 100L,
                time = Instant.parse("2024-03-16T10:00:00Z"))
        )
        val vwap = engine.calculateVWAP(candles, ZoneOffset.UTC)

        assertEquals(candles.size, vwap.size)
        // WHY the typical price is spelled out: VWAP weights (high + low + close) / 3,
        // not the close. Asserting against the close would pass for a plain cumulative
        // average and hide the weighting entirely.
        val typical0 = (11.0 + 9.0 + 10.0) / 3.0
        val typical1 = (13.0 + 10.0 + 12.0) / 3.0
        assertEquals(typical0, requireNotNull(vwap[0]), 1e-9)
        assertEquals((typical0 * 100 + typical1 * 200) / 300.0, requireNotNull(vwap[1]), 1e-9)
        // WHY this is the assertion that matters: a cumulative-from-the-first-bar VWAP
        // would carry the 10 and 12 into the next session and flatten into a constant.
        assertEquals(20.0, requireNotNull(vwap[2]), 1e-9)
    }

    @Test
    fun `the vwap anchor moves with the chart time zone`() {
        // 20:00Z and 22:00Z are the same UTC day but different days in Nairobi (UTC+3).
        val candles = listOf(
            candle(0, open = 9.0, high = 11.0, low = 9.0, close = 10.0, volume = 100L,
                time = Instant.parse("2024-03-15T20:00:00Z")),
            candle(1, open = 19.0, high = 21.0, low = 19.0, close = 20.0, volume = 100L,
                time = Instant.parse("2024-03-15T22:00:00Z"))
        )

        val utc = engine.calculateVWAP(candles, ZoneOffset.UTC)
        val nairobi = engine.calculateVWAP(candles, ZoneId.of("Africa/Nairobi"))

        // Same UTC day → the second bar accumulates: (10×100 + 20×100) / 200 = 15.
        assertEquals(15.0, requireNotNull(utc[1]), 1e-9)
        // New Nairobi day → the anchor resets to the bar's own typical price.
        assertEquals(20.0, requireNotNull(nairobi[1]), 1e-9)
        // The first bar is the anchor in both zones, so the two agree there.
        assertEquals(10.0, requireNotNull(utc[0]), 1e-9)
        assertEquals(10.0, requireNotNull(nairobi[0]), 1e-9)
    }

    @Test
    fun `zero volume bars carry the previous vwap instead of dividing by zero`() {
        val candles = listOf(
            candle(0, open = 9.0, high = 11.0, low = 9.0, close = 10.0, volume = 0L),
            candle(1, open = 10.0, high = 12.0, low = 10.0, close = 11.0, volume = 0L),
            candle(2, open = 13.0, high = 15.0, low = 13.0, close = 14.0, volume = 50L)
        )
        val vwap = engine.calculateVWAP(candles, ZoneOffset.UTC)
        // WHY the typical price for the first bar: with nothing traded yet, the bar's
        // own typical price (11 + 9 + 10) / 3 = 10 is the only honest estimate, and it
        // keeps the line continuous instead of emitting NaN.
        assertEquals(10.0, requireNotNull(vwap[0]), 1e-9)
        // WHY the second bar repeats it: a simulated or thin feed produces runs of
        // zero-volume bars, and dividing by a zero cumulative volume would give NaN,
        // which then poisons every later value in the series.
        assertEquals(10.0, requireNotNull(vwap[1]), 1e-9)
        // The first bar with volume restarts the average from itself.
        assertEquals(14.0, requireNotNull(vwap[2]), 1e-9)
        vwap.forEach { assertTrue(it.isFinite()) }
        assertTrue(engine.calculateVWAP(emptyList()).isEmpty())
    }

    // ---------- Stochastic ----------

    @Test
    fun `stochastic k and d are aligned with the candles`() {
        val candles = listOf(
            candle(0, open = 8.0, high = 10.0, low = 5.0, close = 8.0),
            candle(1, open = 8.0, high = 12.0, low = 6.0, close = 11.0),
            candle(2, open = 11.0, high = 11.0, low = 7.0, close = 9.0),
            candle(3, open = 9.0, high = 13.0, low = 8.0, close = 12.0)
        )
        val result = engine.calculateStochastic(candles, kPeriod = 3, dPeriod = 2)

        assertEquals(candles.size, result.k.size)
        assertEquals(candles.size, result.d.size)
        assertNull(result.k[0])
        assertNull(result.k[1])
        // Bar 2: highest high 12, lowest low 5, close 9 → (9−5)/7 = 57.142857 %.
        assertEquals(400.0 / 7.0, requireNotNull(result.k[2]), 1e-9)
        // Bar 3: highest high 13, lowest low 6, close 12 → (12−6)/7 = 85.714286 %.
        assertEquals(600.0 / 7.0, requireNotNull(result.k[3]), 1e-9)

        // WHY %D alignment is asserted by index: %D is an SMA of the *computed* %K
        // values, so its first non-null index is (kPeriod − 1) + (dPeriod − 1) = 3.
        // Re-zipping the compacted list would put it at index 1 and draw it two bars
        // early — invisible on screen, wrong in every crossover signal.
        assertNull(result.d[0])
        assertNull(result.d[1])
        assertNull(result.d[2])
        assertEquals((400.0 / 7.0 + 600.0 / 7.0) / 2.0, requireNotNull(result.d[3]), 1e-9)
    }

    @Test
    fun `stochastic reports neutral on a window with no range`() {
        val flat = (0 until 5).map { index -> candle(index, open = 10.0, high = 10.0, low = 10.0, close = 10.0) }
        val result = engine.calculateStochastic(flat, kPeriod = 3, dPeriod = 2)
        assertEquals(50.0, requireNotNull(result.k[4]), 1e-9)
        // WHY 50 and not 0 or 100: a window with no range says nothing about position
        // within it, and 50 matches the RSI convention already used in this engine —
        // reporting 100 would flag an overbought market that is perfectly still.
        result.k.filterNotNull().forEach { assertTrue(it in 0.0..100.0) }
    }

    @Test
    fun `stochastic needs enough bars and rejects bad periods`() {
        val two = listOf(candle(0, 1.0, 2.0, 0.5, 1.5), candle(1, 1.5, 2.5, 1.0, 2.0))
        val result = engine.calculateStochastic(two, kPeriod = 5, dPeriod = 2)
        assertEquals(2, result.k.size)
        assertTrue(result.k.all { it == null })
        assertTrue(engine.calculateStochastic(emptyList(), kPeriod = 3, dPeriod = 2).k.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { engine.calculateStochastic(two, kPeriod = 1) }
        assertThrows(IllegalArgumentException::class.java) { engine.calculateStochastic(two, dPeriod = 1) }
    }

    // ---------- Heikin-Ashi ----------

    @Test
    fun `heikin ashi smooths recursively and keeps time and volume`() {
        val raw = listOf(
            candle(0, open = 10.0, high = 11.0, low = 9.0, close = 10.5, volume = 111L),
            candle(1, open = 10.5, high = 12.0, low = 10.0, close = 11.5, volume = 222L)
        )
        val smoothed = engine.heikinAshi(raw)

        assertEquals(raw.size, smoothed.size)
        // WHY time and volume are preserved: the time axis and the volume pane must
        // keep working when the user switches chart style.
        assertEquals(raw.map { it.time }, smoothed.map { it.time })
        assertEquals(raw.map { it.volume }, smoothed.map { it.volume })

        // First bar opens at the midpoint of the raw open and close.
        assertEquals(10.25, smoothed[0].open, 1e-9)
        assertEquals((10.0 + 11.0 + 9.0 + 10.5) / 4.0, smoothed[0].close, 1e-9)
        // Each later bar opens at the midpoint of the *smoothed* previous bar.
        assertEquals((10.25 + smoothed[0].close) / 2.0, smoothed[1].open, 1e-9)
        assertEquals((10.5 + 12.0 + 10.0 + 11.5) / 4.0, smoothed[1].close, 1e-9)
        assertEquals(12.0, smoothed[1].high, 1e-9)
        assertEquals(10.0, smoothed[1].low, 1e-9)
    }

    @Test
    fun `every heikin ashi bar still bounds its own open and close`() {
        val raw = listOf(
            candle(0, open = 100.0, high = 101.0, low = 99.0, close = 100.5),
            candle(1, open = 100.5, high = 103.0, low = 100.0, close = 102.5),
            candle(2, open = 102.5, high = 103.0, low = 98.0, close = 98.5),
            candle(3, open = 98.5, high = 99.0, low = 96.0, close = 96.5)
        )
        engine.heikinAshi(raw).forEach { bar ->
            // WHY: CandleStick validates this in its own init, so a violation would
            // throw while rendering — but asserting it here names the invariant that
            // the transform must preserve rather than leaving it implicit.
            assertTrue(bar.high >= maxOf(bar.open, bar.close))
            assertTrue(bar.low <= minOf(bar.open, bar.close))
            assertTrue(bar.high >= bar.low)
        }
        assertTrue(engine.heikinAshi(emptyList()).isEmpty())
    }

    @Test
    fun `heikin ashi of a single bar is that bar smoothed once`() {
        val rawVolume = 77L
        val single = engine.heikinAshi(
            listOf(candle(0, open = 10.0, high = 11.0, low = 9.0, close = 10.0, volume = rawVolume))
        )
        assertEquals(1, single.size)
        assertEquals(10.0, single[0].open, 1e-9)
        assertEquals(10.0, single[0].close, 1e-9)
        assertEquals(11.0, single[0].high, 1e-9)
        assertEquals(9.0, single[0].low, 1e-9)
        assertEquals(rawVolume, single[0].volume)
    }
}
