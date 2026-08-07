package com.example.myapplication3.intraday

import android.util.Log
import kotlin.math.abs

object SignalEngine {

    private const val TAG = "SignalEngine"
    private const val MIN_CONFIDENCE = 70      // 70+ required; bearish-market signals top out ~71 due to RSI oversold offsetting bear score
    private const val DEFAULT_MAX_RISK_RS = 1000.0

    data class StockQuote(
        val symbol: String,
        val name: String,
        val price: Double,
        val changePercent: Double,
        val volume: Long,
        val avgVolume: Long,
        val high52w: Double,
        val low52w: Double,
        val ma50: Double,   // 0.0 if not available — handled gracefully
        val ma200: Double,  // 0.0 if not available — handled gracefully
        val dayHigh: Double,
        val dayLow: Double,
        val marketState: String,
        val historicalCloses: List<Double> = emptyList(),  // 1-year daily closes for period analysis
        val gapPercent: Double = 0.0   // open vs prev-close gap — feeds RiskGuard Rule 5
    )

    /** Analyze all quotes, return qualifying signals sorted by confidence. */
    fun analyzeAll(
        quotes: List<StockQuote>,
        marketTrend: String,
        // Optional live ticks (symbol → last traded price) from the Angel One feed.
        // When a tick is present for a stock and it has already run past the fixed
        // entry zone / target / stop, that signal is flagged chanceGone so the UI
        // can disable buying (loss cause #3). Keyed by q.symbol, or its
        // ".NS"-stripped / uppercased form — all three are tried. Default null =
        // no live data (backward compatible with existing callers).
        livePrices: Map<String, Double>? = null,
        // Optional REAL intraday reads (symbol → IntradayScorer.read() result from
        // Angel One 5-minute candles). When present for a stock its score joins the
        // point total and maxPossible grows by 20 (honest H3 dynamic scoring); its
        // plain-words verdict is appended to the "Why" list. Default null = daily
        // data only — behavior is bit-for-bit unchanged (B8 free-forever path).
        intradayReads: Map<String, IntradayRead>? = null,
        // Optional REAL sector strength (H2 rotation), computed each cycle by
        // IntradayRepository from the live universe: symbol → "STRONG" | "WEAK".
        // A symbol ABSENT from the map means its sector is neutral today or had
        // too little data — no points either way AND no denominator growth (H3:
        // missing data never punishes). Keyed like livePrices (q.symbol, its
        // ".NS"-stripped or uppercased form). Default null = no sector context —
        // behavior is bit-for-bit unchanged (B8).
        sectorStrength: Map<String, String>? = null
    ): List<TradingSignal> {
        val signals = quotes.mapNotNull { q ->
            val lp = livePrices?.let { m ->
                m[q.symbol]
                    ?: m[q.symbol.removeSuffix(".NS")]
                    ?: m[q.symbol.removeSuffix(".NS").uppercase()]
            }
            val ir = intradayReads?.let { m ->
                m[q.symbol]
                    ?: m[q.symbol.removeSuffix(".NS")]
                    ?: m[q.symbol.removeSuffix(".NS").uppercase()]
            }
            val tone = sectorStrength?.let { m ->
                m[q.symbol]
                    ?: m[q.symbol.removeSuffix(".NS")]
                    ?: m[q.symbol.removeSuffix(".NS").uppercase()]
            }
            runCatching { analyze(q, marketTrend, lp, ir, tone) }.getOrNull()
        }
            .filter { it.action != SignalAction.WAIT }
            .sortedByDescending { it.confidence }
        Log.d(TAG, "analyzeAll: ${quotes.size} quotes → ${signals.size} qualifying signals")
        return signals
    }

