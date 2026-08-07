package com.example.myapplication3.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "news",
    indices = [Index(value = ["publishedAt"]), Index(value = ["relatedSymbolsJson"])]
)
data class NewsEntity(
    @PrimaryKey val id: String,
    val headline: String,
    val summary: String,
    val source: String,
    val url: String,
    val publishedAt: Long,
    val relatedSymbolsJson: String,
    val category: String,
    val sentiment: String,
    val sentimentScore: Double,
    val impactScore: Double,
    val confidenceScore: Double,
    val aiSummary: String = "",
    val keyPointsJson: String = "[]",
    val insertedAt: Long = System.currentTimeMillis()
)
