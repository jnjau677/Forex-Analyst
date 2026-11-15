package com.devcode940.ForexAnalyst.data.chart

import com.devcode940.ForexAnalyst.data.model.ChartDrawing
import com.devcode940.ForexAnalyst.data.model.ChartPoint
import com.devcode940.ForexAnalyst.data.model.ChartToolMode
import com.devcode940.ForexAnalyst.data.model.DrawingId
import com.devcode940.ForexAnalyst.data.model.DrawingStyle
import com.devcode940.ForexAnalyst.data.model.PairSymbol
import com.devcode940.ForexAnalyst.data.model.Timeframe
import com.devcode940.ForexAnalyst.data.model.TradeSide
import java.time.Instant
import kotlin.math.abs

/**
 * Turns a completed gesture into a persisted [ChartDrawing].
 *
 * WHY this is a separate object: the mapping from "tool + two anchors" to "a valid
 * domain object" is the part of the drawing feature with the most edge cases — a
 * zero-length drag, a long whose stop was dragged upwards, a vertical line with no
 * price. Keeping it here makes every rule unit-testable on the JVM, and keeps the
 * ViewModel free of geometry.
 *
 * WHY it returns null instead of throwing: a gesture that cannot produce a valid
 * object (a tap that never moved while a trendline was armed) is a normal outcome,
 * not an error. The caller drops the draft and, if useful, tells the user why.
 */
object DrawingFactory {

    /**
     * Builds the drawing for a finished gesture.
     *
     * @param tool the armed tool.
     * @param start first anchor (touch-down).
     * @param end second anchor (release); equal to [start] for a one-anchor tool.
     * @param pairSymbol chart the object belongs to.
     * @param timeframe chart the object belongs to.
     * @param style line style chosen in the toolbar.
     * @param colorArgb colour chosen in the toolbar.
     * @param createdAt creation timestamp; injected so tests are deterministic.
     * @param label optional user label, already validated by the caller.
     * @return the drawing, or null when the gesture cannot produce a valid one.
     */
    fun create(
        tool: ChartToolMode,
        start: ChartPoint,
        end: ChartPoint,
        pairSymbol: PairSymbol,
        timeframe: Timeframe,
        style: DrawingStyle,
        colorArgb: Int,
        createdAt: Instant,
        label: String = ""
    ): ChartDrawing? {
        // WHY catch here: every subtype validates its own invariants in `init`, and a
        // gesture can legitimately violate one (coincident anchors). The alternative —
        // pre-checking each rule in this function — would duplicate the invariants and
        // eventually disagree with them.
        return runCatching {
            when (tool) {
                ChartToolMode.HORIZONTAL_LINE -> ChartDrawing.HorizontalLine(
                    id = DrawingId.generate(),
                    pairSymbol = pairSymbol,
                    timeframe = timeframe,
                    style = style,
                    colorArgb = colorArgb,
                    createdAt = createdAt,
                    label = label,
                    price = start.price
                )

                ChartToolMode.VERTICAL_LINE -> ChartDrawing.VerticalLine(
                    id = DrawingId.generate(),
                    pairSymbol = pairSymbol,
                    timeframe = timeframe,
                    style = style,
                    colorArgb = colorArgb,
                    createdAt = createdAt,
                    label = label,
                    time = start.time
                )

                ChartToolMode.TREND_LINE, ChartToolMode.RAY, ChartToolMode.EXTENDED_LINE ->
                    twoAnchor(start, end)?.let { (from, to) ->
                        ChartDrawing.TrendLine(
                            id = DrawingId.generate(),
                            pairSymbol = pairSymbol,
                            timeframe = timeframe,
                            style = style,
                            colorArgb = colorArgb,
                            createdAt = createdAt,
                            label = label,
                            start = from,
                            end = to,
                            // WHY flags and not separate types: a ray is a trendline
                            // whose right end is stretched to the pane edge, so the
                            // stored shape and the hit-test maths stay identical.
                            extendLeft = tool == ChartToolMode.EXTENDED_LINE,
                            extendRight = tool != ChartToolMode.TREND_LINE
                        )
                    }

                ChartToolMode.FIBONACCI -> twoAnchor(start, end)?.let { (from, to) ->
                    ChartDrawing.FibRetracement(
                        id = DrawingId.generate(),
                        pairSymbol = pairSymbol,
                        timeframe = timeframe,
                        style = style,
                        colorArgb = colorArgb,
                        createdAt = createdAt,
                        label = label,
                        start = from,
                        end = to
                    )
                }

                ChartToolMode.FIB_EXTENSION -> twoAnchor(start, end)?.let { (from, to) ->
                    ChartDrawing.FibExtension(
                        id = DrawingId.generate(),
                        pairSymbol = pairSymbol,
                        timeframe = timeframe,
                        style = style,
                        colorArgb = colorArgb,
                        createdAt = createdAt,
                        label = label,
                        start = from,
                        end = to
                    )
                }

                ChartToolMode.RECTANGLE -> twoAnchor(start, end)?.let { (from, to) ->
                    ChartDrawing.Rectangle(
                        id = DrawingId.generate(),
                        pairSymbol = pairSymbol,
                        timeframe = timeframe,
                        style = style,
                        colorArgb = colorArgb,
                        createdAt = createdAt,
                        label = label,
                        start = from,
                        end = to
                    )
                }

                ChartToolMode.MEASURE -> twoAnchor(start, end)?.let { (from, to) ->
                    ChartDrawing.Measurement(
                        id = DrawingId.generate(),
                        pairSymbol = pairSymbol,
                        timeframe = timeframe,
                        style = style,
                        colorArgb = colorArgb,
                        createdAt = createdAt,
                        label = label,
                        start = from,
                        end = to
                    )
                }

                ChartToolMode.LONG_POSITION -> position(
                    TradeSide.LONG, start, end, pairSymbol, timeframe, style, colorArgb, createdAt, label
                )

                ChartToolMode.SHORT_POSITION -> position(
                    TradeSide.SHORT, start, end, pairSymbol, timeframe, style, colorArgb, createdAt, label
                )

                // WHY: navigation modes produce nothing.
                ChartToolMode.NONE, ChartToolMode.PAN -> null
            }
        }.getOrNull()
    }

