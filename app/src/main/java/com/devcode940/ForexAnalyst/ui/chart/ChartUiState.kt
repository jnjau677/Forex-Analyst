package com.devcode940.ForexAnalyst.ui.chart

import androidx.compose.runtime.Immutable
import com.devcode940.ForexAnalyst.data.chart.ChartCoordinateSystem
import com.devcode940.ForexAnalyst.data.chart.ChartLayout
import com.devcode940.ForexAnalyst.data.chart.ChartLayoutBuilder
import com.devcode940.ForexAnalyst.data.chart.ChartMath
import com.devcode940.ForexAnalyst.data.chart.ChartRect
import com.devcode940.ForexAnalyst.data.chart.ChartViewport
import com.devcode940.ForexAnalyst.data.chart.CrosshairData
import com.devcode940.ForexAnalyst.data.chart.DraftDrawing
import com.devcode940.ForexAnalyst.data.chart.PriceAxis
import com.devcode940.ForexAnalyst.data.chart.PriceTick
import com.devcode940.ForexAnalyst.data.chart.TimeAxis
import com.devcode940.ForexAnalyst.data.chart.TimeTick
import com.devcode940.ForexAnalyst.data.model.CandleStick
import com.devcode940.ForexAnalyst.data.model.ChartConfig
import com.devcode940.ForexAnalyst.data.model.ChartDrawing
import com.devcode940.ForexAnalyst.data.model.ChartOverlay
import com.devcode940.ForexAnalyst.data.model.ChartToolMode
import com.devcode940.ForexAnalyst.data.model.DrawingId
import com.devcode940.ForexAnalyst.data.model.DrawingPalette
import com.devcode940.ForexAnalyst.data.model.DrawingStyle
import com.devcode940.ForexAnalyst.data.model.IndicatorState
import com.devcode940.ForexAnalyst.data.model.Instrument
import com.devcode940.ForexAnalyst.data.model.InstrumentCatalog
import com.devcode940.ForexAnalyst.data.model.PairSymbol
import com.devcode940.ForexAnalyst.data.model.PriceFormatter
import com.devcode940.ForexAnalyst.data.model.Timeframe
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.abs

/**
 * Everything the chart canvas needs to render one frame.
 *
 * WHY one flat immutable snapshot: the canvas is a `Canvas { }` lambda that reads
 * its inputs during draw. Passing eleven separate parameters (the previous shape)
 * made it trivially easy to pass, say, the RSI list alongside the *old* candles,
 * producing a chart where the indicator was one pan out of sync with the price
 * action. A single value object removes that whole class of bug.
 *
 * @property candles the series to draw, **already transformed** for the active
 *   [com.devcode940.ForexAnalyst.data.model.ChartStyle] — Heikin-Ashi smoothing is
 *   applied upstream so the renderer stays a dumb painter and the transform is
 *   unit-testable without Compose.
 * @property drawings objects belonging to this pair *and* timeframe, ascending by
 *   creation time.
 * @property draft the drawing currently being dragged, if any.
 */
@Immutable
data class ChartUiState(
    val pair: PairSymbol,
    val timeframe: Timeframe,
    val candles: List<CandleStick>,
    val indicators: IndicatorState,
    val config: ChartConfig,
    val viewport: ChartViewport = ChartViewport.DEFAULT,
    val crosshair: CrosshairData? = null,
    val draft: DraftDrawing? = null,
    val drawings: List<ChartDrawing> = emptyList(),
    val selectedDrawingId: DrawingId? = null,
    val activeTool: ChartToolMode = ChartToolMode.NONE,
    val pendingStyle: DrawingStyle = DrawingStyle.SOLID,
    val pendingColorArgb: Int = DrawingPalette.DEFAULT,
    val isLoading: Boolean = false
) {

    /**
     * Instrument metadata, when the symbol is in the catalogue.
     *
     * WHY nullable: a user-typed or newly listed symbol must still render with
     * generic 5-digit formatting rather than blanking the chart.
     */
    val instrument: Instrument? get() = InstrumentCatalog.specFor(pair)

    /** Decimal places used for every price label on this chart. */
    val digits: Int get() = instrument?.quoteDigits ?: PriceFormatter.DEFAULT_DIGITS

    /** True while a two-anchor gesture is in progress. */
    val isDrafting: Boolean get() = draft != null

    /** True when there is anything to draw at all. */
    val hasData: Boolean get() = candles.isNotEmpty()

    companion object {
        /** Empty state for a chart that has not loaded yet. */
        fun empty(pair: PairSymbol, timeframe: Timeframe) = ChartUiState(
            pair = pair,
            timeframe = timeframe,
            candles = emptyList(),
            indicators = IndicatorState(),
            config = ChartConfig.default()
        )
    }
}

