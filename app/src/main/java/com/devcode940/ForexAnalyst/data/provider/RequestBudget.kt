package com.devcode940.ForexAnalyst.data.provider

import com.devcode940.ForexAnalyst.data.model.MarketDataProvider
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.ArrayDeque

/**
 * Client-side quota guard for the metered public market-data feeds.
 *
 * WHY this exists at all: both feeds are metered and both are *small* on their free
 * tier — Alpha Vantage allows 25 requests per day, and FCS allows **500 credits per
 * month** (its own documentation, `fcsapi.com/document/forex-api`). This app
 * refreshes candles for a chart *and* for every monitored alert, so an unguarded
 * loop exhausts a month of FCS credits in a couple of days, after which the
 * provider refuses everything and the user concludes the app is broken. The
 * alternative — "let the provider tell us when we are out" — makes the user pay for
 * that discovery with their quota, and repeated abuse can get a key suspended.
 *
 * WHY the counters are persisted ([BudgetStore]): a monthly quota guarded by an
 * in-memory counter is no quota at all — restarting the app would reset it, and
 * restarting is exactly what a frustrated user does when prices stop updating.
 *
 * WHY sliding windows rather than fixed ones: a fixed window admits `2 x max`
 * requests straddling the boundary (one at 23:59, one at 00:00), which is precisely
 * the burst pattern that gets a key rate-limited.
 *
 * @property clock injected so window arithmetic is deterministically testable.
 */
