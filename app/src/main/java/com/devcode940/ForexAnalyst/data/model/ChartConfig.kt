package com.devcode940.ForexAnalyst.data.model

import com.google.gson.annotations.SerializedName
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Candle/body rendering style.
 *
 * WHY an enum with `@SerializedName`: the value is persisted inside the settings
 * JSON, so the on-disk spelling must be frozen — renaming a constant later would
 * otherwise deserialize to null and silently reset the user's chart.
 */
enum class ChartStyle(val displayName: String) {
    @SerializedName("candles") CANDLES("Candles"),
    @SerializedName("hollow") HOLLOW_CANDLES("Hollow"),
    @SerializedName("bars") OHLC_BARS("OHLC bars"),
    @SerializedName("line") LINE("Line"),
    @SerializedName("area") AREA("Area"),
    @SerializedName("heikin-ashi") HEIKIN_ASHI("Heikin-Ashi")
}

/**
 * Time zone used to *style* every time on the chart: axis labels, crosshair tag,
 * session shading and tooltips.
 *
 * WHY this exists at all: forex is a 24-hour global market and every broker quotes
 * daily bars in its own zone. A trader in Nairobi reading a chart stamped in UTC
 * has to do mental arithmetic to know whether a candle is the London open or the
 * New York close. Making the zone an explicit, persisted choice removes that.
 *
 * [DEVICE] resolves lazily so a device time-zone change is picked up without a
 * restart.
 */
enum class ChartTimeZone(val displayName: String, private val fixedZone: ZoneId?) {
    @SerializedName("device") DEVICE("Device", null),
    @SerializedName("utc") UTC("UTC", ZoneOffset.UTC),
    @SerializedName("london") LONDON("London", ZoneId.of("Europe/London")),
    @SerializedName("new_york") NEW_YORK("New York", ZoneId.of("America/New_York")),
    @SerializedName("tokyo") TOKYO("Tokyo", ZoneId.of("Asia/Tokyo")),
    @SerializedName("nairobi") NAIROBI("Nairobi", ZoneId.of("Africa/Nairobi")),
    @SerializedName("dubai") DUBAI("Dubai", ZoneId.of("Asia/Dubai")),
    @SerializedName("singapore") SINGAPORE("Singapore", ZoneId.of("Asia/Singapore"));

    /** The zone to format with; [DEVICE] follows the current system zone. */
    fun resolve(): ZoneId = fixedZone ?: ZoneId.systemDefault()

    /** Short suffix rendered next to axis labels so the zone is never ambiguous. */
    fun badge(): String = if (fixedZone == null) "local" else name.lowercase()
}

/**
 * Optional chart layers.
 *
 * WHY one enum with an [isPane] flag instead of ten booleans: the toolbar iterates
 * the enum to build its toggles, the layout iterates the panes to divide vertical
 * space, and adding a layer is a single new constant instead of touching a config
 * class, a mapper, a ViewModel setter and three composables.
 *
 * @property isPane true when the layer needs its own horizontal sub-panel below
 *   the price pane (volume, oscillators), false when it overlays the price pane.
 */
enum class ChartOverlay(val displayName: String, val isPane: Boolean) {
    @SerializedName("ema") EMA("EMA", false),
    @SerializedName("sma") SMA("SMA", false),
    @SerializedName("bollinger") BOLLINGER("Bollinger", false),
    @SerializedName("vwap") VWAP("VWAP", false),
    @SerializedName("sessions") SESSIONS("Sessions", false),
    @SerializedName("grid") GRID("Grid", false),
    @SerializedName("volume") VOLUME("Volume", true),
    @SerializedName("rsi") RSI("RSI", true),
    @SerializedName("macd") MACD("MACD", true),
    @SerializedName("stochastic") STOCHASTIC("Stochastic", true);

    companion object {
        /** Layers that get their own sub-panel, in top-to-bottom order. */
        val PANES: List<ChartOverlay> = entries.filter { it.isPane }

        /** Layers drawn on top of the price pane. */
        val OVERLAYS: List<ChartOverlay> = entries.filter { !it.isPane }

        /** Layers enabled on a fresh chart. */
        val DEFAULT_ENABLED: Set<ChartOverlay> = setOf(EMA, GRID, SESSIONS, VOLUME, RSI)
    }
}

/**
 * Anchor snapping while drawing.
 *
 * WHY: on a touch screen a finger cannot land on an exact candle high. Magnet
 * snapping to the nearest OHLC value is what makes hand-drawn levels usable;
 * [WEAK] snaps only within a few pixels, [STRONG] always snaps.
 */
enum class MagnetMode(val displayName: String, val tolerancePx: Float) {
    @SerializedName("none") NONE("Off", 0f),
    @SerializedName("weak") WEAK("Weak", 14f),
    @SerializedName("strong") STRONG("Strong", Float.MAX_VALUE)
}

