package com.example.myapplication3.core.domain.model

data class NewsItem(
    val id: String,
    val headline: String,
    val summary: String,
    val source: String,
    val url: String,
    val publishedAt: Long,
    val relatedSymbols: List<String>,
    val category: NewsCategory,
    val sentiment: NewsSentiment,
    val sentimentScore: Double,
    val impactScore: Double,
    val confidenceScore: Double,
    val aiSummary: String = "",
    val keyPoints: List<String> = emptyList()
)

enum class NewsCategory(val displayName: String) {
    EARNINGS("Earnings"),
    RESULTS("Results"),
    CORPORATE_ACTION("Corporate Action"),
    EXCHANGE_FILING("Exchange Filing"),
    RBI_ANNOUNCEMENT("RBI Announcement"),
    GOVERNMENT_POLICY("Government Policy"),
    GLOBAL_MARKET("Global Market"),
    SECTOR_NEWS("Sector News"),
    MERGER_ACQUISITION("Merger & Acquisition"),
    INSIDER_TRADING("Insider Trading"),
    ANALYST_REPORT("Analyst Report"),
    GENERAL("General")
}

enum class NewsSentiment(val displayName: String, val score: Double) {
    STRONGLY_BULLISH("Strongly Bullish", 1.0),
    BULLISH("Bullish", 0.5),
    NEUTRAL("Neutral", 0.0),
    BEARISH("Bearish", -0.5),
    STRONGLY_BEARISH("Strongly Bearish", -1.0)
}
