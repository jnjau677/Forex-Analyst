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
import com.devcode940.ForexAnalyst.data.chart.TimeAxis
import com.devcode940.ForexAnalyst.data.model.BollingerBands
import com.devcode940.ForexAnalyst.data.model.CandleStick
import com.devcode940.ForexAnalyst.data.model.ChartOverlay
import com.devcode940.ForexAnalyst.data.model.ChartStyle
import com.devcode940.ForexAnalyst.data.model.PriceFormatter
import com.devcode940.ForexAnalyst.data.model.TradingSession
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Paints the price pane, its overlays, both axes and the crosshair.
 *
 * WHY a stateless object: the renderer must be a pure function of (frame, palette)
 * so a bug is reproducible from the frame alone, and so nothing can hold a stale
 * coordinate mapping between frames. The only mutable objects are the reusable
 * paints in [ChartTextPainter], which the composable owns.
 *
 * WHY the draw order is fixed here: sessions behind candles, candles behind
 * overlays, overlays behind drawings, drawings behind the crosshair. Any other order
 * makes an annotation hide the data it is supposed to describe.
 */
internal object ChartRenderer {

    /** Stroke width of a price-overlay line. */
    private const val OVERLAY_STROKE_PX = 1.6f

    /** Stroke width of a candle wick and of an OHLC bar spine. */
    private const val WICK_STROKE_PX = 1.1f

    /** Minimum body height so a doji is still visible at any zoom. */
    private const val MIN_BODY_PX = 1f

    /** Body width as a fraction of the bar slot; the rest is the gap between bars. */
    private const val BODY_RATIO = 0.85f

    private const val AREA_FILL_ALPHA = 0.16f
    private const val LINE_STROKE_PX = 1.8f
    private const val AXIS_LABEL_PADDING_PX = 6f
    private const val PRICE_TAG_HEIGHT_PX = 17f
    private const val PRICE_TAG_PADDING_PX = 6f
    private const val BADGE_BASELINE_OFFSET_PX = 4f
    private const val BADGE_ALPHA = 0.75f
    private const val TOOLTIP_PADDING_PX = 7f
    private const val TOOLTIP_LINE_HEIGHT_PX = 15f
    private const val TOOLTIP_OFFSET_PX = 12f

    /** First tooltip line that holds OHLC figures and therefore needs tabular text. */
    private const val OHLC_LINE_INDEX = 2

    /** Dash pattern for the crosshair. */
    private val CROSSHAIR_DASH = floatArrayOf(8f, 6f)

    /** Dash pattern for VWAP. */
    private val VWAP_DASH = floatArrayOf(10f, 5f)

    /** Dash pattern for the Bollinger mid-line. */
    private val BOLLINGER_MID_DASH = floatArrayOf(4f, 4f)

    /**
     * Which session's colour wins when several overlap.
     *
     * WHY a fixed priority instead of blending: two overlapping alpha bands produce a
     * colour the legend cannot explain. Picking the most liquid active session gives
     * shading the trader can actually read ("this stretch is London").
     */
    private val SESSION_PRIORITY: List<TradingSession> = listOf(
        TradingSession.LONDON,
        TradingSession.NEW_YORK,
        TradingSession.TOKYO,
        TradingSession.SYDNEY
    )

    /**
     * Renders one complete frame.
     *
     * @param scope the canvas draw scope.
     * @param frame resolved geometry for this frame.
     * @param text reusable paints and text helpers.
     */
    fun render(scope: DrawScope, frame: ChartFrame, text: ChartTextPainter) {
        with(scope) {
            drawBackground(frame)
            if (!frame.isDrawable) return
            if (frame.state.config.isOn(ChartOverlay.SESSIONS)) drawSessions(frame)
            if (frame.state.config.isOn(ChartOverlay.GRID)) drawGrid(frame)
            // WHY clip: zoom and pan place bars outside the price pane, and an
            // unclipped wick would draw across the sub-panes and the axes.
            clipRect(
                left = frame.layout.priceRect.leftEdge,
                top = frame.layout.priceRect.topEdge,
                right = frame.layout.priceRect.rightEdge,
                bottom = frame.layout.priceRect.bottomEdge
            ) {
                drawPriceSeries(frame)
                drawPriceOverlays(frame)
                DrawingRenderer.draw(this, frame, text)
            }
            PaneRenderer.draw(this, frame, text)
            drawPriceAxis(frame, text)
            drawTimeAxis(frame, text)
            drawCrosshair(frame, text)
        }
    }

