package com.devcode940.ForexAnalyst.data.settings

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Centralized, atomic persistence for [AppSettings].
 *
 * WHY EncryptedSharedPreferences: settings include third-party API keys. Plain
 * SharedPreferences are world-readable to any process with root and are copied
 * verbatim by auto-backup — see `res/xml/backup_rules.xml`, which excludes them.
 *
 * WHY [MutableStateFlow.update]: it is an atomic compare-and-set. The previous
 * implementation did read-modify-write on `.value`, so two concurrent writers
 * (a toggle flipped while a key is being saved) silently lost one change.
 */
class SettingsRepository private constructor(context: Context) {

    private val gson = Gson()
    private val prefs: SharedPreferences = createPrefs(context)

    /**
     * WHY: application-scoped writer, intentionally never cancelled — it lives
     * exactly as long as the process. A persist in flight must not be cancelled
     * because the user navigated away from the Settings screen.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Current settings snapshot.
     *
     * WHY loaded synchronously: an async load left a window where every reader
     * (theme, indicator periods, API keys) saw factory defaults on a cold start
     * — the chart would compute indicators with the wrong periods and the theme
     * would visibly flip. This is one small string read, once per process.
     */
    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    init {
        _settings.value = load()
    }

    /**
     * Applies [transform] to the current settings and persists the result.
     *
     * WHY: callers express a delta, never a whole-object write, so concurrent
     * updates to different fields cannot clobber each other.
     */
    fun update(transform: (AppSettings) -> AppSettings) {
        _settings.update(transform)
        persist(_settings.value)
    }

    /** Restores every indicator field to its factory default. */
    fun resetIndicators() {
        val defaults = AppSettings()
        update { current ->
            current.copy(
                rsiPeriod = defaults.rsiPeriod,
                emaFast = defaults.emaFast,
                emaMedium = defaults.emaMedium,
                emaSlow = defaults.emaSlow,
                macdFast = defaults.macdFast,
                macdSlow = defaults.macdSlow,
                macdSignal = defaults.macdSignal,
                atrPeriod = defaults.atrPeriod,
                showEma = defaults.showEma,
                showRsi = defaults.showRsi
            )
        }
    }

    /** Removes all third-party credentials from the device. */
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
                ?.let { json -> gson.fromJson(json, AppSettings::class.java) }
                ?: AppSettings()
        } catch (e: Exception) {
            // WHY: Exception, not Throwable — swallowing an OutOfMemoryError
            // or StackOverflowError hides a real fault behind "defaults".
            // Corrupt JSON (e.g. an older schema) just resets to defaults.
            Log.w(TAG, "Failed to load settings, using defaults", e)
            AppSettings()
        }
    }

    private fun persist(value: AppSettings) {
        scope.launch {
            // WHY: commit() (not apply()) — settings writes are rare, explicit
            // user actions, and apply() can drop the write if the process is
            // killed first. Running on IO keeps the caller off the disk.
            try {
                prefs.edit().putString(KEY_SETTINGS, gson.toJson(value)).commit()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist settings", e)
            }
        }
    }

    companion object {
        private const val TAG = "SettingsRepository"

        /** Encrypted store (new). */
        private const val PREFS_NAME = "app_settings_secure"

        /** Plain-text store used before encryption was introduced. */
        private const val LEGACY_PREFS_NAME = "app_settings"

        private const val KEY_SETTINGS = "settings_json"

        @Volatile
        private var instance: SettingsRepository? = null

        /**
         * Process-wide singleton.
         *
         * WHY double-checked locking with @Volatile: getInstance is called from
         * the UI thread and from application startup; the volatile field makes
         * the publication safe without synchronizing every read.
         */
        fun getInstance(context: Context): SettingsRepository {
            return instance ?: synchronized(this) {
                instance ?: SettingsRepository(context.applicationContext).also { instance = it }
            }
        }

        /**
         * Opens the encrypted store, migrating any pre-encryption settings once.
         *
         * WHY a separate file name: EncryptedSharedPreferences and a plain
         * `getSharedPreferences` on the SAME file keep two independent
         * in-memory caches of one file, and the last writer wins — a real
         * corruption path. The encrypted store therefore uses its own file and
         * the legacy file is read once, copied, then cleared.
         *
         * WHY the try/catch: the Android Keystore is unusable on some devices
         * (corrupt key blob, locked-down work profiles). Degrading to plain
         * prefs with a loud warning beats crashing on every launch — the keys
         * are still excluded from backups.
         */
        private fun createPrefs(context: Context): SharedPreferences {
            val encrypted = try {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    context,
                    PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (e: Exception) {
                Log.e(TAG, "EncryptedSharedPreferences unavailable; falling back to plain storage", e)
                return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            }

            if (encrypted.getString(KEY_SETTINGS, null) == null) {
                val legacy = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
                val legacyJson = legacy.getString(KEY_SETTINGS, null)
                if (legacyJson != null) {
                    encrypted.edit().putString(KEY_SETTINGS, legacyJson).commit()
                    // WHY: clear() removes the plaintext API keys from disk —
                    // leaving them behind defeats the whole migration.
                    legacy.edit().clear().commit()
                    Log.i(TAG, "Migrated settings from plaintext store to encrypted store")
                }
            }
            return encrypted
        }
    }
}
