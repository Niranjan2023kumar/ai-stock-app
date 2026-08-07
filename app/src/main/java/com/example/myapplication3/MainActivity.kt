package com.example.myapplication3

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.WorkManager
import com.example.myapplication3.ledger.LedgerEvaluationWorker
import com.example.myapplication3.mutualfunds.SipReminderWorker
import com.example.myapplication3.navigation.AppNavHost
import com.example.myapplication3.navigation.Screen
import com.example.myapplication3.notifications.worker.ScannerWorker
import com.example.myapplication3.ui.screen.FirstLaunchPrefs
import com.example.myapplication3.ui.theme.DarkTabBar
import com.example.myapplication3.ui.theme.GoldContainer
import com.example.myapplication3.ui.theme.GoldLight
import com.example.myapplication3.ui.theme.StockIntelligenceTheme
import com.example.myapplication3.ui.theme.TextMuted
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    // Android 13+ requires a runtime grant for POST_NOTIFICATIONS. Without asking,
    // every scanner/stop-loss/daily-limit notification is silently dropped —
    // StockNotificationManager's catch(SecurityException) hides the failure.
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* granted or not, app continues */ }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        requestNotificationPermissionIfNeeded()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            ScannerWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            ScannerWorker.buildPeriodicRequest()
        )

        // Daily SIP-day reminder for the tracked mutual fund SIP (C20a) — the
        // Mutual Fund tab promises "the app reminds you every month".
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            SipReminderWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            SipReminderWorker.buildPeriodicRequest()
        )

        // Daily after-close PASS/FAIL judging of the suggestion record (U9.6) —
        // Stock and Intraday ledgers, judged separately against real closes.
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            LedgerEvaluationWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            LedgerEvaluationWorker.buildPeriodicRequest()
        )

        // Daily 3:15 PM square-off alarm for open intraday trades (F2, C15a)
        com.example.myapplication3.tracking.SquareOffScheduler.scheduleNext(this)

        // First-launch walkthrough (ROADMAP P1 #9): the SharedPreferences flag
        // is read HERE, synchronously, BEFORE any composition — a returning
        // user starts straight on the Stock tab and the walkthrough composable
        // never builds. Never blocks returning users.
        val startRoute =
            if (FirstLaunchPrefs.isDone(this)) Screen.Home.route
            else Screen.FirstLaunch.route

        setContent {
            StockIntelligenceTheme {
                MainScaffold(startDestination = startRoute)
            }
        }
    }
}

private data class NavItem(val route: String, val icon: ImageVector, val label: String)

@Composable
fun MainScaffold(startDestination: String = Screen.Home.route) {
    val navController = rememberNavController()
    val currentEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentEntry?.destination?.route

    // User-requested order: Stock | Intraday | Mutual Fund | Guide
    val bottomNavItems = listOf(
        NavItem(Screen.Home.route,        Icons.Default.Search,                "Stock"),
        NavItem(Screen.Trading.route,     Icons.AutoMirrored.Filled.ShowChart, "Intraday"),
        NavItem(Screen.MutualFunds.route, Icons.Default.PieChart,              "Mutual Fund"),
        NavItem(Screen.Learning.route,    Icons.Default.School,                "Guide"),
    )

    val showBottomBar = bottomNavItems.any { it.route == currentRoute }

    Scaffold(
        modifier  = Modifier.fillMaxSize(),
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(containerColor = DarkTabBar) {
                    bottomNavItems.forEach { item ->
                        val selected = currentRoute == item.route
                        NavigationBarItem(
                            icon     = {
                                Icon(
                                    imageVector        = item.icon,
                                    contentDescription = item.label
                                )
                            },
                            label    = { Text(item.label, style = MaterialTheme.typography.labelSmall) },
                            selected = selected,
                            onClick  = {
                                if (!selected) {
                                    navController.navigate(item.route) {
                                        popUpTo(navController.graph.startDestinationId) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState    = true
                                    }
                                }
                            },
                            // White bar, GREEN selected item, grey unselected. The
                            // selected pill is a soft brand-green (GoldContainer) with a
                            // deep-green icon+label (GoldLight) so both stay >=4.5:1
                            // readable on white — bright green text/white-on-green would
                            // fail contrast on the light bar.
                            colors   = NavigationBarItemDefaults.colors(
                                selectedIconColor   = GoldLight,
                                unselectedIconColor = TextMuted,
                                selectedTextColor   = GoldLight,
                                unselectedTextColor = TextMuted,
                                indicatorColor      = GoldContainer
                            )
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        // consumeWindowInsets — without it, screens hosting their own Scaffold/TopAppBar
        // apply the status-bar inset a second time under edge-to-edge
        AppNavHost(
            navController = navController,
            modifier = Modifier.padding(innerPadding).consumeWindowInsets(innerPadding),
            startDestination = startDestination
        )
    }
}
