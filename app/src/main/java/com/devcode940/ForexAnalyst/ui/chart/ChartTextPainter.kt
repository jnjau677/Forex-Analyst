package com.devcode940.ForexAnalyst.ui.chart

import android.graphics.Paint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density

/**
 * Horizontal anchor for a canvas text draw.
 *
 * WHY an explicit enum: `Paint.setTextAlign` is mutable state on a shared object.
 * Setting it per call and never restoring it is how a right-aligned axis label ends
 * up centring the next tooltip. Every helper here sets the alignment it needs and
 * computes the offset from the measured width instead of relying on the paint's
 * remembered alignment.
 */
internal enum class TextAnchor { START, CENTER, END }

/**
 * Reusable [Paint] instances and text helpers for the chart canvas.
 *
 * WHY not Compose `TextMeasurer`: measuring inside a `DrawScope` allocates a layout
 * per string, and the chart draws ~40 labels every frame while panning. A single
 * `android.graphics.Paint` measures with no allocation and is what the rest of the
 * Android drawing stack uses.
 *
 * WHY created once and remembered: `Paint` construction is cheap but not free, and
 * allocating six of them per frame during a 60 fps pan shows up as GC churn — the
 * classic cause of the "chart stutters only while dragging" complaint.
 *
 * @property density used to convert sp/dp-ish sizes into pixels exactly once.
 */
internal class ChartTextPainter(density: Density) {

    private val scale = density.density

    /** Time-axis labels along the bottom. */
    val axis: Paint = paint(textSizePx = AXIS_TEXT_SP, color = Color.White)

    /** Price-axis labels on the right. */
    val price: Paint = paint(textSizePx = AXIS_TEXT_SP, color = Color.White)

    /** Small labels inside panes (indicator headers, session names). */
    val paneHeader: Paint = paint(textSizePx = PANE_HEADER_SP, color = Color.White)

    /** Text inside a filled tag (last price, crosshair price/time). */
    val tag: Paint = paint(textSizePx = AXIS_TEXT_SP, color = Color.White, bold = true)

    /** Crosshair tooltip body. */
    val tooltip: Paint = paint(textSizePx = TOOLTIP_TEXT_SP, color = Color.White)

    /** Crosshair tooltip OHLC row (tabular figures matter here). */
    val tooltipMono: Paint = paint(textSizePx = TOOLTIP_TEXT_SP, color = Color.White, monospace = true)

    /** Text drawn on top of user drawings (level labels, measurement readout). */
    val drawingLabel: Paint = paint(textSizePx = DRAWING_LABEL_SP, color = Color.White)

    /**
     * Draws [text] into [scope] with [paint] recoloured to [color].
     *
     * WHY recolour per call: one paint per *style*, not per colour, keeps the paint
     * count fixed while still letting a session label be grey and a crosshair tag be
     * white.
     *
     * WHY the anchor is applied by measuring instead of `Paint.setTextAlign`: that
     * setter is mutable state on a shared object, and forgetting to restore it is how
     * a right-aligned axis label ends up centring the next tooltip.
     */
    fun draw(
        scope: DrawScope,
        text: String,
        x: Float,
        y: Float,
        paint: Paint,
        color: Color,
        anchor: TextAnchor = TextAnchor.START
    ) {
        if (text.isEmpty()) return
        val argb = color.toArgb()
        if (paint.color != argb) paint.color = argb
        val measured = paint.measureText(text)
        val drawX = when (anchor) {
            TextAnchor.START -> x
            TextAnchor.CENTER -> x - measured / 2f
            TextAnchor.END -> x - measured
        }
        scope.drawIntoCanvas { canvas -> canvas.nativeCanvas.drawText(text, drawX, y, paint) }
    }

    /** Pixel width of [text] in [paint]; used to size tags and tooltips. */
    fun measure(text: String, paint: Paint): Float = paint.measureText(text)

    private fun paint(
        textSizePx: Float,
        color: Color,
        bold: Boolean = false,
        monospace: Boolean = false
    ): Paint = Paint().apply {
        isAntiAlias = true
        isSubpixelText = true
        this.textSize = textSizePx * scale
        this.color = color.toArgb()
        // WHY bold only on tags: a tag sits on a filled background and needs weight
        // to stay legible, while axis labels at the same weight crowd each other.
        isFakeBoldText = bold
        if (monospace) typeface = android.graphics.Typeface.MONOSPACE
    }

    companion object {
        private const val AXIS_TEXT_SP = 10.5f
        private const val PANE_HEADER_SP = 10f
        private const val TOOLTIP_TEXT_SP = 11f
        private const val DRAWING_LABEL_SP = 10.5f

        /**
         * Baseline that visually centres text of [paint] on [centerY].
         *
         * WHY ascent/descent and not `paint.fontMetrics`: the latter allocates a
         * `FontMetrics` object on every call, and this runs once per axis label per
         * frame. `ascent()`/`descent()` are allocation-free.
         *
         * WHY it exists at all: `drawText` takes a *baseline*, so passing a centre y
         * directly renders every label shifted up by half a line.
         */
        fun baselineFor(centerY: Float, paint: Paint): Float =
            centerY - (paint.ascent() + paint.descent()) / 2f
    }
}
