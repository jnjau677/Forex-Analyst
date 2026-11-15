package com.devcode940.ForexAnalyst.data.alert

import com.devcode940.ForexAnalyst.data.settings.AppSettings

/**
 * The port [AlertMonitor] uses to reach the notification system.
 *
 * WHY an interface rather than using [AlertNotifier] directly: posting a notification
 * needs a `Context`, a `NotificationManager` and a runtime permission, none of which
 * exist on the JVM. Without this seam the monitor's whole decision table — fire, do not
 * fire, persist before posting, respect a blocked permission — could only be tested on
 * an emulator, which means in practice it would not be tested at all.
 *
 * WHY the settings are passed per call instead of read here: the sink stays free of any
 * dependency on the settings repository, so it cannot disagree with the monitor about
 * which preferences were in force when the alert was evaluated.
 */
interface AlertNotificationSink {

    /** Whether a notification may be posted right now (preferences and OS permission). */
    fun canNotify(settings: AppSettings): Boolean

    /**
     * Posts one alert.
     *
     * @return true when the notification actually reached the system. WHY a return
     *   value: a revoked permission makes posting fail silently, and the monitor's state
     *   has to be able to say "fired but not shown" rather than "fired".
     */
    fun notify(evaluation: AlertEvaluation, settings: AppSettings): Boolean
}
