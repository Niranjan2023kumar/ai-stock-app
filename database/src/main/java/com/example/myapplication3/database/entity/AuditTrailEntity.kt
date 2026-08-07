package com.example.myapplication3.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "audit_trail",
    indices = [Index(value = ["recommendationId"]), Index(value = ["timestamp"])]
)
data class AuditTrailEntity(
    @PrimaryKey val id: String,
    val recommendationId: String,
    val timestamp: Long,
    val symbol: String,
    val inputsJson: String,
    val scoresJson: String,
    val newsUsedJson: String,
    val indicatorsUsedJson: String,
    val signalsUsedJson: String,
    val finalRecommendation: String,
    val aiModelUsed: String
)
