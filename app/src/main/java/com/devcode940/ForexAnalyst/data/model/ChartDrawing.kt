package com.devcode940.ForexAnalyst.data.model

import java.time.Instant
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Identity of a user drawing. See [SignalId] for why UUID beats String. */
@JvmInline
value class DrawingId(val value: UUID) {
    companion object {
        /** Creates a fresh, collision-free drawing identity. */
        fun generate(): DrawingId = DrawingId(UUID.randomUUID())
    }
}

/**
 * An anchor of a drawing in **data space** (time + price), not pixels.
 *
 * WHY data space: pixel coordinates are meaningless once the user pans, zooms or
 * rotates the device. Storing (time, price) means a trendline stays pinned to the
 * same two candles forever — the renderer resolves it to pixels every frame.
 */
data class ChartPoint(val time: Instant, val price: Double) {
    init {
        // WHY: a NaN anchor would make the whole drawing invisible *and* poison
        // the hit-testing maths (every comparison with NaN is false), leaving an
        // undeletable ghost object on the chart.
        require(price.isFinite()) { "ChartPoint price must be finite (got $price)" }
    }
}

/** Line rendering style. */
enum class DrawingStyle(val displayName: String) {
    SOLID("Solid"),
    DASHED("Dashed"),
    DOTTED("Dotted")
}

/** Direction of a position drawing. */
enum class TradeSide(val displayName: String) {
    LONG("Long"),
    SHORT("Short")
}

/**
 * Drawing colour palette, as ARGB ints.
 *
 * WHY ARGB ints and not `androidx.compose.ui.graphics.Color`: the domain model
 * must stay free of Compose types so it is unit-testable on the JVM and could be
 * persisted to Room without a converter for a Compose class.
 */
object DrawingPalette {

    /**
     * One picker swatch: the colour plus the name a screen reader announces.
     *
     * WHY the name lives with the value: a row of identical circles is unusable without
     * one, and keeping the names in a parallel UI list means the first palette reorder
     * silently mislabels every swatch.
     */
    data class Swatch(val argb: Int, val name: String)

    /** Accent swatches, ordered as they appear in the picker. */
    val SWATCHES: List<Swatch> = listOf(
        Swatch(0xFF2962FF.toInt(), "Blue"),
        Swatch(0xFF26A69A.toInt(), "Teal"),
        Swatch(0xFFEF5350.toInt(), "Red"),
        Swatch(0xFFFFB74D.toInt(), "Amber"),
        Swatch(0xFFAB47BC.toInt(), "Purple"),
        Swatch(0xFF66BB6A.toInt(), "Green"),
        Swatch(0xFFECEFF1.toInt(), "White")
    )

    /** Bare ARGB values, for callers that only paint. */
    val COLORS: List<Int> = SWATCHES.map { it.argb }

    /** Default colour for newly created drawings. */
    val DEFAULT: Int = COLORS[0]

    /** Bullish/bearish colours used by the position tool. */
    val LONG: Int = 0xFF26A69A.toInt()
    val SHORT: Int = 0xFFEF5350.toInt()

    /** Fibonacci level colours, indexed by level position. */
    val FIB_LEVELS: List<Int> = listOf(
        0xFF787B86.toInt(),
        0xFFF23645.toInt(),
        0xFFFF9800.toInt(),
        0xFF4CAF50.toInt(),
        0xFF089981.toInt(),
        0xFF2962FF.toInt(),
        0xFF787B86.toInt()
    )
}

/**
 * A user-created chart annotation, in data space.
 *
 * WHY a sealed hierarchy: each tool has genuinely different geometry (a
 * horizontal line has one price and no time; a rectangle has two of both), and a
 * sealed type makes the renderer's `when` exhaustive — adding a tool becomes a
 * compile error at every place that must handle it, instead of a silently
 * invisible drawing.
 *
 * Every subtype carries [pairSymbol] and [timeframe] so drawings are scoped to
 * the chart they were made on: an H1 trendline must not appear on the D1 chart of
 * a different pair.
 */
sealed class ChartDrawing {

    /** Stable identity, used for selection, deletion and undo. */
    abstract val id: DrawingId

    /** Chart this drawing belongs to. */
    abstract val pairSymbol: PairSymbol

