package com.example.myapplication3.database.entity

import androidx.room.Entity

@Entity(
    tableName = "sector_data",
    primaryKeys = ["sector", "timestamp"]
)
data class SectorDataEntity(
    val sector: String,
    val advances: Int,
    val declines: Int,
    val unchanged: Int,
    val strengthScore: Double,
    val momentumScore: Double,
    val timestamp: Long
)
