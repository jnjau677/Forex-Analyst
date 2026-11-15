package com.devcode940.ForexAnalyst.ui.chart

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.devcode940.ForexAnalyst.data.chart.ChartCoordinateSystem
import com.devcode940.ForexAnalyst.data.chart.ChartRect
import com.devcode940.ForexAnalyst.data.chart.DraftDrawing
import com.devcode940.ForexAnalyst.data.chart.DrawingGeometry
import com.devcode940.ForexAnalyst.data.chart.PixelPoint
import com.devcode940.ForexAnalyst.data.chart.TimeAxis
import com.devcode940.ForexAnalyst.data.model.ChartDrawing
import com.devcode940.ForexAnalyst.data.model.ChartPoint
import com.devcode940.ForexAnalyst.data.model.ChartToolMode
import com.devcode940.ForexAnalyst.data.model.DrawingPalette
import com.devcode940.ForexAnalyst.data.model.DrawingStyle
import com.devcode940.ForexAnalyst.data.model.PriceFormatter
import com.devcode940.ForexAnalyst.data.model.TradeSide
import java.time.Instant
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Paints user drawings and the draft currently being dragged.
 *
 * WHY the draft goes through the same painters as committed objects: a preview drawn
 * by different code routinely looks *almost* right, so the user commits and the object
 * jumps. Rendering both with identical geometry removes that gap.
 *
 * WHY labels sit on a translucent plate: they are drawn over candles, bands and
 * session shading, and unbacked text is illegible against a busy chart.
 *
 * Every painter here is a [DrawScope] extension so the call sites read as plain
 * drawing verbs and no painter can accidentally use a different scope than its caller.
 */
internal object DrawingRenderer {

    /** Stroke width of a committed drawing. */
    private const val STROKE_PX = 1.8f

    /** Width of the translucent glow drawn under a selected object. */
    private const val SELECTION_GLOW_PX = 6f

    /** Alpha of that glow. */
    private const val SELECTION_ALPHA = 0.22f

    /** Radius of an anchor handle. */
    private const val ANCHOR_RADIUS_PX = 3.5f

    /** Alpha applied to a draft preview. */
    private const val DRAFT_ALPHA = 0.75f

    /** Translucent fill alpha for rectangles, fib zones and position boxes. */
    private const val FILL_ALPHA = 0.13f

    private const val LABEL_PADDING_PX = 4f
    private const val LABEL_HEIGHT_PX = 15f
    private const val LABEL_OFFSET_PX = 6f

    private val DASHED = floatArrayOf(10f, 6f)
    private val DOTTED = floatArrayOf(2f, 4f)

    /**
     * Draws every committed drawing for this chart, then the draft on top.
     *
     * WHY the draft is last: it is what the finger is manipulating right now, so it
     * must never be hidden behind an older object.
     *
     * @param scope canvas draw scope, already clipped to the price pane.
     * @param frame resolved geometry.
     * @param text reusable paints.
     */
    fun draw(scope: DrawScope, frame: ChartFrame, text: ChartTextPainter) {
        val coords = frame.priceCoords
        val state = frame.state

        state.drawings.forEach { drawing ->
            scope.drawOne(frame, text, coords, drawing, selected = drawing.id == state.selectedDrawingId)
        }
        state.draft?.let { draft -> scope.drawDraft(frame, text, coords, draft) }
    }

