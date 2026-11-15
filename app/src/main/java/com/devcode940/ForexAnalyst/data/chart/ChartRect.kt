package com.devcode940.ForexAnalyst.data.chart

import kotlin.math.max
import kotlin.math.min

/**
 * An axis-aligned rectangle in canvas pixels.
 *
 * WHY a plain Kotlin type and not `androidx.compose.ui.geometry.Rect`: everything
 * in `data.chart` is pure JVM code so it can be unit-tested (and reason about)
 * without Compose on the classpath. The renderer converts to Compose geometry at
 * the draw boundary only.
 *
 * Coordinates are normalized in [init], so `left <= right` and `top <= bottom`
 * always hold — a renderer can therefore never receive an inverted rect from a
 * drag that went right-to-left.
 */
data class ChartRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    private val normalizedLeft: Float = min(left, right)
    private val normalizedTop: Float = min(top, bottom)
    private val normalizedRight: Float = max(left, right)
    private val normalizedBottom: Float = max(top, bottom)

    /** Left edge (always <= [rightEdge]). */
    val leftEdge: Float get() = normalizedLeft

    /** Top edge (always <= [bottomEdge]). */
    val topEdge: Float get() = normalizedTop

    /** Right edge. */
    val rightEdge: Float get() = normalizedRight

    /** Bottom edge. */
    val bottomEdge: Float get() = normalizedBottom

    /** Width, never negative. */
    val width: Float get() = normalizedRight - normalizedLeft

    /** Height, never negative. */
    val height: Float get() = normalizedBottom - normalizedTop

    /** True when the rect has a usable area. */
    val isUsable: Boolean get() = width > 0f && height > 0f

    /** True when [x],[y] lies inside the rect. */
    fun contains(x: Float, y: Float): Boolean =
        x in normalizedLeft..normalizedRight && y in normalizedTop..normalizedBottom

    /** True when [x] lies inside the horizontal span. */
    fun containsX(x: Float): Boolean = x in normalizedLeft..normalizedRight

    /** A copy inset by [amount] on every side; never inverts. */
    fun inset(amount: Float): ChartRect {
        val horizontal = min(amount, width / 2f)
        val vertical = min(amount, height / 2f)
        return ChartRect(
            left = normalizedLeft + horizontal,
            top = normalizedTop + vertical,
            right = normalizedRight - horizontal,
            bottom = normalizedBottom - vertical
        )
    }

    companion object {
        /** Builds a rect from an origin and a size. */
        fun of(left: Float, top: Float, width: Float, height: Float): ChartRect =
            ChartRect(left, top, left + width, top + height)

        /** The empty rect. */
        val EMPTY = ChartRect(0f, 0f, 0f, 0f)
    }
}
