package com.example.myapplication3.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "portfolio_positions",
    indices = [Index(value = ["symbol", "status"])]
)
data class PortfolioPositionEntity(
    @PrimaryKey val id: String,
    val symbol: String,
    val exchange: String,
    val direction: String,
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
    val status: String = "OPEN",
    val updatedAt: Long = System.currentTimeMillis()
)
