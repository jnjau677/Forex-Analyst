package com.devcode940.ForexAnalyst.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.devcode940.ForexAnalyst.data.model.Instrument
import com.devcode940.ForexAnalyst.data.model.InstrumentCatalog
import com.devcode940.ForexAnalyst.data.model.InstrumentCategory
import com.devcode940.ForexAnalyst.data.model.PairSymbol
import com.devcode940.ForexAnalyst.data.model.PriceFormatter
import com.devcode940.ForexAnalyst.data.model.TradingSession
import com.devcode940.ForexAnalyst.ui.viewmodel.ForexViewModel

/**
 * Watchlist screen: search, open and manage the tracked instruments.
 *
 * WHY rows show more than the symbol: the catalogue now spans FX, metals, energy,
 * indices and crypto. `XAUUSD` and `USDJPY` need different decimal precision and
 * trade in different sessions, and a bare symbol list hides both facts — the user
 * only finds out after opening a chart and wondering why gold quotes two decimals.
 *
 * @param viewModel source of the watchlist stream.
 * @param onPairSelected opens the chart for the tapped instrument.
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
                watchlist.filter { symbol ->
                    symbol.value.contains(query, ignoreCase = true) ||
                        (InstrumentCatalog.specFor(symbol)?.displayName?.contains(query, ignoreCase = true) ?: false)
                }
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
                        Icon(Icons.Default.Add, contentDescription = "Add instrument")
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
                placeholder = { Text("Search symbol or name…") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true
            )

            when {
                // WHY: two different empty states need two different messages —
                // "nothing matches your search" and "your watchlist is empty"
                // lead to opposite actions.
                filtered.isEmpty() && watchlist.isEmpty() -> EmptyState(
                    title = "Your watchlist is empty",
                    body = "Tap + to pick from ${InstrumentCatalog.size} instruments across FX, metals, " +
                        "energy, indices and crypto."
                )

                filtered.isEmpty() -> EmptyState(
                    title = "Nothing matches \"$searchQuery\"",
                    body = "Check the spelling, or clear the search to see all ${watchlist.size} tracked instruments."
                )

                else -> LazyColumn {
                    // WHY: stable keys let Compose reuse row state when an item
                    // is removed, instead of rebinding every following row.
                    items(items = filtered, key = { pair -> pair.value }) { pair ->
                        val instrument = remember(pair) { InstrumentCatalog.specFor(pair) }
                        ListItem(
                            modifier = Modifier.clickable(onClick = { onPairSelected(pair) }),
                            headlineContent = { Text(pair.value) },
                            supportingContent = {
                                Text(
                                    text = instrumentSummary(instrument, pair.value),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
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
        InstrumentPickerDialog(
            // WHY: remember on the watchlist — building the "available" set on every
            // recomposition of an open dialog is wasted work.
            tracked = remember(watchlist) { watchlist.toSet() },
            onDismiss = { showAddDialog = false },
            onConfirm = { symbols ->
                // WHY a batch: the picker is multi-select, and adding six instruments
                // one dialog at a time is the kind of friction that makes people give
                // up halfway through building a watchlist.
                symbols.forEach { viewModel.addToWatchlist(it) }
                showAddDialog = false
            }
        )
    }
}

/**
 * One line describing an instrument: name, category, precision and sessions.
 *
 * WHY precision is shown as a sample price rather than "5 decimals": `1.08500` is
 * instantly recognisable as "this is how it will be quoted", whereas a digit count
 * asks the user to do the work themselves.
 */
