package com.devcode940.ForexAnalyst.ui.chart

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Colour vocabulary of the chart.
 *
 * WHY a fixed palette rather than MaterialTheme colours: traders read charts by
 * colour convention (up = green, down = red, EMA ribbon = blue/orange/purple), and
 * a theme that flips those semantics per app theme would make the same signal look
 * opposite on a light and dark phone. Only the *background and text* follow the
 * theme; every data colour is constant.
 *
 * WHY `@Immutable`: Compose can then skip recomposition of the chart when an
 * unrelated state changes, and the palette can be passed straight into the draw
 * scope without stability warnings.
 */
@Immutable
data class ChartColors(
    val background: Color,
    val paneBackground: Color,
    val grid: Color,
    val axisText: Color,
    val axisLine: Color,
    val crosshair: Color,
    val up: Color,
    val down: Color,
    val upFill: Color,
    val downFill: Color,
    val emaFast: Color,
    val emaMedium: Color,
    val emaSlow: Color,
    val sma: Color,
    val bollinger: Color,
    val bollingerFill: Color,
    val vwap: Color,
    val volume: Color,
    val rsi: Color,
    val rsiGuide: Color,
    val macdLine: Color,
    val macdSignal: Color,
    val macdHistogramPositive: Color,
    val macdHistogramNegative: Color,
    val stochasticK: Color,
    val stochasticD: Color,
    val sessionLondon: Color,
    val sessionNewYork: Color,
    val sessionTokyo: Color,
    val sessionSydney: Color,
    val priceTagBackground: Color,
    val priceTagText: Color,
    val tooltipBackground: Color,
    val tooltipText: Color,
    val tooltipBorder: Color,
    val selectedOutline: Color,
    val positionEntry: Color,
    val positionStop: Color,
    val positionTarget: Color
) {
    companion object {
        /**
         * Builds the palette for the current theme.
         *
         * @param isDark whether the host theme is dark.
         */
        fun forTheme(isDark: Boolean): ChartColors = if (isDark) DARK else LIGHT

        // WHY alpha-blended session bands: they must tint the candles underneath
        // without hiding them, so full opacity is never an option.
        private val LONDON_BAND = Color(0xFF2962FF).copy(alpha = 0.10f)
        private val NEW_YORK_BAND = Color(0xFF00BFA5).copy(alpha = 0.10f)
        private val TOKYO_BAND = Color(0xFFFF9100).copy(alpha = 0.08f)
        private val SYDNEY_BAND = Color(0xFFD500F9).copy(alpha = 0.08f)

        private val DARK = ChartColors(
            background = Color(0xFF0D1117),
            paneBackground = Color(0xFF111820),
            grid = Color(0xFF1E2A35),
            axisText = Color(0xFF8B9AA7),
            axisLine = Color(0xFF24303B),
            crosshair = Color(0xFF6E8290),
            up = Color(0xFF26C281),
            down = Color(0xFFE05A5A),
            upFill = Color(0xFF26C281),
            downFill = Color(0xFFE05A5A),
            emaFast = Color(0xFF4FC3F7),
            emaMedium = Color(0xFFFFB74D),
            emaSlow = Color(0xFFBA68C8),
            sma = Color(0xFF90A4AE),
            bollinger = Color(0xFF7E9CB8),
            bollingerFill = Color(0xFF7E9CB8).copy(alpha = 0.10f),
            vwap = Color(0xFFFFEE58),
            volume = Color(0xFF546E7A),
            rsi = Color(0xFFAB47BC),
            rsiGuide = Color(0xFF37474F),
            macdLine = Color(0xFF42A5F5),
            macdSignal = Color(0xFFFFA726),
            macdHistogramPositive = Color(0xFF66BB6A).copy(alpha = 0.70f),
            macdHistogramNegative = Color(0xFFEF5350).copy(alpha = 0.70f),
            stochasticK = Color(0xFF26C6DA),
            stochasticD = Color(0xFFFF7043),
            sessionLondon = LONDON_BAND,
            sessionNewYork = NEW_YORK_BAND,
            sessionTokyo = TOKYO_BAND,
            sessionSydney = SYDNEY_BAND,
            priceTagBackground = Color(0xFF2B3947),
            priceTagText = Color(0xFFE6EDF3),
            tooltipBackground = Color(0xCC1B2733),
            tooltipText = Color(0xFFE6EDF3),
            tooltipBorder = Color(0xFF2E3E4E),
            selectedOutline = Color(0xFF4FC3F7),
            positionEntry = Color(0xFF4FC3F7),
            positionStop = Color(0xFFE05A5A),
            positionTarget = Color(0xFF26C281)
        )

        private val LIGHT = ChartColors(
            background = Color(0xFFFFFFFF),
            paneBackground = Color(0xFFF7F9FB),
            grid = Color(0xFFE4E9EE),
            axisText = Color(0xFF5A6B78),
            axisLine = Color(0xFFD3DBE2),
            crosshair = Color(0xFF93A1AC),
            up = Color(0xFF16A34A),
            down = Color(0xFFDC2626),
            upFill = Color(0xFF16A34A),
            downFill = Color(0xFFDC2626),
            emaFast = Color(0xFF0288D1),
            emaMedium = Color(0xFFF57C00),
            emaSlow = Color(0xFF8E24AA),
            sma = Color(0xFF607D8B),
            bollinger = Color(0xFF6B8CA8),
            bollingerFill = Color(0xFF6B8CA8).copy(alpha = 0.10f),
            vwap = Color(0xFFC9A227),
            volume = Color(0xFF90A4AE),
            rsi = Color(0xFF8E24AA),
            rsiGuide = Color(0xFFCFD8DC),
            macdLine = Color(0xFF1976D2),
            macdSignal = Color(0xFFEF6C00),
            macdHistogramPositive = Color(0xFF43A047).copy(alpha = 0.65f),
            macdHistogramNegative = Color(0xFFE53935).copy(alpha = 0.65f),
            stochasticK = Color(0xFF00838F),
            stochasticD = Color(0xFFD84315),
            sessionLondon = LONDON_BAND.copy(alpha = 0.07f),
            sessionNewYork = NEW_YORK_BAND.copy(alpha = 0.07f),
            sessionTokyo = TOKYO_BAND.copy(alpha = 0.05f),
            sessionSydney = SYDNEY_BAND.copy(alpha = 0.05f),
            priceTagBackground = Color(0xFF3D4C59),
            priceTagText = Color(0xFFFFFFFF),
            tooltipBackground = Color(0xF2FFFFFF),
            tooltipText = Color(0xFF1B2733),
            tooltipBorder = Color(0xFFD3DBE2),
            selectedOutline = Color(0xFF0288D1),
            positionEntry = Color(0xFF0288D1),
            positionStop = Color(0xFFDC2626),
            positionTarget = Color(0xFF16A34A)
        )
    }
}

/**
 * Palette for user drawings, mirrored from [com.devcode940.ForexAnalyst.data.model.DrawingPalette]
 * so the toolbar swatches and the canvas always agree.
 *
 * WHY the mapping lives here: `DrawingPalette` stores ARGB ints (persistable, no
 * Compose dependency); the renderer needs `Color`. One lookup table keeps both in sync.
 */
internal fun drawingColor(argb: Int): Color = Color(argb)
