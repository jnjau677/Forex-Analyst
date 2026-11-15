package com.devcode940.ForexAnalyst.data.repository

import com.devcode940.ForexAnalyst.data.model.AlertId
import com.devcode940.ForexAnalyst.data.model.CandleStick
import com.devcode940.ForexAnalyst.data.model.ChartDrawing
import com.devcode940.ForexAnalyst.data.model.CustomAlert
import com.devcode940.ForexAnalyst.data.model.DrawingId
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

    /** Next result returned by the drawing write operations. */
    var drawingResult: Result<Unit> = Result.Success(Unit)

    /** Recorded arguments, in call order — lets tests assert on queries. */
    val fetchedPairs = mutableListOf<PairSymbol>()
    val fetchedTimeframes = mutableListOf<Timeframe>()
    val fetchedLimits = mutableListOf<Int>()
    val savedSignals = mutableListOf<TradeSignal>()
    val watchlistAdditions = mutableListOf<PairSymbol>()
    val watchlistRemovals = mutableListOf<PairSymbol>()
    val savedAlerts = mutableListOf<CustomAlert>()
    val deletedAlertIds = mutableListOf<AlertId>()
    val savedDrawings = mutableListOf<ChartDrawing>()
    val deletedDrawingIds = mutableListOf<DrawingId>()
    val clearedDrawingCharts = mutableListOf<Pair<PairSymbol, Timeframe>>()
    val replacedDrawingCharts = mutableListOf<Pair<PairSymbol, Timeframe>>()

    private val signalsByPair = mutableMapOf<PairSymbol, MutableStateFlow<List<TradeSignal>>>()
    private val alertsByPair = mutableMapOf<PairSymbol, MutableStateFlow<List<CustomAlert>>>()
    private val drawingsByChart = mutableMapOf<Pair<PairSymbol, Timeframe>, MutableStateFlow<List<ChartDrawing>>>()
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

    /** Pushes [drawings] as the next emission for one chart. */
    fun emitDrawings(pairSymbol: PairSymbol, timeframe: Timeframe, drawings: List<ChartDrawing>) {
        drawingFlow(pairSymbol to timeframe).value = drawings
    }

    private fun drawingFlow(
        chart: Pair<PairSymbol, Timeframe>
    ): MutableStateFlow<List<ChartDrawing>> =
        drawingsByChart.getOrPut(chart) { MutableStateFlow(emptyList()) }

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

    override fun observeDrawings(pairSymbol: PairSymbol, timeframe: Timeframe): Flow<List<ChartDrawing>> =
        drawingFlow(pairSymbol to timeframe)

    // WHY the drawing writes below also update the observed flow: Room re-emits the
    // query result after every insert or delete, and the ViewModel reconciles that
    // emission against its in-memory store. A fake that records the call but stays
    // silent would let every reconciliation bug pass — including the one where our own
    // write comes back and is adopted as a second undo step, which makes Undo look dead.

    override suspend fun saveDrawing(drawing: ChartDrawing): Result<Unit> {
        savedDrawings += drawing
        upsertDrawing(drawing)
        return drawingResult
    }

    override suspend fun saveDrawings(drawings: List<ChartDrawing>): Result<Unit> {
        savedDrawings += drawings
        drawings.forEach { upsertDrawing(it) }
        return drawingResult
    }

    override suspend fun deleteDrawing(drawingId: DrawingId): Result<Unit> {
        deletedDrawingIds += drawingId
        drawingsByChart.values.forEach { flow -> flow.value = flow.value.filterNot { it.id == drawingId } }
        return drawingResult
    }

    override suspend fun deleteDrawingsForChart(pairSymbol: PairSymbol, timeframe: Timeframe): Result<Unit> {
        clearedDrawingCharts += pairSymbol to timeframe
        drawingFlow(pairSymbol to timeframe).value = emptyList()
        return drawingResult
    }

    /**
     * REPLACE-by-id insert, mirroring `OnConflictStrategy.REPLACE`.
     *
     * WHY the object moves to the end when its id already exists: SQLite implements
     * REPLACE as delete-then-insert, which gives the row a new rowid, and the DAO
     * orders by `createdAt, rowid`. Editing a drawing therefore re-orders it to the top
     * of the paint stack in the real database too — a fake that kept the old position
     * would hide that behaviour.
     */
    private fun upsertDrawing(drawing: ChartDrawing) {
        val flow = drawingFlow(drawing.pairSymbol to drawing.timeframe)
        flow.value = flow.value.filterNot { it.id == drawing.id } + drawing
    }

    override suspend fun replaceDrawingsForChart(
        pairSymbol: PairSymbol,
        timeframe: Timeframe,
        drawings: List<ChartDrawing>
    ): Result<Unit> {
        replacedDrawingCharts += pairSymbol to timeframe
        drawingFlow(pairSymbol to timeframe).value = drawings
        return drawingResult
    }
}
