package com.example.myapplication3.network.api.finnhub

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

interface FinnhubApiService {

    @GET("stock/candle")
    suspend fun getCandles(
        @Query("symbol") symbol: String,
        @Query("resolution") resolution: String,
        @Query("from") from: Long,
        @Query("to") to: Long,
        @Query("token") token: String
    ): Response<FinnhubCandleResponse>

    @GET("quote")
    suspend fun getQuote(
        @Query("symbol") symbol: String,
        @Query("token") token: String
    ): Response<FinnhubQuoteResponse>

    @GET("news")
    suspend fun getMarketNews(
        @Query("category") category: String = "general",
        @Query("token") token: String
    ): Response<List<FinnhubNewsResponse>>

    @GET("company-news")
    suspend fun getCompanyNews(
        @Query("symbol") symbol: String,
        @Query("from") from: String,
        @Query("to") to: String,
        @Query("token") token: String
    ): Response<List<FinnhubCompanyNewsResponse>>

    @GET("stock/profile2")
    suspend fun getCompanyProfile(
        @Query("symbol") symbol: String,
        @Query("token") token: String
    ): Response<FinnhubProfileResponse>

    @GET("news-sentiment")
    suspend fun getNewsSentiment(
        @Query("symbol") symbol: String,
        @Query("token") token: String
    ): Response<FinnhubSentimentResponse>
}
