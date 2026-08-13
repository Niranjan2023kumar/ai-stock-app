package com.example.myapplication3.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myapplication3.core.common.Constants
import com.example.myapplication3.core.domain.model.TradeDirection
import com.example.myapplication3.intraday.ResearchNewsItem
import com.example.myapplication3.tracking.TrackedTrade
import com.example.myapplication3.ui.viewmodel.InterdayViewModel
import com.example.myapplication3.ui.theme.CautionAmber
import com.example.myapplication3.ui.theme.CautionContainer
import com.example.myapplication3.ui.theme.DarkBorder
import com.example.myapplication3.ui.theme.DarkCard
import com.example.myapplication3.ui.theme.GoldAccent
import com.example.myapplication3.ui.theme.GoldContainer
import com.example.myapplication3.ui.theme.GoldLight
import com.example.myapplication3.ui.theme.GreenLight
import com.example.myapplication3.ui.theme.GreenPrimary
import com.example.myapplication3.ui.theme.RedLight
import com.example.myapplication3.ui.theme.RedPrimary
import com.example.myapplication3.ui.theme.TextMuted
import com.example.myapplication3.ui.theme.TextOnGold
import com.example.myapplication3.ui.theme.TextPrimary
import com.example.myapplication3.ui.theme.TextSecondary
import java.util.Locale

// ═════════════════════════════════════════════════════════════════════════════
// Indian digit grouping (I9) — the ONE shared rupee-number formatter
// ═════════════════════════════════════════════════════════════════════════════
// Java's Locale("en","IN") with "%,d" / "%,.0f" does NOT produce lakh/crore
// grouping on Android — java.util.Formatter always groups in threes, so users
// saw 100,000 instead of the 1,00,000 they read everywhere else in India.
// These functions implement TRUE Indian grouping with pure string math:
// the last 3 integer digits stay together, every group above that has 2 digits.
//
//   formatIndianRupees(100000.0)          → "1,00,000"
//   formatIndianRupees(1234567.89, 2)     → "12,34,567.89"
//   formatIndianRupees(-4500.0)           → "-4,500"
//   formatIndianRupees(999.95, 0)         → "1,000"   (rounds FIRST, then groups)
//   formatIndianRupees(123456789L)        → "12,34,56,789"
//
// No "₹" prefix is added — callers put the symbol in front, exactly like the
// screens already do ("₹${…}"). Screens adopt this in the next fix stage.

/**
 * Formats [v] with Indian (lakh/crore) digit grouping.
 *
 * @param v        the amount. Non-finite input (NaN/Infinity) returns "—" so a
 *                 broken upstream number can never masquerade as real money (B8).
 * @param decimals digits after the decimal point (0–6, default 0). The value is
 *                 rounded half-up to this precision BEFORE grouping.
 * @return e.g. "1,00,000", "12,34,567.89", "-4,500". A value that rounds to
 *         zero never shows a minus sign ("-0" is normalised to "0").
 */
fun formatIndianRupees(v: Double, decimals: Int = 0): String {
    if (v.isNaN() || v.isInfinite()) return "—"
    val d = decimals.coerceIn(0, 6)
    // Locale.US guarantees ASCII digits and '.' as the decimal separator; %f
    // rounds half-up and never emits scientific notation, so string math below
    // is safe for any finite double.
    var plain = String.format(Locale.US, "%.${d}f", v)
    val negative = plain.startsWith("-")
    if (negative) plain = plain.substring(1)
    val dot = plain.indexOf('.')
    val intPart  = if (dot >= 0) plain.substring(0, dot) else plain
    val fracPart = if (dot >= 0) plain.substring(dot) else ""   // includes the '.'
    val grouped = groupIndianDigits(intPart) + fracPart
    // "-0" / "-0.00" (tiny negatives rounded away) must not show a minus sign.
    val roundedToZero = plain.none { it in '1'..'9' }
    return if (negative && !roundedToZero) "-$grouped" else grouped
}

/**
 * Formats [v] with Indian (lakh/crore) digit grouping, e.g.
 * `formatIndianRupees(12345678L)` → "1,23,45,678".
 * Works for the full Long range including [Long.MIN_VALUE]
 * (sign handled as text, so there is no abs() overflow).
 */
