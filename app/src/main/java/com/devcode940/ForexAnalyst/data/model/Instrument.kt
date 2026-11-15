package com.devcode940.ForexAnalyst.data.model

import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.pow

/**
 * Market category an instrument belongs to.
 *
 * WHY: Drives grouping in the instrument picker and the default volatility used
 * by the simulator. An enum (not a String) keeps the set closed, so a new
 * category forces a compile-time review of every `when` that switches on it.
 *
 * @property displayName human-readable group heading.
 * @property sortWeight ordering used when listing categories.
 */
enum class InstrumentCategory(val displayName: String, val sortWeight: Int) {
    MAJORS("Majors", 0),
    CROSSES("Crosses", 1),
    EXOTICS("Exotics", 2),
    METALS("Metals", 3),
    ENERGY("Energy", 4),
    INDICES("Indices", 5),
    CRYPTO("Crypto", 6)
}

/**
 * A trading session, expressed in UTC hours `[startHourUtc, endHourUtc)`.
 *
 * WHY: Forex liquidity is not uniform across the day — the London/New York
 * overlap is where most volatility happens. Sessions let the chart shade those
 * windows and let the simulator pick a sensible default volatility.
 *
 * Sydney wraps past midnight UTC, so [isActiveAt] handles the wrap explicitly
 * rather than assuming `start < end`.
 */
enum class TradingSession(
    val displayName: String,
    val startHourUtc: Int,
    val endHourUtc: Int
) {
    SYDNEY("Sydney", 22, 7),
    TOKYO("Tokyo", 0, 9),
    LONDON("London", 7, 16),
    NEW_YORK("New York", 12, 21);

    /** True when [instant] falls inside this session (UTC), handling midnight wrap. */
    fun isActiveAt(instant: Instant): Boolean {
        val hour = instant.atZone(ZoneOffset.UTC).hour
        return if (startHourUtc < endHourUtc) {
            hour in startHourUtc until endHourUtc
        } else {
            // WHY: Sydney runs 22:00 -> 07:00 UTC; a plain range check would
            // report it closed for two thirds of the day.
            hour >= startHourUtc || hour < endHourUtc
        }
    }

    companion object {
        /** All sessions, in chronological order of their UTC start. */
        val ORDERED: List<TradingSession> = listOf(TOKYO, LONDON, NEW_YORK, SYDNEY)

        /** Sessions open at [instant], chronological. */
        fun activeAt(instant: Instant): List<TradingSession> = ORDERED.filter { it.isActiveAt(instant) }
    }
}

/**
 * A tradable instrument and everything the app needs to render it correctly.
 *
 * WHY: The previous catalog was a bare list of symbols, which forced the UI to
 * hardcode 5-decimal formatting (`"%.5f"`) for everything — wrong for JPY pairs
 * (3 digits), gold (2) and crypto. Precision is a property of the instrument, so
 * it belongs to the instrument.
 *
 * @property symbol canonical uppercase symbol, e.g. `EURUSD`.
 * @property displayName human label, e.g. `Euro / US Dollar`.
 * @property category market group.
 * @property quoteDigits decimals used to quote the price (5 for most FX, 3 for
 *   JPY pairs, 2 for metals/crypto/indices).
 * @property basePrice reference price used by the simulator when no live feed
 *   is configured; also the seed for the first generated candle.
 * @property volatility per-bar relative volatility (`0.001` ~= 0.1 % moves),
 *   used by the simulator so gold does not move like EUR/USD.
 * @property sessions sessions during which the instrument trades; used for
 *   session shading. Continuous markets list every session.
 */
