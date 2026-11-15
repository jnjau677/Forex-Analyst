package com.devcode940.ForexAnalyst.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.devcode940.ForexAnalyst.ForexApplication
import com.devcode940.ForexAnalyst.data.chart.ChartViewport
import com.devcode940.ForexAnalyst.data.chart.DraftDrawing
import com.devcode940.ForexAnalyst.data.chart.DrawingFactory
import com.devcode940.ForexAnalyst.data.chart.DrawingStore
import com.devcode940.ForexAnalyst.data.chart.HistoryAvailability
import com.devcode940.ForexAnalyst.data.db.ForexDatabase
import com.devcode940.ForexAnalyst.data.model.AlertId
import com.devcode940.ForexAnalyst.data.model.AppError
import com.devcode940.ForexAnalyst.data.model.CandleStick
import com.devcode940.ForexAnalyst.data.model.ChartConfig
import com.devcode940.ForexAnalyst.data.model.ChartDrawing
import com.devcode940.ForexAnalyst.data.model.ChartStyle
import com.devcode940.ForexAnalyst.data.model.ChartToolMode
import com.devcode940.ForexAnalyst.data.model.CustomAlert
import com.devcode940.ForexAnalyst.data.model.DataOrigin
import com.devcode940.ForexAnalyst.data.model.DrawingId
import com.devcode940.ForexAnalyst.data.model.IndicatorSettings
import com.devcode940.ForexAnalyst.data.model.IndicatorState
import com.devcode940.ForexAnalyst.data.model.PairSymbol
import com.devcode940.ForexAnalyst.data.model.Result
import com.devcode940.ForexAnalyst.data.model.Timeframe
import com.devcode940.ForexAnalyst.data.model.TradeSignal
import com.devcode940.ForexAnalyst.data.repository.ForexRepository
import com.devcode940.ForexAnalyst.data.repository.MarketDataRepository
import com.devcode940.ForexAnalyst.data.repository.TechnicalAnalysisEngine
import com.devcode940.ForexAnalyst.data.settings.AppSettings
import com.devcode940.ForexAnalyst.data.settings.ChartSettingsWriter
import com.devcode940.ForexAnalyst.data.settings.SettingsRepository
import com.devcode940.ForexAnalyst.ui.chart.ChartEvent
import com.devcode940.ForexAnalyst.ui.chart.ChartUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Clock