    /**
     * Fills the canvas and the price pane.
     *
     * WHY drawn even when the frame is not drawable: a chart that is still loading
     * must show the correct background rather than a white rectangle that flashes on
     * every theme change.
     */
    private fun DrawScope.drawBackground(frame: ChartFrame) {
        drawRect(frame.colors.background)
        val priceRect = frame.layout.priceRect
        if (priceRect.isUsable && frame.colors.paneBackground != frame.colors.background) {
            drawRect(
                color = frame.colors.paneBackground,
                topLeft = Offset(priceRect.leftEdge, priceRect.topEdge),
                size = Size(priceRect.width, priceRect.height)
            )
        }
    }

    /**
     * Shades the trading session each visible bar belongs to.
     *
     * WHY runs instead of per-bar rectangles: sessions change only a handful of times
     * across the visible window, so grouping consecutive bars with the same active
     * session set turns ~200 `drawRect` calls into ~6 and removes the seams per-bar
     * shading leaves between rectangles.
     *
     * WHY intraday only: on D1 and above every bar spans several sessions, so the
     * whole chart would be one flat tint — information-free but visually noisy.
     */
    private fun DrawScope.drawSessions(frame: ChartFrame) {
        if (!frame.state.timeframe.isIntraday) return
        val candles = frame.state.candles
        val coords = frame.priceCoords
        val rect = frame.layout.priceRect
        val halfBar = frame.barWidthPx / 2f
        if (frame.visibleRange.isEmpty()) return

        var runStart = frame.visibleRange.first
        var runSessions = TradingSession.activeAt(candles[runStart].time)

        fun flush(runEndInclusive: Int) {
            val session = SESSION_PRIORITY.firstOrNull { it in runSessions } ?: return
            val left = coords.timeToX(candles[runStart].time) - halfBar
            val right = coords.timeToX(candles[runEndInclusive].time) + halfBar
            val clampedLeft = max(left, rect.leftEdge)
            val clampedRight = min(right, rect.rightEdge)
            if (clampedRight <= clampedLeft) return
            drawRect(
                color = sessionColor(frame.colors, session),
                topLeft = Offset(clampedLeft, rect.topEdge),
                size = Size(clampedRight - clampedLeft, rect.height)
            )
        }

        for (index in frame.visibleRange) {
            val sessions = TradingSession.activeAt(candles[index].time)
            if (sessions != runSessions) {
                flush(index - 1)
                runStart = index
                runSessions = sessions
            }
        }
        flush(frame.visibleRange.last)
    }

    private fun sessionColor(colors: ChartColors, session: TradingSession): Color = when (session) {
        TradingSession.LONDON -> colors.sessionLondon
        TradingSession.NEW_YORK -> colors.sessionNewYork
        TradingSession.TOKYO -> colors.sessionTokyo
        TradingSession.SYDNEY -> colors.sessionSydney
    }

    /** Horizontal price grid lines and vertical time grid lines. */
    private fun DrawScope.drawGrid(frame: ChartFrame) {
        val rect = frame.layout.priceRect
        frame.priceTicks.forEach { tick ->
            if (tick.yPx < rect.topEdge || tick.yPx > rect.bottomEdge) return@forEach
            drawLine(
                color = frame.colors.grid,
                start = Offset(rect.leftEdge, tick.yPx),
                end = Offset(rect.rightEdge, tick.yPx),
                strokeWidth = 1f
            )
        }
        frame.timeTicks.forEach { tick ->
            if (tick.xPx < rect.leftEdge || tick.xPx > rect.rightEdge) return@forEach
            drawLine(
                color = frame.colors.grid,
                start = Offset(tick.xPx, rect.topEdge),
                end = Offset(tick.xPx, rect.bottomEdge),
                strokeWidth = 1f
            )
        }
    }

