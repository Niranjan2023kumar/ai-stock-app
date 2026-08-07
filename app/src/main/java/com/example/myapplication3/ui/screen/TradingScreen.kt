package com.example.myapplication3.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import kotlinx.coroutines.delay
import com.example.myapplication3.groww.GrowwLauncher
import com.example.myapplication3.groww.OrderType
import com.example.myapplication3.intraday.*
import com.example.myapplication3.navigation.Screen
import com.example.myapplication3.tracking.TrackedTrade
import com.example.myapplication3.ui.component.GrowwActionRow
import com.example.myapplication3.ui.component.MyStockCard
import com.example.myapplication3.ui.component.NotificationsOffBanner
import com.example.myapplication3.ui.component.PendingOrderConfirmDialog
import com.example.myapplication3.ui.component.TodayPnlBar
import com.example.myapplication3.ui.component.computeTargetSplit
import com.example.myapplication3.ui.component.formatIndianRupees
import com.example.myapplication3.ui.theme.*
import com.example.myapplication3.ui.viewmodel.InterdayViewModel
import com.example.myapplication3.ui.viewmodel.LossLimitType

// ── Money formatting (I9) — the ONE shared Indian-grouping formatter, so big
// amounts read the Groww way (₹1,23,456) on every screen the same. Rupee AMOUNTS
// only; indices are POINTS / a percent and never pass through here.
private fun fmtRs(v: Double): String    = formatIndianRupees(v)      // whole rupees
private fun fmtPrice(v: Double): String = formatIndianRupees(v, 2)   // price with paise

// ── B2 wall-clock freshness (U1.6/U5.3): prices older than this read amber
// "Old price" even if the cached flag is still false (e.g. after the tab was
// backgrounded and the resumed fetch has not finished). Same 5-min bar the
// Stock header uses (HomeScreen.INDEX_STALE_AFTER_MS).
private const val PRICE_STALE_AFTER_MS = 5 * 60 * 1000L

// ── Which session do closed-market prices come from? Plain words for the neutral
// freshness line: "today's" (after the 3:30 close), "yesterday's", or the weekday
// ("Friday's" all weekend). java.util.Calendar because minSdk 24 has no java.time.
private fun lastCloseDayLabel(): String {
    val ist = java.util.TimeZone.getTimeZone("Asia/Kolkata")
    val cal = java.util.Calendar.getInstance(ist)
    val todayOfYear = cal.get(java.util.Calendar.DAY_OF_YEAR)
    val nowMinutes  = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
    // Before today's 3:30 PM close, the last FINISHED session was an earlier day
    if (nowMinutes < 15 * 60 + 30) cal.add(java.util.Calendar.DAY_OF_MONTH, -1)
    // Weekends never trade — walk back to the last weekday
    while (cal.get(java.util.Calendar.DAY_OF_WEEK) == java.util.Calendar.SATURDAY ||
           cal.get(java.util.Calendar.DAY_OF_WEEK) == java.util.Calendar.SUNDAY) {
        cal.add(java.util.Calendar.DAY_OF_MONTH, -1)
    }
    return when (todayOfYear - cal.get(java.util.Calendar.DAY_OF_YEAR)) {
        0    -> "today's"
        1    -> "yesterday's"
        // Farther back (or across a year boundary) → the weekday name reads best
        else -> java.text.SimpleDateFormat("EEEE", java.util.Locale.ENGLISH)
                    .apply { timeZone = ist }.format(cal.time) + "'s"
    }
}

// ── Company name a human recognises (panel #7): strip the legal tail
// ("LIMITED"/"LTD"), fall back to the ticker only when there is no name at all.
private fun shortCompanyName(name: String, symbol: String): String {
    val cleaned = name.trim()
        .replace(Regex("(?i)\\s+(LIMITED|LTD\\.?)\\s*$"), "")
        .trim()
    return cleaned.ifEmpty { symbol }
}

// Plain ordinal for the sell plan (I2): "1st target", never analyst shorthand "T1".
private fun ordinalWord(n: Int): String = when (n) {
    1    -> "1st"
    2    -> "2nd"
    3    -> "3rd"
    else -> "${n}th"
}

// ── Beginner verdict word (I2) — the checklist score turned into ONE plain word.
// This is setup QUALITY (how many good signs lined up), never a chance of profit.
// The raw number stays hidden behind "See more".
// Bands are LOCKED by the corrected spec (B3): 90+ Strong / 84–89 Okay /
// 70–83 Weak — and they MATCH TradingSignal.confidenceMeaning exactly, so the
// word and the "See more" sentence can never disagree about the same score.
private fun verdictWord(confidence: Int): String = when {
    confidence >= 90 -> "Strong setup"
    confidence >= 84 -> "Okay setup"
    else             -> "Weak setup"
}
private fun verdictColor(confidence: Int): Color = when {
    confidence >= 90 -> GreenPrimary
    confidence >= 84 -> CautionAmber
    else             -> TextMuted
}

// ── C6: one plain verdict line per screener row — the SAME badge logic and the
// SAME words as the Stock tab / watchlist (verdictFromBadge), so two tabs can
// never disagree about the same stock on the same data.
private fun screenerVerdictLine(item: StockScreenerItem): String {
    val badge = item.computeSignalBadge()
    return when {
        badge.isNeutral               -> "No clear direction today — nothing to do."
        badge.label == "Buy Now"      -> "Strong today — trading above its usual price levels."
        badge.label == "Sell Now"     -> "Weak today — trading below its usual price levels."
        badge.label == "Watch to Buy" -> "Slightly positive — worth watching, no clear entry yet."
        badge.label == "Caution"      -> "Slightly weak today — be careful with this one."
        badge.isPositive              -> "Slightly positive — worth watching, no clear entry yet."
        else                          -> "Slightly weak today — be careful with this one."
    }
}

// ── One reusable "read this out" control — a real Material icon (no emoji) with a
// 48dp touch target so a zero-knowledge user can always tap it (I5). ──────────────
@Composable
private fun SpeakButton(onSpeak: () -> Unit) {
    IconButton(onClick = onSpeak, modifier = Modifier.size(48.dp)) {
        Icon(Icons.Default.VolumeUp, contentDescription = "Read this out loud", tint = GoldAccent, modifier = Modifier.size(22.dp))
    }
}

