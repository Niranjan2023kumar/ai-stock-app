package com.example.myapplication3.ui.screen

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.example.myapplication3.groww.GrowwLauncher
import com.example.myapplication3.mutualfunds.CachedTopFunds
import com.example.myapplication3.mutualfunds.MutualFund
import com.example.myapplication3.mutualfunds.MutualFundRepository
import com.example.myapplication3.mutualfunds.SipRecord
import com.example.myapplication3.ui.component.NotificationsOffBanner
import com.example.myapplication3.ui.component.TodayPnlBar
import com.example.myapplication3.ui.component.formatIndianRupees
import com.example.myapplication3.ui.theme.*
import com.example.myapplication3.ui.viewmodel.InterdayViewModel
import com.example.myapplication3.ui.viewmodel.MutualFundsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

// ₹ amounts use the shared formatIndianRupees (I9) — true lakh/crore grouping
// ("1,00,000"), the same formatter every other screen uses.

/** "Yesterday's prices" / "Prices from N days ago" — calendar days in local time. */
private fun cacheAgeLabel(savedAtMs: Long): String {
    val tz = TimeZone.getDefault()
    val nowMs = System.currentTimeMillis()
    val nowDay = (nowMs + tz.getOffset(nowMs)) / 86_400_000L
    val savedDay = (savedAtMs + tz.getOffset(savedAtMs)) / 86_400_000L
    return when ((nowDay - savedDay).coerceAtLeast(0L)) {
        0L -> "Prices from earlier today"
        1L -> "Yesterday's prices"
        else -> "Prices from ${(nowDay - savedDay)} days ago"
    }
}

// Plan variants and high-risk categories a beginner must never be steered into —
// mirrors the repository's EXCLUDED_PLAN_WORDS (search results are NOT plan-filtered,
// so "SBI Large Cap - Regular Plan - IDCW" would otherwise get the badge)
private val beginnerExcludedWords = listOf(
    "idcw", "dividend", "bonus", "payout", "reinvest", "regular",
    "small", "sectoral", "thematic"
)

private fun isBeginnerPick(fund: MutualFund): Boolean {
    val text = (fund.category + " " + fund.name).lowercase(Locale.US)
    if (beginnerExcludedWords.any { text.contains(it) }) return false
    return listOf("large cap", "index", "balanced", "flexi")
        .any { text.contains(it) }
}

// Rule C19 hard-risk list: sectoral, thematic, small/mid-cap heavy, credit-risk.
// Search can surface these; they must carry a loud "Not for beginners" warning.
private val riskyCategoryWords = listOf(
    "small cap", "smallcap", "small-cap",
    "mid cap", "midcap", "mid-cap",
    "sectoral", "thematic", "credit risk"
)

private fun isRiskyForBeginner(fund: MutualFund): Boolean {
    val text = (fund.category + " " + fund.name).lowercase(Locale.US)
    return riskyCategoryWords.any { text.contains(it) }
}

/**
 * I2/U9.1/D3: the raw AMFI scheme_category ("Index Funds", "Large Cap Fund", "ELSS")
 * is jargon a village shopkeeper does not know. Translate it to plain shopkeeper words
 * for the card face — the literal category and the words "index fund" live only behind
 * "See more" (D3). Returns "" for an unknown type so the chip is DROPPED rather than
 * ever printing raw jargon.
 */
private fun plainCategory(category: String): String {
    val c = category.lowercase(Locale.US)
    return when {
        // I2 names the exact phrase for index/ETF: "ready-made basket of top companies"
        c.contains("index") || c.contains("etf") -> "Ready-made basket of top companies"
        c.contains("large cap") || c.contains("largecap") ||
            c.contains("blue chip") || c.contains("bluechip") ->
            "India's biggest, steadiest companies"
        c.contains("flexi") || c.contains("multi cap") || c.contains("multicap") ->
            "A mix of big and growing companies"
        c.contains("elss") || c.contains("tax saver") || c.contains("tax saving") ->
            "Grows money and saves tax"
        c.contains("balanced") || c.contains("hybrid") ->
            "A safe mix of shares and savings"
        c.contains("mid cap") || c.contains("midcap") ->
            "Medium companies — more ups and downs"
        c.contains("small cap") || c.contains("smallcap") ->
            "Small companies — risky, big swings"
        c.contains("sectoral") || c.contains("thematic") ->
            "Bets on just one industry — risky"
        else -> ""
    }
}

// ══════════════════════════════════════════════════════════════════
//  Groww hand-off for mutual funds (rules B0.2 / C20)
// ══════════════════════════════════════════════════════════════════

/**
 * Opens Groww on the fund's search page so the user starts the SIP THERE —
 * this app never places or touches the order (rule B0.4). GrowwLauncher.openStock
 * is stock-only (uppercases + strips .NS), so this small fund variant lives here.
 * Same best-effort chain: Groww app → Groww launcher → Play Store → browser.
 */
