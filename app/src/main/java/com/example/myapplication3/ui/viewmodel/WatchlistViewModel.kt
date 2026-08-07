package com.example.myapplication3.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication3.database.dao.WatchlistDao
import com.example.myapplication3.database.entity.WatchlistEntity
import com.example.myapplication3.intraday.IntradayRepository
import com.example.myapplication3.intraday.SignalAction
import com.example.myapplication3.intraday.StockResearchData
import com.example.myapplication3.intraday.StockScreenerItem
import com.example.myapplication3.intraday.TradingSignal
import com.example.myapplication3.intraday.computeSignalBadge
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class WatchlistItem(val symbol: String, val exchange: String, val sector: String)
data class WatchlistUiState(val items: List<WatchlistItem> = emptyList(), val isLoading: Boolean = false)

/**
 * One watchlist row with today's numbers and a one-line plain-English verdict
 * (spec H10 — the watchlist must answer "how is MY stock doing today?").
 *
 * - [price]/[changePct] are 0.0 and meaningless when [hasData] is false — the
 *   screen must then show only [verdictLine] (which explains why) instead of
 *   fake zeros.
 * - [currency] is the quote currency ("INR", "USD", …). A non-INR price must
 *   never be shown with a ₹ symbol (a ~90x error for a beginner).
 * - [isFromCache] true = numbers come from the app's last successful refresh
 *   (see [WatchlistRowsState.updatedAtMs]), not a live fetch made just now.
 *   The screen should pair cached rows with an honest "as of HH:mm" line (B2).
 */
data class WatchlistRow(
    val symbol: String,
    val name: String,
    val exchange: String,
    val price: Double,
    val changePct: Double,
    val verdictLine: String,
    val hasData: Boolean = false,
    val isFromCache: Boolean = false,
    val currency: String = "INR"
)

/**
 * Row list + freshness for the watchlist screen.
 * [updatedAtMs] = wall-clock ms of the cached refresh backing the cache-derived
 * rows (0 = the app has never completed a refresh). [isRefreshing] is true only
 * while live lookups run for stocks outside the analyzed universe.
 */
data class WatchlistRowsState(
    val rows: List<WatchlistRow> = emptyList(),
    val isRefreshing: Boolean = false,
    val updatedAtMs: Long = 0L
)