// ─── Main Screen ──────────────────────────────────────────────────────────────
// ONE scroll, no inner tabs (B5/B10): the day's trade decision on top, the
// screener behind a single "See all stocks" tap. The Market/Analysis analyst
// pages are gone per E3b — their data already feeds the status banner.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TradingScreen(navController: NavController) {
    // ── Activity-scoped ViewModel — ONE instance shared across all bottom-nav tabs
    // (a plain hiltViewModel() would create a per-destination copy, each running its
    // own 30s auto-refresh loop and duplicating every Yahoo fetch)
    val activity = androidx.compose.ui.platform.LocalContext.current as androidx.activity.ComponentActivity
    val vm: InterdayViewModel = hiltViewModel(activity)
    val state by vm.uiState.collectAsStateWithLifecycle()

    // Trade tracking — the SAME persisted rows the Stock tab shows (B7b: today's
    // P/L visible at all times, on THIS tab too, not only on Home).
    val todayPnl by vm.todayRealizedPnl.collectAsStateWithLifecycle()
    val openTrades by vm.openTrades.collectAsStateWithLifecycle()
    val practiceMode by vm.practiceMode.collectAsStateWithLifecycle()
    val dailyCapital by vm.dailyCapital.collectAsStateWithLifecycle()

    // Real-time Angel One ticks when configured — the intraday tab is where
    // seconds matter most, so it must not run on delayed snapshots alone.
    val livePrices by vm.livePrices.collectAsStateWithLifecycle()
    val liveConnected by vm.liveConnected.collectAsStateWithLifecycle()

    // ── Pending Groww order — survives process death (B0.2b / locked decision #3).
    // The marker was written to DataStore BEFORE Groww opened; if Android killed us
    // while the user placed the order, this dialog still re-appears on return.
    val pendingOrder by vm.pendingOrder.collectAsStateWithLifecycle()
    pendingOrder?.let { p ->
        PendingOrderConfirmDialog(
            pending = p,
            livePrice = if (liveConnected) livePrices[p.symbol] else null,
            onConfirm = { price, qty -> vm.confirmPendingOrder(price, qty) },
            onDismiss = { vm.clearPendingOrder() }
        )
    }

    Scaffold(
        containerColor = DarkBackground,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Intraday Trading",
                        fontWeight = FontWeight.Bold,
                        fontSize   = 18.sp,
                        color      = TextPrimary
                    )
                },
                // DECLUTTER (B10): the old header open/closed pill is gone — the ONE
                // market open/closed indicator now lives in the market banner below,
                // and the ONE freshness clock lives in the FreshnessLine. The header
                // keeps only the two plain actions.
                actions = {
                    IconButton(onClick = { vm.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = TextSecondary)
                    }
                    IconButton(onClick = { navController.navigate(Screen.Settings.route) }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = TextSecondary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBackground)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(DarkBackground)
        ) {
            // E1: alerts ARE the product — if Android is blocking our notifications,
            // this one shared banner says so, pinned near the top of every tab.
            // Renders nothing when alerts are deliverable.
            NotificationsOffBanner()
            // Today's running P/L — pinned on THIS tab too (B7b). Practice results
            // always carry the PRACTICE label (B0.3b).
            TodayPnlBar(
                realizedPnl = todayPnl,
                openTradeCount = openTrades.size,
                practiceMode = practiceMode,
                budget = dailyCapital
            )
            AiTradeContent(
                vm = vm,
                state = state,
                navController = navController,
                openTrades = openTrades,
                practiceMode = practiceMode,
                dailyCapital = dailyCapital,
                livePrices = livePrices,
                liveConnected = liveConnected
            )
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
//  The single Intraday scroll (C9–C16, in spec order)
// ══════════════════════════════════════════════════════════════════════════════

@Composable
private fun AiTradeContent(
    vm: InterdayViewModel,
    state: InterdayUiState,
    navController: NavController,
    openTrades: List<TrackedTrade>,
    practiceMode: Boolean,
    dailyCapital: Int,
    livePrices: Map<String, Double>,
    liveConnected: Boolean
) {
    // This week's honest score (E1a) — real closed trades. Collected before any
    // early return so the value is read on every path (Compose hook ordering).
    val weekReport by vm.weekReport.collectAsStateWithLifecycle()
    // E3e: WHICH loss limit blocks right now — a user stopped by the WEEKLY limit
    // must never be told "try again tomorrow" (tomorrow is still blocked).
    val breachedLimit by vm.breachedLimitType.collectAsStateWithLifecycle()
    // Weekly numbers for the E3e banner: same formula the ViewModel enforces
    // (effective capital × weekly-loss %, ₹100 floor).
    val riskSettings by vm.riskSettings.collectAsStateWithLifecycle()
    // H14 honesty badge (P1): true ONLY when this cycle's signals were really
    // computed WITH Angel One 5-minute candle reads — the ViewModel keeps the
    // flag false on any miss (no keys / market closed / error / loss-limit
    // blank), so rendering it can never lie (B6). Collected here, before any
    // early return, for Compose hook ordering like weekReport above.
    val intradayPowered by vm.intradayPowered.collectAsStateWithLifecycle()

    // ── After-loss trust card (P0 #1 / E1c / U0) — REAL loss today only ───────
    // Collected here (before any early return) for Compose hook ordering, like
    // weekReport above. Driven by the REAL, already-exposed todayRealizedPnl so
    // the card lights up the moment a real trade closes in a net loss today;
    // practice losses never trigger it. TODO(tracking agent): swap the
    // todayRealizedPnl<0 derivation for the precise per-trade after-loss API on
    // InterdayViewModel when it lands (names the single last-closed losing trade).
    val todayRealizedPnl by vm.todayRealizedPnl.collectAsStateWithLifecycle()
    val afterLossAmount: Double? =
        if (!practiceMode && todayRealizedPnl < 0.0) kotlin.math.abs(todayRealizedPnl) else null
    // Dismissible for the day (rememberSaveable survives rotation/process recreate).
    var afterLossDismissed by rememberSaveable { mutableStateOf(false) }

    // ── B2 wall-clock freshness (U1.6/U5.3) — collected + ticked HERE, before any
    // early return, so the hooks run on every path (Compose hook ordering, like
    // weekReport above). The ticking clock flips old prices to amber even while a
    // resumed fetch is still in flight.
    val lastPriceEpochMs by vm.lastPriceEpochMs.collectAsStateWithLifecycle()
    // E3a/H13/C24 honest track record for the intraday top pick — (symbol, line).
    val intradayTopTrust by vm.intradayTopTrustLine.collectAsStateWithLifecycle()
    var freshnessNowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) { delay(30_000L); freshnessNowMs = System.currentTimeMillis() }
    }

    // ── Loading: no data yet ──────────────────────────────────────────────────
    if (state.isLoading && state.signals.isEmpty() && openTrades.isEmpty()) {
        Box(Modifier.fillMaxSize().background(DarkBackground), Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(32.dp)) {
                CircularProgressIndicator(color = GoldAccent, strokeWidth = 3.dp)
                Text("Checking ${state.analyzedCount.coerceAtLeast(200)}+ stocks for you…",
                    style = MaterialTheme.typography.bodyMedium, color = TextSecondary, textAlign = TextAlign.Center)
                Text("We show only the best chances",
                    fontSize = 12.sp, color = TextMuted, textAlign = TextAlign.Center)
            }
        }
        return
    }

    // ── Error: no data ────────────────────────────────────────────────────────
    if (state.error != null && state.signals.isEmpty() && openTrades.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(32.dp).background(DarkBackground), Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.CloudOff, null, modifier = Modifier.size(56.dp), tint = TextMuted)
                Text("Could not load data",
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = TextPrimary, textAlign = TextAlign.Center)
                Text(state.error, fontSize = 12.sp, textAlign = TextAlign.Center, color = TextMuted)
                Spacer(Modifier.height(8.dp))
                Button(onClick = { vm.refresh() }, colors = ButtonDefaults.buttonColors(containerColor = GoldAccent)) {
                    Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp), tint = TextOnGold)
                    Spacer(Modifier.width(8.dp))
                    Text("Try again", color = TextOnGold, fontWeight = FontWeight.Bold)
                }
            }
        }
        return
    }

    // ── Compute RiskGuard with REAL inputs ────────────────────────────────────
    val topSignal = state.signals.firstOrNull()
    val stopLossPct = topSignal?.let {
        if (it.currentPrice > 0.0) (kotlin.math.abs(it.currentPrice - it.stopLoss) / it.currentPrice) * 100.0 else 2.0
    } ?: 2.0
    val riskResult = RiskGuard.evaluate(
        // Rule 3 checks the STOCK's own move, not the NIFTY index change
        changePercent = topSignal?.changePercent ?: 0.0,
        volume        = topSignal?.volume ?: 0L,
        avgVolume     = topSignal?.avgVolume ?: 0L,
        stopLossPct   = stopLossPct,
        vix           = state.vix,
        gapPercent    = topSignal?.gapPercent ?: 0.0
    )

    val marketClosedNow = !RiskGuard.isTradingHoursNow()

    // ── The ONE gate every buy control obeys (I7/B4). Computed PER SIGNAL so a
    // SEARCHED stock is judged on ITS OWN move / gap / volume / stop-loss, never
    // on the top pick's risk (validation fix U7.2/C10/B4). When non-null the Groww
    // button is REALLY disabled (greyed) with this one-line reason — a block must
    // never be an advisory banner above a live button.
    fun blockReasonFor(sig: com.example.myapplication3.intraday.TradingSignal?): String? {
        val slPct = sig?.let {
            if (it.currentPrice > 0.0) (kotlin.math.abs(it.currentPrice - it.stopLoss) / it.currentPrice) * 100.0 else 2.0
        } ?: 2.0
        val rr = RiskGuard.evaluate(
            changePercent = sig?.changePercent ?: 0.0,
            volume        = sig?.volume ?: 0L,
            avgVolume     = sig?.avgVolume ?: 0L,
            stopLossPct   = slPct,
            vix           = state.vix,
            gapPercent    = sig?.gapPercent ?: 0.0
        )
        return when {
            marketClosedNow ->
                "Market closed — opens ${RiskGuard.nextMarketOpenLabel()}"
            // H8: the first 15 minutes are the jumpiest of the day — no fresh buys.
            RiskGuard.isOpeningVolatilityWindow() ->
                RiskGuard.OPENING_WINDOW_MESSAGE
            // E3e: the weekly limit says WEEKLY words.
            state.isDailyLimitBreached && breachedLimit == LossLimitType.WEEKLY ->
                "Enough for this week - we start fresh next week."
            state.isDailyLimitBreached ->
                "Loss limit reached — no new trades today. Saving your money is also earning."
            // Rule 3/4/5/6 on THIS stock's own numbers (moved >5%, gap >3%, thin
            // volume, wide stop) — the searched card now gets its own verdict.
            rr.isBlocked ->
                "${rr.primaryMessage} — buying is off"
            // Capital-path (B0.3a): no budget saved = the MOST cautious case.
            dailyCapital <= 0 ->
                "First tell me your money — tap the ₹ button on the Stock tab"
            dailyCapital in 1..1_999 ->
                "Your money is small for intraday — grow it with a SIP (Mutual Fund tab)"
            dailyCapital in 2_000..9_999 ->
                "Intraday needs ₹10,000+ — see the Stock tab picks instead"
            else -> null
        }
    }
    val buyBlockReason: String? = blockReasonFor(topSignal)

    // ── ONE market-state truth for this whole screen (panel #1): the clock/holiday
    // gate wins over any stale API snapshot, and "Live" may appear ONLY when this
    // says the market is really open. Every badge, chip and banner derives from
    // these two values — never from a per-signal snapshot that can lag behind.
    // Clock+holiday is the ONLY truth: the v7 marketState field is dead (401) and the
    // v8 fallback has no such field, so it reads "CLOSED" forever — trusting it during
    // open hours would show "Market closed" all day (audit ui-truth HIGH finding).
    val effectiveMarketState = if (marketClosedNow) "CLOSED" else "REGULAR"
    val marketOpenNow = effectiveMarketState == "REGULAR"
    // ── ONE staleness truth (panel #2): the screen-level cached flag, applied to
    // EVERY signal card the same way — never one card red while its sibling is clean.
    // B2/U1.6/U5.3: a wall-clock fallback rides ON TOP of the cached flag — prices
    // older than PRICE_STALE_AFTER_MS are stale REGARDLESS of isUsingCachedData, so a
    // ~15-min-old snapshot can never briefly read green while the resumed fetch runs.
    // Only while the market is OPEN — when closed, the neutral "Prices from … close"
    // line (and its own dot) owns freshness, so old-by-the-clock is expected there.
    val wallClockStale = marketOpenNow && lastPriceEpochMs > 0L &&
        (freshnessNowMs - lastPriceEpochMs) > PRICE_STALE_AFTER_MS
    val screenStale = state.isUsingCachedData || wallClockStale
    // B2 plain words for every "Cached" chip: the ViewModel already turns the
    // cache age into "Old price - from X minutes ago" (staleAgeLabel) and ships
    // it in lastUpdated while cached — consume it, never invent a second clock.
    val staleLabel =
        if (state.lastUpdated.startsWith("Old price")) state.lastUpdated
        else "Old price - from earlier"

    val screenerBySymbol = remember(state.allScreenerItems) {
        state.allScreenerItems.associateBy { it.symbol }
    }
    // Cheap/costly screener stays behind ONE tap (B10) — it must never compete
    // with the one decision.
    var screenerExpanded by rememberSaveable { mutableStateOf(false) }
    // Other chances also stay behind ONE tap (C14/B10) — one decision on screen.
    var otherChancesExpanded by rememberSaveable { mutableStateOf(false) }

    // Effective live price for a symbol: real-time Angel One tick when connected,
    // else the latest snapshot the signal already carries.
    fun livePriceFor(symbol: String): Double? =
        if (liveConnected) livePrices[symbol.removeSuffix(".NS").uppercase()] else null

    val onLaunchGroww: (TradingSignal, Double, Int) -> Unit = { s, price, qty ->
        vm.startPendingOrder(s, OrderType.INTRADAY, price, qty)
    }
    val onPracticeBuy: (TradingSignal, Double, Int) -> Unit = { s, price, qty ->
        vm.trackTrade(s, price, OrderType.INTRADAY, qty)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(DarkBackground).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp)
    ) {

        // ── Refresh status ────────────────────────────────────────────────────
        // Hidden when the market is closed (panel #1): nothing fresh can arrive,
        // so a "Getting new data…" bar would contradict the closed banner below.
        if (state.isLoading && effectiveMarketState != "CLOSED") {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Label first + capped; the bar takes the REMAINING width, so the
                    // text can never be pushed off the right edge (maintainer #2).
                    Text("Getting new data…", fontSize = 12.sp, color = TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    LinearProgressIndicator(modifier = Modifier.weight(1f).clip(RoundedCornerShape(2.dp)), color = GoldAccent, trackColor = DarkBorder)
                }
            }
        }

        // ── C9: THE one market banner (open/closed + NIFTY change + timer) ────
        // This is the SINGLE market open/closed indicator on the screen.
        item {
            NseMarketStatusBanner(
                // The ONE market-state truth (panel #1) — a cached "REGULAR" snapshot
                // must never show "Market is Open / LIVE" while the clock says closed.
                marketState = effectiveMarketState,
                niftyChange = state.marketHealth?.niftyChange ?: 0.0,
                timerLabel  = state.marketTimerLabel
            )
        }

        // ── B2: honest "last updated" — the SINGLE freshness clock, always visible
        // under the banner. Fresh data reads "Updated HH:MM" (green dot); stale reads
        // "Old price - from X minutes ago" in amber. The Angel One live-socket dot
        // sits beside it so the user sees BOTH the connection and how fresh the
        // numbers are.
        item {
            FreshnessLine(
                // B2/U1.6: the wall-clock-aware staleness, so old prices show the
                // amber "Old price…" label even before the cached flag flips.
                lastUpdated   = state.lastUpdated,
                isCached      = screenStale,
                liveConnected = liveConnected,
                marketOpen    = marketOpenNow
            )
        }

        // ── 📒 Suggestion record (U9.6/A5) — one quiet, right-aligned line near
        // the top: the honest day-by-day PASS/FAIL diary of THIS tab's own
        // suggestions (the Intraday record — physically separate from the Stock
        // tab's record, the two can never mix). Outlined + compact so the one
        // decision still leads the screen (B10); 48dp touch target (I5).
        // runCatching: if the ledger screen is not registered yet, a tap can
        // never crash the app (B8).
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OutlinedButton(
                    onClick  = { runCatching { navController.navigate("ledger/INTRADAY") } },
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

        // ── After-loss trust card (P0 #1 / E1c) — near the top, when today's REAL
        // trades closed in a net loss. Calm and green: it reassures and reframes the
        // stop-loss as protection working; never scolds, never hides. Dismissible.
        if (afterLossAmount != null && !afterLossDismissed) {
            val amt = afterLossAmount
            item {
                AfterLossTrustCard(
                    lossAmount = amt,
                    onSpeak = {
                        vm.speakText(
                            "Your stop loss did its job. It closed the trade at ${amt.toInt()} rupees loss, " +
                            "instead of a bigger loss. The system is working. Tomorrow is a new day."
                        )
                    },
                    onDismiss = { afterLossDismissed = true }
                )
            }
        }

        // ══ BLOCKS ON TOP ══ When trading is truly off, the block sits ABOVE the
        // pick so the reason is impossible to miss (I7). When nothing blocks, the
        // ONE decision is the first thing under the freshness clock (spec I8/C12).

        // ── C11: Loss Protection — a hard block, kept on top. E3e: the WEEKLY
        // limit shows weekly words and WEEKLY numbers (WEEKLY wins when both
        // are breached — the ViewModel already encodes that priority).
        if (state.isDailyLimitBreached) {
            item {
                if (breachedLimit == LossLimitType.WEEKLY) {
                    // Same formula the ViewModel enforces: effective capital
                    // (today's check-in, else Settings capital) × weekly %, ₹100 floor.
                    val effCap = if (dailyCapital > 0) dailyCapital.toDouble() else riskSettings.capital
                    val weeklyLimit = (effCap * riskSettings.weeklyLossPercent / 100.0).coerceAtLeast(100.0)
                    val weeklyLoss = (-weekReport.netPnl).coerceAtLeast(0.0)
                    WeeklyLimitBanner(
                        weekLoss    = weeklyLoss,
                        maxWeekLoss = weeklyLimit,
                        onSpeak     = { vm.speakText("You have lost too much this week. Trading is off. We start fresh next week. Do not make any new trade this week.") }
                    )
                } else {
                    DailyLimitBanner(
                        dailyLoss    = state.dailyLoss,
                        maxDailyLoss = state.maxDailyLoss,
                        onSpeak      = { vm.speakText("You have lost too much today. Trading is off. Do not make any new trade today.") }
                    )
                }
            }
        }

        // ── C10: Safety Check — on top ONLY when it BLOCKS. In the blocked state
        // the card IS the red "Trading Blocked" answer with its reason box, so no
        // separate duplicate banner is needed above the decision.
        if (riskResult.isBlocked) {
            item { SafetyCheckCard(riskResult, state.vix, noQualifyingSignal = topSignal == null) }
        }

        // ── Capital not set (B0.3a): the MOST cautious case — the app cannot size
        // any trade without knowing the money, so the ask sits on top with the blocks.
        if (dailyCapital <= 0) {
            item { CapitalNotSetCard() }
        }

        // ── C12: THE top pick (the app's own math, not a trained model) ───────
        // The ONE decision — reachable in the first screenful (I8). The single
        // freshness clock above is the one honest "last updated" line, so the old
        // section-header timestamp stays removed (B10).
        if (topSignal != null) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SectionHeader(
                        icon    = Icons.Default.Star,
                        title   = "Top pick (app's math)",
                        subtitle = "The best trade right now"
                    )
                    // H14 (P1): "Using live 5-minute data" — a small green chip shown
                    // ONLY while the ViewModel's honesty flag is true (this cycle's
                    // signals really used Angel One 5-minute reads). False → nothing
                    // renders at all, so the badge can never lie (B6), and nothing
                    // else keys off it — the no-Angel-One screen is unchanged (B8).
                    if (intradayPowered) {
                        Surface(
                            shape  = RoundedCornerShape(8.dp),
                            color  = GreenContainer,
                            border = BorderStroke(0.5.dp, GreenPrimary.copy(alpha = 0.5f))
                        ) {
                            Row(
                                modifier              = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment     = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Box(modifier = Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(GreenPrimary))
                                Text(
                                    "Using live 5-minute data",
                                    style      = MaterialTheme.typography.labelSmall,
                                    color      = GreenPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
            item {
                MasterSignalCard(
                    signal = topSignal,
                    livePrice = livePriceFor(topSignal.stockSymbol),
                    blockReason = buyBlockReason,
                    practiceMode = practiceMode,
                    marketOpen = marketOpenNow,
                    screenStale = screenStale,
                    staleLabel = staleLabel,
                    // First Groww mention on this tab (panel #4) — the one-line intro
                    // rides with the top pick's Groww row, once, never repeated below.
                    showGrowwIntro = true,
                    // H13/C24: the VM's trust line describes signals.first(); only
                    // pass it when this card shows that SAME stock (never a wrong-pick line).
                    trustLine = intradayTopTrust?.takeIf { it.first == topSignal.stockSymbol }?.second,
                    onSpeak = { vm.speakPick(topSignal) },
                    onLaunchGroww = onLaunchGroww,
                    onPracticeBuy = onPracticeBuy
                )
            }
        }

        // ── No-trade mode — the "decision" when nothing qualifies today ───────
        if (state.signals.isEmpty() && !state.isLoading) {
            item { NoTradeCard(state.marketClosed || marketClosedNow, state.marketHealth?.niftyChange ?: 0.0) }
        }

        // ══ EVERYTHING ELSE — below the decision ═══════════════════════════════

        // ── C10 (not blocked): the Safe/Caution status + market mood sits BELOW
        // the decision so it never pushes the pick down. Soft warnings ride with it.
        // Guarded so exactly ONE Safety Check renders (top if blocked, here if not).
        if (!riskResult.isBlocked) {
            item { SafetyCheckCard(riskResult, state.vix, noQualifyingSignal = topSignal == null) }
            if (riskResult.warnings.isNotEmpty()) {
                items(riskResult.warnings) { w ->
                    RiskGuardBanner(w.message, isBlock = w.severity == RiskSeverity.HIGH)
                }
            }
        }

        // ── One honest ceiling line (A5/B0.3a) — never oversell intraday ──────
        item {
            Text(
                "These are ideas from daily numbers — the app cannot promise profit. Small money is safer in the Stock tab or a monthly SIP.",
                fontSize = 12.sp,
                color = TextMuted,
                lineHeight = 16.sp,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // ── This week's honest score (E1a) — real wins/losses, not a formula ──
        item { WeeklyScoreLine(weekReport) }

        // Small money (or no budget at all) should be steered AWAY from intraday
        // (charges + fast losses). Capital-not-set keeps this caution too — never
        // unrestricted access just because the ₹ button was skipped.
        if (dailyCapital in 0..9_999) {
            item { IntradayCapitalCaution(dailyCapital) }
        }

        // ── C15: Your open trades — visible on the tab where they were made ──
        if (openTrades.isNotEmpty()) {
            item {
                SectionHeader(
                    icon = Icons.Default.Assignment, title = "Your open trades",
                    subtitle = "The app is watching these for you"
                )
            }
            items(openTrades, key = { "trade-${it.id}" }) { trade ->
                val ctx = LocalContext.current
                val px = livePriceFor(trade.symbol)
                    ?: screenerBySymbol[trade.symbol]?.currentPrice
                    ?: state.signals.firstOrNull { it.stockSymbol == trade.symbol }?.currentPrice
                    ?: 0.0
                MyStockCard(
                    trade = trade,
                    currentPrice = px,
                    onSell = { GrowwLauncher.openStock(ctx, trade.symbol) },
                    onMarkSold = { exit -> vm.closeTrackedTrade(trade.id, exit) }
                )
            }
        }

        // ── C16: Search a stock for an instant verdict ────────────────────────
        item { AiSearchSection(state = state, onQuery = { vm.setAiSearchQuery(it) }, onClear = { vm.clearAiSearch() }) }

        // ── Search result card ────────────────────────────────────────────────
        state.aiSearchResult?.let { found ->
            item {
                Surface(color = GoldContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    // Thin 4dp inset only — MasterSignalCard already carries its own 14dp
                    // inner padding, so a fat wrapper inset would shrink the decision card.
                    Column(Modifier.padding(4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Default.Search, null, modifier = Modifier.size(16.dp), tint = GoldLight)
                            Text("Found", fontSize = 12.sp, color = GoldLight, fontWeight = FontWeight.Bold)
                        }
                        MasterSignalCard(
                            signal = found,
                            livePrice = livePriceFor(found.stockSymbol),
                            // Per-signal risk for the SEARCHED stock (U7.2/B4 fix).
                            blockReason = blockReasonFor(found),
                            practiceMode = practiceMode,
                            marketOpen = marketOpenNow,
                            screenStale = screenStale,
                            staleLabel = staleLabel,
                            // Only when the searched stock IS the top pick (symbols match).
                            trustLine = intradayTopTrust?.takeIf { it.first == found.stockSymbol }?.second,
                            onSpeak = { vm.speakPick(found) },
                            onLaunchGroww = onLaunchGroww,
                            onPracticeBuy = onPracticeBuy
                        )
                    }
                }
            }
        }

        // ── C14: Other opportunities — collapsed behind ONE tap (panel #3/B10) so
        // the screen keeps exactly one decision. A single row says how many more
        // chances exist; tapping it opens the list.
        val otherSignals = state.signals.drop(1)
        if (otherSignals.isNotEmpty()) {
            item {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = DarkCard,
                    modifier = Modifier.fillMaxWidth().clickable { otherChancesExpanded = !otherChancesExpanded }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.BarChart, contentDescription = null, tint = GoldAccent, modifier = Modifier.size(20.dp))
                            Column {
                                Text("More chances today (${otherSignals.size})", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                Text(if (otherChancesExpanded) "Tap to hide" else "See more", fontSize = 12.sp, color = TextMuted)
                            }
                        }
                        Icon(
                            if (otherChancesExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = null, tint = GoldAccent
                        )
                    }
                }
            }
            if (otherChancesExpanded) {
                // ONE honesty caption for the whole list (I1/A5) — the per-card copy was
                // removed because it collided with long stock symbols.
                item {
                    Text(
                        "Setup words show quality — not a promise of profit.",
                        fontSize = 12.sp, color = TextMuted, lineHeight = 16.sp,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                items(otherSignals, key = { "sig-${it.stockSymbol}" }) { signal ->
                    CompactSignalCard(
                        signal      = signal,
                        marketOpen  = marketOpenNow,
                        screenStale = screenStale,
                        onSpeak     = { vm.speakPick(signal) }
                    )
                }
            }
        }

        // ── The screener — ONE tap away, never competing with the decision ────
        item {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = DarkCard,
                modifier = Modifier.fillMaxWidth().clickable { screenerExpanded = !screenerExpanded }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("See all stocks", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        Text("Optional — find cheap / costly stocks", fontSize = 12.sp, color = TextMuted)
                    }
                    Icon(
                        if (screenerExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = null, tint = GoldAccent
                    )
                }
            }
        }
        if (screenerExpanded) {
            item { ScreenerControls(vm = vm, state = state) }
            if (state.filteredScreenerItems.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            if (state.screenerSearchQuery.isNotEmpty()) "\"${state.screenerSearchQuery}\" not found"
                            else "No data yet — tap the refresh icon on top to load",
                            style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = TextSecondary
                        )
                        if (state.screenerSearchQuery.isNotEmpty()) {
                            OutlinedButton(
                                onClick = { navController.navigate("research/${android.net.Uri.encode(state.screenerSearchQuery.uppercase().trim())}") },
                                border  = BorderStroke(1.dp, BlueAccent),
                                colors  = ButtonDefaults.outlinedButtonColors(contentColor = BlueAccent)
                            ) {
                                Icon(Icons.Default.ManageSearch, null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Research \"${state.screenerSearchQuery.uppercase().trim()}\"")
                            }
                        }
                    }
                }
            } else {
                items(state.filteredScreenerItems, key = { "sc-${it.symbol}" }) { item ->
                    ScreenerStockCard(
                        item       = item,
                        filterType = state.screenerFilter,
                        days       = state.screenerDays,
                        // Live Angel One tick per row (P1) — the same shared feed the
                        // pick uses (livePriceFor gates on liveConnected). Fail-safe:
                        // feed off / no tick → null → the exact old snapshot row. This
                        // one card is BOTH the top-movers view (the default "sorted by
                        // % move" list) and the Lowest/Highest screener views.
                        livePrice  = livePriceFor(item.symbol),
                        marketOpen = marketOpenNow,
                        onResearch = { navController.navigate("research/${item.symbol}") }
                    )
                }
            }
        }

        // ── Refresh + disclaimer ──────────────────────────────────────────────
        item {
            OutlinedButton(
                onClick  = { vm.refresh() },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                border   = BorderStroke(1.dp, DarkBorder),
                colors   = ButtonDefaults.outlinedButtonColors(contentColor = GoldAccent)
            ) {
                Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Refresh")
            }
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Warning, null, tint = TextMuted, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    "This is only to help you learn. No profit is promised. Trading can lose money.",
                    fontSize = 12.sp, color = TextMuted, textAlign = TextAlign.Center
                )
            }
        }
    }
}

