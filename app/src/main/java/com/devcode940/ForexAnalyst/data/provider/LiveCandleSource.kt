package com.devcode940.ForexAnalyst.data.provider

import android.util.Log
import com.devcode940.ForexAnalyst.data.model.AppError
import com.devcode940.ForexAnalyst.data.model.CandleBatch
import com.devcode940.ForexAnalyst.data.model.CandleSource
import com.devcode940.ForexAnalyst.data.model.CandleStick
import com.devcode940.ForexAnalyst.data.model.MarketDataException
import com.devcode940.ForexAnalyst.data.model.MarketDataProvider
import com.devcode940.ForexAnalyst.data.model.PairSymbol
import com.devcode940.ForexAnalyst.data.model.Timeframe
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException

/**
 * Routes candle requests to the live feeds, in the user's preferred order, and
 * falls back to [fallback] when none of them can answer.
 *
 * WHY a routing layer rather than letting the repository pick: the decision involves
 * four separate concerns — which keys exist, which feed covers this instrument,
 * which feed still has quota, and which feed has been refusing us. Putting those in
 * `ForexRepository` would bury them next to the cache logic and make each one
 * untestable without a database. Here they are one class with no Android dependency
 * beyond `Log`.
 *
 * WHY it always returns candles and never throws: a chart that cannot be drawn is
 * worse than a chart drawn from the simulator with a badge saying so. The failure is
 * reported through [CandleBatch.degradedReason] instead, which is the mechanism that
 * keeps the two outcomes distinguishable.
 *
 * @property orderedProviders live feeds to try, best first, given the current
 *   settings. Read per call, not captured: the user can change the preference or
 *   paste a key in Settings and it must take effect on the next chart, not the next
 *   process start.
 */
class LiveCandleSource(
    private val clients: Map<MarketDataProvider, MarketDataClient>,
    private val fallback: CandleSource,
    private val budget: RequestBudget,
    private val orderedProviders: () -> List<MarketDataProvider>
) : CandleSource {

    /**
     * Feeds that have been refusing us, and when to try them again.
     *
     * WHY suspend a feed after an auth failure instead of retrying every time: a
     * wrong key does not become right by being sent again, and every retry is
     * another failed credential presented to a provider that may well lock the
     * account after a few of them. One hour is long enough to cover "the user pasted
     * the key wrong, then fixed it" without spending the session on a dead end.
     */
    private val suspendedUntil = mutableMapOf<MarketDataProvider, Instant>()

    override suspend fun load(
        pairSymbol: PairSymbol,
        timeframe: Timeframe,
        limit: Int,
        clock: Clock
    ): CandleBatch {
        val wanted = orderedProviders()
        if (wanted.isEmpty()) {
            // OFFLINE preference, or no key configured. Not a degradation — the user
            // asked for this, or has not opted in yet, so there is nothing to explain.
            return fallback.load(pairSymbol, timeframe, limit, clock)
        }

        var lastFailure: AppError? = null
        val now = clock.instant()

        for (provider in wanted) {
            val client = clients[provider] ?: continue

            val suspended = suspendedUntil[provider]
            if (suspended != null && suspended.isAfter(now)) {
                lastFailure = AppError.Unauthorized
                continue
            }
            if (suspended != null) suspendedUntil.remove(provider)

            if (!client.supports(pairSymbol, timeframe)) {
                // WHY recorded and not swallowed: "FCS API does not cover SPX500 on
                // W1" is an answer, and hiding it leaves the user staring at a
                // simulated chart with no explanation.
                lastFailure = AppError.Unsupported
                continue
            }

            when (val decision = budget.tryAcquire(provider)) {
                RequestBudget.Decision.Granted -> Unit
                is RequestBudget.Decision.Denied -> {
                    Log.i(TAG, "$provider quota reached; next window in ${decision.retryAfter}")
                    lastFailure = AppError.RateLimited
                    continue
                }
            }

            // WHY not `runCatching`: it catches `CancellationException` too, which
            // would turn "the user navigated away" into "this feed failed" and
            // silently defeat structured concurrency for the whole polling loop.
            val candles: List<CandleStick>?
            val failure: Throwable?
            try {
                candles = client.fetchCandles(pairSymbol, timeframe, limit, clock)
                failure = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                candles = null
                failure = e
            }

            if (candles != null) {
                return CandleBatch(
                    candles = candles,
                    source = provider,
                    fetchedAt = clock.instant(),
                    degradedReason = null
                )
            }

            val error = classify(failure)
            if (error is AppError.Network) {
                // WHY refund only on a network failure: the request never reached the
                // provider, so nothing was consumed. Refunding refusals too would be
                // right for FCS (its docs charge only for HTTP 200) and wrong for
                // Alpha Vantage, which counts every call — and getting that wrong in
                // the optimistic direction is how a key gets suspended.
                budget.refund(provider)
            }
            if (error is AppError.Unauthorized) {
                suspendedUntil[provider] = clock.instant().plus(AUTH_FAILURE_SUSPENSION)
            }
            Log.w(TAG, "$provider could not serve ${pairSymbol.value} $timeframe: ${error.code}")
            lastFailure = error
        }

        // Every candidate failed. Simulate, and say why.
        val simulated = fallback.load(pairSymbol, timeframe, limit, clock)
        return simulated.copy(degradedReason = lastFailure ?: AppError.Network)
    }

    /** Remaining quota summary for every configured feed, for the Settings screen. */
    fun quotaSummary(): List<String> = clients.keys.map { budget.describe(it) }

    /** Clears auth suspensions. Called after the user edits an API key. */
    fun resetSuspensions() {
        suspendedUntil.clear()
    }

    /**
     * Drops every locally recorded request and lifts every suspension.
     *
     * WHY this exists at all: the counters are this device's *estimate* of a quota the
     * provider tracks server-side. When they drift — a key upgraded to a paid plan, a
     * window that already rolled over, a counter written by an older build — the estimate
     * is the only thing standing between the user and their own feed, and refusing to
     * fetch with no way to clear it turns a bookkeeping bug into "the app is broken".
     * Resetting here is safe: the next real call still costs a real request, and a
     * provider that disagrees will answer 429, which `classify` reports honestly.
     */
    fun resetQuota() {
        budget.reset()
        suspendedUntil.clear()
    }

    private fun classify(cause: Throwable?): AppError = when (cause) {
        is MarketDataException -> cause.error
        is IOException -> AppError.Network
        null -> AppError.Network
        // WHY not Unknown: `Unknown` renders "Something went wrong", which for a
        // data feed is a dead end. `ProviderRejected` says where to look.
        else -> AppError.ProviderRejected(
            ProviderMessageSanitizer.sanitize(cause.message ?: cause.javaClass.simpleName)
        )
    }

    companion object {
        private const val TAG = "LiveCandleSource"

        /** How long a feed is skipped after it rejects the credential. */
        val AUTH_FAILURE_SUSPENSION: Duration = Duration.ofHours(1)
    }
}
