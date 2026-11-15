package com.devcode940.ForexAnalyst.data.model

object PairCatalog {
    val MAJOR_PAIRS = listOf(PairSymbol("EURUSD"), PairSymbol("GBPUSD"), PairSymbol("USDJPY"), PairSymbol("USDCHF"), PairSymbol("AUDUSD"), PairSymbol("USDCAD"), PairSymbol("NZDUSD"))
    val MINOR_PAIRS = listOf(PairSymbol("EURGBP"), PairSymbol("EURJPY"), PairSymbol("GBPJPY"), PairSymbol("AUDJPY"), PairSymbol("CADJPY"))
    val METALS_AND_CRYPTO = listOf(PairSymbol("XAUUSD"), PairSymbol("XAGUSD"), PairSymbol("BTCUSD"), PairSymbol("ETHUSD"))
    val ALL_PAIRS = MAJOR_PAIRS + MINOR_PAIRS + METALS_AND_CRYPTO
    fun getDefaultWatchlist(): List<PairSymbol> = listOf(PairSymbol("EURUSD"), PairSymbol("GBPUSD"), PairSymbol("XAUUSD"), PairSymbol("BTCUSD"))
}