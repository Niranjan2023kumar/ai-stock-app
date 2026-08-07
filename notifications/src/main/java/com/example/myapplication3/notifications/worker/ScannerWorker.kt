package com.example.myapplication3.notifications.worker

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.example.myapplication3.core.common.MarketCalendar
import com.example.myapplication3.database.dao.TrackedTradeDao
import com.example.myapplication3.news.repository.NewsRepository
import com.example.myapplication3.notifications.manager.StockNotificationManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import javax.inject.Named

@HiltWorker
class ScannerWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val newsRepository: NewsRepository,
    private val trackedTradeDao: TrackedTradeDao,
    private val notificationManager: StockNotificationManager,
    private val marketCalendar: MarketCalendar,
    private val dataStore: DataStore<Preferences>,
    @Named("finnhubApiKey") private val finnhubKey: String
) : CoroutineWorker(appContext, workerParams) {

    private val priceClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    override suspend fun doWork(): Result {
        if (!marketCalendar.isMarketOpen()) return Result.success()

        return try {
            // A blank/placeholder Finnhub key guarantees a 401 — skip the news
            // refresh entirely instead of burning a dead network call every scan
            // (same guard AiAnalysisEngine uses for its cloud keys).
            if (hasUsableNewsKey()) newsRepository.refreshMarketNews()
            checkExitAlerts()
            checkDailyLossLimit()
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    private fun hasUsableNewsKey(): Boolean =
        finnhubKey.isNotBlank() && !finnhubKey.startsWith("your_")

    /**
     * Backup stop-loss/target check for the user's REAL open trades — reads
     * tracked_trades, the table TradeTrackerRepository actually writes (B0.2b),
     * so this scan protects the same positions the foreground watcher does.
     * Alert-only: the user sells in Groww (B0.4); the worker never closes a trade.
     */
    private suspend fun checkExitAlerts() {
        val openTrades = try {
            // Real money only — practice trades must never buzz "sell now" (F5).
            trackedTradeDao.getOpenOnce(practice = false)
        } catch (e: Exception) {
            return
        }
        if (openTrades.isEmpty()) return

        val symbols = openTrades.map { it.symbol }.distinct()
        val priceMap = fetchLivePrices(symbols)
        if (priceMap.isEmpty()) return

        openTrades.forEach { trade ->
            val currentPrice = priceMap[trade.symbol] ?: return@forEach
            if (currentPrice <= 0.0) return@forEach

            // Tracked trades are long-only (action = BUY) — see TrackedTradeEntity.
            when {
                trade.stopLoss > 0.0 && currentPrice <= trade.stopLoss -> {
                    val loss = (trade.entryPrice - currentPrice) * trade.quantity
                    notificationManager.alertStopLossLive(
                        trade.symbol, currentPrice, "Loss so far ₹${loss.toInt()}."
                    )
                }
                trade.target1 > 0.0 && currentPrice >= trade.target1 -> {
                    notificationManager.alertTargetLive(trade.symbol, currentPrice)
                }
            }
        }
    }

    /**
     * Daily-loss check from today's REALIZED P/L (closed real trades since IST
     * midnight), using the EXACT limit the in-app gate enforces (E3e/E5):
     * InterdayViewModel.dailyLossLimit() = effectiveCapital × the user's OWN
     * dailyLoss% (Settings), floor ₹100 — where effectiveCapital is today's
     * check-in capital ("daily_capital") when set, else the Settings capital
     * ("pref_capital"). No ₹1,000 floor: that floor was bigger than a small
     * user's whole day-capital, so a ₹2,000 user got blocked in-app around
     * ₹100-200 but saw no background warning until ₹800-1,000.
     */
    private suspend fun checkDailyLossLimit() {
        try {
            val realized = trackedTradeDao.realizedSinceOnce(istTodayStartMs(), practice = false)
            val dailyLoss = -realized.coerceAtMost(0.0)   // rupees lost today; 0 when in profit
            if (dailyLoss <= 0.0) return

            // Same DataStore file ("stock_intelligence_prefs", di/AppModule) and same
            // keys InterdayViewModel + UserSettingsStore use. On a read failure fall
            // back to UserSettingsStore's defaults — the in-app gate's initial state.
            val prefs = try {
                dataStore.data.first()
            } catch (e: Exception) {
                null
            }
            val dayCapital = prefs?.get(CAPITAL_KEY) ?: 0
            val settingsCapital = prefs?.get(SETTINGS_CAPITAL_KEY) ?: DEFAULT_CAPITAL
            val lossPercent = prefs?.get(DAILY_LOSS_PCT_KEY) ?: DEFAULT_DAILY_LOSS_PCT

            // InterdayViewModel.effectiveCapital(): today's check-in wins, else Settings.
            val effectiveCapital = if (dayCapital > 0) dayCapital.toDouble() else settingsCapital
            val limit = (effectiveCapital * lossPercent / 100.0).coerceAtLeast(100.0)

            val usedPercent = dailyLoss / limit
            when {
                usedPercent >= 1.0 -> notificationManager.notifyDailyLimitBreached(dailyLoss, limit)
                usedPercent >= 0.8 -> notificationManager.notifyDailyLimitWarning(dailyLoss, limit)
            }
        } catch (_: Exception) {}
    }

    /** Midnight today in IST — P/L days are Asia/Kolkata days, not device-zone days. */
    private fun istTodayStartMs(): Long =
        Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata")).apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    // Yahoo's v7 batch quote endpoint returns 401 without a cookie+crumb, so we
    // fetch one v8 chart per open-trade symbol instead (a handful at most) and
    // read meta.regularMarketPrice — the same fallback the UI paths use.
    private fun fetchLivePrices(symbols: List<String>): Map<String, Double> {
        val prices = mutableMapOf<String, Double>()
        for (raw in symbols.distinct()) {
            try {
                val sym = if (raw.endsWith(".NS")) raw else "$raw.NS"
                val url = "https://query1.finance.yahoo.com/v8/finance/chart/$sym" +
                        "?interval=1d&range=1d"
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .header("Accept", "application/json")
                    .build()
                val response = priceClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    response.close()
                    continue
                }
                val body = response.body?.string() ?: continue
                val meta = JSONObject(body)
                    .getJSONObject("chart")
                    .getJSONArray("result")
                    .getJSONObject(0)
                    .getJSONObject("meta")
                val price = meta.optDouble("regularMarketPrice", 0.0)
                if (price > 0) prices[raw] = price
            } catch (_: Exception) {
                // One bad symbol must not sink the rest — skip and keep going.
            }
        }
        return prices
    }

    companion object {
        const val WORK_NAME = "scanner_worker"

        /** InterdayViewModel's "money for today" preference key (F3) — read-only here. */
        private val CAPITAL_KEY = intPreferencesKey("daily_capital")

        // UserSettingsStore's keys/defaults (app module — not a dependency of this
        // module, so the names/values are mirrored; they are legacy-stable, written
        // by the Settings screen since day one and documented in UserSettingsStore).
        private val SETTINGS_CAPITAL_KEY = doublePreferencesKey("pref_capital")
        private val DAILY_LOSS_PCT_KEY = doublePreferencesKey("pref_max_daily_loss")
        private const val DEFAULT_CAPITAL = 100_000.0      // UserSettingsStore.DEFAULT_CAPITAL
        private const val DEFAULT_DAILY_LOSS_PCT = 3.0     // UserSettingsStore.DEFAULT_DAILY_LOSS_PCT

        // 15 minutes is WorkManager's minimum periodic interval (anything lower
        // is silently clamped up to it) — matches locked decision #2's ~15-min
        // backup scan behind the foreground TradeWatchService.
        fun buildPeriodicRequest(): PeriodicWorkRequest =
            PeriodicWorkRequestBuilder<ScannerWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
    }
}
