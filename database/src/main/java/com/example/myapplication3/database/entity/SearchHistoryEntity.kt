package com.example.myapplication3.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "search_history")
data class SearchHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val symbol: String,
    val name: String,
    val searchedAt: Long,
    val lastPrice: Double = 0.0,
    val exchange: String = "NSE"
)
