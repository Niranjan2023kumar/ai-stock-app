package com.example.myapplication3.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.example.myapplication3.ui.screen.*

// Per REQUIREMENTS_FINAL rules E3b/E3c the analyst screens (Backtesting, Sector
// Rotation, Market Breadth, News, Portfolio, Dashboard, Recommendations, Scanner)
// are REMOVED from user navigation — their engines keep feeding the AI internally.
sealed class Screen(val route: String) {
    // ── Primary bottom-nav tabs (PART C order: Stock | Intraday | Mutual Fund | Guide)
    data object Home              : Screen("home")
    data object Trading           : Screen("trading")
    data object MutualFunds       : Screen("mutual_funds")
    data object Learning          : Screen("learning")

    // ── Secondary screens ────────────────────────────────────────────────────
    data object Settings          : Screen("settings")      // gear icon (E5)
    data object Watchlist         : Screen("watchlist")     // stays per E3c
    data object MoreWays          : Screen("more_ways")     // Guide ▸ Advanced (C24)
    data object FirstLaunch       : Screen("first_launch")  // 3-card walkthrough, once (ROADMAP P1 #9)

    // ── Parametric screens ───────────────────────────────────────────────────
    data class StockResearch(val symbol: String = "{symbol}") : Screen("research/{symbol}") {
        fun createRoute(sym: String) = "research/$sym"
    }

    // Suggestion record (U9.6 honesty): the app's own day-by-day PASS/FAIL
    // diary. tab = "stock" | "intraday" — two separate records, never mixed.
    data class Ledger(val tab: String = "{tab}") : Screen("ledger/{tab}") {
        fun createRoute(tab: String) = "ledger/$tab"
    }
}

@Composable
fun AppNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    // MainActivity passes FirstLaunch.route exactly once — on the very first
    // launch. Every other launch starts on Home, so returning users never
    // compose the walkthrough at all.
    startDestination: String = Screen.Home.route
) {
    NavHost(
        navController    = navController,
        startDestination = startDestination,
        modifier         = modifier
    ) {
        // First-launch walkthrough (ROADMAP P1 #9) — reachable ONLY as the
        // start destination; nothing navigates here afterwards. Done/Skip
        // replaces it with Home so back never returns to the intro.
        composable(Screen.FirstLaunch.route) {
            FirstLaunchScreen(onDone = {
                navController.navigate(Screen.Home.route) {
                    popUpTo(Screen.FirstLaunch.route) { inclusive = true }
                }
            })
        }

        // Bottom-nav roots
        composable(Screen.Home.route)        { HomeScreen(navController) }
        composable(Screen.Trading.route)     { TradingScreen(navController) }
        composable(Screen.MutualFunds.route) { MutualFundsScreen(navController) }
        composable(Screen.Learning.route)    { LearningScreen(navController) }

        // Secondary
        composable(Screen.Settings.route)    { SettingsScreen(navController) }
        composable(Screen.Watchlist.route)   { WatchlistScreen(navController) }
        composable(Screen.MoreWays.route)    { MoreWaysScreen(navController) }

        // Parametric
        composable(Screen.StockResearch().route) {
            StockResearchScreen(navController)
        }

        // Suggestion record — one screen, two separate records ("stock" /
        // "intraday"); the LedgerViewModel reads the same {tab} argument via
        // SavedStateHandle, so title and data always come from one source.
        composable(Screen.Ledger().route) { backStackEntry ->
            LedgerScreen(
                navController = navController,
                tab = backStackEntry.arguments?.getString("tab") ?: "stock"
            )
        }
    }
}
