package com.devcode940.ForexAnalyst.data.repository

import com.devcode940.ForexAnalyst.data.model.AlertId
import com.devcode940.ForexAnalyst.data.model.CandleStick
import com.devcode940.ForexAnalyst.data.model.CustomAlert
import com.devcode940.ForexAnalyst.data.model.PairSymbol
import com.devcode940.ForexAnalyst.data.model.Result
import com.devcode940.ForexAnalyst.data.model.Timeframe
import com.devcode940.ForexAnalyst.data.model.TradeSignal
import kotlinx.coroutines.flow.Flow

/**
 * Abstraction over all persisted market data, watchlist, signal and alert access.
 *
 * WHY: Program against this interface (DIP) — the ViewModel no longer
 * hardwires a concrete Room-backed repository, so it can be unit-tested with
 * an in-memory fake on the JVM (no Android, no emulator). Every fallible
 * call returns [Result] instead of throwing, so no internal exception can
 * escape into the presentation layer.
 *
 * WHY Flow (not suspend) for observation: Room pushes incremental updates —
 * the UI never polls and never loads more than the bounded DAO LIMIT.
 */
interface MarketDataRepository {

    /** Bounded stream of saved signals for one pair, newest first. */
    fun observeSignals(pairSymbol: PairSymbol): Flow<List<TradeSignal>>

    /** Bounded stream of the user's bookmarked signals across all pairs. */
    fun observeBookmarkedSignals(): Flow<List<TradeSignal>>

    /** Stream of the user's watchlist symbols. */
    fun observeWatchlist(): Flow<List<PairSymbol>>

    /** Stream of active (not yet triggered) alerts for one pair. */
    fun observeAlerts(pairSymbol: PairSymbol): Flow<List<CustomAlert>>

    /**
     * Up to [limit] candles (ascending by time) for a pair/timeframe,
     * generated and cached on a miss.
     *
     * WHY: timeframe is a parameter — hardcoding it silently served the
     * wrong granularity whenever the user switched the chart timeframe.
     */
    suspend fun fetchHistoricalCandles(
        pairSymbol: PairSymbol,
        timeframe: Timeframe,
        limit: Int = DEFAULT_CANDLE_LIMIT
    ): Result<List<CandleStick>>

    /**
     * Persists [signals] idempotently.
     *
     * WHY: Safe to retry — a unique content fingerprint plus
     * OnConflictStrategy.IGNORE means a duplicate save is a no-op, not an
     * exception and not a second row.
     */
    suspend fun saveSignals(signals: List<TradeSignal>): Result<Unit>

    /** One-shot read of every watchlist symbol. */
    suspend fun getActiveWatchlistPairs(): Result<List<PairSymbol>>

    /** Adds [pairSymbol] to the watchlist (no-op if already present). */
    suspend fun addToWatchlist(pairSymbol: PairSymbol): Result<Unit>

    /** Removes [pairSymbol] from the watchlist (no-op if absent). */
    suspend fun removeFromWatchlist(pairSymbol: PairSymbol): Result<Unit>

    /** One-shot read of all active alerts. */
    suspend fun getActiveAlerts(): Result<List<CustomAlert>>

    /** Inserts or replaces [alert] keyed by its id. */
    suspend fun saveAlert(alert: CustomAlert): Result<Unit>

    /** Deletes the alert with [alertId] (no-op if absent). */
    suspend fun deleteAlert(alertId: AlertId): Result<Unit>

    companion object {
        /** WHY: Shared default so the interface and implementations agree. */
        const val DEFAULT_CANDLE_LIMIT = 500

        /** WHY: SQLite treats a negative LIMIT as "unlimited" — clamp instead. */
        const val MAX_CANDLE_LIMIT = 5_000
    }
}
