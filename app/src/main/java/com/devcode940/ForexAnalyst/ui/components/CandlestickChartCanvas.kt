package com.devcode940.ForexAnalyst.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.devcode940.ForexAnalyst.data.model.CandleStick
import com.devcode940.ForexAnalyst.data.model.IndicatorState
import kotlin.math.abs

data class CrosshairData(
    val price: Double,
    val timestamp: Long,
    val candle: CandleStick?
)

private val BullishColor = Color(0xFF26A69A)
private val BearishColor = Color(0xFFEF5350)
private val Ema20Color = Color(0xFFFFEB3B)
private val Ema50Color = Color(0xFF2196F3)
private val Ema200Color = Color(0xFFE91E63)
private val RsiColor = Color(0xFFAB47BC)
private val RsiGuideColor = Color(0xFF9E9E9E)

/**
 * Fraction of the canvas height reserved for the RSI sub-panel.
 *
 * WHY: a fixed fraction (not a fixed dp value) keeps the price area usable on
 * small screens and on tablets without a second layout pass.
 */
private const val RSI_PANEL_FRACTION = 0.22f

/** RSI is defined on 0..100 — these are the conventional thresholds. */
private const val RSI_OVERSOLD_LEVEL = 30.0
private const val RSI_OVERBOUGHT_LEVEL = 70.0

/**
 * Interactive candlestick chart with EMA overlays and an optional RSI sub-panel.
 *
 * WHY [showEma]/[showRsi] are parameters: the Settings screen persisted both
 * toggles but nothing consumed them, so switching RSI off changed nothing.
 *
 * @param candles ascending OHLC series to render.
 * @param indicators index-aligned overlay series (see [IndicatorState]).
 * @param showEma draw the EMA overlays.
 * @param showRsi reserve a sub-panel and draw the RSI line with 30/70 guides.
 * @param onCrosshairUpdate invoked with the tapped price/candle, or null on reset.
 * @param modifier layout modifier.
 */
@Composable
fun CandlestickChartCanvas(
    candles: List<CandleStick>,
    indicators: IndicatorState,
    showEma: Boolean = true,
    showRsi: Boolean = false,
    onCrosshairUpdate: (CrosshairData?) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var crosshairPosition by remember { mutableStateOf<Offset?>(null) }

    // WHY: rememberUpdatedState so the pointer-input coroutines (keyed on
    // candles.size, therefore not restarted when a toggle changes) always read
    // the CURRENT preferences instead of a stale captured value.
    val currentShowEma by rememberUpdatedState(showEma)
    val currentShowRsi by rememberUpdatedState(showRsi)
    val currentRsi by rememberUpdatedState(indicators.rsi)

    // WHY: Reusing Path objects avoids allocating on every frame during pan/zoom.
    val bodyPath = remember { Path() }
    val wickPath = remember { Path() }
    val indicatorPath = remember { Path() }
    val rsiPath = remember { Path() }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(candles.size) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(0.5f, 10f)
                    offsetX += pan.x
                }
            }
            .pointerInput(candles.size) {
                detectTapGestures(
                    onTap = { offset ->
                        crosshairPosition = offset
                        if (candles.isNotEmpty()) {
                            // WHY: PointerInputScope.size is IntSize; convert to Size explicitly.
                            // WHY: identical geometry to the draw pass — if the
                            // RSI panel is visible the price area is shorter, and
                            // mapping the tap against the full height would report
                            // the wrong price in the tooltip.
                            val panel = rsiPanelHeight(
                                size.height.toFloat(),
                                currentShowRsi,
                                currentRsi
                            )
                            val canvas = Size(size.width.toFloat(), size.height.toFloat() - panel)
                            val cs = ChartCoordinateSystem(canvas, candles, scale, offsetX)
                            val time = cs.xToTime(offset.x)
                            val nearest = candles.minByOrNull { abs(it.timeMillis - time) }
                            onCrosshairUpdate(
                                CrosshairData(cs.yToPrice(offset.y), time, nearest)
                            )
                        }
                    },
                    onDoubleTap = {
                        scale = 1f
                        offsetX = 0f
                        crosshairPosition = null
                        onCrosshairUpdate(null)
                    }
                )
            }
    ) {
        if (candles.isEmpty()) return@Canvas

        // WHY: reserve the RSI band first, then build the price coordinate
        // system on the REMAINING height — otherwise candles would be drawn
        // underneath the sub-panel.
        val panelHeight = rsiPanelHeight(size.height, currentShowRsi, indicators.rsi)
        val priceArea = Size(size.width, size.height - panelHeight)
        val cs = ChartCoordinateSystem(priceArea, candles, scale, offsetX)
        val range = cs.calculateVisibleRange()
        val from = range.first.coerceIn(0, candles.size)
        val to = range.second.coerceIn(from, candles.size)
        val visible = candles.subList(from, to)

        drawGrid(priceArea.height)
        if (currentShowEma) {
            drawIndicators(indicators, candles, from, to, cs, indicatorPath)
        }
        drawCandles(visible, cs, bodyPath, wickPath)
        if (panelHeight > 0f) {
            val rsi = indicators.rsi
            if (rsi != null) {
                drawRsiPanel(
                    rsi = rsi,
                    allCandles = candles,
                    from = from,
                    to = to,
                    cs = cs,
                    top = priceArea.height,
                    height = panelHeight,
                    path = rsiPath
                )
            }
        }

        val cross = crosshairPosition
        if (cross != null) {
            drawCrosshair(cross)
        }
    }
}

