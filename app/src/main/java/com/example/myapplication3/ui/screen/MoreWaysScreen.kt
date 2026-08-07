package com.example.myapplication3.ui.screen

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.example.myapplication3.groww.GrowwLauncher
import com.example.myapplication3.ui.component.formatIndianRupees
import com.example.myapplication3.ui.theme.*
import com.example.myapplication3.ui.viewmodel.EarnItem
import com.example.myapplication3.ui.viewmodel.IpoItem
import com.example.myapplication3.ui.viewmodel.MoreWaysViewModel
import java.util.Locale
import kotlin.math.abs

// Indian-English locale for every money/percent string (never the device default)
private val localeIndia = Locale("en", "IN")

// ══════════════════════════════════════════════════════════════════
//  Groww hand-off (rule B0.2) — the user buys THERE, never here (B0.4)
// ══════════════════════════════════════════════════════════════════

/**
 * Opens Groww on a search query. GrowwLauncher.openStock is stock-symbol-only
 * (uppercases + strips .NS), so this local variant handles ETF names, fund
 * searches ("NIFTY 50 index fund") and US company names. Same best-effort chain
 * as MutualFundsScreen's openGrowwForFund: Groww app → Groww launcher →
 * Play Store → browser.
 */
private fun openGrowwSearch(context: Context, query: String) {
    val clean = query.trim()
    val searchUrl = "https://groww.in/search?q=" + Uri.encode(clean)

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
            Toast.makeText(context, "Search '$clean' in Groww", Toast.LENGTH_LONG).show()
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreWaysScreen(
    navController: NavController,
    viewModel: MoreWaysViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("More ways to earn", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().background(DarkBackground).padding(padding),
            contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            item {
                Text(
                    "Simple, slower ways to grow your money. The gold, silver and monthly-saving ones you buy in Groww. US stocks are watch-only for now.",
                    fontSize = 12.sp, color = TextSecondary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            // D3 — the most important way for small money (B0.3a). Always shown; the
            // per-user monthly amount is pre-computed in the ViewModel from stored money.
            item { IndexSipCard(monthlyLine = state.sipMonthlyLine, onSpeak = viewModel::speak) }

            if (state.isLoading && !state.hasData) {
                item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = GoldAccent)
                    }
                }
            }
            // I6: what happened + what to do next, with a real Retry (B9 — never a dead end).
            if (state.error != null && !state.hasData && !state.isLoading) {
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(state.error ?: "Could not load — check internet", color = TextSecondary, fontSize = 13.sp)
                        Button(
                            onClick = { viewModel.load() },
                            colors = ButtonDefaults.buttonColors(containerColor = GoldAccent, contentColor = TextOnGold)
                        ) {
                            Text("Retry", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
            }

            state.gold?.let { item { EarnCard(it, onSpeak = viewModel::speak) } }
            state.silver?.let { item { EarnCard(it, onSpeak = viewModel::speak) } }

            if (state.usStocks.isNotEmpty()) {
                item {
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp)) {
                        Text(
                            "World's biggest companies (US)",
                            fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimary
                        )
                        // Amended D4 — honest: no US order path or ₹ sizing exists,
                        // so this whole section is watch-only, never a BUY.
                        Text(
                            "Watch only — buying US stocks needs a special account.",
                            fontSize = 11.sp, color = TextSecondary
                        )
                        // D4/B6 — when the live $→₹ rate could not be fetched, the cards
                        // show NO ₹ line and this says so honestly (never a stale rate).
                        state.usInrNote?.let {
                            Text(it, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = TextSecondary)
                        }
                    }
                }
                items(state.usStocks.size) { i -> EarnCard(state.usStocks[i], onSpeak = viewModel::speak) }
            }

            // D2 — IPO card driven by real listings; empty list → honest "no IPOs" state.
            item { IpoCard(ipos = state.ipos, onSpeak = viewModel::speak) }

            item {
                Text(
                    "⚠️ No guarantee — invest only what you can afford. This is not advice.",
                    fontSize = 11.sp, color = TextSecondary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════
//  D3 — ETF / Index SIP: THE answer for a zero-knowledge user
// ══════════════════════════════════════════════════════════════════

@Composable
private fun IndexSipCard(monthlyLine: String?, onSpeak: (String) -> Unit) {
    val context = LocalContext.current
    // D3/I2 — the card FACE says it in shopkeeper words; the term "index fund" and
    // the exact fund name live only behind "See more".
    val decision = "The simplest way: every month, put a fixed amount into one fund " +
            "that owns India's 50 biggest companies."
    // Per-user amount (D3) is pre-computed in the ViewModel; fall back to the honest
    // ₹500 SIP floor only when the money is unknown. No math on screen.
    val amountLine = monthlyLine ?: "Most funds start from ₹500 a month."
    var showTerm by rememberSaveable { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        border = BorderStroke(1.dp, GreenPrimary.copy(alpha = 0.35f))
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Every month — the safest start", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                SpeakButton { onSpeak("$decision ${amountLine.replace("₹", "rupees ")}") }
            }
            Text(decision, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimary, lineHeight = 18.sp)
            Text(amountLine, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary, lineHeight = 18.sp)
            TextButton(onClick = { showTerm = !showTerm }, contentPadding = PaddingValues(0.dp)) {
                Text(if (showTerm) "Hide ▲" else "See more ▼",
                    fontSize = 11.sp, fontWeight = FontWeight.Bold, color = GoldAccent)
            }
            if (showTerm) {
                Text("In Groww, look for the UTI Nifty 50 index fund. The words for this are \"index fund\".",
                    fontSize = 11.sp, color = TextSecondary)
            }
            GrowwButton(label = "Start in Groww") { openGrowwSearch(context, "UTI Nifty 50 index fund") }
        }
    }
}

// ══════════════════════════════════════════════════════════════════
//  D2 — IPOs: open/upcoming new-company shares. The app checks each one
//  against the user's money and says APPLY (only if one lot fits) or SKIP.
//  Empty list → HONEST "no IPOs right now" state — never fabricated rows (B6).
// ══════════════════════════════════════════════════════════════════

@Composable
private fun IpoCard(ipos: List<IpoItem>, onSpeak: (String) -> Unit) {
    val context = LocalContext.current
    val what = "IPOs: new companies selling their shares for the first time."
    // Honest empty-state line (B6) — spoken and shown identically (I5).
    val emptyLine = "No IPOs open right now — I will alert you when one opens."
    val spoken = if (ipos.isEmpty()) "$what $emptyLine"
        else "$what " + ipos.joinToString(" ") {
            // I5 parity — the voice says every line the row shows, "₹" as "rupees".
            "${it.name}. ${it.priceLine} ${it.dateLine} ${it.moneyLine} ${it.verdict}. ${it.reason}"
                .replace("₹", "rupees ")
        }
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        border = BorderStroke(1.dp, GoldAccent.copy(alpha = 0.35f))
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("IPOs — new companies", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                SpeakButton { onSpeak(spoken) }
            }
            Text(what, fontSize = 13.sp, color = TextPrimary, lineHeight = 18.sp)
            if (ipos.isEmpty()) {
                Text(emptyLine, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = GoldAccent, lineHeight = 18.sp)
            } else {
                ipos.forEach { ipo -> IpoRow(ipo, context) }
            }
        }
    }
}