private fun openGrowwForFund(context: Context, fundName: String) {
    // "Parag Parikh Flexi Cap Fund - Direct Plan - Growth" → search just the fund name
    val query = fundName.substringBefore(" - ").trim().ifBlank { fundName.trim() }
    val searchUrl = "https://groww.in/search?q=" + Uri.encode(query)

    // 1) Groww app on the search URL (scoped to Groww's package)
    val inGroww = Intent(Intent.ACTION_VIEW, Uri.parse(searchUrl)).apply {
        setPackage(GrowwLauncher.GROWW_PACKAGE)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    if (startSafe(context, inGroww)) return

    // 2) Groww installed but didn't claim the link — open its launcher + hint
    val launch = runCatching {
        context.packageManager.getLaunchIntentForPackage(GrowwLauncher.GROWW_PACKAGE)
    }.getOrNull()
    if (launch != null) {
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (startSafe(context, launch)) {
            Toast.makeText(context, "Search '$query' in Groww", Toast.LENGTH_LONG).show()
            return
        }
    }

    // 3) Groww not installed — its Play Store page
    val play = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("market://details?id=" + GrowwLauncher.GROWW_PACKAGE)
    ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    if (startSafe(context, play)) {
        Toast.makeText(context, "Install Groww first", Toast.LENGTH_LONG).show()
        return
    }

    // 4) Last resort — the groww.in search page in a browser
    val web = Intent(Intent.ACTION_VIEW, Uri.parse(searchUrl))
        .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    if (!startSafe(context, web)) {
        Toast.makeText(context, "Could not open Groww", Toast.LENGTH_LONG).show()
    }
}

private fun startSafe(context: Context, intent: Intent): Boolean =
    runCatching { context.startActivity(intent); true }.getOrDefault(false)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MutualFundsScreen(navController: NavController) {
    val vm: MutualFundsViewModel = hiltViewModel()
    val state by vm.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // F1.5: P&L bar — same activity-scoped instance HomeScreen uses
    val interdayVm: InterdayViewModel = hiltViewModel()
    val todayPnl by interdayVm.todayRealizedPnl.collectAsStateWithLifecycle()
    val openTrades by interdayVm.openTrades.collectAsStateWithLifecycle()
    val practiceMode by interdayVm.practiceMode.collectAsStateWithLifecycle(false)

    // ── Disk cache of the last good curated list ("yesterday's prices") ──
    // Read once, off the main thread — rendered while the fresh load runs,
    // so the tab NEVER sits on an endless "Loading funds…" spinner.
    var cachedTop by remember { mutableStateOf<CachedTopFunds?>(null) }
    LaunchedEffect(Unit) {
        cachedTop = withContext(Dispatchers.IO) { MutualFundRepository.readFundsCache(context) }
    }

    // ONE FUND FIRST (C18): the other curated funds stay behind one tap
    var showMoreFunds by rememberSaveable { mutableStateOf(false) }

    // A spinner may never run forever — after ~6s it becomes a tappable retry
    var slowLoad by remember { mutableStateOf(false) }
    var loadAttempt by rememberSaveable { mutableStateOf(0) }
    LaunchedEffect(state.isLoading, loadAttempt) {
        slowLoad = false
        if (state.isLoading) {
            delay(6_000)
            slowLoad = true
        }
    }
    val retryTopFunds = { loadAttempt++; vm.loadTopFunds() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mutual Funds", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(DarkBackground),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)
        ) {
            // ── Pinned tagline + search bar — OPAQUE background, so cards
            // scrolling beneath vanish behind it instead of showing half-cut ──
            stickyHeader {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(DarkBackground)
                        .padding(bottom = 6.dp)
                ) {
                    // F1.5: today's P&L — same bar as Stock/Intraday tabs; hides when
                    // nothing to show (component self-guards on zero P&L + no trades).
                    TodayPnlBar(
                        realizedPnl   = todayPnl,
                        openTradeCount = openTrades.size,
                        practiceMode  = practiceMode
                    )
                    // E1: alerts-off warning pinned on every tab — renders nothing
                    // while alerts are deliverable (shared self-contained banner)
                    NotificationsOffBanner()
                    // U6.1: one plain sentence on WHY a SIP is the safe way (drip-
                    // feeding spreads the ups and downs), not just "it grows big".
                    Text(
                        text = "Adding a little every month spreads out the ups and downs — the safe way to grow money slowly.",
                        fontSize = 13.sp,
                        color = GoldAccent,
                        fontWeight = FontWeight.Medium,
                        lineHeight = 18.sp,
                        modifier = Modifier.padding(top = 12.dp, bottom = 6.dp)
                    )
                    OutlinedTextField(
                        value = state.searchQuery,
                        onValueChange = vm::onSearchQueryChange,
                        placeholder = {
                            Text("Search a fund (like SBI)", color = TextMuted, fontSize = 13.sp)
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Search, contentDescription = null, tint = TextMuted)
                        },
                        trailingIcon = {
                            if (state.searchQuery.isNotEmpty()) {
                                IconButton(onClick = { vm.clearSearch() }) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear", tint = TextMuted)
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = GoldAccent,
                            unfocusedBorderColor = DarkBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            cursorColor = GoldAccent,
                            focusedContainerColor = DarkCard,
                            unfocusedContainerColor = DarkCard
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            if (state.searchQuery.isNotBlank()) {
                // Search mode
                if (state.isSearching) {
                    item { MfLoadingRow("Searching…") }
                } else if (state.searchError != null) {
                    // Network error is NOT "fund does not exist" — say so, offer retry
                    item { MfErrorBox(state.searchError ?: "") { vm.retrySearch() } }
                } else if (state.searchDone && state.searchResults.isEmpty()) {
                    // Only after a search for THIS query really finished empty
                    item {
                        Text(
                            text = "No funds found",
                            fontSize = 13.sp,
                            color = TextSecondary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp)
                        )
                    }
                } else {
                    items(state.searchResults, key = { it.schemeCode }) { fund ->
                        MfFundCard(
                            fund = fund,
                            hasSip = state.mySip != null,
                            isMySipFund = state.mySip?.schemeCode == fund.schemeCode,
                            onStartSip = vm::startSip,
                            onSpeak = vm::speak
                        )
                    }
                    if (state.searchResults.size >= 8) {
                        item {
                            Text(
                                text = "Showing the first 8 matches only. Type more of the name to see others.",
                                fontSize = 11.sp,
                                color = TextMuted,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                            )
                        }
                    }
                }
            } else {
                // ── Top funds mode: the cached list renders instantly, the
                // fresh list replaces it when the background refresh lands ──
                val cache = cachedTop
                val displayFunds =
                    if (state.topFunds.isNotEmpty()) state.topFunds else cache?.funds.orEmpty()
                val usingCache = state.topFunds.isEmpty() && displayFunds.isNotEmpty()

                item { MfBeginnerBanner() }
                state.mySip?.let { sip ->
                    item {
                        MySipCard(
                            sip = sip,
                            nav = state.sipNav,
                            dueNow = state.sipDueNow,
                            hasLateConfirms = state.hasLateConfirms,
                            onConfirm = vm::confirmSipPaid,
                            onRefreshNav = { vm.refreshSipNav(sip.schemeCode) },
                            onStop = vm::stopSip
                        )
                    }
                }

                // 3-step instructions only when the fund list is really visible —
                // never point the user at an invisible list
                if (displayFunds.isNotEmpty()) {
                    item { MfSipGuidanceCard(onSpeak = vm::speak) }
                } else if (state.isLoading) {
                    item { MfMiniInfoCard("Loading the fund list first…") }
                }

                if (usingCache) {
                    item {
                        MfCacheBanner(
                            label = cacheAgeLabel(cache?.savedAtMs ?: 0L),
                            refreshing = state.isLoading,
                            failed = state.error != null && !state.isLoading,
                            onRetry = retryTopFunds
                        )
                    }
                }

                when {
                    displayFunds.isNotEmpty() -> {
                        // ONE FUND FIRST (C18): the simplest fund is THE answer…
                        val firstFund = displayFunds.first()
                        item { MfStartHereHeader() }
                        item(key = firstFund.schemeCode) {
                            MfFundCard(
                                fund = firstFund,
                                hasSip = state.mySip != null,
                                isMySipFund = state.mySip?.schemeCode == firstFund.schemeCode,
                                onStartSip = vm::startSip,
                                onSpeak = vm::speak,
                                highlight = true
                            )
                        }
                        // …and the other safe choices wait behind ONE tap
                        val moreFunds = displayFunds.drop(1)
                        if (moreFunds.isNotEmpty() && !showMoreFunds) {
                            item {
                                OutlinedButton(
                                    onClick = { showMoreFunds = true },
                                    border = BorderStroke(1.dp, GoldAccent.copy(alpha = 0.4f)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(44.dp)
                                ) {
                                    Text(
                                        text = "See ${moreFunds.size} more safe choices",
                                        color = GoldAccent,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 13.sp
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        imageVector = Icons.Default.ExpandMore,
                                        contentDescription = null,
                                        tint = GoldAccent,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        } else {
                            items(moreFunds, key = { it.schemeCode }) { fund ->
                                MfFundCard(
                                    fund = fund,
                                    hasSip = state.mySip != null,
                                    isMySipFund = state.mySip?.schemeCode == fund.schemeCode,
                                    onStartSip = vm::startSip,
                                    onSpeak = vm::speak
                                )
                            }
                        }
                    }
                    // Nothing renderable: quick spinner first…
                    state.isLoading && !slowLoad -> item { MfLoadingRow("Loading funds…") }
                    // …but an endless spinner is forbidden — slow or failed
                    // loads become a tappable retry with a plain reason
                    else -> item {
                        MfErrorBox(
                            message = "Could not load funds. Check internet and tap to retry.",
                            onRetry = retryTopFunds
                        )
                    }
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════
//  Banner / loading / error
// ══════════════════════════════════════════════════════════════════

@Composable
private fun MfBeginnerBanner() {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = GoldContainer,
        border = BorderStroke(1.dp, GoldAccent.copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Lightbulb,
                contentDescription = null,
                tint = GoldLight,
                modifier = Modifier.size(24.dp)
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "What is a mutual fund?",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = GoldLight
                )
                Text(
                    text = "Experts invest your money in shares. With a monthly investment plan, you add a little every month.",
                    fontSize = 12.sp,
                    color = TextSecondary,
                    lineHeight = 18.sp
                )
            }
        }
    }
}

/** While the list itself is not visible yet, the instructions must not point at it. */
@Composable
private fun MfMiniInfoCard(text: String) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = DarkCard,
        border = BorderStroke(1.dp, DarkBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            color = TextSecondary,
            lineHeight = 17.sp,
            modifier = Modifier.padding(14.dp)
        )
    }
}

/**
 * Amber strip shown while the curated list comes from the disk cache —
 * old prices are ALWAYS labelled old (U1.6/U5.3), and a failed refresh
 * offers a tap-to-retry instead of silence.
 */
@Composable
private fun MfCacheBanner(
    label: String,
    refreshing: Boolean,
    failed: Boolean,
    onRetry: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = GoldContainer,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (failed) Modifier.clickable { onRetry() } else Modifier)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (refreshing) {
                CircularProgressIndicator(
                    color = GoldLight,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(14.dp)
                )
            } else if (failed) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    tint = GoldLight,
                    modifier = Modifier.size(16.dp)
                )
            }
            Text(
                text = when {
                    refreshing -> "$label — getting fresh prices…"
                    failed -> "$label — could not refresh. Tap to retry."
                    else -> label
                },
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = GoldLight,
                lineHeight = 16.sp
            )
        }
    }
}

/** ONE FUND FIRST (C18): the first curated fund is presented as THE answer. */
@Composable
private fun MfStartHereHeader() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Star,
            contentDescription = null,
            tint = GoldAccent,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = "Start here — the simplest fund",
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = GoldAccent
        )
    }
}

