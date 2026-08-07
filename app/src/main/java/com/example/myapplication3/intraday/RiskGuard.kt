package com.example.myapplication3.intraday

import java.util.Calendar
import java.util.TimeZone

// ── Result types ──────────────────────────────────────────────────────────────

enum class RiskSeverity { BLOCK, HIGH, MEDIUM, LOW, OK }

data class RiskWarning(
    val message: String,
    val severity: RiskSeverity
)

data class RiskGuardResult(
    val isBlocked: Boolean,
    val warnings: List<RiskWarning>,
    val canTrade: Boolean,
    val primaryMessage: String
)

// ── RiskGuard engine ──────────────────────────────────────────────────────────

/**
 * Stateless intraday risk guard.
 *
 * Call [evaluate] before placing any intraday order. The engine checks seven
 * rules and returns a [RiskGuardResult] that tells the UI whether trading is
 * allowed and which warnings to show.
 *
 * All time-based rules operate in IST (Asia/Kolkata).
 */
object RiskGuard {

    private const val IST = "Asia/Kolkata"

    // Holiday awareness — without this the guard said "All clear, you can trade"
    // at 10 AM on Diwali. MarketCalendar has a no-arg constructor, safe to hold here.
    private val marketCalendar = com.example.myapplication3.core.common.MarketCalendar()

    // Thresholds
    private const val OPEN_HOUR = 9             // 9:15 AM — market open
    private const val OPEN_MINUTE = 15
    private const val STABLE_HOUR = 9           // 9:30 AM — opening volatility over (H8)
    private const val STABLE_MINUTE = 30
    private const val BLOCK_HOUR = 15           // 3:00 PM — hard block
    private const val BLOCK_MINUTE = 0

    /**
     * H8 opening-volatility gate: the one plain-words reason shown while fresh
     * BUY signals are blocked between 9:15 and 9:30 IST. Single-sourced here so
     * the guard and the buy-button gating can never word it differently.
     */
    const val OPENING_WINDOW_MESSAGE =
        "The market just opened - wait till 9:30, the first minutes are jumpy."
    private const val WARN_HOUR = 14            // 2:30 PM — orange warning start
    private const val WARN_MINUTE = 30
    private const val CLOSED_HOUR = 15          // 3:30 PM — market fully closed
    private const val CLOSED_MINUTE = 30