// ── Section Header helper ─────────────────────────────────────────────────────
// Groww-clean: a flat Material line-icon (no emoji) + title + subtitle. The old
// duplicate "timestamp" slot is gone — the single freshness clock is above.
@Composable
private fun SectionHeader(icon: ImageVector, title: String, subtitle: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, contentDescription = null, tint = GoldAccent, modifier = Modifier.size(20.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = TextPrimary)
            Text(subtitle, fontSize = 12.sp, color = TextMuted)
        }
    }
}

// ── B2: freshness line — the honest "last updated" clock, always under the banner ─
// Fresh: green dot + "Updated HH:MM". Stale/cached: amber dot + the ViewModel's
// plain-words age label ("Old price - from X minutes ago") shown AS-IS — never
// the analyst word "Cached". The Angel One "● Live" socket dot rides on the same
// line so the user sees live-connection AND data-freshness together.
@Composable
private fun FreshnessLine(lastUpdated: String, isCached: Boolean, liveConnected: Boolean, marketOpen: Boolean = true) {
    if (lastUpdated.isEmpty()) return
    // ── Market closed (panel #1/#2): ONE neutral truth — "Prices from Friday's
    // close" — never a "Live" chip and never amber cache-alarm spam. The market
    // banner above already says closed + when it opens; this line only says where
    // the prices come from.
    if (!marketOpen) {
        Row(
            modifier              = Modifier.fillMaxWidth(),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(modifier = Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(TextMuted))
            Text(
                "Prices from ${lastCloseDayLabel()} close",
                fontSize   = 12.sp,
                color      = TextSecondary,
                fontWeight = FontWeight.Medium
            )
        }
        return
    }
    val timeText = lastUpdated.removePrefix("Cached:").trim()
    // B2 plain words: while cached, the ViewModel's lastUpdated already IS the
    // beginner-readable age line ("Old price - from X minutes ago") — show it
    // verbatim. Older builds' "Cached: HH:MM" values fall back to the same words.
    val staleText =
        if (timeText.startsWith("Old price")) timeText else "Old price - from $timeText"
    // Stale = amber (CautionAmber), fresh = green. GoldAccent is now brand-green, so
    // the cached state must use the real caution amber, never brand green.
    val dotColor = if (isCached) CautionAmber else GreenPrimary
    Row(
        modifier              = Modifier.fillMaxWidth(),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(dotColor)
        )
        Text(
            if (isCached) staleText else "Updated $timeText",
            fontSize   = 12.sp,
            color      = if (isCached) CautionAmber else TextSecondary,
            fontWeight = FontWeight.Medium
        )
        // "Live" may exist ONLY while the market is really open (panel #1) —
        // marketOpen is guaranteed true past the early-return above.
        if (liveConnected) {
            Spacer(Modifier.weight(1f))
            // Live socket dot — GREEN, never a red "LIVE" emoji.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(modifier = Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(GreenPrimary))
                Text("Live", fontSize = 12.sp, color = GreenPrimary, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ── This week's honest report (E1a) — real wins AND losses build trust ────────
// One plain line of real closed-trade results. When nothing has closed yet it
// says so honestly instead of showing a fake "0 wins".
@Composable
private fun WeeklyScoreLine(report: com.example.myapplication3.tracking.WeekReport) {
    val net = report.netPnl
    val line = if (report.total == 0)
        "This week: no closed trades yet"
    else
        "This week: ${report.wins} right, ${report.losses} wrong, net ${if (net >= 0) "+₹" else "-₹"}${fmtRs(kotlin.math.abs(net))}"
    Surface(
        color    = DarkCard,
        shape    = RoundedCornerShape(12.dp),
        border   = BorderStroke(0.5.dp, DarkBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier              = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Default.CalendarMonth, null, tint = TextMuted, modifier = Modifier.size(16.dp))
            Text(
                line,
                style      = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color      = if (report.total == 0) TextMuted else if (net >= 0) GreenPrimary else RedPrimary
            )
        }
    }
}

// ── After-loss trust card (P0 #1 / E1c / U0) — the emotional-safety card ──────
// After a losing trade the app must NOT go silent or blame the user. This calm,
// green, dismissible card reframes the stop-loss as protection that worked (U9.6
// honesty + protection, never blame) and points to a fresh tomorrow. Green (the
// safe-state colour, U9.3), never red — a loss already happened; this is reassurance,
// not another alarm. Carries a SpeakButton so the zero-reading user hears the same
// words (U1.5). The ₹ amount is fed in, so swapping to the precise per-trade
// tracking API later touches only the caller.
@Composable
private fun AfterLossTrustCard(
    lossAmount: Double,
    onSpeak: () -> Unit,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
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
                    "Your stop-loss did its job",
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
            SpeakButton(onSpeak = onSpeak)
        }
    }
}

