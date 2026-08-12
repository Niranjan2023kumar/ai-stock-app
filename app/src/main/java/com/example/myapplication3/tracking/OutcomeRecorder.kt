package com.example.myapplication3.tracking

import com.example.myapplication3.analytics.tracker.PerformanceTracker
import com.example.myapplication3.analytics.tracker.TradeOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Structured after-loss info for the E1c trust card (P0 #1). [lossAmount] is a
 * POSITIVE rupee figure (net of round-trip charges); [message] is the ready, TRUE
 * one-liner the UI can render or speak as-is.
 */
data class AfterLossInfo(
    val symbol: String,
    val lossAmount: Double,
    val message: String
)

/**
 * Closes the H13 learning loop: every REAL closed trade is recorded durably, and the
 * app can honestly answer "how often were picks like this right recently?".
 *
 * Honesty rules (A5/B6), enforced here:
 *  - A trust line is produced ONLY from >= [MIN_SAMPLES] real outcomes. Below that: null.
 *    The UI must show nothing rather than an invented number.
 *  - Practice trades are never recorded (callers skip them) — fake fills must not
 *    inflate the real track record.
 *  - Everything is fail-safe: any storage error means "no data" (null/empty),
 *    never a crash and never a made-up answer.
 */
@Singleton
class OutcomeRecorder @Inject constructor(
    private val performanceTracker: PerformanceTracker
) {

    /**
     * Record one closed REAL trade. [confidenceAtPick] is on the 0–100 scale the
     * signal engine uses; pass null when the confidence at pick time is unknown
     * (TrackedTradeEntity does not persist it yet — a future wave will). A value
     * given as a 0–1 fraction is normalized to 0–100 defensively.
     * Fail-safe: never throws.
     */
    suspend fun record(
        symbol: String,
        confidenceAtPick: Double?,
        action: String,
        netPnl: Double,
        closedAt: Long = System.currentTimeMillis()
    ) {
        try {
            val normalized = confidenceAtPick?.let { c ->
                if (c in 0.0..1.0) c * 100.0 else c
            }?.takeIf { it in 0.0..100.0 }
            withContext(Dispatchers.IO) {
                performanceTracker.recordTradeOutcome(
                    TradeOutcome(
                        symbol = symbol,
                        confidenceAtPick = normalized,
                        action = action,
                        netPnl = netPnl,
                        win = netPnl > 0.0,
                        closedAt = closedAt
                    )
                )
            }
        } catch (_: Exception) {
            // Losing one learning sample must never break the close-trade flow.
        }
    }

    /**
     * Rolling calibration table over the last 90 days:
     * bucket ("70-79" | "80-89" | "90+") -> (wins, total).
     * Only outcomes that carry a confidence can be bucketed; buckets with zero
     * outcomes are omitted. Fail-safe: empty map on any error.
     */
    suspend fun calibration(): Map<String, Pair<Int, Int>> = try {
        withContext(Dispatchers.IO) {
            val since = System.currentTimeMillis() - WINDOW_MS
            performanceTracker.getTradeOutcomes(since)
                .mapNotNull { o -> bucketOf(o.confidenceAtPick)?.let { it to o.win } }
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, wins) -> wins.count { it } to wins.size }
        }
    } catch (_: Exception) {
        emptyMap()
    }

    /**
     * Honest one-liner for the pick card, e.g.
     * "Picks like this were right 7 of 10 times recently".
     *
     * With a [confidence] (0–100): uses that confidence bucket's last-90-day record,
     * and only speaks when the bucket has >= [MIN_SAMPLES] outcomes. Without one
     * (today's trades store no confidence yet): falls back to the overall last-90-day
     * real record, same >= [MIN_SAMPLES] minimum, with wording that does not pretend
     * to be pick-specific. Anything less: null — NEVER invent a track record.
     *
     * Suspend + Dispatchers.IO like [record]/[calibration]: the tracker reads
     * prefs-backed storage, which must never run on the main thread.
     */
    suspend fun trustLine(confidence: Double? = null): String? = try {
        withContext(Dispatchers.IO) {
            val since = System.currentTimeMillis() - WINDOW_MS
            val outcomes = performanceTracker.getTradeOutcomes(since)
            if (confidence != null) {
                val bucket = bucketOf(confidence)
                if (bucket == null) null else {
                    val inBucket = outcomes.filter { bucketOf(it.confidenceAtPick) == bucket }
                    if (inBucket.size >= MIN_SAMPLES) {
                        val wins = inBucket.count { it.win }
                        "Picks like this were right $wins of ${inBucket.size} times recently"
                    } else null
                }
            } else {
                if (outcomes.size >= MIN_SAMPLES) {
                    val wins = outcomes.count { it.win }
                    "Your closed trades were right $wins of ${outcomes.size} times recently"
                } else null
            }
        }
    } catch (e: CancellationException) {
        throw e     // never swallow coroutine cancellation
    } catch (_: Exception) {
        null
    }

    /**
     * Win rate (0.0–1.0) over the last 10 REAL outcomes — the H13 auto-caution input.
     * Honest minimum: null until at least [MIN_SAMPLES] outcomes exist. This wave only
     * measures; no behavior is wired to it yet.
     *
     * Suspend + Dispatchers.IO like [record]/[calibration]: prefs-backed reads
     * must never run on the main thread.
     */
    suspend fun recentWinRate(): Double? = try {
        withContext(Dispatchers.IO) {
            val recent = performanceTracker.getTradeOutcomes()
                .sortedByDescending { it.closedAt }
                .take(RECENT_COUNT)
            if (recent.size >= MIN_SAMPLES) recent.count { it.win }.toDouble() / recent.size else null
        }
    } catch (e: CancellationException) {
        throw e     // never swallow coroutine cancellation
    } catch (_: Exception) {
        null
    }

    /**
     * After-loss trust line (E1c / P0 #1): honest, protection-framed text for the LAST
     * REAL closed trade, or null when that close was a profit/breakeven (or there is no
     * closed trade yet). Kept TRUE (A5/B6): it states the REAL realized loss (net of
     * charges) and frames closing the position as the protection that capped it — it
     * NEVER invents a "saved ₹Y" number, because how much further the price would have
     * fallen is unknowable.
     *
     * [sinceMs] scopes which closes count: default 0L = the single most recent real
     * close of all time; pass the start-of-today millis to reassure only about a loss
     * that happened TODAY (recommended for the daily-resetting E1c card, so yesterday's
     * loss never resurfaces on a new day).
     *
     * There is no per-trade id here: [PerformanceTracker] stores outcomes without a
     * trade id, so this keys on "latest" (by close time), not a specific tradeId. Use
     * [afterLossInfo] for the structured symbol + ₹ amount when the UI renders its own
     * layout (the shipped E1c card takes the ₹ amount).
     *
     * Suspend + Dispatchers.IO like the rest of this class: prefs-backed reads must
     * never run on the main thread. Fail-safe: null on any error.
     */
    suspend fun afterLossMessage(sinceMs: Long = 0L): String? = afterLossInfo(sinceMs)?.message

    /**
     * Structured form of [afterLossMessage]: the last real closed trade's symbol, its
     * realized net loss as a POSITIVE ₹ amount, and the ready E1c line. Null when the
     * last close was a profit/breakeven or there is no data. See [afterLossMessage]
     * for [sinceMs] semantics.
     */
    suspend fun afterLossInfo(sinceMs: Long = 0L): AfterLossInfo? = try {
        withContext(Dispatchers.IO) {
            val last = performanceTracker.getTradeOutcomes(sinceMs).maxByOrNull { it.closedAt }
            if (last == null || last.netPnl >= 0.0) null
            else {
                val amount = kotlin.math.abs(last.netPnl)
                AfterLossInfo(
                    symbol = last.symbol,
                    lossAmount = amount,
                    message = "Your safety stop worked on ${last.symbol} — it closed the trade at a " +
                        "₹${formatRupeesIndian(amount)} loss, instead of a bigger loss. " +
                        "The system is working. Tomorrow is a new day."
                )
            }
        }
    } catch (e: CancellationException) {
        throw e     // never swallow coroutine cancellation
    } catch (_: Exception) {
        null
    }

    /** Whole-rupee amount with Indian (lakh/crore) grouping, e.g. 1,234 / 1,00,000 (U9.2). */
    private fun formatRupeesIndian(amount: Double): String =
        java.text.NumberFormat.getIntegerInstance(java.util.Locale("en", "IN"))
            .format(Math.round(kotlin.math.abs(amount)))

    /** 0–100 confidence -> calibration bucket, or null below 70 / unknown. */
    private fun bucketOf(confidence: Double?): String? = when {
        confidence == null -> null
        confidence >= 90.0 -> BUCKET_90_PLUS
        confidence >= 80.0 -> BUCKET_80_89
        confidence >= 70.0 -> BUCKET_70_79
        else -> null
    }

    private companion object {
        val WINDOW_MS: Long = TimeUnit.DAYS.toMillis(90)
        const val MIN_SAMPLES = 5      // honest minimum before the app claims anything
        const val RECENT_COUNT = 10    // "last 10 real outcomes" (H13)
        const val BUCKET_70_79 = "70-79"
        const val BUCKET_80_89 = "80-89"
        const val BUCKET_90_PLUS = "90+"
    }
}
