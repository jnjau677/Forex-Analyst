package com.devcode940.ForexAnalyst.data.chart

import com.devcode940.ForexAnalyst.data.model.Timeframe
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * A "nice" time-axis tick interval.
 *
 * WHY a closed set: an axis that ticks at arbitrary offsets (14:37, 15:22, …) is
 * unreadable. Every step here lands on a boundary a human recognizes — a whole
 * minute, hour, day, week or month — so the label tells the truth about the bar it
 * sits under.
 *
 * [isCalendarStep] marks steps that cannot be expressed as a fixed number of
 * milliseconds (months vary), which is also what makes them DST-safe: calendar
 * steps advance with `plusMonths`, not by adding 30 × 24 h.
 */
enum class TimeTickStep(val displayName: String, val stepMillis: Long, val isCalendarStep: Boolean) {
    MINUTE_1("1 min", 60_000L, false),
    MINUTE_5("5 min", 5 * 60_000L, false),
    MINUTE_15("15 min", 15 * 60_000L, false),
    MINUTE_30("30 min", 30 * 60_000L, false),
    HOUR_1("1 hour", 60 * 60_000L, false),
    HOUR_2("2 hours", 2 * 60 * 60_000L, false),
    HOUR_4("4 hours", 4 * 60 * 60_000L, false),
    HOUR_6("6 hours", 6 * 60 * 60_000L, false),
    HOUR_12("12 hours", 12 * 60 * 60_000L, false),
    DAY_1("1 day", 24 * 60 * 60_000L, false),
    DAY_2("2 days", 2 * 24 * 60 * 60_000L, false),
    WEEK_1("1 week", 7 * 24 * 60 * 60_000L, false),
    MONTH_1("1 month", 30L * 24 * 60 * 60_000L, true);

    /** Steps in ascending order, used for the "smallest step that fits" search. */
    companion object {
        /** All steps, finest first. */
        val ORDERED: List<TimeTickStep> = entries.toList()
    }
}

/**
 * Generates and labels time-axis ticks.
 *
 * WHY "styling time" is a real feature and not cosmetics: the same 500 bars mean
 * completely different things on M1 and on W1. On M1 the axis must read `14:35`, on D1 it must
 * read `12 Mar`, and on W1 `Mar 2024`. Picking the interval from the *visible span*
 * (not from the timeframe alone) is what keeps labels legible at every zoom level,
 * and doing the arithmetic in an explicit [ZoneId] is what keeps a Nairobi user from
 * having to convert UTC in their head.
 *
 * All functions are pure: they take the zone as a parameter, so they are testable
 * without touching the device clock.
 */
object TimeAxis {

    /** Tick interval that yields roughly [targetTicks] labels for a span of [visibleSpanMillis]. */
    fun chooseStep(visibleSpanMillis: Long, targetTicks: Int = 6): TimeTickStep {
        if (visibleSpanMillis <= 0L || targetTicks <= 0) return TimeTickStep.HOUR_1
        // WHY: the smallest step whose duration is at least span/target — using
        // the next-smaller step would produce overlapping labels, using a much
        // larger one would leave the axis nearly empty.
        val desired = visibleSpanMillis.toDouble() / targetTicks.toDouble()
        return TimeTickStep.ORDERED.firstOrNull { it.stepMillis >= desired } ?: TimeTickStep.MONTH_1
    }

    /**
     * Tick interval appropriate for [timeframe] at its default zoom.
     *
     * WHY: the first frame is drawn before the user zooms, so the axis needs a
     * sensible step derived from the bar interval (an H4 chart ticks in days, an M5
     * chart ticks in hours) instead of guessing from an empty span.
     */
    fun defaultStep(timeframe: Timeframe): TimeTickStep = when (timeframe.value) {
        "M1" -> TimeTickStep.MINUTE_15
        "M5" -> TimeTickStep.HOUR_1
        "M15" -> TimeTickStep.HOUR_4
        "H1" -> TimeTickStep.HOUR_12
        "H4" -> TimeTickStep.DAY_1
        "D1" -> TimeTickStep.WEEK_1
        "W1" -> TimeTickStep.MONTH_1
        else -> TimeTickStep.HOUR_12
    }

    /**
     * Tick instants in `(start, end]`, aligned to calendar boundaries in [zone].
     *
     * WHY zone-aligned and not millis-aligned: adding a fixed number of
     * milliseconds across a DST change lands on 02:30 instead of 03:00 local time.
     * Building ticks from a truncated [ZonedDateTime] keeps every label on a round
     * local hour/minute, which is what a trader expects the axis to show.
     *
     * @return at most [maxTicks] instants, ascending; empty when the span is not
     *   usable (a degenerate canvas or a single candle).
     */
    fun ticks(
        start: Instant,
        end: Instant,
        step: TimeTickStep,
        zone: ZoneId,
        maxTicks: Int = 12
    ): List<Instant> {
        if (!end.isAfter(start) || maxTicks <= 0) return emptyList()

        val ticks = ArrayList<Instant>(maxTicks)
        var cursor = truncate(start.atZone(zone), step)
        // WHY: the first aligned boundary usually sits just before the visible
        // window; walk forward until the tick is strictly inside it. The counter is
        // a hard stop — a pathological step/zone combination must never hang the
        // draw pass, because an infinite loop on the UI thread is an ANR.
        var seekGuard = 0
        while (cursor.toInstant() <= start && seekGuard < MAX_ITERATIONS) {
            cursor = advance(cursor, step)
            seekGuard++
        }
        if (cursor.toInstant() <= start) return emptyList()

        var emitGuard = 0
        while (cursor.toInstant() <= end && ticks.size < maxTicks && emitGuard < MAX_ITERATIONS) {
            ticks.add(cursor.toInstant())
            cursor = advance(cursor, step)
            emitGuard++
        }
        return ticks
    }

