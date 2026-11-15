package com.devcode940.ForexAnalyst.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.devcode940.ForexAnalyst.data.chart.HistoryAvailability
import com.devcode940.ForexAnalyst.data.model.ChartOverlay
import com.devcode940.ForexAnalyst.data.model.ChartStyle
import com.devcode940.ForexAnalyst.data.model.ChartTimeZone
import com.devcode940.ForexAnalyst.data.model.ChartToolMode
import com.devcode940.ForexAnalyst.data.model.DrawingPalette
import com.devcode940.ForexAnalyst.data.model.DrawingStyle
import com.devcode940.ForexAnalyst.data.model.MagnetMode
import com.devcode940.ForexAnalyst.ui.chart.ChartEvent
import com.devcode940.ForexAnalyst.ui.chart.ChartUiState

/**
 * The chart's tool rail and display controls.
 *
 * WHY two always-visible rows plus one collapsible panel: a phone chart is already
 * short. The tools a trader reaches for mid-analysis (draw, undo, delete) stay one tap
 * away, while the settings that are touched once per session (overlays, colours, line
 * style, magnet) live behind a **Display** toggle so they do not permanently steal
 * vertical space from the price action.
 *
 * WHY chips with text and not icons only: the icon set available here is the Material
 * core set, which has no trendline/fibonacci/ray glyphs. Inventing them from
 * unrelated icons would be worse than a short label — and a label is readable by a
 * screen reader without extra work.
 *
 * @param state chart snapshot; only the fields the toolbar shows are read.
 * @param history undo/redo availability.
 * @param onEvent single interaction sink, shared with the canvas.
 * @param modifier layout modifier.
 */
@Composable
fun ChartToolbar(
    state: ChartUiState,
    history: HistoryAvailability,
    onEvent: (ChartEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    var displayExpanded by remember { mutableStateOf(false) }
    var styleMenuOpen by remember { mutableStateOf(false) }
    var zoneMenuOpen by remember { mutableStateOf(false) }
    var magnetMenuOpen by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // WHY the rail is driven by the enum: adding a tool to
            // [ChartToolMode.TOOLBAR] puts it in the UI with no further change, so the
            // toolbar can never fall behind the model.
            ChartToolMode.TOOLBAR.forEach { tool ->
                FilterChip(
                    selected = state.activeTool == tool,
                    onClick = { onEvent(ChartEvent.ToolSelected(tool)) },
                    label = { Text(tool.shortName, style = MaterialTheme.typography.labelSmall) }
                )
            }

            Spacer(Modifier.width(4.dp))

            FilterChip(
                selected = false,
                enabled = history.canUndo,
                onClick = { onEvent(ChartEvent.Undo) },
                label = { Text("Undo", style = MaterialTheme.typography.labelSmall) }
            )
            FilterChip(
                selected = false,
                enabled = history.canRedo,
                onClick = { onEvent(ChartEvent.Redo) },
                label = { Text("Redo", style = MaterialTheme.typography.labelSmall) }
            )
            FilterChip(
                selected = state.selectedDrawingId != null,
                enabled = state.selectedDrawingId != null,
                onClick = { state.selectedDrawingId?.let { onEvent(ChartEvent.DrawingDeleted(it)) } },
                label = { Text("Delete", style = MaterialTheme.typography.labelSmall) }
            )
            FilterChip(
                selected = false,
                enabled = state.drawings.isNotEmpty(),
                onClick = { onEvent(ChartEvent.ClearDrawings) },
                label = { Text("Clear", style = MaterialTheme.typography.labelSmall) }
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = displayExpanded,
                onClick = { displayExpanded = !displayExpanded },
                label = { Text("Display", style = MaterialTheme.typography.labelSmall) }
            )

            // WHY menus instead of chip rows for these three: each has 6-8 mutually
            // exclusive options, and a row of that many chips would need its own
            // scroll inside a scroll.
            Box {
                FilterChip(
                    selected = false,
                    onClick = { styleMenuOpen = true },
                    label = { Text(state.config.style.displayName, style = MaterialTheme.typography.labelSmall) }
                )
                DropdownMenu(expanded = styleMenuOpen, onDismissRequest = { styleMenuOpen = false }) {
                    ChartStyle.entries.forEach { style ->
                        DropdownMenuItem(
                            text = { Text(style.displayName) },
                            onClick = {
                                onEvent(ChartEvent.StyleSelected(style))
                                styleMenuOpen = false
                            }
                        )
                    }
                }
            }

            Box {
                FilterChip(
                    selected = false,
                    onClick = { zoneMenuOpen = true },
                    // WHY the badge and not the display name: the chip sits next to the
                    // time axis it controls, so `london` reads as "this axis is London
                    // time" without repeating the word "zone".
                    label = { Text("Time ${state.config.timeZone.badge()}", style = MaterialTheme.typography.labelSmall) }
                )
                DropdownMenu(expanded = zoneMenuOpen, onDismissRequest = { zoneMenuOpen = false }) {
                    ChartTimeZone.entries.forEach { zone ->
                        DropdownMenuItem(
                            text = { Text(zone.displayName) },
                            onClick = {
                                onEvent(ChartEvent.TimeZoneSelected(zone))
                                zoneMenuOpen = false
                            }
                        )
                    }
                }
            }

            Box {
                FilterChip(
                    selected = false,
                    onClick = { magnetMenuOpen = true },
                    label = { Text("Magnet ${state.config.magnet.displayName}", style = MaterialTheme.typography.labelSmall) }
                )
                DropdownMenu(expanded = magnetMenuOpen, onDismissRequest = { magnetMenuOpen = false }) {
                    MagnetMode.entries.forEach { magnet ->
                        DropdownMenuItem(
                            text = { Text(magnet.displayName) },
                            onClick = {
                                onEvent(ChartEvent.MagnetSelected(magnet))
                                magnetMenuOpen = false
                            }
                        )
                    }
                }
            }
        }

        if (displayExpanded) {
            DisplayPanel(state = state, onEvent = onEvent)
        }

        // WHY a one-line status: it tells the user what the armed tool expects
        // ("drag between two points") before they discover it by failing.
        Text(
            text = toolbarHint(state),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
        )
    }
}

