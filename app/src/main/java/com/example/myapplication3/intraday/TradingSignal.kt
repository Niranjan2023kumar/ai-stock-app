package com.example.myapplication3.intraday

enum class SignalAction { BUY, SELL, WAIT }
enum class RiskLevel { LOW, MEDIUM, HIGH }
enum class PriceFilterType { NONE, N_DAY_LOW, N_DAY_HIGH }

data class TradingSignal(
    val stockSymbol: String,
    val stockName: String,
    val action: SignalAction,
    val currentPrice: Double,
    val changePercent: Double = 0.0,   // stock's own % change vs prev close — feeds RiskGuard Rule 3
    val entryPrice: Double,
    val entryZone: String = "",
    // FIXED numeric entry zone, anchored the first time the signal appears in a day
    // (C12a) — the zone must never re-center on the live price every refresh.
    val entryLow: Double = 0.0,
    val entryHigh: Double = 0.0,
    val targetPrice: Double,
    val target2: Double = 0.0,
    val target3: Double = 0.0,
    val stopLoss: Double,
    val trailingStop: Double = 0.0,
    val expectedProfit: Double,        // GROSS per-share reward at Target-1 (= |t1 − entry|); NOT after charges
    val expectedLoss: Double,
    // ── After-charges truth (B0.3a, loss cause #1) ────────────────────────────
    // A user who reaches the shown target still LOSES if brokerage + STT +
    // exchange txn + GST + SEBI + stamp + slippage aren't subtracted first.
    // These are the honest rupee figures for a `recommendedQty`-sized position.
    // The ViewModel re-sizes qty to the user's real capital, so recompute both
    // with TradingSignal.estimatedCostFor() / netProfitFor() for the shown qty.
    val estimatedCostRs: Double = 0.0, // round-trip charges (₹) for the position
    val netProfitRs: Double = 0.0,     // profit (₹) AFTER charges — the only profit figure safe to show
    val riskLevel: RiskLevel,
    val confidence: Int,
    val reasons: List<String>,
    val validUntil: String,
    val tradeValidityTime: String = "Till 3:15 PM",
    val isBeginnerSafe: Boolean,
    val marketState: String = "REGULAR",
    val recommendedQty: Int = 1,
    val riskReward: Double = 0.0,
    val upsideProbability: Int = 0,    // rough model LEAN toward an up-move — NOT a measured win rate (see upsideProbabilityMeaning)
    val downsideProbability: Int = 0,
    val sidewaysProbability: Int = 0,
    val sectorStrength: String = "NEUTRAL",
    val marketTrend: String = "SIDEWAYS",
    val institutionalFlow: String = "NEUTRAL",
    // RiskGuard inputs — real values from SignalEngine
    val volume: Long = 0L,
    val avgVolume: Long = 0L,
    val gapPercent: Double = 0.0,
    // News intelligence — sentiment of this stock's latest headlines
    val newsSentiment: String = "NONE",   // POSITIVE | NEGATIVE | NEUTRAL | NONE
    // Live-tick staleness (C12a, loss cause #3): true when a live price supplied
    // at analysis time had already run beyond the fixed entry zone / target /
    // stop. The UI must disable the buy button when this is true. Defaults false
    // so a signal is treated as fresh until a live tick proves otherwise.
    val chanceGone: Boolean = false
)

/**
 * Stale-entry protection (C12a): true when the live price has run beyond the
 * signal's FIXED entry zone (chasing), or the setup is already dead (price at/
 * past the target, or at/under the stop). A card in this state must switch to
 * "This chance passed — do not buy now" and hide every buy control.
 */
fun TradingSignal.buyChancePassed(livePrice: Double = currentPrice): Boolean {
    if (action != SignalAction.BUY || livePrice <= 0.0) return false
    val ranPastZone = entryHigh > 0.0 && livePrice > entryHigh
    val setupDead   = (targetPrice > 0.0 && livePrice >= targetPrice) ||
                      (stopLoss > 0.0 && livePrice <= stopLoss)
    return ranPastZone || setupDead
}

