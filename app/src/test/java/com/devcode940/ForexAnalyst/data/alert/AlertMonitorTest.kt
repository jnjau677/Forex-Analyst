package com.devcode940.ForexAnalyst.data.alert

import com.devcode940.ForexAnalyst.MutableClock
import com.devcode940.ForexAnalyst.data.model.AlertConditionType
import com.devcode940.ForexAnalyst.data.model.AlertId
import com.devcode940.ForexAnalyst.data.model.AppError
import com.devcode940.ForexAnalyst.data.model.CandleStick
import com.devcode940.ForexAnalyst.data.model.CustomAlert
import com.devcode940.ForexAnalyst.data.model.MarketDataPreference
import com.devcode940.ForexAnalyst.data.model.PairSymbol
import com.devcode940.ForexAnalyst.data.model.Result
import com.devcode940.ForexAnalyst.data.model.Timeframe
import com.devcode940.ForexAnalyst.data.repository.FakeMarketDataRepository
import com.devcode940.ForexAnalyst.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Duration

/**
 * Covers [AlertMonitor]'s decision table: when it evaluates, what it persists, and what
 * it refuses to do.
 *
 * WHY the "refuses" cases get as much coverage as the happy path: every one of them is a
 * way this feature could send a *false* trading signal — evaluating simulated prices,
 * firing twice on one crossing, or firing on a price from last Thursday. Those are worse
 * than not firing at all.
 */
class AlertMonitorTest {

    private val clock = MutableClock()
    private val repository = FakeMarketDataRepository()
    private val notifier = RecordingNotifier()
    private val settings = MutableStateFlow(liveSettings())

    private fun monitor(foreground: Boolean = true) = AlertMonitor(
        repository = repository,
        settings = settings,
        notifier = notifier,
        // WHY an unused scope in most tests: these drive evaluateOnce() directly. Only
        // the polling test starts the loop, and it passes its own cancellable scope.
        scope = CoroutineScope(Job()),
        isForeground = { foreground },
        clock = clock
    )

    /** Settings that resolve to a live feed, with alerting switched on. */
    private fun liveSettings() = AppSettings(
        fcsApiKey = "FCS_TEST_KEY",
        marketDataSource = MarketDataPreference.AUTO,
        alertMonitoringEnabled = true,
        notificationsEnabled = true,
        alertNotifications = true,
        rsiPeriod = 14
    )

    private fun alert(pair: String, condition: AlertConditionType, target: Double) = CustomAlert(
        id = AlertId.generate(),
        pairSymbol = PairSymbol(pair),
        conditionType = condition,
        targetValue = target,
        isTriggered = false,
        isActive = true,
        note = "",
        createdAt = clock.instant().minus(Duration.ofDays(1))
    )

    /** Twenty hourly bars ending "now", all closing at [price]. */
    private fun candlesAt(price: Double) = (0 until 20).map { index ->
        CandleStick(
            time = clock.instant().minusMillis((19 - index) * Duration.ofHours(1).toMillis()),
            open = price,
            high = price + 0.0001,
            low = price - 0.0001,
            close = price
        )
    }

    @Before
    fun setUp() {
        repository.candleResult = Result.Success(candlesAt(1.1000))
        settings.value = liveSettings()
    }

    // ---------- the happy path ----------

    @Test
    fun `a fired alert is persisted as triggered before it is announced`() {
        // WHY the order matters: if the notification went first and the write then
        // failed, the next tick would fire the same alert again, every five minutes.
        // Marking it triggered first makes the worst case a missed notification, not a
        // spam loop.
        repository.activeAlerts = listOf(alert("EURUSD", AlertConditionType.PRICE_ABOVE, 1.0900))
        notifier.stateProbe = { repository.savedAlerts.isNotEmpty() }

        val fired = runBlocking { monitor().evaluateOnce() }

        assertEquals(1, fired)
        assertEquals(1, repository.savedAlerts.size)
        assertTrue("the alert must be stored as triggered", repository.savedAlerts.single().isTriggered)
        assertEquals(1, notifier.posted.size)
        assertTrue(
            "the alert must already be persisted when the notification goes out",
            notifier.probeAtPostTime.single()
        )
    }

