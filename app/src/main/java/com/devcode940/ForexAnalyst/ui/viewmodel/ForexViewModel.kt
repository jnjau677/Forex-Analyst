package com.devcode940.ForexAnalyst.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.devcode940.ForexAnalyst.data.db.ForexDatabase
import com.devcode940.ForexAnalyst.data.model.AlertId
import com.devcode940.ForexAnalyst.data.model.CandleStick
import com.devcode940.ForexAnalyst.data.model.CustomAlert
import com.devcode940.ForexAnalyst.data.model.IndicatorSettings
import com.devcode940.ForexAnalyst.data.model.IndicatorState
import com.devcode940.ForexAnalyst.data.model.PairSymbol
import com.devcode940.ForexAnalyst.data.model.Timeframe
import com.devcode940.ForexAnalyst.data.model.TradeSignal
import com.devcode940.ForexAnalyst.data.repository.ForexRepository
import com.devcode940.ForexAnalyst.data.repository.TechnicalAnalysisEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

class ForexViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ForexRepository(ForexDatabase.getInstance(application))

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

    init {
        observeWatchlist()
        observeSignals()
        observeAlerts()
        loadCandles()
    }

    private fun observeWatchlist() {
        viewModelScope.launch {
            repository.observeWatchlist().collect { pairs ->
                _watchlist.value = pairs
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeSignals() {
        viewModelScope.launch {
            _selectedPair
                .flatMapLatest { pair -> repository.observeSignals(pair) }
                .collect { list -> _signals.value = list }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeAlerts() {
        viewModelScope.launch {
            _selectedPair
                .flatMapLatest { pair -> repository.observeAlerts(pair) }
                .collect { list -> _alerts.value = list }
        }
    }

    fun selectPair(pairSymbol: PairSymbol) {
        if (_selectedPair.value == pairSymbol) return
        _selectedPair.value = pairSymbol
        loadCandles()
    }

    fun selectTimeframe(timeframe: Timeframe) {
        if (_selectedTimeframe.value == timeframe) return
        _selectedTimeframe.value = timeframe
        loadCandles()
    }

    private fun loadCandles() {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            runCatching {
                repository.fetchHistoricalCandles(_selectedPair.value)
            }.onSuccess { list ->
                _candles.value = list
                calculateIndicators(list)
                generateSignals(list)
            }.onFailure { error ->
                Log.e(TAG, "Failed to load candles", error)
                _candles.value = emptyList()
            }
            _isLoading.value = false
        }
    }

    private fun calculateIndicators(list: List<CandleStick>) {
        if (list.isEmpty()) {
            _indicators.value = IndicatorState()
            return
        }
        val closes = list.map { it.close }
        _indicators.value = IndicatorState(
            ema20 = TechnicalAnalysisEngine.calculateEMA(closes, 20),
            ema50 = TechnicalAnalysisEngine.calculateEMA(closes, 50),
            ema200 = TechnicalAnalysisEngine.calculateEMA(closes, 200),
            rsi = TechnicalAnalysisEngine.calculateRSI(closes, 14),
            macd = TechnicalAnalysisEngine.calculateMACD(closes)
        )
    }

    private suspend fun generateSignals(list: List<CandleStick>) {
        runCatching {
            val generated = TechnicalAnalysisEngine.generateSignals(
                candles = list,
                timeframe = _selectedTimeframe.value,
                settings = IndicatorSettings.default()
            )
            if (generated.isNotEmpty()) {
                repository.saveSignals(generated)
            }
        }.onFailure { error ->
            Log.w(TAG, "Signal generation failed: ${error.message}")
        }
    }

    fun addToWatchlist(pairSymbol: PairSymbol) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.addToWatchlist(pairSymbol) }
                .onFailure { Log.e(TAG, "Add to watchlist failed", it) }
        }
    }

    fun removeFromWatchlist(pairSymbol: PairSymbol) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.removeFromWatchlist(pairSymbol) }
                .onFailure { Log.e(TAG, "Remove from watchlist failed", it) }
        }
    }

    fun saveAlert(alert: CustomAlert) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.saveAlert(alert) }
                .onFailure { Log.e(TAG, "Save alert failed", it) }
        }
    }

    fun deleteAlert(alertId: AlertId) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.deleteAlert(alertId) }
                .onFailure { Log.e(TAG, "Delete alert failed", it) }
        }
    }

    companion object {
        private const val TAG = "ForexViewModel"
    }
}