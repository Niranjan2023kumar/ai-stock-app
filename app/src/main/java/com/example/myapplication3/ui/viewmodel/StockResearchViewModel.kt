package com.example.myapplication3.ui.viewmodel

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication3.groww.OrderType
import com.example.myapplication3.intraday.AiPrediction
import com.example.myapplication3.intraday.IntradayRepository
import com.example.myapplication3.intraday.StockResearchData
import com.example.myapplication3.network.api.anthropic.AnthropicApiService
import com.example.myapplication3.network.api.anthropic.AnthropicMessage
import com.example.myapplication3.network.api.anthropic.AnthropicMessageRequest
import com.example.myapplication3.settings.RiskSettings
import com.example.myapplication3.settings.UserSettingsStore
import com.example.myapplication3.tracking.TradeTrackerRepository
import com.example.myapplication3.tracking.TradeWatchService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Named

/**
 * I3/B3 — the pre-computed "how many shares" answer for a BUY verdict, sized from
 * the user's OWN money settings (capital + risk-per-trade from the Settings screen).
 * The research screen renders this; a zero-knowledge user must never have to do
 * this arithmetic themselves.
 */
data class BuyPlan(
    /** True only when the current verdict is BUY and a live price exists — show the plan only then. */
    val isBuy: Boolean = false,
    /**
     * Shares to buy = floor(min(riskRupees ÷ stop-distance, capital ÷ price)).
     * 0 with isBuy = true means even 1 share breaks the user's money rules —
     * the screen must say "too costly for your money".
     */
    val recommendedQty: Int = 0,
    /** False when recommendedQty is 0 — screen shows "too costly for your money". */
    val affordable: Boolean = false,
    /** ₹ cost of buying recommendedQty at the current price (0 when qty is 0). */
    val totalCost: Double = 0.0,
    /** The capital (₹) the qty was computed from — for an honest "based on your ₹X" line. */
    val capital: Double = 0.0,
    /** ₹ the user allows to lose on ONE trade (capital × risk% from Settings). */
    val riskRupees: Double = 0.0,
    /**
     * False for non-rupee stocks: the user's capital is in ₹, so share-count math
     * against a $ price would be a ~90x currency lie. Screen should say quantity
     * sizing only works for Indian stocks.
     */
    val currencyOk: Boolean = true
)