/**
 * The tool currently armed in the chart toolbar.
 *
 * WHY [NONE] and [PAN] are distinct: [NONE] means "tap shows the crosshair",
 * which is the reading mode traders expect; [PAN] disables crosshair taps so a
 * drag cannot accidentally drop a measurement.
 */
enum class ChartToolMode(val displayName: String, val shortName: String) {
    @SerializedName("none") NONE("Crosshair", "Cross"),
    @SerializedName("pan") PAN("Pan / zoom", "Pan"),
    @SerializedName("horizontal") HORIZONTAL_LINE("Horizontal line", "H-line"),
    @SerializedName("vertical") VERTICAL_LINE("Vertical line", "V-line"),
    @SerializedName("trendline") TREND_LINE("Trend line", "Trend"),
    @SerializedName("ray") RAY("Ray", "Ray"),
    @SerializedName("extended") EXTENDED_LINE("Extended line", "Extend"),
    @SerializedName("fibonacci") FIBONACCI("Fib retracement", "Fib"),
    @SerializedName("fibextension") FIB_EXTENSION("Fib extension", "Fib X"),
    @SerializedName("rectangle") RECTANGLE("Rectangle", "Rect"),
    @SerializedName("measure") MEASURE("Measure", "Measure"),
    @SerializedName("long_position") LONG_POSITION("Long position", "Long"),
    @SerializedName("short_position") SHORT_POSITION("Short position", "Short");

    /**
     * True when the tool needs two anchors, i.e. a press-drag-release gesture.
     *
     * WHY the position tools are two-anchor: a position needs an entry *and* a stop,
     * and a single tap cannot supply both. Dragging from entry to stop is the only
     * gesture that gives the user control over the risk distance, and the preview
     * mirrors the target on the other side so the resulting 1:1 plan is visible before
     * the finger lifts.
     *
     * WHY the line tools are one-anchor: a horizontal line is fully described by one
     * price and a vertical line by one time, so making the user drag for them adds a
     * gesture with nothing to decide.
     */
    val isTwoAnchor: Boolean
        get() = when (this) {
            NONE, PAN, HORIZONTAL_LINE, VERTICAL_LINE -> false
            TREND_LINE, RAY, EXTENDED_LINE, FIBONACCI, FIB_EXTENSION, RECTANGLE, MEASURE,
            LONG_POSITION, SHORT_POSITION -> true
        }

    /** True when the tool is a drawing tool (as opposed to a navigation mode). */
    val isDrawingTool: Boolean get() = this != NONE && this != PAN

    companion object {
        /** Tools offered in the toolbar rail, in display order. */
        val TOOLBAR: List<ChartToolMode> = listOf(
            NONE, PAN, HORIZONTAL_LINE, VERTICAL_LINE, TREND_LINE, RAY, EXTENDED_LINE,
            FIBONACCI, FIB_EXTENSION, RECTANGLE, MEASURE, LONG_POSITION, SHORT_POSITION
        )
    }
}

/**
 * Immutable chart display configuration.
 *
 * WHY a value object: it is persisted as part of [com.devcode940.ForexAnalyst.data.settings.AppSettings]
 * and read by the renderer every frame. A single immutable snapshot means a toggle
 * can never be observed half-applied (e.g. MACD enabled but its pane height not yet
 * reserved), which is exactly the flicker users report as "the chart jumped".
 */
data class ChartConfig(
    val style: ChartStyle = ChartStyle.CANDLES,
    val timeZone: ChartTimeZone = ChartTimeZone.DEVICE,
    val magnet: MagnetMode = MagnetMode.WEAK,
    val enabled: Set<ChartOverlay> = ChartOverlay.DEFAULT_ENABLED
) {
    /**
     * WHY no `init` validation: every field is already an enum or a set of enums,
     * so invalid states are unrepresentable — there is nothing to check. All
     * mutators below return `enabled ± overlay`, which is an immutable set, so a
     * config obtained through them can never be mutated from the outside.
     */

    /** True when [overlay] is switched on. */
    fun isOn(overlay: ChartOverlay): Boolean = overlay in enabled

    /** Returns a copy with [overlay] toggled. */
    fun toggled(overlay: ChartOverlay): ChartConfig =
        copy(enabled = if (isOn(overlay)) enabled - overlay else enabled + overlay)

    /** Returns a copy with [overlay] explicitly on or off. */
    fun with(overlay: ChartOverlay, on: Boolean): ChartConfig =
        copy(enabled = if (on) enabled + overlay else enabled - overlay)

    /** Sub-panels to render, in top-to-bottom order. */
    val panes: List<ChartOverlay> get() = ChartOverlay.PANES.filter { it in enabled }

    companion object {
        /** Configuration used on a fresh install. */
        fun default(): ChartConfig = ChartConfig()
    }
}
