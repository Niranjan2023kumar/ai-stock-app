package com.example.myapplication3.core.domain.model

data class Signal(
    val type: SignalType,
    val symbol: String,
    val strength: Double,
    val timestamp: Long,
    val description: String,
    val supportingData: Map<String, Double> = emptyMap()
)

enum class SignalType(val displayName: String, val isBullish: Boolean?) {
    VOLUME_BREAKOUT("Volume Breakout", true),
    RELATIVE_VOLUME_BREAKOUT("Relative Volume Breakout", true),
    DELIVERY_BREAKOUT("Delivery Breakout", true),
    GAP_UP("Gap Up", true),
    GAP_DOWN("Gap Down", false),
    OPENING_RANGE_BREAKOUT("Opening Range Breakout", true),
    OPENING_RANGE_BREAKDOWN("Opening Range Breakdown", false),
    TREND_CONTINUATION("Trend Continuation", null),
    TREND_REVERSAL("Trend Reversal", null),
    RELATIVE_STRENGTH("Relative Strength", true),
    RELATIVE_WEAKNESS("Relative Weakness", false),
    MOMENTUM_EXPANSION("Momentum Expansion", true),
    VOLATILITY_CONTRACTION("Volatility Contraction", null),
    VOLATILITY_EXPANSION("Volatility Expansion", null),
    RSI_OVERSOLD("RSI Oversold", true),
    RSI_OVERBOUGHT("RSI Overbought", false),
    MACD_BULLISH_CROSS("MACD Bullish Cross", true),
    MACD_BEARISH_CROSS("MACD Bearish Cross", false),
    VWAP_RECLAIM("VWAP Reclaim", true),
    VWAP_REJECTION("VWAP Rejection", false),
    EMA_BULLISH_CROSS("EMA Bullish Cross", true),
    EMA_BEARISH_CROSS("EMA Bearish Cross", false),
    BOLLINGER_SQUEEZE("Bollinger Squeeze", null),
    BOLLINGER_EXPANSION("Bollinger Expansion", null)
}