/**
 * Height of the RSI sub-panel in pixels.
 *
 * WHY: one shared calculation for the draw pass and the tap pass — duplicated
 * geometry is how a tooltip ends up reporting the wrong price.
 * Returns 0 when the panel is disabled or has no data yet (leading nulls).
 */
private fun rsiPanelHeight(canvasHeight: Float, showRsi: Boolean, rsi: List<Double?>?): Float {
    if (!showRsi || rsi == null) return 0f
    // WHY: do not reserve space for a panel that cannot draw anything (e.g. a
    // series shorter than the RSI period is all nulls).
    if (rsi.none { it != null }) return 0f
    return canvasHeight * RSI_PANEL_FRACTION
}

/**
 * Converts between price/time domain values and canvas pixels.
 */
class ChartCoordinateSystem(
    private val canvasSize: Size,
    private val candles: List<CandleStick>,
    private val scale: Float,
    private val offsetX: Float
) {
    private val minPrice: Double
    private val maxPrice: Double
    private val startTime: Long
    private val endTime: Long

    init {
        val min = candles.minOfOrNull { it.low } ?: 0.0
        val max = candles.maxOfOrNull { it.high } ?: 1.0
        val padding = (max - min) * 0.05
        minPrice = min - padding
        maxPrice = max + padding
        startTime = candles.firstOrNull()?.timeMillis ?: 0L
        endTime = candles.lastOrNull()?.timeMillis ?: 1L
    }

    fun priceToY(price: Double): Float {
        val range = maxPrice - minPrice
        if (range == 0.0) return canvasSize.height / 2f
        val normalized = ((price - minPrice) / range).toFloat()
        return canvasSize.height * (1f - normalized)
    }

    fun timeToX(timestamp: Long): Float {
        val range = endTime - startTime
        if (range == 0L) return canvasSize.width / 2f
        val normalized = (timestamp - startTime).toFloat() / range.toFloat()
        return canvasSize.width * normalized * scale + offsetX
    }

    fun yToPrice(y: Float): Double {
        if (canvasSize.height == 0f) return minPrice
        val normalized = 1f - (y / canvasSize.height)
        return minPrice + normalized.toDouble() * (maxPrice - minPrice)
    }

    fun xToTime(x: Float): Long {
        val denom = canvasSize.width * scale
        if (denom == 0f) return startTime
        val normalized = (x - offsetX) / denom
        return startTime + (normalized * (endTime - startTime)).toLong()
    }

    /** Returns [fromIndex, toIndexExclusive] with a small buffer for smooth panning. */
    fun calculateVisibleRange(): Pair<Int, Int> {
        if (candles.isEmpty()) return 0 to 0
        val t0 = xToTime(0f)
        val t1 = xToTime(canvasSize.width)
        val lo = minOf(t0, t1)
        val hi = maxOf(t0, t1)

        var startIndex = candles.indexOfFirst { it.timeMillis >= lo }
        if (startIndex < 0) startIndex = 0
        var endIndex = candles.indexOfLast { it.timeMillis <= hi }
        if (endIndex < 0) endIndex = candles.size - 1

        val from = (startIndex - 2).coerceAtLeast(0)
        val to = (endIndex + 3).coerceAtMost(candles.size)
        return from to to.coerceAtLeast(from)
    }

    fun candleWidth(): Float {
        if (candles.size <= 1) return 10f
        val span = (endTime - startTime).toFloat()
        if (span == 0f) return 10f
        val perCandle = span / candles.size
        val pixelsPerMs = (canvasSize.width * scale) / span
        return (perCandle * pixelsPerMs * 0.7f).coerceIn(1f, 50f)
    }
}

/**
 * Horizontal price grid.
 *
 * @param height height of the PRICE area (excludes the RSI sub-panel).
 */
private fun DrawScope.drawGrid(height: Float) {
    val gridColor = Color.Gray.copy(alpha = 0.2f)
    for (i in 0..4) {
        val y = height * i / 4f
        drawLine(
            color = gridColor,
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = 1f
        )
    }
}

