package com.example.myapplication3.database.dao

import androidx.room.*
import com.example.myapplication3.database.entity.RecommendationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RecommendationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(recommendation: RecommendationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(recommendations: List<RecommendationEntity>)

    @Update
    suspend fun update(recommendation: RecommendationEntity)

    @Query("SELECT * FROM recommendations ORDER BY generatedAt DESC")
    fun getAllRecommendations(): Flow<List<RecommendationEntity>>

    @Query("SELECT * FROM recommendations WHERE status = 'ACTIVE' ORDER BY confidenceScore DESC")
    fun getActiveRecommendations(): Flow<List<RecommendationEntity>>

    @Query("SELECT * FROM recommendations WHERE id = :id")
    suspend fun getById(id: String): RecommendationEntity?

    @Query("SELECT * FROM recommendations WHERE symbol = :symbol ORDER BY generatedAt DESC LIMIT :limit")
    suspend fun getBySymbol(symbol: String, limit: Int = 20): List<RecommendationEntity>

    /** All recommendations that have a recorded outcome — for real performance/calibration stats. */
    @Query("SELECT * FROM recommendations WHERE outcome IS NOT NULL ORDER BY generatedAt DESC")
    suspend fun getWithOutcome(): List<RecommendationEntity>

    @Query("UPDATE recommendations SET status = :status, outcome = :outcome, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, outcome: String?, updatedAt: Long)

    @Query("DELETE FROM recommendations WHERE generatedAt < :beforeTimestamp AND status != 'ACTIVE'")
    suspend fun deleteOld(beforeTimestamp: Long)

    @Query("SELECT COUNT(*) FROM recommendations WHERE status = 'ACTIVE'")
    suspend fun getActiveCount(): Int
}
