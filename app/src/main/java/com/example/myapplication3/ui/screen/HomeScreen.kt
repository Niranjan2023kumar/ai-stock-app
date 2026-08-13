package com.example.myapplication3.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.example.myapplication3.intraday.*
import androidx.compose.ui.platform.LocalContext
import com.example.myapplication3.groww.GrowwLauncher
import com.example.myapplication3.groww.OrderType
import com.example.myapplication3.ui.component.GrowwActionRow
import com.example.myapplication3.ui.component.MyStockCard
import com.example.myapplication3.ui.component.NotificationsOffBanner
import com.example.myapplication3.ui.component.StockNewsSection
import com.example.myapplication3.ui.component.TodayPnlBar
import com.example.myapplication3.ui.component.computeTargetSplit
import com.example.myapplication3.ui.component.formatIndianRupees
import com.example.myapplication3.ui.theme.*
import com.example.myapplication3.ui.viewmodel.HomeUiState
import com.example.myapplication3.ui.viewmodel.HomeViewModel
import com.example.myapplication3.ui.viewmodel.InterdayViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

// Indices older than this show the "⚠️ Old" badge
private const val INDEX_STALE_AFTER_MS = 5 * 60 * 1000L

// ─── Formatting (never locale-default) ───────────────────────────────────────

private val localeIN = Locale("en", "IN")
private fun fmtPrice(v: Double): String  = String.format(localeIN, "%,.2f", v)
private fun fmtSigned(v: Double): String = String.format(localeIN, "%+,.2f", v)
private fun fmtPct(v: Double): String    = String.format(localeIN, "%+.2f%%", v)
// I9: TRUE Indian lakh/crore grouping via the ONE shared formatter — Java's
// Locale("en","IN") groups in threes (100,000), not the 1,00,000 users read.
private fun fmtRs(v: Double): String     = formatIndianRupees(v, 0)
// Millis → IST clock time (12-hour, e.g. "03:45 PM") for the freshness line.
private fun fmtIstHm(ms: Long): String {
    val sdf = java.text.SimpleDateFormat("hh:mm a", localeIN)
    sdf.timeZone = java.util.TimeZone.getTimeZone("Asia/Kolkata")
    return sdf.format(java.util.Date(ms))
}
// "T1/T2" is broker jargon — sell plans spell it out ("1st target - sell 2"),
// the same wording TradingScreen uses.
private fun ordinalLabel(i: Int): String = when (i) {
    1 -> "1st"; 2 -> "2nd"; 3 -> "3rd"; else -> "${i}th"
}

// Panel #1 (one market-state truth): name the day the shown prices actually come
// from — "prices from Friday's close" beats a vague "old prices". Holiday-aware
// via MarketCalendar; if today already traded and closed, it is "today's close".
// Falls back to "the last close" if no trading day is found within a week.
private fun lastTradingCloseLabel(): String {
    val tz  = java.util.TimeZone.getTimeZone("Asia/Kolkata")
    val cal = java.util.Calendar.getInstance(tz)
    val marketCal = com.example.myapplication3.core.common.MarketCalendar()
    fun tradedOn(c: java.util.Calendar): Boolean {
        val dow = c.get(java.util.Calendar.DAY_OF_WEEK)
        return dow != java.util.Calendar.SATURDAY && dow != java.util.Calendar.SUNDAY &&
            !marketCal.isHoliday(c.timeInMillis)
    }
    val nowMin = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
    if (tradedOn(cal) && nowMin >= 15 * 60 + 30) return "today's close"
    repeat(7) {
        cal.add(java.util.Calendar.DAY_OF_MONTH, -1)
        if (tradedOn(cal)) {
            val name = java.text.SimpleDateFormat("EEEE", Locale.ENGLISH)
                .apply { timeZone = tz }.format(cal.time)
            return "$name's close"
        }
    }
    return "the last close"
}

// ─── Main Screen ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(navController: NavController) {
    // ── Activity-scoped ViewModels — ONE instance shared across all bottom-nav
    // tabs (a plain hiltViewModel() would create a per-destination copy, each
    // running its own auto-refresh loop and duplicating every Yahoo fetch)
    val activity = androidx.compose.ui.platform.LocalContext.current as androidx.activity.ComponentActivity
    val interdayVm: InterdayViewModel = hiltViewModel(activity)
    val homeVm: HomeViewModel = hiltViewModel(activity)
    val interdayState by interdayVm.uiState.collectAsStateWithLifecycle()
    val homeState by homeVm.uiState.collectAsStateWithLifecycle()
    // Today's running P/L — pinned at the very top, visible at ALL times (B7b).
    val todayPnl by interdayVm.todayRealizedPnl.collectAsStateWithLifecycle()
    val openTrades by interdayVm.openTrades.collectAsStateWithLifecycle()
    val practiceModeTop by interdayVm.practiceMode.collectAsStateWithLifecycle()
    val dailyCapitalTop by interdayVm.dailyCapital.collectAsStateWithLifecycle()

    var showSearch by remember { mutableStateOf(false) }
    var showHelp   by remember { mutableStateOf(false) }
    // Re-open the budget question from the ✎ chip — a mis-tap is fixable (B7b).
    var showBudgetEditor by remember { mutableStateOf(false) }

    // ── Pending Groww order (survives process death): "Order placed? Yes/No" ──
    // Persisted to DataStore before Groww opened, so the confirm re-appears even
    // if Android killed the app while the user was placing the order (B0.2b).
    val pendingOrder by interdayVm.pendingOrder.collectAsStateWithLifecycle()
    val livePricesTop by interdayVm.livePrices.collectAsStateWithLifecycle()
    // Real-time feed state — one collection, shared by the pending dialog AND the
    // compact top header (the old separate "● Live" line is folded into the header).
    val liveConnected by interdayVm.liveConnected.collectAsStateWithLifecycle()
    // Market open/closed for the compact top header (same rule the decision card uses).
    // Clock+holiday calendar is the ONLY open/closed truth (v8 has no marketState;
    // the v7 field is dead and defaulted to CLOSED forever — see OneDecisionCard note).
    val marketOpenTop = RiskGuard.isTradingHoursNow()
    pendingOrder?.let { p ->
        com.example.myapplication3.ui.component.PendingOrderConfirmDialog(
            pending = p,
            // ONE market-state truth (panel #1): a closed market has NO live price
            // anywhere on this screen — the dialog must not show a "Live" tick either.
            livePrice = if (liveConnected && marketOpenTop) livePricesTop[p.symbol] else null,
            onConfirm = { price, qty -> interdayVm.confirmPendingOrder(price, qty) },
            onDismiss = { interdayVm.clearPendingOrder() }
        )
    }

    // ── FORCED no-internet popup — ONLY when there is NOTHING to show (C8/U5.2) ──
    // A fetch must have COMPLETED and FAILED *and* no cache or earlier success
    // exists (lastUpdated empty, no signals). When cached data exists the app
    // stays USABLE: the amber "Cached" header + the red refusal inside the
    // decision card carry the honesty — never a modal wall over usable data.
    // It stays composed across the 30s retries, so the voice line speaks ONCE
    // per outage instead of every refresh cycle.
    val offlineBlocked = interdayState.lastFetchFailed &&
        !interdayState.isUsingCachedData &&
        interdayState.lastUpdated.isEmpty() &&
        interdayState.signals.isEmpty()
    if (offlineBlocked) {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        // Speak the instruction once each time the popup appears
        LaunchedEffect(Unit) {
            interdayVm.speakText("Turn on internet. Without internet you will not get real prices.")
        }
        AlertDialog(
            onDismissRequest = { /* forced — cannot be dismissed by tapping outside */ },
            containerColor   = DarkCard,
            icon  = { Text("📡", fontSize = 34.sp) },
            title = {
                Text(
                    "Turn ON Internet",
                    color = RedPrimary, fontWeight = FontWeight.ExtraBold,
                    fontSize = 18.sp, lineHeight = 26.sp, textAlign = TextAlign.Center
                )
            },
            text = {
                Text(
                    // This popup only fires when there is NO saved data at all —
                    // there is no old price to fall back on, so say exactly that.
                    "The app has no prices to show yet. Turn on the internet once and it will start working.",
                    color = TextSecondary, fontSize = 13.sp, lineHeight = 19.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        runCatching {
                            val intent = if (android.os.Build.VERSION.SDK_INT >= 29)
                                android.content.Intent(android.provider.Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
                            else
                                android.content.Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS)
                            ctx.startActivity(intent)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = GoldAccent)
                ) {
                    Text("📶 Turn on Internet", color = TextOnGold, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { interdayVm.refresh() }) {
                    Text("↻ Try again", color = GoldAccent, fontWeight = FontWeight.SemiBold)
                }
            }
        )
    }

    if (showHelp) {
        HelpDialog(
            onDismiss = { showHelp = false },
            onSpeak   = {
                interdayVm.speakText(
                    "This app has four tabs at the bottom. Stock shows today's four top picks and the cheapest stocks. " +
                    "Intraday shows today's buy and sell signals. Mutual Fund lets you invest a little every month. " +
                    "Guide helps you learn the stock market. Tap the speaker button next to any stock and the app will read it out. " +
                    "Remember, this is not advice. Your money is your own responsibility."
                )
            }
        )
    }

    Scaffold(
        containerColor = DarkBackground,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Stocks",
                        fontWeight = FontWeight.Bold,
                        color      = TextPrimary
                    )
                },
                actions = {
                    IconButton(onClick = { showHelp = true }) {
                        Icon(
                            Icons.AutoMirrored.Filled.Help,
                            contentDescription = "Help",
                            tint = TextSecondary
                        )
                    }
                    IconButton(onClick = { showSearch = true }) {
                        Icon(Icons.Default.Search, contentDescription = "Search", tint = TextSecondary)
                    }
                    // Watchlist moved OFF the top bar (E3c) — its entry now lives in
                    // the see-more area below the finder, where browsing happens.
                    // Manual refresh — the one action users need when data looks stuck.
                    // Also refetches the indices, so the "⚠️ Old" NIFTY badge above it
                    // actually responds to this button.
                    IconButton(onClick = { interdayVm.refresh(); homeVm.refreshIndices() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = TextSecondary)
                    }
                    // Settings reachable from the MAIN tab (48dp target) — before,
                    // the gear existed only on the Intraday screen (audit gap).
                    IconButton(
                        onClick  = { navController.navigate(com.example.myapplication3.navigation.Screen.Settings.route) },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = TextSecondary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBackground)
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // ── Pinned header block — OPAQUE over its FULL bounds (panel fix:
                // sticky-header slicing). Scrolled list content must pass UNDER it
                // invisibly, never render half-cut glyphs through a gap; zIndex keeps
                // it above the LazyColumn sibling, and the divider below is the
                // subtle bottom hairline that ends the pinned area.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .zIndex(1f)
                        .background(DarkBackground)
                ) {
                    // ── Notification safety banner (I7) — only when alerts are denied.
                    // Without POST_NOTIFICATIONS the app cannot deliver stop-loss
                    // warnings, which silently breaks the core safety promise.
                    // Shared component (CommonComponents) — it also catches the
                    // silenced-channel case the old private copy missed.
                    NotificationsOffBanner()

                    // ── Battery-kill safety banner (B0.2d completion / U4.5) — the
                    // second half of the same promise: alerts also die when the OEM
                    // battery killer freezes the app. RE-CHECKS on every resume
                    // (exactly like NotificationsOffBanner) instead of the old
                    // one-time-dismissible card — protection that is still at risk
                    // must keep saying so. Dismiss hides it for TODAY only.
                    BatteryProtectionBanner()

                    // ── 0. Today's running P/L — pinned above the scroll (B7b) ──
                    // Practice results carry the PRACTICE label (B0.3b); the ✎ chip
                    // re-opens the budget question so a mis-tap is fixable (B7b).
                    TodayPnlBar(
                        realizedPnl = todayPnl,
                        openTradeCount = openTrades.size,
                        practiceMode = practiceModeTop,
                        budget = dailyCapitalTop,
                        onEditBudget = { showBudgetEditor = true }
                    )

                    // ── 1. Compact status header (B10/I8) — ONE thin row that replaces
                    // the old index strip + freshness line + "● Live" tag + market line,
                    // so the decision card is the first prominent thing on screen:
                    //   NIFTY points · one freshness dot · market open/closed
                    CompactStatusHeader(
                        indices         = homeState.indices,
                        fetchedAtMs     = homeState.indicesFetchedAtMs,
                        lastUpdatedText = interdayState.lastUpdated,
                        isCached        = interdayState.isUsingCachedData,
                        liveConnected   = liveConnected,
                        marketOpen      = marketOpenTop
                    )

                    HorizontalDivider(color = DarkBorder, thickness = 0.5.dp)
                }

                // ── 2. One simple scroll — no inner tabs, no placeholders ───
                ExploreTabContent(
                    navController,
                    interdayState,
                    interdayVm,
                    onOpenSearch = { showSearch = true },
                    suppressDialogs = offlineBlocked || pendingOrder != null,
                    forceBudgetDialog = showBudgetEditor,
                    onBudgetDialogClosed = { showBudgetEditor = false }
                )
            }

            // ── Global search overlay (search ANY stock in the world) ──────
            if (showSearch) {
                SearchOverlay(
                    state         = homeState,
                    onQueryChange = { homeVm.setSearchQuery(it) },
                    onClose       = {
                        showSearch = false
                        homeVm.clearSearch()
                    },
                    onResultClick = { symbol ->
                        showSearch = false
                        homeVm.clearSearch()
                        // Uri.encode — global symbols may contain '&' etc.
                        navController.navigate("research/${android.net.Uri.encode(symbol)}")
                    }
                )
            }
        }
    }
}

