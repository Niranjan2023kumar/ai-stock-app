package com.example.myapplication3.ui.screen

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.example.myapplication3.navigation.Screen
import com.example.myapplication3.ui.theme.GoldAccent
import com.example.myapplication3.ui.theme.GreenPrimary
import com.example.myapplication3.ui.theme.RedPrimary
import com.example.myapplication3.ui.viewmodel.LearningViewModel

// ══════════════════════════════════════════════════════════════════
//  Guide tab — one scroll, no inner tabs (B5).
//  Order: lessons that make money (C21) → trading styles → the clearly
//  labelled ADVANCED area (C24): "More ways to earn" entry + the
//  glossary (collapsed by default — market words stay off the
//  beginner's path, per B10/I2).
//  The old news feed is gone per E3c — news lives in Stock/Intraday.
// ══════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LearningScreen(navController: NavController) {
    val vm: LearningViewModel = hiltViewModel()
    val readLessons by vm.readLessons.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // C24 — the glossary is Advanced content and starts COLLAPSED: a beginner
    // never meets a wall of market words unless they ask for it
    var glossaryOpen by rememberSaveable { mutableStateOf(false) }
    // Glossary search — rememberSaveable survives rotation
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val filteredTerms = remember(searchQuery) {
        if (searchQuery.isBlank()) {
            glossaryTerms
        } else {
            val q = searchQuery.trim().lowercase()
            glossaryTerms.filter {
                it.term.lowercase().contains(q) || it.meaning.lowercase().contains(q)
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            // Fully opaque header with a hard shadow edge — scrolling cards
            // (lessons, glossary terms like "Circuit") must never show through
            // or look "sliced" under the pinned "Learn" title.
            Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 2.dp) {
                TopAppBar(
                    title = { Text("Learn", fontWeight = FontWeight.Bold) },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        scrolledContainerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(top = 14.dp, bottom = 24.dp)
        ) {
            // ── Lessons that make you money (C21) ─────────────────────────
            item {
                Column {
                    Text(
                        "Lessons That Make You Money",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "${guideLessons.count { it.id in readLessons }} of ${guideLessons.size} read",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            items(guideLessons, key = { it.id }) { lesson ->
                LessonCard(
                    lesson = lesson,
                    isRead = lesson.id in readLessons,
                    onRead = { vm.markLessonRead(lesson.id) },
                    onSpeak = {
                        val spoken = vm.speakLesson(
                            "${lesson.title}. ${lesson.body} ${lesson.moneyLine}"
                        )
                        if (!spoken) {
                            Toast.makeText(context, "Voice not ready", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }

            // ── Trading styles ────────────────────────────────────────────
            item {
                Text(
                    "Ways People Trade and Invest",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            items(tradingStyles, key = { it.title }) { style ->
                ExpandableTradingStyleCard(style)
            }

            // ── ADVANCED (B10/C24) — clearly labelled, off the beginner path.
            //    A full-width divider makes the boundary explicit so the Advanced
            //    sub-section is visually SEPARATE and never read as part of the
            //    beginner lesson list (B10 progressive disclosure). ──
            item {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "Advanced",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = GoldAccent
                    )
                    Text(
                        "Extra depth for curious users — you never need this to use the app.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // "More ways to earn" entry card — lives inside Advanced (C24)
            item {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { navController.navigate(Screen.MoreWays.route) }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Savings,
                                contentDescription = null,
                                tint = GoldAccent,
                                modifier = Modifier.size(24.dp)
                            )
                            Column {
                                Text("More ways to earn", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
                                Text("Gold, Silver, US stocks — tap to see", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = GoldAccent,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }

            // ── Glossary — Advanced, COLLAPSED by default (C24/B10): market
            //    words the app itself never uses on its cards (I2) ──────────
            item {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { glossaryOpen = !glossaryOpen }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Glossary — Market Words",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "Words other people use — the app itself speaks plainly.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            if (glossaryOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (glossaryOpen) "Collapse glossary" else "Expand glossary",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
            if (glossaryOpen) {
                item {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Type a word you don't understand...") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Clear",
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor   = GoldAccent,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline
                        )
                    )
                }
                if (filteredTerms.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "\"$searchQuery\" not found",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else {
                    items(filteredTerms, key = { it.term }) { entry ->
                        GlossaryCard(entry)
                    }
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════
//  SECTION 1 — Lessons (C21): short, simple English, earning order.
//  Every lesson ends with one "How this makes you money" line.
// ══════════════════════════════════════════════════════════════════

private data class GuideLesson(
    val id: String,
    val title: String,
    val body: String,
    val moneyLine: String,       // C21: every lesson ends with this line
    val isScamLesson: Boolean = false
)

private val guideLessons = listOf(
    GuideLesson(
        id    = "stop_loss",
        title = "What a safety stop saves you",
        body  = "A safety stop is your exit price if a trade goes wrong. You decide it BEFORE you buy. " +
                "If the price falls to that level, you sell right away — no waiting, no hoping. " +
                "A small loss today keeps your money alive for the next good trade. " +
                "Every signal in this app comes with a safety stop. Never trade without one.",
        moneyLine = "How this makes you money: small losses never become big losses, so your money survives to catch the winning days."
    ),
    GuideLesson(
        id    = "app_picks",
        title = "Why the app picks these stocks",
        body  = "The app checks real market data for every stock: the price trend, signs of tiredness or fresh energy in the price, how much buying is happening, and how far it is from its recent high or low. " +
                "Only stocks that pass all the safety rules become a pick. " +
                "A pick is a rule-based guess, not a promise — nobody can be sure about the market. " +
                "That is why every pick comes with a stop-loss and a target. " +
                "If the app says WAIT, the best move is to do nothing.",
        moneyLine = "How this makes you money: you only enter trades that pass tested rules, and you skip the risky ones that eat beginners' money."
    ),
    GuideLesson(
        id    = "sip",
        title = "How monthly investing grows money",
        body  = "A monthly investment means putting a small fixed amount into a mutual fund every month. " +
                "When the market falls, the same money buys MORE units — so a fall helps you later. " +
                "Over the years your returns start earning their own returns. This is called compounding. " +
                "Example: ₹2,000 every month at about 12% a year can grow to about ₹4.6 lakh in 10 years — you put in only ₹2.4 lakh. " +
                "The one rule: never stop the SIP when the market is down.",
        moneyLine = "How this makes you money: time and patience do the work — the ups and downs average out and your money compounds."
    ),
    GuideLesson(
        id    = "targets",
        title = "How targets work",
        body  = "A target is the price where you take your profit out. You decide it before you buy — the app shows one with every pick. " +
                "When the price reaches the target, sell and be happy with the profit. " +
                "Do not wait for 'a little more' — that is how a profit turns back into a loss. " +
                "The app sets targets so the possible profit is bigger than the possible loss.",
        moneyLine = "How this makes you money: profit you take out is real money in your account — uncollected profit can vanish in one bad hour."
    ),
    GuideLesson(
        id    = "mistakes",
        title = "Common mistakes that lose money",
        body  = "Five ways beginners lose money: " +
                "1) Buying without a safety stop. " +
                "2) Adding more money to a falling stock. " +
                "3) Trading with money needed at home. " +
                "4) Revenge trading — one loss, then a bigger angry trade. " +
                "5) Buying what everyone is talking about, right at the top. " +
                "This app blocks many of these. When it blocks you, it is protecting you.",
        moneyLine = "How this makes you money: money not lost to these mistakes stays in your account and keeps working for you."
    ),
    GuideLesson(
        id    = "scam",
        title = "Scam protection",
        body  = "Never buy on a 'sure tip' from WhatsApp or Telegram. Anything outside the app's rules is gambling. " +
                "Scammers buy a cheap stock first, spread the 'tip', and then sell to the people who follow it — you become their exit. " +
                "Nobody who truly knows a sure winner shares it in a free group. " +
                "SEBI never sends stock tips. If a tip promises fixed or double returns, it is a scam.",
        moneyLine = "How this makes you money: one avoided scam saves more money than months of good trades can earn.",
        isScamLesson = true
    )
)

@Composable
private fun LessonCard(
    lesson: GuideLesson,
    isRead: Boolean,
    onRead: () -> Unit,
    onSpeak: () -> Unit
) {
    // rememberSaveable — plain remember loses expansion when the item scrolls off-screen
    var expanded by rememberSaveable { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                expanded = !expanded
                if (expanded) onRead()   // opening a lesson counts as reading it
            },
        colors = CardDefaults.cardColors(
            // The scam lesson is the one that protects real money — make it stand out
            containerColor = if (lesson.isScamLesson) RedPrimary.copy(alpha = 0.10f)
                             else MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    if (lesson.isScamLesson) {
                        Box(
                            modifier = Modifier
                                .background(RedPrimary.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                "PROTECTS REAL MONEY",
                                style = MaterialTheme.typography.labelSmall,
                                color = RedPrimary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isRead) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = "Lesson read",
                                tint = GreenPrimary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        Text(
                            lesson.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                // 48dp default IconButton — do not shrink (touch-target rule)
                IconButton(onClick = onSpeak) {
                    Icon(
                        Icons.Default.VolumeUp,
                        contentDescription = "Speak this lesson",
                        tint = GoldAccent
                    )
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(modifier = Modifier.padding(end = 6.dp, bottom = 4.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        lesson.body,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        lineHeight = 20.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        lesson.moneyLine,
                        style = MaterialTheme.typography.bodySmall,
                        color = GoldAccent,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 18.sp
                    )
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════
//  SECTION 2 — Trading styles
// ══════════════════════════════════════════════════════════════════

private data class TradingStyle(
    val title: String,
    val subtitle: String,
    val risk: String,
    val time: String,
    val description: String
)

private val tradingStyles = listOf(
    TradingStyle(
        title       = "Intraday Trading",
        subtitle    = "Buy and sell in one day",
        risk        = "HIGH",
        time        = "1 Day",
        description = "Buy in the morning, sell before market close. Quick profits but high risk."
    ),
    TradingStyle(
        title       = "Swing Trading",
        subtitle    = "Hold for 2-7 days",
        risk        = "MEDIUM",
        time        = "2-7 Days",
        description = "Hold for a few days. Look for price patterns and signs of fresh energy in the price to find good entries."
    ),
    TradingStyle(
        title       = "Positional Trading",
        subtitle    = "Hold for weeks to months",
        risk        = "MEDIUM",
        time        = "1-3 Months",
        description = "Catch the trend and be patient. Bigger moves, bigger profits."
    ),
    TradingStyle(
        title       = "Long Term Investing",
        subtitle    = "Invest in strong companies",
        risk        = "LOW",
        time        = "1-5+ Years",
        description = "Pick good companies and hold. This is how real wealth is built."
    ),
    // Options is a WARNING, not an earning way (A3): most beginners lose here
    TradingStyle(
        title       = "Options Trading (F&O)",
        subtitle    = "Warning — not a way to earn",
        risk        = "VERY_HIGH",
        time        = "Stay away",
        description = "Most beginners LOSE money in options — SEBI data says about 9 in 10. " +
                      "This app will never recommend options. Do not touch F&O until you have years of experience."
    ),
    TradingStyle(
        title       = "Mutual Fund SIP",
        subtitle    = "Invest a little every month",
        risk        = "LOW",
        time        = "5-20+ Years",
        description = "SIP every month and stay invested. Market will go up and down — do not panic."
    )
)

@Composable
private fun ExpandableTradingStyleCard(style: TradingStyle) {
    // rememberSaveable — plain remember loses expansion when the item scrolls off-screen
    var expanded by rememberSaveable { mutableStateOf(false) }

    val riskColor = when (style.risk) {
        "LOW"       -> GreenPrimary
        "MEDIUM"    -> GoldAccent
        "HIGH"      -> RedPrimary
        "VERY_HIGH" -> RedPrimary
        else        -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val riskLabel = when (style.risk) {
        "LOW"       -> "Low Risk"
        "MEDIUM"    -> "Medium Risk"
        "HIGH"      -> "High Risk"
        "VERY_HIGH" -> "Very High Risk"
        else        -> style.risk
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        style.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        style.subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(horizontalAlignment = Alignment.End) {
                        Box(
                            modifier = Modifier
                                .background(riskColor.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                riskLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = riskColor,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Icon(
                                Icons.Default.Schedule,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(12.dp)
                            )
                            Text(
                                style.time,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column {
                    Spacer(modifier = Modifier.height(10.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        style.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        lineHeight = 20.sp
                    )
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════
//  SECTION 3 — Glossary
// ══════════════════════════════════════════════════════════════════

private data class GlossaryTerm(val term: String, val meaning: String)

// C24/I2 — definitions of jargon terms are ONE line of simple English: the
// glossary may explain a word other people use, but never in more jargon.
private val glossaryTerms = listOf(
    GlossaryTerm("RSI (tiredness meter)", "A number from 0 to 100 that shows if a stock has run up too fast (tired — may fall) or fallen too much (maybe cheap — may rise). This app says 'the stock looks tired' instead of showing you the number."),
    GlossaryTerm("MACD (trend signal)", "A chart line that hints when a price trend may be changing direction. This app describes it in plain words so you never need to know this term."),
    GlossaryTerm("VWAP", "The day's average trading price — day traders compare the live price with it."),
    GlossaryTerm("Safety stop", "The price at which you sell to stop a loss from getting bigger. The most important protection tool. This app sets it automatically on every pick."),
    GlossaryTerm("Target", "The price at which you plan to book your profit. Always decide this before entering a trade."),
    GlossaryTerm("Volume", "Total number of shares bought and sold in a day. Higher volume means more interest in the stock."),
    GlossaryTerm("NSE", "National Stock Exchange — India's largest stock exchange where Nifty 50 is traded."),
    GlossaryTerm("BSE", "Bombay Stock Exchange — India's oldest stock exchange where Sensex is traded."),
    GlossaryTerm("SEBI", "Securities and Exchange Board of India — India's stock market regulator. Protects investor interests."),
    GlossaryTerm("Nifty", "Index of top 50 companies on NSE. The main benchmark for the health of the Indian stock market."),
    GlossaryTerm("Sensex", "Index of top 30 companies on BSE. India's oldest market index, started in 1986."),
    GlossaryTerm("FII", "Foreign Institutional Investors — foreign funds investing in Indian markets. Their buying and selling has the biggest market impact."),
    GlossaryTerm("DII", "Domestic Institutional Investors — Indian mutual funds and insurance companies that invest in the local market."),
    GlossaryTerm("IPO", "Initial Public Offering — when a company sells its shares to the public for the first time. Chance to invest in new companies."),
    GlossaryTerm("P/E Ratio", "How costly a share is next to the company's profit — lower can mean cheaper, but never buy on this number alone."),
    GlossaryTerm("Dividend", "A part of the company's profit shared with shareholders. Can be paid as cash or additional shares."),
    GlossaryTerm("Circuit", "Each stock has a daily limit on how far its price may move — at the lower limit there may be no buyers, so you can be stuck unable to sell."),
    GlossaryTerm("Margin", "Trading with borrowed money from your broker. It increases both profits and losses — use carefully."),
    GlossaryTerm("Short Selling", "Sell first, buy later. A way to profit in a falling market. Risk can be unlimited."),
    GlossaryTerm("Bull Market", "Rising market — when prices keep going up. A bull charges upward with its horns."),
    GlossaryTerm("Bear Market", "Falling market — when the market drops 20% or more. A bear swipes downward with its paw."),
    GlossaryTerm("Intraday", "Buying and selling shares within the same day. No position is held overnight."),
    GlossaryTerm("Swing Trade", "Holding shares for a few days to weeks. A style between intraday and long-term investing."),
    GlossaryTerm("Price chart bar", "Each bar on a price chart shows one day's price movement. Green = price went up, red = price went down."),
    GlossaryTerm("Support", "A price level where the stock repeatedly stops falling. Can be a good place to buy — but it can break, so always set a Stop Loss."),
    GlossaryTerm("Resistance", "A price level where the stock repeatedly stops rising. Good place to consider booking profit."),
    GlossaryTerm("Price jump (breakout)", "When a stock suddenly trades above a level it was stuck below. Can mean a strong move up — but half of these fail, so always have a safety stop."),
    GlossaryTerm("Open Interest", "How many risky F&O bets are open right now — this app never touches F&O."),
    GlossaryTerm("EBITDA", "A company's core profit before interest, tax and paper charges — higher is better."),
    GlossaryTerm("Face Value", "The original price of a share set by the company. Usually ₹1, ₹2, or ₹10. Dividend is calculated on this."),
    GlossaryTerm("Market Cap", "Total value of all shares of a company. SEBI rule: Large Cap = top 100 biggest companies (safest), Mid Cap = next 150, Small Cap = all the rest (more risk)."),
    GlossaryTerm("Bonus Issue", "Company gives free extra shares to existing shareholders. 1:1 bonus = one free share for every share held."),
    GlossaryTerm("Rights Issue", "Company gives existing shareholders the right to buy new shares at a discounted price."),
    GlossaryTerm("Demat Account", "Digital locker where your shares are stored electronically. Mandatory for trading in India."),
    GlossaryTerm("Portfolio", "The collection of all your investments. A good portfolio is spread across different sectors."),
    GlossaryTerm("Fundamental Analysis", "Deciding to invest based on a company's balance sheet, profits, debt, and future prospects."),
    GlossaryTerm("Technical Analysis", "Predicting a stock's next move by studying charts, patterns, and indicators.")
)

@Composable
private fun GlossaryCard(entry: GlossaryTerm) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                entry.term,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.ExtraBold,
                color = GoldAccent
            )
            Text(
                entry.meaning,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                lineHeight = 18.sp
            )
        }
    }
}
