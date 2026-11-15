package com.devcode940.ForexAnalyst.data.chart

import com.devcode940.ForexAnalyst.data.model.CandleStick
import com.devcode940.ForexAnalyst.data.model.ChartDrawing
import com.devcode940.ForexAnalyst.data.model.ChartPoint
import com.devcode940.ForexAnalyst.data.model.ChartToolMode
import com.devcode940.ForexAnalyst.data.model.DrawingId
import com.devcode940.ForexAnalyst.data.model.DrawingPalette
import com.devcode940.ForexAnalyst.data.model.DrawingStyle
import com.devcode940.ForexAnalyst.data.model.InstrumentCatalog
import com.devcode940.ForexAnalyst.data.model.MagnetMode
import com.devcode940.ForexAnalyst.data.model.PairSymbol
import com.devcode940.ForexAnalyst.data.model.Timeframe
import com.devcode940.ForexAnalyst.data.model.TradeSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Tests for chart drawings: geometry, the in-memory store and the gesture→object
 * factory.
 *
 * WHY this file carries the heaviest test load of the new features: drawing bugs are
 * the ones a reviewer cannot see. A hit-test that is two pixels too strict makes an
 * object unselectable; a fib that counts 0 % from the wrong anchor mirrors every
 * level; an undo that pushes a snapshot for a no-op makes Undo look broken. All three
 * are one-line fixes once a test names them.
 */
class DrawingTest {

    private val pair = PairSymbol("EURUSD")
    private val timeframe = Timeframe("H1")
    private val createdAt = Instant.parse("2024-03-15T12:00:00Z")
    private val instrument = InstrumentCatalog.specFor(pair)

    /**
     * A 400×200 pane mapping t∈[0,1000] to x and price∈[1,2] to y.
     *
     * WHY these numbers: they make every expected coordinate a round value
     * (t=500 → x=200, price=1.5 → y=100), so a wrong assertion is obvious instead of
     * needing a calculator to check.
     */
    private val coords = ChartCoordinateSystem(
        rect = ChartRect.of(0f, 0f, 400f, 200f),
        minPrice = 1.0,
        maxPrice = 2.0,
        startTimeMillis = 0L,
        endTimeMillis = 1_000L
    )

    private fun point(timeMillis: Long, price: Double) =
        ChartPoint(Instant.ofEpochMilli(timeMillis), price)

    // ---------- geometry ----------

    @Test
    fun `distance and distance-to-segment behave at the endpoints and off them`() {
        val a = PixelPoint(0f, 0f)
        val b = PixelPoint(100f, 0f)
        assertEquals(100f, DrawingGeometry.distance(a, b), 0.001f)
        // Above the middle of the segment.
        assertEquals(20f, DrawingGeometry.distanceToSegment(PixelPoint(50f, 20f), a, b), 0.001f)
        // Beyond an endpoint the closest point is the endpoint, not the projection.
        assertEquals(50f, DrawingGeometry.distanceToSegment(PixelPoint(150f, 0f), a, b), 0.001f)
        assertEquals(
            DrawingGeometry.distance(PixelPoint(-30f, 40f), a),
            DrawingGeometry.distanceToSegment(PixelPoint(-30f, 40f), a, b),
            0.001f
        )
        // A zero-length segment must not divide by zero.
        assertEquals(5f, DrawingGeometry.distanceToSegment(PixelPoint(3f, 4f), a, a), 0.001f)
    }

    @Test
    fun `angle snapping lands on the nearest octant and leaves a tiny drag alone`() {
        val anchor = PixelPoint(0f, 0f)
        // atan2(9,10) ≈ 0.733 rad rounds to π/4, at radius √181.
        val snapped = DrawingGeometry.snapAngle(anchor, PixelPoint(10f, 9f))
        assertEquals(9.514f, snapped.x, 0.01f)
        assertEquals(9.514f, snapped.y, 0.01f)

        // A near-horizontal drag snaps flat, which is what makes hand-drawn
        // horizontal levels usable.
        val flat = DrawingGeometry.snapAngle(anchor, PixelPoint(100f, 10f))
        assertEquals(0f, flat.y, 0.01f)

        // WHY sub-pixel drags are returned untouched: atan2(0,0) is 0, so snapping a
        // stationary finger would jump it to the anchor's right and place a phantom
        // object one pixel wide.
        val tiny = PixelPoint(0.4f, 0.3f)
        assertSame(tiny, DrawingGeometry.snapAngle(anchor, tiny))
    }