/** Overlays, sub-panes, drawing colour and line style. */
@Composable
private fun DisplayPanel(state: ChartUiState, onEvent: (ChartEvent) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // WHY one loop over the enum: overlays and sub-panes differ only in where
            // they are drawn, so the toggle UI is identical and must not be written
            // twice (two lists is how one of them stops being persisted).
            ChartOverlay.entries.forEach { overlay ->
                FilterChip(
                    selected = state.config.isOn(overlay),
                    onClick = { onEvent(ChartEvent.OverlayToggled(overlay)) },
                    label = { Text(overlay.displayName, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Line", style = MaterialTheme.typography.labelSmall)
            DrawingStyle.entries.forEach { style ->
                FilterChip(
                    selected = state.pendingStyle == style,
                    onClick = { onEvent(ChartEvent.LineStyleSelected(style)) },
                    label = { Text(style.displayName, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Colour", style = MaterialTheme.typography.labelSmall)
            DrawingPalette.SWATCHES.forEach { swatch ->
                ColorSwatch(
                    argb = swatch.argb,
                    selected = state.pendingColorArgb == swatch.argb,
                    onClick = { onEvent(ChartEvent.ColorSelected(swatch.argb)) }
                )
            }
        }
    }
}

/**
 * One palette swatch.
 *
 * WHY a `contentDescription`: a row of identical circles is completely unusable with a
 * screen reader unless each one names its colour.
 */
@Composable
private fun ColorSwatch(argb: Int, selected: Boolean, onClick: () -> Unit) {
    val name = remember(argb) { colorName(argb) }
    Box(
        modifier = Modifier
            .size(24.dp)
            .background(Color(argb), CircleShape)
            // WHY a border only when selected: a ring around every swatch would make
            // the palette look like a set of already-chosen values.
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.outline,
                shape = CircleShape
            )
            .clickable(onClick = onClick)
            .semantics { contentDescription = name }
    )
}

/**
 * Human name for a palette colour.
 *
 * WHY a lookup rather than a positional list: the names live on
 * [DrawingPalette.Swatch] next to the values they describe, so a reordered palette
 * cannot mislabel a swatch. An unknown value (a colour restored from storage that is
 * no longer in the palette) falls back to a generic description instead of crashing.
 */
private fun colorName(argb: Int): String =
    DrawingPalette.SWATCHES.firstOrNull { it.argb == argb }?.name ?: "Custom colour"

/**
 * One line telling the user what the armed tool expects.
 *
 * WHY: the difference between "tap to place" and "drag between two points" is not
 * guessable, and the cost of guessing wrong is a chart full of accidental objects.
 */
private fun toolbarHint(state: ChartUiState): String {
    if (state.drawings.isNotEmpty() && state.activeTool == ChartToolMode.NONE) {
        return "${state.drawings.size} object(s) on this chart · tap one to select it"
    }
    return when (state.activeTool) {
        ChartToolMode.NONE -> "Tap the chart to read a candle"
        ChartToolMode.PAN -> "Drag to pan · pinch to zoom · double-tap to reset"
        ChartToolMode.HORIZONTAL_LINE, ChartToolMode.VERTICAL_LINE ->
            "Tap to place the ${state.activeTool.displayName.lowercase()}"
        ChartToolMode.LONG_POSITION, ChartToolMode.SHORT_POSITION ->
            "Drag from the entry to the stop distance"
        else -> "Drag between two points"
    }
}