    /**
     * Dispatches one drawing to its painter.
     *
     * WHY exhaustive: a new tool added to [ChartDrawing] becomes a compile error here
     * until it can actually be rendered — an invisible drawing is the worst possible
     * failure mode for a tool the user just took care to place.
     */
    private fun DrawScope.drawOne(
        frame: ChartFrame,
        text: ChartTextPainter,
        coords: ChartCoordinateSystem,
        drawing: ChartDrawing,
        selected: Boolean
    ) {
        val color = drawingColor(drawing.colorArgb)
        val effect = pathEffectFor(drawing.style)
        if (selected) drawSelection(frame, coords, drawing)

        when (drawing) {
            is ChartDrawing.HorizontalLine ->
                drawHorizontal(frame, text, coords, drawing.price, color, effect, drawing.label)

            is ChartDrawing.VerticalLine ->
                drawVertical(frame, text, coords, drawing.time, color, effect, drawing.label)

            is ChartDrawing.TrendLine ->
                drawTrend(frame, text, coords, drawing, color, effect, selected)

            is ChartDrawing.FibRetracement ->
                drawFib(frame, text, coords, drawing.start, drawing.end, drawing.levels, drawing.label, selected)

            is ChartDrawing.FibExtension ->
                drawFib(frame, text, coords, drawing.start, drawing.end, drawing.levels, drawing.label, selected)

            is ChartDrawing.Rectangle ->
                drawRectangle(frame, text, coords, drawing.start, drawing.end, color, effect, drawing.label)

            is ChartDrawing.Measurement ->
                drawMeasurement(frame, text, coords, drawing, color)

            is ChartDrawing.Position ->
                drawPosition(frame, text, coords, drawing)
        }
    }

    /**
     * Draws the in-progress gesture.
     *
     * WHY a `when` on the tool instead of constructing a temporary [ChartDrawing]:
     * several subtypes reject invalid input in their `init` (a long whose stop sits
     * above its entry, coincident anchors), and a half-dragged gesture is exactly such
     * a state. Building the real object mid-drag would throw on the UI thread; drawing
     * the preview geometry directly cannot.
     */
    private fun DrawScope.drawDraft(
        frame: ChartFrame,
        text: ChartTextPainter,
        coords: ChartCoordinateSystem,
        draft: DraftDrawing
    ) {
        val color = drawingColor(frame.state.pendingColorArgb).copy(alpha = DRAFT_ALPHA)
        val effect = pathEffectFor(frame.state.pendingStyle)
        val start = draft.start
        val end = draft.current

        when (draft.tool) {
            ChartToolMode.HORIZONTAL_LINE ->
                drawHorizontal(frame, text, coords, start.price, color, effect, label = "")

            ChartToolMode.VERTICAL_LINE ->
                drawVertical(frame, text, coords, start.time, color, effect, label = "")

            ChartToolMode.TREND_LINE ->
                drawSegment(coords, start, end, color, effect, handles = true)

            ChartToolMode.RAY ->
                drawSegment(coords, start, end, color, effect, handles = true, extendRight = true)

            ChartToolMode.EXTENDED_LINE ->
                drawSegment(coords, start, end, color, effect, handles = true, extendLeft = true, extendRight = true)

            ChartToolMode.FIBONACCI ->
                drawFib(frame, text, coords, start, end, ChartDrawing.FibRetracement.DEFAULT_LEVELS, "", selected = true)

            ChartToolMode.FIB_EXTENSION ->
                drawFib(frame, text, coords, start, end, ChartDrawing.FibExtension.DEFAULT_LEVELS, "", selected = true)

            ChartToolMode.RECTANGLE ->
                drawRectangle(frame, text, coords, start, end, color, effect, label = "")

            ChartToolMode.MEASURE ->
                if (start != end) {
                    drawMeasurementBody(frame, text, coords, start, end, color, effect)
                }

            ChartToolMode.LONG_POSITION ->
                drawPositionPreview(frame, text, coords, TradeSide.LONG, start, end)

            ChartToolMode.SHORT_POSITION ->
                drawPositionPreview(frame, text, coords, TradeSide.SHORT, start, end)

            // WHY: crosshair and pan produce no geometry.
            ChartToolMode.NONE, ChartToolMode.PAN -> Unit
        }
    }

    /** A price level spanning the pane, labelled at the left edge. */
    private fun DrawScope.drawHorizontal(
        frame: ChartFrame,
        text: ChartTextPainter,
        coords: ChartCoordinateSystem,
        price: Double,
        color: Color,
        effect: PathEffect?,
        label: String
    ) {
        val rect = frame.layout.priceRect
        val y = coords.priceToY(price)
        if (y < rect.topEdge || y > rect.bottomEdge) return
        drawLine(
            color = color,
            start = Offset(rect.leftEdge, y),
            end = Offset(rect.rightEdge, y),
            strokeWidth = STROKE_PX,
            pathEffect = effect
        )
        // WHY the price doubles as the caption: an unlabelled level still has to be
        // readable, and the level's own value is the only label that is always correct.
        drawLabel(frame, text, label.ifBlank { PriceFormatter.format(price, frame.state.digits) }, color,
            rect.leftEdge + LABEL_OFFSET_PX, y - LABEL_OFFSET_PX)
    }