    @Test
    fun `point-in-box grows the box by the tolerance`() {
        val first = PixelPoint(10f, 10f)
        val second = PixelPoint(50f, 40f)
        assertTrue(DrawingGeometry.pointInBox(PixelPoint(30f, 20f), first, second, 0f))
        assertTrue(DrawingGeometry.pointInBox(PixelPoint(55f, 20f), first, second, 10f))
        assertFalse(DrawingGeometry.pointInBox(PixelPoint(80f, 20f), first, second, 10f))
        // Corners given in the other order must behave identically.
        assertTrue(DrawingGeometry.pointInBox(PixelPoint(30f, 20f), second, first, 0f))
    }

    @Test
    fun `fib levels count from the first anchor, whichever way it was dragged`() {
        val fractions = listOf(0.0, 0.236, 0.5, 1.0)
        val up = DrawingGeometry.fibLevels(startPrice = 1.0, endPrice = 2.0, fractions = fractions)
        assertEquals(1.0, up[0].price, 1e-9)
        assertEquals(1.236, up[1].price, 1e-9)
        assertEquals(1.5, up[2].price, 1e-9)
        assertEquals(2.0, up[3].price, 1e-9)

        // WHY (regression): counting 0 % from the higher price instead of the first
        // anchor mirrors every level, so a 61.8 % retracement of an up-move would be
        // drawn where the 38.2 % belongs.
        val down = DrawingGeometry.fibLevels(startPrice = 2.0, endPrice = 1.0, fractions = fractions)
        assertEquals(2.0, down[0].price, 1e-9)
        assertEquals(1.0, down[3].price, 1e-9)
        assertTrue(down[1].price < down[0].price)

        assertEquals("23.6 %", up[1].label)
        assertEquals("0.0 %", up[0].label)
    }

    @Test
    fun `measurement stats report price, percent, pips, bars and duration`() {
        val start = point(0L, 1.00000)
        val end = point(3 * 3_600_000L, 1.00500)
        val stats = DrawingGeometry.stats(start, end, timeframe, instrument)

        assertEquals(0.005, stats.deltaPrice, 1e-9)
        assertEquals(0.5, stats.deltaPercent, 1e-9)
        assertEquals(3L, stats.bars)
        assertEquals("3 h", stats.durationText)
        assertTrue(stats.isUpward)

        val summary = stats.summary(instrument)
        // WHY these substrings: the readout is the whole point of the measure tool, so
        // it must carry the price move, the percentage, the pip count and the bars.
        assertTrue(summary, summary.contains("+0.00500"))
        assertTrue(summary, summary.contains("+0.50 %"))
        assertTrue(summary, summary.contains("+50.0 pips"))
        assertTrue(summary, summary.contains("3 bars"))

        // A downward measurement is not reported as upward.
        val down = DrawingGeometry.stats(end, start, timeframe, instrument)
        assertFalse(down.isUpward)
        // WHY positive: a measurement reports a magnitude, and a negative bar count
        // would read as "minus three bars" on the chart.
        assertEquals(3L, down.bars)
        assertEquals("3 h", down.durationText)
        // A zero start price must not divide by zero.
        assertEquals(0.0, DrawingGeometry.stats(point(0L, 0.0), end, timeframe, instrument).deltaPercent, 0.0)
        // An unknown instrument still produces a summary, just without pips.
        assertTrue(DrawingGeometry.stats(start, end, timeframe, null).summary(null).contains("3 bars"))
    }

    @Test
    fun `a projected ray and extended line reach the pane edges`() {
        // WHY this slope: 0.25 per 200 ms means the ray reaches exactly 2.0 at the
        // right edge of the pane, so the expected coordinate is a round number.
        val start = point(200L, 1.0)
        val end = point(400L, 1.25)

        val plain = trendLine(start, end)
        val plainSegment = requireNotNull(DrawingGeometry.projectedSegment(plain, coords))
        assertEquals(coords.timeToX(start.time), plainSegment.first.x, 0.001f)
        assertEquals(coords.timeToX(end.time), plainSegment.second.x, 0.001f)

        val ray = trendLine(start, end, extendRight = true)
        val raySegment = requireNotNull(DrawingGeometry.projectedSegment(ray, coords))
        assertEquals(coords.rect.rightEdge, raySegment.second.x, 0.001f)
        // WHY this is the assertion that matters: extending must preserve the slope.
        // t=1000 is 800 ms past the first anchor, so the price rises 1.0 to 2.0 and
        // lands exactly on the top edge.
        assertEquals(coords.priceToY(2.0), raySegment.second.y, 0.001f)
        assertEquals(coords.rect.topEdge, raySegment.second.y, 0.001f)

        val extended = trendLine(start, end, extendLeft = true, extendRight = true)
        val extendedSegment = requireNotNull(DrawingGeometry.projectedSegment(extended, coords))
        assertEquals(coords.rect.leftEdge, extendedSegment.first.x, 0.001f)
        // Backwards 200 ms from the anchor: 1.0 − 0.25 = 0.75, below the pane floor.
        assertEquals(coords.priceToY(0.75), extendedSegment.first.y, 0.001f)
        assertEquals(coords.rect.rightEdge, extendedSegment.second.x, 0.001f)

        // A vertical trendline has no slope, so projection must decline rather than
        // produce Infinity.
        val vertical = trendLine(point(200L, 1.0), point(200L, 1.5), extendRight = true)
        assertNull(DrawingGeometry.projectedSegment(vertical, coords))
    }

