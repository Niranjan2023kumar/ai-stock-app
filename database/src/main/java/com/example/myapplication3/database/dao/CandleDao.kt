package com.example.myapplication3.database.dao

import androidx.room.*
import com.example.myapplication3.database.entity.CandleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CandleDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCandles(candles: List<CandleEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCandle(candle: CandleEntity)

    @Query("SELECT * FROM candles WHERE symbol = :symbol AND exchange = :exchange AND interval = :interval ORDER BY timestamp ASC")
    fun getCandles(symbol: String, exchange: String, interval: String): Flow<List<CandleEntity>>

    @Query("SELECT * FROM candles WHERE symbol = :symbol AND exchange = :exchange AND interval = :interval ORDER BY timestamp ASC")
    suspend fun getCandlesOnce(symbol: String, exchange: String, interval: String): List<CandleEntity>

    @Query("SELECT * FROM candles WHERE symbol = :symbol AND exchange = :exchange AND interval = :interval AND timestamp >= :fromTimestamp ORDER BY timestamp ASC")
    suspend fun getCandlesFrom(symbol: String, exchange: String, interval: String, fromTimestamp: Long): List<CandleEntity>

    @Query("SELECT * FROM candles WHERE symbol = :symbol AND exchange = :exchange AND interval = :interval ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getLatestCandles(symbol: String, exchange: String, interval: String, limit: Int): List<CandleEntity>

    @Query("SELECT MAX(timestamp) FROM candles WHERE symbol = :symbol AND exchange = :exchange AND interval = :interval")
    suspend fun getLatestTimestamp(symbol: String, exchange: String, interval: String): Long?

    @Query("DELETE FROM candles WHERE insertedAt < :beforeTimestamp")
    suspend fun deleteOldCandles(beforeTimestamp: Long)

    @Query("SELECT COUNT(*) FROM candles WHERE symbol = :symbol AND exchange = :exchange AND interval = :interval")
    suspend fun getCandleCount(symbol: String, exchange: String, interval: String): Int
}