@HiltViewModel
class StockResearchViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: IntradayRepository,
    private val anthropicApi: AnthropicApiService,
    @Named("anthropicApiKey") private val anthropicKey: String,
    private val ttsManager: com.example.myapplication3.tts.HindiTtsManager,
    private val tradeTracker: TradeTrackerRepository,
    private val dataStore: DataStore<Preferences>,
    private val userSettings: UserSettingsStore,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context
) : ViewModel() {

    companion object {
        private const val TAG = "StockResearchVM"

        /**
         * I2 — cloud-AI free text (reasoning, bull/bear case, risks) is rendered
         * verbatim on screen, so any jargon token the model emits would leak to a
         * zero-knowledge user. Every banned token is swapped for the plain
         * substitute from amended I2 before display. Word-boundary + ignore-case,
         * so "Navin" or "attract" are never touched.
         */
        private val JARGON_SUBSTITUTES = listOf(
            Regex("\\bRSI\\b", RegexOption.IGNORE_CASE) to "the stock's energy meter",
            Regex("\\bATR\\b", RegexOption.IGNORE_CASE) to "the usual daily price swing",
            Regex("\\bVWAP\\b", RegexOption.IGNORE_CASE) to "the day's average price",
            Regex("\\bNAV\\b", RegexOption.IGNORE_CASE) to "price per unit",
            Regex("\\bT1\\b", RegexOption.IGNORE_CASE) to "Target 1",
            Regex("\\bT2\\b", RegexOption.IGNORE_CASE) to "Target 2",
            Regex("\\bT3\\b", RegexOption.IGNORE_CASE) to "Target 3",
            Regex("\\bsquare[-\\s]?off\\b", RegexOption.IGNORE_CASE) to "the broker's automatic sell at 3:20",
            Regex("\\bprice band\\b", RegexOption.IGNORE_CASE) to "share price range"
        )

        /** Run one AI-written string through the I2 jargon filter. */
        private fun plainWords(text: String): String {
            var out = text
            JARGON_SUBSTITUTES.forEach { (re, plain) -> out = re.replace(out, plain) }
            return out
        }
    }

    val symbol: String = savedStateHandle.get<String>("symbol") ?: ""

    private val _state = MutableStateFlow(
        StockResearchData(symbol = symbol, isLoading = true)
    )
    val state: StateFlow<StockResearchData> = _state.asStateFlow()

    /**
     * I3/B3 — the pre-computed share quantity for a BUY verdict, recomputed live
     * from the research state and the user's saved money settings. The screen
     * renders it next to the BUY verdict; the user never does the math.
     */
    val buyPlan: StateFlow<BuyPlan> =
        combine(_state, userSettings.settings) { s, rs -> computeBuyPlan(s, rs) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BuyPlan())

    /**
     * I7/I6 honesty — true only when the background price watcher can actually
     * fetch this stock. The watcher force-appends ".NS" to bare symbols, so a
     * foreign stock (AAPL, 7203.T) or a BSE-suffixed one silently gets NO
     * stop-loss/target alerts ever. The screen must show this before the user
     * relies on protection that will never come.
     */
    val watchable: StateFlow<Boolean> =
        _state.map { isWatchable(it.symbol, it.currency) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** The watcher can only resolve INR stocks whose symbol survives its ".NS" append. */
    private fun isWatchable(sym: String, currency: String): Boolean {
        if (!currency.equals("INR", ignoreCase = true)) return false
        // fetchPricesForSymbols turns "X" into "X.NS" and leaves "X.NS" alone —
        // any OTHER suffix ("X.BO" → "X.BO.NS") becomes garbage Yahoo can't resolve
        return !sym.contains(".") || sym.endsWith(".NS", ignoreCase = true)
    }

    private fun computeBuyPlan(s: StockResearchData, rs: RiskSettings): BuyPlan {
        val price = s.currentPrice
        val ai = s.aiPrediction
        if (ai.isLoading || ai.recommendation != "BUY" || price <= 0.0) return BuyPlan()

        // ₹ capital against a $ price is a ~90x lie — refuse to size non-INR stocks
        if (!s.currency.equals("INR", ignoreCase = true)) {
            return BuyPlan(
                isBuy = true, currencyOk = false,
                capital = rs.capital, riskRupees = rs.riskPerTradeRupees
            )
        }

        // Risk leg: shares whose stop-loss hit loses exactly the allowed ₹ per trade.
        // Both BUY paths (validated AI + local math) guarantee 0 < stop < price, but
        // guard anyway — a missing stop must never unlock an all-capital position.
        val stopDistance = price - ai.stopLoss
        val byRisk = if (ai.stopLoss > 0.0 && stopDistance > 0.0)
            rs.riskPerTradeRupees / stopDistance else 0.0
        // Capital leg: can never buy more than the user's whole money allows
        val byCapital = rs.capital / price

        val qty = kotlin.math.floor(minOf(byRisk, byCapital)).toInt().coerceAtLeast(0)
        return BuyPlan(
            isBuy = true,
            recommendedQty = qty,
            affordable = qty >= 1,
            totalCost = qty * price,
            capital = rs.capital,
            riskRupees = rs.riskPerTradeRupees,
            currencyOk = true
        )
    }

    // Single in-flight load — refresh cancels the previous one so a slow, stale
    // response can never overwrite newer data
    private var loadJob: kotlinx.coroutines.Job? = null

    init {
        if (symbol.isNotBlank()) loadResearch()
    }

    fun refresh() {
        if (symbol.isNotBlank()) {
            // Keep the old data (and old verdict) on screen while refreshing — a
            // failed refresh must never leave the user with nothing (B2)
            _state.update { it.copy(isLoading = true, error = null) }
            loadResearch()
        }
    }

    /**
     * After "Order placed? Yes" + the real fill price + share count (B0.2b):
     * record the DELIVERY trade so the P/L bar, loss limits, and background
     * stop-loss watcher all see it. Practice mode is respected.
     */
    fun trackBuy(fillPrice: Double, quantity: Int) {
        val s = _state.value
        if (fillPrice <= 0.0 || quantity < 1) return
        viewModelScope.launch {
            val practice = runCatching { dataStore.data.first()[booleanPreferencesKey("practice_mode")] }.getOrNull() ?: false
            tradeTracker.open(
                symbol     = s.symbol,
                name       = s.name.ifEmpty { s.symbol },
                orderType  = OrderType.DELIVERY,
                entryPrice = fillPrice,
                quantity   = quantity,
                stopLoss   = s.aiPrediction.stopLoss,
                target1    = s.aiPrediction.targetPrice,
                isPractice = practice,
                // P1 learning loop: persist the research verdict's 0–100 confidence so
                // this trade's real outcome feeds the calibration buckets. Only a real
                // value (> 0); the "insufficient data" fallback reports 0, which we pass
                // as null rather than pretend it was a genuine confidence (B6 honesty).
                confidence = s.aiPrediction.confidence.takeIf { it > 0 }
            )
            if (practice) {
                ttsManager.speakText("Practice order saved. This is not real money.")
            } else if (isWatchable(s.symbol, s.currency)) {
                // Start the foreground watcher so stop-loss/target alerts arrive (F2)
                runCatching { TradeWatchService.start(appContext) }.onFailure { android.util.Log.w("StockResearchVM", "Could not start trade watcher: $it") }
                ttsManager.speakText("Order saved. I will watch it for you now.")
            } else {
                // I7/I6 FORCED HONESTY — the watcher force-appends ".NS", so this
                // stock can never be price-checked. Saying "I will watch it" here
                // would promise protection that never arrives. Still start the
                // service: it protects any OTHER open Indian trades.
                runCatching { TradeWatchService.start(appContext) }.onFailure { android.util.Log.w("StockResearchVM", "Could not start trade watcher: $it") }
                ttsManager.speakText(
                    "Order saved. Note: I can only watch Indian stocks - I cannot alert you for this one."
                )
            }
        }
    }

    /** 🔊 Speak the whole research summary in simple English — for users who listen, not read. */
    fun speakSummary() {
        val s = _state.value
        if (s.currentPrice <= 0.0) {
            ttsManager.speakText("The price is loading. Please wait a moment.")
            return
        }
        val dir = if (s.changePercent >= 0) "It is up today" else "It is down today"
        // Speak the RIGHT money word — a US stock spoken as "rupees" is a ~90x lie
        val unit = when (s.currency) {
            "INR" -> "rupees"
            "USD" -> "dollars"
            else  -> s.currency
        }
        // FORCED HONESTY on the voice channel too: only call it "AI says" when the
        // opinion actually came from a real AI model. The local heuristic must announce
        // itself as the app's own plain-math estimate — never as AI.
        val model = s.aiPrediction.modelUsed
        val fromRealAi = model.isNotBlank() && !model.startsWith("Local", ignoreCase = true) &&
            !model.startsWith("App", ignoreCase = true)
        val label = if (fromRealAi) "AI says" else "The app's simple math says"
        // I5 read-aloud parity — these sentences MUST match the verdict line the
        // screen shows at the top (verdictSentence in StockResearchScreen)
        val rec = when (s.aiPrediction.recommendation) {
            // FORCED HONESTY — only speak target/stop when they are sane numbers;
            // a zeroed/absent field must never become "sell at 0 rupees" on the voice channel
            "BUY"  -> {
                val target = s.aiPrediction.targetPrice
                val stop   = s.aiPrediction.stopLoss
                if (target > s.currentPrice && stop > 0.0 && stop < s.currentPrice)
                    "$label you can buy this stock now. Sell at ${target.toInt()} $unit. If it drops below ${stop.toInt()}, sell right away."
                else
                    "$label you can buy this stock now."
            }
            "SELL" -> "$label do not buy now. It may go down."
            "HOLD" -> "$label if you own it, keep it. Do not buy more now."
            "WAIT" -> "$label wait for now. It is not a good time to buy."
            else   -> ""
        }
        ttsManager.speakText(
            "${s.symbol}. Price is ${s.currentPrice.toInt()} $unit. $dir. $rec Remember, this is not advice."
        )
    }

    private fun loadResearch() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            // Fetch market data + news
            val data = withContext(Dispatchers.IO) {
                repository.fetchStockResearch(symbol)
            }

            if (data.error != null) {
                // B2: a failed REFRESH must never wipe the numbers the user is
                // looking at — keep the old data + old verdict, mark it Cached via
                // the error line. Full replace only when there was nothing yet.
                _state.update { prev ->
                    if (prev.currentPrice > 0.0)
                        prev.copy(isLoading = false, error = data.error,
                            aiPrediction = prev.aiPrediction.copy(isLoading = false))
                    else data
                }
                return@launch
            }

            // Show data immediately, start AI prediction in parallel
            _state.value = data.copy(aiPrediction = AiPrediction(isLoading = true))

            // Run AI prediction
            val aiPrediction = withContext(Dispatchers.IO) {
                runCatching { fetchAiPrediction(data) }.getOrElse { e ->
                    Log.e(TAG, "AI prediction failed: ${e.message}")
                    generateLocalPrediction(data)
                }
            }

            _state.update { it.copy(aiPrediction = aiPrediction) }
        }
    }

    private suspend fun fetchAiPrediction(data: StockResearchData): AiPrediction {
        val hasValidKey = anthropicKey.isNotBlank() &&
                !anthropicKey.startsWith("YOUR_") &&
                !anthropicKey.startsWith("REPLACE_") &&
                anthropicKey.length > 20

        if (!hasValidKey) {
            Log.d(TAG, "No valid Anthropic key — using local prediction")
            return generateLocalPrediction(data)
        }

        // The prompt must talk in the stock's OWN currency — feeding a US stock to the
        // model as ₹ prices invites rupee-flavoured targets on a dollar stock
        val cur = when (data.currency) {
            "INR" -> "₹"
            "USD" -> "$"
            else  -> "${data.currency} "
        }

        val priceHistory = if (data.historicalCloses.size >= 2) {
            val last30 = data.historicalCloses.takeLast(30)
            val start = last30.firstOrNull() ?: 0.0
            val end   = last30.lastOrNull() ?: 0.0
            val chg   = if (start > 0) (end - start) / start * 100.0 else 0.0
            val trend = when {
                chg > 5  -> "STRONG UPTREND +${String.format("%.1f", chg)}% over 30 days"
                chg > 1  -> "MILD UPTREND +${String.format("%.1f", chg)}% over 30 days"
                chg < -5 -> "STRONG DOWNTREND ${String.format("%.1f", chg)}% over 30 days"
                chg < -1 -> "MILD DOWNTREND ${String.format("%.1f", chg)}% over 30 days"
                else     -> "SIDEWAYS ${String.format("%.1f", chg)}% over 30 days"
            }
            "$cur${String.format("%.0f", start)}→$cur${String.format("%.0f", end)} | $trend"
        } else "Insufficient history"

        val volRatioStr = if (data.avgVolume > 0)
            "${String.format("%.1f", data.volume.toDouble() / data.avgVolume)}x avg"
        else "N/A"

        val posRelTo52w = if (data.high52w > data.low52w) {
            val pct = (data.currentPrice - data.low52w) / (data.high52w - data.low52w) * 100
            "${String.format("%.0f", pct)}% of 52W range"
        } else "N/A"

        val newsText = data.news.take(6).joinToString("\n") {
            "[${it.sentiment}] ${it.headline.take(100)} — ${it.source}"
        }.ifEmpty { "No recent news available" }

        val maContext = buildString {
            if (data.ma50 > 0) append("MA50=$cur${String.format("%.0f", data.ma50)} ")
            if (data.ma200 > 0) append("MA200=$cur${String.format("%.0f", data.ma200)} ")
            if (data.ma50 > 0 && data.ma200 > 0) {
                append(if (data.currentPrice > data.ma50 && data.currentPrice > data.ma200)
                    "(price ABOVE both MAs — rising trend)" else
                    if (data.currentPrice < data.ma50 && data.currentPrice < data.ma200)
                        "(price BELOW both MAs — falling trend)" else "(mixed trend)")
            }
        }

        val prompt = """You are a senior ${if (data.currency == "INR") "NSE/BSE" else "global equity"} quant analyst. Provide a precise trading analysis. All prices are in ${data.currency}.

STOCK: ${data.symbol} (${data.name})
PRICE: $cur${String.format("%.2f", data.currentPrice)} (${String.format("+%.2f", data.changePercent)}% today, $cur${String.format("%.2f", kotlin.math.abs(data.changeAbsolute))} abs)
DAY RANGE: ${if (data.dayHigh > data.dayLow) "$cur${String.format("%.2f", data.dayLow)}–$cur${String.format("%.2f", data.dayHigh)}" else "not available"}
52W RANGE: $cur${String.format("%.2f", data.low52w)}–$cur${String.format("%.2f", data.high52w)} | Position: $posRelTo52w
MOVING AVG: $maContext
VOLUME: ${data.volume} ($volRatioStr)
30-DAY TREND: $priceHistory

RECENT NEWS (${data.news.size} items):
$newsText

Based on ALL above, provide JSON (no markdown wrapper, pure JSON only):
{"recommendation":"BUY|SELL|HOLD|WAIT","confidence":75,"target_price":${String.format("%.2f", data.currentPrice * 1.05)},"stop_loss":${String.format("%.2f", data.currentPrice * 0.97)},"bullish_case":"specific technical+fundamental reason to buy","bearish_case":"specific risk or reason not to buy","reasoning":"2 concise sentences about current setup","key_risks":["risk1","risk2","risk3"]}"""

        return try {
            val request = AnthropicMessageRequest(
                model     = "claude-haiku-4-5-20251001",
                maxTokens = 600,
                messages  = listOf(AnthropicMessage("user", prompt)),
                temperature = 0.15
            )
            val response = anthropicApi.createMessage(anthropicKey, request = request)

            if (!response.isSuccessful) {
                Log.w(TAG, "Anthropic ${response.code()} — fallback to local")
                return generateLocalPrediction(data)
            }

            val content = response.body()?.content?.firstOrNull()?.text
                ?: return generateLocalPrediction(data)

            parseAiJson(content, "Claude Haiku", data)
        } catch (e: Exception) {
            Log.e(TAG, "Anthropic call error: ${e.message}")
            generateLocalPrediction(data)
        }
    }

    private fun parseAiJson(raw: String, model: String, data: StockResearchData? = null): AiPrediction {
        return runCatching {
            val cleaned = raw.trim()
                .removePrefix("```json").removePrefix("```")
                .removeSuffix("```").trim()
            val obj = JSONObject(cleaned)
            val risksArr = obj.optJSONArray("key_risks")
            val risks = if (risksArr != null) {
                (0 until risksArr.length()).map { risksArr.optString(it, "") }.filter { it.isNotEmpty() }
            } else emptyList()

            // I2 — every free-text field the model wrote passes the jargon filter
            // before it can reach the screen or the voice channel.
            val prediction = AiPrediction(
                recommendation = obj.optString("recommendation", "WAIT"),
                confidence     = obj.optInt("confidence", 50).coerceIn(0, 100),
                targetPrice    = obj.optDouble("target_price", 0.0),
                stopLoss       = obj.optDouble("stop_loss", 0.0),
                bullishCase    = plainWords(obj.optString("bullish_case", "")),
                bearishCase    = plainWords(obj.optString("bearish_case", "")),
                reasoning      = plainWords(obj.optString("reasoning", "")),
                keyRisks       = risks.map { plainWords(it) },
                modelUsed      = model,
                isLoading      = false
            )

            // FORCED HONESTY — never trust model-emitted numbers blindly: an out-of-band
            // target/stop flows straight into the chips AND the Hindi voice line as
            // authoritative sell instructions. Validate, else fall back to local math.
            val price    = data?.currentPrice ?: 0.0
            val validRec = prediction.recommendation in listOf("BUY", "SELL", "HOLD", "WAIT")
            val validLevels = prediction.recommendation != "BUY" || (
                price > 0.0 &&
                prediction.stopLoss > 0.0 &&
                prediction.stopLoss < price &&
                prediction.targetPrice > price &&
                prediction.stopLoss >= price * 0.90 &&
                prediction.targetPrice <= price * 1.20
            )
            if (validRec && validLevels) {
                prediction
            } else {
                Log.w(TAG, "AI JSON failed sanity checks (rec=${prediction.recommendation}, " +
                        "target=${prediction.targetPrice}, stop=${prediction.stopLoss}, price=$price) — using local prediction")
                generateLocalPrediction(data)
            }
        }.getOrElse { e ->
            Log.w(TAG, "JSON parse failed: ${e.message} | raw: ${raw.take(200)}")
            // Fall back to the LOCAL model with the fetched market data — passing null
            // here would discard everything and show "Insufficient data"
            generateLocalPrediction(data)
        }
    }

    private fun generateLocalPrediction(data: StockResearchData?): AiPrediction {
        if (data == null || data.currentPrice <= 0.0) {
            return AiPrediction(
                recommendation = "WAIT",
                confidence     = 0,
                reasoning      = "Insufficient data for analysis",
                modelUsed      = "Local"
            )
        }

        val price  = data.currentPrice
        val ma50   = data.ma50
        val ma200  = data.ma200
        val high52 = data.high52w
        val low52  = data.low52w

        var bullScore = 0; var bearScore = 0
        val reasons = mutableListOf<String>(); val risks = mutableListOf<String>()

        // MA analysis — reasons/risks are shown to a zero-knowledge user, so they are
        // written in plain words (I2), never as "DMA"/"52W" jargon
        if (ma200 > 0) {
            if (price > ma200 * 1.03) { bullScore += 4; reasons += "Price is well above its long-term average — rising for months" }
            else if (price > ma200)    { bullScore += 2; reasons += "Price is above its long-term average — rising" }
            else if (price < ma200 * 0.97) { bearScore += 4; risks += "Price is below its long-term average — falling for months" }
            else { bearScore += 2 }
        }
        if (ma50 > 0) {
            if (price > ma50 * 1.02) { bullScore += 3; reasons += "Price is above its recent average — rising lately" }
            else if (price > ma50)   bullScore += 1
            else if (price < ma50 * 0.98) { bearScore += 3; risks += "Price is below its recent average — falling lately" }
            else bearScore += 1
        }

        // 52W position
        if (high52 > low52) {
            val rangePos = (price - low52) / (high52 - low52)
            when {
                rangePos < 0.20 -> { bullScore += 3; reasons += "Near its 1-year low — cheap right now" }
                rangePos < 0.35 -> { bullScore += 2; reasons += "In the cheaper part of its 1-year range" }
                rangePos > 0.90 -> { bearScore += 3; risks += "Near its 1-year high — already costly" }
                rangePos > 0.75 -> { bearScore += 1; risks += "In the costly part of its 1-year range" }
            }
        }

        // Momentum
        if (data.changePercent > 0.5)       { bullScore += 2; reasons += "Moving up today" }
        else if (data.changePercent < -0.5) { bearScore += 2; risks += "Falling today — people are selling" }

        // News sentiment
        val posNews = data.news.count { it.sentiment == "POSITIVE" }
        val negNews = data.news.count { it.sentiment == "NEGATIVE" }
        if (posNews > negNews + 1) { bullScore += 2; reasons += "Recent news about it is good" }
        else if (negNews > posNews + 1) { bearScore += 2; risks += "Recent news about it is bad" }

        // Volume
        if (data.avgVolume > 0) {
            val vr = data.volume.toDouble() / data.avgVolume
            if (vr >= 2.0) { bullScore += 2; reasons += "Many people are trading it today — strong interest" }
            else if (vr < 0.5) risks += "Very few people are trading it today"
        }

        val recommendation = when {
            bullScore >= bearScore + 4 -> "BUY"
            bearScore >= bullScore + 4 -> "SELL"
            bullScore > bearScore + 1  -> "HOLD"
            else                       -> "WAIT"
        }

        val confidence = (50 + (bullScore - bearScore) * 4).coerceIn(25, 88)

        // Measured volatility, not a fake constant — average absolute daily % change
        // over the last 20 closes, clamped to 1%–4% so a sleepy largecap's stop isn't
        // absurdly wide and a wild smallcap's stop isn't inside normal daily noise
        val dailyMoves = data.historicalCloses.takeLast(20)
            .zipWithNext { a, b -> if (a > 0.0) kotlin.math.abs((b - a) / a) else 0.0 }
            .filter { it > 0.0 }
        val volPct = (if (dailyMoves.isNotEmpty()) dailyMoves.average() else 0.015).coerceIn(0.01, 0.04)
        val atr    = price * volPct

        // Non-BUY calls get NO target/stop — the short-trade numbers (target below
        // price, stop above) only mislead a beginner who cannot short. The > 0 guards
        // in the UI and speakSummary then hide them entirely.
        val targetP    = if (recommendation == "BUY") price + atr * 2.5 else 0.0
        val stopL      = if (recommendation == "BUY") price - atr * 1.0 else 0.0

        return AiPrediction(
            recommendation = recommendation,
            confidence     = confidence,
            targetPrice    = targetP,
            stopLoss       = stopL,
            bullishCase    = if (reasons.isNotEmpty()) reasons.take(2).joinToString("; ") else "The price pattern looks healthy",
            bearishCase    = if (risks.isNotEmpty()) risks.take(2).joinToString("; ") else "A weak market day can pull it down",
            reasoning      = "This is simple math, not real AI. " +
                    "${if (ma200 > 0) "The price is ${if (price > ma200) "above" else "below"} its long-term average. " else ""}" +
                    "${if (high52 > low52) "It sits at ${String.format(java.util.Locale.US, "%.0f", (price - low52) / (high52 - low52) * 100)}% of its one-year price range." else ""}",
            keyRisks       = risks.take(3).ifEmpty { listOf("The whole market can fall suddenly", "Bad news can change the price fast", "No stock is ever a sure thing") },
            modelUsed      = "Local AI",
            isLoading      = false
        )
    }
}