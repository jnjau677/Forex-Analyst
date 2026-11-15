package com.devcode940.ForexAnalyst.data.provider

import com.devcode940.ForexAnalyst.data.model.CandleStick
import com.devcode940.ForexAnalyst.data.model.InstrumentCategory
import com.devcode940.ForexAnalyst.data.model.InstrumentCatalog
import com.devcode940.ForexAnalyst.data.model.MarketDataException
import com.devcode940.ForexAnalyst.data.model.MarketDataProvider
import com.devcode940.ForexAnalyst.data.model.PairSymbol
import com.devcode940.ForexAnalyst.data.model.Timeframe
import java.time.Clock

/**
 * One market-data feed, expressed purely in domain types.
 *
 * WHY this is the seam: the rest of the app talks to [MarketDataProvider] plus
 * [CandleStick] and knows nothing about URLs, JSON shapes or quota rules. Adding
 * a fourth feed means adding one class — no UI change, no schema change, no
 * ViewModel change. That is the entire point of keeping the interface in the
 * domain vocabulary rather than leaking Retrofit into the repository.
 *
 * Contract: implementations either return candles ordered oldest-first, or throw
 * a [MarketDataException] carrying a classified `AppError`. They must never
 * return an empty list to mean "failed" — an empty list means "this instrument
 * genuinely has no bars yet", which the caller must be able to distinguish from
 * a rejected key.
 */
interface MarketDataClient {

    /** Which feed this client is. Used for provenance badges and quota accounting. */
    val provider: MarketDataProvider

    /**
     * Cheap, synchronous capability check.
     *
     * WHY it exists separately from [fetchCandles]: the gateway asks every
     * candidate feed before spending a request on one that cannot serve the
     * instrument. A `supports()` that is wrong in the pessimistic direction
     * costs a fallback; one that is wrong in the optimistic direction costs a
     * quota unit, which is the scarcer resource.
     */
    fun supports(pair: PairSymbol, timeframe: Timeframe): Boolean

    /**
     * Returns up to [limit] candles, oldest-first.
     *
     * @throws MarketDataException with `AppError.Unauthorized`, `RateLimited`,
     *   `Unsupported`, `ProviderRejected` or `Network` — never a raw exception.
     */
    suspend fun fetchCandles(
        pair: PairSymbol,
        timeframe: Timeframe,
        limit: Int,
        clock: Clock
    ): List<CandleStick>
}

/**
 * Splits this app's concatenated symbol (`EURUSD`, `BTCUSD`, `XAUUSD`) into the
 * two currency codes every FX feed expects (`EUR` / `USD`).
 *
 * WHY a dedicated type: the naive implementation is `symbol.substring(0, 3)`,
 * which is wrong for every crypto symbol whose base code is not three
 * characters (`DOGEUSD` -> `DOG`/`EUSD`, `MATICUSD` -> `MAT`/`ICUSD`) and
 * meaningless for indices (`SPX500` -> `SPX`/`500`). Both failures produce a
 * *plausible-looking* request that the provider answers with "unknown symbol",
 * which the user reads as "this app is broken".
 *
 * @property base base/first currency, e.g. `BTC`.
 * @property quote quote/counter currency, e.g. `USD`.
 * @property slashForm the `BASE/QUOTE` spelling FCS uses.
 */
data class InstrumentSplit(val base: String, val quote: String) {
    init {
        require(base.length >= MIN_CODE_LENGTH) { "base currency code too short: '$base'" }
        require(quote.length >= MIN_CODE_LENGTH) { "quote currency code too short: '$quote'" }
    }

    val slashForm: String get() = "$base/$quote"

    companion object {
        /** Shortest real currency code (all ISO 4217 codes are three characters). */
        private const val MIN_CODE_LENGTH = 3

        /**
         * Categories an FX/crypto feed can serve at all.
         *
         * WHY indices and energy are excluded rather than sent and rejected:
         * `SPX500`, `NAS100`, `GER40`, `USOIL`, `NATGAS` and friends are CFD-style
         * tickers invented by brokers. They have no ISO code, so no two providers
         * spell them the same way, and neither of the feeds wired here serves them.
         * Claiming support would spend a quota unit to receive an error.
         */
        private val SERVABLE_CATEGORIES: Set<InstrumentCategory> = setOf(
            InstrumentCategory.MAJORS,
            InstrumentCategory.CROSSES,
            InstrumentCategory.EXOTICS,
            InstrumentCategory.METALS,
            InstrumentCategory.CRYPTO
        )

        /**
         * Splits [pair], or returns null when the instrument is not servable by an
         * FX/crypto feed.
         *
         * The quote is the *trailing* three characters and the base is everything
         * before them. That covers every symbol in the catalog: six-character FX
         * pairs split 3+3, and variable-length crypto bases (`DOGEUSD`,
         * `MATICUSD`) keep their full code.
         */
        fun of(pair: PairSymbol): InstrumentSplit? {
            val symbol = pair.value
            if (symbol.length < MIN_CODE_LENGTH * 2) return null
            val category = InstrumentCatalog.specFor(pair)?.category ?: return null
            if (category !in SERVABLE_CATEGORIES) return null
            val base = symbol.dropLast(3)
            val quote = symbol.takeLast(3)
            return runCatching { InstrumentSplit(base, quote) }.getOrNull()
        }
    }
}

/**
 * Maps this app's [Timeframe] onto the interval spelling a specific feed uses.
 *
 * WHY per-feed instead of one global map: Alpha Vantage's FX endpoint only offers
 * `1min,5min,15min,30min,60min` plus a separate daily endpoint, while FCS has a
 * single `period` ladder covering `1m..1w`. A shared map would have to be the
 * intersection, silently dropping H4/D1/W1 for the feed that *could* serve them.
 */
interface TimeframeMapper {
    /** Provider spelling for [timeframe], or null when the feed cannot serve it. */
    fun intervalFor(timeframe: Timeframe): String?
}
