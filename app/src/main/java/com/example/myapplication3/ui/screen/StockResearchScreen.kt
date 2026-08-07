package com.example.myapplication3.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.example.myapplication3.groww.OrderType
import com.example.myapplication3.intraday.AiPrediction
import com.example.myapplication3.intraday.ResearchNewsItem
import com.example.myapplication3.intraday.StockResearchData
import com.example.myapplication3.ui.component.GrowwActionRow
import com.example.myapplication3.ui.component.formatIndianRupees
import com.example.myapplication3.ui.theme.BlueAccent
import com.example.myapplication3.ui.theme.GoldAccent
import com.example.myapplication3.ui.theme.GreenPrimary
import com.example.myapplication3.ui.theme.RedPrimary
import com.example.myapplication3.ui.viewmodel.BuyPlan
import com.example.myapplication3.ui.viewmodel.StockResearchViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** Display symbol for the quote currency — ₹ only for rupee stocks (never for US stocks). */
private fun currencySymbol(code: String): String = when (code) {
    "INR" -> "₹"
    "USD" -> "$"
    else  -> "$code "
}

/**
 * I9 / U9.2 — every ₹ amount uses Indian lakh/crore grouping (₹1,00,000, never
 * ₹100000) via the shared formatIndianRupees(), exactly like HomeScreen/TradingScreen.
 * [cur] is the symbol from currencySymbol() — "₹" only for INR; $ (US) prices keep
 * plain Western grouping so a foreign price is never mangled into lakh/crore.
 */
private fun fmtMoney(cur: String, v: Double, decimals: Int = 0): String =
    if (cur == "₹") formatIndianRupees(v, decimals)
    else String.format(Locale.US, "%,.${decimals}f", v)

/**
 * B1: the one-sentence "what should I do?" for the top of the screen.
 * MUST stay in step with the spoken sentences in StockResearchViewModel.speakSummary (I5).
 */
