package com.devcode940.ForexAnalyst.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.devcode940.ForexAnalyst.data.model.PriceFormatter
import com.devcode940.ForexAnalyst.data.model.SignalType
import com.devcode940.ForexAnalyst.data.model.TradeSignal
import com.devcode940.ForexAnalyst.ui.theme.BearishRed
import com.devcode940.ForexAnalyst.ui.theme.BullishGreen
import com.devcode940.ForexAnalyst.ui.viewmodel.ForexViewModel
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// WHY: one formatter instance for the whole list — DateTimeFormatter is
// thread-safe and expensive to build, and creating it per row would allocate
// on every recomposition while scrolling.
private val SIGNAL_TIME_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.US).withZone(ZoneId.systemDefault())

/**
 * Read-only list of generated trade signals for the selected pair.
 *
 * @param viewModel source of the signal stream.
 * @param onBack navigation callback to the previous destination.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignalsScreen(
    viewModel: ForexViewModel,
    onBack: () -> Unit
) {
    val signals by viewModel.signals.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                // WHY: the count tells the user the list is complete rather
                // than still loading.
                title = { Text(if (signals.isEmpty()) "Trade Signals" else "Trade Signals (${signals.size})") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (signals.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                // WHY: "No signals detected yet." explains nothing. An empty
                // state must say what produces data and what to do next.
                Column(
                    modifier = Modifier.padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "No signals for this pair yet",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "A signal appears when RSI crosses below 30 or above 70 " +
                            "with MACD momentum confirming. Try a lower timeframe, or " +
                            "check back after the next candle closes.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        } else {
            LazyColumn(modifier = Modifier.padding(padding)) {
                // WHY: stable keys let Compose reuse row state and skip
                // rebinding untouched rows when a new signal is prepended —
                // without keys every emission rebinds the whole list.
                items(items = signals, key = { signal -> signal.id.value.toString() }) { signal ->
                    SignalRow(signal)
                    HorizontalDivider()
                }
            }
        }
    }
}

/** One signal row: direction, levels, confidence, age and rationale. */
@Composable
private fun SignalRow(signal: TradeSignal) {
    val isBuy = signal.signalType == SignalType.BUY
    ListItem(
        headlineContent = {
            // WHY: the timeframe is part of the signal's meaning — an H1 BUY
            // and a D1 BUY are different trades.
            Text("${signal.signalType.name} ${signal.pairSymbol.value} · ${signal.timeframe.value}")
        },
        supportingContent = {
            Column {
                // WHY PriceFormatter and not "%.5f": it is locale-stable (always a
                // dot) *and* instrument-aware, so USDJPY quotes three decimals and
                // gold two instead of everything being padded to five.
                Text(
                    "Entry ${PriceFormatter.format(signal.entryPrice, signal.pairSymbol)} · " +
                        "SL ${PriceFormatter.format(signal.stopLoss, signal.pairSymbol)} · " +
                        "TP ${PriceFormatter.format(signal.takeProfit1, signal.pairSymbol)} · " +
                        "R:R ${PriceFormatter.format(signal.riskRewardRatio, 2)}"
                )
                // WHY: confidence and the candle time were persisted but never
                // shown, so the user could not judge how stale a signal was.
                Text(
                    text = "${(signal.confidenceScore * 100).toInt()}% confidence · " +
                        SIGNAL_TIME_FORMAT.format(signal.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // WHY: show the reasoning, not just the numbers — a signal
                // without a rationale is not actionable.
                Text(
                    text = signal.summaryRationale,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        leadingContent = {
            Icon(
                imageVector = if (isBuy) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (isBuy) "Buy signal" else "Sell signal",
                tint = if (isBuy) BullishGreen else BearishRed
            )
        }
    )
}
