package com.devcode940.ForexAnalyst.data.repository

import com.devcode940.ForexAnalyst.data.model.AlertId
import com.devcode940.ForexAnalyst.data.model.CandleStick
import com.devcode940.ForexAnalyst.data.model.CustomAlert
import com.devcode940.ForexAnalyst.data.model.PairSymbol
import com.devcode940.ForexAnalyst.data.model.Result
import com.devcode940.ForexAnalyst.data.model.Timeframe
import com.devcode940.ForexAnalyst.data.model.TradeSignal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * In-memory [MarketDataRepository] double for JVM unit tests.
 *
 * WHY: Because the ViewModel depends on the interface (not on Room), the whole
 * presentation flow — including failure handling — can be tested without an
 * emulator, a database, or Robolectric. Results are injectable so each test
 * decides whether the "backend" succeeds or fails.
 */
class FakeMarketDataRepository : MarketDataRepository {

    /** Next result returned by [fetchHistoricalCandles]. */
    var candleResult: Result<List<CandleStick>> = Result.Success(emptyList())

    /** Next result returned by [saveSignals]. */
    var saveSignalsResult: Result<Unit> = Result.Success(Unit)

    /** Next result returned by the watchlist write operations. */
    var watchlistResult: Result<Unit> = Result.Success(Unit)

    /** Next result returned by the alert write operations. */
    var alertResult: Result<Unit> = Result.Success(Unit)

    /** Recorded arguments, in call order — lets tests assert on queries. */
    val fetchedPairs = mutableListOf<PairSymbol>()
    val fetchedTimeframes = mutableListOf<Timeframe>()
    val fetchedLimits = mutableListOf<Int>()
    val savedSignals = mutableListOf<TradeSignal>()
    val watchlistAdditions = mutableListOf<PairSymbol>()
    val watchlistRemovals = mutableListOf<PairSymbol>()
    val savedAlerts = mutableListOf<CustomAlert>()
    val deletedAlertIds = mutableListOf<AlertId>()

    private val signalsByPair = mutableMapOf<PairSymbol, MutableStateFlow<List<TradeSignal>>>()
    private val alertsByPair = mutableMapOf<PairSymbol, MutableStateFlow<List<CustomAlert>>>()
    private val watchlistFlow = MutableStateFlow<List<PairSymbol>>(emptyList())
    private val bookmarkedFlow = MutableStateFlow<List<TradeSignal>>(emptyList())

    /** Pushes [signals] as the next emission for [pair]. */
    fun emitSignals(pair: PairSymbol, signals: List<TradeSignal>) {
        signalFlow(pair).value = signals
    }

    /** Pushes [alerts] as the next emission for [pair]. */
    fun emitAlerts(pair: PairSymbol, alerts: List<CustomAlert>) {
        alertFlow(pair).value = alerts
    }

    /** Pushes [pairs] as the next watchlist emission. */
    fun emitWatchlist(pairs: List<PairSymbol>) {
        watchlistFlow.value = pairs
    }

    private fun signalFlow(pair: PairSymbol): MutableStateFlow<List<TradeSignal>> =
        signalsByPair.getOrPut(pair) { MutableStateFlow(emptyList()) }

    private fun alertFlow(pair: PairSymbol): MutableStateFlow<List<CustomAlert>> =
        alertsByPair.getOrPut(pair) { MutableStateFlow(emptyList()) }

    override fun observeSignals(pairSymbol: PairSymbol): Flow<List<TradeSignal>> = signalFlow(pairSymbol)

    override fun observeBookmarkedSignals(): Flow<List<TradeSignal>> = bookmarkedFlow

    override fun observeWatchlist(): Flow<List<PairSymbol>> = watchlistFlow

    override fun observeAlerts(pairSymbol: PairSymbol): Flow<List<CustomAlert>> = alertFlow(pairSymbol)

    override suspend fun fetchHistoricalCandles(
        pairSymbol: PairSymbol,
        timeframe: Timeframe,
        limit: Int
    ): Result<List<CandleStick>> {
        fetchedPairs += pairSymbol
        fetchedTimeframes += timeframe
        fetchedLimits += limit
        return candleResult
    }

    override suspend fun saveSignals(signals: List<TradeSignal>): Result<Unit> {
        savedSignals += signals
        return saveSignalsResult
    }

    override suspend fun getActiveWatchlistPairs(): Result<List<PairSymbol>> =
        Result.Success(watchlistFlow.value)

    override suspend fun addToWatchlist(pairSymbol: PairSymbol): Result<Unit> {
        watchlistAdditions += pairSymbol
        return watchlistResult
    }

    override suspend fun removeFromWatchlist(pairSymbol: PairSymbol): Result<Unit> {
        watchlistRemovals += pairSymbol
        return watchlistResult
    }

    override suspend fun getActiveAlerts(): Result<List<CustomAlert>> = Result.Success(emptyList())

    override suspend fun saveAlert(alert: CustomAlert): Result<Unit> {
        savedAlerts += alert
        return alertResult
    }

    override suspend fun deleteAlert(alertId: AlertId): Result<Unit> {
        deletedAlertIds += alertId
        return alertResult
    }
}
