package com.example.myapplication3.scanner.detector

import com.example.myapplication3.core.domain.model.*
import com.example.myapplication3.scanner.technical.TechnicalIndicators
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

@Singleton
class SignalDetector @Inject constructor() {

    fun detectAll(symbol: String, candles: List<Candle>, marketData: MarketData): List<Signal> {
        if (candles.size < 50) return emptyList()
        val signals = mutableListOf<Signal>()
        val now = System.currentTimeMillis()

        detectVolumeBreakout(symbol, candles, now)?.let { signals.add(it) }
        detectRelativeVolumeBreakout(symbol, candles, now)?.let { signals.add(it) }
        detectGap(symbol, marketData, now)?.let { signals.add(it) }
        detectOpeningRangeBreakout(symbol, candles, now)?.let { signals.add(it) }
        detectTrendSignals(symbol, candles, now).let { signals.addAll(it) }
        detectMomentumSignals(symbol, candles, now).let { signals.addAll(it) }
        detectVwapSignal(symbol, candles, now)?.let { signals.add(it) }
        detectVolatilitySignals(symbol, candles, now).let { signals.addAll(it) }

        return signals
    }

    private fun detectVolumeBreakout(symbol: String, candles: List<Candle>, now: Long): Signal? {
        val lookback = 20
        if (candles.size < lookback + 1) return null
        val recent = candles.last()
        val avgVolume = candles.takeLast(lookback + 1).dropLast(1).map { it.volume.toDouble() }.average()
        if (avgVolume == 0.0) return null
        val ratio = recent.volume / avgVolume
        if (ratio < 2.5) return null
        return Signal(
            type = SignalType.VOLUME_BREAKOUT,
            symbol = symbol,
            strength = minOf(ratio / 5.0, 1.0),
            timestamp = now,
            description = "Volume ${String.format("%.1f", ratio)}x average",
            supportingData = mapOf("volumeRatio" to ratio, "avgVolume" to avgVolume, "currentVolume" to recent.volume.toDouble())
        )
    }

    private fun detectRelativeVolumeBreakout(symbol: String, candles: List<Candle>, now: Long): Signal? {
        val rvol = TechnicalIndicators.relativeVolume(candles, 20)
        if (rvol < 3.0) return null
        return Signal(
            type = SignalType.RELATIVE_VOLUME_BREAKOUT,
            symbol = symbol,
            strength = minOf(rvol / 6.0, 1.0),
            timestamp = now,
            description = "Relative volume ${String.format("%.1f", rvol)}x",
            supportingData = mapOf("relativeVolume" to rvol)
        )
    }

    private fun detectGap(symbol: String, data: MarketData, now: Long): Signal? {
        val gapPct = data.gapPercent
        return when {
            gapPct > 2.0 -> Signal(
                type = SignalType.GAP_UP,
                symbol = symbol,
                strength = minOf(abs(gapPct) / 5.0, 1.0),
                timestamp = now,
                description = "Gap up ${String.format("%.2f", gapPct)}%",
                supportingData = mapOf("gapPercent" to gapPct)
            )
            gapPct < -2.0 -> Signal(
                type = SignalType.GAP_DOWN,
                symbol = symbol,
                strength = minOf(abs(gapPct) / 5.0, 1.0),
                timestamp = now,
                description = "Gap down ${String.format("%.2f", gapPct)}%",
                supportingData = mapOf("gapPercent" to gapPct)
            )
            else -> null
        }
    }

