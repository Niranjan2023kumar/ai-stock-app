package com.example.myapplication3.database.dao

import androidx.room.*
import com.example.myapplication3.database.entity.WatchlistEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WatchlistDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: WatchlistEntity)

    @Delete
    suspend fun delete(item: WatchlistEntity)

    @Query("DELETE FROM watchlist WHERE symbol = :symbol")
    suspend fun deleteBySymbol(symbol: String)

    @Query("SELECT * FROM watchlist ORDER BY addedAt DESC")
    fun getAll(): Flow<List<WatchlistEntity>>

    @Query("SELECT * FROM watchlist WHERE symbol = :symbol LIMIT 1")
    suspend fun getBySymbol(symbol: String): WatchlistEntity?

    @Query("SELECT COUNT(*) FROM watchlist WHERE symbol = :symbol")
    suspend fun isInWatchlist(symbol: String): Int
}