    @Test
    fun `an alert whose condition is not met is left alone`() {
        repository.activeAlerts = listOf(alert("EURUSD", AlertConditionType.PRICE_ABOVE, 1.5000))

        val monitor = monitor()
        val fired = runBlocking { monitor.evaluateOnce() }

        assertEquals(0, fired)
        assertTrue("nothing should be written", repository.savedAlerts.isEmpty())
        assertTrue(notifier.posted.isEmpty())
        assertNull("a healthy pass should not report a failure", monitor.state.value.lastError)
        assertTrue(monitor.state.value.enabled)
    }

    @Test
    fun `a successful pass records when it ran and how many fired`() {
        repository.activeAlerts = listOf(alert("EURUSD", AlertConditionType.PRICE_ABOVE, 1.0900))

        val monitor = monitor()
        runBlocking { monitor.evaluateOnce() }

        val state = monitor.state.value
        assertTrue(state.enabled)
        assertEquals(1, state.lastFiredCount)
        assertEquals(clock.instant(), state.lastRunAt)
        assertNull(state.lastError)
        assertFalse(state.waitingForLiveFeed)
        assertFalse(state.notificationsBlocked)
    }

    // ---------- when monitoring refuses to run ----------

    @Test
    fun `monitoring switched off does nothing and reports itself paused`() {
        settings.value = liveSettings().copy(alertMonitoringEnabled = false)
        repository.activeAlerts = listOf(alert("EURUSD", AlertConditionType.PRICE_ABOVE, 1.0000))

        val monitor = monitor()
        val fired = runBlocking { monitor.evaluateOnce() }

        assertEquals(0, fired)
        assertTrue("no prices should be fetched", repository.fetchedPairs.isEmpty())
        assertFalse("the banner has to say monitoring is paused", monitor.state.value.enabled)
    }

    @Test
    fun `with no live feed configured the monitor refuses to evaluate`() {
        // WHY this is the most important case in the suite: a simulated EURUSD crosses a
        // target purely because the generator says so. Notifying on that is a fabricated
        // trading signal, which is worse than the alert feature not existing.
        settings.value = liveSettings().copy(fcsApiKey = "", alphaVantageApiKey = "")
        repository.activeAlerts = listOf(alert("EURUSD", AlertConditionType.PRICE_ABOVE, 1.0000))

        val monitor = monitor()
        val fired = runBlocking { monitor.evaluateOnce() }

        assertEquals(0, fired)
        assertTrue("no prices should be fetched", repository.fetchedPairs.isEmpty())
        assertTrue(notifier.posted.isEmpty())
        assertTrue("the state must explain the wait", monitor.state.value.waitingForLiveFeed)
    }

    @Test
    fun `the offline preference is treated the same as having no key`() {
        settings.value = liveSettings().copy(marketDataSource = MarketDataPreference.OFFLINE)
        repository.activeAlerts = listOf(alert("EURUSD", AlertConditionType.PRICE_ABOVE, 1.0000))

        val monitor = monitor()
        assertEquals(0, runBlocking { monitor.evaluateOnce() })
        assertTrue(monitor.state.value.waitingForLiveFeed)
        assertTrue(repository.fetchedPairs.isEmpty())
    }

    @Test
    fun `no alerts means no price fetches`() {
        // WHY: each fetch spends metered quota, and a user with no alerts configured
        // should cost nothing at all.
        repository.activeAlerts = emptyList()

        val fired = runBlocking { monitor().evaluateOnce() }

        assertEquals(0, fired)
        assertTrue(repository.fetchedPairs.isEmpty())
    }

    // ---------- quota discipline ----------

    @Test
    fun `evaluation uses the H1 series and a bounded bar count`() {
        repository.activeAlerts = listOf(alert("EURUSD", AlertConditionType.PRICE_ABOVE, 1.0900))

        runBlocking { monitor().evaluateOnce() }

        assertEquals(listOf(Timeframe("H1")), repository.fetchedTimeframes)
        assertEquals(listOf(AlertEvaluator.EVALUATION_CANDLE_COUNT), repository.fetchedLimits)
    }

