package com.devcode940.ForexAnalyst.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.devcode940.ForexAnalyst.ForexApplication
import com.devcode940.ForexAnalyst.data.model.MarketDataPreference
import com.devcode940.ForexAnalyst.data.settings.AppSettings
import com.devcode940.ForexAnalyst.data.settings.IndicatorFormInput
import com.devcode940.ForexAnalyst.data.settings.IndicatorFormResult
import com.devcode940.ForexAnalyst.data.settings.IndicatorFormValidator
import com.devcode940.ForexAnalyst.data.settings.SettingsRepository
import com.devcode940.ForexAnalyst.data.settings.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = SettingsRepository.getInstance(application)

    val settings: StateFlow<AppSettings> = repository.settings

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun consumeMessage() {
        _message.value = null
    }

    /**
     * Validates and persists the indicator form.
     *
     * WHY: The old version coerced values silently and validated in the wrong
     * order (coerce AFTER validate), so an out-of-range entry could be stored
     * while the user was told everything was fine. Validation is delegated to
     * [IndicatorFormValidator] — a pure object that is unit-tested on the JVM.
     */
    fun saveIndicators(input: IndicatorFormInput) {
        when (val result = IndicatorFormValidator.validate(input)) {
            is IndicatorFormResult.Valid -> {
                val values = result.values
                repository.update { current ->
                    current.copy(
                        rsiPeriod = values.rsiPeriod,
                        emaFast = values.emaFast,
                        emaMedium = values.emaMedium,
                        emaSlow = values.emaSlow,
                        macdFast = values.macdFast,
                        macdSlow = values.macdSlow,
                        macdSignal = values.macdSignal,
                        atrPeriod = values.atrPeriod,
                        showEma = values.showEma,
                        showRsi = values.showRsi
                    )
                }
                _message.value = "Indicator settings saved."
            }
            // WHY: Show the validator's actionable message (field + accepted
            // range + what was typed) instead of a generic "Invalid input".
            is IndicatorFormResult.Invalid -> _message.value = result.userMessage
        }
    }

    fun resetIndicators() {
        repository.resetIndicators()
        _message.value = "Indicators reset to defaults."
    }

    fun saveApiKeys(fcs: String, alphaVantage: String, openRouter: String, model: String) {
        repository.update { current ->
            current.copy(
                fcsApiKey = fcs.trim(),
                alphaVantageApiKey = alphaVantage.trim(),
                openRouterApiKey = openRouter.trim(),
                aiModel = model.trim().ifBlank { AppSettings().aiModel }
            )
        }
        // WHY drop the candle cache here: cached rows may have been simulated, and the
        // cache TTL is an hour. Without this, pasting a working key looks like it did
        // nothing for up to an hour - the single most confusing failure mode this screen
        // can produce.
        invalidateCandleCache()
        // WHY also clear auth suspensions: a key rejected earlier suspended its feed for
        // an hour, and the user has just fixed the reason.
        liveSource()?.resetSuspensions()
        _message.value = "API keys saved."
    }

    fun clearApiKeys() {
        repository.clearApiKeys()
        invalidateCandleCache()
        _message.value = "All API keys removed."
    }

    /**
     * Chooses the market-data feed.
     *
     * WHY the cache is dropped: rows cached from the previous feed would keep being
     * served for up to an hour, so the setting would appear not to work.
     */
    fun setMarketDataSource(preference: MarketDataPreference) {
        repository.update { it.copy(marketDataSource = preference) }
        invalidateCandleCache()
        liveSource()?.resetSuspensions()
        _message.value = "Market data source set to ${preference.displayName}."
    }

    /** Pauses or resumes the background alert monitor. */
    fun setAlertMonitoringEnabled(enabled: Boolean) {
        repository.update { it.copy(alertMonitoringEnabled = enabled) }
        _message.value = if (enabled) "Alert monitoring on." else "Alert monitoring paused."
    }

    /**
     * Remaining quota per configured feed, for the API-keys screen.
     *
     * WHY surfaced: "prices stopped updating" has exactly one non-obvious cause on a
     * free tier, and without this the user cannot check it themselves.
     */
    fun quotaSummary(): List<String> = liveSource()?.quotaSummary() ?: emptyList()

    /**
     * Clears the request counters this device has recorded and lifts feed suspensions.
     *
     * WHY a user-facing escape hatch for an internal counter: the counter is this
     * device's *estimate* of a quota the provider tracks server-side, and when the
     * estimate drifts (a key upgraded to a paid plan, a window that has already rolled
     * over, counters written by an older build) it is the only thing between the user
     * and their own feed. "Prices stopped updating" with no way to clear the block is a
     * support ticket; the honest alternative is a button whose worst case is one extra
     * request that the provider answers with 429.
     */
    fun resetQuota() {
        val source = liveSource()
        if (source == null) {
            _message.value = "Live feeds are not active, so there is nothing to reset."
            return
        }
        source.resetQuota()
        // WHY drop the cache as well: with an hour-long TTL the next chart load would be
        // served from SQLite and the reset would look like it did nothing - the same
        // trap as pasting a working API key.
        invalidateCandleCache()
        _message.value = "Request counters reset. The next chart load will try the feeds again."
    }

    private fun liveSource() = ForexApplication.graphOf(getApplication())?.marketData?.liveSource

    private fun invalidateCandleCache() {
        val marketRepository = ForexApplication.graphOf(getApplication())?.repository ?: return
        // WHY the ViewModel scope: the write is a Room transaction and must not run on
        // the main thread, and it must be cancelled if the screen goes away.
        viewModelScope.launch { marketRepository.invalidateCandleCache() }
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        repository.update { it.copy(notificationsEnabled = enabled) }
    }

    fun setSignalNotifications(enabled: Boolean) {
        repository.update { it.copy(signalNotifications = enabled) }
    }

    fun setAlertNotifications(enabled: Boolean) {
        repository.update { it.copy(alertNotifications = enabled) }
    }

    fun setVibration(enabled: Boolean) {
        repository.update { it.copy(vibrationEnabled = enabled) }
    }

    fun setSound(enabled: Boolean) {
        repository.update { it.copy(soundEnabled = enabled) }
    }

    /** WHY: ThemeMode enum (not String) — invalid states are unrepresentable. */
    fun setThemeMode(mode: ThemeMode) {
        repository.update { it.copy(themeMode = mode) }
    }
}
