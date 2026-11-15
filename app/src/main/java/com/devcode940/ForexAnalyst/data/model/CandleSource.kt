package com.devcode940.ForexAnalyst.data.model

import java.time.Clock

/**
 * Where the repository gets its candles from.
 *
 * WHY this port exists: `ForexRepository` used to call `PriceGenerator` directly,
 * which made the simulator the only possible source and hard-wired "no live data"
 * into the class that owns the cache. Extracting the source means the cache,
 * retention, mutex and pruning logic — the parts that are actually subtle and
 * actually tested — stay exactly where they are, while a live feed is plugged in
 * beside the simulator rather than spliced into the middle of them.
 *
 * Implementations:
 * - `SimulatedCandleSource` — the offline default, unchanged behaviour;
 * - `LiveCandleSource` — FCS / Alpha Vantage, with the simulator as its fallback.
 *
 * Contract: return a [CandleBatch] with [CandleBatch.candles] oldest-first, or
 * throw. Implementations must never throw for "the feed is down" — that is what
 * [CandleBatch.degradedReason] is for. Reserving exceptions for genuine programming
 * errors keeps the caller's `catch` meaningful.
 */
interface CandleSource {

    /**
     * Produces up to [limit] candles for one chart.
     *
     * @param clock injected rather than read from the system so cache-freshness and
     *   the simulator's "now" stay deterministically testable.
     */
    suspend fun load(
        pairSymbol: PairSymbol,
        timeframe: Timeframe,
        limit: Int,
        clock: Clock
    ): CandleBatch
}
