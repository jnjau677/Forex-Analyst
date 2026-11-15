package com.devcode940.ForexAnalyst.data.model

/**
 * Static catalog of the instruments this build supports.
 *
 * WHY: one place defines what can be watched, searched and charted. Symbols are
 * canonical literals, so [PairSymbol]'s invariant (non-blank) is satisfied at
 * compile time rather than discovered at runtime.
 */
object PairCatalog {

    /** G10 majors — highest liquidity, tightest spreads. */
    val MAJOR_PAIRS: List<PairSymbol> = listOf(
        PairSymbol("EURUSD"),
        PairSymbol("GBPUSD"),
        PairSymbol("USDJPY"),
        PairSymbol("USDCHF"),
        PairSymbol("AUDUSD"),
        PairSymbol("USDCAD"),
        PairSymbol("NZDUSD")
    )

    /** Crosses without the US dollar. */
    val MINOR_PAIRS: List<PairSymbol> = listOf(
        PairSymbol("EURGBP"),
        PairSymbol("EURJPY"),
        PairSymbol("GBPJPY"),
        PairSymbol("AUDJPY"),
        PairSymbol("CADJPY")
    )

    /** Metals and crypto quoted against the US dollar. */
    val METALS_AND_CRYPTO: List<PairSymbol> = listOf(
        PairSymbol("XAUUSD"),
        PairSymbol("XAGUSD"),
        PairSymbol("BTCUSD"),
        PairSymbol("ETHUSD")
    )

    /** Every supported instrument, in catalog order. */
    val ALL_PAIRS: List<PairSymbol> = MAJOR_PAIRS + MINOR_PAIRS + METALS_AND_CRYPTO

    /**
     * Pairs seeded into a fresh install's watchlist.
     *
     * WHY: seeded exactly once (guarded by a persisted flag in
     * ForexApplication) — an `isEmpty()` guard would resurrect these after the
     * user deliberately cleared the watchlist.
     */
    fun getDefaultWatchlist(): List<PairSymbol> = listOf(
        PairSymbol("EURUSD"),
        PairSymbol("GBPUSD"),
        PairSymbol("XAUUSD"),
        PairSymbol("BTCUSD")
    )
}
