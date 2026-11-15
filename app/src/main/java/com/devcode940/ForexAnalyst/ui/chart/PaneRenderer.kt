package com.devcode940.ForexAnalyst.ui.chart

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import com.devcode940.ForexAnalyst.data.chart.ChartCoordinateSystem
import com.devcode940.ForexAnalyst.data.chart.ChartRect
import com.devcode940.ForexAnalyst.data.model.ChartOverlay
import com.devcode940.ForexAnalyst.data.model.PriceFormatter
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Paints the sub-panes: volume, RSI, MACD and Stochastic.
 *
 * WHY a separate renderer: each pane has its own coordinate system and its own
 * value domain, so mixing them into the price renderer is how an oscillator ends up
 * scaled against prices — the single most confusing chart bug there is.
 *
 * WHY every pane is clipped: an oscillator spike or a volume bar taller than the pane
 * must never paint over the candles above it.
 */
internal object PaneRenderer {

    /** Stroke width of an oscillator line. */
    private const val LINE_STROKE_PX = 1.4f

    /** Guide-line dash pattern (RSI 30/70, Stochastic 20/80). */
    private val GUIDE_DASH = floatArrayOf(5f, 5f)

    /** Header padding from the pane's top-left corner. */
    private const val HEADER_PADDING_PX = 5f

    /** Volume bars are drawn from the pane floor and occupy at most this fraction. */
    private const val VOLUME_HEIGHT_FRACTION = 0.92f

    /**
     * Draws every enabled pane.
     *
     * @param scope canvas draw scope.
     * @param frame resolved geometry.
     * @param text reusable paints.
     */
    fun draw(scope: DrawScope, frame: ChartFrame, text: ChartTextPainter) {
        if (frame.visibleRange.isEmpty()) return
        frame.layout.panes.forEach { pane ->
            val coords = frame.coordsFor(pane.overlay) ?: return@forEach
            with(scope) {
                drawPaneChrome(frame, pane.rect, pane.overlay, text)
                clipRect(
                    left = pane.rect.leftEdge,
                    top = pane.rect.topEdge,
                    right = pane.rect.rightEdge,
                    bottom = pane.rect.bottomEdge
                ) {
                    when (pane.overlay) {
                        ChartOverlay.VOLUME -> drawVolume(frame, pane.rect, coords)
                        ChartOverlay.RSI -> drawRsi(frame, pane.rect, coords)
                        ChartOverlay.MACD -> drawMacd(frame, pane.rect, coords)
                        ChartOverlay.STOCHASTIC -> drawStochastic(frame, coords)
                        // WHY explicit: a price overlay must never be painted into a
                        // pane; if the layout ever hands one over, nothing is drawn
                        // rather than something meaningless.
                        ChartOverlay.EMA,
                        ChartOverlay.SMA,
                        ChartOverlay.BOLLINGER,
                        ChartOverlay.VWAP,
                        ChartOverlay.SESSIONS,
                        ChartOverlay.GRID -> Unit
                    }
                }
            }
        }
    }

    /** Pane background, top separator and the header readout. */
    private fun DrawScope.drawPaneChrome(
        frame: ChartFrame,
        rect: ChartRect,
        overlay: ChartOverlay,
        text: ChartTextPainter
    ) {
        if (!rect.isUsable) return
        drawRect(
            color = frame.colors.paneBackground,
            topLeft = Offset(rect.leftEdge, rect.topEdge),
            size = Size(rect.width, rect.height)
        )
        drawLine(
            color = frame.colors.axisLine,
            start = Offset(rect.leftEdge, rect.topEdge),
            end = Offset(rect.rightEdge, rect.topEdge),
            strokeWidth = 1f
        )
        text.draw(
            scope = this,
            // WHY the current value is in the header: the trader reading the pane
            // wants the number without hunting for the crosshair, and the header is
            // space that would otherwise be empty.
            text = frame.labelFor(overlay),
            x = rect.leftEdge + HEADER_PADDING_PX,
            y = ChartTextPainter.baselineFor(rect.topEdge + HEADER_PADDING_PX + PANE_HEADER_LINE_PX / 2f, text.paneHeader),
            paint = text.paneHeader,
            color = frame.colors.axisText,
            anchor = TextAnchor.START
        )
    }

    /** Volume histogram, each bar coloured by its candle's direction. */
    private fun DrawScope.drawVolume(frame: ChartFrame, rect: ChartRect, coords: ChartCoordinateSystem) {
        val candles = frame.state.candles
        val colors = frame.colors
        val barWidth = max(frame.barWidthPx * BODY_RATIO, 1f)
        val floor = rect.bottomEdge

        for (index in frame.visibleRange) {
            val candle = candles[index]
            val x = coords.timeToX(candle.time)
            val top = coords.priceToY(candle.volume.toDouble())
            // WHY clamp the bar to the pane: a single enormous volume print would
            // otherwise paint over the header text.
            val barTop = max(top, rect.topEdge + rect.height * (1f - VOLUME_HEIGHT_FRACTION))
            val height = floor - barTop
            if (height <= 0f) continue
            drawRect(
                color = if (candle.close >= candle.open) colors.up.copy(alpha = VOLUME_ALPHA)
                else colors.down.copy(alpha = VOLUME_ALPHA),
                topLeft = Offset(x - barWidth / 2f, barTop),
                size = Size(barWidth, height)
            )
        }
    }

