package com.devcode940.ForexAnalyst.data.repository

import com.devcode940.ForexAnalyst.data.db.CustomAlertEntity
import com.devcode940.ForexAnalyst.data.db.ForexDatabase
import com.devcode940.ForexAnalyst.data.db.HistoricalCandleEntity
import com.devcode940.ForexAnalyst.data.db.SavedSignalEntity
import com.devcode940.ForexAnalyst.data.db.WatchlistItemEntity
import com.devcode940.ForexAnalyst.data.model.AlertConditionType
import com.devcode940.ForexAnalyst.data.model.AlertId
import com.devcode940.ForexAnalyst.data.model.CandleStick
import com.devcode940.ForexAnalyst.data.model.CustomAlert
import com.devcode940.ForexAnalyst.data.model.PairSymbol
import com.devcode940.ForexAnalyst.data.model.SignalId
import com.devcode940.ForexAnalyst.data.model.SignalType
import com.devcode940.ForexAnalyst.data.model.Timeframe
import com.devcode940.ForexAnalyst.data.model.TradeSignal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant

/**
 * Single source of truth for market data, watchlist, signals and alerts.
 *
 * WHY: Entities use plain String/Long for Room+KAPT compatibility; the domain
 * layer uses value classes. All conversion is funnelled through the mappers
 * at the bottom of this file so no other layer touches raw DB types.
 */
class ForexRepository(private val database: ForexDatabase) {

    private val signalDao = database.signalDao()
    private val watchlistDao = database.watchlistDao()
    private val alertDao = database.alertDao()
    private val candleDao = database.historicalCandleDao()

    // ---------- Signals ----------

    fun observeSignals(pairSymbol: PairSymbol): Flow<List<TradeSignal>> {
        return signalDao.observeSignalsByPair(pairSymbol.value)
            .map { list -> list.map { entity -> entity.toDomain() } }
    }

    fun observeBookmarkedSignals(): Flow<List<TradeSignal>> {
        return signalDao.observeBookmarkedSignals()
            .map { list -> list.map { entity -> entity.toDomain() } }
    }

    suspend fun saveSignals(signals: List<TradeSignal>) {
        signalDao.insertSignals(signals.map { signal -> signal.toEntity() })
    }

    suspend fun isSignalDuplicate(fingerprint: String): Boolean {
        return signalDao.countByFingerprint(fingerprint) > 0
    }

    // ---------- Watchlist ----------

    fun observeWatchlist(): Flow<List<PairSymbol>> {
        return watchlistDao.observeAllItems()
            .map { list -> list.map { item -> PairSymbol(item.symbol) } }
    }

    suspend fun getActiveWatchlistPairs(): List<PairSymbol> {
        return watchlistDao.getAllSymbols().map { value -> PairSymbol(value) }
    }

    suspend fun addToWatchlist(pairSymbol: PairSymbol) {
        watchlistDao.insertItem(WatchlistItemEntity(pairSymbol.value, Instant.now()))
    }

    suspend fun removeFromWatchlist(pairSymbol: PairSymbol) {
        watchlistDao.deleteItem(pairSymbol.value)
    }

    // ---------- Candles ----------

    suspend fun fetchHistoricalCandles(pairSymbol: PairSymbol): List<CandleStick> {
        val timeframe = "H1"
        val cached = candleDao.getLatestCandles(pairSymbol.value, timeframe, 500)

        if (cached.isNotEmpty()) {
            val latest = candleDao.getLatestTimestamp(pairSymbol.value, timeframe)
            // WHY: Serve from cache when fresh (< 1h old) to avoid regenerating data.
            if (latest != null && Instant.now().minusSeconds(3600).isBefore(latest)) {
                return cached.map { entity -> entity.toDomain() }.sortedBy { it.time }
            }
        }

        val generated = PriceGenerator.generateCandles(
            count = 500,
            basePrice = basePriceFor(pairSymbol.value)
        )
        candleDao.insertCandles(
            generated.map { candle -> candle.toEntity(pairSymbol.value, timeframe) }
        )
        return generated
    }

    // ---------- Alerts ----------