    /**
     * The two anchors of a drag, or null when the drag had no extent.
     *
     * WHY reject a zero-length drag instead of nudging it apart: a trendline between
     * two identical points has no slope, so it would render as a dot and could never
     * be extended, hit-tested or edited. Refusing to create it is the honest answer.
     */
    private fun twoAnchor(start: ChartPoint, end: ChartPoint): Pair<ChartPoint, ChartPoint>? =
        if (start == end) null else start to end

    /**
     * Builds a position from an entry anchor and a dragged stop distance.
     *
     * WHY the distance is taken as an absolute value: the user drags *away* from the
     * entry to say "this much risk", and dragging in the wrong direction (up while
     * arming a long) should still produce a valid plan rather than a rejected one.
     * The stop is placed on the correct side by [side], and the target mirrors it at
     * the same distance, which is a 1:1 starting plan the user can then edit.
     */
    private fun position(
        side: TradeSide,
        start: ChartPoint,
        end: ChartPoint,
        pairSymbol: PairSymbol,
        timeframe: Timeframe,
        style: DrawingStyle,
        colorArgb: Int,
        createdAt: Instant,
        label: String
    ): ChartDrawing? {
        val entry = start.price
        val distance = abs(end.price - entry)
        if (distance <= 0.0) return null
        val stop = if (side == TradeSide.LONG) entry - distance else entry + distance
        val target = if (side == TradeSide.LONG) entry + distance else entry - distance
        return ChartDrawing.Position(
            id = DrawingId.generate(),
            pairSymbol = pairSymbol,
            timeframe = timeframe,
            style = style,
            colorArgb = colorArgb,
            createdAt = createdAt,
            label = label,
            side = side,
            entryPrice = entry,
            stopPrice = stop,
            targetPrice = target
        )
    }
}