/** Rule C20 — how to start, which date, and the one thing to never do. */
@Composable
private fun MfSipGuidanceCard(onSpeak: (String) -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = DarkCard,
        border = BorderStroke(1.dp, DarkBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "How to begin investing (3 steps)",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    modifier = Modifier.weight(1f)
                )
                // 🔊 read the whole 3-step guidance out loud (rule I5)
                MfSpeakButton(onClick = {
                    onSpeak(
                        "How to start investing, in 3 steps. " +
                        "Step 1: pick one fund below. The first one is the simplest. " +
                        "Step 2: tap Start investing in Groww, and set it up there. " +
                        "Most funds allow from 500 rupees a month. " +
                        "Step 3: pick a date just after your salary day, like the 1st or the 5th. " +
                        "And never stop your monthly investment when the market falls. " +
                        "Cheap months buy you more units."
                    )
                })
            }
            Text(
                text = "1. Pick ONE fund below. The first one is the simplest.",
                fontSize = 12.sp, color = TextSecondary, lineHeight = 17.sp
            )
            Text(
                text = "2. Tap 'Start investing in Groww' and set it up there. Most funds allow from ₹500 a month.",
                fontSize = 12.sp, color = TextSecondary, lineHeight = 17.sp
            )
            Text(
                text = "3. Pick a date just after your salary day — like the 1st or 5th.",
                fontSize = 12.sp, color = TextSecondary, lineHeight = 17.sp
            )
            Text(
                text = "Never stop your monthly investment when the market falls. Cheap months buy you MORE units — stopping then is the #1 beginner mistake.",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = GoldLight,
                lineHeight = 17.sp
            )
        }
    }
}

