package com.example.myapplication3.intraday

import android.util.Log
import com.example.myapplication3.network.api.nse.NseApiService
import com.example.myapplication3.network.api.nse.NseIpoIssueDto
import com.example.myapplication3.network.api.nse.NseMarketStatusResponse
import com.example.myapplication3.network.api.nse.NseStockData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One IPO (or unit issue) from the NSE feed, in app-ready form.
 *
 * Honesty rules (A5/B6): every field either comes straight from NSE or is
 * `null` — never an invented number. `null` price/lot means "NSE did not give
 * a readable value"; the UI must show it as unavailable, not as 0.
 *
 * @param name      Company name (falls back to [symbol] when NSE omits it).
 * @param symbol    NSE symbol, e.g. "XTRANET".
 * @param openDate  Bidding start date exactly as NSE sends it, "23-Jul-2026".
 * @param closeDate Bidding end date, same format.
 * @param priceMin  Lower price band in ₹, or null when unparseable.
 * @param priceMax  Upper price band in ₹ (equals [priceMin] for fixed-price
 *                  issues), or null when unparseable.
 * @param lotSize   Minimum bid quantity (shares/units), or null when the
 *                  detail endpoint failed or gave no readable number.
 * @param isOpenNow True only when NSE says the issue is Active AND today is
 *                  inside the open–close window (fail-safe: unknown = false).
 */
data class IpoIssue(
    val name: String,
    val symbol: String,
    val openDate: String,
    val closeDate: String,
    val priceMin: Double?,
    val priceMax: Double?,
    val lotSize: Int?,
    val isOpenNow: Boolean
)

