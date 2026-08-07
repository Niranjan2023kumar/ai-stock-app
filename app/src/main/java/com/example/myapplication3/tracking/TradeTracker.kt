package com.example.myapplication3.tracking

import com.example.myapplication3.groww.OrderType

/**
 * This week's honest scorecard (E1a) — right vs wrong picks and net P/L.
 * [netPnl] is AFTER round-trip charges (B0.3a): each trade's realized P/L already
 * has brokerage/STT/GST/slippage subtracted at close time, so this matches Groww.
 *
 * B0.3b honesty: a report is built from ONE mode only — real and practice trades
 * are never mixed (the DAO query filters on isPractice). [isPractice] says which
 * mode this report is, so any UI showing it can (and must) label practice numbers
 * as practice instead of passing them off as the real track record.
 */
data class WeekReport(
    val wins: Int,
    val losses: Int,
    val netPnl: Double,
    val isPractice: Boolean = false
) {
    val total: Int get() = wins + losses
}

/** A tracked, user-placed trade (app-facing view of TrackedTradeEntity). */
data class TrackedTrade(
    val id: String,
    val symbol: String,
    val name: String,
    val orderType: OrderType,
    val isPractice: Boolean,
    val entryPrice: Double,
    val quantity: Int,
    val stopLoss: Double,
    val target1: Double,
    val target2: Double,
    val target3: Double,
    val openedAt: Long,
    val isOpen: Boolean,
    val exitPrice: Double? = null,
    val closedAt: Long? = null,
    // NET of round-trip charges (B0.3a) — set at close time by TradeTrackerRepository.close()
    val realizedPnl: Double? = null
) {
    val invested: Double get() = entryPrice * quantity

    /** Live unrealized P/L for a long position at [currentPrice]. */
    fun unrealizedPnl(currentPrice: Double): Double = (currentPrice - entryPrice) * quantity

    /** True if the live price has hit/breached the stop-loss (protection trigger). */
    fun stopLossHit(currentPrice: Double): Boolean = stopLoss > 0.0 && currentPrice <= stopLoss

    /** True if the live price has reached the first target. */
    fun target1Hit(currentPrice: Double): Boolean = target1 > 0.0 && currentPrice >= target1
}