// ── Round-trip trading-cost model (B0.3a / H5, loss cause #1) ──────────────────
// A user who hits the shown "profit" target still LOSES if the round-trip cost
// isn't subtracted first. These are honest, deliberately slightly-conservative
// estimates covering BOTH legs (buy + sell). The cost has TWO parts:
//
//  (1) A PROPORTIONAL part — a FRACTION OF POSITION VALUE (entry price × quantity)
//      for the statutory + exchange charges that genuinely scale with turnover:
//        • INTRADAY ≈ 0.12% — STT 0.025% on the sell leg + exchange txn charge
//                    + 18% GST on (brokerage + txn) + SEBI + stamp.
//        • DELIVERY ≈ 0.20% — higher STT (0.1% on BOTH legs) dominates the total.
//      plus a 0.05% slippage buffer for the gap between shown price and real fill.
//
//  (2) A FLAT BROKERAGE FLOOR — discount brokers (Groww/Zerodha style) bill each
//      executed order at max(0.03% of turnover, ₹20) PER LEG, so a tiny trade
//      still pays ~₹20/order → ~₹40 per round trip even when 0.03% would be a few
//      paise. Without this floor the old purely-proportional model UNDER-counted
//      charges on exactly the small positions where intraday first unlocks
//      (H5 / B0.3a: real charges are ~₹50–60 per round trip on a ~₹10k position,
//      but 0.17% of ₹10k is only ~₹17). The 0.03% term takes over once the trade
//      is big enough that 0.03% > ₹20 (turnover > ~₹66,667 per leg), so large
//      trades stay purely proportional.
//
// Under-promising profit is the safe error to make: far better than showing a
// green target the user cannot actually reach after costs.
const val INTRADAY_COST_RATE       = 0.0012   // 0.12% of turnover (STT/txn/GST/SEBI/stamp)
const val DELIVERY_COST_RATE       = 0.0020   // 0.20% of turnover (STT dominates)
const val SLIPPAGE_BUFFER_RATE     = 0.0005   // 0.05% buffer for entry+exit slippage
const val BROKERAGE_RATE           = 0.0003   // 0.03% of turnover per leg (discount-broker brokerage)
const val FLAT_BROKERAGE_PER_ORDER = 20.0     // ₹20 flat floor per executed order/leg (~₹40 round trip)

/**
 * Honest round-trip cost in rupees for a position worth [tradeValueRs]
 * (entry price × quantity). [intraday] true for same-day square-off (default),
 * false for delivery. Combines the proportional statutory/exchange/slippage part
 * with a flat per-order brokerage floor — max(0.03% of turnover, ₹20) per leg,
 * the way discount brokers charge — so tiny trades carry the real ~₹40 round-trip
 * minimum. Used to turn a gross target into a net figure and to reject setups
 * whose charges would eat the edge. Never negative.
 */
fun roundTripCostRs(tradeValueRs: Double, intraday: Boolean = true): Double {
    if (tradeValueRs <= 0.0) return 0.0
    val rate = (if (intraday) INTRADAY_COST_RATE else DELIVERY_COST_RATE) + SLIPPAGE_BUFFER_RATE
    val proportional = tradeValueRs * rate
    // Brokerage floor: max(0.03% of turnover, ₹20) per executed order (leg), so a
    // small trade still pays the real ~₹40 round-trip minimum instead of a few paise.
    val brokeragePerLeg = maxOf(tradeValueRs * BROKERAGE_RATE, FLAT_BROKERAGE_PER_ORDER)
    return proportional + 2.0 * brokeragePerLeg
}

/** Round-trip charges (₹) for holding [qty] shares of this signal (see roundTripCostRs). */
fun TradingSignal.estimatedCostFor(qty: Int, intraday: Boolean = true): Double =
    roundTripCostRs(entryPrice * qty, intraday)