private fun DrawScope.drawCandles(
    candles: List<CandleStick>,
    cs: ChartCoordinateSystem,
    bodyPath: Path,
    wickPath: Path
) {
    val width = cs.candleWidth()

    for (candle in candles) {
        val x = cs.timeToX(candle.timeMillis)
        if (x < -width || x > size.width + width) continue

        val openY = cs.priceToY(candle.open)
        val closeY = cs.priceToY(candle.close)
        val highY = cs.priceToY(candle.high)
        val lowY = cs.priceToY(candle.low)

        bodyPath.reset()
        bodyPath.addRect(
            Rect(
                left = x - width / 2f,
                top = minOf(openY, closeY),
                right = x + width / 2f,
                bottom = maxOf(openY, closeY)
            )
        )

        wickPath.reset()
        wickPath.moveTo(x, highY)
        wickPath.lineTo(x, lowY)

        val color = if (candle.close >= candle.open) BullishColor else BearishColor
        drawPath(path = bodyPath, color = color)
        drawPath(
            path = wickPath,
            color = color,
            style = Stroke(width = 1.5f, cap = StrokeCap.Round)
        )
    }
}

private fun DrawScope.drawIndicators(
    indicators: IndicatorState,
    allCandles: List<CandleStick>,
    from: Int,
    to: Int,
    cs: ChartCoordinateSystem,
    path: Path
) {
    indicators.ema20?.let { drawIndicatorLine(it, allCandles, from, to, cs, path, Ema20Color) }
    indicators.ema50?.let { drawIndicatorLine(it, allCandles, from, to, cs, path, Ema50Color) }
    indicators.ema200?.let { drawIndicatorLine(it, allCandles, from, to, cs, path, Ema200Color) }
}

private fun DrawScope.drawIndicatorLine(
    values: List<Double?>,
    allCandles: List<CandleStick>,
    from: Int,
    to: Int,
    cs: ChartCoordinateSystem,
    path: Path,
    color: Color
) {
    path.reset()
    var started = false

    for (index in from until to) {
        val value = values.getOrNull(index) ?: continue
        val candle = allCandles.getOrNull(index) ?: continue
        val x = cs.timeToX(candle.timeMillis)
        val y = cs.priceToY(value)

        if (!started) {
            path.moveTo(x, y)
            started = true
        } else {
            path.lineTo(x, y)
        }
    }

    if (started) {
        drawPath(path = path, color = color, style = Stroke(width = 2.dp.toPx()))
    }
}

/**
 * Draws the RSI sub-panel: separator, 30/70 guides and the RSI line.
 *
 * WHY a sub-panel instead of an overlay: RSI is bounded 0..100 while prices are
 * unbounded — plotting them on one axis makes one of the two unreadable.
 * X coordinates come from the SAME [ChartCoordinateSystem] as the candles, so
 * the RSI value always sits under its own bar.
 */
private fun DrawScope.drawRsiPanel(
    rsi: List<Double?>,
    allCandles: List<CandleStick>,
    from: Int,
    to: Int,
    cs: ChartCoordinateSystem,
    top: Float,
    height: Float,
    path: Path
) {
    fun yFor(value: Double): Float = top + height * (1f - (value / 100.0).toFloat())

    drawLine(
        color = RsiGuideColor,
        start = Offset(0f, top),
        end = Offset(size.width, top),
        strokeWidth = 1f
    )
    listOf(RSI_OVERSOLD_LEVEL, RSI_OVERBOUGHT_LEVEL).forEach { level ->
        val y = yFor(level)
        drawLine(
            color = RsiGuideColor.copy(alpha = 0.45f),
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = 1f
        )
    }

    path.reset()
    var started = false
    for (index in from until to) {
        val value = rsi.getOrNull(index) ?: continue
        val candle = allCandles.getOrNull(index) ?: continue
        val x = cs.timeToX(candle.timeMillis)
        val y = yFor(value)
        if (!started) {
            path.moveTo(x, y)
            started = true
        } else {
            path.lineTo(x, y)
        }
    }
    if (started) {
        drawPath(path = path, color = RsiColor, style = Stroke(width = 1.5.dp.toPx()))
    }
}

private fun DrawScope.drawCrosshair(position: Offset) {
    val color = Color.White.copy(alpha = 0.6f)
    drawLine(
        color = color,
        start = Offset(0f, position.y),
        end = Offset(size.width, position.y),
        strokeWidth = 1f
    )
    drawLine(
        color = color,
        start = Offset(position.x, 0f),
        end = Offset(position.x, size.height),
        strokeWidth = 1f
    )
}