/** One open/upcoming IPO — plain price, affordability, and a green APPLY / amber SKIP verdict. */
@Composable
private fun IpoRow(ipo: IpoItem, context: Context) {
    // APPLY only ever reaches here when one lot fits the user's money (checked in the VM).
    val verdictColor = if (ipo.apply) GreenPrimary else GoldAccent
    Column(
        Modifier.fillMaxWidth().padding(top = 6.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(ipo.name, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
        Text(ipo.priceLine, fontSize = 12.sp, color = TextSecondary)
        // D2 — the apply window in plain words ("Open now — you can apply until …").
        Text(ipo.dateLine, fontSize = 12.sp, color = TextSecondary, lineHeight = 16.sp)
        Text(ipo.moneyLine, fontSize = 12.sp, color = TextSecondary, lineHeight = 16.sp)
        Text("${ipo.verdict} — ${ipo.reason}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = verdictColor, lineHeight = 18.sp)
        // The apply happens in Groww (B0.2/B0.4) — only offered when the app says APPLY.
        if (ipo.apply) {
            GrowwButton(label = "Apply in Groww") { openGrowwSearch(context, ipo.name) }
        }
    }
}

// ══════════════════════════════════════════════════════════════════
//  D1 / D4 — live-price cards: one decision + one action each (B10).
//  ₹ cards can say "Buy in Groww"; $ cards are WATCH-ONLY (amended D4).
// ══════════════════════════════════════════════════════════════════

@Composable
private fun EarnCard(item: EarnItem, onSpeak: (String) -> Unit) {
    val context = LocalContext.current
    val color = if (item.isGood) GreenPrimary else GoldAccent
    val changeColor = if (item.changePercent >= 0) GreenPrimary else RedPrimary
    var showTerm by rememberSaveable(item.symbol) { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        border = BorderStroke(1.dp, color.copy(alpha = 0.35f))
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                Text(item.title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                Column(horizontalAlignment = Alignment.End) {
                    // U9.2 — ₹ amounts use Indian (lakh/crore) grouping via the shared
                    // formatter; $ amounts keep western grouping (correct for dollars).
                    val priceText = if (item.currency == "₹") {
                        formatIndianRupees(item.price, 2)
                    } else {
                        String.format(localeIndia, "%,.2f", item.price)
                    }
                    Text(
                        "${item.currency}$priceText",
                        fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = TextPrimary
                    )
                    // D4 — the rupee-only user reads the $ price in ₹ ("about ₹X"); the
                    // approximate value is pre-computed in the ViewModel.
                    item.approxInr?.let {
                        Text(it, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = TextSecondary)
                    }
                    Text(
                        String.format(localeIndia, "%+.2f%% today", item.changePercent),
                        fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = changeColor
                    )
                }
            }
            Text(item.verdict, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = color)
            // D1 — the pre-computed "put about ₹X in gold this month" line (from the
            // user's stored money, computed in the ViewModel — the screen never does math)
            item.monthlyLine?.let {
                Text(it, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary, lineHeight = 18.sp)
            }
            // D1 — the plain "digital gold ... no locker, no shop" reassurance on the face.
            item.plainNote?.let {
                Text(it, fontSize = 12.sp, color = TextPrimary, lineHeight = 16.sp)
            }
            item.growwNote?.let { Text(it, fontSize = 11.sp, color = TextSecondary) }
            // D1 — the technical terms ("Gold ETF / Sovereign Gold Bond") live behind See more.
            item.advancedNote?.let { advanced ->
                TextButton(onClick = { showTerm = !showTerm }, contentPadding = PaddingValues(0.dp)) {
                    Text(if (showTerm) "Hide ▲" else "See more ▼",
                        fontSize = 11.sp, fontWeight = FontWeight.Bold, color = GoldAccent)
                }
                if (showTerm) Text(advanced, fontSize = 11.sp, color = TextSecondary)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                // Amended D4 — a $ card is watch-only: the button may only LOOK, never
                // say "Buy". ₹ cards keep the one-tap buy hand-off (B0.2).
                GrowwButton(
                    label = if (item.currency == "$") "See in Groww" else "Buy in Groww",
                    modifier = Modifier.weight(1f)
                ) {
                    openGrowwSearch(context, item.growwQuery)
                }
                SpeakButton { onSpeak(spokenLine(item)) }
            }
        }
    }
}

/** Read-aloud parity (I5): says exactly what the card shows, in simple English. */
private fun spokenLine(item: EarnItem): String {
    val unit = if (item.currency == "₹") "rupees" else "dollars"
    val move = when {
        item.changePercent > 0.0 -> "Up ${String.format(localeIndia, "%.1f", item.changePercent)} percent today. "
        item.changePercent < 0.0 -> "Down ${String.format(localeIndia, "%.1f", abs(item.changePercent))} percent today. "
        else -> ""
    }
    // D4 parity: the ₹ conversion of a $ price is spoken too — "₹" becomes "rupees".
    val inr = item.approxInr?.let { " That is ${it.replace("₹", "rupees ")}." } ?: ""
    // D1 parity: the monthly ₹ line is spoken too — "₹" becomes the word "rupees"
    val monthly = item.monthlyLine?.let { " " + it.replace("₹", "rupees ") } ?: ""
    // D1 parity: the "digital gold ... no locker, no shop" reassurance is spoken too.
    val plain = item.plainNote?.let { " $it" } ?: ""
    return "${item.title}. Price ${item.price.toInt()} $unit.$inr $move${item.verdict}$monthly$plain"
}

// ── Small shared controls (48dp touch targets, matching app style) ──

@Composable
private fun GrowwButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(48.dp),
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(containerColor = GoldAccent, contentColor = TextOnGold)
    ) {
        Text(label, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

@Composable
private fun SpeakButton(onClick: () -> Unit) {
    // Default IconButton size = 48dp minimum touch target — never shrunk here.
    IconButton(onClick = onClick) {
        Text("🔊", fontSize = 18.sp)
    }
}