    // ---------- hit testing ----------

    @Test
    fun `a horizontal line is grabbable along its length but not above it`() {
        val line = ChartDrawing.HorizontalLine(
            id = DrawingId.generate(), pairSymbol = pair, timeframe = timeframe,
            style = DrawingStyle.SOLID, colorArgb = DrawingPalette.DEFAULT,
            createdAt = createdAt, label = "", price = 1.5
        )
        val onLine = PixelPoint(350f, coords.priceToY(1.5))
        assertTrue(DrawingGeometry.hitTest(line, coords, onLine))
        assertTrue(DrawingGeometry.hitTest(line, coords, PixelPoint(350f, onLine.y + 10f)))
        assertFalse(DrawingGeometry.hitTest(line, coords, PixelPoint(350f, onLine.y + 40f)))
        // A tighter tolerance must reject what the default accepted.
        assertFalse(DrawingGeometry.hitTest(line, coords, PixelPoint(350f, onLine.y + 10f), tolerancePx = 4f))
    }

    @Test
    fun `a vertical line is grabbable at its time only`() {
        val line = ChartDrawing.VerticalLine(
            id = DrawingId.generate(), pairSymbol = pair, timeframe = timeframe,
            style = DrawingStyle.DASHED, colorArgb = DrawingPalette.DEFAULT,
            createdAt = createdAt, label = "NFP", time = Instant.ofEpochMilli(500L)
        )
        val x = coords.timeToX(500L)
        assertTrue(DrawingGeometry.hitTest(line, coords, PixelPoint(x, 10f)))
        assertTrue(DrawingGeometry.hitTest(line, coords, PixelPoint(x, 190f)))
        assertFalse(DrawingGeometry.hitTest(line, coords, PixelPoint(x + 40f, 100f)))
        assertEquals("Vertical line", line.description)
        assertEquals(listOf(line.time), line.anchorTimes)
        assertTrue(line.anchorPrices.isEmpty())
    }

    @Test
    fun `a trendline is grabbable on the line and not inside its bounding box`() {
        val line = trendLine(point(0L, 1.0), point(1_000L, 2.0))
        // On the diagonal: x=200 ↔ t=500 ↔ price 1.5 ↔ y=100.
        assertTrue(DrawingGeometry.hitTest(line, coords, PixelPoint(200f, 100f)))
        // 8 px below the line — inside a finger's width, so still grabbable.
        assertTrue(DrawingGeometry.hitTest(line, coords, PixelPoint(200f, 108f)))
        // WHY these are the important cases: a bounding-box hit-test would accept both
        // corners, which makes two crossing lines impossible to select individually.
        assertFalse(DrawingGeometry.hitTest(line, coords, PixelPoint(20f, 20f)))
        assertFalse(DrawingGeometry.hitTest(line, coords, PixelPoint(380f, 180f)))
    }

    @Test
    fun `a rectangle is grabbable by its outline and not by its filled interior`() {
        val rectangle = ChartDrawing.Rectangle(
            id = DrawingId.generate(), pairSymbol = pair, timeframe = timeframe,
            style = DrawingStyle.SOLID, colorArgb = DrawingPalette.DEFAULT,
            createdAt = createdAt, label = "zone",
            start = point(100L, 1.2), end = point(600L, 1.8)
        )
        val left = coords.timeToX(100L)
        val right = coords.timeToX(600L)
        val top = coords.priceToY(1.8)
        val bottom = coords.priceToY(1.2)

        assertTrue(DrawingGeometry.hitTest(rectangle, coords, PixelPoint(left, (top + bottom) / 2f)))
        assertTrue(DrawingGeometry.hitTest(rectangle, coords, PixelPoint((left + right) / 2f, top)))
        assertFalse(DrawingGeometry.hitTest(rectangle, coords, PixelPoint((left + right) / 2f, (top + bottom) / 2f)))
        assertFalse(DrawingGeometry.hitTest(rectangle, coords, PixelPoint(right + 60f, top)))
    }

