package com.example.myapplication3.core.domain.model

data class PortfolioPosition(
    val id: String,
    val symbol: String,
    val exchange: Exchange,
    val direction: TradeDirection,
    val quantity: Int,
    val averageEntryPrice: Double,
    val currentPrice: Double,
    val stopLoss: Double,
    val target1: Double,
    val target2: Double,
    val recommendationId: String,
    val openedAt: Long,
    val closedAt: Long? = null,
    val closingPrice: Double? = null,
    val status: PositionStatus = PositionStatus.OPEN
) {
    val investedAmount: Double get() = quantity * averageEntryPrice
    val currentValue: Double get() = quantity * currentPrice
    val unrealizedPnl: Double get() = if (direction == TradeDirection.LONG)
        (currentPrice - averageEntryPrice) * quantity
    else (averageEntryPrice - currentPrice) * quantity
    val unrealizedPnlPercent: Double get() = if (investedAmount > 0) (unrealizedPnl / investedAmount) * 100 else 0.0
    val realizedPnl: Double get() = if (closingPrice != null) {
        if (direction == TradeDirection.LONG) (closingPrice - averageEntryPrice) * quantity
        else (averageEntryPrice - closingPrice) * quantity
    } else 0.0
}

enum class PositionStatus { OPEN, CLOSED, PARTIALLY_CLOSED }

data class PortfolioSummary(
    val totalInvested: Double,
    val currentValue: Double,
    val unrealizedPnl: Double,
    val realizedPnlToday: Double,
    val realizedPnlWeek: Double,
    val openPositions: Int,
    val winRate: Double,
    val dailyLoss: Double,
    val weeklyLoss: Double,
    val maxDailyLossLimit: Double,
    val maxWeeklyLossLimit: Double
) {
    val totalPnl: Double get() = unrealizedPnl + realizedPnlToday
    val isDailyLimitBreached: Boolean get() = dailyLoss >= maxDailyLossLimit
    val isWeeklyLimitBreached: Boolean get() = weeklyLoss >= maxWeeklyLossLimit
    val availableRisk: Double get() = maxOf(0.0, maxDailyLossLimit - dailyLoss)
}