data class Instrument(
    val symbol: PairSymbol,
    val displayName: String,
    val category: InstrumentCategory,
    val quoteDigits: Int,
    val basePrice: Double,
    val volatility: Double,
    val sessions: Set<TradingSession>
) {
    init {
        // WHY: Fail fast at catalog construction (class-init time) rather than
        // rendering a broken chart later. quoteDigits drives every price string
        // in the app, so an out-of-range value would corrupt all of them.
        require(quoteDigits in 0..8) { "$symbol: quoteDigits must be 0..8 (got $quoteDigits)" }
        require(basePrice.isFinite() && basePrice > 0.0) {
            "$symbol: basePrice must be finite and > 0 (got $basePrice)"
        }
        require(volatility.isFinite() && volatility in 0.0..0.5) {
            "$symbol: volatility must be finite and within 0..0.5 (got $volatility)"
        }
        require(displayName.isNotBlank()) { "$symbol: displayName cannot be blank" }
        require(sessions.isNotEmpty()) { "$symbol: at least one session must be declared" }
    }

    /**
     * Smallest price increment, i.e. one unit of the last quoted digit.
     *
     * WHY: Derived from [quoteDigits] instead of declared per instrument — a
     * hand-declared value can silently disagree with the digit count and make
     * pip maths wrong for that symbol only (the hardest kind of bug to spot).
     */
    val tickSize: Double get() = 10.0.pow(-quoteDigits.toDouble())

    /**
     * One pip: the second-to-last quoted digit, the industry convention for FX
     * (`0.0001` on a 5-digit pair, `0.01` on a 3-digit JPY pair).
     *
     * WHY: For metals, indices and crypto a "pip" is a display convention only;
     * those markets quote points. Kept uniform so delta readouts never divide by
     * zero or by a per-symbol special case.
     */
    val pipSize: Double get() = 10.0.pow(-(quoteDigits - 1).toDouble())

    /** Converts a price delta into pips. */
    fun toPips(priceDelta: Double): Double = priceDelta / pipSize

    /** True when the instrument trades around the clock. */
    val isContinuous: Boolean get() = sessions.size == TradingSession.entries.size

    /** Sessions open at [instant] for this instrument. */
    fun sessionsActiveAt(instant: Instant): List<TradingSession> =
        TradingSession.activeAt(instant).filter { it in sessions }
}

/**
 * Static catalog of every instrument this build supports.
 *
 * WHY: One source of truth for symbols, precision, simulator seeds and sessions.
 * The repository, the chart, the watchlist picker and the alert dialog all read
 * from here, so adding a currency is a one-line change that cannot drift between
 * layers (previously `ForexRepository.basePriceFor()` had its own hardcoded
 * `when` with `symbol.contains("JPY")` heuristics).
 */
object InstrumentCatalog {

    /**
     * FX and metals trade across all four sessions, five days a week.
     *
     * WHY declared first: an `object`'s property initializers run in
     * declaration order — a list below that references a session set declared
     * further down would read a still-uninitialized field and fail the
     * `sessions.isNotEmpty()` invariant at class-init time.
     */
    private val FX_24H: Set<TradingSession> = TradingSession.entries.toSet()

    /** Continuous markets (crypto) never close. */
    private val ALL_SESSIONS: Set<TradingSession> = TradingSession.entries.toSet()

    private const val DEFAULT_BASE_PRICE = 1.1000
    private const val DEFAULT_VOLATILITY = 0.0010
    private const val DEFAULT_QUOTE_DIGITS = 5

    /** FX majors: the seven USD-quoted G10 pairs. */
    val MAJORS: List<Instrument> = listOf(
        fx("EURUSD", "Euro / US Dollar", InstrumentCategory.MAJORS, 5, 1.0850, 0.0007),
        fx("GBPUSD", "British Pound / US Dollar", InstrumentCategory.MAJORS, 5, 1.2650, 0.0008),
        fx("USDJPY", "US Dollar / Japanese Yen", InstrumentCategory.MAJORS, 3, 151.50, 0.0008),
        fx("USDCHF", "US Dollar / Swiss Franc", InstrumentCategory.MAJORS, 5, 0.9050, 0.0007),
        fx("AUDUSD", "Australian Dollar / US Dollar", InstrumentCategory.MAJORS, 5, 0.6550, 0.0008),
        fx("USDCAD", "US Dollar / Canadian Dollar", InstrumentCategory.MAJORS, 5, 1.3650, 0.0007),
        fx("NZDUSD", "New Zealand Dollar / US Dollar", InstrumentCategory.MAJORS, 5, 0.6050, 0.0009)
    )