/**
 * Net rupee profit at Target-1 AFTER charges for [qty] shares — the only profit
 * figure safe to show a beginner (A5/B6). Can be negative; if it is, the setup
 * should not be presented as a buy.
 */
fun TradingSignal.netProfitFor(qty: Int, intraday: Boolean = true): Double =
    expectedProfit * qty - estimatedCostFor(qty, intraday)

/**
 * Honest label for [confidence] (I1/A5/B6). Confidence is a 0–100 CHECKLIST
 * SCORE of how many good signs lined up — NOT a probability of profit and NOT a
 * guarantee. The UI must use wording like this and must never present the number
 * as a "% chance of profit". Simple English (A4).
 *
 * Bands are LOCKED by the corrected spec (B3): 90+ Strong / 84–89 Okay /
 * 70–83 Weak — and they MUST match verdictWord in TradingScreen exactly, so the
 * one-word verdict and this "See more" sentence can never disagree.
 */
val TradingSignal.confidenceMeaning: String
    get() {
        val strength = when {
            confidence >= 90 -> "Strong setup"
            confidence >= 84 -> "Okay setup"
            else             -> "Weak setup"
        }
        return "$strength — score $confidence out of 100 (how many good signs lined up). " +
               "This is NOT the chance of profit and NOT a guarantee."
    }

/**
 * Honest wording for [upsideProbability] (B6): it is a rough model-derived LEAN
 * toward an up-move, NOT a measured win rate. Surfacing the bare number as a
 * "% chance of profit" over-promises — pair it with this caveat.
 */
val TradingSignal.upsideProbabilityMeaning: String
    get() = "Model leans up (~$upsideProbability out of 100) — an estimate from the signs, not a measured win rate."

/**
 * A Groww order awaiting the user's "Order placed? Yes/No" answer (B0.2b).
 * Persisted to DataStore BEFORE Groww opens, so a process death while the user
 * is inside Groww can never lose the confirm flow (locked owner decision #3).
 */
data class PendingOrder(
    val symbol: String,
    val name: String,
    val orderType: String,      // OrderType.name
    val price: Double,          // suggested/live price at launch time
    val qty: Int,               // recommended quantity at launch time
    val stopLoss: Double,
    val target1: Double,
    val target2: Double,
    val target3: Double,
    val timestampMs: Long
)

/**
 * The right earning path for the user's money (B0.3a) — small money should not do
 * intraday, because ~₹50–60 round-trip charges eat the tiny profit.
 */
enum class CapitalPath { SIP_ONLY, DELIVERY, FULL }

fun capitalPathFor(capital: Int): CapitalPath = when {
    capital in 1 until 2000      -> CapitalPath.SIP_ONLY   // charges eat the profit
    capital in 2000 until 10000  -> CapitalPath.DELIVERY   // delivery only, intraday discouraged
    else                         -> CapitalPath.FULL       // >= 10000 (or 0 = not set yet)
}

data class MarketHealth(
    val trend: String,
    val riskLevel: RiskLevel,
    val niftyChange: Double,
    val marketState: String,
    val niftyPrice: Double = 0.0,
    // false = the live Nifty fetch failed and these are placeholder values.
    // The UI must show "data unavailable" instead of presenting them as real.
    val isAvailable: Boolean = true
)

/**
 * Real sector performance, aggregated from the LIVE prices of the sector's
 * constituent stocks in the watchlist. No synthetic values.
 */
data class SectorPerformance(
    val sectorName: String,      // display name, e.g. "Banking"
    val avgChangePct: Double,    // average of constituents' today % change (momentum)
    val breadthPct: Float,       // fraction of constituents trading above their 50-DMA (0..1)
    val advancers: Int,          // constituents up today
    val decliners: Int,          // constituents down today
    val count: Int               // number of constituents with data
)

