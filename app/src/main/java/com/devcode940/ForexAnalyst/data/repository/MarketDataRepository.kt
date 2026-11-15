package com.devcode940.ForexAnalyst.data.repository

import com.devcode940.ForexAnalyst.data.model.AlertId
import com.devcode940.ForexAnalyst.data.model.CandleStick
import com.devcode940.ForexAnalyst.data.model.ChartDrawing
import com.devcode940.ForexAnalyst.data.model.CustomAlert
import com.devcode940.ForexAnalyst.data.model.DataOrigin
import com.devcode940.ForexAnalyst.data.model.DrawingId
import com.devcode940.ForexAnalyst.data.model.PairSymbol
import com.devcode940.ForexAnalyst.data.model.Result
import com.devcode940.ForexAnalyst.data.model.Timeframe
import com.devcode940.ForexAnalyst.data.model.TradeSignal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

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
     * Up to [limit] candles (ascending by time) for a pair/timeframe, fetched from the
     * configured [com.devcode940.ForexAnalyst.data.model.CandleSource] and cached on a
     * miss.
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
     * Where the prices on screen came from, and why they might not be live.
     *
     * WHY the repository owns this and not the ViewModel: only the layer that decided
     * between cache, live feed and simulator knows the answer. A ViewModel guessing it
     * from "did the fetch succeed" would report live prices for a cache hit that was
     * written by the simulator an hour ago - the exact mistake this badge exists to
     * prevent.
     */
    val dataOrigin: StateFlow<DataOrigin>

    /**
     * Drops every cached candle so the next fetch reaches the configured source.
     *
     * WHY: the cache TTL is an hour, so adding an API key, switching provider or
     * switching to offline would otherwise appear to do nothing until it expired.
     */
    suspend fun invalidateCandleCache(): Result<Unit>

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

    // ---------- Chart drawings ----------

    /**
     * Bounded stream of the drawings that belong to one chart, oldest first.
     *
     * WHY scoped by pair *and* timeframe: an H1 trendline is an assertion about H1
     * bars. Showing it on the D1 chart of the same pair (or the H1 chart of another)
     * draws a level that means nothing there, which is worse than showing nothing.
     *
     * WHY ascending: the renderer paints in list order, so the newest object ends up
     * on top, and the hit-tester's "last match wins" rule then selects what the user
     * actually touched.
     */
    fun observeDrawings(pairSymbol: PairSymbol, timeframe: Timeframe): Flow<List<ChartDrawing>>

    /** Inserts or replaces [drawing], keyed by its id. */
    suspend fun saveDrawing(drawing: ChartDrawing): Result<Unit>

    /**
     * Inserts or replaces [drawings] in one transaction.
     *
     * WHY a batch variant: undo/redo and "clear" restore whole snapshots, and N
     * separate transactions would emit N intermediate flows the UI has to diff.
     */
    suspend fun saveDrawings(drawings: List<ChartDrawing>): Result<Unit>

    /** Deletes one drawing (no-op if absent). */
    suspend fun deleteDrawing(drawingId: DrawingId): Result<Unit>

    /** Deletes every drawing on one chart. */
    suspend fun deleteDrawingsForChart(pairSymbol: PairSymbol, timeframe: Timeframe): Result<Unit>

    /**
     * Atomically replaces every drawing on one chart with [drawings].
     *
     * WHY: undo/redo restore a whole snapshot, which usually means removing objects
     * as well as adding them. Two separate calls would leave a window where the chart
     * shows nothing.
     */
    suspend fun replaceDrawingsForChart(
        pairSymbol: PairSymbol,
        timeframe: Timeframe,
        drawings: List<ChartDrawing>
    ): Result<Unit>

    companion object {
        /** WHY: Shared default so the interface and implementations agree. */
        const val DEFAULT_CANDLE_LIMIT = 500

        /** WHY: SQLite treats a negative LIMIT as "unlimited" — clamp instead. */
        const val MAX_CANDLE_LIMIT = 5_000
    }
}