/**
 * Per-frame resolved geometry: pane rectangles, coordinate mappings, the visible
 * window and the axis ticks.
 *
 * WHY computed once per frame and passed down: the renderer has a dozen sections
 * (candles, three EMAs, Bollinger, VWAP, up to four sub-panes, two axes, drawings,
 * crosshair) and every one needs the *same* mapping. Computing it per section is
 * both slower and — worse — the way you end up with one section drawn against a
 * different mapping than the rest of the chart.
 *
 * @property visibleRange inclusive indices to iterate; every draw loop uses this
 *   and never the full series, so cost scales with what is on screen.
 */
@Immutable
class ChartFrame private constructor(
    val state: ChartUiState,
    val colors: ChartColors,
    val widthPx: Float,
    val heightPx: Float,
    val layout: ChartLayout,
    val priceCoords: ChartCoordinateSystem,
    val paneCoords: Map<ChartOverlay, ChartCoordinateSystem>,
    val visibleRange: IntRange,
    val barWidthPx: Float,
    val timeTicks: List<TimeTick>,
    val priceTicks: List<PriceTick>,
    val paneLabels: Map<ChartOverlay, String>,
    val zone: ZoneId
) {
    /** True when a frame can actually be painted. */
    val isDrawable: Boolean get() = visibleRange.isNotEmpty() && layout.priceRect.isUsable

    /** The candles inside [visibleRange], as a list slice (no copy). */
    val visibleCandles: List<CandleStick>
        get() = if (visibleRange.isEmpty()) {
            emptyList()
        } else {
            state.candles.subList(visibleRange.first, visibleRange.last + 1)
        }

    /** Last candle of the whole series, for the price tag on the right axis. */
    val lastCandle: CandleStick? get() = state.candles.lastOrNull()

    /** Coordinate mapping of a sub-pane; null when the pane is not enabled. */
    fun coordsFor(overlay: ChartOverlay): ChartCoordinateSystem? = paneCoords[overlay]

    /** Rectangle of a sub-pane; null when the pane is not enabled. */
    fun rectFor(overlay: ChartOverlay): ChartRect? =
        layout.panes.firstOrNull { it.overlay == overlay }?.rect

    /** Header text of a sub-pane, e.g. `RSI · 63.2`. */
    fun labelFor(overlay: ChartOverlay): String = paneLabels[overlay] ?: overlay.displayName

    /**
     * Returns [viewport] with its pan offset clamped to the current canvas size.
     *
     * WHY exposed: rotation changes the canvas width, so an offset that was legal in
     * portrait can push the series off-screen in landscape. The canvas calls this on
     * size change and pushes the corrected viewport back up instead of letting the
     * user face a blank chart with no way to recover.
     */
    fun clampViewport(viewport: ChartViewport): ChartViewport =
        viewport.copy(offsetX = priceCoords.clampOffset(viewport.offsetX))

    companion object {

        /** Bars kept outside the visible window so partial candles still render. */
        private const val VISIBLE_BUFFER = 2

        /** Target horizontal grid lines. */
        private const val TARGET_PRICE_TICKS = 6

        /** Hard ceiling on time labels, however wide the canvas is. */
        private const val MAX_TIME_TICKS = 10

        /** Minimum horizontal space between two time labels before they collide. */
        private const val MIN_TICK_SPACING_PX = 74f

        /** Label height used to decide whether price labels would collide. */
        private const val LABEL_HEIGHT_PX = 14f

        /**
         * Placeholder pair for the not-drawable frame.
         *
         * WHY a sentinel: [EMPTY] is returned before any state exists (first
         * composition, size 0×0). It is never read — `isDrawable` is false — but a
         * value is still required, and an arbitrary-but-valid symbol avoids
         * propagating nullability through every renderer parameter.
         */
        private val SENTINEL_PAIR = PairSymbol.of("EMPTY")

        /** An unusable frame; the renderer paints nothing. */
        private val EMPTY = ChartFrame(
            state = ChartUiState.empty(SENTINEL_PAIR, Timeframe.H1),
            colors = ChartColors.forTheme(isDark = true),
            widthPx = 0f,
            heightPx = 0f,
            layout = ChartLayoutBuilder.build(0f, 0f, ChartConfig.default()),
            priceCoords = ChartCoordinateSystem(ChartRect.EMPTY, 0.0, 1.0, 0L, 1L),
            paneCoords = emptyMap(),
            visibleRange = IntRange.EMPTY,
            barWidthPx = 0f,
            timeTicks = emptyList(),
            priceTicks = emptyList(),
            paneLabels = emptyMap(),
            zone = ZoneOffset.UTC
        )

        /**
         * Resolves all geometry for one frame.
         *
         * @param state chart inputs.
         * @param colors palette for the active theme.
         * @param widthPx canvas width in pixels.
         * @param heightPx canvas height in pixels.
         */
        fun build(state: ChartUiState, colors: ChartColors, widthPx: Float, heightPx: Float): ChartFrame {
            if (widthPx <= 0f || heightPx <= 0f || state.candles.isEmpty()) return EMPTY

            val config = state.config
            val zone = config.timeZone.resolve()
            val layout = ChartLayoutBuilder.build(widthPx, heightPx, config)
            val priceRect = layout.priceRect
            if (!priceRect.isUsable) return EMPTY

            // WHY two passes: the price axis must scale to the *visible* window, but
            // which candles are visible depends on the mapping, which depends on the
            // price bounds. Pass 1 builds a provisional mapping over the full series
            // purely to find the window; pass 2 rebuilds with bounds taken from that
            // window only. Both passes are O(log n) plus an O(visible) bounds scan.
            val provisional = ChartCoordinateSystem.forCandles(
                rect = priceRect,
                candles = state.candles,
                visibleRange = IntRange.EMPTY,
                scale = state.viewport.scale,
                offsetX = state.viewport.offsetX
            )
            val startMillis = provisional.xToTime(priceRect.leftEdge)
            val endMillis = provisional.xToTime(priceRect.rightEdge)
            val visibleRange = ChartMath.visibleRange(state.candles, startMillis, endMillis, VISIBLE_BUFFER)
            if (visibleRange.isEmpty()) return EMPTY

            val priceCoords = ChartCoordinateSystem.forCandles(
                rect = priceRect,
                candles = state.candles,
                visibleRange = visibleRange,
                scale = state.viewport.scale,
                offsetX = state.viewport.offsetX
            )

            return ChartFrame(
                state = state,
                colors = colors,
                widthPx = widthPx,
                heightPx = heightPx,
                layout = layout,
                priceCoords = priceCoords,
                paneCoords = layout.panes.associate { pane ->
                    pane.overlay to paneCoordinateSystem(pane.rect, pane.overlay, state, visibleRange, priceCoords)
                },
                visibleRange = visibleRange,
                barWidthPx = priceCoords.barWidthPx(state.timeframe.intervalMillis),
                timeTicks = buildTimeTicks(priceCoords, zone),
                priceTicks = buildPriceTicks(state, priceCoords, priceRect.height),
                paneLabels = layout.panes.associate { pane ->
                    pane.overlay to paneLabel(pane.overlay, state, visibleRange)
                },
                zone = zone
            )
        }

        /**
         * Builds the mapping for one sub-pane.
         *
         * WHY bounded ranges for oscillators: RSI and Stochastic have a fixed 0..100
         * domain, so scaling them to their visible extremes would flatten the 30/70
         * guides and hide overbought readings.
         *
         * WHY volume and MACD are data-scaled: both are unbounded, and a fixed range
         * would either clip spikes or squash them into the baseline.
         */
        private fun paneCoordinateSystem(
            rect: ChartRect,
            overlay: ChartOverlay,
            state: ChartUiState,
            visibleRange: IntRange,
            priceCoords: ChartCoordinateSystem
        ): ChartCoordinateSystem {
            val (min, max) = when (overlay) {
                ChartOverlay.VOLUME -> volumeBounds(state, visibleRange)
                ChartOverlay.RSI, ChartOverlay.STOCHASTIC -> OSCILLATOR_RANGE
                ChartOverlay.MACD -> macdBounds(state, visibleRange)
                // WHY: price overlays never get panes, but the `when` must stay
                // exhaustive; mirroring the price range is a harmless no-op.
                else -> priceCoords.minPrice to priceCoords.maxPrice
            }
            return ChartCoordinateSystem.forFixedRange(
                rect = rect,
                minValue = min,
                maxValue = max,
                startTimeMillis = priceCoords.startTimeMillis,
                endTimeMillis = priceCoords.endTimeMillis,
                scale = state.viewport.scale,
                offsetX = state.viewport.offsetX
            )
        }

        /** Highest visible volume, or a unit range when every bar is zero. */
        private fun volumeBounds(state: ChartUiState, visibleRange: IntRange): Pair<Double, Double> {
            var max = 0.0
            for (index in visibleRange) {
                val volume = state.candles[index].volume
                if (volume > max) max = volume.toDouble()
            }
            // WHY: a zero maximum divides by zero in the mapping and the pane blanks.
            return if (max <= 0.0) 0.0 to 1.0 else 0.0 to max
        }

        /** Bounds of the visible MACD line, signal line and histogram. */
        private fun macdBounds(state: ChartUiState, visibleRange: IntRange): Pair<Double, Double> {
            val macd = state.indicators.macd ?: return MACD_FALLBACK_RANGE
            var min = Double.POSITIVE_INFINITY
            var max = Double.NEGATIVE_INFINITY
            for (index in visibleRange) {
                listOf(
                    macd.macdLine.getOrNull(index),
                    macd.signalLine.getOrNull(index),
                    macd.histogram.getOrNull(index)
                ).forEach { value ->
                    if (value != null && value.isFinite()) {
                        if (value < min) min = value
                        if (value > max) max = value
                    }
                }
            }
            if (min == Double.POSITIVE_INFINITY) return MACD_FALLBACK_RANGE
            // WHY: pad so the histogram never touches the pane border, but never let
            // the padding exceed the data range itself (a flat window would otherwise
            // be scaled entirely by its own padding).
            val span = max - min
            val padding = if (span > 0.0) span * MACD_PADDING_FRACTION else abs(max) * MACD_PADDING_FRACTION
            return (min - padding) to (max + padding)
        }

        /**
         * Time-axis ticks, projected to pixels.
         *
         * WHY the step is chosen from the visible span rather than the timeframe: the
         * timeframe says how far apart the *bars* are, but labels must be spaced by
         * the *pixels* available — a zoomed-in H1 chart needs minutes, a zoomed-out
         * one needs months.
         */
        private fun buildTimeTicks(coords: ChartCoordinateSystem, zone: ZoneId): List<TimeTick> {
            val rect = coords.rect
            if (!rect.isUsable) return emptyList()
            val startMillis = coords.xToTime(rect.leftEdge)
            val endMillis = coords.xToTime(rect.rightEdge)
            if (endMillis <= startMillis) return emptyList()

            // WHY scale the tick budget to width: a fixed count crowds labels on a
            // phone and spaces them absurdly on a tablet.
            val budget = (rect.width / MIN_TICK_SPACING_PX).toInt().coerceIn(3, MAX_TIME_TICKS)
            val step = TimeAxis.chooseStep(endMillis - startMillis, budget)
            return TimeAxis.ticks(
                start = Instant.ofEpochMilli(startMillis),
                end = Instant.ofEpochMilli(endMillis),
                step = step,
                zone = zone,
                maxTicks = budget
            ).map { instant ->
                TimeTick(
                    instant = instant,
                    label = TimeAxis.labelFor(instant, step, zone),
                    xPx = coords.timeToX(instant)
                )
            }
        }

        /** Price-axis ticks, projected to pixels and formatted for this instrument. */
        private fun buildPriceTicks(
            state: ChartUiState,
            coords: ChartCoordinateSystem,
            paneHeightPx: Float
        ): List<PriceTick> {
            val ticks = PriceAxis.ticks(
                min = coords.minPrice,
                max = coords.maxPrice,
                targetTicks = TARGET_PRICE_TICKS,
                digits = state.digits
            )
            val spacing = PriceAxis.labelSpacingPx(paneHeightPx, ticks.size)
            // WHY drop every other tick instead of shrinking the font: a smaller font
            // is unreadable on a phone, and collided labels are worse than fewer
            // labels. The stride is 2 only when they actually would collide.
            val stride = if (PriceAxis.labelsCollide(spacing, LABEL_HEIGHT_PX)) 2 else 1
            return ticks.filterIndexed { index, _ -> index % stride == 0 }.map { price ->
                PriceTick(
                    price = price,
                    label = PriceFormatter.format(price, state.digits),
                    yPx = coords.priceToY(price)
                )
            }
        }

        /**
         * Header text for a sub-pane, e.g. `Stoch · 71.4 / 65.0`.
         *
         * WHY it lives in the frame: it needs the indicator values and the settings
         * that produced them; deriving it inside the draw lambda would recompute the
         * same string on every paint of every pane.
         */
        private fun paneLabel(overlay: ChartOverlay, state: ChartUiState, visibleRange: IntRange): String {
            val index = if (visibleRange.isEmpty()) -1 else visibleRange.last
            return when (overlay) {
                ChartOverlay.VOLUME -> overlay.displayName
                ChartOverlay.RSI -> {
                    val value = state.indicators.rsi?.getOrNull(index)
                    if (value == null) overlay.displayName else "${overlay.displayName} · ${PriceFormatter.format(value, 1)}"
                }
                ChartOverlay.MACD -> {
                    val value = state.indicators.macd?.macdLine?.getOrNull(index)
                    if (value == null) overlay.displayName else "${overlay.displayName} · ${PriceFormatter.format(value, 5)}"
                }
                ChartOverlay.STOCHASTIC -> {
                    val k = state.indicators.stochastic?.k?.getOrNull(index)
                    val d = state.indicators.stochastic?.d?.getOrNull(index)
                    if (k == null || d == null) {
                        overlay.displayName
                    } else {
                        "Stoch · ${PriceFormatter.format(k, 1)} / ${PriceFormatter.format(d, 1)}"
                    }
                }
                // WHY: price overlays have no pane, so no header exists.
                else -> overlay.displayName
            }
        }

        private val OSCILLATOR_RANGE = 0.0 to 100.0
        private val MACD_FALLBACK_RANGE = -1.0 to 1.0
        private const val MACD_PADDING_FRACTION = 0.10
    }
}
