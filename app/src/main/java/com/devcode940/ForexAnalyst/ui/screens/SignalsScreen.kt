package com.devcode940.ForexAnalyst.ui.screens

import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.devcode940.ForexAnalyst.data.model.SignalType
import com.devcode940.ForexAnalyst.ui.theme.BearishRed
import com.devcode940.ForexAnalyst.ui.theme.BullishGreen
import com.devcode940.ForexAnalyst.ui.viewmodel.ForexViewModel

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
                title = { Text("Trade Signals") },
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
                Text("No signals detected yet.")
            }
        } else {
            LazyColumn(modifier = Modifier.padding(padding)) {
                items(signals) { signal ->
                    val isBuy = signal.signalType == SignalType.BUY
                    ListItem(
                        headlineContent = {
                            Text("${signal.signalType.name} ${signal.pairSymbol.value}")
                        },
                        supportingContent = {
                            Text(
                                "Entry ${"%.5f".format(signal.entryPrice)} · " +
                                    "SL ${"%.5f".format(signal.stopLoss)} · " +
                                    "TP ${"%.5f".format(signal.takeProfit1)} · " +
                                    "R:R ${"%.2f".format(signal.riskRewardRatio)}"
                            )
                        },
                        leadingContent = {
                            Icon(
                                imageVector = if (isBuy) {
                                    Icons.Default.KeyboardArrowUp
                                } else {
                                    Icons.Default.KeyboardArrowDown
                                },
                                contentDescription = null,
                                tint = if (isBuy) BullishGreen else BearishRed
                            )
                        }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}