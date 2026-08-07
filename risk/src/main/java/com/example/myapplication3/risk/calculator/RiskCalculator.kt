package com.example.myapplication3.risk.calculator

import com.example.myapplication3.core.common.Constants
import com.example.myapplication3.core.domain.model.*
import com.example.myapplication3.scanner.technical.TechnicalIndicators
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

@Singleton
class RiskCalculator @Inject constructor() {

    fun calculateRisk(
        stock: Stock,
        candles: List<Candle>,
        marketData: MarketData
    ): RiskMetrics {
        val flags = mutableListOf<RiskFlag>()

        val liquidityRisk = assessLiquidityRisk(marketData, flags)
        val volatilityRisk = assessVolatilityRisk(candles, flags)
        val manipulationRisk = assessManipulationRisk(candles, marketData, stock, flags)

        if (stock.isCircuitRestricted) flags.add(RiskFlag.CIRCUIT_RESTRICTED)
        if (stock.isSuspended) flags.add(RiskFlag.SUSPENDED)

        val dataQualityScore = assessDataQuality(candles)
        if (dataQualityScore < 0.7) flags.add(RiskFlag.DATA_QUALITY_ISSUE)

        val criticalFlags = flags.filter { it.severity == RiskSeverity.CRITICAL }
        val highFlags = flags.filter { it.severity == RiskSeverity.HIGH }

        val isTradeAllowed = criticalFlags.isEmpty() && highFlags.isEmpty()
        val rejectionReason = if (!isTradeAllowed) {
            (criticalFlags + highFlags).firstOrNull()?.description
        } else null

        val overallScore = (liquidityRisk.score * 0.3 + volatilityRisk.score * 0.3 + manipulationRisk.score * 0.4)
            .coerceIn(0.0, 1.0)

        return RiskMetrics(
            symbol = stock.symbol,
            overallRiskScore = overallScore,
            liquidityRisk = liquidityRisk,
            volatilityRisk = volatilityRisk,
            manipulationRisk = manipulationRisk,
            dataQualityScore = dataQualityScore,
            flags = flags,
            isTradeAllowed = isTradeAllowed,
            rejectionReason = rejectionReason
        )
    }

    private fun assessLiquidityRisk(data: MarketData, flags: MutableList<RiskFlag>): LiquidityRisk {
        val isLowLiquidity = data.volume < Constants.MIN_LIQUIDITY_VOLUME
        val isWidenedSpread = data.spreadPercent > Constants.MAX_SPREAD_PERCENT

        if (isLowLiquidity) flags.add(RiskFlag.LOW_LIQUIDITY)
        if (isWidenedSpread) flags.add(RiskFlag.WIDE_SPREAD)

        val liquidityScore = if (isLowLiquidity || isWidenedSpread) 0.8 else
            (data.spreadPercent / Constants.MAX_SPREAD_PERCENT * 0.4 +
                    (1 - (data.volume / Constants.MIN_LIQUIDITY_VOLUME.toDouble()).coerceAtMost(1.0)) * 0.4).coerceIn(0.0, 1.0)

        return LiquidityRisk(
            score = liquidityScore,
            averageVolume = data.averageVolume20D,
            spreadPercent = data.spreadPercent,
            isLowLiquidity = isLowLiquidity,
            isWidenedSpread = isWidenedSpread
        )
    }

    private fun assessVolatilityRisk(candles: List<Candle>, flags: MutableList<RiskFlag>): VolatilityRisk {
        if (candles.isEmpty()) {
            return VolatilityRisk(score = 0.5, atr = 0.0, atrPercent = 0.0, isHighVolatility = false, historicalVolatility = 0.0)
        }

        val atrValues = TechnicalIndicators.atr(candles, 14)
        val atr = atrValues.lastOrNull() ?: 0.0
        val lastClose = candles.last().close
        val atrPercent = if (lastClose > 0) (atr / lastClose) * 100 else 0.0

        val returns = candles.zipWithNext { a, b -> (b.close - a.close) / a.close }
        val hv = if (returns.size > 1) returns.let { r ->
            val mean = r.average()
            Math.sqrt(r.map { (it - mean) * (it - mean) }.average()) * Math.sqrt(252.0) * 100
        } else 0.0

        val isHighVolatility = atrPercent > 3.0
        if (isHighVolatility) flags.add(RiskFlag.HIGH_VOLATILITY)

        return VolatilityRisk(
            score = (atrPercent / 5.0).coerceIn(0.0, 1.0),
            atr = atr,
            atrPercent = atrPercent,
            isHighVolatility = isHighVolatility,
            historicalVolatility = hv
        )
    }

