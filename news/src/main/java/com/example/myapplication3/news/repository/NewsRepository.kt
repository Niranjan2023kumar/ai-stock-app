package com.example.myapplication3.news.repository

import com.example.myapplication3.core.common.AppException
import com.example.myapplication3.core.common.Result
import com.example.myapplication3.core.domain.model.NewsItem
import com.example.myapplication3.database.dao.NewsDao
import com.example.myapplication3.database.entity.NewsEntity
import com.example.myapplication3.network.api.finnhub.FinnhubApiService
import com.example.myapplication3.network.health.DataProvider
import com.example.myapplication3.network.health.ProviderHealthMonitor
import com.example.myapplication3.news.analyzer.SentimentAnalyzer
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class NewsRepository @Inject constructor(
    private val finnhubApi: FinnhubApiService,
    private val newsDao: NewsDao,
    private val healthMonitor: ProviderHealthMonitor,
    private val sentimentAnalyzer: SentimentAnalyzer,
    private val gson: Gson,
    @Named("finnhubApiKey") private val finnhubKey: String
) {
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    fun getLatestNews(): Flow<List<NewsItem>> =
        newsDao.getLatestNews(50).map { entities -> entities.map { it.toDomain() } }

    suspend fun refreshMarketNews(): Result<List<NewsItem>> {
        return try {
            val response = healthMonitor.getCircuitBreaker(DataProvider.FINNHUB).execute {
                finnhubApi.getMarketNews(token = finnhubKey)
            }

            if (!response.isSuccessful) {
                return Result.Error(AppException.ApiException("Finnhub news error: ${response.code()}", response.code(), "Finnhub"))
            }

            val items = response.body() ?: return Result.Error(AppException.InsufficientDataException("No news data"))
            val newsItems = items.map { raw ->
                val sentimentResult = sentimentAnalyzer.analyze(raw.headline, raw.summary)
                val category = sentimentAnalyzer.classifyCategory(raw.headline, raw.source)
                NewsItem(
                    id = raw.id.toString(),
                    headline = raw.headline,
                    summary = raw.summary,
                    source = raw.source,
                    url = raw.url,
                    publishedAt = raw.datetime * 1000L,
                    // Keep the real related tickers Finnhub provides instead of discarding them.
                    relatedSymbols = raw.related.split(',', ' ', ';')
                        .map { it.trim() }.filter { it.isNotEmpty() },
                    category = category,
                    sentiment = sentimentResult.sentiment,
                    sentimentScore = sentimentResult.score,
                    impactScore = sentimentResult.impactScore,
                    confidenceScore = sentimentResult.confidence
                )
            }

            newsDao.insertAll(newsItems.map { it.toEntity() })
            healthMonitor.recordSuccess(DataProvider.FINNHUB, 0)
            Result.Success(newsItems)
        } catch (e: Exception) {
            healthMonitor.recordFailure(DataProvider.FINNHUB)
            Result.Error(AppException.NetworkException(e.message ?: "News fetch failed", cause = e))
        }
    }

    suspend fun getSymbolNews(symbol: String): Result<List<NewsItem>> {
        return try {
            val to = dateFormat.format(Date())
            val from = dateFormat.format(Date(System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L))
            val finnhubSymbol = "${symbol}.NS"

            val response = healthMonitor.getCircuitBreaker(DataProvider.FINNHUB).execute {
                finnhubApi.getCompanyNews(finnhubSymbol, from, to, finnhubKey)
            }

            if (!response.isSuccessful) return Result.Success(emptyList())

            val items = response.body() ?: return Result.Success(emptyList())
            val newsItems = items.map { raw ->
                val sentimentResult = sentimentAnalyzer.analyze(raw.headline, raw.summary)
                val category = sentimentAnalyzer.classifyCategory(raw.headline, raw.source)
                NewsItem(
                    id = raw.id.toString(),
                    headline = raw.headline,
                    summary = raw.summary,
                    source = raw.source,
                    url = raw.url,
                    publishedAt = raw.datetime * 1000L,
                    relatedSymbols = listOf(symbol),
                    category = category,
                    sentiment = sentimentResult.sentiment,
                    sentimentScore = sentimentResult.score,
                    impactScore = sentimentResult.impactScore,
                    confidenceScore = sentimentResult.confidence
                )
            }

            newsDao.insertAll(newsItems.map { it.toEntity() })
            Result.Success(newsItems)
        } catch (e: Exception) {
            Result.Error(AppException.NetworkException(e.message ?: "Symbol news fetch failed", cause = e))
        }
    }

    private fun NewsItem.toEntity() = NewsEntity(
        id = id,
        headline = headline,
        summary = summary,
        source = source,
        url = url,
        publishedAt = publishedAt,
        relatedSymbolsJson = gson.toJson(relatedSymbols),
        category = category.name,
        sentiment = sentiment.name,
        sentimentScore = sentimentScore,
        impactScore = impactScore,
        confidenceScore = confidenceScore,
        aiSummary = aiSummary,
        keyPointsJson = gson.toJson(keyPoints)
    )

    private fun NewsEntity.toDomain(): NewsItem {
        val listType = object : TypeToken<List<String>>() {}.type
        return NewsItem(
            id = id,
            headline = headline,
            summary = summary,
            source = source,
            url = url,
            publishedAt = publishedAt,
            relatedSymbols = try { gson.fromJson(relatedSymbolsJson, listType) } catch (e: Exception) { emptyList() },
            category = try { com.example.myapplication3.core.domain.model.NewsCategory.valueOf(category) } catch (e: Exception) { com.example.myapplication3.core.domain.model.NewsCategory.GENERAL },
            sentiment = try { com.example.myapplication3.core.domain.model.NewsSentiment.valueOf(sentiment) } catch (e: Exception) { com.example.myapplication3.core.domain.model.NewsSentiment.NEUTRAL },
            sentimentScore = sentimentScore,
            impactScore = impactScore,
            confidenceScore = confidenceScore,
            aiSummary = aiSummary,
            keyPoints = try { gson.fromJson(keyPointsJson, listType) } catch (e: Exception) { emptyList() }
        )
    }
}
