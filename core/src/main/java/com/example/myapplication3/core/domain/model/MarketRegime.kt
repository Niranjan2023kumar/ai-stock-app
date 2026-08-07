package com.example.myapplication3.core.domain.model

data class MarketRegimeState(
    val regime: MarketRegime,
    val volatilityState: VolatilityState,
    val trendStrength: Double,
    val breadthScore: Double,
    val timestamp: Long,
    val description: String
)

enum class MarketRegime(val displayName: String) {
    STRONG_BULL("Strong Bull"),
    BULL("Bull"),
    SIDEWAYS("Sideways"),
    BEAR("Bear"),
    STRONG_BEAR("Strong Bear")
}

enum class VolatilityState(val displayName: String) {
    VERY_LOW("Very Low"),
    LOW("Low"),
    NORMAL("Normal"),
    HIGH("High"),
    EXTREME("Extreme")
}
