package com.example.myapplication3.database.dao

import androidx.room.*
import com.example.myapplication3.database.entity.SearchHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SearchHistoryDao {

    @Query("SELECT * FROM search_history ORDER BY searchedAt DESC LIMIT 20")
    fun getRecentSearches(): Flow<List<SearchHistoryEntity>>

    @Query("SELECT * FROM search_history ORDER BY searchedAt DESC LIMIT 20")
    suspend fun getRecentSearchesOnce(): List<SearchHistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: SearchHistoryEntity)

    @Query("DELETE FROM search_history WHERE symbol = :symbol")
    suspend fun deleteBySymbol(symbol: String)

    @Query("DELETE FROM search_history")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM search_history")
    suspend fun count(): Int
}