    /** Timeframe this drawing belongs to. */
    abstract val timeframe: Timeframe

    /** Line style. */
    abstract val style: DrawingStyle

    /** ARGB colour. */
    abstract val colorArgb: Int

    /** Creation time, used for ordering and "last drawn" display. */
    abstract val createdAt: Instant

    /** Optional user-visible label. */
    abstract val label: String

    /**
     * Anchor prices, for hit-testing and axis tags. Empty for time-only drawings.
     *
     * WHY: the renderer and the hit-tester both need "which prices does this
     * object occupy" without a `when` on the concrete type.
     */
    abstract val anchorPrices: List<Double>

    /** Anchor times, for hit-testing. Empty for price-only drawings. */
    abstract val anchorTimes: List<Instant>

    /** Short description shown while the drawing is selected. */
    abstract val description: String

    /** A horizontal price level spanning the whole chart. */
    data class HorizontalLine(
        override val id: DrawingId,
        override val pairSymbol: PairSymbol,
        override val timeframe: Timeframe,
        override val style: DrawingStyle,
        override val colorArgb: Int,
        override val createdAt: Instant,
        override val label: String,
        val price: Double
    ) : ChartDrawing() {
        init {
            require(price.isFinite()) { "Horizontal line price must be finite (got $price)" }
            ChartDrawing.validateLabel(label)
        }

        override val anchorPrices: List<Double> get() = listOf(price)
        override val anchorTimes: List<Instant> get() = emptyList()
        override val description: String get() = "Horizontal line"
    }

    /** A vertical time marker (e.g. a news event or a session boundary). */
    data class VerticalLine(
        override val id: DrawingId,
        override val pairSymbol: PairSymbol,
        override val timeframe: Timeframe,
        override val style: DrawingStyle,
        override val colorArgb: Int,
        override val createdAt: Instant,
        override val label: String,
        val time: Instant
    ) : ChartDrawing() {
        init {
            ChartDrawing.validateLabel(label)
        }

        override val anchorPrices: List<Double> get() = emptyList()
        override val anchorTimes: List<Instant> get() = listOf(time)
        override val description: String get() = "Vertical line"
    }

    /**
     * A two-point trendline.
     *
     * WHY one type with [extendLeft]/[extendRight] instead of separate
     * Trendline/Ray/Extended types: a ray *is* a trendline whose right segment is
     * stretched to the pane edge. Modelling it as flags keeps the stored shape and
     * the hit-test maths identical, and converting a segment into a ray is a
     * one-field edit rather than a type migration.
     */
    data class TrendLine(
        override val id: DrawingId,
        override val pairSymbol: PairSymbol,
        override val timeframe: Timeframe,
        override val style: DrawingStyle,
        override val colorArgb: Int,
        override val createdAt: Instant,
        override val label: String,
        val start: ChartPoint,
        val end: ChartPoint,
        val extendLeft: Boolean = false,
        val extendRight: Boolean = false
    ) : ChartDrawing() {
        init {
            ChartDrawing.validateLabel(label)
            // WHY: a zero-length trendline has no slope; extrapolating it would
            // divide by zero when projecting to the pane edge.
            require(start != end) { "Trendline anchors must differ" }
        }

        override val anchorPrices: List<Double> get() = listOf(start.price, end.price)
        override val anchorTimes: List<Instant> get() = listOf(start.time, end.time)
        override val description: String get() = when {
            extendLeft && extendRight -> "Extended line"
            extendRight -> "Ray"
            extendLeft -> "Backward ray"
            else -> "Trend line"
        }

        /** Slope in price per millisecond; null for a perfectly vertical line. */
        val slopePerMillis: Double?
            get() {
                val dt = (end.time.toEpochMilli() - start.time.toEpochMilli()).toDouble()
                // WHY: a vertical trendline has infinite slope; returning null
                // forces callers to handle the case instead of producing Infinity.
                return if (dt == 0.0) null else (end.price - start.price) / dt
            }
    }

