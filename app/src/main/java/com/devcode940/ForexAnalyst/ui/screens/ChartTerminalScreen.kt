package com.devcode940.ForexAnalyst.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.devcode940.ForexAnalyst.data.chart.TimeAxis
import com.devcode940.ForexAnalyst.data.model.PriceFormatter
import com.devcode940.ForexAnalyst.data.model.CandleStick
import com.devcode940.ForexAnalyst.data.model.Timeframe
import com.devcode940.ForexAnalyst.data.model.TradingSession
import com.devcode940.ForexAnalyst.data.settings.ThemeMode
import com.devcode940.ForexAnalyst.ui.chart.ChartColors
import com.devcode940.ForexAnalyst.ui.chart.ChartUiState
import com.devcode940.ForexAnalyst.ui.components.CandlestickChartCanvas
import com.devcode940.ForexAnalyst.ui.components.ChartToolbar
import com.devcode940.ForexAnalyst.ui.theme.BearishRed
import com.devcode940.ForexAnalyst.ui.theme.BullishGreen
import com.devcode940.ForexAnalyst.ui.viewmodel.ForexViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChartTerminalScreen(
    viewModel: ForexViewModel,
    onNavigateToWatchlist: () -> Unit,
    onNavigateToAlerts: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToAiAnalyst: () -> Unit
) {
    val selectedPair by viewModel.selectedPair.collectAsState()
    val selectedTimeframe by viewModel.selectedTimeframe.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val appSettings by viewModel.appSettings.collectAsState()
    // WHY the chart reads one snapshot instead of four flows: the canvas needs the
    // candles, the indicators, the display config and the drawings to describe the
    // same moment. Collecting them separately lets a recomposition observe the new
    // config with the old candles, which is the flicker users report as "the chart
    // jumped when I toggled RSI".
    val chartState by viewModel.chartState.collectAsState()
    val drawingHistory by viewModel.drawingHistory.collectAsState()

    // WHY resolve the theme here rather than calling isSystemInDarkTheme() alone: the
    // app can be pinned to light or dark in Settings, and a chart that followed the
    // system while the rest of the app followed the setting would be the one surface
    // that disagrees with everything around it.
    val systemDark = isSystemInDarkTheme()
    val isDark = when (appSettings.themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val chartColors = remember(isDark) { ChartColors.forTheme(isDark) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = selectedPair.value,
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                actions = {
                    IconButton(onClick = onNavigateToAiAnalyst) {
                        Icon(Icons.Default.Info, contentDescription = "AI Analyst")
                    }
                    IconButton(onClick = onNavigateToAlerts) {
                        Icon(Icons.Default.Notifications, contentDescription = "Alerts")
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        bottomBar = {
            TimeframeSelector(
                selected = selectedTimeframe,
                onSelected = { viewModel.selectTimeframe(it) }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNavigateToWatchlist,
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ) {
                Icon(Icons.Default.Star, contentDescription = "Watchlist")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // WHY: Show load failures instead of a silent empty chart —
            // the message is actionable (tells the user what to do next).
            val error = errorMessage
            if (error != null) {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            ChartToolbar(
                state = chartState,
                history = drawingHistory,
                onEvent = viewModel::onChartEvent
            )

            CandlestickChartCanvas(
                state = chartState,
                colors = chartColors,
                onEvent = viewModel::onChartEvent,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    // WHY a spoken summary: a Canvas is opaque to TalkBack, so without
                    // this the whole chart is one silent rectangle. The description
                    // names the pair, the timeframe and the crossed candle — the same
                    // three things a sighted user reads first.
                    .semantics { contentDescription = chartContentDescription(chartState) }
            )

            ChartReadoutBar(state = chartState)

            SignalSummaryBar(viewModel = viewModel)
        }
    }
}

/**
 * Always-on text readout under the chart.
 *
 * WHY it exists even though the canvas draws a tooltip: the canvas tooltip only
 * appears while a finger is down, and it is invisible to screen readers. This row is
 * real text — selectable, readable aloud, and it keeps the last price on screen after
 * the touch ends.
 *
 * WHY the digits come from the instrument: JPY pairs quote 3 decimals, metals 2, and
 * most FX 5. A single hardcoded `%.5f` (the previous behaviour) quoted USDJPY as
 * 151.50000 and gold as 2350.00000, which reads as ignorance of the instrument.
 */
@Composable
private fun ChartReadoutBar(state: ChartUiState) {
    val candle = state.crosshair?.candle ?: state.candles.lastOrNull()
    val digits = state.digits
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            if (candle == null) {
                Text(
                    text = if (state.isLoading) "Loading ${state.pair.value}…" else "No data for ${state.pair.value}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                return@Column
            }
            val change = candle.close - candle.open
            val isUp = change >= 0.0
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = PriceFormatter.format(candle.close, digits),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isUp) BullishGreen else BearishRed
                )
                Text(
                    text = "${PriceFormatter.formatSigned(change, digits)}  " +
                        PriceFormatter.formatPips(change, state.instrument),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isUp) BullishGreen else BearishRed
                )
            }
            Text(
                text = readoutDetail(state, candle, digits),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * The detail line: which candle is being read, in the chart's own time zone, plus the
 * sessions that were open.
 */
private fun readoutDetail(
    state: ChartUiState,
    candle: CandleStick,
    digits: Int
): String {
    val zone = state.config.timeZone
    val whenLabel = TimeAxis.detailedLabel(candle.time, zone.resolve())
    val sessions = TradingSession.activeAt(candle.time)
    val sessionLabel = if (sessions.isEmpty()) "market closed" else sessions.joinToString(" + ") { it.displayName }
    val source = if (state.crosshair != null) "Crosshair" else "Last bar"
    return buildString {
        append(source).append(": ").append(whenLabel).append("  ·  ").append(zone.displayName).append(" time")
        append("  ·  ").append(sessionLabel)
        append("  ·  O ").append(PriceFormatter.format(candle.open, digits))
        append(" H ").append(PriceFormatter.format(candle.high, digits))
        append(" L ").append(PriceFormatter.format(candle.low, digits))
        append(" C ").append(PriceFormatter.format(candle.close, digits))
    }
}

/**
 * Spoken description of the chart for accessibility services.
 *
 * WHY built from the same values the readout shows: one description that disagrees
 * with the visible numbers is worse than none, because it cannot be cross-checked.
 */
private fun chartContentDescription(state: ChartUiState): String {
    val candle = state.crosshair?.candle ?: state.candles.lastOrNull() ?: return "${state.pair.value} chart, no data"
    val digits = state.digits
    val change = candle.close - candle.open
    return buildString {
        append(state.pair.value).append(" ").append(state.timeframe.value).append(" chart, ")
        append(state.config.style.displayName).append(", times in ").append(state.config.timeZone.displayName)
        append(". Bar at ").append(TimeAxis.detailedLabel(candle.time, state.config.timeZone.resolve()))
        append(": open ").append(PriceFormatter.format(candle.open, digits))
        append(", high ").append(PriceFormatter.format(candle.high, digits))
        append(", low ").append(PriceFormatter.format(candle.low, digits))
        append(", close ").append(PriceFormatter.format(candle.close, digits))
        append(", ").append(if (change >= 0) "up" else "down").append(" ")
        append(PriceFormatter.formatPips(change, state.instrument))
        append(". ").append(state.drawings.size).append(" drawn objects")
    }
}

@Composable
private fun TimeframeSelector(
    selected: Timeframe,
    onSelected: (Timeframe) -> Unit
) {
    // WHY: read the supported set from the domain type instead of a hardcoded
    // UI list — the two used to disagree (M1/W1 were valid but unselectable).
    val timeframes = Timeframe.ORDERED
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        for (tf in timeframes) {
            FilterChip(
                selected = tf == selected.value,
                onClick = { onSelected(Timeframe(tf)) },
                label = { Text(tf) }
            )
        }
    }
}

@Composable
private fun SignalSummaryBar(viewModel: ForexViewModel) {
    val signals by viewModel.signals.collectAsState()
    val selectedTimeframe by viewModel.selectedTimeframe.collectAsState()
    // WHY: the DAO stream is filtered by pair only; without this filter the
    // summary bar advertises an H1 signal while the chart shows D1 candles,
    // which reads as a wrong price level to the user.
    val latest = signals.firstOrNull { it.timeframe == selectedTimeframe }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        if (latest == null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No active signals",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = latest.signalType.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (latest.signalType.name == "BUY") BullishGreen else BearishRed
                    )
                    Text(
                        // WHY instrument-aware digits: this bar shows levels for
                        // whichever pair is selected, and USDJPY at five decimals
                        // (151.50000) reads as a bug to anyone who trades it.
                        text = "Entry: ${PriceFormatter.format(latest.entryPrice, latest.pairSymbol)}",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "SL: ${PriceFormatter.format(latest.stopLoss, latest.pairSymbol)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = BearishRed
                    )
                    Text(
                        text = "TP: ${PriceFormatter.format(latest.takeProfit1, latest.pairSymbol)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = BullishGreen
                    )
                    Text(
                        text = "R:R ${PriceFormatter.format(latest.riskRewardRatio, 2)} · ${(latest.confidenceScore * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}