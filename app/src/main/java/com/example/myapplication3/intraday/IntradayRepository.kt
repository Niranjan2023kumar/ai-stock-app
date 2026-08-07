


package com.example.myapplication3.intraday

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.myapplication3.core.domain.model.Sector
import com.example.myapplication3.network.api.nse.NseStockData
import com.example.myapplication3.smartapi.InstrumentMaster
import com.example.myapplication3.smartapi.SmartApiClient
import com.example.myapplication3.smartapi.SmartApiStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

@Singleton
class IntradayRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    // Angel One layer (H14 upgrade) — used ONLY to read today's real 5-minute
    // candles when the user has connected SmartAPI. Every path here is fail-safe
    // and degrades to the existing daily-data path (B8 free-forever): no keys,
    // market closed, or any error ⇒ the app behaves exactly as it does today.
    private val smartApiStore: SmartApiStore,
    private val smartApiClient: SmartApiClient,
    private val instrumentMaster: InstrumentMaster,
    // NSE fallback (single-source risk): used ONLY when the Yahoo universe
    // fetch comes back empty/mostly-empty. Fail-safe — if NSE also fails, the
    // existing honest cached/stale behavior is untouched (B8).
    private val nseRepository: NseRepository
) {
    companion object {
        private const val TAG = "IntradayRepo"
        // All NSE Yahoo Finance hosts for rotation
        private val YF_HOSTS = arrayOf(
            "query1.finance.yahoo.com",
            "query2.finance.yahoo.com"
        )
        // Honest news-vetting cap (B6). Reading Yahoo headlines costs one HTTP call
        // per stock, so we read news for at most this many signals per refresh —
        // BUY signals FIRST. In normal conditions the qualifying BUY set is far
        // smaller than this, so every BUY that can become a daily pick is checked;
        // the cap only bites in an unusually broad rally, and even then the
        // highest-confidence BUYs (the ones shown as daily picks) are the ones read.
        // Comfortably covers the 4 daily picks + the ~20 top signals a user scrolls.
        private const val NEWS_VET_CAP = 30
        // ── P0 #2 NEWS TRUTH ─────────────────────────────────────────────────
        // News TTL cache: the 30s auto-refresh must NOT re-fire up to NEWS_VET_CAP
        // Yahoo news calls every tick. A per-symbol headline read stays valid for
        // this long (mirrors INTRADAY_READ_TTL_MS) — data + rate-limit safety (E3d).
        private const val NEWS_CACHE_TTL_MS = 5 * 60 * 1000L
        // Freshness cutoff: a headline older than this — or of unknown age
        // (providerPublishTime == 0) — can no longer move a stock's confidence.
        private const val NEWS_MAX_AGE_MS = 72L * 60 * 60 * 1000L
        // H4 event tiers — how market-moving a headline is (scales the delta).
        private const val TIER_ROUTINE = 1   // dividend / bonus / generic price move
        private const val TIER_MEDIUM  = 2   // guidance / order / approval / rating / results
        private const val TIER_BIG     = 3   // fraud / probe / raid / recall / merger / results-surprise
        // H14 intraday layer: 5-minute candle reads are fetched for at most this
        // many top BUY candidates per cycle — the SmartAPI candle endpoint is
        // rate-limited (~3 req/sec), so the fan-out stays small and sequential.
        private const val INTRADAY_READ_CAP = 12
        // One new 5-minute candle exists per 5 minutes — refetching faster than
        // that (the 30s auto-refresh) would burn the rate limit for zero new data.
        private const val INTRADAY_READ_TTL_MS = 5 * 60 * 1000L
        // Failed-login cooldown so a broken/expired key can never hammer Angel
        // One's login endpoint on every 30-second refresh.
        private const val LOGIN_RETRY_COOLDOWN_MS = 2 * 60 * 1000L
        // ── REFRESH-COST DIET ────────────────────────────────────────────────
        // The universe's 1-year close history gains exactly ONE new bar per
        // trading day, so the heavy range=1y charts run once per IST day and
        // every other refresh (the 30s loop AND manual pulls) fetches a tiny
        // interval=1d&range=5d chart per symbol instead — ~10x less data and
        // wall-time, so prices update FASTER. See fetchAllViaChart.
        // The daily full pass only counts as "done" when it covered at least
        // this fraction of the universe — a Yahoo outage must never lock a bad
        // (near-empty) history in for the whole day.
        private const val HISTORY_PASS_MIN_COVERAGE = 0.5
        // NSE fallback activation: only when the Yahoo universe fetch returns
        // fewer than this many quotes (empty / mostly-empty ⇒ Yahoo is down)
        // do we try the NSE path. The happy path never pays for NSE calls.
        private const val NSE_FALLBACK_MIN_QUOTES = 20
        // ── H2 SECTOR STRENGTH (rotation) ────────────────────────────────────
        // A sector counts as STRONG (its picks score +5) or WEAK (−5) only when
        // the average of its constituents' REAL % change today clears this bar.
        // Sectors drifting inside the band are neutral and simply ABSENT from
        // the map passed to SignalEngine — no points, no denominator growth
        // (H3: neutral/missing sector data must never punish a stock).
        private const val SECTOR_STRENGTH_MIN_PCT = 0.75
        // An "average" over 1–2 stocks is just those stocks' own moves relabeled
        // as a sector — require at least this many constituents reporting.
        private const val SECTOR_MIN_CONSTITUENTS = 3
        // ── DELIVERY GEOMETRY (Stock-tab multi-day holds) ────────────────────
        // The engine's levels are INTRADAY geometry (built from ONE day's range
        // for a 3:15 PM square-off). Delivery picks are held for DAYS, so their
        // levels are rebuilt from a 10-day volatility unit (see
        // applyDeliveryGeometry). Multipliers keep the intraday shape family:
        // rr stays 2.0, T2:T1 = 1.75, T3:T1 = 2.5, trail:stop = 1.2.
        private const val DELIVERY_VOL_LOOKBACK  = 10     // daily closes in the volatility unit
        private const val DELIVERY_SL_MULT       = 1.5    // stop      = entry − 1.5 × unit
        private const val DELIVERY_T1_MULT       = 3.0    // target-1  = entry + 3.0 × unit (rr 2.0)
        private const val DELIVERY_T2_MULT       = 5.25   // target-2  = entry + 5.25 × unit
        private const val DELIVERY_T3_MULT       = 7.5    // target-3  = entry + 7.5 × unit
        private const val DELIVERY_TRAIL_MULT    = 1.8    // trailing  = entry + 1.8 × unit
        private const val DELIVERY_MIN_VOL_FRAC  = 0.012  // unit floor: 1.2% of price (same fraction the intraday ATR floors at)
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences("intraday_cache_v3", Context.MODE_PRIVATE)
    }

    // ── Data-saver foreground gate for the indices auto-refresh (E3d) ────────
    // HomeViewModel refreshes indices every 60s in a plain while-loop with NO
    // foreground check, silently burning mobile data all day while the app is
    // backgrounded. The gate lives HERE because fetchIndices() is the single
    // funnel every indices call passes through (same _isForeground.first{it}
    // pattern InterdayViewModel uses for the signals loop). Starts true so the
    // first load is never blocked, and if no Activity ever registers (tests,
    // workers) it simply stays true — the gate can never block forever (B8).
    private val indicesForeground = MutableStateFlow(true)
    private var startedActivityCount = 0

    init {
        (context.applicationContext as? android.app.Application)
            ?.registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: android.app.Activity) {
                    startedActivityCount++
                    indicesForeground.value = true
                }
                override fun onActivityStopped(activity: android.app.Activity) {
                    startedActivityCount = (startedActivityCount - 1).coerceAtLeast(0)
                    if (startedActivityCount == 0) indicesForeground.value = false
                }
                override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {}
                override fun onActivityResumed(activity: android.app.Activity) {}
                override fun onActivityPaused(activity: android.app.Activity) {}
                override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) {}
                override fun onActivityDestroyed(activity: android.app.Activity) {}
            })
    }

    // OkHttp caps per-host concurrency at 5 by default — with only 2 Yahoo hosts
    // that throttled the whole chart fan-out. Raise it so 30 parallel charts fly.
    private val parallelDispatcher = okhttp3.Dispatcher().apply {
        maxRequests = 64
        maxRequestsPerHost = 16
    }

    // Fast client — bulk requests (v7)
    private val fastClient = OkHttpClient.Builder()
        .dispatcher(parallelDispatcher)
        .connectTimeout(7, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(20, 5, TimeUnit.MINUTES))
        .addInterceptor(browserInterceptor())
        .build()

    // Chart client — per-symbol v8 with slightly longer timeout
    private val chartClient = OkHttpClient.Builder()
        .dispatcher(parallelDispatcher)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(14, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(20, 5, TimeUnit.MINUTES))
        .addInterceptor(browserInterceptor())
        .build()

    // Research client — for research requests (longer timeouts)
    private val researchClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
        .addInterceptor(browserInterceptor())
        .build()

    private fun browserInterceptor() = okhttp3.Interceptor { chain ->
        chain.proceed(
            chain.request().newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                .header("Accept", "application/json, text/plain, */*")
                .header("Accept-Language", "en-US,en;q=0.9,hi;q=0.8")
                .header("Referer", "https://finance.yahoo.com/")
                .header("Origin", "https://finance.yahoo.com")
                .header("Cache-Control", "no-cache")
                .build()
        )
    }

    // Nifty 500 universe — 200 symbols across Large / Mid / Small Cap
    private val watchlist = listOf(
        // ── Nifty 50 (all 50) ────────────────────────────────────────────────
        "RELIANCE.NS","TCS.NS","HDFCBANK.NS","INFY.NS","ICICIBANK.NS",
        "SBIN.NS","WIPRO.NS","ITC.NS","AXISBANK.NS","KOTAKBANK.NS",
        "LT.NS","BAJFINANCE.NS","HCLTECH.NS","SUNPHARMA.NS","NESTLEIND.NS",
        "MARUTI.NS","ULTRACEMCO.NS","TITAN.NS","ASIANPAINT.NS","HINDUNILVR.NS",
        "ADANIENT.NS","ADANIPORTS.NS","BAJAJFINSV.NS","BHARTIARTL.NS","BPCL.NS",
        "CIPLA.NS","COALINDIA.NS","DRREDDY.NS","EICHERMOT.NS","GRASIM.NS",
        "HDFCLIFE.NS","HEROMOTOCO.NS","HINDALCO.NS","INDUSINDBK.NS","JSWSTEEL.NS",
        "NTPC.NS","ONGC.NS","POWERGRID.NS","SBILIFE.NS","TMPV.NS",
        "TATASTEEL.NS","TECHM.NS","TRENT.NS","DIVISLAB.NS","TATACONSUM.NS",
        "BAJAJ-AUTO.NS","BRITANNIA.NS","APOLLOHOSP.NS","VEDL.NS","ETERNAL.NS",
        // ── Nifty Next 50 ────────────────────────────────────────────────────
        "ADANIGREEN.NS","ADANIPOWER.NS","AMBUJACEM.NS","ATGL.NS","DLF.NS",
        "GAIL.NS","GODREJCP.NS","ICICIGI.NS","JSWENERGY.NS","LODHA.NS",
        "LTF.NS","LTM.NS","LTTS.NS","MARICO.NS","MANKIND.NS",
        "UNITDSPR.NS","NYKAA.NS","OBEROIRLTY.NS","PIIND.NS","POLYCAB.NS",
        "SBICARD.NS","TORNTPHARM.NS","TATAPOWER.NS","VBL.NS","ZYDUSLIFE.NS",
        "SHRIRAMFIN.NS","BANKBARODA.NS","CHOLAFIN.NS","DMART.NS","HAL.NS",
        "HAVELLS.NS","IRFC.NS","LUPIN.NS","MUTHOOTFIN.NS","NAUKRI.NS",
        "PFC.NS","RECLTD.NS","SIEMENS.NS","TVSMOTOR.NS","BEL.NS",
        // ── Nifty Midcap 150 picks ────────────────────────────────────────────
        "APLAPOLLO.NS","ASTRAL.NS","AUBANK.NS","BALKRISIND.NS","BANDHANBNK.NS",
        "CANBK.NS","CGPOWER.NS","COFORGE.NS","CROMPTON.NS","CUMMINSIND.NS",
        "DEEPAKNTR.NS","DIXON.NS","FEDERALBNK.NS","GMRAIRPORT.NS","IDFCFIRSTB.NS",
        "INDIGO.NS","INDUSTOWER.NS","IRCTC.NS","IREDA.NS","JKCEMENT.NS",
        "JUBLFOOD.NS","KAJARIACER.NS","KPITTECH.NS","LALPATHLAB.NS","MAXHEALTH.NS",
        "MFSL.NS","MOTILALOFS.NS","MPHASIS.NS","MRF.NS","NHPC.NS",
        "NMDC.NS","OFSS.NS","PAGEIND.NS","PERSISTENT.NS","PETRONET.NS",
        "PIDILITIND.NS","SJVN.NS","SOLARINDS.NS","STARHEALTH.NS","SUNDARMFIN.NS",
        "SUPREMEIND.NS","SYNGENE.NS","TATACOMM.NS","TIINDIA.NS","TORNTPOWER.NS",
        "UBL.NS","VOLTAS.NS","INDIANB.NS","PNBHOUSING.NS","TRIDENT.NS",
        // ── Mid & Small Cap additions ─────────────────────────────────────────
        "AARTIIND.NS","ABCAPITAL.NS","ACC.NS","AJANTPHARM.NS","ALKEM.NS",
        "APOLLOTYRE.NS","AUROPHARMA.NS","BSOFT.NS","CAMS.NS","CANFINHOME.NS",
        "CHAMBLFERT.NS","CONCOR.NS","ECLERX.NS","FLUOROCHEM.NS","GNFC.NS",
        "GRINDWELL.NS","GSFC.NS","HFCL.NS","KARURVYSYA.NS","LICI.NS",
        "METROPOLIS.NS","MOIL.NS","RADICO.NS","RBLBANK.NS","TATAELXSI.NS",
        "VGUARD.NS","ZEEL.NS","GODREJPROP.NS","SAIL.NS","COLPAL.NS",
        "DABUR.NS","EMAMILTD.NS","EXIDEIND.NS","IOC.NS","LAURUSLABS.NS",
        "LICHSGFIN.NS","BERGEPAINT.NS","ATUL.NS","HINDCOPPER.NS","RAYMOND.NS",
        "RITES.NS","SONACOMS.NS","NLCINDIA.NS","FORTIS.NS","CEATLTD.NS",
        "UNIONBANK.NS","NATCOPHARM.NS","CENTRALBK.NS","TATACHEM.NS","PPLPHARMA.NS"
    )

    // Public watchlist without .NS suffix for search/display
    val watchlistSymbols: List<String> get() = watchlist.map { it.removeSuffix(".NS") }

    // ─── Public API ───────────────────────────────────────────────────────────

    data class FetchResult(
        val signals: List<TradingSignal>,
        val topMovers: List<MarketMove>,
        val analyzedCount: Int,
        val health: MarketHealth,
        val screenerItems: List<StockScreenerItem>,
        val rawQuotes: List<SignalEngine.StockQuote> = emptyList(),
        // H14 honesty flag: true ONLY when `signals` were computed WITH real
        // Angel One 5-minute candle reads this cycle. false ⇒ everything is
        // byte-for-byte the existing daily path — no fake intraday claims (B6).
        val intradayPowered: Boolean = false,
        // The daily-only baseline (news-vetted, zero intraday deltas) carrying
        // DELIVERY-geometry levels: stops/targets rebuilt from 10-day volatility
        // for the multi-day hold (see applyDeliveryGeometry) — while `signals`
        // (the Intraday tab) keeps its same-day square-off geometry untouched.
        // Same stocks/confidences as `signals` when intradayPowered is false.
        // The Stock tab's delivery-timeframe picks are chosen from THIS list, so
        // picks never absorb intraday-timeframe adjustments (spec: picks stay
        // daily-based) and never inherit 1-day-range stops for a days-long hold.
        val dailySignals: List<TradingSignal> = emptyList()
    )

    suspend fun fetchAll(): Result<FetchResult> = withContext(Dispatchers.IO) {
        runCatching {
            Log.d(TAG, "fetchAll() start — ${watchlist.size} symbols")

            val health      = runCatching { fetchMarketHealthInternal() }.getOrElse { defaultHealth() }
            val marketTrend = health.trend

            val quotes = fetchAllQuotes()
            Log.d(TAG, "Total quotes fetched: ${quotes.size}, marketTrend=$marketTrend")

            if (quotes.isEmpty()) error("No quotes from any source. Check internet connection.")

            // Screener items (default 20-day period) — computed BEFORE the engine
            // runs so the H2 sector-strength aggregation can reuse them. Pure
            // in-memory math over the quotes we already fetched, no extra calls.
            val screenerItems = computeScreenerItems(quotes, 20)

            // H2 SECTOR STRENGTH (rotation): distill this cycle's REAL per-sector
            // performance into symbol → STRONG/WEAK tones for the engine. Strictly
            // fail-safe: any problem (or an all-neutral day) ⇒ null ⇒ the engine
            // scores exactly as before — no denominator growth, no fake factor (H3/B8).
            val sectorTones = runCatching { computeSectorStrengthMap(screenerItems) }
                .getOrElse { emptyMap() }
                .ifEmpty { null }

            // Daily-quote signals FIRST — this is the free-forever baseline (B8)
            // and stays the sole decision path whenever intraday reads are absent.
            val baseSignals = SignalEngine.analyzeAll(quotes, marketTrend, sectorStrength = sectorTones)

            // H14 intraday upgrade: ONLY when Angel One is connected AND the
            // market is open, read today's real 5-minute candles for the top BUY
            // candidates and re-run the engine WITH that context. Strictly
            // fail-safe — fetchIntradayReads returns an EMPTY map on ANY problem
            // (no keys / closed / rate limit / parse error), and an empty map
            // means the baseline above is used untouched: same signals as today,
            // no fake intraday claims (B6/A5).
            val buyCandidates = baseSignals
                .filter { it.action == SignalAction.BUY }
                .map { it.stockSymbol }
            val intradayReads = runCatching { fetchIntradayReads(buyCandidates) }
                .getOrElse { emptyMap() }
            var intradayPowered = false
            val engineSignals = if (intradayReads.isEmpty()) baseSignals else runCatching {
                SignalEngine.analyzeAll(
                    quotes, marketTrend,
                    intradayReads  = intradayReads,
                    sectorStrength = sectorTones   // same real sector context as the baseline run
                ).also { intradayPowered = true }
            }.getOrDefault(baseSignals)

            // News intelligence: the AI also READS each top candidate's latest
            // headlines and adjusts its confidence — good news up, bad news down.
            // Headlines are fetched ONCE and the same sentiments are folded into
            // both signal sets, so the intraday re-run never doubles Yahoo calls.
            //
            // Vet basis = the UNION of the daily-only baseline AND the intraday-
            // adjusted list. Daily picks are chosen from the daily baseline, so
            // vetting only the intraday-adjusted list let a stock demoted by
            // intraday reads become a daily pick with its news never read. The
            // two lists are interleaved rank-by-rank (daily first at each rank —
            // those become the picks) so NEWS_VET_CAP crowds neither side out;
            // fetchNewsSentiments de-duplicates symbols and keeps BUYs first.
            // When reads were unavailable the lists are identical (B8 unchanged).
            val vetBasis = if (intradayPowered) {
                val union = ArrayList<TradingSignal>(engineSignals.size + baseSignals.size)
                for (i in 0 until maxOf(engineSignals.size, baseSignals.size)) {
                    if (i < baseSignals.size)   union += baseSignals[i]
                    if (i < engineSignals.size) union += engineSignals[i]
                }
                union
            } else engineSignals
            val newsVet = fetchNewsSentiments(vetBasis)
            val signals = applyNewsSentiments(engineSignals, newsVet)
            // Stock-tab picks stay DELIVERY-timeframe (daily bars only — correct
            // per spec): when intraday context was used, the daily-only baseline
            // is carried alongside for pick selection.
            // DELIVERY GEOMETRY (ui-truth fix): the engine's stops/targets are
            // same-day square-off math from ONE day's range — reused as-is they
            // put a multi-day pick's stop inside normal overnight wiggle. The
            // daily list gets honest multi-day levels rebuilt from 10-day
            // volatility; `signals` (Intraday tab) is left byte-for-byte alone.
            val dailyBase =
                if (intradayPowered) applyNewsSentiments(baseSignals, newsVet) else signals
            val dailySignals = applyDeliveryGeometry(dailyBase, quotes)
            val topMovers = quotes
                .sortedByDescending { abs(it.changePercent) }
                .take(12)
                .map { q ->
                    val volRatio = if (q.avgVolume > 0) q.volume.toDouble() / q.avgVolume else 1.0
                    MarketMove(q.symbol.removeSuffix(".NS"), q.price, q.changePercent, volRatio)
                }

            Log.d(TAG, "Signals: ${signals.size}, TopMovers: ${topMovers.size}, Screener: ${screenerItems.size}")
            cacheSignals(signals, health)
            cacheScreener(screenerItems)

            FetchResult(
                signals, topMovers, quotes.size, health, screenerItems,
                rawQuotes       = quotes,
                intradayPowered = intradayPowered,
                dailySignals    = dailySignals
            )
        }
    }

    // Legacy kept for compatibility
    suspend fun fetchSignals(): Result<List<TradingSignal>> =
        fetchAll().map { it.signals }

    suspend fun fetchMarketHealth(): MarketHealth = withContext(Dispatchers.IO) {
        runCatching { fetchMarketHealthInternal() }.getOrElse { defaultHealth() }
    }

    fun loadCachedSignals(): List<TradingSignal> = runCatching {
        val json = prefs.getString("sig_json", null) ?: return emptyList()
        val arr = JSONArray(json)
        var signals = (0 until arr.length()).mapNotNull { i ->
            runCatching { arr.getJSONObject(i).toSignal() }.getOrNull()
        }
        // If cached data is from a previous trading day, mark it clearly
        val cachedAtMs = getCacheTimestampMs()
        if (cachedAtMs > 0L) {
            val dateFmt = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
            val cacheDate = dateFmt.format(java.util.Date(cachedAtMs))
            val todayDate = dateFmt.format(java.util.Date())
            if (cacheDate != todayDate) {
                signals = signals.map { it.copy(validUntil = "From yesterday") }
            }
        }
        signals
    }.getOrElse { emptyList() }

    fun loadCachedHealth(): MarketHealth? = runCatching {
        val json = prefs.getString("health_json", null) ?: return null
        JSONObject(json).run {
            MarketHealth(
                trend        = optString("trend", "SIDEWAYS"),
                riskLevel    = RiskLevel.valueOf(optString("riskLevel", "MEDIUM")),
                niftyChange  = optDouble("niftyChange", 0.0),
                marketState  = optString("marketState", "CLOSED"),
                niftyPrice   = optDouble("niftyPrice", 0.0),
                isAvailable  = optBoolean("isAvailable", true)
            )
        }
    }.getOrNull()

    fun getCacheTimestampMs(): Long = prefs.getLong("cache_ts", 0L)

    /** Returns true if cached data is older than [maxAgeMs] ms (default 30 minutes). */
    fun isCacheStale(maxAgeMs: Long = 30 * 60 * 1000L): Boolean {
        val ts = getCacheTimestampMs()
        return ts == 0L || System.currentTimeMillis() - ts > maxAgeMs
    }

    suspend fun fetchPricesForSymbols(symbols: List<String>): Map<String, Double> =
        withContext(Dispatchers.IO) {
            if (symbols.isEmpty()) return@withContext emptyMap()
            val nsSymbols = symbols.map { if (it.endsWith(".NS")) it else "$it.NS" }
            // v7 is dead (HTTP 401 without a crumb) - v8 chart per symbol is the only path.
            // Open-trade lists are tiny and chartClient shares the parallel dispatcher.
            runCatching {
                coroutineScope {
                    nsSymbols.map { sym ->
                        async(Dispatchers.IO) {
                            runCatching {
                                val enc = java.net.URLEncoder.encode(sym, "UTF-8")
                                val body = fetchUrl("https://query1.finance.yahoo.com/v8/finance/chart/$enc?interval=1d&range=1d", chartClient)
                                val meta = JSONObject(body).getJSONObject("chart")
                                    .getJSONArray("result").getJSONObject(0)
                                    .getJSONObject("meta")
                                val price = meta.optDouble("regularMarketPrice", 0.0)
                                if (price > 0) sym.removeSuffix(".NS") to price else null
                            }.getOrNull()
                        }
                    }.mapNotNull { it.await() }.toMap()
                }
            }.getOrElse { emptyMap() }
        }

    // ─── Intraday reads (H14) — today's real 5-minute candles via Angel One ──

    // Per-symbol read cache: the 30s auto-refresh must NOT refetch candles every
    // tick — a read stays valid for one 5-minute candle (INTRADAY_READ_TTL_MS).
    private val intradayReadCache = ConcurrentHashMap<String, Pair<Long, IntradayRead>>()

    // Last failed login attempt — see LOGIN_RETRY_COOLDOWN_MS.
    @Volatile private var lastLoginFailureMs = 0L

    /**
     * Fetch per-symbol intraday reads (today's 5-minute candles → IntradayScorer)
     * for the given BUY candidates. STRICTLY fail-safe (B8 free-forever): every
     * guard below returns an EMPTY map, and an empty map means the caller keeps
     * the existing daily-data signals completely unchanged — no Angel One keys,
     * market closed, login trouble, or any error all land in the same safe place.
     *
     * Runs ONLY when:
     *  (a) SmartAPI is configured and a session exists / can be established,
     *  (b) the market is OPEN right now (RiskGuard.isTradingHoursNow — IST,
     *      weekend- and NSE-holiday-aware),
     *  (c) capped to the first [INTRADAY_READ_CAP] symbols — the candle endpoint
     *      allows ~3 req/sec; the client spaces its calls, we cap the fan-out and
     *      fetch SEQUENTIALLY so that spacing is never defeated.
     *
     * Keys of the returned map are ".NS"-stripped upper-case symbols — the same
     * convention as the live-tick map SignalEngine already resolves.
     */
    suspend fun fetchIntradayReads(symbols: List<String>): Map<String, IntradayRead> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (symbols.isEmpty()) return@runCatching emptyMap<String, IntradayRead>()
                // (b) Market open — outside NSE hours a 5-min read is stale noise.
                if (!RiskGuard.isTradingHoursNow()) return@runCatching emptyMap()
                // (a) Configured — no Angel One keys ⇒ daily path only, silently.
                val configured = runCatching { smartApiStore.current().isComplete }.getOrDefault(false)
                if (!configured) return@runCatching emptyMap()

                // (c) Cap the fan-out to the top candidates (caller passes BUYs
                // first). Normalize to the shared symbol-key convention.
                val capped = symbols.asSequence()
                    .map { it.trim().removeSuffix(".NS").uppercase() }
                    .filter { it.isNotEmpty() }
                    .distinct()
                    .take(INTRADAY_READ_CAP)
                    .toList()

                val now = System.currentTimeMillis()
                val out = HashMap<String, IntradayRead>()
                val misses = mutableListOf<String>()
                for (sym in capped) {
                    val hit = intradayReadCache[sym]
                    if (hit != null && now - hit.first < INTRADAY_READ_TTL_MS) out[sym] = hit.second
                    else misses += sym
                }
                if (misses.isEmpty()) return@runCatching dropNeutralReads(out)

                // Logged in: reuse the live session; attempt a fresh login only
                // outside the failure cooldown so a bad key can't spam Angel One.
                if (smartApiClient.session == null &&
                    now - lastLoginFailureMs < LOGIN_RETRY_COOLDOWN_MS
                ) return@runCatching out
                val sessionOk = runCatching { smartApiClient.ensureSession().isSuccess }.getOrDefault(false)
                if (!sessionOk) {
                    lastLoginFailureMs = System.currentTimeMillis()
                    return@runCatching out
                }

                // SEQUENTIAL on purpose — the client spaces candle calls for the
                // 3/sec limit; a parallel fan-out here would defeat that spacing.
                // A failed symbol is skipped (retried next cycle), never fatal.
                for (sym in misses) {
                    val read = runCatching { fetchReadForSymbol(sym) }.getOrNull() ?: continue
                    intradayReadCache[sym] = System.currentTimeMillis() to read
                    out[sym] = read
                }
                val useful = dropNeutralReads(out)
                Log.d(TAG, "Intraday reads: ${out.size}/${capped.size} symbols (${misses.size} fetched fresh, ${out.size - useful.size} neutral treated as no-read)")
                useful
            }.getOrElse {
                Log.w(TAG, "fetchIntradayReads failed: ${it.message}")
                emptyMap()
            }
        }

    /**
     * H3 honesty fix (documented choice): a NEUTRAL read (score == 0) is treated
     * as NO-READ. Passing it to SignalEngine grows the confidence denominator
     * (maxPossible +20) while contributing 0 points — silently punishing a stock
     * for having neutral intraday data, which is the exact missing-data penalty
     * H3 forbids. Dropping it here makes that stock take the daily-only baseline
     * path, byte-for-byte the same as when no read exists (B8). The read STAYS in
     * [intradayReadCache], so this never causes a rate-limit-burning refetch —
     * and if every read is neutral the map comes back empty, the engine re-run is
     * skipped and intradayPowered stays honestly false (B6).
     */
    private fun dropNeutralReads(reads: Map<String, IntradayRead>): Map<String, IntradayRead> =
        reads.filterValues { it.score != 0 }

    /**
     * One symbol → one IntradayRead, or null on any failure. This is the ONLY
     * place that touches the Angel One candle + scorer APIs, so any future
     * signature change is a one-spot fix. Both callees are themselves fail-safe
     * (null on any error) — the runCatching wrappers are belt-and-braces.
     */
    private suspend fun fetchReadForSymbol(symbol: String): IntradayRead? {
        val token = runCatching { instrumentMaster.tokenFor(symbol) }.getOrNull() ?: return null
        val candles = runCatching { smartApiClient.getTodayFiveMinCandles(token) }.getOrNull() ?: return null
        if (candles.isEmpty()) return null
        return runCatching { IntradayScorer.read(candles) }.getOrNull()
    }

    /**
     * Compute StockScreenerItem list for a given period (days).
     * days=252 covers 52 weeks (1 year of trading days).
     */
    fun computeScreenerItems(quotes: List<SignalEngine.StockQuote>, days: Int): List<StockScreenerItem> {
        val actualDays = if (days == 252) 252 else days.coerceIn(1, 252)
        return quotes.mapNotNull { q ->
            runCatching {
                if (q.price <= 0.0) return@runCatching null
                val periodCloses = q.historicalCloses.takeLast(actualDays)

                // v7 quotes carry no close history — approximate with 52-week bounds
                // rather than dropping the stock (an empty screener/Stocks tab is worse).
                val periodLow: Double
                val periodHigh: Double
                if (periodCloses.isEmpty()) {
                    periodLow  = if (q.low52w > 0)  minOf(q.low52w, q.price)  else q.dayLow
                    periodHigh = if (q.high52w > 0) maxOf(q.high52w, q.price) else q.dayHigh
                } else {
                    periodLow  = periodCloses.minOrNull() ?: q.price
                    periodHigh = periodCloses.maxOrNull() ?: q.price
                }
                val distFromLow  = if (periodLow > 0) (q.price - periodLow) / periodLow * 100.0 else 0.0
                val distFromHigh = if (periodHigh > 0) (periodHigh - q.price) / periodHigh * 100.0 else 0.0
                val volRatio = if (q.avgVolume > 0) q.volume.toDouble() / q.avgVolume else 1.0

                StockScreenerItem(
                    symbol               = q.symbol.removeSuffix(".NS"),
                    name                 = q.name.take(28),
                    currentPrice         = q.price,
                    changePercent        = q.changePercent,
                    volume               = q.volume,
                    avgVolume            = q.avgVolume,
                    periodLow            = periodLow,
                    periodHigh           = periodHigh,
                    low52w               = q.low52w,
                    high52w              = q.high52w,
                    distFromPeriodLowPct = distFromLow,
                    distFromPeriodHighPct= distFromHigh,
                    historicalCloses     = q.historicalCloses.takeLast(30),
                    ma50                 = q.ma50,
                    ma200                = q.ma200,
                    marketState          = q.marketState,
                    periodDays           = days,
                    volRatio             = volRatio
                )
            }.getOrNull()
        }
    }

    // ─── Sector classification & real sector-strength aggregation ─────────────

    /**
     * Static NSE sector classification for the watchlist constituents. This is
     * factual reference data (which sector each company belongs to) — the same
     * kind of fixed fact as the watchlist itself. Sector STRENGTH is never
     * hardcoded; it is computed live from these stocks' real prices below.
     */
    private val symbolSector: Map<String, Sector> = buildMap {
        listOf("HDFCBANK","ICICIBANK","SBIN","AXISBANK","KOTAKBANK","INDUSINDBK","BANKBARODA",
            "AUBANK","BANDHANBNK","FEDERALBNK","IDFCFIRSTB","CANBK","RBLBANK","KARURVYSYA",
            "INDIANB","UNIONBANK","CENTRALBK").forEach { put(it, Sector.BANKING) }
        listOf("BAJFINANCE","BAJAJFINSV","HDFCLIFE","SBILIFE","SHRIRAMFIN","CHOLAFIN","SBICARD",
            "ICICIGI","MUTHOOTFIN","PFC","RECLTD","IRFC","LTF","MFSL","LICI","LICHSGFIN",
            "CANFINHOME","ABCAPITAL","MOTILALOFS","CAMS","STARHEALTH","PNBHOUSING","IREDA").forEach { put(it, Sector.FINANCIAL_SERVICES) }
        listOf("TCS","INFY","WIPRO","HCLTECH","TECHM","LTM","LTTS","COFORGE","MPHASIS",
            "PERSISTENT","KPITTECH","OFSS","TATAELXSI","BSOFT","ECLERX").forEach { put(it, Sector.IT) }
        listOf("SUNPHARMA","CIPLA","DRREDDY","DIVISLAB","LUPIN","AUROPHARMA","TORNTPHARM","ALKEM",
            "ZYDUSLIFE","MANKIND","LAURUSLABS","NATCOPHARM","AJANTPHARM","SYNGENE","LALPATHLAB",
            "METROPOLIS","FORTIS","MAXHEALTH","APOLLOHOSP").forEach { put(it, Sector.PHARMA) }
        listOf("MARUTI","TMPV","EICHERMOT","HEROMOTOCO","BAJAJ-AUTO","TVSMOTOR","BALKRISIND",
            "MRF","APOLLOTYRE","CEATLTD","SONACOMS","EXIDEIND","TIINDIA").forEach { put(it, Sector.AUTO) }
        listOf("ITC","HINDUNILVR","NESTLEIND","BRITANNIA","TATACONSUM","DABUR","MARICO","GODREJCP",
            "COLPAL","EMAMILTD","VBL","UBL","RADICO","UNITDSPR","NYKAA","DMART","TRENT","PAGEIND").forEach { put(it, Sector.FMCG) }
        listOf("TATASTEEL","JSWSTEEL","HINDALCO","VEDL","SAIL","NMDC","HINDCOPPER","MOIL","APLAPOLLO").forEach { put(it, Sector.METALS) }
        listOf("RELIANCE","ONGC","BPCL","IOC","GAIL","PETRONET","ATGL").forEach { put(it, Sector.OIL_GAS) }
        listOf("DLF","LODHA","OBEROIRLTY","GODREJPROP").forEach { put(it, Sector.REALTY) }
        listOf("BHARTIARTL","INDUSTOWER","TATACOMM","HFCL").forEach { put(it, Sector.TELECOM) }
        listOf("LT","ADANIPORTS","GMRAIRPORT","RITES","CONCOR","SIEMENS","BEL","HAL","CUMMINSIND",
            "CGPOWER","POLYCAB","GRINDWELL","SUPREMEIND","ASTRAL","ULTRACEMCO","AMBUJACEM","ACC",
            "JKCEMENT","GRASIM","ADANIENT").forEach { put(it, Sector.INFRA) }
        listOf("NTPC","POWERGRID","TATAPOWER","ADANIGREEN","ADANIPOWER","JSWENERGY","NHPC","SJVN",
            "NLCINDIA","TORNTPOWER").forEach { put(it, Sector.POWER) }
        listOf("PIDILITIND","DEEPAKNTR","AARTIIND","ATUL","FLUOROCHEM","GNFC","GSFC","CHAMBLFERT",
            "PIIND","TATACHEM","SOLARINDS").forEach { put(it, Sector.CHEMICALS) }
        listOf("ZEEL").forEach { put(it, Sector.MEDIA) }
        listOf("TITAN","HAVELLS","VOLTAS","CROMPTON","VGUARD","DIXON","KAJARIACER","RAYMOND").forEach { put(it, Sector.CONSUMER_DURABLES) }
    }

    /**
     * Aggregate REAL sector performance from live screener items. Strength =
     * fraction of a sector's stocks trading above their 50-DMA; momentum = the
     * sector's average today % change. Sectors with no data are omitted.
     */
    fun computeSectorPerformance(items: List<StockScreenerItem>): List<SectorPerformance> {
        if (items.isEmpty()) return emptyList()
        return items.groupBy { symbolSector[it.symbol] ?: Sector.OTHERS }
            .filterKeys { it != Sector.OTHERS }
            .map { (sector, list) ->
                val withMa   = list.count { it.ma50 > 0.0 }
                val aboveMa  = list.count { it.ma50 > 0.0 && it.currentPrice > it.ma50 }
                val breadth  = if (withMa > 0) aboveMa.toFloat() / withMa else 0f
                SectorPerformance(
                    sectorName   = sector.displayName,
                    avgChangePct = list.map { it.changePercent }.average(),
                    breadthPct   = breadth,
                    advancers    = list.count { it.changePercent > 0 },
                    decliners    = list.count { it.changePercent < 0 },
                    count        = list.size
                )
            }
            .sortedByDescending { it.breadthPct }
    }

    /**
     * H2 SECTOR STRENGTH → per-symbol tone map for SignalEngine. Distills
     * [computeSectorPerformance] (real per-sector aggregation of constituents'
     * actual % change today — never hardcoded, never synthetic) into
     * symbol → "STRONG" | "WEAK". A symbol appears ONLY when its sector's
     * average move cleared ±[SECTOR_STRENGTH_MIN_PCT] with at least
     * [SECTOR_MIN_CONSTITUENTS] constituents reporting; every other symbol is
     * ABSENT, and absent means SignalEngine scores that stock exactly as it
     * did before this feature existed — no points, no denominator growth
     * (H3 neutral-is-no-data pattern, same as the intraday reads). Keys are
     * ".NS"-stripped upper-case symbols (the engine's shared map convention).
     */
    private fun computeSectorStrengthMap(items: List<StockScreenerItem>): Map<String, String> {
        val perf = computeSectorPerformance(items)
        if (perf.isEmpty()) return emptyMap()
        val toneBySector = HashMap<String, String>()
        for (p in perf) {
            if (p.count < SECTOR_MIN_CONSTITUENTS) continue
            val tone = when {
                p.avgChangePct >=  SECTOR_STRENGTH_MIN_PCT -> "STRONG"
                p.avgChangePct <= -SECTOR_STRENGTH_MIN_PCT -> "WEAK"
                else                                       -> null   // neutral sector → absent
            } ?: continue
            toneBySector[p.sectorName] = tone
        }
        if (toneBySector.isEmpty()) return emptyMap()
        val out = HashMap<String, String>()
        for ((symbol, sector) in symbolSector) {
            val tone = toneBySector[sector.displayName] ?: continue
            out[symbol.uppercase()] = tone
        }
        Log.d(TAG, "H2 sector strength: ${toneBySector.size} sector(s) beyond ±$SECTOR_STRENGTH_MIN_PCT% → ${out.size} symbols tagged")
        return out
    }

    // ─── Delivery geometry — Stock-tab multi-day levels ───────────────────────

    /**
     * DELIVERY GEOMETRY (ui-truth finding): the engine's stop/targets are
     * INTRADAY geometry — 1×/2×/3.5×/5× of an ATR built from ONE day's range,
     * sized for a 3:15 PM square-off. The Stock tab's picks are DELIVERY trades
     * held for days; reusing 1-day geometry there parks the stop inside normal
     * overnight wiggle (healthy positions get stopped out on noise) and calls
     * a single day's fluctuation a "multi-day target". Every daily-list signal
     * is rebuilt on a 10-day volatility unit instead:
     *
     *   unit = stddev of the last [DELIVERY_VOL_LOOKBACK] daily closes — a
     *          "stddev-ish" 10-day swing-size proxy from data we really have
     *          (daily closes; we have no reliable daily high/low history),
     *   floored at [DELIVERY_MIN_VOL_FRAC]×price (a dead-quiet tape still moves)
     *   AND at the signal's own intraday stop distance ÷ [DELIVERY_SL_MULT] —
     *   this pass may only ever WIDEN, never hand out a tighter stop,
     *
     *   stop = 1.5×unit  |  T1 = 3×unit (rr 2.0)  |  T2 = 5.25×unit  |
     *   T3 = 7.5×unit  |  trail = 1.8×unit  — the same shape family as the
     *   intraday levels (T2:T1 = 1.75, T3:T1 = 2.5, trail:stop = 1.2).
     *
     * Rupee cost/net-profit are recomputed at DELIVERY rates (0.20% + slippage
     * — higher STT than intraday), so the daily list never shows an intraday-
     * cost profit for a delivery hold (A5). FAIL-SAFE (B8): fewer than
     * [DELIVERY_VOL_LOOKBACK] closes of history, or any error, and the signal
     * passes through byte-for-byte unchanged — current behavior exactly.
     */
    private fun applyDeliveryGeometry(
        signals: List<TradingSignal>,
        quotes: List<SignalEngine.StockQuote>
    ): List<TradingSignal> {
        if (signals.isEmpty()) return signals
        val closesBySym = HashMap<String, List<Double>>(quotes.size * 2)
        for (q in quotes) closesBySym[q.symbol.removeSuffix(".NS").uppercase()] = q.historicalCloses
        var widened = 0
        val out = signals.map { sig ->
            runCatching {
                widenForDelivery(sig, closesBySym[sig.stockSymbol.removeSuffix(".NS").uppercase()])
                    ?.also { widened++ }
            }.getOrNull() ?: sig
        }
        Log.d(TAG, "Delivery geometry: $widened/${signals.size} daily signals widened to 10-day levels")
        return out
    }

    /**
     * One signal → its delivery-geometry copy, or null when history is
     * missing/too short (caller keeps the original — fail-safe, B8).
     */
    private fun widenForDelivery(sig: TradingSignal, closes: List<Double>?): TradingSignal? {
        if (sig.action == SignalAction.WAIT) return null
        val entry = sig.entryPrice
        if (entry <= 0.0) return null
        val recent = closes?.takeLast(DELIVERY_VOL_LOOKBACK) ?: return null
        if (recent.size < DELIVERY_VOL_LOOKBACK) return null    // short history ⇒ current behavior
        val mean   = recent.average()
        val stddev = kotlin.math.sqrt(recent.sumOf { (it - mean) * (it - mean) } / recent.size)
        // Floors: quiet-tape minimum, and never TIGHTER than the intraday stop
        // this pass replaces (widening must only ever widen).
        val unit = maxOf(stddev, entry * DELIVERY_MIN_VOL_FRAC, sig.expectedLoss / DELIVERY_SL_MULT)
        if (unit <= 0.0 || unit.isNaN()) return null
        val dir    = if (sig.action == SignalAction.BUY) 1.0 else -1.0
        val sl     = entry - dir * unit * DELIVERY_SL_MULT
        val t1     = entry + dir * unit * DELIVERY_T1_MULT
        val profit = abs(t1 - entry)
        val loss   = abs(entry - sl)
        if (loss <= 0.0) return null
        // Honest delivery-rate rupee figures for the engine's placeholder qty.
        // The ViewModel re-sizes qty from the user's real money and the UI
        // recomputes via estimatedCostFor()/netProfitFor(); these keep any path
        // that skips that recompute truthful instead of intraday-cost-flattering.
        val costRs = roundTripCostRs(entry * sig.recommendedQty, intraday = false)
        return sig.copy(
            stopLoss        = sl,
            targetPrice     = t1,
            target2         = entry + dir * unit * DELIVERY_T2_MULT,
            target3         = entry + dir * unit * DELIVERY_T3_MULT,
            trailingStop    = entry + dir * unit * DELIVERY_TRAIL_MULT,
            expectedProfit  = profit,
            expectedLoss    = loss,
            riskReward      = profit / loss,
            estimatedCostRs = costRs,
            netProfitRs     = profit * sig.recommendedQty - costRs
        )
    }

    /**
     * Aggregate REAL market breadth from live screener items across the whole
     * watchlist universe. Returns null when there is no data to compute from.
     */
    fun computeMarketBreadth(items: List<StockScreenerItem>): MarketBreadthData? {
        if (items.isEmpty()) return null
        val adv   = items.count { it.changePercent >  0.05 }
        val dec   = items.count { it.changePercent < -0.05 }
        val unch  = items.size - adv - dec
        val adRatio = if (dec > 0) adv.toDouble() / dec else adv.toDouble()
        val newHighs = items.count { it.high52w > 0.0 && it.currentPrice >= it.high52w * 0.999 }
        val newLows  = items.count { it.low52w  > 0.0 && it.currentPrice <= it.low52w  * 1.001 }
        val withMa   = items.count { it.ma50 > 0.0 }
        val aboveMa  = items.count { it.ma50 > 0.0 && it.currentPrice > it.ma50 }
        val pctAboveMa = if (withMa > 0) aboveMa.toDouble() / withMa else 0.0
        val advShare = if (adv + dec > 0) adv.toDouble() / (adv + dec) else 0.5
        val healthScore = ((advShare * 0.5 + pctAboveMa * 0.5) * 100).toInt().coerceIn(0, 100)
        return MarketBreadthData(adv, dec, unch, adRatio, newHighs, newLows, pctAboveMa, healthScore, items.size)
    }

    /**
     * Fetch comprehensive research data for any stock symbol — Indian first, global fallback.
     * Bare symbols (RELIANCE, AAPL) try NSE (.NS) first, then the raw global symbol;
     * symbols already carrying an exchange suffix (7203.T, INFY.BO) are used as-is.
     */
    suspend fun fetchStockResearch(symbol: String): StockResearchData = withContext(Dispatchers.IO) {
        val cleanSymbol = symbol.removeSuffix(".NS").uppercase()
        val candidates = if (symbol.contains(".")) listOf(symbol) else listOf("$symbol.NS", symbol)

        var quote: SignalEngine.StockQuote? = null
        var currency = "INR"
        outer@ for (candidate in candidates) {
            for (host in YF_HOSTS) {
                val body = runCatching {
                    fetchUrl("https://$host/v8/finance/chart/$candidate?interval=1d&range=1y&includePrePost=false", researchClient)
                }.getOrNull() ?: continue
                val parsed = parseChartResponse(candidate, body)
                if (parsed != null) {
                    quote = parsed
                    // Yahoo's chart meta says what currency this quote is in — a US
                    // stock must never be labeled/spoken as rupees downstream
                    currency = runCatching {
                        JSONObject(body).getJSONObject("chart").getJSONArray("result")
                            .getJSONObject(0).getJSONObject("meta")
                            .optString("currency", "INR")
                    }.getOrDefault("INR").ifBlank { "INR" }.uppercase()
                    break@outer
                }
            }
        }

        if (quote == null) {
            return@withContext StockResearchData(
                symbol = cleanSymbol,
                isLoading = false,
                error = "Could not fetch data for $cleanSymbol. Check symbol."
            )
        }

        val news = runCatching { fetchYahooNews(cleanSymbol) }.getOrElse { emptyList() }

        // Rupee change must be measured from the PREVIOUS close (back-derived from
        // the %) — price×pct/100 overstated up-moves and understated down-moves.
        val prevCloseDenom = 1 + quote.changePercent / 100.0
        val prevClose = if (prevCloseDenom > 0) quote.price / prevCloseDenom else quote.price

        StockResearchData(
            symbol         = cleanSymbol,
            name           = quote.name,
            currentPrice   = quote.price,
            changePercent  = quote.changePercent,
            changeAbsolute = quote.price - prevClose,
            dayHigh        = quote.dayHigh,
            dayLow         = quote.dayLow,
            volume         = quote.volume,
            avgVolume      = quote.avgVolume,
            high52w        = quote.high52w,
            low52w         = quote.low52w,
            ma50           = quote.ma50,
            ma200          = quote.ma200,
            historicalCloses = quote.historicalCloses,
            news           = news,
            marketState    = quote.marketState,
            currency       = currency,
            lastUpdatedAt  = System.currentTimeMillis(),
            isLoading      = false
        )
    }

    // ─── Live indices (Stock-tab ticker strip) ────────────────────────────────

    // Only the two indices ordinary users recognize — more is noise for this audience
    private val indexSymbols = listOf(
        "^BSESN" to "SENSEX",
        "^NSEI"  to "NIFTY 50"
    )

    /**
     * Fetch live values for the main Indian indices. Returns empty list on failure.
     * SUSPENDS while the app is backgrounded (E3d data-saver): HomeViewModel's 60s
     * refresh loop parks here instead of fetching, and the moment the user returns
     * this resumes and fetches fresh values immediately. Manual refresh is
     * unaffected — a user can only tap refresh while the app is visible.
     */
    suspend fun fetchIndices(): List<IndexQuote> = withContext(Dispatchers.IO) {
        indicesForeground.first { it }
        runCatching {
            val nameMap = indexSymbols.toMap()
            val syms = java.net.URLEncoder.encode(indexSymbols.joinToString(",") { it.first }, "UTF-8")
            for (host in YF_HOSTS) {
                val body = runCatching {
                    fetchUrl("https://$host/v7/finance/quote?symbols=$syms&lang=en&region=IN", fastClient)
                }.getOrNull() ?: continue
                val results = JSONObject(body).optJSONObject("quoteResponse")?.optJSONArray("result")
                if (results != null && results.length() > 0) {
                    return@runCatching (0 until results.length()).mapNotNull { i ->
                        runCatching {
                            val q = results.getJSONObject(i)
                            val sym = q.optString("symbol", "")
                            val price = q.optDouble("regularMarketPrice", 0.0)
                            if (price <= 0) return@runCatching null
                            IndexQuote(
                                symbol        = sym,
                                name          = nameMap[sym] ?: sym,
                                price         = price,
                                change        = q.optDouble("regularMarketChange", 0.0),
                                changePercent = q.optDouble("regularMarketChangePercent", 0.0)
                            )
                        }.getOrNull()
                    }
                }
            }
            // v7 failed everywhere — fall back to one v8 chart call per index
            indexSymbols.mapNotNull { (sym, name) ->
                runCatching {
                    val enc = java.net.URLEncoder.encode(sym, "UTF-8")
                    val body = fetchUrl("https://query1.finance.yahoo.com/v8/finance/chart/$enc?interval=1d&range=5d", chartClient)
                    val result = JSONObject(body).getJSONObject("chart")
                        .getJSONArray("result").getJSONObject(0)
                    val meta  = result.getJSONObject("meta")
                    val price = meta.optDouble("regularMarketPrice", 0.0)
                    if (price <= 0) return@runCatching null
                    // chartPreviousClose on a range=5d request is the close ~a week ago —
                    // yesterday's close is the 2nd-to-last entry of the close series
                    val closes = result.optJSONObject("indicators")
                        ?.optJSONArray("quote")?.optJSONObject(0)
                        ?.optJSONArray("close")?.toDoubleList() ?: emptyList()
                    val prev = if (closes.size >= 2 && closes[closes.size - 2] > 0.0)
                        closes[closes.size - 2]
                    else
                        meta.optDouble("previousClose", price)
                    IndexQuote(sym, name, price, price - prev,
                        if (prev > 0) (price - prev) / prev * 100.0 else 0.0)
                }.getOrNull()
            }
        }.getOrElse { emptyList() }
    }

    // ─── Global stock search (search any stock in the world) ──────────────────

    /** Search any listed stock/ETF worldwide via Yahoo search. */
    suspend fun searchStocks(query: String): List<StockSearchResult> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        runCatching {
            val q = java.net.URLEncoder.encode(query.trim(), "UTF-8")
            var body: String? = null
            for (host in YF_HOSTS) {
                body = runCatching {
                    fetchUrl("https://$host/v1/finance/search?q=$q&lang=en-US&region=IN&quotesCount=12&newsCount=0", fastClient)
                }.getOrNull()
                if (body != null) break
            }
            val arr = JSONObject(body ?: return@runCatching emptyList())
                .optJSONArray("quotes") ?: return@runCatching emptyList()
            (0 until arr.length()).mapNotNull { i ->
                runCatching {
                    val item = arr.getJSONObject(i)
                    val type = item.optString("quoteType", "")
                    if (type != "EQUITY" && type != "ETF") return@runCatching null
                    val sym = item.optString("symbol", "")
                    if (sym.isEmpty() || sym.contains("^")) return@runCatching null
                    StockSearchResult(
                        symbol    = sym,
                        name      = item.optString("shortname", item.optString("longname", sym)),
                        exchange  = item.optString("exchDisp", item.optString("exchange", "")),
                        quoteType = type
                    )
                }.getOrNull()
            }
            // Indian listings first (target users trade NSE/BSE); stable within groups
            .sortedByDescending { it.symbol.endsWith(".NS") || it.symbol.endsWith(".BO") }
        }.getOrElse { emptyList() }
    }

    // ─── Core fetch strategy ──────────────────────────────────────────────────

    /**
     * Two-stage fetch — optimised for the expanded 200-symbol watchlist:
     *  Stage A: chunked v7 requests (100 symbols/chunk, 2 chunks) — cheap first try;
     *           currently dead (HTTP 401 without a crumb) but kept in case Yahoo restores it
     *  Stage B: v8 chart parallel (semaphore=30) on the FULL watchlist — the reliable
     *           path with real 1-year close history; v7 failing never shrinks the universe
     *  Both stages run concurrently; v8 wins for overlapping symbols.
     *  NSE fallback: ONLY when the combined result is empty/mostly-empty
     *  (< NSE_FALLBACK_MIN_QUOTES ⇒ Yahoo is down) the NSE live-analysis feed
     *  supplies basic universe quotes; if that fails too, the empty result flows
     *  to the caller and the honest cached/stale behavior stays unchanged (B8).
     */
    private suspend fun fetchAllQuotes(): List<SignalEngine.StockQuote> = coroutineScope {
        // v7 (Yahoo Finance bulk quote) is dead (HTTP 401 without a crumb) - v8 chart
        // is the sole reliable path. v8 covers the FULL watchlist with real 1-year
        // close history; the NSE fallback below activates only when v8 is mostly empty.
        val v8Quotes = fetchAllViaChart(watchlist)
        Log.d(TAG, "Stage B (v8 chart full): ${v8Quotes.size} stocks")

        val combined = v8Quotes
        Log.d(TAG, "Combined: ${combined.size} stocks")

        // ── NSE FALLBACK (single-source risk) ────────────────────────────────
        // Yahoo effectively down ⇒ try NSE for universe quote basics. Never
        // touched on the happy path (the check above short-circuits), and
        // fail-safe throughout: NSE failing leaves `combined` exactly as-is.
        if (combined.size >= NSE_FALLBACK_MIN_QUOTES) return@coroutineScope combined
        val nseQuotes = runCatching { fetchNseFallbackQuotes(combined.map { it.symbol }.toSet()) }
            .getOrElse { emptyList() }
        if (nseQuotes.isNotEmpty())
            Log.w(TAG, "Yahoo mostly down (${combined.size} quotes) — NSE fallback supplied ${nseQuotes.size} more")
        combined + nseQuotes
    }

    /**
     * NSE fallback: build basic universe quotes from NSE's own live-analysis
     * feed (most-active + gainers + losers, de-duplicated) when Yahoo is down.
     * Quote basics only — price / % change / volume / day range; when an
     * earlier cycle today cached a symbol's 1-year history, the NSE price is
     * merged onto it so MAs, sparklines and period filters keep working.
     * Symbols outside the 200-stock universe are ignored; symbols Yahoo DID
     * return are never overwritten. Every step is fail-safe: any error returns
     * an empty list and the caller keeps the honest cached/stale behavior (B8).
     */
    private suspend fun fetchNseFallbackQuotes(
        alreadyHave: Set<String>
    ): List<SignalEngine.StockQuote> = runCatching {
        val analysis = nseRepository.getUniverseSnapshot()
        val rows = LinkedHashMap<String, NseStockData>()
        for (row in analysis.mostActive + analysis.gainers + analysis.losers) {
            val sym = row.symbol.trim().uppercase()
            if (sym.isNotEmpty() && !rows.containsKey(sym)) rows[sym] = row
        }
        if (rows.isEmpty()) return@runCatching emptyList<SignalEngine.StockQuote>()
        val watchSet   = watchlist.toSet()
        val today      = istToday()
        val tradingNow = runCatching { RiskGuard.isTradingHoursNow() }.getOrDefault(false)

        rows.entries.mapNotNull { (sym, row) ->
            runCatching {
                val nsSymbol = "$sym.NS"
                if (nsSymbol !in watchSet || nsSymbol in alreadyHave) return@runCatching null
                val price = when {
                    row.ltp > 0.0       -> row.ltp
                    row.lastPrice > 0.0 -> row.lastPrice
                    else                -> return@runCatching null
                }
                val prev = row.previousPrice
                // Derive % from real prices when possible; NSE's netPrice (the
                // reported % change) only as a last resort.
                val changePercent = if (prev > 0.0) (price - prev) / prev * 100.0 else row.netPrice
                val gapPercent = if (row.open > 0.0 && prev > 0.0) (row.open - prev) / prev * 100.0 else 0.0

                // Merge onto today's cached 1-year history when we have it.
                val cached = historyCache[nsSymbol]
                var ma50 = 0.0
                var ma200 = 0.0
                val closes: List<Double>
                if (cached != null) {
                    val merged = ArrayList(cached.closes)
                    when {
                        cached.lastBarDate == today && merged.isNotEmpty() ->
                            merged[merged.size - 1] = price   // update today's bar
                        tradingNow ->
                            merged.add(price)                 // first bar of today
                        else -> { /* market closed & history ends earlier — never invent a bar */ }
                    }
                    closes = merged
                    ma50   = if (merged.size >= 50)  merged.takeLast(50).average()  else cached.ma50
                    ma200  = if (merged.size >= 200) merged.takeLast(200).average() else cached.ma200
                } else {
                    // No history — consumers degrade exactly like v7-only quotes.
                    closes = emptyList()
                }

                SignalEngine.StockQuote(
                    symbol           = nsSymbol,
                    name             = cached?.name ?: sym,
                    price            = price,
                    changePercent    = changePercent,
                    volume           = row.tradedQuantity,
                    avgVolume        = cached?.avgVolume ?: 0L,   // 0 = unknown; consumers guard on > 0
                    high52w          = cached?.high52w ?: 0.0,
                    low52w           = cached?.low52w ?: 0.0,
                    ma50             = ma50,
                    ma200            = ma200,
                    dayHigh          = if (row.high > 0.0) row.high else price,
                    dayLow           = if (row.low > 0.0) row.low else price,
                    marketState      = if (tradingNow) "REGULAR" else "CLOSED",
                    historicalCloses = closes,
                    gapPercent       = gapPercent
                )
            }.getOrNull()
        }
    }.getOrElse {
        Log.w(TAG, "NSE fallback failed: ${it.message}")
        emptyList()
    }

    /**
     * Chunked v7 bulk fetch — splits [symbols] into [chunkSize]-symbol batches,
     * fetches each chunk independently, then merges results.
     * Handles watchlists of any size reliably.
     */
    private suspend fun fetchV7Chunked(symbols: List<String>, chunkSize: Int = 100): List<SignalEngine.StockQuote> = coroutineScope {
        // Chunks fetched in PARALLEL (was sequential — halved the wait for 200 symbols)
        val all = symbols.chunked(chunkSize).mapIndexed { idx, chunk ->
            async(Dispatchers.IO) {
                for (host in YF_HOSTS) {
                    val body = runCatching {
                        val syms = chunk.joinToString(",")
                        fetchUrl("https://$host/v7/finance/quote?symbols=$syms&lang=en&region=IN", fastClient)
                    }.getOrNull() ?: continue
                    val q = parseV7Quotes(body)
                    if (q.isNotEmpty()) {
                        Log.d(TAG, "v7 chunk $idx: ${q.size}/${chunk.size} from $host")
                        return@async q
                    }
                }
                Log.w(TAG, "v7 chunk $idx failed entirely")
                emptyList()
            }
        }.flatMap { it.await() }
        if (all.isEmpty()) throw Exception("v7 returned no data from any host")
        all
    }

    /** Batch v7 for specific symbols (used by ScannerWorker price checks) */
    private fun fetchV7Batch(symbols: List<String>, client: OkHttpClient): List<SignalEngine.StockQuote> {
        return try {
            val syms = symbols.joinToString(",")
            for (host in YF_HOSTS) {
                val body = runCatching {
                    fetchUrl("https://$host/v7/finance/quote?symbols=$syms&lang=en&region=IN", client)
                }.getOrNull() ?: continue
                val q = parseV7Quotes(body)
                if (q.isNotEmpty()) return q
            }
            emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "fetchV7Batch failed: ${e.message}")
            emptyList()
        }
    }

    // ─── Stage A: Yahoo Finance v7 ────────────────────────────────────────────

    private fun parseV7Quotes(body: String): List<SignalEngine.StockQuote> = runCatching {
        val results = JSONObject(body)
            .getJSONObject("quoteResponse")
            .optJSONArray("result") ?: return emptyList()
        (0 until results.length()).mapNotNull { i ->
            runCatching {
                val q = results.getJSONObject(i)
                val price = q.optDouble("regularMarketPrice", 0.0)
                if (price <= 0.0) return@mapNotNull null
                val open = q.optDouble("regularMarketOpen", 0.0)
                val pc   = q.optDouble("regularMarketPreviousClose", 0.0)
                val gap  = if (open > 0 && pc > 0) (open - pc) / pc * 100.0 else 0.0
                SignalEngine.StockQuote(
                    symbol        = q.optString("symbol", ""),
                    name          = q.optString("shortName", q.optString("longName", q.optString("symbol", ""))),
                    price         = price,
                    changePercent = q.optDouble("regularMarketChangePercent", 0.0),
                    volume        = q.optLong("regularMarketVolume", 0L),
                    // 0 = genuinely unknown. Every volRatio consumer guards on `> 0`
                    // and stays neutral (1.0); coercing to 1 would fabricate a huge ratio.
                    avgVolume     = q.optLong("averageDailyVolume3Month", 0L),
                    high52w       = q.optDouble("fiftyTwoWeekHigh", 0.0),
                    low52w        = q.optDouble("fiftyTwoWeekLow", 0.0),
                    ma50          = q.optDouble("fiftyDayAverage", 0.0),
                    ma200         = q.optDouble("twoHundredDayAverage", 0.0),
                    dayHigh       = q.optDouble("regularMarketDayHigh", price),
                    dayLow        = q.optDouble("regularMarketDayLow", price),
                    marketState   = q.optString("marketState", "REGULAR"),
                    // v7 doesn't give historical closes — historicalCloses stays empty
                    gapPercent    = gap
                )
            }.getOrNull()
        }
    }.getOrElse { emptyList() }

    // ─── Daily 1-year history cache (refresh-cost diet) ──────────────────────

    /**
     * One symbol's once-per-IST-day chart facts. [closes] is the full 1-year
     * forward-filled daily close series — exactly what every historicalCloses
     * consumer expects; [lastBarDate] is the IST date of its last bar so the
     * cheap range=5d update can merge new bars deterministically (same-date
     * bar replaces the last close, newer dates append).
     */
    private data class DayHistory(
        val closes: List<Double>,
        val lastBarDate: String,
        val name: String,
        val high52w: Double,
        val low52w: Double,
        val avgVolume: Long,
        val ma50: Double,
        val ma200: Double
    )

    // In-memory, process-lifetime. Cheap to rebuild: the first fetch of a new
    // IST day (or a fresh process) is the one full 1-year pass.
    private val historyCache = ConcurrentHashMap<String, DayHistory>()
    @Volatile private var historyCacheDate: String = ""

    /** Epoch-seconds → IST calendar date (yyyy-MM-dd). */
    private fun istDateOf(epochSeconds: Long): String {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        fmt.timeZone = java.util.TimeZone.getTimeZone("Asia/Kolkata")
        return fmt.format(java.util.Date(epochSeconds * 1000L))
    }

    private fun istToday(): String = istDateOf(System.currentTimeMillis() / 1000L)

    // ─── Stage B: Yahoo Finance v8 chart ─────────────────────────────────────

    /**
     * REFRESH-COST DIET: the 1-year history only changes once per trading day,
     * so the heavy range=1y charts run on the FIRST fetch of each IST day (or
     * for symbols that missed that pass — they self-heal with a full fetch on
     * later cycles). Every other cycle — the 30s loop and manual refresh alike
     * — fetches a tiny range=5d chart per symbol and merges the live fields
     * onto the cached history: every consumer sees the same field set
     * (historicalCloses, MAs, 52-week bounds included), for ~10x less data and
     * wall-time per cycle.
     */
    private suspend fun fetchAllViaChart(
        symbols: List<String> = watchlist
    ): List<SignalEngine.StockQuote> = coroutineScope {
        val today     = istToday()
        val dailyPass = historyCacheDate != today
        val semaphore = Semaphore(30)   // full pass: 200 charts in ~10-14s; cheap pass: ~1-2s
        val quotes = symbols.mapIndexed { idx, symbol ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    val host   = YF_HOSTS[idx % 2]
                    val cached = if (dailyPass) null else historyCache[symbol]
                    if (cached != null) {
                        runCatching { fetchCheapUpdate(symbol, host, cached) }.getOrElse {
                            Log.w(TAG, "v8 5d update failed $symbol: ${it.message}")
                            null
                        }
                    } else {
                        runCatching { fetchChartSymbol(symbol, host) }.getOrElse {
                            Log.w(TAG, "v8 chart failed $symbol: ${it.message}")
                            null
                        }
                    }
                }
            }
        }.map { it.await() }.filterNotNull()

        if (dailyPass) {
            // Only count the day's full pass as done when it actually covered
            // most of the universe — an outage must not mark the day "done".
            val minCovered = (symbols.size * HISTORY_PASS_MIN_COVERAGE).toInt().coerceAtLeast(1)
            if (quotes.size >= minCovered) {
                historyCacheDate = today
                Log.d(TAG, "Daily 1y history pass done: ${quotes.size}/${symbols.size} cached for $today")
            } else {
                Log.w(TAG, "Daily 1y history pass too thin (${quotes.size}/${symbols.size}) — retrying next cycle")
            }
        }
        quotes
    }

    private fun fetchChartSymbol(symbol: String, host: String): SignalEngine.StockQuote {
        val url = "https://$host/v8/finance/chart/$symbol?interval=1d&range=1y&includePrePost=false"
        val body = fetchUrl(url, chartClient)
        val quote = parseChartResponse(symbol, body)
            ?: throw Exception("Could not parse chart for $symbol")
        // Refresh-cost diet: remember today's full 1-year history so every
        // later cycle can take the tiny range=5d path. Best-effort — a cache
        // miss only means this symbol pays full price next cycle, never wrong data.
        runCatching {
            val tsArr = JSONObject(body).getJSONObject("chart").getJSONArray("result")
                .getJSONObject(0).optJSONArray("timestamp")
            val lastTs = if (tsArr != null && tsArr.length() > 0) tsArr.optLong(tsArr.length() - 1, 0L) else 0L
            if (lastTs > 0L && quote.historicalCloses.isNotEmpty()) {
                historyCache[symbol] = DayHistory(
                    closes      = quote.historicalCloses,
                    lastBarDate = istDateOf(lastTs),
                    name        = quote.name,
                    high52w     = quote.high52w,
                    low52w      = quote.low52w,
                    avgVolume   = quote.avgVolume,
                    ma50        = quote.ma50,
                    ma200       = quote.ma200
                )
            }
        }
        return quote
    }

    /**
     * REFRESH-COST DIET cheap path: one tiny interval=1d&range=5d chart call
     * (~40x smaller than range=1y) updates the live fields — price, change,
     * volume, day high/low, market state, gap — and merges the recent bars
     * onto the 1-year close history cached by today's full pass. Consumers get
     * the exact same field expectations as the full path. Returns null on any
     * problem — the caller drops the symbol this cycle, exactly like a failed
     * full fetch, and it retries next cycle (never wrong data, B8).
     */
    private fun fetchCheapUpdate(
        symbol: String,
        host: String,
        cached: DayHistory
    ): SignalEngine.StockQuote? {
        val url = "https://$host/v8/finance/chart/$symbol?interval=1d&range=5d&includePrePost=false"
        val body = fetchUrl(url, chartClient)
        val results = JSONObject(body).getJSONObject("chart").optJSONArray("result") ?: return null
        if (results.length() == 0) return null
        val result = results.getJSONObject(0)
        val meta   = result.getJSONObject("meta")

        val price = meta.optDouble("regularMarketPrice", 0.0)
        if (price <= 0.0) return null

        val quoteData = result.optJSONObject("indicators")
            ?.optJSONArray("quote")?.optJSONObject(0)
        val closeArr = quoteData?.optJSONArray("close")
        val openArr  = quoteData?.optJSONArray("open")
        val tsArr    = result.optJSONArray("timestamp")

        // Recent daily bars, oldest → newest, keyed by IST date.
        class Bar(val date: String, val close: Double, val open: Double)
        val bars = ArrayList<Bar>()
        if (tsArr != null && closeArr != null) {
            for (i in 0 until minOf(tsArr.length(), closeArr.length())) {
                val ts = tsArr.optLong(i, 0L)
                if (ts <= 0L) continue
                val c = closeArr.optDouble(i, Double.NaN)
                if (c.isNaN() || c <= 0.0) continue
                val o = openArr?.optDouble(i, Double.NaN) ?: Double.NaN
                bars += Bar(istDateOf(ts), c, if (o.isNaN() || o <= 0.0) 0.0 else o)
            }
        }
        if (bars.isEmpty()) return null   // no usable bars ⇒ treat as a failed fetch

        // Merge the new bars onto the cached year: a same-date bar replaces the
        // last close (fresher snapshot of the same session), newer dates append.
        val closes = ArrayList(cached.closes)
        var lastDate = cached.lastBarDate
        for (b in bars) {
            when {
                b.date > lastDate                         -> { closes.add(b.close); lastDate = b.date }
                b.date == lastDate && closes.isNotEmpty() -> closes[closes.size - 1] = b.close
                else                                      -> { /* older bar — already in history */ }
            }
        }
        if (closes.isEmpty()) return null
        val today = istToday()
        // The meta price is the freshest tick — let the live session's close
        // reflect it (the bar series can lag it by a few seconds).
        if (lastDate == today) closes[closes.size - 1] = price

        // Same prev-close rule as the full path: the close BEFORE the last bar.
        // (range=5d chartPreviousClose is ~a week old — never use it.)
        val prevClose = if (closes.size >= 2 && closes[closes.size - 2] > 0.0)
            closes[closes.size - 2]
        else
            meta.optDouble("previousClose", price)
        val changePercent = if (prevClose > 0) (price - prevClose) / prevClose * 100.0 else 0.0
        // Gap needs TODAY's open — only when today's bar actually exists.
        val todayOpen  = bars.last().takeIf { it.date == today }?.open ?: 0.0
        val gapPercent = if (todayOpen > 0 && prevClose > 0) (todayOpen - prevClose) / prevClose * 100.0 else 0.0

        val high52w = meta.optDouble("fiftyTwoWeekHigh", 0.0).takeIf { it > 0.0 } ?: cached.high52w
        val low52w  = meta.optDouble("fiftyTwoWeekLow", 0.0).takeIf { it > 0.0 } ?: cached.low52w
        // Same MA rules as the full path: a true 50/200-DMA needs 50/200 closes;
        // otherwise keep the daily pass's value (which may be Yahoo's own).
        val ma50  = if (closes.size >= 50)  closes.takeLast(50).average()  else cached.ma50
        val ma200 = if (closes.size >= 200) closes.takeLast(200).average() else cached.ma200
        val name  = meta.optString("shortName", "").ifBlank { cached.name }

        // Advance the cache so the NEXT cheap merge starts from this bar.
        historyCache[symbol] = cached.copy(
            closes = closes, lastBarDate = lastDate, name = name,
            high52w = high52w, low52w = low52w, ma50 = ma50, ma200 = ma200
        )

        return SignalEngine.StockQuote(
            symbol           = symbol,
            name             = name,
            price            = price,
            changePercent    = changePercent,
            volume           = meta.optLong("regularMarketVolume", 0L),
            avgVolume        = cached.avgVolume,
            high52w          = high52w,
            low52w           = low52w,
            ma50             = ma50,
            ma200            = ma200,
            dayHigh          = meta.optDouble("regularMarketDayHigh", price),
            dayLow           = meta.optDouble("regularMarketDayLow", price),
            marketState      = meta.optString("marketState", "CLOSED"),
            historicalCloses = closes,
            gapPercent       = gapPercent
        )
    }

    private fun parseChartResponse(symbol: String, body: String): SignalEngine.StockQuote? {
        return runCatching {
            val chart   = JSONObject(body).getJSONObject("chart")
            val results = chart.optJSONArray("result") ?: return null
            if (results.length() == 0) return null

            val result = results.getJSONObject(0)
            val meta   = result.getJSONObject("meta")

            val price = meta.optDouble("regularMarketPrice", 0.0)
            if (price <= 0.0) return null

            val volume        = meta.optLong("regularMarketVolume", 0L)
            val high52w       = meta.optDouble("fiftyTwoWeekHigh", 0.0)
            val low52w        = meta.optDouble("fiftyTwoWeekLow", 0.0)
            // No fabricated range — when Yahoo omits day high/low, default to price
            // (zero-width). Consumers treat dayHigh<=dayLow as "no real range".
            val dayHigh       = meta.optDouble("regularMarketDayHigh", price)
            val dayLow        = meta.optDouble("regularMarketDayLow", price)
            val marketState   = meta.optString("marketState", "CLOSED")
            val name          = meta.optString("shortName", meta.optString("longName", symbol.removeSuffix(".NS")))

            var ma50  = meta.optDouble("fiftyDayAverage", 0.0)
            var ma200 = meta.optDouble("twoHundredDayAverage", 0.0)
            var avgVol = meta.optLong("averageDailyVolume3Month", 0L)

            val quoteData = result.optJSONObject("indicators")
                ?.optJSONArray("quote")
                ?.optJSONObject(0)

            val closes  = quoteData?.optJSONArray("close")?.toDoubleList()  ?: emptyList()
            val volumes = quoteData?.optJSONArray("volume")?.toLongList()   ?: emptyList()

            // Today's open (last entry of the open series) — for gap-vs-prev-close
            val opens     = quoteData?.optJSONArray("open")
            val todayOpen = opens?.let { arr ->
                if (arr.length() > 0) arr.optDouble(arr.length() - 1, 0.0).takeIf { !it.isNaN() && it > 0 } else null
            } ?: 0.0

            // Today's change must compare against the PREVIOUS DAY's close. Yahoo's
            // meta.chartPreviousClose for a range=1y request is the close from ~1 year
            // ago, so using it renders the 1-year move as if it were today's move.
            val prevClose = if (closes.size >= 2 && closes[closes.size - 2] > 0.0)
                closes[closes.size - 2]
            else
                meta.optDouble("chartPreviousClose", meta.optDouble("previousClose", price))
            val changePercent = if (prevClose > 0) (price - prevClose) / prevClose * 100.0 else 0.0
            val gapPercent    = if (todayOpen > 0 && prevClose > 0) (todayOpen - prevClose) / prevClose * 100.0 else 0.0

            if (closes.size >= 50 && ma50 == 0.0)
                ma50  = closes.takeLast(50).average()
            // A true 200-DMA needs 200 closes — a shorter average mislabeled as the
            // 200-DMA would feed SignalEngine's biggest factor with wrong data.
            // Left at 0.0 otherwise; the engine handles a missing MA via its dataCap.
            if (closes.size >= 200 && ma200 == 0.0)
                ma200 = closes.takeLast(200).average()
            if (volumes.isNotEmpty() && avgVol == 0L) {
                val last63 = volumes.takeLast(minOf(volumes.size, 63))
                avgVol = last63.average().toLong()
            }

            SignalEngine.StockQuote(
                symbol           = symbol,
                name             = name,
                price            = price,
                changePercent    = changePercent,
                volume           = volume,
                // 0 when Yahoo omits it and no volume history exists — volRatio
                // consumers guard on `> 0` and stay neutral rather than fabricating.
                avgVolume        = avgVol,
                high52w          = high52w,
                low52w           = low52w,
                ma50             = ma50,
                ma200            = ma200,
                dayHigh          = dayHigh,
                dayLow           = dayLow,
                marketState      = marketState,
                historicalCloses = closes,  // full 1-year history for period filters
                gapPercent       = gapPercent
            )
        }.getOrNull()
    }

    /**
     * One entry per trading day. Yahoo returns null for holidays/halted sessions —
     * forward-fill those instead of dropping them, so takeLast(N) still means
     * "last N trading days" and closes[size-2] is really the previous session.
     */
    private fun JSONArray.toDoubleList(): List<Double> {
        val list = mutableListOf<Double>()
        var lastValid = Double.NaN
        for (i in 0 until length()) {
            val v = optDouble(i, Double.NaN)
            if (!v.isNaN() && v > 0) lastValid = v
            if (!lastValid.isNaN()) list.add(lastValid)   // leading gaps are skipped
        }
        return list
    }

    private fun JSONArray.toLongList(): List<Long> {
        val list = mutableListOf<Long>()
        for (i in 0 until length()) {
            val v = optLong(i, 0L)
            if (v > 0) list.add(v)
        }
        return list
    }

    // ─── News intelligence — signals adjusted by real headlines ───────────────

    // Per-symbol news cache (P0 #2 #3): mirrors intradayReadCache so the 30s auto-
    // refresh reuses recent headlines instead of firing up to NEWS_VET_CAP network
    // calls every tick. ONE cache covers BOTH sources (Yahoo search + Google News
    // RSS) and stores the RELEVANCE-FILTERED merged list — the research card shows
    // exactly this; the sentiment vet layers its 72h freshness cutoff on top.
    private val newsCache = ConcurrentHashMap<String, Pair<Long, List<ResearchNewsItem>>>()

    /**
     * One stock's fresh-headline verdict (fetched once per refresh cycle).
     * [tier] is the H4 event tier (TIER_BIG/MEDIUM/ROUTINE) of the dominant-side
     * headlines — it scales how far the news may move confidence.
     */
    private data class NewsVet(val sentiment: String, val pos: Int, val neg: Int, val tier: Int)

    /**
     * Fetch the latest headlines (Yahoo search + Google News RSS fallback) ONCE
     * for the top candidate signals and
     * classify each stock's sentiment. Split from the apply step so the H14
     * intraday re-run can fold the SAME verdicts into a second signal list
     * without doubling the Yahoo news calls.
     *
     * EVERY signal here already cleared the engine's 70 floor and CAN be shown to
     * the user — the Signals tab lists them all and the daily picks are the top 4.
     * A BUY carrying fresh BAD news is a direct loss cause, so news must be READ
     * for the whole actionable set, not just the first 8. The old code vetted
     * signals.take(8) and re-appended signals.drop(8) unchecked, so a BUY ranked
     * 9th or lower could reach the user with unread bad-news risk.
     *
     * News is the only real cost (one Yahoo call per stock), so we spend that
     * budget on BUY signals FIRST — bad news on a BUY is what makes the user buy
     * and lose — and cap the fetch count at NEWS_VET_CAP. A signal beyond the cap
     * keeps newsSentiment = "NONE" (honestly "not read", B6), never a guess.
     */
    private suspend fun fetchNewsSentiments(signals: List<TradingSignal>): Map<String, NewsVet> = coroutineScope {
        if (signals.isEmpty()) return@coroutineScope emptyMap()
        // BUYs first (bad news on a BUY is what makes the user buy and lose), dedup
        // by symbol, and carry the company NAME so the relevance guard can run.
        val ordered = signals.filter { it.action == SignalAction.BUY } +
                      signals.filter { it.action != SignalAction.BUY }
        val vetTargets = LinkedHashMap<String, String>()   // stockSymbol -> stockName
        for (s in ordered) if (!vetTargets.containsKey(s.stockSymbol)) vetTargets[s.stockSymbol] = s.stockName
        val capped = vetTargets.entries.take(NEWS_VET_CAP)

        capped.map { (sym, name) ->
            async(Dispatchers.IO) {
                // Passing the company NAME lets the fetcher run its own relevance
                // guard and build the Google News fallback query correctly.
                val news = runCatching { fetchYahooNews(sym, name) }.getOrElse { emptyList() }
                if (news.isEmpty()) return@async null   // nothing read — stays honest "NONE"
                val now = System.currentTimeMillis()
                // ── RELEVANCE GUARD + FRESHNESS CUTOFF (P0 #2 #1, #2) ──────────
                // A headline may feed sentiment ONLY if it (a) actually names this
                // stock and (b) is <= 72h old with a known date. fetchYahooNews now
                // relevance-filters BOTH sources at the source (the research card
                // shows the same filtered list), so (a) is a belt-and-braces
                // re-check; the freshness cutoff is enforced HERE — a relevant but
                // older Yahoo item may still show on the research card (its date is
                // visible there) yet can never move this stock's confidence (B6).
                val scoreable = news.filter { item ->
                    item.publishedAt > 0L &&
                    now - item.publishedAt <= NEWS_MAX_AGE_MS &&
                    headlineMentionsStock(item.headline, sym, name)
                }
                if (scoreable.isEmpty()) return@async null   // nothing scoreable → honest "NONE"
                // ── H4 3-TIER WEIGHTED DOMINANCE (P0 #2 #4) ────────────────────
                // Each relevant fresh headline votes with a weight = its event tier,
                // so one BIG event (fraud/merger) is never out-voted by routine
                // chatter, and the surviving side's strongest tier scales the delta.
                var posScore = 0; var negScore = 0
                var posCount = 0; var negCount = 0
                var posTier  = TIER_ROUTINE; var negTier = TIER_ROUTINE
                for (item in scoreable) {
                    val tier = newsEventTier(item.headline)
                    when (item.sentiment) {
                        "POSITIVE" -> { posScore += tier; posCount++; if (tier > posTier) posTier = tier }
                        "NEGATIVE" -> { negScore += tier; negCount++; if (tier > negTier) negTier = tier }
                    }
                }
                if (posCount == 0 && negCount == 0) return@async null   // only neutral relevant news
                val sentiment: String
                val tier: Int
                when {
                    negScore > posScore -> { sentiment = "NEGATIVE"; tier = negTier }
                    posScore > negScore -> { sentiment = "POSITIVE"; tier = posTier }
                    else                -> { sentiment = "NEUTRAL";  tier = TIER_ROUTINE }
                }
                sym to NewsVet(sentiment, posCount, negCount, tier)
            }
        }.mapNotNull { it.await() }.toMap()
    }

    /**
     * Fold pre-fetched headline sentiment into the signals: good news for a BUY →
     * confidence up, bad news for a BUY → confidence down hard (news risk), and
     * vice versa for SELL. Adds a reason line so the user SEES that news was read.
     * Pure (no network) — safe to apply to both the intraday-powered list and the
     * daily-only baseline with the same verdicts.
     */
    private fun applyNewsSentiments(
        signals: List<TradingSignal>,
        vets: Map<String, NewsVet>
    ): List<TradingSignal> {
        if (signals.isEmpty()) return signals
        val processed = signals.map { sig ->
            // Outside the vet cap / nothing read — left honest ("NONE"), unchanged.
            val vet = vets[sig.stockSymbol] ?: return@map sig
            val sentiment = vet.sentiment
            // H4 3-tier magnitudes (P0 #2 #4), scaled by how market-moving the
            // event is. Within EVERY tier the penalty outweighs the reward, so the
            // old negative-heavier asymmetry (A5/B6) is preserved: bad news can only
            // ever LOWER a BUY's confidence, never inflate it into a pick.
            //   BIG (fraud/probe/recall/merger/results-surprise) reward +10 / pen -15
            //   MEDIUM (guidance/order/approval/rating/results)   reward  +5 / pen  -8
            //   ROUTINE (dividend/bonus/generic move)             reward  +1 / pen  -2
            val reward: Int
            val penalty: Int
            when (vet.tier) {
                TIER_BIG    -> { reward = 10; penalty = -15 }
                TIER_MEDIUM -> { reward =  5; penalty =  -8 }
                else        -> { reward =  1; penalty =  -2 }
            }
            val delta = when {
                sentiment == "POSITIVE" && sig.action == SignalAction.BUY  -> reward
                sentiment == "NEGATIVE" && sig.action == SignalAction.BUY  -> penalty
                sentiment == "NEGATIVE" && sig.action == SignalAction.SELL -> reward
                sentiment == "POSITIVE" && sig.action == SignalAction.SELL -> penalty
                else                                                        -> 0
            }
            val newsReason = when {
                sentiment == "POSITIVE" && vet.tier >= TIER_BIG -> "📰 Big positive news for this stock"
                sentiment == "POSITIVE" -> "📰 Good news for this stock (${vet.pos} recent ${if (vet.pos == 1) "headline" else "headlines"})"
                sentiment == "NEGATIVE" && vet.tier >= TIER_BIG -> "📰 Serious bad news — be very careful"
                sentiment == "NEGATIVE" -> "📰 Bad news — be extra careful (${vet.neg} recent ${if (vet.neg == 1) "headline" else "headlines"})"
                else -> null
            }
            val adjusted = (sig.confidence + delta).coerceIn(30, 97)
            // Risk tier must reflect the news-adjusted confidence, not the
            // pre-news tier (LOW >= 90, MEDIUM >= 84, else HIGH)
            val riskLevel = when {
                adjusted >= 90 -> RiskLevel.LOW
                adjusted >= 84 -> RiskLevel.MEDIUM
                else           -> RiskLevel.HIGH
            }
            sig.copy(
                confidence     = adjusted,
                riskLevel      = riskLevel,
                isBeginnerSafe = riskLevel == RiskLevel.LOW,
                newsSentiment  = sentiment,
                reasons        = if (newsReason != null) sig.reasons + newsReason else sig.reasons
            )
        }

        // A signal whose news pushed it below the engine's 70 floor must NOT survive
        // just because the delta was applied after the floor. Only vetted signals can
        // fall below 70 here (unvetted ones are unchanged and were already >= 70).
        val vetted = processed.filter { it.confidence >= 70 }
        Log.d(TAG, "News applied to ${vets.size} of ${signals.size} signals (cap $NEWS_VET_CAP); ${processed.size - vetted.size} dropped after 70-floor, ${vetted.size} kept")
        // Re-rank with news factored in
        return vetted.sortedByDescending { it.confidence }
    }

    // ─── Stock news — Yahoo search + Google News RSS (REAL NEWS AT LAST) ──────

    /**
     * Fetches recent, RELEVANT news for a stock symbol from two real sources:
     *
     *  1. Yahoo Finance search queried with the FULL ".NS" symbol — the 2026-07
     *     audit probe showed q="SYM.NS" resolves the CORRECT NSE company, while
     *     the bare symbol returns world noise for most NSE stocks. Symbols that
     *     already carry another exchange suffix (7203.T, INFY.BO) query as-is;
     *     a bare global symbol (AAPL) self-heals by retrying without ".NS" when
     *     the NSE-suffixed query finds nothing.
     *  2. Google News RSS search as a SECOND source whenever Yahoo yields fewer
     *     than 2 relevant items — parsed with plain regex (no new dependencies).
     *
     * The returned list is RELEVANCE-FILTERED for BOTH sources (the headline
     * must actually name the stock — headlineMentionsStock), so the research
     * card never shows world headlines under a stock (B6). Google items must
     * additionally be <= 72h fresh with a known date (NEWS_MAX_AGE_MS); an
     * older Yahoo item that DOES name the stock is kept for display (the card
     * shows its date) and the sentiment vet's own freshness cutoff keeps it
     * from ever moving confidence. Fail-safe everywhere: either source failing
     * contributes nothing; BOTH failing returns empty WITHOUT caching, so a
     * transient outage stays retryable next cycle (B8).
     *
     * [companyName] (optional) powers the relevance guard and the Google query;
     * when blank it is resolved from today's history cache or Yahoo's own quote
     * resolution, and if still unknown the guard matches on the ticker word
     * only — the SAFE direction (missing a real headline, never a wrong one).
     */
    fun fetchYahooNews(symbol: String, companyName: String = ""): List<ResearchNewsItem> {
        // ── NEWS TTL CACHE (P0 #2 #3) ─────────────────────────────────────────
        // Reuse a recent read — covering BOTH sources — so the 30s auto-refresh
        // can't fire fresh network calls for the same symbol every tick (E3d).
        // Keyed on the ".NS"-stripped upper-case symbol so display + vet share it.
        val key = symbol.removeSuffix(".NS").uppercase()
        newsCache[key]?.let { (ts, cached) ->
            if (System.currentTimeMillis() - ts < NEWS_CACHE_TTL_MS) return cached
        }
        return runCatching {
            var name = companyName.ifBlank {
                runCatching { historyCache["$key.NS"]?.name }.getOrNull() ?: ""
            }

            // ── Source 1: Yahoo search, FULL .NS symbol first ─────────────────
            val queries = when {
                symbol.contains(".") && !symbol.endsWith(".NS") -> listOf(symbol)
                else                                            -> listOf("$key.NS", key)
            }
            var yahooRead = false
            var yahooItems: List<ResearchNewsItem> = emptyList()
            for (q in queries) {
                // URL-encode — symbols like M&M would otherwise break the query
                val enc = java.net.URLEncoder.encode(q, "UTF-8")
                val body = runCatching {
                    fetchUrl(
                        "https://query1.finance.yahoo.com/v1/finance/search" +
                            "?q=$enc&lang=en-US&region=IN" +
                            "&quotesCount=1&newsCount=10&enableFuzzyQuery=false" +
                            "&newsQuerySchema=2&enableCb=false&enableEnhancedTrivialQuery=true",
                        fastClient
                    )
                }.getOrElse {
                    runCatching {
                        fetchUrl(
                            "https://query2.finance.yahoo.com/v1/finance/search?q=$enc&lang=en-US&region=IN&quotesCount=1&newsCount=10",
                            fastClient
                        )
                    }.getOrNull()
                } ?: continue
                val json = runCatching { JSONObject(body) }.getOrNull() ?: continue
                yahooRead = true
                // The search response resolves the company itself — free, correct
                // name for the relevance guard + Google query (probe-confirmed).
                if (name.isBlank()) {
                    val q0 = json.optJSONArray("quotes")?.optJSONObject(0)
                    name = q0?.optString("shortname").orEmpty()
                        .ifBlank { q0?.optString("longname").orEmpty() }
                }
                val newsArr = json.optJSONArray("news")
                val parsed: List<ResearchNewsItem> = if (newsArr == null) emptyList() else
                    (0 until newsArr.length()).mapNotNull { i ->
                        runCatching {
                            val item = newsArr.getJSONObject(i)
                            val title = item.optString("title", "")
                            if (title.isEmpty()) return@runCatching null
                            ResearchNewsItem(
                                headline    = title,
                                summary     = item.optString("summary", ""),
                                source      = item.optString("publisher", "Yahoo Finance"),
                                url         = item.optString("link", ""),
                                publishedAt = item.optLong("providerPublishTime", 0L) * 1000L,
                                sentiment   = classifyNewsSentiment(title)
                            )
                        }.getOrNull()
                    }
                if (parsed.isNotEmpty()) { yahooItems = parsed; break }
            }

            // RELEVANCE FILTER FOR DISPLAY TOO: the research card must never show
            // world headlines under a stock. Missing a real headline is the safe
            // direction — it shows nothing, never something wrong (B6).
            val relevantYahoo = yahooItems.filter { headlineMentionsStock(it.headline, key, name) }

            // ── Source 2: Google News RSS when Yahoo is thin (<2 relevant) ────
            var googleRead = false
            val merged = if (relevantYahoo.size < 2) {
                val google = runCatching { fetchGoogleNewsRss(key, name) }
                    .onSuccess { googleRead = true }
                    .getOrElse {
                        Log.w(TAG, "Google News RSS failed for $key: ${it.message}")
                        emptyList()
                    }
                val seen = relevantYahoo.map { headlineKey(it.headline) }.toHashSet()
                relevantYahoo + google.filter { seen.add(headlineKey(it.headline)) }
            } else relevantYahoo

            // Newest first; unknown-date (older Yahoo) items sink to the bottom.
            val result = merged.sortedByDescending { it.publishedAt }

            // Cache only a SUCCESSFUL read from at least one source (including a
            // legitimately empty result) so a quiet symbol isn't re-hit for
            // NEWS_CACHE_TTL_MS. BOTH sources failing is NOT cached — a transient
            // outage stays retryable next cycle.
            if (yahooRead || googleRead) {
                newsCache[key] = System.currentTimeMillis() to result
            }
            result
        }.getOrElse {
            Log.w(TAG, "fetchYahooNews failed for $symbol: ${it.message}")
            emptyList()
        }
    }

    /**
     * Second news source (REAL NEWS AT LAST): Google News RSS search, queried as
     * `"<company name>" stock India` (ticker when the name is unknown) on the
     * India-English edition. Parsed with simple regex — title, pubDate, link,
     * source — no new dependencies. Only items that (a) actually name the stock
     * (same relevance guard as the Yahoo path) and (b) are <= 72h old with a
     * known date survive: Google's search reaches far back in time, and an
     * undated or stale item must neither clutter the research card nor ever
     * feed sentiment (B6). THROWS on a fetch failure so the caller can tell
     * "source down" (not cached, retryable) from "genuinely no news" (cached);
     * a per-item parse problem just skips that item.
     */
    private fun fetchGoogleNewsRss(symbol: String, companyName: String): List<ResearchNewsItem> {
        val raw = companyName.ifBlank { symbol }.trim()
        if (raw.isEmpty()) return emptyList()
        // Trim corporate boilerplate ("The Tata Power Company Limited" → "Tata
        // Power"): the quoted phrase must match how headlines actually write the
        // company. Live probe: the raw Yahoo shortname returned 19 items, the
        // cleaned name 100 — same endpoint, 5x the real coverage.
        val suffixes = setOf("ltd", "limited", "co", "company", "corp", "corporation", "inc", "plc")
        var words = raw.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size > 1 && words.first().equals("the", ignoreCase = true)) words = words.drop(1)
        while (words.size > 1 && words.last().trimEnd('.').lowercase() in suffixes) words = words.dropLast(1)
        val topic = words.joinToString(" ").ifBlank { raw }
        val q = java.net.URLEncoder.encode("\"$topic\" stock India", "UTF-8")
        val xml = fetchUrl("https://news.google.com/rss/search?q=$q&hl=en-IN&gl=IN&ceid=IN:en", researchClient)
        val now = System.currentTimeMillis()
        return Regex("<item>(.*?)</item>", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml)
            .mapNotNull { m ->
                runCatching {
                    val block = m.groupValues[1]
                    val rawTitle = rssTag(block, "title") ?: return@runCatching null
                    val source = rssTag(block, "source") ?: "Google News"
                    // Google appends " - Publisher" to every title — strip it so the
                    // sentiment pass can't misread the publisher's name as words.
                    var title = rawTitle
                    val sep = title.lastIndexOf(" - ")
                    if (sep > 0 && title.length - sep <= 60) title = title.substring(0, sep)
                    title = title.trim()
                    if (title.isEmpty()) return@runCatching null
                    // Same gates as the sentiment vet: known date, <= 72h, names the stock.
                    val publishedAt = parseRssDate(rssTag(block, "pubDate") ?: "")
                    if (publishedAt <= 0L || now - publishedAt > NEWS_MAX_AGE_MS) return@runCatching null
                    if (!headlineMentionsStock(title, symbol, companyName)) return@runCatching null
                    ResearchNewsItem(
                        headline    = title,
                        summary     = "",
                        source      = source,
                        url         = rssTag(block, "link") ?: "",
                        publishedAt = publishedAt,
                        sentiment   = classifyNewsSentiment(title)
                    )
                }.getOrNull()
            }
            .take(10)
            .toList()
    }

    /** First `<tag>…</tag>` value in an RSS item block — CDATA-aware, basic entities decoded. */
    private fun rssTag(block: String, tag: String): String? {
        val m = Regex("<$tag(?:\\s[^>]*)?>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL)
            .find(block) ?: return null
        var v = m.groupValues[1].trim()
        val cdata = Regex("^<!\\[CDATA\\[(.*)]]>\$", RegexOption.DOT_MATCHES_ALL).find(v)
        if (cdata != null) v = cdata.groupValues[1].trim()
        return v
            .replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")   // last, so double-escapes can't re-expand
            .trim()
            .ifBlank { null }
    }

    /** RFC-822 pubDate ("Fri, 18 Jul 2026 05:30:00 GMT") → epoch ms; 0 when unparseable. */
    private fun parseRssDate(raw: String): Long {
        if (raw.isBlank()) return 0L
        for (pattern in arrayOf("EEE, dd MMM yyyy HH:mm:ss zzz", "dd MMM yyyy HH:mm:ss zzz")) {
            val t = runCatching {
                java.text.SimpleDateFormat(pattern, java.util.Locale.US).parse(raw.trim())?.time
            }.getOrNull()
            if (t != null && t > 0L) return t
        }
        return 0L
    }

    /** Case/punctuation-insensitive key for cross-source headline de-duplication. */
    private fun headlineKey(headline: String): String =
        headline.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

    /**
     * Deterministic, free (no LLM) sentiment for a headline. Naive whole-word
     * counting misreads the phrasings Indian-market headlines use constantly —
     * "cuts costs", "narrows loss", "fraud probe dropped", "52-week high" all pair a
     * "bad" word with a GOOD meaning (and vice-versa). Two passes fix that:
     *   1. Known multi-word PHRASES are scored first and then blanked out, so the
     *      single-word pass can't re-read the leftover "cut" / "loss" / "high".
     *   2. Single words are scored with NEGATION handling: a negator right before a
     *      word flips it — a denied positive ("no growth") leans negative, a denied
     *      negative ("denies fraud", "no cut") is only cancelled, never made positive
     *      (staying on the cautious side per A5).
     */
    private fun classifyNewsSentiment(headline: String): String {
        // Pad with spaces so start/end word checks behave.
        var text = " ${headline.lowercase()} "
        var bull = 0
        var bear = 0

        // ── 1. Phrases (checked, then blanked so words below aren't double-read) ──
        val positivePhrases = listOf(
            "cuts cost", "cut cost", "cuts costs", "cost cut", "trims cost", "lowers cost", "reduces cost",
            "narrows loss", "narrowing loss", "loss narrows", "trims loss", "reduces loss",
            "back in profit", "returns to profit", "return to profit", "swings to profit", "back to profit",
            "beats estimate", "beat estimate", "beats forecast", "beats expectations", "above estimate",
            "raises guidance", "raise guidance", "hikes guidance", "lifts guidance", "raised guidance",
            "raises target", "hikes target", "target raised", "price target raised", "target hiked",
            "hikes dividend", "raises dividend", "special dividend", "dividend hike",
            "cuts debt", "cut debt", "reduces debt", "debt free", "debt-free", "pares debt", "lowers debt",
            "wins order", "bags order", "order win", "wins contract", "bags contract", "secures order",
            "record profit", "record high", "all-time high", "all time high", "lifetime high", "life high",
            "52-week high", "52 week high", "fresh high", "new high",
            "probe dropped", "case dismissed", "charges dropped", "probe closed", "cleared of",
            "gets approval", "wins approval", "gets nod", "approval for"
        )
        val negativePhrases = listOf(
            "profit falls", "profit drops", "profit declines", "profit slips", "profit down", "profit dips",
            "misses estimate", "miss estimate", "misses forecast", "below estimate", "misses expectations",
            "cuts guidance", "cut guidance", "lowers guidance", "trims guidance", "slashes guidance",
            "cuts target", "cut target", "target cut", "price target cut", "lowers target", "target slashed",
            "cuts rating", "cut rating", "downgrade", "downgrades", "downgraded",
            "widens loss", "loss widens", "widening loss", "slips into loss", "posts loss", "reports loss", "sinks to loss", "wider loss",
            "record low", "all-time low", "all time low", "lifetime low", "life low", "52-week low", "52 week low", "fresh low", "new low", "record loss",
            "profit warning", "fraud probe", "under probe", "under investigation", "tax raid", "it raid",
            "cuts stake", "stake sale", "block deal", "pledged shares", "share pledge",
            "product recall", "recall", "import ban", "ban on", "penalty imposed", "fine imposed", "penalised"
        )
        for (p in positivePhrases) if (text.contains(p)) { bull++; text = text.replace(p, "  ") }
        for (p in negativePhrases) if (text.contains(p)) { bear++; text = text.replace(p, "  ") }

        // ── 2. Single words with negation ────────────────────────────────────────
        val bullishWords = listOf("surge", "rise", "gain", "profit", "buy", "rally", "bull", "growth",
            "positive", "strong", "upgrade", "beat", "record", "high", "boost", "recover", "jump", "soar")
        val bearishWords = listOf("fall", "drop", "loss", "sell", "crash", "bear", "decline", "weak",
            "negative", "downgrade", "miss", "low", "cut", "risk", "concern", "warning", "slump", "plunge")
        val negators = setOf("no", "not", "never", "without", "denies", "denied", "deny",
            "dismissed", "dismisses", "dismiss", "rejects", "rejected", "avoids", "avoided")

        // startsWith keeps the original prefix semantics (gains→gain, rises→rise) while
        // splitting on non-alphanumerics keeps the whole-word intent (no "low" in "follow").
        val tokens = text.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        for ((i, tok) in tokens.withIndex()) {
            val isBull = bullishWords.any { tok.startsWith(it) }
            val isBear = bearishWords.any { tok.startsWith(it) }
            if (!isBull && !isBear) continue
            val negated = (1..2).any { back -> i - back >= 0 && tokens[i - back] in negators }
            when {
                isBull && negated -> bear++    // denied positive → cautious negative
                isBull            -> bull++
                isBear && negated -> Unit       // denied negative → cancel, never flip to positive
                isBear            -> bear++
            }
        }

        return when {
            bull > bear -> "POSITIVE"
            bear > bull -> "NEGATIVE"
            else        -> "NEUTRAL"
        }
    }

    /**
     * H4 event tier for a headline (P0 #2 #4) — how market-moving the event is,
     * used to scale the confidence delta. BIG events (fraud, raids, recalls, M&A,
     * an earnings surprise) move a stock hard; guidance / orders / approvals /
     * ratings / results are MEDIUM; a dividend, bonus or a generic price move is
     * ROUTINE. Deterministic and free (no LLM). Checked most-severe first, so a
     * "profit warning" scores BIG even though "profit" alone would be MEDIUM.
     */
    private fun newsEventTier(headline: String): Int {
        val h = " ${headline.lowercase()} "
        val big = listOf(
            "fraud", "probe", "raid", "arrest", "recall", "merger", "acquisition",
            "acquire", "takeover", "scam", "insolven", "bankrupt", "delist",
            "beats estimate", "beat estimate", "beats forecast", "beats expectations",
            "misses estimate", "miss estimate", "misses forecast", "misses expectations",
            "profit warning", "open offer", "stake sale", "block deal"
        )
        val medium = listOf(
            "guidance", "wins order", "bags order", "order win", "secures order",
            "wins contract", "bags contract", "wins deal", "approval", "gets nod",
            "approved", "rating", "upgrade", "downgrade", "price target",
            "target raised", "target cut", "results", "earnings", "quarter",
            "revenue", "profit", "loss", "buyback", "expansion", "capex", "capacity"
        )
        if (big.any { h.contains(it) }) return TIER_BIG
        if (medium.any { h.contains(it) }) return TIER_MEDIUM
        return TIER_ROUTINE
    }

    /**
     * RELEVANCE GUARD (P0 #2 #1): true only when [headline] actually names this
     * stock — either its ticker as a standalone word (so "LT" can't match "salt",
     * "ITC" can't match "switch") or a significant word of its company name
     * (>= 4 chars, minus generic corporate/sector words). Yahoo's per-symbol search
     * still slips in generic world news; a headline that names neither is
     * IRRELEVANT and must be treated as no-news so it can never move this stock's
     * confidence (B6). Missing a real headline (e.g. an "L&T" abbreviation the name
     * words don't cover) is the SAFE direction — it yields no adjustment, never a
     * wrong one.
     */
    private fun headlineMentionsStock(headline: String, symbol: String, name: String): Boolean {
        if (headline.isBlank()) return false
        val lower = headline.lowercase()
        val sym = symbol.removeSuffix(".NS").lowercase().trim()
        val tokens = lower.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        if (sym.length >= 2 && tokens.contains(sym)) return true
        val stop = setOf(
            "ltd", "limited", "india", "indian", "company", "corp", "corporation",
            "industries", "enterprises", "holdings", "finance", "financial",
            "services", "bank", "motors", "steel", "power", "energy", "group",
            "international", "technologies", "tech", "pharma", "pharmaceuticals",
            "chemicals", "cement", "auto", "and", "the"
        )
        val nameTokens = name.lowercase().split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 4 && it !in stop }
        return nameTokens.any { lower.contains(it) }
    }

    // ─── Market health ────────────────────────────────────────────────────────

    private fun fetchMarketHealthInternal(): MarketHealth {
        val niftySymbol = "%5ENSEI"
        // Try v7 on all hosts first
        var body: String? = null
        for (host in YF_HOSTS) {
            body = runCatching {
                fetchUrl("https://$host/v7/finance/quote?symbols=$niftySymbol&lang=en&region=IN", fastClient)
            }.getOrNull()
            if (body != null) break
        }
        // Fallback to v8 chart for Nifty
        if (body == null) {
            body = runCatching {
                fetchUrl("https://query1.finance.yahoo.com/v8/finance/chart/%5ENSEI?interval=1d&range=5d", chartClient)
            }.getOrNull()
        }

        val safeBody = body ?: return defaultHealth()
        return runCatching {
            val root    = JSONObject(safeBody)
            val results = root.optJSONObject("quoteResponse")?.optJSONArray("result")
            if (results != null && results.length() > 0) {
                val nifty  = results.getJSONObject(0)
                val change = nifty.optDouble("regularMarketChangePercent", 0.0)
                val price  = nifty.optDouble("regularMarketPrice", 0.0)
                val state  = nifty.optString("marketState", "CLOSED")
                return buildHealth(change, price, state)
            }

            val chartResults = root.optJSONObject("chart")?.optJSONArray("result")
            if (chartResults != null && chartResults.length() > 0) {
                val chartResult = chartResults.getJSONObject(0)
                val meta  = chartResult.getJSONObject("meta")
                val price = meta.optDouble("regularMarketPrice", 0.0)
                // chartPreviousClose on range=5d is ~a week old — use yesterday's close
                // from the series, else NIFTY's "today's change" becomes the 5-day move
                // and biases the trend fed to SignalEngine + the Hindi day verdict
                val closes = chartResult.optJSONObject("indicators")
                    ?.optJSONArray("quote")?.optJSONObject(0)
                    ?.optJSONArray("close")?.toDoubleList() ?: emptyList()
                val prevClose = if (closes.size >= 2 && closes[closes.size - 2] > 0.0)
                    closes[closes.size - 2]
                else
                    meta.optDouble("previousClose", price)
                val change = if (prevClose > 0) (price - prevClose) / prevClose * 100.0 else 0.0
                val state  = meta.optString("marketState", "CLOSED")
                return buildHealth(change, price, state)
            }

            defaultHealth()
        }.getOrElse { defaultHealth() }
    }

    private fun buildHealth(change: Double, price: Double, state: String) = MarketHealth(
        trend = when {
            change >= 0.6  -> "BULLISH"
            change <= -0.6 -> "BEARISH"
            else           -> "SIDEWAYS"
        },
        riskLevel = when {
            abs(change) >= 1.5 -> RiskLevel.HIGH
            abs(change) >= 0.6 -> RiskLevel.MEDIUM
            else               -> RiskLevel.LOW
        },
        niftyChange  = change,
        marketState  = state,
        niftyPrice   = price
    )

    // ─── India VIX ───────────────────────────────────────────────────────────────

    /**
     * Fetches India VIX from Yahoo Finance (%5EINDIAVIX).
     * Tries the v7 quote first, then falls back to the v8 chart meta price —
     * v7 is dead (HTTP 401 without a crumb), and without the fallback the VIX
     * stayed 0.0 forever and RiskGuard Rule 7 never engaged.
     * Returns 0.0 on any failure so RiskGuard Rule 7 is simply skipped.
     */
    suspend fun fetchIndiaVix(): Double = withContext(Dispatchers.IO) {
        runCatching {
            val vixSymbol = "%5EINDIAVIX"
            var body: String? = null
            for (host in YF_HOSTS) {
                body = runCatching {
                    fetchUrl("https://$host/v7/finance/quote?symbols=$vixSymbol&lang=en&region=IN", fastClient)
                }.getOrNull()
                if (body != null) break
            }
            if (body != null) {
                val result = JSONObject(body).optJSONObject("quoteResponse")?.optJSONArray("result")
                if (result != null && result.length() > 0) {
                    val price = result.getJSONObject(0).optDouble("regularMarketPrice", 0.0)
                    if (price > 0) return@runCatching price
                }
            }
            // v7 failed everywhere — one v8 chart call, same pattern as fetchIndices
            val chartBody = fetchUrl("https://query1.finance.yahoo.com/v8/finance/chart/$vixSymbol?interval=1d&range=1d", chartClient)
            JSONObject(chartBody).getJSONObject("chart")
                .getJSONArray("result").getJSONObject(0)
                .getJSONObject("meta")
                .optDouble("regularMarketPrice", 0.0)
        }.getOrElse { 0.0 }
    }

    // ─── HTTP ─────────────────────────────────────────────────────────────────

    private fun fetchUrl(url: String, client: OkHttpClient): String {
        val request = Request.Builder().url(url).get().build()
        // use{} guarantees the response is closed on EVERY path — including a null
        // body or an exception mid-read. The old manual close leaked the connection
        // in those cases, slowly starving OkHttp's pool over a long trading session.
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "HTTP ${response.code} for $url")
                throw Exception("HTTP ${response.code}")
            }
            response.body?.string() ?: throw Exception("Empty response body")
        }
    }

    // ─── Cache ────────────────────────────────────────────────────────────────

    private fun cacheSignals(signals: List<TradingSignal>, health: MarketHealth) {
        runCatching {
            val arr = JSONArray().also { a -> signals.forEach { s -> a.put(s.toJson()) } }
            val healthJson = JSONObject().apply {
                put("trend", health.trend); put("riskLevel", health.riskLevel.name)
                put("niftyChange", health.niftyChange); put("marketState", health.marketState)
                put("niftyPrice", health.niftyPrice); put("isAvailable", health.isAvailable)
            }
            prefs.edit()
                .putString("sig_json", arr.toString())
                .putString("health_json", healthJson.toString())
                .putLong("cache_ts", System.currentTimeMillis())
                .apply()
            Log.d(TAG, "Cached ${signals.size} signals")
        }
    }

    private fun TradingSignal.toJson(): JSONObject = JSONObject().apply {
        put("sym", stockSymbol); put("name", stockName); put("action", action.name)
        put("price", currentPrice); put("entry", entryPrice); put("entryZone", entryZone)
        put("t1", targetPrice); put("t2", target2); put("t3", target3)
        put("sl", stopLoss); put("trail", trailingStop)
        put("profit", expectedProfit); put("loss", expectedLoss)
        put("conf", confidence); put("rr", riskReward)
        put("upPct", upsideProbability); put("dnPct", downsideProbability); put("sidePct", sidewaysProbability)
        put("risk", riskLevel.name); put("reasons", JSONArray(reasons))
        put("state", marketState); put("sector", sectorStrength)
        put("mktTrend", marketTrend); put("instFlow", institutionalFlow); put("qty", recommendedQty)
        // RiskGuard inputs — cached signals must keep feeding the guard real values
        put("chg", changePercent); put("gap", gapPercent)
        put("vol", volume); put("avgVol", avgVolume)
        put("newsSent", newsSentiment)
    }

    private fun JSONObject.toSignal(): TradingSignal {
        val r = optJSONArray("reasons") ?: JSONArray()
        return TradingSignal(
            stockSymbol         = getString("sym"),
            stockName           = getString("name"),
            action              = SignalAction.valueOf(getString("action")),
            currentPrice        = getDouble("price"),
            entryPrice          = getDouble("entry"),
            entryZone           = optString("entryZone", ""),
            targetPrice         = getDouble("t1"),
            target2             = optDouble("t2", 0.0),
            target3             = optDouble("t3", 0.0),
            stopLoss            = getDouble("sl"),
            trailingStop        = optDouble("trail", 0.0),
            expectedProfit      = optDouble("profit", abs(getDouble("t1") - getDouble("price"))),
            expectedLoss        = optDouble("loss",   abs(getDouble("price") - getDouble("sl"))),
            confidence          = getInt("conf"),
            riskReward          = optDouble("rr", 0.0),
            upsideProbability   = optInt("upPct", 0),
            downsideProbability = optInt("dnPct", 0),
            sidewaysProbability = optInt("sidePct", 0),
            riskLevel           = RiskLevel.valueOf(optString("risk", "MEDIUM")),
            reasons             = (0 until r.length()).map { r.getString(it) },
            validUntil          = "Cached",
            tradeValidityTime   = "Cached Data",
            isBeginnerSafe      = optString("risk", "MEDIUM") == "LOW",
            marketState         = optString("state", "CLOSED"),
            sectorStrength      = optString("sector", "NEUTRAL"),
            marketTrend         = optString("mktTrend", "SIDEWAYS"),
            institutionalFlow   = optString("instFlow", "NEUTRAL"),
            recommendedQty      = optInt("qty", 1),
            changePercent       = optDouble("chg", 0.0),
            gapPercent          = optDouble("gap", 0.0),
            volume              = optLong("vol", 0L),
            avgVolume           = optLong("avgVol", 0L),
            newsSentiment       = optString("newsSent", "NONE")
        )
    }

    // Returned only when the live Nifty fetch fails. isAvailable=false tells the UI
    // to show "market data unavailable" rather than presenting 0.00% / SIDEWAYS as real.
    private fun defaultHealth() = MarketHealth(
        trend = "SIDEWAYS", riskLevel = RiskLevel.MEDIUM,
        niftyChange = 0.0, marketState = "CLOSED", isAvailable = false
    )

    // ─── Daily picks — the SAME 4 stocks all day (requirement 1.1) ───────────

    /** Returns (istDate, picks) saved earlier today, or null. */
    fun loadDailyPicks(): Pair<String, List<TradingSignal>>? = runCatching {
        val json = prefs.getString("daily_picks_json", null) ?: return null
        val obj  = JSONObject(json)
        val date = obj.getString("date")
        val arr  = obj.getJSONArray("picks")
        date to (0 until arr.length()).mapNotNull { i ->
            runCatching { arr.getJSONObject(i).toSignal() }.getOrNull()
        }
    }.getOrNull()

    fun saveDailyPicks(date: String, picks: List<TradingSignal>) {
        runCatching {
            val obj = JSONObject().apply {
                put("date", date)
                put("picks", JSONArray().also { a -> picks.forEach { s -> a.put(s.toJson()) } })
            }
            prefs.edit().putString("daily_picks_json", obj.toString()).apply()
        }
    }

    // ─── Screener cache — Stock tab shows data INSTANTLY on app open ─────────

    fun loadCachedScreener(): List<StockScreenerItem> = runCatching {
        val json = prefs.getString("screener_json", null) ?: return emptyList()
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            runCatching { arr.getJSONObject(i).toScreenerItem() }.getOrNull()
        }
    }.getOrElse { emptyList() }

    private fun cacheScreener(items: List<StockScreenerItem>) {
        runCatching {
            val arr = JSONArray()
            items.forEach { s ->
                arr.put(JSONObject().apply {
                    put("sym", s.symbol); put("name", s.name); put("price", s.currentPrice)
                    put("chg", s.changePercent); put("vol", s.volume); put("avgVol", s.avgVolume)
                    put("pLow", s.periodLow); put("pHigh", s.periodHigh)
                    put("l52", s.low52w); put("h52", s.high52w)
                    put("dLow", s.distFromPeriodLowPct); put("dHigh", s.distFromPeriodHighPct)
                    put("ma50", s.ma50); put("ma200", s.ma200)
                    put("mktState", s.marketState); put("days", s.periodDays); put("volR", s.volRatio)
                    // last 30 closes, rounded — keeps the cached sparklines working
                    put("closes", JSONArray(s.historicalCloses.takeLast(30).map { c -> Math.round(c * 100.0) / 100.0 }))
                })
            }
            prefs.edit().putString("screener_json", arr.toString()).apply()
        }
    }

    private fun JSONObject.toScreenerItem(): StockScreenerItem {
        val closesArr = optJSONArray("closes") ?: JSONArray()
        return StockScreenerItem(
            symbol               = getString("sym"),
            name                 = optString("name", ""),
            currentPrice         = getDouble("price"),
            changePercent        = optDouble("chg", 0.0),
            volume               = optLong("vol", 0L),
            avgVolume            = optLong("avgVol", 0L),
            periodLow            = optDouble("pLow", 0.0),
            periodHigh           = optDouble("pHigh", 0.0),
            low52w               = optDouble("l52", 0.0),
            high52w              = optDouble("h52", 0.0),
            distFromPeriodLowPct = optDouble("dLow", 0.0),
            distFromPeriodHighPct= optDouble("dHigh", 0.0),
            historicalCloses     = (0 until closesArr.length()).map { closesArr.optDouble(it, 0.0) },
            ma50                 = optDouble("ma50", 0.0),
            ma200                = optDouble("ma200", 0.0),
            marketState          = optString("mktState", "CLOSED"),
            periodDays           = optInt("days", 20),
            volRatio             = optDouble("volR", 1.0)
        )
    }
}
