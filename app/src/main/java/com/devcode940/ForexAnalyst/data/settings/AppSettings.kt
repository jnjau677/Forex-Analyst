package com.devcode940.ForexAnalyst.data.settings

/**
 * All user-configurable application settings.
 *
 * WHY: A single immutable snapshot makes state changes atomic and prevents
 * partially-applied configuration (e.g. new RSI period with old MACD period).
 */
data class AppSettings(
    // Indicators
    val rsiPeriod: Int = 14,
    val emaFast: Int = 20,
    val emaMedium: Int = 50,
    val emaSlow: Int = 200,
    val macdFast: Int = 12,
    val macdSlow: Int = 26,
    val macdSignal: Int = 9,
    val atrPeriod: Int = 14,
    val showEma: Boolean = true,
    val showRsi: Boolean = true,

    // API credentials
    val fcsApiKey: String = "",
    val alphaVantageApiKey: String = "",
    val openRouterApiKey: String = "",
    val aiModel: String = "google/gemini-2.0-flash-exp:free",

    // Notifications
    val notificationsEnabled: Boolean = true,
    val signalNotifications: Boolean = true,
    val alertNotifications: Boolean = true,
    val vibrationEnabled: Boolean = true,
    val soundEnabled: Boolean = true,

    // Appearance
    val themeMode: String = "system" // "system" | "light" | "dark"
) {
    /** WHY: Surface configuration completeness to the UI without leaking keys. */
    val hasMarketDataKey: Boolean get() = fcsApiKey.isNotBlank() || alphaVantageApiKey.isNotBlank()
    val hasAiKey: Boolean get() = openRouterApiKey.isNotBlank()

    companion object {
        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"
    }
}