    /** Non-USD crosses. */
    val CROSSES: List<Instrument> = listOf(
        fx("EURGBP", "Euro / British Pound", InstrumentCategory.CROSSES, 5, 0.8580, 0.0006),
        fx("EURJPY", "Euro / Japanese Yen", InstrumentCategory.CROSSES, 3, 164.40, 0.0009),
        fx("EURCHF", "Euro / Swiss Franc", InstrumentCategory.CROSSES, 5, 0.9820, 0.0005),
        fx("EURAUD", "Euro / Australian Dollar", InstrumentCategory.CROSSES, 5, 1.6560, 0.0010),
        fx("EURCAD", "Euro / Canadian Dollar", InstrumentCategory.CROSSES, 5, 1.4810, 0.0008),
        fx("EURNZD", "Euro / New Zealand Dollar", InstrumentCategory.CROSSES, 5, 1.8060, 0.0010),
        fx("GBPJPY", "British Pound / Japanese Yen", InstrumentCategory.CROSSES, 3, 191.60, 0.0011),
        fx("GBPAUD", "British Pound / Australian Dollar", InstrumentCategory.CROSSES, 5, 1.9300, 0.0011),
        fx("GBPCAD", "British Pound / Canadian Dollar", InstrumentCategory.CROSSES, 5, 1.7260, 0.0009),
        fx("GBPCHF", "British Pound / Swiss Franc", InstrumentCategory.CROSSES, 5, 1.1450, 0.0008),
        fx("GBPNZD", "British Pound / New Zealand Dollar", InstrumentCategory.CROSSES, 5, 2.0900, 0.0011),
        fx("AUDJPY", "Australian Dollar / Japanese Yen", InstrumentCategory.CROSSES, 3, 99.20, 0.0009),
        fx("AUDCAD", "Australian Dollar / Canadian Dollar", InstrumentCategory.CROSSES, 5, 0.8940, 0.0007),
        fx("AUDCHF", "Australian Dollar / Swiss Franc", InstrumentCategory.CROSSES, 5, 0.5930, 0.0007),
        fx("AUDNZD", "Australian Dollar / New Zealand Dollar", InstrumentCategory.CROSSES, 5, 1.0830, 0.0007),
        fx("CADJPY", "Canadian Dollar / Japanese Yen", InstrumentCategory.CROSSES, 3, 111.00, 0.0008),
        fx("CHFJPY", "Swiss Franc / Japanese Yen", InstrumentCategory.CROSSES, 3, 167.40, 0.0008),
        fx("NZDJPY", "New Zealand Dollar / Japanese Yen", InstrumentCategory.CROSSES, 3, 91.60, 0.0009),
        fx("NZDCAD", "New Zealand Dollar / Canadian Dollar", InstrumentCategory.CROSSES, 5, 0.8260, 0.0007),
        fx("NZDCHF", "New Zealand Dollar / Swiss Franc", InstrumentCategory.CROSSES, 5, 0.5470, 0.0007)
    )

    /**
     * Emerging-market and thinly traded pairs.
     *
     * WHY included: these carry the widest spreads and the highest volatility,
     * which is exactly what a signal/alert tool is useful for. Note that a
     * 4-digit quote on TRY/ZAR/MXN pairs is a broker convention, not a rule —
     * [quoteDigits] is declared per instrument for that reason.
     */
    val EXOTICS: List<Instrument> = listOf(
        fx("USDTRY", "US Dollar / Turkish Lira", InstrumentCategory.EXOTICS, 4, 32.4000, 0.0025),
        fx("EURTRY", "Euro / Turkish Lira", InstrumentCategory.EXOTICS, 4, 35.1500, 0.0026),
        fx("GBPTRY", "British Pound / Turkish Lira", InstrumentCategory.EXOTICS, 4, 40.9500, 0.0028),
        fx("USDMXN", "US Dollar / Mexican Peso", InstrumentCategory.EXOTICS, 4, 16.8500, 0.0014),
        fx("USDZAR", "US Dollar / South African Rand", InstrumentCategory.EXOTICS, 4, 18.6000, 0.0016),
        fx("EURZAR", "Euro / South African Rand", InstrumentCategory.EXOTICS, 4, 20.1800, 0.0017),
        fx("ZARJPY", "South African Rand / Japanese Yen", InstrumentCategory.EXOTICS, 3, 8.1400, 0.0016),
        fx("USDSGD", "US Dollar / Singapore Dollar", InstrumentCategory.EXOTICS, 5, 1.3450, 0.0005),
        fx("USDHKD", "US Dollar / Hong Kong Dollar", InstrumentCategory.EXOTICS, 4, 7.8200, 0.0002),
        fx("USDNOK", "US Dollar / Norwegian Krone", InstrumentCategory.EXOTICS, 4, 10.6500, 0.0011),
        fx("USDSEK", "US Dollar / Swedish Krona", InstrumentCategory.EXOTICS, 4, 10.5500, 0.0011),
        fx("USDDKK", "US Dollar / Danish Krone", InstrumentCategory.EXOTICS, 4, 6.9000, 0.0009),
        fx("USDPLN", "US Dollar / Polish Zloty", InstrumentCategory.EXOTICS, 4, 3.9800, 0.0011),
        fx("USDHUF", "US Dollar / Hungarian Forint", InstrumentCategory.EXOTICS, 2, 360.50, 0.0013),
        fx("USDCNH", "US Dollar / Offshore Yuan", InstrumentCategory.EXOTICS, 4, 7.2400, 0.0005),
        fx("USDINR", "US Dollar / Indian Rupee", InstrumentCategory.EXOTICS, 4, 83.4000, 0.0006),
        fx("USDKES", "US Dollar / Kenyan Shilling", InstrumentCategory.EXOTICS, 4, 129.5000, 0.0008),
        fx("USDNGN", "US Dollar / Nigerian Naira", InstrumentCategory.EXOTICS, 2, 1580.00, 0.0030),
        fx("USDEGP", "US Dollar / Egyptian Pound", InstrumentCategory.EXOTICS, 4, 47.8000, 0.0022),
        fx("USDBRL", "US Dollar / Brazilian Real", InstrumentCategory.EXOTICS, 4, 5.1200, 0.0014)
    )