    /**
     * Fibonacci retracement between two anchors.
     *
     * WHY levels are data, not a constant: traders use custom level sets, and the
     * defaults ([DEFAULT_LEVELS]) mirror the industry-standard
     * 0 / 23.6 / 38.2 / 50 / 61.8 / 78.6 / 100 % set.
     */
    data class FibRetracement(
        override val id: DrawingId,
        override val pairSymbol: PairSymbol,
        override val timeframe: Timeframe,
        override val style: DrawingStyle,
        override val colorArgb: Int,
        override val createdAt: Instant,
        override val label: String,
        val start: ChartPoint,
        val end: ChartPoint,
        val levels: List<Double> = DEFAULT_LEVELS
    ) : ChartDrawing() {
        init {
            ChartDrawing.validateLabel(label)
            ChartDrawing.validateLevels(levels, RANGE_RETRACEMENT)
            require(start != end) { "Fibonacci anchors must differ" }
        }

        override val anchorPrices: List<Double> get() = listOf(start.price, end.price)
        override val anchorTimes: List<Instant> get() = listOf(start.time, end.time)
        override val description: String get() = "Fibonacci retracement"

        /** Higher price of the two anchors — the 0 % end of an uptrend retracement. */
        val highPrice: Double get() = max(start.price, end.price)

        /** Lower price of the two anchors. */
        val lowPrice: Double get() = min(start.price, end.price)

        companion object {
            /** Standard retracement levels as fractions of the measured range. */
            val DEFAULT_LEVELS: List<Double> = listOf(0.0, 0.236, 0.382, 0.5, 0.618, 0.786, 1.0)

            /** Retracement levels are fractions of the swing, so 0..1. */
            val RANGE_RETRACEMENT: ClosedFloatingPointRange<Double> = 0.0..1.0
        }
    }

    /** Fibonacci *extension*: levels beyond the swing (127.2 %, 161.8 %, 261.8 %). */
    data class FibExtension(
        override val id: DrawingId,
        override val pairSymbol: PairSymbol,
        override val timeframe: Timeframe,
        override val style: DrawingStyle,
        override val colorArgb: Int,
        override val createdAt: Instant,
        override val label: String,
        val start: ChartPoint,
        val end: ChartPoint,
        val levels: List<Double> = DEFAULT_LEVELS
    ) : ChartDrawing() {
        init {
            ChartDrawing.validateLabel(label)
            ChartDrawing.validateLevels(levels, RANGE_EXTENSION)
            require(start != end) { "Fibonacci anchors must differ" }
        }

        override val anchorPrices: List<Double> get() = listOf(start.price, end.price)
        override val anchorTimes: List<Instant> get() = listOf(start.time, end.time)
        override val description: String get() = "Fibonacci extension"

        val highPrice: Double get() = max(start.price, end.price)
        val lowPrice: Double get() = min(start.price, end.price)

        companion object {
            /** Standard extension levels, including the two beyond the swing. */
            val DEFAULT_LEVELS: List<Double> = listOf(0.0, 0.618, 1.0, 1.618, 2.618)

            /** Extensions run past 100 %, but not to infinity. */
            val RANGE_EXTENSION: ClosedFloatingPointRange<Double> = 0.0..4.0
        }
    }

    /** A filled rectangle between two corners (support/resistance zones). */
    data class Rectangle(
        override val id: DrawingId,
        override val pairSymbol: PairSymbol,
        override val timeframe: Timeframe,
        override val style: DrawingStyle,
        override val colorArgb: Int,
        override val createdAt: Instant,
        override val label: String,
        val start: ChartPoint,
        val end: ChartPoint
    ) : ChartDrawing() {
        init {
            ChartDrawing.validateLabel(label)
            require(start != end) { "Rectangle corners must differ" }
        }

        override val anchorPrices: List<Double> get() = listOf(start.price, end.price)
        override val anchorTimes: List<Instant> get() = listOf(start.time, end.time)
        override val description: String get() = "Rectangle"
    }

    /**
     * Measurement: Δ price, Δ %, pip count and bar count between two points.
     *
     * WHY persisted like any other drawing: a measurement the user took an hour
     * ago is still a measurement they want to see; TradingView keeps it too.
     */
    data class Measurement(
        override val id: DrawingId,
        override val pairSymbol: PairSymbol,
        override val timeframe: Timeframe,
        override val style: DrawingStyle,
        override val colorArgb: Int,
        override val createdAt: Instant,
        override val label: String,
        val start: ChartPoint,
        val end: ChartPoint
    ) : ChartDrawing() {
        init {
            ChartDrawing.validateLabel(label)
            require(start != end) { "Measurement anchors must differ" }
        }

        override val anchorPrices: List<Double> get() = listOf(start.price, end.price)
        override val anchorTimes: List<Instant> get() = listOf(start.time, end.time)
        override val description: String get() = "Measurement"
    }