    @Test
    fun `a fib is grabbable on any of its levels inside the anchor span`() {
        val fib = ChartDrawing.FibRetracement(
            id = DrawingId.generate(), pairSymbol = pair, timeframe = timeframe,
            style = DrawingStyle.SOLID, colorArgb = DrawingPalette.DEFAULT,
            createdAt = createdAt, label = "",
            start = point(100L, 1.0), end = point(700L, 2.0)
        )
        val insideX = coords.timeToX(400L)
        // 50 % level → price 1.5.
        assertTrue(DrawingGeometry.hitTest(fib, coords, PixelPoint(insideX, coords.priceToY(1.5))))
        // 61.8 % level → price 1.618.
        assertTrue(DrawingGeometry.hitTest(fib, coords, PixelPoint(insideX, coords.priceToY(1.618))))
        // WHY a tight tolerance for the "between levels" case: at the default finger
        // tolerance (18 px) the seven levels of a full-height fan are closer together
        // than the tolerance, so the whole fan is grabbable — deliberately, because a
        // finger cannot aim between them. With a precise pointer the gaps are real.
        assertFalse(DrawingGeometry.hitTest(fib, coords, PixelPoint(insideX, coords.priceToY(1.56)), tolerancePx = 4f))
        assertTrue(DrawingGeometry.hitTest(fib, coords, PixelPoint(insideX, coords.priceToY(1.56))))
        // Outside the measured span, no level is grabbable however close in price.
        assertFalse(DrawingGeometry.hitTest(fib, coords, PixelPoint(coords.timeToX(900L), coords.priceToY(1.5))))
    }

    @Test
    fun `a measurement is grabbable on its outline or its diagonal`() {
        val measurement = ChartDrawing.Measurement(
            id = DrawingId.generate(), pairSymbol = pair, timeframe = timeframe,
            style = DrawingStyle.SOLID, colorArgb = DrawingPalette.DEFAULT,
            createdAt = createdAt, label = "",
            start = point(100L, 1.2), end = point(600L, 1.8)
        )
        val midX = (coords.timeToX(100L) + coords.timeToX(600L)) / 2f
        val midY = (coords.priceToY(1.2) + coords.priceToY(1.8)) / 2f
        assertTrue(DrawingGeometry.hitTest(measurement, coords, PixelPoint(midX, midY)))
        assertTrue(DrawingGeometry.hitTest(measurement, coords, PixelPoint(coords.timeToX(100L), midY)))
        assertFalse(DrawingGeometry.hitTest(measurement, coords, PixelPoint(coords.timeToX(900L), 10f)))
    }

    @Test
    fun `a position is grabbable on its entry, stop or target`() {
        val position = ChartDrawing.Position(
            id = DrawingId.generate(), pairSymbol = pair, timeframe = timeframe,
            style = DrawingStyle.SOLID, colorArgb = DrawingPalette.LONG,
            createdAt = createdAt, label = "",
            side = TradeSide.LONG, entryPrice = 1.5, stopPrice = 1.4, targetPrice = 1.7
        )
        assertTrue(DrawingGeometry.hitTest(position, coords, PixelPoint(50f, coords.priceToY(1.5))))
        assertTrue(DrawingGeometry.hitTest(position, coords, PixelPoint(50f, coords.priceToY(1.4))))
        assertTrue(DrawingGeometry.hitTest(position, coords, PixelPoint(50f, coords.priceToY(1.7))))
        // 1.60 sits 20 px from every one of the three levels — outside the tolerance.
        assertFalse(DrawingGeometry.hitTest(position, coords, PixelPoint(50f, coords.priceToY(1.60))))
        assertEquals(listOf(1.5, 1.4, 1.7), position.anchorPrices)
        assertTrue(position.anchorTimes.isEmpty())
        assertEquals(0.1, position.riskPerUnit, 1e-9)
        assertEquals(0.2, position.rewardPerUnit, 1e-9)
        assertEquals(2.0, position.riskRewardRatio, 1e-9)
        assertEquals("Long position", position.description)
    }

