package com.example.myapplication3.network.api.finnhub

import com.google.gson.annotations.SerializedName

data class FinnhubCandleResponse(
    @SerializedName("c") val closes: List<Double>?,
    @SerializedName("h") val highs: List<Double>?,
    @SerializedName("l") val lows: List<Double>?,
    @SerializedName("o") val opens: List<Double>?,
    @SerializedName("v") val volumes: List<Long>?,
    @SerializedName("t") val timestamps: List<Long>?,
    @SerializedName("s") val status: String
)

data class FinnhubQuoteResponse(
    @SerializedName("c") val currentPrice: Double,
    @SerializedName("d") val change: Double,
    @SerializedName("dp") val changePercent: Double,
    @SerializedName("h") val high: Double,
    @SerializedName("l") val low: Double,
    @SerializedName("o") val open: Double,
    @SerializedName("pc") val previousClose: Double,
    @SerializedName("t") val timestamp: Long
)

data class FinnhubNewsResponse(
    @SerializedName("category") val category: String,
    @SerializedName("datetime") val datetime: Long,
    @SerializedName("headline") val headline: String,
    @SerializedName("id") val id: Long,
    @SerializedName("image") val image: String,
    @SerializedName("related") val related: String,
    @SerializedName("source") val source: String,
    @SerializedName("summary") val summary: String,
    @SerializedName("url") val url: String
)

data class FinnhubCompanyNewsResponse(
    @SerializedName("category") val category: String,
    @SerializedName("datetime") val datetime: Long,
    @SerializedName("headline") val headline: String,
    @SerializedName("id") val id: Long,
    @SerializedName("image") val image: String,
    @SerializedName("related") val related: String,
    @SerializedName("source") val source: String,
    @SerializedName("summary") val summary: String,
    @SerializedName("url") val url: String
)

data class FinnhubProfileResponse(
    @SerializedName("country") val country: String = "",
    @SerializedName("currency") val currency: String = "",
    @SerializedName("exchange") val exchange: String = "",
    @SerializedName("finnhubIndustry") val industry: String = "",
    @SerializedName("ipo") val ipo: String = "",
    @SerializedName("logo") val logo: String = "",
    @SerializedName("marketCapitalization") val marketCap: Double = 0.0,
    @SerializedName("name") val name: String = "",
    @SerializedName("shareOutstanding") val sharesOutstanding: Double = 0.0,
    @SerializedName("ticker") val ticker: String = "",
    @SerializedName("weburl") val webUrl: String = ""
)

data class FinnhubSentimentResponse(
    @SerializedName("buzz") val buzz: FinnhubBuzz?,
    @SerializedName("companyNewsScore") val companyNewsScore: Double = 0.0,
    @SerializedName("sectorAverageBullishPercent") val sectorBullish: Double = 0.0,
    @SerializedName("sectorAverageNewsScore") val sectorScore: Double = 0.0,
    @SerializedName("sentiment") val sentiment: FinnhubSentimentData?,
    @SerializedName("symbol") val symbol: String = ""
)

data class FinnhubBuzz(
    @SerializedName("articlesInLastWeek") val articlesLastWeek: Int = 0,
    @SerializedName("buzz") val buzz: Double = 0.0,
    @SerializedName("weeklyAverage") val weeklyAverage: Double = 0.0
)

data class FinnhubSentimentData(
    @SerializedName("bearishPercent") val bearishPercent: Double = 0.0,
    @SerializedName("bullishPercent") val bullishPercent: Double = 0.0
)
