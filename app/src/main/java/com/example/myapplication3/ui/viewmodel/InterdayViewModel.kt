package com.example.myapplication3.ui.viewmodel

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication3.intraday.InterdayUiState
import com.example.myapplication3.intraday.IntradayRepository
import com.example.myapplication3.intraday.MarketHealth
import com.example.myapplication3.intraday.MarketMove
import com.example.myapplication3.intraday.PendingOrder
import com.example.myapplication3.intraday.PriceFilterType
import com.example.myapplication3.intraday.RiskGuard
import com.example.myapplication3.intraday.RiskLevel
import com.example.myapplication3.intraday.SignalAction
import com.example.myapplication3.intraday.SignalEngine
import com.example.myapplication3.intraday.StockScreenerItem
import com.example.myapplication3.intraday.TradingSignal
import com.example.myapplication3.intraday.buyChancePassed
import com.example.myapplication3.groww.OrderType
import com.example.myapplication3.settings.RiskSettings
import com.example.myapplication3.settings.UserSettingsStore
import com.example.myapplication3.portfolio.repository.PortfolioRepository
import com.example.myapplication3.tracking.TrackedTrade
import com.example.myapplication3.tts.HindiTtsManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject

/**
 * Which loss limit blocked trading (E3e/B6) — so TradingScreen can say the RIGHT
 * thing: "today's loss limit" vs "this week's loss limit". WEEKLY wins when both
 * are breached, because it is the longer-lasting block (the daily limit resets
 * at midnight but the weekly one still keeps trading off).
 */
enum class LossLimitType { DAILY, WEEKLY }

/**
 * P0 #3 (C7a / U4.2) — the daily "signs of falling" re-score for a stock the user
 * OWNS. My Stocks used to sit on HOLD until the stop-loss physically hit; this
 * verdict lets the app warn EARLIER, the moment the setup dies. Built ONLY from
 * real signal data already fetched (the app's own SELL signal, or a broken trend
 * with falling momentum). The card still owns the price-vs-levels target/stop
 * check (it holds the live price); this only adds the earlier deterioration
 * warning. A symbol with no verdict here means "no sell signal" — the card keeps
 * its existing price-vs-levels HOLD/SELL (honesty B6: no data, no claim).
 */
data class OwnedStockVerdict(
    /** true = the setup has died → advise protecting the profit by selling. */
    val sell: Boolean,
    /** Plain-English line shown on the card AND spoken (voice-parity U1.5). */
    val message: String
)