    @Test
    fun `magnet snapping pulls a price onto the nearest candle extreme only when close`() {
        val series = listOf(
            CandleStick(Instant.ofEpochMilli(500L), 1.40, 1.60, 1.30, 1.50, 10L)
        )

        // Off: no snapping at all.
        assertEquals(1.55, DrawingGeometry.magnetSnap(1.55, 500L, series, coords, MagnetMode.NONE), 1e-9)

        // Weak: 1.55 is 10px from the high (1.60 → y=80, 1.55 → y=90), inside the
        // 14px tolerance, so it snaps.
        assertEquals(1.60, DrawingGeometry.magnetSnap(1.55, 500L, series, coords, MagnetMode.WEAK), 1e-9)
        // Weak: 1.20 is far from every extreme of that bar, so it stays put.
        assertEquals(1.20, DrawingGeometry.magnetSnap(1.20, 500L, series, coords, MagnetMode.WEAK), 1e-9)

        // Strong: any price snaps to the nearest extreme, however far away.
        assertEquals(1.30, DrawingGeometry.magnetSnap(1.20, 500L, series, coords, MagnetMode.STRONG), 1e-9)
        assertEquals(1.60, DrawingGeometry.magnetSnap(1.99, 500L, series, coords, MagnetMode.STRONG), 1e-9)

        // An empty series has nothing to snap to.
        assertEquals(1.55, DrawingGeometry.magnetSnap(1.55, 500L, emptyList(), coords, MagnetMode.STRONG), 1e-9)
        // WHY the snap is measured in pixels, not price: 0.05 means 10 px on a 5-digit
        // pair and a fraction of a pixel on BTC, so a tolerance expressed in price would
        // be unusable on one instrument or the other.
        assertTrue(
            Math.abs(coords.priceToY(1.55) - coords.priceToY(1.60)) <= MagnetMode.WEAK.tolerancePx
        )
    }

    @Test
    fun `pixel to data conversion round-trips`() {
        val pixel = PixelPoint(123f, 77f)
        val data = DrawingGeometry.pointFromPixel(pixel, coords)
        assertEquals(123L * 1_000L / 400L, data.time.toEpochMilli())
        assertEquals(coords.yToPrice(77f), data.price, 1e-9)
        // WHY a half-pixel tolerance on x and an exact one on y: time is quantized to
        // whole milliseconds on the way out (`toLong`), and in this fixture one
        // millisecond is 0.4 px, so the round trip can land a fraction of a pixel away.
        // Price carries no such quantization, so it must come back exactly — a drifted
        // price is what makes a drawn level sit visibly off the candle it was aimed at.
        assertEquals(pixel.x, coords.timeToX(data.time), 0.5f)
        assertEquals(pixel.y, coords.priceToY(data.price), 0.001f)
    }

    // ---------- DrawingStore ----------

    @Test
    fun `the store scopes drawings by pair and timeframe`() {
        val store = DrawingStore()
        val eurusdH1 = horizontalLine(pair, timeframe)
        val eurusdD1 = horizontalLine(pair, Timeframe("D1"), price = 1.6)
        val gbpusdH1 = horizontalLine(PairSymbol("GBPUSD"), timeframe, price = 1.7)

        store.commit(eurusdH1)
        store.commit(eurusdD1)
        store.commit(gbpusdH1)

        assertEquals(3, store.size)
        // WHY: an H1 level drawn on EURUSD must not appear on the D1 chart of the same
        // pair, nor on the H1 chart of another pair — in both cases the level describes
        // bars the user is not looking at.
        assertEquals(listOf(eurusdH1), store.currentFor(pair, timeframe))
        assertEquals(listOf(eurusdD1), store.currentFor(pair, Timeframe("D1")))
        assertEquals(listOf(gbpusdH1), store.currentFor(PairSymbol("GBPUSD"), timeframe))
        assertTrue(store.currentFor(PairSymbol("USDJPY"), timeframe).isEmpty())
    }

    @Test
    fun `undo and redo walk the snapshot history and report availability`() {
        val store = DrawingStore()
        assertFalse(store.historyState.value.canUndo)
        assertFalse(store.historyState.value.canRedo)

        val first = horizontalLine(pair, timeframe, price = 1.1)
        val second = horizontalLine(pair, timeframe, price = 1.2)
        store.commit(first)
        store.commit(second)
        assertEquals(2, store.size)
        assertTrue(store.historyState.value.canUndo)
        assertFalse(store.historyState.value.canRedo)

        assertTrue(store.undo())
        assertEquals(listOf(first), store.currentFor(pair, timeframe))
        assertTrue(store.historyState.value.canRedo)

        assertTrue(store.redo())
        assertEquals(listOf(first, second), store.currentFor(pair, timeframe))
        assertFalse(store.historyState.value.canRedo)

        // Walking past either end is refused, not wrapped.
        assertTrue(store.undo())
        assertTrue(store.undo())
        assertFalse(store.undo())
        assertEquals(0, store.size)
    }

