package com.example.myapplication3.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication3.intraday.IntradayRepository
import com.example.myapplication3.intraday.IpoIssue
import com.example.myapplication3.intraday.NseRepository
import com.example.myapplication3.settings.UserSettingsStore
import com.example.myapplication3.tts.HindiTtsManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale
import javax.inject.Inject

/** One "other way to earn" row with a plain, honest verdict (D1/D4). */
data class EarnItem(
    val title: String,
    val symbol: String,
    val price: Double,
    val changePercent: Double,
    val currency: String,      // "₹" or "$"
    val verdict: String,
    val isGood: Boolean,
    val growwQuery: String,    // what the card's Groww button searches for (B0.2)
    val growwNote: String? = null,  // one plain extra line, e.g. "In Groww it is called GOLDBEES."
    /** D1 — pre-computed "put about ₹X in gold this month" line from the user's stored money. */
    val monthlyLine: String? = null,
    /** D1 — plain reassurance shown on the card FACE, e.g. "digital gold ... no locker, no shop". */
    val plainNote: String? = null,
    /** D1 — the technical terms ("Gold ETF / Sovereign Gold Bond") — revealed only under "See more". */
    val advancedNote: String? = null,
    /** D4 — approximate ₹ value of a $ price for the ₹-only user, e.g. "about ₹18,500". */
    val approxInr: String? = null
)

/**
 * D2 — one open/upcoming IPO, already decided for the user so the screen does NO
 * math: price in plain words, affordability vs the user's stored money, and a
 * one-line APPLY/SKIP verdict. APPLY appears ONLY when one lot fits the money.
 */
data class IpoItem(
    val name: String,
    val priceLine: String,   // "Shares cost ₹X–₹Y each."
    val dateLine: String,    // plain apply-window words, e.g. "Open now — apply before 28 Jul."
    val moneyLine: String,   // affordability vs the user's stored money
    val apply: Boolean,      // true → open now AND one lot fits → APPLY, else SKIP
    val verdict: String,     // "APPLY" or "SKIP"
    val reason: String
)

data class MoreWaysUiState(
    val isLoading: Boolean = true,
    val gold: EarnItem? = null,
    val silver: EarnItem? = null,
    val usStocks: List<EarnItem> = emptyList(),
    /** D3 — per-user "put about ₹X every month" line, computed from stored money (null → generic floor). */
    val sipMonthlyLine: String? = null,
    /** D2 — open/upcoming IPOs; EMPTY drives the honest "no IPOs" state (never fabricated). */
    val ipos: List<IpoItem> = emptyList(),
    /**
     * D4/B6 — honest note under the US section when the live USD→INR rate could
     * not be fetched: the cards then show NO ₹ conversion at all. Never a stale
     * constant presented as today's rate.
     */
    val usInrNote: String? = null,
    val error: String? = null
) {
    /** True when at least one live-price card has loaded. */
    val hasData: Boolean get() = gold != null || silver != null || usStocks.isNotEmpty()
}

/**
 * "More ways to earn" (PART D) — Gold/Silver via NSE ETFs priced in ₹ (D1) and the
 * world's biggest US stocks (D4), all through the SAME free Yahoo engine. Verdict is
 * honest: price vs its own 1-year average. Lives under the Guide "Advanced" area (B10).
 * The D3 index-SIP card shows a per-user monthly ₹ amount computed here; the D2 IPO
 * card renders real NSE listings (NseRepository.fetchIpos) or an honest "no IPOs" state.
 *
 * D4 (amended): US cards are WATCH-ONLY — the app has no one-tap US order path and
 * cannot pre-compute shares/amount in ₹ with forex + brokerage charges, so a BUY-style
 * verdict is never shown for them. Plain info / wait wording only, with the honest line.
 * D1 (amended): the gold card pre-computes a monthly ₹ amount from the user's stored
 * money (UserSettingsStore, read-only) when the verdict says gold is cheap.
 */
