package com.example.myapplication3.strategy.regime

import com.example.myapplication3.core.domain.model.*
import com.example.myapplication3.scanner.technical.TechnicalIndicators
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MarketRegimeEngine @Inject constructor() {

    fun determineRegime(
        indexCandles: List<Candle>,
        breadth: MarketBreadth
    ): MarketRegimeState {
        if (indexCandles.size < 50) {
            return MarketRegimeState(
                regime = MarketRegime.SIDEWAYS,
                volatilityState = VolatilityState.NORMAL,
                trendStrength = 0.5,
                breadthScore = breadth.healthScore / 100.0,
                timestamp = System.currentTimeMillis(),
                description = "Insufficient data for regime determination"
            )
        }

        val ema20 = TechnicalIndicators.ema(indexCandles, 20).lastOrNull() ?: 0.0
        val ema50 = TechnicalIndicators.ema(indexCandles, 50).lastOrNull() ?: 0.0
        // null when there are fewer than 200 candles — NEVER default to 0.0, which
        // would make every price "above the 200-EMA" and bias the regime bullish.
        val ema200 = TechnicalIndicators.ema(indexCandles, 200).lastOrNull()
        val lastClose = indexCandles.last().close
        val rsi = TechnicalIndicators.rsi(indexCandles, 14).lastOrNull() ?: 50.0
        val atrValues = TechnicalIndicators.atr(indexCandles, 14)
        val atr = atrValues.lastOrNull() ?: 0.0
        val atrPercent = if (lastClose > 0) (atr / lastClose) * 100 else 0.0

        val volatilityState = when {
            atrPercent > 2.5 -> VolatilityState.EXTREME
            atrPercent > 1.8 -> VolatilityState.HIGH
            atrPercent > 1.0 -> VolatilityState.NORMAL
            atrPercent > 0.5 -> VolatilityState.LOW
            else -> VolatilityState.VERY_LOW
        }

        val trendStrength = calculateTrendStrength(lastClose, ema20, ema50, ema200, rsi)

        val isHH = TechnicalIndicators.isHigherHigh(indexCandles, 10)
        val isHL = TechnicalIndicators.isHigherLow(indexCandles, 10)
        val isLH = TechnicalIndicators.isLowerHigh(indexCandles, 10)
        val isLL = TechnicalIndicators.isLowerLow(indexCandles, 10)

        val regime = when {
            ema200 != null && lastClose > ema200 && ema20 > ema50 && isHH && isHL && rsi > 55 -> MarketRegime.STRONG_BULL
            lastClose > ema50 && ema20 > ema50 && breadth.advanceDeclineRatio > 1.5 -> MarketRegime.BULL
            ema200 != null && lastClose < ema200 && ema20 < ema50 && isLH && isLL && rsi < 45 -> MarketRegime.STRONG_BEAR
            lastClose < ema50 && ema20 < ema50 && breadth.advanceDeclineRatio < 0.7 -> MarketRegime.BEAR
            else -> MarketRegime.SIDEWAYS
        }

        val description = buildDescription(regime, volatilityState, trendStrength, breadth)

        return MarketRegimeState(
            regime = regime,
            volatilityState = volatilityState,
            trendStrength = trendStrength,
            breadthScore = breadth.healthScore / 100.0,
            timestamp = System.currentTimeMillis(),
            description = description
        )
    }

    private fun calculateTrendStrength(
        price: Double, ema20: Double, ema50: Double, ema200: Double?, rsi: Double
    ): Double {
        var score = 0.5
        if (price > ema20) score += 0.1
        if (price > ema50) score += 0.15
        if (ema200 != null && price > ema200) score += 0.2
        if (ema20 > ema50) score += 0.1
        score += (rsi - 50) / 100.0 * 0.2
        return score.coerceIn(0.0, 1.0)
    }

    private fun buildDescription(
        regime: MarketRegime,
        volatility: VolatilityState,
        trendStrength: Double,
        breadth: MarketBreadth
    ): String = "${regime.displayName} market with ${volatility.displayName.lowercase()} volatility. " +
            "Trend strength: ${String.format("%.0f", trendStrength * 100)}%. " +
            "Breadth: ${breadth.advances}A/${breadth.declines}D (AD ratio: ${String.format("%.2f", breadth.advanceDeclineRatio)})"
}
