package com.example.myapplication3.database.dao

import androidx.room.*
import com.example.myapplication3.database.entity.PortfolioPositionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PortfolioDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(position: PortfolioPositionEntity)

    @Update
    suspend fun update(position: PortfolioPositionEntity)

    @Query("SELECT * FROM portfolio_positions WHERE status = 'OPEN' ORDER BY openedAt DESC")
    fun getOpenPositions(): Flow<List<PortfolioPositionEntity>>

    @Query("SELECT * FROM portfolio_positions ORDER BY openedAt DESC")
    fun getAllPositions(): Flow<List<PortfolioPositionEntity>>

    @Query("SELECT * FROM portfolio_positions WHERE id = :id")
    suspend fun getById(id: String): PortfolioPositionEntity?

    @Query("UPDATE portfolio_positions SET currentPrice = :price, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updatePrice(id: String, price: Double, updatedAt: Long)

    @Query("UPDATE portfolio_positions SET status = :status, closedAt = :closedAt, closingPrice = :closingPrice, updatedAt = :updatedAt WHERE id = :id")
    suspend fun closePosition(id: String, status: String, closedAt: Long, closingPrice: Double, updatedAt: Long)

    @Query("SELECT * FROM portfolio_positions WHERE status = 'CLOSED' AND closedAt >= :from")
    suspend fun getClosedSince(from: Long): List<PortfolioPositionEntity>

    @Query("SELECT COUNT(*) FROM portfolio_positions WHERE status = 'OPEN'")
    suspend fun getOpenCount(): Int

    @Query("SELECT * FROM portfolio_positions WHERE status = 'OPEN' ORDER BY openedAt DESC")
    suspend fun getOpenPositionsOnce(): List<PortfolioPositionEntity>
}
