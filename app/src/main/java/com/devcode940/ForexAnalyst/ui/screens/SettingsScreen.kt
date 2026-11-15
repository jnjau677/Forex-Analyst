package com.devcode940.ForexAnalyst.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.devcode940.ForexAnalyst.data.settings.AppSettings
import com.devcode940.ForexAnalyst.data.settings.IndicatorFormInput
import com.devcode940.ForexAnalyst.data.settings.ThemeMode
import com.devcode940.ForexAnalyst.ui.viewmodel.SettingsViewModel

// ---------------------------------------------------------------------------
// MAIN SETTINGS LIST
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onOpenIndicators: () -> Unit,
    onOpenApiKeys: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenAbout: () -> Unit
) {
    val settings by viewModel.settings.collectAsState()

    Scaffold(
        topBar = { SettingsTopBar("Settings", onBack) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            SettingsRow(
                icon = Icons.Default.Settings,
                title = "Indicator Settings",
                subtitle = "RSI ${settings.rsiPeriod} · MACD ${settings.macdFast}/${settings.macdSlow}/${settings.macdSignal} · EMA ${settings.emaFast}/${settings.emaMedium}/${settings.emaSlow}",
                onClick = onOpenIndicators
            )
            HorizontalDivider()

            SettingsRow(
                icon = Icons.Default.Lock,
                title = "API Keys",
                subtitle = if (settings.hasMarketDataKey || settings.hasAiKey) {
                    buildString {
                        append(if (settings.hasMarketDataKey) "Market data ✓" else "Market data —")
                        append("   ")
                        append(if (settings.hasAiKey) "AI ✓" else "AI —")
                    }
                } else {
                    "Not configured — using simulated data"
                },
                onClick = onOpenApiKeys
            )
            HorizontalDivider()

            SettingsRow(
                icon = Icons.Default.Notifications,
                title = "Notifications",
                subtitle = if (settings.notificationsEnabled) "Enabled" else "Disabled",
                onClick = onOpenNotifications
            )
            HorizontalDivider()

            SettingsRow(
                icon = Icons.Default.Info,
                title = "About",
                subtitle = "Version, licenses and diagnostics",
                onClick = onOpenAbout
            )
            HorizontalDivider()

            Spacer(Modifier.height(16.dp))
            Text(
                text = "Theme",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ThemeChip("System", ThemeMode.SYSTEM, settings.themeMode) { viewModel.setThemeMode(it) }
                ThemeChip("Light", ThemeMode.LIGHT, settings.themeMode) { viewModel.setThemeMode(it) }
                ThemeChip("Dark", ThemeMode.DARK, settings.themeMode) { viewModel.setThemeMode(it) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ThemeChip(
    label: String,
    value: ThemeMode,
    selected: ThemeMode,
    onSelect: (ThemeMode) -> Unit
) {
    if (value == selected) {
        Button(onClick = { onSelect(value) }) { Text(label) }
    } else {
        OutlinedButton(onClick = { onSelect(value) }) { Text(label) }
    }
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = {
            Icon(Icons.Default.KeyboardArrowRight, contentDescription = null)
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsTopBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
            }
        }
    )
}

/** WHY: One shared snackbar behaviour for every settings sub-screen. */
@Composable
private fun SettingsMessageHost(
    hostState: SnackbarHostState,
    message: String?,
    onConsumed: () -> Unit
) {
    LaunchedEffect(message) {
        val text = message
        if (text != null) {
            hostState.showSnackbar(text)
            onConsumed()
        }
    }
}

// ---------------------------------------------------------------------------
// INDICATOR SETTINGS
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IndicatorSettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit
) {
    val settings by viewModel.settings.collectAsState()
    val message by viewModel.message.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    // WHY: Key remember on each field's own value — remember(settings)
    // reset every field whenever ANY setting changed, wiping in-progress edits.
    var rsi by remember(settings.rsiPeriod) { mutableStateOf(settings.rsiPeriod.toString()) }
    var emaFast by remember(settings.emaFast) { mutableStateOf(settings.emaFast.toString()) }
    var emaMed by remember(settings.emaMedium) { mutableStateOf(settings.emaMedium.toString()) }
    var emaSlow by remember(settings.emaSlow) { mutableStateOf(settings.emaSlow.toString()) }
    var macdFast by remember(settings.macdFast) { mutableStateOf(settings.macdFast.toString()) }
    var macdSlow by remember(settings.macdSlow) { mutableStateOf(settings.macdSlow.toString()) }
    var macdSignal by remember(settings.macdSignal) { mutableStateOf(settings.macdSignal.toString()) }
    var atr by remember(settings.atrPeriod) { mutableStateOf(settings.atrPeriod.toString()) }
    var showEma by remember(settings.showEma) { mutableStateOf(settings.showEma) }
    var showRsi by remember(settings.showRsi) { mutableStateOf(settings.showRsi) }

    SettingsMessageHost(snackbar, message) { viewModel.consumeMessage() }

    Scaffold(
        topBar = { SettingsTopBar("Indicator Settings", onBack) },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Momentum", style = MaterialTheme.typography.titleSmall)
            NumberField("RSI period", rsi) { rsi = it }
            NumberField("ATR period", atr) { atr = it }

            HorizontalDivider()
            Text("Moving Averages", style = MaterialTheme.typography.titleSmall)
            NumberField("EMA fast", emaFast) { emaFast = it }
            NumberField("EMA medium", emaMed) { emaMed = it }
            NumberField("EMA slow", emaSlow) { emaSlow = it }

            HorizontalDivider()
            Text("MACD", style = MaterialTheme.typography.titleSmall)
            NumberField("Fast period", macdFast) { macdFast = it }
            NumberField("Slow period", macdSlow) { macdSlow = it }
            NumberField("Signal period", macdSignal) { macdSignal = it }

            HorizontalDivider()
            Text("Overlays", style = MaterialTheme.typography.titleSmall)
            SwitchRow("Show EMA lines", showEma) { showEma = it }
            SwitchRow("Show RSI values", showRsi) { showRsi = it }

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = {
                    // WHY: Pass the RAW text. The old call did
                    // `toIntOrNull() ?: currentValue`, which silently threw
                    // away a typo and then reported "Saved" — the user's edit
                    // vanished with no feedback. Validation now happens in
                    // IndicatorFormValidator, which names the offending field.
                    viewModel.saveIndicators(
                        IndicatorFormInput(
                            rsiPeriod = rsi,
                            emaFast = emaFast,
                            emaMedium = emaMed,
                            emaSlow = emaSlow,
                            macdFast = macdFast,
                            macdSlow = macdSlow,
                            macdSignal = macdSignal,
                            atrPeriod = atr,
                            showEma = showEma,
                            showRsi = showRsi
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Save") }

            OutlinedButton(
                onClick = { viewModel.resetIndicators() },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Reset to defaults") }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        // WHY: Digits-only filter prevents crashes from "-" or "." in integer fields.
        onValueChange = { input -> onChange(input.filter { it.isDigit() }.take(4)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

// ---------------------------------------------------------------------------
// API KEYS
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiKeysScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit
) {
    val settings by viewModel.settings.collectAsState()
    val message by viewModel.message.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    // WHY: Key remember on each field's own value (see IndicatorSettingsScreen).
    var fcs by remember(settings.fcsApiKey) { mutableStateOf(settings.fcsApiKey) }
    var alpha by remember(settings.alphaVantageApiKey) { mutableStateOf(settings.alphaVantageApiKey) }
    var openRouter by remember(settings.openRouterApiKey) { mutableStateOf(settings.openRouterApiKey) }
    var model by remember(settings.aiModel) { mutableStateOf(settings.aiModel) }
    var reveal by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    SettingsMessageHost(snackbar, message) { viewModel.consumeMessage() }

    Scaffold(
        topBar = { SettingsTopBar("API Keys", onBack) },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // WHY: state the actual guarantee. "Stored on this device" was
            // not true before (plaintext prefs + included in auto-backup);
            // users decide whether to paste a paid API key based on this copy.
            Text(
                "Keys are encrypted with the Android Keystore and excluded from backups, so they never " +
                    "leave this device. Without them the app runs on simulated market data.",
                style = MaterialTheme.typography.bodySmall
            )

            SecretField("FCS API key", fcs, reveal) { fcs = it }
            SecretField("Alpha Vantage API key", alpha, reveal) { alpha = it }
            SecretField("OpenRouter API key", openRouter, reveal) { openRouter = it }

            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text("AI model id") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            SwitchRow("Reveal keys", reveal) { reveal = it }

            Button(
                onClick = { viewModel.saveApiKeys(fcs, alpha, openRouter, model) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Save keys") }

            OutlinedButton(
                onClick = { confirmClear = true },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Clear all keys") }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear all API keys?") },
            text = { Text("Live data and AI analysis will stop working until you add keys again.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearApiKeys()
                    confirmClear = false
                }) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
            }
        )
    }
}

/**
 * Masked single-line field for a credential.
 *
 * @param label field caption.
 * @param value current secret text.
 * @param reveal when false the value is rendered as a password.
 * @param onChange invoked with the edited text.
 */
@Composable
private fun SecretField(
    label: String,
    value: String,
    reveal: Boolean,
    onChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (reveal) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        // WHY: API keys are opaque tokens — autocorrect/capitalization/
        // suggestions silently corrupt them, and a "smart" keyboard that
        // capitalizes the first letter produces a key that fails auth with no
        // obvious cause.
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            autoCorrect = false,
            imeAction = ImeAction.Done
        ),
        modifier = Modifier.fillMaxWidth()
    )
}

// ---------------------------------------------------------------------------
// NOTIFICATION SETTINGS
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationSettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit
) {
    val settings by viewModel.settings.collectAsState()
    val childrenEnabled = settings.notificationsEnabled

    Scaffold(
        topBar = { SettingsTopBar("Notifications", onBack) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SwitchRow("Enable notifications", settings.notificationsEnabled) {
                viewModel.setNotificationsEnabled(it)
            }
            HorizontalDivider()

            Text("Channels", style = MaterialTheme.typography.titleSmall)
            SwitchRow("Trade signals", settings.signalNotifications && childrenEnabled) {
                viewModel.setSignalNotifications(it)
            }
            SwitchRow("Price & indicator alerts", settings.alertNotifications && childrenEnabled) {
                viewModel.setAlertNotifications(it)
            }

            HorizontalDivider()
            Text("Feedback", style = MaterialTheme.typography.titleSmall)
            SwitchRow("Vibration", settings.vibrationEnabled && childrenEnabled) {
                viewModel.setVibration(it)
            }
            SwitchRow("Sound", settings.soundEnabled && childrenEnabled) {
                viewModel.setSound(it)
            }

            if (!childrenEnabled) {
                Text(
                    "Notifications are turned off. Enable the master switch to configure channels.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// ABOUT
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit
) {
    val settings by viewModel.settings.collectAsState()
    val context = LocalContext.current

    // WHY: Read the real installed version instead of hardcoding a string that drifts.
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "1.0.0"
    }

    Scaffold(
        topBar = { SettingsTopBar("About", onBack) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            ListItem(
                headlineContent = { Text("Forex Analyst") },
                supportingContent = { Text("Technical analysis terminal for Forex, metals and crypto") }
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Version") },
                supportingContent = { Text(versionName) }
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Data source") },
                supportingContent = {
                    Text(if (settings.hasMarketDataKey) "Live API configured" else "Simulated (no API key)")
                }
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("AI analyst") },
                supportingContent = {
                    Text(if (settings.hasAiKey) "Configured · ${settings.aiModel}" else "Not configured")
                }
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("License") },
                supportingContent = { Text("Apache License 2.0") }
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Disclaimer") },
                supportingContent = {
                    Text("Signals are informational only and not financial advice. Trading carries risk of loss.")
                }
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}