    private fun analyze(
        q: StockQuote,
        marketTrend: String,
        livePrice: Double? = null,
        intradayRead: IntradayRead? = null,
        // H2 sector rotation: "STRONG" | "WEAK" from REAL per-sector aggregation,
        // or null (= neutral sector / no sector data → factor fully absent, H3).
        sectorTone: String? = null
    ): TradingSignal? {
        if (q.price <= 0.0) return null

        val volRatio  = if (q.avgVolume > 0) q.volume.toDouble() / q.avgVolume else 1.0
        // Real 14-day Wilder RSI only — never synthesized from a single day's move.
        val realRsi    = wilderRSI(q.historicalCloses)
        val hasRealRsi = realRsi != null
        val rangePos  = if (q.high52w > q.low52w)
            (q.price - q.low52w) / (q.high52w - q.low52w) else 0.5

        val hasMa200  = q.ma200 > 0.0
        val hasMa50   = q.ma50  > 0.0

        var bullPoints = 0
        var bearPoints = 0
        val bullReasons = mutableListOf<String>()
        val bearReasons = mutableListOf<String>()

        // ── 1. Long-term trend — 200 DMA (max 15) ────────────────────────────
        // Reasons are the user-visible "Why" lines — plain words only (I2).
        if (hasMa200) {
            when {
                q.price > q.ma200 * 1.03 -> { bullPoints += 15; bullReasons += "Rising steadily for many months — a strong stock" }
                q.price > q.ma200         -> { bullPoints += 8;  bullReasons += "Slowly rising over many months" }
                q.price < q.ma200 * 0.97  -> { bearPoints += 15; bearReasons += "Falling for many months — a weak stock" }
                else                      -> { bearPoints += 8 }
            }
        }

        // ── 2. Medium-term trend — 50 DMA (max 12) ───────────────────────────
        if (hasMa50) {
            when {
                q.price > q.ma50 * 1.02 -> { bullPoints += 12; bullReasons += "Rising well in recent weeks" }
                q.price > q.ma50         -> { bullPoints += 7 }
                q.price < q.ma50 * 0.98  -> { bearPoints += 12; bearReasons += "Falling in recent weeks" }
                else                     -> { bearPoints += 6 }
            }
        }

        // ── 3. RSI zone (max 12) — scored ONLY when a real 14-day RSI exists ──
        // When daily-close history is unavailable we add no RSI points (exactly
        // like the MA factors below) instead of fabricating a value from one day.
        // The word "RSI" never reaches the screen — translated per rule I2.
        if (hasRealRsi) {
            val rsi = realRsi!!
            when {
                rsi in 38.0..63.0 -> { bullPoints += 12; bullReasons += "Not too hot, not too cold — a good time to buy" }
                rsi in 26.0..38.0 -> { bullPoints += 9;  bullReasons += "Fell a lot recently — may bounce back up" }
                rsi < 26           -> { bullPoints += 5;  bullReasons += "Fell very hard recently — very cheap now" }
                rsi in 63.0..72.0  -> { bearPoints += 7;  bearReasons += "Has risen fast — the stock looks tired" }
                rsi > 72           -> { bearPoints += 12; bearReasons += "Risen too fast — it may fall back" }
            }
        }

        // ── 4. Volume / Institutional activity (max 15) ──────────────────────
        when {
            volRatio >= 2.5 -> { bullPoints += 15; bullReasons += "Big investors are buying heavily today" }
            volRatio >= 1.8 -> { bullPoints += 12; bullReasons += "Much more buying than usual today" }
            volRatio >= 1.3 -> { bullPoints += 7;  bullReasons += "More buying than usual today" }
            volRatio < 0.7  -> { bearPoints += 4 }
        }

        // ── 5. 52-week range position (max 10) ────────────────────────────────
        when {
            rangePos < 0.20 -> { bullPoints += 10; bullReasons += "Near its lowest price of the year — cheap now" }
            rangePos < 0.35 -> { bullPoints += 7;  bullReasons += "Cheaper than most of this year" }
            rangePos < 0.55 -> { bullPoints += 4 }
            rangePos > 0.90 -> { bearPoints += 10; bearReasons += "Near its highest price of the year — costly now" }
            rangePos > 0.75 -> { bearPoints += 6;  bearReasons += "Costlier than most of this year" }
        }

        // ── 6. Price momentum (max 8) ─────────────────────────────────────────
        when {
            q.changePercent in  0.4..2.5  -> { bullPoints += 8; bullReasons += "Up ${String.format("%.1f",q.changePercent)}% today and moving well" }
            q.changePercent in  0.0..0.4  -> { bullPoints += 3 }
            q.changePercent in -2.5..-0.4 -> { bearPoints += 8; bearReasons += "Down ${String.format("%.1f",q.changePercent)}% today — people are selling" }
            q.changePercent < -2.5         -> { bearPoints += 10; bearReasons += "Falling fast today (${String.format("%.1f",q.changePercent)}%) — risky" }
        }

        // ── 7. Market trend alignment (max 8) ─────────────────────────────────
        when (marketTrend) {
            "BULLISH" -> bullPoints += 8
            "BEARISH" -> bearPoints += 8
        }

        // ── 8. Sector strength — H2 rotation (max ±5) ─────────────────────────
        // REAL per-sector aggregation only (average of constituents' actual %
        // change today, computed in IntradayRepository each cycle) — money flows
        // with sectors, the app follows the flow. null = neutral sector or no
        // sector data this cycle: no points AND no denominator growth below
        // (H3 pattern — exactly like the RSI/MA/intraday factors).
        when (sectorTone) {
            "STRONG" -> { bullPoints += 5; bullReasons += "Its sector is strong today" }
            "WEAK"   -> { bearPoints += 5; bearReasons += "Its sector is weak today" }
        }

        // ── Direction ─────────────────────────────────────────────────────────
        val isBuySetup  = bullPoints >= bearPoints + 8 && q.changePercent > -2.5
        val isSellSetup = bearPoints >= bullPoints + 8 && q.changePercent < 2.5
        if (!isBuySetup && !isSellSetup) return null

        val action      = if (isBuySetup) SignalAction.BUY else SignalAction.SELL
        val activePoints= if (action == SignalAction.BUY) bullPoints else bearPoints

        // ── 9. REAL intraday layer (5-min candles, max ±20) — only when a read
        // exists. The read's score is signed (+ = intraday supports buying), so a
        // BUY setup adds it as-is while a SELL setup adds its mirror; an intraday
        // picture that CONTRADICTS the daily setup honestly drags confidence down
        // (A5/B6). Floor at 0 so a hard contradiction can't go below the base.
        val intradayDelta = intradayRead?.let { r ->
            if (action == SignalAction.BUY) r.score else -r.score
        }
        val scoredPoints = if (intradayDelta != null)
            (activePoints + intradayDelta).coerceAtLeast(0) else activePoints

        // CRITICAL: maxPossible is DYNAMIC — no penalty for missing data.
        // Always-available factors: Volume(15) + Range(10) + Momentum(8) + Market(8) = 41
        val maxPossible = 15 + 10 + 8 + 8 +
                (if (hasRealRsi) 12 else 0) +
                (if (hasMa200) 15 else 0) +
                (if (hasMa50)  12 else 0) +
                (if (intradayRead != null) 20 else 0) +  // honest H3: denominator grows ONLY with real 5-min data
                (if (sectorTone != null) 5 else 0)       // H2 rotation: only when real sector data exists (H3)
        // No history: 41 | +real RSI: 53 | +ma200: 68 | +both MAs: 80 | +intraday: 100 | +sector: 105

        // Cap confidence when MA data is missing — a data-poor stock must never be
        // labeled top-tier (>=84 unlocks MEDIUM/LOW risk + beginner-safe badges).
        val dataCap = when {
            hasMa50 && hasMa200 -> 97.0
            hasMa50 || hasMa200 -> 88.0
            else                -> 82.0
        }
        val confidence = (30 + (scoredPoints.toDouble() / maxPossible * 67))
            .coerceIn(30.0, dataCap).toInt()

        Log.d(TAG, "${q.symbol}: bull=$bullPoints bear=$bearPoints intra=${intradayDelta ?: "-"} sector=${sectorTone ?: "-"} max=$maxPossible conf=$confidence action=$action")

        if (confidence < MIN_CONFIDENCE) return null

        // ── Price levels (ATR-based) ───────────────────────────────────────────
        val dayRange = if (q.dayHigh > q.dayLow) q.dayHigh - q.dayLow else q.price * 0.015
        val atr      = maxOf(dayRange * 1.5, q.price * 0.012)

        val isBuy   = action == SignalAction.BUY
        val sl      = if (isBuy) q.price - atr * 1.0 else q.price + atr * 1.0
        val trailSl = if (isBuy) q.price + atr * 1.2 else q.price - atr * 1.2
        val t1Atr   = if (isBuy) q.price + atr * 2.0 else q.price - atr * 2.0
        val t2Atr   = if (isBuy) q.price + atr * 3.5 else q.price - atr * 3.5
        val t3Atr   = if (isBuy) q.price + atr * 5.0 else q.price - atr * 5.0
        // ── H5 (A5 honesty): respect REAL resistance/support ──────────────────
        // Pure-ATR targets can sail far past where price actually reversed before.
        // Clamp each target to the nearest real swing level from the 1-year closes,
        // so the target — and the profit computed from it below — reflects where
        // sellers (BUY) / buyers (SELL) sit, not where a formula wishes. When
        // history is short or no level sits before a target, the ATR target is
        // kept unchanged (behavior-preserving). The charges gate downstream then
        // re-judges reward:risk on the REALISTIC target, so a trade whose honest
        // Target-1 no longer clears the bar is correctly dropped.
        val (t1, t2, t3) = respectLevels(q.historicalCloses, q.price, isBuy, t1Atr, t2Atr, t3Atr)

        val profit = abs(t1 - q.price)          // GROSS per-share reward at Target-1
        val loss   = abs(q.price - sl)          // per-share risk to the stop
        if (loss <= 0.0) return null
        val rr     = profit / loss

        // ── Charges gate (B0.3a, loss cause #1) ────────────────────────────────
        // These are INTRADAY signals (square-off by 3:15 PM), so subtract the
        // honest intraday round-trip cost + slippage BEFORE judging the reward.
        // Cost is a fraction of position value, so on a PER-SHARE basis it is
        // qty-independent — this gate stays valid after the ViewModel re-sizes the
        // position to the user's real capital. A setup whose charges turn the
        // profit negative, OR drop the after-cost reward:risk below 1.8, is
        // REJECTED here and never shown as a green target.
        val costPerShare      = q.price * (INTRADAY_COST_RATE + SLIPPAGE_BUFFER_RATE)
        val netProfitPerShare = profit - costPerShare
        val netRr             = netProfitPerShare / loss
        if (netProfitPerShare <= 0.0 || netRr < 1.8) return null

        // ── Probability ───────────────────────────────────────────────────────
        val upsideProb = if (action == SignalAction.BUY)
            (35 + (scoredPoints.toDouble() / maxPossible * 40)).toInt().coerceIn(40, 80)
        else
            (25 + ((maxPossible - scoredPoints).toDouble() / maxPossible * 30)).toInt().coerceIn(20, 55)
        val downProb   = maxOf(10, 70 - upsideProb)
        val sideways   = maxOf(5, 100 - upsideProb - downProb)

        val riskLevel = when {
            // rr is a geometric constant 2.0 (T1 = 2·ATR, SL = 1·ATR), so an
            // rr >= 2.5 term would make LOW/beginner-safe unreachable — confidence decides.
            confidence >= 90 -> RiskLevel.LOW
            confidence >= 84 -> RiskLevel.MEDIUM
            else             -> RiskLevel.HIGH
        }

        val institutionalFlow = when {
            volRatio >= 2.0 && action == SignalAction.BUY  -> "BUYING"
            volRatio >= 2.0 && action == SignalAction.SELL -> "SELLING"
            volRatio >= 1.4                                -> "ACCUMULATING"
            else                                           -> "NEUTRAL"
        }

        val sectorStrength = when {
            hasMa50 && hasMa200 && q.price > q.ma50 && q.price > q.ma200 -> "STRONG"
            hasMa200 && q.price > q.ma200                                  -> "MODERATE"
            else                                                           -> "NEUTRAL"
        }

        // Floor, never round up (H6). This engine-level default is re-sized by the
        // ViewModel from the user's REAL settings (UserSettingsStore) after fetch;
        // the ₹1,000 default only covers paths that skip the ViewModel.
        val rawQty     = (DEFAULT_MAX_RISK_RS / loss).toInt()
        val qty        = maxOf(1, rawQty)
        // Honest rupee figures for this (placeholder) position size. The ViewModel
        // re-sizes qty to the user's real capital and should recompute both with
        // TradingSignal.estimatedCostFor() / netProfitFor().
        val estimatedCostRs = roundTripCostRs(q.price * qty, intraday = true)
        val netProfitRs     = profit * qty - estimatedCostRs
        val entryLowV  = q.price - atr * 0.15
        val entryHighV = q.price + atr * 0.15
        val entryZone  = "₹${String.format("%.0f", entryLowV)}–₹${String.format("%.0f", entryHighV)}"
        val reasons = (if (action == SignalAction.BUY) bullReasons else bearReasons).take(5)
            .ifEmpty { listOf("Several good signs lined up together") }
            // Append the live 5-min picture in plain words (I2). Shown even when it
            // disagrees with the setup — the user deserves the honest live view (A5).
            .let { base -> if (intradayRead != null) base + intradayRead.verdictPlain else base }

        val signal = TradingSignal(
            stockSymbol         = q.symbol.removeSuffix(".NS"),
            stockName           = q.name.take(28),
            action              = action,
            currentPrice        = q.price,
            changePercent       = q.changePercent,
            entryPrice          = q.price,
            entryZone           = entryZone,
            entryLow            = entryLowV,
            entryHigh           = entryHighV,
            targetPrice         = t1,
            target2             = t2,
            target3             = t3,
            stopLoss            = sl,
            trailingStop        = trailSl,
            expectedProfit      = profit,
            expectedLoss        = loss,
            estimatedCostRs     = estimatedCostRs,
            netProfitRs         = netProfitRs,
            riskLevel           = riskLevel,
            confidence          = confidence,
            reasons             = reasons,
            validUntil          = "Till 3:15 PM",
            tradeValidityTime   = "Till 3:15 PM today",
            // B6: "Beginner safe" may only ever ride on a BUY — a short-sell is
            // never a beginner trade, no matter how high its checklist score.
            isBeginnerSafe      = action == SignalAction.BUY && riskLevel == RiskLevel.LOW,
            marketState         = q.marketState,
            recommendedQty      = qty,
            riskReward          = rr,
            upsideProbability   = upsideProb,
            downsideProbability = downProb,
            sidewaysProbability = sideways,
            sectorStrength      = sectorStrength,
            marketTrend         = marketTrend,
            institutionalFlow   = institutionalFlow,
            // RiskGuard real inputs
            volume              = q.volume,
            avgVolume           = q.avgVolume,
            gapPercent          = q.gapPercent   // open vs prev-close, parsed in IntradayRepository
        )

        // Live-tick staleness (loss cause #3): if a live price was supplied and it
        // has already run past the fixed entry zone / target / stop, flag the
        // signal so the UI disables buying. Reuses buyChancePassed so the rule is
        // single-sourced (BUY-only; SELL signals stay chanceGone = false).
        return if (livePrice != null && livePrice > 0.0)
            signal.copy(chanceGone = signal.buyChancePassed(livePrice))
        else signal
    }

