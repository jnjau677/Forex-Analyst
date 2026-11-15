package com.devcode940.ForexAnalyst.data.chart

import com.devcode940.ForexAnalyst.data.model.ChartConfig
import com.devcode940.ForexAnalyst.data.model.ChartOverlay

/** One sub-panel with the overlay it renders. */
data class PaneLayout(val overlay: ChartOverlay, val rect: ChartRect)

/**
 * Fully resolved chart geometry: where the price pane, each sub-pane and the two
 * axes live, in canvas pixels.
 *
 * @property plotRect area available to price + sub-panes (excludes both axes).
 * @property priceRect rectangle the candles and price overlays are drawn into.
 * @property panes sub-panels below the price pane, top to bottom.
 * @property timeAxisRect strip reserved for time labels along the bottom.
 * @property priceAxisRect strip reserved for price labels on the right.
 */
data class ChartLayout(
    val plotRect: ChartRect,
    val priceRect: ChartRect,
    val panes: List<PaneLayout>,
    val timeAxisRect: ChartRect,
    val priceAxisRect: ChartRect
) {
    /** Time axis spans the full plot width; its own coordinate mapping. */
    val axisWidthPx: Float get() = priceAxisRect.width

    /** True when at least one sub-pane is visible. */
    val hasPanes: Boolean get() = panes.isNotEmpty()
}

/**
 * Divides the canvas between the price pane, the sub-panes and the two axes.
 *
 * WHY a pure function: pane geometry is the single most common source of "the
 * indicator is drawn over the candles" and "the crosshair reports the wrong price"
 * bugs. Computing it here — with unit tests — means the renderer only ever receives
 * rectangles that already add up to the canvas height.
 */
object ChartLayoutBuilder {

    /** Height reserved for the time axis label strip. */
    const val TIME_AXIS_HEIGHT_PX = 34f

    /** Width reserved for the price axis label strip. */
    const val PRICE_AXIS_WIDTH_PX = 68f

    /** Vertical gap between panes. */
    const val PANE_GAP_PX = 6f

    /** Smallest height a sub-pane may be given. */
    const val MIN_PANE_HEIGHT_PX = 46f

    /** Smallest height the price pane may keep. */
    const val MIN_PRICE_HEIGHT_PX = 96f

    /** Share of the plot height each sub-pane asks for before scaling. */
    private val PANE_WEIGHTS: Map<ChartOverlay, Float> = mapOf(
        ChartOverlay.VOLUME to 0.13f,
        ChartOverlay.RSI to 0.17f,
        ChartOverlay.MACD to 0.19f,
        ChartOverlay.STOCHASTIC to 0.17f
    )

    /** Weight used for a pane type with no explicit entry. */
    private const val DEFAULT_PANE_WEIGHT = 0.17f

    /**
     * Resolves the layout for a canvas of [widthPx] x [heightPx].
     *
     * @param widthPx total canvas width in pixels.
     * @param heightPx total canvas height in pixels.
     * @param config chart configuration; only the enabled panes are laid out.
     * @return the resolved [ChartLayout]; degenerate sizes yield empty rects
     *   rather than throwing, because a zero-size canvas happens legitimately
     *   during the first composition pass.
     */
    fun build(widthPx: Float, heightPx: Float, config: ChartConfig): ChartLayout {
        if (widthPx <= 0f || heightPx <= 0f) {
            return ChartLayout(ChartRect.EMPTY, ChartRect.EMPTY, emptyList(), ChartRect.EMPTY, ChartRect.EMPTY)
        }

        val plotWidth = (widthPx - PRICE_AXIS_WIDTH_PX).coerceAtLeast(0f)
        val plotHeight = (heightPx - TIME_AXIS_HEIGHT_PX).coerceAtLeast(0f)
        val plotRect = ChartRect.of(0f, 0f, plotWidth, plotHeight)
        val priceAxisRect = ChartRect.of(plotWidth, 0f, PRICE_AXIS_WIDTH_PX, plotHeight)
        val timeAxisRect = ChartRect.of(0f, plotHeight, plotWidth, TIME_AXIS_HEIGHT_PX)

        val requestedPanes = config.panes
        if (requestedPanes.isEmpty() || plotHeight <= MIN_PRICE_HEIGHT_PX) {
            // WHY: on a very short canvas, sub-panes are dropped entirely rather
            // than squeezing the price pane below a readable height.
            return ChartLayout(plotRect, plotRect, emptyList(), timeAxisRect, priceAxisRect)
        }

        // WHY one gap *before* each pane (including the first): the gap count is
        // then exactly the pane count, which keeps the height budget below simple
        // enough to verify by inspection — an off-by-one here is what makes the
        // last pane overflow the canvas by a few pixels.
        val gapsTotal = PANE_GAP_PX * requestedPanes.size
        val availableForPanes = (plotHeight - MIN_PRICE_HEIGHT_PX - gapsTotal).coerceAtLeast(0f)
        val requestedHeights = requestedPanes.map { overlay ->
            val weight = PANE_WEIGHTS[overlay] ?: DEFAULT_PANE_WEIGHT
            maxOf(MIN_PANE_HEIGHT_PX, plotHeight * weight)
        }
        val requestedTotal = requestedHeights.sum()

        // WHY: scale panes down proportionally when they ask for more room than
        // the canvas can give, instead of pushing the price pane off-screen.
        val scaleFactor = if (requestedTotal > availableForPanes && requestedTotal > 0f) {
            availableForPanes / requestedTotal
        } else {
            1f
        }
        val paneHeights = requestedHeights.map { it * scaleFactor }
        val panesTotal = paneHeights.sum()

        // WHY: the price pane takes whatever is left, anchored to the top so the
        // most important series keeps the largest, most stable rectangle.
        val priceHeight = (plotHeight - gapsTotal - panesTotal).coerceAtLeast(MIN_PRICE_HEIGHT_PX)
        val priceRect = ChartRect.of(0f, 0f, plotWidth, priceHeight)

        val paneLayouts = ArrayList<PaneLayout>(requestedPanes.size)
        var top = priceHeight
        requestedPanes.forEachIndexed { index, overlay ->
            top += PANE_GAP_PX
            paneLayouts.add(PaneLayout(overlay, ChartRect.of(0f, top, plotWidth, paneHeights[index])))
            top += paneHeights[index]
        }

        return ChartLayout(
            plotRect = plotRect,
            priceRect = priceRect,
            panes = paneLayouts,
            timeAxisRect = timeAxisRect,
            priceAxisRect = priceAxisRect
        )
    }
}