// ── Budget-not-set ask (B0.3a) — ONE plain line, shown when the user never told
// the app their money. Amber caution style: it protects, it does not sell.
@Composable
private fun CapitalNotSetCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CautionContainer),
        border = BorderStroke(1.dp, CautionAmber.copy(alpha = 0.6f)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Default.CurrencyRupee, null, tint = CautionAmber, modifier = Modifier.size(18.dp))
            Text(
                "First tell me your money — tap the ₹ button on the Stock tab — so I can keep you safe.",
                fontSize = 12.sp, lineHeight = 16.sp,
                fontWeight = FontWeight.SemiBold, color = TextPrimary,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

// ── Small-money caution (B0.3a) ───────────────────────────────────────────────
@Composable
private fun IntradayCapitalCaution(capital: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CautionContainer),
        border = BorderStroke(1.dp, CautionAmber.copy(alpha = 0.6f)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Default.Warning, null, tint = CautionAmber, modifier = Modifier.size(18.dp))
                Text(
                    // No budget saved yet → no "₹0" nonsense; the plain truth instead.
                    if (capital > 0) "Intraday is risky for ₹${fmtRs(capital.toDouble())}"
                    else "Intraday is risky",
                    fontWeight = FontWeight.ExtraBold, color = TextPrimary,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                "With small money, charges and fast losses hurt the most. For you, delivery (Stock tab) or a monthly SIP (Mutual Fund tab) is much safer.",
                style = MaterialTheme.typography.bodySmall, color = TextSecondary
            )
        }
    }
}

// ── C10: Safety Check Card ────────────────────────────────────────────────────

/** Typed holder — destructuring a heterogeneous listOf() yields Any and doesn't compile. */
private data class StatusStyle(val color: Color, val icon: ImageVector, val label: String, val hindi: String)

