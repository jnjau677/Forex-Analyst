package com.devcode940.ForexAnalyst

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * Tracks whether any activity of this app is currently visible.
 *
 * WHY hand-rolled instead of `ProcessLifecycleOwner`: that lives in
 * `androidx.lifecycle:lifecycle-process`, a dependency this project does not have, and
 * adding one to answer a yes/no question is a poor trade. `ActivityLifecycleCallbacks`
 * is framework API, needs no dependency and no annotation processor, and gives exactly
 * the signal the alert monitor needs.
 *
 * WHY counting started activities rather than tracking "the current one": rotation
 * destroys and recreates an activity, and a naive `onResume`/`onPause` flag reads
 * "backgrounded" for the few milliseconds in between. A count only reaches zero when
 * the app really is not on screen.
 *
 * WHY it matters at all: the alert monitor spends metered API quota, and the free
 * tiers are small enough (FCS: 500 credits a month) that polling while the phone is in
 * a pocket would exhaust them. Foreground-only polling is what makes the feature
 * affordable; see `AlertMonitor` for the trade-off that costs.
 */
class ForegroundTracker : Application.ActivityLifecycleCallbacks {

    @Volatile
    private var startedActivities = 0

    /** True while at least one activity of this app is started. */
    val isForeground: Boolean get() = startedActivities > 0

    override fun onActivityStarted(activity: Activity) {
        startedActivities++
    }

    override fun onActivityStopped(activity: Activity) {
        // WHY clamp at zero: an unbalanced callback (a restored process, or an OEM
        // lifecycle quirk) would otherwise drive the count negative and leave the app
        // permanently "backgrounded", silently disabling alerts.
        startedActivities = (startedActivities - 1).coerceAtLeast(0)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
