package com.example.myapplication3.intraday

import com.example.myapplication3.backtesting.engine.BacktestEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * E3a "simple-trust line" provider.
 *
 * Turns the honest quick backtest ([BacktestEngine.quickWinRate], pure math over
 * the 1-year daily closes the app already carries on every quote) into the ONE
 * plain-English sentence the spec allows the user to see:
 *
 *     "This kind of pick was right 7 out of 10 times recently."
 *
 * The user NEVER sees backtest jargon — no "backtest", "win rate", "Sharpe",
 * or "Monte Carlo" ever reaches the screen through this class.
 *
 * Honesty guarantees (A5/B6):
 *  - Numbers come only from real simulated trades on real closes. Fewer than
 *    5 trades, short history, or ANY error => null, and null means the UI
 *    shows nothing. Never a made-up or padded number.
 *  - Wins are judged AFTER round-trip charges (the same B0.3a cost model the
 *    live engine uses), so the count matches what a real wallet would feel.
 *  - The time claim matches the data: "last year" ONLY when the simulation
 *    really scanned ~a full trading year ([BacktestEngine.QuickStats.coversFullYear]);
 *    with the usual 1-year quote history the scan is shorter, so it says
 *    "recently" — never a wider claim than the math supports.
 *  - Deterministic: same closes on the same day => same line.
 *
 * Fail-safe (B8): pure CPU work on already-fetched data — no network, no disk,
 * no Angel One dependency. Any unexpected failure returns null and the
 * existing daily-data path continues unchanged.
 *
 * Caching: in-memory, per symbol, per IST (Asia/Kolkata) calendar day — the
 * market's day, matching the rest of the app, so the cache rolls over at IST
 * midnight and not at some UTC boundary mid-evening. Daily closes do not
 * change intraday, so one computation per symbol per day is enough. Results
 * computed from insufficient history are NOT cached, so a later call the same
 * day with the full 1-year history still gets a real answer.
 *
 * UI wiring (later wave): decision card's "See more" calls
 * `trustLineFor(signal.stockSymbol, quote.historicalCloses)` and shows the
 * returned line verbatim iff non-null.
 */
@Singleton
class TrustLineProvider @Inject constructor() {

    private data class CachedLine(val dayKey: Long, val line: String?)

    /** symbol (normalized) -> today's computed line (null line = honestly "say nothing"). */
    private val cache = ConcurrentHashMap<String, CachedLine>()

    /** Minimum closes for quickWinRate — single-sourced from the engine. */
    private val minCloses = BacktestEngine.QUICK_MIN_BARS

    /**
     * @param symbol the stock symbol (with or without ".NS" — normalized internally)
     * @param closes 1-year daily closes, oldest first (e.g. StockQuote.historicalCloses)
     * @return the one-line trust sentence, or null when there is nothing honest
     *         to say (insufficient history, fewer than 5 simulated trades, or
     *         any internal error). Null must render as NOTHING in the UI.
     */
    suspend fun trustLineFor(symbol: String, closes: List<Double>): String? {
        return runCatching {
            val key = symbol.trim().uppercase().removeSuffix(".NS")
            if (key.isEmpty()) return@runCatching null
            val today = istDayKey()

            cache[key]?.let { if (it.dayKey == today) return@runCatching it.line }

            // Not enough history to even attempt the simulation — do NOT cache,
            // a fuller quote later today should still get a real computation.
            if (closes.size < minCloses) return@runCatching null

            val line = withContext(Dispatchers.Default) {
                // Same round-trip cost model the live engine applies (B0.3a) —
                // passed in here because the backtesting module cannot depend
                // on this package. A "win" that charges would eat is a loss.
                BacktestEngine.quickWinRate(
                    closes,
                    roundTripCostFraction = INTRADAY_COST_RATE + SLIPPAGE_BUFFER_RATE
                )?.let { stats ->
                    if (stats.total >= 5) {
                        // B6: claim "last year" only when the scan truly covered one.
                        val period = if (stats.coversFullYear) "last year" else "recently"
                        "This kind of pick was right ${stats.wins} out of ${stats.total} times $period"
                    } else null
                }
            }

            cache[key] = CachedLine(today, line)
            line
        }.getOrElse { e ->
            // Never swallow coroutine cancellation; everything else fails safe to null.
            if (e is kotlinx.coroutines.CancellationException) throw e
            null
        }
    }

    /**
     * Day key in IST (Asia/Kolkata) — the market's calendar day, same zone the
     * rest of the app uses. java.util.Calendar (not java.time): minSdk 24 has
     * no desugaring here.
     */
    private fun istDayKey(): Long {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone(IST))
        return cal.get(java.util.Calendar.YEAR) * 1000L + cal.get(java.util.Calendar.DAY_OF_YEAR)
    }

    private companion object {
        const val IST = "Asia/Kolkata"
    }
}