@HiltViewModel
class WatchlistViewModel @Inject constructor(
    private val watchlistDao: WatchlistDao,
    private val repository: IntradayRepository
) : ViewModel() {

    companion object {
        // Out-of-universe stocks (e.g. AAPL) need one research fetch each — bound
        // the fan-out so a huge watchlist can't fire dozens of chart calls at once.
        private const val MAX_LIVE_LOOKUPS = 10
        // How long a live per-symbol lookup stays fresh before a rebuild refetches it.
        private const val LIVE_TTL_MS = 10 * 60 * 1000L
    }

    val uiState: StateFlow<WatchlistUiState> = watchlistDao.getAll()
        .map { entities ->
            WatchlistUiState(items = entities.map { WatchlistItem(it.symbol, it.exchange, it.sector) })
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), WatchlistUiState(isLoading = true))

    // ─── Add validation (plain-English error the screen can show) ─────────────

    /** Non-null = the last add attempt failed; simple-English message (A4). Clear with [clearAddError]. */
    private val _addError = MutableStateFlow<String?>(null)
    val addError: StateFlow<String?> = _addError.asStateFlow()

    /** True while an add is being validated against the live symbol search. */
    private val _isAdding = MutableStateFlow(false)
    val isAdding: StateFlow<Boolean> = _isAdding.asStateFlow()

    fun clearAddError() { _addError.value = null }

    /**
     * Validated add: the typed symbol is resolved through the live Yahoo search
     * first. Unknown symbols are REJECTED with a plain-English error instead of
     * being inserted as permanent junk rows; a recognizable typo resolves to the
     * real listing (we store the RESOLVED symbol, never the raw typed text).
     */
    fun addToWatchlist(symbol: String) {
        viewModelScope.launch {
            val typed = symbol.trim().uppercase()
            if (typed.isEmpty()) {
                _addError.value = "Please type a stock symbol first."
                return@launch
            }
            _isAdding.value = true
            try {
                // searchStocks returns emptyList on network failure too — the
                // error message below covers both "wrong symbol" and "no internet".
                val results = runCatching { repository.searchStocks(typed) }.getOrNull().orEmpty()
                val match = results.firstOrNull { baseSymbol(it.symbol) == typed }
                    ?: results.firstOrNull()
                if (match == null) {
                    _addError.value = "Could not find \"$typed\". Check the spelling, " +
                        "make sure internet is on, and try again."
                    return@launch
                }
                val storeSymbol = baseSymbol(match.symbol)
                if (watchlistDao.isInWatchlist(storeSymbol) > 0) {
                    _addError.value = "$storeSymbol is already in your watchlist."
                    return@launch
                }
                watchlistDao.insert(
                    WatchlistEntity(
                        symbol   = storeSymbol,
                        exchange = match.exchange.takeIf { it.isNotBlank() } ?: "",
                        name     = match.name.ifBlank { storeSymbol },
                        sector   = ""
                    )
                )
                _addError.value = null
            } finally {
                _isAdding.value = false
            }
        }
    }

    // Indian listings are stored without the Yahoo suffix (matches signals/screener
    // keys); other exchanges (AAPL, 7203.T) keep their Yahoo symbol as-is.
    private fun baseSymbol(s: String): String =
        s.removeSuffix(".NS").removeSuffix(".BO").uppercase()

    fun removeFromWatchlist(symbol: String) {
        viewModelScope.launch {
            runCatching { watchlistDao.deleteBySymbol(symbol) }.onFailure { android.util.Log.w("WatchlistVM", "Delete failed: $it") }
        }
    }

    // ─── Per-row price + verdict (spec H10) ───────────────────────────────────
    //
    // Data source — lightest correct path, in order:
    //  1. Cached signals (loadCachedSignals) — ZERO network; strongest verdict
    //     (the engine's full analysis) for universe stocks that produced a signal.
    //  2. Cached screener (loadCachedScreener) — ZERO network; price/change% for
    //     the whole 200-stock universe, verdict via computeSignalBadge() (the
    //     SAME logic the Stocks tab uses, so both tabs agree about a stock).
    //  3. fetchStockResearch — ONLY for watchlist stocks outside the universe
    //     (e.g. AAPL): one chart call each, bounded by MAX_LIVE_LOOKUPS and
    //     memoized for LIVE_TTL_MS. fetchAll() (200 symbols) is never triggered.

    /** Ask for [rowsState] to be rebuilt, refetching any live (out-of-universe) lookups. */
    fun refreshRows() {
        liveMemo.clear()
        refreshTick.value++
    }

    private val refreshTick = MutableStateFlow(0)

    // symbol → (fetchedAtMs, research result). Failures are memoized too so a DB
    // change can't hammer Yahoo; TTL (or refreshRows) allows the retry.
    private val liveMemo = HashMap<String, Pair<Long, StockResearchData>>()

    private val _rowsState = MutableStateFlow(WatchlistRowsState())
    val rowsState: StateFlow<WatchlistRowsState> = _rowsState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(watchlistDao.getAll(), refreshTick) { entities, _ -> entities }
                .collectLatest { entities -> rebuildRows(entities) }
        }
    }

    private suspend fun rebuildRows(entities: List<WatchlistEntity>) {
        val cacheTs = repository.getCacheTimestampMs()
        if (entities.isEmpty()) {
            _rowsState.value = WatchlistRowsState(updatedAtMs = cacheTs)
            return
        }

        val screener = repository.loadCachedScreener().associateBy { it.symbol.uppercase() }
        val signals  = repository.loadCachedSignals().associateBy { it.stockSymbol.uppercase() }

        fun buildAll(refreshing: Boolean) = WatchlistRowsState(
            rows         = entities.map { buildRow(it, screener, signals) },
            isRefreshing = refreshing,
            updatedAtMs  = cacheTs
        )

        // Live lookups only for symbols with no cached coverage and no fresh memo.
        val now = System.currentTimeMillis()
        val missing = entities.map { it.symbol.uppercase() }
            .filter { it !in screener && it !in signals }
            .filter { now - (liveMemo[it]?.first ?: 0L) > LIVE_TTL_MS }
            .distinct()
            .take(MAX_LIVE_LOOKUPS)

        // Instant first paint from cache — never blocked behind the network.
        _rowsState.value = buildAll(refreshing = missing.isNotEmpty())
        if (missing.isEmpty()) return

        coroutineScope {
            missing.map { sym ->
                async {
                    val data = runCatching { repository.fetchStockResearch(sym) }.getOrNull()
                        ?: StockResearchData(symbol = sym, isLoading = false, error = "fetch failed")
                    liveMemo[sym] = System.currentTimeMillis() to data
                }
            }.awaitAll()
        }
        _rowsState.value = buildAll(refreshing = false)
    }

    private fun buildRow(
        entity: WatchlistEntity,
        screener: Map<String, StockScreenerItem>,
        signals: Map<String, TradingSignal>
    ): WatchlistRow {
        val key = entity.symbol.uppercase()
        val fallbackName = entity.name.ifBlank { entity.symbol }

        // 1. Full engine signal — the strongest verdict available.
        signals[key]?.let { sig ->
            return WatchlistRow(
                symbol      = entity.symbol,
                name        = sig.stockName.ifBlank { fallbackName },
                exchange    = entity.exchange,
                price       = sig.currentPrice,
                changePct   = sig.changePercent,
                verdictLine = verdictFromSignal(sig),
                hasData     = sig.currentPrice > 0,
                isFromCache = true,
                currency    = "INR"
            )
        }

        // 2. Screener cache — covers the whole analyzed universe.
        screener[key]?.let { item ->
            return WatchlistRow(
                symbol      = entity.symbol,
                name        = item.name.ifBlank { fallbackName },
                exchange    = entity.exchange,
                price       = item.currentPrice,
                changePct   = item.changePercent,
                verdictLine = verdictFromBadge(item),
                hasData     = item.currentPrice > 0,
                isFromCache = true,
                currency    = "INR"
            )
        }

        // 3. Live lookup memo (out-of-universe stocks).
        val live = liveMemo[key]?.second
        if (live != null && live.currentPrice > 0) {
            return WatchlistRow(
                symbol      = entity.symbol,
                name        = live.name.ifBlank { fallbackName },
                exchange    = entity.exchange,
                price       = live.currentPrice,
                changePct   = live.changePercent,
                verdictLine = verdictFromBadge(researchToItem(live)),
                hasData     = true,
                isFromCache = false,
                currency    = live.currency
            )
        }

        // No data anywhere — say so honestly (B6), never show fake zeros.
        val verdict = if (liveMemo.containsKey(key))
            "No data right now — check your internet and refresh."
        else
            "Getting today's data for this stock…"
        return WatchlistRow(
            symbol      = entity.symbol,
            name        = fallbackName,
            exchange    = entity.exchange,
            price       = 0.0,
            changePct   = 0.0,
            verdictLine = verdict,
            hasData     = false,
            isFromCache = false,
            currency    = "INR"
        )
    }

    // ─── Verdict wording — one line, simple English (A4), honest (A5/B6) ──────

    private fun verdictFromSignal(sig: TradingSignal): String = when (sig.action) {
        SignalAction.BUY  -> when {
            sig.confidence >= 90 -> "Strong buy setup today — signs are clearly positive. Not a guarantee."
            sig.confidence >= 84 -> "Good buy setup today — signs line up well. Not a guarantee."
            else                 -> "Weak buy setup today — signs are mixed. Be careful."
        }
        SignalAction.SELL ->
            "Selling pressure today — not a day to buy this."
        SignalAction.WAIT ->
            "No clear chance today — better to wait."
    }

    /**
     * Verdict via the SAME badge logic the Stocks tab uses — the watchlist and
     * the Stocks tab must never disagree about the same stock on the same data.
     */
    private fun verdictFromBadge(item: StockScreenerItem): String {
        val badge = item.computeSignalBadge()
        return when {
            badge.isNeutral              -> "No clear direction today — nothing to do."
            badge.label == "Buy Now"     -> "Strong today — trading above its usual price levels."
            badge.label == "Sell Now"    -> "Weak today — trading below its usual price levels."
            badge.label == "Watch to Buy"-> "Slightly positive — worth watching, no clear entry yet."
            badge.label == "Caution"     -> "Slightly weak today — be careful with this one."
            badge.isPositive             -> "Slightly positive — worth watching, no clear entry yet."
            else                         -> "Slightly weak today — be careful with this one."
        }
    }

    /** Adapt a research result to the screener shape so computeSignalBadge() applies. */
    private fun researchToItem(d: StockResearchData): StockScreenerItem {
        val closes20   = d.historicalCloses.takeLast(20)
        val periodLow  = closes20.minOrNull()
            ?: if (d.low52w > 0) minOf(d.low52w, d.currentPrice) else d.currentPrice
        val periodHigh = closes20.maxOrNull()
            ?: if (d.high52w > 0) maxOf(d.high52w, d.currentPrice) else d.currentPrice
        return StockScreenerItem(
            symbol                = d.symbol,
            name                  = d.name,
            currentPrice          = d.currentPrice,
            changePercent         = d.changePercent,
            volume                = d.volume,
            avgVolume             = d.avgVolume,
            periodLow             = periodLow,
            periodHigh            = periodHigh,
            low52w                = d.low52w,
            high52w               = d.high52w,
            distFromPeriodLowPct  = if (periodLow > 0) (d.currentPrice - periodLow) / periodLow * 100.0 else 0.0,
            distFromPeriodHighPct = if (periodHigh > 0) (periodHigh - d.currentPrice) / periodHigh * 100.0 else 0.0,
            historicalCloses      = closes20,
            ma50                  = d.ma50,
            ma200                 = d.ma200,
            marketState           = d.marketState,
            periodDays            = 20,
            volRatio              = if (d.avgVolume > 0) d.volume.toDouble() / d.avgVolume else 1.0
        )
    }
}
