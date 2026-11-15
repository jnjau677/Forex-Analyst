package com.devcode940.ForexAnalyst.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.devcode940.ForexAnalyst.data.model.PairCatalog
import com.devcode940.ForexAnalyst.data.model.PairSymbol
import com.devcode940.ForexAnalyst.ui.viewmodel.ForexViewModel

/**
 * Watchlist screen: search, open and manage the tracked pairs.
 *
 * @param viewModel source of the watchlist stream.
 * @param onPairSelected opens the chart for the tapped pair.
 * @param onBack navigation callback to the previous destination.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchlistScreen(
    viewModel: ForexViewModel,
    onPairSelected: (PairSymbol) -> Unit,
    onBack: () -> Unit
) {
    val watchlist by viewModel.watchlist.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    var showAddDialog by remember { mutableStateOf(false) }

    // WHY: derivedStateOf recomputes the filter only when the watchlist or the
    // query actually changes, instead of on every recomposition of the screen.
    // Both inputs are State reads inside the calculation block, so no key is
    // needed — and adding one would throw the cached result away needlessly.
    val filtered by remember {
        derivedStateOf {
            val query = searchQuery.trim()
            if (query.isEmpty()) {
                watchlist
            } else {
                watchlist.filter { it.value.contains(query, ignoreCase = true) }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Watchlist") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Add pair")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                placeholder = { Text("Search pair...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true
            )

            when {
                // WHY: two different empty states need two different messages —
                // "nothing matches your search" and "your watchlist is empty"
                // lead to opposite actions.
                filtered.isEmpty() && watchlist.isEmpty() -> EmptyState(
                    title = "Your watchlist is empty",
                    body = "Tap + to track a pair. Tracked pairs are the first thing the chart offers you."
                )

                filtered.isEmpty() -> EmptyState(
                    title = "No pairs match \"$searchQuery\"",
                    body = "Check the spelling, or clear the search to see all ${watchlist.size} tracked pairs."
                )

                else -> LazyColumn {
                    // WHY: stable keys let Compose reuse row state when an item
                    // is removed, instead of rebinding every following row.
                    items(items = filtered, key = { pair -> pair.value }) { pair ->
                        ListItem(
                            modifier = Modifier.clickable(onClick = { onPairSelected(pair) }),
                            headlineContent = { Text(pair.value) },
                            trailingContent = {
                                IconButton(onClick = { viewModel.removeFromWatchlist(pair) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Remove ${pair.value}")
                                }
                            }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddPairDialog(
            // WHY: remember on the watchlist — building the "available" set and
            // a HashSet on every recomposition of an open dialog is wasted work.
            available = remember(watchlist) {
                val tracked = watchlist.toSet()
                PairCatalog.ALL_PAIRS.filter { it !in tracked }
            },
            onDismiss = { showAddDialog = false },
            onConfirm = { pair ->
                viewModel.addToWatchlist(pair)
                showAddDialog = false
            }
        )
    }
}

/** Centered two-line empty state. */
@Composable
private fun EmptyState(title: String, body: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Single-choice dialog for adding a pair that is not tracked yet.
 *
 * @param available catalog pairs not already in the watchlist.
 * @param onDismiss closes without changes.
 * @param onConfirm adds the selected pair.
 */
@Composable
private fun AddPairDialog(
    available: List<PairSymbol>,
    onDismiss: () -> Unit,
    onConfirm: (PairSymbol) -> Unit
) {
    var selected by remember(available) { mutableStateOf(available.firstOrNull()) }

    // WHY: if every catalog pair is already tracked there is nothing to choose —
    // say so instead of showing an empty dialog with a dead "Add" button.
    if (available.isEmpty()) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Add Pair") },
            text = { Text("Every supported pair is on your watchlist already.") },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Pair") },
        text = {
            Column {
                available.forEach { pair ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selected = pair }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = pair == selected, onClick = { selected = pair })
                        Spacer(Modifier.width(8.dp))
                        Text(pair.value)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { selected?.let(onConfirm) },
                enabled = selected != null
            ) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