    /** A time marker spanning the pane height, labelled with the zone-styled time. */
    private fun DrawScope.drawVertical(
        frame: ChartFrame,
        text: ChartTextPainter,
        coords: ChartCoordinateSystem,
        time: Instant,
        color: Color,
        effect: PathEffect?,
        label: String
    ) {
        val rect = frame.layout.priceRect
        val x = coords.timeToX(time)
        if (x < rect.leftEdge || x > rect.rightEdge) return
        drawLine(
            color = color,
            start = Offset(x, rect.topEdge),
            end = Offset(x, rect.bottomEdge),
            strokeWidth = STROKE_PX,
            pathEffect = effect
        )
        // WHY the caption is styled in the chart's zone: this is a *time* annotation,
        // so it must read in the same zone as the axis underneath it, or the two
        // disagree about when the marked event happened.
        val caption = label.ifBlank {
            TimeAxis.labelFor(time, TimeAxis.defaultStep(frame.state.timeframe), frame.zone)
        }
        drawLabel(frame, text, caption, color, x + LABEL_OFFSET_PX, rect.topEdge + LABEL_OFFSET_PX + LABEL_HEIGHT_PX)
    }

    /** A committed trendline, ray or extended line. */
    private fun DrawScope.drawTrend(
        frame: ChartFrame,
        text: ChartTextPainter,
        coords: ChartCoordinateSystem,
        drawing: ChartDrawing.TrendLine,
        color: Color,
        effect: PathEffect?,
        selected: Boolean
    ) {
        val segment = DrawingGeometry.projectedSegment(drawing, coords)
        if (segment == null) {
            // WHY: a vertical trendline has no slope to project, so draw the two
            // anchors joined directly instead of dropping the object entirely.
            drawSegment(coords, drawing.start, drawing.end, color, effect, handles = selected)
            return
        }
        drawLine(
            color = color,
            start = Offset(segment.first.x, segment.first.y),
            end = Offset(segment.second.x, segment.second.y),
            strokeWidth = STROKE_PX,
            pathEffect = effect,
            cap = StrokeCap.Round
        )
        if (!selected) return
        drawAnchor(coords, drawing.start, color)
        drawAnchor(coords, drawing.end, color)
        // WHY the stats appear only while selected: a permanent readout on every
        // trendline would clutter a chart that legitimately carries a dozen of them.
        val stats = DrawingGeometry.stats(drawing.start, drawing.end, frame.state.timeframe, frame.state.instrument)
        drawLabel(frame, text, stats.summary(frame.state.instrument), color,
            min(segment.first.x, segment.second.x) + LABEL_OFFSET_PX,
            min(segment.first.y, segment.second.y) - LABEL_OFFSET_PX)
    }

    /** Straight two-anchor segment, optionally extended to the pane edges. */
    private fun DrawScope.drawSegment(
        coords: ChartCoordinateSystem,
        start: ChartPoint,
        end: ChartPoint,
        color: Color,
        effect: PathEffect?,
        handles: Boolean,
        extendLeft: Boolean = false,
        extendRight: Boolean = false
    ) {
        val a = PixelPoint(coords.timeToX(start.time), coords.priceToY(start.price))
        val b = PixelPoint(coords.timeToX(end.time), coords.priceToY(end.price))
        val deltaMillis = (end.time.toEpochMilli() - start.time.toEpochMilli()).toDouble()
        val slope = if (deltaMillis == 0.0) null else (end.price - start.price) / deltaMillis

        val from = if (extendLeft && slope != null) {
            val leftTime = coords.xToTime(coords.rect.leftEdge)
            PixelPoint(
                coords.rect.leftEdge,
                coords.priceToY(start.price + slope * (leftTime - start.time.toEpochMilli()))
            )
        } else {
            a
        }
        val to = if (extendRight && slope != null) {
            val rightTime = coords.xToTime(coords.rect.rightEdge)
            PixelPoint(
                coords.rect.rightEdge,
                coords.priceToY(start.price + slope * (rightTime - start.time.toEpochMilli()))
            )
        } else {
            b
        }

        drawLine(
            color = color,
            start = Offset(from.x, from.y),
            end = Offset(to.x, to.y),
            strokeWidth = STROKE_PX,
            pathEffect = effect,
            cap = StrokeCap.Round
        )
        if (handles) {
            drawAnchor(coords, start, color)
            drawAnchor(coords, end, color)
        }
    }

