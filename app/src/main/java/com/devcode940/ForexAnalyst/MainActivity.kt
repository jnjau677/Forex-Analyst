package com.devcode940.ForexAnalyst

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.devcode940.ForexAnalyst.data.alert.AlertNotifier
import com.devcode940.ForexAnalyst.data.settings.SettingsRepository
import com.devcode940.ForexAnalyst.ui.navigation.ForexNavHost
import com.devcode940.ForexAnalyst.ui.navigation.Routes
import com.devcode940.ForexAnalyst.ui.theme.ForexAnalystTheme

/**
 * Single-activity host for the Compose navigation graph.
 *
 * WHY: The theme preference is collected here (above the NavHost) so a change
 * in Settings recomposes the whole tree once, instead of every screen having
 * to know about appearance.
 */
class MainActivity : ComponentActivity() {

    /**
     * Asks for the notification permission once, on API 33+.
     *
     * WHY here and not in the alert monitor: only an activity may request a runtime
     * permission, and a request made while the app is backgrounded is silently denied.
     *
     * WHY only once, tracked in preferences: Android returns "denied" forever after the
     * user picks "don't ask again", and re-asking on every launch trains people to tap
     * deny without reading. One honest prompt, then the Alerts screen explains how to
     * enable it in system settings.
     */
    private val notificationPermission =
        // WHY the result is ignored: whether the permission is currently granted is
        // answered live by AlertNotifier.hasPostPermission(), so storing a second copy
        // here would only create a value that can go stale.
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            promptPrefs().edit().putBoolean(KEY_PERMISSION_ASKED, true).apply()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settingsRepository = SettingsRepository.getInstance(application)
        val startRoute = resolveStartRoute(intent?.getStringExtra(AlertNotifier.EXTRA_START_ROUTE))

        setContent {
            val settings by settingsRepository.settings.collectAsState()
            ForexAnalystTheme(themeMode = settings.themeMode) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ForexNavHost(startDestination = startRoute)
                }
            }
        }

        maybeRequestNotificationPermission(settingsRepository)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // WHY handled: tapping a notification while the app is already running delivers
        // the intent here, not to onCreate (the activity is launched singleTop). Without
        // this the start route is read once at launch and every later tap looks broken.
        val requested = intent?.getStringExtra(AlertNotifier.EXTRA_START_ROUTE) ?: return
        if (resolveStartRoute(requested) == Routes.CHART_DEFAULT) return
        setIntent(intent)
        // WHY recreate() rather than navigate(): the NavHost's start destination is fixed
        // when it is composed, and this activity owns no NavController to redirect.
        // Recreating is heavier than a navigate() call but correct without threading a
        // controller through the composition - and it happens on a notification tap, not
        // on a frame.
        recreate()
    }

    private fun maybeRequestNotificationPermission(
        settingsRepository: SettingsRepository
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        if (promptPrefs().getBoolean(KEY_PERMISSION_ASKED, false)) return
        // WHY gated on the user's own setting: asking for a permission the app has been
        // told not to use is how an app gets a "why does it need this" review.
        val settings = settingsRepository.settings.value
        if (!settings.notificationsEnabled || !settings.alertNotifications) return
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /**
     * Maps an incoming route request onto a real destination.
     *
     * WHY validated against a whitelist: the NavHost throws `IllegalArgumentException`
     * for an unknown start route, and this value crosses a process boundary (it arrives
     * in an Intent the system built). A stale extra from an older version, or a crafted
     * Intent from another app, would otherwise turn a notification tap into a crash on
     * launch. Anything unrecognised falls back to the chart.
     */
    private fun resolveStartRoute(requested: String?): String = when (requested) {
        Routes.ALERTS -> Routes.ALERTS
        Routes.WATCHLIST -> Routes.WATCHLIST
        Routes.SIGNALS -> Routes.SIGNALS
        Routes.SETTINGS -> Routes.SETTINGS
        Routes.SETTINGS_API_KEYS -> Routes.SETTINGS_API_KEYS
        else -> Routes.CHART_DEFAULT
    }

    private fun promptPrefs() = getSharedPreferences(PROMPT_PREFS, MODE_PRIVATE)

    private companion object {
        const val PROMPT_PREFS = "permission_prompts"
        const val KEY_PERMISSION_ASKED = "notifications_asked"
    }
}