fun formatIndianRupees(v: Long): String {
    val s = v.toString()
    val negative = s.startsWith("-")
    val digits = if (negative) s.substring(1) else s
    val grouped = groupIndianDigits(digits)
    return if (negative) "-$grouped" else grouped
}

/**
 * Groups a plain string of decimal digits Indian-style: the last 3 digits stay
 * together, then commas every 2 digits going left.
 * "1" → "1" · "1234" → "1,234" · "100000" → "1,00,000" · "12345678" → "1,23,45,678".
 */
private fun groupIndianDigits(digits: String): String {
    if (digits.length <= 3) return digits
    val last3 = digits.substring(digits.length - 3)
    var head  = digits.substring(0, digits.length - 3)
    // Collect 2-digit groups right-to-left, then reassemble left-to-right.
    val groups = ArrayList<String>(head.length / 2 + 1)
    while (head.length > 2) {
        groups.add(head.substring(head.length - 2))
        head = head.substring(0, head.length - 2)
    }
    if (head.isNotEmpty()) groups.add(head)
    val sb = StringBuilder(digits.length + digits.length / 2)
    for (i in groups.indices.reversed()) {
        sb.append(groups[i]).append(',')
    }
    sb.append(last3)
    return sb.toString()
}

// ── MetricCard — FIX: modifier param so it works inside Row/Column layouts ────
@Composable
fun MetricCard(
    title: String,
    value: String,
    subtitle: String = "",
    color: Color = TextPrimary,   // was Color.White → invisible on the white card
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                title,
                style    = MaterialTheme.typography.labelSmall,
                color    = TextSecondary
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                value,
                style      = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color      = color
            )
            if (subtitle.isNotEmpty()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        }
    }
}

// ── ConfidenceBadge ──────────────────────────────────────────────────────────
@Composable
fun ConfidenceBadge(score: Double) {
    // HIGH = green, MEDIUM = CAUTION amber (was brand green → identical to HIGH after
    // the re-theme), LOW = red. Text uses a readable ink, not the bright tint colour,
    // so every badge stays >=4.5:1 on the light card.
    val (tint, ink, label) = when {
        score >= 0.8  -> Triple(GreenPrimary, GreenLight,  "HIGH")
        score >= 0.65 -> Triple(CautionAmber, TextPrimary, "MEDIUM")
        else          -> Triple(RedPrimary,   RedLight,    "LOW")
    }
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = tint.copy(alpha = 0.18f)
    ) {
        Text(
            "$label ${String.format("%.0f", score * 100)}%",
            modifier   = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            color      = ink,
            style      = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold
        )
    }
}

// ── DirectionBadge ───────────────────────────────────────────────────────────
@Composable
fun DirectionBadge(direction: TradeDirection) {
    val (tint, ink, label) = if (direction == TradeDirection.LONG)
        Triple(GreenPrimary, GreenLight, "LONG")
    else
        Triple(RedPrimary, RedLight, "SHORT")
    Surface(shape = RoundedCornerShape(6.dp), color = tint.copy(alpha = 0.2f)) {
        Text(
            label,
            modifier   = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            color      = ink,
            style      = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold
        )
    }
}

// ── RecommendationCard — DELETED (I2/B5) ─────────────────────────────────────
// The old RecommendationCard composable lived here. It had ZERO callers (grep-
// verified) and rendered banned trader jargon ("T2", "RR") that the zero-
// knowledge rules forbid, so it was removed instead of shipped dark.

// ── LevelItem ────────────────────────────────────────────────────────────────
@Composable
fun LevelItem(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        Spacer(modifier = Modifier.height(2.dp))
        Text(value,  style = MaterialTheme.typography.bodySmall, color = color, fontWeight = FontWeight.Bold)
    }
}

// ── SectionHeader ─────────────────────────────────────────────────────────────
@Composable
fun SectionHeader(title: String, action: String = "", onAction: () -> Unit = {}) {
    Row(
        modifier              = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (action.isNotEmpty()) {
            TextButton(onClick = onAction) {
                Text(action, style = MaterialTheme.typography.labelMedium, color = GoldLight)
            }
        }
    }
}

// ── LoadingIndicator — FIX: accepts padding so it doesn't overlap status bar ─
@Composable
fun LoadingIndicator(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = GoldAccent)
    }
}

