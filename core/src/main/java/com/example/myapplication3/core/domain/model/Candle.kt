package com.example.myapplication3.core.domain.model

data class Candle(
    val symbol: String,
    val timestamp: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long,
    val deliveryVolume: Long = 0L,
    val vwap: Double = 0.0,
    val interval: CandleInterval = CandleInterval.ONE_MINUTE,
    val isComplete: Boolean = true
) {
    val range: Double get() = high - low
    val body: Double get() = kotlin.math.abs(close - open)
    val isBullish: Boolean get() = close > open
    val isBearish: Boolean get() = close < open
    val upperWick: Double get() = high - maxOf(open, close)
    val lowerWick: Double get() = minOf(open, close) - low
    val bodyPercent: Double get() = if (range > 0) (body / range) * 100 else 0.0
    val deliveryPercent: Double get() = if (volume > 0) (deliveryVolume.toDouble() / volume) * 100 else 0.0
}

enum class CandleInterval(val minutes: Int, val label: String) {
    ONE_MINUTE(1, "1m"),
    THREE_MINUTES(3, "3m"),
    FIVE_MINUTES(5, "5m"),
    FIFTEEN_MINUTES(15, "15m"),
    THIRTY_MINUTES(30, "30m"),
    ONE_HOUR(60, "1h"),
    ONE_DAY(1440, "1D"),
    ONE_WEEK(10080, "1W"),
    ONE_MONTH(43200, "1M")
}
