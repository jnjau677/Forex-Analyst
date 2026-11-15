package com.devcode940.ForexAnalyst.data.settings

import com.devcode940.ForexAnalyst.data.model.ChartConfig
import com.devcode940.ForexAnalyst.data.model.ChartOverlay
import com.devcode940.ForexAnalyst.data.model.ChartStyle
import com.devcode940.ForexAnalyst.data.model.ChartTimeZone
import com.devcode940.ForexAnalyst.data.model.MagnetMode
import com.devcode940.ForexAnalyst.data.model.MarketDataProvider
import com.devcode940.ForexAnalyst.data.model.MarketDataPreference
import com.google.gson.annotations.SerializedName

/**
 * UI theme selection.
 *
 * WHY: An enum (not String) makes invalid states unrepresentable.
 * @SerializedName keeps the on-disk JSON values ("system"/"light"/"dark")
 * stable so existing installs migrate without data loss.
 */
enum class ThemeMode {
    @SerializedName("system") SYSTEM,
    @SerializedName("light") LIGHT,
    @SerializedName("dark") DARK
}

/**
 * All user-configurable application settings.
 *
 * WHY: A single immutable snapshot makes state changes atomic and prevents
 * partially-applied configuration (e.g. new RSI period with old MACD period).
 *
 * WHY every field has a default: Gson constructs this class through its
 * no-arg constructor and assigns only the keys present in the stored JSON. A field
 * without a default would be left as `null` in a non-null Kotlin type the first
 * time an older install loads — a crash with no obvious cause. New fields are
 * therefore always additive and defaulted.
 *
 * WHY enum fields carry `@SerializedName` on their constants: Gson maps enums by
 * name, and an unknown name deserializes to `null`, which would put a null into a
 * non-null field. Freezing the wire spelling makes renaming a constant safe.
 */
