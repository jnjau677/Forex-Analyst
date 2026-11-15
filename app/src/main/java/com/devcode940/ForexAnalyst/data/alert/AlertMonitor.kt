package com.devcode940.ForexAnalyst.data.alert

import android.util.Log
import com.devcode940.ForexAnalyst.data.model.AppError
import com.devcode940.ForexAnalyst.data.model.CustomAlert
import com.devcode940.ForexAnalyst.data.model.MarketDataProvider
import com.devcode940.ForexAnalyst.data.model.Result
import com.devcode940.ForexAnalyst.data.repository.MarketDataRepository
import com.devcode940.ForexAnalyst.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant

/**
 * Polls the saved alerts, evaluates them against live prices, marks the ones that
 * fired and hands them to [AlertNotifier].
 *
 * WHY a loop in the process rather than WorkManager: the free tiers this app targets
 * are tiny — FCS gives 500 credits a *month* and Alpha Vantage 25 requests a day. A
 * WorkManager periodic worker has a 15-minute floor, runs whether or not the user
 * cares, and would spend a month of FCS credits in under two days. Running only while
 * the app is in the foreground costs a handful of requests a day and still fires
 * alerts while the user is actually looking at the market. Background alerting is a
 * real limitation and is written up as such in the README rather than shipped as a
 * quota-burning loop.
 *
 * WHY it refuses to evaluate simulated data: a simulated EURUSD series crosses a
 * user's target purely because the generator says so. Firing a notification for that
 * is a fabricated trading signal — the single most harmful thing this app could do.
 * With no live feed configured, monitoring is skipped and the reason is recorded in
 * [state] so the Alerts screen can explain it.
 */
