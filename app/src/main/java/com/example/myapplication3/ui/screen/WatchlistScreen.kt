package com.example.myapplication3.ui.screen

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.example.myapplication3.ui.component.formatIndianRupees
import com.example.myapplication3.ui.theme.GreenPrimary
import com.example.myapplication3.ui.theme.RedPrimary
import com.example.myapplication3.ui.viewmodel.InterdayViewModel
import com.example.myapplication3.ui.viewmodel.WatchlistRow
import com.example.myapplication3.ui.viewmodel.WatchlistViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ₹ only for rupee stocks — a US stock must never be shown with a ₹ symbol.
private fun currencySymbol(code: String): String = when (code) {
    "INR" -> "₹"
    "USD" -> "$"
    else  -> "$code "
}

// True Indian digit grouping (₹1,23,456.70) for rupee prices via the shared
// formatter — Locale("en","IN") "%,.2f" groups in threes on Android, not
// lakh/crore (I9/U9.2). US ($) prices keep western grouping.
private fun fmtPrice(v: Double, currency: String): String =
    if (currency == "INR") formatIndianRupees(v, 2)
    else String.format(Locale.US, "%,.2f", v)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchlistScreen(navController: NavController, vm: WatchlistViewModel = hiltViewModel()) {
    val rowsState by vm.rowsState.collectAsStateWithLifecycle()
    val addError by vm.addError.collectAsStateWithLifecycle()
    val isAdding by vm.isAdding.collectAsStateWithLifecycle()

    // Live Angel One feed — the SAME activity-scoped InterdayViewModel instance the
    // Stock/Intraday screens and MyStockCard share (they all call hiltViewModel(activity)),
    // so this adds ZERO new connections: the feed already subscribes the whole universe.
    // When connected, rows overlay the real-time tick; when not, nothing changes (B8).
    val activity = LocalContext.current as androidx.activity.ComponentActivity
    val interdayVm: InterdayViewModel = hiltViewModel(activity)
    val livePrices by interdayVm.livePrices.collectAsStateWithLifecycle()
    val liveConnected by interdayVm.liveConnected.collectAsStateWithLifecycle()

    // row symbol → real-time tick, ONLY while the feed is connected. Key convention
    // matches InterdayViewModel.effectivePriceFor (symbol without .NS, upper-case).
    // INR-only guard: the feed is NSE — a US stock must never pick up an Indian
    // tick that happens to share the same ticker. Feed off → empty map → the whole
    // screen behaves exactly as before (fail-safe B8).
    val liveBySymbol: Map<String, Double> =
        if (liveConnected)
            rowsState.rows.mapNotNull { r ->
                if (r.currency != "INR") null
                else livePrices[r.symbol.removeSuffix(".NS").uppercase()]
                    ?.takeIf { it > 0.0 }
                    ?.let { r.symbol to it }
            }.toMap()
        else emptyMap()
    var showAddDialog by remember { mutableStateOf(false) }
    var newSymbol by remember { mutableStateOf("") }
    var addAttempted by remember { mutableStateOf(false) }
    val timeFmt = remember { SimpleDateFormat("h:mm a", Locale.ENGLISH) }

    fun closeAddDialog() {
        showAddDialog = false
        addAttempted = false
        newSymbol = ""
        vm.clearAddError()
    }

    if (showAddDialog) {
        // Close automatically once an attempted add finishes with no error (= success).
        LaunchedEffect(isAdding, addError) {
            if (addAttempted && !isAdding && addError == null) closeAddDialog()
        }
        AlertDialog(
            onDismissRequest = { if (!isAdding) closeAddDialog() },
            title = { Text("Add to Watchlist") },
            text = {
                Column {
                    OutlinedTextField(
                        value = newSymbol,
                        onValueChange = {
                            newSymbol = it.uppercase()
                            if (addError != null) vm.clearAddError()
                        },
                        label = { Text("Symbol (e.g. RELIANCE)") },
                        singleLine = true,
                        isError = addError != null,
                        enabled = !isAdding
                    )
                    // The ViewModel's plain-English validation message
                    // (e.g. "Could not find "XYZ". Check the spelling…").
                    addError?.let { message ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    if (isAdding) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Checking that stock…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        addAttempted = true
                        vm.addToWatchlist(newSymbol)
                    },
                    enabled = !isAdding && newSymbol.isNotBlank()
                ) { Text("Add") }
            },
            dismissButton = {
                TextButton(onClick = { closeAddDialog() }, enabled = !isAdding) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            // Fully opaque header with a hard shadow edge — scrolling rows must
            // never show through or look "sliced" under the pinned title.
            Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 2.dp) {
                TopAppBar(
                    title = { Text("Watchlist", fontWeight = FontWeight.Bold) },
                    navigationIcon = { IconButton(onClick = { navController.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                    actions = {
                        IconButton(onClick = { vm.refreshRows() }) { Icon(Icons.Default.Refresh, contentDescription = "Refresh prices") }
                        IconButton(onClick = { showAddDialog = true }) { Icon(Icons.Default.Add, contentDescription = "Add stock") }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        scrolledContainerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item { Spacer(modifier = Modifier.height(8.dp)) }
            if (rowsState.rows.isEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Box(modifier = Modifier.padding(32.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text("Tap + to add stocks to your watchlist", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            } else {
                // Honest freshness line (B2/U1.6): cached numbers say when they are
                // from; live-fed rows say so too. Feed off → the exact old wording.
                if (rowsState.isRefreshing || (rowsState.updatedAtMs > 0 && rowsState.rows.any { it.isFromCache })) {
                    item {
                        // "All live" = every cached (universe) row has a tick — then
                        // the "as of" clock would only mislead. Mixed = say both.
                        val allCachedLive = liveBySymbol.isNotEmpty() &&
                            rowsState.rows.filter { it.isFromCache }.all { it.symbol in liveBySymbol }
                        Text(
                            text = when {
                                rowsState.isRefreshing -> "Updating prices…"
                                allCachedLive -> "● Live — prices update in real time"
                                liveBySymbol.isNotEmpty() ->
                                    "● Live rows update in real time; others as of " +
                                        timeFmt.format(Date(rowsState.updatedAtMs))
                                else -> "Prices as of " + timeFmt.format(Date(rowsState.updatedAtMs))
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (allCachedLive && !rowsState.isRefreshing) GreenPrimary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                items(rowsState.rows, key = { it.symbol }) { row ->
                    WatchlistRowCard(
                        row = row,
                        // Prefer the real-time Angel One tick (null when feed off /
                        // no tick / non-INR — the card then renders exactly as before).
                        livePrice = liveBySymbol[row.symbol],
                        onOpen = { navController.navigate("research/" + Uri.encode(row.symbol)) },
                        onDelete = { vm.removeFromWatchlist(row.symbol) }
                    )
                }
            }
            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

/**
 * Groww-style row: COMPANY NAME primary (the user knows "Tata Motors", not
 * "TATAMOTORS"), ticker + exchange small and muted below; price + change% on
 * the right (green up / red down), one-line plain verdict underneath. Tapping
 * the card opens the full research page for the stock.
 *
 * [livePrice] non-null = a real-time Angel One tick for this stock right now —
 * it replaces the cached price and the row shows a small green "● Live" dot
 * (U1.6: the user always sees whether prices are fresh, in words). Null = the
 * feed is off or has no tick for this stock → the card behaves exactly as
 * before, honest "Prices as of h:mm" header included (fail-safe B8).
 */
@Composable
private fun WatchlistRowCard(row: WatchlistRow, livePrice: Double?, onOpen: () -> Unit, onDelete: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                // Company name is primary — a beginner recognises "Tata Motors",
                // not the ticker code. Blank name falls back to the symbol.
                val hasName = row.name.isNotBlank() && !row.name.equals(row.symbol, ignoreCase = true)
                Text(
                    if (hasName) row.name else row.symbol,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val tickerLine = buildString {
                    if (hasName) append(row.symbol)
                    if (row.exchange.isNotBlank()) {
                        if (isNotEmpty()) append(" · ")
                        append(row.exchange)
                    }
                }
                if (tickerLine.isNotEmpty()) {
                    Text(
                        tickerLine,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    row.verdictLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // Price + change% only when real numbers exist — never fake zeros (B6).
            // A live tick also counts as real numbers (it can arrive before the
            // cached refresh covers a stock).
            if (row.hasData || livePrice != null) {
                val shownPrice = livePrice ?: row.price
                // Re-anchor change% to the live tick so the number and colour agree
                // with the price shown: previous close is derived from the cached
                // price/change pair (prev = price / (1 + pct/100)), then the live
                // tick is measured against it. Falls back to the cached change%
                // when derivation is impossible (no cached data, or pct ≤ −100).
                val shownChangePct =
                    if (livePrice != null && row.hasData && row.price > 0.0 && row.changePct > -100.0) {
                        val prevClose = row.price / (1.0 + row.changePct / 100.0)
                        if (prevClose > 0.0) (livePrice - prevClose) / prevClose * 100.0 else row.changePct
                    } else row.changePct
                Spacer(modifier = Modifier.width(12.dp))
                Column(horizontalAlignment = Alignment.End) {
                    if (livePrice != null) {
                        // Small green dot: this row's price is updating in real time.
                        Text(
                            "● Live",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = GreenPrimary,
                            maxLines = 1
                        )
                    }
                    Text(
                        currencySymbol(row.currency) + fmtPrice(shownPrice, row.currency),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                    // Change% needs the cached baseline — with a live tick but no
                    // cached data yet there is no honest % to show, so show none (B6).
                    if (row.hasData) {
                        val up = shownChangePct >= 0
                        Text(
                            (if (up) "+" else "") + String.format(Locale.US, "%.2f", shownChangePct) + "%",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (up) GreenPrimary else RedPrimary,
                            maxLines = 1
                        )
                    }
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Remove ${row.symbol} from watchlist", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}