@Composable
private fun SafetyCheckCard(riskResult: RiskGuardResult, vix: Double, noQualifyingSignal: Boolean = false) {
    val (safeColor, safeIcon, safeLabel, _) = when {
        riskResult.isBlocked -> StatusStyle(RedPrimary, Icons.Default.Block, "Trading Blocked", "Trading off")
        riskResult.warnings.count { it.severity == RiskSeverity.HIGH } >= 2 -> StatusStyle(RedPrimary, Icons.Default.Warning, "High Risk", "High risk")
        riskResult.warnings.isNotEmpty() -> StatusStyle(CautionAmber, Icons.Default.Bolt, "Caution", "Be careful")
        // U0/I8/C10: on a no-signal day the conditions may be calm, but a green
        // "Safe to Trade" tick sitting right under the amber "NO TRADE" card is a
        // mixed message a beginner cannot resolve in ten seconds. When there is no
        // qualifying pick, say so — amber, matching the NO-TRADE shield above —
        // instead of a green go-signal.
        noQualifyingSignal -> StatusStyle(CautionAmber, Icons.Default.Security, "Calm — but no trade today", "No trade")
        else -> StatusStyle(GreenPrimary, Icons.Default.CheckCircle, "Safe to Trade", "Safe")
    }
    Surface(color = DarkCard, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, safeColor.copy(alpha = 0.4f)), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(safeIcon, null, tint = safeColor, modifier = Modifier.size(24.dp))
                Column {
                    Text("Safety Check", fontSize = 12.sp, color = TextMuted)
                    Text(safeLabel, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = safeColor)
                }
                Spacer(Modifier.weight(1f))
                // Market mood from India VIX — translated to plain words (I2), and
                // honestly "not available" instead of silently disappearing at 0.0.
                Column(horizontalAlignment = Alignment.End) {
                    Text("Market mood", fontSize = 12.sp, color = TextMuted)
                    when {
                        vix <= 0.0 -> Text("not available now", fontSize = 12.sp, color = TextMuted)
                        vix > 20.0 -> Text("very jumpy", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = RedPrimary)
                        vix > 15.0 -> Text("a bit jumpy", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = CautionAmber)
                        else       -> Text("calm", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = GreenPrimary)
                    }
                }
            }
            if (!riskResult.canTrade) {
                Surface(color = RedContainer, shape = RoundedCornerShape(8.dp)) {
                    // H8: the opening-window block already says "wait till 9:30" —
                    // adding "Do not trade today" under it would be a lie.
                    val tail = if (riskResult.primaryMessage == RiskGuard.OPENING_WINDOW_MESSAGE) ""
                               else "\nDo not trade today — the risk is too high."
                    Text("${riskResult.primaryMessage}$tail",
                        fontSize = 12.sp, lineHeight = 16.sp, color = RedPrimary, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                }
            }
        }
    }
}

// ── C16: AI Search ────────────────────────────────────────────────────────────
@Composable
private fun AiSearchSection(state: InterdayUiState, onQuery: (String) -> Unit, onClear: () -> Unit) {
    OutlinedTextField(
        value         = state.aiSearchQuery,
        onValueChange = onQuery,
        modifier      = Modifier.fillMaxWidth(),
        placeholder   = { Text("Search a stock (e.g. RELIANCE)", color = TextMuted, style = MaterialTheme.typography.bodySmall) },
        leadingIcon   = { Icon(Icons.Default.Search, null, tint = TextMuted) },
        trailingIcon  = {
            if (state.aiSearchQuery.isNotEmpty()) {
                IconButton(onClick = onClear) { Icon(Icons.Default.Close, null, modifier = Modifier.size(18.dp), tint = TextMuted) }
            }
        },
        singleLine = true,
        shape      = RoundedCornerShape(12.dp),
        colors     = OutlinedTextFieldDefaults.colors(
            focusedBorderColor   = GoldAccent,
            unfocusedBorderColor = DarkBorder,
            focusedTextColor     = TextPrimary,
            unfocusedTextColor   = TextPrimary,
            cursorColor          = GoldAccent
        )
    )
    if (state.aiSearchQuery.isNotEmpty() && state.aiSearchResult == null) {
        // Honesty (B6): "wait" is a verdict only for stocks we actually analyzed.
        Text(
            if (state.aiSearchInUniverse)
                "No strong chance on '${state.aiSearchQuery}' today — best to wait"
            else
                "We haven't checked '${state.aiSearchQuery}' here — use the Stock tab search for full research",
            fontSize = 12.sp, lineHeight = 16.sp, color = TextMuted, modifier = Modifier.padding(top = 4.dp)
        )
    }
}

// ══════════════════════════════════════════════════════════════════════════════
//  Screener (behind one tap — B10)
// ══════════════════════════════════════════════════════════════════════════════

@Composable
private fun ScreenerControls(vm: InterdayViewModel, state: InterdayUiState) {
    // C6: this screener is FIXED to the 52-week (1-year) view — the 5D/10D/20D
    // period chips are gone (one analyst dial fewer, same as the Stock tab).
    // Self-healing: the shared ViewModel can arrive with another period set
    // elsewhere; normalise it back to 52 whenever this panel is open.
    LaunchedEffect(state.screenerDays) {
        if (state.screenerDays != 52) vm.setScreenerFilter(state.screenerFilter, 52)
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Search bar
        OutlinedTextField(
            value         = state.screenerSearchQuery,
            onValueChange = { vm.setScreenerSearchQuery(it) },
            modifier      = Modifier.fillMaxWidth(),
            placeholder   = { Text("Search symbol…", color = TextMuted) },
            leadingIcon   = { Icon(Icons.Default.Search, null, tint = TextMuted) },
            trailingIcon  = {
                if (state.screenerSearchQuery.isNotEmpty()) {
                    IconButton(onClick = { vm.setScreenerSearchQuery("") }) {
                        Icon(Icons.Default.Close, null, modifier = Modifier.size(18.dp), tint = TextMuted)
                    }
                }
            },
            singleLine = true,
            shape      = RoundedCornerShape(12.dp),
            colors     = OutlinedTextFieldDefaults.colors(
                focusedBorderColor   = GoldAccent,
                unfocusedBorderColor = DarkBorder,
                focusedTextColor     = TextPrimary,
                unfocusedTextColor   = TextPrimary,
                cursorColor          = GoldAccent
            )
        )

        // C6: no period chips here — the whole list is the honest 1-year view.

        // Filter type buttons
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterTypeButton(
                label    = "Lowest",
                icon     = Icons.Default.SouthEast,
                selected = state.screenerFilter == PriceFilterType.N_DAY_LOW,
                color    = GreenPrimary,
                onClick  = {
                    vm.setScreenerFilter(
                        if (state.screenerFilter == PriceFilterType.N_DAY_LOW) PriceFilterType.NONE
                        else PriceFilterType.N_DAY_LOW,
                        52   // C6: fixed 52-week view
                    )
                },
                modifier = Modifier.weight(1f)
            )
            FilterTypeButton(
                label    = "Highest",
                icon     = Icons.Default.NorthEast,
                selected = state.screenerFilter == PriceFilterType.N_DAY_HIGH,
                color    = RedPrimary,
                onClick  = {
                    vm.setScreenerFilter(
                        if (state.screenerFilter == PriceFilterType.N_DAY_HIGH) PriceFilterType.NONE
                        else PriceFilterType.N_DAY_HIGH,
                        52   // C6: fixed 52-week view
                    )
                },
                modifier = Modifier.weight(1f)
            )
            FilterTypeButton(
                label    = "All",
                icon     = Icons.Default.BarChart,
                selected = state.screenerFilter == PriceFilterType.NONE,
                color    = BlueAccent,
                onClick  = { vm.setScreenerFilter(PriceFilterType.NONE, 52) },   // C6: fixed 52-week view
                modifier = Modifier.weight(1f)
            )
        }

        // Result summary — plain words, fixed 1-year view (C6), no "52W" shorthand
        val filterLabel = when (state.screenerFilter) {
            PriceFilterType.N_DAY_LOW  -> "Nearest to their 1-year low first"
            PriceFilterType.N_DAY_HIGH -> "Nearest to their 1-year high first"
            PriceFilterType.NONE       -> "All stocks, sorted by % move"
        }
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically
        ) {
            Text(filterLabel, fontSize = 12.sp, color = TextMuted, modifier = Modifier.weight(1f))
            Text(
                "${state.filteredScreenerItems.size} stocks",
                fontSize   = 12.sp,
                color      = GoldAccent,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun FilterTypeButton(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedButton(
        onClick        = onClick,
        modifier       = modifier.height(48.dp),
        border         = BorderStroke(1.5.dp, if (selected) color else DarkBorder),
        colors         = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) color.copy(alpha = 0.12f) else Color.Transparent,
            contentColor   = if (selected) color else TextSecondary
        ),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
        shape          = RoundedCornerShape(8.dp)
    ) {
        Icon(icon, null, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(
            label,
            style      = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines   = 1,
            overflow   = TextOverflow.Clip
        )
    }
}

@Composable
private fun ScreenerStockCard(
    item: StockScreenerItem,
    filterType: PriceFilterType,
    days: Int,
    // Real-time Angel One tick for THIS row (null = feed off / no tick yet) and
    // the screen's ONE market-state truth (panel #1). Defaults keep every other
    // caller and the no-Angel-One experience byte-for-byte unchanged (B8).
    livePrice: Double? = null,
    marketOpen: Boolean = true,
    onResearch: () -> Unit
) {
    val changeColor = if (item.changePercent >= 0) GreenPrimary else RedPrimary
    // ── Live tick overlay (P1) — DISPLAY only, fail-safe: the real-time price
    // replaces the snapshot number ONLY while the market is really open (panel
    // #1 — a last socket tick after the close must never claim "live") and the
    // tick is sane (> 0). The verdict line, badge maths, % chip, sparkline and
    // period stats all stay computed from the SAME snapshot item, so no
    // gating/staleness rule moves anywhere (B6/B8).
    val rowLivePrice = if (marketOpen) livePrice?.takeIf { it > 0.0 } else null
    val effPrice     = rowLivePrice ?: item.currentPrice
    // Honesty: symbols without daily-close history fall back to 52-week bounds
    // upstream — label them "1Y", never as the N-day low/high they are not.
    val hasRealPeriod = item.historicalCloses.isNotEmpty()
    val periodLabel = if (days == 52 || !hasRealPeriod) "1Y" else "${days}D"

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(containerColor = DarkCard),
        shape    = RoundedCornerShape(12.dp),
        border   = BorderStroke(0.5.dp, DarkBorder)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Header
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(item.symbol, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.ExtraBold, color = TextPrimary)
                    Text(item.name, fontSize = 12.sp, color = TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(horizontalAlignment = Alignment.End) {
                        Row(
                            verticalAlignment     = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            // Green Live dot (U9.3): this row's price is a real-time
                            // tick — same dot language as the pick card / FreshnessLine.
                            if (rowLivePrice != null) {
                                Box(modifier = Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(GreenPrimary))
                            }
                            Text(
                                "₹${fmtPrice(effPrice)}",
                                style      = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color      = TextPrimary
                            )
                        }
                        Surface(shape = RoundedCornerShape(6.dp), color = if (item.changePercent >= 0) GreenContainer else RedContainer) {
                            Text(
                                "${if (item.changePercent >= 0) "+" else ""}${String.format("%.2f", item.changePercent)}%",
                                style    = MaterialTheme.typography.labelSmall,
                                color    = changeColor,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    if (item.historicalCloses.size >= 5) {
                        PriceSparkline(prices = item.historicalCloses, isUp = item.changePercent >= 0, modifier = Modifier.width(60.dp).height(28.dp))
                    }
                }
            }

            // C6: one-line plain verdict — same words as the Stock tab rows.
            Text(
                screenerVerdictLine(item),
                style    = MaterialTheme.typography.bodySmall,
                color    = TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            // Period stats — plain words, no analyst shorthand (I2)
            if (filterType != PriceFilterType.NONE) {
                HorizontalDivider(color = DarkBorder)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    when (filterType) {
                        PriceFilterType.N_DAY_LOW -> {
                            PeriodStatChip("$periodLabel low",  "₹${fmtRs(item.periodLow)}", GreenPrimary)
                            PeriodStatChip("Above low",  "+${String.format("%.1f", item.distFromPeriodLowPct)}%",
                                if (item.distFromPeriodLowPct < 3) GreenPrimary else TextPrimary)
                            PeriodStatChip("Buying today", "${String.format("%.1f", item.volRatio)}x usual",
                                if (item.volRatio >= 1.5) GreenPrimary else TextSecondary)
                        }
                        PriceFilterType.N_DAY_HIGH -> {
                            PeriodStatChip("$periodLabel high", "₹${fmtRs(item.periodHigh)}", RedPrimary)
                            PeriodStatChip("Below high", "-${String.format("%.1f", item.distFromPeriodHighPct)}%",
                                if (item.distFromPeriodHighPct < 3) RedPrimary else TextPrimary)
                            PeriodStatChip("Buying today", "${String.format("%.1f", item.volRatio)}x usual",
                                if (item.volRatio >= 1.5) GreenPrimary else TextSecondary)
                        }
                        else -> {}
                    }
                }
            } else {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (item.ma50 > 0) {
                        val above = item.currentPrice > item.ma50
                        PeriodStatChip("Recent weeks",
                            "${if (above) "+" else ""}${String.format("%.1f", (item.currentPrice - item.ma50) / item.ma50 * 100)}%",
                            if (above) GreenPrimary else RedPrimary)
                    }
                    if (item.ma200 > 0) {
                        val above = item.currentPrice > item.ma200
                        PeriodStatChip("Many months",
                            "${if (above) "+" else ""}${String.format("%.1f", (item.currentPrice - item.ma200) / item.ma200 * 100)}%",
                            if (above) GreenPrimary else RedPrimary)
                    }
                    PeriodStatChip("Buying today", "${String.format("%.1f", item.volRatio)}x usual",
                        if (item.volRatio >= 1.5) GreenPrimary else TextSecondary)
                }
            }

            // Research button
            HorizontalDivider(color = DarkBorder)
            TextButton(
                onClick        = onResearch,
                modifier       = Modifier.fillMaxWidth().height(48.dp),
                contentPadding = PaddingValues(0.dp)
            ) {
                Icon(Icons.Default.ManageSearch, null, modifier = Modifier.size(16.dp), tint = BlueAccent)
                Spacer(modifier = Modifier.width(4.dp))
                Text("Full Research + AI Prediction", style = MaterialTheme.typography.labelSmall, color = BlueAccent, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.weight(1f))
                Icon(Icons.Default.ChevronRight, null, modifier = Modifier.size(14.dp), tint = BlueAccent)
            }
        }
    }
}

