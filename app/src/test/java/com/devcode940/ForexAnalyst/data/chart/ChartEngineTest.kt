package com.devcode940.ForexAnalyst.data.chart

import com.devcode940.ForexAnalyst.data.model.CandleStick
import com.devcode940.ForexAnalyst.data.model.ChartConfig
import com.devcode940.ForexAnalyst.data.model.ChartOverlay
import com.devcode940.ForexAnalyst.data.model.ChartTimeZone
import com.devcode940.ForexAnalyst.data.model.PriceFormatter
import com.devcode940.ForexAnalyst.data.model.Timeframe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Tests for the pure chart engine: index maths, coordinate mapping, layout, both axes
 * and time formatting.
 *
 * WHY these are the most valuable tests in the project: every one of them describes a
 * bug that is invisible in a code review and obvious on screen — an indicator drawn
 * eight bars early, a label reading 02:30 instead of 03:00 after a DST change, a
 * sub-pane overlapping the candles. None of them needs Compose, a device or a
 * screenshot to catch.
 */
class ChartEngineTest {

    /** Ascending hourly series with a known shape: 1.00 rising by 0.01 per bar. */
    private fun candles(count: Int, startPrice: Double = 1.00, step: Double = 0.01): List<CandleStick> {
        val start = Instant.parse("2024-03-15T00:00:00Z")
        return (0 until count).map { index ->
            val open = startPrice + index * step
            val close = open + step
            CandleStick(
                time = start.plusSeconds(3600L * index),
                open = open,
                high = close + step,
                low = open - step,
                close = close,
                volume = 1_000L + index
            )
        }
    }

    // ---------- ChartMath ----------

    @Test
    fun `lowerBound and upperBound bracket the target index`() {
        val series = candles(10)
        val target = series[4].timeMillis
        assertEquals(4, ChartMath.lowerBound(series, target))
        assertEquals(5, ChartMath.upperBound(series, target))
        // Before everything / after everything.
        assertEquals(0, ChartMath.lowerBound(series, series.first().timeMillis - 1))
        assertEquals(series.size, ChartMath.lowerBound(series, series.last().timeMillis + 1))
        assertEquals(series.size, ChartMath.upperBound(series, series.last().timeMillis))
    }

    @Test
    fun `nearestIndex picks the closer of the two surrounding bars`() {
        val series = candles(10)
        val between = series[3].timeMillis
        val quarterHour = 900_000L
        assertEquals(3, ChartMath.nearestIndex(series, between + quarterHour))
        assertEquals(4, ChartMath.nearestIndex(series, between + 3 * quarterHour))
        assertEquals(0, ChartMath.nearestIndex(series, between - 10 * quarterHour))
        assertEquals(series.size - 1, ChartMath.nearestIndex(series, between + 100 * quarterHour))
        assertEquals(-1, ChartMath.nearestIndex(emptyList(), between))
    }

    @Test
    fun `visibleRange includes a buffer so partial candles still render`() {
        val series = candles(20)
        val range = ChartMath.visibleRange(series, series[5].timeMillis, series[9].timeMillis, buffer = 2)
        assertEquals(3, range.first)
        // WHY last == 11: upperBound is exclusive and points one past the final
        // visible bar, so the buffer extends it to 11 and the range is 3..11 inclusive
        // of index 11 - which is `until 12`.
        assertEquals(11, range.last)
        assertTrue(ChartMath.visibleRange(emptyList<CandleStick>(), 0L, 1L).isEmpty())
    }

    @Test
    fun `priceBounds widens a flat window instead of dividing by zero`() {
        val flat = (0 until 5).map { index ->
            CandleStick(Instant.parse("2024-03-15T00:00:00Z").plusSeconds(3600L * index), 1.0, 1.0, 1.0, 1.0, 0L)
        }
        val (min, max) = ChartMath.priceBounds(flat, 0, flat.size)
        assertTrue(max > min)
        // An out-of-range window is clamped, not crashed on.
        assertEquals(0.0 to 1.0, ChartMath.priceBounds(emptyList(), 0, 5))
        val (lo, hi) = ChartMath.priceBounds(flat, 99, 100)
        assertTrue(hi >= lo)
    }

    // ---------- ChartCoordinateSystem ----------

