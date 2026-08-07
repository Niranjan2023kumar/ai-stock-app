package com.example.myapplication3.scanner.technical

import com.example.myapplication3.core.domain.model.Candle
import kotlin.math.*

object TechnicalIndicators {

    fun ema(candles: List<Candle>, period: Int): List<Double> {
        if (candles.size < period) return emptyList()
        val k = 2.0 / (period + 1)
        val result = mutableListOf<Double>()
        var ema = candles.take(period).map { it.close }.average()
        result.add(ema)
        candles.drop(period).forEach { c ->
            ema = c.close * k + ema * (1 - k)
            result.add(ema)
        }
        return result
    }

    fun sma(candles: List<Candle>, period: Int): List<Double> {
        if (candles.size < period) return emptyList()
        return (period..candles.size).map { i ->
            candles.subList(i - period, i).map { it.close }.average()
        }
    }

    fun rsi(candles: List<Candle>, period: Int = 14): List<Double> {
        if (candles.size < period + 1) return emptyList()
        val gains = mutableListOf<Double>()
        val losses = mutableListOf<Double>()
        for (i in 1 until candles.size) {
            val diff = candles[i].close - candles[i - 1].close
            gains.add(max(0.0, diff))
            losses.add(max(0.0, -diff))
        }
        var avgGain = gains.take(period).average()
        var avgLoss = losses.take(period).average()
        val result = mutableListOf<Double>()
        result.add(if (avgLoss == 0.0) 100.0 else 100 - 100 / (1 + avgGain / avgLoss))

        for (i in period until gains.size) {
            avgGain = (avgGain * (period - 1) + gains[i]) / period
            avgLoss = (avgLoss * (period - 1) + losses[i]) / period
            result.add(if (avgLoss == 0.0) 100.0 else 100 - 100 / (1 + avgGain / avgLoss))
        }
        return result
    }

    data class MacdResult(val macd: Double, val signal: Double, val histogram: Double)

    fun macd(candles: List<Candle>, fast: Int = 12, slow: Int = 26, signal: Int = 9): List<MacdResult> {
        val fastEma = ema(candles, fast)
        val slowEma = ema(candles, slow)
        if (fastEma.isEmpty() || slowEma.isEmpty()) return emptyList()
        val offset = slow - fast
        val macdLine = fastEma.drop(offset).zip(slowEma).map { (f, s) -> f - s }
        if (macdLine.size < signal) return emptyList()
        val k = 2.0 / (signal + 1)
        var sigEma = macdLine.take(signal).average()
        val result = mutableListOf<MacdResult>()
        result.add(MacdResult(macdLine[signal - 1], sigEma, macdLine[signal - 1] - sigEma))
        macdLine.drop(signal).forEach { m ->
            sigEma = m * k + sigEma * (1 - k)
            result.add(MacdResult(m, sigEma, m - sigEma))
        }
        return result
    }

    fun atr(candles: List<Candle>, period: Int = 14): List<Double> {
        if (candles.size < period + 1) return emptyList()
        val trueRanges = mutableListOf<Double>()
        for (i in 1 until candles.size) {
            val tr = maxOf(
                candles[i].high - candles[i].low,
                abs(candles[i].high - candles[i - 1].close),
                abs(candles[i].low - candles[i - 1].close)
            )
            trueRanges.add(tr)
        }
        var atr = trueRanges.take(period).average()
        val result = mutableListOf(atr)
        trueRanges.drop(period).forEach { tr ->
            atr = (atr * (period - 1) + tr) / period
            result.add(atr)
        }
        return result
    }

    data class BollingerBands(val upper: Double, val middle: Double, val lower: Double, val width: Double)

    fun bollingerBands(candles: List<Candle>, period: Int = 20, multiplier: Double = 2.0): List<BollingerBands> {
        if (candles.size < period) return emptyList()
        return (period..candles.size).map { i ->
            val slice = candles.subList(i - period, i).map { it.close }
            val mean = slice.average()
            val stdDev = sqrt(slice.map { (it - mean).pow(2) }.average())
            BollingerBands(mean + multiplier * stdDev, mean, mean - multiplier * stdDev, stdDev * 2 * multiplier)
        }
    }

    fun vwap(candles: List<Candle>): List<Double> {
        var cumulativeTPV = 0.0
        var cumulativeVolume = 0L
        return candles.map { c ->
            val typicalPrice = (c.high + c.low + c.close) / 3.0
            cumulativeTPV += typicalPrice * c.volume
            cumulativeVolume += c.volume
            if (cumulativeVolume == 0L) 0.0 else cumulativeTPV / cumulativeVolume
        }
    }

    fun obv(candles: List<Candle>): List<Long> {
        var obv = 0L
        return candles.mapIndexed { i, c ->
            if (i > 0) {
                obv += when {
                    c.close > candles[i - 1].close -> c.volume
                    c.close < candles[i - 1].close -> -c.volume
                    else -> 0L
                }
            }
            obv
        }
    }

    fun relativeVolume(candles: List<Candle>, lookback: Int = 20): Double {
        if (candles.size < lookback + 1) return 0.0
        val avgVolume = candles.takeLast(lookback + 1).dropLast(1).map { it.volume.toDouble() }.average()
        return if (avgVolume > 0) candles.last().volume / avgVolume else 0.0
    }

    fun isHigherHigh(candles: List<Candle>, lookback: Int = 5): Boolean {
        if (candles.size < lookback * 2) return false
        val recentHighs = candles.takeLast(lookback).map { it.high }
        val priorHighs = candles.dropLast(lookback).takeLast(lookback).map { it.high }
        return recentHighs.max() > priorHighs.max()
    }

    fun isHigherLow(candles: List<Candle>, lookback: Int = 5): Boolean {
        if (candles.size < lookback * 2) return false
        val recentLows = candles.takeLast(lookback).map { it.low }
        val priorLows = candles.dropLast(lookback).takeLast(lookback).map { it.low }
        return recentLows.min() > priorLows.min()
    }

    fun isLowerHigh(candles: List<Candle>, lookback: Int = 5): Boolean {
        if (candles.size < lookback * 2) return false
        val recentHighs = candles.takeLast(lookback).map { it.high }
        val priorHighs = candles.dropLast(lookback).takeLast(lookback).map { it.high }
        return recentHighs.max() < priorHighs.max()
    }

    fun isLowerLow(candles: List<Candle>, lookback: Int = 5): Boolean {
        if (candles.size < lookback * 2) return false
        val recentLows = candles.takeLast(lookback).map { it.low }
        val priorLows = candles.dropLast(lookback).takeLast(lookback).map { it.low }
        return recentLows.min() < priorLows.min()
    }
}