    /**
     * Draws the price series for the configured [ChartStyle].
     *
     * WHY one dispatch over six small painters: the styles share a coordinate mapping
     * but differ in shape, so siblings keep each readable while the exhaustive `when`
     * guarantees a newly added style cannot be silently skipped.
     */
    private fun DrawScope.drawPriceSeries(frame: ChartFrame) {
        when (frame.state.config.style) {
            ChartStyle.CANDLES, ChartStyle.HEIKIN_ASHI -> drawCandles(frame, hollow = false)
            ChartStyle.HOLLOW_CANDLES -> drawCandles(frame, hollow = true)
            ChartStyle.OHLC_BARS -> drawOhlcBars(frame)
            ChartStyle.LINE -> drawLineSeries(frame, fill = false)
            ChartStyle.AREA -> drawLineSeries(frame, fill = true)
        }
    }

    /** Japanese candles, or hollow candles (an up bar drawn as an outline). */
    private fun DrawScope.drawCandles(frame: ChartFrame, hollow: Boolean) {
        val coords = frame.priceCoords
        val colors = frame.colors
        val bodyWidth = max(frame.barWidthPx * BODY_RATIO, MIN_BODY_PX)

        for (index in frame.visibleRange) {
            val candle = frame.state.candles[index]
            val x = coords.timeToX(candle.time)
            val isUp = candle.close >= candle.open
            val color = if (isUp) colors.up else colors.down

            drawLine(
                color = color,
                start = Offset(x, coords.priceToY(candle.high)),
                end = Offset(x, coords.priceToY(candle.low)),
                strokeWidth = WICK_STROKE_PX
            )

            val openY = coords.priceToY(candle.open)
            val closeY = coords.priceToY(candle.close)
            val top = min(openY, closeY)
            val height = max(abs(openY - closeY), MIN_BODY_PX)
            val left = x - bodyWidth / 2f

            if (hollow && isUp) {
                // WHY stroke instead of fill: a hollow candle must let the session
                // band and the grid show through its body — that transparency is the
                // entire point of the style.
                drawRect(
                    color = color,
                    topLeft = Offset(left, top),
                    size = Size(bodyWidth, height),
                    style = Stroke(width = WICK_STROKE_PX)
                )
            } else {
                drawRect(
                    color = color,
                    topLeft = Offset(left, top),
                    size = Size(bodyWidth, height)
                )
            }
        }
    }

    /** Classic OHLC bars: a high-low spine with an open tick left and a close tick right. */
    private fun DrawScope.drawOhlcBars(frame: ChartFrame) {
        val coords = frame.priceCoords
        val colors = frame.colors
        val tick = max(frame.barWidthPx / 2f, 2f)

        for (index in frame.visibleRange) {
            val candle = frame.state.candles[index]
            val x = coords.timeToX(candle.time)
            val color = if (candle.close >= candle.open) colors.up else colors.down

            drawLine(
                color = color,
                start = Offset(x, coords.priceToY(candle.high)),
                end = Offset(x, coords.priceToY(candle.low)),
                strokeWidth = WICK_STROKE_PX
            )
            // WHY open ticks point left and close ticks right: that is the convention
            // every bar-chart reader knows, and it makes a bar's direction readable
            // without colour — which matters for colour-blind traders.
            drawLine(
                color = color,
                start = Offset(x - tick, coords.priceToY(candle.open)),
                end = Offset(x, coords.priceToY(candle.open)),
                strokeWidth = WICK_STROKE_PX
            )
            drawLine(
                color = color,
                start = Offset(x, coords.priceToY(candle.close)),
                end = Offset(x + tick, coords.priceToY(candle.close)),
                strokeWidth = WICK_STROKE_PX
            )
        }
    }

