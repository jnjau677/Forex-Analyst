package com.devcode940.ForexAnalyst.data.chart

import com.devcode940.ForexAnalyst.data.model.CandleStick
import kotlin.math.abs

/**
 * Index maths over a time-ascending candle series.
 *
 * WHY binary search: the chart calls these on every frame while panning. The
 * previous implementation used `indexOfFirst`/`indexOfLast` and `minByOrNull`,
 * i.e. O(n) per call — with 500 candles and a 60 fps pan that is ~90 000
 * comparisons per second for work that takes ~9 steps. Binary search also makes
 * the cost independent of how much history the user loads.
 *
 * All functions require an ascending series; that invariant is guaranteed by
 * [com.devcode940.ForexAnalyst.data.repository.MarketDataRepository.fetchHistoricalCandles],
 * which returns candles in ascending time order (covered by an integration test).
 */
object ChartMath {

    /**
     * Index of the first candle whose time is `>= [targetMillis]`, or
     * `candles.size` when every candle is earlier.
     *
     * WHY this exact contract: it is the standard `lower_bound`, which makes the
     * visible-range computation a two-call operation with no off-by-one guesswork.
     */
    fun lowerBound(candles: List<CandleStick>, targetMillis: Long): Int {
        var low = 0
        var high = candles.size
        while (low < high) {
            // WHY: unsigned shift avoids the (low + high) overflow that breaks
            // naive binary search on large indices.
            val mid = (low + high) ushr 1
            if (candles[mid].timeMillis < targetMillis) low = mid + 1 else high = mid
        }
        return low
    }

    /**
     * Index of the first candle whose time is `> [targetMillis]`, or
     * `candles.size` when none is later.
     */
    fun upperBound(candles: List<CandleStick>, targetMillis: Long): Int {
        var low = 0
        var high = candles.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (candles[mid].timeMillis <= targetMillis) low = mid + 1 else high = mid
        }
        return low
    }

    /**
     * Index of the candle nearest to [targetMillis], or -1 for an empty series.
     *
     * WHY: the crosshair must snap to a real candle; scanning the whole series to
     * find it was the single most expensive per-tap operation in the chart.
     */
    fun nearestIndex(candles: List<CandleStick>, targetMillis: Long): Int {
        if (candles.isEmpty()) return -1
        val atOrAfter = lowerBound(candles, targetMillis)
        // WHY: the nearest candle is either the first one at/after the target or
        // the one immediately before it — no other candidate can be closer.
        return when {
            atOrAfter == 0 -> 0
            atOrAfter >= candles.size -> candles.size - 1
            else -> {
                val before = atOrAfter - 1
                val distanceBefore = abs(candles[before].timeMillis - targetMillis)
                val distanceAfter = abs(candles[atOrAfter].timeMillis - targetMillis)
                if (distanceBefore <= distanceAfter) before else atOrAfter
            }
        }
    }

    /**
     * Inclusive index range of the candles that overlap `[startMillis, endMillis]`,
     * expanded by [buffer] candles on each side.
     *
     * WHY a buffer: a candle body is centred on its timestamp and a wick extends
     * past it, so the first/last partially visible candle must still be drawn or
     * the chart shows a gap at each edge while panning.
     */
    fun visibleRange(
        candles: List<CandleStick>,
        startMillis: Long,
        endMillis: Long,
        buffer: Int = 2
    ): IntRange {
        if (candles.isEmpty()) return IntRange.EMPTY
        val from = (lowerBound(candles, startMillis) - buffer).coerceAtLeast(0)
        // WHY: upperBound (exclusive) already points one past the last visible
        // candle, so the buffer only needs to be added once.
        val to = (upperBound(candles, endMillis) + buffer).coerceAtMost(candles.size)
        return if (from >= to) IntRange.EMPTY else from until to
    }

    /**
     * Lowest low and highest high over `[from, to)`.
     *
     * WHY: the price axis must scale to the **visible** window, not the whole
     * series — otherwise zooming into a flat week of a volatile year renders the
     * candles as a single straight line.
     */
    fun priceBounds(candles: List<CandleStick>, from: Int, to: Int): Pair<Double, Double> {
        if (candles.isEmpty() || from >= to) return 0.0 to 1.0
        val start = from.coerceIn(0, candles.size)
        val end = to.coerceIn(start, candles.size)
        var min = Double.POSITIVE_INFINITY
        var max = Double.NEGATIVE_INFINITY
        for (index in start until end) {
            val candle = candles[index]
            if (candle.low < min) min = candle.low
            if (candle.high > max) max = candle.high
        }
        // WHY: a perfectly flat window (min == max) would make the normalized
        // price mapping divide by zero; widen it by a tick so the line renders
        // in the middle of the pane.
        return if (min == max) (min - 1.0) to (max + 1.0) else min to max
    }
}