    private const val STRONG_MOVE_THRESHOLD = 5.0      // Rule 3: >5% from prev close
    private const val LOW_VOLUME_RATIO = 0.50           // Rule 4: <50% of avg volume
    private const val GAP_THRESHOLD = 3.0               // Rule 5: >3% gap up/down
    private const val MAX_STOP_LOSS_PCT = 3.0           // Rule 6: SL >3%
    private const val HIGH_VIX_THRESHOLD = 20.0         // Rule 7: VIX >20

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Evaluate all seven risk rules for an intraday trade.
     *
     * @param changePercent  Percentage change from previous close (signed).
     *                       Positive = stock up, negative = stock down.
     * @param volume         Today's traded volume so far.
     * @param avgVolume      Historical average daily volume (e.g. 20-day avg).
     *                       Pass 0 to skip the volume rule.
     * @param stopLossPct    Proposed stop-loss distance as a positive percentage
     *                       (e.g. 2.5 for a 2.5 % SL).
     * @param vix            India VIX value. Pass 0.0 to skip Rule 7.
     * @param gapPercent     Gap-up/gap-down percentage from previous close to
     *                       today's open (signed). Pass 0.0 to skip Rule 5.
     */
    fun evaluate(
        changePercent: Double,
        volume: Long,
        avgVolume: Long,
        stopLossPct: Double,
        vix: Double = 0.0,
        gapPercent: Double = 0.0
    ): RiskGuardResult {

        // ── Rule 0: Market closed — weekend, after 3:30 PM, or before 9:15 AM ─
        // Without this the engine says "All clear, you can trade" on a Sunday.
        if (isMarketClosed() || isBeforeOpen()) {
            return RiskGuardResult(
                isBlocked = true,
                warnings = listOf(
                    RiskWarning("Market is closed now", RiskSeverity.BLOCK)
                ),
                canTrade = false,
                primaryMessage = "Market is closed now"
            )
        }

        // ── Rule 0b (H8): Opening volatility — 9:15–9:30 IST → hard block ────
        // The first minutes whip around on opening auctions and gap chasing; no
        // fresh BUY signal may fire until 9:30. Same plain reason everywhere.
        if (isOpeningVolatilityWindow()) {
            return RiskGuardResult(
                isBlocked = true,
                warnings = listOf(
                    RiskWarning(OPENING_WINDOW_MESSAGE, RiskSeverity.BLOCK)
                ),
                canTrade = false,
                primaryMessage = OPENING_WINDOW_MESSAGE
            )
        }

        val warnings = mutableListOf<RiskWarning>()

        // ── Rule 1: Time > 3:00 PM IST → hard block ───────────────────────────
        if (isPastBlockTime()) {
            return RiskGuardResult(
                isBlocked = true,
                warnings = listOf(
                    RiskWarning("Trading time over, try tomorrow", RiskSeverity.BLOCK)
                ),
                canTrade = false,
                primaryMessage = "Trading time over, try tomorrow"
            )
        }

        // ── Rule 2: 2:30 PM – 3:00 PM → orange warning ───────────────────────
        if (isNearClose()) {
            warnings += RiskWarning("Very little time left to trade", RiskSeverity.HIGH)
        }

        // ── Rule 3: Stock moved >5% from prev close ────────────────────────────
        if (Math.abs(changePercent) > STRONG_MOVE_THRESHOLD) {
            warnings += RiskWarning("Stock has already moved a lot today", RiskSeverity.HIGH)
        }

        // ── Rule 4: Volume < 50% of average ───────────────────────────────────
        // I2 plain words: "low volume" means nothing to a beginner.
        if (avgVolume > 0L && volume < avgVolume * LOW_VOLUME_RATIO) {
            warnings += RiskWarning("Very few buyers and sellers right now - trade carefully", RiskSeverity.MEDIUM)
        }

        // ── Rule 5: Gap Up/Down > 3% ───────────────────────────────────────────
        // I2 plain words: "gap" is analyst talk — say what actually happened.
        if (Math.abs(gapPercent) > GAP_THRESHOLD) {
            warnings += RiskWarning("Opened far from yesterday's price - be careful", RiskSeverity.HIGH)
        }

        // ── Rule 6: Stop Loss > 3% ────────────────────────────────────────────
        if (stopLossPct > MAX_STOP_LOSS_PCT) {
            warnings += RiskWarning("Stop loss is too wide", RiskSeverity.MEDIUM)
        }

        // ── Rule 7: India VIX > 20 ────────────────────────────────────────────
        // I2 plain words: "volatile" (VIX) becomes "jumpy" — the app's one word for it.
        if (vix > HIGH_VIX_THRESHOLD) {
            warnings += RiskWarning("The market is very jumpy today", RiskSeverity.HIGH)
        }

        // ── Determine overall tradability ─────────────────────────────────────
        val hasBlocker = warnings.any { it.severity == RiskSeverity.BLOCK }
        // B4 fix: low-volume and wide-stop are MEDIUM severity, but they are still
        // two of the five risky conditions B4 names — so they must be able to feed
        // the "3+ serious warnings = full block" stack. Count HIGH and MEDIUM
        // warnings together as "serious": any 3 combining trips the automatic block,
        // so a stock with very low volume + a wide stop-loss + one HIGH condition
        // (three of B4's conditions) is now blocked, not left buyable.
        val seriousCount = warnings.count {
            it.severity == RiskSeverity.HIGH || it.severity == RiskSeverity.MEDIUM
        }

        // Block on any explicit blocker, or when 3+ serious warnings stack up (B4).
        val isBlocked  = hasBlocker || seriousCount >= 3
        val canTrade   = !isBlocked

        val primaryMessage = when {
            isBlocked  -> warnings.firstOrNull { it.severity == RiskSeverity.BLOCK }?.message
                              ?: warnings.first().message
            warnings.isNotEmpty() -> warnings.first().message
            else -> "All clear, you can trade"
        }

        return RiskGuardResult(
            isBlocked = isBlocked,
            warnings = warnings.toList(),
            canTrade = canTrade,
            primaryMessage = primaryMessage
        )
    }

