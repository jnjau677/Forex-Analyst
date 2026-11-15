package com.devcode940.ForexAnalyst.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import java.time.Instant

@Dao
interface SignalDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSignal(signal: SavedSignalEntity)

    /** WHY: Batch insert = one transaction instead of N round-trips. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSignals(signals: List<SavedSignalEntity>)

    /**
     * WHY: saved_signals grows forever — an unbounded Flow re-emits the
     * entire history on every insert and the UI diffs all of it. Cap at
     * [limit] (most recent first); paginate further if the UI needs it.
     */
    @Query("SELECT * FROM saved_signals WHERE pairSymbol = :pairSymbol ORDER BY timestamp DESC LIMIT :limit")
    fun observeSignalsByPair(pairSymbol: String, limit: Int = 200): Flow<List<SavedSignalEntity>>

    @Query("SELECT * FROM saved_signals WHERE isBookmarked = 1 ORDER BY timestamp DESC LIMIT :limit")
    fun observeBookmarkedSignals(limit: Int = 200): Flow<List<SavedSignalEntity>>

    @Update
    suspend fun updateSignal(signal: SavedSignalEntity)

    @Query("DELETE FROM saved_signals WHERE id = :id")
    suspend fun deleteSignal(id: String)
}

@Dao
interface WatchlistDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItem(item: WatchlistItemEntity)

    /** WHY: One batch insert for seeding instead of N single inserts. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<WatchlistItemEntity>)

    @Query("SELECT * FROM watchlist_items ORDER BY addedAt DESC")
    fun observeAllItems(): Flow<List<WatchlistItemEntity>>

    @Query("SELECT symbol FROM watchlist_items")
    suspend fun getAllSymbols(): List<String>

    @Query("DELETE FROM watchlist_items WHERE symbol = :symbol")
    suspend fun deleteItem(symbol: String)
}

@Dao
interface AlertDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAlert(alert: CustomAlertEntity)

    /** WHY: Deterministic order + cap — the old query returned rows in
     * arbitrary order with no bound. */
    @Query("SELECT * FROM custom_alerts WHERE isActive = 1 AND isTriggered = 0 ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getActiveAlerts(limit: Int = 500): List<CustomAlertEntity>

    @Query("SELECT * FROM custom_alerts WHERE pairSymbol = :pairSymbol AND isActive = 1")
    fun observeActiveAlertsByPair(pairSymbol: String): Flow<List<CustomAlertEntity>>

    @Update
    suspend fun updateAlert(alert: CustomAlertEntity)

    @Query("DELETE FROM custom_alerts WHERE id = :id")
    suspend fun deleteAlert(id: String)
}

@Dao
interface HistoricalCandleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCandles(candles: List<HistoricalCandleEntity>)

    /** WHY: DESC + LIMIT returns the newest [limit] rows; the repository
     * reverses them into ascending order (cheaper than a full sort). */
    @Query("SELECT * FROM historical_candles WHERE symbol = :symbol AND timeframe = :timeframe ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getLatestCandles(symbol: String, timeframe: String, limit: Int): List<HistoricalCandleEntity>

    /**
     * WHY: Bounds table growth — without pruning, every cache miss appends
     * 500 rows forever and every future query scans more history.
     * Returns the number of rows deleted.
     */
    @Query("DELETE FROM historical_candles WHERE symbol = :symbol AND timeframe = :timeframe AND timestamp < :before")
    suspend fun deleteOlderThan(symbol: String, timeframe: String, before: Instant): Int

    /**
     * Empties the whole candle cache.
     *
     * WHY this exists: the cache has a one-hour TTL, and rows written before the user
     * added an API key are simulated. Without an explicit invalidation, pasting a key
     * in Settings would appear to do nothing for up to an hour - which reads as "the
     * key does not work" and costs a support round trip. The same call covers
     * switching provider and switching data source.
     *
     * Returns the number of rows deleted.
     */
    @Query("DELETE FROM historical_candles")
    suspend fun deleteAllCandles(): Int
}
