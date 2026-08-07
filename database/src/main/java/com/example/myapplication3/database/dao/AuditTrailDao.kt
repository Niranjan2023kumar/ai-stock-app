package com.example.myapplication3.database.dao

import androidx.room.*
import com.example.myapplication3.database.entity.AuditTrailEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AuditTrailDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: AuditTrailEntity)

    @Query("SELECT * FROM audit_trail ORDER BY timestamp DESC LIMIT :limit")
    fun getRecent(limit: Int = 100): Flow<List<AuditTrailEntity>>

    @Query("SELECT * FROM audit_trail WHERE recommendationId = :recommendationId")
    suspend fun getByRecommendation(recommendationId: String): AuditTrailEntity?

    @Query("DELETE FROM audit_trail WHERE timestamp < :beforeTimestamp")
    suspend fun deleteOld(beforeTimestamp: Long)
}
