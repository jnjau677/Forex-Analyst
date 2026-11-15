package com.devcode940.ForexAnalyst.data.model

/**
 * Backwards-compatible symbol view over [InstrumentCatalog].
 *
 * WHY: Call sites that only need symbols (watchlist seeding, search) keep their
 * existing API, while everything that needs precision, sessions or a reference
 * price reads [Instrument] from [InstrumentCatalog]. Keeping this as a thin
 * projection means the two can never disagree — the old version was a separate
 * hardcoded list that had already drifted from the repository's price table.
 */
object PairCatalog {

    /** G10 majors — highest liquidity, tightest spreads. */
    val MAJOR_PAIRS: List<PairSymbol> = InstrumentCatalog.MAJORS.map { it.symbol }

    /** Non-USD crosses. */
    val MINOR_PAIRS: List<PairSymbol> = InstrumentCatalog.CROSSES.map { it.symbol }

    /** Metals and crypto quoted against the US dollar. */
    val METALS_AND_CRYPTO: List<PairSymbol> =
        (InstrumentCatalog.METALS + InstrumentCatalog.CRYPTO).map { it.symbol }

    /** Every supported symbol, in catalog order. */
    val ALL_PAIRS: List<PairSymbol> = InstrumentCatalog.ALL.map { it.symbol }

    /**
     * Pairs seeded into a fresh install's watchlist.
     *
     * WHY: seeded exactly once (guarded by a persisted flag in
     * ForexApplication) — an `isEmpty()` guard would resurrect these after the
     * user deliberately cleared the watchlist. The mix spans every market class
     * so a new user immediately sees FX, metal and crypto formatting.
     */
    fun getDefaultWatchlist(): List<PairSymbol> = listOf(
        PairSymbol("EURUSD"),
        PairSymbol("GBPUSD"),
        PairSymbol("USDJPY"),
        PairSymbol("XAUUSD"),
        PairSymbol("BTCUSD")
    )
}
