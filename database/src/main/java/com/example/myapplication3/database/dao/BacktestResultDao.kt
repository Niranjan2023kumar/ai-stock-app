package com.example.myapplication3.database.dao

import androidx.room.*
import com.example.myapplication3.database.entity.BacktestResultEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BacktestResultDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(result: BacktestResultEntity)

    @Query("SELECT * FROM backtest_results ORDER BY createdAt DESC")
    fun getAll(): Flow<List<BacktestResultEntity>>

    @Query("SELECT * FROM backtest_results WHERE id = :id")
    suspend fun getById(id: String): BacktestResultEntity?

    @Query("SELECT * FROM backtest_results WHERE strategyName = :strategyName ORDER BY createdAt DESC LIMIT 1")
    suspend fun getLatestForStrategy(strategyName: String): BacktestResultEntity?

    @Delete
    suspend fun delete(result: BacktestResultEntity)
}