    /** Close-price polyline, optionally filled down to the pane floor. */
    private fun DrawScope.drawLineSeries(frame: ChartFrame, fill: Boolean) {
        val coords = frame.priceCoords
        val candles = frame.state.candles
        val range = frame.visibleRange
        if (range.isEmpty()) return

        // WHY the line takes the direction colour of the visible window: on a line
        // chart there are no per-bar colours left to read, so the overall drift is the
        // only signal available and it should be obvious at a glance.
        val lineColor = if (candles[range.last].close >= candles[range.first].open) {
            frame.colors.up
        } else {
            frame.colors.down
        }

        val path = Path()
        for (index in range) {
            val x = coords.timeToX(candles[index].time)
            val y = coords.priceToY(candles[index].close)
            if (index == range.first) path.moveTo(x, y) else path.lineTo(x, y)
        }

        if (fill) {
            // WHY close along the pane floor through the first/last x: an area chart
            // is the region under the line, and closing it that way keeps the fill
            // inside the pane even when the series is panned past an edge.
            val area = Path().apply {
                addPath(path)
                lineTo(coords.timeToX(candles[range.last].time), frame.layout.priceRect.bottomEdge)
                lineTo(coords.timeToX(candles[range.first].time), frame.layout.priceRect.bottomEdge)
                close()
            }
            drawPath(area, lineColor.copy(alpha = AREA_FILL_ALPHA))
        }
        drawPath(path, lineColor, style = Stroke(width = LINE_STROKE_PX, cap = StrokeCap.Round))
    }

    /**
     * Draws every enabled price-pane overlay.
     *
     * WHY Bollinger first: its fill is translucent, so anything drawn afterwards stays
     * fully visible; a band drawn last would wash out the moving averages.
     */
    private fun DrawScope.drawPriceOverlays(frame: ChartFrame) {
        val config = frame.state.config
        val indicators = frame.state.indicators

        if (config.isOn(ChartOverlay.BOLLINGER)) {
            indicators.bollinger?.let { bands -> drawBollinger(frame, bands) }
        }
        if (config.isOn(ChartOverlay.SMA)) {
            drawSeries(frame, indicators.sma, frame.colors.sma, dash = null)
        }
        if (config.isOn(ChartOverlay.EMA)) {
            // WHY slow → medium → fast: the fast EMA is the one traders act on, so it
            // is painted last and never hidden where the three cross.
            drawSeries(frame, indicators.emaSlow, frame.colors.emaSlow, dash = null)
            drawSeries(frame, indicators.emaMedium, frame.colors.emaMedium, dash = null)
            drawSeries(frame, indicators.emaFast, frame.colors.emaFast, dash = null)
        }
        if (config.isOn(ChartOverlay.VWAP)) {
            drawSeries(frame, indicators.vwap, frame.colors.vwap, dash = VWAP_DASH)
        }
    }

    /**
     * A single nullable indicator series, breaking the path across null runs.
     *
     * WHY break on null: leading nulls mean "not enough bars yet". Connecting across
     * them draws a line from the pane floor into the first real value, which reads as
     * a crash.
     */
    internal fun DrawScope.drawSeries(
        frame: ChartFrame,
        values: List<Double?>?,
        color: Color,
        dash: FloatArray?,
        range: IntRange = frame.visibleRange
    ) {
        if (values == null) return
        val coords = frame.priceCoords
        val candles = frame.state.candles
        val path = Path()
        var open = false

        for (index in range) {
            val value = values.getOrNull(index)
            if (value == null || !value.isFinite()) {
                open = false
                continue
            }
            val x = coords.timeToX(candles.getOrNull(index)?.time ?: continue)
            val y = coords.priceToY(value)
            if (!open) {
                path.moveTo(x, y)
                open = true
            } else {
                path.lineTo(x, y)
            }
        }
        if (!open) return
        drawPath(
            path,
            color,
            style = Stroke(
                width = OVERLAY_STROKE_PX,
                cap = StrokeCap.Round,
                pathEffect = dash?.let { PathEffect.dashPathEffect(it) }
            )
        )
    }

