package com.devcode940.ForexAnalyst.data.chart

import com.devcode940.ForexAnalyst.data.model.CandleStick
import com.devcode940.ForexAnalyst.data.model.ChartDrawing
import com.devcode940.ForexAnalyst.data.model.ChartPoint
import com.devcode940.ForexAnalyst.data.model.Instrument
import com.devcode940.ForexAnalyst.data.model.MagnetMode
import com.devcode940.ForexAnalyst.data.model.PriceFormatter
import com.devcode940.ForexAnalyst.data.model.Timeframe
import java.time.Instant
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin

/** A point in canvas pixels. */
data class PixelPoint(val x: Float, val y: Float)

/** One resolved Fibonacci level. */
data class FibLevel(val fraction: Double, val price: Double) {
    /** Label such as `23.6 %` — used both on the chart and in the object list. */
    val label: String get() = String.format(Locale.US, "%.1f %%", fraction * 100.0)
}

/** Statistics of a two-anchor drawing, in data space. */
data class TrendStats(
    val deltaPrice: Double,
    val deltaPercent: Double,
    val bars: Long,
    val durationText: String,
    val isUpward: Boolean
) {
    /** One-line readout shown while the drawing is selected or being dragged. */
    fun summary(instrument: Instrument?): String {
        val digits = instrument?.quoteDigits ?: PriceFormatter.DEFAULT_DIGITS
        val direction = if (isUpward) "+" else ""
        val pips = instrument?.let { PriceFormatter.formatPips(deltaPrice, it) } ?: ""
        return buildString {
            append(direction).append(PriceFormatter.format(deltaPrice, digits))
            append("  (").append(String.format(Locale.US, "%+.2f %%", deltaPercent)).append(")")
            if (pips.isNotEmpty()) append("  ").append(pips)
            append("  ").append(bars).append(" bars / ").append(durationText)
        }
    }
}

/**
 * Pure geometry for chart drawings: level resolution, measurement, hit-testing and
 * magnet snapping.
 *
 * WHY pure and pixel-explicit: drawing interaction is the easiest place to ship a
 * subtle bug that is invisible in a screenshot (a line you cannot grab, a fib level
 * one bar off). Every function here takes plain numbers and returns plain numbers,
 * so each rule is unit-testable without Compose, a device or a screenshot diff.
 *
 * The hit-testing rules (distance-to-segment, 45° angle snapping, tolerance boxes)
 * follow the same geometry used by open-source web charting plugins, so the tools
 * behave the way a trader expects from TradingView-style editors.
 */
object DrawingGeometry {

    /** Straight-line pixel distance. */
    fun distance(a: PixelPoint, b: PixelPoint): Float = hypot(b.x - a.x, b.y - a.y)

    /**
     * Shortest pixel distance from [p] to the segment `a→b`.
     *
     * WHY: hit-testing a trendline against its *bounding box* would make the whole
     * rectangle around a diagonal line grabbable, which feels broken as soon as two
     * drawings cross. Projecting onto the segment is what makes thin lines selectable.
     */
    fun distanceToSegment(p: PixelPoint, a: PixelPoint, b: PixelPoint): Float {
        val abx = b.x - a.x
        val aby = b.y - a.y
        val lengthSquared = abx * abx + aby * aby
        // WHY: a zero-length segment (both anchors on the same pixel) would divide
        // by zero; fall back to the point distance.
        if (lengthSquared == 0f) return distance(p, a)
        val t = ((p.x - a.x) * abx + (p.y - a.y) * aby) / lengthSquared
        val projection = PixelPoint(a.x + t.coerceIn(0f, 1f) * abx, a.y + t.coerceIn(0f, 1f) * aby)
        return distance(p, projection)
    }

    /**
     * Constrains [p] to the nearest 45° increment around [anchor].
     *
     * WHY: drawing a clean 45° channel by finger is impossible; snapping to octants
     * is the conventional assist and keeps diagonal lines visually consistent.
     */
    fun snapAngle(anchor: PixelPoint, p: PixelPoint): PixelPoint {
        val dx = p.x - anchor.x
        val dy = p.y - anchor.y
        val radius = hypot(dx, dy)
        // WHY: below one pixel the angle is meaningless (atan2(0,0) = 0) and the
        // user has not really dragged yet — return the raw point.
        if (radius < 1f) return p
        val step = Math.PI / 4.0
        val angle = round(atan2(dy.toDouble(), dx.toDouble()) / step) * step
        return PixelPoint(
            x = anchor.x + (radius * cos(angle)).toFloat(),
            y = anchor.y + (radius * sin(angle)).toFloat()
        )
    }