    private fun detectOpeningRangeBreakout(symbol: String, candles: List<Candle>, now: Long): Signal? {
        val firstHour = candles.take(12)
        if (firstHour.size < 12) return null
        val orHigh = firstHour.maxOf { it.high }
        val orLow = firstHour.minOf { it.low }
        val last = candles.last()
        return when {
            last.close > orHigh -> Signal(
                type = SignalType.OPENING_RANGE_BREAKOUT,
                symbol = symbol,
                strength = minOf((last.close - orHigh) / orHigh * 100 / 1.0, 1.0),
                timestamp = now,
                description = "ORB breakout above ${String.format("%.2f", orHigh)}",
                supportingData = mapOf("orHigh" to orHigh, "orLow" to orLow, "breakoutLevel" to orHigh)
            )
            last.close < orLow -> Signal(
                type = SignalType.OPENING_RANGE_BREAKDOWN,
                symbol = symbol,
                strength = minOf((orLow - last.close) / orLow * 100 / 1.0, 1.0),
                timestamp = now,
                description = "ORB breakdown below ${String.format("%.2f", orLow)}",
                supportingData = mapOf("orHigh" to orHigh, "orLow" to orLow, "breakdownLevel" to orLow)
            )
            else -> null
        }
    }

    private fun detectTrendSignals(symbol: String, candles: List<Candle>, now: Long): List<Signal> {
        val signals = mutableListOf<Signal>()
        val ema20 = TechnicalIndicators.ema(candles, 20)
        val ema50 = TechnicalIndicators.ema(candles, 50)
        if (ema20.size < 2 || ema50.isEmpty()) return signals

        val isHH = TechnicalIndicators.isHigherHigh(candles)
        val isHL = TechnicalIndicators.isHigherLow(candles)
        val isLH = TechnicalIndicators.isLowerHigh(candles)
        val isLL = TechnicalIndicators.isLowerLow(candles)

        if (isHH && isHL) {
            signals.add(Signal(
                type = SignalType.TREND_CONTINUATION,
                symbol = symbol,
                strength = 0.7,
                timestamp = now,
                description = "Uptrend: Higher Highs and Higher Lows",
                supportingData = mapOf("ema20" to ema20.last(), "ema50" to ema50.last())
            ))
        }

        if (isLH && isLL) {
            signals.add(Signal(
                type = SignalType.TREND_REVERSAL,
                symbol = symbol,
                strength = 0.65,
                timestamp = now,
                description = "Downtrend: Lower Highs and Lower Lows",
                supportingData = mapOf("ema20" to ema20.last(), "ema50" to ema50.last())
            ))
        }

        val prevEma20 = ema20[ema20.size - 2]
        val currEma20 = ema20.last()
        if (ema50.size >= 2) {
            val prevEma50 = ema50[ema50.size - 2]
            val currEma50 = ema50.last()
            if (prevEma20 < prevEma50 && currEma20 > currEma50) {
                signals.add(Signal(
                    type = SignalType.EMA_BULLISH_CROSS,
                    symbol = symbol,
                    strength = 0.75,
                    timestamp = now,
                    description = "EMA 20 crossed above EMA 50",
                    supportingData = mapOf("ema20" to currEma20, "ema50" to currEma50)
                ))
            } else if (prevEma20 > prevEma50 && currEma20 < currEma50) {
                signals.add(Signal(
                    type = SignalType.EMA_BEARISH_CROSS,
                    symbol = symbol,
                    strength = 0.75,
                    timestamp = now,
                    description = "EMA 20 crossed below EMA 50",
                    supportingData = mapOf("ema20" to currEma20, "ema50" to currEma50)
                ))
            }
        }
        return signals
    }

