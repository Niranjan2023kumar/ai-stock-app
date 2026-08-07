package com.example.myapplication3.database.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "candles",
    primaryKeys = ["symbol", "exchange", "interval", "timestamp"],
    indices = [
        Index(value = ["symbol", "exchange", "interval", "timestamp"]),
        Index(value = ["symbol", "timestamp"])
    ]
)
data class CandleEntity(
    val symbol: String,
    val exchange: String,
    val interval: String,
    val timestamp: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long,
    val deliveryVolume: Long = 0L,
    val vwap: Double = 0.0,
    val isComplete: Boolean = true,
    val insertedAt: Long = System.currentTimeMillis()
)
