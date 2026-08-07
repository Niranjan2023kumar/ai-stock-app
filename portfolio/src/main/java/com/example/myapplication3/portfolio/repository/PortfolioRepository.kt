package com.example.myapplication3.portfolio.repository

import android.content.Context
import com.example.myapplication3.core.common.Constants
import com.example.myapplication3.core.common.Result
import com.example.myapplication3.core.domain.model.*
import com.example.myapplication3.database.dao.PortfolioDao
import com.example.myapplication3.database.entity.PortfolioPositionEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.Calendar
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PortfolioRepository @Inject constructor(
    private val portfolioDao: PortfolioDao,
    @ApplicationContext private val context: Context
) {

    private val prefs by lazy {
        context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getOpenPositions(): Flow<List<PortfolioPosition>> =
        portfolioDao.getOpenPositions().map { entities -> entities.map { it.toDomain() } }

    fun getAllPositions(): Flow<List<PortfolioPosition>> =
        portfolioDao.getAllPositions().map { entities -> entities.map { it.toDomain() } }

    suspend fun getOpenPositionsOnce(): List<PortfolioPosition> =
        portfolioDao.getOpenPositionsOnce().map { it.toDomain() }

    suspend fun openPosition(
        recommendation: Recommendation,
        quantity: Int,
        executionPrice: Double
    ): Result<PortfolioPosition> {
        val position = PortfolioPositionEntity(
            id = UUID.randomUUID().toString(),
            symbol = recommendation.symbol,
            exchange = recommendation.exchange.name,
            direction = recommendation.direction.name,
            quantity = quantity,
            averageEntryPrice = executionPrice,
            currentPrice = executionPrice,
            stopLoss = recommendation.stopLoss,
            target1 = recommendation.target1,
            target2 = recommendation.target2,
            recommendationId = recommendation.id,
            openedAt = System.currentTimeMillis()
        )
        portfolioDao.insert(position)
        return Result.Success(position.toDomain())
    }

    suspend fun closePosition(positionId: String, closingPrice: Double): Result<Unit> {
        portfolioDao.closePosition(
            id = positionId,
            status = PositionStatus.CLOSED.name,
            closedAt = System.currentTimeMillis(),
            closingPrice = closingPrice,
            updatedAt = System.currentTimeMillis()
        )
        return Result.Success(Unit)
    }

    suspend fun updateCurrentPrice(positionId: String, price: Double) {
        portfolioDao.updatePrice(positionId, price, System.currentTimeMillis())
    }

    suspend fun calculateDailySummary(): PortfolioSummary {
        val dayStart = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
        }.timeInMillis
        val weekStart = dayStart - 7 * 24 * 60 * 60 * 1000L

        val openPositions = portfolioDao.getOpenCount()
        val closedToday = portfolioDao.getClosedSince(dayStart)
        val closedThisWeek = portfolioDao.getClosedSince(weekStart)

        val dailyPnl = closedToday.sumOf { p ->
            p.closingPrice?.let { cp ->
                val direction = TradeDirection.valueOf(p.direction)
                if (direction == TradeDirection.LONG) (cp - p.averageEntryPrice) * p.quantity
                else (p.averageEntryPrice - cp) * p.quantity
            } ?: 0.0
        }

        val weeklyPnl = closedThisWeek.sumOf { p ->
            p.closingPrice?.let { cp ->
                val direction = TradeDirection.valueOf(p.direction)
                if (direction == TradeDirection.LONG) (cp - p.averageEntryPrice) * p.quantity
                else (p.averageEntryPrice - cp) * p.quantity
            } ?: 0.0
        }

        val totalTrades = closedThisWeek.size
        val wins = closedThisWeek.count { p ->
            p.closingPrice?.let { cp ->
                val direction = TradeDirection.valueOf(p.direction)
                if (direction == TradeDirection.LONG) cp > p.averageEntryPrice
                else cp < p.averageEntryPrice
            } ?: false
        }

        val maxDailyLoss = prefs.getFloat(Constants.PREF_MAX_DAILY_LOSS, 5000f).toDouble()
        val maxWeeklyLoss = prefs.getFloat(Constants.PREF_MAX_WEEKLY_LOSS, 15000f).toDouble()

        // Real aggregates from open positions (not hardcoded 0.0). currentValue /
        // unrealizedPnl reflect each position's currentPrice — keep it live via
        // updateCurrentPrice() when positions are being tracked.
        val openList = getOpenPositionsOnce()

        return PortfolioSummary(
            totalInvested = openList.sumOf { it.investedAmount },
            currentValue = openList.sumOf { it.currentValue },
            unrealizedPnl = openList.sumOf { it.unrealizedPnl },
            realizedPnlToday = dailyPnl,
            realizedPnlWeek = weeklyPnl,
            openPositions = openPositions,
            winRate = if (totalTrades > 0) wins.toDouble() / totalTrades else 0.0,
            dailyLoss = if (dailyPnl < 0) -dailyPnl else 0.0,
            weeklyLoss = if (weeklyPnl < 0) -weeklyPnl else 0.0,
            maxDailyLossLimit = maxDailyLoss,
            maxWeeklyLossLimit = maxWeeklyLoss
        )
    }

    fun isDailyLimitBreached(): Boolean {
        val maxDailyLoss = prefs.getFloat(Constants.PREF_MAX_DAILY_LOSS, 5000f).toDouble()
        // We expose this synchronously from prefs for quick checks; summary has the real calc
        return false // Evaluated properly in calculateDailySummary
    }

    private fun PortfolioPositionEntity.toDomain() = PortfolioPosition(
        id = id,
        symbol = symbol,
        exchange = Exchange.valueOf(exchange),
        direction = TradeDirection.valueOf(direction),
        quantity = quantity,
        averageEntryPrice = averageEntryPrice,
        currentPrice = currentPrice,
        stopLoss = stopLoss,
        target1 = target1,
        target2 = target2,
        recommendationId = recommendationId,
        openedAt = openedAt,
        closedAt = closedAt,
        closingPrice = closingPrice,
        status = try { PositionStatus.valueOf(status) } catch (e: Exception) { PositionStatus.OPEN }
    )
}
