package com.example.myapplication3.ledger

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject

/**
 * One calendar day of the suggestion record, ready to render: a friendly date
 * label, an honest one-line score ("2 right, 1 wrong · 1 waiting for result"),
 * and the day's rows exactly as they sit in the CSV file.
 */
data class LedgerDay(
    val date: String,        // yyyy-MM-dd (IST), newest day first in the list
    val dateLabel: String,   // "Today — 18 Jul 2026" / "17 Jul 2026, Thursday"
    val summary: String,     // plain-English day score, never invented
    val rightCount: Int,
    val wrongCount: Int,
    val waitingCount: Int,
    val rows: List<LedgerRow>
)

data class LedgerUiState(
    val isLoading: Boolean = true,
    val days: List<LedgerDay> = emptyList()
)

/**
 * Screen-side ViewModel for the suggestion record (route "ledger/{tab}").
 *
 * Reads ONE tab's ledger — Stock or Intraday, decided by the nav argument —
 * through [SuggestionLedger], so the two records can never mix on screen
 * either. Rendering is read-only: results come exclusively from the CSV the
 * after-close worker judged; this class never computes a PASS/FAIL itself
 * (A5/B6 — the screen shows the record, it does not invent one).
 */
@HiltViewModel
class LedgerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val ledger: SuggestionLedger
) : ViewModel() {

    /** True = this instance shows the Intraday record; false = the Stock record. */
    val isIntraday: Boolean =
        (savedStateHandle.get<String>("tab") ?: "")
            .trim()
            .equals("intraday", ignoreCase = true)

    private val tabName: String =
        if (isIntraday) SuggestionLedger.TAB_INTRADAY else SuggestionLedger.TAB_STOCK

    private val _uiState = MutableStateFlow(LedgerUiState())
    val uiState: StateFlow<LedgerUiState> = _uiState.asStateFlow()

    /** One-line save/export outcome to show once (snackbar); null = nothing. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        refresh()
    }

    /** Reload the record from disk (also safe to call when returning to the screen). */
    fun refresh() {
        viewModelScope.launch {
            val rows = ledger.readRows(tabName) // empty on any failure (B8)
            _uiState.value = LedgerUiState(isLoading = false, days = groupByDay(rows))
        }
    }

    /** File name for ACTION_CREATE_DOCUMENT — matches the on-disk record's name. */
    fun suggestedFileName(): String =
        if (isIntraday) SuggestionLedger.FILE_INTRADAY else SuggestionLedger.FILE_STOCK

    /**
     * Copy this tab's raw CSV — byte for byte — into the user's chosen file
     * (SAF uri from ACTION_CREATE_DOCUMENT). "wt" truncates, so re-saving over
     * an old export never leaves stale bytes at the tail.
     */
    fun export(uri: Uri, resolver: ContentResolver) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val out = resolver.openOutputStream(uri, "wt")
                        ?: error("stream unavailable")
                    out.use { ledger.exportCopy(tabName, it) }
                }
            }
            _message.value = result.fold(
                onSuccess = { "Saved. The file opens in Excel or Google Sheets." },
                onFailure = { "Could not save the file. Try again and pick a different folder." }
            )
        }
    }

    /** Call after the snackbar has shown the message, so it never repeats. */
    fun clearMessage() {
        _message.value = null
    }

    // ── Grouping ──────────────────────────────────────────────────────────────

    private fun groupByDay(rows: List<LedgerRow>): List<LedgerDay> {
        if (rows.isEmpty()) return emptyList()
        val today = istToday()
        return rows
            .groupBy { it.date }
            .toSortedMap(reverseOrder()) // newest day first ("yyyy-MM-dd" sorts naturally)
            .map { (date, dayRows) ->
                val right = dayRows.count { it.result == SuggestionLedger.RESULT_PASS }
                val wrong = dayRows.count { it.result == SuggestionLedger.RESULT_FAIL }
                val waiting = dayRows.size - right - wrong
                LedgerDay(
                    date = date,
                    dateLabel = labelFor(date, today),
                    summary = summaryFor(right, wrong, waiting),
                    rightCount = right,
                    wrongCount = wrong,
                    waitingCount = waiting,
                    rows = dayRows
                )
            }
    }

    /**
     * The day's honest score line. Counts only — no averages, no percentages,
     * and a day with nothing judged yet says exactly that (B6).
     */
    private fun summaryFor(right: Int, wrong: Int, waiting: Int): String {
        val parts = mutableListOf<String>()
        if (right + wrong > 0) parts += "$right right, $wrong wrong"
        if (waiting > 0) parts += "$waiting waiting for result"
        return parts.joinToString(" · ")
    }

    // ── Dates (IST, same convention as SuggestionLedger) ──────────────────────

    private fun istToday(): String {
        val cal = Calendar.getInstance(TimeZone.getTimeZone(IST))
        return "%04d-%02d-%02d".format(
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH)
        )
    }

    /** "Today — 18 Jul 2026" for today, else "17 Jul 2026, Thursday". */
    private fun labelFor(date: String, today: String): String = runCatching {
        val tz = TimeZone.getTimeZone(IST)
        val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            .apply { timeZone = tz; isLenient = false }
            .parse(date) ?: return@runCatching date
        if (date == today) {
            "Today — " + SimpleDateFormat("d MMM yyyy", Locale.ENGLISH)
                .apply { timeZone = tz }.format(parsed)
        } else {
            SimpleDateFormat("d MMM yyyy, EEEE", Locale.ENGLISH)
                .apply { timeZone = tz }.format(parsed)
        }
    }.getOrDefault(date) // a malformed date renders raw, never crashes (B8)

    private companion object {
        const val IST = "Asia/Kolkata"
    }
}
