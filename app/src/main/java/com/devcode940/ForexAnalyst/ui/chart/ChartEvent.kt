package com.devcode940.ForexAnalyst.ui.chart

import com.devcode940.ForexAnalyst.data.chart.ChartViewport
import com.devcode940.ForexAnalyst.data.chart.CrosshairData
import com.devcode940.ForexAnalyst.data.model.ChartOverlay
import com.devcode940.ForexAnalyst.data.model.ChartPoint
import com.devcode940.ForexAnalyst.data.model.ChartStyle
import com.devcode940.ForexAnalyst.data.model.ChartTimeZone
import com.devcode940.ForexAnalyst.data.model.ChartToolMode
import com.devcode940.ForexAnalyst.data.model.DrawingId
import com.devcode940.ForexAnalyst.data.model.DrawingStyle
import com.devcode940.ForexAnalyst.data.model.MagnetMode

/**
 * Every interaction the chart surface can produce.
 *
 * WHY one sealed event type instead of a dozen callbacks: the canvas is a leaf
 * component with a single `onEvent` sink, so it cannot forget to wire one of ten
 * lambdas, and the ViewModel handles every case in one exhaustive `when` — adding a
 * tool or a toggle becomes a compile error until the handling exists.
 *
 * WHY points and not pixels: events carry data-space anchors ([ChartPoint]) so the
 * ViewModel never needs a canvas size, a coordinate system or a Compose type. All
 * pixel→data conversion happens in the renderer, which is the only place that knows
 * the layout.
 */
sealed interface ChartEvent {

    /** The user armed a tool from the toolbar. */
    data class ToolSelected(val tool: ChartToolMode) : ChartEvent

    /** The user toggled an overlay or sub-pane. */
    data class OverlayToggled(val overlay: ChartOverlay) : ChartEvent

    /** The user picked a candle rendering style. */
    data class StyleSelected(val style: ChartStyle) : ChartEvent

    /** The user picked the time zone the axis is styled in. */
    data class TimeZoneSelected(val timeZone: ChartTimeZone) : ChartEvent

    /** The user changed anchor snapping. */
    data class MagnetSelected(val magnet: MagnetMode) : ChartEvent

    /** The user picked the colour new drawings will use. */
    data class ColorSelected(val colorArgb: Int) : ChartEvent

    /** The user picked the line style new drawings will use. */
    data class LineStyleSelected(val style: DrawingStyle) : ChartEvent

    /** Touch-down while a drawing tool is armed. */
    data class DraftStarted(val anchor: ChartPoint) : ChartEvent

    /** Finger moved while drawing. */
    data class DraftMoved(val anchor: ChartPoint) : ChartEvent

    /** Release: commit the drawing. */
    data class DraftFinished(val anchor: ChartPoint) : ChartEvent

    /** The gesture was cancelled (e.g. by a competing gesture or a tool switch). */
    data object DraftCancelled : ChartEvent

    /** The user tapped a drawing (or tapped empty space to deselect). */
    data class DrawingSelected(val id: DrawingId?) : ChartEvent

    /** The user deleted the selected drawing. */
    data class DrawingDeleted(val id: DrawingId) : ChartEvent

    /** Undo the last drawing mutation. */
    data object Undo : ChartEvent

    /** Redo the last undone mutation. */
    data object Redo : ChartEvent

    /** Delete every drawing on the current chart. */
    data object ClearDrawings : ChartEvent

    /** Crosshair moved to a bar, or was cleared. */
    data class CrosshairChanged(val crosshair: CrosshairData?) : ChartEvent

    /** Pinch/pan changed the viewport. */
    data class ViewportChanged(val viewport: ChartViewport) : ChartEvent

    /** Double-tap reset the viewport. */
    data object ViewportReset : ChartEvent
}