// ─── Help Dialog (very simple Hindi) ─────────────────────────────────────────

@Composable
private fun HelpDialog(onDismiss: () -> Unit, onSpeak: () -> Unit = {}) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = DarkCard,
        dismissButton = {
            TextButton(onClick = onSpeak) {
                Icon(
                    Icons.Default.VolumeUp, contentDescription = null,
                    tint = GoldAccent, modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text("Listen", color = GoldAccent, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
        },
        title = {
            Text(
                text       = "How to use this app?",
                color      = TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize   = 18.sp
            )
        },
        text = {
            Column(
                modifier            = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("📱 There are 4 tabs below:", color = GoldAccent, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text("• Stock — search stocks and see today's top 4 picks", color = TextSecondary, fontSize = 13.sp, lineHeight = 19.sp)
                Text("• Intraday — buy and sell today, see the app's signals", color = TextSecondary, fontSize = 13.sp, lineHeight = 19.sp)
                Text("• Mutual Fund — invest a little every month", color = TextSecondary, fontSize = 13.sp, lineHeight = 19.sp)
                Text("• Guide — learn about the stock market", color = TextSecondary, fontSize = 13.sp, lineHeight = 19.sp)
                HorizontalDivider(color = DarkBorder, thickness = 0.5.dp)
                Text(
                    "🔍 Open 'Cheapest / Costliest in the last year' to see stocks near their yearly low or high",
                    color = TextSecondary, fontSize = 13.sp, lineHeight = 19.sp
                )
                Text(
                    "🔊 Tap the speaker button next to any stock — the app will read it out",
                    color = TextSecondary, fontSize = 13.sp, lineHeight = 19.sp
                )
                Text(
                    "🤖 The app does the analysis for you — you just look",
                    color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 19.sp
                )
                Text(
                    "⚠️ This is not advice — your money is your own responsibility",
                    color = RedLight, fontSize = 13.sp, lineHeight = 19.sp
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Got it ✓", color = GoldAccent, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
        }
    )
}

// ─── Global Search Overlay ───────────────────────────────────────────────────

@Composable
private fun SearchOverlay(
    state: HomeUiState,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
    onResultClick: (String) -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    // 🎤 Voice search — illiterate users SPEAK the stock name instead of typing
    val speechLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val spoken = res.data
            ?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
        if (!spoken.isNullOrBlank()) onQueryChange(spoken)
    }
    val launchVoiceSearch: () -> Unit = {
        runCatching {
            speechLauncher.launch(
                android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                    )
                    putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
                    putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Speak — which stock to search?")
                }
            )
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color    = DarkBackground
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Search bar
            Row(
                modifier          = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClose) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = TextSecondary
                    )
                }
                OutlinedTextField(
                    value         = state.searchQuery,
                    onValueChange = onQueryChange,
                    modifier      = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester),
                    placeholder   = {
                        Text(
                            "Search any stock (RELIANCE, AAPL, TESLA...)",
                            fontSize = 12.sp,
                            color    = TextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    singleLine   = true,
                    shape        = RoundedCornerShape(12.dp),
                    trailingIcon = {
                        if (state.searchQuery.isNotEmpty()) {
                            IconButton(onClick = { onQueryChange("") }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear", tint = TextMuted)
                            }
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = GoldAccent,
                        unfocusedBorderColor = DarkBorder,
                        focusedTextColor     = TextPrimary,
                        unfocusedTextColor   = TextPrimary,
                        cursorColor          = GoldAccent
                    )
                )
                // Speak instead of typing
                IconButton(onClick = launchVoiceSearch) {
                    Icon(
                        Icons.Default.Mic,
                        contentDescription = "Search by voice",
                        tint = GoldAccent,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            // Voice hint for users who can't type
            Text(
                text     = "Can't type? Tap the mic and speak",
                fontSize = 11.sp,
                color    = GoldAccent,
                modifier = Modifier.padding(start = 60.dp, bottom = 6.dp)
            )

            HorizontalDivider(color = DarkBorder, thickness = 0.5.dp)

            when {
                state.isSearching -> {
                    Box(
                        modifier         = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = GoldAccent, modifier = Modifier.size(32.dp))
                    }
                }
                state.searchQuery.isBlank() -> {
                    Column(
                        modifier            = Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("🌍", fontSize = 36.sp)
                        Text(
                            text      = "Search any stock in the world",
                            fontSize  = 13.sp,
                            color     = TextSecondary,
                            textAlign = TextAlign.Center,
                            lineHeight = 19.sp
                        )
                        Text(
                            text      = "Like: RELIANCE, TATA, AAPL, TESLA, GOOGL",
                            fontSize  = 11.sp,
                            color     = TextMuted,
                            textAlign = TextAlign.Center
                        )
                    }
                }
                state.searchResults.isEmpty() -> {
                    Column(
                        modifier            = Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("🔍", fontSize = 32.sp)
                        Text(
                            text      = "No results",
                            fontSize  = 13.sp,
                            color     = TextSecondary,
                            textAlign = TextAlign.Center
                        )
                    }
                }
                else -> {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        itemsIndexed(state.searchResults) { i, result ->
                            SearchResultRow(result, isTopMatch = i == 0) { onResultClick(result.symbol) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultRow(result: StockSearchResult, isTopMatch: Boolean = false, onClick: () -> Unit) {
    if (isTopMatch) {
        Text(
            text     = "⭐ Best match — tap this",
            fontSize = 11.sp,
            color    = GoldAccent,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp)
        )
    }
    Row(
        modifier              = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier              = Modifier.weight(1f)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(DarkSurfaceElevated),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text       = result.symbol.take(2),
                    color      = GoldAccent,
                    fontWeight = FontWeight.Bold,
                    fontSize   = 13.sp
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text       = result.name,
                    fontSize   = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color      = TextPrimary,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis
                )
                Text(
                    text     = result.symbol,
                    fontSize = 11.sp,
                    color    = TextMuted
                )
            }
        }
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = DarkSurfaceElevated
        ) {
            Text(
                text     = result.exchange,
                fontSize = 11.sp,
                color    = GoldAccent,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
    HorizontalDivider(
        color     = DarkBorder,
        thickness = 0.3.dp,
        modifier  = Modifier.padding(horizontal = 16.dp)
    )
}

// ─── Compact status header (B10/I8) — two tight lines: NIFTY, then status ─────
// Line 1: NIFTY name · price · change.  Line 2: one freshness dot+label and the
// market open/closed dot. Both lines start at the SAME 16dp inset as the decision
// card below, so the content's left edge lines up all the way down the screen —
// NO full-bleed edge-to-edge, NO horizontal scroll. FlowRow wraps on a very narrow
// phone instead of clipping, so nothing ever runs off the right edge (the old
// horizontal-scroll cut "Cached · updated HH:MM" in half). Amber dot = cached/old;
// green = fresh/live. Re-checks age every 30s so it flips to "Cached" (5-min stale).

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CompactStatusHeader(
    indices: List<IndexQuote>,
    fetchedAtMs: Long,
    lastUpdatedText: String,
    isCached: Boolean,
    liveConnected: Boolean,
    marketOpen: Boolean
) {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            nowMs = System.currentTimeMillis()
        }
    }
    // Just ONE index — NIFTY is enough for a beginner (SENSEX removed, rule B10).
    val nifty = indices.firstOrNull { it.name.contains("NIFTY", ignoreCase = true) || it.symbol == "^NSEI" }
        ?: indices.firstOrNull()
    // Forced honesty — numbers stay on screen when a later fetch fails, so flag
    // anything cached or older than 5 minutes as stale (amber), never green.
    val isOld = fetchedAtMs > 0L && (nowMs - fetchedAtMs) > INDEX_STALE_AFTER_MS
    val stale = isCached || isOld
    val timeText = if (fetchedAtMs > 0L) fmtIstHm(fetchedAtMs)
                   else lastUpdatedText.removePrefix("Cached:").trim()
    val freshColor = if (stale) CautionAmber else GreenPrimary
    // ONE market-state truth (panel #1 — 7 flags): when the market is CLOSED there
    // is no such thing as "Live", ever — the whole status line collapses into one
    // honest sentence naming the day the prices come from (see the closed branch
    // below). "● Live" (green dot + word) may only appear while the market is OPEN
    // and the feed is fresh.
    val freshLabel = when {
        timeText.isBlank() && !liveConnected -> "Updating…"
        liveConnected && !stale              -> "Live"
        stale                                -> "Cached $timeText"
        else                                 -> "Updated $timeText"
    }

    // ONE consistent 16dp inset (same as the decision card) for the whole block —
    // the left edge lines up with every card below it, and no inner padding drifts it.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkBackground)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Line 1 (C1): WORDS first — a zero-knowledge user reads the MEANING, not
        // a number. The NIFTY numbers follow small; tense stays honest when closed.
        if (nifty != null) {
            val changeColor = if (nifty.isUp) GreenPrimary else RedPrimary
            val marketWords = when {
                marketOpen && nifty.isUp -> "Market is up today — look at the picks below"
                marketOpen               -> "Market is down today — be extra careful"
                nifty.isUp               -> "Market was up — see the plan below"
                else                     -> "Market was down — see the plan below"
            }
            Text(
                marketWords,
                fontSize = 13.sp, color = TextPrimary, fontWeight = FontWeight.SemiBold,
                lineHeight = 18.sp
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(nifty.name, fontSize = 11.sp, color = TextMuted, fontWeight = FontWeight.Medium)
                Text(fmtPrice(nifty.price), fontSize = 11.sp, color = TextSecondary, fontWeight = FontWeight.SemiBold)
                Text(
                    "${fmtSigned(nifty.change)} (${fmtPct(nifty.changePercent)})",
                    fontSize = 11.sp, color = changeColor, fontWeight = FontWeight.Medium
                )
            }
        } else {
            Text("Loading…", fontSize = 12.sp, color = TextMuted)
        }

        // Line 2 — ONE market-state truth (panel #1). CLOSED: a single sentence,
        // no freshness dot, no "Live" anywhere — closed and live cannot both be
        // true and 7 reviewers flagged exactly that contradiction. OPEN: the
        // freshness dot+label ("Live"/"Updated"/"Cached") plus "Market open".
        if (!marketOpen) {
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(TextMuted)
                )
                Text(
                    "Market closed — prices from ${lastTradingCloseLabel()}",
                    fontSize   = 12.sp,
                    color      = TextSecondary,
                    fontWeight = FontWeight.SemiBold
                )
            }
        } else FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            // Freshness (folds in the old "● Live" tag)
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(if (timeText.isBlank() && !liveConnected) TextMuted else freshColor)
                )
                Text(
                    freshLabel,
                    fontSize   = 12.sp,
                    color      = if (stale) CautionAmber else TextSecondary,
                    fontWeight = FontWeight.SemiBold
                )
            }
            // Market open
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(GreenPrimary)
                )
                Text(
                    "Market open",
                    fontSize   = 12.sp,
                    color      = TextSecondary,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

// (The private NotificationOffBanner copy was deleted — the screen now uses the
// SHARED NotificationsOffBanner from CommonComponents, which also catches the
// app-wide-toggle-off and silenced-channel cases this copy missed.)

// ─── Shared speak button (I5) — Material icon, 48dp touch target (rule B/Fix 3) ──
// Replaces the old 🔊 emoji buttons. Uses the same VolumeUp icon as the shared
// VoiceButton in CommonComponents, but at a 48dp target (VoiceButton is 40dp).
@Composable
private fun SpeakButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier.size(48.dp)) {
        Icon(
            imageVector        = Icons.Default.VolumeUp,
            contentDescription = "Listen",
            tint               = GoldAccent,
            modifier           = Modifier.size(24.dp)
        )
    }
}

