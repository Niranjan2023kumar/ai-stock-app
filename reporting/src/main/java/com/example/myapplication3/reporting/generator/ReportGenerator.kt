package com.example.myapplication3.reporting.generator

import com.example.myapplication3.analytics.tracker.PerformanceTracker
import com.example.myapplication3.database.dao.AuditTrailDao
import com.example.myapplication3.database.dao.BacktestResultDao
import com.example.myapplication3.database.dao.RecommendationDao
import javax.inject.Inject
import javax.inject.Singleton

data class DailyReport(
    val date: String,
    val totalRecommendations: Int,
    val actionableRecommendations: Int,
    val avgConfidence: Double,
    val avgRiskReward: Double,
    val topOpportunities: List<String>,
    val marketSummary: String
)

@Singleton
class ReportGenerator @Inject constructor(
    private val recommendationDao: RecommendationDao,
    private val auditTrailDao: AuditTrailDao,
    private val backtestResultDao: BacktestResultDao,
    private val performanceTracker: PerformanceTracker
) {

    suspend fun generateDailyReport(dateStr: String): DailyReport {
        val stats = performanceTracker.getCalibration()
        return DailyReport(
            date = dateStr,
            totalRecommendations = stats.totalRecommendations,
            actionableRecommendations = stats.wins + stats.losses,
            avgConfidence = stats.averageConfidenceOnWins,
            avgRiskReward = 0.0,
            topOpportunities = emptyList(),
            marketSummary = "Win rate: ${String.format("%.1f", stats.winRate * 100)}% across ${stats.totalRecommendations} trades"
        )
    }
}
