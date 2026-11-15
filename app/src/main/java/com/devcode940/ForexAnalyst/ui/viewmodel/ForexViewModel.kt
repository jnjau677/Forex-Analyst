package com.devcode940.ForexAnalyst.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.devcode940.ForexAnalyst.data.db.ForexDatabase
import com.devcode940.ForexAnalyst.data.model.AlertId
import com.devcode940.ForexAnalyst.data.model.AppError
import com.devcode940.ForexAnalyst.data.model.CandleStick
import com.devcode940.ForexAnalyst.data.model.CustomAlert
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
import com.devcode940.ForexAnalyst.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private val settings: StateFlow<AppSettings>
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
     * WHY: The old ViewModel only logged failures, so the user saw an empty
     * chart with no explanation. This carries a safe, actionable message
     * (never an internal exception) that the UI can render.
     */
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // WHY: Cancels the in-flight load when the user switches pair/timeframe
    // rapidly — otherwise a slow stale response overwrites fresh data.
    private var loadJob: Job? = null

    init {
        observe(_selectedPair.flatMapLatest { repository.observeSignals(it) }, _signals)
        observe(_selectedPair.flatMapLatest { repository.observeAlerts(it) }, _alerts)
        observe(repository.observeWatchlist(), _watchlist)
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
        loadCandles()
    }

    /** Selects [timeframe] and reloads the chart (no-op if unchanged). */
    fun selectTimeframe(timeframe: Timeframe) {
        if (_selectedTimeframe.value == timeframe) return
        _selectedTimeframe.value = timeframe
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
                        generateSignals(list, pair, timeframe)
                    }
                    is Result.Failure -> {
                        // WHY: Surface the classified message; keep stale data
                        // off the screen so the chart cannot lie about prices.
                        _candles.value = emptyList()
                        _indicators.value = IndicatorState()
                        _errorMessage.value = result.error.userMessage
                    }
                }
            } finally {
                // WHY: finally guarantees the spinner stops even if the
                // coroutine is cancelled mid-load (pair switched quickly).
                _isLoading.value = false
            }
        }
    }

    private fun calculateIndicators(list: List<CandleStick>): IndicatorState {
        if (list.isEmpty()) return IndicatorState()
        val prefs = settings.value
        val closes = list.map { it.close }
        // WHY: Honour the user's configured periods — the old code hardcoded
        // 20/50/200/14 here while the Settings screen pretended to change them.
        return IndicatorState(
            ema20 = TechnicalAnalysisEngine.calculateEMA(closes, prefs.emaFast),
            ema50 = TechnicalAnalysisEngine.calculateEMA(closes, prefs.emaMedium),
            ema200 = TechnicalAnalysisEngine.calculateEMA(closes, prefs.emaSlow),
            rsi = TechnicalAnalysisEngine.calculateRSI(closes, prefs.rsiPeriod),
            macd = TechnicalAnalysisEngine.calculateMACD(
                closes,
                prefs.macdFast,
                prefs.macdSlow,
                prefs.macdSignal
            )
        )
    }

    private suspend fun generateSignals(list: List<CandleStick>, pair: PairSymbol, timeframe: Timeframe) {
        // WHY: Build settings from persisted user config, falling back to
        // defaults if a legacy value is out of range (IndicatorSettings
        // validates in init). Signal generation is best-effort: a failure
        // must not blank the chart the user is already looking at.
        val prefs = settings.value
        val indicatorSettings = runCatching {
            IndicatorSettings(
                emaPeriods = listOf(prefs.emaFast, prefs.emaMedium, prefs.emaSlow).sorted(),
                rsiPeriod = prefs.rsiPeriod,
                macdFastPeriod = prefs.macdFast,
                macdSlowPeriod = prefs.macdSlow,
                macdSignalPeriod = prefs.macdSignal,
                atrPeriod = prefs.atrPeriod
            )
        }.getOrElse {
            Log.w(TAG, "Persisted indicator settings invalid, using defaults", it)
            IndicatorSettings.default()
        }

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
                    val database = ForexDatabase.getInstance(application)
                    return ForexViewModel(
                        repository = ForexRepository(database),
                        settings = SettingsRepository.getInstance(application).settings
                    ) as T
                }
            }
        }
    }
}
