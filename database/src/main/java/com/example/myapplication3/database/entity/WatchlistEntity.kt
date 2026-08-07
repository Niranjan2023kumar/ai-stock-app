package com.example.myapplication3.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "watchlist")
data class WatchlistEntity(
    @PrimaryKey val symbol: String,
    val exchange: String,
    val name: String,
    val sector: String,
    val addedAt: Long = System.currentTimeMillis(),
    val notes: String = ""
)