    @Test
    fun `one request covers every alert on the same pair`() {
        // WHY: quota is the scarce resource. Three alerts on EURUSD must not cost three
        // requests.
        repository.activeAlerts = listOf(
            alert("EURUSD", AlertConditionType.PRICE_ABOVE, 1.0500),
            alert("EURUSD", AlertConditionType.PRICE_BELOW, 1.2000),
            alert("EURUSD", AlertConditionType.PRICE_ABOVE, 1.0900)
        )

        val fired = runBlocking { monitor().evaluateOnce() }

        assertEquals(3, fired)
        assertEquals("one fetch per pair, not per alert", 1, repository.fetchedPairs.size)
    }

    @Test
    fun `the number of pairs refreshed per pass is capped`() {
        repository.activeAlerts = (1..12).map { index ->
            alert("SYM${index}USD", AlertConditionType.PRICE_ABOVE, 0.5)
        }

        runBlocking { monitor().evaluateOnce() }

        val distinct = repository.fetchedPairs.distinct().size
        assertEquals(
            "the cap must bind, otherwise a long alert list spends the whole quota",
            AlertMonitor.MAX_MONITORED_PAIRS,
            distinct
        )
    }

    @Test
    fun `the poll interval is short enough to feel timely and long enough to be cheap`() {
        // WHY both bounds are asserted: five minutes is the compromise between "an alert
        // that fires an hour late is useless" and "a five-second loop spends a month of
        // free-tier quota in a day". Either drift is a real regression.
        assertTrue(AlertMonitor.POLL_INTERVAL_MILLIS >= Duration.ofMinutes(1).toMillis())
        assertTrue(AlertMonitor.POLL_INTERVAL_MILLIS <= Duration.ofMinutes(15).toMillis())
    }

    @Test
    fun `starting the monitor twice produces one polling loop`() = runTest {
        repository.activeAlerts = listOf(alert("EURUSD", AlertConditionType.PRICE_ABOVE, 1.5000))
        val monitorScope = CoroutineScope(coroutineContext + Job())
        val monitor = AlertMonitor(
            repository = repository,
            settings = settings,
            notifier = notifier,
            scope = monitorScope,
            isForeground = { true },
            clock = clock
        )

        monitor.start()
        monitor.start()
        monitor.start()

        // Three poll intervals admit four passes (one immediately, then one per interval).
        advanceTimeBy(AlertMonitor.POLL_INTERVAL_MILLIS * 3 + 1)
        runCurrent()
        monitorScope.cancel()

        assertEquals(
            "three start() calls must not produce three loops",
            4,
            repository.fetchedPairs.size
        )
    }

    @Test
    fun `the loop does nothing while the app is backgrounded`() = runTest {
        repository.activeAlerts = listOf(alert("EURUSD", AlertConditionType.PRICE_ABOVE, 1.0000))
        var foreground = false
        val monitorScope = CoroutineScope(coroutineContext + Job())
        val monitor = AlertMonitor(
            repository = repository,
            settings = settings,
            notifier = notifier,
            scope = monitorScope,
            isForeground = { foreground },
            clock = clock
        )

        monitor.start()
        advanceTimeBy(AlertMonitor.POLL_INTERVAL_MILLIS * 2 + 1)
        runCurrent()
        assertTrue("a backgrounded app must not spend quota", repository.fetchedPairs.isEmpty())

        foreground = true
        advanceTimeBy(AlertMonitor.POLL_INTERVAL_MILLIS + 1)
        runCurrent()
        monitorScope.cancel()

        assertEquals(1, repository.fetchedPairs.size)
    }

    // ---------- failure handling ----------

    @Test
    fun `a candle fetch failure is recorded and does not stop the pass`() {
        repository.activeAlerts = listOf(
            alert("AAAUSD", AlertConditionType.PRICE_ABOVE, 1.0900),
            alert("EURUSD", AlertConditionType.PRICE_ABOVE, 1.0900)
        )
        repository.candleResult = Result.Failure(AppError.Network)

        val monitor = monitor()
        val fired = runBlocking { monitor.evaluateOnce() }

        assertEquals(0, fired)
        assertEquals("both pairs should still have been attempted", 2, repository.fetchedPairs.size)
        assertSame(AppError.Network, monitor.state.value.lastError)
        assertTrue(notifier.posted.isEmpty())
    }