    /** RSI line with the 30 / 50 / 70 guides. */
    private fun DrawScope.drawRsi(frame: ChartFrame, rect: ChartRect, coords: ChartCoordinateSystem) {
        val guides = frame.colors.rsiGuide
        listOf(RSI_OVERSOLD, RSI_MIDPOINT, RSI_OVERBOUGHT).forEach { level ->
            val y = coords.priceToY(level)
            drawLine(
                color = guides,
                start = Offset(rect.leftEdge, y),
                end = Offset(rect.rightEdge, y),
                strokeWidth = 1f,
                // WHY the midline is dotted and the thresholds dashed: three identical
                // dashes make it impossible to tell which line is 50 at a glance.
                pathEffect = if (level == RSI_MIDPOINT) PathEffect.dashPathEffect(DOTTED) else PathEffect.dashPathEffect(GUIDE_DASH)
            )
        }
        drawPaneSeries(frame, coords, frame.state.indicators.rsi, frame.colors.rsi)
    }

    /** MACD histogram, MACD line and signal line. */
    private fun DrawScope.drawMacd(frame: ChartFrame, rect: ChartRect, coords: ChartCoordinateSystem) {
        val macd = frame.state.indicators.macd ?: return
        val colors = frame.colors
        val candles = frame.state.candles
        val zeroY = coords.priceToY(0.0)
        val barWidth = max(frame.barWidthPx * MACD_BAR_RATIO, 1f)

        drawLine(
            color = colors.axisLine,
            start = Offset(rect.leftEdge, zeroY),
            end = Offset(rect.rightEdge, zeroY),
            strokeWidth = 1f
        )

        for (index in frame.visibleRange) {
            val value = macd.histogram.getOrNull(index) ?: continue
            if (!value.isFinite()) continue
            val x = coords.timeToX(candles[index].time)
            val y = coords.priceToY(value)
            val top = min(y, zeroY)
            val height = abs(y - zeroY)
            if (height <= 0f) continue
            drawRect(
                color = if (value >= 0.0) colors.macdHistogramPositive else colors.macdHistogramNegative,
                topLeft = Offset(x - barWidth / 2f, top),
                size = Size(barWidth, height)
            )
        }

        // WHY lines after the histogram: the crossover is what traders read, and a
        // histogram drawn over it hides the exact bar where the cross happens.
        drawPaneSeries(frame, coords, macd.macdLine, colors.macdLine)
        drawPaneSeries(frame, coords, macd.signalLine, colors.macdSignal)
    }

    /** Stochastic %K and %D with the 20 / 80 guides. */
    private fun DrawScope.drawStochastic(frame: ChartFrame, coords: ChartCoordinateSystem) {
        val stoch = frame.state.indicators.stochastic ?: return
        val rect = coords.rect
        val colors = frame.colors

        listOf(STOCH_OVERSOLD, STOCH_OVERBOUGHT).forEach { level ->
            val y = coords.priceToY(level)
            drawLine(
                color = colors.rsiGuide,
                start = Offset(rect.leftEdge, y),
                end = Offset(rect.rightEdge, y),
                strokeWidth = 1f,
                pathEffect = PathEffect.dashPathEffect(GUIDE_DASH)
            )
        }
        drawPaneSeries(frame, coords, stoch.k, colors.stochasticK)
        drawPaneSeries(frame, coords, stoch.d, colors.stochasticD)
    }

    /**
     * Polyline for one pane's series.
     *
     * WHY a pane-specific painter instead of reusing the price one: the price painter
     * maps against the price coordinate system by design, and passing a pane's system
     * into it would be correct only by accident — an explicit parameter makes the
     * mapping impossible to get wrong.
     */
    private fun DrawScope.drawPaneSeries(
        frame: ChartFrame,
        coords: ChartCoordinateSystem,
        values: List<Double?>?,
        color: Color
    ) {
        if (values == null) return
        val candles = frame.state.candles
        val path = Path()
        var open = false

        for (index in frame.visibleRange) {
            val value = values.getOrNull(index)
            val candle = candles.getOrNull(index)
            if (value == null || candle == null || !value.isFinite()) {
                open = false
                continue
            }
            val x = coords.timeToX(candle.time)
            val y = coords.priceToY(value)
            if (!open) {
                path.moveTo(x, y)
                open = true
            } else {
                path.lineTo(x, y)
            }
        }
        if (!open) return
        drawPath(path, color, style = Stroke(width = LINE_STROKE_PX, cap = StrokeCap.Round))
    }

    /** Formats a pane's own value axis (used by the object list and tests). */
    fun paneValueLabel(overlay: ChartOverlay, value: Double): String = when (overlay) {
        ChartOverlay.VOLUME -> PriceFormatter.formatCompact(value)
        // WHY 1 decimal for oscillators: RSI and %K are quoted to one place by
        // convention everywhere else a trader reads them.
        ChartOverlay.RSI, ChartOverlay.STOCHASTIC -> PriceFormatter.format(value, 1)
        else -> PriceFormatter.format(value, 5)
    }

    private const val PANE_HEADER_LINE_PX = 13f
    private const val BODY_RATIO = 0.85f
    private const val MACD_BAR_RATIO = 0.7f
    private const val VOLUME_ALPHA = 0.55f
    private const val RSI_OVERSOLD = 30.0
    private const val RSI_MIDPOINT = 50.0
    private const val RSI_OVERBOUGHT = 70.0
    private const val STOCH_OVERSOLD = 20.0
    private const val STOCH_OVERBOUGHT = 80.0
    private val DOTTED = floatArrayOf(2f, 4f)
}
