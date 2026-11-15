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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChartTerminalScreen(
    viewModel: ForexViewModel,
    onNavigateToWatchlist: () -> Unit,
    onNavigateToAlerts: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToAiAnalyst: () -> Unit,
    onRequestNotificationPermission: () -> Unit
) {
    val selectedPair by viewModel.selectedPair.collectAsState()
    val candles by viewModel.candles.collectAsState()
    val indicators by viewModel.indicators.collectAsState()
    val selectedTimeframe by viewModel.selectedTimeframe.collectAsState()
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

            CandlestickChartCanvas(
                candles = candles,
                indicators = indicators,
                onCrosshairUpdate = { crosshairData = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )

            SignalSummaryBar(viewModel = viewModel)
        }
    }
}

@Composable
private fun CrosshairTooltip(data: CrosshairData) {
    Surface(
        modifier = Modifier.padding(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text(
                text = "Price: ${"%.5f".format(data.price)}",
                style = MaterialTheme.typography.labelSmall
            )
            val c = data.candle
            if (c != null) {
                Text(
                    text = "O ${"%.5f".format(c.open)}  H ${"%.5f".format(c.high)}",
                    style = MaterialTheme.typography.labelSmall
                )
                Text(
                    text = "L ${"%.5f".format(c.low)}  C ${"%.5f".format(c.close)}",
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
    val timeframes = listOf("M5", "M15", "H1", "H4", "D1")
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
    val latest = signals.firstOrNull()

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
                        text = "Entry: ${"%.5f".format(latest.entryPrice)}",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "SL: ${"%.5f".format(latest.stopLoss)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = BearishRed
                    )
                    Text(
                        text = "TP: ${"%.5f".format(latest.takeProfit1)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = BullishGreen
                    )
                    Text(
                        text = "R:R ${"%.2f".format(latest.riskRewardRatio)} · ${(latest.confidenceScore * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}