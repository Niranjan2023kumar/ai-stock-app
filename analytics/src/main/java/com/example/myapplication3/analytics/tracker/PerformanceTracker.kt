package com.example.myapplication3.analytics.tracker

import android.content.Context
import android.content.SharedPreferences
import com.example.myapplication3.core.domain.model.RecommendationOutcome
import com.example.myapplication3.core.domain.model.RecommendationStatus
import com.example.myapplication3.database.dao.RecommendationDao
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

data class PerformanceStats(
    val totalRecommendations: Int,
    val wins: Int,
    val losses: Int,
    val winRate: Double,
    val averageConfidenceOnWins: Double,
    val averageConfidenceOnLosses: Double,
    val calibrationScore: Double
)

/**
 * One REAL closed trade the app learns from (H13). Stored durably so the
 * calibration table ("picks like this were right 7 of 10 times") survives
 * restarts and reinstalls of the process, not just the session.
 *
 * [confidenceAtPick] is on the 0–100 scale the signal engine uses. It is null
 * when the trade was tracked before confidence persistence existed (the
 * TrackedTrade entity does not carry confidence yet) — null-confidence
 * outcomes still count for overall win rate but can never enter a
 * confidence bucket. NEVER invent a confidence for them.
 */
data class TradeOutcome(
    val symbol: String,
    val confidenceAtPick: Double?,
    val action: String,
    val netPnl: Double,          // NET of round-trip charges (B0.3a truth)
    val win: Boolean,
    val closedAt: Long
)

@Singleton
class PerformanceTracker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val recommendationDao: RecommendationDao
) {

    private val lock = Any()

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    suspend fun recordOutcome(recommendationId: String, outcome: RecommendationOutcome) {
        val status = when (outcome) {
            RecommendationOutcome.WIN -> RecommendationStatus.TARGET1_HIT
            RecommendationOutcome.LOSS -> RecommendationStatus.STOPPED_OUT
            RecommendationOutcome.BREAKEVEN -> RecommendationStatus.EXPIRED
        }
        recommendationDao.updateStatus(
            id = recommendationId,
            status = status.name,
            outcome = outcome.name,
            updatedAt = System.currentTimeMillis()
        )
    }

    /**
     * Durably store one closed-trade outcome (H13 learning loop). Appends to a
     * SharedPreferences JSON array, keeping the most recent [MAX_OUTCOMES].
     * Fail-safe: any storage/JSON error is swallowed — recording an outcome
     * must NEVER break the close-trade flow that calls it.
     */
    fun recordTradeOutcome(outcome: TradeOutcome) {
        try {
            synchronized(lock) {
                val existing = readOutcomesLocked().toMutableList()
                existing.add(outcome)
                // Keep newest MAX_OUTCOMES by close time so the file can't grow forever.
                val trimmed = existing.sortedBy { it.closedAt }.takeLast(MAX_OUTCOMES)
                val arr = JSONArray()
                trimmed.forEach { o ->
                    arr.put(JSONObject().apply {
                        put(KEY_SYMBOL, o.symbol)
                        if (o.confidenceAtPick != null) put(KEY_CONFIDENCE, o.confidenceAtPick)
                        put(KEY_ACTION, o.action)
                        put(KEY_NET_PNL, o.netPnl)
                        put(KEY_WIN, o.win)
                        put(KEY_CLOSED_AT, o.closedAt)
                    })
                }
                prefs.edit().putString(KEY_OUTCOMES, arr.toString()).apply()
            }
        } catch (_: Exception) {
            // Fail-safe by contract: losing one learning sample is acceptable;
            // breaking the user's trade-close flow is not.
        }
    }

    /**
     * All stored outcomes closed at/after [sinceMs], oldest first.
     * Fail-safe: returns empty on any parse/storage error.
     */
    fun getTradeOutcomes(sinceMs: Long = 0L): List<TradeOutcome> = try {
        synchronized(lock) { readOutcomesLocked() }
            .filter { it.closedAt >= sinceMs }
            .sortedBy { it.closedAt }
    } catch (_: Exception) {
        emptyList()
    }

    /** Must be called while holding [lock]. Skips (never throws on) corrupt entries. */
    private fun readOutcomesLocked(): List<TradeOutcome> {
        val raw = prefs.getString(KEY_OUTCOMES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                try {
                    val o = arr.getJSONObject(i)
                    TradeOutcome(
                        symbol = o.getString(KEY_SYMBOL),
                        confidenceAtPick =
                            if (o.has(KEY_CONFIDENCE)) o.getDouble(KEY_CONFIDENCE) else null,
                        action = o.optString(KEY_ACTION, "BUY"),
                        netPnl = o.getDouble(KEY_NET_PNL),
                        win = o.getBoolean(KEY_WIN),
                        closedAt = o.getLong(KEY_CLOSED_AT)
                    )
                } catch (_: Exception) {
                    null // one corrupt entry must not poison the rest
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun getCalibration(): PerformanceStats {
        // Real query for all recommendations that have an outcome. The old
        // getBySymbol("", 1000) matched no rows (no recommendation has an empty
        // symbol), so calibration stats were permanently zero.
        val withOutcome = recommendationDao.getWithOutcome()

        if (withOutcome.isEmpty()) {
            return PerformanceStats(0, 0, 0, 0.0, 0.0, 0.0, 0.0)
        }

        val wins = withOutcome.filter { it.outcome == RecommendationOutcome.WIN.name }
        val losses = withOutcome.filter { it.outcome == RecommendationOutcome.LOSS.name }
        val winRate = wins.size.toDouble() / withOutcome.size
        val avgConfWins = wins.map { it.confidenceScore }.average().takeIf { wins.isNotEmpty() } ?: 0.0
        val avgConfLosses = losses.map { it.confidenceScore }.average().takeIf { losses.isNotEmpty() } ?: 0.0
        val calibration = if (avgConfWins > avgConfLosses) (avgConfWins - avgConfLosses) else 0.0

        return PerformanceStats(
            totalRecommendations = withOutcome.size,
            wins = wins.size,
            losses = losses.size,
            winRate = winRate,
            averageConfidenceOnWins = avgConfWins,
            averageConfidenceOnLosses = avgConfLosses,
            calibrationScore = calibration
        )
    }

    private companion object {
        const val PREFS_NAME = "performance_outcomes"
        const val KEY_OUTCOMES = "outcomes_v1"
        const val MAX_OUTCOMES = 500

        const val KEY_SYMBOL = "symbol"
        const val KEY_CONFIDENCE = "confidenceAtPick"
        const val KEY_ACTION = "action"
        const val KEY_NET_PNL = "netPnl"
        const val KEY_WIN = "win"
        const val KEY_CLOSED_AT = "closedAt"
    }
}
