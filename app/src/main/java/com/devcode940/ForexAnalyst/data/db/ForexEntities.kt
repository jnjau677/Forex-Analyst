package com.devcode940.ForexAnalyst.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

// WHY: Room + KAPT cannot handle Kotlin value classes directly. 
// We use String here and convert to value classes in the Repository.

@Entity(tableName = "watchlist_items", indices = [Index(value = ["addedAt"])])
data class WatchlistItemEntity(
    @PrimaryKey val symbol: String,
    val addedAt: Instant
)

@Entity(tableName = "saved_signals", indices = [
    Index(value = ["pairSymbol"]), Index(value = ["timestamp"]),
    Index(value = ["pairSymbol", "timestamp"]), Index(value = ["isBookmarked"]),
    Index(value = ["fingerprint"], unique = true)
])
data class SavedSignalEntity(
    @PrimaryKey val id: String,
    val pairSymbol: String,
    val signalType: String,
    val entryPrice: Double,
    val stopLoss: Double,
    val takeProfit1: Double,
    val takeProfit2: Double,
    val riskRewardRatio: Double,
    val confidenceScore: Double,
    val timeframe: String,
    val summaryRationale: String,
    val timestamp: Instant,
    val isBookmarked: Boolean,
    val fingerprint: String
)

@Entity(tableName = "custom_alerts", indices = [Index(value = ["pairSymbol", "isActive"]), Index(value = ["isTriggered"])])
data class CustomAlertEntity(
    @PrimaryKey val id: String,
    val pairSymbol: String,
    val conditionType: String,
    val targetValue: Double,
    val isTriggered: Boolean,
    val isActive: Boolean,
    val note: String,
    val createdAt: Instant
)

@Entity(tableName = "historical_candles", primaryKeys = ["symbol", "timeframe", "timestamp"],
    indices = [Index(value = ["symbol", "timeframe", "timestamp"])])
data class HistoricalCandleEntity(
    val symbol: String,
    val timeframe: String,
    val timestamp: Instant,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long
)