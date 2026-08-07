package com.example.myapplication3.ai.local

import com.example.myapplication3.ai.engine.AiAnalysisResult
import com.example.myapplication3.core.domain.model.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalQuantEngine @Inject constructor() {

    fun analyze(
        symbol: String,
        signals: List<Signal>,
        newsItems: List<NewsItem>,
        indicators: Map<String, Double>,
        marketData: MarketData
    ): AiAnalysisResult {
        val thesis = buildThesis(symbol, signals, marketData)
        val sentimentSummary = buildSentimentSummary(newsItems)
        val risks = identifyRisks(signals, indicators, marketData)
        val confidenceAdj = calculateConfidenceAdjustment(signals, newsItems, indicators)

        return AiAnalysisResult(
            thesis = thesis,
            sentimentSummary = sentimentSummary,
            keyRisks = risks,
            confidenceAdjustment = confidenceAdj,
            explanation = buildExplanation(signals, indicators),
            modelUsed = "Local Quant Engine"
        )
    }

    private fun buildThesis(symbol: String, signals: List<Signal>, data: MarketData): String {
        val bullishSignals = signals.filter { it.type.isBullish == true }
        val bearishSignals = signals.filter { it.type.isBullish == false }
        val direction = if (bullishSignals.size > bearishSignals.size) "bullish" else "bearish"
        val topSignal = signals.maxByOrNull { it.strength }?.type?.displayName ?: "Multiple signals"
        return "$symbol showing $direction momentum. Primary trigger: $topSignal. " +
                "Volume ${String.format("%.1f", data.relativeVolume)}x average. " +
                "${bullishSignals.size} bullish and ${bearishSignals.size} bearish signals detected."
    }

    private fun buildSentimentSummary(newsItems: List<NewsItem>): String {
        if (newsItems.isEmpty()) return "No recent news available"
        val avgScore = newsItems.map { it.sentimentScore }.average()
        val sentiment = when {
            avgScore > 0.3 -> "Positive"
            avgScore < -0.3 -> "Negative"
            else -> "Neutral"
        }
        return "$sentiment news sentiment. ${newsItems.size} articles analyzed."
    }

    private fun identifyRisks(
        signals: List<Signal>,
        indicators: Map<String, Double>,
        data: MarketData
    ): List<String> {
        val risks = mutableListOf<String>()
        val rsi = indicators["rsi"]
        if (rsi != null && rsi > 70) risks.add("RSI overbought at ${String.format("%.1f", rsi)}")
        if (rsi != null && rsi < 30) risks.add("RSI oversold at ${String.format("%.1f", rsi)}")
        if (data.spreadPercent > 0.3) risks.add("Wide bid-ask spread: ${String.format("%.2f", data.spreadPercent)}%")
        if (data.relativeVolume > 5.0) risks.add("Extreme volume spike, potential volatility")
        if (signals.any { it.type == SignalType.GAP_UP || it.type == SignalType.GAP_DOWN })
            risks.add("Gap-based setup, potential for gap fill reversal")
        return risks.ifEmpty { listOf("Standard intraday risk applies") }
    }

    private fun calculateConfidenceAdjustment(
        signals: List<Signal>,
        newsItems: List<NewsItem>,
        indicators: Map<String, Double>
    ): Double {
        var adj = 0.0
        if (newsItems.any { it.sentimentScore > 0.5 }) adj += 0.05
        if (newsItems.any { it.sentimentScore < -0.5 }) adj -= 0.05
        if (signals.size >= 3) adj += 0.03
        val rsi = indicators["rsi"] ?: 50.0
        if (rsi in 40.0..60.0) adj += 0.02
        return adj.coerceIn(-0.2, 0.2)
    }

    private fun buildExplanation(signals: List<Signal>, indicators: Map<String, Double>): String {
        val sb = StringBuilder("Technical analysis based recommendation. ")
        signals.take(3).forEach { sb.append("${it.type.displayName}: ${it.description}. ") }
        indicators["atr"]?.let { sb.append("ATR: ${String.format("%.2f", it)}. ") }
        return sb.toString()
    }
}