    /** Fibonacci levels between two anchors, each labelled with its fraction and price. */
    private fun DrawScope.drawFib(
        frame: ChartFrame,
        text: ChartTextPainter,
        coords: ChartCoordinateSystem,
        start: ChartPoint,
        end: ChartPoint,
        fractions: List<Double>,
        label: String,
        selected: Boolean
    ) {
        if (start == end) return
        val rect = frame.layout.priceRect
        val levels = DrawingGeometry.fibLevels(start.price, end.price, fractions)
        // WHY the zone spans the anchors and no further: a fib retracement describes
        // the measured swing; stretching it across the whole pane implies the levels
        // apply everywhere, which they do not.
        val startX = coords.timeToX(start.time)
        val endX = coords.timeToX(end.time)
        val leftX = min(startX, endX)
        val rightX = max(startX, endX)
        val topY = coords.priceToY(max(start.price, end.price))
        val bottomY = coords.priceToY(min(start.price, end.price))

        drawRect(
            color = drawingColor(frame.state.pendingColorArgb).copy(alpha = FILL_ALPHA),
            topLeft = Offset(leftX, topY),
            size = Size(max(rightX - leftX, 1f), max(bottomY - topY, 1f))
        )
        levels.forEachIndexed { index, level ->
            val y = coords.priceToY(level.price)
            if (y < rect.topEdge - LABEL_HEIGHT_PX || y > rect.bottomEdge + LABEL_HEIGHT_PX) return@forEachIndexed
            val color = fibColor(index, levels.size)
            drawLine(
                color = color,
                start = Offset(leftX, y),
                end = Offset(rightX, y),
                strokeWidth = STROKE_PX
            )
            // WHY fraction and price together: the fraction says which level it is,
            // the price says where the order goes.
            drawLabel(frame, text, "${level.label}  ${PriceFormatter.format(level.price, frame.state.digits)}",
                color, leftX + LABEL_OFFSET_PX, y - LABEL_OFFSET_PX)
        }
        if (label.isNotBlank()) {
            drawLabel(frame, text, label, frame.colors.axisText, leftX + LABEL_OFFSET_PX, bottomY + LABEL_HEIGHT_PX)
        }
        if (selected) {
            drawAnchor(coords, start, frame.colors.selectedOutline)
            drawAnchor(coords, end, frame.colors.selectedOutline)
        }
    }

    /**
     * Colour for one fib level.
     *
     * WHY index-based rather than level-based: the level *set* is user-configurable,
     * so keying colours to specific fractions would leave custom levels uncoloured.
     * Cycling the palette by position keeps neighbours distinguishable for any set.
     */
    private fun fibColor(index: Int, total: Int): Color {
        val palette = DrawingPalette.FIB_LEVELS
        if (palette.isEmpty()) return Color.White
        val scaled = if (total <= 1) 0 else index * palette.size / total
        return drawingColor(palette[scaled.coerceIn(0, palette.size - 1)])
    }

    /** A filled zone between two corners (support/resistance). */
    private fun DrawScope.drawRectangle(
        frame: ChartFrame,
        text: ChartTextPainter,
        coords: ChartCoordinateSystem,
        start: ChartPoint,
        end: ChartPoint,
        color: Color,
        effect: PathEffect?,
        label: String
    ) {
        if (start == end) return
        val left = min(coords.timeToX(start.time), coords.timeToX(end.time))
        val right = max(coords.timeToX(start.time), coords.timeToX(end.time))
        val top = min(coords.priceToY(start.price), coords.priceToY(end.price))
        val bottom = max(coords.priceToY(start.price), coords.priceToY(end.price))

        drawRect(
            color = color.copy(alpha = FILL_ALPHA),
            topLeft = Offset(left, top),
            size = Size(max(right - left, 1f), max(bottom - top, 1f))
        )
        drawRect(
            color = color,
            topLeft = Offset(left, top),
            size = Size(max(right - left, 1f), max(bottom - top, 1f)),
            style = Stroke(width = STROKE_PX, pathEffect = effect)
        )
        if (label.isNotBlank()) {
            drawLabel(frame, text, label, color, left + LABEL_OFFSET_PX, top - LABEL_OFFSET_PX)
        }
    }

