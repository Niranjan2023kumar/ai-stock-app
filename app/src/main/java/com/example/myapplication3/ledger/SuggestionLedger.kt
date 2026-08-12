package com.example.myapplication3.ledger

import android.content.Context
import com.example.myapplication3.intraday.SignalAction
import com.example.myapplication3.intraday.TradingSignal
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One row of the honest suggestion record. Numbers are 0.0 / blank while the
 * row is still PENDING (closePrice/dayHigh/dayLow are only known after the
 * market closes and [SuggestionLedger.evaluatePending] fills them in).
 */
data class LedgerRow(
    val date: String,           // IST calendar day, yyyy-MM-dd
    val tab: String,            // STOCK | INTRADAY — belt-and-braces copy of which file this is
    val symbol: String,
    val name: String,
    val action: String,         // BUY | SELL
    val suggestedPrice: Double, // the entry price the app suggested that day
    val target: Double,
    val stopLoss: Double,
    val confidence: Int,
    val closePrice: Double,     // 0.0 while PENDING
    val dayHigh: Double,        // 0.0 while PENDING
    val dayLow: Double,         // 0.0 while PENDING
    val result: String,         // PENDING | PASS | FAIL
    val reason: String          // plain English, e.g. "target hit" / "stop hit"
)

/**
 * The daily PASS/FAIL record of the app's own suggestions (U9.6: "shows its
 * real weekly record"; honesty rules A5/B6).
 *
 * TWO physically separate CSV files in [Context.getFilesDir] — one for the
 * Stock tab, one for the Intraday tab — so the two records can NEVER mix:
 *   • suggestions_stock.csv
 *   • suggestions_intraday.csv
 * Both are plain comma-separated text with a header row, so they open
 * directly in Excel / Google Sheets (share via [exportCopy]).
 *
 * Flow: the day's suggested stocks are appended once per (date, symbol) with
 * result=PENDING; after market close [LedgerEvaluationWorker] fetches the real
 * close / day-high / day-low and [evaluatePending] marks each row PASS or FAIL
 * with a plain-English reason. Results are judged ONLY against real fetched
 * prices — a row whose prices could not be fetched stays PENDING (never
 * invented, B6). Every entry point is Dispatchers.IO + runCatching and a
 * single [Mutex] serializes all file access (B8 fail-safe: worst case the
 * record simply doesn't update this run; it is never corrupted).
 */
