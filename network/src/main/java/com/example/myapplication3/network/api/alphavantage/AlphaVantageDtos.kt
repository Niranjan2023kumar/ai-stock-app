package com.example.myapplication3.network.api.alphavantage

import com.google.gson.annotations.SerializedName

data class AlphaVantageIntradayResponse(
    @SerializedName("Meta Data") val metadata: AlphaVantageMetadata?,
    @SerializedName("Time Series (1min)") val series1min: Map<String, AlphaVantageCandle>?,
    @SerializedName("Time Series (5min)") val series5min: Map<String, AlphaVantageCandle>?,
    @SerializedName("Time Series (15min)") val series15min: Map<String, AlphaVantageCandle>?,
    @SerializedName("Time Series (30min)") val series30min: Map<String, AlphaVantageCandle>?,
    @SerializedName("Time Series (60min)") val series60min: Map<String, AlphaVantageCandle>?
)

data class AlphaVantageMetadata(
    @SerializedName("1. Information") val information: String = "",
    @SerializedName("2. Symbol") val symbol: String = "",
    @SerializedName("3. Last Refreshed") val lastRefreshed: String = "",
    @SerializedName("4. Interval") val interval: String = "",
    @SerializedName("5. Output Size") val outputSize: String = "",
    @SerializedName("6. Time Zone") val timeZone: String = ""
)

data class AlphaVantageCandle(
    @SerializedName("1. open") val open: String,
    @SerializedName("2. high") val high: String,
    @SerializedName("3. low") val low: String,
    @SerializedName("4. close") val close: String,
    @SerializedName("5. volume") val volume: String
) {
    fun toDoubles() = AlphaVantageCandleValues(
        open.toDoubleOrNull() ?: 0.0,
        high.toDoubleOrNull() ?: 0.0,
        low.toDoubleOrNull() ?: 0.0,
        close.toDoubleOrNull() ?: 0.0,
        volume.toLongOrNull() ?: 0L
    )
}

data class AlphaVantageCandleValues(
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long
)

data class AlphaVantageGlobalQuoteResponse(
    @SerializedName("Global Quote") val globalQuote: AlphaVantageGlobalQuote?
)

data class AlphaVantageGlobalQuote(
    @SerializedName("01. symbol") val symbol: String = "",
    @SerializedName("02. open") val open: String = "0",
    @SerializedName("03. high") val high: String = "0",
    @SerializedName("04. low") val low: String = "0",
    @SerializedName("05. price") val price: String = "0",
    @SerializedName("06. volume") val volume: String = "0",
    @SerializedName("07. latest trading day") val latestTradingDay: String = "",
    @SerializedName("08. previous close") val previousClose: String = "0",
    @SerializedName("09. change") val change: String = "0",
    @SerializedName("10. change percent") val changePercent: String = "0%"
)