    @Test
    fun `a failure to read the alert list is reported, not swallowed`() {
        repository.alertReadResult = Result.Failure(AppError.Storage)

        val monitor = monitor()
        val fired = runBlocking { monitor.evaluateOnce() }

        assertEquals(0, fired)
        assertSame(AppError.Storage, monitor.state.value.lastError)
        assertEquals(clock.instant(), monitor.state.value.lastRunAt)
    }

    @Test
    fun `a failed alert write suppresses that notification`() {
        // WHY: announcing an alert the app has not recorded means the next pass fires it
        // again, and the user gets the same notification every five minutes forever.
        repository.activeAlerts = listOf(alert("EURUSD", AlertConditionType.PRICE_ABOVE, 1.0900))
        repository.alertResult = Result.Failure(AppError.Storage)

        val monitor = monitor()
        val fired = runBlocking { monitor.evaluateOnce() }

        assertEquals(0, fired)
        assertTrue(notifier.posted.isEmpty())
        assertSame(AppError.Storage, monitor.state.value.lastError)
    }

    // ---------- notifications ----------

    @Test
    fun `a fired alert is still persisted when notifications are blocked`() {
        // WHY: the alert genuinely happened. Losing it because the OS muted the app would
        // mean the user can never see it, and re-firing it later would be wrong too.
        settings.value = liveSettings().copy(notificationsEnabled = false)
        repository.activeAlerts = listOf(alert("EURUSD", AlertConditionType.PRICE_ABOVE, 1.0900))

        val monitor = monitor()
        val fired = runBlocking { monitor.evaluateOnce() }

        assertEquals(1, fired)
        assertEquals(1, repository.savedAlerts.size)
        assertTrue("nothing should be posted", notifier.posted.isEmpty())
        assertTrue(
            "the state must say alerts fired but were not shown",
            monitor.state.value.notificationsBlocked
        )
    }

    @Test
    fun `the user's sound and vibration settings reach the sink untouched`() {
        settings.value = liveSettings().copy(soundEnabled = false, vibrationEnabled = true)
        repository.activeAlerts = listOf(alert("EURUSD", AlertConditionType.PRICE_ABOVE, 1.0900))

        runBlocking { monitor().evaluateOnce() }

        val received = notifier.settingsAtPostTime.single()
        assertFalse(received.soundEnabled)
        assertTrue(received.vibrationEnabled)
    }

    @Test
    fun `the notification names the pair and quotes the level in the instrument's precision`() {
        repository.activeAlerts = listOf(alert("USDJPY", AlertConditionType.PRICE_BELOW, 152.0))
        repository.candleResult = Result.Success(candlesAt(151.500))

        runBlocking { monitor().evaluateOnce() }

        val posted = notifier.posted.single()
        assertTrue("headline should name the pair: ${posted.headline}", posted.headline.contains("USDJPY"))
        assertTrue("headline should quote the target: ${posted.headline}", posted.headline.contains("152.000"))
    }

    /**
     * Records what the monitor asked for.
     *
     * @property stateProbe sampled at post time, so a test can assert what the monitor had
     *   already done before it announced anything.
     */
    private class RecordingNotifier : AlertNotificationSink {
        val posted = mutableListOf<AlertEvaluation>()
        val settingsAtPostTime = mutableListOf<AppSettings>()
        val probeAtPostTime = mutableListOf<Boolean>()
        var stateProbe: () -> Boolean = { true }

        override fun canNotify(settings: AppSettings): Boolean =
            settings.notificationsEnabled && settings.alertNotifications

        override fun notify(evaluation: AlertEvaluation, settings: AppSettings): Boolean {
            posted += evaluation
            settingsAtPostTime += settings
            probeAtPostTime += stateProbe()
            return true
        }
    }
}
