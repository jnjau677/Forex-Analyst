package com.devcode940.ForexAnalyst.ui.navigation

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.devcode940.ForexAnalyst.data.model.PairSymbol
import com.devcode940.ForexAnalyst.ui.screens.AboutScreen
import com.devcode940.ForexAnalyst.ui.screens.AiAnalystScreen
import com.devcode940.ForexAnalyst.ui.screens.AlertsScreen
import com.devcode940.ForexAnalyst.ui.screens.ApiKeysScreen
import com.devcode940.ForexAnalyst.ui.screens.ChartTerminalScreen
import com.devcode940.ForexAnalyst.ui.screens.IndicatorSettingsScreen
import com.devcode940.ForexAnalyst.ui.screens.NotificationSettingsScreen
import com.devcode940.ForexAnalyst.ui.screens.SettingsScreen
import com.devcode940.ForexAnalyst.ui.screens.SignalsScreen
import com.devcode940.ForexAnalyst.ui.screens.WatchlistScreen
import com.devcode940.ForexAnalyst.ui.viewmodel.ForexViewModel
import com.devcode940.ForexAnalyst.ui.viewmodel.SettingsViewModel

/**
 * Navigation graph route templates.
 *
 * WHY: route strings live in one typed place — a typo in an inline
 * `navigate("setings")` fails silently at runtime with an IllegalArgumentException
 * from the NavHost, which is far harder to trace than a missing constant.
 */
object Routes {
    const val CHART_DEFAULT = "chart/default"
    const val CHART = "chart/{pairSymbol}"
    const val WATCHLIST = "watchlist"
    const val ALERTS = "alerts"
    const val SIGNALS = "signals"
    const val AI_ANALYST = "ai_analyst"
    const val SETTINGS = "settings"
    const val SETTINGS_INDICATORS = "settings/indicators"
    const val SETTINGS_API_KEYS = "settings/api_keys"
    const val SETTINGS_NOTIFICATIONS = "settings/notifications"
    const val SETTINGS_ABOUT = "settings/about"
}

/**
 * Application navigation host.
 *
 * WHY: ViewModels are created here (activity-scoped) and passed down, so every
 * destination shares one [ForexViewModel] instance and one source of truth for
 * the selected pair — navigating to the chart from the watchlist must not reset
 * the chart's state.
 *
 * @param forexViewModel market-data ViewModel shared by all trading screens.
 * @param settingsViewModel preferences ViewModel shared by all settings screens.
 */
@Composable
fun ForexNavHost(
    forexViewModel: ForexViewModel = viewModel(
        factory = ForexViewModel.Factory(LocalContext.current.applicationContext as Application)
    ),
    settingsViewModel: SettingsViewModel = viewModel()
) {
    val nav = rememberNavController()

    NavHost(navController = nav, startDestination = Routes.CHART_DEFAULT) {

        composable(Routes.CHART_DEFAULT) {
            ChartRoute(forexViewModel, nav)
        }

        composable(
            route = Routes.CHART,
            arguments = listOf(navArgument("pairSymbol") { type = NavType.StringType })
        ) { entry ->
            val symbol = entry.arguments?.getString("pairSymbol")?.takeIf { it.isNotBlank() } ?: "EURUSD"
            // WHY: Side effects belong in LaunchedEffect, not composition —
            // composition can run multiple times and must be idempotent.
            // PairSymbol.of normalizes casing/whitespace from the nav arg.
            LaunchedEffect(symbol) {
                forexViewModel.selectPair(PairSymbol.of(symbol))
            }
            ChartRoute(forexViewModel, nav)
        }

        composable(Routes.WATCHLIST) {
            WatchlistScreen(
                viewModel = forexViewModel,
                onPairSelected = { pair ->
                    forexViewModel.selectPair(pair)
                    nav.popBackStack()
                },
                onBack = { nav.popBackStack() }
            )
        }

        composable(Routes.ALERTS) {
            AlertsScreen(viewModel = forexViewModel, onBack = { nav.popBackStack() })
        }

        composable(Routes.SIGNALS) {
            SignalsScreen(viewModel = forexViewModel, onBack = { nav.popBackStack() })
        }

        composable(Routes.AI_ANALYST) {
            AiAnalystScreen(onBack = { nav.popBackStack() })
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                viewModel = settingsViewModel,
                onBack = { nav.popBackStack() },
                onOpenIndicators = { nav.navigate(Routes.SETTINGS_INDICATORS) },
                onOpenApiKeys = { nav.navigate(Routes.SETTINGS_API_KEYS) },
                onOpenNotifications = { nav.navigate(Routes.SETTINGS_NOTIFICATIONS) },
                onOpenAbout = { nav.navigate(Routes.SETTINGS_ABOUT) }
            )
        }

        composable(Routes.SETTINGS_INDICATORS) {
            IndicatorSettingsScreen(viewModel = settingsViewModel, onBack = { nav.popBackStack() })
        }

        composable(Routes.SETTINGS_API_KEYS) {
            ApiKeysScreen(viewModel = settingsViewModel, onBack = { nav.popBackStack() })
        }

        composable(Routes.SETTINGS_NOTIFICATIONS) {
            NotificationSettingsScreen(viewModel = settingsViewModel, onBack = { nav.popBackStack() })
        }

        composable(Routes.SETTINGS_ABOUT) {
            AboutScreen(viewModel = settingsViewModel, onBack = { nav.popBackStack() })
        }
    }
}

@Composable
private fun ChartRoute(vm: ForexViewModel, nav: NavHostController) {
    ChartTerminalScreen(
        viewModel = vm,
        onNavigateToWatchlist = { nav.navigate(Routes.WATCHLIST) },
        onNavigateToAlerts = { nav.navigate(Routes.ALERTS) },
        onNavigateToSettings = { nav.navigate(Routes.SETTINGS) },
        onNavigateToAiAnalyst = { nav.navigate(Routes.AI_ANALYST) }
    )
}