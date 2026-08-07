package com.example.myapplication3.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication3.intraday.IndexQuote
import com.example.myapplication3.intraday.IntradayRepository
import com.example.myapplication3.intraday.StockSearchResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** UI state for the Stock (Home) tab. */
data class HomeUiState(
    val indices: List<IndexQuote> = emptyList(),
    /** When the indices were last SUCCESSFULLY fetched (0 = never) — drives the "old data" badge. */
    val indicesFetchedAtMs: Long = 0L,
    val searchQuery: String = "",
    val searchResults: List<StockSearchResult> = emptyList(),
    val isSearching: Boolean = false
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: IntradayRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    /** Debounced global-search job — a new keystroke cancels the previous one. */
    private var searchJob: Job? = null

    init {
        // Fetch live indices immediately, then refresh every 60 seconds.
        viewModelScope.launch {
            while (true) {
                fetchIndicesOnce()
                delay(60_000L)
            }
        }
    }

    private suspend fun fetchIndicesOnce() {
        val fresh = runCatching { repository.fetchIndices() }.getOrElse { emptyList() }
        if (fresh.isNotEmpty()) {
            // Record WHEN this succeeded — failed refreshes keep the old values,
            // so the UI must be able to tell how old the numbers really are.
            _uiState.update {
                it.copy(indices = fresh, indicesFetchedAtMs = System.currentTimeMillis())
            }
        }
    }

    /**
     * Manual refresh for the toolbar button — without this, the one visible
     * refresh control never refetched the very NIFTY number flagged "Old".
     */
    fun refreshIndices() {
        viewModelScope.launch { fetchIndicesOnce() }
    }

    /** Update the search query; debounce 400 ms before hitting the network. */
    fun setSearchQuery(q: String) {
        searchJob?.cancel()
        if (q.isBlank()) {
            _uiState.update {
                it.copy(searchQuery = q, searchResults = emptyList(), isSearching = false)
            }
            return
        }
        _uiState.update { it.copy(searchQuery = q) }
        searchJob = viewModelScope.launch {
            delay(400L)
            _uiState.update { it.copy(isSearching = true) }
            val results = runCatching { repository.searchStocks(q) }.getOrElse { emptyList() }
            _uiState.update { it.copy(searchResults = results, isSearching = false) }
        }
    }

    /** Clear query and results (called when the search overlay closes). */
    fun clearSearch() {
        searchJob?.cancel()
        _uiState.update {
            it.copy(searchQuery = "", searchResults = emptyList(), isSearching = false)
        }
    }
}
