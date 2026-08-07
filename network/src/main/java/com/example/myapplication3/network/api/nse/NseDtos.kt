package com.example.myapplication3.network.api.nse

import com.google.gson.annotations.SerializedName

// ── Market Status ─────────────────────────────────────────────────────────────

/**
 * Top-level response from NSE `/api/marketStatus`.
 */
data class NseMarketStatusResponse(
    @field:SerializedName("marketState")
    val marketState: List<MarketStateItem> = emptyList()
)

/**
 * One entry in the `marketState` array — describes the status of a single
 * NSE market segment (e.g. Capital Market, F&O, Currency Derivatives …).
 */
data class MarketStateItem(
    @field:SerializedName("market")
    val market: String = "",

    @field:SerializedName("marketStatus")
    val marketStatus: String = "",

    @field:SerializedName("tradeDate")
    val tradeDate: String = "",

    @field:SerializedName("index")
    val index: String = "",

    @field:SerializedName("last")
    val last: Double = 0.0,

    @field:SerializedName("variation")
    val variation: Double = 0.0,

    @field:SerializedName("percentChange")
    val percentChange: Double = 0.0,

    @field:SerializedName("marketStatusMessage")
    val marketStatusMessage: String = ""
)

// ── Live Analysis / Variations ────────────────────────────────────────────────

/**
 * Top-level response from NSE `/api/live-analysis-variations`.
 * NSE sometimes returns `data` as a String (e.g. when market is closed or
 * the index is temporarily unavailable) instead of the expected Array — the
 * raw JsonElement approach handles both gracefully.
 */
data class NseLiveAnalysisResponse(
    @field:SerializedName("data")
    private val _dataRaw: com.google.gson.JsonElement? = null
) {
    val data: List<NseStockData>
        get() {
            val el = _dataRaw ?: return emptyList()
            if (el.isJsonNull || !el.isJsonArray) return emptyList()
            return el.asJsonArray.mapNotNull { item ->
                runCatching { GSON.fromJson(item, NseStockData::class.java) }.getOrNull()
            }
        }

    companion object {
        private val GSON = com.google.gson.Gson()
    }
}

/**
 * Per-symbol data row returned by the live-analysis endpoint.
 */
data class NseStockData(
    @field:SerializedName("symbol")
    val symbol: String = "",

    @field:SerializedName("series")
    val series: String = "",

    @field:SerializedName("open")
    val open: Double = 0.0,

    @field:SerializedName("high")
    val high: Double = 0.0,

    @field:SerializedName("low")
    val low: Double = 0.0,

    @field:SerializedName("ltp")
    val ltp: Double = 0.0,

    @field:SerializedName("previousPrice")
    val previousPrice: Double = 0.0,

    @field:SerializedName("netPrice")
    val netPrice: Double = 0.0,

    @field:SerializedName("tradedQuantity")
    val tradedQuantity: Long = 0L,

    @field:SerializedName("turnover")
    val turnover: Double = 0.0,

    @field:SerializedName("lastCorpAnnouncement")
    val lastCorpAnnouncement: String = "",

    @field:SerializedName("lastCorpAnnouncementDate")
    val lastCorpAnnouncementDate: String = "",

    @field:SerializedName("lastPrice")
    val lastPrice: Double = 0.0
)

// ── IPO Issues (current + upcoming) ───────────────────────────────────────────

/**
 * One row from NSE `/api/all-upcoming-issues?category=ipo`.
 *
 * The endpoint returns a bare JSON array. It covers BOTH currently-open and
 * forthcoming issues; [status] distinguishes them (`"Active"` = bidding open,
 * `"Forthcoming"` = not yet open). NOTE: `/api/ipo-current-issues` is DEAD
 * (404 "Resource not found", verified 2026-07-24) — do not add it.
 *
 * Live-verified sample row (2026-07-24):
 * ```
 * {"companyName":"Xtranet Technologies Limited","issueEndDate":"27-Jul-2026",
 *  "issuePrice":"Rs.120 to Rs.127","issueSize":"9193800",
 *  "issueStartDate":"23-Jul-2026","series":"EQ","status":"Active",
 *  "symbol":"XTRANET"}
 * ```
 * `series` can also be `"IV"` (InvIT/REIT units) or SME series — not only EQ.
 */
data class NseIpoIssueDto(
    @field:SerializedName("companyName")
    val companyName: String = "",

    @field:SerializedName("symbol")
    val symbol: String = "",

    @field:SerializedName("series")
    val series: String = "",

    @field:SerializedName("issueStartDate")
    val issueStartDate: String = "",

    @field:SerializedName("issueEndDate")
    val issueEndDate: String = "",

    /** Free-text price band, e.g. `"Rs.120 to Rs.127"`. Parse defensively. */
    @field:SerializedName("issuePrice")
    val issuePrice: String = "",

    /** Total shares offered, as a plain-number string, e.g. `"9193800"`. */
    @field:SerializedName("issueSize")
    val issueSize: String = "",

    /** `"Active"` (open for bidding) or `"Forthcoming"`. */
    @field:SerializedName("status")
    val status: String = ""
)

/**
 * Trimmed response from NSE `/api/ipo-detail?symbol=X&series=Y`.
 * Only [issueInfo] is mapped — its `dataList` title/value pairs carry the lot
 * size ("Minimum Order Quantity" => "Minimum 110 Equity Shares",
 * "Bid Lot" => "95 units and in multiples thereof").
 */
data class NseIpoDetailResponse(
    @field:SerializedName("issueInfo")
    val issueInfo: NseIpoIssueInfo? = null
)

/** `issueInfo` object inside [NseIpoDetailResponse]. */
data class NseIpoIssueInfo(
    @field:SerializedName("dataList")
    val dataList: List<NseIpoInfoItem> = emptyList()
)

/** One title/value row inside `issueInfo.dataList`. Either side may be null. */
data class NseIpoInfoItem(
    @field:SerializedName("title")
    val title: String? = null,

    @field:SerializedName("value")
    val value: String? = null
)

// ── Market Status Enum ────────────────────────────────────────────────────────

/**
 * Strongly-typed representation of the NSE market state.
 * Map the raw [MarketStateItem.marketStatus] string to this enum in the
 * repository layer.
 */
enum class NseMarketStatus {
    /** Regular trading session is active. */
    OPEN,

    /** Market is closed (after 3:30 PM or holiday/weekend). */
    CLOSED,

    /** Pre-open call auction session (9:00–9:15 AM IST). */
    PRE_OPEN,

    /** Post-close session (3:40–4:00 PM IST). */
    POST_CLOSE;

    companion object {
        /**
         * Parse the raw string returned by NSE (e.g. `"Open"`, `"Close"`,
         * `"Pre-open"`, `"Post Close"`) into a [NseMarketStatus].
         * Returns [CLOSED] for any unrecognised value.
         */
        fun from(raw: String): NseMarketStatus = when (raw.trim().lowercase()) {
            "open"        -> OPEN
            "close",
            "closed"      -> CLOSED
            "pre-open",
            "preopen",
            "pre open"    -> PRE_OPEN
            "post close",
            "postclose",
            "post-close"  -> POST_CLOSE
            else          -> CLOSED
        }
    }
}