private fun verdictSentence(recommendation: String): Pair<String, Color>? = when (recommendation) {
    "BUY"  -> "You can buy this stock now" to GreenPrimary
    "SELL" -> "Do not buy now — it may go down" to RedPrimary
    "HOLD" -> "If you own it, keep it — do not buy more" to GoldAccent
    "WAIT" -> "Wait for now — it is not a good time to buy" to GoldAccent
    else   -> null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StockResearchScreen(navController: NavController) {
    val vm: StockResearchViewModel = hiltViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    // I3/B3 + I6/I7 — pre-computed share count and watchability come from the VM;
    // the screen only renders them, it never does the money math itself
    val buyPlan by vm.buyPlan.collectAsStateWithLifecycle()
    val watchable by vm.watchable.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state.symbol.ifEmpty { vm.symbol }, fontWeight = FontWeight.ExtraBold,
                            style = MaterialTheme.typography.titleMedium)
                        if (state.name.isNotEmpty()) {
                            Text(state.name, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // 🔊 speak the whole summary in simple English — same listen-first pattern as Home
                    IconButton(onClick = { vm.speakSummary() }) {
                        Text("🔊", fontSize = 18.sp)
                    }
                    IconButton(onClick = { vm.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { padding ->
        when {
            state.isLoading && state.currentPrice == 0.0 && state.error == null -> {
                Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        CircularProgressIndicator(color = GreenPrimary, strokeWidth = 3.dp)
                        Text("Getting ${vm.symbol} info…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Price, news and AI opinion",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            state.error != null && state.currentPrice == 0.0 -> {
                Box(modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Default.ErrorOutline, null, modifier = Modifier.size(56.dp),
                            tint = RedPrimary)
                        Text("Could not load", style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold)
                        Text(state.error ?: "", style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = { vm.refresh() },
                            colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary)) {
                            Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Try again", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(vertical = 14.dp)
                ) {
                    // ── B1: the one-sentence answer FIRST — matches what 🔊 speaks (I5) ──
                    verdictSentence(state.aiPrediction.recommendation)?.let { (sentence, color) ->
                        if (!state.aiPrediction.isLoading) {
                            item {
                                Column(modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(sentence,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = color,
                                        modifier = Modifier.fillMaxWidth())
                                    // I3/B3: a visible BUY must ALWAYS carry its
                                    // "how many shares?" answer right next to it
                                    if (state.aiPrediction.recommendation == "BUY") {
                                        BuyQtyLine(buyPlan)
                                    }
                                }
                            }
                        }
                    }

                    // ── B2: honest freshness — "Cached" when a refresh failed ──
                    item {
                        val timeStr = if (state.lastUpdatedAt > 0L)
                            SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(state.lastUpdatedAt))
                        else ""
                        if (state.error != null) {
                            Card(modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = GoldAccent.copy(alpha = 0.12f)),
                                border = BorderStroke(1.dp, GoldAccent.copy(alpha = 0.5f)),
                                shape  = RoundedCornerShape(12.dp)) {
                                Text(
                                    "Could not refresh — these are old prices" +
                                        (if (timeStr.isNotEmpty()) " (from $timeStr)" else "") +
                                        ". Tap ⟳ to try again.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(10.dp))
                            }
                        } else if (timeStr.isNotEmpty()) {
                            Text("Updated $timeStr",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    // ── Price header ──
                    item { PriceHeaderCard(state) }

                    // ── Price history sparkline ──
                    if (state.historicalCloses.size >= 10) {
                        item { PriceHistoryCard(state.historicalCloses, state.changePercent) }
                    }

                    // ── AI Prediction (the decision) ──
                    item {
                        AiPredictionSection(
                            prediction   = state.aiPrediction,
                            currentPrice = state.currentPrice,
                            symbol       = state.symbol.ifEmpty { vm.symbol },
                            currency     = state.currency,
                            buyPlan      = buyPlan,
                            watchable    = watchable,
                            onBuyConfirmed = { fill, qty -> vm.trackBuy(fill, qty) }
                        )
                    }

                    // ── Key numbers — hidden behind one tap (B10 / I2) ──
                    item {
                        var showStats by rememberSaveable { mutableStateOf(false) }
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            TextButton(onClick = { showStats = !showStats },
                                contentPadding = PaddingValues(0.dp)) {
                                Text(if (showStats) "Hide the numbers ▲" else "See more numbers ▼",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold, color = BlueAccent)
                            }
                            if (showStats) KeyStatsCard(state)
                        }
                    }

                    // ── News ──
                    if (state.news.isNotEmpty()) {
                        item {
                            Text("Recent News (${state.news.size})",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp))
                        }
                        // No key — Yahoo news often has publishedAt=0 and near-duplicate headlines
                        items(state.news) { newsItem ->
                            NewsItemCard(newsItem)
                        }
                    } else if (!state.isLoading) {
                        item {
                            Card(modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                                Row(modifier = Modifier.padding(16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Newspaper, null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp))
                                    Text("No recent news found for ${state.symbol}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }

                    // ── Disclaimer ──
                    item {
                        Text(
                            "⚠ The app can be wrong. Put in only money you can afford to lose.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun PriceHeaderCard(state: StockResearchData) {
    val changeColor = if (state.changePercent >= 0) GreenPrimary else RedPrimary
    val changeArrow = if (state.changePercent >= 0) "▲" else "▼"
    val cur = currencySymbol(state.currency)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(
            containerColor = changeColor.copy(alpha = 0.08f)),
        border   = BorderStroke(1.dp, changeColor.copy(alpha = 0.4f)),
        shape    = RoundedCornerShape(14.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("$cur${fmtMoney(cur, state.currentPrice, 2)}",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.ExtraBold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("$changeArrow ${String.format(java.util.Locale.US, "%.2f", abs(state.changePercent))}%",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = changeColor)
                    Text("($cur${fmtMoney(cur, abs(state.changeAbsolute), 2)})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // Plain-words verdict — a beginner must not decode arrows/colors
                Text(
                    if (state.changePercent >= 0) "Up today 📈" else "Down today 📉",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = changeColor)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // Clock+holiday truth — the Yahoo marketState field is dead (v7 401;
                // v8 meta has no such field), it would read "Market closed" forever.
                val marketOpenNow = com.example.myapplication3.intraday.RiskGuard.isTradingHoursNow()
                Text(
                    if (marketOpenNow) "🟢 Market open" else "💤 Market closed",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (marketOpenNow) GreenPrimary else GoldAccent)
                // Show the day range ONLY when Yahoo sent a real one (dayHigh>dayLow)
                if (state.dayHigh > state.dayLow) {
                    // Neutral — day high/low are just the range, not good/bad. Green/red
                    // are reserved for up/down (a low price is CHEAPER, not "bad").
                    Text("Today high: $cur${fmtMoney(cur, state.dayHigh, 2)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Today low: $cur${fmtMoney(cur, state.dayLow, 2)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun PriceHistoryCard(closes: List<Double>, todayChange: Double) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape    = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val change1Y = if (closes.size >= 2) {
                val start = closes.first(); val end = closes.last()
                if (start > 0) (end - start) / start * 100.0 else 0.0
            } else 0.0
            Row(modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically) {
                Text("Price over 1 year", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                val col = if (change1Y >= 0) GreenPrimary else RedPrimary
                Text("${if (change1Y >= 0) "+" else ""}${String.format(java.util.Locale.US, "%.1f", change1Y)}% in 1 year",
                    style = MaterialTheme.typography.labelSmall, color = col, fontWeight = FontWeight.Bold)
            }
            PriceSparkline(
                prices   = closes,
                isUp     = change1Y >= 0,   // color the 1-YEAR chart by the 1-year move, not today's tick
                modifier = Modifier.fillMaxWidth().height(80.dp)
            )
            // Period labels
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("1 year ago", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("6 months ago", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Today", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun KeyStatsCard(state: StockResearchData) {
    val cur = currencySymbol(state.currency)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape    = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Behind "See more numbers" (B10) — labels stay in plain words anyway (I2)
            Text("The numbers", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

            // 1-year range bar
            if (state.high52w > state.low52w) {
                val rangePos = ((state.currentPrice - state.low52w) / (state.high52w - state.low52w)).toFloat().coerceIn(0f, 1f)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("1-year low $cur${fmtMoney(cur, state.low52w)}",
                            style = MaterialTheme.typography.labelSmall, color = GreenPrimary)
                        Text("${String.format("%.0f", rangePos * 100)}% of the way up",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("1-year high $cur${fmtMoney(cur, state.high52w)}",
                            style = MaterialTheme.typography.labelSmall, color = RedPrimary)
                    }
                    Box(modifier = Modifier.fillMaxWidth().height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))) {
                        Box(modifier = Modifier.fillMaxWidth(rangePos).fillMaxHeight()
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (rangePos > 0.5f) GreenPrimary else GoldAccent))
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            }

            // Stats grid — "MA50/MA200" jargon translated to plain averages (I2)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                ResearchStatItem("50-day average", if (state.ma50 > 0) "$cur${fmtMoney(cur, state.ma50)}" else "—",
                    if (state.ma50 > 0 && state.currentPrice > state.ma50) GreenPrimary else RedPrimary)
                ResearchStatItem("200-day average", if (state.ma200 > 0) "$cur${fmtMoney(cur, state.ma200)}" else "—",
                    if (state.ma200 > 0 && state.currentPrice > state.ma200) GreenPrimary else RedPrimary)
                ResearchStatItem("Shares traded", formatVolume(state.volume),
                    if (state.avgVolume > 0 && state.volume > state.avgVolume) GreenPrimary
                    else MaterialTheme.colorScheme.onSurface)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                val volRatioStr = if (state.avgVolume > 0)
                    "${String.format("%.1f", state.volume.toDouble() / state.avgVolume)}x normal"
                else "—"
                ResearchStatItem("Vs a normal day", volRatioStr,
                    if (state.avgVolume > 0 && state.volume > state.avgVolume * 1.5) GreenPrimary
                    else MaterialTheme.colorScheme.onSurface)
                // Real range only — dayHigh<=dayLow means Yahoo sent no range today
                if (state.dayHigh > state.dayLow) {
                    ResearchStatItem("Day high", "$cur${fmtMoney(cur, state.dayHigh, 2)}", GreenPrimary)
                    ResearchStatItem("Day low", "$cur${fmtMoney(cur, state.dayLow, 2)}",  RedPrimary)
                }
            }
        }
    }
}

@Composable
private fun ResearchStatItem(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold, color = color, fontSize = 12.sp)
    }
}

@Composable
private fun AiPredictionSection(
    prediction: AiPrediction,
    currentPrice: Double,
    symbol: String,
    currency: String,
    buyPlan: BuyPlan,
    watchable: Boolean,
    onBuyConfirmed: (fillPrice: Double, quantity: Int) -> Unit
) {
    val cur = currencySymbol(currency)
    // After the Groww fill-price confirm, one more simple question: how many shares?
    var pendingFill by remember { mutableStateOf(0.0) }
    var askQty by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape    = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Psychology, null, tint = BlueAccent, modifier = Modifier.size(20.dp))
                // FORCED HONESTY — the local score counter must not brand itself as AI
                Text(
                    if (prediction.modelUsed.startsWith("Local")) "📊 App guess (simple math)"
                    else "🤖 AI opinion",
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.weight(1f))
                if (prediction.modelUsed.isNotEmpty()) {
                    // Honest label — the local formula must not masquerade as cloud AI
                    Text(if (prediction.modelUsed == "Local AI" || prediction.modelUsed == "Local") "App check" else prediction.modelUsed,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            when {
                prediction.isLoading -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = BlueAccent)
                        Text("AI is thinking…", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                prediction.error != null -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Info, null, tint = GoldAccent, modifier = Modifier.size(16.dp))
                        Text(prediction.error, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                prediction.recommendation.isNotEmpty() -> {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)

                    // Recommendation badge
                    val (recColor, recBg) = when (prediction.recommendation) {
                        "BUY"  -> Pair(GreenPrimary, GreenPrimary.copy(alpha = 0.15f))
                        "SELL" -> Pair(RedPrimary,   RedPrimary.copy(alpha = 0.15f))
                        "HOLD" -> Pair(GoldAccent,   GoldAccent.copy(alpha = 0.15f))
                        else   -> Pair(MaterialTheme.colorScheme.onSurface, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                    }

                    Row(modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment     = Alignment.CenterVertically) {
                        Box(modifier = Modifier.background(recBg, RoundedCornerShape(10.dp))
                            .padding(horizontal = 20.dp, vertical = 10.dp)) {
                            // The single most money-critical word on the screen
                            val recLabel = when (prediction.recommendation) {
                                "BUY"  -> "BUY"
                                "SELL" -> "SELL"
                                "HOLD" -> "HOLD"
                                "WAIT" -> "WAIT"
                                else   -> prediction.recommendation
                            }
                            Text(recLabel, style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.ExtraBold, color = recColor)
                        }
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            // B3/U7.4 — the setup strength is a WORD by default
                            // (90+ Strong, 84–89 Okay, else Weak); the raw number
                            // lives one tap away behind "See more" (B10)
                            val setupWord = when {
                                prediction.confidence >= 90 -> "Strong"
                                prediction.confidence >= 84 -> "Okay"
                                else                        -> "Weak"
                            }
                            Text("$setupWord setup",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold, color = recColor)
                            LinearProgressIndicator(
                                progress = { prediction.confidence / 100f },
                                modifier = Modifier.width(100.dp).height(6.dp).clip(RoundedCornerShape(3.dp)),
                                color = recColor, trackColor = MaterialTheme.colorScheme.outline
                            )
                        }
                    }

                    // B10 — the numeric score only behind one tap, never a bare "% sure"
                    var showScore by rememberSaveable { mutableStateOf(false) }
                    TextButton(onClick = { showScore = !showScore },
                        contentPadding = PaddingValues(0.dp)) {
                        Text(if (showScore) "Hide the score ▲" else "See more ▼",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold, color = BlueAccent)
                    }
                    if (showScore) {
                        Text("The app's own score for this call: ${prediction.confidence} out of 100.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    // I3/B3: the how-many answer sits right under the BUY badge —
                    // the user must never do the share arithmetic themselves
                    if (prediction.recommendation == "BUY") {
                        // B3 — a visible BUY must carry its risk word (H6 bands
                        // from confidence; "Beginner Safe" only when truly LOW)
                        val (riskLine, riskColor) = when {
                            prediction.confidence >= 90 -> "Risk: LOW — Beginner Safe" to GreenPrimary
                            prediction.confidence >= 84 -> "Risk: MEDIUM — use only money you can spare" to GoldAccent
                            else                        -> "Risk: HIGH — easy to lose money here" to RedPrimary
                        }
                        Text(riskLine,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold, color = riskColor)
                        BuyQtyLine(buyPlan)
                        if (buyPlan.isBuy && buyPlan.currencyOk && buyPlan.recommendedQty >= 1 && buyPlan.capital > 0) {
                            Text(
                                "That costs about ₹${formatIndianRupees(buyPlan.totalCost)} " +
                                    "of your ₹${formatIndianRupees(buyPlan.capital)}.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    // Price targets
                    if (prediction.targetPrice > 0 || prediction.stopLoss > 0) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            if (prediction.targetPrice > 0) {
                                val upPct = if (currentPrice > 0) (prediction.targetPrice - currentPrice) / currentPrice * 100 else 0.0
                                PriceTargetChip("Sell here", prediction.targetPrice, upPct, GreenPrimary, cur)
                            }
                            if (currentPrice > 0) {
                                PriceTargetChip("Price now", currentPrice, 0.0, MaterialTheme.colorScheme.onSurface, cur)
                            }
                            if (prediction.stopLoss > 0) {
                                val dnPct = if (currentPrice > 0) (prediction.stopLoss - currentPrice) / currentPrice * 100 else 0.0
                                PriceTargetChip("Sell if it drops", prediction.stopLoss, dnPct, RedPrimary, cur)
                            }
                        }
                        // B3 — the stop-loss must carry its plain meaning, not just a number
                        if (prediction.recommendation == "BUY" && prediction.stopLoss > 0) {
                            Text(
                                "If the price falls to $cur${fmtMoney(cur, prediction.stopLoss, 2)}, sell immediately.",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = RedPrimary)
                        }
                    }

                    // B0.2: a BUY must end in the Groww action — DELIVERY label, one
                    // button, order-confirm on return. Indian (rupee) stocks only; a
                    // broken deep link must never be shown for foreign stocks.
                    if (prediction.recommendation == "BUY") {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                        when {
                            currency == "INR" -> GrowwActionRow(
                                symbol         = symbol,
                                orderType      = OrderType.DELIVERY,
                                suggestedPrice = currentPrice,
                                onConfirmed    = { fill -> pendingFill = fill; askQty = true }
                            )
                            currency == "USD" -> Text(
                                "US stock: buy this in Groww's US stocks section.",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            else -> Text(
                                "This stock trades abroad — you cannot buy it in Groww.",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        // I6/I7 honest note — the background price watcher can only
                        // resolve Indian (.NS) stocks, so no stop-loss/target alert
                        // will ever come for this one. Wording matches the spoken
                        // line in StockResearchViewModel.trackBuy exactly.
                        if (!watchable) {
                            Text(
                                "Note: I can only watch Indian stocks - I cannot alert you for this one.",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = GoldAccent
                            )
                        }
                    }

                    // Reasoning — B3's "simple reasons in plain English", with a
                    // heading so a BUY never renders without visible reasons
                    if (prediction.reasoning.isNotEmpty()) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                        Text("WHY THE APP SAYS THIS", style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 1.sp)
                        Text(prediction.reasoning, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface, lineHeight = 18.sp)
                    }

                    // Bull / Bear case
                    if (prediction.bullishCase.isNotEmpty() || prediction.bearishCase.isNotEmpty()) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (prediction.bullishCase.isNotEmpty()) {
                                CaseCard("🟢 Why it could rise", prediction.bullishCase, GreenPrimary, Modifier.weight(1f))
                            }
                            if (prediction.bearishCase.isNotEmpty()) {
                                CaseCard("🔴 Why it could fall", prediction.bearishCase, RedPrimary, Modifier.weight(1f))
                            }
                        }
                    }

                    // Key risks
                    if (prediction.keyRisks.isNotEmpty()) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                        Text("WHAT COULD GO WRONG", style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 1.sp)
                        prediction.keyRisks.forEach { risk ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("⚠", style = MaterialTheme.typography.labelSmall)
                                Text(risk, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }

    // B0.2b step 3: exact share count — tracking must record what was REALLY bought
    if (askQty) {
        QtyInputDialog(
            onConfirm = { qty ->
                askQty = false
                onBuyConfirmed(pendingFill, qty)
            },
            onDismiss = { askQty = false }
        )
    }
}

/**
 * I3/B3: the "how many shares?" answer that must accompany EVERY visible BUY.
 * The number is pre-computed in StockResearchViewModel (BuyPlan) from the user's
 * own money settings — this composable only renders it, it never does the math.
 */
@Composable
private fun BuyQtyLine(plan: BuyPlan) {
    when {
        // The plan flow can lag the verdict by a frame — never show a bare BUY
        !plan.isBuy -> Text(
            "Working out how many shares fit your money…",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        // ₹ capital cannot size a $ stock — say so instead of lying with a number
        !plan.currencyOk -> Text(
            "I can count shares only for Indian (rupee) stocks.",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        plan.recommendedQty >= 1 -> Text(
            "Buy ${plan.recommendedQty} ${if (plan.recommendedQty == 1) "share" else "shares"} (for your money)",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.ExtraBold,
            color = GreenPrimary)
        // qty == 0: even one share breaks the user's money rules — be honest
        else -> Text(
            "This share is too costly for your money - see other picks.",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = GoldAccent)
    }
}

/** Asks how many shares were bought in Groww — pre-filled 1, digits only, one tap. */
@Composable
private fun QtyInputDialog(onConfirm: (Int) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("1") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("How many shares?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Enter the number of shares you bought in Groww.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = text,
                    onValueChange = { new -> text = new.filter { it.isDigit() } },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { text.toIntOrNull()?.let { if (it > 0) onConfirm(it) } },
                enabled = (text.toIntOrNull() ?: 0) > 0
            ) { Text("Save", fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun PriceTargetChip(label: String, price: Double, pctChange: Double, color: Color, cur: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("$cur${fmtMoney(cur, price, 2)}", style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold, color = color)
        if (pctChange != 0.0) {
            Text("${if (pctChange >= 0) "+" else ""}${String.format("%.1f", pctChange)}%",
                style = MaterialTheme.typography.labelSmall, color = color)
        }
    }
}

@Composable
private fun CaseCard(title: String, body: String, color: Color, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors   = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.08f)),
        border   = BorderStroke(1.dp, color.copy(alpha = 0.3f)),
        shape    = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold, color = color)
            Text(body, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 16.sp)
        }
    }
}

@Composable
private fun NewsItemCard(item: ResearchNewsItem) {
    val uriHandler = LocalUriHandler.current
    val sentimentColor = when (item.sentiment) {
        "POSITIVE" -> GreenPrimary
        "NEGATIVE" -> RedPrimary
        else       -> GoldAccent
    }
    val sentimentIcon = when (item.sentiment) {
        "POSITIVE" -> Icons.AutoMirrored.Filled.TrendingUp
        "NEGATIVE" -> Icons.AutoMirrored.Filled.TrendingDown
        else       -> Icons.Default.Remove
    }
    val timeStr = if (item.publishedAt > 0L) {
        SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date(item.publishedAt))
    } else ""

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape    = RoundedCornerShape(12.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(modifier = Modifier.background(sentimentColor.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                .padding(6.dp)) {
                Icon(sentimentIcon, null, tint = sentimentColor, modifier = Modifier.size(18.dp))
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(item.headline, style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Row(modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment     = Alignment.CenterVertically) {
                    Text(item.source, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (timeStr.isNotEmpty()) {
                        Text(timeStr, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (item.url.isNotEmpty()) {
                    TextButton(
                        onClick = { runCatching { uriHandler.openUri(item.url) } },
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Text("Read full story →", style = MaterialTheme.typography.labelSmall,
                            color = BlueAccent)
                    }
                }
            }
        }
    }
}

private fun formatVolume(volume: Long): String = when {
    volume >= 10_000_000L -> "${String.format("%.1f", volume / 10_000_000.0)}Cr"
    volume >= 100_000L    -> "${String.format("%.1f", volume / 100_000.0)}L"
    volume >= 1_000L      -> "${String.format("%.1f", volume / 1_000.0)}K"
    else                  -> volume.toString()
}