// ── ErrorBanner ───────────────────────────────────────────────────────────────
@Composable
fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(containerColor = RedPrimary.copy(alpha = 0.12f)),
        shape    = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier              = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically
        ) {
            Text(
                message,
                modifier = Modifier.weight(1f),
                style    = MaterialTheme.typography.bodySmall,
                color    = RedLight
            )
            TextButton(onClick = onDismiss) {
                Text("Dismiss", color = RedLight)
            }
        }
    }
}

// ── NotificationsOffBanner — SHARED alerts-off safety banner (E1/U4.5, I7) ────
// Canonical version of the banner that was private in HomeScreen: when the app
// cannot deliver notifications it CANNOT warn about stop-loss — a silent safety
// hole — so one thin amber banner says so and tapping it opens this app's
// system notification page. This shared version catches ALL the ways alerts
// can be off, on every supported Android version (minSdk 24):
//   • App-wide notifications toggled off (any API level, via
//     NotificationManagerCompat.areNotificationsEnabled(); on 33+ this is also
//     false when the POST_NOTIFICATIONS permission was denied).
//   • Android 13+ POST_NOTIFICATIONS denied (checked explicitly as well,
//     belt-and-braces).
//   • Android 8+ (API 26): the ALERTS channel individually silenced —
//     importance set to NONE — even though app-wide notifications look on.
// Re-checks on every resume, so it disappears the moment the user turns alerts
// back on and returns. Renders nothing while everything is fine.
@Composable
fun NotificationsOffBanner() {
    val ctx = LocalContext.current
    fun alertsBlocked(): Boolean = runCatching {
        val nm = NotificationManagerCompat.from(ctx)
        // 1) Master switch (all API levels; covers denied POST_NOTIFICATIONS on 33+).
        if (!nm.areNotificationsEnabled()) return@runCatching true
        // 2) Android 13+ runtime permission, checked explicitly too.
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                ctx, android.Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return@runCatching true
        // 3) Android 8+: the alerts CHANNEL alone can be silenced.
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            val ch = nm.getNotificationChannel(Constants.CHANNEL_ID_ALERTS)
            if (ch != null && ch.importance == android.app.NotificationManager.IMPORTANCE_NONE)
                return@runCatching true
        }
        false
    }.getOrDefault(false) // B8: a failed check must never crash the screen.
    var blocked by remember { mutableStateOf(alertsBlocked()) }
    // On-resume re-check — the user leaves for the system settings page and the
    // banner must update the moment they come back.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) blocked = alertsBlocked()
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    if (!blocked) return
    Surface(
        color    = CautionContainer,
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                runCatching {
                    val intent = if (android.os.Build.VERSION.SDK_INT >= 26) {
                        android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                    } else {
                        // API 24-25: no per-app notification settings intent extras —
                        // the app-details page has the notification toggle.
                        android.content.Intent(
                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.parse("package:${ctx.packageName}")
                        )
                    }
                    ctx.startActivity(intent)
                }
            }
    ) {
        Row(
            modifier              = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("🔕", fontSize = 14.sp)
            Text(
                "Alerts are OFF — I cannot warn you about safety stops. Tap to turn on.",
                fontSize   = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color      = TextPrimary,
                lineHeight = 16.sp,
                modifier   = Modifier.weight(1f)
            )
            Icon(
                Icons.Default.KeyboardArrowRight,
                contentDescription = null,
                tint     = CautionAmber,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

// ── VoiceButton — THE single canonical "read this aloud" control (I5) ─────────
// One shared speak button everywhere: a 48dp touch target (was 40dp — under the
// minimum) with a clean Material speaker icon, deep-green ink on a soft brand-green
// tint so it stays readable on the light card. Always carries a contentDescription
// for TalkBack.
@Composable
fun VoiceButton(
    contentDescription: String = "Listen",
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    FilledTonalIconButton(
        onClick  = onClick,
        modifier = modifier.size(48.dp),
        colors   = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = GoldContainer,
            contentColor   = GoldLight
        )
    ) {
        Icon(
            imageVector        = Icons.Default.VolumeUp,
            contentDescription = contentDescription,
            modifier           = Modifier.size(24.dp)
        )
    }
}

// ── MyStockCard — one open trade with its daily HOLD/SELL verdict (C7a) ───────
// Shared by the Stock (Home) tab AND the Intraday tab, so a position confirmed on
// either tab is visible — with the Mark-sold flow — on both (audit: "open position
// invisible on Intraday tab"). The app only advises; selling happens in Groww (B0.4).

// I9: was String.format(Locale("en","IN"), "%,.0f", v) — that groups in threes
// (100,000), NOT Indian style. Delegates to the shared true-Indian formatter.
private fun rs(v: Double): String = formatIndianRupees(v, 0)

@Composable
fun MyStockCard(
    trade: TrackedTrade,
    currentPrice: Double,
    onSell: () -> Unit,
    onMarkSold: (Double) -> Unit,
    modifier: Modifier = Modifier
) {
    var askSellPrice by remember { mutableStateOf(false) }
    val pnl = if (currentPrice > 0.0) (currentPrice - trade.entryPrice) * trade.quantity else 0.0

    // P0 #3 (C7a/U4.2): the ACTIVITY-scoped InterdayViewModel — the SAME instance
    // the Stock/Intraday screens use (they call hiltViewModel(activity) too), so the
    // owned-stock re-score map and the voice both come from one shared source. This
    // is how the shared card consumes the flow without the call sites passing it in.
    val activity = LocalContext.current as androidx.activity.ComponentActivity
    val vm: InterdayViewModel = hiltViewModel(activity)
    val verdicts by vm.myStockVerdicts.collectAsStateWithLifecycle()
    val symKey = trade.symbol.removeSuffix(".NS").uppercase()
    // The signal-based deterioration verdict (present only when the app wants a sell).
    val fallingVerdict = verdicts[symKey]?.takeIf { it.sell }

    val (verdict, color, canSell) = when {
        currentPrice <= 0.0 ->
            Triple("Checking the price…", TextSecondary, false)
        trade.target1 > 0.0 && currentPrice >= trade.target1 ->
            Triple("SELL now — target reached, take your profit", GreenLight, true)
        trade.stopLoss > 0.0 && currentPrice <= trade.stopLoss ->
            Triple("SELL now — near your safety stop, protect your money", RedLight, true)
        // Signs-of-falling re-score: warn BEFORE the stop physically hits when the
        // setup has died (app's own SELL signal, or trend + momentum rolled over).
        // Real signal data only — absent for a symbol we can't judge, so this branch
        // simply doesn't fire and the price-vs-levels HOLD below stands (B6).
        fallingVerdict != null ->
            Triple(fallingVerdict.message, RedLight, true)
        // "still ahead" wording (was "still away"); guard the ₹0 case when a trade
        // was recorded without a target, so the user never reads "target ₹0".
        trade.target1 > 0.0 ->
            Triple("Still watching — target ₹${rs(trade.target1)} is ahead", TextPrimary, false)
        else ->
            Triple("Watching this for you", TextPrimary, false)
    }

    // Voice-parity (U1.5): the SpeakButton reads the SAME words the card shows —
    // who/how many, the live profit-or-loss, then the verdict. This is the most
    // consequential daily decision after buying, and previously had no speaker.
    val spoken = buildString {
        if (trade.isPractice) append("Practice trade. ")
        append("${trade.symbol}, ${trade.quantity} shares. ")
        if (currentPrice > 0.0) {
            val amt = kotlin.math.abs(pnl).toInt()
            append(if (pnl >= 0) "You are up $amt rupees. " else "You are down $amt rupees. ")
        }
        append(verdict)
        if (!verdict.trimEnd().endsWith(".")) append(".")
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        border = BorderStroke(1.dp, color.copy(alpha = 0.35f))
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${trade.symbol}  •  ${trade.quantity} shares", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    // Fake money must never read like real money (B0.3b) → CAUTION
                    // amber, never the brand green. Amber frame + dark text stays readable.
                    if (trade.isPractice) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = CautionContainer,
                            border = BorderStroke(1.dp, CautionAmber)
                        ) {
                            Text("PRACTICE", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextPrimary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                    }
                }
                if (currentPrice > 0.0) {
                    val up = pnl >= 0
                    Text("${if (up) "+" else "−"}₹${rs(kotlin.math.abs(pnl))}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (up) GreenPrimary else RedPrimary)
                }
            }
            Text(verdict, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = color)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (canSell) {
                    Button(
                        onClick = onSell,
                        modifier = Modifier.heightIn(min = 48.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = GoldAccent, contentColor = TextOnGold)
                    ) { Text("Sell in Groww", fontWeight = FontWeight.Bold, fontSize = 12.sp) }
                }
                TextButton(
                    onClick = { askSellPrice = true },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("I sold it ✓", color = TextSecondary, fontSize = 12.sp)
                }
                // Voice-parity (U1.5): one tap hears the same verdict the card shows.
                Spacer(Modifier.weight(1f))
                VoiceButton(
                    contentDescription = "Listen to this stock's advice",
                    onClick = { vm.speakText(spoken) }
                )
            }
        }
    }
    // Capture the REAL sell price so P/L and the loss-limit are correct (money-tracking fix).
    if (askSellPrice) {
        PriceInputDialog(
            title = "Your sell price",
            prompt = "Enter the price you sold ${trade.symbol} at in Groww. Not sure? Keep the shown price.",
            suggested = if (currentPrice > 0.0) currentPrice else trade.entryPrice,
            onConfirm = { p -> askSellPrice = false; onMarkSold(p) },
            onDismiss = { askSellPrice = false }
        )
    }
}

