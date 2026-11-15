package com.devcode940.ForexAnalyst.data.chart

import com.devcode940.ForexAnalyst.data.model.CandleStick
import com.devcode940.ForexAnalyst.data.model.ChartPoint
import com.devcode940.ForexAnalyst.data.model.ChartToolMode
import java.time.Instant

/**
 * Horizontal zoom and pan of the chart.
 *
 * WHY a value object: the two numbers must always change together (a zoom without
 * a re-clamped offset pushes the series off-screen). Keeping them in one immutable
 * type makes that invariant easy to hold in a single `copy` at the call site, and
 * lets the ViewModel publish them as one state emission instead of two that a
 * recomposition could observe separately.
 *
 * @property scale horizontal zoom factor; 1f fits the whole loaded series.
 * @property offsetX horizontal pan offset in pixels, clamped by the renderer.
 */
data class ChartViewport(val scale: Float = 1f, val offsetX: Float = 0f) {
    init {
        // WHY: a zero or negative scale divides by zero in every time→pixel
        // mapping; NaN offsets make every coordinate NaN and the canvas silently
        // draws nothing.
        require(scale.isFinite() && scale > 0f) { "scale must be finite and > 0 (got $scale)" }
        require(offsetX.isFinite()) { "offsetX must be finite (got $offsetX)" }
    }

    companion object {
        /** Default view: whole series, no pan. */
        val DEFAULT = ChartViewport()

        /** Minimum zoom — below this a bar is narrower than a pixel. */
        const val MIN_SCALE = 0.5f

        /** Maximum zoom; beyond it candle bodies are wider than the pane. */
        const val MAX_SCALE = 12f
    }
}

/**
 * Crosshair readout for one tapped bar.
 *
 * WHY data space and not pixels: the crosshair survives rotation and zoom, so it is
 * stored as (price, time) and re-projected to pixels every frame. A stored pixel
 * position would point at a different price after any pan.
 *
 * @property price price at the tapped y coordinate.
 * @property time time at the tapped x coordinate.
 * @property candle the bar the crosshair snapped to, if any.
 */
data class CrosshairData(
    val price: Double,
    val time: Instant,
    val candle: CandleStick?
) {
    init {
        require(price.isFinite()) { "Crosshair price must be finite (got $price)" }
    }

    /** Epoch millis view for the renderer's coordinate mapping. */
    val timeMillis: Long get() = time.toEpochMilli()
}

/**
 * A drawing in progress (press-drag-release not yet finished).
 *
 * WHY modelled separately from [com.devcode940.ForexAnalyst.data.model.ChartDrawing]:
 * a draft has no id, no colour decision committed and may still be cancelled; giving
 * it the persisted type would force the store to hold half-built objects and would
 * make "cancel" a delete of something that was never added.
 *
 * @property tool the armed tool that produced the draft.
 * @property start first anchor (touch-down).
 * @property current live anchor (current finger position).
 */
data class DraftDrawing(
    val tool: ChartToolMode,
    val start: ChartPoint,
    val current: ChartPoint
) {
    /** True once the two anchors differ, i.e. the draft is drawable. */
    val isDrawable: Boolean get() = start != current
}

/** One labelled time-axis tick, already projected to pixels. */
data class TimeTick(val instant: Instant, val label: String, val xPx: Float)

/** One labelled price-axis tick, already projected to pixels. */
data class PriceTick(val price: Double, val label: String, val yPx: Float)