    /** True when [p] is inside the box spanned by two corners, grown by [tolerancePx]. */
    fun pointInBox(p: PixelPoint, first: PixelPoint, second: PixelPoint, tolerancePx: Float): Boolean {
        val minX = min(first.x, second.x) - tolerancePx
        val maxX = max(first.x, second.x) + tolerancePx
        val minY = min(first.y, second.y) - tolerancePx
        val maxY = max(first.y, second.y) + tolerancePx
        return p.x in minX..maxX && p.y in minY..maxY
    }

    /**
     * Resolves Fibonacci fractions to prices.
     *
     * WHY direction-aware: a retracement drawn from a swing low to a swing high must
     * count 0 % at the low, while the same two anchors dragged the other way count
     * 0 % at the high. Deriving the levels from the anchor order (rather than always
     * from the higher price) matches every mainstream charting editor.
     */
    fun fibLevels(startPrice: Double, endPrice: Double, fractions: List<Double>): List<FibLevel> {
        val span = endPrice - startPrice
        return fractions.map { fraction -> FibLevel(fraction, startPrice + span * fraction) }
    }

    /**
     * Measurement readout between two anchors.
     *
     * @param start first anchor (drag start).
     * @param end second anchor (drag end).
     * @param timeframe used to convert elapsed time into bars.
     * @param instrument used for decimal places and pip conversion.
     */
    fun stats(start: ChartPoint, end: ChartPoint, timeframe: Timeframe, instrument: Instrument?): TrendStats {
        val delta = end.price - start.price
        val percent = if (start.price == 0.0) 0.0 else delta / start.price * 100.0
        return TrendStats(
            deltaPrice = delta,
            deltaPercent = percent,
            bars = TimeFormatter.barsBetween(start.time, end.time, timeframe),
            durationText = TimeFormatter.duration(start.time, end.time),
            isUpward = delta >= 0.0
        )
    }

    /**
     * Projects a trendline to the pane edges, honouring the extend flags.
     *
     * WHY: a ray/extended line is stored as two anchors plus flags; the renderer and
     * the hit-tester both need the *effective* segment, so the projection lives here
     * once instead of being duplicated (and diverging) in two places.
     *
     * @return the segment to draw/hit-test, or null when the line is vertical and
     *   cannot be projected by slope.
     */
    fun projectedSegment(
        line: ChartDrawing.TrendLine,
        cs: ChartCoordinateSystem
    ): Pair<PixelPoint, PixelPoint>? {
        val a = PixelPoint(cs.timeToX(line.start.time), cs.priceToY(line.start.price))
        val b = PixelPoint(cs.timeToX(line.end.time), cs.priceToY(line.end.price))
        if (!line.extendLeft && !line.extendRight) return a to b

        val slope = line.slopePerMillis ?: return null
        val leftTime = cs.xToTime(cs.rect.leftEdge)
        val rightTime = cs.xToTime(cs.rect.rightEdge)
        val start = if (line.extendLeft) {
            PixelPoint(cs.rect.leftEdge, cs.priceToY(line.start.price + slope * (leftTime - line.start.time.toEpochMilli())))
        } else {
            a
        }
        val end = if (line.extendRight) {
            PixelPoint(cs.rect.rightEdge, cs.priceToY(line.start.price + slope * (rightTime - line.start.time.toEpochMilli())))
        } else {
            b
        }
        return start to end
    }

    /**
     * True when the pixel [p] touches [drawing] within [tolerancePx].
     *
     * WHY per-type geometry: a single "distance to bounding box" rule would make a
     * horizontal line grabbable anywhere across the chart height and a rectangle
     * ungrabble in its middle. Each tool gets the rule a trader expects.
     */
    fun hitTest(
        drawing: ChartDrawing,
        cs: ChartCoordinateSystem,
        p: PixelPoint,
        tolerancePx: Float = DEFAULT_TOLERANCE_PX
    ): Boolean = when (drawing) {
        is ChartDrawing.HorizontalLine ->
            abs(p.y - cs.priceToY(drawing.price)) <= tolerancePx && cs.rect.containsX(p.x)

        is ChartDrawing.VerticalLine ->
            abs(p.x - cs.timeToX(drawing.time)) <= tolerancePx && p.y in cs.rect.topEdge..cs.rect.bottomEdge

        is ChartDrawing.TrendLine -> {
            val segment = projectedSegment(drawing, cs)
            segment != null && distanceToSegment(p, segment.first, segment.second) <= tolerancePx
        }

        is ChartDrawing.FibRetracement ->
            hitFib(drawing.start, drawing.end, drawing.levels, cs, p, tolerancePx)

        is ChartDrawing.FibExtension ->
            hitFib(drawing.start, drawing.end, drawing.levels, cs, p, tolerancePx)

        is ChartDrawing.Rectangle -> {
            // WHY: the outline is what is grabbable, not the filled interior —
            // otherwise a large zone swallows every tap meant for the candles.
            val a = PixelPoint(cs.timeToX(drawing.start.time), cs.priceToY(drawing.start.price))
            val b = PixelPoint(cs.timeToX(drawing.end.time), cs.priceToY(drawing.end.price))
            nearBoxOutline(p, a, b, tolerancePx)
        }

        is ChartDrawing.Measurement -> {
            val a = PixelPoint(cs.timeToX(drawing.start.time), cs.priceToY(drawing.start.price))
            val b = PixelPoint(cs.timeToX(drawing.end.time), cs.priceToY(drawing.end.price))
            nearBoxOutline(p, a, b, tolerancePx) || distanceToSegment(p, a, b) <= tolerancePx
        }

        is ChartDrawing.Position ->
            drawing.anchorPrices.any { price -> abs(p.y - cs.priceToY(price)) <= tolerancePx } &&
                cs.rect.containsX(p.x)
    }