    /** A committed measurement. */
    private fun DrawScope.drawMeasurement(
        frame: ChartFrame,
        text: ChartTextPainter,
        coords: ChartCoordinateSystem,
        drawing: ChartDrawing.Measurement,
        color: Color
    ) {
        drawMeasurementBody(frame, text, coords, drawing.start, drawing.end, color, pathEffectFor(drawing.style))
    }

    /**
     * Measurement box with the Δ price / % / pips / bars readout.
     *
     * WHY the readout is centred in the box: the box *is* the measurement, so the
     * number belongs inside it rather than floating to one side where the next drag
     * can cover it.
     */
    private fun DrawScope.drawMeasurementBody(
        frame: ChartFrame,
        text: ChartTextPainter,
        coords: ChartCoordinateSystem,
        start: ChartPoint,
        end: ChartPoint,
        color: Color,
        effect: PathEffect?
    ) {
        val stats = DrawingGeometry.stats(start, end, frame.state.timeframe, frame.state.instrument)
        val left = min(coords.timeToX(start.time), coords.timeToX(end.time))
        val right = max(coords.timeToX(start.time), coords.timeToX(end.time))
        val top = min(coords.priceToY(start.price), coords.priceToY(end.price))
        val bottom = max(coords.priceToY(start.price), coords.priceToY(end.price))

        drawRect(
            color = color.copy(alpha = FILL_ALPHA),
            topLeft = Offset(left, top),
            size = Size(max(right - left, 1f), max(bottom - top, 1f))
        )
        drawRect(
            color = color,
            topLeft = Offset(left, top),
            size = Size(max(right - left, 1f), max(bottom - top, 1f)),
            style = Stroke(width = STROKE_PX, pathEffect = effect)
        )

        val caption = stats.summary(frame.state.instrument)
        val width = text.measure(caption, text.drawingLabel) + LABEL_PADDING_PX * 2f
        val centerX = (left + right) / 2f
        val centerY = (top + bottom) / 2f
        drawRect(
            color = frame.colors.tooltipBackground,
            topLeft = Offset(centerX - width / 2f, centerY - LABEL_HEIGHT_PX / 2f),
            size = Size(width, LABEL_HEIGHT_PX)
        )
        text.draw(
            scope = this,
            text = caption,
            x = centerX,
            y = ChartTextPainter.baselineFor(centerY, text.drawingLabel),
            paint = text.drawingLabel,
            color = frame.colors.tooltipText,
            anchor = TextAnchor.CENTER
        )
    }