data class AppSettings(
    // ---- Indicators ----
    val rsiPeriod: Int = 14,
    val emaFast: Int = 20,
    val emaMedium: Int = 50,
    val emaSlow: Int = 200,
    val macdFast: Int = 12,
    val macdSlow: Int = 26,
    val macdSignal: Int = 9,
    val atrPeriod: Int = 14,
    val bollingerPeriod: Int = 20,
    val bollingerStdDev: Double = 2.0,
    val stochasticKPeriod: Int = 14,
    val stochasticDPeriod: Int = 3,
    val showEma: Boolean = true,
    val showRsi: Boolean = true,

    // ---- Chart display ----
    /** Candle rendering style (candles, bars, line, area, Heikin-Ashi, hollow). */
    val chartStyle: ChartStyle = ChartStyle.CANDLES,

    /**
     * Time zone used to style every time on the chart.
     *
     * WHY persisted separately from the device zone: a trader following London
     * or New York sessions wants the axis in that zone regardless of where the
     * phone is, and flipping the device zone to do it would break every other app.
     */
    val chartTimeZone: ChartTimeZone = ChartTimeZone.DEVICE,

    /** Anchor snapping while drawing. */
    val chartMagnet: MagnetMode = MagnetMode.WEAK,

    val showSma: Boolean = false,
    val showBollinger: Boolean = false,
    val showVwap: Boolean = false,
    val showSessions: Boolean = true,
    val showGrid: Boolean = true,
    val showVolume: Boolean = true,
    val showMacd: Boolean = false,
    val showStochastic: Boolean = false,

    // ---- Market data ----
    /**
     * Which feed prices come from.
     *
     * WHY this is a setting and not inferred from the keys: with both keys present
     * the choice would be an invisible implementation detail, and with one key whose
     * quota has run out the user would have no way to stop the app calling it. It is
     * also how the app is run offline-on-purpose — in a review, or in a test.
     *
     * @SerializedName freezes the wire spelling, like every other enum here: Gson
     * maps an unknown name to `null`, which would put a null into this non-null
     * field on the next launch after a rename.
     */
    val marketDataSource: MarketDataPreference = MarketDataPreference.AUTO,

    /**
     * Whether the background alert monitor runs.
     *
     * WHY a switch exists: evaluation costs battery and, on a metered feed, quota.
     * A polling loop the user cannot turn off is a one-star review, and "alerts do
     * not notify" is a setting to check rather than a bug to file.
     */
    val alertMonitoringEnabled: Boolean = true,

    // ---- API credentials ----
    val fcsApiKey: String = "",
    val alphaVantageApiKey: String = "",
    val openRouterApiKey: String = "",
    val aiModel: String = "google/gemini-2.0-flash-exp:free",

    // ---- Notifications ----
    val notificationsEnabled: Boolean = true,
    val signalNotifications: Boolean = true,
    val alertNotifications: Boolean = true,
    val vibrationEnabled: Boolean = true,
    val soundEnabled: Boolean = true,

    // ---- Appearance ----
    val themeMode: ThemeMode = ThemeMode.SYSTEM
) {
    /** WHY: Surface configuration completeness to the UI without leaking keys. */
    val hasMarketDataKey: Boolean get() = fcsApiKey.isNotBlank() || alphaVantageApiKey.isNotBlank()

    /** The feed prices will actually come from, given the keys that exist. */
    val resolvedMarketDataProvider: MarketDataProvider
        get() = MarketDataPreference.resolve(
            preference = marketDataSource,
            hasFcsKey = fcsApiKey.isNotBlank(),
            hasAlphaVantageKey = alphaVantageApiKey.isNotBlank()
        )

    /**
     * Live feeds to try, best first, honouring [marketDataSource].
     *
     * WHY derived here rather than in the provider layer: the provider layer must
     * not be handed the whole settings object (it would then have the AI key too),
     * and this is the only projection of the credentials that feed selection needs.
     */
    val liveFeedOrder: List<MarketDataProvider>
        get() = MarketDataPreference.candidates(
            preference = marketDataSource,
            hasFcsKey = fcsApiKey.isNotBlank(),
            hasAlphaVantageKey = alphaVantageApiKey.isNotBlank()
        )

    /** Every credential currently held, for scrubbing them out of provider messages. */
    val configuredSecrets: List<String>
        get() = listOf(fcsApiKey, alphaVantageApiKey, openRouterApiKey).filter { it.isNotBlank() }
    val hasAiKey: Boolean get() = openRouterApiKey.isNotBlank()

    /**
     * Projects the persisted display flags onto a [ChartConfig].
     *
     * WHY a projection instead of storing a ChartConfig object: the settings JSON
     * stays flat (readable, diffable, and tolerant of new fields), while the chart
     * still gets one immutable value object with the helpers it needs.
     */
    fun toChartConfig(): ChartConfig = ChartConfig(
        style = chartStyle,
        timeZone = chartTimeZone,
        magnet = chartMagnet,
        enabled = buildSet {
            if (showEma) add(ChartOverlay.EMA)
            if (showSma) add(ChartOverlay.SMA)
            if (showBollinger) add(ChartOverlay.BOLLINGER)
            if (showVwap) add(ChartOverlay.VWAP)
            if (showSessions) add(ChartOverlay.SESSIONS)
            if (showGrid) add(ChartOverlay.GRID)
            if (showVolume) add(ChartOverlay.VOLUME)
            if (showRsi) add(ChartOverlay.RSI)
            if (showMacd) add(ChartOverlay.MACD)
            if (showStochastic) add(ChartOverlay.STOCHASTIC)
        }
    )

    /**
     * Applies a [ChartConfig] back onto the persisted flags.
     *
     * WHY the round trip exists: toolbar toggles write through here, so the chart
     * display survives a restart instead of resetting to defaults every launch.
     */
    fun withChartConfig(config: ChartConfig): AppSettings = copy(
        chartStyle = config.style,
        chartTimeZone = config.timeZone,
        chartMagnet = config.magnet,
        showEma = config.isOn(ChartOverlay.EMA),
        showSma = config.isOn(ChartOverlay.SMA),
        showBollinger = config.isOn(ChartOverlay.BOLLINGER),
        showVwap = config.isOn(ChartOverlay.VWAP),
        showSessions = config.isOn(ChartOverlay.SESSIONS),
        showGrid = config.isOn(ChartOverlay.GRID),
        showVolume = config.isOn(ChartOverlay.VOLUME),
        showRsi = config.isOn(ChartOverlay.RSI),
        showMacd = config.isOn(ChartOverlay.MACD),
        showStochastic = config.isOn(ChartOverlay.STOCHASTIC)
    )
}
