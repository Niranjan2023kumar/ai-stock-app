package com.example.myapplication3.network.api.polygon

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface PolygonApiService {

    @GET("v2/aggs/ticker/{ticker}/range/{multiplier}/{timespan}/{from}/{to}")
    suspend fun getAggregates(
        @Path("ticker") ticker: String,
        @Path("multiplier") multiplier: Int,
        @Path("timespan") timespan: String,
        @Path("from") from: String,
        @Path("to") to: String,
        @Query("adjusted") adjusted: Boolean = true,
        @Query("sort") sort: String = "asc",
        @Query("limit") limit: Int = 5000,
        @Query("apiKey") apiKey: String
    ): Response<PolygonAggregatesResponse>

    @GET("v3/reference/tickers/{ticker}")
    suspend fun getTickerDetails(
        @Path("ticker") ticker: String,
        @Query("apiKey") apiKey: String
    ): Response<PolygonTickerDetailsResponse>

    @GET("v2/snapshot/locale/global/markets/stocks/tickers/{stocksTicker}")
    suspend fun getSnapshot(
        @Path("stocksTicker") ticker: String,
        @Query("apiKey") apiKey: String
    ): Response<PolygonSnapshotResponse>
}
