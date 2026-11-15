package com.devcode940.ForexAnalyst.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.devcode940.ForexAnalyst.data.settings.AppSettings
import com.devcode940.ForexAnalyst.data.settings.SettingsRepository
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

    fun saveIndicators(
        rsiPeriod: Int,
        emaFast: Int,
        emaMedium: Int,
        emaSlow: Int,
        macdFast: Int,
        macdSlow: Int,
        macdSignal: Int,
        atrPeriod: Int,
        showEma: Boolean,
        showRsi: Boolean
    ) {
        if (macdFast >= macdSlow) {
            _message.value = "MACD fast period must be smaller than slow period."
            return
        }
        if (emaFast >= emaMedium || emaMedium >= emaSlow) {
            _message.value = "EMA periods must increase: fast < medium < slow."
            return
        }

        repository.update { current ->
            current.copy(
                rsiPeriod = rsiPeriod.coerceIn(2, 100),
                emaFast = emaFast.coerceIn(2, 400),
                emaMedium = emaMedium.coerceIn(2, 400),
                emaSlow = emaSlow.coerceIn(2, 400),
                macdFast = macdFast.coerceIn(2, 100),
                macdSlow = macdSlow.coerceIn(3, 200),
                macdSignal = macdSignal.coerceIn(2, 100),
                atrPeriod = atrPeriod.coerceIn(2, 100),
                showEma = showEma,
                showRsi = showRsi
            )
        }
        _message.value = "Indicator settings saved."
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

    fun setThemeMode(mode: String) {
        repository.update { it.copy(themeMode = mode) }
    }
}