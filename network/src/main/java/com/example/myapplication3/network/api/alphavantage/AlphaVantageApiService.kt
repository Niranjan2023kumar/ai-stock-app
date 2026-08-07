package com.example.myapplication3.network.api.alphavantage

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

interface AlphaVantageApiService {

    @GET("query")
    suspend fun getIntradayData(
        @Query("function") function: String = "TIME_SERIES_INTRADAY",
        @Query("symbol") symbol: String,
        @Query("interval") interval: String,
        @Query("outputsize") outputSize: String = "full",
        @Query("datatype") dataType: String = "json",
        @Query("apikey") apiKey: String
    ): Response<AlphaVantageIntradayResponse>

    @GET("query")
    suspend fun getGlobalQuote(
        @Query("function") function: String = "GLOBAL_QUOTE",
        @Query("symbol") symbol: String,
        @Query("apikey") apiKey: String
    ): Response<AlphaVantageGlobalQuoteResponse>

    @GET("query")
    suspend fun getRsi(
        @Query("function") function: String = "RSI",
        @Query("symbol") symbol: String,
        @Query("interval") interval: String = "5min",
        @Query("time_period") timePeriod: Int = 14,
        @Query("series_type") seriesType: String = "close",
        @Query("apikey") apiKey: String
    ): Response<RsiResponse>

    @GET("query")
    suspend fun getMacd(
        @Query("function") function: String = "MACD",
        @Query("symbol") symbol: String,
        @Query("interval") interval: String = "5min",
        @Query("series_type") seriesType: String = "close",
        @Query("apikey") apiKey: String
    ): Response<MacdResponse>

    @GET("query")
    suspend fun getVwap(
        @Query("function") function: String = "VWAP",
        @Query("symbol") symbol: String,
        @Query("interval") interval: String = "5min",
        @Query("apikey") apiKey: String
    ): Response<VwapResponse>
}