    private fun detectMomentumSignals(symbol: String, candles: List<Candle>, now: Long): List<Signal> {
        val signals = mutableListOf<Signal>()
        val rsiValues = TechnicalIndicators.rsi(candles, 14)
        if (rsiValues.size >= 2) {
            val rsi = rsiValues.last()
            val prevRsi = rsiValues[rsiValues.size - 2]
            if (rsi < 30 && prevRsi >= 30) {
                signals.add(Signal(SignalType.RSI_OVERSOLD, symbol, (30 - rsi) / 30, now,
                    "RSI oversold at ${String.format("%.1f", rsi)}", mapOf("rsi" to rsi)))
            } else if (rsi > 70 && prevRsi <= 70) {
                signals.add(Signal(SignalType.RSI_OVERBOUGHT, symbol, (rsi - 70) / 30, now,
                    "RSI overbought at ${String.format("%.1f", rsi)}", mapOf("rsi" to rsi)))
            }
        }

        val macdList = TechnicalIndicators.macd(candles)
        if (macdList.size >= 2) {
            val curr = macdList.last()
            val prev = macdList[macdList.size - 2]
            if (prev.histogram < 0 && curr.histogram > 0) {
                signals.add(Signal(SignalType.MACD_BULLISH_CROSS, symbol, minOf(curr.histogram / curr.macd, 1.0), now,
                    "MACD histogram turned positive", mapOf("macd" to curr.macd, "signal" to curr.signal, "histogram" to curr.histogram)))
            } else if (prev.histogram > 0 && curr.histogram < 0) {
                signals.add(Signal(SignalType.MACD_BEARISH_CROSS, symbol, minOf(abs(curr.histogram / curr.macd), 1.0), now,
                    "MACD histogram turned negative", mapOf("macd" to curr.macd, "signal" to curr.signal, "histogram" to curr.histogram)))
            }
        }

        val rsiLen = rsiValues.size
        if (rsiLen >= 5) {
            val recentRsi = rsiValues.takeLast(5)
            val isExpanding = recentRsi.last() > 60 && recentRsi.first() < 50 && recentRsi.last() > recentRsi.first()
            if (isExpanding) {
                signals.add(Signal(SignalType.MOMENTUM_EXPANSION, symbol, 0.65, now,
                    "Momentum expanding, RSI rising",
                    mapOf("rsi" to rsiValues.last(), "rsiChange" to (rsiValues.last() - rsiValues[rsiLen - 5]))))
            }
        }
        return signals
    }

    private fun detectVwapSignal(symbol: String, candles: List<Candle>, now: Long): Signal? {
        if (candles.size < 10) return null
        val vwapValues = TechnicalIndicators.vwap(candles)
        if (vwapValues.size < 2) return null
        val lastVwap = vwapValues.last()
        val prevVwap = vwapValues[vwapValues.size - 2]
        val lastClose = candles.last().close
        val prevClose = candles[candles.size - 2].close

        return when {
            prevClose < prevVwap && lastClose > lastVwap -> Signal(
                SignalType.VWAP_RECLAIM, symbol, 0.7, now, "Price reclaimed VWAP",
                mapOf("vwap" to lastVwap, "price" to lastClose)
            )
            prevClose > prevVwap && lastClose < lastVwap -> Signal(
                SignalType.VWAP_REJECTION, symbol, 0.65, now, "Price rejected at VWAP",
                mapOf("vwap" to lastVwap, "price" to lastClose)
            )
            else -> null
        }
    }

    private fun detectVolatilitySignals(symbol: String, candles: List<Candle>, now: Long): List<Signal> {
        val signals = mutableListOf<Signal>()
        val bbList = TechnicalIndicators.bollingerBands(candles, 20)
        if (bbList.size < 10) return signals

        val recentWidths = bbList.takeLast(10).map { it.width }
        val currentWidth = recentWidths.last()
        val avgWidth = recentWidths.dropLast(1).average()

        if (currentWidth < avgWidth * 0.5) {
            signals.add(Signal(SignalType.BOLLINGER_SQUEEZE, symbol, 0.7, now,
                "Bollinger Bands contracting, breakout imminent",
                mapOf("bandWidth" to currentWidth, "avgWidth" to avgWidth)))
            signals.add(Signal(SignalType.VOLATILITY_CONTRACTION, symbol, 0.65, now,
                "Volatility contracting",
                mapOf("bandWidth" to currentWidth)))
        } else if (currentWidth > avgWidth * 1.5) {
            signals.add(Signal(SignalType.VOLATILITY_EXPANSION, symbol, 0.6, now,
                "Volatility expanding",
                mapOf("bandWidth" to currentWidth, "avgWidth" to avgWidth)))
        }
        return signals
    }

    fun rankSignals(signals: List<Signal>): List<Signal> =
        signals.sortedByDescending { it.strength }
}
