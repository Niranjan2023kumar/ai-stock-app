package com.example.myapplication3.network.api.alphavantage

import com.google.gson.annotations.SerializedName

// ── RSI ───────────────────────────────────────────────────────────────────────

/**
 * Top-level response for the Alpha Vantage `RSI` technical indicator function.
 *
 * JSON shape:
 * ```json
 * {
 *   "Meta Data": { … },
 *   "Technical Analysis: RSI": {
 *     "2024-06-10 15:55:00": { "RSI": "58.1234" },
 *     …
 *   }
 * }
 * ```
 */
data class RsiResponse(
    @field:SerializedName("Technical Analysis: RSI")
    val technicalAnalysis: Map<String, RsiData> = emptyMap(),

    @field:SerializedName("Meta Data")
    val metaData: Map<String, String> = emptyMap()
)

/**
 * Single data point in the RSI time series.
 */
data class RsiData(
    @field:SerializedName("RSI")
    val rsi: String = "0"
) {
    /** Convenience: parse [rsi] to [Double]. Returns 0.0 on parse failure. */
    fun rsiValue(): Double = rsi.toDoubleOrNull() ?: 0.0
}

// ── MACD ──────────────────────────────────────────────────────────────────────

/**
 * Top-level response for the Alpha Vantage `MACD` technical indicator function.
 *
 * JSON shape:
 * ```json
 * {
 *   "Meta Data": { … },
 *   "Technical Analysis: MACD": {
 *     "2024-06-10 15:55:00": {
 *       "MACD": "0.1234",
 *       "MACD_Signal": "0.0987",
 *       "MACD_Hist": "0.0247"
 *     },
 *     …
 *   }
 * }
 * ```
 */
data class MacdResponse(
    @field:SerializedName("Technical Analysis: MACD")
    val technicalAnalysis: Map<String, MacdData> = emptyMap(),

    @field:SerializedName("Meta Data")
    val metaData: Map<String, String> = emptyMap()
)

/**
 * Single data point in the MACD time series.
 */
data class MacdData(
    @field:SerializedName("MACD")
    val macd: String = "0",

    @field:SerializedName("MACD_Signal")
    val macdSignal: String = "0",

    @field:SerializedName("MACD_Hist")
    val macdHist: String = "0"
) {
    /** Convenience: parse [macd] to [Double]. */
    fun macdValue(): Double = macd.toDoubleOrNull() ?: 0.0

    /** Convenience: parse [macdSignal] to [Double]. */
    fun signalValue(): Double = macdSignal.toDoubleOrNull() ?: 0.0

    /** Convenience: parse [macdHist] to [Double]. */
    fun histValue(): Double = macdHist.toDoubleOrNull() ?: 0.0

    /** Returns true when the MACD line is above its signal line (bullish). */
    fun isBullishCrossover(): Boolean = macdValue() > signalValue()
}

// ── VWAP ──────────────────────────────────────────────────────────────────────

/**
 * Top-level response for the Alpha Vantage `VWAP` technical indicator function.
 *
 * JSON shape:
 * ```json
 * {
 *   "Meta Data": { … },
 *   "Technical Analysis: VWAP": {
 *     "2024-06-10 15:55:00": { "VWAP": "1234.5678" },
 *     …
 *   }
 * }
 * ```
 */
data class VwapResponse(
    @field:SerializedName("Technical Analysis: VWAP")
    val technicalAnalysis: Map<String, VwapData> = emptyMap(),

    @field:SerializedName("Meta Data")
    val metaData: Map<String, String> = emptyMap()
)

/**
 * Single data point in the VWAP time series.
 */
data class VwapData(
    @field:SerializedName("VWAP")
    val vwap: String = "0"
) {
    /** Convenience: parse [vwap] to [Double]. Returns 0.0 on parse failure. */
    fun vwapValue(): Double = vwap.toDoubleOrNull() ?: 0.0
}