    /**
     * H5 — respect real levels. Returns (t1,t2,t3) clamped so no target sails past
     * the nearest swing high (BUY) / low (SELL) that price actually reversed at in
     * the last ~6 months. Falls back to the ATR targets when history is too short
     * or no level sits before a target (behavior-preserving).
     */
    private fun respectLevels(
        closes: List<Double>, entry: Double, isBuy: Boolean,
        t1Atr: Double, t2Atr: Double, t3Atr: Double
    ): Triple<Double, Double, Double> {
        val pivots = swingPivots(closes, highs = isBuy)
        if (pivots.isEmpty()) return Triple(t1Atr, t2Atr, t3Atr)
        return if (isBuy) {
            val res = pivots.filter { it > entry }.sorted()                    // resistances above entry, ascending
            val a = res.firstOrNull { it < t1Atr } ?: t1Atr                    // nearest wall before ATR T1
            val b = (res.firstOrNull { it > a && it < t2Atr } ?: t2Atr).coerceAtLeast(a)
            val c = (res.firstOrNull { it > b && it < t3Atr } ?: t3Atr).coerceAtLeast(b)
            Triple(a, b, c)
        } else {
            val sup = pivots.filter { it < entry }.sortedDescending()          // supports below entry, nearest first
            val a = sup.firstOrNull { it > t1Atr } ?: t1Atr                    // nearest floor before ATR T1 (target < entry)
            val b = (sup.firstOrNull { it < a && it > t2Atr } ?: t2Atr).coerceAtMost(a)
            val c = (sup.firstOrNull { it < b && it > t3Atr } ?: t3Atr).coerceAtMost(b)
            Triple(a, b, c)
        }
    }