// 48dp speaker (rule I5) — same pattern as the shared SpeakButton on Home:
// Material VolumeUp icon at a full 48dp touch target, gold tint.
@Composable
private fun MfSpeakButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier.size(48.dp)) {
        Icon(
            imageVector = Icons.Default.VolumeUp,
            contentDescription = "Listen",
            tint = GoldAccent,
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
private fun MfLoadingRow(message: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        CircularProgressIndicator(color = GoldAccent, strokeWidth = 3.dp)
        Text(message, fontSize = 13.sp, color = TextSecondary)
    }
}

@Composable
private fun MfErrorBox(message: String, onRetry: () -> Unit) {
    // The whole box is tappable — "tap to retry" must mean tap ANYWHERE here
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onRetry() }
            .padding(vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = Icons.Default.CloudOff,
            contentDescription = null,
            tint = TextMuted,
            modifier = Modifier.size(34.dp)
        )
        Text(
            text = message,
            fontSize = 13.sp,
            color = TextSecondary,
            textAlign = TextAlign.Center
        )
        Button(
            onClick = onRetry,
            colors = ButtonDefaults.buttonColors(containerColor = GoldAccent)
        ) {
            Icon(
                Icons.Default.Refresh,
                contentDescription = null,
                tint = TextOnGold,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Try again", color = TextOnGold, fontWeight = FontWeight.Bold)
        }
    }
}

// ══════════════════════════════════════════════════════════════════
//  My SIP card (rule C20a — reminder + honest growth line)
// ══════════════════════════════════════════════════════════════════