    /**
     * Long/short position planner: entry, stop and target levels plus risk.
     *
     * WHY validated per side: a "long" whose stop sits above its entry is a
     * contradiction that would render an upside-down risk box and report a
     * negative risk/reward — the kind of silently wrong output that costs money.
     */
    data class Position(
        override val id: DrawingId,
        override val pairSymbol: PairSymbol,
        override val timeframe: Timeframe,
        override val style: DrawingStyle,
        override val colorArgb: Int,
        override val createdAt: Instant,
        override val label: String,
        val side: TradeSide,
        val entryPrice: Double,
        val stopPrice: Double,
        val targetPrice: Double,
        val riskPercent: Double = DEFAULT_RISK_PERCENT
    ) : ChartDrawing() {
        init {
            ChartDrawing.validateLabel(label)
            listOf(entryPrice, stopPrice, targetPrice).forEach { price ->
                require(price.isFinite()) { "Position prices must be finite (got $price)" }
            }
            require(riskPercent.isFinite() && riskPercent in 0.01..100.0) {
                "riskPercent must be 0.01..100 (got $riskPercent)"
            }
            when (side) {
                TradeSide.LONG -> {
                    require(stopPrice < entryPrice) {
                        "A long stop ($stopPrice) must sit below the entry ($entryPrice)"
                    }
                    require(targetPrice > entryPrice) {
                        "A long target ($targetPrice) must sit above the entry ($entryPrice)"
                    }
                }
                TradeSide.SHORT -> {
                    require(stopPrice > entryPrice) {
                        "A short stop ($stopPrice) must sit above the entry ($entryPrice)"
                    }
                    require(targetPrice < entryPrice) {
                        "A short target ($targetPrice) must sit below the entry ($entryPrice)"
                    }
                }
            }
        }

        override val anchorPrices: List<Double> get() = listOf(entryPrice, stopPrice, targetPrice)
        override val anchorTimes: List<Instant> get() = emptyList()
        override val description: String get() = "${side.displayName} position"

        /** Absolute price at risk per unit. */
        val riskPerUnit: Double get() = abs(entryPrice - stopPrice)

        /** Absolute price reward per unit. */
        val rewardPerUnit: Double get() = abs(targetPrice - entryPrice)

        /** Reward divided by risk; 0 when the stop equals the entry. */
        val riskRewardRatio: Double
            get() = if (riskPerUnit == 0.0) 0.0 else rewardPerUnit / riskPerUnit

        companion object {
            /** Conventional default risk per trade. */
            const val DEFAULT_RISK_PERCENT = 1.0
        }
    }

    companion object {
        /** Maximum user-supplied label length; longer text is truncated in the UI. */
        const val MAX_LABEL_LENGTH = 80

        /**
         * WHY shared validation: every subtype accepts a label, and a 10 000-char
         * label would be drawn across the whole canvas and blow up the persisted
         * row size.
         */
        internal fun validateLabel(label: String) {
            require(label.length <= MAX_LABEL_LENGTH) {
                "Drawing label too long (max $MAX_LABEL_LENGTH chars, got ${label.length})"
            }
            require(label.none { it.isISOControl() }) { "Drawing label cannot contain control characters" }
        }

        /**
         * WHY shared validation: levels drive both rendering and hit-testing;
         * unsorted or out-of-range values would draw overlapping lines and make
         * the level picker ambiguous.
         */
        internal fun validateLevels(levels: List<Double>, range: ClosedFloatingPointRange<Double>) {
            require(levels.isNotEmpty()) { "Fibonacci needs at least one level" }
            require(levels.size <= 12) { "Fibonacci supports at most 12 levels (got ${levels.size})" }
            require(levels.all { it.isFinite() && it in range }) {
                "Fibonacci levels must be within $range (got $levels)"
            }
            require(levels == levels.sorted()) { "Fibonacci levels must be ascending (got $levels)" }
            require(levels.distinct().size == levels.size) { "Fibonacci levels must be unique (got $levels)" }
        }
    }
}