@Singleton
class SuggestionLedger @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        const val TAB_STOCK = "STOCK"
        const val TAB_INTRADAY = "INTRADAY"

        const val RESULT_PENDING = "PENDING"
        const val RESULT_PASS = "PASS"
        const val RESULT_FAIL = "FAIL"

        const val FILE_STOCK = "suggestions_stock.csv"
        const val FILE_INTRADAY = "suggestions_intraday.csv"

        private const val HEADER =
            "date,tab,symbol,name,action,suggestedPrice,target,stopLoss," +
                "confidence,closePrice,dayHigh,dayLow,result,reason"

        private val DATE_REGEX = Regex("""\d{4}-\d{2}-\d{2}""")
        private const val IST = "Asia/Kolkata"

        /**
         * The one plain-English sentence a screen MUST show next to this record
         * (A5/B6: the PASS/FAIL rule is stated, never implied and never invented).
         * Single-sourced here so no screen can word the rule differently.
         */
        const val RULE_TEXT =
            "How PASS/FAIL is decided: every evening after the market closes, " +
                "each suggestion is checked against the real prices of that day. " +
                "A BUY is PASS if the day's high reached the target, or the stock " +
                "closed above the suggested price. It is FAIL if the day's low " +
                "touched the safety stop, or it closed at or below the suggested " +
                "price. A SELL is judged the mirror way. If both the target and " +
                "the stop were touched the same day, it counts as FAIL — the app " +
                "never claims a win it cannot prove. If real prices could not be " +
                "fetched, the row stays PENDING — results are never guessed."
    }

    // ONE lock for both files: appends, evaluations and exports never interleave.
    private val mutex = Mutex()

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Append today's suggested stocks for [tab] ("STOCK" / "INTRADAY") with
     * result=PENDING. Idempotent per (date, symbol): calling this on every
     * refresh is safe — a symbol already recorded today is never written twice.
     * Only BUY / SELL suggestions are recorded (a WAIT is not advice that can
     * pass or fail). Any IO failure is swallowed (B8) — worst case today's rows
     * appear on a later call.
     */
    suspend fun appendSuggestions(tab: String, signals: List<TradingSignal>) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                runCatching {
                    val tabName = normalizedTab(tab) ?: return@runCatching
                    val file = fileFor(tabName)
                    val today = istToday()
                    // Symbols already recorded today, across the whole file.
                    val taken = readRowsLocked(file).asSequence()
                        .filter { it.date == today }
                        .map { normalizeSymbol(it.symbol) }
                        .toMutableSet()

                    val sb = StringBuilder()
                    if (!file.exists() || file.length() == 0L) sb.appendLine(HEADER)
                    for (signal in signals) {
                        if (signal.action != SignalAction.BUY &&
                            signal.action != SignalAction.SELL
                        ) continue
                        val sym = normalizeSymbol(signal.stockSymbol)
                        if (sym.isEmpty() || !taken.add(sym)) continue
                        val row = LedgerRow(
                            date = today,
                            tab = tabName,
                            symbol = sym,
                            name = signal.stockName,
                            action = signal.action.name,
                            suggestedPrice =
                                if (signal.entryPrice > 0.0) signal.entryPrice
                                else signal.currentPrice,
                            target = signal.targetPrice,
                            stopLoss = signal.stopLoss,
                            confidence = signal.confidence,
                            closePrice = 0.0,
                            dayHigh = 0.0,
                            dayLow = 0.0,
                            result = RESULT_PENDING,
                            reason = "waiting for market close"
                        )
                        sb.appendLine(toCsvLine(row))
                    }
                    if (sb.isNotEmpty()) file.appendText(sb.toString())
                }
            }
        }
    }

    /** All rows of [tab]'s record, oldest first. Empty on any failure (B8). */
    suspend fun readRows(tab: String): List<LedgerRow> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                val tabName = normalizedTab(tab)
                    ?: return@runCatching emptyList<LedgerRow>()
                readRowsLocked(fileFor(tabName))
            }.getOrElse { emptyList() }
        }
    }

    /**
     * Mark PENDING rows of [tab] dated today-or-earlier as PASS or FAIL using
     * REAL end-of-day prices. [quotes] maps symbol → Triple(close, dayHigh,
     * dayLow); keys may carry a ".NS" suffix or any case. Rows whose symbol has
     * no quote — or whose close is not a positive number — are left PENDING
     * (never judged blind, B6). Returns how many rows were decided.
     *
     * Rules (must match [RULE_TEXT] exactly):
     *   BUY  → FAIL if dayLow <= stopLoss ("stop hit")
     *          else PASS if dayHigh >= target ("target hit")
     *          else PASS if close > suggestedPrice ("closed above buy price")
     *          else FAIL ("closed at or below buy price")
     *   SELL → the mirror image.
     * The stop check runs FIRST: when both stop and target were touched the
     * same day nobody can know which came first, and the honest, conservative
     * call is FAIL — the app never invents a win (A5/B6).
     */
    suspend fun evaluatePending(
        tab: String,
        quotes: Map<String, Triple<Double, Double, Double>>
    ): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                val tabName = normalizedTab(tab) ?: return@runCatching 0
                if (quotes.isEmpty()) return@runCatching 0
                val file = fileFor(tabName)
                val rows = readRowsLocked(file)
                if (rows.isEmpty()) return@runCatching 0

                val normQuotes = HashMap<String, Triple<Double, Double, Double>>(quotes.size)
                for ((key, value) in quotes) {
                    val nk = normalizeSymbol(key)
                    if (nk.isNotEmpty()) normQuotes[nk] = value
                }

                val today = istToday()
                var decided = 0
                val updated = rows.map { row ->
                    if (row.result != RESULT_PENDING) return@map row
                    if (row.date > today) return@map row // never judge a future-dated row
                    val quote = normQuotes[normalizeSymbol(row.symbol)] ?: return@map row
                    val (close, high, low) = quote
                    if (close <= 0.0) return@map row
                    judge(row, close, high, low)?.also { decided++ } ?: row
                }
                if (decided > 0) rewrite(file, updated)
                decided
            }.getOrElse { 0 }
        }
    }

    /**
     * Copy [tab]'s raw CSV into [outputStream] (for the share/export flow) —
     * exactly the bytes on disk, Excel-openable. A record with no file yet
     * exports just the header row. The caller owns closing the stream.
     */
    suspend fun exportCopy(tab: String, outputStream: OutputStream) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                runCatching {
                    val tabName = normalizedTab(tab) ?: return@runCatching
                    val file = fileFor(tabName)
                    if (file.exists() && file.length() > 0L) {
                        file.inputStream().use { it.copyTo(outputStream) }
                    } else {
                        outputStream.write((HEADER + "\n").toByteArray(Charsets.UTF_8))
                    }
                    outputStream.flush()
                }
            }
        }
    }

    // ── Judging ───────────────────────────────────────────────────────────────

    /** PASS/FAIL verdict for one row, or null if its action is not judgeable. */
    private fun judge(row: LedgerRow, close: Double, high: Double, low: Double): LedgerRow? {
        val isBuy = row.action.equals("BUY", ignoreCase = true)
        val isSell = row.action.equals("SELL", ignoreCase = true)
        if (!isBuy && !isSell) return null

        val stopHit =
            if (isBuy) row.stopLoss > 0.0 && low > 0.0 && low <= row.stopLoss
            else row.stopLoss > 0.0 && high > 0.0 && high >= row.stopLoss
        val targetHit =
            if (isBuy) row.target > 0.0 && high > 0.0 && high >= row.target
            else row.target > 0.0 && low > 0.0 && low <= row.target

        val (result, reason) = when {
            // Stop first — when both were touched the same day the order is
            // unknowable, and the honest, conservative verdict is FAIL (A5/B6).
            stopHit                                 -> RESULT_FAIL to "stop hit"
            targetHit                               -> RESULT_PASS to "target hit"
            isBuy && close > row.suggestedPrice     -> RESULT_PASS to "closed above buy price"
            isBuy                                   -> RESULT_FAIL to "closed at or below buy price"
            close < row.suggestedPrice              -> RESULT_PASS to "closed below sell price"
            else                                    -> RESULT_FAIL to "closed at or above sell price"
        }
        return row.copy(
            closePrice = close,
            dayHigh = high,
            dayLow = low,
            result = result,
            reason = reason
        )
    }

    // ── File plumbing ─────────────────────────────────────────────────────────

    /** Strict whitelist — an unknown tab can never write into the wrong file. */
    private fun normalizedTab(tab: String): String? =
        when (tab.trim().uppercase(Locale.US)) {
            TAB_STOCK -> TAB_STOCK
            TAB_INTRADAY -> TAB_INTRADAY
            else -> null
        }

    private fun fileFor(tabName: String): File =
        File(context.filesDir, if (tabName == TAB_INTRADAY) FILE_INTRADAY else FILE_STOCK)

    private fun normalizeSymbol(symbol: String): String =
        symbol.trim().removeSuffix(".NS").uppercase(Locale.US)

    private fun istToday(): String {
        val cal = Calendar.getInstance(TimeZone.getTimeZone(IST))
        return "%04d-%02d-%02d".format(
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH)
        )
    }

    /** Caller must hold [mutex]. Header and unparseable lines are skipped. */
    private fun readRowsLocked(file: File): List<LedgerRow> {
        if (!file.exists()) return emptyList()
        return file.readLines().mapNotNull { parseRow(it) }
    }

    /**
     * Caller must hold [mutex]. Full rewrite via a temp file + rename so a
     * crash mid-write can never leave a half-written record behind.
     */
    private fun rewrite(file: File, rows: List<LedgerRow>) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(
            buildString {
                appendLine(HEADER)
                rows.forEach { appendLine(toCsvLine(it)) }
            }
        )
        if (!tmp.renameTo(file)) {
            // Rename refused (some filesystems won't replace an existing file) —
            // fall back to a direct overwrite; the mutex guarantees one writer.
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }

    // ── CSV encode / decode ───────────────────────────────────────────────────

    private fun toCsvLine(r: LedgerRow): String {
        val pending = r.result == RESULT_PENDING
        return listOf(
            r.date,
            r.tab,
            r.symbol,
            r.name,
            r.action,
            money(r.suggestedPrice),
            money(r.target),
            money(r.stopLoss),
            r.confidence.toString(),
            if (pending) "" else money(r.closePrice),
            if (pending) "" else money(r.dayHigh),
            if (pending) "" else money(r.dayLow),
            r.result,
            r.reason
        ).joinToString(",") { esc(it) }
    }

    private fun parseRow(line: String): LedgerRow? {
        if (line.isBlank()) return null
        val f = parseCsvLine(line)
        if (f.size < 14) return null
        if (!DATE_REGEX.matches(f[0])) return null // skips the header row too
        return LedgerRow(
            date = f[0],
            tab = f[1],
            symbol = f[2],
            name = f[3],
            action = f[4],
            suggestedPrice = f[5].toDoubleOrNull() ?: 0.0,
            target = f[6].toDoubleOrNull() ?: 0.0,
            stopLoss = f[7].toDoubleOrNull() ?: 0.0,
            confidence = f[8].toIntOrNull() ?: 0,
            closePrice = f[9].toDoubleOrNull() ?: 0.0,
            dayHigh = f[10].toDoubleOrNull() ?: 0.0,
            dayLow = f[11].toDoubleOrNull() ?: 0.0,
            result = f[12].ifBlank { RESULT_PENDING },
            reason = f[13]
        )
    }

    /** Locale-safe money text — always a dot decimal, so Excel parses it anywhere. */
    private fun money(v: Double): String = String.format(Locale.US, "%.2f", v)

    /** RFC-4180 escaping: quote a field holding a comma/quote/newline, double inner quotes. */
    private fun esc(field: String): String =
        if (field.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + field.replace("\"", "\"\"") + "\""
        } else {
            field
        }

    /** RFC-4180 line parser (the inverse of [esc]). */
    private fun parseCsvLine(line: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inQuotes && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                    cur.append('"'); i++
                }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> {
                    out.add(cur.toString()); cur.setLength(0)
                }
                else -> cur.append(c)
            }
            i++
        }
        out.add(cur.toString())
        return out
    }
}
