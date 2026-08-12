package com.example.myapplication3.tracking

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.myapplication3.core.common.Constants
import com.example.myapplication3.intraday.IntradayRepository
import com.example.myapplication3.notifications.manager.StockNotificationManager
import com.example.myapplication3.smartapi.SmartApiFeed
import com.example.myapplication3.smartapi.SmartApiStore
import com.example.myapplication3.tts.HindiTtsManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service that protects OPEN trades (locked decision: "both, auto" —
 * runs only while a position is open). It watches live prices for the user's open
 * trades and, the moment one hits its stop-loss or target, fires a strong buzz +
 * notification + voice (C15a). The app can only ALERT — the user sells in Groww
 * (B0.4). Self-stops when no trades remain open.
 *
 * Price sources: when the Angel One live feed is connected, its real-time ticks
 * are preferred and checks run every ~15s; the ~90s Yahoo batch fetch always keeps
 * running as the backup source (and is the only source without SmartAPI keys).
 */
@AndroidEntryPoint
class TradeWatchService : Service() {

    @Inject lateinit var tracker: TradeTrackerRepository
    @Inject lateinit var marketRepo: IntradayRepository
    @Inject lateinit var feed: SmartApiFeed
    @Inject lateinit var smartApiStore: SmartApiStore
    @Inject lateinit var notifier: StockNotificationManager
    @Inject lateinit var tts: HindiTtsManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    // Alert bookkeeping (B0.3c/C15a/U4.3). The danger path and the profit path escalate
    // DIFFERENTLY, because their promises differ:
    //  • TARGET (profit) — a single ping is easy to sleep through, so a reached target
    //    re-buzzes on consecutive cycles up to MAX_REPEAT_ALERTS, then goes quiet.
    //    Insistent, never infinite spam; re-armed when price pulls back off the target.
    //  • STOP-LOSS (danger) — B0.3c says "the app never gives up": while the position is
    //    still open and below its stop, it MUST keep escalating (buzz + voice) every few
    //    minutes with live growing-loss numbers, and stop ONLY when the user marks the
    //    trade closed — never merely because the price bobbed back for a cycle. So stop
    //    alerts are throttled by wall-clock time (not capped by a count), the first-breach
    //    loss is remembered as the "grown from ₹X" baseline, and both stop maps are cleared
    //    solely by the close-pruning below — not on a price recovery.
    private val targetAlertCount = mutableMapOf<String, Int>()    // trade id -> target buzzes so far
    private val stopBaselineLoss = mutableMapOf<String, Double>() // trade id -> loss (₹) at first breach = the "from ₹X"
    private val stopLastAlertMs = mutableMapOf<String, Long>()    // trade id -> last stop-loss alert time (throttle)
    // #5 FEED SELF-START: the open-trade symbol set the live feed was last started for,
    // so we only (re)start it when that set actually changes.
    private var lastFeedSymbols: Set<String> = emptySet()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val note = ongoing("Watching your open trades…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(FG_ID, note, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(FG_ID, note)
        }
        if (job == null) job = scope.launch { watchLoop() }
        return START_STICKY
    }

    private suspend fun watchLoop() {
        var yahooPrices = emptyMap<String, Double>()   // last good backup snapshot
        var lastYahooFetchMs = 0L
        while (scope.isActive) {
            // REAL trades only — practice is fake money and must never buzz "sell now".
            val open = runCatching { tracker.getOpenOnce(practice = false) }.getOrElse { emptyList() }
            if (open.isEmpty()) { stopSelf(); return }

            // The user acted on (closed) any trade no longer open — drop its alert
            // bookkeeping so nothing lingers or mis-fires when ids are reused. For the
            // stop-loss escalation this close-pruning is the ONLY thing that stops it
            // (B0.3c: "stops only when the user marks the position closed").
            val openIds = open.mapTo(HashSet()) { it.id }
            targetAlertCount.keys.retainAll(openIds)
            stopBaselineLoss.keys.retainAll(openIds)
            stopLastAlertMs.keys.retainAll(openIds)

            // Prices only move while the market is open — don't drain battery/data
            // polling Yahoo overnight. Sleep long and re-check.
            if (!isMarketHours()) { delay(CLOSED_INTERVAL_MS); continue }

            val symbols = open.map { it.symbol }.distinct()
            val now = System.currentTimeMillis()

            // #5 FEED SELF-START: on a process restart with the app still closed,
            // InterdayViewModel never runs, so nobody starts the live feed and this
            // watcher is stuck on Yahoo-only. When SmartAPI keys are present, start the
            // feed ourselves for exactly the open-trade symbols, so real-tick protection
            // resumes without the app being opened. Idempotent + guarded; if it can't
            // start, the Yahoo path below still protects. Only (re)started when the
            // open-symbol set changes, to avoid churning the feed the UI shares. Not
            // stopped here — the app (InterdayViewModel) may still be using the feed;
            // it dies naturally with the process once the FGS stops.
            val symbolSet = symbols.toSet()
            if (symbolSet != lastFeedSymbols) {
                val configured = runCatching { smartApiStore.current().isComplete }.getOrDefault(false)
                if (configured) {
                    runCatching { feed.start(symbols) }.onSuccess { lastFeedSymbols = symbolSet }
                }
            }

            // Yahoo backup refresh every ~90s (keep the last snapshot if a fetch fails).
            if (now - lastYahooFetchMs >= CHECK_INTERVAL_MS) {
                lastYahooFetchMs = now
                yahooPrices = runCatching { marketRepo.fetchPricesForSymbols(symbols) }
                    .getOrElse { yahooPrices }
            }

            // Prefer the Angel One real-time tick per symbol while the feed is connected;
            // any symbol without a live tick falls back to the Yahoo snapshot.
            val liveOn = feed.connected.value
            val live = if (liveOn) feed.livePrices.value else emptyMap()
            val prices = if (live.isEmpty()) yahooPrices else yahooPrices + live.filterKeys { it in symbols }
            for (t in open) {
                val px = prices[t.symbol] ?: continue
                val stopHit = t.stopLossHit(px)
                val targetHit = t.target1Hit(px)
                when {
                    // Stop-loss (danger): B0.3c "the app never gives up". While this
                    // position is still open and below its stop, keep buzzing AND speaking
                    // on a throttled every-few-minutes cadence — NO count cap — with live
                    // growing-loss numbers, until the user marks the trade closed (the
                    // close-pruning above is the only thing that finally stops it).
                    stopHit -> {
                        val nowMs = System.currentTimeMillis()
                        val loss = (t.entryPrice - px) * t.quantity
                        val baseline = stopBaselineLoss[t.id]
                        val lastMs = stopLastAlertMs[t.id]
                        if (baseline == null) {
                            // First breach: remember the "from ₹X" baseline and alert now.
                            stopBaselineLoss[t.id] = loss
                            stopLastAlertMs[t.id] = nowMs
                            notifier.alertStopLossLive(t.symbol, px, "Loss so far ₹${loss.toInt()}.")
                            tts.speakText("Sell ${t.symbol} now. It has fallen to your safety level at ${px.toInt()} rupees.")
                        } else if (lastMs == null || nowMs - lastMs >= STOP_REPEAT_INTERVAL_MS) {
                            // Still breached and open: re-alert with the growing-loss line.
                            stopLastAlertMs[t.id] = nowMs
                            val grown = "Sell now — your loss has grown from ₹${baseline.toInt()} to ₹${loss.toInt()}."
                            notifier.alertStopLossLive(t.symbol, px, grown)
                            tts.speakText("Sell ${t.symbol} now. Your loss has grown from ${baseline.toInt()} to ${loss.toInt()} rupees.")
                        }
                    }
                    // Target (success): profit is there to book, so repeat the alert 2–3
                    // times (the cap), then go quiet — insistent, never infinite spam.
                    targetHit -> {
                        val fired = targetAlertCount[t.id] ?: 0
                        if (fired < MAX_REPEAT_ALERTS) {
                            targetAlertCount[t.id] = fired + 1
                            notifier.alertTargetLive(t.symbol, px)
                            tts.speakText("${t.symbol} reached your target at ${px.toInt()} rupees. Sell in Groww to book your profit.")
                        }
                    }
                }
                // Re-arm the TARGET alert on a pull-back so a genuine fresh target-hit later
                // alerts again. The STOP-LOSS escalation is deliberately NOT cleared here —
                // a price bobbing back off the stop for one cycle must not silence the
                // danger alert (B0.3c); its state is cleared only by the close-pruning above.
                if (!targetHit) targetAlertCount.remove(t.id)
            }
            // With live ticks flowing we can re-check far faster than the Yahoo poll —
            // that is the whole point of wiring the real-time feed into the watcher.
            delay(if (liveOn) LIVE_CHECK_INTERVAL_MS else CHECK_INTERVAL_MS)
        }
    }

    /** NSE trading window in IST: Mon–Fri 9:15–15:30 (holidays ignored — harmless). */
    private fun isMarketHours(): Boolean {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Kolkata"))
        val dow = cal.get(java.util.Calendar.DAY_OF_WEEK)
        if (dow == java.util.Calendar.SATURDAY || dow == java.util.Calendar.SUNDAY) return false
        val min = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
        return min in (9 * 60 + 15)..(15 * 60 + 30)
    }

    // Android 14+ caps a dataSync foreground service to ~6h/day, then calls this.
    // Stop cleanly instead of being force-killed — and TELL the user, because the
    // ongoing "Watching…" note dies with the service and silence would look like safety.
    override fun onTimeout(startId: Int) {
        notifyWatchPaused()
        stopSelf()
    }

    // Android 15 delivers the typed overload for dataSync; same clean stop.
    override fun onTimeout(startId: Int, fgsType: Int) {
        onTimeout(startId)
    }

    /**
     * Tap-to-open (U2.1/U4.3): every note this service posts opens the app when
     * tapped — "Open the app" text with a dead notification strands the user.
     */
    private fun openAppIntent(): android.app.PendingIntent? = try {
        packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            android.app.PendingIntent.getActivity(
                this, 0, launch,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
        }
    } catch (e: Exception) { null }   // fail-safe: alert still shows without a tap action

    /** Visible alert that Android paused the watcher — reopening the app restarts it. */
    private fun notifyWatchPaused() {
        try {
            val builder = NotificationCompat.Builder(this, Constants.CHANNEL_ID_ALERTS)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("Trade watch paused by Android")
                .setContentText("Open the app to resume watching your trades.")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
            openAppIntent()?.let { builder.setContentIntent(it) }
            NotificationManagerCompat.from(this).notify(FG_ID + 1, builder.build())
        } catch (e: SecurityException) { /* notification permission missing */ }
    }

    private fun ongoing(text: String): Notification {
        val builder = NotificationCompat.Builder(this, Constants.CHANNEL_ID_ALERTS)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("AI Stock — protecting your trades")
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        openAppIntent()?.let { builder.setContentIntent(it) }
        return builder.build()
    }

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        Log.d(TAG, "TradeWatchService stopped")
        super.onDestroy()
    }

    companion object {
        private const val TAG = "TradeWatchService"
        private const val FG_ID = 4201
        private const val CHECK_INTERVAL_MS = 45_000L       // ~45s Yahoo poll while market is open
        private const val LIVE_CHECK_INTERVAL_MS = 15_000L  // ~15s re-check when live ticks flow
        private const val CLOSED_INTERVAL_MS = 30 * 60_000L // 30 min while market is closed
        private const val MAX_REPEAT_ALERTS = 3               // TARGET-hit only: insist 2–3 times, then go quiet (C15a)
        private const val STOP_REPEAT_INTERVAL_MS = 3 * 60_000L // stop-loss re-alert cadence: every few minutes (B0.3c)

        fun start(context: Context) {
            val i = Intent(context, TradeWatchService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
            else context.startService(i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TradeWatchService::class.java))
        }
    }
}