    @Test
    fun `price mapping is exact at both edges and invertible`() {
        val rect = ChartRect.of(0f, 0f, 400f, 200f)
        val cs = ChartCoordinateSystem(rect, minPrice = 1.0, maxPrice = 2.0, startTimeMillis = 0L, endTimeMillis = 1_000L)
        assertEquals(rect.topEdge, cs.priceToY(2.0), 0.001f)
        assertEquals(rect.bottomEdge, cs.priceToY(1.0), 0.001f)
        assertEquals(rect.topEdge + rect.height / 2f, cs.priceToY(1.5), 0.001f)
        // WHY round-trip: the crosshair converts a pixel back to a price; if the two
        // directions disagree the tooltip reports a price that is not under the finger.
        assertEquals(1.25, cs.yToPrice(cs.priceToY(1.25)), 1e-9)
    }

    @Test
    fun `time mapping honours scale and offset and clamps the pan`() {
        val rect = ChartRect.of(0f, 0f, 400f, 200f)
        val cs = ChartCoordinateSystem(rect, 1.0, 2.0, startTimeMillis = 0L, endTimeMillis = 1_000L, scale = 2f, offsetX = -100f)
        // Span is 800px; t=500 is the midpoint → 400px, minus the 100px pan.
        assertEquals(300f, cs.timeToX(500L), 0.001f)
        assertEquals(500L, cs.xToTime(300f))
        // At 2x zoom the overflow is 400px, so the offset is clamped to [-400, 0].
        assertEquals(0f, cs.clampOffset(50f), 0.001f)
        assertEquals(-400f, cs.clampOffset(-900f), 0.001f)
        assertEquals(-250f, cs.clampOffset(-250f), 0.001f)
        // At 1x there is no overflow, so any pan is refused.
        val unzoomed = ChartCoordinateSystem(rect, 1.0, 2.0, 0L, 1_000L)
        assertEquals(0f, unzoomed.clampOffset(-50f), 0.001f)
    }

    @Test
    fun `bar width shrinks with the bar interval and stays inside its clamp`() {
        val rect = ChartRect.of(0f, 0f, 400f, 200f)
        val cs = ChartCoordinateSystem(rect, 1.0, 2.0, startTimeMillis = 0L, endTimeMillis = 400_000L)
        // 100 000 ms per bar over a 400 000 ms span = 100px per slot, × 0.7 body ratio.
        assertEquals(70f, cs.barWidthPx(100_000L), 0.001f)
        // A bar far wider than the pane is clamped, so one candle cannot cover everything.
        assertEquals(48f, cs.barWidthPx(10_000_000L), 0.001f)
        // A degenerate interval falls back to the default instead of returning 0.
        assertEquals(8f, cs.barWidthPx(0L), 0.001f)
    }

    @Test
    fun `forCandles pads the price range so wicks are never clipped`() {
        val rect = ChartRect.of(0f, 0f, 400f, 200f)
        val series = candles(20)
        val cs = ChartCoordinateSystem.forCandles(rect, series, 0 until series.size)
        assertTrue(cs.minPrice < series.minOf { it.low })
        assertTrue(cs.maxPrice > series.maxOf { it.high })
        // An empty series still yields a usable (non-NaN) mapping.
        val empty = ChartCoordinateSystem.forCandles(rect, emptyList(), IntRange.EMPTY)
        assertNotNull(empty)
        assertTrue(empty.priceToY(0.5).isFinite())
    }

    // ---------- ChartLayoutBuilder ----------

    @Test
    fun `layout heights add up to the canvas and axes keep their reserved space`() {
        val config = ChartConfig(enabled = setOf(ChartOverlay.VOLUME, ChartOverlay.RSI))
        val layout = ChartLayoutBuilder.build(400f, 600f, config)

        assertEquals(ChartLayoutBuilder.PRICE_AXIS_WIDTH_PX, layout.priceAxisRect.width, 0.001f)
        assertEquals(ChartLayoutBuilder.TIME_AXIS_HEIGHT_PX, layout.timeAxisRect.height, 0.001f)
        assertEquals(2, layout.panes.size)
        assertEquals(ChartOverlay.VOLUME, layout.panes[0].overlay)
        assertEquals(ChartOverlay.RSI, layout.panes[1].overlay)

        // WHY this assertion matters: a pane layout that overflows by a few pixels
        // draws the last indicator over the time axis, and the arithmetic is easy to
        // get wrong by exactly one gap.
        val lastPane = layout.panes.last().rect
        assertTrue(
            "panes overflow the plot rect",
            lastPane.bottomEdge <= layout.plotRect.bottomEdge + 0.001f
        )
        assertTrue(layout.priceRect.bottomEdge <= layout.panes.first().rect.topEdge)
        assertTrue(layout.priceRect.height >= ChartLayoutBuilder.MIN_PRICE_HEIGHT_PX)
    }

