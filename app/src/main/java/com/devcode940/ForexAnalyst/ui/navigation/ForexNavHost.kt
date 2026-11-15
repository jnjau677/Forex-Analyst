package com.devcode940.ForexAnalyst.ui.navigation

import androidx.compose.runtime.Composable
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

@Composable
fun ForexNavHost(
    forexViewModel: ForexViewModel = viewModel(),
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
            val symbol = entry.arguments?.getString("pairSymbol") ?: "EURUSD"
            forexViewModel.selectPair(PairSymbol(symbol))
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
            AiAnalystScreen(viewModel = forexViewModel, onBack = { nav.popBackStack() })
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
        onNavigateToAiAnalyst = { nav.navigate(Routes.AI_ANALYST) },
        onRequestNotificationPermission = {}
    )
}