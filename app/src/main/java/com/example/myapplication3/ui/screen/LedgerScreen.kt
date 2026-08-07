package com.example.myapplication3.ui.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.example.myapplication3.ledger.LedgerDay
import com.example.myapplication3.ledger.LedgerRow
import com.example.myapplication3.ledger.LedgerViewModel
import com.example.myapplication3.ledger.SuggestionLedger
import com.example.myapplication3.ui.component.LoadingIndicator
import com.example.myapplication3.ui.component.formatIndianRupees
import com.example.myapplication3.ui.theme.DarkCard
import com.example.myapplication3.ui.theme.DarkSurfaceElevated
import com.example.myapplication3.ui.theme.GreenContainer
import com.example.myapplication3.ui.theme.GreenLight
import com.example.myapplication3.ui.theme.RedContainer
import com.example.myapplication3.ui.theme.RedLight
import com.example.myapplication3.ui.theme.TextPrimary
import com.example.myapplication3.ui.theme.TextSecondary

// ₹ with true Indian grouping and paise (I9) — "₹1,23,456.75".
private fun rupees(v: Double): String = "₹" + formatIndianRupees(v, 2)

/**
 * Suggestion record — the app's own daily PASS/FAIL diary for ONE tab
 * (U9.6: "shows its real weekly record"; honesty A5/B6).
 *
 * [tab] is the nav argument from "ledger/{tab}": "intraday" shows the
 * Intraday record, anything else the Stock record — matching how
 * [LedgerViewModel] resolves the same argument, so title and data always
 * agree. The two records live in two separate files and are never mixed.
 *
 * The PASS/FAIL rule shown on top is [SuggestionLedger.RULE_TEXT] verbatim —
 * single-sourced so this screen can never word the rule differently from how
 * the record is actually judged (A5/B6).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LedgerScreen(
    navController: NavController,
    tab: String,
    vm: LedgerViewModel = hiltViewModel()
) {
    val uiState by vm.uiState.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    val isIntraday = tab.trim().equals("intraday", ignoreCase = true)
    val title = if (isIntraday) "Suggestion record — Intraday" else "Suggestion record — Stock"

    // SAF export: the user picks where the file goes; the app never touches
    // shared storage on its own. MIME text/csv → opens in Excel / Sheets.
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri -> uri?.let { vm.export(it, context.contentResolver) } }

    // Show each save outcome exactly once.
    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            vm.clearMessage()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 2.dp) {
                TopAppBar(
                    title = {
                        Text(
                            title,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            // Suggested name matches the record file, e.g.
                            // "suggestions_stock.csv". Opens in Excel or Sheets.
                            runCatching { saveLauncher.launch(vm.suggestedFileName()) }
                        }) {
                            Icon(Icons.Default.Share, contentDescription = "Save this record as a file")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        scrolledContainerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        }
    ) { padding ->
        if (uiState.isLoading) {
            LoadingIndicator(modifier = Modifier.padding(padding))
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item { Spacer(modifier = Modifier.height(8.dp)) }

            // The honest rule, stated on screen and single-sourced (A5/B6):
            // this exact text is how the record is judged — never reworded here.
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = DarkCard),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            SuggestionLedger.RULE_TEXT,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "The share button on top saves this record as a file. " +
                                "It opens in Excel or Google Sheets.",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                }
            }

            if (uiState.days.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = DarkSurfaceElevated),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            "No suggestions recorded yet — they appear here from tomorrow's picks.",
                            modifier = Modifier.padding(24.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                    }
                }
            } else {
                uiState.days.forEach { day ->
                    item { DayHeader(day) }
                    day.rows.forEach { row ->
                        item { LedgerRowCard(row) }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

// ── Day header: date + the day's honest score line ───────────────────────────
@Composable
private fun DayHeader(day: LedgerDay) {
    Column(modifier = Modifier.padding(top = 8.dp)) {
        Text(
            day.dateLabel,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )
        if (day.summary.isNotEmpty()) {
            Text(
                day.summary,
                style = MaterialTheme.typography.bodySmall,
                // The day line goes green only when nothing went wrong that day;
                // any wrong call shows red — losses are never played down (A5).
                color = when {
                    day.wrongCount > 0 -> RedLight
                    day.rightCount > 0 -> GreenLight
                    else -> TextSecondary
                },
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

// ── One suggestion row: what was advised, and how it really ended ────────────
@Composable
private fun LedgerRowCard(row: LedgerRow) {
    val decided = row.result == SuggestionLedger.RESULT_PASS ||
        row.result == SuggestionLedger.RESULT_FAIL
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        row.symbol,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (row.name.isNotBlank() && !row.name.equals(row.symbol, ignoreCase = true)) {
                        Text(
                            row.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                ResultChip(row.result)
            }

            // What the app advised that morning — price, target, safety stop.
            Text(
                buildString {
                    append(if (row.action.equals("SELL", ignoreCase = true)) "Sell" else "Buy")
                    append(" · suggested at ").append(rupees(row.suggestedPrice))
                    if (row.target > 0.0) append(" · target ").append(rupees(row.target))
                    if (row.stopLoss > 0.0) append(" · stop-loss ").append(rupees(row.stopLoss))
                },
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )

            // Why it passed or failed — the judged reason from the record file,
            // plus the real closing price it was judged against. A pending row
            // says only that it is waiting; nothing is ever invented (B6).
            val reasonText =
                row.reason.trim().replaceFirstChar { it.uppercase() }.ifEmpty {
                    if (decided) "Judged after market close" else "Waiting for market close"
                } + if (decided && row.closePrice > 0.0) {
                    " — closed at " + rupees(row.closePrice)
                } else ""
            Text(
                reasonText,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = when (row.result) {
                    SuggestionLedger.RESULT_PASS -> GreenLight
                    SuggestionLedger.RESULT_FAIL -> RedLight
                    else -> TextSecondary
                }
            )
        }
    }
}

// ── Result chip: plain English — "It worked" / "Didn't work" / "Still waiting" (U9.3 / I1)
@Composable
private fun ResultChip(result: String) {
    val (bg, ink, label) = when (result) {
        SuggestionLedger.RESULT_PASS -> Triple(GreenContainer, GreenLight, "It worked")
        SuggestionLedger.RESULT_FAIL -> Triple(RedContainer, RedLight, "Didn't work")
        else -> Triple(DarkSurfaceElevated, TextSecondary, "Still waiting")
    }
    Surface(shape = RoundedCornerShape(6.dp), color = bg) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = ink
        )
    }
}