    /**
     * Label for [instant] at the given [step], in [zone].
     *
     * WHY the midnight rule: on an intraday step, the tick that lands on local
     * midnight is labelled with the date instead of `00:00`, so the axis shows
     * *when the day changed* — otherwise a multi-day intraday chart is a wall of
     * times with no date context.
     */
    fun labelFor(instant: Instant, step: TimeTickStep, zone: ZoneId): String {
        val zoned = instant.atZone(zone)
        return when {
            step == TimeTickStep.MONTH_1 -> MONTH_FORMAT.format(zoned)
            step == TimeTickStep.WEEK_1 || step == TimeTickStep.DAY_1 || step == TimeTickStep.DAY_2 ->
                DAY_FORMAT.format(zoned)
            // Intraday steps: promote the day boundary to a date label.
            zoned.hour == 0 && zoned.minute == 0 -> DAY_FORMAT.format(zoned)
            else -> TIME_FORMAT.format(zoned)
        }
    }

    /**
     * Full-precision label for a single instant (crosshair tag, tooltip).
     *
     * WHY separate from [labelFor]: an axis label must be short enough not to
     * collide with its neighbours, while a crosshair tag has room for the date,
     * the time and the session.
     */
    fun detailedLabel(instant: Instant, zone: ZoneId): String =
        DETAIL_FORMAT.format(instant.atZone(zone))

    /**
     * Truncates [zoned] down to the boundary of [step].
     *
     * WHY each branch is explicit: `truncatedTo(HOURS)` alone cannot express
     * "every 4 hours from local midnight", which is what makes an H1 chart tick at
     * 00:00 / 04:00 / 08:00 rather than at arbitrary offsets.
     */
    private fun truncate(zoned: ZonedDateTime, step: TimeTickStep): ZonedDateTime = when (step) {
        TimeTickStep.MINUTE_1 -> zoned.truncatedTo(ChronoUnit.MINUTES)
        TimeTickStep.MINUTE_5 -> truncateMinutes(zoned, 5)
        TimeTickStep.MINUTE_15 -> truncateMinutes(zoned, 15)
        TimeTickStep.MINUTE_30 -> truncateMinutes(zoned, 30)
        TimeTickStep.HOUR_1 -> zoned.truncatedTo(ChronoUnit.HOURS)
        TimeTickStep.HOUR_2 -> truncateHours(zoned, 2)
        TimeTickStep.HOUR_4 -> truncateHours(zoned, 4)
        TimeTickStep.HOUR_6 -> truncateHours(zoned, 6)
        TimeTickStep.HOUR_12 -> truncateHours(zoned, 12)
        TimeTickStep.DAY_1, TimeTickStep.DAY_2 -> zoned.truncatedTo(ChronoUnit.DAYS)
        TimeTickStep.WEEK_1 -> startOfWeek(zoned)
        TimeTickStep.MONTH_1 -> zoned.truncatedTo(ChronoUnit.DAYS).withDayOfMonth(1)
    }

    /** Advances [zoned] by one [step], staying calendar-correct across DST. */
    private fun advance(zoned: ZonedDateTime, step: TimeTickStep): ZonedDateTime = when (step) {
        TimeTickStep.MINUTE_1 -> zoned.plusMinutes(1)
        TimeTickStep.MINUTE_5 -> zoned.plusMinutes(5)
        TimeTickStep.MINUTE_15 -> zoned.plusMinutes(15)
        TimeTickStep.MINUTE_30 -> zoned.plusMinutes(30)
        TimeTickStep.HOUR_1 -> zoned.plusHours(1)
        TimeTickStep.HOUR_2 -> zoned.plusHours(2)
        TimeTickStep.HOUR_4 -> zoned.plusHours(4)
        TimeTickStep.HOUR_6 -> zoned.plusHours(6)
        TimeTickStep.HOUR_12 -> zoned.plusHours(12)
        TimeTickStep.DAY_1 -> zoned.plusDays(1)
        TimeTickStep.DAY_2 -> zoned.plusDays(2)
        TimeTickStep.WEEK_1 -> zoned.plusWeeks(1)
        TimeTickStep.MONTH_1 -> zoned.plusMonths(1)
    }

    /** Floors the minute field to a multiple of [minutes]. */
    private fun truncateMinutes(zoned: ZonedDateTime, minutes: Int): ZonedDateTime =
        zoned.truncatedTo(ChronoUnit.HOURS).plusMinutes((zoned.minute / minutes) * minutes.toLong())

    /** Floors the hour field to a multiple of [hours] from local midnight. */
    private fun truncateHours(zoned: ZonedDateTime, hours: Int): ZonedDateTime =
        zoned.truncatedTo(ChronoUnit.DAYS).plusHours((zoned.hour / hours) * hours.toLong())

    /** Start of the ISO week (Monday, local midnight). */
    private fun startOfWeek(zoned: ZonedDateTime): ZonedDateTime {
        val day = zoned.truncatedTo(ChronoUnit.DAYS)
        val daysSinceMonday = (day.dayOfWeek.value - DayOfWeek.MONDAY.value).toLong()
        return day.minusDays(daysSinceMonday)
    }

    // WHY: formatters are immutable and expensive to build; these are created once
    // per process instead of once per label (the axis re-labels every frame while
    // panning, which would otherwise allocate ~12 formatters per frame).
    // Locale.US pins the month names so the axis cannot switch language under a
    // user whose device locale changes mid-session.
    private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
    private val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.US)
    private val MONTH_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM yyyy", Locale.US)
    private val DETAIL_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm", Locale.US)

    /** Hard cap on tick generation iterations. */
    private const val MAX_ITERATIONS = 512
}
