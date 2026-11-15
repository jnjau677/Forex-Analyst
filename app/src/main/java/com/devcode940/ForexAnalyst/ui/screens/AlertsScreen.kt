package com.devcode940.ForexAnalyst.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.devcode940.ForexAnalyst.data.model.AlertConditionType
import com.devcode940.ForexAnalyst.data.model.AlertId
import com.devcode940.ForexAnalyst.data.model.CustomAlert
import com.devcode940.ForexAnalyst.data.model.PriceFormatter
import com.devcode940.ForexAnalyst.ui.components.AlertMonitorBanner
import com.devcode940.ForexAnalyst.ui.viewmodel.ForexViewModel
import java.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertsScreen(
    viewModel: ForexViewModel,
    onBack: () -> Unit
) {
    val alerts by viewModel.alerts.collectAsState()
    val selectedPair by viewModel.selectedPair.collectAsState()
    var showCreateDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Alerts · ${selectedPair.value}") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showCreateDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "New alert")
                    }
                }
            )
        }
    ) { padding ->
        // WHY a Column wrapper: the monitoring banner has to be visible whether or not
        // there are alerts. A screen that only explains itself once the list is non-empty
        // is useless in the exact case that needs explaining - no alerts, no idea why.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            AlertMonitorBanner()

            if (alerts.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Notifications,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("No alerts yet. Tap + to create one.")
                    }
                }
            } else {
                LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                    items(alerts) { alert ->
                        ListItem(
                            headlineContent = {
                                // WHY PriceFormatter: locale-stable *and* instrument-aware,
                                // so an alert on USDJPY reads 151.500 and one on XAUUSD
                                // reads 2350.00 instead of both being padded to 5 decimals.
                                Text("${alert.conditionType.name} ${PriceFormatter.format(alert.targetValue, alert.pairSymbol)}")
                            },
                            supportingContent = {
                                Column {
                                    if (alert.isTriggered) {
                                        // WHY a fired alert stays in the list: deleting it
                                        // would erase the evidence that it worked, and the
                                        // user has no way to re-arm an alert they cannot see.
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = "Triggered",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.tertiary
                                            )
                                            TextButton(onClick = {
                                                viewModel.saveAlert(alert.copy(isTriggered = false))
                                            }) {
                                                Text("Re-arm")
                                            }
                                        }
                                    }
                                    if (alert.note.isNotBlank()) Text(alert.note)
                                }
                            },
                            trailingContent = {
                                Row {
                                    Switch(
                                        checked = alert.isActive,
                                        onCheckedChange = {
                                            viewModel.saveAlert(alert.copy(isActive = !alert.isActive))
                                        }
                                    )
                                    IconButton(onClick = { viewModel.deleteAlert(alert.id) }) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete")
                                    }
                                }
                            }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        var targetValue by remember { mutableStateOf("") }
        var note by remember { mutableStateOf("") }
        // WHY: the dialog used to hardcode PRICE_ABOVE, so three of the four
        // conditions the domain model supports were unreachable from the UI.
        var condition by remember { mutableStateOf(AlertConditionType.PRICE_ABOVE) }

        // WHY: parse once, reuse for the enabled state, the inline hint and
        // the save action — three separate parses could disagree with each other.
        val parsedTarget = targetValue.trim().toDoubleOrNull()?.takeIf { it.isFinite() }

        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("New Alert") },
            text = {
                Column {
                    Text("Pair: ${selectedPair.value}")
                    Spacer(Modifier.height(8.dp))
                    Text("Trigger when", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(4.dp))
                    // WHY: two rows instead of a FlowRow — FlowRow is still
                    // experimental in this Compose BOM, and an explicit layout
                    // cannot silently reflow into an unreadable dialog.
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AlertConditionChip(AlertConditionType.PRICE_ABOVE, condition) { condition = it }
                        AlertConditionChip(AlertConditionType.PRICE_BELOW, condition) { condition = it }
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AlertConditionChip(AlertConditionType.RSI_ABOVE, condition) { condition = it }
                        AlertConditionChip(AlertConditionType.RSI_BELOW, condition) { condition = it }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = targetValue,
                        onValueChange = { targetValue = it },
                        // WHY: the label tells the user which unit to type —
                        // an RSI threshold is 0..100, a price is not.
                        label = { Text(condition.targetLabel()) },
                        singleLine = true,
                        isError = targetValue.isNotBlank() && parsedTarget == null,
                        // WHY: explain a disabled button. Silently disabling
                        // "Create" leaves the user guessing what is wrong.
                        supportingText = {
                            if (targetValue.isNotBlank() && parsedTarget == null) {
                                Text("Enter a number, for example ${condition.exampleValue()}")
                            } else {
                                Text(condition.hint())
                            }
                        }
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = note,
                        // WHY: Cap at the model's MAX_NOTE_LENGTH so the
                        // field can never produce a value the model rejects.
                        onValueChange = { note = it.take(CustomAlert.MAX_NOTE_LENGTH) },
                        label = { Text("Note (optional)") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        // WHY: isFinite() — "NaN".toDoubleOrNull() succeeds,
                        // and a NaN alert can never trigger.
                        val value = parsedTarget
                        if (value != null) {
                            viewModel.saveAlert(
                                CustomAlert(
                                    id = AlertId.generate(),
                                    pairSymbol = selectedPair,
                                    conditionType = condition,
                                    targetValue = value,
                                    isTriggered = false,
                                    isActive = true,
                                    note = note.trim(),
                                    createdAt = Instant.now()
                                )
                            )
                            showCreateDialog = false
                        }
                    },
                    enabled = parsedTarget != null
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) { Text("Cancel") }
            }
        )
    }
}
/**
 * Selectable chip for one [AlertConditionType].
 *
 * WHY: an exhaustive `when` supplies the label, so adding a condition to the
 * enum is a compile error here rather than a missing button in the UI.
 */