// ── GoldChip — selectable filter chip with gold selected state ────────────────
@Composable
fun GoldChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    FilterChip(
        selected = selected,
        onClick  = onClick,
        label    = { Text(label, style = MaterialTheme.typography.labelMedium) },
        modifier = modifier,
        colors   = FilterChipDefaults.filterChipColors(
            selectedContainerColor    = GoldAccent,
            selectedLabelColor        = TextOnGold,
            containerColor            = DarkCard,
            labelColor                = TextSecondary
        ),
        border   = FilterChipDefaults.filterChipBorder(
            enabled              = true,
            selected             = selected,
            selectedBorderColor  = GoldAccent,
            borderColor          = DarkBorder
        )
    )
}

// ═════════════════════════════════════════════════════════════════════════════
// F6.2 — Contextual news: latest headlines for the shown stock
// ═════════════════════════════════════════════════════════════════════════════

/**
 * Shows up to 3 recent news headlines for [symbol].
 * Fetches asynchronously on first composition; hides itself when empty or loading.
 * Used in both TradingScreen (Intraday tab) and HomeScreen (Stock decision card).
 */
@Composable
fun StockNewsSection(symbol: String, vm: InterdayViewModel, modifier: Modifier = Modifier) {
    val newsItems by produceState<List<ResearchNewsItem>>(initialValue = emptyList(), key1 = symbol) {
        value = runCatching { vm.fetchNewsFor(symbol) }.getOrElse { emptyList() }
    }
    if (newsItems.isEmpty()) return
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Latest news", fontSize = 11.sp, color = TextMuted, fontWeight = FontWeight.SemiBold)
        newsItems.take(3).forEach { item -> StockNewsRow(item) }
    }
}