class RequestBudget(
    private val limits: Map<MarketDataProvider, List<Window>>,
    private val clock: Clock = Clock.systemUTC(),
    private val store: BudgetStore? = null
) {

    /**
     * One sliding window: at most [maxRequests] in any rolling [span].
     *
     * @property displayName used in the "try again in ..." message, e.g. "minute".
     */
    data class Window(val span: Duration, val maxRequests: Int, val displayName: String) {
        init {
            // WHY comparisons rather than `span.isNegative`/`isZero`: those two
            // Duration methods are Java 9 additions and are not reliably present on
            // every API 26 runtime this app supports. `compareTo` is Java 8.
            require(span > Duration.ZERO) { "window span must be positive" }
            require(maxRequests > 0) { "maxRequests must be positive (got $maxRequests)" }
            require(displayName.isNotBlank()) { "displayName cannot be blank" }
        }
    }

    /** Outcome of [tryAcquire]. */
    sealed interface Decision {
        /** A request may be made now, and has been charged. */
        object Granted : Decision

        /**
         * No request may be made; [retryAfter] is how long until the oldest charge
         * in the binding window expires.
         *
         * WHY carry [retryAfter]: the caller can say "try again in 4 minutes"
         * instead of "failed", and the polling loop can sleep exactly that long
         * rather than hammering a closed door every tick.
         */
        data class Denied(val window: Window, val retryAfter: Duration) : Decision
    }

    /**
     * Where usage is remembered between process restarts.
     *
     * WHY an interface: the JVM tests need an in-memory store, and the app needs one
     * backed by preferences. Keeping it abstract also keeps [RequestBudget] free of
     * `android.*`, so the window arithmetic stays unit-testable.
     */
    interface BudgetStore {
        /** Previously recorded request times, in any order. */
        fun load(provider: MarketDataProvider): List<Instant>

        /** Replaces the recorded request times for [provider]. */
        fun save(provider: MarketDataProvider, usage: List<Instant>)
    }

    // WHY one deque per feed, guarded by a lock: the chart and the alert monitor run
    // on different coroutines, and a non-atomic "count then add" would let both
    // pass a check for the same final request.
    private val usage = mutableMapOf<MarketDataProvider, ArrayDeque<Instant>>()
    private val lock = Any()

    /**
     * Charges one request against [provider]'s quota when every window allows it.
     *
     * A feed with no configured windows (the simulator) is unlimited: a missing
     * entry must never turn a working feed into a permanently denied one.
     */
    fun tryAcquire(provider: MarketDataProvider): Decision {
        val windows = limits[provider] ?: return Decision.Granted
        val now = clock.instant()
        synchronized(lock) {
            val deque = dequeFor(provider)
            prune(deque, windows, now)

            // The widest window's ceiling is what bounds the deque's length, so a
            // single pass over it is enough for every window.
            for (window in windows.sortedBy { it.span }) {
                val since = now.minus(window.span)
                val used = deque.count { it.isAfter(since) }
                if (used >= window.maxRequests) {
                    val oldest = deque.firstOrNull { it.isAfter(since) } ?: now
                    val retryAfter = Duration.between(now, oldest.plus(window.span))
                    return Decision.Denied(
                        window = window,
                        retryAfter = if (retryAfter < Duration.ZERO) Duration.ZERO else retryAfter
                    )
                }
            }

            deque.addLast(now)
            persist(provider, deque)
            return Decision.Granted
        }
    }

    /**
     * Gives a charge back.
     *
     * WHY: a request that fails before reaching the provider (DNS, no connectivity,
     * TLS) consumed nothing, and charging for it would shrink the usable quota every
     * time the user passes through a tunnel or a captive portal. Callers refund only
     * when they are certain the request was never sent.
     */
    fun refund(provider: MarketDataProvider) {
        if (!limits.containsKey(provider)) return
        synchronized(lock) {
            val deque = usage[provider] ?: return@synchronized
            if (deque.pollLast() != null) persist(provider, deque)
        }
    }

    /** Requests charged against [provider] inside [window]. */
    fun usedIn(provider: MarketDataProvider, window: Window): Int {
        val now = clock.instant()
        val since = now.minus(window.span)
        synchronized(lock) {
            return dequeFor(provider).count { it.isAfter(since) }
        }
    }

    /**
     * A one-line human summary of remaining quota, for the Settings screen.
     *
     * WHY the UI gets a string rather than raw numbers: three providers x two
     * windows each is a table nobody wants to read, and "FCS API: 12 of 400 used
     * this month" answers the only question that matters — "why did prices stop?"
     */
    fun describe(provider: MarketDataProvider): String {
        val windows = limits[provider] ?: return "${provider.displayName}: unlimited"
        val parts = windows
            .sortedByDescending { it.span }
            .map { window -> "${usedIn(provider, window)} of ${window.maxRequests} per ${window.displayName}" }
        return "${provider.displayName}: ${parts.joinToString(", ")}"
    }

    /**
     * Drops all recorded usage, in memory and in the [store].
     *
     * Called from `LiveCandleSource.resetQuota()`, which the Settings screen exposes as
     * "Reset counters" — see that method for why a user-facing escape hatch is safe.
     */
    fun reset() {
        synchronized(lock) {
            usage.clear()
            limits.keys.forEach { provider -> store?.save(provider, emptyList()) }
        }
    }

    private fun dequeFor(provider: MarketDataProvider): ArrayDeque<Instant> =
        usage.getOrPut(provider) {
            // WHY load inside the lock: two coroutines could otherwise each build a
            // deque from the store and one set of recorded requests would be lost,
            // quietly doubling the effective quota.
            ArrayDeque<Instant>().apply {
                store?.load(provider)?.forEach { addLast(it) }
            }
        }

    private fun prune(deque: ArrayDeque<Instant>, windows: List<Window>, now: Instant) {
        val widest = windows.maxOf { it.span }
        val cutoff = now.minus(widest)
        while (deque.isNotEmpty() && deque.peekFirst().isBefore(cutoff)) {
            deque.pollFirst()
        }
    }

    private fun persist(provider: MarketDataProvider, deque: ArrayDeque<Instant>) {
        store?.save(provider, deque.toList())
    }

    companion object {
        private val MINUTE = Duration.ofMinutes(1)
        private val HOUR = Duration.ofHours(1)
        private val DAY = Duration.ofDays(1)
        private val MONTH = Duration.ofDays(30)

        /**
         * Deliberately conservative windows for each feed's free tier.
         *
         * WHY below the published ceilings: the published number is where the
         * provider starts refusing, and a client that sits exactly on it races its
         * own retry logic into a 429. Leaving headroom costs a couple of requests a
         * month and removes the whole class of "it worked in the morning" report.
         *
         * WHY FCS has no per-day window: its quota is monthly (500 credits), so a
         * daily cap would be an invention. The hourly window is what actually
         * matters there — it stops a fast pair/timeframe switch from spending ten
         * credits in ten seconds.
         *
         * WHY the hourly caps match the repository's cache TTL: FCS's free plan
         * caches its own answers for 60 minutes, so asking more often than hourly
         * spends a credit to receive the identical payload.
         */
        val FREE_TIER_LIMITS: Map<MarketDataProvider, List<Window>> = mapOf(
            MarketDataProvider.ALPHA_VANTAGE to listOf(
                Window(MINUTE, maxRequests = 5, displayName = "minute"),
                Window(DAY, maxRequests = 20, displayName = "day")
            ),
            MarketDataProvider.FCS_API to listOf(
                Window(HOUR, maxRequests = 4, displayName = "hour"),
                Window(MONTH, maxRequests = 400, displayName = "month")
            )
        )
    }
}