    /** Local swing highs (highs=true) / lows (else) from ~6 months of closes (±3 bars). */
    private fun swingPivots(closes: List<Double>, highs: Boolean): List<Double> {
        val k = 3
        if (closes.size < 2 * k + 1) return emptyList()
        val w = closes.takeLast(140)
        val out = ArrayList<Double>()
        for (i in k until w.size - k) {
            val v = w[i]
            var pivot = true
            for (j in i - k..i + k) {
                if (highs) { if (w[j] > v) { pivot = false; break } }
                else       { if (w[j] < v) { pivot = false; break } }
            }
            if (pivot) out += v
        }
        return out
    }

    /** Real 14-day Wilder RSI from daily closes; null when history is too short. */
    private fun wilderRSI(closes: List<Double>, period: Int = 14): Double? {
        if (closes.size < period + 1) return null
        // Seed: simple average of the first `period` changes
        var avgGain = 0.0
        var avgLoss = 0.0
        for (i in 1..period) {
            val change = closes[i] - closes[i - 1]
            if (change > 0) avgGain += change else avgLoss -= change
        }
        avgGain /= period
        avgLoss /= period
        // Wilder smoothing over the remaining history
        for (i in period + 1 until closes.size) {
            val change = closes[i] - closes[i - 1]
            avgGain = (avgGain * (period - 1) + maxOf(change, 0.0)) / period
            avgLoss = (avgLoss * (period - 1) + maxOf(-change, 0.0)) / period
        }
        if (avgLoss == 0.0) return 100.0
        return 100.0 - 100.0 / (1.0 + avgGain / avgLoss)
    }

