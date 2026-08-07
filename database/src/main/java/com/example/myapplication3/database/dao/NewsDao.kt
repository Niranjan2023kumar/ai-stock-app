package com.example.myapplication3.database.dao

import androidx.room.*
import com.example.myapplication3.database.entity.NewsEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NewsDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(news: List<NewsEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(news: NewsEntity)

    @Query("SELECT * FROM news ORDER BY publishedAt DESC LIMIT :limit")
    fun getLatestNews(limit: Int = 50): Flow<List<NewsEntity>>

    @Query("SELECT * FROM news WHERE relatedSymbolsJson LIKE '%' || :symbol || '%' ORDER BY publishedAt DESC LIMIT :limit")
    suspend fun getNewsBySymbol(symbol: String, limit: Int = 20): List<NewsEntity>

    @Query("SELECT * FROM news WHERE publishedAt >= :from ORDER BY publishedAt DESC")
    suspend fun getNewsFrom(from: Long): List<NewsEntity>

    @Query("SELECT * FROM news WHERE sentiment IN ('BULLISH', 'STRONGLY_BULLISH') ORDER BY publishedAt DESC LIMIT :limit")
    suspend fun getBullishNews(limit: Int = 20): List<NewsEntity>

    @Query("DELETE FROM news WHERE insertedAt < :beforeTimestamp")
    suspend fun deleteOld(beforeTimestamp: Long)

    @Query("SELECT COUNT(*) FROM news WHERE publishedAt >= :from")
    suspend fun getNewsCountSince(from: Long): Int
}
