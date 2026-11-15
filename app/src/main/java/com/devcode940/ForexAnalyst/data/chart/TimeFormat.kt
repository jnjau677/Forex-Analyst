package com.devcode940.ForexAnalyst.data.chart

import com.devcode940.ForexAnalyst.data.model.Timeframe
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * Human-facing time formatting for lists, tooltips and measurement readouts.
 *
 * WHY a dedicated formatter: the app showed absolute timestamps everywhere, so a
 * signal generated 4 minutes ago read `2024-03-15T09:00:00Z` — technically correct
 * and useless. Relative wording answers the question the user actually has ("how
 * stale is this?"), while the absolute time stays available where precision matters
 * (the crosshair tag).
 *
 * All functions take [now] explicitly, which keeps them pure and testable: no
 * hidden clock, no flaky assertions.
 */
object TimeFormatter {

    /**
     * Relative wording for [instant] as seen from [now], e.g. `just now`,
     * `5 min ago`, `in 2 h`.
     *
     * WHY future tense is handled: candle timestamps come from a data feed whose
     * clock can run ahead of the device. Rendering a future bar as "0 min ago"
     * would look like a bug; "in 3 min" is honest.
     */
    fun relative(instant: Instant, now: Instant): String {
        val duration = Duration.between(now, instant)
        val millis = duration.toMillis()
        val absolute = if (millis < 0) -millis else millis
        val suffix = if (millis < 0) "ago" else null

        val text = when {
            absolute < MINUTE_MILLIS -> "just now"
            absolute < HOUR_MILLIS -> plural(absolute / MINUTE_MILLIS, "min")
            absolute < DAY_MILLIS -> plural(absolute / HOUR_MILLIS, "h")
            absolute < WEEK_MILLIS -> plural(absolute / DAY_MILLIS, "d")
            absolute < MONTH_MILLIS -> plural(absolute / WEEK_MILLIS, "w")
            else -> plural(absolute / MONTH_MILLIS, "mo")
        }
        // WHY: "just now" never takes a direction — "in just now" is not English.
        return when {
            text == "just now" -> text
            suffix != null -> "$text $suffix"
            else -> "in $text"
        }
    }

    /**
     * Compact duration, e.g. `1 h 25 min`, `3 d 4 h`.
     *
     * WHY two units and not one: "85 min" is hard to read on a measurement readout,
     * and "1 h" alone hides the 25 minutes that decide whether a pattern is valid.
     */
    fun duration(from: Instant, to: Instant): String {
        val total = Duration.between(from, to).abs()
        val days = total.toDays()
        val hours = total.toHours() % 24
        val minutes = total.toMinutes() % 60
        return when {
            days > 0 && hours > 0 -> "$days d $hours h"
            days > 0 -> "$days d"
            hours > 0 && minutes > 0 -> "$hours h $minutes min"
            hours > 0 -> "$hours h"
            minutes > 0 -> "$minutes min"
            else -> "${total.seconds} s"
        }
    }

    /** Absolute label with the day name, e.g. `Fri 15 Mar 2024, 09:00`. */
    fun absolute(instant: Instant, zone: ZoneId): String =
        TimeAxis.detailedLabel(instant, zone)

    /**
     * Number of [timeframe] bars between two instants (at least 0).
     *
     * WHY: traders count bars, not seconds — "18 bars" tells them whether a move
     * took a session or a week.
     */
    fun barsBetween(from: Instant, to: Instant, timeframe: Timeframe): Long {
        val interval = timeframe.intervalMillis
        if (interval <= 0L) return 0L
        val span = Duration.between(from, to).abs().toMillis()
        return span / interval
    }

    /** `1 min` / `12 min` style pluralization without a formatting library. */
    private fun plural(value: Long, unit: String): String =
        String.format(Locale.US, "%d %s", value, unit)

    private const val MINUTE_MILLIS = 60_000L
    private const val HOUR_MILLIS = 60 * MINUTE_MILLIS
    private const val DAY_MILLIS = 24 * HOUR_MILLIS
    private const val WEEK_MILLIS = 7 * DAY_MILLIS

    /** WHY 30 days: a month label is approximate, and precision beyond that is noise. */
    private const val MONTH_MILLIS = 30 * DAY_MILLIS
}
