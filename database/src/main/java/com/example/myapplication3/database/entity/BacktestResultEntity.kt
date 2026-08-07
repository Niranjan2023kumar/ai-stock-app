package com.example.myapplication3.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "backtest_results")
data class BacktestResultEntity(
    @PrimaryKey val id: String,
    val strategyName: String,
    val fromDate: Long,
    val toDate: Long,
    val period: String,
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
    val monteCarloJson: String? = null,
    val walkForwardJson: String = "[]"
)