    @Test
    fun `a commit after an undo discards the redo branch`() {
        val store = DrawingStore()
        val first = horizontalLine(pair, timeframe, price = 1.1)
        val second = horizontalLine(pair, timeframe, price = 1.2)
        val third = horizontalLine(pair, timeframe, price = 1.3)

        store.commit(first)
        store.commit(second)
        store.undo()
        store.commit(third)

        // WHY: "redo" into a state that no longer contains the object just added is
        // the classic editor bug this asserts against.
        assertFalse(store.historyState.value.canRedo)
        assertEquals(listOf(first, third), store.currentFor(pair, timeframe))
    }

    @Test
    fun `an idempotent delete or clear does not consume an undo step`() {
        val store = DrawingStore()
        val drawing = horizontalLine(pair, timeframe)
        store.commit(drawing)
        val depthAfterCommit = store.historyDepth

        store.delete(DrawingId.generate())
        assertEquals("a delete of an unknown id pushed a snapshot", depthAfterCommit, store.historyDepth)

        store.clearFor(PairSymbol("USDJPY"), timeframe)
        assertEquals(depthAfterCommit, store.historyDepth)

        store.delete(drawing.id)
        assertEquals(depthAfterCommit + 1, store.historyDepth)
        assertEquals(0, store.size)

        // Deleting the same object twice must not add a second step either.
        store.delete(drawing.id)
        assertEquals(depthAfterCommit + 1, store.historyDepth)
    }

    @Test
    fun `replaceAllFor reconciles without duplicating and without a no-op snapshot`() {
        val store = DrawingStore()
        val persisted = listOf(
            horizontalLine(pair, timeframe, price = 1.1),
            horizontalLine(pair, timeframe, price = 1.2)
        )
        val otherChart = horizontalLine(PairSymbol("GBPUSD"), timeframe, price = 1.3)
        store.commit(otherChart)

        store.replaceAllFor(pair, timeframe, persisted)
        assertEquals(persisted, store.currentFor(pair, timeframe))
        assertEquals(listOf(otherChart), store.currentFor(PairSymbol("GBPUSD"), timeframe))

        // WHY: this is the path our own database write takes on the way back, so it
        // must not push a history step — otherwise every save would consume an undo.
        val depth = store.historyDepth
        store.replaceAllFor(pair, timeframe, persisted)
        assertEquals(depth, store.historyDepth)
        assertEquals(persisted, store.currentFor(pair, timeframe))
    }

    @Test
    fun `the store bounds both the drawing count and the history depth`() {
        val store = DrawingStore()
        repeat(DrawingStore.MAX_DRAWINGS + 50) { index ->
            store.commit(horizontalLine(pair, timeframe, price = 1.0 + index * 0.0001))
        }
        assertTrue(store.size <= DrawingStore.MAX_DRAWINGS)
        // WHY drop the oldest rather than reject the newest: a chart that refuses the
        // user's next line is worse than one that forgets a line from an hour ago.
        assertEquals(DrawingStore.MAX_DRAWINGS, store.currentFor(pair, timeframe).size)

        assertTrue(store.historyDepth <= DrawingStore.MAX_HISTORY)
    }

    // ---------- DrawingFactory ----------

