package com.example.myapplication3.news.analyzer

import com.example.myapplication3.core.domain.model.NewsCategory
import com.example.myapplication3.core.domain.model.NewsSentiment
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SentimentAnalyzer @Inject constructor() {

    private val bullishKeywords = setOf(
        "profit", "growth", "record", "beat", "surge", "rally", "buy", "upgrade",
        "positive", "strong", "expansion", "acquisition", "dividend", "outperform",
        "revenue increase", "order win", "capacity expansion", "new contract",
        "operational improvement", "margin expansion", "deal", "partnership"
    )

    private val bearishKeywords = setOf(
        "loss", "decline", "fall", "drop", "miss", "downgrade", "sell", "weak",
        "negative", "slowdown", "debt", "default", "probe", "investigation",
        "fraud", "regulatory", "penalty", "fine", "recall", "lawsuit",
        "plant shutdown", "strike", "inventory build", "margin compression"
    )

    private val highImpactKeywords = setOf(
        "earnings", "results", "dividend", "bonus", "split", "merger", "acquisition",
        "rbi", "sebi", "nifty", "sensex", "budget", "policy rate", "inflation",
        "fii", "dii", "promoter", "buyback", "delisting", "ipo", "qip"
    )

    fun analyze(headline: String, summary: String): SentimentScore {
        val text = "$headline $summary".lowercase()
        var bullishCount = 0
        var bearishCount = 0

        bullishKeywords.forEach { if (text.contains(it)) bullishCount++ }
        bearishKeywords.forEach { if (text.contains(it)) bearishCount++ }

        val total = bullishCount + bearishCount
        val rawScore = if (total == 0) 0.0 else (bullishCount - bearishCount).toDouble() / total

        val sentiment = when {
            rawScore > 0.5 -> NewsSentiment.STRONGLY_BULLISH
            rawScore > 0.1 -> NewsSentiment.BULLISH
            rawScore < -0.5 -> NewsSentiment.STRONGLY_BEARISH
            rawScore < -0.1 -> NewsSentiment.BEARISH
            else -> NewsSentiment.NEUTRAL
        }

        val impactScore = calculateImpact(text, bullishCount + bearishCount)
        val confidence = if (total == 0) 0.3 else minOf(0.5 + total * 0.1, 0.9)

        return SentimentScore(sentiment, rawScore, impactScore, confidence)
    }

    fun classifyCategory(headline: String, source: String): NewsCategory {
        val text = headline.lowercase()
        return when {
            text.contains("q1") || text.contains("q2") || text.contains("q3") || text.contains("q4")
                    || text.contains("quarterly") || text.contains("results") || text.contains("earnings") -> NewsCategory.RESULTS
            text.contains("dividend") || text.contains("bonus") || text.contains("split") || text.contains("buyback") -> NewsCategory.CORPORATE_ACTION
            text.contains("rbi") || text.contains("repo rate") || text.contains("monetary policy") -> NewsCategory.RBI_ANNOUNCEMENT
            text.contains("merger") || text.contains("acquisition") || text.contains("takeover") -> NewsCategory.MERGER_ACQUISITION
            text.contains("sebi") || text.contains("exchange filing") || text.contains("bse filing") || text.contains("nse filing") -> NewsCategory.EXCHANGE_FILING
            text.contains("government") || text.contains("budget") || text.contains("ministry") || text.contains("policy") -> NewsCategory.GOVERNMENT_POLICY
            text.contains("global") || text.contains("dow") || text.contains("s&p") || text.contains("fed") || text.contains("nasdaq") -> NewsCategory.GLOBAL_MARKET
            text.contains("sector") || text.contains("industry") -> NewsCategory.SECTOR_NEWS
            else -> NewsCategory.GENERAL
        }
    }

    private fun calculateImpact(text: String, keywordCount: Int): Double {
        val baseImpact = minOf(keywordCount * 0.15, 0.6)
        val highImpact = highImpactKeywords.count { text.contains(it) } * 0.1
        return minOf(baseImpact + highImpact, 1.0)
    }
}

data class SentimentScore(
    val sentiment: NewsSentiment,
    val score: Double,
    val impactScore: Double,
    val confidence: Double
)