    /** Bollinger Bands: a translucent fill between the outer bands plus three lines. */
    private fun DrawScope.drawBollinger(frame: ChartFrame, bands: BollingerBands) {
        val coords = frame.priceCoords
        val candles = frame.state.candles

        // WHY collect the usable indices first: the fill polygon must walk the upper
        // band forward and the lower band backward over exactly the same indices, or
        // the shape self-intersects and the fill renders as a bowtie.
        val indices = frame.visibleRange.filter { index ->
            val upper = bands.upper.getOrNull(index)
            val lower = bands.lower.getOrNull(index)
            upper != null && upper.isFinite() && lower != null && lower.isFinite()
        }
        if (indices.isNotEmpty()) {
            val band = Path()
            indices.forEachIndexed { offset, index ->
                val x = coords.timeToX(candles[index].time)
                val y = coords.priceToY(bands.upper[index] ?: return@forEachIndexed)
                if (offset == 0) band.moveTo(x, y) else band.lineTo(x, y)
            }
            indices.asReversed().forEach { index ->
                val y = coords.priceToY(bands.lower[index] ?: return@forEach)
                band.lineTo(coords.timeToX(candles[index].time), y)
            }
            band.close()
            drawPath(band, frame.colors.bollingerFill)
        }

        drawSeries(frame, bands.upper, frame.colors.bollinger, dash = null)
        drawSeries(frame, bands.middle, frame.colors.bollinger, dash = BOLLINGER_MID_DASH)
        drawSeries(frame, bands.lower, frame.colors.bollinger, dash = null)
    }

    /**
     * Price-axis labels plus the last-price tag.
     *
     * WHY a tag on the axis: the last price is the first number a trader looks for,
     * and without the tag it has to be read off a candle against the grid.
     */
    private fun DrawScope.drawPriceAxis(frame: ChartFrame, text: ChartTextPainter) {
        val axisRect = frame.layout.priceAxisRect
        if (!axisRect.isUsable) return
        val colors = frame.colors

        drawLine(
            color = colors.axisLine,
            start = Offset(axisRect.leftEdge, axisRect.topEdge),
            end = Offset(axisRect.leftEdge, axisRect.bottomEdge),
            strokeWidth = 1f
        )

        frame.priceTicks.forEach { tick ->
            if (tick.yPx < axisRect.topEdge || tick.yPx > axisRect.bottomEdge) return@forEach
            text.draw(
                scope = this,
                text = tick.label,
                x = axisRect.rightEdge - AXIS_LABEL_PADDING_PX,
                y = ChartTextPainter.baselineFor(tick.yPx, text.price),
                paint = text.price,
                color = colors.axisText,
                anchor = TextAnchor.END
            )
        }

        val last = frame.lastCandle ?: return
        val lastY = frame.priceCoords.priceToY(last.close)
        if (lastY < axisRect.topEdge || lastY > axisRect.bottomEdge) return
        drawPriceTag(
            frame = frame,
            text = text,
            y = lastY,
            label = PriceFormatter.format(last.close, frame.state.digits),
            background = if (last.close >= last.open) colors.up else colors.down,
            foreground = colors.priceTagText
        )
    }

    /** A filled tag on the price axis — used for the last price and the crosshair. */
    internal fun DrawScope.drawPriceTag(
        frame: ChartFrame,
        text: ChartTextPainter,
        y: Float,
        label: String,
        background: Color,
        foreground: Color
    ) {
        val axisRect = frame.layout.priceAxisRect
        val height = PRICE_TAG_HEIGHT_PX
        val top = (y - height / 2f).coerceIn(axisRect.topEdge, max(axisRect.topEdge, axisRect.bottomEdge - height))
        drawRect(
            color = background,
            topLeft = Offset(axisRect.leftEdge, top),
            size = Size(axisRect.width, height)
        )
        text.draw(
            scope = this,
            text = label,
            x = axisRect.leftEdge + axisRect.width / 2f,
            y = ChartTextPainter.baselineFor(top + height / 2f, text.tag),
            paint = text.tag,
            color = foreground,
            anchor = TextAnchor.CENTER
        )
    }

