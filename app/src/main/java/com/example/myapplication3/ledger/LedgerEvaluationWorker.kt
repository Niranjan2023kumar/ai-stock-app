package com.example.myapplication3.ledger

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import com.example.myapplication3.core.common.MarketCalendar
import com.example.myapplication3.intraday.IntradayRepository
import com.example.myapplication3.intraday.RiskGuard
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Daily after-close judge for the suggestion record (mirrors the
 * [com.example.myapplication3.mutualfunds.SipReminderWorker] pattern: a small
 * daily @HiltWorker that keeps one promise even when the app stays closed).
 *
 * Once per day, AFTER 3:35 PM IST on a trading day (weekday, not an NSE
 * holiday, session over), it looks for PENDING rows in BOTH ledgers — Stock
 * and Intraday, judged separately, never mixed — fetches each pending
 * symbol's REAL close / day-high / day-low from the Yahoo v8 chart (via
 * [IntradayRepository.fetchStockResearch], the one public API that returns
 * all three), and lets [SuggestionLedger.evaluatePending] mark PASS or FAIL.
 *
 * Strictly fail-safe (B8): every failure path — wrong time, holiday, no
 * pending rows, Yahoo down, one symbol failing — ends in Result.success()
 * with rows either correctly judged or left PENDING for the next run.
 * A verdict is NEVER invented from missing data (A5/B6).
 */
@HiltWorker
class LedgerEvaluationWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val ledger: SuggestionLedger,
    private val intradayRepository: IntradayRepository,
    private val marketCalendar: MarketCalendar
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        runCatching {
            if (!isAfterCloseOnTradingDay()) return Result.success()

            // Pending rows from BOTH tabs (dated today-or-earlier by
            // construction) — one combined symbol list, one fetch per symbol.
            val pending =
                (ledger.readRows(SuggestionLedger.TAB_STOCK) +
                    ledger.readRows(SuggestionLedger.TAB_INTRADAY))
                    .filter { it.result == SuggestionLedger.RESULT_PENDING }
            if (pending.isEmpty()) return Result.success()

            val symbols = pending.asSequence()
                .map { it.symbol.trim().removeSuffix(".NS").uppercase() }
                .filter { it.isNotEmpty() }
                .distinct()
                .toList()

            // Real end-of-day prices: close, day-high, day-low per symbol.
            // A symbol that fails is simply skipped — its rows stay PENDING.
            val quotes = HashMap<String, Triple<Double, Double, Double>>()
            for (symbol in symbols) {
                val data = runCatching { intradayRepository.fetchStockResearch(symbol) }
                    .getOrNull() ?: continue
                if (data.error != null) continue
                if (data.currentPrice <= 0.0) continue
                quotes[symbol] = Triple(data.currentPrice, data.dayHigh, data.dayLow)
            }
            if (quotes.isEmpty()) return Result.success() // Yahoo down — judge nothing blind

            // Both records, evaluated SEPARATELY — each writes only its own file.
            ledger.evaluatePending(SuggestionLedger.TAB_STOCK, quotes)
            ledger.evaluatePending(SuggestionLedger.TAB_INTRADAY, quotes)
        }
        return Result.success()
    }

    /**
     * True only after 3:35 PM IST on a weekday that is not an NSE holiday,
     * with RiskGuard agreeing the session is over — the moment the day's
     * final close / high / low are real, settled numbers.
     */
    private fun isAfterCloseOnTradingDay(): Boolean {
        val cal = Calendar.getInstance(TimeZone.getTimeZone(IST))
        val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
        if (dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY) return false
        if (runCatching { marketCalendar.isHoliday() }.getOrDefault(false)) return false
        val totalMinutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        if (totalMinutes < EVAL_HOUR * 60 + EVAL_MINUTE) return false
        // Belt and braces — RiskGuard is IST + weekend + holiday aware too.
        return !RiskGuard.isTradingHoursNow()
    }

    companion object {
        const val WORK_NAME = "ledger_eval"

        private const val IST = "Asia/Kolkata"
        private const val EVAL_HOUR = 15   // judge only after 15:35 IST —
        private const val EVAL_MINUTE = 35 // close (15:30) plus settle margin

        /** Daily check; needs the network to fetch real closing prices. */
        fun buildPeriodicRequest(): PeriodicWorkRequest =
            PeriodicWorkRequestBuilder<LedgerEvaluationWorker>(24, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
    }
}