    @Test
    fun `every tool builds the matching drawing type`() {
        val start = point(0L, 1.0)
        val end = point(500L, 1.5)

        val horizontal = requireNotNull(create(ChartToolMode.HORIZONTAL_LINE, start, end))
        assertTrue(horizontal is ChartDrawing.HorizontalLine)
        assertEquals(start.price, (horizontal as ChartDrawing.HorizontalLine).price, 1e-9)

        val vertical = requireNotNull(create(ChartToolMode.VERTICAL_LINE, start, end))
        assertTrue(vertical is ChartDrawing.VerticalLine)
        assertEquals(start.time, (vertical as ChartDrawing.VerticalLine).time)

        val trend = requireNotNull(create(ChartToolMode.TREND_LINE, start, end)) as ChartDrawing.TrendLine
        assertFalse(trend.extendLeft)
        assertFalse(trend.extendRight)

        val ray = requireNotNull(create(ChartToolMode.RAY, start, end)) as ChartDrawing.TrendLine
        assertFalse(ray.extendLeft)
        assertTrue(ray.extendRight)

        val extended = requireNotNull(create(ChartToolMode.EXTENDED_LINE, start, end)) as ChartDrawing.TrendLine
        assertTrue(extended.extendLeft)
        assertTrue(extended.extendRight)

        assertTrue(create(ChartToolMode.FIBONACCI, start, end) is ChartDrawing.FibRetracement)
        assertTrue(create(ChartToolMode.FIB_EXTENSION, start, end) is ChartDrawing.FibExtension)
        assertTrue(create(ChartToolMode.RECTANGLE, start, end) is ChartDrawing.Rectangle)
        assertTrue(create(ChartToolMode.MEASURE, start, end) is ChartDrawing.Measurement)

        // WHY the fib level sets differ: a retracement's levels live inside the swing
        // (0..1) and an extension's run past it (up to 261.8 %).
        val fib = create(ChartToolMode.FIBONACCI, start, end) as ChartDrawing.FibRetracement
        assertEquals(ChartDrawing.FibRetracement.DEFAULT_LEVELS, fib.levels)
        val fibExtension = create(ChartToolMode.FIB_EXTENSION, start, end) as ChartDrawing.FibExtension
        assertEquals(ChartDrawing.FibExtension.DEFAULT_LEVELS, fibExtension.levels)
        assertTrue(fibExtension.levels.max() > 1.0)
    }

    @Test
    fun `navigation modes build nothing and a zero-length drag is refused`() {
        val anchor = point(100L, 1.2)
        assertNull(create(ChartToolMode.NONE, anchor, anchor))
        assertNull(create(ChartToolMode.PAN, anchor, anchor))

        // WHY refused rather than nudged apart: a two-point object with no extent has
        // no slope, so it renders as a dot and can never be extended or edited.
        ChartToolMode.entries.filter { it.isTwoAnchor }.forEach { tool ->
            assertNull("expected $tool to reject a zero-length drag", create(tool, anchor, anchor))
        }
        // One-anchor tools do not need an extent.
        assertNotNull(create(ChartToolMode.HORIZONTAL_LINE, anchor, anchor))
        assertNotNull(create(ChartToolMode.VERTICAL_LINE, anchor, anchor))
    }

    @Test
    fun `a position is valid whichever way the user drags`() {
        val entry = point(0L, 1.50000)

        val draggedDown = requireNotNull(create(ChartToolMode.LONG_POSITION, entry, point(500L, 1.48)))
            as ChartDrawing.Position
        assertEquals(TradeSide.LONG, draggedDown.side)
        assertEquals(1.50, draggedDown.entryPrice, 1e-9)
        assertEquals(1.48, draggedDown.stopPrice, 1e-9)
        assertEquals(1.52, draggedDown.targetPrice, 1e-9)
        assertEquals(1.0, draggedDown.riskRewardRatio, 1e-9)
        assertEquals(ChartDrawing.Position.DEFAULT_RISK_PERCENT, draggedDown.riskPercent, 1e-9)

        // WHY the absolute distance: dragging *up* while a long is armed still means
        // "this much risk"; producing an invalid long (stop above entry) would throw,
        // and refusing the gesture would feel like a broken tool.
        val draggedUp = requireNotNull(create(ChartToolMode.LONG_POSITION, entry, point(500L, 1.52)))
            as ChartDrawing.Position
        assertEquals(1.48, draggedUp.stopPrice, 1e-9)
        assertEquals(1.52, draggedUp.targetPrice, 1e-9)
        assertTrue(draggedUp.stopPrice < draggedUp.entryPrice)
        assertTrue(draggedUp.targetPrice > draggedUp.entryPrice)

        val short = requireNotNull(create(ChartToolMode.SHORT_POSITION, entry, point(500L, 1.52)))
            as ChartDrawing.Position
        assertEquals(TradeSide.SHORT, short.side)
        assertEquals(1.52, short.stopPrice, 1e-9)
        assertEquals(1.48, short.targetPrice, 1e-9)
        assertTrue(short.stopPrice > short.entryPrice)
    }

    @Test
    fun `the toolbar style and colour reach the created object`() {
        val created = requireNotNull(
            DrawingFactory.create(
                tool = ChartToolMode.TREND_LINE,
                start = point(0L, 1.0),
                end = point(500L, 1.5),
                pairSymbol = pair,
                timeframe = timeframe,
                style = DrawingStyle.DOTTED,
                colorArgb = DrawingPalette.COLORS[3],
                createdAt = createdAt,
                label = "supply"
            )
        )
        assertEquals(DrawingStyle.DOTTED, created.style)
        assertEquals(DrawingPalette.COLORS[3], created.colorArgb)
        assertEquals("supply", created.label)
        assertEquals(createdAt, created.createdAt)
        assertEquals(pair, created.pairSymbol)
        assertEquals(timeframe, created.timeframe)
        assertNotEquals(DrawingId.generate(), created.id)
    }