@Composable
private fun MySipCard(
    sip: SipRecord,
    nav: Double?,
    dueNow: Boolean,
    /** C20a: a payment was confirmed late → the growth line is only approximate. */
    hasLateConfirms: Boolean,
    onConfirm: () -> Unit,
    onRefreshNav: () -> Unit,
    onStop: () -> Unit
) {
    // "Remove" must never wipe history on a stray tap — tiny confirm first
    var showRemoveConfirm by rememberSaveable { mutableStateOf(false) }
    if (showRemoveConfirm) {
        AlertDialog(
            onDismissRequest = { showRemoveConfirm = false },
            containerColor = DarkCard,
            title = {
                Text(
                    text = "Remove tracking?",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            },
            text = {
                Text(
                    text = "Your real monthly investment in Groww keeps running. Your saved history here will be erased.",
                    fontSize = 13.sp,
                    color = TextSecondary,
                    lineHeight = 18.sp
                )
            },
            confirmButton = {
                TextButton(onClick = { showRemoveConfirm = false; onStop() }) {
                    Text("Remove", color = RedPrimary, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveConfirm = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            }
        )
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = DarkCard,
        border = BorderStroke(1.dp, GoldAccent.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Your monthly investment",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = GoldAccent,
                    modifier = Modifier.weight(1f)
                )
                // Only stops TRACKING — the real SIP lives in Groww
                TextButton(onClick = { showRemoveConfirm = true }) {
                    Text("Remove", fontSize = 12.sp, color = RedPrimary)
                }
            }
            Text(
                text = sip.fundName,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
                lineHeight = 17.sp
            )
            Text(
                text = "₹${formatIndianRupees(sip.monthlyAmount.toDouble())} every month, on day ${sip.sipDay}",
                fontSize = 11.sp,
                color = TextSecondary
            )

            // ── SIP-day reminder ──
            if (dueNow) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = GoldContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Today is your monthly investment day — did your ₹${formatIndianRupees(sip.monthlyAmount.toDouble())} get taken?",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = GoldLight,
                            lineHeight = 17.sp
                        )
                        Button(
                            onClick = onConfirm,
                            enabled = nav != null,  // needs today's NAV to count units honestly
                            colors = ButtonDefaults.buttonColors(containerColor = GoldAccent)
                        ) {
                            Text("Yes, money went", color = TextOnGold, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                        if (nav == null) {
                            Text(
                                text = "Getting today's price first…",
                                fontSize = 11.sp,
                                color = TextMuted,
                                modifier = Modifier.clickable { onRefreshNav() }
                            )
                        }
                    }
                }
            }

            // ── Growth line — real NAV data only, never invented (rule B6) ──
            when {
                sip.totalInvested <= 0.0 -> Text(
                    text = "No payments counted yet. Tap Yes on your monthly investment day and this line will grow.",
                    fontSize = 11.sp,
                    color = TextMuted,
                    lineHeight = 16.sp
                )
                nav != null -> {
                    val current = sip.totalUnits * nav
                    val pct = (current - sip.totalInvested) / sip.totalInvested * 100.0
                    val direction = if (current >= sip.totalInvested) "up" else "down"
                    Text(
                        text = "Your ₹${formatIndianRupees(sip.totalInvested)} monthly investment is now worth ₹${formatIndianRupees(current)} — $direction ${String.format(Locale.US, "%.1f", kotlin.math.abs(pct))}%",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (current >= sip.totalInvested) GreenPrimary else RedPrimary,
                        lineHeight = 17.sp
                    )
                    // C20a: late confirms were booked at the confirm-day price, so
                    // the line above is honest-but-approximate — and says so.
                    if (hasLateConfirms) {
                        Text(
                            text = "(approx - one payment was confirmed late)",
                            fontSize = 11.sp,
                            color = TextMuted,
                            lineHeight = 15.sp
                        )
                    }
                }
                else -> Text(
                    text = "Your ₹${formatIndianRupees(sip.totalInvested)} monthly investment. Could not get today's price — tap to retry.",
                    fontSize = 11.sp,
                    color = TextSecondary,
                    lineHeight = 16.sp,
                    modifier = Modifier.clickable { onRefreshNav() }
                )
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════
//  Fund card
// ══════════════════════════════════════════════════════════════════

@Composable
private fun MfFundCard(
    fund: MutualFund,
    hasSip: Boolean,
    isMySipFund: Boolean,
    onStartSip: (MutualFund, Int, Int) -> Unit,
    onSpeak: (String) -> Unit,
    /** True on the ONE "Start here" fund (C18) — stronger gold border. */
    highlight: Boolean = false
) {
    // rememberSaveable — the open card must survive rotation and tab hops
    var expanded by rememberSaveable(fund.schemeCode) { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = DarkCard,
        border = BorderStroke(
            if (highlight) 1.5.dp else 1.dp,
            when {
                expanded -> GoldAccent.copy(alpha = 0.4f)
                highlight -> GoldAccent.copy(alpha = 0.7f)
                else -> DarkBorder
            }
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Fund name on its own FULL-WIDTH line — nothing may squeeze it sideways
            Text(
                text = fund.name,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
                lineHeight = 18.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Chip + badge + sparkline share the second row: only the chip flexes
            // (weight 1f, ellipsis). The badge is measured FIRST so it always gets
            // its full intrinsic width — it can never wrap letter-per-line again.
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    MfCategoryChip(fund.category)
                }
                if (isRiskyForBeginner(fund)) MfRiskyBadge()
                else if (isBeginnerPick(fund)) MfBeginnerBadge()
                val isUp = fund.navHistory.size >= 2 &&
                        fund.navHistory.last() >= fund.navHistory.first()
                PriceSparkline(
                    prices = fund.navHistory,
                    isUp = isUp,
                    modifier = Modifier
                        .width(72.dp)
                        .height(30.dp)
                )
            }

            // One-line "why this fund" on curated picks (rule C18) + read-aloud (I5)
            fund.reason?.let { reason ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Why: $reason",
                        fontSize = 11.sp,
                        color = GoldLight,
                        lineHeight = 15.sp,
                        modifier = Modifier.weight(1f)
                    )
                    MfSpeakButton(onClick = { onSpeak("Why this fund: $reason") })
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // NAV + returns row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "₹" + formatIndianRupees(fund.nav, 2),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = "Price per unit • ${fund.navDate}",
                        fontSize = 11.sp,
                        color = TextMuted
                    )
                    // Stale-NAV badge — mfapi.in keeps serving discontinued/merged schemes
                    // whose "latest" NAV is months old; the user must see that clearly
                    if (fund.ageDays > 7) {
                        Surface(shape = RoundedCornerShape(6.dp), color = RedContainer) {
                            Text(
                                text = "Old data (${fund.ageDays} days)",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = RedPrimary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                MfReturnChip("Last year", fund.return1y)
                MfReturnChip("Last 3 yrs", fund.return3y)
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = TextMuted,
                    modifier = Modifier.size(20.dp)
                )
            }

            // C18: the "Start here" card must answer the two first questions
            // WITHOUT a tap — the minimum amount and an honest projection.
            // (Expanded, the growth box shows the full picture instead.)
            if (highlight && !expanded) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Most funds allow from ₹500 a month.",
                    fontSize = 11.sp,
                    color = TextSecondary,
                    lineHeight = 15.sp
                )
                // No projection on a stale price (same rule as the growth box)
                if (fund.ageDays <= 7) {
                    val collapsedReturn3y = fund.return3y
                    if (collapsedReturn3y != null && collapsedReturn3y < 0.0) {
                        // B6: a fund that has been LOSING must not show a rosy 8%
                        // projection on the un-expanded card — say the loss honestly,
                        // the same line the expanded calculator uses.
                        Text(
                            text = "Careful: this fund LOST about " +
                                    String.format(Locale.US, "%.1f", abs(collapsedReturn3y)) +
                                    "% a year over the last 3 years.",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = RedPrimary,
                            lineHeight = 15.sp,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    } else {
                        // U6.2: labelled a GUESS, never a promise.
                        // When no 3-year history: say we assumed 8%, don't let user
                        // think X is this fund's own projected return.
                        val noHistory = fund.return3y == null
                        Text(
                            text = "If you invest ₹1,000 a month, in 5 years it could become about " +
                                    "₹${formatIndianRupees(projectSipValue(1_000, 5, fund.return3y))}" +
                                    if (noHistory) " — rough guess using 8% (no fund history yet)."
                                    else " — only a guess, not a promise.",
                            fontSize = 11.sp,
                            color = GoldLight,
                            lineHeight = 15.sp,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
            }

            // Expanded detail + SIP calculator
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column {
                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider(color = DarkBorder)
                    Spacer(modifier = Modifier.height(8.dp))

                    val return5y = fund.return5y
                    MfDetailRow(
                        label = "5-year return (per year)",
                        value = return5y?.let {
                            String.format(Locale.US, "%+.1f%%", it)
                        } ?: "—",
                        valueColor = when {
                            return5y == null -> TextMuted
                            return5y >= 0.0 -> GreenPrimary
                            else -> RedPrimary
                        }
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    MfDetailRow(label = "Fund company", value = fund.fundHouse, valueColor = TextSecondary)
                    Spacer(modifier = Modifier.height(6.dp))
                    // D3: the literal technical type ("Index Fund", "Large Cap") lives
                    // ONLY here in "See more", never as jargon on the card face.
                    MfDetailRow(
                        label = "Fund type",
                        value = fund.category.substringAfterLast(" - ").ifBlank { fund.category },
                        valueColor = TextSecondary
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    // No SIP math on a stale NAV — a dead fund's months-old price must
                    // not feed a 20-year projection (and no Start button either)
                    if (fund.ageDays > 7) {
                        Text(
                            text = "Growth numbers are off — this fund's price is ${fund.ageDays} days old",
                            fontSize = 12.sp,
                            color = RedPrimary,
                            lineHeight = 16.sp
                        )
                    } else {
                        MfSipCalculator(
                            fund = fund,
                            isRisky = isRiskyForBeginner(fund),
                            hasSip = hasSip,
                            isMySipFund = isMySipFund,
                            onStartSip = onStartSip
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MfReturnChip(label: String, value: Double?) {
    // "1Y +12.3%" pill — green for growth, red for loss, muted dash when unknown
    val color = when {
        value == null -> TextMuted
        value >= 0.0  -> GreenPrimary
        else          -> RedPrimary
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value?.let { String.format(Locale.US, "%+.1f%% per yr", it) } ?: "—",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = color
        )
        Text(text = label, fontSize = 11.sp, color = TextMuted)
    }
}

@Composable
private fun MfCategoryChip(category: String) {
    // I2/U9.1: never print raw AMFI category jargon ("Index Funds", "Large Cap")
    // on the card face — show plain shopkeeper words, or drop the chip if we have
    // no plain translation (the literal type stays behind "See more", rule D3).
    val plain = plainCategory(category)
    if (plain.isBlank()) return
    Surface(shape = RoundedCornerShape(6.dp), color = DarkSurfaceElevated) {
        Text(
            text = plain,
            fontSize = 11.sp,
            color = TextSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
        )
    }
}

@Composable
private fun MfBeginnerBadge() {
    Surface(shape = RoundedCornerShape(6.dp), color = GoldContainer) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Star,
                contentDescription = null,
                tint = GoldLight,
                modifier = Modifier.size(12.dp)
            )
            Text(
                text = "Beginner Pick",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = GoldLight,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

/** Rule C19 — risky categories found via search get a loud red warning, not silence. */
@Composable
private fun MfRiskyBadge() {
    Surface(shape = RoundedCornerShape(6.dp), color = RedContainer) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = RedPrimary,
                modifier = Modifier.size(12.dp)
            )
            Text(
                text = "Not for beginners",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = RedPrimary,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

@Composable
private fun MfDetailRow(label: String, value: String, valueColor: androidx.compose.ui.graphics.Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 12.sp, color = TextMuted)
        Text(
            value,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = valueColor,
            textAlign = TextAlign.End,
            modifier = Modifier.padding(start = 12.dp)
        )
    }
}

// ══════════════════════════════════════════════════════════════════
//  "See what your money can become" (was "SIP calculator" — I3)
// ══════════════════════════════════════════════════════════════════

/**
 * C18: monthly-SIP future value for the collapsed "Start here" line — the SAME
 * maths and clamps as [MfSipCalculator] (monthly compounding, CAGR clamped to
 * 1–14%). A losing fund only ever gets the conservative 8% scenario (rule B6),
 * and a fund with no 3-year history assumes 8%, never an invented 12%.
 */
private fun projectSipValue(monthly: Int, years: Int, return3y: Double?): Double {
    val annualPct =
        if (return3y != null && return3y < 0.0) 8.0
        else (return3y ?: 8.0).coerceIn(1.0, 14.0)
    val r = annualPct / 100.0 / 12.0
    val n = years * 12
    return monthly * (((1.0 + r).pow(n) - 1.0) / r) * (1.0 + r)
}

@Composable
private fun MfSipCalculator(
    fund: MutualFund,
    isRisky: Boolean,
    hasSip: Boolean,
    isMySipFund: Boolean,
    onStartSip: (MutualFund, Int, Int) -> Unit
) {
    // rememberSaveable — slider positions must survive rotation and tab hops
    var monthlyValue by rememberSaveable { mutableStateOf(2000f) }
    var yearsValue by rememberSaveable { mutableStateOf(10f) }

    val monthly = ((monthlyValue / 500f).roundToInt() * 500).coerceIn(500, 10_000)
    val years = yearsValue.roundToInt().coerceIn(1, 20)

    // Monthly compounding — the assumed CAGR is clamped to 14% (long-run equity
    // average): a hot small-cap 3Y number projected over 20 years reads like a
    // promised crore. A conservative 8% figure is always shown alongside.
    // No 3-year history → assume the conservative 8%, never an invented optimistic 12%.
    val return3y = fund.return3y
    // A LOSING fund must not be silently clamped into a +1% growth story (rule B6):
    // its real negative number is said out loud and only the 8% scenario is projected.
    val fundHasLost = return3y != null && return3y < 0.0
    val annualPct = (return3y ?: 8.0).coerceIn(1.0, 14.0)
    val r = annualPct / 100.0 / 12.0
    val n = years * 12
    val invested = monthly.toDouble() * n
    val futureValue = monthly * (((1.0 + r).pow(n) - 1.0) / r) * (1.0 + r)
    val rLow = 8.0 / 100.0 / 12.0
    val conservativeValue = monthly * (((1.0 + rLow).pow(n) - 1.0) / rLow) * (1.0 + rLow)

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = DarkSurfaceElevated,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // I3: reads as an answer, not a "calculator" — the sliders stay
            // because choosing an amount is the user's one legitimate input.
            Text(
                text = "See what your money can become",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = GoldAccent
            )

            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "Each month: ₹${formatIndianRupees(monthly.toDouble())}",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )
            Slider(
                value = monthlyValue,
                onValueChange = { monthlyValue = it },
                valueRange = 500f..10_000f,
                steps = 18,
                colors = SliderDefaults.colors(
                    thumbColor = GoldAccent,
                    activeTrackColor = GoldAccent,
                    inactiveTrackColor = DarkBorder
                )
            )

            Text(
                text = "For how many years: $years years",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )
            Slider(
                value = yearsValue,
                onValueChange = { yearsValue = it },
                valueRange = 1f..20f,
                steps = 18,
                colors = SliderDefaults.colors(
                    thumbColor = GoldAccent,
                    activeTrackColor = GoldAccent,
                    inactiveTrackColor = DarkBorder
                )
            )

            Spacer(modifier = Modifier.height(6.dp))
            // Neutral box, not a green "profit" promise — show a range, not one number
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = DarkCard,
                border = BorderStroke(1.dp, DarkBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        text = "You put in: ₹${formatIndianRupees(invested)}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        lineHeight = 18.sp
                    )
                    if (fundHasLost) {
                        Text(
                            text = "Careful: this fund LOST about " +
                                    String.format(Locale.US, "%.1f", abs(return3y ?: 0.0)) +
                                    "% a year over the last 3 years.",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = RedPrimary,
                            lineHeight = 18.sp
                        )
                    }
                    // Label each scenario by its OWN growth rate — never "at least/at most"
                    // (which inverts when the fund's 3Y return is below 8%).
                    // When return3y is null (no history), show only 8% and say so clearly.
                    if (return3y == null) {
                        Text(
                            text = "No 3-year history for this fund — using 8% as a rough market average.",
                            fontSize = 11.sp,
                            color = TextSecondary,
                            lineHeight = 16.sp
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "If it grows about 8% a year: ₹${formatIndianRupees(conservativeValue)}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary,
                            lineHeight = 18.sp
                        )
                    } else {
                        Text(
                            text = "If it grows about 8% a year: ₹${formatIndianRupees(conservativeValue)}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary,
                            lineHeight = 18.sp
                        )
                        // No fund-specific growth line for a losing fund — a clamped +1%
                        // would quietly turn a loser into a growth story
                        if (!fundHasLost) {
                            Text(
                                text = "If it grows about ${String.format(Locale.US, "%.1f", annualPct)}% a year: ₹${formatIndianRupees(futureValue)}",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary,
                                lineHeight = 18.sp
                            )
                        }
                    }
                    Text(
                        text = "This is only a guess, not a promise",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Past returns don't promise the future.",
                fontSize = 12.sp,
                color = TextMuted
            )

            // ── Start the SIP for real (rules B0.2 / C20 — no dead end) ──────
            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = DarkBorder)
            Spacer(modifier = Modifier.height(10.dp))
            MfStartSipSection(
                fund = fund,
                monthly = monthly,
                isRisky = isRisky,
                hasSip = hasSip,
                isMySipFund = isMySipFund,
                onStartSip = onStartSip
            )
        }
    }
}

/**
 * The concrete next action the earning loop needs (rule B0.2): open Groww to
 * place the SIP there, plus the honest minimum-amount and date guidance (C20),
 * and a one-tap way to track the SIP in this app (C20a).
 */
@Composable
private fun MfStartSipSection(
    fund: MutualFund,
    monthly: Int,
    isRisky: Boolean,
    hasSip: Boolean,
    isMySipFund: Boolean,
    onStartSip: (MutualFund, Int, Int) -> Unit
) {
    val context = LocalContext.current

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (isRisky) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = RedPrimary,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "This is a risky type of fund — not for beginners.",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = RedPrimary,
                    lineHeight = 16.sp
                )
            }
        }
        // mfapi.in has no min-SIP field — say the honest standard line, no invented number
        Text(
            text = "Most funds allow monthly investments from ₹500 a month.",
            fontSize = 11.sp,
            color = TextSecondary
        )
        Text(
            text = "Pick a date just after your salary day — like the 1st or 5th.",
            fontSize = 11.sp,
            color = TextSecondary
        )
        // What Groww even IS — one line before the hand-off, so the user is
        // never sent to an unexplained app (panel fix)
        Text(
            text = "Groww is a SEBI-registered investing app — the shop where you buy. This app only guides you.",
            fontSize = 11.sp,
            color = TextSecondary,
            lineHeight = 15.sp
        )

        // Rule C19/I7 — a fund flagged "Not for beginners" must not hand the user
        // a live buy button: greyed out, no tap, with the plain reason below.
        Button(
            onClick = { openGrowwForFund(context, fund.name) },
            enabled = !isRisky,
            colors = ButtonDefaults.buttonColors(
                containerColor = GoldAccent,
                disabledContainerColor = DarkSurfaceElevated
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            val contentTint = if (isRisky) TextMuted else TextOnGold
            Text(
                text = "Start investing in Groww",
                color = contentTint,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp
            )
            Spacer(modifier = Modifier.width(6.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = contentTint,
                modifier = Modifier.size(18.dp)
            )
        }
        if (isRisky) {
            Text(
                text = "Not for beginners - pick a safer fund above.",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = RedPrimary,
                lineHeight = 16.sp
            )
        }
        Text(
            text = "You place the monthly investment yourself in Groww. This app never touches your money.",
            fontSize = 12.sp,
            color = TextMuted,
            lineHeight = 16.sp
        )

        // ── Track it here (one SIP only — one decision, rule B10) ──
        when {
            isMySipFund -> Text(
                text = "You track this monthly investment at the top of this tab.",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = GoldLight
            )
            hasSip -> Text(
                text = "You already track one monthly investment. Remove it at the top first to track this one.",
                fontSize = 11.sp,
                color = TextMuted,
                lineHeight = 16.sp
            )
            else -> {
                var sipDay by rememberSaveable { mutableStateOf(5) }
                Text(
                    text = "Started it? Track it here — the app reminds you every month:",
                    fontSize = 11.sp,
                    color = TextSecondary,
                    lineHeight = 16.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(1, 5, 10).forEach { day ->
                        FilterChip(
                            selected = sipDay == day,
                            onClick = { sipDay = day },
                            label = { Text("Day $day", fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = GoldAccent,
                                selectedLabelColor = TextOnGold,
                                labelColor = TextSecondary
                            )
                        )
                    }
                }
                OutlinedButton(
                    onClick = { onStartSip(fund, monthly, sipDay) },
                    border = BorderStroke(1.dp, GoldAccent),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Track my ₹${formatIndianRupees(monthly.toDouble())} monthly investment (day $sipDay)",
                        color = GoldAccent,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}