@Composable
private fun PeriodStatChip(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextMuted)
        Text(value, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.ExtraBold, color = color, fontSize = 11.sp)
    }
}

// Public — also used by StockResearchScreen and MutualFundsScreen.
@Composable
fun PriceSparkline(prices: List<Double>, isUp: Boolean, modifier: Modifier = Modifier) {
    if (prices.size < 2) return
    val color = if (isUp) GreenPrimary else RedPrimary
    Canvas(modifier = modifier) {
        val min   = prices.min()
        val max   = prices.max()
        val range = max - min
        // Relative epsilon — near-flat series (float noise) would otherwise explode the y-scale
        if (range < max * 1e-6) return@Canvas
        val step = size.width / (prices.size - 1)
        val path = Path()
        prices.forEachIndexed { idx, price ->
            val x = idx * step
            val y = size.height * (1f - ((price - min) / range).toFloat())
            if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = color, style = Stroke(width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

// ══════════════════════════════════════════════════════════════════════════════
//  BANNERS
// ══════════════════════════════════════════════════════════════════════════════

@Composable
private fun NseMarketStatusBanner(marketState: String, niftyChange: Double, timerLabel: String = "") {
    val isOpen   = marketState == "REGULAR"
    val isPre    = marketState == "PRE"
    val isPost   = marketState == "POST"
    // Pre/post = CAUTION amber (not fully open), open = green, closed = red. GoldAccent
    // is now brand-green, so the "half-open" states must use the real caution amber.
    val bgColor  = when { isOpen -> GreenContainer;    isPre || isPost -> CautionContainer;    else -> RedContainer }
    val brdColor = when { isOpen -> GreenPrimary.copy(alpha = 0.5f); isPre || isPost -> CautionAmber.copy(alpha = 0.5f); else -> RedPrimary.copy(alpha = 0.4f) }
    val txtColor = when { isOpen -> GreenPrimary;       isPre || isPost -> CautionAmber;       else -> RedPrimary }
    val icon     = when { isOpen -> Icons.Default.CheckCircle; isPre -> Icons.Default.Schedule; isPost -> Icons.Default.NightsStay; else -> Icons.Default.Lock }
    val title    = when { isOpen -> "Market is Open"; isPre -> "Market opening soon"; isPost -> "Market Closed"; else -> "Market Closed" }
    // Closed → say when it opens, honestly and specifically (B9). Open → NIFTY %
    // move + the countdown, so the timer needs no extra card of its own.
    val sub      = when {
        isOpen -> buildString {
            append("NIFTY ${if (niftyChange >= 0) "+" else ""}${String.format("%.2f", niftyChange)}% today")
            if (timerLabel.startsWith("Market closes")) append(" • ${timerLabel.removePrefix("Market ").trim()}")
        }
        isPre  -> "Opens at 9:15 AM"
        else   -> "Opens ${RiskGuard.nextMarketOpenLabel()}"
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(containerColor = bgColor),
        border   = BorderStroke(1.dp, brdColor),
        shape    = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier              = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = txtColor, modifier = Modifier.size(22.dp))
                Column {
                    Text(title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.ExtraBold, color = txtColor)
                    Text(sub,   fontSize = 12.sp,  color = TextSecondary)
                }
            }
            Surface(shape = RoundedCornerShape(6.dp), color = txtColor.copy(alpha = 0.15f)) {
                Text(
                    when (marketState) { "REGULAR" -> "LIVE"; "PRE" -> "OPENING SOON"; else -> "CLOSED" },
                    style      = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color      = txtColor,
                    modifier   = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }
    }
}

@Composable
private fun DailyLimitBanner(dailyLoss: Double, maxDailyLoss: Double, onSpeak: (() -> Unit)? = null) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(containerColor = RedContainer),
        border   = BorderStroke(1.dp, RedPrimary.copy(alpha = 0.6f)),
        shape    = RoundedCornerShape(12.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Warning, null, tint = RedPrimary, modifier = Modifier.size(28.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
                Text("Daily loss limit reached. Trading is off.", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold, color = RedPrimary)
                Text("You lost ₹${fmtRs(dailyLoss)} today. Safety limit is ₹${fmtRs(maxDailyLoss)}. No new trades today.", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
            if (onSpeak != null) SpeakButton(onSpeak)
        }
    }
}

// ── E3e: the WEEKLY loss stop — weekly words + WEEKLY numbers, so a user blocked
// for the week is never told the daily lie "no new trades today".
@Composable
private fun WeeklyLimitBanner(weekLoss: Double, maxWeekLoss: Double, onSpeak: (() -> Unit)? = null) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(containerColor = RedContainer),
        border   = BorderStroke(1.dp, RedPrimary.copy(alpha = 0.6f)),
        shape    = RoundedCornerShape(12.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Warning, null, tint = RedPrimary, modifier = Modifier.size(28.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
                Text("Enough for this week - we start fresh next week.", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold, color = RedPrimary)
                Text("You lost ₹${fmtRs(weekLoss)} this week. Weekly safety limit is ₹${fmtRs(maxWeekLoss)}. No new trades this week.", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
            if (onSpeak != null) SpeakButton(onSpeak)
        }
    }
}

@Composable
private fun RiskGuardBanner(message: String, isBlock: Boolean) {
    val bgColor  = if (isBlock) RedContainer else CautionContainer
    val brdColor = if (isBlock) RedPrimary.copy(0.6f) else CautionAmber.copy(0.6f)
    val iconTint = if (isBlock) RedPrimary else CautionAmber
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = bgColor), border = BorderStroke(1.dp, brdColor), shape = RoundedCornerShape(12.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (isBlock) Icons.Default.Block else Icons.Default.Warning, null, tint = iconTint, modifier = Modifier.size(20.dp))
            Text(message, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = if (isBlock) RedPrimary else TextPrimary)
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
//  MASTER SIGNAL CARD  (Groww-clean single decision)
// ══════════════════════════════════════════════════════════════════════════════
// The one decision (B5/B10): action badge + price + shares + stop + target +
// profit-after-charges + the Groww button. Everything analytical — the raw score,
// the reasons, extra targets, the signal-lean bar and market-state — stays folded
// behind "See more". All the loss-prevention logic (stale-entry disable, cached-
// price honesty, net-profit gate) is preserved exactly.
@Composable
private fun MasterSignalCard(
    signal: TradingSignal,
    livePrice: Double? = null,
    blockReason: String? = null,
    practiceMode: Boolean = false,
    marketOpen: Boolean = true,
    screenStale: Boolean = false,
    // B2 plain words for the stale chip — the ViewModel's age line
    // ("Old price - from X minutes ago"); never the analyst word "Cached".
    staleLabel: String = "Old price",
    showGrowwIntro: Boolean = false,
    // H13/C24 honest track record ("This kind of pick was right 7 out of 10 times
    // last year") — shown under "See more" ONLY when non-null (never fabricated).
    trustLine: String? = null,
    onSpeak: (() -> Unit)? = null,
    onLaunchGroww: (TradingSignal, Double, Int) -> Unit = { _, _, _ -> },
    onPracticeBuy: (TradingSignal, Double, Int) -> Unit = { _, _, _ -> }
) {
    val isBuy       = signal.action == SignalAction.BUY
    val actionColor = if (isBuy) GreenPrimary else RedPrimary
    val actionIcon  = if (isBuy) Icons.Default.TrendingUp else Icons.Default.TrendingDown
    // ONE staleness condition for every card (panel #2): the screen-level cached
    // flag OR this quote's own cached stamp — identical formula on master + compact.
    val isCached    = signal.validUntil == "Cached" || screenStale
    // Real-time tick wins over the snapshot the signal carries
    val effPrice    = livePrice?.takeIf { it > 0.0 } ?: signal.currentPrice

    // ── C12a stale-entry protection: the entry zone was FIXED when the signal
    // first appeared today. If the live price ran past it (or the setup died, or
    // the engine already flagged it chanceGone), the card itself must flip — an old
    // rate must never stay buyable on screen (I7).
    if (isBuy && !isCached && (signal.chanceGone || signal.buyChancePassed(effPrice))) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors   = CardDefaults.cardColors(containerColor = DarkCard),
            border   = BorderStroke(2.dp, CautionAmber.copy(alpha = 0.6f)),
            shape    = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text(signal.stockSymbol, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                        Text("now ₹${fmtPrice(effPrice)}", fontSize = 12.sp, color = TextMuted)
                    }
                    if (onSpeak != null) SpeakButton(onSpeak)
                }
                Surface(color = CautionContainer, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Default.HourglassEmpty, null, tint = CautionAmber, modifier = Modifier.size(18.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "This chance passed — wait for the next one.",
                                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = TextPrimary
                            )
                            Text(
                                "The price ran past the buy zone (${signal.entryZone}). Buying late is how money is lost.",
                                fontSize = 12.sp, lineHeight = 16.sp, color = TextSecondary
                            )
                        }
                    }
                }
            }
        }
        return
    }

    var showMore by rememberSaveable(signal.stockSymbol) { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(containerColor = DarkCard),
        border   = BorderStroke(2.dp, actionColor.copy(alpha = 0.7f)),
        shape    = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // FORCED HONESTY — cached price must never look like an actionable live
            // signal. Red ONLY while the market is OPEN and the data is stale (the
            // dangerous case, panel #2); when closed, the neutral screen-level
            // "Prices from Friday's close" line covers every card — no red spam.
            if (isCached && marketOpen) {
                Surface(shape = RoundedCornerShape(8.dp), color = RedContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, null, tint = RedPrimary, modifier = Modifier.size(14.dp))
                        Text(
                            "Old price. Do NOT buy or sell on this.",
                            fontSize = 12.sp, fontWeight = FontWeight.Bold, color = RedPrimary
                        )
                    }
                }
            }
            // Action + stock header — the verdict WORD replaces the raw "84/100" (I2).
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (onSpeak != null) SpeakButton(onSpeak)
                    Surface(shape = RoundedCornerShape(8.dp), color = actionColor) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(actionIcon, null, tint = TextOnGold, modifier = Modifier.size(18.dp))
                            Text(
                                signal.action.name,
                                color      = TextOnGold,
                                fontWeight = FontWeight.ExtraBold,
                                style      = MaterialTheme.typography.titleMedium
                            )
                        }
                    }
                    Column {
                        Text(signal.stockSymbol, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                        Text(signal.stockName,   fontSize = 12.sp, color = TextMuted, maxLines = 1)
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(verdictWord(signal.confidence), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold, color = verdictColor(signal.confidence))
                    if (signal.isBeginnerSafe) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            Icon(Icons.Default.CheckCircle, null, tint = GoldAccent, modifier = Modifier.size(12.dp))
                            Text("Beginner safe", style = MaterialTheme.typography.labelSmall, color = GoldAccent, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // The verdict word is setup QUALITY (how many good signs lined up) — NOT
            // the chance of profit (I1/A5/B6). One plain line so a beginner can never
            // read it as odds of winning. The raw number sits behind "See more".
            Text(
                "This means the setup quality — not a promise of profit.",
                fontSize = 12.sp,
                color = TextMuted,
                lineHeight = 16.sp
            )

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("₹${fmtPrice(effPrice)}", fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.ExtraBold, color = TextPrimary)
                // "● Live" ONLY while the market is really open (panel #1) — a last
                // socket tick after the close must never claim the price is live.
                if (marketOpen && livePrice != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(modifier = Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(GreenPrimary))
                        Text("Live", style = MaterialTheme.typography.labelSmall, color = GreenPrimary, fontWeight = FontWeight.Bold)
                    }
                }
            }

            HorizontalDivider(color = DarkBorder)

            // Trade levels — the simple, money-critical rows only. Extra targets and
            // the trailing stop move behind "See more". FORCED HONESTY — a SELL signal
            // must never carry buy-side labels, or the user will BUY the falling stock.
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (isBuy) {
                    MasterRow("Buy at",   signal.entryZone.ifEmpty { "₹${fmtRs(signal.entryPrice)}" }, TextPrimary)
                    // Pre-computed size from the user's saved budget (I3) — no calculator.
                    if (signal.recommendedQty >= 1) {
                        MasterRow("Shares to buy", "${signal.recommendedQty}", TextPrimary)
                    }
                    MasterRow("Stop loss, sell here", "₹${fmtRs(signal.stopLoss)}", RedPrimary)
                    MasterRow("Target, sell here", "₹${fmtRs(signal.targetPrice)}", GreenPrimary)
                } else {
                    MasterRow("Sell at",   signal.entryZone.ifEmpty { "₹${fmtRs(signal.entryPrice)}" }, TextPrimary)
                    MasterRow("Stop loss, buy back here", "₹${fmtRs(signal.stopLoss)}", RedPrimary)
                    MasterRow("Target, buy back here", "₹${fmtRs(signal.targetPrice)}", GreenPrimary)
                }
            }

            // ── The money loop (B0.2): place THIS order in Groww ─────────────
            // Only for BUY, and never on a cached/old price. Short-sells are not
            // beginner-safe (B6), so a zero-knowledge user is never handed one here.
            if (!isCached && isBuy) {
                HorizontalDivider(color = DarkBorder)
                // First Groww mention on this tab (panel #4): one small line that
                // tells a zero-knowledge user what Groww even IS — once, right here.
                if (showGrowwIntro) {
                    Text(
                        "Groww is a SEBI-registered investing app — the shop where you buy and sell. This app only guides you.",
                        fontSize = 12.sp, lineHeight = 16.sp, color = TextMuted
                    )
                }
                if (signal.recommendedQty < 1) {
                    // B7a — never pretend an unaffordable share is buyable
                    Surface(color = RedContainer, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "This share is too costly for your money — see the other chances below.",
                            fontSize = 12.sp, fontWeight = FontWeight.Bold, color = RedPrimary,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                } else {
                    // After-charges truth (B0.3a): the ONLY profit figure safe to show a
                    // beginner. Intraday round-trip charges (brokerage+STT+GST+slippage)
                    // are subtracted for the recommended quantity.
                    val netProfit = signal.netProfitFor(signal.recommendedQty, intraday = true)
                    // Stale-entry (I7/C12a): if the live price ran past the fixed zone, the
                    // chance is gone. A gross target the user can't keep after charges is
                    // also not buyable. Either one REALLY disables the Groww button.
                    val stale = signal.chanceGone || signal.buyChancePassed(effPrice)
                    val netBlock: String? = when {
                        blockReason != null -> blockReason
                        stale               -> "This chance passed — wait for the next one"
                        netProfit <= 0.0    -> "After charges the profit is too small — skip this one today"
                        else                -> null
                    }
                    // U7.1/B0.3a: protection before profit. The green after-charges
                    // figure appears ONLY when the buy is actually takeable — a
                    // blocked or small-money user (netBlock set) sees the block
                    // reason, never an enticing profit teaser above it.
                    if (netBlock == null) {
                        Surface(color = GreenContainer, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Text(
                                // Real grammar: "for 1 share", "for 3 shares" — never "1 shares".
                                "Profit after charges (estimate): about +₹${fmtRs(netProfit)} for ${signal.recommendedQty} " +
                                    if (signal.recommendedQty == 1) "share" else "shares",
                                fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                color = GreenLight, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                    val exits = computeTargetSplit(signal.recommendedQty, signal.targetPrice, signal.target2, signal.target3)
                    // Buy-detail (the sell plan) also stays behind the block, so a
                    // small-money user is steered to safety first, not into detail.
                    if (netBlock == null && exits.size > 1) {
                        // Plain words, no analyst shorthand (I2): never "T1: 2  T2: 1".
                        Text(
                            "Sell plan: " + exits.joinToString(", ") { "${ordinalWord(it.index)} target — sell ${it.shares}" },
                            fontSize = 12.sp, lineHeight = 16.sp, color = TextSecondary
                        )
                    }
                    if (practiceMode && netBlock == null) {
                        // Practice: record a fake-money trade directly, no real Groww order.
                        // Practice-mode is CAUTION amber (B0.3b), never the brand-green CTA.
                        Button(
                            onClick = { onPracticeBuy(signal, effPrice, signal.recommendedQty) },
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = CautionAmber, contentColor = TextPrimary)
                        ) {
                            Icon(Icons.Default.Science, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Practice buy — fake money", fontWeight = FontWeight.Bold)
                        }
                    } else {
                        // I7: when the guard blocks (or the chance passed / net profit is
                        // gone), this button is REALLY disabled with the one-line reason —
                        // never a live button under a warning banner.
                        GrowwActionRow(
                            symbol = signal.stockSymbol,
                            orderType = OrderType.INTRADAY,
                            suggestedPrice = effPrice,
                            enabled = netBlock == null,
                            disabledReason = netBlock ?: "",
                            externalConfirm = true,
                            onLaunch = { onLaunchGroww(signal, effPrice, signal.recommendedQty) }
                        )
                    }
                }
            }

            // ── "See more" — every analytical number stays one tap away (B10) ─────
            TextButton(onClick = { showMore = !showMore }, contentPadding = PaddingValues(0.dp)) {
                Text(if (showMore) "See less" else "See more", style = MaterialTheme.typography.labelMedium, color = GoldAccent, fontWeight = FontWeight.Bold)
                Icon(if (showMore) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null, tint = GoldAccent, modifier = Modifier.size(18.dp))
            }
            if (showMore) {
                // The raw checklist score — kept honest: it is NOT a chance of profit.
                Text(
                    signal.confidenceMeaning,
                    fontSize = 12.sp,
                    color = TextMuted,
                    lineHeight = 16.sp
                )

                // H13/C24: the honest track record for THIS pick — real simulated
                // trades on real closes (>=5), or nothing. Null renders nothing; a
                // fabricated number is never shown (A5/B6). Same wording as Home.
                if (trustLine != null) {
                    Row(
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Default.History, null, tint = TextMuted, modifier = Modifier.size(14.dp))
                        Text(trustLine, fontSize = 12.sp, color = TextMuted, lineHeight = 16.sp)
                    }
                }

                // Extra targets + trailing stop (moved out of the simple decision)
                if (signal.target2 > 0)  MasterRow(if (isBuy) "Target 2" else "Target 2, buy back", "₹${fmtRs(signal.target2)}",  GreenPrimary)
                if (signal.target3 > 0)  MasterRow(if (isBuy) "Target 3" else "Target 3, buy back", "₹${fmtRs(signal.target3)}", GreenPrimary.copy(0.75f))
                // Hand-holding (panel #5): tell the user EXACTLY what to do in Groww,
                // with this signal's real numbers — never the bare analyst row.
                if (signal.trailingStop > 0) {
                    if (isBuy) {
                        Text(
                            "When the price crosses ₹${fmtRs(signal.targetPrice)} (Target 1): in Groww, change your stop-loss sell price from ₹${fmtRs(signal.stopLoss)} to ₹${fmtRs(signal.trailingStop)} — this locks in profit.",
                            fontSize = 12.sp, lineHeight = 16.sp, color = TextSecondary
                        )
                    } else {
                        MasterRow("After Target 1, move stop to", "₹${fmtRs(signal.trailingStop)}", CautionAmber)
                    }
                }

                // Trade stats
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                    StatChip("Shares", if (signal.recommendedQty >= 1) "${signal.recommendedQty}" else "—", TextPrimary)
                    StatChip("For every ₹1 risk", "gain ₹${String.format(java.util.Locale.US, "%.1f", signal.riskReward)}", if (signal.riskReward >= 2.0) GreenPrimary else CautionAmber)
                    StatChip("Risk level", when (signal.riskLevel.name) { "LOW" -> "Low — safe"; "HIGH" -> "High — easy to lose"; else -> "Medium" }, when (signal.riskLevel) { RiskLevel.LOW -> GreenPrimary; RiskLevel.MEDIUM -> CautionAmber; else -> RedPrimary })
                }

                HorizontalDivider(color = DarkBorder)

                // Why the app says this — the plain-English reasons (folded here, B10)
                Text("Why the app says ${signal.action.name}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = actionColor)
                signal.reasons.forEachIndexed { i, reason ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${i + 1}.", style = MaterialTheme.typography.bodySmall, color = GoldAccent, fontWeight = FontWeight.Bold)
                        Text(reason, style = MaterialTheme.typography.bodySmall, color = TextSecondary, modifier = Modifier.weight(1f))
                    }
                }

                // Which-way bar (panel #6) — a PURE color bar with the numbers in a
                // legend BELOW it, never inside a segment where a narrow slice clips
                // the digits. Honestly labelled: signs counted, not a prediction.
                Text("Which way do the signs point? (counting good/bad signs — not a prediction)", fontSize = 12.sp, lineHeight = 16.sp, color = TextMuted)
                Row(modifier = Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp))) {
                    Box(modifier = Modifier.weight(signal.upsideProbability.toFloat().coerceAtLeast(1f)).fillMaxHeight().background(GreenPrimary))
                    Box(modifier = Modifier.weight(signal.sidewaysProbability.toFloat().coerceAtLeast(1f)).fillMaxHeight().background(CautionAmber))
                    Box(modifier = Modifier.weight(signal.downsideProbability.toFloat().coerceAtLeast(1f)).fillMaxHeight().background(RedPrimary))
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Up ${signal.upsideProbability}%",   style = MaterialTheme.typography.labelSmall, color = GreenPrimary, fontWeight = FontWeight.Bold)
                    Text("·", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                    Text("Flat ${signal.sidewaysProbability}%", style = MaterialTheme.typography.labelSmall, color = CautionAmber, fontWeight = FontWeight.Bold)
                    Text("·", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                    Text("Down ${signal.downsideProbability}%", style = MaterialTheme.typography.labelSmall, color = RedPrimary, fontWeight = FontWeight.Bold)
                }
                // Context — plain words, not analyst shorthand (I2)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    ContextTag("Whole market", when (signal.marketTrend) {
                        "BULLISH" -> "rising"; "BEARISH" -> "falling"; else -> "flat"
                    }, signal.marketTrend == "BULLISH", signal.marketTrend == "BEARISH")
                    ContextTag("This stock's trend", when (signal.sectorStrength) {
                        "STRONG" -> "strong"; "MODERATE" -> "okay"; else -> "quiet"
                    }, signal.sectorStrength == "STRONG", false)
                    ContextTag("Big investors", when (signal.institutionalFlow) {
                        "BUYING" -> "buying"; "SELLING" -> "selling"; "ACCUMULATING" -> "gathering"; else -> "quiet"
                    }, signal.institutionalFlow == "BUYING" || signal.institutionalFlow == "ACCUMULATING", signal.institutionalFlow == "SELLING")
                }

                // Validity + market-state — moved out of the simple decision (B10). The
                // live indicator is a GREEN dot, never a red "LIVE" emoji.
                HorizontalDivider(color = DarkBorder)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Schedule, null, tint = TextMuted, modifier = Modifier.size(12.dp))
                        Text(signal.tradeValidityTime, style = MaterialTheme.typography.labelSmall, color = TextMuted)
                    }
                    // ONE market-state truth (panel #1): this chip follows the SCREEN's
                    // state, never the signal's own possibly-lagging snapshot. When the
                    // market is closed the chip disappears entirely — the market banner
                    // is the single closed indicator on this screen.
                    if (isCached) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(Icons.Default.History, null, tint = CautionAmber, modifier = Modifier.size(12.dp))
                            // B2: plain words with the real age — never "Cached".
                            Text(staleLabel, style = MaterialTheme.typography.labelSmall, color = CautionAmber, fontWeight = FontWeight.Bold)
                        }
                    } else if (marketOpen) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box(modifier = Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(GreenPrimary))
                            Text("Live", style = MaterialTheme.typography.labelSmall, color = GreenPrimary, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MasterRow(label: String, value: String, color: Color) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.ExtraBold, color = TextMuted, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = color)
    }
}

