package com.example.myapplication3.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "recommendations",
    indices = [Index(value = ["symbol", "generatedAt"])]
)
data class RecommendationEntity(
    @PrimaryKey val id: String,
    val symbol: String,
    val exchange: String,
    val generatedAt: Long,
    val direction: String,
    val currentPrice: Double,
    val entryPrice: Double,
    val stopLoss: Double,
    val target1: Double,
    val target2: Double,
    val riskRewardRatio: Double,
    val confidenceScore: Double,
    val riskScore: Double,
    val thesis: String,
    val triggeredSignalsJson: String,
    val contributingNewsJson: String,
    val riskFactorsJson: String,
    val indicatorValuesJson: String,
    val status: String,
    val outcome: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
)
