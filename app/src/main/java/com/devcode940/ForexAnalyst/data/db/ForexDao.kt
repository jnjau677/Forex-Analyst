package com.devcode940.ForexAnalyst.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import java.time.Instant

@Dao interface SignalDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertSignal(signal: SavedSignalEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertSignals(signals: List<SavedSignalEntity>)
    @Query("SELECT * FROM saved_signals WHERE pairSymbol = :pairSymbol ORDER BY timestamp DESC")
    fun observeSignalsByPair(pairSymbol: String): Flow<List<SavedSignalEntity>>
    @Query("SELECT * FROM saved_signals WHERE isBookmarked = 1 ORDER BY timestamp DESC")
    fun observeBookmarkedSignals(): Flow<List<SavedSignalEntity>>
    @Query("SELECT COUNT(*) FROM saved_signals WHERE fingerprint = :fingerprint")
    suspend fun countByFingerprint(fingerprint: String): Int
    @Update suspend fun updateSignal(signal: SavedSignalEntity)
    @Query("DELETE FROM saved_signals WHERE id = :id") suspend fun deleteSignal(id: String)
}

@Dao interface WatchlistDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertItem(item: WatchlistItemEntity)
    @Query("SELECT * FROM watchlist_items ORDER BY addedAt DESC") fun observeAllItems(): Flow<List<WatchlistItemEntity>>
    @Query("SELECT symbol FROM watchlist_items") suspend fun getAllSymbols(): List<String>
    @Query("DELETE FROM watchlist_items WHERE symbol = :symbol") suspend fun deleteItem(symbol: String)
}

@Dao interface AlertDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAlert(alert: CustomAlertEntity)
    @Query("SELECT * FROM custom_alerts WHERE isActive = 1 AND isTriggered = 0") suspend fun getActiveAlerts(): List<CustomAlertEntity>
    @Query("SELECT * FROM custom_alerts WHERE pairSymbol = :pairSymbol AND isActive = 1")
    fun observeActiveAlertsByPair(pairSymbol: String): Flow<List<CustomAlertEntity>>
    @Update suspend fun updateAlert(alert: CustomAlertEntity)
    @Query("DELETE FROM custom_alerts WHERE id = :id") suspend fun deleteAlert(id: String)
}

@Dao interface HistoricalCandleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertCandles(candles: List<HistoricalCandleEntity>)
    @Query("SELECT * FROM historical_candles WHERE symbol = :symbol AND timeframe = :timeframe ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getLatestCandles(symbol: String, timeframe: String, limit: Int): List<HistoricalCandleEntity>
    @Query("SELECT MAX(timestamp) FROM historical_candles WHERE symbol = :symbol AND timeframe = :timeframe")
    suspend fun getLatestTimestamp(symbol: String, timeframe: String): Instant?
}