@Singleton
class NseRepository @Inject constructor(
    private val nseApi: NseApiService
) {

    companion object {
        private const val TAG = "NseRepo"

        // NSE index identifiers used by the live-analysis endpoint
        private const val INDEX_MOST_ACTIVE = "NIFTY 500"
        private const val INDEX_GAINERS = "NIFTY 500"
        private const val INDEX_LOSERS = "NIFTY 500"

        /**
         * Matches one number in NSE free text, Indian grouping included —
         * "Rs.120", "Rs. 2,00,000", "Minimum 110 Equity Shares" → 120 /
         * 200000 / 110.
         */
        private val FIRST_NUMBER = Regex("""\d[\d,]*(?:\.\d+)?""")
    }

    data class LiveAnalysisResult(
        val mostActive: List<NseStockData>,
        val gainers: List<NseStockData>,
        val losers: List<NseStockData>
    )

    /**
     * Returns the current NSE market status, or null if the request fails.
     */
    suspend fun getMarketStatus(): NseMarketStatusResponse? = withContext(Dispatchers.IO) {
        try {
            val response = nseApi.getMarketStatus()
            if (response.isSuccessful) {
                response.body()
            } else {
                Log.e(TAG, "getMarketStatus failed: HTTP ${response.code()}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "getMarketStatus error: ${e.message}", e)
            null
        }
    }

    /**
     * Returns a [LiveAnalysisResult] combining most-active, gainers, and losers.
     * Each list falls back to empty on failure.
     */
    suspend fun getLiveAnalysis(): LiveAnalysisResult = withContext(Dispatchers.IO) {
        LiveAnalysisResult(
            mostActive = getMostActive(),
            gainers = getGainers(),
            losers = getLosers()
        )
    }

    /**
     * ONE-call snapshot for fallback consumers (IntradayRepository's Yahoo-down
     * path): [getLiveAnalysis] fires three identical HTTP calls to the same
     * endpoint; this fetches the rows ONCE and derives all three views from the
     * same response — a third of the cost, important when it runs inside a
     * refresh cycle. Fail-safe: empty lists on any error.
     */
    suspend fun getUniverseSnapshot(): LiveAnalysisResult = withContext(Dispatchers.IO) {
        try {
            val response = nseApi.getLiveAnalysisVariations(INDEX_MOST_ACTIVE)
            val data = if (response.isSuccessful) {
                response.body()?.data ?: emptyList()
            } else {
                Log.e(TAG, "getUniverseSnapshot failed: HTTP ${response.code()}")
                emptyList()
            }
            LiveAnalysisResult(
                mostActive = data.sortedByDescending { it.tradedQuantity },
                gainers    = data.filter { it.netPrice > 0.0 }.sortedByDescending { it.netPrice },
                losers     = data.filter { it.netPrice < 0.0 }.sortedBy { it.netPrice }
            )
        } catch (e: Exception) {
            Log.e(TAG, "getUniverseSnapshot error: ${e.message}", e)
            LiveAnalysisResult(emptyList(), emptyList(), emptyList())
        }
    }

    /**
     * Returns stocks sorted by traded volume (descending), falling back to empty list on error.
     */
    suspend fun getMostActive(): List<NseStockData> = withContext(Dispatchers.IO) {
        try {
            val response = nseApi.getLiveAnalysisVariations(INDEX_MOST_ACTIVE)
            if (response.isSuccessful) {
                response.body()?.data
                    ?.sortedByDescending { it.tradedQuantity }
                    ?: emptyList()
            } else {
                Log.e(TAG, "getMostActive failed: HTTP ${response.code()}")
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "getMostActive error: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Returns stocks with a positive net price change, sorted descending, falling back to empty list on error.
     */
    suspend fun getGainers(): List<NseStockData> = withContext(Dispatchers.IO) {
        try {
            val response = nseApi.getLiveAnalysisVariations(INDEX_GAINERS)
            if (response.isSuccessful) {
                response.body()?.data
                    ?.filter { it.netPrice > 0.0 }
                    ?.sortedByDescending { it.netPrice }
                    ?: emptyList()
            } else {
                Log.e(TAG, "getGainers failed: HTTP ${response.code()}")
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "getGainers error: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Fetches current + upcoming IPO issues from NSE.
     *
     * Primary call: `GET /api/all-upcoming-issues?category=ipo` (one feed for
     * both open and forthcoming issues — the dead `/api/ipo-current-issues`
     * endpoint returns 404 and is deliberately NOT used).
     *
     * Lot size is not in the feed, so each row is enriched (in parallel) via
     * `GET /api/ipo-detail`; an enrichment failure only nulls that row's
     * [IpoIssue.lotSize] — it never drops the row or fails the list.
     *
     * Fail-safe (B8): returns an EMPTY list on any top-level failure so the
     * UI keeps its honest empty state. Open-now issues are listed first.
     */
    suspend fun fetchIpos(): List<IpoIssue> = withContext(Dispatchers.IO) {
        try {
            val response = nseApi.getUpcomingIpoIssues()
            if (!response.isSuccessful) {
                Log.e(TAG, "fetchIpos failed: HTTP ${response.code()}")
                return@withContext emptyList()
            }
            val rows = response.body().orEmpty()
                .filter { it.symbol.isNotBlank() || it.companyName.isNotBlank() }
            coroutineScope {
                rows.map { row ->
                    async { toIpoIssue(row, fetchLotSize(row)) }
                }.awaitAll()
            }.sortedByDescending { it.isOpenNow }
        } catch (e: Exception) {
            Log.e(TAG, "fetchIpos error: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Reads the minimum bid quantity for one issue from the ipo-detail
     * endpoint. Returns null on ANY problem — never an invented number.
     */
    private suspend fun fetchLotSize(row: NseIpoIssueDto): Int? {
        if (row.symbol.isBlank()) return null
        return try {
            val response = nseApi.getIpoDetail(
                symbol = row.symbol,
                series = row.series.ifBlank { "EQ" }
            )
            if (!response.isSuccessful) return null
            val items = response.body()?.issueInfo?.dataList.orEmpty()
            val text = items.firstOrNull {
                it.title?.contains("Minimum Order Quantity", ignoreCase = true) == true
            }?.value ?: items.firstOrNull {
                it.title?.contains("Bid Lot", ignoreCase = true) == true
            }?.value
            text?.let { FIRST_NUMBER.find(it)?.value?.replace(",", "")?.toIntOrNull() }
                ?.takeIf { it > 0 }
        } catch (e: Exception) {
            Log.e(TAG, "fetchLotSize(${row.symbol}) error: ${e.message}")
            null
        }
    }

    private fun toIpoIssue(row: NseIpoIssueDto, lotSize: Int?): IpoIssue {
        val (priceMin, priceMax) = parsePriceBand(row.issuePrice)
        return IpoIssue(
            name = row.companyName.trim().ifBlank { row.symbol.trim() },
            symbol = row.symbol.trim(),
            openDate = row.issueStartDate.trim(),
            closeDate = row.issueEndDate.trim(),
            priceMin = priceMin,
            priceMax = priceMax,
            lotSize = lotSize,
            isOpenNow = isOpenNow(row)
        )
    }

    /**
     * Parses NSE's free-text price band, e.g. "Rs.120 to Rs.127" or a single
     * fixed price "Rs.100" (then min == max). Null/null when unreadable.
     */
    private fun parsePriceBand(raw: String): Pair<Double?, Double?> {
        val numbers = FIRST_NUMBER.findAll(raw)
            .mapNotNull { it.value.replace(",", "").toDoubleOrNull() }
            .filter { it > 0.0 }
            .toList()
        return when {
            numbers.isEmpty()   -> null to null
            numbers.size == 1   -> numbers[0] to numbers[0]
            else                -> numbers.first() to numbers.last()
        }
    }

    /**
     * Open-now = NSE marks the issue "Active" AND today falls inside the
     * open–close window (both days inclusive). If the dates cannot be read,
     * NSE's own "Active" flag is trusted alone. Any other status — including
     * "Forthcoming" or an unknown value — is NOT open (fail-safe).
     */
    private fun isOpenNow(row: NseIpoIssueDto): Boolean {
        if (!row.status.trim().equals("Active", ignoreCase = true)) return false
        return try {
            val fmt = SimpleDateFormat("dd-MMM-yyyy", Locale.ENGLISH).apply { isLenient = false }
            val start = fmt.parse(row.issueStartDate.trim())
            val end = fmt.parse(row.issueEndDate.trim())
            if (start == null || end == null) return true
            // Normalise "now" to midnight so both boundary days count as open.
            val today = fmt.parse(fmt.format(Date())) ?: return true
            !today.before(start) && !today.after(end)
        } catch (e: Exception) {
            true // dates unreadable — trust NSE's Active flag
        }
    }

    /**
     * Returns stocks with a negative net price change, sorted ascending (biggest losers first),
     * falling back to empty list on error.
     */
    suspend fun getLosers(): List<NseStockData> = withContext(Dispatchers.IO) {
        try {
            val response = nseApi.getLiveAnalysisVariations(INDEX_LOSERS)
            if (response.isSuccessful) {
                response.body()?.data
                    ?.filter { it.netPrice < 0.0 }
                    ?.sortedBy { it.netPrice }
                    ?: emptyList()
            } else {
                Log.e(TAG, "getLosers failed: HTTP ${response.code()}")
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "getLosers error: ${e.message}", e)
            emptyList()
        }
    }
}
