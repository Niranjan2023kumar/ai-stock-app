package com.example.myapplication3.ui.screen

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.example.myapplication3.core.common.Constants
import com.example.myapplication3.diag.CrashLog
import com.example.myapplication3.tts.HindiTtsManager
import com.example.myapplication3.ui.component.NotificationsOffBanner
import com.example.myapplication3.ui.component.formatIndianRupees
import com.example.myapplication3.ui.theme.DarkBorder
import com.example.myapplication3.ui.theme.DarkCard
import com.example.myapplication3.ui.theme.GoldAccent
import com.example.myapplication3.ui.theme.GreenPrimary
import com.example.myapplication3.ui.theme.RedPrimary
import com.example.myapplication3.ui.viewmodel.SettingsViewModel
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

// ── E5: the two risk presets, said in words. "Normal" = the app's defaults
// (UserSettingsStore); "Safe" roughly halves them. Raw percentages exist ONLY
// behind the Advanced expander — never on the default view (I3/U2.3).
private const val SAFE_RISK_PCT    = 0.5
private const val SAFE_DAILY_PCT   = 2.0
private const val SAFE_WEEKLY_PCT  = 5.0
private const val NORMAL_RISK_PCT   = 1.0   // = UserSettingsStore.DEFAULT_RISK_PCT
private const val NORMAL_DAILY_PCT  = 3.0   // = DEFAULT_DAILY_LOSS_PCT
private const val NORMAL_WEEKLY_PCT = 8.0   // = DEFAULT_WEEKLY_LOSS_PCT

