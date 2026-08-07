package com.example.myapplication3.core.domain.model

data class RiskMetrics(
    val symbol: String,
    val overallRiskScore: Double,
    val liquidityRisk: LiquidityRisk,
    val volatilityRisk: VolatilityRisk,
    val manipulationRisk: ManipulationRisk,
    val dataQualityScore: Double,
    val flags: List<RiskFlag>,
    val isTradeAllowed: Boolean,
    val rejectionReason: String? = null
)

data class LiquidityRisk(
    val score: Double,
    val averageVolume: Long,
    val spreadPercent: Double,
    val isLowLiquidity: Boolean,
    val isWidenedSpread: Boolean
)

data class VolatilityRisk(
    val score: Double,
    val atr: Double,
    val atrPercent: Double,
    val isHighVolatility: Boolean,
    val historicalVolatility: Double
)

data class ManipulationRisk(
    val score: Double,
    val isPumpDumpSuspected: Boolean,
    val isAbnormalVolumeSpike: Boolean,
    val isLowFreeFloat: Boolean,
    val isSuspiciousGap: Boolean,
    val abnormalityScore: Double
)

enum class RiskFlag(val description: String, val severity: RiskSeverity) {
    LOW_LIQUIDITY("Insufficient trading volume", RiskSeverity.HIGH),
    WIDE_SPREAD("Bid-ask spread too wide", RiskSeverity.HIGH),
    CIRCUIT_RESTRICTED("Stock under circuit restriction", RiskSeverity.CRITICAL),
    SUSPENDED("Stock suspended", RiskSeverity.CRITICAL),
    PUMP_DUMP_SUSPECTED("Potential pump and dump activity", RiskSeverity.HIGH),
    ABNORMAL_VOLUME("Volume spike exceeds normal range", RiskSeverity.MEDIUM),
    LOW_FREE_FLOAT("Low free float stock", RiskSeverity.MEDIUM),
    SUSPICIOUS_GAP("Suspicious price gap detected", RiskSeverity.MEDIUM),
    DATA_QUALITY_ISSUE("Incomplete or unreliable data", RiskSeverity.HIGH),
    HIGH_VOLATILITY("Extreme volatility detected", RiskSeverity.MEDIUM),
    NEWS_DRIVEN_MOVE("Sharp move driven by news", RiskSeverity.LOW)
}

enum class RiskSeverity { LOW, MEDIUM, HIGH, CRITICAL }
