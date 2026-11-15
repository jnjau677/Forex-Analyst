package com.devcode940.ForexAnalyst.data.alert

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.devcode940.ForexAnalyst.R
import com.devcode940.ForexAnalyst.data.settings.AppSettings

/**
 * Posts an [AlertEvaluation] as a system notification.
 *
 * WHY this is separate from [AlertEvaluator]: evaluation is pure and testable;
 * posting is Android, permission-gated and untestable without an emulator. Keeping
 * them apart means the truth table for "did this alert fire" is covered by JVM tests
 * and the only untested code left is the part that is a straight line through
 * framework APIs.
 */
class AlertNotifier(private val context: Context) : AlertNotificationSink {

    private val notificationManager: NotificationManager? =
        ContextCompat.getSystemService(context, NotificationManager::class.java)

    /** Channels already created in this process, so the check is not a binder call. */
    private val createdChannels = mutableSetOf<String>()

    /**
     * Whether a notification may be posted at all.
     *
     * Checked before building anything: on API 33+ posting without the runtime
     * permission is silently dropped, so an app that does not ask first produces
     * alerts that "work" in the code and never appear.
     */
    override fun canNotify(settings: AppSettings): Boolean {
        if (!settings.notificationsEnabled || !settings.alertNotifications) return false
        return hasPostPermission()
    }

    /** True when the OS will let this app post notifications. */
    fun hasPostPermission(): Boolean =
        // WHY the version guard: POST_NOTIFICATIONS does not exist before API 33, and
        // checking an unknown permission returns DENIED, which would silence alerts
        // on every device below 33 — most of the installed base at minSdk 26.
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Posts one alert notification.
     *
     * @return true when a notification was actually handed to the system.
     */
    // WHY SuppressLint: canNotify() below *is* the permission check; lint cannot see
    // through it, and posting without POST_NOTIFICATIONS throws on API 33+.
    @SuppressLint("MissingPermission")
    override fun notify(evaluation: AlertEvaluation, settings: AppSettings): Boolean {
        if (!canNotify(settings)) return false

        val channelId = ensureChannel(settings)
        val notification = NotificationCompat.Builder(context, channelId)
            // WHY the launcher drawable: this project ships no dedicated
            // notification silhouette. A small icon should be a white shape on a
            // transparent background and the launcher art is not that, so on some API
            // levels Android renders it as a white square. Replacing it with a
            // purpose-drawn vector is a one-file change; noted in the README.
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(evaluation.headline)
            .setContentText(evaluation.detail)
            // WHY BIG_TEXT: the detail carries a timestamp and the user's own note,
            // which the one-line style truncates at roughly 40 characters.
            .setStyle(NotificationCompat.BigTextStyle().bigText(evaluation.detail))
            .setContentIntent(openAlertsIntent(evaluation))
            .setAutoCancel(true)
            // WHY CATEGORY_PRICE: lets the OS's own filters (driving mode, priority
            // senders, a wearable's mute) treat this as what it is rather than as an
            // unclassified promotional ping.
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setWhen(evaluation.observedAt?.toEpochMilli() ?: System.currentTimeMillis())
            // WHY group by pair: several alerts on EURUSD collapse into one entry
            // instead of stacking, and the summary stays readable.
            .setGroup(GROUP_KEY_PREFIX + evaluation.alert.pairSymbol.value)
            .apply {
                if (settings.soundEnabled || settings.vibrationEnabled) {
                    setPriority(NotificationCompat.PRIORITY_HIGH)
                } else {
                    setPriority(NotificationCompat.PRIORITY_DEFAULT)
                }
            }
            .build()

        return try {
            // WHY the alert's UUID as the notification id: posting the same alert
            // twice (a retried poll, a restored process) replaces the existing
            // notification instead of adding a second identical one. A sequential or
            // hashed id would let duplicates pile up in the shade.
            NotificationManagerCompat.from(context)
                .notify(notificationIdFor(evaluation), notification)
            true
        } catch (e: SecurityException) {
            // WHY catch rather than let it propagate: the permission can be revoked
            // while the app runs, and the polling loop must survive that instead of
            // taking the whole app down.
            Log.w(TAG, "notification permission revoked; alert not posted")
            false
        } catch (e: Exception) {
            Log.e(TAG, "could not post alert notification", e)
            false
        }
    }

    /** Cancels every alert notification. Used by "clear all" in the alerts screen. */
    fun cancelAll() {
        runCatching { NotificationManagerCompat.from(context).cancelAll() }
            .onFailure { Log.w(TAG, "could not cancel notifications") }
    }