    @Test
    fun `every palette swatch carries a name a screen reader can announce`() {
        assertEquals(DrawingPalette.SWATCHES.size, DrawingPalette.COLORS.size)
        DrawingPalette.SWATCHES.forEach { swatch ->
            assertTrue(swatch.name.isNotBlank())
            assertNotEquals(0, swatch.argb)
        }
        assertEquals(DrawingPalette.SWATCHES.map { it.argb }, DrawingPalette.COLORS)
        assertTrue(DrawingPalette.DEFAULT in DrawingPalette.COLORS)
        // WHY: the position tool paints risk and reward from these two, so they must
        // be distinguishable — an identical long/short colour makes the planner
        // useless at a glance.
        assertNotEquals(DrawingPalette.LONG, DrawingPalette.SHORT)
        assertEquals(0, DrawingPalette.FIB_LEVELS.indexOf(DrawingPalette.FIB_LEVELS.first()))
        assertTrue(DrawingPalette.FIB_LEVELS.size >= ChartDrawing.FibRetracement.DEFAULT_LEVELS.size)
    }

    @Test
    fun `drawing labels and fib levels are validated at construction`() {
        val longLabel = "x".repeat(ChartDrawing.MAX_LABEL_LENGTH + 1)
        assertThrows(IllegalArgumentException::class.java) {
            horizontalLine(pair, timeframe, label = longLabel)
        }
        // The limit itself is accepted — only past it is rejected.
        assertNotNull(horizontalLine(pair, timeframe, label = "x".repeat(ChartDrawing.MAX_LABEL_LENGTH)))
        assertThrows(IllegalArgumentException::class.java) {
            ChartDrawing.FibRetracement(
                id = DrawingId.generate(), pairSymbol = pair, timeframe = timeframe,
                style = DrawingStyle.SOLID, colorArgb = DrawingPalette.DEFAULT,
                createdAt = createdAt, label = "",
                start = point(0L, 1.0), end = point(500L, 2.0),
                levels = listOf(0.5, 0.2) // not ascending
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChartDrawing.FibRetracement(
                id = DrawingId.generate(), pairSymbol = pair, timeframe = timeframe,
                style = DrawingStyle.SOLID, colorArgb = DrawingPalette.DEFAULT,
                createdAt = createdAt, label = "",
                start = point(0L, 1.0), end = point(500L, 2.0),
                levels = listOf(0.5, 1.4) // outside 0..1 for a retracement
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            // A long whose stop sits above its entry is a contradiction that would
            // report a negative risk/reward.
            ChartDrawing.Position(
                id = DrawingId.generate(), pairSymbol = pair, timeframe = timeframe,
                style = DrawingStyle.SOLID, colorArgb = DrawingPalette.LONG,
                createdAt = createdAt, label = "",
                side = TradeSide.LONG, entryPrice = 1.5, stopPrice = 1.6, targetPrice = 1.7
            )
        }
    }

    // ---------- fixtures ----------

    private fun create(tool: ChartToolMode, start: ChartPoint, end: ChartPoint) = DrawingFactory.create(
        tool = tool,
        start = start,
        end = end,
        pairSymbol = pair,
        timeframe = timeframe,
        style = DrawingStyle.SOLID,
        colorArgb = DrawingPalette.DEFAULT,
        createdAt = createdAt
    )

    private fun trendLine(
        start: ChartPoint,
        end: ChartPoint,
        extendLeft: Boolean = false,
        extendRight: Boolean = false
    ) = ChartDrawing.TrendLine(
        id = DrawingId.generate(),
        pairSymbol = pair,
        timeframe = timeframe,
        style = DrawingStyle.SOLID,
        colorArgb = DrawingPalette.DEFAULT,
        createdAt = createdAt,
        label = "",
        start = start,
        end = end,
        extendLeft = extendLeft,
        extendRight = extendRight
    )

    private fun horizontalLine(
        symbol: PairSymbol = pair,
        tf: Timeframe = timeframe,
        price: Double = 1.5,
        label: String = ""
    ) = ChartDrawing.HorizontalLine(
        id = DrawingId.generate(),
        pairSymbol = symbol,
        timeframe = tf,
        style = DrawingStyle.SOLID,
        colorArgb = DrawingPalette.DEFAULT,
        createdAt = createdAt,
        label = label,
        price = price
    )
}
