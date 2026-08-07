package com.example.myapplication3.marketdata.validator

import com.example.myapplication3.core.domain.model.Candle
import javax.inject.Inject
import javax.inject.Singleton

data class ValidationResult(
    val valid: List<Candle>,
    val rejected: List<Pair<Candle, String>>
)

@Singleton
class CandleValidator @Inject constructor() {

    fun validate(candles: List<Candle>): ValidationResult {
        val valid = mutableListOf<Candle>()
        val rejected = mutableListOf<Pair<Candle, String>>()
        val seenTimestamps = HashSet<Long>()

        candles.sortedBy { it.timestamp }.forEach { candle ->
            val error = validate(candle)
            if (error != null) {
                rejected.add(candle to error)
                return@forEach
            }
            if (!seenTimestamps.add(candle.timestamp)) {
                rejected.add(candle to "Duplicate timestamp: ${candle.timestamp}")
                return@forEach
            }
            valid.add(candle)
        }

        return ValidationResult(valid, rejected)
    }

    private fun validate(candle: Candle): String? {
        if (candle.high < candle.low) return "High (${candle.high}) < Low (${candle.low})"
        if (candle.open < 0 || candle.high < 0 || candle.low < 0 || candle.close < 0) return "Negative price"
        if (candle.volume < 0) return "Negative volume"
        if (candle.open > candle.high || candle.open < candle.low) return "Open outside High/Low"
        if (candle.close > candle.high || candle.close < candle.low) return "Close outside High/Low"
        if (candle.timestamp <= 0) return "Invalid timestamp"
        if (candle.high == 0.0 && candle.low == 0.0) return "Zero price candle"
        if (candle.high / candle.low > 2.0) return "Suspicious price range: high/low ratio > 2"
        return null
    }
}
