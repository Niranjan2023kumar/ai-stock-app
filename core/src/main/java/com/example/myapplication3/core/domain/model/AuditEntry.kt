package com.example.myapplication3.core.domain.model

data class AuditEntry(
    val id: String,
    val recommendationId: String,
    val timestamp: Long,
    val symbol: String,
    val inputs: AuditInputs,
    val scores: AuditScores,
    val newsUsed: List<String>,
    val indicatorsUsed: Map<String, Double>,
    val signalsUsed: List<SignalType>,
    val finalRecommendation: String,
    val aiModelUsed: String
)

data class AuditInputs(
    val candlesUsed: Int,
    val dataFromProvider: String,
    val dataQualityScore: Double,
    val marketRegime: MarketRegime,
    val sectorStrength: Double
)

data class AuditScores(
    val technicalScore: Double,
    val newsScore: Double,
    val riskScore: Double,
    val confidenceScore: Double,
    val finalScore: Double
)
