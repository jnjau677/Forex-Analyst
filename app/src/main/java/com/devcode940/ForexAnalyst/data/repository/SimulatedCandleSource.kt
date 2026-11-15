package com.devcode940.ForexAnalyst.data.repository

import com.devcode940.ForexAnalyst.data.model.CandleBatch
import com.devcode940.ForexAnalyst.data.model.CandleSource
import com.devcode940.ForexAnalyst.data.model.InstrumentCatalog
import com.devcode940.ForexAnalyst.data.model.MarketDataProvider
import com.devcode940.ForexAnalyst.data.model.PairSymbol
import com.devcode940.ForexAnalyst.data.model.Timeframe
import java.time.Clock

/**
 * The offline [CandleSource]: deterministic, locally generated candles.
 *
 * This is the behaviour `ForexRepository` had before live feeds existed, lifted out
 * unchanged so that the app remains fully usable with no API key, no network and no
 * account — which is most of its life in a review or an automated test.
 *
 * WHY the simulation is per-instrument rather than one generic walk: 71 instruments
 * have their own reference price, volatility and quote precision in
 * [InstrumentCatalog]. Without that, a BTCUSD chart moves 0.1 % an hour and quotes
 * five decimals, and every indicator on it is meaningless.
 *
 * WHY the timeframe decides the bar spacing: generating hourly bars for a D1 chart
 * labels 500 consecutive hours as if they were days, which breaks the time axis,
 * the session shading and the VWAP anchor all at once.
 */
class SimulatedCandleSource : CandleSource {

    override suspend fun load(
        pairSymbol: PairSymbol,
        timeframe: Timeframe,
        limit: Int,
        clock: Clock
    ): CandleBatch {
        val candles = PriceGenerator.generateCandles(
            count = limit,
            basePrice = InstrumentCatalog.basePriceFor(pairSymbol),
            volatility = InstrumentCatalog.volatilityFor(pairSymbol),
            intervalMs = timeframe.intervalMillis,
            random = PriceGenerator.seedFor(pairSymbol, timeframe),
            clock = clock
        )
        return CandleBatch(
            candles = candles,
            source = MarketDataProvider.SIMULATED,
            fetchedAt = clock.instant(),
            degradedReason = null
        )
    }
}
