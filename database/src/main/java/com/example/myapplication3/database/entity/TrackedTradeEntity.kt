package com.example.myapplication3.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A trade the user actually placed in Groww (confirmed via "Order placed?" — B0.2b)
 * and is now tracking. Feeds the top P/L bar, the daily/weekly loss limits, and the
 * background stop-loss/target alerts (F2). isPractice separates paper trades (F5).
 */
@Entity(tableName = "tracked_trades")
data class TrackedTradeEntity(
    @PrimaryKey val id: String,
    val symbol: String,
    val name: String,
    val orderType: String,       // INTRADAY | DELIVERY
    val action: String,          // BUY (short-sell tracking added later)
    val isPractice: Boolean,     // false = real money, true = practice (F5)
    val entryPrice: Double,      // the user's ACTUAL fill price (B0.2b decision 3)
    val quantity: Int,
    val stopLoss: Double,
    val target1: Double,
    val target2: Double,
    val target3: Double,
    val openedAt: Long,
    val status: String,          // OPEN | CLOSED
    val exitPrice: Double? = null,
    val closedAt: Long? = null,
    val realizedPnl: Double? = null,
    // P0 #12: the signal engine's 0–100 confidence at the moment this pick was
    // tracked. Persisted so OutcomeRecorder can bucket real outcomes by their
    // pick-time confidence (calibration table + per-confidence trust line).
    // Nullable + default null: old rows (and any caller that omits it) stay NULL,
    // and those outcomes simply fall into the "no confidence" (un-bucketed) case.
    // Room maps Int? to a nullable INTEGER column with no SQL default — the
    // Migration(3,4) adds it as a plain nullable column (see DatabaseModule).
    val confidenceAtPick: Int? = null
)