    /**
     * Snaps a price to the nearest candle extreme, when the magnet allows it.
     *
     * WHY: on a touch screen the finger covers the level it is trying to mark.
     * Snapping to the nearest open/high/low/close inside the tolerance makes drawn
     * levels land exactly on the wick the trader meant.
     */
    fun magnetSnap(
        rawPrice: Double,
        atTimeMillis: Long,
        candles: List<CandleStick>,
        cs: ChartCoordinateSystem,
        mode: MagnetMode
    ): Double {
        if (mode == MagnetMode.NONE || candles.isEmpty()) return rawPrice
        val tolerance = mode.tolerancePx
        if (tolerance <= 0f) return rawPrice

        val index = ChartMath.nearestIndex(candles, atTimeMillis)
        if (index < 0) return rawPrice
        val candle = candles[index]
        val targetY = cs.priceToY(rawPrice)
        // WHY: only the four OHLC extremes are candidates — snapping to a
        // neighbouring bar's close would move the level somewhere the user never
        // pointed at.
        val candidates = listOf(candle.open, candle.high, candle.low, candle.close)
        var best = rawPrice
        var bestDistance = tolerance
        candidates.forEach { candidate ->
            val distance = abs(cs.priceToY(candidate) - targetY)
            if (distance < bestDistance) {
                bestDistance = distance
                best = candidate
            }
        }
        return best
    }

    /** Builds the anchor [ChartPoint] for a pixel inside the price pane. */
    fun pointFromPixel(pixel: PixelPoint, cs: ChartCoordinateSystem): ChartPoint =
        ChartPoint(
            time = Instant.ofEpochMilli(cs.xToTime(pixel.x)),
            price = cs.yToPrice(pixel.y)
        )

    private fun hitFib(
        start: ChartPoint,
        end: ChartPoint,
        fractions: List<Double>,
        cs: ChartCoordinateSystem,
        p: PixelPoint,
        tolerancePx: Float
    ): Boolean {
        val levels = fibLevels(start.price, end.price, fractions)
        val leftX = cs.timeToX(min(start.time.toEpochMilli(), end.time.toEpochMilli()))
        val rightX = cs.timeToX(max(start.time.toEpochMilli(), end.time.toEpochMilli()))
        if (p.x < leftX - tolerancePx || p.x > rightX + tolerancePx) return false
        // WHY: a fib tool is a stack of horizontal levels; hitting any one of them
        // selects the whole object, which is what users expect when they drag it.
        return levels.any { level -> abs(p.y - cs.priceToY(level.price)) <= tolerancePx }
    }

    private fun nearBoxOutline(p: PixelPoint, a: PixelPoint, b: PixelPoint, tolerancePx: Float): Boolean {
        val topLeft = PixelPoint(min(a.x, b.x), min(a.y, b.y))
        val bottomRight = PixelPoint(max(a.x, b.x), max(a.y, b.y))
        if (!pointInBox(p, topLeft, bottomRight, tolerancePx)) return false
        val horizontal = PixelPoint(p.x, topLeft.y)
        val horizontalBottom = PixelPoint(p.x, bottomRight.y)
        val vertical = PixelPoint(topLeft.x, p.y)
        val verticalRight = PixelPoint(bottomRight.x, p.y)
        return minOf(
            distance(p, horizontal),
            distance(p, horizontalBottom),
            distance(p, vertical),
            distance(p, verticalRight)
        ) <= tolerancePx
    }

    /** Default grab tolerance in pixels; roughly the width of a finger stroke. */
    const val DEFAULT_TOLERANCE_PX = 18f
}
