package com.devcode940.ForexAnalyst.data.chart

import com.devcode940.ForexAnalyst.data.model.PriceFormatter
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * "Nice number" tick generation for the price axis.
 *
 * WHY: splitting the visible range into N equal parts produces labels like
 * `1.0837429` — precise, unreadable, and different at every zoom level. Trading
 * charts label round levels (1.0840, 1.0850 …) so a reader can compare two charts
 * of the same instrument at a glance. This is the same "nice numbers" algorithm
 * used by essentially every plotting library, implemented without floating-point
 * accumulation (each tick is computed from the index, so ticks cannot drift).
 */
object PriceAxis {

    /**
     * Tick values spanning `[min, max]`.
     *
     * @param min lowest visible price (already padded).
     * @param max highest visible price (already padded).
     * @param targetTicks desired label count; the result may be one or two off
     *   because the step is rounded to a 1/2/2.5/5 × 10ⁿ value.
     * @param digits decimals used to round the result, normally the instrument's
     *   [com.devcode940.ForexAnalyst.data.model.Instrument.quoteDigits].
     * @return ascending ticks, possibly empty for a degenerate range.
     */
    fun ticks(min: Double, max: Double, targetTicks: Int = 6, digits: Int = 5): List<Double> {
        if (!min.isFinite() || !max.isFinite() || targetTicks <= 0) return emptyList()
        val span = max - min
        if (span <= 0.0) return listOf(PriceFormatter.round(min, digits))

        val step = niceStep(span / targetTicks)
        if (step <= 0.0 || !step.isFinite()) return emptyList()

        // WHY: start at the first multiple of the step at or above min, so labels
        // land on round numbers instead of on min itself.
        val first = ceil(min / step) * step
        val count = floor((max - first) / step).toInt() + 1
        if (count <= 0 || count > MAX_TICKS) return emptyList()

        return (0 until count).map { index ->
            // WHY: multiply the index instead of accumulating `first + step + step…`;
            // accumulation drifts by several ULPs over a long axis and produces
            // labels such as 1.0850000000000002.
            PriceFormatter.round(first + index * step, digits)
        }
    }

    /**
     * Rounds a raw interval up to the nearest 1, 2, 2.5 or 5 × 10ⁿ.
     *
     * WHY these four: they divide evenly into each other and into 10, so zooming in
     * or out steps through a predictable sequence (0.5 → 0.25 → 0.1 → 0.05 …)
     * instead of jumping between unrelated magnitudes.
     */
    fun niceStep(rawStep: Double): Double {
        if (!rawStep.isFinite() || rawStep <= 0.0) return 0.0
        val magnitude = 10.0.pow(floor(log10(rawStep)))
        val normalized = rawStep / magnitude
        val niceNormalized = when {
            normalized <= 1.0 -> 1.0
            normalized <= 2.0 -> 2.0
            normalized <= 2.5 -> 2.5
            normalized <= 5.0 -> 5.0
            else -> 10.0
        }
        return niceNormalized * magnitude
    }

    /**
     * Distance between two adjacent labels in pixels, used to skip labels that
     * would collide.
     *
     * WHY: on a short pane (or a phone in landscape) six labels can overlap into an
     * unreadable smear; the renderer measures the available height against the label
     * height and drops every second tick when they would collide.
     */
    fun labelSpacingPx(paneHeightPx: Float, tickCount: Int): Float {
        if (tickCount <= 1) return paneHeightPx
        return paneHeightPx / (tickCount - 1)
    }

    /** True when [labelHeightPx] labels at [spacingPx] would overlap. */
    fun labelsCollide(spacingPx: Float, labelHeightPx: Float): Boolean = spacingPx < labelHeightPx * 1.2f

    /** Upper bound on generated ticks; beyond this the labels are unreadable anyway. */
    private const val MAX_TICKS = 64
}
