package com.devcode940.ForexAnalyst.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.devcode940.ForexAnalyst.data.model.PairCatalog
import com.devcode940.ForexAnalyst.data.model.PairSymbol
import com.devcode940.ForexAnalyst.ui.viewmodel.ForexViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun WatchlistScreen(viewModel: ForexViewModel, onPairSelected: (PairSymbol) -> Unit, onBack: () -> Unit) {
    val watchlist by viewModel.watchlist.collectAsState(); var searchQuery by remember { mutableStateOf("") }; var showAddDialog by remember { mutableStateOf(false) }
    val filtered = watchlist.filter { it.value.contains(searchQuery, ignoreCase = true) }
    Scaffold(topBar = { TopAppBar(title = { Text("Watchlist") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") } }, actions = { IconButton(onClick = { showAddDialog = true }) { Icon(Icons.Default.Add, contentDescription = "Add pair") } }) }) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            OutlinedTextField(value = searchQuery, onValueChange = { searchQuery = it }, modifier = Modifier.fillMaxWidth().padding(16.dp), placeholder = { Text("Search pair...") }, leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) }, singleLine = true)
            if (filtered.isEmpty()) { Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No pairs match your search") } } else { LazyColumn { items(filtered) { pair -> ListItem(modifier = Modifier.clickable(onClick = { onPairSelected(pair) }), headlineContent = { Text(pair.value) }, trailingContent = { IconButton(onClick = { viewModel.removeFromWatchlist(pair) }) { Icon(Icons.Default.Delete, contentDescription = "Remove") } }); HorizontalDivider() } } }
        }
    }
    if (showAddDialog) { val available = PairCatalog.ALL_PAIRS.filter { it !in watchlist.toSet() }; var selected by remember { mutableStateOf(available.firstOrNull()) }; AlertDialog(onDismissRequest = { showAddDialog = false }, title = { Text("Add Pair") }, text = { Column { available.forEach { pair -> Row(modifier = Modifier.fillMaxWidth().clickable { selected = pair }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { RadioButton(selected = pair == selected, onClick = { selected = pair }); Spacer(Modifier.width(8.dp)); Text(pair.value) } } } }, confirmButton = { TextButton(onClick = { selected?.let { viewModel.addToWatchlist(it) }; showAddDialog = false }, enabled = selected != null) { Text("Add") } }, dismissButton = { TextButton(onClick = { showAddDialog = false }) { Text("Cancel") } }) }
}