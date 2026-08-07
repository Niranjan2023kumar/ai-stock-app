package com.example.myapplication3.intraday

import com.example.myapplication3.smartapi.Candle

/**
 * Result of reading one symbol's 5-minute session candles.
 *
 * Everything here is computed from REAL intraday bars (Angel One historical
 * candle API) — nothing is synthesized. When candles are missing or broken the
 * scorer returns null and the daily-data signal path continues unchanged (B8).
 *
 * @property vwapValue        Session VWAP (volume-weighted average price).
 * @property priceVsVwapPct   Last close vs VWAP, in percent (+ above / − below).
 * @property orState          Opening-range breakout state: "BULLISH", "BEARISH" or "NEUTRAL".
 * @property momentumPct      Last close vs first open of the session, in percent.
 * @property volumeSurgeRatio Last-3-candle average volume ÷ session per-candle average.
 * @property score            Net intraday score in −20..+20 (positive = intraday supports buying).
 * @property verdictPlain     One shopkeeper-words sentence for the "Why" list (I2 — no jargon).
 */
data class IntradayRead(
    val vwapValue: Double,
    val priceVsVwapPct: Double,
    val orState: String,
    val momentumPct: Double,
    val volumeSurgeRatio: Double,
    val score: Int,
    val verdictPlain: String
)

/**
 * Pure-math intraday scoring from 5-minute candles. No Android dependencies —
 * every function is deterministic and unit-testable.
 *
 * Fail-safe contract: every function returns null (never throws) when the
 * input is empty, too short, or contains broken numbers, so the existing
 * daily-data path is never disturbed.
 */
object IntradayScorer {

    const val OR_BULLISH = "BULLISH"
    const val OR_BEARISH = "BEARISH"
    const val OR_NEUTRAL = "NEUTRAL"

    /** Number of 5-minute candles that form the opening range (09:15–09:30 IST). */
    private const val OPENING_RANGE_CANDLES = 3

    /** Number of 5-minute candles in the last 30 minutes. */
    private const val LAST_30_MIN_CANDLES = 6

    /** Minimum candles for a meaningful read (opening range + at least one bar after it). */
    private const val MIN_CANDLES = OPENING_RANGE_CANDLES + 1

    /** Dead-zone around VWAP: within ±0.05% counts as "at the average", not above/below. */
    private const val VWAP_DEAD_ZONE_PCT = 0.05

    /** Volume-surge threshold that earns amplification points. */
    private const val SURGE_THRESHOLD = 1.5

    /**
     * Direction of a short close-series: +1 rising, −1 falling, 0 flat.
     * @property pctChange     Session change: last close vs first open, percent.
     * @property slopeDirection Least-squares slope sign of the last-30-min closes.
     */
    data class Momentum(val pctChange: Double, val slopeDirection: Int)

    /**
     * Session VWAP: cumulative (typical price × volume) ÷ cumulative volume,
     * where typical price = (high + low + close) / 3.
     * Null when candles are empty or total volume is zero.
     */
    fun vwap(candles: List<Candle>): Double? {
        if (candles.isEmpty()) return null
        var pv = 0.0
        var vol = 0.0
        for (c in candles) {
            val v = c.volume.toDouble()
            if (v <= 0.0) continue
            val typical = (c.high + c.low + c.close) / 3.0
            if (!typical.isFinite() || typical <= 0.0) continue
            pv += typical * v
            vol += v
        }
        if (vol <= 0.0) return null
        val result = pv / vol
        return if (result.isFinite() && result > 0.0) result else null
    }

    /**
     * Opening range: (high, low) of the first 3 five-minute candles (09:15–09:30).
     * Null when fewer than 3 candles exist or the numbers are broken.
     */
    fun openingRange(candles: List<Candle>): Pair<Double, Double>? {
        if (candles.size < OPENING_RANGE_CANDLES) return null
        val first = candles.take(OPENING_RANGE_CANDLES)
        val high = first.maxOf { it.high }
        val low = first.minOf { it.low }
        if (!high.isFinite() || !low.isFinite() || low <= 0.0 || high < low) return null
        return high to low
    }

    /**
     * Opening-range breakout state judged from the LAST close:
     * above the range high = [OR_BULLISH], below the range low = [OR_BEARISH],
     * inside = [OR_NEUTRAL]. Also neutral when there is no candle after the
     * opening range yet (no breakout can exist during the range itself) or the
     * range could not be computed.
     */
    fun openingRangeState(candles: List<Candle>): String {
        val range = openingRange(candles) ?: return OR_NEUTRAL
        if (candles.size <= OPENING_RANGE_CANDLES) return OR_NEUTRAL
        val price = candles.last().close
        if (!price.isFinite() || price <= 0.0) return OR_NEUTRAL
        return when {
            price > range.first -> OR_BULLISH
            price < range.second -> OR_BEARISH
            else -> OR_NEUTRAL
        }
    }

    /**
     * Session momentum: percent change of the last close vs the first open,
     * plus the direction (least-squares slope sign) of the last 30 minutes'
     * closes (6 five-minute candles). Null when inputs are unusable.
     */
    fun intradayMomentum(candles: List<Candle>): Momentum? {
        if (candles.isEmpty()) return null
        val firstOpen = candles.first().open
        val lastClose = candles.last().close
        if (!firstOpen.isFinite() || !lastClose.isFinite() || firstOpen <= 0.0 || lastClose <= 0.0) return null
        val pct = (lastClose - firstOpen) / firstOpen * 100.0
        if (!pct.isFinite()) return null

        val tailCloses = candles.takeLast(LAST_30_MIN_CANDLES).map { it.close }
        val slope = if (tailCloses.size >= 2) leastSquaresSlope(tailCloses) else 0.0
        // Flat threshold: less than 0.02% of price per candle is noise, not a trend.
        val flatEps = lastClose * 0.0002
        val direction = when {
            slope > flatEps -> 1
            slope < -flatEps -> -1
            else -> 0
        }
        return Momentum(pct, direction)
    }

