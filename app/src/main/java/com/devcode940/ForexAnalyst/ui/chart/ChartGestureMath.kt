package com.devcode940.ForexAnalyst.ui.chart

import androidx.compose.ui.geometry.Offset
import com.devcode940.ForexAnalyst.data.chart.ChartMath
import com.devcode940.ForexAnalyst.data.chart.ChartViewport
import com.devcode940.ForexAnalyst.data.chart.CrosshairData
import com.devcode940.ForexAnalyst.data.chart.DrawingGeometry
import com.devcode940.ForexAnalyst.data.chart.PixelPoint
import com.devcode940.ForexAnalyst.data.model.ChartDrawing
import com.devcode940.ForexAnalyst.data.model.ChartPoint
import com.devcode940.ForexAnalyst.data.model.MagnetMode
import com.devcode940.ForexAnalyst.data.model.PriceFormatter

/**
 * Converts touch positions into chart data, using a resolved [ChartFrame].
 *
 * WHY these live outside both the composable and the ViewModel: the conversion needs
 * the frame's coordinate mapping (a UI concern) but must not need Compose state, a
 * coroutine scope or a device — so it is testable on the JVM and cannot drift from
 * what the painter drew.
 *
 * WHY every function returns null instead of a fallback: a touch outside the price
 * pane (on an axis, a sub-pane, or before the series loaded) has no meaningful
 * (time, price). Guessing one is how a drawing ends up anchored to a price the user
 * never touched.
 */
internal object ChartGestureMath {

    /**
     * The (time, price) anchor under [position], or null outside the price pane.
     *
     * Three adjustments are applied in order, each for a distinct reason:
     *
     * 1. **Magnet snap** to the nearest candle extreme, because a finger covers the
     *    wick it is trying to mark.
     * 2. **45° angle snap** while dragging a two-anchor tool under
     *    [MagnetMode.STRONG], because a phone has no Shift key and the strongest
     *    magnet setting is the natural place to hang "snap aggressively".
     * 3. **Tick rounding**, because an order at 1.08473126 on a 5-digit pair is not a
     *    price a broker can fill, and persisting it makes every later comparison
     *    against a quoted price fail by a fraction of a pip.
     *
     * @param frame resolved geometry for the current frame.
     * @param position touch position in canvas pixels.
     */
    fun chartPointAt(frame: ChartFrame, position: Offset): ChartPoint? {
        if (!frame.isDrawable) return null
        val rect = frame.layout.priceRect
        if (!rect.contains(position.x, position.y)) return null

        val coords = frame.priceCoords
        val raw = DrawingGeometry.pointFromPixel(PixelPoint(position.x, position.y), coords)
        val snappedPrice = DrawingGeometry.magnetSnap(
            rawPrice = raw.price,
            atTimeMillis = raw.time.toEpochMilli(),
            candles = frame.state.candles,
            cs = coords,
            mode = frame.state.config.magnet
        )
        val angleSnapped = angleSnap(frame, snappedPrice, raw.time.toEpochMilli(), position)
        return ChartPoint(
            time = raw.time,
            price = PriceFormatter.round(angleSnapped, frame.state.digits)
        )
    }

    /**
     * Applies 45° angle snapping to a price while a draft is in progress.
     *
     * WHY the anchor is the draft's own start point: angle snapping is meaningless
     * without a vertex, and the vertex of the line being drawn is exactly where the
     * gesture began. With no draft (a one-anchor tool, or the first event) the price is
     * returned untouched.
     */
    private fun angleSnap(
        frame: ChartFrame,
        price: Double,
        timeMillis: Long,
        position: Offset
    ): Double {
        if (frame.state.config.magnet != MagnetMode.STRONG) return price
        val draft = frame.state.draft ?: return price
        if (!draft.tool.isTwoAnchor) return price

        val coords = frame.priceCoords
        val anchor = PixelPoint(coords.timeToX(draft.start.time), coords.priceToY(draft.start.price))
        val current = PixelPoint(coords.timeToX(timeMillis), coords.priceToY(price))
        if (anchor == current) return price
        val snapped = DrawingGeometry.snapAngle(anchor, current)
        val adjusted = coords.yToPrice(snapped.y)
        // WHY guard the result: a snap that leaves the pane would store a price the
        // chart cannot draw, and the object would look like it vanished on commit.
        return if (adjusted.isFinite()) adjusted else price
    }

    /**
     * Crosshair readout for [position]: the exact price under the finger, snapped to
     * the nearest bar in time.
     *
     * WHY time snaps but price does not: the vertical line should sit on a real candle
     * so the OHLC tooltip is meaningful, while the horizontal line must show the price
     * the user actually pointed at — snapping it to the close would hide the level
     * they are measuring.
     */
    fun crosshairAt(frame: ChartFrame, position: Offset): CrosshairData? {
        if (!frame.isDrawable) return null
        val rect = frame.layout.priceRect
        if (!rect.contains(position.x, position.y)) return null

        val coords = frame.priceCoords
        val candles = frame.state.candles
        val index = ChartMath.nearestIndex(candles, coords.xToTime(position.x))
        if (index < 0) return null
        val price = coords.yToPrice(position.y)
        if (!price.isFinite()) return null
        return CrosshairData(price = price, time = candles[index].time, candle = candles[index])
    }

    /**
     * The topmost drawing under [position], or null when the tap hit empty space.
     *
     * WHY topmost means last: drawings are painted in creation order, so the newest
     * is visually on top and must also win the hit-test — otherwise a tap on a
     * freshly drawn line selects the older one underneath it.
     */
    fun drawingHitAt(frame: ChartFrame, position: Offset): ChartDrawing? {
        if (!frame.isDrawable) return null
        val coords = frame.priceCoords
        val pixel = PixelPoint(position.x, position.y)
        // WHY a generous tolerance: a finger is ~8 mm wide, and a 1.8 px line is
        // essentially unselectable without one.
        val tolerance = DrawingGeometry.DEFAULT_TOLERANCE_PX
        return frame.state.drawings.lastOrNull { drawing ->
            DrawingGeometry.hitTest(drawing, coords, pixel, tolerance)
        }
    }

    /**
     * The viewport after a pinch/pan gesture.
     *
     * WHY the offset is clamped here and not in the ViewModel: the clamp depends on
     * the pane width and the current scale, both of which only the frame knows. The
     * ViewModel would have to duplicate that geometry — and a duplicated clamp is a
     * clamp that eventually disagrees.
     *
     * WHY the clamp uses the pre-zoom mapping: the frame is rebuilt immediately after
     * this event is applied, so the bound is off by at most one frame of zoom. Using
     * the stale scale still prevents the pathological case (series dragged entirely
     * off-screen), and computing a second coordinate system per gesture tick would
     * cost more than the inaccuracy it removes.
     */
    fun nextViewport(frame: ChartFrame, panX: Float, zoom: Float): ChartViewport {
        val current = frame.state.viewport
        val scale = (current.scale * zoom).coerceIn(ChartViewport.MIN_SCALE, ChartViewport.MAX_SCALE)
        val offsetX = frame.priceCoords.clampOffset(current.offsetX + panX)
        return if (scale == current.scale && offsetX == current.offsetX) current
        else ChartViewport(scale = scale, offsetX = offsetX)
    }
}