/**
 * Presentation state and orchestration for the chart terminal.
 *
 * WHY: Depends on the [MarketDataRepository] abstraction and a read-only
 * [StateFlow] of settings (Interface Segregation — this screen never writes
 * settings), all injected through [Factory]. The old ViewModel `new`ed a
 * concrete repository from the Application singleton, which made it
 * impossible to unit-test without an emulator.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ForexViewModel(
    private val repository: MarketDataRepository,
    private val settings: StateFlow<AppSettings>,
    /**
     * Write access to settings, narrowed to one method.
     *
     * WHY not [SettingsRepository]: the chart toolbar persists display choices only.
     * Handing it the repository would also hand it the API keys and the theme, and
     * every test would need the whole settings stack instead of a lambda.
     */
    private val settingsWriter: ChartSettingsWriter,
    /** WHY injected: drawing timestamps become deterministic in tests. */
    private val clock: Clock = Clock.systemUTC()
) : ViewModel() {

    /**
     * Read-only view of the user's preferences.
     *
     * WHY: screens need display toggles (EMA/RSI overlays) without owning the
     * settings repository — the ViewModel already depends on it, so it exposes
     * the narrowest possible slice instead of letting each screen reach into
     * SettingsRepository.getInstance(context) on its own.
     */
    val appSettings: StateFlow<AppSettings> = settings

    private val _selectedPair = MutableStateFlow(PairSymbol("EURUSD"))
    val selectedPair: StateFlow<PairSymbol> = _selectedPair.asStateFlow()

    private val _selectedTimeframe = MutableStateFlow(Timeframe("H1"))
    val selectedTimeframe: StateFlow<Timeframe> = _selectedTimeframe.asStateFlow()

    private val _candles = MutableStateFlow<List<CandleStick>>(emptyList())
    val candles: StateFlow<List<CandleStick>> = _candles.asStateFlow()

    private val _indicators = MutableStateFlow(IndicatorState())
    val indicators: StateFlow<IndicatorState> = _indicators.asStateFlow()

    private val _watchlist = MutableStateFlow<List<PairSymbol>>(emptyList())
    val watchlist: StateFlow<List<PairSymbol>> = _watchlist.asStateFlow()

    private val _signals = MutableStateFlow<List<TradeSignal>>(emptyList())
    val signals: StateFlow<List<TradeSignal>> = _signals.asStateFlow()

    private val _alerts = MutableStateFlow<List<CustomAlert>>(emptyList())
    val alerts: StateFlow<List<CustomAlert>> = _alerts.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /**
     * Where the prices on screen came from.
     *
     * WHY forwarded rather than recomputed: only the repository knows whether the last
     * fetch hit a live feed, was served from its cache, or fell back to the simulator.
     * Deriving it here from "did the call succeed" would label a simulated fallback as
     * live, which is the one mistake this badge exists to prevent.
     */
    val dataOrigin: StateFlow<DataOrigin> = repository.dataOrigin

    /**
     * WHY: The old ViewModel only logged failures, so the user saw an empty
     * chart with no explanation. This carries a safe, actionable message
     * (never an internal exception) that the UI can render.
     */
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /**
     * Everything the chart canvas draws, as one immutable snapshot.
     *
     * WHY a single state object: see [ChartUiState]. The canvas receives it whole, so
     * the candles, the indicators, the config and the drawings can never be observed
     * mid-update — which is what made the old chart flicker when a toggle landed
     * between two emissions.
     */
    private val _chartState = MutableStateFlow(
        ChartUiState.empty(PairSymbol(DEFAULT_PAIR), Timeframe(DEFAULT_TIMEFRAME))
    )
    val chartState: StateFlow<ChartUiState> = _chartState.asStateFlow()

    /**
     * Drawings for the active chart, oldest first.
     *
     * WHY the store and not the database: a committed object must appear in the same
     * frame the finger lifts. The repository write follows, and the store is
     * reconciled only if the persisted set genuinely differs.
     */
    private val drawingStore = DrawingStore()

    /** Undo/redo availability, so the toolbar can enable or disable its buttons. */
    val drawingHistory: StateFlow<HistoryAvailability> = drawingStore.historyState

    // WHY: Cancels the in-flight load when the user switches pair/timeframe
    // rapidly — otherwise a slow stale response overwrites fresh data.
    private var loadJob: Job? = null

    init {
        observe(_selectedPair.flatMapLatest { repository.observeSignals(it) }, _signals)
        observe(_selectedPair.flatMapLatest { repository.observeAlerts(it) }, _alerts)
        observe(repository.observeWatchlist(), _watchlist)
        observeChartConfig()
        observeDrawingStore()
        observePersistedDrawings()
        loadCandles()
    }

    /**
     * WHY: One collector helper — the old code repeated the same
     * launch/collect block three times, each a place to forget cancellation.
     * viewModelScope cancels all of them when the ViewModel is cleared.
     */
    private fun <T> observe(source: Flow<List<T>>, target: MutableStateFlow<List<T>>) {
        viewModelScope.launch {
            source.collect { target.value = it }
        }
    }

    /** Selects [pairSymbol] and reloads the chart (no-op if unchanged). */
    fun selectPair(pairSymbol: PairSymbol) {
        if (_selectedPair.value == pairSymbol) return
        _selectedPair.value = pairSymbol
        // WHY publish the chart identity before the load: [chartState] is the single
        // snapshot the canvas, the toolbar and the drawing layer read, and the drawing
        // layer files a new object under `chartState.pair` / `chartState.timeframe`.
        // Waiting for the candles would let a drawing committed during the load be
        // stored against the chart the user just left — invisible until they switch
        // back and find an object they never placed there.
        _chartState.update { it.copy(pair = pairSymbol) }
        loadCandles()
    }

    /** Selects [timeframe] and reloads the chart (no-op if unchanged). */
    fun selectTimeframe(timeframe: Timeframe) {
        if (_selectedTimeframe.value == timeframe) return
        _selectedTimeframe.value = timeframe
        // WHY: see [selectPair] — the snapshot must name the selected chart the moment
        // the selection changes, not when its candles happen to arrive.
        _chartState.update { it.copy(timeframe = timeframe) }
        loadCandles()
    }

    /** Clears the error banner after the user has seen it. */
    fun consumeError() {
        _errorMessage.value = null
    }

    private fun loadCandles() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _isLoading.value = true
            // WHY mirror the flag into the chart snapshot: the screen renders its empty
            // state from `chartState.isLoading`, and a snapshot that never hears about
            // the load would report "No data for EURUSD" while the candles are still on
            // their way — an error message for a perfectly normal moment.
            _chartState.update { it.copy(isLoading = true) }
            _errorMessage.value = null
            try {
                val pair = _selectedPair.value
                val timeframe = _selectedTimeframe.value
                // WHY: Room suspend calls are main-safe (Room hops to its own
                // executor), so no Dispatchers.IO wrapper is needed here.
                when (val result = repository.fetchHistoricalCandles(pair, timeframe)) {
                    is Result.Success -> {
                        val list = result.data
                        // WHY: CPU-bound indicator math belongs on Default.
                        // The old code ran EMA/RSI/MACD on Dispatchers.IO,
                        // which is sized for blocking I/O and starves the
                        // thread pool during heavy computation.
                        val computed = withContext(Dispatchers.Default) { calculateIndicators(list) }
                        _candles.value = list
                        _indicators.value = computed
                        publishChartData(pair, timeframe, list, computed)
                        generateSignals(list, pair, timeframe)
                    }
                    is Result.Failure -> {
                        // WHY: Surface the classified message; keep stale data
                        // off the screen so the chart cannot lie about prices.
                        _candles.value = emptyList()
                        _indicators.value = IndicatorState()
                        publishChartData(pair, timeframe, emptyList(), IndicatorState())
                        _errorMessage.value = result.error.userMessage
                    }
                }
            } finally {
                // WHY: finally guarantees the spinner stops even if the
                // coroutine is cancelled mid-load (pair switched quickly).
                _isLoading.value = false
                _chartState.update { it.copy(isLoading = false) }
            }
        }
    }

    /**
     * Computes every overlay the chart can render.
     *
     * WHY all of them at once instead of per-toggle: the list is already in memory
     * and each pass is O(n); recomputing on every toggle would stall the UI thread
     * for the same work. Toggles only decide what is *drawn*.
     *
     * WHY the user's periods: the old code hardcoded 20/50/200/14 here while the
     * Settings screen pretended to change them.
     */
    private fun calculateIndicators(list: List<CandleStick>): IndicatorState {
        if (list.isEmpty()) return IndicatorState()
        val prefs = settings.value
        val closes = list.map { it.close }
        val indicatorSettings = indicatorSettingsOrDefault(prefs)
        return IndicatorState(
            emaFast = TechnicalAnalysisEngine.calculateEMA(closes, prefs.emaFast),
            emaMedium = TechnicalAnalysisEngine.calculateEMA(closes, prefs.emaMedium),
            emaSlow = TechnicalAnalysisEngine.calculateEMA(closes, prefs.emaSlow),
            sma = TechnicalAnalysisEngine.calculateSMA(closes, prefs.emaFast),
            rsi = TechnicalAnalysisEngine.calculateRSI(closes, prefs.rsiPeriod),
            macd = TechnicalAnalysisEngine.calculateMACD(
                closes,
                prefs.macdFast,
                prefs.macdSlow,
                prefs.macdSignal
            ),
            bollinger = TechnicalAnalysisEngine.calculateBollingerBands(
                closes,
                indicatorSettings.bollingerPeriod,
                indicatorSettings.bollingerStdDev
            ),
            // WHY the chart time zone: VWAP anchors on the session boundary, so it
            // must use the same zone the axis is labelled in — otherwise the reset
            // happens at a different moment than the day line the user sees.
            // WHY read it from `prefs` and not from the mirrored chart state: this
            // function also runs during the initial load, before the settings collector
            // has published the config into `_chartState`, so the mirror can still hold
            // the default zone while the user's stored choice says otherwise.
            vwap = TechnicalAnalysisEngine.calculateVWAP(
                list,
                prefs.chartTimeZone.resolve()
            ),
            stochastic = TechnicalAnalysisEngine.calculateStochastic(
                list,
                indicatorSettings.stochasticKPeriod,
                indicatorSettings.stochasticDPeriod
            )
        )
    }

    /**
     * Builds [IndicatorSettings] from persisted preferences.
     *
     * WHY a shared helper: both the indicator pass and the signal pass need the same
     * validated settings, and the fallback-to-defaults rule must not be written twice
     * (two copies would drift, and only one would log).
     */
    private fun indicatorSettingsOrDefault(prefs: AppSettings): IndicatorSettings = runCatching {
        IndicatorSettings(
            emaPeriods = listOf(prefs.emaFast, prefs.emaMedium, prefs.emaSlow).sorted(),
            rsiPeriod = prefs.rsiPeriod,
            macdFastPeriod = prefs.macdFast,
            macdSlowPeriod = prefs.macdSlow,
            macdSignalPeriod = prefs.macdSignal,
            atrPeriod = prefs.atrPeriod,
            bollingerPeriod = prefs.bollingerPeriod,
            bollingerStdDev = prefs.bollingerStdDev,
            stochasticKPeriod = prefs.stochasticKPeriod,
            stochasticDPeriod = prefs.stochasticDPeriod
        )
    }.getOrElse { error ->
        Log.w(TAG, "Persisted indicator settings invalid, using defaults", error)
        IndicatorSettings.default()
    }

    private suspend fun generateSignals(list: List<CandleStick>, pair: PairSymbol, timeframe: Timeframe) {
        // WHY: Build settings from persisted user config, falling back to
        // defaults if a legacy value is out of range (IndicatorSettings
        // validates in init). Signal generation is best-effort: a failure
        // must not blank the chart the user is already looking at.
        val indicatorSettings = indicatorSettingsOrDefault(settings.value)

        val generated = runCatching {
            TechnicalAnalysisEngine.generateSignals(
                candles = list,
                pairSymbol = pair,
                timeframe = timeframe,
                settings = indicatorSettings
            )
        }.getOrElse { error ->
            Log.w(TAG, "Signal generation failed", error)
            emptyList()
        }
        if (generated.isEmpty()) return

        when (val saved = repository.saveSignals(generated)) {
            is Result.Success -> Unit // WHY: idempotent batch save; nothing to report.
            is Result.Failure -> Log.w(TAG, "Saving signals failed: ${saved.error.code}")
        }
    }

    // WHY: No Dispatchers.IO here — Room suspend functions are main-safe and
    // viewModelScope already runs on Main, so state stays on the main thread.

    /** Adds [pairSymbol] to the watchlist; reports a safe message on failure. */
    fun addToWatchlist(pairSymbol: PairSymbol) {
        write("addToWatchlist") { repository.addToWatchlist(pairSymbol) }
    }

    /** Removes [pairSymbol] from the watchlist. */
    fun removeFromWatchlist(pairSymbol: PairSymbol) {
        write("removeFromWatchlist") { repository.removeFromWatchlist(pairSymbol) }
    }

    /** Creates or updates [alert]. */
    fun saveAlert(alert: CustomAlert) {
        write("saveAlert") { repository.saveAlert(alert) }
    }

    /** Deletes the alert identified by [alertId]. */
    fun deleteAlert(alertId: AlertId) {
        write("deleteAlert") { repository.deleteAlert(alertId) }
    }

    // ---------- Chart interaction ----------

    /**
     * Handles every interaction the chart surface can produce.
     *
     * WHY one entry point: the canvas has a single event sink, so a new tool or toggle
     * becomes a compile error here until it is handled. Ten optional lambdas would let
     * one be forgotten silently — and the forgotten one is always the one in use.
     */
    fun onChartEvent(event: ChartEvent) {
        when (event) {
            is ChartEvent.ToolSelected -> selectTool(event.tool)
            is ChartEvent.OverlayToggled -> updateChartConfig { it.toggled(event.overlay) }
            is ChartEvent.StyleSelected -> updateChartConfig { config -> config.copy(style = event.style) }
            is ChartEvent.TimeZoneSelected -> updateChartConfig { config -> config.copy(timeZone = event.timeZone) }
            is ChartEvent.MagnetSelected -> updateChartConfig { config -> config.copy(magnet = event.magnet) }
            is ChartEvent.ColorSelected ->
                _chartState.update { it.copy(pendingColorArgb = event.colorArgb) }

            is ChartEvent.LineStyleSelected ->
                _chartState.update { it.copy(pendingStyle = event.style) }

            is ChartEvent.DraftStarted -> startDraft(event)
            is ChartEvent.DraftMoved -> moveDraft(event)
            is ChartEvent.DraftFinished -> finishDraft(event)
            ChartEvent.DraftCancelled -> _chartState.update { it.copy(draft = null) }

            is ChartEvent.DrawingSelected ->
                _chartState.update { it.copy(selectedDrawingId = event.id) }

            is ChartEvent.DrawingDeleted -> deleteDrawing(event.id)

            ChartEvent.Undo -> undoDrawingChange()
            ChartEvent.Redo -> redoDrawingChange()
            ChartEvent.ClearDrawings -> clearDrawings()

            is ChartEvent.CrosshairChanged ->
                _chartState.update { it.copy(crosshair = event.crosshair) }

            is ChartEvent.ViewportChanged ->
                _chartState.update { it.copy(viewport = event.viewport) }

            // WHY reset the crosshair too: a double-tap means "start over", and
            // leaving the crosshair on the old bar makes the reset look partial.
            ChartEvent.ViewportReset -> _chartState.update {
                it.copy(viewport = ChartViewport.DEFAULT, crosshair = null)
            }
        }
    }

    /**
     * Arms a tool.
     *
     * WHY arming clears the draft and the selection: a half-finished object from the
     * previous tool would otherwise be committed by the next release, and a selected
     * object invites an edit the user no longer means to make.
     */
    private fun selectTool(tool: ChartToolMode) {
        _chartState.update { current ->
            current.copy(
                activeTool = tool,
                draft = null,
                selectedDrawingId = null,
                // WHY keep the crosshair only in reading modes: a drawing tool that
                // also moves the crosshair produces two answers to one gesture.
                crosshair = if (tool.isDrawingTool) null else current.crosshair
            )
        }
    }

    /** Persists a chart-display change through the narrowed settings writer. */
    private fun updateChartConfig(transform: (ChartConfig) -> ChartConfig) {
        settingsWriter.update { prefs -> prefs.withChartConfig(transform(prefs.toChartConfig())) }
    }

    private fun startDraft(event: ChartEvent.DraftStarted) {
        val tool = _chartState.value.activeTool
        if (!tool.isDrawingTool) return
        _chartState.update { it.copy(draft = DraftDrawing(tool = tool, start = event.anchor, current = event.anchor)) }
    }

    private fun moveDraft(event: ChartEvent.DraftMoved) {
        _chartState.update { current ->
            val draft = current.draft ?: return@update current
            current.copy(draft = draft.copy(current = event.anchor))
        }
    }

    /**
     * Commits the gesture, or drops it when it cannot produce a valid object.
     *
     * WHY the tool disarms after a commit: editors behave this way for a reason — the
     * next tap is almost always meant to read the crosshair, and an armed tool would
     * silently drop another object instead.
     */
    private fun finishDraft(event: ChartEvent.DraftFinished) {
        val state = _chartState.value
        val draft = state.draft ?: return
        val drawing = DrawingFactory.create(
            tool = draft.tool,
            start = draft.start,
            end = event.anchor,
            pairSymbol = state.pair,
            timeframe = state.timeframe,
            style = state.pendingStyle,
            colorArgb = state.pendingColorArgb,
            createdAt = clock.instant()
        )
        _chartState.update { it.copy(draft = null) }
        if (drawing == null) {
            // WHY tell the user: a gesture that produces nothing looks like a frozen
            // app. One short line explains that the drag had no extent.
            _errorMessage.value = DRAWING_TOO_SMALL_MESSAGE
            return
        }
        drawingStore.commit(drawing)
        _chartState.update { it.copy(activeTool = ChartToolMode.NONE, selectedDrawingId = drawing.id) }
        write("saveDrawing") { repository.saveDrawing(drawing) }
    }

    private fun deleteDrawing(id: DrawingId) {
        val removed = drawingStore.delete(id)
        // WHY clear the selection unconditionally: if the id is not in the store the
        // selection is stale anyway, and a selection pointing at nothing keeps the
        // toolbar's delete button enabled forever.
        _chartState.update { current ->
            current.copy(selectedDrawingId = if (current.selectedDrawingId == id) null else current.selectedDrawingId)
        }
        // WHY only write when the store actually changed: deleting an object that is not
        // there is a no-op, and a no-op should not reach the database — a storage failure
        // on a retry would show the user an error for an action that changed nothing.
        if (removed) write("deleteDrawing") { repository.deleteDrawing(id) }
    }

    /** Deletes the selected drawing; used by the toolbar's delete button. */
    fun deleteSelectedDrawing() {
        _chartState.value.selectedDrawingId?.let { deleteDrawing(it) }
    }

    /**
     * Steps the drawing history back and persists the restored snapshot.
     *
     * WHY replace rather than delete-one: undo can restore an object that was
     * deleted, which no single-row write can express.
     */
    private fun undoDrawingChange() {
        if (!drawingStore.undo()) return
        persistDrawingSnapshot()
    }

    private fun redoDrawingChange() {
        if (!drawingStore.redo()) return
        persistDrawingSnapshot()
    }

    private fun clearDrawings() {
        val state = _chartState.value
        drawingStore.clearFor(state.pair, state.timeframe)
        _chartState.update { it.copy(selectedDrawingId = null) }
        write("deleteDrawingsForChart") {
            repository.deleteDrawingsForChart(state.pair, state.timeframe)
        }
    }

    /** Writes the store's current snapshot for the active chart in one transaction. */
    private fun persistDrawingSnapshot() {
        val state = _chartState.value
        val snapshot = drawingStore.currentFor(state.pair, state.timeframe)
        write("replaceDrawingsForChart") {
            repository.replaceDrawingsForChart(state.pair, state.timeframe, snapshot)
        }
    }

    /**
     * Mirrors the persisted display flags into [chartState].
     *
     * WHY recompute on a zone change: VWAP anchors on the session boundary, so it must
     * be recomputed in the same zone the axis is labelled in — otherwise the reset
     * happens at a moment the user cannot see.
     *
     * WHY recompute the display series on a style change: Heikin-Ashi is a transform of
     * the candles, not a drawing option, so switching to or from it changes what the
     * canvas is handed.
     */
    private fun observeChartConfig() {
        viewModelScope.launch {
            settings.collect { prefs ->
                val config = prefs.toChartConfig()
                val previous = _chartState.value.config
                _chartState.update { current ->
                    current.copy(
                        config = config,
                        candles = displayCandles(_candles.value, config.style)
                    )
                }
                if (previous.timeZone != config.timeZone && _candles.value.isNotEmpty()) {
                    recomputeIndicators()
                }
            }
        }
    }

    /**
     * Publishes the store's drawings for the active chart into [chartState].
     *
     * WHY the emission carries its own scope: `flatMapLatest` cancels the previous
     * subscription, but an emission already in flight can still be collected after the
     * user has switched charts. Publishing it would paint the objects of the chart they
     * left onto the chart they are looking at — and a tap would then select an object
     * that belongs to another pair and timeframe.
     */
    private fun observeDrawingStore() {
        viewModelScope.launch {
            scopes()
                .flatMapLatest { scope ->
                    drawingStore.drawingsFor(scope.pair, scope.timeframe).map { scope to it }
                }
                .collect { (scope, drawings) ->
                    if (!scope.isActive()) return@collect
                    _chartState.update { it.copy(drawings = drawings) }
                }
        }
    }

    /**
     * Reconciles the persisted drawings into the store.
     *
     * WHY compare before replacing: this flow also carries our own writes. Seeding the
     * store from them would push a duplicate history entry, and Undo would then appear
     * to do nothing.
     *
     * WHY `recordHistory = false`: what arrives here is the database's copy, not an
     * action the user just took. Recording it as an undo step would enable Undo on a
     * freshly opened chart, and pressing it would persist the empty baseline over the
     * user's stored drawings. Adoption must be the baseline, not a step.
     */
    private fun observePersistedDrawings() {
        viewModelScope.launch {
            scopes()
                .flatMapLatest { scope ->
                    repository.observeDrawings(scope.pair, scope.timeframe).map { scope to it }
                }
                .collect { (scope, persisted) ->
                    if (!scope.isActive()) return@collect
                    val known = drawingStore.currentFor(scope.pair, scope.timeframe)
                    if (sameDrawingSet(known, persisted)) return@collect
                    drawingStore.replaceAllFor(scope.pair, scope.timeframe, persisted, recordHistory = false)
                }
        }
    }

    /** The chart the user has selected, as a stream of immutable scopes. */
    private fun scopes(): Flow<ChartScope> =
        combine(_selectedPair, _selectedTimeframe) { pair, timeframe -> ChartScope(pair, timeframe) }

    /**
     * One chart identity.
     *
     * WHY a type and not a `Pair`: the drawing observers destructure a pair *and* carry
     * a payload, so `Pair<PairSymbol, Timeframe>` next to `Pair<ChartDrawing, ...>`
     * becomes unreadable — and a nested pair destructuring is where a swapped component
     * hides.
     */
    private data class ChartScope(val pair: PairSymbol, val timeframe: Timeframe)

    /**
     * True while [this] scope is still the chart on screen.
     *
     * WHY an extension on the ViewModel rather than a method of [ChartScope]: the scope
     * is a nested (static) class, so it cannot see `_selectedPair`; keeping the
     * comparison here also keeps the single source of truth about "which chart is
     * active" in one place.
     */
    private fun ChartScope.isActive(): Boolean =
        pair == _selectedPair.value && timeframe == _selectedTimeframe.value

    /**
     * True when both sets hold the same objects.
     *
     * WHY compare by id: the persisted copy has been through a database round-trip, so
     * list equality would also demand identical ordering and float formatting. Identity
     * is what "the same set of objects" means here.
     */
    private fun sameDrawingSet(a: List<ChartDrawing>, b: List<ChartDrawing>): Boolean {
        if (a.size != b.size) return false
        val ids = a.map { it.id }.toSet()
        return b.all { it.id in ids }
    }

    /**
     * The series the canvas draws: Heikin-Ashi smoothed when that style is selected,
     * otherwise the raw candles.
     *
     * WHY the transform lives here and not in the renderer: indicators must be computed
     * from *real* prices, so the smoothed series is a display concern only. Doing it
     * upstream keeps the renderer a dumb painter and makes the transform testable.
     */
    private fun displayCandles(raw: List<CandleStick>, style: ChartStyle): List<CandleStick> =
        if (style == ChartStyle.HEIKIN_ASHI && raw.isNotEmpty()) {
            TechnicalAnalysisEngine.heikinAshi(raw)
        } else {
            raw
        }

    /** Recomputes indicators from the candles already in memory. */
    private fun recomputeIndicators() {
        val raw = _candles.value
        if (raw.isEmpty()) return
        viewModelScope.launch {
            val computed = withContext(Dispatchers.Default) { calculateIndicators(raw) }
            _indicators.value = computed
            publishChartData(_selectedPair.value, _selectedTimeframe.value, raw, computed)
        }
    }

    /** Writes candles + indicators into the chart snapshot. */
    private fun publishChartData(
        pair: PairSymbol,
        timeframe: Timeframe,
        raw: List<CandleStick>,
        computed: IndicatorState
    ) {
        val style = _chartState.value.config.style
        _chartState.update { current ->
            current.copy(
                pair = pair,
                timeframe = timeframe,
                candles = displayCandles(raw, style),
                indicators = computed
            )
        }
    }

    /**
     * Runs a write operation and reports only a safe message on failure.
     *
     * WHY: The old code wrapped every write in `runCatching { }` + Log.e, so
     * a failed alert save looked like success to the user.
     */
    private fun write(operation: String, block: suspend () -> Result<Unit>) {
        viewModelScope.launch {
            when (val result = runCatching { block() }.getOrElse { error ->
                Log.e(TAG, "$operation threw", error)
                Result.Failure(AppError.Unknown.of(error))
            }) {
                is Result.Success -> _errorMessage.value = null
                is Result.Failure -> _errorMessage.value = result.error.userMessage
            }
        }
    }

    companion object {
        private const val TAG = "ForexViewModel"

        /** Chart opened on a cold start; the watchlist's first pair. */
        private const val DEFAULT_PAIR = "EURUSD"
        private const val DEFAULT_TIMEFRAME = "H1"

        /**
         * Shown when a drag was too small to define an object.
         *
         * WHY a message and not silence: a gesture that produces nothing otherwise
         * looks like a frozen chart.
         */
        private const val DRAWING_TOO_SMALL_MESSAGE =
            "Drag between two points to place that object"

        /**
         * WHY: A hand-rolled factory keeps constructor injection (and thus
         * testability) without adding a DI framework to a single-module app.
         */
        fun Factory(application: Application): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(ForexViewModel::class.java)) {
                        "Unknown ViewModel class: ${modelClass.name}"
                    }
                    val settingsRepository = SettingsRepository.getInstance(application)
                    // WHY the process-wide repository when there is one: it shares the
                    // alert monitor's quota budget, its candle cache provenance and its
                    // credential set. A per-ViewModel repository would keep a second
                    // budget counter against the same monthly allowance, and the chart
                    // and the alerts would disagree about where prices came from.
                    // WHY the fallback exists: previews and instrumented tests can run
                    // with a plain Application, and crashing them with a
                    // ClassCastException tells nobody anything.
                    val repository = ForexApplication.graphOf(application)?.repository
                        ?: ForexRepository(ForexDatabase.getInstance(application))
                    return ForexViewModel(
                        repository = repository,
                        settings = settingsRepository.settings,
                        // WHY the repository itself satisfies the writer: it is the
                        // only implementation, and the interface exists to narrow what
                        // the ViewModel can do with it, not to add a wrapper.
                        settingsWriter = settingsRepository
                    ) as T
                }
            }
        }
    }
}