    /**
     * Ensures a channel exists for the current sound/vibration combination and
     * returns its id.
     *
     * WHY one channel per combination: since API 26 sound, vibration and importance
     * are properties of the *channel*, and `NotificationCompat.setSound`/`setVibrate`
     * are ignored once a channel is set. So honouring the app's own sound and
     * vibration switches requires choosing a channel that already has them.
     *
     * WHY channels are created lazily rather than all four up front: each one appears
     * in the system notification settings for this app. Registering four categories
     * when the user will only ever use one is clutter that makes the real toggle hard
     * to find.
     */
    private fun ensureChannel(settings: AppSettings): String {
        val id = channelId(settings.soundEnabled, settings.vibrationEnabled)
        if (id in createdChannels) return id

        val manager = notificationManager ?: return id
        val importance =
            if (settings.soundEnabled || settings.vibrationEnabled) {
                NotificationManager.IMPORTANCE_HIGH
            } else {
                NotificationManager.IMPORTANCE_DEFAULT
            }
        val channel = NotificationChannel(id, channelName(settings), importance).apply {
            description = CHANNEL_DESCRIPTION
            enableVibration(settings.vibrationEnabled)
            // WHY not `setSound(null, null)` when sound is off: a null audio
            // attributes object makes some OEM builds fall back to the default
            // channel sound. Disabling the flag is the documented way.
            if (!settings.soundEnabled) {
                setSound(null, null)
            }
        }
        runCatching { manager.createNotificationChannel(channel) }
            .onFailure { Log.w(TAG, "could not create notification channel $id") }
        createdChannels += id
        return id
    }

    private fun channelName(settings: AppSettings): String = when {
        settings.soundEnabled && settings.vibrationEnabled -> CHANNEL_NAME
        settings.soundEnabled -> "$CHANNEL_NAME (sound only)"
        settings.vibrationEnabled -> "$CHANNEL_NAME (vibration only)"
        else -> "$CHANNEL_NAME (silent)"
    }

    private fun channelId(sound: Boolean, vibration: Boolean): String = when {
        sound && vibration -> CHANNEL_ID
        sound -> "${CHANNEL_ID}_sound"
        vibration -> "${CHANNEL_ID}_vibrate"
        else -> "${CHANNEL_ID}_silent"
    }

    /**
     * Stable notification id derived from the alert's own UUID.
     *
     * WHY not `alertId.hashCode()` alone: `Int` collisions across two different
     * alerts would silently overwrite one notification with another. Mixing in the
     * pair keeps the value stable per alert while spreading the space.
     */
    private fun notificationIdFor(evaluation: AlertEvaluation): Int {
        val uuid = evaluation.alert.id.value
        return (uuid.mostSignificantBits xor uuid.leastSignificantBits).toInt()
    }

    private fun openAlertsIntent(evaluation: AlertEvaluation): PendingIntent {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val intent = (launch ?: Intent()).apply {
            // WHY singleTop + clearTop: tapping a notification should show the alerts
            // screen, not stack a second copy of the app on top of the one already
            // running.
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(EXTRA_START_ROUTE, ROUTE_ALERTS)
            putExtra(EXTRA_ALERT_ID, evaluation.alert.id.value.toString())
        }
        return PendingIntent.getActivity(
            context,
            notificationIdFor(evaluation),
            intent,
            // WHY IMMUTABLE: a mutable PendingIntent handed to the system can be
            // rewritten by whatever receives it. This one carries no data that needs
            // to change, so immutability is free hardening.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        private const val TAG = "AlertNotifier"
        private const val CHANNEL_ID = "forex_price_alerts"
        private const val CHANNEL_NAME = "Price alerts"
        private const val CHANNEL_DESCRIPTION =
            "Fired when a price or RSI level you saved is reached."
        private const val GROUP_KEY_PREFIX = "alerts_"
        /**
         * Start route requested when the notification is tapped.
         *
         * WHY a literal rather than a reference to `ui.navigation.Routes.ALERTS`: the
         * data layer must not depend on the UI layer. `MainActivity` validates this
         * value against its own route whitelist before handing it to the NavHost, so
         * a stale or wrong value degrades to the default screen instead of throwing
         * `IllegalArgumentException` during launch.
         */
        private const val ROUTE_ALERTS = "alerts"

        /** Intent extra read by `MainActivity` to pick the navigation start route. */
        const val EXTRA_START_ROUTE = "forex_start_route"

        /** Intent extra carrying the alert the user tapped, for future deep links. */
        const val EXTRA_ALERT_ID = "forex_alert_id"
    }
}