@Composable
private fun AlertConditionChip(
    condition: AlertConditionType,
    selected: AlertConditionType,
    onSelect: (AlertConditionType) -> Unit
) {
    FilterChip(
        selected = condition == selected,
        onClick = { onSelect(condition) },
        label = { Text(condition.shortLabel()) }
    )
}

/** Short chip label for a condition. */
private fun AlertConditionType.shortLabel(): String = when (this) {
    AlertConditionType.PRICE_ABOVE -> "Price above"
    AlertConditionType.PRICE_BELOW -> "Price below"
    AlertConditionType.RSI_ABOVE -> "RSI above"
    AlertConditionType.RSI_BELOW -> "RSI below"
}

/** Field label describing the expected unit. */
private fun AlertConditionType.targetLabel(): String = when (this) {
    AlertConditionType.PRICE_ABOVE, AlertConditionType.PRICE_BELOW -> "Target price"
    AlertConditionType.RSI_ABOVE, AlertConditionType.RSI_BELOW -> "Target RSI (0-100)"
}

/** Example value shown when the current input cannot be parsed. */
private fun AlertConditionType.exampleValue(): String = when (this) {
    AlertConditionType.PRICE_ABOVE, AlertConditionType.PRICE_BELOW -> "1.0850"
    AlertConditionType.RSI_ABOVE, AlertConditionType.RSI_BELOW -> "70"
}

/** WHY: states what the alert will do — an alert whose meaning is unclear is
 * an alert the user does not trust. */
private fun AlertConditionType.hint(): String = when (this) {
    AlertConditionType.PRICE_ABOVE -> "Fires once the ask price reaches or exceeds this level."
    AlertConditionType.PRICE_BELOW -> "Fires once the bid price reaches or falls below this level."
    AlertConditionType.RSI_ABOVE -> "Fires when RSI(14) rises above this level (overbought)."
    AlertConditionType.RSI_BELOW -> "Fires when RSI(14) drops below this level (oversold)."
}