    /**
     * Long/short position planner: entry, stop and target levels, risk and reward
     * boxes, and the R:R readout.
     *
     * WHY boxes and not just three lines: the area between entry and stop is the money
     * at risk and the area between entry and target is the money at reward. Making
     * both visible turns an abstract ratio into something the eye can compare.
     */
    private fun DrawScope.drawPosition(
        frame: ChartFrame,
        text: ChartTextPainter,
        coords: ChartCoordinateSystem,
        drawing: ChartDrawing.Position
    ) {
        val colors = frame.colors
        val rect = frame.layout.priceRect
        val entryY = coords.priceToY(drawing.entryPrice)
        val stopY = coords.priceToY(drawing.stopPrice)
        val targetY = coords.priceToY(drawing.targetPrice)
        val digits = frame.state.digits

        drawRiskBoxes(frame.colors, rect, entryY, stopY, targetY, 1f)
        drawLevel(rect, entryY, colors.positionEntry, null)
        drawLevel(rect, stopY, colors.positionStop, pathEffectFor(DrawingStyle.DASHED))
        drawLevel(rect, targetY, colors.positionTarget, pathEffectFor(DrawingStyle.DASHED))

        drawLabel(frame, text, "${drawing.side.displayName} · ${PriceFormatter.format(drawing.entryPrice, digits)}",
            colors.tooltipText, rect.leftEdge + LABEL_OFFSET_PX, entryY - LABEL_OFFSET_PX)
        drawLabel(frame, text, "Stop ${PriceFormatter.format(drawing.stopPrice, digits)}  −${PriceFormatter.format(drawing.riskPerUnit, digits)}",
            colors.positionStop, rect.leftEdge + LABEL_OFFSET_PX, stopY - LABEL_OFFSET_PX)
        drawLabel(frame, text, "Target ${PriceFormatter.format(drawing.targetPrice, digits)}  +${PriceFormatter.format(drawing.rewardPerUnit, digits)}",
            colors.positionTarget, rect.leftEdge + LABEL_OFFSET_PX, targetY - LABEL_OFFSET_PX)
        // WHY the ratio is drawn last, at the right edge by the target: it is the
        // summary of the whole plan, so it belongs at the end of the reward box rather
        // than buried at the entry.
        drawLabel(frame, text,
            "R:R ${PriceFormatter.format(drawing.riskRewardRatio, 2)} · risk ${PriceFormatter.format(drawing.riskPercent, 2)}%",
            colors.positionTarget, rect.rightEdge - LABEL_OFFSET_PX, targetY - LABEL_OFFSET_PX, TextAnchor.END)
        if (drawing.label.isNotBlank()) {
            drawLabel(frame, text, drawing.label, colors.axisText, rect.leftEdge + LABEL_OFFSET_PX, entryY + LABEL_HEIGHT_PX)
        }
    }

    /**
     * Position preview while dragging.
     *
     * WHY stop and target are derived from the drag distance: the tool needs three
     * levels but a drag supplies two, and a symmetric 1:1 preview is the least
     * surprising reading of "drag from entry to stop". The committed object keeps the
     * same distances, so what the user sees is what is stored.
     */
    private fun DrawScope.drawPositionPreview(
        frame: ChartFrame,
        text: ChartTextPainter,
        coords: ChartCoordinateSystem,
        side: TradeSide,
        start: ChartPoint,
        end: ChartPoint
    ) {
        val colors = frame.colors
        val rect = frame.layout.priceRect
        val entry = start.price
        val distance = abs(end.price - entry)
        if (distance <= 0.0) return

        val isLong = side == TradeSide.LONG
        val stop = if (isLong) entry - distance else entry + distance
        val target = if (isLong) entry + distance else entry - distance
        val entryY = coords.priceToY(entry)
        val stopY = coords.priceToY(stop)
        val targetY = coords.priceToY(target)

        drawRiskBoxes(colors, rect, entryY, stopY, targetY, DRAFT_ALPHA)
        drawLevel(rect, entryY, colors.positionEntry.copy(alpha = DRAFT_ALPHA), null)
        drawLevel(rect, stopY, colors.positionStop.copy(alpha = DRAFT_ALPHA), pathEffectFor(DrawingStyle.DASHED))
        drawLevel(rect, targetY, colors.positionTarget.copy(alpha = DRAFT_ALPHA), pathEffectFor(DrawingStyle.DASHED))
        drawLabel(frame, text,
            "${side.displayName} ${PriceFormatter.format(entry, frame.state.digits)} → " +
                PriceFormatter.format(target, frame.state.digits) + "  R:R 1.00",
            colors.tooltipText, rect.leftEdge + LABEL_OFFSET_PX, entryY - LABEL_OFFSET_PX)
    }

