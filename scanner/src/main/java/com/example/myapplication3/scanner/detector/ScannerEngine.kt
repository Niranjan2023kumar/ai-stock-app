package com.example.myapplication3.scanner.detector

import com.example.myapplication3.core.common.MarketCalendar
import com.example.myapplication3.core.domain.model.*
import javax.inject.Inject
import javax.inject.Singleton

data class ScanResult(
    val symbol: String,
    val exchange: Exchange,
    val signals: List<Signal>,
    val overallScore: Double,
    val timestamp: Long
)

@Singleton
class ScannerEngine @Inject constructor(
    private val signalDetector: SignalDetector,
    private val marketCalendar: MarketCalendar
) {

    fun scan(
        symbol: String,
        exchange: Exchange,
        candles: List<Candle>,
        marketData: MarketData
    ): ScanResult? {
        if (!marketCalendar.isMarketOpen()) return null
        if (candles.size < 50) return null

        val signals = signalDetector.detectAll(symbol, candles, marketData)
        if (signals.isEmpty()) return null

        val overallScore = calculateOverallScore(signals, marketData)

        return ScanResult(
            symbol = symbol,
            exchange = exchange,
            signals = signalDetector.rankSignals(signals),
            overallScore = overallScore,
            timestamp = System.currentTimeMillis()
        )
    }

    private fun calculateOverallScore(signals: List<Signal>, marketData: MarketData): Double {
        if (signals.isEmpty()) return 0.0

        val signalScore = signals.map { it.strength }.average()
        val signalCount = minOf(signals.size.toDouble() / 5.0, 1.0)
        val liquidityScore = if (marketData.volume > 500_000L) 1.0 else marketData.volume / 500_000.0
        val momentumScore = if (marketData.relativeVolume > 2.0) 1.0 else marketData.relativeVolume / 2.0

        return (signalScore * 0.4 + signalCount * 0.2 + liquidityScore * 0.2 + momentumScore * 0.2)
            .coerceIn(0.0, 1.0)
    }

    fun rankScanResults(results: List<ScanResult>): List<ScanResult> =
        results.sortedByDescending { it.overallScore }
}