@HiltViewModel
class InterdayViewModel @Inject constructor(
    private val repository: IntradayRepository,
    private val portfolioRepository: PortfolioRepository,
    private val tradeTracker: com.example.myapplication3.tracking.TradeTrackerRepository,
    // H13 auto-caution inputs: the recorder supplies the honest last-10 win rate,
    // the tracker supplies the sample COUNT so the app never judges itself on
    // fewer than 6 real outcomes (recentWinRate()'s own floor is 5).
    private val outcomeRecorder: com.example.myapplication3.tracking.OutcomeRecorder,
    private val performanceTracker: com.example.myapplication3.analytics.tracker.PerformanceTracker,
    // E3a simple-trust line for the hero pick ("This kind of pick was right 7
    // out of 10 times last year") — suspend + per-day cached inside the provider.
    private val trustLineProvider: com.example.myapplication3.intraday.TrustLineProvider,
    // Honest suggestion record (U9.6/A5): the day's SHOWN suggestions are appended
    // once per IST day into TWO separate Excel-openable CSVs (Stock tab / Intraday
    // tab — never mixed) and judged PASS/FAIL after close by LedgerEvaluationWorker.
    // Fail-safe (B8): every call runs in its own coroutine under runCatching, so a
    // ledger error can never touch a trading flow.
    private val suggestionLedger: com.example.myapplication3.ledger.SuggestionLedger,
    private val smartApiFeed: com.example.myapplication3.smartapi.SmartApiFeed,
    private val smartApiStore: com.example.myapplication3.smartapi.SmartApiStore,
    private val userSettings: UserSettingsStore,
    private val ttsManager: HindiTtsManager,
    private val dataStore: DataStore<Preferences>,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context
) : ViewModel() {

    // ── The user's real money settings (E5) — sizing & loss limits read HERE,
    // never from hardcoded ₹10,000/₹1,000 defaults (I3/H6).
    val riskSettings: StateFlow<RiskSettings> =
        userSettings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, RiskSettings())

    // ── Real-time feed (Angel One SmartAPI) — live prices when configured ────
    val livePrices: StateFlow<Map<String, Double>> = smartApiFeed.livePrices
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())
    val liveConnected: StateFlow<Boolean> = smartApiFeed.connected
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // ── Intraday-powered signals (H14) — honesty flag only, no UI change ─────
    // true ONLY when the signals currently shown were computed WITH real Angel
    // One 5-minute candle reads in this refresh cycle. The UI can later badge
    // "Using live 5-minute data" from this — and because it is false whenever
    // reads were unavailable (no Angel One / market closed / any error), that
    // badge can never lie (B6). Nothing else in the app keys off this flag, so
    // the no-Angel-One experience is byte-for-byte unchanged (B8).
    private val _intradayPowered = MutableStateFlow(false)
    val intradayPowered: StateFlow<Boolean> = _intradayPowered.asStateFlow()

    // ── AUTO-CAUTION (H13): the app honestly judges ITSELF ───────────────────
    // When the win rate over the last 10 REAL closed outcomes falls below 30%
    // (and only with >= 6 real outcomes — never judged on thin data), the app
    // enters caution mode: at most ONE BUY (the top pick) is shown per list and
    // the UI gets a plain-words message to render. Recomputed once per fetch
    // cycle; fail-safe — any storage error means "no caution", never a crash.
    private val _cautionActive = MutableStateFlow(false)
    private val _cautionMessage = MutableStateFlow<String?>(null)
    /** Non-null while caution mode is active. Null = render nothing. */
    val cautionMessage: StateFlow<String?> = _cautionMessage.asStateFlow()

    // ── TRUST LINE (E3a): honest track record for the hero pick ─────────────
    // One plain sentence ("This kind of pick was right 7 out of 10 times last
    // year") computed from the hero pick's real 1-year closes, cached per day
    // inside TrustLineProvider. Null = nothing honest to say = render NOTHING.
    private val _heroTrustLine = MutableStateFlow<String?>(null)
    val heroTrustLine: StateFlow<String?> = _heroTrustLine.asStateFlow()

    // ── TRUST LINE for the INTRADAY top pick (E3a/H13/C24) ──────────────────
    // The SAME honest sentence, computed for the Intraday tab's own top signal
    // (a different stock than the Stock hero above). Stored WITH its symbol so
    // the UI can refuse to show a stale line under a changed pick. Null = nothing
    // honest to say = render NOTHING (never fabricated).
    private val _intradayTopTrustLine = MutableStateFlow<Pair<String, String>?>(null)
    val intradayTopTrustLine: StateFlow<Pair<String, String>?> = _intradayTopTrustLine.asStateFlow()

    // ── Wall-clock freshness (B2/U1.6/U5.3) ─────────────────────────────────
    // Epoch of the currently-shown prices: the last successful fetch, or the
    // cache timestamp on a cold open / failed refresh. The UI freshness line and
    // the voice guard treat prices older than PRICE_STALE_AFTER_MS as stale
    // REGARDLESS of isUsingCachedData, mirroring the Stock header — so old prices
    // can never briefly read green/"fresh" nor be spoken as a live buy.
    private val _lastPriceEpochMs = MutableStateFlow(0L)
    val lastPriceEpochMs: StateFlow<Long> = _lastPriceEpochMs.asStateFlow()

    // True only when the LAST failed refresh was a real network loss — so the
    // stale-data voice blames "No internet" only when that is truly the cause,
    // and otherwise speaks the cause-neutral "prices are old" line (B2/U9.5).
    private var lastRefreshWasNoInternet = false

    // ── Data-saver (E3d): auto-refresh runs ONLY while the app is visible ─────
    // The 30s loop used to keep fetching quotes while the app was backgrounded,
    // silently burning the user's mobile data all day. lifecycle-process is not a
    // dependency, so foreground state is tracked here via Application activity
    // lifecycle callbacks (Hilt's @ApplicationContext IS the Application) — no
    // Activity/other-file change required. Starts true so the first-load fetch
    // is never blocked.
    private val _isForeground = MutableStateFlow(true)
    val isForeground: StateFlow<Boolean> = _isForeground.asStateFlow()

    /** Optional external hook (e.g. a lifecycle observer in the UI layer). */
    fun setForeground(foreground: Boolean) { _isForeground.value = foreground }

    private var startedActivityCount = 0
    private val foregroundTracker = object : android.app.Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: android.app.Activity) {
            startedActivityCount++
            _isForeground.value = true
        }
        override fun onActivityStopped(activity: android.app.Activity) {
            startedActivityCount = (startedActivityCount - 1).coerceAtLeast(0)
            if (startedActivityCount == 0) _isForeground.value = false
        }
        override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {}
        override fun onActivityResumed(activity: android.app.Activity) {}
        override fun onActivityPaused(activity: android.app.Activity) {}
        override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) {}
        override fun onActivityDestroyed(activity: android.app.Activity) {}
    }

    companion object {
        private const val TAG = "InterdayVM"
        // FIX: 30s during market hours (was 5 min)
        private const val AUTO_REFRESH_MS = 30 * 1000L
        private const val CLOSED_REFRESH_MS = 30 * 60 * 1000L
        // B2 wall-clock staleness: prices older than this read amber "Old price"
        // and the voice refuses buy/sell advice — the same 5-min bar the Stock
        // header uses (HomeScreen.INDEX_STALE_AFTER_MS), regardless of the cached flag.
        private const val PRICE_STALE_AFTER_MS = 5 * 60 * 1000L
        // "Lowest/Highest" screener chips keep stocks within this % of the period extreme
        private const val NEAR_EXTREME_PCT = 5.0
        // P0 #3 (C7a/U4.2): the one plain-English line for an owned stock whose setup
        // has died — shown on MyStockCard AND spoken by its SpeakButton (voice-parity).
        private const val SELL_FALLING_MSG = "Sell today — signs of falling, protect your profit."
    }

    private val _uiState = MutableStateFlow(InterdayUiState())
    val uiState: StateFlow<InterdayUiState> = _uiState.asStateFlow()

    // ── Live price fed INTO the decision (C12a / H4) ─────────────────────────────
    // livePrices was collected but only displayed. The price the app REASONS about
    // for a signal must be the real-time Angel One tick when the feed is on, else the
    // snapshot the signal already carries. Same key convention the UI uses (symbol
    // without .NS, upper-case). Returns null when not live so callers fall back to
    // the snapshot — a stale tick is never presented as a live price (B6 honesty).
    fun effectivePriceFor(symbol: String): Double? =
        if (liveConnected.value)
            livePrices.value[symbol.removeSuffix(".NS").uppercase()]?.takeIf { it > 0.0 }
        else null

    /**
     * Today's picks with the LIVE price merged into currentPrice, so the stale-entry
     * guard actually fires (spec C12a): because currentPrice now carries the real-time
     * price, TradingSignal.buyChancePassed() (and the SignalEngine live-price path a
     * parallel agent is adding) both see the true price and can flag a chased/dead
     * entry as "this chance passed" instead of telling the user to buy at a run-up.
     * The FIXED entry zone (entryLow/entryHigh) is left untouched, so anchoring holds.
     * Falls back to the snapshot when the feed is off. Additive — uiState.dailyPicks
     * is unchanged; the UI can render this flow directly.
     */
    val dailyPicksLive: StateFlow<List<TradingSignal>> =
        combine(uiState, livePrices, liveConnected) { state, live, connected ->
            if (!connected) state.dailyPicks
            else state.dailyPicks.map { p ->
                val px = live[p.stockSymbol.removeSuffix(".NS").uppercase()]?.takeIf { it > 0.0 }
                if (px != null) p.copy(currentPrice = px) else p
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ── Practice vs Real mode (F5, B0.3b) ────────────────────────────────────
    // FIRST-TIME USERS DEFAULT TO PRACTICE (panel fix): an unset preference reads
    // as practice=true (and init persists it), so a brand-new user can never place
    // a real-money order before consciously switching real mode ON. The safe-side
    // initial value is also true — flashing PRACTICE at a real user for a moment
    // is harmless; flashing REAL at a practice user is not.
    private val practiceKey = booleanPreferencesKey("practice_mode")
    val practiceMode: StateFlow<Boolean> =
        dataStore.data.map { it[practiceKey] ?: true }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    fun setPracticeMode(on: Boolean) {
        viewModelScope.launch { dataStore.edit { it[practiceKey] = on } }
    }

    // ── Trade tracking (F1): P/L + open positions for the CURRENT mode ────────
    @OptIn(ExperimentalCoroutinesApi::class)
    val todayRealizedPnl: StateFlow<Double> =
        practiceMode.flatMapLatest { tradeTracker.observeTodayRealized(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    @OptIn(ExperimentalCoroutinesApi::class)
    val openTrades: StateFlow<List<TrackedTrade>> =
        practiceMode.flatMapLatest { tradeTracker.observeOpen(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ── P0 #3: signs-of-falling SELL re-score for OWNED stocks (C7a / U4.2) ──────
    // My Stocks used to show HOLD until the stop-loss physically hit. This re-scores
    // each owned position on every cycle from the SAME real signal data already
    // fetched — never new numbers — so the card can warn the user to protect their
    // profit BEFORE the setup fully collapses. The map is keyed by normalized symbol
    // and contains ONLY stocks the app now wants sold; a symbol that is absent means
    // "no sell signal", so MyStockCard keeps its price-vs-levels HOLD/SELL (B6).
    val myStockVerdicts: StateFlow<Map<String, OwnedStockVerdict>> =
        combine(openTrades, uiState) { trades, state ->
            if (trades.isEmpty()) emptyMap<String, OwnedStockVerdict>()
            else {
                // The app's own actionable signal (BUY/SELL) and the real daily quote
                // (carries the 50-day line + today's % move) for each owned symbol.
                val signalBySym = state.signals.associateBy { normSym(it.stockSymbol) }
                val quoteBySym  = state.allScreenerItems.associateBy { normSym(it.symbol) }
                buildMap {
                    trades.forEach { t ->
                        val key = normSym(t.symbol)
                        ownedStockVerdict(signalBySym[key], quoteBySym[key])?.let { put(key, it) }
                    }
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** Same normalization MyStockCard uses for its live-price lookup: drop .NS, upper-case. */
    private fun normSym(symbol: String): String = symbol.removeSuffix(".NS").uppercase()

    /**
     * P0 #3 part (c): the SIGNAL-based half of the owned-stock verdict. Returns a
     * SELL verdict when the app's own current signal for this symbol has turned SELL,
     * OR its momentum + trend have rolled over — price below its 50-day line (trend
     * broken) while it is falling today (momentum down), the classic "setup died".
     * Returns null when there is no real signal/quote data to judge OR the position
     * still looks fine, so MyStockCard falls back to its price-vs-levels verdict.
     * Uses ONLY real fetched data (A5/B6) — no synthetic values, no crash path.
     */
    private fun ownedStockVerdict(
        signal: TradingSignal?,
        quote: StockScreenerItem?
    ): OwnedStockVerdict? {
        // (c1) the engine's own current signal for this stock has turned SELL.
        if (signal != null && signal.action == SignalAction.SELL)
            return OwnedStockVerdict(sell = true, message = SELL_FALLING_MSG)
        // (c2) momentum + trend rolled over, from the real daily quote.
        if (quote != null) {
            val trendBroken  = quote.ma50 > 0.0 && quote.currentPrice > 0.0 && quote.currentPrice < quote.ma50
            val momentumDown = quote.changePercent < 0.0
            if (trendBroken && momentumDown)
                return OwnedStockVerdict(sell = true, message = SELL_FALLING_MSG)
        }
        // Have data but it is holding fine, or no data at all → no sell verdict.
        return null
    }

    /** After "Order placed? Yes" + the user's real fill price (B0.2b): record & watch it. */
    fun trackTrade(signal: TradingSignal, fillPrice: Double, orderType: OrderType, quantity: Int) {
        if (fillPrice <= 0.0 || quantity < 1) return
        val practice = practiceMode.value
        viewModelScope.launch {
            tradeTracker.open(
                symbol = signal.stockSymbol, name = signal.stockName, orderType = orderType,
                entryPrice = fillPrice, quantity = quantity,
                stopLoss = signal.stopLoss, target1 = signal.targetPrice,
                target2 = signal.target2, target3 = signal.target3, isPractice = practice,
                // P1 learning loop: the 0–100 confidence the engine computed for THIS
                // exact pick (the signal the user is buying) is persisted with the trade
                // so its real outcome lands in the matching calibration bucket.
                confidence = signal.confidence
            )
            if (practice) {
                ttsManager.speakText("Practice order saved. This is not real money.")
            } else {
                // Start the foreground watcher so stop-loss/target alerts arrive (F2).
                // Guarded: Android 12+ throws ForegroundServiceStartNotAllowedException
                // when the app is backgrounded — a crash here would lose the trade save.
                runCatching { com.example.myapplication3.tracking.TradeWatchService.start(appContext) }
                    .onFailure { Log.w(TAG, "Trade watcher start blocked (background?): ${it.message}") }
                ttsManager.speakText("Order saved. I will watch it for you now.")
            }
        }
    }

    fun closeTrackedTrade(id: String, exitPrice: Double) {
        viewModelScope.launch { tradeTracker.close(id, exitPrice) }
    }

    // ── Pending order confirm — survives process death while the user is in Groww ─
    // (locked decision #3 / B0.2b: tracking must never depend on the app staying
    // alive, so the marker lives in DataStore, not in composition state.)

    private val pendingOrderKey = stringPreferencesKey("pending_order_confirm")
    private val PENDING_MAX_AGE_MS = 60 * 60 * 1000L   // stale after 1 hour

    val pendingOrder: StateFlow<PendingOrder?> =
        dataStore.data.map { prefs -> parsePendingOrder(prefs[pendingOrderKey]) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Persist the marker BEFORE Groww opens, so the confirm can never be lost. */
    fun startPendingOrder(
        signal: TradingSignal,
        orderType: OrderType,
        price: Double = signal.currentPrice,
        qty: Int = signal.recommendedQty
    ) {
        val json = org.json.JSONObject()
            .put("symbol", signal.stockSymbol)
            .put("name", signal.stockName)
            .put("orderType", orderType.name)
            .put("price", price)
            .put("qty", qty.coerceAtLeast(1))
            .put("stopLoss", signal.stopLoss)
            .put("target1", signal.targetPrice)
            .put("target2", signal.target2)
            .put("target3", signal.target3)
            // P1 learning loop: carry the engine's 0–100 confidence for THIS pick in the
            // marker so confirmPendingOrder can persist it with the trade. PendingOrder is
            // a shared model we don't own, so the value rides in the JSON, not the object.
            .put("confidence", signal.confidence)
            .put("ts", System.currentTimeMillis())
            .toString()
        viewModelScope.launch { dataStore.edit { it[pendingOrderKey] = json } }
    }

    /** "No" / "I didn't buy" — drop the marker, nothing is tracked. */
    fun clearPendingOrder() {
        viewModelScope.launch { dataStore.edit { it.remove(pendingOrderKey) } }
    }

    /**
     * "Order placed? Yes" + user-confirmed price AND quantity → record the trade
     * (confirmFill corrects the row to the real fill) and start the watcher.
     */
    fun confirmPendingOrder(fillPrice: Double, quantity: Int) {
        val p = pendingOrder.value ?: return
        if (fillPrice <= 0.0 || quantity < 1) return
        val practice = practiceMode.value
        viewModelScope.launch {
            // P1 learning loop: recover the engine's 0–100 confidence saved into the
            // marker at launch (startPendingOrder) so this trade lands in the matching
            // calibration bucket. Read from the raw JSON — PendingOrder carries no
            // confidence field. Old markers (or any parse error) → null, honestly; a
            // guessed number would poison the buckets (B6).
            val pickConfidence = runCatching {
                val raw = dataStore.data.first()[pendingOrderKey]
                org.json.JSONObject(raw ?: "{}").optInt("confidence", -1).takeIf { it in 0..100 }
            }.getOrNull()
            val id = tradeTracker.open(
                symbol = p.symbol, name = p.name,
                orderType = runCatching { OrderType.valueOf(p.orderType) }.getOrDefault(OrderType.DELIVERY),
                entryPrice = p.price, quantity = p.qty.coerceAtLeast(1),
                stopLoss = p.stopLoss, target1 = p.target1,
                target2 = p.target2, target3 = p.target3, isPractice = practice,
                confidence = pickConfidence
            )
            // Correct to what the user REALLY paid/bought — P/L, loss limits and
            // stop-loss alerts must track the true position, not our suggestion.
            tradeTracker.confirmFill(id, fillPrice, quantity)
            dataStore.edit { it.remove(pendingOrderKey) }
            if (practice) {
                ttsManager.speakText("Practice order saved. This is not real money.")
            } else {
                // Guarded like trackTrade: a background FGS start on Android 12+
                // throws — never let it crash the confirm flow.
                runCatching { com.example.myapplication3.tracking.TradeWatchService.start(appContext) }
                    .onFailure { Log.w(TAG, "Trade watcher start blocked (background?): ${it.message}") }
                ttsManager.speakText("Order saved. I will watch it for you now.")
            }
        }
    }

    private fun parsePendingOrder(json: String?): PendingOrder? {
        if (json.isNullOrBlank()) return null
        return runCatching {
            val o = org.json.JSONObject(json)
            val ts = o.optLong("ts", 0L)
            // An hours-old marker is noise, not a real round-trip — ignore it.
            if (System.currentTimeMillis() - ts > PENDING_MAX_AGE_MS) return null
            PendingOrder(
                symbol = o.getString("symbol"),
                name = o.optString("name", o.getString("symbol")),
                orderType = o.optString("orderType", OrderType.DELIVERY.name),
                price = o.optDouble("price", 0.0),
                qty = o.optInt("qty", 1),
                stopLoss = o.optDouble("stopLoss", 0.0),
                target1 = o.optDouble("target1", 0.0),
                target2 = o.optDouble("target2", 0.0),
                target3 = o.optDouble("target3", 0.0),
                timestampMs = ts
            )
        }.getOrNull()
    }

    // ── Daily capital + capital-path (F3, B7b / B0.3a) ───────────────────────
    private val capitalKey = intPreferencesKey("daily_capital")
    private val capitalDateKey = stringPreferencesKey("daily_capital_date")
    private val checkInSkipDateKey = stringPreferencesKey("daily_capital_skip_date")

    // Holiday awareness for the check-in (B7b: skipped on closed days).
    private val marketCalendar = com.example.myapplication3.core.common.MarketCalendar()

    /** The money the user set aside for today (0 = not set yet). */
    val dailyCapital: StateFlow<Int> =
        dataStore.data.map { it[capitalKey] ?: 0 }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /**
     * Ask "how much today?" once per TRADING day (B7b): never on weekends or NSE
     * holidays, and never again after the user answered — or tapped "Not today".
     */
    val needsCapitalCheckIn: StateFlow<Boolean> =
        dataStore.data.map { prefs ->
            val today = istToday()
            val setToday = prefs[capitalDateKey] == today && (prefs[capitalKey] ?: 0) > 0
            val skippedToday = prefs[checkInSkipDateKey] == today
            !setToday && !skippedToday && isWeekday() && !marketCalendar.isHoliday()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // Legacy Settings capital key — kept in lock-step with the check-in below so
    // the two money figures can never disagree (E5/C13, HIGH validation fix).
    private val prefCapitalKey = androidx.datastore.preferences.core.doublePreferencesKey("pref_capital")

    fun setDailyCapital(amount: Int) {
        viewModelScope.launch {
            dataStore.edit {
                it[capitalKey] = amount
                it[capitalDateKey] = istToday()
                // Mirror into pref_capital so Settings (and any worker reading it)
                // always shows the SAME money the tabs size from — one money value.
                it[prefCapitalKey] = amount.toDouble()
            }
        }
    }

    /** "Not today" — the check-in is a question, not a trap (B7b). */
    fun skipCheckInToday() {
        viewModelScope.launch { dataStore.edit { it[checkInSkipDateKey] = istToday() } }
    }

    // ── Loss limits on the current mode's trades (E3e) — protection can't be missed ─
    @OptIn(ExperimentalCoroutinesApi::class)
    private val weekRealizedPnl: StateFlow<Double> =
        practiceMode.flatMapLatest { tradeTracker.observeWeekRealized(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    /** ₹ the user is working with right now: today's check-in, else their Settings capital. */
    private fun effectiveCapital(dayCapital: Int = dailyCapital.value): Double =
        dayCapital.takeIf { it > 0 }?.toDouble() ?: riskSettings.value.capital

    /**
     * Daily loss ceiling from the user's OWN settings (E5): capital × dailyLoss%.
     * No ₹1,000 floor — a floor bigger than a small user's whole day-capital was
     * letting a ₹2,000 user lose 50% before any block (the old bug). ₹100 minimum
     * only guards against a zero limit.
     */
    private fun dailyLossLimit(capital: Int): Double =
        (effectiveCapital(capital) * riskSettings.value.dailyLossPercent / 100.0).coerceAtLeast(100.0)

    /** Weekly loss ceiling from the user's settings: capital × weeklyLoss% (E3e). */
    private fun weeklyLossLimit(capital: Int): Double =
        (effectiveCapital(capital) * riskSettings.value.weeklyLossPercent / 100.0).coerceAtLeast(100.0)

    val dailyLossBreached: StateFlow<Boolean> =
        combine(todayRealizedPnl, dailyCapital, riskSettings) { pnl, cap, _ -> pnl <= -dailyLossLimit(cap) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val weeklyLossBreached: StateFlow<Boolean> =
        combine(weekRealizedPnl, dailyCapital, riskSettings) { pnl, cap, _ -> pnl <= -weeklyLossLimit(cap) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /**
     * WHICH limit is blocking right now (E3e/B6): DAILY, WEEKLY, or null when
     * trading is not blocked. TradingScreen keys its message off this so a user
     * stopped by the WEEKLY limit is never told "try again tomorrow" (a lie —
     * tomorrow is still blocked). WEEKLY takes priority when both are breached.
     */
    val breachedLimitType: StateFlow<LossLimitType?> =
        combine(dailyLossBreached, weeklyLossBreached) { daily, weekly ->
            when {
                weekly -> LossLimitType.WEEKLY
                daily  -> LossLimitType.DAILY
                else   -> null
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // ── Weekly report card (E1a) — trust from honest wins AND losses ─────────
    @OptIn(ExperimentalCoroutinesApi::class)
    val weekReport: StateFlow<com.example.myapplication3.tracking.WeekReport> =
        practiceMode.flatMapLatest { tradeTracker.observeWeekReport(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000),
                com.example.myapplication3.tracking.WeekReport(0, 0, 0.0))

    private fun isWeekday(): Boolean {
        val d = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata")).get(Calendar.DAY_OF_WEEK)
        return d != Calendar.SATURDAY && d != Calendar.SUNDAY
    }

    // ── One-time battery/autostart setup (B0.2d) — so alerts survive OEM killers ─
    private val batteryGuideKey = booleanPreferencesKey("battery_guide_done")
    val batteryGuideDone: StateFlow<Boolean> =
        dataStore.data.map { it[batteryGuideKey] ?: false }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    fun dismissBatteryGuide() {
        viewModelScope.launch { dataStore.edit { it[batteryGuideKey] = true } }
    }

    // Cache raw quotes for recomputing screener on filter change without re-fetching
    private var cachedRawQuotes: List<SignalEngine.StockQuote> = emptyList()

    // Daily-loss voice warning fires ONCE per breach (false→true edge), not every
    // 30-second refresh — ~120 warnings/hour taught users to mute the app (E1).
    private var limitWarningSpoken = false

    // ── Stale-entry protection (C12a): each signal's entry zone is FIXED the first
    // time it appears in a day. Anchors persist in DataStore so process death
    // cannot re-center the zone onto a run-up price.
    private val entryAnchorDateKey = stringPreferencesKey("entry_anchor_date")
    private val entryAnchorsKey = stringPreferencesKey("entry_anchors_json")
    private val entryAnchors = mutableMapOf<String, Pair<Double, Double>>()
    private var anchorsLoaded = false

    private suspend fun ensureAnchorsLoaded() {
        if (anchorsLoaded) return
        anchorsLoaded = true
        runCatching {
            val prefs = dataStore.data.first()
            if (prefs[entryAnchorDateKey] == istToday()) {
                val o = org.json.JSONObject(prefs[entryAnchorsKey] ?: "{}")
                o.keys().forEach { sym ->
                    val arr = o.getJSONArray(sym)
                    entryAnchors[sym] = arr.getDouble(0) to arr.getDouble(1)
                }
            }
        }
    }

    /** Anchor every signal's entry zone to its first-seen values for the day. */
    private suspend fun anchorEntryZones(signals: List<TradingSignal>): List<TradingSignal> {
        ensureAnchorsLoaded()
        var dirty = false
        val out = signals.map { s ->
            val anchor = entryAnchors[s.stockSymbol]
            when {
                anchor != null -> s.copy(
                    entryLow = anchor.first,
                    entryHigh = anchor.second,
                    entryZone = "₹${"%.0f".format(anchor.first)}–₹${"%.0f".format(anchor.second)}"
                )
                s.entryLow > 0.0 && s.entryHigh > 0.0 -> {
                    entryAnchors[s.stockSymbol] = s.entryLow to s.entryHigh
                    dirty = true
                    s
                }
                else -> s
            }
        }
        if (dirty) {
            val o = org.json.JSONObject()
            entryAnchors.forEach { (sym, z) ->
                o.put(sym, org.json.JSONArray().put(z.first).put(z.second))
            }
            val json = o.toString()
            val today = istToday()
            runCatching {
                dataStore.edit { it[entryAnchorDateKey] = today; it[entryAnchorsKey] = json }
            }
        }
        return out
    }

    /**
     * Re-size every signal from the user's REAL money (I3/H6):
     * qty = min(risk-₹ ÷ per-share stop distance, what the capital can afford),
     * floored — 0 means "cannot buy safely" and the UI must say so honestly (B7a),
     * never pretend "you can buy 1 share".
     */
    private fun applyRiskSizing(signals: List<TradingSignal>): List<TradingSignal> {
        val s = riskSettings.value
        val cap = effectiveCapital()
        val riskRs = cap * s.riskPerTradePercent / 100.0
        return signals.map { sig ->
            val stopDist = kotlin.math.abs(sig.currentPrice - sig.stopLoss)
            val afford = if (sig.currentPrice > 0.0) (cap / sig.currentPrice).toInt() else 0
            val riskQty = if (stopDist > 0.0) (riskRs / stopDist).toInt() else 0
            sig.copy(recommendedQty = minOf(afford, riskQty).coerceAtLeast(0))
        }
    }

    /**
     * H13 auto-caution check, run once per fetch cycle. Caution turns ON only when
     * BOTH hold over the durable real-outcome store:
     *  - at least 6 of the last 10 REAL closed outcomes exist (never judge on less;
     *    recentWinRate()'s own floor is 5, so the count is re-checked here), and
     *  - the win rate over those last 10 is below 30%.
     * Fail-safe: any storage error = no caution. Practice trades never count —
     * OutcomeRecorder only ever records real-money closes.
     */
    private suspend fun refreshCautionMode() {
        // H9: 2+ consecutive REAL losses TODAY → immediate psychology pause
        val consecutiveLosses = withContext(Dispatchers.IO) {
            tradeTracker.todayConsecutiveLosses()
        }
        val h9Active = consecutiveLosses >= 2

        // H13: poor recent win rate over last 10 REAL outcomes → system caution
        val h13Active = withContext(Dispatchers.IO) {
            runCatching {
                val recentCount = performanceTracker.getTradeOutcomes()
                    .sortedByDescending { it.closedAt }
                    .take(10)
                    .size
                val rate = outcomeRecorder.recentWinRate()
                recentCount >= 6 && rate != null && rate < 0.30
            }.getOrDefault(false)
        }

        val active = h9Active || h13Active
        _cautionActive.value = active
        _cautionMessage.value = when {
            h9Active ->
                "You have had $consecutiveLosses losses in a row today — better to pause and come back tomorrow with fresh eyes."
            h13Active ->
                "The system is not doing well right now — better to pause today."
            else -> null
        }
    }

    /**
     * H13 caution cap: while caution is active, keep only the single
     * highest-confidence BUY (the top pick) — every other BUY is hidden so a
     * cold-streak system cannot keep pushing buy ideas. SELL/WAIT rows pass
     * through untouched (they warn, they don't tempt). No-op outside caution.
     */
    private fun applyCautionCap(signals: List<TradingSignal>): List<TradingSignal> {
        if (!_cautionActive.value) return signals
        val topBuy = signals.filter { it.action == SignalAction.BUY }
            .maxByOrNull { it.confidence }
        return signals.filter { it.action != SignalAction.BUY || it === topBuy }
    }

    // ── Suggestion record (U9.6/A5): once-per-IST-day append guards ──────────
    // One DataStore date flag PER TAB, so the Stock record and the Intraday
    // record are each written exactly once a day and can never mix.
    private val ledgerStockDateKey = stringPreferencesKey("ledger_stock_logged_date")
    private val ledgerIntradayDateKey = stringPreferencesKey("ledger_intraday_logged_date")

    /**
     * Append today's SHOWN suggestions to the two separate PASS/FAIL records:
     *  • STOCK — the day's daily picks, once per IST day, trading days only
     *    (a weekend/holiday-dated row could never be judged against a real
     *    session's prices — never invented, B6);
     *  • INTRADAY — the top pick + the other BUY chances the Intraday tab
     *    shows, once per IST day, only after the list settles (market open,
     *    signals non-empty).
     * The DataStore date flag is the first guard; the ledger's own
     * per-(date,symbol) idempotence is the second, so a row can never double.
     * Fire-and-forget in its OWN coroutine under runCatching (B8): a ledger
     * error can never delay, cancel or crash the trading flow that called it.
     */
    private fun recordSuggestionsForLedger(
        dailyPicks: List<TradingSignal>,
        intradaySignals: List<TradingSignal>
    ) {
        viewModelScope.launch {
            runCatching {
                val today = istToday()
                val prefs = dataStore.data.first()
                // STOCK tab record — suggestions_stock.csv.
                if (dailyPicks.isNotEmpty() &&
                    prefs[ledgerStockDateKey] != today &&
                    isWeekday() && !marketCalendar.isHoliday()
                ) {
                    suggestionLedger.appendSuggestions(
                        com.example.myapplication3.ledger.SuggestionLedger.TAB_STOCK,
                        dailyPicks
                    )
                    dataStore.edit { it[ledgerStockDateKey] = today }
                }
                // INTRADAY tab record — suggestions_intraday.csv.
                if (intradaySignals.isNotEmpty() &&
                    prefs[ledgerIntradayDateKey] != today &&
                    RiskGuard.isTradingHoursNow()
                ) {
                    val shown = listOf(intradaySignals.first()) +
                        intradaySignals.drop(1).filter { it.action == SignalAction.BUY }
                    suggestionLedger.appendSuggestions(
                        com.example.myapplication3.ledger.SuggestionLedger.TAB_INTRADAY,
                        shown
                    )
                    dataStore.edit { it[ledgerIntradayDateKey] = today }
                }
            }.onFailure { Log.w(TAG, "Suggestion record skipped this cycle: ${it.message}") }
        }
    }

    // Single in-flight fetch — a new refresh cancels the old one so a slow, stale
    // response can never overwrite newer data (last-writer-wins race).
    private var fetchJob: kotlinx.coroutines.Job? = null

    private var lastSpokenSymbols = setOf<String>()
    private var isFirstLoad = true

    init {
        // Foreground tracking for the data-saver gate (E3d) — see foregroundTracker.
        (appContext as? android.app.Application)
            ?.registerActivityLifecycleCallbacks(foregroundTracker)
        // Persist the first-time practice default so EVERY reader of the key
        // (e.g. StockResearchViewModel, which falls back to false) agrees with
        // the practice=true default above from the very first session.
        viewModelScope.launch {
            runCatching {
                if (dataStore.data.first()[practiceKey] == null) dataStore.edit { it[practiceKey] = true }
            }
        }
        loadCachedThenFetch()
        startAutoRefresh()
        startMarketTimer()
        // Resume protection after an app restart: if any trade is still open, the
        // foreground watcher must be running again (F2, "both, auto").
        viewModelScope.launch {
            openTrades.collect { trades ->
                // Only watch REAL trades — practice trades are not real money.
                // Guarded: this collector can fire while the app is backgrounded,
                // where an FGS start throws on Android 12+ — the openTrades collect
                // retries on the next emission / app return, so a skipped start heals.
                if (trades.isNotEmpty() && !practiceMode.value) {
                    runCatching { com.example.myapplication3.tracking.TradeWatchService.start(appContext) }
                        .onFailure { Log.w(TAG, "Trade watcher start blocked (background?): ${it.message}") }
                }
            }
        }
        // Start the real-time SmartAPI feed the moment keys are present — and react
        // if the user saves them later, so the feed goes live without an app restart.
        viewModelScope.launch {
            smartApiStore.isConfigured
                .distinctUntilChanged()
                .collect { configured ->
                    if (configured) runCatching { smartApiFeed.start(repository.watchlistSymbols) }
                    else smartApiFeed.stop()
                }
        }
    }

    /** Show cached data instantly, then fetch live in background. */
    private fun loadCachedThenFetch() {
        viewModelScope.launch {
            val cachedSignals  = repository.loadCachedSignals()
            val cachedHealth   = repository.loadCachedHealth()
            val cachedScreener = repository.loadCachedScreener()
            val cacheTs        = repository.getCacheTimestampMs()

            if (cachedSignals.isNotEmpty() || cachedScreener.isNotEmpty()) {
                Log.d(TAG, "Showing ${cachedSignals.size} cached signals + ${cachedScreener.size} screener items")
                // topMovers derived from the cached screener so the whole tab is instant
                val cachedMovers = cachedScreener
                    .sortedByDescending { kotlin.math.abs(it.changePercent) }
                    .take(12)
                    .map { MarketMove(it.symbol, it.currentPrice, it.changePercent, it.volRatio) }
                // Today's saved picks — instant, and stable across app restarts
                val savedPicks  = repository.loadDailyPicks()
                val cachedDaily = if (savedPicks?.first == istToday()) savedPicks.second else emptyList()
                _uiState.update {
                    it.copy(
                        signals               = cachedSignals,
                        dailyPicks            = cachedDaily,
                        marketHealth          = cachedHealth,
                        allScreenerItems      = cachedScreener,
                        filteredScreenerItems = applyFilter(cachedScreener, it.screenerFilter, it.screenerDays, it.screenerSearchQuery),
                        topMovers             = cachedMovers,
                        isUsingCachedData     = true,
                        // B2 plain words: age in minutes/hours, never a raw
                        // timestamp a zero-knowledge user must do math on.
                        lastUpdated           = staleAgeLabel(cacheTs),
                        isLoading             = true
                    )
                }
                // Wall-clock epoch for the shown (cached) prices — feeds the amber
                // "Old price" fallback and the voice stale-refusal (B2).
                _lastPriceEpochMs.value = cacheTs
            } else {
                _uiState.update { it.copy(isLoading = true) }
            }

            launchFetch(silent = cachedSignals.isNotEmpty())
        }
    }

    fun refresh() {
        val hasCurrent = _uiState.value.signals.isNotEmpty()
        if (!hasCurrent) _uiState.update { it.copy(isLoading = true, error = null) }
        else _uiState.update { it.copy(isLoading = true) }
        launchFetch(silent = hasCurrent)
    }

    /** Cancel any in-flight fetch and start a new one. */
    private fun launchFetch(silent: Boolean) {
        fetchJob?.cancel()
        fetchJob = viewModelScope.launch { doFetch(silent) }
    }

    private suspend fun doFetch(silent: Boolean) = kotlinx.coroutines.coroutineScope {
        Log.d(TAG, "doFetch() silent=$silent")

        // Children of THIS scope — cancelling the fetch job cancels the network calls too
        val fetchDeferred   = async { repository.fetchAll() }
        val summaryDeferred = async {
            runCatching { portfolioRepository.calculateDailySummary() }.getOrNull()
        }
        // FIX: Fetch India VIX in parallel — feeds RiskGuard Rule 7
        val vixDeferred = async {
            runCatching { repository.fetchIndiaVix() }.getOrElse { 0.0 }
        }

        val fetchResult = fetchDeferred.await()
        val summary     = summaryDeferred.await()
        val vix         = vixDeferred.await()

        // H13 AUTO-CAUTION: re-judge the recent REAL track record once per cycle,
        // BEFORE signals are built, so this cycle's lists respect the verdict.
        refreshCautionMode()

        // Use the REAL tracker-based limits (the old portfolioRepository table is never
        // populated, so its breach flag was always false — dead protection). This gates
        // intraday signals off once the user's real daily/weekly loss limit is hit (E3e).
        val isDailyLimitBreached = dailyLossBreached.value || weeklyLossBreached.value
        val dailyLoss    = -todayRealizedPnl.value.coerceAtMost(0.0)
        val maxDailyLoss = dailyLossLimit(dailyCapital.value)

        // Speak the warning ONCE when the limit trips, not on every 30s refresh.
        if (isDailyLimitBreached) {
            if (!limitWarningSpoken) {
                limitWarningSpoken = true
                ttsManager.speakDailyLimitWarning()
            }
        } else {
            limitWarningSpoken = false
        }

        fetchResult.fold(
            onSuccess = { result ->
                // Fixed first-seen entry zones (C12a) + share counts from the user's
                // real money settings (I3/H6) — applied before anything is shown.
                val liveSignals = applyRiskSizing(anchorEntryZones(result.signals))
                // H13 caution cap rides on top of the loss-limit gate: in caution
                // mode only the single best BUY survives (WAIT/SELL rows stay —
                // they are warnings, not temptations).
                val signals = if (isDailyLimitBreached) emptyList() else applyCautionCap(liveSignals)
                val health  = result.health
                cachedRawQuotes = result.rawQuotes          // Fix: store for period recomputation
                // H14 honesty flag: reads used this cycle or not — nothing else
                // changes. Gated on the loss-limit breach too: when signals are
                // blanked by the limit, a "live 5-minute data" badge over an empty
                // list would be describing signals the user cannot see (B6).
                _intradayPowered.value = result.intradayPowered && !isDailyLimitBreached
                speakNewSignals(signals)
                // Daily loss limit breached → NO picks at all. computeDailyPicks reloads
                // today's saved picks from disk, which would silently bypass the loss cap
                // and keep telling the user "BUY" after max loss for the day.
                //
                // Pick basis (H14): the Stock tab is DELIVERY timeframe, so picks are
                // chosen from the daily-only baseline when this cycle's signals carry
                // intraday context. Entry anchors are shared/first-seen-per-day, so
                // both lists end up with identical zones. When reads were unavailable
                // dailySignals == signals and this is exactly the old path (B8).
                // Basis stays UNCAPPED (liveSignals, not the caution-capped list):
                // picks are chosen/saved from the full set; caution only limits
                // what is DISPLAYED below. (When the loss limit is breached this
                // path is skipped entirely, so using liveSignals here is safe.)
                // Delivery geometry now always ships on the Stock tab: dailySignals whenever non-empty, free path included.
                val dailyBasis = if (result.dailySignals.isNotEmpty())
                    applyRiskSizing(anchorEntryZones(result.dailySignals))
                else liveSignals
                // Caution cap applied AFTER computeDailyPicks (which saves the full
                // pick set): the day's 4 stay stable on disk, only the DISPLAY is
                // capped to one BUY — when caution lifts, the same picks return.
                val dailyPicks = if (isDailyLimitBreached) emptyList()
                                 else applyCautionCap(computeDailyPicks(dailyBasis))

                // Apply current screener filter to the newly fetched data.
                // result.screenerItems is computed at the default 20-day period — if the
                // user has a different period selected, recompute so a background refresh
                // doesn't silently revert the screener to 20-day data.
                val currentFilter = _uiState.value.screenerFilter
                val currentDays   = _uiState.value.screenerDays
                val currentQuery  = _uiState.value.screenerSearchQuery
                val screenerItems = if (currentDays != 20 && result.rawQuotes.isNotEmpty()) {
                    repository.computeScreenerItems(result.rawQuotes, if (currentDays == 52) 252 else currentDays)
                } else {
                    result.screenerItems
                }
                val filtered = applyFilter(screenerItems, currentFilter, currentDays, currentQuery)

                Log.d(TAG, "Fetch success: ${signals.size} signals, screener=${result.screenerItems.size}")
                _uiState.update {
                    it.copy(
                        isLoading             = false,
                        signals               = signals,
                        dailyPicks            = dailyPicks,
                        marketHealth          = health,
                        topMovers             = result.topMovers,
                        analyzedCount         = result.analyzedCount,
                        error                 = null,
                        lastUpdated           = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date()),
                        isDailyLimitBreached  = isDailyLimitBreached,
                        dailyLoss             = dailyLoss,
                        maxDailyLoss          = maxDailyLoss,
                        isUsingCachedData     = false,
                        lastFetchFailed       = false,
                        // Clock+holiday truth — v7 marketState is dead, reads CLOSED forever
                        marketClosed          = !RiskGuard.isTradingHoursNow(),
                        allScreenerItems      = screenerItems,
                        filteredScreenerItems = filtered,
                        vix                   = vix    // FIX: real India VIX
                    )
                }
                // Fresh live data (B2): stamp the wall-clock epoch and clear the
                // no-internet cause, so the freshness line reads green and the voice
                // speaks live advice again.
                _lastPriceEpochMs.value = System.currentTimeMillis()
                lastRefreshWasNoInternet = false
                // Suggestion record (U9.6/A5): append today's SHOWN suggestions once
                // per IST day — the lists passed are exactly what the UI renders this
                // cycle (loss-limit blank / caution-capped included), so the record
                // never claims a suggestion the user was not shown. Fire-and-forget,
                // fail-safe (B8) — can never touch this fetch or any trading flow.
                recordSuggestionsForLedger(dailyPicks, signals)
                // E3a: honest trust line for the HERO pick (the first daily pick).
                // Computed AFTER the state update so a slow first computation never
                // delays the signals; cached per symbol per day in the provider.
                // Null (no hero / thin history / any error) = the UI renders nothing.
                val hero = dailyPicks.firstOrNull()
                _heroTrustLine.value = if (hero == null) null else try {
                    val heroKey = hero.stockSymbol.removeSuffix(".NS").uppercase()
                    val closes = result.rawQuotes
                        .firstOrNull { it.symbol.removeSuffix(".NS").uppercase() == heroKey }
                        ?.historicalCloses ?: emptyList()
                    trustLineProvider.trustLineFor(hero.stockSymbol, closes)
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    throw ce   // a cancelled fetch must die, not write stale state
                } catch (_: Exception) {
                    null       // fail-safe: no line beats a wrong line (B6)
                }
                // E3a/H13/C24: the SAME honest track-record line for the INTRADAY
                // top pick (signals.first() — usually a different stock than the
                // hero). Stored with its symbol so a changed pick never shows a
                // stale line; null (no pick / thin history / any error) renders nothing.
                val intradayTop = signals.firstOrNull()
                _intradayTopTrustLine.value = if (intradayTop == null) null else try {
                    val topKey = intradayTop.stockSymbol.removeSuffix(".NS").uppercase()
                    val closes = result.rawQuotes
                        .firstOrNull { it.symbol.removeSuffix(".NS").uppercase() == topKey }
                        ?.historicalCloses ?: emptyList()
                    trustLineProvider.trustLineFor(intradayTop.stockSymbol, closes)
                        ?.let { intradayTop.stockSymbol to it }
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    throw ce
                } catch (_: Exception) {
                    null
                }
            },
            onFailure = { err ->
                Log.e(TAG, "Fetch failed: ${err.message}")
                val keepSignals = _uiState.value.signals
                val usingCache  = keepSignals.isNotEmpty()
                val fallbackHealth = runCatching { repository.fetchMarketHealth() }.getOrElse {
                    // Last real value if we have one; otherwise an explicitly-unavailable
                    // placeholder so the UI shows "data unavailable", not a fake 0.00%.
                    _uiState.value.marketHealth
                        ?: MarketHealth("SIDEWAYS", RiskLevel.MEDIUM, 0.0, "CLOSED", isAvailable = false)
                }
                // B2/U9.5: remember whether THIS failure was a real network loss so
                // the stale-data voice blames "No internet" only when that is the true
                // cause (else it speaks the cause-neutral "prices are old" line).
                val failMsg = err.message.orEmpty()
                lastRefreshWasNoInternet =
                    err is java.net.UnknownHostException ||
                    err is java.net.ConnectException ||
                    failMsg.contains("Unable to resolve host") ||
                    failMsg.contains("Failed to connect")
                // The shown prices are now the cached ones — point the wall-clock epoch
                // at the cache stamp so the amber age label and the stale check agree (B2).
                if (usingCache) _lastPriceEpochMs.value = repository.getCacheTimestampMs()
                _uiState.update {
                    it.copy(
                        isLoading            = false,
                        marketHealth         = fallbackHealth,
                        isDailyLimitBreached = isDailyLimitBreached,
                        dailyLoss            = dailyLoss,
                        maxDailyLoss         = maxDailyLoss,
                        isUsingCachedData    = usingCache,
                        // B2/U1.6: every stale state (cold open AND failed refresh) shows
                        // the SAME plain-words age, never a leftover raw clock time.
                        lastUpdated          = if (usingCache) staleAgeLabel(repository.getCacheTimestampMs()) else it.lastUpdated,
                        lastFetchFailed      = true,
                        marketClosed         = !RiskGuard.isTradingHoursNow(),
                        // Simple-English errors ONLY (A4/B6): raw exception text
                        // (stack messages, URLs, class names) must NEVER reach the UI.
                        error = if (usingCache) null else {
                            val m = err.message.orEmpty()
                            when {
                                err is java.net.UnknownHostException ||
                                err is java.net.ConnectException ||
                                m.contains("Unable to resolve host") ||
                                m.contains("Failed to connect") ->
                                    "No internet. Turn it on and try again."
                                err is java.net.SocketTimeoutException ||
                                m.contains("timeout", ignoreCase = true) ||
                                m.contains("timed out", ignoreCase = true) ->
                                    "Taking too long. Try again."
                                else ->
                                    "Could not get prices. Try again in a minute."
                            }
                        }
                    )
                }
            }
        )
    }

    /** Auto-refresh: 30s during market hours, 30min when closed — foreground only (E3d). */
    private fun startAutoRefresh() {
        viewModelScope.launch {
            while (true) {
                // FIX: Always 30s during market hours for live signal freshness
                val delayMs = if (isMarketHoursNow()) AUTO_REFRESH_MS else CLOSED_REFRESH_MS
                Log.d(TAG, "Auto-refresh in ${delayMs / 1000}s")
                delay(delayMs)
                // Data-saver (E3d): never burn mobile data while backgrounded.
                // Suspend here until the app is visible again — the moment the user
                // returns, this resumes and refreshes immediately (fresh prices).
                _isForeground.first { it }
                // SLOW-3: don't cancel a still-running first-of-day 200-symbol fetch.
                // launchFetch() cancels any in-flight job — if the heavy initial load
                // hasn't finished yet, killing it here means stale data shows forever.
                // Skip this auto-refresh tick instead; the running fetch will complete
                // and post fresh signals on its own.
                if (fetchJob?.isActive == true) continue
                val hasCurrent = _uiState.value.signals.isNotEmpty()
                _uiState.update { it.copy(isLoading = true) }
                launchFetch(silent = hasCurrent)
            }
        }
    }

    override fun onCleared() {
        (appContext as? android.app.Application)
            ?.unregisterActivityLifecycleCallbacks(foregroundTracker)
        super.onCleared()
    }

    // ── Screener filter controls ───────────────────────────────────────────────

    /** Called by UI when user picks a filter chip or changes period. */
    fun setScreenerFilter(type: PriceFilterType, days: Int) {
        val currentState = _uiState.value
        val currentQuery = currentState.screenerSearchQuery

        // Recompute screener items for the new period if data is available
        val screenerItems = if (days != currentState.screenerDays && cachedRawQuotes.isNotEmpty()) {
            repository.computeScreenerItems(cachedRawQuotes, if (days == 52) 252 else days)
        } else {
            currentState.allScreenerItems
        }

        val filtered = applyFilter(screenerItems, type, days, currentQuery)
        _uiState.update {
            it.copy(
                screenerFilter        = type,
                screenerDays          = days,
                allScreenerItems      = screenerItems,
                filteredScreenerItems = filtered
            )
        }
    }

    /** Called by UI when search query changes. */
    fun setScreenerSearchQuery(query: String) {
        val currentState = _uiState.value
        val filtered = applyFilter(
            currentState.allScreenerItems,
            currentState.screenerFilter,
            currentState.screenerDays,
            query
        )
        _uiState.update {
            it.copy(
                screenerSearchQuery   = query,
                filteredScreenerItems = filtered
            )
        }
    }

    private fun applyFilter(
        items: List<StockScreenerItem>,
        filterType: PriceFilterType,
        days: Int,
        query: String
    ): List<StockScreenerItem> {
        var result = items

        // Text search filter
        if (query.isNotBlank()) {
            val q = query.uppercase().trim()
            result = result.filter {
                it.symbol.contains(q) || it.name.uppercase().contains(q)
            }
        }

        // Filter + sort by filter type. "Lowest"/"Highest" must actually FILTER to
        // stocks near their period low/high (within 5%), not just reorder all 200 —
        // fall back to the 15 nearest if nothing is within the threshold.
        result = when (filterType) {
            PriceFilterType.NONE -> result.sortedByDescending { kotlin.math.abs(it.changePercent) }
            PriceFilterType.N_DAY_LOW  -> result
                .filter { it.distFromPeriodLowPct <= NEAR_EXTREME_PCT }
                .sortedBy { it.distFromPeriodLowPct }
                .ifEmpty { result.sortedBy { it.distFromPeriodLowPct }.take(15) }
            PriceFilterType.N_DAY_HIGH -> result
                .filter { it.distFromPeriodHighPct <= NEAR_EXTREME_PCT }
                .sortedBy { it.distFromPeriodHighPct }
                .ifEmpty { result.sortedBy { it.distFromPeriodHighPct }.take(15) }
        }

        return result
    }

    private fun isMarketHoursNow(): Boolean {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata"))
        val day = cal.get(Calendar.DAY_OF_WEEK)
        if (day == Calendar.SATURDAY || day == Calendar.SUNDAY) return false
        val totalMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        return totalMin in (9 * 60 + 10)..(15 * 60 + 35)
    }

    private fun speakNewSignals(signals: List<TradingSignal>) {
        val current = signals.map { it.stockSymbol }.toSet()
        if (isFirstLoad) {
            isFirstLoad = false
            lastSpokenSymbols = current
            return
        }
        signals.filter { it.stockSymbol !in lastSpokenSymbols }.forEach { s ->
            when (s.action) {
                SignalAction.BUY  -> ttsManager.speakBuySignal(s.stockSymbol, s.currentPrice, s.targetPrice, s.stopLoss)
                SignalAction.SELL -> ttsManager.speakSellSignal(s.stockSymbol, s.currentPrice, s.targetPrice, s.stopLoss)
                SignalAction.WAIT -> Unit
            }
        }
        lastSpokenSymbols = current
    }

    // ── Market Timer — ticks every 30s, updates marketTimerLabel ─────────────────
    private fun startMarketTimer() {
        viewModelScope.launch {
            while (true) {
                _uiState.update { it.copy(marketTimerLabel = computeMarketTimerLabel()) }
                delay(30_000L)
            }
        }
    }

    private fun computeMarketTimerLabel(): String {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata"))
        val dow = cal.get(Calendar.DAY_OF_WEEK)
        val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val openMin  = 9 * 60 + 15   // 9:15 AM
        val closeMin = 15 * 60 + 30  // 3:30 PM

        // Holiday truth comes from the calendar, NOT Yahoo's marketState — that v7
        // field is dead (401) and reads CLOSED forever, which made this label say
        // "Market closed today" all through real trading hours.
        val clockSaysOpen = dow != Calendar.SATURDAY && dow != Calendar.SUNDAY && nowMin in openMin until closeMin
        if (clockSaysOpen && !RiskGuard.isTradingHoursNow()) {
            return "Market closed today"   // clock says open but it's an NSE holiday
        }

        if (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY) {
            return "Market opens Monday"
        }
        return when {
            nowMin < openMin -> {
                val remaining = openMin - nowMin
                val h = remaining / 60; val m = remaining % 60
                if (h > 0) "Market opens in ${h}h ${m}m"
                else "Market opens in ${m}m"
            }
            nowMin in openMin until closeMin -> {
                val remaining = closeMin - nowMin
                val h = remaining / 60; val m = remaining % 60
                if (h > 0) "Market closes in ${h}h ${m}m"
                else "Market closes in ${m}m"
            }
            else -> "Market closed"
        }
    }

    // ── AI Trade Tab — Search handler ─────────────────────────────────────────────
    fun setAiSearchQuery(query: String) {
        val q = query.uppercase().trim()
        // Empty query must clear the result — contains("") matches everything and
        // used to duplicate the top pick as a phantom "Found" card (B5).
        if (q.isEmpty()) {
            _uiState.update { it.copy(aiSearchQuery = query, aiSearchResult = null, aiSearchInUniverse = true) }
            return
        }
        val match = _uiState.value.signals.firstOrNull {
            it.stockSymbol.contains(q) || it.stockName.uppercase().contains(q)
        }
        // Honesty (B6): "no signal" is only a verdict for stocks we actually checked.
        val inUniverse = repository.watchlistSymbols.any { it.contains(q) }
        _uiState.update { it.copy(aiSearchQuery = query, aiSearchResult = match, aiSearchInUniverse = inUniverse) }
    }

    fun clearAiSearch() {
        _uiState.update { it.copy(aiSearchQuery = "", aiSearchResult = null, aiSearchInUniverse = true) }
    }

    // (The C13 money calculator is gone — no screen ever called setCapitalInput,
    // and real sizing lives in applyRiskSizing from the user's Settings money.
    // The unused capitalInput/calculatedQty fields still sit in InterdayUiState;
    // that data class lives in TradingSignal.kt, outside this fix's ownership.)

    // ── Daily picks — same 4 stocks all day ──────────────────────────────────────

    private fun istToday(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("Asia/Kolkata")
        return fmt.format(Date())
    }

    /**
     * B2 stale label in plain words: "Old price - from X minutes ago". A raw
     * timestamp ("Cached: 17 Jul 02:31 PM") forces a zero-knowledge user to do
     * clock math; age in minutes/hours is instantly understood.
     */
    private fun staleAgeLabel(tsMs: Long): String {
        if (tsMs <= 0L) return "Old price - from earlier"
        val mins = ((System.currentTimeMillis() - tsMs) / 60_000L).coerceAtLeast(0L)
        val hours = mins / 60
        val days = hours / 24
        return when {
            mins < 1   -> "Old price - from just now"
            mins == 1L -> "Old price - from 1 minute ago"
            mins < 60  -> "Old price - from $mins minutes ago"
            hours == 1L -> "Old price - from 1 hour ago"
            hours < 24 -> "Old price - from $hours hours ago"
            days == 1L -> "Old price - from 1 day ago"
            else       -> "Old price - from $days days ago"
        }
    }

    // ── Sector spread for daily picks (spec H7) ──────────────────────────────────
    // The authoritative symbol→sector classification lives in IntradayRepository
    // (private, no per-symbol accessor), so this is a local mirror of the SAME NSE
    // sector groups. It is FIXED reference data (which sector a company belongs to),
    // never a live/synthetic value — the same kind of fact as the watchlist itself.
    private val pickSectorOf: Map<String, String> = buildMap {
        listOf("HDFCBANK","ICICIBANK","SBIN","AXISBANK","KOTAKBANK","INDUSINDBK","BANKBARODA",
            "AUBANK","BANDHANBNK","FEDERALBNK","IDFCFIRSTB","CANBK","RBLBANK","KARURVYSYA",
            "INDIANB","UNIONBANK","CENTRALBANK").forEach { put(it, "BANKING") }
        listOf("BAJFINANCE","BAJAJFINSV","HDFCLIFE","SBILIFE","SHRIRAMFIN","CHOLAFIN","SBICARD",
            "ICICIGI","MUTHOOTFIN","PFC","RECLTD","IRFC","LTF","MFSL","LICI","LICHSGFIN",
            "CANFINHOME","ABCAPITAL","MOTILALOFS","CAMS","STARHEALTH","PNBHOUSING","IREDA").forEach { put(it, "FINANCIAL_SERVICES") }
        listOf("TCS","INFY","WIPRO","HCLTECH","TECHM","LTIM","LTTS","COFORGE","MPHASIS",
            "PERSISTENT","KPITTECH","OFSS","TATAELXSI","BSOFT","ECLERX").forEach { put(it, "IT") }
        listOf("SUNPHARMA","CIPLA","DRREDDY","DIVISLAB","LUPIN","AUROPHARMA","TORNTPHARM","ALKEM",
            "ZYDUSLIFE","MANKIND","LAURUSLABS","NATCOPHARM","AJANTPHARM","SYNGENE","LALPATHLAB",
            "METROPOLIS","FORTIS","MAXHEALTH","APOLLOHOSP").forEach { put(it, "PHARMA") }
        listOf("MARUTI","TMPV","EICHERMOT","HEROMOTOCO","BAJAJ-AUTO","TVSMOTOR","BALKRISIND",
            "MRF","APOLLOTYRE","CEATLTD","SONACOMS","EXIDEIND","TIINDIA").forEach { put(it, "AUTO") }
        listOf("ITC","HINDUNILVR","NESTLEIND","BRITANNIA","TATACONSUM","DABUR","MARICO","GODREJCP",
            "COLPAL","EMAMILTD","VBL","UBL","RADICO","MCDOWELL-N","NYKAA","DMART","TRENT","PAGEIND").forEach { put(it, "FMCG") }
        listOf("TATASTEEL","JSWSTEEL","HINDALCO","VEDL","SAIL","NMDC","HINDCOPPER","MOIL","APLAPOLLO").forEach { put(it, "METALS") }
        listOf("RELIANCE","ONGC","BPCL","IOCL","GAIL","PETRONET","ATGL").forEach { put(it, "OIL_GAS") }
        listOf("DLF","LODHA","OBEROIRLTY","GODREJPROP").forEach { put(it, "REALTY") }
        listOf("BHARTIARTL","INDUSTOWER","TATACOMM","HFCL").forEach { put(it, "TELECOM") }
        listOf("LT","ADANIPORTS","GMRINFRA","RITES","CONCOR","SIEMENS","BEL","HAL","CUMMINSIND",
            "CGPOWER","POLYCAB","GRINDWELL","SUPREMEIND","ASTRAL","ULTRACEMCO","AMBUJACEM","ACC",
            "JKCEMENT","GRASIM","ADANIENT").forEach { put(it, "INFRA") }
        listOf("NTPC","POWERGRID","TATAPOWER","ADANIGREEN","ADANIPOWER","JSWENERGY","NHPC","SJVN",
            "NLCINDIA","TORNTPOWER").forEach { put(it, "POWER") }
        listOf("PIDILITIND","DEEPAKNTR","AARTIIND","ATUL","FLUOROCHEM","GNFC","GSFC","CHAMBLFERT",
            "PIIND","TATACHEM","SOLARINDS").forEach { put(it, "CHEMICALS") }
        listOf("ZEEL").forEach { put(it, "MEDIA") }
        listOf("TITAN","HAVELLS","VOLTAS","CROMPTON","VGUARD","DIXON","KAJARIACER","RAYMOND").forEach { put(it, "CONSUMER_DURABLES") }
    }

    /**
     * Sector used for pick diversity. A symbol NOT in the map gets a UNIQUE key, so an
     * unlisted stock is never wrongly dropped as a "same-sector" duplicate — only KNOWN
     * same-sector stocks are limited (honesty over silently hiding a good pick, B7a).
     */
    private fun pickSector(symbol: String): String =
        pickSectorOf[symbol.removeSuffix(".NS").uppercase()] ?: "UNIQUE:$symbol"

    /**
     * Requirement 1.1: "daily wise suggest 4 stocks". Once the day's 4 are chosen
     * they stay the SAME all day — only their prices/targets refresh. A beginner
     * must never see picks appear and vanish between refreshes.
     */
    private fun computeDailyPicks(signals: List<TradingSignal>): List<TradingSignal> {
        val today = istToday()
        val saved = repository.loadDailyPicks()
        val picks = if (saved != null && saved.first == today && saved.second.isNotEmpty()) {
            saved.second.map { old ->
                // fresh signal wins; else at least refresh the live price from raw quotes
                signals.firstOrNull { s -> s.stockSymbol == old.stockSymbol }
                    ?: cachedRawQuotes.firstOrNull { q -> q.symbol.removeSuffix(".NS") == old.stockSymbol }
                        ?.let { q -> old.copy(currentPrice = q.price, changePercent = q.changePercent) }
                    ?: old
            }
        } else {
            // Spec H7 — sector spread: never let all 4 picks be the same sector, or one
            // sector crash wipes the user's whole day. Take the highest-confidence signal
            // per sector until 4 distinct-sector picks are chosen. If fewer than 4
            // distinct-sector good signals exist, show only what exists (B7a) — never pad
            // with a same-sector name just to reach 4.
            val chosen = mutableListOf<TradingSignal>()
            val usedSectors = mutableSetOf<String>()
            for (s in signals.sortedByDescending { it.confidence }) {
                val sector = pickSector(s.stockSymbol)
                if (sector in usedSectors) continue
                chosen += s
                usedSectors += sector
                if (chosen.size >= 4) break
            }
            chosen
        }
        if (picks.isNotEmpty()) repository.saveDailyPicks(today, picks)
        return picks
    }

    // ── Voice (simple Stock tab) ─────────────────────────────────────────────────
    /**
     * B2 wall-clock staleness: the shown prices are OLD when the cached flag is set
     * OR when the last price epoch is older than PRICE_STALE_AFTER_MS — the same
     * fallback the Stock header uses, so a ~15-min-old snapshot can never read
     * "fresh" nor be spoken as a live buy just because a failed refresh has not
     * flipped isUsingCachedData yet.
     */
    private fun pricesAreStale(): Boolean {
        if (_uiState.value.isUsingCachedData) return true
        // Wall-clock fallback matters only during trading hours — when the market is
        // closed the prices are honestly the last close (the summary already says so),
        // so old-by-the-clock is expected and must not trigger a stale refusal.
        if (!RiskGuard.isTradingHoursNow()) return false
        val ts = _lastPriceEpochMs.value
        return ts > 0L && System.currentTimeMillis() - ts > PRICE_STALE_AFTER_MS
    }

    /** Speak a pick aloud in Hindi — for users who listen rather than read. */
    fun speakPick(signal: TradingSignal) {
        // Forced honesty (B2): never speak a stale price as if it were live — stale
        // by the cached flag OR by wall-clock age. Blame "No internet" only when that
        // was really the cause, else the cause-neutral "prices are old" line (U9.5).
        if (pricesAreStale()) {
            ttsManager.speakText(
                if (lastRefreshWasNoInternet) "No internet. This is an old price. Do not trust it."
                else "Prices are old. Wait for fresh prices. Do not trust this old price."
            )
            return
        }
        when (signal.action) {
            SignalAction.BUY  -> {
                // Voice must match the card (worst-gap fix): the card refuses a stale
                // buy via chanceGone / buyChancePassed(live price) — the voice must
                // apply the SAME checks, or a listening-only user hears "buy" while
                // the screen says "chance passed".
                val px = effectivePriceFor(signal.stockSymbol) ?: signal.currentPrice
                if (signal.chanceGone || signal.buyChancePassed(px) ||
                    signal.targetPrice <= px || px <= signal.stopLoss)
                    ttsManager.speakText("${signal.stockSymbol}: the chance has passed. Do not buy now.")
                else
                    ttsManager.speakBuySignal(signal.stockSymbol, px, signal.targetPrice, signal.stopLoss)
            }
            SignalAction.SELL -> ttsManager.speakSellSignal(signal.stockSymbol, signal.currentPrice, signal.targetPrice, signal.stopLoss)
            SignalAction.WAIT -> ttsManager.speakText("Wait on ${signal.stockSymbol} now. Wait for a good chance.")
        }
    }

    /** Speak any Hindi text (help dialog, hints). */
    fun speakText(text: String) = ttsManager.speakText(text)

    /**
     * 🔊 One tap → hear the WHOLE day in Hindi: market mood + all 4 picks with
     * buy/sell/stop prices. The zero-reading path for illiterate users.
     */
    fun speakDaySummary() {
        val s = _uiState.value
        // Forced honesty (same rule as speakPick): NEVER speak actionable buy/sell
        // prices from cached/stale data — a listening-only user cannot tell the
        // difference. Stale by the cached flag OR by wall-clock age (B2); blame
        // "No internet" only when that was really the cause (U9.5).
        if (pricesAreStale()) {
            ttsManager.speakText(
                if (lastRefreshWasNoInternet)
                    "No internet. I will not tell you to buy or sell on old prices. Please turn on the internet."
                else
                    "Prices are old. Wait for fresh prices. I will not tell you to buy or sell on old prices."
            )
            return
        }
        val sb = StringBuilder()
        val healthUnavailable = s.marketHealth?.isAvailable == false
        // Clock+holiday truth (v7 marketState is dead — it would say "closed" mid-session)
        val closed = !RiskGuard.isTradingHoursNow()
        sb.append(
            when {
                healthUnavailable   -> "Market news is not ready yet. "
                closed              -> "The market is closed now. "
                s.marketHealth?.trend == "BULLISH" -> "The market is good today. "
                s.marketHealth?.trend == "BEARISH" -> "The market is falling today. Be very careful. "
                else                -> "The market is quiet today. "
            }
        )
        val picks = s.dailyPicks.ifEmpty { s.signals.sortedByDescending { it.confidence }.take(4) }
        if (picks.isEmpty()) {
            sb.append("No strong chance right now. Please listen again later.")
        } else {
            sb.append("Listen to today's ${picks.size} stocks. ")
            picks.forEachIndexed { i, p ->
                sb.append("Number ${i + 1}: ${p.stockSymbol}. ")
                when (p.action) {
                    // Dead-setup guard: same checks as the card (chanceGone +
                    // buyChancePassed against the live-or-snapshot price) — never
                    // speak "buy" when the chance has already passed.
                    SignalAction.BUY  -> {
                        val px = effectivePriceFor(p.stockSymbol) ?: p.currentPrice
                        if (p.chanceGone || p.buyChancePassed(px) ||
                            p.targetPrice <= px || px <= p.stopLoss)
                            sb.append("The chance has passed. Do not buy now. ")
                        else
                            sb.append("Buy at ${px.toInt()} rupees. Sell at ${p.targetPrice.toInt()} for profit. If it drops below ${p.stopLoss.toInt()}, sell right away. ")
                    }
                    SignalAction.SELL -> sb.append("This may fall. Do not buy now. ")
                    SignalAction.WAIT -> sb.append("Wait for now. ")
                }
            }
        }
        sb.append("Remember, this is not advice. Your money is your own responsibility.")
        ttsManager.speakText(sb.toString(), "day-summary")
    }

    // (The in-memory trade journal is gone — TrackedTrade rows, shown on both the
    // Stock and Intraday tabs with Mark-sold, are the one real journal now.)

    // ── F6.2: contextual news ─────────────────────────────────────────────────
    /**
     * Fetches news for [symbol] using the repository's cached Yahoo+Google source.
     * Runs on IO; returns [] on any error — callers never crash.
     */
    suspend fun fetchNewsFor(symbol: String): List<com.example.myapplication3.intraday.ResearchNewsItem> =
        withContext(Dispatchers.IO) {
            runCatching { repository.fetchYahooNews(symbol) }.getOrElse { emptyList() }
        }
}