@Composable
private fun StatChip(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextMuted)
        Text(value, style = MaterialTheme.typography.bodySmall,  fontWeight = FontWeight.ExtraBold, color = color)
    }
}

@Composable
private fun ContextTag(label: String, value: String, positive: Boolean, negative: Boolean) {
    val color = when { positive -> GreenPrimary; negative -> RedPrimary; else -> CautionAmber }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextMuted)
        Text(value, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.ExtraBold, color = color)
    }
}

@Composable
private fun CompactSignalCard(
    signal: TradingSignal,
    marketOpen: Boolean = true,
    screenStale: Boolean = false,
    onSpeak: (() -> Unit)? = null
) {
    val actionColor = if (signal.action == SignalAction.BUY) GreenPrimary else RedPrimary
    // ONE staleness condition for every card (panel #2) — same formula as
    // MasterSignalCard, so identical siblings can never disagree.
    val isCached    = signal.validUntil == "Cached" || screenStale
    // C12a also protects the smaller cards — a passed chance must say so
    val chancePassed = !isCached && (signal.chanceGone || signal.buyChancePassed())
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(containerColor = DarkCard),
        border   = BorderStroke(1.dp, (if (chancePassed) CautionAmber else actionColor).copy(alpha = 0.4f)),
        shape    = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // FORCED HONESTY — same stale-price banner as MasterSignalCard, red ONLY
            // while the market is OPEN (panel #2); when closed, the neutral screen-
            // level "Prices from … close" line covers every card without red spam.
            if (isCached && marketOpen) {
                Surface(shape = RoundedCornerShape(6.dp), color = RedContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, null, tint = RedPrimary, modifier = Modifier.size(13.dp))
                        Text(
                            "Old price. Do NOT buy or sell on this.",
                            fontSize = 12.sp, fontWeight = FontWeight.Bold, color = RedPrimary
                        )
                    }
                }
            }
            // Layout contract (maintainer fix): the symbol column is the ONLY flexible
            // piece (weight 1f + ellipsis); the right column keeps its natural width.
            // With SpaceBetween + two intrinsic sides, long symbols crashed straight
            // into the right-side text with zero gap — never again.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onSpeak != null) SpeakButton(onSpeak)
                Surface(shape = RoundedCornerShape(6.dp), color = if (chancePassed) CautionAmber else actionColor) {
                    Text(
                        if (chancePassed) "PASSED" else signal.action.name,
                        color = TextOnGold, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    // Company NAME first (panel #7) — a human word the user recognises;
                    // the ticker code sits small and muted below with the price. A long
                    // name may ellipsize at the tail, but a ticker letter-chopped into
                    // "RELIANCE IN…" nonsense is gone for good.
                    Text(
                        shortCompanyName(signal.stockName, signal.stockSymbol),
                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = TextPrimary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${signal.stockSymbol} · ₹${fmtRs(signal.currentPrice)}",
                        style = MaterialTheme.typography.labelSmall, color = TextMuted,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                if (chancePassed) {
                    Text(
                        "Chance passed —\ndo not buy now",
                        style = MaterialTheme.typography.labelSmall, color = CautionAmber, fontWeight = FontWeight.Bold, textAlign = TextAlign.End
                    )
                } else {
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        // A plain verdict WORD, not a "%" — the ONE "setup quality" caption
                        // for all these cards sits under the section header above.
                        Text(verdictWord(signal.confidence), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.ExtraBold, color = actionColor)
                        Text("Target: ₹${fmtRs(signal.targetPrice)}", style = MaterialTheme.typography.labelSmall, color = GreenPrimary)
                        Text("Stop loss: ₹${fmtRs(signal.stopLoss)}", style = MaterialTheme.typography.labelSmall, color = RedPrimary)
                    }
                }
            }
        }
    }
}