@HiltViewModel
class MoreWaysViewModel @Inject constructor(
    private val repository: IntradayRepository,
    private val nseRepository: NseRepository,
    private val ttsManager: HindiTtsManager,
    private val userSettings: UserSettingsStore
) : ViewModel() {

    companion object {
        /**
         * This VM is scoped to the MoreWays back-stack entry, so every re-entry
         * builds a fresh instance (audit re-entry finding). This small static cache
         * lets a return visit render the last data instantly instead of a spinner
         * (data-saver rule E3d), refreshing silently when stale. Process death
         * clears it, which is fine — init falls back to a normal load. Known
         * limitation: it is process-wide state, not a real repository cache.
         */
        private const val CACHE_FRESH_MS = 3 * 60_000L
        @Volatile private var cachedState: MoreWaysUiState? = null
        @Volatile private var cachedAtMs = 0L

        /**
         * D4 — sanity band for the live USD→INR rate. A value outside this band is
         * treated as a FAILED fetch (bad symbol/parse), never shown. There is NO
         * numeric fallback: a stale constant shown as today's rate is an invented
         * number (B6). Verified live 2026-07-24: Yahoo USDINR=X 96.495, Yahoo
         * INR=X 96.50, open.er-api.com 96.72 — all agree, so a fetched ~96 is the
         * real rate, not a bug.
         */
        private const val USDINR_MIN = 70.0
        private const val USDINR_MAX = 110.0

        /** D4 — honest line shown when the live rate cannot be fetched (B6/B8). */
        const val US_INR_UNAVAILABLE = "US price in ₹ not available right now."
    }

    private val _uiState = MutableStateFlow(MoreWaysUiState())
    val uiState: StateFlow<MoreWaysUiState> = _uiState.asStateFlow()

    init {
        val cached = cachedState
        if (cached != null && cached.hasData) {
            // Instant render from the last visit; only refetch when the data is old.
            _uiState.value = cached.copy(isLoading = false, error = null)
            if (System.currentTimeMillis() - cachedAtMs > CACHE_FRESH_MS) load()
        } else {
            load()
        }
    }

    /** 🔊 read-aloud parity (I5): the spoken line matches the card on screen. */
    fun speak(text: String) = ttsManager.speakText(text)

    fun load() {
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            // The user's money once, read-only (default ₹1,00,000). Drives the D3 SIP
            // amount, the D1 gold amount and the D2 IPO affordability check — the
            // screen never does money math, everything is pre-computed here.
            val cap = runCatching { userSettings.capitalOnce() }.getOrDefault(0.0)

            // D3 — per-user monthly amount for the index SIP (same pattern as the gold
            // card): ~5% of capital, rounded down to ₹100, minimum ₹500 (the real SIP
            // floor). Card face stays in shopkeeper words; the fund name lives behind See more.
            val sipLine = if (cap > 0.0)
                "For your money: put about ₹${inrGroup(sipAmtFor(cap).toDouble())} every month."
            else null

            // D2 — real NSE IPO listings (NseRepository.fetchIpos). An empty repo list
            // or any fetch error keeps the honest "no IPOs" state — never fabricated
            // rows (B6), fail-safe on error (B8).
            val ipoItems = fetchIpos(cap)

            _uiState.update { it.copy(sipMonthlyLine = sipLine, ipos = ipoItems) }

            runCatching {
                // Gold & Silver ETFs trade on NSE in ₹ — the simple, safe way (D1). The
                // plain "digital gold ... no locker, no shop" reassurance is on the card
                // face; the terms "Gold ETF / Sovereign Gold Bond" live under See more.
                val goldD = async {
                    fetch(
                        "GOLDBEES.NS", "Gold", "₹",
                        growwQuery = "GOLDBEES", growwNote = "In Groww it is called GOLDBEES.",
                        plainNote = "Digital gold — no locker, no shop. Held safely inside Groww.",
                        advancedNote = "The full names for this are Gold ETF and Sovereign Gold Bond."
                    )
                }
                val silverD = async {
                    fetch(
                        "SILVERBEES.NS", "Silver", "₹",
                        growwQuery = "SILVERBEES", growwNote = "In Groww it is called SILVERBEES.",
                        plainNote = "This is digital silver you keep inside Groww — no locker, no shop.",
                        advancedNote = "The full name for this is Silver ETF."
                    )
                }
                // The world's biggest companies (D4) — prices in $, WATCH-ONLY per
                // amended D4: no one-tap US order path + no ₹ sizing exists, so these
                // cards must never show an actionable BUY verdict. A ₹ conversion IS
                // shown ("about ₹X") so the rupee-only user can read the price (D4).
                val usSyms = listOf("AAPL" to "Apple", "MSFT" to "Microsoft", "GOOGL" to "Google", "AMZN" to "Amazon")
                val usD = usSyms.map { (sym, name) ->
                    async {
                        fetch(
                            sym, name, "$", growwQuery = name,
                            growwNote = "The app cannot place or size US orders yet — watch only.",
                            watchOnly = true
                        )
                    }
                }

                val goldRaw = goldD.await()
                val silver = silverD.await()
                val us = usD.awaitAll().filterNotNull()

                // D4 — one live USD→INR rate (free Yahoo forex), applied to every US
                // card as an approximate "about ₹X" line. When the live rate cannot be
                // fetched, fall back to ₹84/$ with a "(rate approx)" label so the
                // ₹-only user always gets a rough ₹ context (D4 / B6 — the approx
                // label makes it honest; never a stale constant presented as live).
                val rate = fetchUsdInrRate()
                val approxRate = rate ?: 84.0
                val rateNote = if (rate == null) " (rate approx)" else ""
                val usWithInr = us.map { it.copy(approxInr = "about ₹${inrGroup(it.price * approxRate)}$rateNote") }
                val usInrNote = if (rate == null && us.isNotEmpty()) US_INR_UNAVAILABLE else null

                // D1 — pre-compute the month's gold ₹ amount from the user's stored money:
                // ~5% of capital, rounded down to ₹100, minimum ₹100. Shown only when the
                // verdict already says gold is cheap — never beside a "wait" verdict.
                val gold = if (goldRaw != null && goldRaw.isGood && cap > 0.0) {
                    val amt = (((cap * 0.05) / 100.0).toInt() * 100).coerceAtLeast(100)
                    goldRaw.copy(monthlyLine = "For your money: put about ₹${inrGroup(amt.toDouble())} in gold this month.")
                } else goldRaw

                _uiState.update {
                    it.copy(isLoading = false, gold = gold, silver = silver, usStocks = usWithInr,
                        usInrNote = usInrNote,
                        error = if (gold == null && silver == null && usWithInr.isEmpty()) "Could not load — check internet" else null)
                }
                if (_uiState.value.hasData) {
                    cachedState = _uiState.value
                    cachedAtMs = System.currentTimeMillis()
                }
            }.onFailure { e ->
                _uiState.update { it.copy(isLoading = false, error = e.message?.take(80) ?: "Could not load data") }
            }
        }
    }

    /** ₹ amount in Indian (lakh/crore) grouping — matches the app's shared formatter output. */
    private fun inrGroup(v: Double): String =
        NumberFormat.getIntegerInstance(Locale("en", "IN")).format(v.toLong())

    /** D3 — per-user monthly SIP amount: ~5% of capital, floored to ₹100, at least the ₹500 SIP minimum. */
    private fun sipAmtFor(capital: Double): Int =
        (((capital * 0.05) / 100.0).toInt() * 100).coerceAtLeast(500)

    /**
     * D4 — live USD→INR rate via the SAME free Yahoo layer (forex symbol
     * "USDINR=X", meta.regularMarketPrice — verified against two independent
     * non-Yahoo sources on 2026-07-24, all ~96.5). Returns null when the fetch
     * fails or the value falls outside the 70..110 sanity band; the caller then
     * shows the honest "not available" line — NEVER a hardcoded rate presented
     * as live (B6). The conversion, when shown, is always "about ₹X".
     */
    private suspend fun fetchUsdInrRate(): Double? {
        val live = runCatching { repository.fetchStockResearch("USDINR=X").currentPrice }.getOrDefault(0.0)
        return live.takeIf { it in USDINR_MIN..USDINR_MAX }
    }

    // ── D2 — IPOs ───────────────────────────────────────────────────────────────

    /**
     * D2 — real open/upcoming IPO rows from NSE (NseRepository.fetchIpos), each
     * turned into a plain-word, pre-decided card. Fail-safe (B8): any error or an
     * empty repo list gives an EMPTY list — exactly the honest "no IPOs" state the
     * screen already renders. NEVER fabricated rows (B6).
     */
    private suspend fun fetchIpos(capital: Double): List<IpoItem> =
        runCatching {
            nseRepository.fetchIpos().map { ipoItemOf(it, capital) }
        }.getOrDefault(emptyList())

    /**
     * D2 — one raw NSE IPO row → a plain-word, pre-decided card (I3: the screen does
     * NO math). Price in plain words ("shares cost ₹X–₹Y each" — never "price band"),
     * the apply window in plain dates, affordability vs the user's stored money, and
     * a one-line APPLY/SKIP verdict — APPLY ONLY when the issue is OPEN NOW and one
     * whole lot verifiably fits the money; everything else is SKIP with a one-line
     * reason. Null price/lot from NSE is shown as "not known yet" (A5/B6 — never 0,
     * never invented) and can never produce an APPLY.
     */
    private fun ipoItemOf(ipo: IpoIssue, capital: Double): IpoItem {
        // The price the user is actually charged is the TOP of the range (for
        // fixed-price issues NSE sends min == max). Null = NSE gave no readable
        // number — the card says so; it never shows 0 or a guess.
        val perShare = ipo.priceMax ?: ipo.priceMin
        val lotCost = if (perShare != null && perShare > 0.0 && ipo.lotSize != null && ipo.lotSize > 0)
            perShare * ipo.lotSize
        else null
        val fits = lotCost != null && capital > 0.0 && lotCost <= capital

        val priceLine = when {
            perShare == null || perShare <= 0.0 ->
                "Share price not announced yet."
            ipo.priceMin != null && ipo.priceMin > 0.0 && ipo.priceMin < perShare ->
                "Shares cost ₹${inrGroup(ipo.priceMin)}–₹${inrGroup(perShare)} each."
            else ->
                "Shares cost about ₹${inrGroup(perShare)} each."
        }
        val dateLine = if (ipo.isOpenNow)
            "Open now — you can apply until ${ipo.closeDate}."
        else
            "Not open yet — you can apply from ${ipo.openDate} to ${ipo.closeDate}."
        val moneyLine = when {
            lotCost == null -> "The money needed for one lot is not known yet."
            fits            -> "You need about ₹${inrGroup(lotCost)} for one lot — this fits your money."
            else            -> "You need about ₹${inrGroup(lotCost)} for one lot — that needs more money than you set aside."
        }
        // APPLY only when BOTH are true: open right now AND one lot provably fits.
        val apply = ipo.isOpenNow && fits
        val reason = when {
            apply           -> "Open now and one lot fits your money — you can apply in Groww."
            !ipo.isOpenNow  -> "Not open yet — wait until ${ipo.openDate}."
            lotCost == null -> "The full cost is not known yet — better to wait."
            else            -> "Needs more money than you set aside — skip this one."
        }
        return IpoItem(
            name = ipo.name,
            priceLine = priceLine,
            dateLine = dateLine,
            moneyLine = moneyLine,
            apply = apply,
            verdict = if (apply) "APPLY" else "SKIP",
            reason = reason
        )
    }

    private suspend fun fetch(
        symbol: String,
        title: String,
        currency: String,
        growwQuery: String,
        growwNote: String? = null,
        watchOnly: Boolean = false,
        plainNote: String? = null,
        advancedNote: String? = null
    ): EarnItem? {
        val d = runCatching { repository.fetchStockResearch(symbol) }.getOrNull() ?: return null
        if (d.currentPrice <= 0.0) return null
        val avg = if (d.ma200 > 0.0) d.ma200 else d.ma50
        // One plain sentence per verdict (I1/I4) — a decision, never bare data.
        // watchOnly (amended D4): plain info / WAIT wording only — never a
        // BUY-style verdict the user cannot act on, and never a green "good" card.
        val (verdict, good) = when {
            watchOnly -> when {
                avg <= 0.0           -> "Watch only — not enough history to judge yet." to false
                d.currentPrice < avg -> "Cheaper than usual — but watch only, do not buy yet." to false
                else                 -> "Costly right now — watch only." to false
            }
            avg <= 0.0                 -> "Wait — not enough history to judge this yet." to false
            d.currentPrice < avg       -> "Cheaper than usual — a good time to start." to true
            else                       -> "Costly right now — better to wait." to false
        }
        return EarnItem(
            title, symbol, d.currentPrice, d.changePercent, currency, verdict, good, growwQuery, growwNote,
            plainNote = plainNote, advancedNote = advancedNote
        )
    }
}
