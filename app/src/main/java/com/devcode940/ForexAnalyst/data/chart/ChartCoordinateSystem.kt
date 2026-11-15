package com.devcode940.ForexAnalyst.data.chart

import com.devcode940.ForexAnalyst.data.model.CandleStick
import kotlin.math.max
import kotlin.math.min

/**
 * Maps between data space (time, price) and canvas pixels for one pane.
 *
 * WHY rect-aware: the chart is divided into a price pane plus optional sub-panes
 * (volume, RSI, MACD). If the mapping returned coordinates relative to a pane of
 * unknown origin, every draw call would have to add `rect.top` by hand — the exact
 * kind of repeated arithmetic that produced the old bug where the crosshair tooltip
 * reported a price computed against the full canvas height while the candles were
 * drawn in the top 78 % of it.
 *
 * WHY time-based x (not index-based): bars stay positioned at their real timestamp,
 * which is what session shading, vertical lines and time-axis ticks need. The
 * trade-off is that a market closure (weekend, index overnight gap) leaves empty
 * space; that is documented in the README and is the same behaviour a time-axis
 * chart shows on most platforms.
 *
 * Pure Kotlin and immutable: safe to build per frame and to unit-test.
 */
class ChartCoordinateSystem(
    /** Pane this system maps into, in canvas pixels. */
    val rect: ChartRect,
    /** Lowest price at the bottom edge (already padded). */
    val minPrice: Double,
    /** Highest price at the top edge (already padded). */
    val maxPrice: Double,
    /** Leftmost visible time in epoch millis. */
    val startTimeMillis: Long,
    /** Rightmost visible time in epoch millis. */
    val endTimeMillis: Long,
    /** Horizontal zoom factor; 1f = fit the whole series. */
    val scale: Float = 1f,
    /** Horizontal pan offset in pixels. */
    val offsetX: Float = 0f
) {

    init {
        // WHY: a degenerate rect or an inverted price range produces NaN pixel
        // coordinates, and Compose silently skips NaN draw calls — the chart would
        // just go blank with no error anywhere. Fail loudly in tests instead.
        require(minPrice.isFinite() && maxPrice.isFinite()) {
            "Price bounds must be finite (got $minPrice..$maxPrice)"
        }
        require(startTimeMillis.isFiniteMillis() && endTimeMillis.isFiniteMillis()) {
            "Time bounds must be finite (got $startTimeMillis..$endTimeMillis)"
        }
        require(scale > 0f) { "scale must be > 0 (got $scale)" }
    }

    /** Usable horizontal span after zoom. */
    private val spanWidthPx: Float get() = rect.width * scale

    /** Price span; never zero because [ChartMath.priceBounds] widens flat windows. */
    private val priceSpan: Double get() = maxPrice - minPrice

    /** Time span in millis; may be zero for a single-candle series. */
    private val timeSpan: Long get() = endTimeMillis - startTimeMillis

    /** Canvas y for [price]; higher prices map to smaller y (screen coordinates). */
    fun priceToY(price: Double): Float {
        if (priceSpan == 0.0) return rect.topEdge + rect.height / 2f
        val normalized = ((price - minPrice) / priceSpan).toFloat()
        // WHY: clamping keeps an outlier (or a drawing anchor far outside the
        // visible range) from producing a coordinate thousands of pixels away,
        // which would make drawLine render an enormous clipped shape per frame.
        return rect.bottomEdge - normalized.coerceIn(-10f, 11f) * rect.height
    }

    /** Price at canvas y. */
    fun yToPrice(y: Float): Double {
        if (rect.height == 0f) return minPrice
        val normalized = (rect.bottomEdge - y) / rect.height
        return minPrice + normalized.toDouble() * priceSpan
    }

    /** Canvas x for a timestamp in epoch millis. */
    fun timeToX(timeMillis: Long): Float {
        if (timeSpan == 0L) return rect.leftEdge + rect.width / 2f + offsetX
        val normalized = (timeMillis - startTimeMillis).toDouble() / timeSpan.toDouble()
        return rect.leftEdge + (normalized.toFloat() * spanWidthPx) + offsetX
    }

    /** Canvas x for a [java.time.Instant]. */
    fun timeToX(instant: java.time.Instant): Float = timeToX(instant.toEpochMilli())

    /** Timestamp at canvas x, in epoch millis. */
    fun xToTime(x: Float): Long {
        if (spanWidthPx == 0f) return startTimeMillis
        val normalized = (x - rect.leftEdge - offsetX) / spanWidthPx
        return startTimeMillis + (normalized.toDouble() * timeSpan).toLong()
    }

    /** Pixel width of one bar, given the bar interval; used for candle bodies. */
    fun barWidthPx(barIntervalMillis: Long): Float {
        if (timeSpan <= 0L || barIntervalMillis <= 0L) return DEFAULT_BAR_WIDTH_PX
        val pixelsPerMilli = spanWidthPx / timeSpan
        // WHY: 0.7 leaves a visible gap between bodies; without it adjacent
        // candles merge into a solid block on dense timeframes.
        return (barIntervalMillis * pixelsPerMilli * BODY_RATIO).coerceIn(MIN_BAR_WIDTH_PX, MAX_BAR_WIDTH_PX)
    }

    /** True when [timeMillis] falls inside the horizontal span of this pane. */
    fun isTimeVisible(timeMillis: Long): Boolean {
        val x = timeToX(timeMillis)
        return x >= rect.leftEdge - MAX_BAR_WIDTH_PX && x <= rect.rightEdge + MAX_BAR_WIDTH_PX
    }

    /** Clamps a pan offset so the series cannot be dragged completely off-screen. */
    fun clampOffset(proposedOffsetX: Float): Float {
        // WHY: without a clamp a fling can push every candle out of view and the
        // only way back is a double-tap the user does not know about.
        val overflow = spanWidthPx - rect.width
        return if (overflow <= 0f) 0f else proposedOffsetX.coerceIn(-overflow, 0f)
    }

    companion object {
        /** Vertical whitespace above/below the extremes, as a fraction of the range. */
        const val VERTICAL_PADDING_FRACTION = 0.06

        private const val BODY_RATIO = 0.7f
        private const val MIN_BAR_WIDTH_PX = 1f
        private const val MAX_BAR_WIDTH_PX = 48f
        private const val DEFAULT_BAR_WIDTH_PX = 8f

        /**
         * Builds a system for the visible slice of [candles].
         *
         * @param rect pane rectangle in canvas pixels.
         * @param candles ascending candle series (the full loaded series).
         * @param visibleRange indices to scale the price axis to.
         * @param scale horizontal zoom factor.
         * @param offsetX horizontal pan offset in pixels.
         */
        fun forCandles(
            rect: ChartRect,
            candles: List<CandleStick>,
            visibleRange: IntRange,
            scale: Float = 1f,
            offsetX: Float = 0f
        ): ChartCoordinateSystem {
            if (candles.isEmpty()) {
                return ChartCoordinateSystem(rect, 0.0, 1.0, 0L, 1L, scale, offsetX)
            }
            val from = if (visibleRange.isEmpty()) 0 else visibleRange.first
            val to = if (visibleRange.isEmpty()) candles.size else visibleRange.last + 1
            val (low, high) = ChartMath.priceBounds(candles, from, to)
            val padding = (high - low) * VERTICAL_PADDING_FRACTION
            return ChartCoordinateSystem(
                rect = rect,
                // WHY: pad both edges so the highest wick is not clipped by the
                // pane border and the lowest one is not hidden behind the axis.
                minPrice = low - padding,
                maxPrice = high + padding,
                startTimeMillis = candles.first().timeMillis,
                endTimeMillis = max(candles.last().timeMillis, candles.first().timeMillis + 1L),
                scale = scale,
                offsetX = offsetX
            )
        }

        /**
         * Builds a system for a bounded sub-pane (RSI, Stochastic).
         *
         * WHY: oscillators have a fixed domain (0..100) — scaling them to the
         * visible min/max would exaggerate noise and hide the 30/70 thresholds.
         */
        fun forFixedRange(
            rect: ChartRect,
            minValue: Double,
            maxValue: Double,
            startTimeMillis: Long,
            endTimeMillis: Long,
            scale: Float = 1f,
            offsetX: Float = 0f
        ): ChartCoordinateSystem = ChartCoordinateSystem(
            rect = rect,
            minPrice = min(minValue, maxValue),
            maxPrice = max(minValue, maxValue),
            startTimeMillis = startTimeMillis,
            endTimeMillis = endTimeMillis,
            scale = scale,
            offsetX = offsetX
        )
    }
}

/** Guards against the pathological millis values a corrupt row could carry. */
private fun Long.isFiniteMillis(): Boolean = this in -62_135_596_800_000L..253_402_300_799_999L
