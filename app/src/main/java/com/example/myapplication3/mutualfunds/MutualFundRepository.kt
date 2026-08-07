package com.example.myapplication3.mutualfunds

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * A mutual fund with its latest NAV, trailing annualised returns and a
 * downsampled NAV history (oldest → newest) for sparklines.
 */
data class MutualFund(
    val schemeCode: Int,
    val name: String,
    val fundHouse: String,
    val category: String,
    val nav: Double,
    val navDate: String,
    val return1y: Double?,
    val return3y: Double?,
    val return5y: Double?,
    val navHistory: List<Double>,
    /** Days since the latest published NAV — mfapi.in keeps serving discontinued/merged
     *  schemes whose last NAV is months old; the UI must badge these as stale. */
    val ageDays: Long = 0L,
    /** One-line "why this fund" shown on curated picks (rule C18); null for search results. */
    val reason: String? = null
)

/**
 * The last successful curated-fund list, restored from disk — rendered INSTANTLY
 * on open (labelled "Yesterday's prices") while the fresh load runs behind it,
 * so the tab never sits on an endless spinner.
 */
data class CachedTopFunds(val funds: List<MutualFund>, val savedAtMs: Long)

/**
 * Free mutual-fund data from https://www.mfapi.in (AMFI NAV mirror, no API key).
 *  - GET /mf/search?q=<query>  → [{"schemeCode":123456,"schemeName":"..."}]
 *  - GET /mf/<schemeCode>      → {"meta":{...},"data":[{"date":"dd-MM-yyyy","nav":"123.45"},...]}
 * NAV data arrives NEWEST FIRST; nav is a String; dates are dd-MM-yyyy.
 */