    @Test
    fun `a short canvas drops sub-panes rather than crushing the price pane`() {
        val config = ChartConfig(enabled = ChartOverlay.PANES.toSet())
        val layout = ChartLayoutBuilder.build(400f, 120f, config)
        assertTrue(layout.panes.isEmpty())
        assertEquals(layout.plotRect, layout.priceRect)
    }

    @Test
    fun `a degenerate canvas yields empty rects instead of throwing`() {
        val layout = ChartLayoutBuilder.build(0f, 0f, ChartConfig.default())
        assertFalse(layout.priceRect.isUsable)
        assertTrue(layout.panes.isEmpty())
    }

    // ---------- PriceAxis ----------

    @Test
    fun `price ticks are ascending, round and inside the range`() {
        val ticks = PriceAxis.ticks(min = 1.08123, max = 1.08987, targetTicks = 6, digits = 5)
        assertTrue(ticks.size >= 2)
        assertEquals(ticks.sorted(), ticks)
        assertTrue(ticks.first() >= 1.08123)
        assertTrue(ticks.last() <= 1.08987)
        // WHY no floating-point dust: labels are rendered verbatim, so a tick at
        // 1.0850000000000002 shows up on screen exactly like that.
        ticks.forEach { tick ->
            assertEquals(tick, PriceFormatter.round(tick, 5), 0.0)
        }
    }

    @Test
    fun `niceStep rounds to the 1-2-2dot5-5 ladder`() {
        assertEquals(1.0, PriceAxis.niceStep(0.9), 1e-9)
        assertEquals(2.0, PriceAxis.niceStep(1.1), 1e-9)
        assertEquals(2.5, PriceAxis.niceStep(2.2), 1e-9)
        assertEquals(5.0, PriceAxis.niceStep(3.0), 1e-9)
        assertEquals(10.0, PriceAxis.niceStep(7.0), 1e-9)
        assertEquals(0.0, PriceAxis.niceStep(-1.0), 0.0)
        assertEquals(0.002, PriceAxis.niceStep(0.0018), 1e-12)
    }

    @Test
    fun `colliding labels are detected so the renderer can thin them out`() {
        assertTrue(PriceAxis.labelsCollide(spacingPx = 10f, labelHeightPx = 14f))
        assertFalse(PriceAxis.labelsCollide(spacingPx = 40f, labelHeightPx = 14f))
        assertEquals(100f, PriceAxis.labelSpacingPx(100f, 1), 0.001f)
        assertEquals(50f, PriceAxis.labelSpacingPx(100f, 3), 0.001f)
    }

    // ---------- TimeAxis: the "style time" feature ----------

    @Test
    fun `the same instant is labelled differently in different zones`() {
        val instant = Instant.parse("2024-03-15T13:00:00Z")
        val utc = TimeAxis.labelFor(instant, TimeTickStep.HOUR_1, ZoneOffset.UTC)
        val nairobi = TimeAxis.labelFor(instant, TimeTickStep.HOUR_1, ZoneId.of("Africa/Nairobi"))
        val newYork = TimeAxis.labelFor(instant, TimeTickStep.HOUR_1, ZoneId.of("America/New_York"))

        assertEquals("13:00", utc)
        // WHY these three: Nairobi is the developer's zone (UTC+3), New York is the
        // session a forex trader cares about (UTC-4 in March, i.e. DST already
        // applied), and UTC is the reference. Getting any of them wrong means the
        // axis lies about when a candle happened.
        assertEquals("16:00", nairobi)
        assertEquals("09:00", newYork)
    }

    @Test
    fun `an intraday tick at local midnight is promoted to a date`() {
        // 2024-03-16T00:00 in Nairobi is 2024-03-15T21:00Z.
        val instant = Instant.parse("2024-03-15T21:00:00Z")
        val label = TimeAxis.labelFor(instant, TimeTickStep.HOUR_1, ZoneId.of("Africa/Nairobi"))
        assertEquals("16 Mar", label)
        // The same instant is not midnight in UTC, so it stays a time.
        assertEquals("21:00", TimeAxis.labelFor(instant, TimeTickStep.HOUR_1, ZoneOffset.UTC))
    }

    @Test
    fun `step size grows with the visible span`() {
        val minute = 60_000L
        assertEquals(TimeTickStep.MINUTE_5, TimeAxis.chooseStep(30 * minute, targetTicks = 6))
        // WHY these spans: each is exactly six times the expected step, so the
        // assertion pins the "smallest step that fits" rule rather than a range of it.
        assertEquals(TimeTickStep.HOUR_1, TimeAxis.chooseStep(6 * 60 * minute, targetTicks = 6))
        assertEquals(TimeTickStep.DAY_1, TimeAxis.chooseStep(6 * 24 * 60 * minute, targetTicks = 6))
        // A nonsense span still yields a usable step rather than throwing.
        assertEquals(TimeTickStep.HOUR_1, TimeAxis.chooseStep(0L))
        assertEquals(TimeTickStep.MONTH_1, TimeAxis.chooseStep(10L * 365 * 24 * 60 * minute, targetTicks = 6))
    }