    /** Precious metals, quoted in USD. */
    val METALS: List<Instrument> = listOf(
        Instrument(PairSymbol("XAUUSD"), "Gold / US Dollar", InstrumentCategory.METALS, 2, 2350.00, 0.0014, FX_24H),
        Instrument(PairSymbol("XAGUSD"), "Silver / US Dollar", InstrumentCategory.METALS, 3, 27.850, 0.0022, FX_24H),
        Instrument(PairSymbol("XPTUSD"), "Platinum / US Dollar", InstrumentCategory.METALS, 2, 985.00, 0.0016, FX_24H),
        Instrument(PairSymbol("XAUEUR"), "Gold / Euro", InstrumentCategory.METALS, 2, 2165.00, 0.0015, FX_24H)
    )

    /** Energy CFDs. */
    val ENERGY: List<Instrument> = listOf(
        Instrument(PairSymbol("USOIL"), "WTI Crude Oil", InstrumentCategory.ENERGY, 2, 82.50, 0.0022, FX_24H),
        Instrument(PairSymbol("UKOIL"), "Brent Crude Oil", InstrumentCategory.ENERGY, 2, 86.40, 0.0021, FX_24H),
        Instrument(PairSymbol("NATGAS"), "Natural Gas", InstrumentCategory.ENERGY, 3, 2.750, 0.0045, FX_24H)
    )

    /**
     * Index CFDs.
     *
     * WHY sessions are restricted: equity indices close overnight and at
     * weekends, unlike FX. Declaring the session lets the chart shade the closed
     * window instead of implying a 24-hour market.
     */
    val INDICES: List<Instrument> = listOf(
        Instrument(PairSymbol("SPX500"), "S&P 500", InstrumentCategory.INDICES, 2, 5450.00, 0.0011, setOf(TradingSession.NEW_YORK)),
        Instrument(PairSymbol("NAS100"), "Nasdaq 100", InstrumentCategory.INDICES, 2, 19800.00, 0.0014, setOf(TradingSession.NEW_YORK)),
        Instrument(PairSymbol("US30"), "Dow Jones 30", InstrumentCategory.INDICES, 2, 40200.00, 0.0010, setOf(TradingSession.NEW_YORK)),
        Instrument(PairSymbol("GER40"), "DAX 40", InstrumentCategory.INDICES, 2, 18400.00, 0.0012, setOf(TradingSession.LONDON)),
        Instrument(PairSymbol("UK100"), "FTSE 100", InstrumentCategory.INDICES, 2, 8350.00, 0.0010, setOf(TradingSession.LONDON)),
        Instrument(PairSymbol("JPN225"), "Nikkei 225", InstrumentCategory.INDICES, 2, 39800.00, 0.0013, setOf(TradingSession.TOKYO)),
        Instrument(PairSymbol("AUS200"), "ASX 200", InstrumentCategory.INDICES, 2, 7850.00, 0.0010, setOf(TradingSession.SYDNEY))
    )