    suspend fun getActiveAlerts(): List<CustomAlert> {
        return alertDao.getActiveAlerts().map { entity -> entity.toDomain() }
    }

    fun observeAlerts(pairSymbol: PairSymbol): Flow<List<CustomAlert>> {
        return alertDao.observeActiveAlertsByPair(pairSymbol.value)
            .map { list -> list.map { entity -> entity.toDomain() } }
    }

    suspend fun saveAlert(alert: CustomAlert) {
        alertDao.insertAlert(alert.toEntity())
    }

    suspend fun deleteAlert(alertId: AlertId) {
        alertDao.deleteAlert(alertId.value)
    }

    private fun basePriceFor(symbol: String): Double {
        return when {
            symbol.contains("JPY") -> 150.0
            symbol.contains("XAU") -> 2000.0
            symbol.contains("XAG") -> 24.0
            symbol.contains("BTC") -> 65000.0
            symbol.contains("ETH") -> 3200.0
            else -> 1.1000
        }
    }
}

// ---------------------------------------------------------------------------
// Mappers: Entity (String) <-> Domain (value classes)
// ---------------------------------------------------------------------------

private fun SavedSignalEntity.toDomain(): TradeSignal {
    return TradeSignal(
        id = SignalId(id),
        pairSymbol = PairSymbol(pairSymbol),
        signalType = SignalType.valueOf(signalType),
        entryPrice = entryPrice,
        stopLoss = stopLoss,
        takeProfit1 = takeProfit1,
        takeProfit2 = takeProfit2,
        riskRewardRatio = riskRewardRatio,
        confidenceScore = confidenceScore,
        timeframe = Timeframe(timeframe),
        summaryRationale = summaryRationale,
        timestamp = timestamp,
        isBookmarked = isBookmarked
    )
}

private fun TradeSignal.toEntity(): SavedSignalEntity {
    return SavedSignalEntity(
        id = id.value,
        pairSymbol = pairSymbol.value,
        signalType = signalType.name,
        entryPrice = entryPrice,
        stopLoss = stopLoss,
        takeProfit1 = takeProfit1,
        takeProfit2 = takeProfit2,
        riskRewardRatio = riskRewardRatio,
        confidenceScore = confidenceScore,
        timeframe = timeframe.value,
        summaryRationale = summaryRationale,
        timestamp = timestamp,
        isBookmarked = isBookmarked,
        fingerprint = buildFingerprint()
    )
}

/** WHY: Stable hash of the setup's identity prevents duplicate rows on rescan. */
private fun TradeSignal.buildFingerprint(): String {
    val raw = "${pairSymbol.value}|${timeframe.value}|${signalType.name}|" +
        "${"%.5f".format(entryPrice)}|${"%.5f".format(stopLoss)}|${"%.5f".format(takeProfit1)}"
    return raw.hashCode().toString()
}

private fun CustomAlertEntity.toDomain(): CustomAlert {
    return CustomAlert(
        id = AlertId(id),
        pairSymbol = PairSymbol(pairSymbol),
        conditionType = AlertConditionType.valueOf(conditionType),
        targetValue = targetValue,
        isTriggered = isTriggered,
        isActive = isActive,
        note = note,
        createdAt = createdAt
    )
}

private fun CustomAlert.toEntity(): CustomAlertEntity {
    return CustomAlertEntity(
        id = id.value,
        pairSymbol = pairSymbol.value,
        conditionType = conditionType.name,
        targetValue = targetValue,
        isTriggered = isTriggered,
        isActive = isActive,
        note = note,
        createdAt = createdAt
    )
}

private fun HistoricalCandleEntity.toDomain(): CandleStick {
    return CandleStick(
        time = timestamp.toEpochMilli(),
        open = open,
        high = high,
        low = low,
        close = close,
        volume = volume
    )
}

private fun CandleStick.toEntity(symbol: String, timeframe: String): HistoricalCandleEntity {
    return HistoricalCandleEntity(
        symbol = symbol,
        timeframe = timeframe,
        timestamp = Instant.ofEpochMilli(time),
        open = open,
        high = high,
        low = low,
        close = close,
        volume = volume
    )
}