package com.example.myapplication3.core.domain.model

data class Recommendation(
    val id: String,
    val symbol: String,
    val exchange: Exchange,
    val generatedAt: Long,
    val direction: TradeDirection,
    val currentPrice: Double,
    val entryPrice: Double,
    val stopLoss: Double,
    val target1: Double,
    val target2: Double,
    val riskRewardRatio: Double,
    val confidenceScore: Double,
    val riskScore: Double,
    val thesis: String,
    val triggeredSignals: List<Signal>,
    val contributingNews: List<String>,
    val riskFactors: List<String>,
    val indicatorValues: Map<String, Double>,
    val status: RecommendationStatus = RecommendationStatus.ACTIVE,
    val outcome: RecommendationOutcome? = null
) {
    val riskAmount: Double get() = kotlin.math.abs(entryPrice - stopLoss)
    val rewardAmount1: Double get() = kotlin.math.abs(target1 - entryPrice)
    val rewardAmount2: Double get() = kotlin.math.abs(target2 - entryPrice)
    val isHighConfidence: Boolean get() = confidenceScore >= 0.7
    val isLowRisk: Boolean get() = riskScore <= 0.4
    val isActionable: Boolean get() = isHighConfidence && isLowRisk && riskRewardRatio >= 2.0
}

enum class TradeDirection { LONG, SHORT }

enum class RecommendationStatus { ACTIVE, TRIGGERED, STOPPED_OUT, TARGET1_HIT, TARGET2_HIT, EXPIRED, CANCELLED }

enum class RecommendationOutcome { WIN, LOSS, BREAKEVEN }