// ─── Explore Tab ─────────────────────────────────────────────────────────────

@Composable
private fun ExploreTabContent(
    navController: NavController,
    state: InterdayUiState,
    vm: InterdayViewModel,
    onOpenSearch: () -> Unit = {},
    suppressDialogs: Boolean = false,
    forceBudgetDialog: Boolean = false,
    onBudgetDialogClosed: () -> Unit = {}
) {
    // Daily picks — the SAME 4 stocks all day; fall back to live signals if empty
    val topPicks = remember(state.dailyPicks, state.signals) {
        if (state.dailyPicks.isNotEmpty()) state.dailyPicks.take(4)
        else state.signals.sortedByDescending { it.confidence }.take(4)
    }
    val screenerBySymbol = remember(state.allScreenerItems) {
        state.allScreenerItems.associateBy { it.symbol }
    }
    // BOTH lists shown together (user requirement 1.2), ranked most-extreme first.
    // C6: the finder is FIXED to the 52-week (1-year) range — the period chips are
    // gone, so the distances come straight from each item's low52w/high52w bounds,
    // never from the screener's N-day setting. Within 5% of the yearly extreme =
    // "near" it; fallback: the 5 nearest. 5 per list — enough to act on, short
    // enough not to overwhelm.
    val finderLowList = remember(state.allScreenerItems) {
        val ranked = state.allScreenerItems
            .filter { it.currentPrice > 0.0 && it.low52w > 0.0 }
            .map { it to (it.currentPrice - it.low52w) / it.low52w * 100.0 }
            .sortedBy { it.second }
        ranked.filter { it.second <= 5.0 }.take(5).ifEmpty { ranked.take(5) }.map { it.first }
    }
    val finderHighList = remember(state.allScreenerItems) {
        val ranked = state.allScreenerItems
            .filter { it.currentPrice > 0.0 && it.high52w > 0.0 }
            .map { it to (it.high52w - it.currentPrice) / it.high52w * 100.0 }
            .sortedBy { it.second }
        ranked.filter { it.second <= 5.0 }.take(5).ifEmpty { ranked.take(5) }.map { it.first }
    }
    // Daily "how much today?" check-in + capital (B7b / B0.3a). Skippable, accepts
    // a custom amount, never shows on weekends/NSE holidays, and re-opens from the
    // ✎ chip on the P/L bar — a question, not a trap.
    val dailyCapital by vm.dailyCapital.collectAsStateWithLifecycle()
    val needsCheckIn by vm.needsCapitalCheckIn.collectAsStateWithLifecycle()
    // The decision card can ALSO open this same dialog when no money is set at
    // all — with budget 0 the P/L bar renders NO ₹ chip, so the card itself is
    // the way in (dead-end fix: "tap the ₹ chip" pointed at nothing).
    var askBudgetFromCard by remember { mutableStateOf(false) }
    if ((needsCheckIn && !suppressDialogs) || forceBudgetDialog || askBudgetFromCard) {
        DailyCheckInDialog(
            onPick = { vm.setDailyCapital(it); askBudgetFromCard = false; onBudgetDialogClosed() },
            onSkip = { vm.skipCheckInToday(); askBudgetFromCard = false; onBudgetDialogClosed() }
        )
    }
    // The user's saved money settings — sizing must use REAL numbers, not ₹10,000 (I3).
    val riskSettings by vm.riskSettings.collectAsStateWithLifecycle()
    // Market open/closed — the IST clock+holiday calendar is the ONLY truth. The old
    // marketState field came from Yahoo v7 (dead, 401) and the v8 fallback has no such
    // field, so it read "CLOSED" forever — the hero would refuse to decide all day.
    val marketOpen = RiskGuard.isTradingHoursNow()
    // Budget-fit (B7a): only show stocks the user can afford at least 1 share of.
    // Today's check-in first, else the capital saved in Settings — a user who set
    // money ONLY in Settings gets the same affordability filter, not the full list.
    val effectiveBudget = if (dailyCapital > 0) dailyCapital else riskSettings.capital.toInt()
    val budgetPicks = if (effectiveBudget > 0)
        topPicks.filter { it.currentPrice > 0.0 && it.currentPrice <= effectiveBudget.toDouble() }
    else topPicks
    val allUnaffordable = effectiveBudget > 0 && topPicks.isNotEmpty() && budgetPicks.isEmpty()
    // The hero = the ONE decision (shown big). The rest are optional chips (rule B10).
    val heroPick = budgetPicks.firstOrNull { it.action == SignalAction.BUY && it.currentPrice > 0 }
    val otherPicks = budgetPicks.filter { it.stockSymbol != heroPick?.stockSymbol }
    // Cheap/costly finder stays collapsed so it never competes with the decision.
    var finderExpanded by remember { mutableStateOf(false) }
    // Open trades drive the "Your stocks" section below (P/L bar lives at screen top).
    val openTrades by vm.openTrades.collectAsStateWithLifecycle()
    // Loss-limit protection (E3e) — stop suggesting trades after too much loss.
    val dailyBreached by vm.dailyLossBreached.collectAsStateWithLifecycle()
    val weeklyBreached by vm.weeklyLossBreached.collectAsStateWithLifecycle()
    // (B0.2d: the one-time battery card is gone — the RE-CHECKING
    // BatteryProtectionBanner in the pinned header replaced it.)
    // Practice vs Real money (B0.3b).
    val practiceMode by vm.practiceMode.collectAsStateWithLifecycle()
    // Real-time SmartAPI prices (when the user configured Angel One keys).
    val livePrices by vm.livePrices.collectAsStateWithLifecycle()
    val liveConnected by vm.liveConnected.collectAsStateWithLifecycle()
    // This week's honest wins/losses (E1a) — real closed trades, not a formula.
    val weekReport by vm.weekReport.collectAsStateWithLifecycle()
    // H13 caution mode — non-null ONLY when the system's recent record is poor;
    // rendered in place of the extra picks. Null = render nothing (stage-2 API).
    val cautionMessage by vm.cautionMessage.collectAsStateWithLifecycle()
    // E3a — one-line honest track record for the hero pick (stage-2 API).
    val heroTrustLine by vm.heroTrustLine.collectAsStateWithLifecycle()

    // ── After-loss trust card (P0 #1 / E1c / U0) — REAL loss today only ───────
    // Driven by the REAL, already-exposed todayRealizedPnl so the card lights up
    // the instant a real trade closes in a net loss today; practice losses never
    // trigger it (practiceMode gate) and a new day resets the P/L to 0 so it clears.
    // TODO(tracking agent): when InterdayViewModel exposes the precise per-trade
    // after-loss API (an afterLossMessage/afterLossToday built on OutcomeRecorder
    // that names the single last-closed losing trade + its stop), swap the
    // todayRealizedPnl<0 derivation below for it — here AND in TradingScreen — for
    // exact "the trade" wording. The card composable already accepts the ₹ amount.
    val todayRealizedPnl by vm.todayRealizedPnl.collectAsStateWithLifecycle()
    val afterLossAmount: Double? =
        if (!practiceMode && todayRealizedPnl < 0.0) abs(todayRealizedPnl) else null
    // Dismissible for the day (rememberSaveable survives rotation/process recreate).
    var afterLossDismissed by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        modifier            = Modifier.fillMaxSize().background(DarkBackground),
        contentPadding      = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        // ── After-loss trust card (P0 #1 / E1c) — near the very top, above the
        // decision, when today's REAL trades closed in a net loss. Reassures and
        // reframes the stop-loss as protection working; never scolds, never hides
        // the loss. Dismissible for the day. See AfterLossTrustCard for the data note.
        if (afterLossAmount != null && !afterLossDismissed) {
            val amt = afterLossAmount
            item {
                AfterLossTrustCard(
                    lossAmount = amt,
                    onSpeak = {
                        vm.speakText(
                            "Your safety stop worked. It closed the trade at ${amt.toInt()} rupees loss, " +
                            "instead of a bigger loss. The system is working. Tomorrow is a new day."
                        )
                    },
                    onDismiss = { afterLossDismissed = true }
                )
            }
        }

        // ── 📒 Suggestion record (U9.6/A5) — one quiet, right-aligned line above
        // the decision: the honest day-by-day PASS/FAIL diary of THIS tab's own
        // picks (the Stock record — the Intraday tab links to its own separate
        // record). Outlined + compact so it never competes with the decision
        // below (B10); 48dp touch target (I5). runCatching: if the ledger screen
        // is not registered yet, a tap can never crash the app (B8).
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.End
            ) {
                OutlinedButton(
                    onClick  = { runCatching { navController.navigate("ledger/STOCK") } },
                    modifier = Modifier.height(48.dp),
                    shape    = RoundedCornerShape(12.dp),
                    border   = BorderStroke(1.dp, DarkBorder),
                    colors   = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary)
                ) {
                    Text(
                        "📒 Suggestion record",
                        fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1
                    )
                }
            }
        }

        // ── 🥇 Today's decision FIRST (B10/I8) — the ONE prominent thing on screen.
        // Market open/closed now lives in the compact top header; the practice
        // toggle and weekly score moved BELOW this card (the battery warning is
        // the re-checking banner in the pinned header now).
        item {
            when {
                // Voice parity (U1.5): the stop card speaks the SAME words it shows.
                weeklyBreached -> LossLimitCard(weekly = true, onSpeak = {
                    vm.speakText(
                        "That's enough for this week. You've reached this week's safety limit. " +
                        "No more trades until next week. Protecting your money is the smart move. " +
                        "Your monthly investment keeps working. Saving is also earning."
                    )
                })
                dailyBreached  -> LossLimitCard(weekly = false, onSpeak = {
                    vm.speakText(
                        "That's enough for today. You've reached today's safety limit. " +
                        "No more trades today. Come back tomorrow. " +
                        "Your monthly investment keeps working. Saving is also earning."
                    )
                })
                else -> OneDecisionCard(
                    picks   = budgetPicks,
                    vm      = vm,
                    budget  = dailyCapital,
                    allUnaffordable = allUnaffordable,
                    practiceMode = practiceMode,
                    // Same ".NS"-strip + uppercase normalization as every other
                    // livePrices consumer — the raw Yahoo symbol never matched the
                    // Angel One feed key, so the hero card silently lost its
                    // real-time tick (audit bug). Gated on marketOpen too (panel #1):
                    // a closed market has NO live price, anywhere.
                    livePrice = if (liveConnected && marketOpen)
                        heroPick?.stockSymbol?.let { livePrices[it.removeSuffix(".NS").uppercase()] }
                    else null,
                    // Red refusal ONLY when a fetch completed and failed (stale cache);
                    // while the first fetch is still running we show "updating", not
                    // a false "No internet" alarm.
                    isStale = state.isUsingCachedData && state.lastFetchFailed,
                    isUpdating = state.isUsingCachedData && !state.lastFetchFailed,
                    // First launch, no cache: fetch still in flight → progress, not error.
                    isLoading = state.isLoading,
                    // REAL live data only: a successful fetch happened (lastUpdated set),
                    // nothing failed, and we are not showing cached values
                    hasLiveData = state.error == null && state.lastUpdated.isNotEmpty() && !state.isUsingCachedData,
                    lastUpdated = state.lastUpdated,
                    marketOpen = marketOpen,
                    riskPct = riskSettings.riskPerTradePercent,
                    fallbackCapital = riskSettings.capital.toInt(),
                    errorText = state.error,
                    // E3a: the VM's trust line describes the VM's hero (first daily
                    // pick) — only pass it when the card shows that SAME stock, so
                    // an honest sentence can never sit under the wrong pick.
                    trustLine = if (heroPick != null &&
                        heroPick.stockSymbol == state.dailyPicks.firstOrNull()?.stockSymbol)
                        heroTrustLine else null,
                    onAskBudget = { askBudgetFromCard = true }
                ) { sym -> navController.navigate("research/$sym") }
            }
        }

        // ── H13 caution mode — the system's own recent record is poor, so the
        // plain-words pause message renders where the extra picks would be (the
        // VM already caps the list to the single top pick while this is active).
        cautionMessage?.let { msg ->
            item {
                Surface(
                    shape    = RoundedCornerShape(12.dp),
                    color    = CautionContainer,
                    border   = BorderStroke(1.dp, CautionAmber.copy(alpha = 0.6f)),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Row(
                        modifier              = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("⚠️", fontSize = 16.sp)
                        Text(
                            msg,
                            fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            color = TextPrimary, lineHeight = 18.sp,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // ── 🔊 Whole-day voice plan (C3) — ONE big button near the decision card.
        // The zero-reading path: vm.speakDaySummary() reads the market mood and
        // every pick aloud, and refuses honestly on cached data.
        item {
            Button(
                onClick  = { vm.speakDaySummary() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp)
                    .height(48.dp),
                shape  = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = DarkSurfaceElevated,
                    contentColor   = GoldAccent
                )
            ) {
                Icon(Icons.Default.VolumeUp, contentDescription = null, tint = GoldAccent, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Listen to today's plan", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        }

        // ── B7a: honest count — when the budget filter dropped picks, say so in
        // plain words instead of silently showing a shorter list.
        if (!dailyBreached && !weeklyBreached && effectiveBudget > 0 &&
            budgetPicks.isNotEmpty() && budgetPicks.size < 4 && topPicks.size > budgetPicks.size
        ) {
            item {
                Text(
                    if (budgetPicks.size == 1) "Only 1 good stock fits your money today"
                    else "Only ${budgetPicks.size} good stocks fit your money today",
                    fontSize = 12.sp, color = TextSecondary, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                )
            }
        }

        // ── Your stocks — daily HOLD/SELL verdict for what you own (C7a) ─────
        if (openTrades.isNotEmpty()) {
            item {
                Text(
                    "Your stocks",
                    fontSize = 13.sp, color = TextPrimary, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)
                )
            }
            items(openTrades, key = { it.id }) { trade ->
                val ctx = LocalContext.current
                // Prefer the real-time Angel One tick; fall back to the Yahoo snapshot.
                val livePx = if (liveConnected)
                    livePrices[trade.symbol.removeSuffix(".NS").uppercase()] else null
                val px = livePx ?: screenerBySymbol[trade.symbol]?.currentPrice ?: 0.0
                MyStockCard(
                    trade = trade,
                    currentPrice = px,
                    onSell = { GrowwLauncher.openStock(ctx, trade.symbol) },
                    onMarkSold = { exit -> vm.closeTrackedTrade(trade.id, exit) },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
        }

        // ── More ideas — small OPTIONAL chips, never competing with the decision (B10) ──
        // Vanish entirely once a daily/weekly loss-limit is breached — ALL buy ideas must
        // disappear, not just the hero decision (B4 / E3e). Also hidden in H13 caution
        // mode: the pause message above replaces the extra-picks area.
        if (!dailyBreached && !weeklyBreached && cautionMessage == null && otherPicks.isNotEmpty()) {
            item {
                Text(
                    // When the market is closed these are tomorrow's plan, not buys (B7c)
                    if (marketOpen) "More ideas (optional)"
                    else "Picks ready for tomorrow — no buying now",
                    fontSize = 12.sp, color = TextMuted, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)
                )
            }
            item { OptionalPickChips(otherPicks) { sym -> navController.navigate("research/$sym") } }
        } else if (topPicks.isEmpty() && state.isLoading) {
            item {
                Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = GoldAccent, modifier = Modifier.size(28.dp))
                }
            }
        }

        // ── Below the decision (B10/I8): practice toggle and this-week score —
        // moved off the top so the decision leads the screen. (The battery setup
        // now lives as the re-checking BatteryProtectionBanner in the pinned header.)
        item { Spacer(modifier = Modifier.height(6.dp)) }
        // Practice vs Real toggle + banner (B0.3b) — never confuse fake with real money
        item { PracticeModeBar(practiceMode, onToggle = { vm.setPracticeMode(it) }) }
        // This week's honest score (E1a) — real wins/losses, the biggest trust line
        item { WeeklyReportLine(weekReport) }

        item { Spacer(modifier = Modifier.height(10.dp)) }
        item { HorizontalDivider(color = DarkBorder, thickness = 0.5.dp) }

        // ── Cheap/costly — behind ONE tap; collapsed so it never competes (B10) ──
        item {
            Surface(
                shape    = RoundedCornerShape(12.dp),
                color    = DarkCard,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                    .clickable { finderExpanded = !finderExpanded }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        // C6: fixed to the last-year range — no period choice to make.
                        Text("Cheapest / Costliest in the last year", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        Text("Optional — tap to open", fontSize = 11.sp, color = TextMuted)
                    }
                    Icon(
                        if (finderExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = null, tint = GoldAccent
                    )
                }
            }
        }

        if (finderExpanded) {
            // C6: the period chips are GONE — the finder is always the last year.
            // Neutral accent for the cheap/costly grouping (Fix 4) — green/red are
            // reserved for actual daily price direction only, so "LOW"/"HIGH"
            // no longer collide with up/down meaning. Arrows + words carry cheap vs costly.
            item { FinderListHeader("🔽 Cheapest in the last year", BlueAccent) }
            item {
                Text(
                    "⚠️ Cheap is not always good",
                    fontSize = 12.sp, color = TextMuted, modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
            if (finderLowList.isEmpty()) {
                item {
                    Text(
                        if (state.isLoading) "Searching…" else "No stock is near its 1-year low right now",
                        fontSize = 12.sp, color = TextMuted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            } else {
                itemsIndexed(finderLowList) { i, item ->
                    SimpleFinderRow(
                        item, isLow = true, rank = i + 1,
                        onSpeak = { vm.speakText("${item.symbol} is near its lowest price of the last year. Today's price is ${item.currentPrice.toInt()} rupees. It is in the cheap zone, but think before you buy.") }
                    ) { navController.navigate("research/${item.symbol}") }
                }
            }
            item { Spacer(modifier = Modifier.height(6.dp)) }
            item { FinderListHeader("🔼 Costliest in the last year", BlueAccent) }
            if (finderHighList.isEmpty()) {
                item {
                    Text(
                        if (state.isLoading) "Searching…" else "No stock is near its 1-year high right now",
                        fontSize = 12.sp, color = TextMuted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            } else {
                itemsIndexed(finderHighList) { i, item ->
                    SimpleFinderRow(
                        item, isLow = false, rank = i + 1,
                        onSpeak = { vm.speakText("${item.symbol} is near its highest price of the last year. Today's price is ${item.currentPrice.toInt()} rupees. It is costly now, be careful.") }
                    ) { navController.navigate("research/${item.symbol}") }
                }
            }
        }

        // ── Watchlist entry (E3c) — moved OFF the top bar into this see-more
        // area, where browsing (not the one decision) happens.
        item { Spacer(modifier = Modifier.height(8.dp)) }
        item {
            Surface(
                shape    = RoundedCornerShape(12.dp),
                color    = DarkCard,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clickable { navController.navigate(com.example.myapplication3.navigation.Screen.Watchlist.route) }
            ) {
                Row(
                    modifier              = Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("⭐", fontSize = 22.sp)
                    Column(modifier = Modifier.weight(1f)) {
                        Text("My favourite stocks", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        Text("Open your watchlist", fontSize = 11.sp, color = TextMuted)
                    }
                    Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = GoldAccent)
                }
            }
        }

        // ── Big search button — opens search on tap (not just a hint) ───────
        item { Spacer(modifier = Modifier.height(8.dp)) }
        item {
            Surface(
                shape    = RoundedCornerShape(12.dp),
                color    = DarkCard,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clickable { onOpenSearch() }
            ) {
                Row(
                    modifier              = Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("🌍", fontSize = 22.sp)
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Search any stock", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        Text("Tap here — you can also search by voice 🎤", fontSize = 11.sp, color = TextMuted)
                    }
                    Icon(Icons.Default.Search, null, tint = GoldAccent)
                }
            }
        }
    }
}

// ─── Daily "how much today?" check-in (B7b) — a question, not a trap ─────────

@Composable
private fun DailyCheckInDialog(onPick: (Int) -> Unit, onSkip: () -> Unit) {
    var customText by remember { mutableStateOf("") }
    val customAmount = customText.toIntOrNull() ?: 0
    AlertDialog(
        // Tapping outside = "Not today" — the user can always just look around.
        onDismissRequest = onSkip,
        containerColor = DarkCard,
        title = { Text("How much will you invest today?", color = TextPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Pick your budget for today. We'll only show what fits your money. You can change it any time from the ₹ chip on top.",
                    fontSize = 12.sp, color = TextSecondary
                )
                listOf(1_000, 5_000, 10_000, 25_000, 50_000).forEach { amt ->
                    Button(
                        onClick = { onPick(amt) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceElevated, contentColor = TextPrimary)
                    ) {
                        Text("₹${fmtRs(amt.toDouble())}", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }
                // Any amount works — the app fits itself to the user's real money (B7b)
                OutlinedTextField(
                    value = customText,
                    onValueChange = { v -> customText = v.filter { it.isDigit() }.take(8) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Or type your own amount (₹)", fontSize = 12.sp, color = TextMuted) },
                    leadingIcon = { Text("₹", fontWeight = FontWeight.Bold, color = TextPrimary) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = GoldAccent, unfocusedBorderColor = DarkBorder,
                        focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, cursorColor = GoldAccent
                    )
                )
            }
        },
        confirmButton = {
            TextButton(enabled = customAmount > 0, onClick = { onPick(customAmount) }) {
                Text("Save", color = if (customAmount > 0) GoldAccent else TextMuted, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onSkip) { Text("Not today", color = TextSecondary) }
        }
    )
}

// ─── This week's honest report (E1a) — truth builds trust, not a formula ─────
// One plain line of real closed-trade results: wins, losses and net rupees after
// the sale. When nothing has closed yet it says so honestly (never a fake "0 win").

@Composable
private fun WeeklyReportLine(report: com.example.myapplication3.tracking.WeekReport) {
    val net = report.netPnl
    val line = if (report.total == 0)
        "This week: no closed trades yet"
    else
        "This week: ${report.wins} right, ${report.losses} wrong, net ${if (net >= 0) "+₹" else "-₹"}${fmtRs(abs(net))}"
    Surface(
        color    = DarkCard,
        shape    = RoundedCornerShape(12.dp),
        border   = BorderStroke(0.5.dp, DarkBorder),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Row(
            modifier              = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("📅", fontSize = 16.sp)
            Text(
                line,
                fontSize   = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color      = if (report.total == 0) TextMuted else if (net >= 0) GreenPrimary else RedPrimary
            )
        }
    }
}

// ─── Practice vs Real money toggle (B0.3b) ───────────────────────────────────
// Panel #3 (mode fear): a full SENTENCE states which mode the user is in — no
// decoding "🧪 PRACTICE — fake money" shorthand. Practice = green (safe state),
// real money = amber (caution, U9.3). The whole card toggles on tap ("Tap to
// practice…" must be true), and the switch itself is labeled "Practice mode".

@Composable
private fun PracticeModeBar(practice: Boolean, onToggle: (Boolean) -> Unit) {
    Surface(
        color  = if (practice) GreenContainer else CautionContainer,
        shape  = RoundedCornerShape(12.dp),
        border = BorderStroke(
            1.dp,
            if (practice) GreenPrimary.copy(alpha = 0.5f) else CautionAmber.copy(alpha = 0.6f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable { onToggle(!practice) }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (practice) "You are in PRACTICE mode — only fake money is used."
                    else "You are in REAL MONEY mode. Tap to practice with fake money instead.",
                    fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    color = if (practice) GreenLight else TextPrimary,
                    lineHeight = 18.sp
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // Explicit colors — the default OFF track was white-on-white invisible
                // on this light theme (screenshot audit), so the switch looked missing.
                androidx.compose.material3.Switch(
                    checked = practice,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor    = Color.White,
                        checkedTrackColor    = GreenPrimary,
                        checkedBorderColor   = GreenPrimary,
                        uncheckedThumbColor  = TextMuted,
                        uncheckedTrackColor  = DarkSurfaceElevated,
                        uncheckedBorderColor = DarkBorder
                    )
                )
                // The switch is never a mystery control — it carries its own label.
                Text("Practice mode", fontSize = 10.sp, color = TextSecondary, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// ─── Battery-kill safety banner (B0.2d completion / U4.5) ────────────────────
// Replaces the old ONE-TIME-dismissible BatteryGuideCard: a card the user could
// dismiss forever while the phone still kills background alerts is a silent
// broken promise. This banner RE-CHECKS the real PowerManager state on every
// resume (same pattern as NotificationsOffBanner) and stays visible while
// protection is at risk. Amber = caution (U9.3). Tapping it fires the system
// "allow background" request (BatteryGuard.requestIntent); ✕ hides it for
// TODAY only — tomorrow it asks again, because the risk is still real.

@Composable
private fun BatteryProtectionBanner() {
    val ctx = LocalContext.current
    var atRisk by remember {
        mutableStateOf(com.example.myapplication3.tracking.BatteryGuard.isProtectionAtRisk(ctx))
    }
    // On-resume re-check — the user goes to the system dialog/settings and the
    // banner must vanish the moment they come back with protection granted.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME)
                atRisk = com.example.myapplication3.tracking.BatteryGuard.isProtectionAtRisk(ctx)
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    // Dismiss hides for the DAY only (IST day, like every other daily state) —
    // rememberSaveable survives rotation/process recreation; a new day re-shows.
    var dismissedDay by rememberSaveable { mutableStateOf("") }
    val todayIst = java.text.SimpleDateFormat("yyyyMMdd", Locale.ENGLISH)
        .apply { timeZone = java.util.TimeZone.getTimeZone("Asia/Kolkata") }
        .format(java.util.Date())
    if (!atRisk || dismissedDay == todayIst) return

    Surface(
        color    = CautionContainer,
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                // Direct system "allow background" dialog; if the OEM rejects it,
                // fall back to the battery-optimization list screen (B8: never
                // crash, never a dead tap).
                runCatching {
                    ctx.startActivity(com.example.myapplication3.tracking.BatteryGuard.requestIntent(ctx))
                }.onFailure {
                    runCatching {
                        ctx.startActivity(
                            android.content.Intent(
                                android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
                            )
                        )
                    }
                }
            }
    ) {
        Row(
            modifier              = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("🔋", fontSize = 14.sp)
            Column(Modifier.weight(1f)) {
                Text(
                    "Your phone may stop the app's alerts. Tap to allow — needed to protect your money.",
                    fontSize   = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color      = TextPrimary,
                    lineHeight = 16.sp
                )
                // B0.2d: Indian OEM skins kill background apps through extra doors the
                // system "allow background" dialog does not cover (Autostart + lock in
                // Recent apps). Give EACH named brand its own exact steps in plain words,
                // so a Xiaomi/Vivo/OnePlus/Samsung user is not left with alerts silently
                // frozen. Unknown brands just get the generic tap above.
                val maker = android.os.Build.MANUFACTURER.lowercase(Locale.ENGLISH)
                val brandTip = when {
                    "xiaomi" in maker || "redmi" in maker || "poco" in maker ->
                        "On Xiaomi / Redmi / Poco: open Settings > Apps > manage this app > turn ON Autostart. Then open Recent apps, pull this app down and tap the lock so the phone does not close it."
                    "vivo" in maker || "iqoo" in maker ->
                        "On Vivo / iQOO: open Settings > Battery > Background power consumption > allow this app, turn ON Auto-start, and lock the app in Recent apps."
                    "oppo" in maker || "realme" in maker ->
                        "On Oppo / Realme: open Settings > Battery (or App management) > allow Auto-start for this app, and lock the app in Recent apps so it is not cleared."
                    "oneplus" in maker ->
                        "On OnePlus: open Settings > Battery > Battery optimization > set this app to 'Don't optimize', and lock the app in Recent apps."
                    "samsung" in maker ->
                        "On Samsung: open Settings > Battery > Background usage limits > add this app to 'Never sleeping apps', and lock it in Recent apps."
                    else -> null
                }
                if (brandTip != null) {
                    Text(
                        brandTip,
                        fontSize   = 11.sp,
                        color      = TextSecondary,
                        lineHeight = 15.sp
                    )
                }
            }
            // Hide for TODAY only — 32dp like the after-loss card's dismiss.
            IconButton(onClick = { dismissedDay = todayIst }, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.Close, contentDescription = "Hide for today",
                    tint = TextMuted, modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

// ─── Loss-limit stop (E3e) — protection that can't be switched off by mistake ─
// Voice parity (U1.5): Trading's loss-limit card speaks — Home's must too, with
// the SAME words the screen shows, via the shared SpeakButton + vm.speakText.

@Composable
private fun LossLimitCard(weekly: Boolean, onSpeak: () -> Unit = {}) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        border = BorderStroke(1.dp, RedPrimary.copy(alpha = 0.6f))
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier              = Modifier.fillMaxWidth(),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    if (weekly) "🛑 That's enough for this week" else "🛑 That's enough for today",
                    fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = RedPrimary,
                    modifier = Modifier.weight(1f)
                )
                SpeakButton(onClick = onSpeak)
            }
            Text(
                if (weekly)
                    "You've reached this week's safety limit. No more trades until next week — protecting your money is the smart move."
                else
                    "You've reached today's safety limit. No more trades today. Come back tomorrow — saving your money is also earning.",
                fontSize = 13.sp, color = TextSecondary, lineHeight = 18.sp
            )
            // Never a dead end (B9): point to the one thing still quietly working.
            Text(
                "Your monthly investment keeps working — saving is also earning.",
                fontSize = 12.sp, color = GreenPrimary, fontWeight = FontWeight.SemiBold, lineHeight = 17.sp
            )
        }
    }
}

// ─── After-loss trust card (P0 #1 / E1c / U0) — the emotional-safety card ─────
// The single biggest trust gap: after a losing trade the app must NOT go silent
// or blame the user. This calm, green, dismissible card reframes the stop-loss as
// protection that worked (U9.6 honesty + protection, never blame) and points to a
// fresh tomorrow. Green (the safe-state colour, U9.3), never red — a loss already
// happened; this card is reassurance, not another alarm. Carries a SpeakButton so
// the zero-reading user hears the same words (U1.5). The ₹ amount is fed in, so
// swapping to the precise per-trade tracking API later touches only the caller.
@Composable
private fun AfterLossTrustCard(
    lossAmount: Double,
    onSpeak: () -> Unit,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        shape  = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = GreenContainer),
        border = BorderStroke(1.dp, GreenPrimary.copy(alpha = 0.6f))
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("🛡️", fontSize = 18.sp)
                Text(
                    "Your safety stop worked",
                    fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = GreenLight,
                    modifier = Modifier.weight(1f)
                )
                // Respectful — the user can hide it for today, never forced to stare at a loss.
                IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.Close, contentDescription = "Hide for today",
                        tint = TextMuted, modifier = Modifier.size(18.dp)
                    )
                }
            }
            Text(
                "It closed the trade at -₹${fmtRs(lossAmount)} instead of a bigger loss. " +
                "The system is working. Tomorrow is a new day.",
                fontSize = 13.sp, color = TextPrimary, lineHeight = 19.sp
            )
            SpeakButton(onClick = onSpeak)
        }
    }
}

// ─── Optional pick chips (the non-hero picks — rule B10) ─────────────────────

// Panel #5: raw ticker codes (DLF, OBEROIRLTY) mean nothing to a zero-knowledge
// user (U9.1) — lead with the company NAME. Shortened: corporate suffixes
// stripped, at most 2 words. Falls back to the ticker if the name is empty.
private fun shortStockName(name: String, fallback: String): String {
    val noise = setOf("LIMITED", "LTD", "LTD.", "LIMITED.")
    val words = name.trim().split(Regex("\\s+"))
        .filter { it.uppercase().trimEnd('.', ',') !in noise }
    return words.take(2).joinToString(" ").ifBlank { fallback }
}

@Composable
private fun OptionalPickChips(picks: List<TradingSignal>, onClick: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        picks.forEach { p ->
            val up = p.action == SignalAction.BUY
            val edge = (if (up) GreenPrimary else RedPrimary).copy(alpha = 0.45f)
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = DarkSurfaceElevated,
                border = BorderStroke(1.dp, edge),
                modifier = Modifier.clickable { onClick(p.stockSymbol) }
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    // Company name leads (panel #5); the chip sizes to its content
                    // inside a horizontal scroll, so the name is NEVER ellipsized
                    // into meaninglessness — it always renders in full.
                    Text(
                        shortStockName(p.stockName, p.stockSymbol),
                        fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimary,
                        maxLines = 1, softWrap = false
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        // Ticker demoted: small + muted, under the real name — and
                        // hidden entirely when it would just repeat the name ("DLF DLF").
                        val displayName = shortStockName(p.stockName, p.stockSymbol)
                        if (!displayName.equals(p.stockSymbol, ignoreCase = true)) {
                            Text(p.stockSymbol, fontSize = 10.sp, color = TextMuted, maxLines = 1)
                        }
                        Text("₹${fmtRs(p.currentPrice)}", fontSize = 11.sp, color = TextSecondary, maxLines = 1)
                    }
                }
            }
        }
    }
}

// ─── 🥇 Today's decision — the ONE decision, all math done by the app ─────────────

@Composable
private fun OneDecisionCard(
    picks: List<TradingSignal>,
    vm: InterdayViewModel,
    budget: Int = 0,
    allUnaffordable: Boolean = false,
    practiceMode: Boolean = false,
    livePrice: Double? = null,
    isStale: Boolean = false,
    isUpdating: Boolean = false,
    hasLiveData: Boolean = true,
    lastUpdated: String = "",
    marketOpen: Boolean = true,
    riskPct: Double = 1.0,
    fallbackCapital: Int = 0,
    errorText: String? = null,
    isLoading: Boolean = false,
    trustLine: String? = null,
    onAskBudget: () -> Unit = {},
    onOpen: (String) -> Unit
) {
    // Best actionable pick = highest-confidence BUY (picks arrive confidence-sorted)
    val best = picks.firstOrNull { it.action == SignalAction.BUY && it.currentPrice > 0 }
    // The user's money: today's check-in first, else the capital saved in Settings
    // (UserSettingsStore) — never an invented ₹10,000 default (I3).
    val amount = if (budget > 0) budget else fallbackCapital
    // Dead-end fix: with no money set the P/L bar shows NO ₹ chip, so the old
    // "tap the ₹ chip on top" pointed at nothing. Exactly when the ask-for-money
    // branch below renders, the whole card becomes the button and opens the same
    // daily check-in dialog the app already has.
    val asksForMoney = amount <= 0 && !isStale && !isUpdating && hasLiveData && marketOpen

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .then(
                if (asksForMoney)
                    Modifier.clip(RoundedCornerShape(16.dp)).clickable { onAskBudget() }
                else Modifier
            ),
        shape  = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        // Red alarm border only while the market is OPEN — closed nights/weekends
        // show a calm final answer, not an emergency (panel #4).
        border = BorderStroke(1.dp, if (isStale && marketOpen) RedPrimary.copy(alpha = 0.6f) else GoldAccent.copy(alpha = 0.5f))
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("🥇 Today's decision — just do this", fontSize = 13.sp, color = GoldAccent, fontWeight = FontWeight.Bold)

            // ── Market closed (B4/B7c + panel #4) — checked FIRST, before any
            // stale/updating branch: after hours there is no fetch worth waiting
            // for and NO "getting fresh prices" spinner — the saved data already
            // holds the final answer, so say it immediately. Also kills the
            // after-hours "Buy now" problem: a working Groww button at night sends
            // the user into a gapped-open order the shown math no longer fits.
            if (!marketOpen) {
                Text(
                    "Market closed — opens ${RiskGuard.nextMarketOpenLabel()}. Nothing to do now.",
                    fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary, lineHeight = 20.sp
                )
                // B7c: a plain countdown — hours a person can feel, days across a
                // weekend/holiday. Recomputed on every recomposition (cheap calendar
                // math) so an overnight screen never shows a frozen number.
                val opensInLabel = run {
                    val ms = com.example.myapplication3.core.common.MarketCalendar().nextMarketOpenMs() -
                        System.currentTimeMillis()
                    val hrs = kotlin.math.ceil(ms / 3_600_000.0).toInt().coerceAtLeast(1)
                    when {
                        hrs >= 42 -> "about ${(hrs + 12) / 24} days"
                        hrs >= 22 -> "about a day"
                        hrs == 1  -> "about 1 hour"
                        else      -> "about $hrs hours"
                    }
                }
                Text(
                    "The market opens again in $opensInLabel.",
                    fontSize = 13.sp, color = TextSecondary, lineHeight = 18.sp
                )
                if (best != null) {
                    Text(
                        "${shortStockName(best.stockName, best.stockSymbol)} is ready for tomorrow — no buying now.",
                        fontSize = 13.sp, color = TextSecondary, lineHeight = 18.sp
                    )
                    TextButton(onClick = { onOpen(best.stockSymbol) }, contentPadding = PaddingValues(0.dp)) {
                        Text("See tomorrow's plan ›", color = GoldAccent, fontSize = 12.sp)
                    }
                }
                // Never empty-handed (B9/B7c): point to what IS working right now.
                Text(
                    "Your monthly investment keeps working — see the Mutual Fund tab.",
                    fontSize = 13.sp, color = TextSecondary, lineHeight = 18.sp
                )
                // B7c: ONE contextual line chosen by the IST clock — honest, static
                // pointers to the Guide (no fake data, no live claims). At night the
                // truthful fact is that US markets trade while India sleeps; in the
                // daytime (weekend/holiday) it is that gold and SIP don't need the
                // NSE to be open. Recomputed each recomposition — cheap clock math.
                val istHour = java.util.Calendar.getInstance(
                    java.util.TimeZone.getTimeZone("Asia/Kolkata")
                ).get(java.util.Calendar.HOUR_OF_DAY)
                Text(
                    if (istHour >= 20 || istHour < 8)
                        "US markets are open at night — see Guide > Advanced > More ways."
                    else
                        "Gold and monthly investments keep working on closed days — see Guide > Advanced.",
                    fontSize = 12.sp, color = TextMuted, lineHeight = 17.sp
                )
                return@Card
            }

            // HARD RULE: no internet → refuse to decide on old prices.
            if (isStale) {
                Text(
                    "No internet — I won't give a decision on old prices.",
                    fontSize = 14.sp, fontWeight = FontWeight.Bold, color = RedPrimary, lineHeight = 20.sp
                )
                if (lastUpdated.isNotEmpty()) {
                    Text(lastUpdated, fontSize = 11.sp, color = TextMuted)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { vm.refresh() },
                        colors  = ButtonDefaults.buttonColors(containerColor = GoldAccent),
                        shape   = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp), tint = TextOnGold)
                        Spacer(Modifier.width(6.dp))
                        Text("Try again", color = TextOnGold, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                    SpeakButton(onClick = { vm.speakText("No internet. I won't decide on old prices. Turn on internet and try again.") })
                }
                return@Card
            }

            // First fetch still running on top of cache — honest "updating", never a
            // false "No internet" alarm while the network is actually working (B2).
            if (isUpdating) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(color = GoldAccent, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    Text(
                        "Old prices — getting fresh ones now…",
                        fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimary
                    )
                }
                if (lastUpdated.isNotEmpty()) {
                    Text(lastUpdated, fontSize = 11.sp, color = TextMuted)
                }
                return@Card
            }

            // No REAL live data — never claim "Live price" when nothing was fetched.
            if (!hasLiveData) {
                // First launch with no cache: while the FIRST fetch is still running
                // this is progress, not an error — never a false "No data yet" +
                // Try-again flash before the network even answered (audit fix).
                if (isLoading || (errorText.isNullOrBlank() && lastUpdated.isEmpty())) {
                    Row(
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(color = GoldAccent, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                        Text(
                            "Getting today's prices…",
                            fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimary
                        )
                    }
                    return@Card
                }
                // A fetch COMPLETED and failed — now "No data yet" is the truth.
                Text(
                    "No data yet",
                    fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary, lineHeight = 20.sp
                )
                // Say WHY + what to do next in one line (I6/B9), never a bare dead end.
                if (!errorText.isNullOrBlank()) {
                    Text(errorText, fontSize = 12.sp, color = RedPrimary, lineHeight = 17.sp)
                }
                Button(
                    onClick = { vm.refresh() },
                    colors  = ButtonDefaults.buttonColors(containerColor = GoldAccent),
                    shape   = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp), tint = TextOnGold)
                    Spacer(Modifier.width(6.dp))
                    Text("Try again", color = TextOnGold, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
                return@Card
            }

            // (Market-closed handled FIRST at the top of this card — panel #4.)
            // Freshness now lives ONCE in the compact top header (no in-card duplicate).

            // Money not set at all (no check-in, no Settings capital) — ask, don't
            // invent. There is NO ₹ chip on the P/L bar in this state, so the card
            // itself is tappable (asksForMoney above) and opens the check-in dialog.
            if (amount <= 0) {
                Text(
                    "Tap here to tell me your money.",
                    fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary, lineHeight = 20.sp
                )
                Text(
                    "I will only show stocks that fit your money.",
                    fontSize = 12.sp, color = TextSecondary, lineHeight = 17.sp
                )
                return@Card
            }

            // Capital-path (B0.3a): small money should not trade shares — charges eat it.
            if (capitalPathFor(amount) == CapitalPath.SIP_ONLY) {
                Text(
                    "Your money is small right now. Buying and selling shares would lose most of it to charges.",
                    fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary, lineHeight = 20.sp
                )
                Text(
                    "The safest way to grow ₹${fmtRs(amount.toDouble())} is a monthly investment. Tap 'Mutual Fund' below.",
                    fontSize = 13.sp, color = TextSecondary, lineHeight = 18.sp
                )
                return@Card
            }

            // Budget-fit (B7a): the good stocks today all cost more than the user's money.
            if (allUnaffordable) {
                Text(
                    "Today's best stocks cost more than ₹${fmtRs(amount.toDouble())}.",
                    fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary, lineHeight = 20.sp
                )
                Text(
                    "Add a little more money, or grow it with a monthly investment (tap 'Mutual Fund' below).",
                    fontSize = 13.sp, color = TextSecondary, lineHeight = 18.sp
                )
                return@Card
            }

            if (best == null) {
                Text(
                    "Don't buy today — no strong opportunity. Saving money is also earning.",
                    fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary, lineHeight = 20.sp
                )
                // Never a dead end (B9): always point to what IS possible right now.
                Text(
                    "Grow your money safely with a monthly investment — tap 'Mutual Fund' below.",
                    fontSize = 13.sp, color = TextSecondary, lineHeight = 18.sp
                )
                return@Card
            }

            // Effective price = true real-time tick when the Angel One feed is live,
            // else the latest Yahoo snapshot. All the money math below uses this.
            val effPrice = livePrice ?: best.currentPrice

            // Stale-entry protection (C12a): the entry zone was FIXED when the pick
            // first appeared today — if the price ran past it (or the setup died),
            // never re-anchor the chase; the chance is gone.
            if (best.buyChancePassed(effPrice) || best.chanceGone) {
                Text(
                    "This chance passed — wait for the next one.",
                    fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary, lineHeight = 20.sp
                )
                return@Card
            }

            // H6 sizing from the user's REAL money: what it can afford, capped by
            // the ₹-risk rule (risk% of capital ÷ per-share stop distance), floored.
            val afford   = (amount / effPrice).toInt()
            val stopDist = effPrice - best.stopLoss
            val riskQty  = if (stopDist > 0.0) (amount * riskPct / 100.0 / stopDist).toInt() else 0
            val qty      = minOf(afford, riskQty)
            // Profit shown AFTER the honest round-trip charge estimate (B0.3a) — a gross
            // gain smaller than brokerage + STT + GST is a real-money loss dressed as a gain.
            // This is a DELIVERY order below, so use the delivery cost rate.
            val charges = best.estimatedCostFor(qty, intraday = false)
            val profit  = qty * (best.targetPrice - effPrice) - charges
            val loss    = qty * (effPrice - best.stopLoss)

            if (afford < 1) {
                // B7a — never pretend an unaffordable share is buyable
                Text(
                    "This share is too costly for your money (1 = ₹${fmtRs(effPrice)}) — see the other picks.",
                    fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimary, lineHeight = 19.sp
                )
                // Never a dead end (B9/U1.4): also point to what IS possible right now.
                Text(
                    "Or grow your money safely with a monthly investment — tap 'Mutual Fund' below.",
                    fontSize = 13.sp, color = TextSecondary, lineHeight = 18.sp
                )
                return@Card
            }
            if (qty < 1) {
                Text(
                    "Even 1 share risks more than your safety limit — skip this one today.",
                    fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimary, lineHeight = 19.sp
                )
                // Never a dead end (B9/U1.4): point to what IS possible right now.
                Text(
                    "Grow your money safely with a monthly investment — tap 'Mutual Fund' below.",
                    fontSize = 13.sp, color = TextSecondary, lineHeight = 18.sp
                )
                return@Card
            }
            if (profit <= 0) {
                Text(
                    "Don't buy today — the profit is too small.",
                    fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimary, lineHeight = 19.sp
                )
                // Never a dead end (B9/U1.4): point to what IS possible right now.
                Text(
                    "Grow your money safely with a monthly investment — tap 'Mutual Fund' below.",
                    fontSize = 13.sp, color = TextSecondary, lineHeight = 18.sp
                )
                return@Card
            }

            // F8.2 — 10-second order: 1. Action  2. Name  3. How much  4. Profit  5. Safety stop  6. Button
            // 1. Action — large green BUY headline
            Text(
                "BUY",
                fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, color = GreenPrimary
            )

            // 2. Stock name — plain, no .NS suffix, no jargon
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    shortStockName(best.stockName, best.stockSymbol),
                    fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = TextPrimary
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SpeakButton(onClick = {
                        vm.speakText(
                            "Today's decision. Buy $qty shares of ${shortStockName(best.stockName, best.stockSymbol)} at ${effPrice.toInt()} rupees each. " +
                            "Profit after charges: about ${profit.toInt()} rupees. " +
                            "Sell if the price falls to ${best.stopLoss.toInt()} rupees — safety stop. This is not advice."
                        )
                    })
                    TextButton(onClick = { onOpen(best.stockSymbol) }) {
                        Text("Details ›", color = GoldAccent, fontSize = 12.sp)
                    }
                }
            }

            // 3. How much — total spend + share count
            Text(
                "Spend about ₹${fmtRs(qty * effPrice)} — buy $qty shares at ₹${fmtRs(effPrice)} each",
                fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary, lineHeight = 20.sp
            )

            // 4. Profit after charges (positive — already checked above)
            Text(
                "Profit after charges: about +₹${fmtRs(profit)} (sell at ₹${fmtRs(best.targetPrice)})",
                fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimary, lineHeight = 18.sp
            )

            // 5. Safety stop — plain language, no "stop-loss" jargon
            Text(
                "Sell if it falls to ₹${fmtRs(best.stopLoss)} (safety stop — the app alerts you)",
                fontSize = 13.sp, color = TextSecondary, lineHeight = 18.sp
            )

            // 6. ONE button: place order in Groww
            if (practiceMode) {
                // Practice: record a fake-money trade directly, no real Groww order.
                Button(
                    onClick = { vm.trackTrade(best, effPrice, OrderType.DELIVERY, qty) },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = GoldAccent, contentColor = TextOnGold)
                ) { Text("Practice buy — virtual money", fontWeight = FontWeight.Bold) }
            } else {
                // Persist the pending-order marker BEFORE Groww opens (locked decision
                // #3): if Android kills us while the user places the order, the
                // "Order placed?" confirm — price AND quantity — still re-appears.
                GrowwActionRow(
                    symbol = best.stockSymbol,
                    orderType = OrderType.DELIVERY,
                    suggestedPrice = effPrice,
                    externalConfirm = true,
                    onLaunch = { vm.startPendingOrder(best, OrderType.DELIVERY, effPrice, qty) }
                )
            }

            // ── Supporting context (B3): setup, risk, sell plan, track record ─────
            // Shown BELOW the main decision so they don't delay the 10-second read.
            val setupWord = when {
                best.confidence >= 90 -> "Strong setup"
                best.confidence >= 84 -> "Okay setup"
                else                  -> "Weak setup"
            }
            val setupColor = when {
                best.confidence >= 90 -> GreenPrimary
                best.confidence >= 84 -> TextSecondary
                else                  -> CautionAmber
            }
            val riskWord = when (best.riskLevel) {
                RiskLevel.LOW    -> if (best.isBeginnerSafe) "LOW risk — Beginner Safe" else "LOW risk"
                RiskLevel.MEDIUM -> "MEDIUM risk"
                RiskLevel.HIGH   -> "HIGH risk"
            }
            val riskColor = when (best.riskLevel) {
                RiskLevel.LOW    -> GreenPrimary
                RiskLevel.MEDIUM -> CautionAmber
                RiskLevel.HIGH   -> RedPrimary
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Text(setupWord, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = setupColor)
                Text("·", fontSize = 12.sp, color = TextMuted)
                Text(riskWord, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = riskColor)
            }
            val plainReasons = best.reasons.take(2).joinToString(" · ")
            if (plainReasons.isNotBlank()) {
                Text(
                    plainReasons,
                    fontSize = 11.sp, color = TextSecondary, lineHeight = 15.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
            }
            if (trustLine != null) {
                Text(trustLine, fontSize = 11.sp, color = TextMuted, lineHeight = 15.sp)
            }
            val exits = computeTargetSplit(qty, best.targetPrice, best.target2, best.target3)
            if (exits.size > 1) {
                Text(
                    "Sell plan: " + exits.joinToString(", ") { "${ordinalLabel(it.index)} target - sell ${it.shares}" },
                    fontSize = 11.sp, color = TextSecondary, lineHeight = 15.sp
                )
            }

            Text("⚠️ No guarantee — invest only what you can afford to lose.", fontSize = 12.sp, color = TextMuted)

            // F6.2: latest headlines for the recommended stock — async, hides when empty
            StockNewsSection(symbol = best.stockSymbol, vm = vm)
        }
    }
}