    @Test
    fun `every timeframe has a default step and intraday detection agrees with it`() {
        Timeframe.ORDERED.forEach { value ->
            val timeframe = Timeframe(value)
            assertNotNull(TimeAxis.defaultStep(timeframe))
            assertEquals(timeframe.intervalMillis < 86_400_000L, timeframe.isIntraday)
        }
        assertEquals(TimeTickStep.MINUTE_15, TimeAxis.defaultStep(Timeframe("M1")))
        assertEquals(TimeTickStep.MONTH_1, TimeAxis.defaultStep(Timeframe("W1")))
        assertEquals(60_000L, Timeframe("M1").intervalMillis)
        assertEquals(604_800_000L, Timeframe("W1").intervalMillis)
    }

    @Test
    fun `ticks are aligned to round local hours and bounded`() {
        val zone = ZoneOffset.UTC
        val start = Instant.parse("2024-03-15T00:30:00Z")
        val end = Instant.parse("2024-03-16T00:00:00Z")
        val ticks = TimeAxis.ticks(start, end, TimeTickStep.HOUR_4, zone, maxTicks = 12)

        assertTrue(ticks.size in 1..12)
        assertEquals(ticks.sorted(), ticks)
        assertTrue(ticks.all { it.isAfter(start) && !it.isAfter(end) })
        // WHY aligned: the first tick must be the next 4-hour boundary, not the raw
        // start, or the axis shows 00:30 / 04:30 / 08:30 — times no trader uses.
        ticks.forEach { tick ->
            val local = tick.atZone(zone)
            assertEquals(0, local.minute)
            assertEquals(0, local.hour % 4)
        }
        assertEquals(Instant.parse("2024-03-15T04:00:00Z"), ticks.first())
    }

    @Test
    fun `a DST switch shifts the local hour but never produces a half-hour label`() {
        // WHY this case is tested at all: 2024-03-10 is the US spring-forward day.
        // Adding fixed milliseconds across it lands labels on 05:30/09:30 local time;
        // building them from a ZonedDateTime keeps every label on a round hour even
        // though the *local* hour number shifts by one when the clocks move.
        val zone = ZoneId.of("America/New_York")
        val start = Instant.parse("2024-03-10T05:00:00Z") // 00:00 EST
        val end = Instant.parse("2024-03-11T05:00:00Z")
        val ticks = TimeAxis.ticks(start, end, TimeTickStep.HOUR_4, zone, maxTicks = 12)

        assertTrue(ticks.size in 1..12)
        assertEquals(ticks.sorted(), ticks)
        ticks.forEach { tick -> assertEquals(0, tick.atZone(zone).minute) }

        // Degenerate and hostile inputs return empty instead of looping forever.
        assertTrue(TimeAxis.ticks(end, start, TimeTickStep.HOUR_1, zone).isEmpty())
        assertTrue(TimeAxis.ticks(start, start, TimeTickStep.HOUR_1, zone).isEmpty())
        assertTrue(TimeAxis.ticks(start, end, TimeTickStep.HOUR_1, zone, maxTicks = 0).isEmpty())
    }

    @Test
    fun `detailed labels name the day so a tooltip is unambiguous`() {
        val instant = Instant.parse("2024-03-15T13:30:00Z")
        val label = TimeAxis.detailedLabel(instant, ZoneOffset.UTC)
        assertEquals("Fri 15 Mar 2024, 13:30", label)
        // WHY the axis label for the same instant is shorter: an axis tick has to fit
        // between its neighbours, while a tooltip has the whole pane to itself.
        assertEquals("13:30", TimeAxis.labelFor(instant, TimeTickStep.HOUR_1, ZoneOffset.UTC))
        assertEquals("15 Mar", TimeAxis.labelFor(instant, TimeTickStep.DAY_1, ZoneOffset.UTC))
    }

