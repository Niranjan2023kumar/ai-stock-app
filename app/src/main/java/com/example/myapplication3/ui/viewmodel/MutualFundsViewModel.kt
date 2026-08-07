package com.example.myapplication3.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication3.mutualfunds.MutualFund
import com.example.myapplication3.mutualfunds.MutualFundRepository
import com.example.myapplication3.mutualfunds.SipRecord
import com.example.myapplication3.mutualfunds.SipStore
import com.example.myapplication3.mutualfunds.sipYearMonth
import com.example.myapplication3.tts.HindiTtsManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject

data class MfUiState(
    val isLoading: Boolean = false,
    val topFunds: List<MutualFund> = emptyList(),
    val searchQuery: String = "",
    val searchResults: List<MutualFund> = emptyList(),
    val isSearching: Boolean = false,
    /** Search failed on the network — show retry, NEVER "No funds found". */
    val searchError: String? = null,
    /** True only after a search for the CURRENT query completed without error —
     *  the empty state must never flash while typing or mid-search. */
    val searchDone: Boolean = false,
    val error: String? = null,
    // ── SIP tracking (rule C20a) ──
    val mySip: SipRecord? = null,
    /** Latest NAV of the tracked SIP's fund — null while loading or on failure. */
    val sipNav: Double? = null,
    /** This month's SIP payment is due (SIP day reached) and not yet confirmed. */
    val sipDueNow: Boolean = false,
    /**
     * One or more payments were confirmed well after SIP day and booked at the
     * confirm-day NAV (we never invent historical NAVs — rule B6), so the
     * growth line is approximate. The screen should label it "approx".
     */
    val hasLateConfirms: Boolean = false
)

@HiltViewModel
class MutualFundsViewModel @Inject constructor(
    private val repo: MutualFundRepository,
    private val sipStore: SipStore,
    private val tts: HindiTtsManager
) : ViewModel() {

    companion object {
        private const val SEARCH_DEBOUNCE_MS = 400L
    }

    private val _uiState = MutableStateFlow(MfUiState())
    val uiState: StateFlow<MfUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null

    init {
        loadTopFunds()
        loadSip()
    }

    fun loadTopFunds() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            val funds = try {
                repo.getTopFunds()
            } catch (ce: CancellationException) {
                throw ce                    // never swallow cancellation
            } catch (e: Exception) {
                emptyList()
            }
            if (funds.isEmpty()) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = "Could not load funds. Please check your internet."
                    )
                }
            } else {
                _uiState.update { it.copy(isLoading = false, topFunds = funds, error = null) }
            }
        }
    }

    fun onSearchQueryChange(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        searchJob?.cancel()
        if (query.isBlank()) {
            _uiState.update {
                it.copy(searchResults = emptyList(), isSearching = false,
                        searchError = null, searchDone = false)
            }
            return
        }
        // isSearching goes true IMMEDIATELY (before the debounce delay) so
        // "No funds found" can never flash while the user is still typing
        _uiState.update { it.copy(isSearching = true, searchError = null, searchDone = false) }
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            runSearch(query.trim())
        }
    }

    /** Retry after a search network error — no debounce, the user asked explicitly. */
    fun retrySearch() {
        val query = _uiState.value.searchQuery
        if (query.isBlank()) return
        searchJob?.cancel()
        _uiState.update { it.copy(isSearching = true, searchError = null, searchDone = false) }
        searchJob = viewModelScope.launch { runSearch(query.trim()) }
    }

    private suspend fun runSearch(query: String) {
        try {
            val results = repo.searchFunds(query)
            _uiState.update { it.copy(searchResults = results, isSearching = false, searchDone = true) }
        } catch (ce: CancellationException) {
            // A newer keystroke cancelled this search — rethrow so the dead
            // coroutine can never clobber state with a false "No funds found"
            throw ce
        } catch (e: Exception) {
            // Network / server failure is an ERROR with a retry, not an empty result
            _uiState.update {
                it.copy(isSearching = false,
                        searchError = "Could not search. Please check your internet.")
            }
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        _uiState.update {
            it.copy(searchQuery = "", searchResults = emptyList(), isSearching = false,
                    searchError = null, searchDone = false)
        }
    }

    /** Read any card's text aloud (rule I5) — the screen adds speaker buttons. */
    fun speak(text: String) {
        tts.speakText(text, "mf-read-aloud")
    }

    // ─── SIP tracking (rule C20a) ─────────────────────────────────────────────
    // Due/year-month logic lives on SipRecord (SipStore.kt) so this ViewModel
    // and SipReminderWorker can never disagree about when a payment is due.

    /** A confirm this many days after SIP day is "late" — booked NAV ≠ SIP-day NAV. */
    private val lateConfirmGraceDays = 3

    private fun loadSip() {
        val record = sipStore.load() ?: return
        _uiState.update {
            it.copy(mySip = record, sipDueNow = record.isDueOn(),
                    hasLateConfirms = record.hasLateConfirms)
        }
        refreshSipNav(record.schemeCode)
    }

    /** Fetch the tracked fund's latest NAV so the growth line uses real AMFI data. */
    fun refreshSipNav(schemeCode: Int) {
        viewModelScope.launch {
            val fund = try {
                repo.getFund(schemeCode)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                null
            }
            if (fund != null) _uiState.update { it.copy(sipNav = fund.nav) }
        }
    }

    /** User says they started this SIP in Groww — track it from zero payments. */
    fun startSip(fund: MutualFund, monthlyAmount: Int, sipDay: Int) {
        val record = SipRecord(
            schemeCode        = fund.schemeCode,
            fundName          = fund.name,
            monthlyAmount     = monthlyAmount,
            sipDay            = sipDay.coerceIn(1, 28),
            totalInvested     = 0.0,
            totalUnits        = 0.0,
            lastPaidYearMonth = ""
        )
        sipStore.save(record)
        _uiState.update {
            it.copy(mySip = record, sipNav = fund.nav, sipDueNow = record.isDueOn(),
                    hasLateConfirms = false)
        }
    }

    /**
     * "Yes, money went" on SIP day — record the payment at today's NAV.
     *
     * Honesty rule (B6): if the user confirms more than [lateConfirmGraceDays]
     * days after SIP day, today's NAV is NOT the NAV their money actually bought
     * at. We still book it (never fabricate a historical NAV), but the record is
     * flagged so the growth line can be labelled "approx" forever after.
     */
    fun confirmSipPaid() {
        val state = _uiState.value
        val record = state.mySip ?: return
        val nav = state.sipNav ?: return    // button stays disabled until NAV loads
        val today = Calendar.getInstance()
        val isLate = today.get(Calendar.DAY_OF_MONTH) - record.sipDay > lateConfirmGraceDays
        val updated = record.copy(
            totalInvested     = record.totalInvested + record.monthlyAmount,
            totalUnits        = record.totalUnits + record.monthlyAmount / nav,
            lastPaidYearMonth = sipYearMonth(today),
            hasLateConfirms   = record.hasLateConfirms || isLate
        )
        sipStore.save(updated)
        _uiState.update {
            it.copy(mySip = updated, sipDueNow = false,
                    hasLateConfirms = updated.hasLateConfirms)
        }
    }

    /** Stop tracking (does NOT stop the real SIP — that lives in Groww). */
    fun stopSip() {
        sipStore.clear()
        _uiState.update {
            it.copy(mySip = null, sipNav = null, sipDueNow = false, hasLateConfirms = false)
        }
    }
}
