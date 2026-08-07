package com.example.myapplication3.core.domain.model

data class BacktestResult(
    val id: String,
    val strategyName: String,
    val fromDate: Long,
    val toDate: Long,
    val period: BacktestPeriod,
    val totalTrades: Int,
    val winningTrades: Int,
    val losingTrades: Int,
    val winRate: Double,
    val profitFactor: Double,
    val sharpeRatio: Double,
    val sortinoRatio: Double,
    val maxDrawdown: Double,
    val cagr: Double,
    val expectancy: Double,
    val totalReturn: Double,
    val averageWin: Double,
    val averageLoss: Double,
    val largestWin: Double,
    val largestLoss: Double,
    val averageHoldingPeriodMinutes: Int,
    val createdAt: Long,
    val monteCarloResults: MonteCarloResult? = null,
    val walkForwardResults: List<WalkForwardResult> = emptyList()
)

enum class BacktestPeriod(val label: String, val years: Int) {
    ONE_YEAR("1 Year", 1),
    THREE_YEARS("3 Years", 3),
    FIVE_YEARS("5 Years", 5),
    TEN_YEARS("10 Years", 10)
}

data class MonteCarloResult(
    val simulations: Int,
    val medianReturn: Double,
    val percentile5Return: Double,
    val percentile95Return: Double,
    val medianMaxDrawdown: Double,
    val percentile5MaxDrawdown: Double,
    val percentile95MaxDrawdown: Double,
    val probabilityOfProfit: Double,
    val probabilityOfRuin: Double
)

data class WalkForwardResult(
    val trainFromDate: Long,
    val trainToDate: Long,
    val validationFromDate: Long,
    val validationToDate: Long,
    val forwardFromDate: Long,
    val forwardToDate: Long,
    val trainReturn: Double,
    val validationReturn: Double,
    val forwardReturn: Double,
    val forwardWinRate: Double
)
