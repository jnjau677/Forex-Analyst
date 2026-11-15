package com.devcode940.ForexAnalyst.data.settings

import android.app.Application
import android.content.Context
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * WHY: The repository centralizes persistence and state updates so all
 * consumers observe a single atomic snapshot of [AppSettings].
 */
class SettingsRepository private constructor(
    private val context: Context
) {

    private val gson = Gson()
    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value)
        _settings.value = next
        persist(next)
    }

    fun resetIndicators() {
        update { current ->
            current.copy(
                rsiPeriod = AppSettings().rsiPeriod,
                emaFast = AppSettings().emaFast,
                emaMedium = AppSettings().emaMedium,
                emaSlow = AppSettings().emaSlow,
                macdFast = AppSettings().macdFast,
                macdSlow = AppSettings().macdSlow,
                macdSignal = AppSettings().macdSignal,
                atrPeriod = AppSettings().atrPeriod,
                showEma = AppSettings().showEma,
                showRsi = AppSettings().showRsi
            )
        }
    }

    fun clearApiKeys() {
        update { current ->
            current.copy(
                fcsApiKey = "",
                alphaVantageApiKey = "",
                openRouterApiKey = ""
            )
        }
    }

    private fun load(): AppSettings {
        return try {
            prefs.getString(KEY_SETTINGS, null)
                ?.let { gson.fromJson(it, AppSettings::class.java) }
                ?: AppSettings()
        } catch (t: Throwable) {
            AppSettings()
        }
    }

    private fun persist(value: AppSettings) {
        scope.launch {
            prefs.edit().putString(KEY_SETTINGS, gson.toJson(value)).apply()
        }
    }

    companion object {
        private const val PREFS_NAME = "app_settings"
        private const val KEY_SETTINGS = "settings_json"

        @Volatile
        private var instance: SettingsRepository? = null

        fun getInstance(context: Context): SettingsRepository {
            return instance ?: synchronized(this) {
                instance ?: SettingsRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
