package com.example.myapplication3.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.myapplication3.database.entity.TrackedTradeEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackedTradeDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(trade: TrackedTradeEntity)

    /** Open positions for a mode (real vs practice), newest first — drives tracking UI + alerts. */
    @Query("SELECT * FROM tracked_trades WHERE status = 'OPEN' AND isPractice = :practice ORDER BY openedAt DESC")
    fun observeOpen(practice: Boolean): Flow<List<TrackedTradeEntity>>

    @Query("SELECT * FROM tracked_trades WHERE status = 'OPEN' AND isPractice = :practice")
    suspend fun getOpenOnce(practice: Boolean): List<TrackedTradeEntity>

    @Query("SELECT * FROM tracked_trades WHERE status = 'OPEN'")
    suspend fun getAllOpenOnce(): List<TrackedTradeEntity>

    @Query("SELECT * FROM tracked_trades WHERE id = :id")
    suspend fun getById(id: String): TrackedTradeEntity?

    @Query("UPDATE tracked_trades SET status = 'CLOSED', exitPrice = :exitPrice, closedAt = :closedAt, realizedPnl = :pnl WHERE id = :id")
    suspend fun close(id: String, exitPrice: Double, closedAt: Long, pnl: Double)

    /** Today's REALIZED P/L for a mode — the number the top P/L bar shows (B7b). */
    @Query("SELECT COALESCE(SUM(realizedPnl), 0.0) FROM tracked_trades WHERE status = 'CLOSED' AND isPractice = :practice AND closedAt >= :since")
    fun observeRealizedSince(since: Long, practice: Boolean): Flow<Double>

    @Query("SELECT COALESCE(SUM(realizedPnl), 0.0) FROM tracked_trades WHERE status = 'CLOSED' AND isPractice = :practice AND closedAt >= :since")
    suspend fun realizedSinceOnce(since: Long, practice: Boolean): Double

    /** Closed trades since [since] — for the weekly report card (E1a). */
    @Query("SELECT * FROM tracked_trades WHERE status = 'CLOSED' AND isPractice = :practice AND closedAt >= :since ORDER BY closedAt DESC")
    fun observeClosedSince(since: Long, practice: Boolean): Flow<List<TrackedTradeEntity>>

    /** One-shot version of the above — for Workers that cannot collect a Flow. */
    @Query("SELECT * FROM tracked_trades WHERE status = 'CLOSED' AND isPractice = :practice AND closedAt >= :since ORDER BY closedAt DESC")
    suspend fun getClosedSinceOnce(since: Long, practice: Boolean): List<TrackedTradeEntity>

    @Query("DELETE FROM tracked_trades WHERE id = :id")
    suspend fun delete(id: String)
}