    // Legacy API for ScannerWorker
    fun generateSignal(
        symbol: String, name: String,
        currentPrice: Double, changePercent: Double,
        volume: Long, avgVolume: Long,
        high52w: Double, low52w: Double,
        ma50: Double, ma200: Double,
        closePrices: List<Double> = emptyList(),
        livePrice: Double? = null   // optional live tick → sets chanceGone when past the zone
    ): TradingSignal {
        val q = StockQuote(
            symbol = symbol, name = name, price = currentPrice,
            changePercent = changePercent, volume = volume, avgVolume = avgVolume,
            high52w = high52w, low52w = low52w, ma50 = ma50, ma200 = ma200,
            // no fabricated range — dayHigh==dayLow triggers the engine's honest fallback
            dayHigh = currentPrice, dayLow = currentPrice,
            marketState = "REGULAR",
            historicalCloses = closePrices  // enables real Wilder RSI when history exists
        )
        return analyze(q, "SIDEWAYS", livePrice) ?: TradingSignal(
            stockSymbol = symbol.removeSuffix(".NS"), stockName = name,
            action = SignalAction.WAIT, currentPrice = currentPrice,
            entryPrice = currentPrice, targetPrice = currentPrice, stopLoss = currentPrice,
            expectedProfit = 0.0, expectedLoss = 0.0, riskLevel = RiskLevel.MEDIUM,
            confidence = 0, reasons = emptyList(), validUntil = "", isBeginnerSafe = false
        )
    }
}