// ─── Find cheap/costly stocks — FIXED to the 52-week range (C6) ──────────────
// (The 5D/10D/20D/1Y period-chip selector was removed: one range, zero choices.
// Rows read straight from each item's low52w/high52w bounds.)

@Composable
private fun FinderListHeader(title: String, color: Color) {
    Text(
        text       = title,
        fontSize   = 13.sp,
        fontWeight = FontWeight.Bold,
        color      = color,
        modifier   = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
private fun SimpleFinderRow(
    item: StockScreenerItem,
    isLow: Boolean,
    rank: Int = 0,
    onSpeak: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    // C6: always the 52-week bounds — the finder has exactly one range now.
    val line = if (isLow)
        "Lowest in 1 year ₹${fmtRs(item.low52w)} • today ₹${fmtRs(item.currentPrice)}"
    else
        "Highest in 1 year ₹${fmtRs(item.high52w)} • today ₹${fmtRs(item.currentPrice)}"
    // I1: a plain verdict in words — the row itself says what the numbers mean.
    val verdict = if (isLow) "Near its yearly low — cheap zone" else "Near its yearly high — costly now"
    Row(
        modifier              = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(DarkSurfaceElevated),
            contentAlignment = Alignment.Center
        ) {
            // Rank number — ranked list, most extreme first. Neutral accent (Fix 4):
            // cheap/costly must NOT borrow green/red, which mean daily up/down here.
            if (rank > 0) {
                Text(
                    text       = "$rank",
                    fontSize   = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color      = BlueAccent
                )
            } else {
                Text(if (isLow) "🔽" else "🔼", fontSize = 16.sp)
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text       = item.symbol,
                fontSize   = 14.sp,
                fontWeight = FontWeight.Bold,
                color      = TextPrimary
            )
            Text(
                text       = line,
                fontSize   = 11.sp,
                color      = TextSecondary,
                lineHeight = 15.sp
            )
            // I1 verdict — words before numbers, neutral accent (not green/red).
            Text(
                text       = verdict,
                fontSize   = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color      = BlueAccent,
                lineHeight = 15.sp
            )
        }
        Text(
            text     = fmtPct(item.changePercent),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color    = if (item.changePercent >= 0) GreenPrimary else RedPrimary
        )
        // Hear this row read aloud — same listen-first pattern as the pick cards (I5)
        if (onSpeak != null) {
            SpeakButton(onClick = onSpeak)
        }
    }
    HorizontalDivider(color = DarkBorder, thickness = 0.3.dp, modifier = Modifier.padding(horizontal = 16.dp))
}
