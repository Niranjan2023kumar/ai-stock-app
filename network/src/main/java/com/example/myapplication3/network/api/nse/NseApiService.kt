package com.example.myapplication3.network.api.nse

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Query

/**
 * Retrofit service interface for the NSE India public API.
 *
 * Base URL: `https://www.nseindia.com/`
 *
 * All endpoints include the browser-style headers that NSE requires to avoid
 * 403 / 401 rejections from its anti-scraping middleware.
 */
interface NseApiService {

    /**
     * Fetch the current market status for all NSE segments.
     *
     * Endpoint: `GET /api/marketStatus`
     *
     * @return [NseMarketStatusResponse] wrapped in a Retrofit [Response].
     */
    @Headers(
        "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
        "Accept: */*",
        "Referer: https://www.nseindia.com/",
        "Accept-Language: en-US,en;q=0.9"
    )
    @GET("api/marketStatus")
    suspend fun getMarketStatus(): Response<NseMarketStatusResponse>

    /**
     * Fetch live price/volume variations for all stocks in a given index.
     *
     * Endpoint: `GET /api/live-analysis-variations?index={index}`
     *
     * @param index  NSE index name, e.g. `"NIFTY 50"`, `"NIFTY BANK"`,
     *               `"NIFTY MIDCAP 100"`.
     * @return [NseLiveAnalysisResponse] wrapped in a Retrofit [Response].
     */
    @Headers(
        "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
        "Accept: */*",
        "Referer: https://www.nseindia.com/",
        "Accept-Language: en-US,en;q=0.9"
    )
    @GET("api/live-analysis-variations")
    suspend fun getLiveAnalysisVariations(
        @Query("index") index: String
    ): Response<NseLiveAnalysisResponse>

    /**
     * Fetch current AND upcoming IPO issues (one feed for both; the row's
     * `status` field says which: `"Active"` = open now, `"Forthcoming"`).
     *
     * Endpoint: `GET /api/all-upcoming-issues?category=ipo`
     *
     * Live-verified 2026-07-24: returns HTTP 200 with a bare JSON array using
     * this module's cookie-less browser-header pattern. The sibling endpoint
     * `/api/ipo-current-issues` is DEAD (404) — never add it back.
     */
    @Headers(
        "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
        "Accept: */*",
        "Referer: https://www.nseindia.com/",
        "Accept-Language: en-US,en;q=0.9"
    )
    @GET("api/all-upcoming-issues")
    suspend fun getUpcomingIpoIssues(
        @Query("category") category: String = "ipo"
    ): Response<List<NseIpoIssueDto>>

    /**
     * Fetch full detail for one IPO issue — used only to read the lot size
     * from `issueInfo.dataList` ("Minimum Order Quantity" / "Bid Lot").
     *
     * Endpoint: `GET /api/ipo-detail?symbol={symbol}&series={series}`
     *
     * Works for equity (`series=EQ`) and unit issues (`series=IV`),
     * live-verified 2026-07-24.
     */
    @Headers(
        "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
        "Accept: */*",
        "Referer: https://www.nseindia.com/",
        "Accept-Language: en-US,en;q=0.9"
    )
    @GET("api/ipo-detail")
    suspend fun getIpoDetail(
        @Query("symbol") symbol: String,
        @Query("series") series: String = "EQ"
    ): Response<NseIpoDetailResponse>
}
