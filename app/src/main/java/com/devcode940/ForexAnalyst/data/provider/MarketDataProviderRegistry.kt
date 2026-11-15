package com.devcode940.ForexAnalyst.data.provider

import android.content.Context
import com.devcode940.ForexAnalyst.data.model.CandleSource
import com.devcode940.ForexAnalyst.data.model.MarketDataProvider
import com.devcode940.ForexAnalyst.data.model.PairSymbol
import com.devcode940.ForexAnalyst.data.model.Timeframe
import com.devcode940.ForexAnalyst.data.provider.alphavantage.AlphaVantageApi
import com.devcode940.ForexAnalyst.data.provider.alphavantage.AlphaVantageClient
import com.devcode940.ForexAnalyst.data.provider.fcs.FcsApi
import com.devcode940.ForexAnalyst.data.provider.fcs.FcsClient
import com.devcode940.ForexAnalyst.data.provider.http.MarketDataHttp
import com.devcode940.ForexAnalyst.data.settings.AppSettings
import kotlinx.coroutines.flow.StateFlow
import java.time.Clock

/**
 * Builds and owns the live market-data stack for the process.
 *
 * This is the composition root for the feed layer: it reads credentials out of
 * settings, constructs the HTTP clients once, and hands the repository a single
 * [CandleSource]. Nothing above it knows Retrofit, OkHttp or a provider's URL.
 *
 * WHY the HTTP stack is built lazily: constructing an `OkHttpClient` starts a
 * connection-pool thread and a dispatcher. Most launches never touch a live feed —
 * the cache serves the first chart — and paying for those threads on every cold
 * start of an offline-first app is a cost with no benefit.
 *
 * @property settings read as a flow, not sampled once. WHY: pasting an API key in
 *   Settings has to take effect on the next chart without a restart, and the
 *   lambdas below close over the flow's current value for exactly that reason.
 * @property logRequests pass `BuildConfig.DEBUG`. WHY not read it here: this class
 *   stays free of generated-code dependencies and the default (`false`) fails safe.
 */
class MarketDataProviderRegistry(
    context: Context,
    private val settings: StateFlow<AppSettings>,
    private val fallback: CandleSource,
    private val clock: Clock = Clock.systemUTC(),
    private val logRequests: Boolean = false
) {

    private val appContext = context.applicationContext

    /**
     * Quota guard, with usage that survives a restart.
     *
     * Exposed so Settings can render "FCS API: 12 of 400 per month" — the only way a
     * user can tell why prices stopped updating without reading logcat.
     */
    val budget: RequestBudget by lazy {
        RequestBudget(
            limits = RequestBudget.FREE_TIER_LIMITS,
            clock = clock,
            store = PrefsBudgetStore(appContext)
        )
    }

    /**
     * The [CandleSource] to hand to the repository.
     *
     * WHY a wrapper rather than exposing [liveSource] directly: `liveSource` is lazy,
     * and reading it during `Application.onCreate` would build the OkHttp stack on
     * every cold start even when the first chart is served from cache. This object is
     * free to create and defers construction to the first actual fetch.
     */
    val candleSource: CandleSource = object : CandleSource {
        override suspend fun load(
            pairSymbol: PairSymbol,
            timeframe: Timeframe,
            limit: Int,
            clock: Clock
        ) = liveSource.load(pairSymbol, timeframe, limit, clock)
    }

    /** The live source, with the simulator wired in as its fallback. */
    val liveSource: LiveCandleSource by lazy {
        LiveCandleSource(
            clients = buildClients(),
            fallback = fallback,
            budget = budget,
            orderedProviders = { settings.value.liveFeedOrder }
        )
    }

    private val httpClient by lazy { MarketDataHttp.okHttpClient(logRequests) }

    private fun buildClients(): Map<MarketDataProvider, MarketDataClient> {
        // WHY the key is a lambda rather than a captured String: the credential lives
        // in encrypted preferences and the user can replace it at any time. A captured
        // value would keep authenticating with the old key until the process died, and
        // the resulting symptom — "I pasted the new key and nothing changed" — is
        // indistinguishable from a broken integration.
        val secrets: () -> Collection<String> = { settings.value.configuredSecrets }

        val fcs = FcsClient(
            api = MarketDataHttp.service(FcsApi.BASE_URL, httpClient, FcsApi::class.java),
            accessKey = { settings.value.fcsApiKey },
            knownSecretsForRedaction = secrets
        )
        val alphaVantage = AlphaVantageClient(
            api = MarketDataHttp.service(
                AlphaVantageApi.BASE_URL,
                httpClient,
                AlphaVantageApi::class.java
            ),
            apiKey = { settings.value.alphaVantageApiKey },
            knownSecretsForRedaction = secrets
        )
        return mapOf(
            MarketDataProvider.FCS_API to fcs,
            MarketDataProvider.ALPHA_VANTAGE to alphaVantage
        )
    }
}