    /**
     * Time-axis labels plus the time-zone badge.
     *
     * WHY the badge: "styling time" is only trustworthy if the chart says which zone
     * it styled in. A label reading `14:00` is ambiguous across eight zones; the badge
     * removes that ambiguity at a glance and costs one small string per frame.
     */
    private fun DrawScope.drawTimeAxis(frame: ChartFrame, text: ChartTextPainter) {
        val axisRect = frame.layout.timeAxisRect
        if (!axisRect.isUsable) return
        val colors = frame.colors

        drawLine(
            color = colors.axisLine,
            start = Offset(axisRect.leftEdge, axisRect.topEdge),
            end = Offset(axisRect.rightEdge, axisRect.topEdge),
            strokeWidth = 1f
        )

        val baseline = ChartTextPainter.baselineFor(axisRect.topEdge + axisRect.height / 2f, text.axis)
        frame.timeTicks.forEach { tick ->
            if (tick.xPx < axisRect.leftEdge || tick.xPx > axisRect.rightEdge) return@forEach
            text.draw(
                scope = this,
                text = tick.label,
                x = tick.xPx,
                y = baseline,
                paint = text.axis,
                color = colors.axisText,
                anchor = TextAnchor.CENTER
            )
        }

        text.draw(
            scope = this,
            text = frame.state.config.timeZone.badge(),
            x = axisRect.leftEdge + AXIS_LABEL_PADDING_PX,
            y = ChartTextPainter.baselineFor(axisRect.bottomEdge - BADGE_BASELINE_OFFSET_PX, text.paneHeader),
            paint = text.paneHeader,
            color = colors.axisText.copy(alpha = BADGE_ALPHA),
            anchor = TextAnchor.START
        )
    }

    /**
     * Crosshair lines, axis tags and the OHLC tooltip.
     *
     * WHY the tooltip is offset from the crosshair: a box centred on the finger hides
     * exactly the candles the user is inspecting.
     */
    private fun DrawScope.drawCrosshair(frame: ChartFrame, text: ChartTextPainter) {
        val crosshair = frame.state.crosshair ?: return
        val rect = frame.layout.priceRect
        val colors = frame.colors
        val coords = frame.priceCoords
        val x = coords.timeToX(crosshair.time)
        val y = coords.priceToY(crosshair.price)
        if (!rect.containsX(x)) return

        val dash = PathEffect.dashPathEffect(CROSSHAIR_DASH)
        drawLine(
            color = colors.crosshair,
            start = Offset(rect.leftEdge, y),
            end = Offset(rect.rightEdge, y),
            strokeWidth = 1f,
            pathEffect = dash
        )
        drawLine(
            color = colors.crosshair,
            start = Offset(x, rect.topEdge),
            end = Offset(x, rect.bottomEdge),
            strokeWidth = 1f,
            pathEffect = dash
        )

        drawPriceTag(
            frame = frame,
            text = text,
            y = y,
            label = PriceFormatter.format(crosshair.price, frame.state.digits),
            background = colors.priceTagBackground,
            foreground = colors.priceTagText
        )
        drawTimeTag(frame, text, x, TimeAxis.detailedLabel(crosshair.time, frame.zone))
        crosshair.candle?.let { candle -> drawTooltip(frame, text, candle, x, y) }
    }

