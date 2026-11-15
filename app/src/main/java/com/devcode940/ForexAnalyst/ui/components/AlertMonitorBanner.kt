package com.devcode940.ForexAnalyst.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.devcode940.ForexAnalyst.ForexApplication
import com.devcode940.ForexAnalyst.data.alert.AlertMonitor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Explains the current state of alert monitoring on the Alerts screen.
 *
 * WHY this exists: an alert that never fires is indistinguishable from a broken alert
 * unless the app says which. There are four separate reasons nothing may happen — no
 * live feed configured, monitoring switched off, notifications blocked, or the feed
 * refusing the key — and each has a different fix in a different place. Guessing costs
 * the user an evening; one line of text costs nothing.
 *
 * WHY it reads the monitor from the application graph instead of the ViewModel: the
 * monitor is process-scoped and outlives every screen, and threading its state through
 * the ViewModel would give the chart's ViewModel a dependency on the alert subsystem
 * for the benefit of one screen.
 */
@Composable
fun AlertMonitorBanner(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // WHY a remembered fallback flow: previews and instrumented tests run with a plain
    // Application, and composing against a null flow would crash the very screen whose
    // job is to explain that nothing is wrong.
    val monitorState: StateFlow<AlertMonitor.State> = remember(context) {
        ForexApplication.graphOf(context)?.alertMonitor?.state
            ?: MutableStateFlow(AlertMonitor.State.IDLE)
    }
    val state by monitorState.collectAsState()

    val message = describe(state) ?: return
    val isError = state.lastError != null || state.notificationsBlocked

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .semantics { contentDescription = message },
        color = if (isError) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        contentColor = if (isError) {
            MaterialTheme.colorScheme.onErrorContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(text = message, style = MaterialTheme.typography.bodySmall)
            state.lastRunAt?.let { ran ->
                Text(
                    text = "Last checked ${TIME_FORMAT.format(ran)}.",
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

/**
 * The one sentence this screen needs, or null when everything is fine and there is
 * nothing to explain.
 *
 * WHY null in the healthy case: a permanent "monitoring is running" banner is noise
 * that teaches the user to skip reading it, which is exactly when a real warning gets
 * skipped too.
 */
private fun describe(state: AlertMonitor.State): String? {
    state.lastError?.let { return it.userMessage }
    return when {
        state.notificationsBlocked ->
            "Alerts are firing but notifications are blocked. Allow notifications for this app in system settings."

        state.waitingForLiveFeed ->
            "Alerts need a live price feed. Add an FCS API or Alpha Vantage key in Settings, then switch the data source off Offline."

        !state.enabled ->
            "Alert monitoring is paused. Turn it on in Settings to have alerts checked while the app is open."

        else -> null
    }
}

/**
 * WHY the device zone and not UTC: this is a wall-clock fact about the user's own phone
 * ("last checked at 14:05"), not a market timestamp. The chart's configurable zone
 * exists for price bars, where the trading session matters; using it here would only
 * make this label disagree with every other clock the user can see.
 */
private val TIME_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm", Locale.US)
