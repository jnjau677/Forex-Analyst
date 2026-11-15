package com.devcode940.ForexAnalyst.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import com.devcode940.ForexAnalyst.data.model.ChartPoint
import com.devcode940.ForexAnalyst.ui.chart.ChartColors
import com.devcode940.ForexAnalyst.ui.chart.ChartEvent
import com.devcode940.ForexAnalyst.ui.chart.ChartFrame
import com.devcode940.ForexAnalyst.ui.chart.ChartGestureMath
import com.devcode940.ForexAnalyst.ui.chart.ChartRenderer
import com.devcode940.ForexAnalyst.ui.chart.ChartTextPainter
import com.devcode940.ForexAnalyst.ui.chart.ChartUiState

/**
 * The interactive price chart.
 *
 * WHY a `BoxWithConstraints` wrapping the `Canvas` rather than a bare `Canvas`: the
 * gesture handlers must convert a touch position into (time, price), which needs the
 * same coordinate mapping the painter used. Building the frame in composition makes
 * that one mapping available to both, so a tap can never land on a different price
 * than the one painted under the finger — the bug that appears the moment the gesture
 * layer keeps its own copy of the scale.
 *
 * WHY the frame is `remember`ed on the whole state: it is a pure function of
 * (state, palette, size). Recomputing only when one of those changes keeps panning at
 * 60 fps while still guaranteeing painter and hit-tester agree.
 *
 * WHY the gesture detectors are keyed on as little as possible: `pointerInput` restarts
 * its coroutine whenever a key changes, and a restart mid-gesture silently cancels the
 * drag. The detectors therefore key only on the tool, and read the current frame and
 * state through `rememberUpdatedState` so they always see fresh values.
 *
 * @param state everything to draw for this frame.
 * @param colors palette for the active theme.
 * @param onEvent single sink for every interaction; see [ChartEvent] for why one sink
 *   beats a dozen optional lambdas.
 * @param modifier layout modifier.
 */
@Composable
fun CandlestickChartCanvas(
    state: ChartUiState,
    colors: ChartColors,
    onEvent: (ChartEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val currentOnEvent by rememberUpdatedState(onEvent)

    BoxWithConstraints(modifier = modifier) {
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }

        // WHY keyed on density.density: text sizes are resolved to pixels once at
        // construction, so a move to a display with a different density must rebuild
        // the paints or every label renders at the wrong size.
        val textPainter = remember(density.density) { ChartTextPainter(density) }

        val frame = remember(state, colors, widthPx, heightPx) {
            ChartFrame.build(state, colors, widthPx, heightPx)
        }
        val currentFrame by rememberUpdatedState(frame)
        val currentState by rememberUpdatedState(state)

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                // WHY two independent detectors instead of one: a drag handler and a
                // tap handler cannot share a single `pointerInput` block (the first
                // suspends forever), and stacking them is the standard way to get both
                // "tap to place" and "drag to size" on the same surface.
                .pointerInput(state.activeTool.isDrawingTool) {
                    if (state.activeTool.isDrawingTool) {
                        detectDrawingDrag(
                            frameProvider = { currentFrame },
                            stateProvider = { currentState },
                            onEvent = { event -> currentOnEvent(event) }
                        )
                    } else {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val next = ChartGestureMath.nextViewport(currentFrame, pan.x, zoom)
                            // WHY the equality check lives in nextViewport: emitting an
                            // unchanged viewport re-runs the whole state pipeline for
                            // nothing, which on a trackpad-sized gesture stream is a
                            // visible stutter.
                            if (next != currentState.viewport) {
                                currentOnEvent(ChartEvent.ViewportChanged(next))
                            }
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { position -> handleTap(currentFrame, currentState, currentOnEvent, position) },
                        onDoubleTap = {
                            if (!currentState.activeTool.isDrawingTool) {
                                currentOnEvent(ChartEvent.ViewportReset)
                            }
                        }
                    )
                }
        ) {
            ChartRenderer.render(this, frame, textPainter)
        }
    }
}

/**
 * Drag handler for an armed drawing tool.
 *
 * WHY one-anchor tools are ignored here: they commit on tap, and letting a drag also
 * emit a draft would place the level twice — once on touch-down and once on release.
 *
 * WHY the last anchor is tracked locally: `onDragEnd` receives no position, and a
 * drawing must be committed at the point the finger actually lifted, not at the point
 * the gesture started.
 */
private suspend fun PointerInputScope.detectDrawingDrag(
    frameProvider: () -> ChartFrame,
    stateProvider: () -> ChartUiState,
    onEvent: (ChartEvent) -> Unit
) {
    var lastAnchor: ChartPoint? = null
    detectDragGestures(
        onDragStart = { position ->
            val state = stateProvider()
            if (!state.activeTool.isTwoAnchor) return@detectDragGestures
            val anchor = ChartGestureMath.chartPointAt(frameProvider(), position) ?: return@detectDragGestures
            lastAnchor = anchor
            onEvent(ChartEvent.DraftStarted(anchor))
        },
        onDrag = { change, _ ->
            // WHY consume: without it the parent scrollable (the chart sits inside a
            // vertical scroll on small screens) steals the movement and the drawing
            // stops following the finger halfway through.
            change.consume()
            val anchor = ChartGestureMath.chartPointAt(frameProvider(), change.position) ?: return@detectDragGestures
            lastAnchor = anchor
            onEvent(ChartEvent.DraftMoved(anchor))
        },
        onDragEnd = {
            lastAnchor?.let { onEvent(ChartEvent.DraftFinished(it)) }
            lastAnchor = null
        },
        onDragCancel = {
            // WHY cancel explicitly: an interrupted gesture must not leave a half-built
            // draft on the chart, which would otherwise be committed by the next
            // unrelated event.
            if (lastAnchor != null) onEvent(ChartEvent.DraftCancelled)
            lastAnchor = null
        }
    )
}

/**
 * Tap handler: place a one-anchor object, select a drawing, or move the crosshair.
 *
 * WHY selection wins over the crosshair in read mode: tapping a trendline to select it
 * and simultaneously jumping the crosshair to that spot is two answers to one gesture,
 * and the user cannot tell which one the app acted on.
 */
private fun handleTap(
    frame: ChartFrame,
    state: ChartUiState,
    onEvent: (ChartEvent) -> Unit,
    position: Offset
) {
    if (state.activeTool.isDrawingTool) {
        if (!state.activeTool.isTwoAnchor) {
            val anchor = ChartGestureMath.chartPointAt(frame, position) ?: return
            // WHY start-then-finish: the ViewModel commits a one-anchor tool on
            // DraftFinished, so a tap and a drag travel the same path and produce
            // identical objects.
            onEvent(ChartEvent.DraftStarted(anchor))
            onEvent(ChartEvent.DraftFinished(anchor))
        }
        return
    }

    val hit = ChartGestureMath.drawingHitAt(frame, position)
    if (hit != null) {
        onEvent(ChartEvent.DrawingSelected(if (hit.id == state.selectedDrawingId) null else hit.id))
        return
    }
    if (state.selectedDrawingId != null) onEvent(ChartEvent.DrawingSelected(null))
    onEvent(ChartEvent.CrosshairChanged(ChartGestureMath.crosshairAt(frame, position)))
}
