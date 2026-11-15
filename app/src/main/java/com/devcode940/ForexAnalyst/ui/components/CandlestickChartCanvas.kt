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

@Composable
fun CandlestickChartCanvas(
    candles: List<CandleStick>,
    indicators: IndicatorState,
    onCrosshairUpdate: (CrosshairData?) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var crosshairPosition by remember { mutableStateOf<Offset?>(null) }

    // WHY: Reusing Path objects avoids allocating on every frame during pan/zoom.
    val bodyPath = remember { Path() }
    val wickPath = remember { Path() }
    val indicatorPath = remember { Path() }

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
                            val canvas = Size(size.width.toFloat(), size.height.toFloat())
                            val cs = ChartCoordinateSystem(canvas, candles, scale, offsetX)
                            val time = cs.xToTime(offset.x)
                            val nearest = candles.minByOrNull { abs(it.time - time) }
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

        val cs = ChartCoordinateSystem(size, candles, scale, offsetX)
        val range = cs.calculateVisibleRange()
        val from = range.first.coerceIn(0, candles.size)
        val to = range.second.coerceIn(from, candles.size)
        val visible = candles.subList(from, to)

        drawGrid()
        drawIndicators(indicators, candles, from, to, cs, indicatorPath)
        drawCandles(visible, cs, bodyPath, wickPath)

        val cross = crosshairPosition
        if (cross != null) {
            drawCrosshair(cross)
        }
    }
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
        startTime = candles.firstOrNull()?.time ?: 0L
        endTime = candles.lastOrNull()?.time ?: 1L
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

        var startIndex = candles.indexOfFirst { it.time >= lo }
        if (startIndex < 0) startIndex = 0
        var endIndex = candles.indexOfLast { it.time <= hi }
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

private fun DrawScope.drawGrid() {
    val gridColor = Color.Gray.copy(alpha = 0.2f)
    for (i in 0..4) {
        val y = size.height * i / 4f
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
        val x = cs.timeToX(candle.time)
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
        val x = cs.timeToX(candle.time)
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