class AlertMonitor(
    private val repository: MarketDataRepository,
    private val settings: StateFlow<AppSettings>,
    private val notifier: AlertNotificationSink,
    private val scope: CoroutineScope,
    private val isForeground: () -> Boolean,
    private val clock: Clock = Clock.systemUTC()
) {

    /**
     * Observable status, for the Alerts screen.
     *
     * WHY a state object rather than a toast per failure: the monitor runs with no UI
     * attached, so anything it learns has to be published for the next time the user
     * opens the screen. Without this, "my alert never fired" has no answer.
     */
    data class State(
        /**
         * Whether monitoring is switched on.
         *
         * WHY not called `running`: "running" reads as "a pass is in flight", and a
         * monitor that is on but has no alerts to check would report itself as off -
         * which is the banner telling the user to enable something they already enabled.
         */
        val enabled: Boolean = false,
        val lastRunAt: Instant? = null,
        val lastFiredCount: Int = 0,
        val lastError: AppError? = null,
        val waitingForLiveFeed: Boolean = false,
        val notificationsBlocked: Boolean = false
    ) {
        companion object {
            val IDLE = State()
        }
    }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile
    private var started = false

    /**
     * Starts the polling loop. Idempotent: a second call does nothing.
     *
     * WHY idempotent: `Application.onCreate` can be followed by a configuration
     * change or a restored process, and two loops would double every request against
     * a quota that is already the scarcest resource in the app.
     */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            while (isActive) {
                runCatching { tick() }
                    .onFailure { Log.e(TAG, "alert monitoring tick failed", it) }
                delay(POLL_INTERVAL_MILLIS)
            }
        }
    }

    /**
     * One evaluation pass.
     *
     * @return the number of alerts that fired. Exposed (and `suspend`) so tests can
     *   drive a single pass deterministically instead of waiting on the loop.
     */
    suspend fun evaluateOnce(): Int {
        val current = settings.value

        if (!current.alertMonitoringEnabled) {
            _state.value = State.IDLE
            return 0
        }
        if (current.resolvedMarketDataProvider == MarketDataProvider.SIMULATED) {
            // Not an error: the user has no key, or asked for offline. Say so, so the
            // Alerts screen can explain why nothing fires.
            _state.value = State(enabled = true, waitingForLiveFeed = true)
            return 0
        }

        val alertsResult = repository.getActiveAlerts()
        if (alertsResult is Result.Failure) {
            _state.value = State(
                enabled = true,
                lastError = alertsResult.error,
                lastRunAt = clock.instant()
            )
            return 0
        }
        val alerts = (alertsResult as Result.Success).data
        if (alerts.isEmpty()) {
            _state.value = State(enabled = true, lastRunAt = clock.instant())
            return 0
        }

        val canNotify = notifier.canNotify(current)
        var fired = 0
        var lastError: AppError? = null

        for (pair in alerts.map { it.pairSymbol }.distinct().take(MAX_MONITORED_PAIRS)) {
            val candlesResult = repository.fetchHistoricalCandles(
                pairSymbol = pair,
                timeframe = AlertEvaluator.EVALUATION_TIMEFRAME,
                limit = AlertEvaluator.EVALUATION_CANDLE_COUNT
            )
            if (candlesResult is Result.Failure) {
                lastError = candlesResult.error
                continue
            }
            val candles = (candlesResult as Result.Success).data
            if (candles.isEmpty()) continue

            val evaluations = AlertEvaluator.evaluateAll(
                alerts = alerts.filter { it.pairSymbol == pair },
                candles = candles,
                rsiPeriod = current.rsiPeriod,
                now = clock.instant()
            )

            for (evaluation in evaluations.filter { it.fired }) {
                // WHY persist before notifying: if the notification is posted first and
                // the write then fails, the next tick fires the same alert again and the
                // user gets a duplicate every five minutes. Marking it triggered first
                // means the worst case is a missed notification, not a spam loop.
                val persisted = persistTriggered(evaluation.alert)
                if (!persisted) {
                    lastError = AppError.Storage
                    continue
                }
                fired++
                if (canNotify) {
                    notifier.notify(evaluation, current)
                }
            }
        }

        _state.value = State(
            enabled = true,
            lastRunAt = clock.instant(),
            lastFiredCount = fired,
            lastError = lastError,
            waitingForLiveFeed = false,
            notificationsBlocked = !canNotify && fired > 0
        )
        return fired
    }

    private suspend fun persistTriggered(alert: CustomAlert): Boolean {
        // WHY no `triggeredAt` stamp: CustomAlert has no such column and the entity
        // table has no such field, so recording one would mean a Room schema
        // migration - which this sandbox cannot test (see AlertEvaluator's KDoc on
        // EVALUATION_TIMEFRAME). `isTriggered` alone is enough for the loop's own
        // idempotence, because getActiveAlerts() filters triggered rows out.
        val updated = alert.copy(isTriggered = true)
        val result = repository.saveAlert(updated)
        if (result is Result.Failure) {
            Log.w(TAG, "could not persist triggered alert: ${result.error.code}")
            return false
        }
        return true
    }

    private suspend fun tick() {
        // WHY gate on foreground: see the class KDoc — this is what keeps a monthly
        // quota from being spent while the phone is in a pocket.
        if (!isForeground()) return
        evaluateOnce()
    }

    companion object {
        private const val TAG = "AlertMonitor"

        /**
         * Five minutes between passes.
         *
         * WHY this is much shorter than the network cost implies: the repository
         * serves candles from its one-hour cache, so a pass usually costs nothing but
         * a database read. Only the first pass after the cache expires reaches the
         * network, and `RequestBudget` caps how often that may happen. Evaluating
         * often while fetching rarely is what makes alerts feel timely on a metered
         * free tier.
         */
        const val POLL_INTERVAL_MILLIS = 5L * 60L * 1000L

        /**
         * Distinct pairs refreshed per pass.
         *
         * WHY a cap: `getActiveAlerts` returns up to 500 alerts, and without a bound a
         * user who creates many of them would spend their entire monthly quota in one
         * pass. The cap is on *pairs*, not alerts, so every alert on a monitored pair
         * is still evaluated for free.
         */
        const val MAX_MONITORED_PAIRS = 5
    }
}
