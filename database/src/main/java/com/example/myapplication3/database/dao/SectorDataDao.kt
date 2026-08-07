package com.example.myapplication3.database.dao

import androidx.room.*
import com.example.myapplication3.database.entity.SectorDataEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SectorDataDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(data: List<SectorDataEntity>)

    @Query("SELECT * FROM sector_data WHERE timestamp = (SELECT MAX(timestamp) FROM sector_data)")
    fun getLatest(): Flow<List<SectorDataEntity>>

    @Query("SELECT * FROM sector_data WHERE sector = :sector ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getHistory(sector: String, limit: Int = 50): List<SectorDataEntity>

    @Query("DELETE FROM sector_data WHERE timestamp < :beforeTimestamp")
    suspend fun deleteOld(beforeTimestamp: Long)
}