/**
 * Real market breadth, aggregated from the LIVE prices of the whole watchlist
 * universe. No placeholders — null when no data has loaded yet.
 */
data class MarketBreadthData(
    val advances: Int,
    val declines: Int,
    val unchanged: Int,
    val advanceDeclineRatio: Double,
    val newHighs: Int,            // at/near 52-week high today
    val newLows: Int,             // at/near 52-week low today
    val pctAboveMa50: Double,     // 0..1 fraction of universe above its 50-DMA
    val healthScore: Int,         // 0..100 composite of breadth + trend
    val total: Int
)

/** Lightweight snapshot of a stock shown in market-watch even when no signal qualifies */
data class MarketMove(
    val symbol: String,
    val price: Double,
    val changePercent: Double,
    val volumeRatio: Double   // vs 3-month avg; >1.5 = above average
)

/** Live market index (NIFTY/SENSEX/etc.) for the Stock-tab ticker strip */
data class IndexQuote(
    val symbol: String,
    val name: String,
    val price: Double,
    val change: Double,
    val changePercent: Double
) { val isUp: Boolean get() = change >= 0 }

/** Result row for the global "search any stock in the world" feature */
data class StockSearchResult(
    val symbol: String,     // full Yahoo symbol, e.g. RELIANCE.NS, AAPL, 7203.T
    val name: String,
    val exchange: String,   // display exchange, e.g. NSE, NASDAQ, Tokyo
    val quoteType: String   // EQUITY | ETF
)

// ── Screener & Research models ─────────────────────────────────────────────

data class StockScreenerItem(
    val symbol: String,
    val name: String,
    val currentPrice: Double,
    val changePercent: Double,
    val volume: Long,
    val avgVolume: Long,
    val periodLow: Double,
    val periodHigh: Double,
    val low52w: Double,
    val high52w: Double,
    val distFromPeriodLowPct: Double,   // % above period low (0 = AT the low)
    val distFromPeriodHighPct: Double,  // % below period high (0 = AT the high)
    val historicalCloses: List<Double>, // last 30 closes for sparkline
    val ma50: Double = 0.0,
    val ma200: Double = 0.0,
    val marketState: String = "CLOSED",
    val periodDays: Int = 20,
    val volRatio: Double = 1.0
)

data class ResearchNewsItem(
    val headline: String,
    val summary: String,
    val source: String,
    val url: String,
    val publishedAt: Long,
    val sentiment: String   // POSITIVE | NEGATIVE | NEUTRAL
)

data class AiPrediction(
    val recommendation: String = "",  // BUY | SELL | HOLD | WAIT
    val targetPrice: Double = 0.0,
    val stopLoss: Double = 0.0,
    val confidence: Int = 0,
    val bullishCase: String = "",
    val bearishCase: String = "",
    val reasoning: String = "",
    val keyRisks: List<String> = emptyList(),
    val modelUsed: String = "",
    val isLoading: Boolean = false,
    val error: String? = null
)

data class StockResearchData(
    val symbol: String = "",
    val name: String = "",
    val currentPrice: Double = 0.0,
    val changePercent: Double = 0.0,
    val changeAbsolute: Double = 0.0,
    val dayHigh: Double = 0.0,
    val dayLow: Double = 0.0,
    val volume: Long = 0L,
    val avgVolume: Long = 0L,
    val high52w: Double = 0.0,
    val low52w: Double = 0.0,
    val ma50: Double = 0.0,
    val ma200: Double = 0.0,
    val historicalCloses: List<Double> = emptyList(),
    val news: List<ResearchNewsItem> = emptyList(),
    val aiPrediction: AiPrediction = AiPrediction(),
    val marketState: String = "",
    // Quote currency from the Yahoo chart meta ("INR", "USD", …) — a US stock must
    // never be shown or spoken as rupees (a ~90x error for a beginner)
    val currency: String = "INR",
    // Wall-clock ms of the last SUCCESSFUL fetch — feeds the honest
    // "Updated HH:mm" / "Cached — HH:mm" line (rule B2)
    val lastUpdatedAt: Long = 0L,
    val isLoading: Boolean = false,
    val error: String? = null
)

