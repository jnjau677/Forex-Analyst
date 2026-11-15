package com.devcode940.ForexAnalyst

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * A [Clock] the test advances by hand.
 *
 * WHY this exists: quota windows, cache TTLs and alert staleness are all arithmetic on
 * "now". Against the system clock those tests either sleep for real minutes or assert
 * on a boundary they cannot reliably land on — the second kind passes locally and fails
 * on a slow CI machine, which is worse than not having the test.
 *
 * WHY not `Clock.offset`: offsetting a fixed clock still cannot be *moved* after
 * construction, and the interesting cases are "one minute later", "the next day".
 */
internal class MutableClock(
    private var current: Instant = Instant.parse("2024-06-01T12:00:00Z"),
    private val zone: ZoneId = ZoneOffset.UTC
) : Clock() {

    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId): Clock = MutableClock(current, zone)

    override fun instant(): Instant = current

    /** Moves the clock forward by [millis]. */
    fun advanceMillis(millis: Long) {
        current = current.plusMillis(millis)
    }

    fun advanceSeconds(seconds: Long) = advanceMillis(seconds * 1_000L)

    fun advanceMinutes(minutes: Long) = advanceMillis(minutes * 60_000L)

    fun advanceHours(hours: Long) = advanceMillis(hours * 3_600_000L)

    fun advanceDays(days: Long) = advanceMillis(days * 86_400_000L)

    /** Sets the clock to an absolute instant. */
    fun setTo(instant: Instant) {
        current = instant
    }
}