private fun nearly(a: Double, b: Double): Boolean = abs(a - b) < 1e-4

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(navController: NavController, vm: SettingsViewModel = hiltViewModel()) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // E5: the raw % fields hide behind this expander — closed by default.
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    // Bumped when a preset overwrites the numbers, so any OPEN Advanced fields
    // re-seed their text from the new values instead of showing stale digits.
    var presetStamp by rememberSaveable { mutableStateOf(0) }

    // Saving now gives real feedback: one clear line, then the flag is reset.
    LaunchedEffect(state.isSaved) {
        if (state.isSaved) {
            snackbarHostState.showSnackbar("Saved on this phone.")
            vm.consumeSaved()
        }
    }
    LaunchedEffect(state.saveError) {
        state.saveError?.let {
            snackbarHostState.showSnackbar(it)
            vm.consumeSaved()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { vm.saveSettings() }) {
                        Icon(Icons.Default.Save, contentDescription = "Save settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        if (!state.isLoaded) {
            // Fields must show the SAVED numbers, never factory defaults — so wait
            // for the one-time load before composing them (it is near-instant).
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = GoldAccent)
            }
            return@Scaffold
        }

        LazyColumn(
            modifier            = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding      = PaddingValues(bottom = 24.dp)
        ) {
            // E1: alerts-off warning pinned at the top of every tab — the shared
            // banner renders nothing at all while alerts are deliverable.
            item { NotificationsOffBanner() }

            // ── 1. MY MONEY — capital + one carefulness choice in words (E5).
            //    Raw percentages live behind the Advanced expander only. ──────────
            item {
                Text("My money",
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text("How much you have, and how carefully the app should work with it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // ONE money value (E5/C13/I3): the money comes from the daily check-in
            // on the Stock screen — never typed a second time here. Shown read-only
            // so Settings and the tabs can never display two different figures.
            item {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = DarkCard,
                    border = BorderStroke(1.dp, DarkBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text("Money you are using now",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("₹${formatIndianRupees(state.capital)}",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold)
                        Text("Set in the daily check-in. To change it, tap the ₹ amount at the top of the Stock screen — you never type your money twice.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            item {
                RiskPresetChoice(
                    capital = state.capital,
                    risk    = state.riskPerTrade,
                    daily   = state.maxDailyLoss,
                    weekly  = state.maxWeeklyLoss,
                    onPick  = { r, d, w ->
                        vm.updateRiskPerTrade(r)
                        vm.updateMaxDailyLoss(d)
                        vm.updateMaxWeeklyLoss(w)
                        presetStamp++
                    }
                )
            }

            // The Advanced expander — raw % numbers only appear after this tap.
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showAdvanced = !showAdvanced }
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Advanced — exact percent numbers",
                            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text("Only if you want to fine-tune. The two choices above are enough.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(
                        imageVector = if (showAdvanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (showAdvanced) "Hide advanced settings" else "Show advanced settings"
                    )
                }
            }
            if (showAdvanced) {
                item {
                    NumberTextField(
                        label         = "How Much to Risk in One Trade (%)",
                        value         = state.riskPerTrade,
                        onValueChange = { vm.updateRiskPerTrade(it) },
                        errorText     = state.riskError,
                        helperText    = "That is about ₹${formatIndianRupees(state.capital * state.riskPerTrade / 100.0)} in one trade.",
                        resetKey      = presetStamp
                    )
                }
                item {
                    NumberTextField(
                        label         = "Most You Can Lose in One Day (%)",
                        value         = state.maxDailyLoss,
                        onValueChange = { vm.updateMaxDailyLoss(it) },
                        errorText     = state.dailyLossError,
                        helperText    = "The app stops new trades after about ₹${formatIndianRupees(state.capital * state.maxDailyLoss / 100.0)} lost in a day.",
                        resetKey      = presetStamp
                    )
                }
                item {
                    NumberTextField(
                        label         = "Most You Can Lose in One Week (%)",
                        value         = state.maxWeeklyLoss,
                        onValueChange = { vm.updateMaxWeeklyLoss(it) },
                        errorText     = state.weeklyLossError,
                        helperText    = "The app stops for the week after about ₹${formatIndianRupees(state.capital * state.maxWeeklyLoss / 100.0)} lost.",
                        resetKey      = presetStamp
                    )
                }
            }

            item {
                Button(
                    onClick  = { vm.saveSettings() },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors   = ButtonDefaults.buttonColors(containerColor = GoldAccent)
                ) {
                    Text("Save Settings",
                        color      = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.Bold,
                        style      = MaterialTheme.typography.titleSmall)
                }
            }

            item { HorizontalDivider(color = MaterialTheme.colorScheme.outline) }

            // ── 2. ALERTS — plain on/off state + a real test alert (E5) ─────────
            item {
                AlertsSection(onResult = { msg ->
                    scope.launch { snackbarHostState.showSnackbar(msg) }
                })
            }

            item { HorizontalDivider(color = MaterialTheme.colorScheme.outline) }

            // ── 3. ABOUT — how the app gets its data (honest, B8/C21a) ──────────
            // The old Alpha Vantage / NewsAPI / RapidAPI key fields were dead ends
            // (nothing read them) — removed. Same for the refresh-speed and NSE/BSE
            // choices, which changed nothing. Only true lines remain.
            item {
                Card(colors = CardDefaults.cardColors(containerColor = DarkCard)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("About Your Data",
                            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text("The app runs fully on free data — no keys and no payment needed.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Prices update every 30 seconds while the market is open. It shows Indian (NSE) stocks.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Your data stays only on your phone — nowhere else. If you change phones or reinstall the app, your journal and settings start fresh.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            item { AboutRow() }

            // Recent problems — surfaces the local crash log (diag/CrashLog).
            item { RecentProblemsRow() }

            item { HorizontalDivider(color = MaterialTheme.colorScheme.outline) }

            // ── 4. Advanced: live data (Angel One SmartAPI) — LAST on purpose: the
            //    app works fully without it, so it must never greet a beginner ────
            item {
                Column {
                    Text("Advanced — live data (optional)",
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Only for Angel One account holders who want faster live prices. The app works fully without this.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item { SmartApiSettingsCard() }
        }
    }
}

// ══════════════════════════════════════════════════════════════════
//  E5 — risk presets in words (Safe / Normal)
// ══════════════════════════════════════════════════════════════════

/**
 * One carefulness choice presented as words plus honest ₹ amounts — never raw
 * percentages. Picking one overwrites all three limits at once; the user still
 * taps Save below, exactly like every other change on this screen.
 */
@Composable
private fun RiskPresetChoice(
    capital: Double,
    risk: Double,
    daily: Double,
    weekly: Double,
    onPick: (risk: Double, daily: Double, weekly: Double) -> Unit
) {
    val safeSelected = nearly(risk, SAFE_RISK_PCT) &&
            nearly(daily, SAFE_DAILY_PCT) && nearly(weekly, SAFE_WEEKLY_PCT)
    val normalSelected = nearly(risk, NORMAL_RISK_PCT) &&
            nearly(daily, NORMAL_DAILY_PCT) && nearly(weekly, NORMAL_WEEKLY_PCT)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("How careful should the app be?",
            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        PresetCard(
            title     = "Safe",
            words     = "Very careful — small trades, stops early.",
            moneyLine = "About ₹${formatIndianRupees(capital * SAFE_RISK_PCT / 100.0)} in one trade. " +
                    "Stops the day after about ₹${formatIndianRupees(capital * SAFE_DAILY_PCT / 100.0)} lost.",
            selected  = safeSelected,
            onClick   = { onPick(SAFE_RISK_PCT, SAFE_DAILY_PCT, SAFE_WEEKLY_PCT) }
        )
        PresetCard(
            title     = "Normal",
            words     = "Careful — the standard setting for this app.",
            moneyLine = "About ₹${formatIndianRupees(capital * NORMAL_RISK_PCT / 100.0)} in one trade. " +
                    "Stops the day after about ₹${formatIndianRupees(capital * NORMAL_DAILY_PCT / 100.0)} lost.",
            selected  = normalSelected,
            onClick   = { onPick(NORMAL_RISK_PCT, NORMAL_DAILY_PCT, NORMAL_WEEKLY_PCT) }
        )
        if (!safeSelected && !normalSelected) {
            // Custom numbers (set earlier or under Advanced) — say so honestly
            // instead of silently highlighting the nearest choice.
            Text("You are using your own numbers right now. Tap a choice above, or fine-tune under Advanced.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PresetCard(
    title: String,
    words: String,
    moneyLine: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = DarkCard,
        border = BorderStroke(
            if (selected) 1.5.dp else 1.dp,
            if (selected) GoldAccent else DarkBorder
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = selected,
                onClick  = null,   // the whole card is the touch target
                colors   = RadioButtonDefaults.colors(selectedColor = GoldAccent)
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(words, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(moneyLine, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════
//  E5 — Alerts section (state + "Send test alert")
// ══════════════════════════════════════════════════════════════════

@Composable
private fun AlertsSection(onResult: (String) -> Unit) {
    val context = LocalContext.current
    var alertsOn by remember { mutableStateOf(alertsDeliverable(context)) }
    // Re-check on resume — the user flips the system toggle and comes back.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) alertsOn = alertsDeliverable(context)
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Alerts", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        // Owner's word 2026-07-18: the MASTER voice switch sits FIRST — one visible
        // switch that makes the whole app silent when OFF (HindiTtsManager gates
        // every speak call on it), then the Hindi voice switch below it.
        VoiceSwitchRow()
        HindiVoiceSwitchRow()
        Text(
            text = if (alertsOn) "Alerts are ON — the app can warn you (stop-loss, SIP day)."
                   else "Alerts are OFF — the app cannot warn you. Tap the banner at the top of this page to turn them on.",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = if (alertsOn) GreenPrimary else RedPrimary
        )
        OutlinedButton(
            onClick = {
                onResult(
                    when {
                        !alertsOn -> "Alerts are off — turn them on first, then try again."
                        sendTestAlert(context) -> "Test alert sent — look at the top of your screen."
                        else -> "Could not send the test alert. Check your phone's alert settings."
                    }
                )
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Send test alert") }
        Text("The test shows you exactly what a real warning will look like.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * MASTER voice switch (owner order, 2026-07-18): "Voice" ON/OFF. OFF = the app
 * is fully silent — HindiTtsManager gates the top of EVERY public speak call on
 * the same "voice_enabled" boolean (default TRUE), re-read on each sentence, so
 * flipping it works instantly and survives restarts. 48dp+ touch target (U2.x).
 */
@Composable
private fun VoiceSwitchRow() {
    val context = LocalContext.current
    var voiceOn by remember { mutableStateOf(HindiTtsManager.isVoiceOn(context)) }
    val flip: (Boolean) -> Unit = { on ->
        voiceOn = on
        HindiTtsManager.setVoiceOn(context, on)
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = DarkCard,
        border = BorderStroke(1.dp, if (voiceOn) GoldAccent else DarkBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .clickable { flip(!voiceOn) }
                .heightIn(min = 48.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Voice",
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text("The app speaks its advice out loud. Turn off for silence.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(
                checked = voiceOn,
                onCheckedChange = flip,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = GoldAccent,
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    }
}

/**
 * Owner-approved (2026-07-18 amendment to the English-only rule): one VISIBLE
 * switch — "Speak alerts in Hindi". The screen stays English no matter what;
 * only the VOICE changes. It writes the same "voice_hindi" boolean that
 * HindiTtsManager re-reads on every sentence, so the change is instant and
 * survives restarts. The whole row is a 48dp+ touch target (U2.x).
 */
@Composable
private fun HindiVoiceSwitchRow() {
    val context = LocalContext.current
    var hindiOn by remember { mutableStateOf(HindiTtsManager.isHindiVoiceOn(context)) }
    val flip: (Boolean) -> Unit = { on ->
        hindiOn = on
        HindiTtsManager.setHindiVoiceOn(context, on)
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = DarkCard,
        border = BorderStroke(1.dp, if (hindiOn) GoldAccent else DarkBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .clickable { flip(!hindiOn) }
                .heightIn(min = 48.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Speak alerts in Hindi",
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text("Screen stays English. Voice speaks Hindi.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(
                checked = hindiOn,
                onCheckedChange = flip,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = GoldAccent,
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    }
}

/** Same checks as the shared banner: master switch + the alerts channel itself. */
private fun alertsDeliverable(context: Context): Boolean = runCatching {
    val nm = NotificationManagerCompat.from(context)
    if (!nm.areNotificationsEnabled()) return@runCatching false
    if (Build.VERSION.SDK_INT >= 26) {
        val ch = nm.getNotificationChannel(Constants.CHANNEL_ID_ALERTS)
        if (ch != null && ch.importance == android.app.NotificationManager.IMPORTANCE_NONE)
            return@runCatching false
    }
    true
}.getOrDefault(true) // B8: a failed check must never crash or cry wolf

/**
 * Posts one real local notification on the SAME channel real alerts use, so the
 * test proves the actual delivery path. Returns false when it could not post
 * (permission revoked mid-session and the like) — the caller says so honestly.
 */
private fun sendTestAlert(context: Context): Boolean = runCatching {
    val nm = NotificationManagerCompat.from(context)
    if (!nm.areNotificationsEnabled()) return@runCatching false
    // The channel normally exists already (the notifications module creates it);
    // creating it again is a no-op, and guarantees the test can never miss.
    nm.createNotificationChannel(
        NotificationChannelCompat.Builder(
            Constants.CHANNEL_ID_ALERTS,
            NotificationManagerCompat.IMPORTANCE_HIGH
        ).setName("Trade Alerts").setDescription("Stop loss and target alerts").build()
    )
    val notification = NotificationCompat.Builder(context, Constants.CHANNEL_ID_ALERTS)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle("Test alert — it works")
        .setContentText("This is what a warning from this app looks like.")
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setAutoCancel(true)
        .build()
    nm.notify("settings-test-alert".hashCode(), notification)
    true
}.getOrDefault(false) // SecurityException (permission pulled) lands here

/** App name + version, read from the installed package (no build-config needed). */
@Composable
private fun AboutRow() {
    val context = LocalContext.current
    val version = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("AI Stock Intelligence",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Version $version",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ══════════════════════════════════════════════════════════════════
//  Recent problems — the local crash log, surfaced honestly (E6/B8)
// ══════════════════════════════════════════════════════════════════

/**
 * Surfaces diag/CrashLog in plain words. When nothing is recorded, the row is
 * one quiet reassuring line. When something IS recorded, it says how many and
 * a tap opens the full notes with Clear and Share. The notes never leave the
 * phone unless the user themselves taps Share (E6). Every file touch is
 * already runCatching-guarded inside CrashLog (B8).
 */
@Composable
private fun RecentProblemsRow() {
    val context = LocalContext.current
    var logText by remember { mutableStateOf(CrashLog.recentCrashes(context)) }
    var showDialog by rememberSaveable { mutableStateOf(false) }

    // Each recorded crash starts with this exact marker (see CrashLog.append);
    // the "No crashes recorded." placeholder contains none, so 0 = clean.
    val problemCount = remember(logText) {
        logText.lineSequence().count { it.startsWith("==== CRASH ") }
    }
    val hasProblems = problemCount > 0

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = hasProblems) {
                logText = CrashLog.recentCrashes(context) // freshest notes in the dialog
                showDialog = true
            }
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Recent problems",
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                text = when {
                    !hasProblems       -> "No problems recorded"
                    problemCount == 1  -> "1 noted — tap to read"
                    else               -> "$problemCount noted — tap to read"
                },
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                // U9.3: green = all good, amber = caution (never red — a saved
                // note is not a live danger).
                color = if (hasProblems) GoldAccent else GreenPrimary
            )
        }
        Text("If the app ever stops working, its notes are saved here — only on your phone.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Recent problems") },
            text = {
                Column {
                    Text("These notes help fix the app. They stay on your phone unless you share them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(logText,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) { Text("Close") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        CrashLog.clear(context)
                        logText = CrashLog.recentCrashes(context)
                        showDialog = false
                    }) { Text("Clear") }
                    TextButton(onClick = { shareCrashLog(context, logText) }) { Text("Share") }
                }
            }
        )
    }
}

/** Opens the system share sheet with the notes as plain text. B8: never crashes. */
private fun shareCrashLog(context: Context, text: String) {
    runCatching {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "AI Stock Intelligence — problem notes")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(intent, "Share problem notes"))
    }
}

/** Whole numbers show without ".00" so the field reads like money, not maths. */
private fun fmtFieldValue(v: Double): String =
    if (v % 1.0 == 0.0) String.format(Locale.US, "%.0f", v)
    else String.format(Locale.US, "%.2f", v)

/**
 * Number field that starts from the SAVED value (the screen composes it only
 * after loading) and never resets while the user types. A wrong entry shows a
 * one-line reason right under the box.
 *
 * [resetKey]: when it CHANGES, the text re-seeds from [value] — used when a
 * risk preset overwrites the numbers while the Advanced fields are open. The
 * user's own typing never changes the key, so typing is never interrupted.
 */
@Composable
fun NumberTextField(
    label: String,
    value: Double,
    onValueChange: (Double) -> Unit,
    errorText: String? = null,
    helperText: String? = null,
    resetKey: Any? = null
) {
    // Initial text set once per resetKey — parent recompositions (from the
    // user's own typing) never reset it mid-edit.
    var text by remember(resetKey) { mutableStateOf(fmtFieldValue(value)) }
    OutlinedTextField(
        value         = text,
        onValueChange = { newText ->
            text = newText
            newText.toDoubleOrNull()?.let { onValueChange(it) }
        },
        label           = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier        = Modifier.fillMaxWidth(),
        singleLine      = true,
        isError         = errorText != null,
        supportingText  = {
            when {
                errorText != null  -> Text(errorText, color = MaterialTheme.colorScheme.error)
                helperText != null -> Text(helperText)
            }
        },
        colors          = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = GoldAccent
        )
    )
}