// ── Signal badge for stock cards ──────────────────────────────────────────────

data class SignalBadge(
    val emoji: String,
    val label: String,
    val isPositive: Boolean,
    val isNeutral: Boolean = false
)

fun StockScreenerItem.computeSignalBadge(): SignalBadge {
    val aboveMa50  = ma50 > 0 && currentPrice > ma50
    val aboveMa200 = ma200 > 0 && currentPrice > ma200
    val strongVol  = volRatio >= 1.5

    val bullScore = (if (aboveMa50) 1 else 0) + (if (aboveMa200) 1 else 0) +
                    (if (changePercent > 0.5) 1 else 0) + (if (strongVol) 1 else 0) +
                    (if (distFromPeriodLowPct < 5.0) 1 else 0)

    val bearScore = (if (!aboveMa50 && ma50 > 0) 1 else 0) + (if (!aboveMa200 && ma200 > 0) 1 else 0) +
                    (if (changePercent < -0.5) 1 else 0) + (if (distFromPeriodHighPct < 3.0) 1 else 0)

    return when {
        bullScore >= 3 -> SignalBadge("✅", "Buy Now",    isPositive = true)
        bearScore >= 3 -> SignalBadge("🔴", "Sell Now",  isPositive = false)
        bullScore > bearScore -> SignalBadge("📈", "Watch to Buy", isPositive = true)
        bearScore > bullScore -> SignalBadge("📉", "Caution",      isPositive = false)
        else           -> SignalBadge("⏳", "Wait",       isPositive = true, isNeutral = true)
    }
}

// (The in-memory "JournalEntry" notebook is gone — it was a second, never-synced
// tracking system that fed no loss protection and evaporated on process death.
// The persisted TrackedTrade rows are the ONE journal now.)

data class InterdayUiState(
    val isLoading: Boolean = false,
    val signals: List<TradingSignal> = emptyList(),
    val marketHealth: MarketHealth? = null,
    val error: String? = null,
    val lastUpdated: String = "",
    val isDailyLimitBreached: Boolean = false,
    val dailyLoss: Double = 0.0,
    val maxDailyLoss: Double = 5000.0,
    val isUsingCachedData: Boolean = false,
    // True only after a live fetch COMPLETED and FAILED (leaving stale cache) —
    // the forced "turn on internet" popup must key on this, never on a fetch
    // that is still running (the false-popup-on-every-cold-start bug).
    val lastFetchFailed: Boolean = false,
    val marketClosed: Boolean = false,
    val topMovers: List<MarketMove> = emptyList(),
    val analyzedCount: Int = 0,
    // Same 4 stocks for the whole day (IST) — prices refresh, the stocks don't churn
    val dailyPicks: List<TradingSignal> = emptyList(),
    // ── Screener / filter features ────────────────────────
    val allScreenerItems: List<StockScreenerItem> = emptyList(),
    val filteredScreenerItems: List<StockScreenerItem> = emptyList(),
    val screenerFilter: PriceFilterType = PriceFilterType.NONE,
    val screenerDays: Int = 20,
    val screenerSearchQuery: String = "",
    // ── AI Trade Tab extras ───────────────────────────────
    val vix: Double = 0.0,
    val aiSearchQuery: String = "",
    val aiSearchResult: TradingSignal? = null,
    // Whether the searched symbol is even in the analyzed universe — an honest
    // "not checked" must never read like an analyzed WAIT verdict (B6).
    val aiSearchInUniverse: Boolean = true,
    val capitalInput: Double = 0.0,
    val calculatedQty: Int = 0,
    val marketTimerLabel: String = ""       // e.g. "Market opens in 2h 15m"
)