@Composable
private fun NoTradeCard(marketClosed: Boolean, niftyChange: Double) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(containerColor = DarkCard),
        border   = BorderStroke(2.dp, CautionAmber.copy(alpha = 0.5f)),
        shape    = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.Security, null, tint = CautionAmber, modifier = Modifier.size(40.dp))
            Text("NO TRADE", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, color = CautionAmber, textAlign = TextAlign.Center)
            HorizontalDivider(color = DarkBorder)
            if (marketClosed) {
                Text("Market is closed now.", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = TextSecondary)
                Text("Opens ${RiskGuard.nextMarketOpenLabel()}", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, color = TextMuted)
            } else {
                Text(
                    when {
                        niftyChange <= -1.0 -> "Market is falling fast. Do not trade now."
                        niftyChange >= 2.5  -> "Market is up too fast. It may fall soon."
                        else                -> "No stock passed the safety checks right now."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color     = TextSecondary
                )
            }
            Card(colors = CardDefaults.cardColors(containerColor = CautionContainer), shape = RoundedCornerShape(10.dp)) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("No trade is also a good choice:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = TextPrimary)
                    listOf(
                        "Keeping your money safe matters most",
                        "Bad trades lose money",
                        "Good traders take only strong chances",
                        "Waiting is not losing. It is discipline."
                    ).forEach { reason ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("•", color = CautionAmber, style = MaterialTheme.typography.bodySmall)
                            Text(reason, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                        }
                    }
                }
            }
        }
    }
}