private fun instrumentSummary(instrument: Instrument?, symbol: String): String {
    if (instrument == null) return "$symbol · not in catalogue"
    val sample = PriceFormatter.format(instrument.basePrice, instrument)
    val sessions = if (instrument.isContinuous) {
        "24h"
    } else {
        TradingSession.ORDERED.filter { it in instrument.sessions }.joinToString("/") { it.displayName }
    }
    return "${instrument.displayName} · ${instrument.category.displayName} · $sample · $sessions"
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
 * Searchable, category-grouped, multi-select instrument picker.
 *
 * WHY not the previous radio-button list: it rendered every catalogue entry into a
 * non-scrolling `Column` inside a dialog. At 71 instruments that overflows the screen
 * and the entries below the fold are unreachable — a dialog that hides most of its own
 * options is worse than no dialog.
 *
 * WHY category chips *and* search: a trader looking for USDZAR knows the symbol and
 * wants search; one looking for "something in crypto" wants the group. Each alone
 * leaves the other case unserved.
 *
 * @param tracked symbols already on the watchlist; shown as ticked and not re-added.
 * @param onDismiss closes without changes.
 * @param onConfirm adds every newly selected symbol.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InstrumentPickerDialog(
    tracked: Set<PairSymbol>,
    onDismiss: () -> Unit,
    onConfirm: (List<PairSymbol>) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<InstrumentCategory?>(null) }
    var selected by remember { mutableStateOf(emptySet<PairSymbol>()) }

    val all = remember { InstrumentCatalog.ALL }

    // WHY derivedStateOf: filtering 71 instruments on every keystroke recomposition is
    // cheap but pointless, and the dialog also recomposes on every checkbox tap.
    val visible by remember(all) {
        derivedStateOf {
            val needle = query.trim()
            all.filter { instrument ->
                (category == null || instrument.category == category) &&
                    (needle.isEmpty() ||
                        instrument.symbol.value.contains(needle, ignoreCase = true) ||
                        instrument.displayName.contains(needle, ignoreCase = true))
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add instruments") },
        text = {
            Column(modifier = Modifier.heightIn(max = DIALOG_MAX_HEIGHT_DP)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search ${all.size} instruments…") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true
                )

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // WHY scrollable: eight category chips do not fit a phone-width
                        // dialog, and a clipped row hides the last categories with no
                        // hint that more exist.
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // WHY "All" is an explicit chip rather than an unselected state:
                    // with no chip selected the user cannot tell whether the filter is
                    // off or simply not visible.
                    FilterChip(
                        selected = category == null,
                        onClick = { category = null },
                        label = { Text("All", style = MaterialTheme.typography.labelSmall) }
                    )
                    Spacer(Modifier.width(6.dp))
                    InstrumentCatalog.CATEGORIES.forEach { entry ->
                        FilterChip(
                            selected = category == entry,
                            onClick = { category = if (category == entry) null else entry },
                            modifier = Modifier.padding(end = 6.dp),
                            label = { Text(entry.displayName, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                if (visible.isEmpty()) {
                    Text(
                        text = "No instrument matches \"$query\".",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                } else {
                    // WHY a LazyColumn: 71 rows must scroll, and a dialog body that
                    // overflows its window makes the entries below the fold
                    // unreachable — which is exactly what the old radio list did.
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        items(items = visible, key = { it.symbol.value }) { instrument ->
                            val isTracked = instrument.symbol in tracked
                            val isChecked = isTracked || instrument.symbol in selected
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !isTracked) {
                                        selected = if (instrument.symbol in selected) {
                                            selected - instrument.symbol
                                        } else {
                                            selected + instrument.symbol
                                        }
                                    }
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = isChecked,
                                    // WHY null for tracked rows: a checkbox the user
                                    // can untick would imply the instrument can be
                                    // removed from here, which this dialog cannot do.
                                    onCheckedChange = if (isTracked) null else { _ ->
                                        selected = if (instrument.symbol in selected) {
                                            selected - instrument.symbol
                                        } else {
                                            selected + instrument.symbol
                                        }
                                    }
                                )
                                Column(modifier = Modifier.padding(end = 8.dp)) {
                                    Text(
                                        text = instrument.symbol.value,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        text = instrument.displayName,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(selected.toList()) },
                enabled = selected.isNotEmpty()
            ) {
                // WHY the count is in the button: with multi-select the user must be
                // able to see what they are about to add before committing to it.
                Text(if (selected.isEmpty()) "Add" else "Add ${selected.size}")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Cap on the picker body so the dialog never runs off a small screen. */
private val DIALOG_MAX_HEIGHT_DP = 420.dp
