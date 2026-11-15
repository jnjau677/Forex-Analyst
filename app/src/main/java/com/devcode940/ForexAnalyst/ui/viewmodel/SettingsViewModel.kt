package com.devcode940.ForexAnalyst.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.devcode940.ForexAnalyst.data.settings.AppSettings
import com.devcode940.ForexAnalyst.data.settings.IndicatorFormInput
import com.devcode940.ForexAnalyst.data.settings.IndicatorFormResult
import com.devcode940.ForexAnalyst.data.settings.IndicatorFormValidator
import com.devcode940.ForexAnalyst.data.settings.SettingsRepository
import com.devcode940.ForexAnalyst.data.settings.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
        _message.value = "API keys saved."
    }

    fun clearApiKeys() {
        repository.clearApiKeys()
        _message.value = "All API keys removed."
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