    @Test
    fun `time zone choices resolve and badge correctly`() {
        assertEquals(ZoneOffset.UTC, ChartTimeZone.UTC.resolve())
        assertEquals("utc", ChartTimeZone.UTC.badge())
        assertEquals("london", ChartTimeZone.LONDON.badge())
        assertEquals("nairobi", ChartTimeZone.NAIROBI.badge())
        // WHY DEVICE is resolved lazily: a user who changes their phone's zone while
        // the app is open should see the chart follow it without a restart.
        assertEquals(ZoneId.systemDefault(), ChartTimeZone.DEVICE.resolve())
        assertEquals("local", ChartTimeZone.DEVICE.badge())
        ChartTimeZone.entries.forEach { zone -> assertNotNull(zone.resolve()) }
    }

    // ---------- TimeFormatter ----------

    @Test
    fun `relative wording covers past, future and just now`() {
        val now = Instant.parse("2024-03-15T12:00:00Z")
        assertEquals("just now", TimeFormatter.relative(now.minusSeconds(20), now))
        assertEquals("5 min ago", TimeFormatter.relative(now.minusSeconds(300), now))
        assertEquals("2 h ago", TimeFormatter.relative(now.minusSeconds(7_200), now))
        assertEquals("3 d ago", TimeFormatter.relative(now.minusSeconds(3 * 86_400), now))
        // WHY future tense exists: a feed whose clock runs ahead would otherwise
        // render "0 min ago" for a bar that has not happened yet.
        assertEquals("in 5 min", TimeFormatter.relative(now.plusSeconds(300), now))
        assertEquals("just now", TimeFormatter.relative(now.plusSeconds(5), now))
    }

    @Test
    fun `durations use two units and measurements count bars`() {
        val from = Instant.parse("2024-03-15T00:00:00Z")
        assertEquals("1 h 25 min", TimeFormatter.duration(from, from.plusSeconds(5_100)))
        assertEquals("2 d 3 h", TimeFormatter.duration(from, from.plusSeconds(2 * 86_400 + 3 * 3_600)))
        assertEquals("45 s", TimeFormatter.duration(from, from.plusSeconds(45)))
        // Direction must not matter for a measurement.
        assertEquals("1 h 25 min", TimeFormatter.duration(from.plusSeconds(5_100), from))

        assertEquals(5L, TimeFormatter.barsBetween(from, from.plusSeconds(5 * 3_600), Timeframe("H1")))
        assertEquals(0L, TimeFormatter.barsBetween(from, from.plusSeconds(1_800), Timeframe("H1")))
        assertEquals(2L, TimeFormatter.barsBetween(from, from.plusSeconds(2 * 86_400), Timeframe("D1")))
    }

    // ---------- ChartRect ----------

    @Test
    fun `rects normalize inverted corners so a right-to-left drag cannot invert them`() {
        val inverted = ChartRect(left = 100f, top = 50f, right = 10f, bottom = 20f)
        assertEquals(10f, inverted.leftEdge, 0.001f)
        assertEquals(100f, inverted.rightEdge, 0.001f)
        assertEquals(20f, inverted.topEdge, 0.001f)
        assertEquals(50f, inverted.bottomEdge, 0.001f)
        assertEquals(90f, inverted.width, 0.001f)
        assertTrue(inverted.contains(50f, 30f))
        assertFalse(inverted.contains(200f, 30f))
        assertFalse(ChartRect.EMPTY.isUsable)
        assertTrue(ChartRect.of(0f, 0f, 10f, 10f).inset(2f).isUsable)
        // An inset larger than the rect must not invert it.
        val crushed = ChartRect.of(0f, 0f, 10f, 10f).inset(50f)
        assertTrue(crushed.leftEdge <= crushed.rightEdge)
        assertTrue(crushed.topEdge <= crushed.bottomEdge)
    }

    // ---------- ChartViewport ----------

    @Test
    fun `viewport rejects a scale that would divide by zero`() {
        assertEquals(1f, ChartViewport.DEFAULT.scale, 0.001f)
        assertThrowsIllegal { ChartViewport(scale = 0f) }
        assertThrowsIllegal { ChartViewport(scale = -1f) }
        assertThrowsIllegal { ChartViewport(offsetX = Float.NaN) }
        assertEquals(
            ChartViewport(2f, 10f),
            ChartViewport(2f, 10f).copy(scale = 2f)
        )
    }

    private fun assertThrowsIllegal(block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertNotNull(expected.message)
        }
    }

    // ---------- CrosshairData ----------

    @Test
    fun `crosshair keeps the candle it snapped to`() {
        val series = candles(5)
        val data = CrosshairData(price = 1.02, time = series[2].time, candle = series[2])
        assertEquals(series[2].timeMillis, data.timeMillis)
        assertEquals(series[2], data.candle)
        assertThrowsIllegal { CrosshairData(price = Double.NaN, time = series[0].time, candle = null) }
    }
}