    /** Filled tag under the time axis showing the crosshair timestamp. */
    private fun DrawScope.drawTimeTag(frame: ChartFrame, text: ChartTextPainter, x: Float, label: String) {
        val axisRect = frame.layout.timeAxisRect
        if (!axisRect.isUsable) return
        val colors = frame.colors
        val width = text.measure(label, text.tag) + PRICE_TAG_PADDING_PX * 2f
        val left = (x - width / 2f).coerceIn(axisRect.leftEdge, max(axisRect.leftEdge, axisRect.rightEdge - width))
        drawRect(
            color = colors.priceTagBackground,
            topLeft = Offset(left, axisRect.topEdge),
            size = Size(width, axisRect.height)
        )
        text.draw(
            scope = this,
            text = label,
            x = left + width / 2f,
            y = ChartTextPainter.baselineFor(axisRect.topEdge + axisRect.height / 2f, text.tag),
            paint = text.tag,
            color = colors.priceTagText,
            anchor = TextAnchor.CENTER
        )
    }

    /**
     * OHLC + change + volume + sessions tooltip for the crossed bar.
     *
     * WHY sessions are listed here: it answers "was this bar London or New York?"
     * without the trader squinting at the shading behind the candles.
     */
    private fun DrawScope.drawTooltip(
        frame: ChartFrame,
        text: ChartTextPainter,
        candle: CandleStick,
        crosshairX: Float,
        crosshairY: Float
    ) {
        val colors = frame.colors
        val digits = frame.state.digits
        val change = candle.close - candle.open
        val sessions = TradingSession.activeAt(candle.time)

        val lines = listOf(
            "${frame.state.pair.value} · ${frame.state.timeframe.value} · ${frame.state.config.timeZone.badge()}",
            TimeAxis.detailedLabel(candle.time, frame.zone),
            "O ${PriceFormatter.format(candle.open, digits)}   H ${PriceFormatter.format(candle.high, digits)}",
            "L ${PriceFormatter.format(candle.low, digits)}   C ${PriceFormatter.format(candle.close, digits)}",
            "Δ ${PriceFormatter.formatSigned(change, digits)}  " +
                PriceFormatter.formatPips(change, frame.state.instrument) +
                "  Vol ${PriceFormatter.formatCompact(candle.volume.toDouble())}",
            if (sessions.isEmpty()) "Market closed" else sessions.joinToString(" · ") { it.displayName }
        )

        val lineHeight = TOOLTIP_LINE_HEIGHT_PX
        val width = lines.maxOf { text.measure(it, text.tooltip) } + TOOLTIP_PADDING_PX * 2f
        val height = lines.size * lineHeight + TOOLTIP_PADDING_PX * 2f
        val pane = frame.layout.priceRect

        // WHY flip near the right edge: the tooltip would otherwise be clipped by the
        // price axis, hiding the close price — the one field the user tapped for.
        var left = crosshairX + TOOLTIP_OFFSET_PX
        if (left + width > pane.rightEdge) left = crosshairX - TOOLTIP_OFFSET_PX - width
        left = left.coerceIn(pane.leftEdge, max(pane.leftEdge, pane.rightEdge - width))

        // WHY prefer above the touch point: the finger is below its own contact
        // position, so the box sits clear of it; below is used only near the top edge.
        var top = crosshairY - height - TOOLTIP_OFFSET_PX
        if (top < pane.topEdge) top = min(crosshairY + TOOLTIP_OFFSET_PX, max(pane.topEdge, pane.bottomEdge - height))

        drawRect(
            color = colors.tooltipBackground,
            topLeft = Offset(left, top),
            size = Size(width, height)
        )
        drawRect(
            color = colors.tooltipBorder,
            topLeft = Offset(left, top),
            size = Size(width, height),
            style = Stroke(width = 1f)
        )

        lines.forEachIndexed { index, line ->
            // WHY monospace from the OHLC rows down: proportional figures make a
            // column of prices ragged, and comparing a high against a low is the
            // whole purpose of the tooltip.
            val paint = if (index >= OHLC_LINE_INDEX) text.tooltipMono else text.tooltip
            val centerY = top + TOOLTIP_PADDING_PX + lineHeight * index + lineHeight / 2f
            text.draw(
                scope = this,
                text = line,
                x = left + TOOLTIP_PADDING_PX,
                y = ChartTextPainter.baselineFor(centerY, paint),
                paint = paint,
                color = colors.tooltipText,
                anchor = TextAnchor.START
            )
        }
    }
}