    /**
     * Returns **true** after 3:30 PM IST or on Saturday / Sunday.
     * Use this to hide the entire intraday section outside market hours.
     */
    fun isMarketClosed(): Boolean {
        val cal = istCalendar()
        val dow = cal.get(Calendar.DAY_OF_WEEK)
        if (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY) return true
        if (marketCalendar.isHoliday()) return true   // NSE trading holiday

        val totalMinutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val closeMinutes = CLOSED_HOUR * 60 + CLOSED_MINUTE
        return totalMinutes >= closeMinutes
    }

    /** True during NSE trading hours (9:15 AM – 3:30 PM IST) on a trading day. */
    fun isTradingHoursNow(): Boolean = !isMarketClosed() && !isBeforeOpen()

    /**
     * H8: true during the opening-volatility window — 9:15 to 9:30 AM IST on a
     * trading day. Fresh BUY signals are blocked here; the UI must gate its buy
     * button on this too, with [OPENING_WINDOW_MESSAGE] as the one reason.
     */
    fun isOpeningVolatilityWindow(): Boolean {
        if (isMarketClosed() || isBeforeOpen()) return false
        val cal = istCalendar()
        val totalMinutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        return totalMinutes < STABLE_HOUR * 60 + STABLE_MINUTE
    }

    /**
     * "today 9:15 AM" / "tomorrow 9:15 AM" / "Monday 9:15 AM" — for the honest
     * blocked-state line "Market closed — opens …" (B4/B9), holiday-aware.
     */
    fun nextMarketOpenLabel(): String {
        val openMs = marketCalendar.nextMarketOpenMs()
        val open = istCalendar().apply { timeInMillis = openMs }
        val now = istCalendar()
        val sameDay = { a: Calendar, b: Calendar ->
            a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
            a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
        }
        val tomorrow = istCalendar().apply { add(Calendar.DAY_OF_MONTH, 1) }
        val dayWord = when {
            sameDay(now, open)      -> "today"
            sameDay(tomorrow, open) -> "tomorrow"
            else -> java.text.SimpleDateFormat("EEEE", java.util.Locale.ENGLISH)
                .apply { timeZone = TimeZone.getTimeZone(IST) }
                .format(java.util.Date(openMs))
        }
        return "$dayWord 9:15 AM"
    }

    /**
     * Returns **true** when the time is in the 2:30 PM – 3:30 PM IST window
     * (i.e. the last hour of the trading session — use to show a time warning).
     */
    fun isNearClose(): Boolean {
        val cal = istCalendar()
        val totalMinutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val warnStart  = WARN_HOUR  * 60 + WARN_MINUTE   // 14:30
        val closeEnd   = CLOSED_HOUR * 60 + CLOSED_MINUTE // 15:30
        return totalMinutes in warnStart until closeEnd
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /** Returns true when current IST time is before the 9:15 AM market open. */
    private fun isBeforeOpen(): Boolean {
        val cal = istCalendar()
        val totalMinutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val openMinutes = OPEN_HOUR * 60 + OPEN_MINUTE
        return totalMinutes < openMinutes
    }

    /** Returns true when current IST time is at or past 3:00 PM. */
    private fun isPastBlockTime(): Boolean {
        val cal = istCalendar()
        val totalMinutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val blockMinutes = BLOCK_HOUR * 60 + BLOCK_MINUTE
        return totalMinutes >= blockMinutes
    }

    private fun istCalendar(): Calendar =
        Calendar.getInstance(TimeZone.getTimeZone(IST))
}