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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.devcode940.ForexAnalyst.data.model.Timeframe
import com.devcode940.ForexAnalyst.ui.components.CandlestickChartCanvas
import com.devcode940.ForexAnalyst.ui.components.CrosshairData
import com.devcode940.ForexAnalyst.ui.theme.BearishRed
import com.devcode940.ForexAnalyst.ui.theme.BullishGreen
import com.devcode940.ForexAnalyst.ui.viewmodel.ForexViewModel
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

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
    val candles by viewModel.candles.collectAsState()
    val indicators by viewModel.indicators.collectAsState()
    val selectedTimeframe by viewModel.selectedTimeframe.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val appSettings by viewModel.appSettings.collectAsState()
    var crosshairData by remember { mutableStateOf<CrosshairData?>(null) }

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
            val data = crosshairData
            if (data != null) {
                CrosshairTooltip(data = data)
            }

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

            CandlestickChartCanvas(
                candles = candles,
                indicators = indicators,
                // WHY: the EMA/RSI toggles in Settings were persisted but never
                // reached the chart, so both switches appeared broken.
                showEma = appSettings.showEma,
                showRsi = appSettings.showRsi,
                onCrosshairUpdate = { crosshairData = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )

            SignalSummaryBar(viewModel = viewModel)
        }
    }
}

// WHY: built once (formatter creation is expensive) and zoned to the device
// so a candle reads in the trader's local time, not UTC.
private val TOOLTIP_TIME_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.US).withZone(ZoneId.systemDefault())

@Composable
private fun CrosshairTooltip(data: CrosshairData) {
    Surface(
        modifier = Modifier.padding(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text(
                text = "Price: ${String.format(Locale.US, "%.5f", data.price)}",
                style = MaterialTheme.typography.labelSmall
            )
            val c = data.candle
            if (c != null) {
                // WHY: show WHICH candle the numbers belong to — the old
                // tooltip listed OHLC with no time, so the user could not tell
                // whether they were reading today's bar or last week's.
                Text(
                    text = TOOLTIP_TIME_FORMAT.format(c.time),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "O ${String.format(Locale.US, "%.5f", c.open)}  H ${String.format(Locale.US, "%.5f", c.high)}",
                    style = MaterialTheme.typography.labelSmall
                )
                Text(
                    text = "L ${String.format(Locale.US, "%.5f", c.low)}  C ${String.format(Locale.US, "%.5f", c.close)}",
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
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
                        text = "Entry: ${String.format(Locale.US, "%.5f", latest.entryPrice)}",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "SL: ${String.format(Locale.US, "%.5f", latest.stopLoss)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = BearishRed
                    )
                    Text(
                        text = "TP: ${String.format(Locale.US, "%.5f", latest.takeProfit1)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = BullishGreen
                    )
                    Text(
                        text = "R:R ${String.format(Locale.US, "%.2f", latest.riskRewardRatio)} · ${(latest.confidenceScore * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}