    /**
     * Volume surge: average volume of the last 3 candles ÷ session per-candle
     * average. 1.0 = normal, above [SURGE_THRESHOLD] = unusual activity right now.
     * Null when fewer than 4 candles exist or the session traded no volume.
     */
    fun volumeSurge(candles: List<Candle>): Double? {
        if (candles.size < MIN_CANDLES) return null
        val sessionAvg = candles.sumOf { it.volume.toDouble() } / candles.size
        if (sessionAvg <= 0.0) return null
        val last3 = candles.takeLast(3)
        val last3Avg = last3.sumOf { it.volume.toDouble() } / last3.size
        val ratio = last3Avg / sessionAvg
        return if (ratio.isFinite() && ratio >= 0.0) ratio else null
    }

    /**
     * Full intraday read for one symbol. Combines VWAP position, opening-range
     * breakout, session momentum and volume surge into a score in −20..+20:
     *
     *  - Price vs VWAP        max ±7  (≥0.25% away = full, >dead-zone = partial)
     *  - Opening-range state  max ±5
     *  - Momentum + slope     max ±5
     *  - Volume surge ≥1.5×   max ±3  (amplifies the existing lean only — a
     *    surge with no direction earns nothing, it is just churn)
     *
     * Null on any failure — the caller's daily-only path continues unchanged.
     */
    fun read(candles: List<Candle>): IntradayRead? = runCatching {
        if (candles.size < MIN_CANDLES) return null
        val vwapValue = vwap(candles) ?: return null
        val lastClose = candles.last().close
        if (!lastClose.isFinite() || lastClose <= 0.0) return null
        val priceVsVwapPct = (lastClose - vwapValue) / vwapValue * 100.0
        if (!priceVsVwapPct.isFinite()) return null
        val orState = openingRangeState(candles)
        val momentum = intradayMomentum(candles) ?: return null
        val surgeRatio = volumeSurge(candles) ?: 1.0

        var score = 0

        // 1. Price vs VWAP (max ±7)
        score += when {
            priceVsVwapPct >= 0.25 -> 7
            priceVsVwapPct > VWAP_DEAD_ZONE_PCT -> 4
            priceVsVwapPct <= -0.25 -> -7
            priceVsVwapPct < -VWAP_DEAD_ZONE_PCT -> -4
            else -> 0
        }

        // 2. Opening-range breakout (max ±5)
        score += when (orState) {
            OR_BULLISH -> 5
            OR_BEARISH -> -5
            else -> 0
        }

        // 3. Momentum: session change + last-30-min slope (max ±5)
        score += when {
            momentum.pctChange >= 0.3 && momentum.slopeDirection > 0 -> 5
            momentum.pctChange >= 0.3 -> 3
            momentum.pctChange > 0.0 && momentum.slopeDirection > 0 -> 2
            momentum.pctChange <= -0.3 && momentum.slopeDirection < 0 -> -5
            momentum.pctChange <= -0.3 -> -3
            momentum.pctChange < 0.0 && momentum.slopeDirection < 0 -> -2
            else -> 0
        }

        // 4. Volume surge amplifies the lean (max ±3) — never creates one.
        if (surgeRatio >= SURGE_THRESHOLD) {
            score += when {
                score > 0 -> 3
                score < 0 -> -3
                else -> 0
            }
        }

        score = score.coerceIn(-20, 20)

        // Shopkeeper words only (I2): where the price sits + which way it is going.
        val position = when {
            priceVsVwapPct > VWAP_DEAD_ZONE_PCT -> "Price is above today's average"
            priceVsVwapPct < -VWAP_DEAD_ZONE_PCT -> "Price is below today's average"
            else -> "Price is right at today's average"
        }
        val direction = when {
            score >= 12 -> "and moving up strongly right now"
            score >= 5 -> "and moving up right now"
            score <= -12 -> "and falling fast right now"
            score <= -5 -> "and slipping down right now"
            else -> "and moving sideways right now"
        }

        IntradayRead(
            vwapValue = vwapValue,
            priceVsVwapPct = priceVsVwapPct,
            orState = orState,
            momentumPct = momentum.pctChange,
            volumeSurgeRatio = surgeRatio,
            score = score,
            verdictPlain = "$position $direction"
        )
    }.getOrNull()

    /**
     * Least-squares slope of y over x = 0..n−1 (price units per candle).
     * Returns 0.0 when the slope is undefined.
     */
    private fun leastSquaresSlope(y: List<Double>): Double {
        val n = y.size
        if (n < 2) return 0.0
        val xMean = (n - 1) / 2.0
        val yMean = y.average()
        var num = 0.0
        var den = 0.0
        for (i in 0 until n) {
            val dx = i - xMean
            num += dx * (y[i] - yMean)
            den += dx * dx
        }
        if (den == 0.0) return 0.0
        val slope = num / den
        return if (slope.isFinite()) slope else 0.0
    }
}
