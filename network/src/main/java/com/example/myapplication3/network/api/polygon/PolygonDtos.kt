package com.example.myapplication3.network.api.polygon

import com.google.gson.annotations.SerializedName

data class PolygonAggregatesResponse(
    @SerializedName("ticker") val ticker: String,
    @SerializedName("resultsCount") val resultsCount: Int = 0,
    @SerializedName("results") val results: List<PolygonCandle>? = null,
    @SerializedName("status") val status: String,
    @SerializedName("queryCount") val queryCount: Int = 0,
    @SerializedName("adjusted") val adjusted: Boolean = true
)

data class PolygonCandle(
    @SerializedName("o") val open: Double,
    @SerializedName("h") val high: Double,
    @SerializedName("l") val low: Double,
    @SerializedName("c") val close: Double,
    @SerializedName("v") val volume: Double,
    @SerializedName("vw") val vwap: Double = 0.0,
    @SerializedName("t") val timestamp: Long,
    @SerializedName("n") val transactions: Int = 0
)

data class PolygonTickerDetailsResponse(
    @SerializedName("results") val results: PolygonTickerDetails?,
    @SerializedName("status") val status: String
)

data class PolygonTickerDetails(
    @SerializedName("ticker") val ticker: String,
    @SerializedName("name") val name: String,
    @SerializedName("market") val market: String,
    @SerializedName("primary_exchange") val primaryExchange: String,
    @SerializedName("type") val type: String,
    @SerializedName("description") val description: String = "",
    @SerializedName("sic_description") val sicDescription: String = "",
    @SerializedName("weighted_shares_outstanding") val sharesOutstanding: Long = 0L,
    @SerializedName("market_cap") val marketCap: Double = 0.0
)

data class PolygonSnapshotResponse(
    @SerializedName("ticker") val ticker: PolygonSnapshot?,
    @SerializedName("status") val status: String
)

data class PolygonSnapshot(
    @SerializedName("ticker") val symbol: String,
    @SerializedName("day") val day: PolygonDayData?,
    @SerializedName("lastTrade") val lastTrade: PolygonLastTrade?,
    @SerializedName("lastQuote") val lastQuote: PolygonLastQuote?,
    @SerializedName("prevDay") val prevDay: PolygonDayData?,
    @SerializedName("todaysChangePerc") val changePercent: Double = 0.0,
    @SerializedName("todaysChange") val change: Double = 0.0,
    @SerializedName("min") val minute: PolygonMinuteData?
)

data class PolygonDayData(
    @SerializedName("o") val open: Double = 0.0,
    @SerializedName("h") val high: Double = 0.0,
    @SerializedName("l") val low: Double = 0.0,
    @SerializedName("c") val close: Double = 0.0,
    @SerializedName("v") val volume: Double = 0.0,
    @SerializedName("vw") val vwap: Double = 0.0
)

data class PolygonLastTrade(
    @SerializedName("p") val price: Double = 0.0,
    @SerializedName("s") val size: Long = 0L,
    @SerializedName("t") val timestamp: Long = 0L
)

data class PolygonLastQuote(
    @SerializedName("P") val askPrice: Double = 0.0,
    @SerializedName("S") val askSize: Long = 0L,
    @SerializedName("p") val bidPrice: Double = 0.0,
    @SerializedName("s") val bidSize: Long = 0L,
    @SerializedName("t") val timestamp: Long = 0L
)

data class PolygonMinuteData(
    @SerializedName("o") val open: Double = 0.0,
    @SerializedName("h") val high: Double = 0.0,
    @SerializedName("l") val low: Double = 0.0,
    @SerializedName("c") val close: Double = 0.0,
    @SerializedName("v") val volume: Double = 0.0,
    @SerializedName("vw") val vwap: Double = 0.0,
    @SerializedName("t") val timestamp: Long = 0L
)