    private fun assessManipulationRisk(
        candles: List<Candle>,
        data: MarketData,
        stock: Stock,
        flags: MutableList<RiskFlag>
    ): ManipulationRisk {
        val lookback = minOf(20, candles.size - 1)
        if (lookback < 5) return ManipulationRisk(0.2, false, false, false, false, 0.2)

        val recentVolumes = candles.takeLast(lookback).map { it.volume.toDouble() }
        val avgVolume = recentVolumes.average()
        val stdVolume = recentVolumes.let { v ->
            val mean = v.average()
            Math.sqrt(v.map { (it - mean) * (it - mean) }.average())
        }
        val currentVolume = candles.last().volume.toDouble()
        val volumeZScore = if (stdVolume > 0) (currentVolume - avgVolume) / stdVolume else 0.0

        val isAbnormalVolumeSpike = volumeZScore > 4.0
        if (isAbnormalVolumeSpike) flags.add(RiskFlag.ABNORMAL_VOLUME)

        val isLowFreeFloat = stock.freeFloat > 0 && stock.freeFloat < 0.2
        if (isLowFreeFloat) flags.add(RiskFlag.LOW_FREE_FLOAT)

        val recentHighs = candles.takeLast(5).map { it.high }
        val priorLow = candles.dropLast(5).takeLast(10).minOfOrNull { it.low } ?: 0.0
        val isSuspiciousGap = recentHighs.max() > priorLow * 1.15
        if (isSuspiciousGap) flags.add(RiskFlag.SUSPICIOUS_GAP)

        val isPumpDump = isAbnormalVolumeSpike && data.changePercent > 8.0 && isLowFreeFloat
        if (isPumpDump) flags.add(RiskFlag.PUMP_DUMP_SUSPECTED)

        val abnormalityScore = (volumeZScore / 5.0).coerceIn(0.0, 1.0)

        return ManipulationRisk(
            score = (isPumpDump.factor() * 0.4 + isAbnormalVolumeSpike.factor() * 0.3 + isLowFreeFloat.factor() * 0.3),
            isPumpDumpSuspected = isPumpDump,
            isAbnormalVolumeSpike = isAbnormalVolumeSpike,
            isLowFreeFloat = isLowFreeFloat,
            isSuspiciousGap = isSuspiciousGap,
            abnormalityScore = abnormalityScore
        )
    }

    private fun assessDataQuality(candles: List<Candle>): Double {
        if (candles.isEmpty()) return 0.0
        val complete = candles.count { it.isComplete }
        val hasZeroVolume = candles.count { it.volume == 0L }
        val completeness = complete.toDouble() / candles.size
        val volumeQuality = 1.0 - (hasZeroVolume.toDouble() / candles.size).coerceAtMost(0.5)
        return (completeness * 0.6 + volumeQuality * 0.4).coerceIn(0.0, 1.0)
    }

    fun calculatePositionSize(
        capital: Double,
        riskPerTrade: Double,
        entryPrice: Double,
        stopLoss: Double
    ): Int {
        if (entryPrice <= 0 || stopLoss <= 0 || entryPrice == stopLoss) return 0
        val riskAmount = capital * (riskPerTrade / 100.0)
        val riskPerShare = abs(entryPrice - stopLoss)
        return (riskAmount / riskPerShare).toInt().coerceAtLeast(0)
    }

    private fun Boolean.factor(): Double = if (this) 1.0 else 0.0
}