@Composable
private fun StockNewsRow(item: ResearchNewsItem) {
    val uriHandler = LocalUriHandler.current
    val ageMs = System.currentTimeMillis() - item.publishedAt
    val timeLabel = when {
        item.publishedAt <= 0L            -> ""
        ageMs < 60 * 60_000L              -> "${(ageMs / 60_000L).coerceAtLeast(1)}m ago"
        ageMs < 24 * 60 * 60_000L        -> "${ageMs / 3_600_000L}h ago"
        ageMs < 48 * 60 * 60_000L        -> "Yesterday"
        else -> java.text.SimpleDateFormat("dd MMM", java.util.Locale.ENGLISH)
                    .format(java.util.Date(item.publishedAt))
    }
    Surface(
        modifier = Modifier.fillMaxWidth().then(
            if (item.url.isNotEmpty())
                Modifier.clickable { runCatching { uriHandler.openUri(item.url) } }
            else Modifier
        ),
        shape = RoundedCornerShape(8.dp),
        color = DarkCard
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(item.headline, fontSize = 12.sp, color = TextPrimary,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(item.source, fontSize = 10.sp, color = TextMuted)
                if (timeLabel.isNotEmpty()) {
                    Text("·  $timeLabel", fontSize = 10.sp, color = TextMuted)
                }
            }
        }
    }
}
