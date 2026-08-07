package com.example.myapplication3.marketdata.repository

import com.example.myapplication3.core.common.AppException
import com.example.myapplication3.core.common.DispatcherProvider
import com.example.myapplication3.core.common.Result
import com.example.myapplication3.core.domain.model.*
import com.example.myapplication3.database.dao.CandleDao
import com.example.myapplication3.marketdata.mapper.CandleMapper
import com.example.myapplication3.marketdata.validator.CandleValidator
import com.example.myapplication3.network.api.polygon.PolygonApiService
import com.example.myapplication3.network.api.finnhub.FinnhubApiService
import com.example.myapplication3.network.health.DataProvider
import com.example.myapplication3.network.health.ProviderHealthMonitor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class MarketDataRepository @Inject constructor(
    private val polygonApi: PolygonApiService,
    private val finnhubApi: FinnhubApiService,
    private val candleDao: CandleDao,
    private val healthMonitor: ProviderHealthMonitor,
    private val validator: CandleValidator,
    private val dispatchers: DispatcherProvider,
    @Named("polygonApiKey") private val polygonKey: String,
    @Named("finnhubApiKey") private val finnhubKey: String
) {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    fun getCachedCandles(symbol: String, exchange: Exchange, interval: CandleInterval): Flow<List<Candle>> =
        candleDao.getCandles(symbol, exchange.code, interval.name)
            .map { entities -> entities.map { CandleMapper.fromEntity(it) } }

    suspend fun fetchCandles(
        symbol: String,
        exchange: Exchange,
        interval: CandleInterval,
        fromMs: Long,
        toMs: Long
    ): Result<List<Candle>> {
        val provider = healthMonitor.getActivePrimaryProvider()
        return try {
            val candles = when (provider) {
                DataProvider.POLYGON -> fetchFromPolygon(symbol, exchange, interval, fromMs, toMs)
                DataProvider.FINNHUB -> fetchFromFinnhub(symbol, interval, fromMs, toMs)
                else -> fetchFromFinnhub(symbol, interval, fromMs, toMs)
            }

            val validated = validator.validate(candles)
            if (validated.valid.isEmpty()) {
                return Result.Error(AppException.InsufficientDataException(
                    "No valid candles for $symbol after validation. Rejected: ${validated.rejected.size}"
                ))
            }

            candleDao.insertCandles(validated.valid.map { CandleMapper.toEntity(it, exchange) })
            healthMonitor.recordSuccess(provider, 0L)
            Result.Success(validated.valid)
        } catch (e: AppException) {
            healthMonitor.recordFailure(provider)
            Result.Error(e)
        } catch (e: Exception) {
            healthMonitor.recordFailure(provider)
            Result.Error(AppException.NetworkException(e.message ?: "Network error", cause = e))
        }
    }

    private suspend fun fetchFromPolygon(
        symbol: String,
        exchange: Exchange,
        interval: CandleInterval,
        fromMs: Long,
        toMs: Long
    ): List<Candle> {
        val ticker = "${exchange.code}:$symbol"
        val (multiplier, timespan) = when (interval) {
            CandleInterval.ONE_MINUTE -> 1 to "minute"
            CandleInterval.THREE_MINUTES -> 3 to "minute"
            CandleInterval.FIVE_MINUTES -> 5 to "minute"
            CandleInterval.FIFTEEN_MINUTES -> 15 to "minute"
            CandleInterval.THIRTY_MINUTES -> 30 to "minute"
            CandleInterval.ONE_HOUR -> 1 to "hour"
            CandleInterval.ONE_DAY -> 1 to "day"
            CandleInterval.ONE_WEEK -> 1 to "week"
            CandleInterval.ONE_MONTH -> 1 to "month"
        }
        val from = dateFormat.format(Date(fromMs))
        val to = dateFormat.format(Date(toMs))

        val response = healthMonitor.getCircuitBreaker(DataProvider.POLYGON).execute {
            polygonApi.getAggregates(ticker, multiplier, timespan, from, to, apiKey = polygonKey)
        }

        if (!response.isSuccessful) {
            throw AppException.ApiException(
                "Polygon API error: ${response.code()} ${response.message()}",
                response.code(), "Polygon"
            )
        }

        return response.body()?.results?.map {
            CandleMapper.fromPolygon(symbol, exchange, interval, it)
        } ?: throw AppException.InsufficientDataException("Polygon returned no data for $symbol")
    }

    private suspend fun fetchFromFinnhub(
        symbol: String,
        interval: CandleInterval,
        fromMs: Long,
        toMs: Long
    ): List<Candle> {
        val resolution = when (interval) {
            CandleInterval.ONE_MINUTE -> "1"
            CandleInterval.FIVE_MINUTES -> "5"
            CandleInterval.FIFTEEN_MINUTES -> "15"
            CandleInterval.THIRTY_MINUTES -> "30"
            CandleInterval.ONE_HOUR -> "60"
            CandleInterval.ONE_DAY -> "D"
            CandleInterval.ONE_WEEK -> "W"
            CandleInterval.ONE_MONTH -> "M"
            else -> "5"
        }
        val finnhubSymbol = "${symbol}.NS"

        val response = healthMonitor.getCircuitBreaker(DataProvider.FINNHUB).execute {
            finnhubApi.getCandles(finnhubSymbol, resolution, fromMs / 1000, toMs / 1000, finnhubKey)
        }

        if (!response.isSuccessful) {
            throw AppException.ApiException(
                "Finnhub API error: ${response.code()}",
                response.code(), "Finnhub"
            )
        }

        val body = response.body() ?: throw AppException.InsufficientDataException("Finnhub returned null")
        if (body.status != "ok") throw AppException.InsufficientDataException("Finnhub status: ${body.status}")

        return CandleMapper.fromFinnhub(symbol, interval, body)
    }

    suspend fun getLatestCachedCandles(
        symbol: String,
        exchange: Exchange,
        interval: CandleInterval,
        limit: Int = 200
    ): List<Candle> = candleDao.getLatestCandles(symbol, exchange.code, interval.name, limit)
        .map { CandleMapper.fromEntity(it) }
        .sortedBy { it.timestamp }
}