    /** Crypto, quoted in USD — trades continuously, no session gaps. */
    val CRYPTO: List<Instrument> = listOf(
        Instrument(PairSymbol("BTCUSD"), "Bitcoin / US Dollar", InstrumentCategory.CRYPTO, 2, 67500.00, 0.0220, ALL_SESSIONS),
        Instrument(PairSymbol("ETHUSD"), "Ethereum / US Dollar", InstrumentCategory.CRYPTO, 2, 3450.00, 0.0260, ALL_SESSIONS),
        Instrument(PairSymbol("SOLUSD"), "Solana / US Dollar", InstrumentCategory.CRYPTO, 3, 172.500, 0.0340, ALL_SESSIONS),
        Instrument(PairSymbol("BNBUSD"), "BNB / US Dollar", InstrumentCategory.CRYPTO, 2, 595.00, 0.0240, ALL_SESSIONS),
        Instrument(PairSymbol("XRPUSD"), "XRP / US Dollar", InstrumentCategory.CRYPTO, 4, 0.6250, 0.0300, ALL_SESSIONS),
        Instrument(PairSymbol("DOGEUSD"), "Dogecoin / US Dollar", InstrumentCategory.CRYPTO, 5, 0.15800, 0.0380, ALL_SESSIONS),
        Instrument(PairSymbol("ADAUSD"), "Cardano / US Dollar", InstrumentCategory.CRYPTO, 4, 0.4650, 0.0320, ALL_SESSIONS),
        Instrument(PairSymbol("AVAXUSD"), "Avalanche / US Dollar", InstrumentCategory.CRYPTO, 3, 36.400, 0.0350, ALL_SESSIONS),
        Instrument(PairSymbol("LINKUSD"), "Chainlink / US Dollar", InstrumentCategory.CRYPTO, 3, 17.850, 0.0330, ALL_SESSIONS),
        Instrument(PairSymbol("MATICUSD"), "Polygon / US Dollar", InstrumentCategory.CRYPTO, 4, 0.7250, 0.0360, ALL_SESSIONS)
    )

    /** Every supported instrument, ordered by category then declaration. */
    val ALL: List<Instrument> =
        (MAJORS + CROSSES + EXOTICS + METALS + ENERGY + INDICES + CRYPTO)
            .sortedWith(compareBy({ it.category.sortWeight }, { it.symbol.value }))

    private val bySymbol: Map<PairSymbol, Instrument> = ALL.associateBy { it.symbol }

    /**
     * Categories present in the catalog, in display order.
     *
     * WHY: The instrument picker renders from this instead of hardcoding group
     * headings, so a new category appears in the UI automatically.
     */
    val CATEGORIES: List<InstrumentCategory> =
        InstrumentCategory.entries.sortedBy { it.sortWeight }.filter { category -> ALL.any { it.category == category } }

    /** Instruments in [category], in catalog order. */
    fun inCategory(category: InstrumentCategory): List<Instrument> = ALL.filter { it.category == category }

    /**
     * Looks up an instrument by symbol, or null when unsupported.
     *
     * WHY: A map lookup (O(1)) instead of `ALL.firstOrNull { }` (O(n)) — this is
     * called per candle render for price formatting, i.e. thousands of times per
     * second while panning the chart.
     */
    fun specFor(symbol: PairSymbol): Instrument? = bySymbol[symbol]

    /**
     * Looks up by raw symbol text, normalizing case, padding and separators.
     *
     * WHY strip `/`, `-`, `_` and inner spaces: that is how people type instruments in
     * a search box ("eur/usd", "XAU-USD"), and a lookup that only accepts the bare
     * canonical form answers "no such instrument" to a query the user can see on
     * screen. Normalizing here keeps every caller (search, navigation argument,
     * alert dialog) from writing its own slightly different cleanup.
     */
    fun specFor(rawSymbol: String): Instrument? = runCatching {
        val canonical = rawSymbol.trim().uppercase().filter { it.isLetterOrDigit() }
        bySymbol[PairSymbol(canonical)]
    }.getOrNull()

    /**
     * Reference price for [symbol], or [DEFAULT_BASE_PRICE] for unknown symbols.
     *
     * WHY: Replaces the repository's `symbol.contains("JPY")` heuristics, which
     * mispriced anything not explicitly listed (e.g. XPTUSD would have been
     * simulated at 1.10).
     */
    fun basePriceFor(symbol: PairSymbol): Double = bySymbol[symbol]?.basePrice ?: DEFAULT_BASE_PRICE

    /** Per-bar volatility for [symbol]. */
    fun volatilityFor(symbol: PairSymbol): Double = bySymbol[symbol]?.volatility ?: DEFAULT_VOLATILITY

    /** Decimal places used to quote [symbol]; falls back to 5 (FX convention). */
    fun quoteDigitsFor(symbol: PairSymbol): Int = bySymbol[symbol]?.quoteDigits ?: DEFAULT_QUOTE_DIGITS

    /** Total number of supported instruments. */
    val size: Int get() = ALL.size

    /** Builds an FX instrument that trades across every session. */
    private fun fx(
        symbol: String,
        name: String,
        category: InstrumentCategory,
        digits: Int,
        basePrice: Double,
        volatility: Double
    ): Instrument = Instrument(
        symbol = PairSymbol(symbol),
        displayName = name,
        category = category,
        quoteDigits = digits,
        basePrice = basePrice,
        volatility = volatility,
        sessions = FX_24H
    )
}