    /**
     * Risk box (entry→stop) and reward box (entry→target).
     *
     * WHY the palette is a parameter: these two boxes are semantic — red is money at
     * risk, green is money at reward — and the colours live in [ChartColors] so both
     * the committed object and its preview agree. Looking the palette up here instead
     * would hardcode a theme and make the preview differ from the result.
     */
    private fun DrawScope.drawRiskBoxes(
        colors: ChartColors,
        rect: ChartRect,
        entryY: Float,
        stopY: Float,
        targetY: Float,
        alpha: Float
    ) {
        drawRect(
            color = colors.positionStop.copy(alpha = FILL_ALPHA * alpha),
            topLeft = Offset(rect.leftEdge, min(entryY, stopY)),
            size = Size(rect.width, max(abs(stopY - entryY), 1f))
        )
        drawRect(
            color = colors.positionTarget.copy(alpha = FILL_ALPHA * alpha),
            topLeft = Offset(rect.leftEdge, min(entryY, targetY)),
            size = Size(rect.width, max(abs(targetY - entryY), 1f))
        )
    }

    /** One horizontal level line across the pane. */
    private fun DrawScope.drawLevel(rect: ChartRect, y: Float, color: Color, effect: PathEffect?) {
        if (y < rect.topEdge || y > rect.bottomEdge) return
        drawLine(
            color = color,
            start = Offset(rect.leftEdge, y),
            end = Offset(rect.rightEdge, y),
            strokeWidth = STROKE_PX,
            pathEffect = effect
        )
    }

    /** A small filled circle marking an editable anchor. */
    private fun DrawScope.drawAnchor(coords: ChartCoordinateSystem, point: ChartPoint, color: Color) {
        drawCircle(
            color = color,
            radius = ANCHOR_RADIUS_PX,
            center = Offset(coords.timeToX(point.time), coords.priceToY(point.price))
        )
    }

    /** Translucent glow behind a selected object, along each of its anchor levels. */
    private fun DrawScope.drawSelection(frame: ChartFrame, coords: ChartCoordinateSystem, drawing: ChartDrawing) {
        val color = frame.colors.selectedOutline.copy(alpha = SELECTION_ALPHA)
        val rect = frame.layout.priceRect
        // WHY glow the anchors rather than outline the whole object: a fib or a
        // measurement has no meaningful outline to thicken, but every object has
        // anchor levels, so one rule highlights all of them consistently.
        drawing.anchorPrices.forEach { price ->
            val y = coords.priceToY(price)
            if (y < rect.topEdge || y > rect.bottomEdge) return@forEach
            drawLine(
                color = color,
                start = Offset(rect.leftEdge, y),
                end = Offset(rect.rightEdge, y),
                strokeWidth = SELECTION_GLOW_PX
            )
        }
        drawing.anchorTimes.forEach { time ->
            val x = coords.timeToX(time)
            if (x < rect.leftEdge || x > rect.rightEdge) return@forEach
            drawLine(
                color = color,
                start = Offset(x, rect.topEdge),
                end = Offset(x, rect.bottomEdge),
                strokeWidth = SELECTION_GLOW_PX
            )
        }
    }

    /** A caption drawn on a translucent plate. */
    private fun DrawScope.drawLabel(
        frame: ChartFrame,
        text: ChartTextPainter,
        caption: String,
        color: Color,
        x: Float,
        y: Float,
        anchor: TextAnchor = TextAnchor.START
    ) {
        if (caption.isEmpty()) return
        val width = text.measure(caption, text.drawingLabel) + LABEL_PADDING_PX * 2f
        val plateLeft = when (anchor) {
            TextAnchor.START -> x - LABEL_PADDING_PX
            TextAnchor.CENTER -> x - width / 2f
            TextAnchor.END -> x - width + LABEL_PADDING_PX
        }
        drawRect(
            color = frame.colors.tooltipBackground,
            topLeft = Offset(plateLeft, y - LABEL_HEIGHT_PX),
            size = Size(width, LABEL_HEIGHT_PX)
        )
        text.draw(
            scope = this,
            text = caption,
            x = x,
            y = ChartTextPainter.baselineFor(y - LABEL_HEIGHT_PX / 2f, text.drawingLabel),
            paint = text.drawingLabel,
            color = color,
            anchor = anchor
        )
    }

    /** Dash effect for a [DrawingStyle]; null for solid. */
    private fun pathEffectFor(style: DrawingStyle): PathEffect? = when (style) {
        DrawingStyle.SOLID -> null
        DrawingStyle.DASHED -> PathEffect.dashPathEffect(DASHED)
        DrawingStyle.DOTTED -> PathEffect.dashPathEffect(DOTTED)
    }
}