@Singleton
class MutualFundRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "MutualFundRepo"
        private const val BASE_URL = "https://api.mfapi.in/mf"
        private const val PREFS_NAME = "mf_cache_v1"
        // Disk cache of the last successful curated list ("yesterday's prices")
        private const val FUNDS_CACHE_KEY = "mf_funds_cache_v1"
        // A partial fetch (network died halfway) must not overwrite a full list
        private const val MIN_FUNDS_TO_CACHE = 3
        private const val MS_PER_DAY = 86_400_000L
        // 5y of NAV rows even for funds that publish daily incl. weekends (5×365 < 1900)
        private const val MAX_PARSED_ROWS = 1900
        private const val SPARKLINE_POINTS = 60
        private const val MAX_CONCURRENT_REQUESTS = 6

        // Plan variants a beginner should never land on
        private val EXCLUDED_PLAN_WORDS =
            listOf("idcw", "dividend", "bonus", "payout", "reinvest", "institutional")

        /** A curated pick: search queries tried in order + a one-line reason (C18). */
        private data class CuratedFund(val queries: List<String>, val reason: String)

        /**
         * Curated beginner-friendly funds. Each entry is a list of search queries tried
         * in order — SEBI's 2025 scheme-name rationalisation renamed several funds
         * ("SBI Bluechip" → "SBI Large Cap", "HDFC Mid-Cap Opportunities" → "HDFC Mid Cap
         * Fund", "Kotak Emerging Equity" → "Kotak Midcap Fund"), so the current name is
         * tried first and the legacy name kept as fallback.
         */
        // BEGINNER-SAFE ONLY (rule C19): flexi-cap, large-cap, index and
        // balanced-advantage. NO small-cap or mid-cap — too risky for a beginner
        // who blindly follows and would ride a deep drawdown they can't judge.
        // Exactly 5 funds, each with a one-line reason (rule C18: 3-5, not 8).
        private val CURATED_FUNDS = listOf(
            CuratedFund(
                listOf("UTI Nifty 50 Index Fund"),
                "Simplest start — owns all 50 biggest companies for a tiny fee"
            ),
            CuratedFund(
                listOf("Parag Parikh Flexi Cap"),
                "Big, careful fund with a long steady record"
            ),
            CuratedFund(
                listOf("SBI Large Cap", "SBI Bluechip"),
                "Sticks to large, strong companies only"
            ),
            CuratedFund(
                listOf("HDFC Flexi Cap"),
                "Old fund house, free to pick companies of any size"
            ),
            CuratedFund(
                listOf("HDFC Balanced Advantage"),
                "Mixes shares and bonds, so market falls hurt less"
            )
        )

        /**
         * Read the last successful curated list from disk — null when absent or
         * corrupt. Public + static so the screen can render it on the very first
         * frame while the ViewModel's fresh load is still running.
         */
        fun readFundsCache(context: Context): CachedTopFunds? = runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(FUNDS_CACHE_KEY, null) ?: return@runCatching null
            val root = JSONObject(raw)
            val savedAtMs = root.optLong("savedAtMs", 0L)
            if (savedAtMs <= 0L) return@runCatching null
            val arr = root.optJSONArray("funds") ?: return@runCatching null
            // distinctBy — the UI keys fund cards by schemeCode; a corrupt cache
            // with a duplicate code must never crash the list
            val funds = (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { fundFromJson(it) }
            }.distinctBy { it.schemeCode }
            if (funds.isEmpty()) null else CachedTopFunds(funds, savedAtMs)
        }.getOrNull()

        private fun fundToJson(f: MutualFund): JSONObject = JSONObject().apply {
            put("schemeCode", f.schemeCode)
            put("name", f.name)
            put("fundHouse", f.fundHouse)
            put("category", f.category)
            put("nav", f.nav)
            put("navDate", f.navDate)
            f.return1y?.let { put("return1y", it) }
            f.return3y?.let { put("return3y", it) }
            f.return5y?.let { put("return5y", it) }
            put("navHistory", JSONArray().also { arr -> f.navHistory.forEach { arr.put(it) } })
            f.reason?.let { put("reason", it) }
        }

        /**
         * Rebuild a fund from cache. ageDays is RECOMPUTED from navDate at read
         * time so every staleness protection (old-data badge, SIP-calculator
         * lockout) keeps working on cached data; an unparseable date counts as
         * very old — protections on, never off.
         */
        private fun fundFromJson(o: JSONObject): MutualFund? = runCatching {
            val code = o.optInt("schemeCode", -1)
            val name = o.optString("name", "")
            val nav = o.optDouble("nav", Double.NaN)
            if (code <= 0 || name.isEmpty() || nav.isNaN() || nav <= 0.0) return@runCatching null
            val navDate = o.optString("navDate", "")
            val navTimeMs = runCatching {
                SimpleDateFormat("dd-MM-yyyy", Locale.US).parse(navDate)?.time
            }.getOrNull()
            val ageDays = navTimeMs
                ?.let { ((System.currentTimeMillis() - it) / MS_PER_DAY).coerceAtLeast(0L) }
                ?: 999L
            val history = o.optJSONArray("navHistory")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    arr.optDouble(i).takeUnless { it.isNaN() }
                }
            } ?: emptyList()
            MutualFund(
                schemeCode = code,
                name       = name,
                fundHouse  = o.optString("fundHouse", ""),
                category   = o.optString("category", ""),
                nav        = nav,
                navDate    = navDate,
                return1y   = o.optDoubleOrNull("return1y"),
                return3y   = o.optDoubleOrNull("return3y"),
                return5y   = o.optDoubleOrNull("return5y"),
                navHistory = history,
                ageDays    = ageDays,
                reason     = o.optString("reason", "").ifBlank { null }
            )
        }.getOrNull()

        private fun JSONObject.optDoubleOrNull(key: String): Double? =
            if (has(key) && !isNull(key)) optDouble(key).takeUnless { it.isNaN() } else null
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(MAX_CONCURRENT_REQUESTS, 5, TimeUnit.MINUTES))
        .build()

    private data class NavEntry(val timeMs: Long, val nav: Double, val date: String)

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * Curated beginner-friendly funds. Scheme codes are resolved via the search
     * endpoint once and cached in SharedPreferences; NAV histories are fetched
     * concurrently (max [MAX_CONCURRENT_REQUESTS] requests in flight).
     */
    suspend fun getTopFunds(): List<MutualFund> = withContext(Dispatchers.IO) {
        val funds = coroutineScope {
            val semaphore = Semaphore(MAX_CONCURRENT_REQUESTS)
            CURATED_FUNDS.map { curated ->
                async {
                    semaphore.withPermit {
                        runCatching {
                            val code = resolveSchemeCode(curated.queries) ?: return@withPermit null
                            fetchFund(code)?.copy(reason = curated.reason)
                        }.getOrNull()
                    }
                }
            }.mapNotNull { it.await() }
                // Two curated queries resolving to the same scheme would give the
                // UI duplicate card keys (crash) — keep the first occurrence only
                .distinctBy { it.schemeCode }
        }
        // Persist the last good list so the next open renders instantly, even offline
        if (funds.size >= MIN_FUNDS_TO_CACHE) saveFundsCache(funds)
        funds
    }

    /** Write-through disk cache — the UI restores it via [readFundsCache] on open. */
    private fun saveFundsCache(funds: List<MutualFund>) {
        runCatching {
            val root = JSONObject().apply {
                put("savedAtMs", System.currentTimeMillis())
                put("funds", JSONArray().also { arr -> funds.forEach { arr.put(fundToJson(it)) } })
            }
            prefs.edit().putString(FUNDS_CACHE_KEY, root.toString()).apply()
        }.onFailure { Log.w(TAG, "saveFundsCache failed: ${it.message}") }
    }

    /**
     * Free-text search — top 8 UNIQUE funds (Direct + Growth variant per fund, same
     * rule as the curated list), each enriched with NAV + returns concurrently.
     * THROWS on network failure so the UI can show "check your internet" + retry
     * instead of a false "No funds found" (an error is not "fund does not exist").
     */
    suspend fun searchFunds(query: String): List<MutualFund> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val hits = dedupeSchemes(searchSchemes(query)).take(8)
        if (hits.isEmpty()) return@withContext emptyList()
        val enriched = coroutineScope {
            val semaphore = Semaphore(MAX_CONCURRENT_REQUESTS)
            hits.map { (code, _) ->
                async {
                    semaphore.withPermit {
                        runCatching { fetchFund(code) }.getOrNull()
                    }
                }
            }.mapNotNull { it.await() }
        }
        // Matches exist but EVERY detail fetch failed — network died mid-search;
        // report it as an error, never as "No funds found"
        if (enriched.isEmpty()) throw Exception("All fund detail fetches failed")
        enriched
    }

    /** One fund by scheme code — used to refresh the tracked SIP's NAV (rule C20a). */
    suspend fun getFund(schemeCode: Int): MutualFund? = withContext(Dispatchers.IO) {
        fetchFund(schemeCode)
    }

    // ─── Scheme-code resolution ───────────────────────────────────────────────

    private fun resolveSchemeCode(queries: List<String>): Int? {
        val cacheKey = "code_" + queries.first().lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "_")
        val cached = prefs.getInt(cacheKey, -1)
        if (cached > 0) return cached

        for (query in queries) {
            val schemes = runCatching { searchSchemes(query) }.getOrElse { emptyList() }
            val code = pickBestScheme(query, schemes) ?: continue
            prefs.edit().putInt(cacheKey, code).apply()
            return code
        }
        return null
    }

    /**
     * One card per fund: collapse Direct/Regular/Growth/IDCW variants of the same
     * scheme into a single Direct + Growth pick. Raw /mf/search output is mostly
     * plan variants of the same few funds — showing 8 rows of "SBI Large Cap"
     * teaches a beginner nothing and hides other real matches.
     */
    private fun dedupeSchemes(schemes: List<Pair<Int, String>>): List<Pair<Int, String>> {
        val groups = LinkedHashMap<String, MutableList<Pair<Int, String>>>()
        for (scheme in schemes) {
            // Base fund name = everything before the first "-" (plan/option suffixes)
            val base = scheme.second.lowercase(Locale.US).substringBefore("-").trim()
            groups.getOrPut(base) { mutableListOf() }.add(scheme)
        }
        return groups.values.mapNotNull { group ->
            // Empty query → pickBestScheme skips phrase matching and just applies
            // the Direct + Growth plan preference within the group
            val code = pickBestScheme("", group) ?: return@mapNotNull null
            group.firstOrNull { it.first == code }
        }
    }

    private fun searchSchemes(query: String): List<Pair<Int, String>> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val body = try {
            fetchUrl("$BASE_URL/search?q=$encoded")
        } catch (e: Exception) {
            // mfapi answers "no matches" with HTTP 404 — that is a real empty
            // result ("No funds found"), NOT a network failure to retry
            if (e.message == "HTTP 404") return emptyList()
            throw e
        }
        val arr = JSONArray(body)
        return (0 until arr.length()).mapNotNull { i ->
            runCatching {
                val item = arr.getJSONObject(i)
                val code = item.optInt("schemeCode", -1)
                val name = item.optString("schemeName", "")
                if (code <= 0 || name.isEmpty()) null else code to name
            }.getOrNull()
        }
    }

    /**
     * Pick the Direct + Growth plan for [query]:
     *  1. prefer names containing the query as a contiguous phrase — keeps
     *     "UTI Nifty 50 Index Fund" from resolving to "UTI Nifty NEXT 50 Index Fund"
     *     (which also contains every token, just not contiguously);
     *  2. otherwise names containing every query token;
     *  3. keep Growth plans, drop IDCW/Dividend/Bonus/Institutional variants;
     *  4. Direct beats Regular; the shortest name wins ties.
     */
    private fun pickBestScheme(query: String, schemes: List<Pair<Int, String>>): Int? {
        if (schemes.isEmpty()) return null
        val phrase = query.trim().lowercase(Locale.US)
        val tokens = phrase.split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }

        val phraseMatched = schemes.filter { (_, name) ->
            name.lowercase(Locale.US).contains(phrase)
        }
        val stage1 = phraseMatched.ifEmpty {
            schemes.filter { (_, name) ->
                val lower = name.lowercase(Locale.US)
                tokens.all { lower.contains(it) }
            }
        }.ifEmpty { schemes }

        val stage2 = stage1.filter { (_, name) ->
            val lower = name.lowercase(Locale.US)
            lower.contains("growth") && EXCLUDED_PLAN_WORDS.none { lower.contains(it) }
        }.ifEmpty { stage1 }

        return stage2.sortedWith(
            compareByDescending<Pair<Int, String>> {
                it.second.contains("direct", ignoreCase = true)
            }.thenBy { it.second.length }
        ).firstOrNull()?.first
    }

    // ─── Fund detail fetch ────────────────────────────────────────────────────

    private fun fetchFund(schemeCode: Int): MutualFund? = runCatching {
        val body = fetchUrl("$BASE_URL/$schemeCode")
        val root = JSONObject(body)
        val meta = root.optJSONObject("meta") ?: return@runCatching null
        val data = root.optJSONArray("data") ?: return@runCatching null
        if (data.length() == 0) return@runCatching null

        val sdf = SimpleDateFormat("dd-MM-yyyy", Locale.US)
        val rows = minOf(data.length(), MAX_PARSED_ROWS)
        val entries = ArrayList<NavEntry>(rows)      // newest first, as the API returns
        for (i in 0 until rows) {
            val row = data.optJSONObject(i) ?: continue
            val nav = row.optString("nav", "").toDoubleOrNull() ?: continue
            if (nav <= 0.0) continue
            val date = row.optString("date", "")
            val timeMs = runCatching { sdf.parse(date)?.time }.getOrNull() ?: continue
            entries.add(NavEntry(timeMs, nav, date))
        }
        if (entries.isEmpty()) return@runCatching null

        val latest = entries.first()
        val yearAgoMs = latest.timeMs - 365L * MS_PER_DAY
        val lastYearNavs = entries
            .filter { it.timeMs >= yearAgoMs }
            .map { it.nav }
            .reversed()                              // oldest → newest for the sparkline

        MutualFund(
            schemeCode = meta.optInt("scheme_code", schemeCode),
            name       = meta.optString("scheme_name", "Scheme $schemeCode"),
            fundHouse  = meta.optString("fund_house", ""),
            category   = meta.optString("scheme_category", ""),
            nav        = latest.nav,
            navDate    = latest.date,
            return1y   = cagrPercent(entries, 365),
            return3y   = cagrPercent(entries, 1095),
            return5y   = cagrPercent(entries, 1825),
            navHistory = downsample(lastYearNavs, SPARKLINE_POINTS),
            ageDays    = ((System.currentTimeMillis() - latest.timeMs) / MS_PER_DAY).coerceAtLeast(0L)
        )
    }.getOrElse {
        Log.w(TAG, "fetchFund($schemeCode) failed: ${it.message}")
        null
    }

    /**
     * Trailing CAGR in percent — NAV closest to [daysBack] days before the latest
     * date, annualised over the actual day span:
     * (latest/old)^(365/daysBetween) − 1. Null when history is too short.
     */
    private fun cagrPercent(entries: List<NavEntry>, daysBack: Int): Double? {
        if (entries.size < 2) return null
        val latest = entries.first()
        val targetMs = latest.timeMs - daysBack * MS_PER_DAY
        val old = entries.minByOrNull { abs(it.timeMs - targetMs) } ?: return null
        val daysBetween = ((latest.timeMs - old.timeMs) / MS_PER_DAY).toInt()
        // History too short — the closest NAV is over a month away from the target date
        if (daysBetween < daysBack - 30 || daysBetween <= 0) return null
        if (old.nav <= 0.0 || latest.nav <= 0.0) return null
        val cagr = (latest.nav / old.nav).pow(365.0 / daysBetween) - 1.0
        if (cagr.isNaN() || cagr.isInfinite()) return null
        return cagr * 100.0
    }

    /** Evenly downsample [points] to at most [target] values, keeping first and last. */
    private fun downsample(points: List<Double>, target: Int): List<Double> {
        if (points.size <= target) return points
        val step = (points.size - 1).toDouble() / (target - 1)
        return (0 until target).map { i ->
            points[(i * step).roundToInt().coerceIn(0, points.size - 1)]
        }
    }

    // ─── HTTP ─────────────────────────────────────────────────────────────────

    private fun fetchUrl(url: String): String {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
            return response.body?.string() ?: throw Exception("Empty response body")
        }
    }
}
