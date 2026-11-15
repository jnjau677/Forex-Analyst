package com.devcode940.ForexAnalyst.data.model

import com.google.gson.annotations.SerializedName
import java.time.Instant

/**
 * Where a candle series actually came from.
 *
 * WHY this exists: the app ships with a simulator so that it is useful with no API
 * key and no network. That is a feature only for as long as the user can *tell* —
 * a chart of invented prices presented as real ones is how somebody loses money on
 * a demo account they believed was live. Every series therefore carries its
 * provenance, and the chart renders it.
 */
enum class MarketDataProvider(val displayName: String) {
    /** Generated locally by `PriceGenerator`; deterministic per pair and timeframe. */
    @SerializedName("simulated") SIMULATED("Simulated"),

    /** fcsapi.com v4 forex history. */
    @SerializedName("fcs_api") FCS_API("FCS API"),

    /** alphavantage.co FX time series. */
    @SerializedName("alpha_vantage") ALPHA_VANTAGE("Alpha Vantage");

    /** True when the prices are real market data rather than a simulation. */
    val isLive: Boolean get() = this != SIMULATED

    companion object {
        /**
         * Parses a stored wire value, tolerating null, blanks and unknown names.
         *
         * WHY tolerant: these strings are persisted in preferences. A value the
         * current build does not recognise — from a downgrade, or from a
         * hand-edited backup — must degrade to the simulator, not crash on
         * launch. `Enum.valueOf` would do exactly that.
         */
        fun fromWire(raw: String?): MarketDataProvider =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: SIMULATED
    }
}

/**
 * Which feed the user wants, as stored in settings.
 *
 * WHY a preference at all rather than "use whichever key exists": with two keys
 * present the precedence would be an invisible implementation detail, and with a
 * key whose quota has run out the user would have no way to say "stop calling it".
 * [OFFLINE] is also how a reviewer, or an automated test, runs the app
 * deterministically with no network access.
 */
enum class MarketDataPreference(val displayName: String) {
    /** Use the best available key; simulate when there is none. */
    @SerializedName("auto") AUTO("Automatic"),

    @SerializedName("fcs_api") FCS_API("FCS API"),

    @SerializedName("alpha_vantage") ALPHA_VANTAGE("Alpha Vantage"),

    /** Never call a provider; always simulate. */
    @SerializedName("offline") OFFLINE("Offline (simulated)");

    companion object {
        /**
         * Resolves the preference against the keys that actually exist.
         *
         * WHY a missing key degrades to the simulator instead of failing: the chart
         * must render *something*, and the header says which. Refusing to draw would
         * be worse than drawing honestly-labelled simulated data.
         *
         * WHY FCS wins under [AUTO]: it covers all seven of this app's timeframes
         * from one endpoint and publishes volume, while Alpha Vantage's FX feed has
         * no H4 or W1 and no volume — and its free tier is 25 requests a day against
         * FCS's 500 a month, which works out to a similar daily allowance but with
         * far less coverage per request.
         */
        fun resolve(
            preference: MarketDataPreference,
            hasFcsKey: Boolean,
            hasAlphaVantageKey: Boolean
        ): MarketDataProvider = when (preference) {
            OFFLINE -> MarketDataProvider.SIMULATED
            FCS_API -> if (hasFcsKey) MarketDataProvider.FCS_API else MarketDataProvider.SIMULATED
            ALPHA_VANTAGE ->
                if (hasAlphaVantageKey) MarketDataProvider.ALPHA_VANTAGE else MarketDataProvider.SIMULATED

            AUTO -> when {
                hasFcsKey -> MarketDataProvider.FCS_API
                hasAlphaVantageKey -> MarketDataProvider.ALPHA_VANTAGE
                else -> MarketDataProvider.SIMULATED
            }
        }

        /**
         * Every candidate feed to try, best first, for [preference].
         *
         * WHY a list and not just the resolved one: under [AUTO] a feed that is out
         * of quota should hand over to the other one before either hands over to the
         * simulator. A single resolved provider cannot express that.
         */
        fun candidates(
            preference: MarketDataPreference,
            hasFcsKey: Boolean,
            hasAlphaVantageKey: Boolean
        ): List<MarketDataProvider> {
            val live = mutableListOf<MarketDataProvider>()
            when (preference) {
                OFFLINE -> Unit
                FCS_API -> if (hasFcsKey) live += MarketDataProvider.FCS_API
                ALPHA_VANTAGE -> if (hasAlphaVantageKey) live += MarketDataProvider.ALPHA_VANTAGE
                AUTO -> {
                    if (hasFcsKey) live += MarketDataProvider.FCS_API
                    if (hasAlphaVantageKey) live += MarketDataProvider.ALPHA_VANTAGE
                }
            }
            // WHY an explicit selection is not allowed to fall through to the other
            // provider: the user asked for one feed, possibly because the other one
            // is the reason their prices look wrong. Overriding an explicit choice
            // would make the setting meaningless.
            return live
        }
    }
}

/**
 * A candle series together with its provenance.
 *
 * WHY not a bare `List<CandleStick>`: the facts a caller needs about a series are
 * where it came from and whether the feed it wanted was reachable. Returning only
 * candles forces the caller to assume "it is fine", and that assumption is exactly
 * what must never be made about prices.
 *
 * @property candles oldest-first.
 * @property source feed that produced [candles].
 * @property fetchedAt when this series was produced.
 * @property degradedReason the classified failure that forced a fallback to a
 *   lesser source, or null when the requested source succeeded. WHY an [AppError]
 *   and not a String: its `userMessage` is already safe to render and its `code` is
 *   already safe to log, whereas a free-form string would sooner or later carry a
 *   provider's raw response body — which can contain the API key.
 */
data class CandleBatch(
    val candles: List<CandleStick>,
    val source: MarketDataProvider,
    val fetchedAt: Instant,
    val degradedReason: AppError? = null
) {
    // WHY there is no invariant tying degradedReason to source: a freshly generated
    // simulation reached *because* a live feed failed carries a reason and is not an
    // error state. Forbidding that would push callers to drop the reason, and the
    // reason is the only thing that tells the user why their prices are invented.

    /** True when these are real market prices from the feed that was asked for. */
    val isLiveAndDirect: Boolean get() = source.isLive && degradedReason == null
}

/**
 * What the chart header tells the user about the prices on screen.
 *
 * WHY a projection of [CandleBatch] rather than the batch itself: the batch is a
 * per-fetch record with an [AppError] attached, and the UI wants one small,
 * always-renderable value. Keeping them separate stops a UI class from ever
 * learning what an `AppError` is.
 *
 * @property provider feed that produced the prices currently on screen.
 * @property isCached true when they were served from the local database rather than
 *   fetched just now. WHY surface it: a live feed on a free plan answers from the
 *   provider's own cache for up to an hour, so "live" does not mean "this second".
 * @property notice safe, actionable text explaining a degraded state, or null when
 *   nothing needs explaining. Rendered as-is; never contains a key or a raw body.
 */
data class DataOrigin(
    val provider: MarketDataProvider,
    val isCached: Boolean,
    val notice: String?
) {
    companion object {
        /** The state before any fetch has happened. */
        val UNKNOWN = DataOrigin(MarketDataProvider.SIMULATED, isCached = true, notice = null)
    }
}
