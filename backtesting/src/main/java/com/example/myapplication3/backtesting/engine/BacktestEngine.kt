package com.example.myapplication3.backtesting.engine

import com.example.myapplication3.core.common.AppException
import com.example.myapplication3.core.common.Result
import com.example.myapplication3.core.domain.model.*
import com.example.myapplication3.database.dao.BacktestResultDao
import com.example.myapplication3.database.entity.BacktestResultEntity
import com.example.myapplication3.scanner.detector.SignalDetector
import com.example.myapplication3.scanner.technical.TechnicalIndicators
import com.google.gson.Gson
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.*
import com.example.myapplication3.core.common.mean
import com.example.myapplication3.core.common.stdDev
import com.example.myapplication3.core.common.percentile

@Singleton
class BacktestEngine @Inject constructor(
    private val signalDetector: SignalDetector,
    private val backtestResultDao: BacktestResultDao,
    private val gson: Gson
) {

    suspend fun runBacktest(
        symbol: String,
        exchange: Exchange,
        candles: List<Candle>,
        period: BacktestPeriod,
        minConfidence: Double = 0.6,
        minRR: Double = 2.0
    ): Result<BacktestResult> {
        if (candles.size < 100) {
            return Result.Error(AppException.InsufficientDataException("Need at least 100 candles for backtesting"))
        }

        val trades = simulateTrades(symbol, candles, minRR)
        if (trades.isEmpty()) {
            return Result.Error(AppException.InsufficientDataException("No trades generated in backtest period"))
        }

        val metrics = calculateMetrics(trades, candles)
        val monteCarloResult = runMonteCarloSimulation(trades, 1000)
        val walkForwardResults = runWalkForward(symbol, candles, minRR)

        val result = BacktestResult(
            id = UUID.randomUUID().toString(),
            strategyName = "AI Intraday v1",
            fromDate = candles.first().timestamp,
            toDate = candles.last().timestamp,
            period = period,
            totalTrades = trades.size,
            winningTrades = trades.count { it.pnl > 0 },
            losingTrades = trades.count { it.pnl < 0 },
            winRate = metrics.winRate,
            profitFactor = metrics.profitFactor,
            sharpeRatio = metrics.sharpeRatio,
            sortinoRatio = metrics.sortinoRatio,
            maxDrawdown = metrics.maxDrawdown,
            cagr = metrics.cagr,
            expectancy = metrics.expectancy,
            totalReturn = metrics.totalReturn,
            averageWin = trades.filter { it.pnl > 0 }.map { it.pnl }.let { if (it.isEmpty()) 0.0 else it.average() },
            averageLoss = trades.filter { it.pnl < 0 }.map { it.pnl }.let { if (it.isEmpty()) 0.0 else it.average() },
            largestWin = trades.maxOfOrNull { it.pnl } ?: 0.0,
            largestLoss = trades.minOfOrNull { it.pnl } ?: 0.0,
            averageHoldingPeriodMinutes = trades.map { it.holdingMinutes }.average().toInt(),
            createdAt = System.currentTimeMillis(),
            monteCarloResults = monteCarloResult,
            walkForwardResults = walkForwardResults
        )

        backtestResultDao.insert(result.toEntity())
        return Result.Success(result)
    }

    private data class SimulatedTrade(
        val entryTime: Long,
        val exitTime: Long,
        val entryPrice: Double,
        val exitPrice: Double,
        val direction: TradeDirection,
        val pnl: Double,
        val holdingMinutes: Int
    )

    private fun simulateTrades(symbol: String, candles: List<Candle>, minRR: Double): List<SimulatedTrade> {
        val trades = mutableListOf<SimulatedTrade>()
        val windowSize = 100

        var i = windowSize
        while (i < candles.size - 20) {
            val window = candles.subList(i - windowSize, i)
            val current = candles[i]

            val atrValues = TechnicalIndicators.atr(window, 14)
            // Note: ?: continue here previously skipped i++ — an infinite loop if
            // ATR ever returned empty. Guard with explicit increment on EVERY path.
            val atr = atrValues.lastOrNull()
            if (atr == null || atr == 0.0) { i++; continue }

            val signals = signalDetector.detectAll(symbol, window, createBarMarketData(symbol, current, candles[i - 1]))
            if (signals.isEmpty()) { i++; continue }

            val bullish = signals.count { it.type.isBullish == true }
            val bearish = signals.count { it.type.isBullish == false }
            if (bullish == bearish) { i++; continue }

            val direction = if (bullish > bearish) TradeDirection.LONG else TradeDirection.SHORT
            val entry = current.close
            // Match the LIVE SignalEngine geometry: SL = 1·ATR, T1 = 2·ATR → RR = 2.0.
            // The old 1.5·ATR stop made RR a constant 1.33, which the default
            // minRR = 2.0 filter rejected on EVERY bar — so the backtest could
            // never generate a single trade ("No trades generated" every run).
            val sl = if (direction == TradeDirection.LONG) entry - atr * 1.0 else entry + atr * 1.0
            val target = if (direction == TradeDirection.LONG) entry + atr * 2.0 else entry - atr * 2.0

            val rr = abs(target - entry) / abs(entry - sl)
            // FP tolerance — 2·ATR / 1·ATR computes as 1.9999999999 with real prices
            if (rr < minRR - 1e-6) { i++; continue }

            var exitPrice = entry
            var exitIdx = i
            var j = i + 1
            while (j < minOf(i + 30, candles.size)) {
                val c = candles[j]
                if (direction == TradeDirection.LONG) {
                    if (c.low <= sl) { exitPrice = sl; exitIdx = j; break }
                    if (c.high >= target) { exitPrice = target; exitIdx = j; break }
                } else {
                    if (c.high >= sl) { exitPrice = sl; exitIdx = j; break }
                    if (c.low <= target) { exitPrice = target; exitIdx = j; break }
                }
                j++
            }
            if (exitIdx == i) { exitPrice = candles[minOf(i + 15, candles.size - 1)].close; exitIdx = minOf(i + 15, candles.size - 1) }

            val pnl = if (direction == TradeDirection.LONG) exitPrice - entry else entry - exitPrice
            val holdingMinutes = ((candles[exitIdx].timestamp - current.timestamp) / 60_000).toInt()

            trades.add(SimulatedTrade(current.timestamp, candles[exitIdx].timestamp, entry, exitPrice, direction, pnl, holdingMinutes))
            i = exitIdx + 1
        }
        return trades
    }

    private data class BacktestMetrics(
        val winRate: Double, val profitFactor: Double, val sharpeRatio: Double,
        val sortinoRatio: Double, val maxDrawdown: Double, val cagr: Double,
        val expectancy: Double, val totalReturn: Double
    )

    private fun calculateMetrics(trades: List<SimulatedTrade>, allCandles: List<Candle>): BacktestMetrics {
        if (trades.isEmpty()) return BacktestMetrics(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)

        val wins = trades.filter { it.pnl > 0 }
        val losses = trades.filter { it.pnl < 0 }
        val winRate = wins.size.toDouble() / trades.size
        val grossProfit = wins.sumOf { it.pnl }
        val grossLoss = abs(losses.sumOf { it.pnl })
        val profitFactor = if (grossLoss > 0) grossProfit / grossLoss else grossProfit
        val totalReturn = trades.sumOf { it.pnl }

        val returns = trades.map { it.pnl / it.entryPrice }
        val avgReturn = returns.mean()
        val stdReturn = returns.stdDev()
        val sharpeRatio = if (stdReturn > 0) (avgReturn / stdReturn) * sqrt(252.0) else 0.0

        val downside = returns.filter { it < 0 }
        val downsideStd = downside.stdDev()
        val sortinoRatio = if (downsideStd > 0) (avgReturn / downsideStd) * sqrt(252.0) else 0.0

        var peak = 0.0; var equity = 0.0; var maxDd = 0.0
        trades.forEach { t ->
            equity += t.pnl
            if (equity > peak) peak = equity
            val dd = if (peak > 0) (peak - equity) / peak else 0.0
            if (dd > maxDd) maxDd = dd
        }

        val daysTraded = if (allCandles.size > 1)
            (allCandles.last().timestamp - allCandles.first().timestamp) / (24 * 60 * 60 * 1000.0) else 365.0
        val years = daysTraded / 365.0
        val initialCapital = 100000.0
        val cagr = if (years > 0) (((initialCapital + totalReturn) / initialCapital).pow(1.0 / years) - 1) * 100 else 0.0

        val avgWin = if (wins.isNotEmpty()) wins.map { it.pnl }.average() else 0.0
        val avgLoss = if (losses.isNotEmpty()) losses.map { it.pnl }.average() else 0.0
        val expectancy = winRate * avgWin + (1 - winRate) * avgLoss

        return BacktestMetrics(winRate, profitFactor, sharpeRatio, sortinoRatio, maxDd * 100, cagr, expectancy, totalReturn)
    }

    private fun runMonteCarloSimulation(trades: List<SimulatedTrade>, simulations: Int): MonteCarloResult {
        val pnls = trades.map { it.pnl }
        val simulatedReturns = mutableListOf<Double>()
        val simulatedMaxDDs = mutableListOf<Double>()
        var ruinCount = 0
        val ruin = -50000.0

        repeat(simulations) {
            val shuffled = pnls.shuffled()
            var equity = 0.0; var peak = 0.0; var maxDd = 0.0
            shuffled.forEach { pnl ->
                equity += pnl
                if (equity > peak) peak = equity
                val dd = if (peak > 0) (peak - equity) / peak * 100 else 0.0
                if (dd > maxDd) maxDd = dd
            }
            simulatedReturns.add(equity)
            simulatedMaxDDs.add(maxDd)
            if (equity < ruin) ruinCount++
        }

        simulatedReturns.sort()
        simulatedMaxDDs.sort()

        return MonteCarloResult(
            simulations = simulations,
            medianReturn = simulatedReturns.percentile(50.0),
            percentile5Return = simulatedReturns.percentile(5.0),
            percentile95Return = simulatedReturns.percentile(95.0),
            medianMaxDrawdown = simulatedMaxDDs.percentile(50.0),
            percentile5MaxDrawdown = simulatedMaxDDs.percentile(5.0),
            percentile95MaxDrawdown = simulatedMaxDDs.percentile(95.0),
            probabilityOfProfit = simulatedReturns.count { it > 0 }.toDouble() / simulations,
            probabilityOfRuin = ruinCount.toDouble() / simulations
        )
    }

    private fun runWalkForward(symbol: String, candles: List<Candle>, minRR: Double): List<WalkForwardResult> {
        val results = mutableListOf<WalkForwardResult>()
        val totalSize = candles.size
        if (totalSize < 300) return results

        val trainSize = (totalSize * 0.6).toInt()
        val validSize = (totalSize * 0.2).toInt()
        val forwardSize = totalSize - trainSize - validSize

        if (forwardSize < 50) return results

        val train = candles.take(trainSize)
        val validation = candles.subList(trainSize, trainSize + validSize)
        val forward = candles.subList(trainSize + validSize, totalSize)

        val trainTrades = simulateTrades(symbol, train, minRR)
        val validTrades = simulateTrades(symbol, validation, minRR)
        val forwardTrades = simulateTrades(symbol, forward, minRR)

        results.add(WalkForwardResult(
            trainFromDate = train.first().timestamp,
            trainToDate = train.last().timestamp,
            validationFromDate = validation.first().timestamp,
            validationToDate = validation.last().timestamp,
            forwardFromDate = forward.first().timestamp,
            forwardToDate = forward.last().timestamp,
            trainReturn = trainTrades.sumOf { it.pnl },
            validationReturn = validTrades.sumOf { it.pnl },
            forwardReturn = forwardTrades.sumOf { it.pnl },
            forwardWinRate = if (forwardTrades.isEmpty()) 0.0 else forwardTrades.count { it.pnl > 0 }.toDouble() / forwardTrades.size
        ))
        return results
    }

    // Reconstruct the per-bar MarketData from REAL candles. previousClose is the
    // prior bar's close (not this bar's own close), so gap/change signals compute
    // against the true overnight move instead of a fabricated 0%.
    private fun createBarMarketData(symbol: String, candle: Candle, prev: Candle) = MarketData(
        symbol = symbol, exchange = Exchange.NSE,
        lastPrice = candle.close, open = candle.open, high = candle.high,
        low = candle.low, previousClose = prev.close,
        change = candle.close - prev.close,
        changePercent = if (prev.close > 0.0) (candle.close - prev.close) / prev.close * 100.0 else 0.0,
        volume = candle.volume, vwap = candle.vwap,
        timestamp = candle.timestamp
    )

    private fun BacktestResult.toEntity() = BacktestResultEntity(
        id = id, strategyName = strategyName, fromDate = fromDate, toDate = toDate,
        period = period.name, totalTrades = totalTrades, winningTrades = winningTrades,
        losingTrades = losingTrades, winRate = winRate, profitFactor = profitFactor,
        sharpeRatio = sharpeRatio, sortinoRatio = sortinoRatio, maxDrawdown = maxDrawdown,
        cagr = cagr, expectancy = expectancy, totalReturn = totalReturn,
        averageWin = averageWin, averageLoss = averageLoss, largestWin = largestWin,
        largestLoss = largestLoss, averageHoldingPeriodMinutes = averageHoldingPeriodMinutes,
        createdAt = createdAt,
        monteCarloJson = monteCarloResults?.let { gson.toJson(it) },
        walkForwardJson = gson.toJson(walkForwardResults)
    )

    /**
     * Result of [quickWinRate]. [scannedBars] is how many recent daily bars the
     * entry scan ACTUALLY covered — the caller must word its claim from this
     * ([coversFullYear] -> "last year", otherwise "recently"), never assume a year.
     */
    data class QuickStats(val wins: Int, val total: Int, val scannedBars: Int) {
        /** True only when the entry scan really covered ~a full trading year. */
        val coversFullYear: Boolean get() = scannedBars >= BacktestEngine.FULL_YEAR_BARS
    }

    companion object {

        /** Minimum daily closes [quickWinRate] needs: 200 MA warm-up + entry + 10-bar exit window. */
        const val QUICK_MIN_BARS = 211

        /** Entry-scan window: ~1 trading year of daily bars. */
        private const val SCAN_BARS = 250

        /** History cap: MA200 warm-up + full-year scan + 10-bar exit tail. */
        private const val MAX_QUICK_BARS = 200 + SCAN_BARS + 10

        /** Scans at least this many bars honestly count as "a year" (~11.5 months). */
        const val FULL_YEAR_BARS = 240

        /**
         * Lightweight, deterministic win-rate check for the E3a "simple-trust line".
         *
         * Why this exists instead of reusing [runBacktest]: that path needs full
         * OHLCV [Candle]s, a SignalDetector, and persists to the DAO. The trust
         * line must run from the bare daily closes the app already carries on
         * every quote, with ZERO side effects. This function is pure math —
         * no injected dependencies, no I/O, no randomness — so the same inputs
         * always produce the same [QuickStats].
         *
         * Rule simulated = the live SignalEngine at its simplest, with the SAME
         * trade geometry the engine and [simulateTrades] use:
         *   ENTRY : close > MA50 > MA200 (stacked uptrend) AND day momentum
         *           +0.4%..+2.5% (the engine's full-points momentum bucket)
         *   EXIT  : +2*ATR target or -1*ATR stop, whichever is TOUCHED first,
         *           within 10 bars; stop is checked before target on the same
         *           bar (conservative, matching simulateTrades). If neither is
         *           touched, exit at the last window bar's close.
         *
         * Win/loss is judged NET of [roundTripCostFraction] (fraction of position
         * value, e.g. the intraday cost model's rate — passed in by the app layer
         * because this module cannot depend on it): a timeout exit a hair above
         * entry is a real-money LOSS once round-trip charges are paid (B0.3a).
         * Pass 0.0 for the raw gross behaviour.
         *
         * ATR(14): true range when highs/lows are supplied (same length as
         * closes), otherwise the honest close-to-close proxy — never fabricated.
         *
         * Trades never overlap (next scan resumes after the exit bar). The entry
         * scan covers EVERY bar of the last ~[SCAN_BARS] that has a full MA200
         * behind it: with 450+ closes that is the whole trading year; with only
         * the ~250 closes of a 1-year quote it is the last ~50 bars, and
         * [QuickStats.coversFullYear] is then false so the caller must say
         * "recently", not "last year" (B6 honesty). Entries look only BACKWARD
         * (MA/ATR end at the entry bar) and exits only FORWARD — no lookahead.
         *
         * @return [QuickStats], or null when fewer than 5 trades occurred or on
         *         any error — null means "say nothing", never a made-up number
         *         (A5/B6 honesty).
         */
        fun quickWinRate(
            closes: List<Double>,
            highs: List<Double>? = null,
            lows: List<Double>? = null,
            roundTripCostFraction: Double = 0.0
        ): QuickStats? = runCatching {
            if (closes.size < QUICK_MIN_BARS) return@runCatching null

            val bars = closes.takeLast(MAX_QUICK_BARS)
            // Highs/lows are only trusted when they align 1:1 with closes.
            val hi = highs?.takeIf { it.size == closes.size }?.takeLast(MAX_QUICK_BARS)
            val lo = lows?.takeIf { it.size == closes.size }?.takeLast(MAX_QUICK_BARS)
            val size = bars.size
            if (bars.any { it <= 0.0 || !it.isFinite() }) return@runCatching null

            // Defensive: a bad rate must degrade to gross, never poison the math.
            val costFraction = roundTripCostFraction.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0

            // Prefix sums -> O(1) SMA lookups, O(n) total.
            val prefix = DoubleArray(size + 1)
            for (k in 0 until size) prefix[k + 1] = prefix[k] + bars[k]
            fun sma(endExclusive: Int, period: Int): Double =
                (prefix[endExclusive] - prefix[endExclusive - period]) / period

            var wins = 0
            var total = 0
            // First bar with a full MA200 behind it AND inside the last-year window.
            val firstScan = maxOf(200, size - SCAN_BARS)
            var i = firstScan
            while (i < size - 1) {
                val price = bars[i]
                val prev = bars[i - 1]
                val ma50 = sma(i + 1, 50)
                val ma200 = sma(i + 1, 200)
                val dayChangePct = (price - prev) / prev * 100.0

                val entry = price > ma50 && ma50 > ma200 &&
                        dayChangePct >= 0.4 && dayChangePct <= 2.5
                if (!entry) { i++; continue }

                // ATR(14) ending at the entry bar.
                var trSum = 0.0
                for (k in i - 13..i) {
                    trSum += if (hi != null && lo != null) {
                        maxOf(hi[k] - lo[k], abs(hi[k] - bars[k - 1]), abs(lo[k] - bars[k - 1]))
                    } else {
                        abs(bars[k] - bars[k - 1])
                    }
                }
                val atr = trSum / 14.0
                if (atr <= 0.0 || !atr.isFinite()) { i++; continue }

                val target = price + atr * 2.0       // T1 = 2*ATR, same as live engine
                val stop = price - atr * 1.0         // SL = 1*ATR, same as live engine

                var exitIdx = -1
                var exitPrice = 0.0
                val lastJ = minOf(i + 10, size - 1)
                var j = i + 1
                while (j <= lastJ) {
                    val barLow = lo?.get(j) ?: bars[j]
                    val barHigh = hi?.get(j) ?: bars[j]
                    if (barLow <= stop) { exitIdx = j; exitPrice = stop; break }   // stop first — conservative
                    if (barHigh >= target) { exitIdx = j; exitPrice = target; break }
                    j++
                }
                if (exitIdx == -1) {                 // timeout: close out at the last window bar
                    exitIdx = lastJ
                    exitPrice = bars[exitIdx]
                }

                // Judge NET of round-trip charges (B0.3a), same for every exit
                // kind: a gross gain smaller than the charges is a real loss.
                val won = exitPrice - price > price * costFraction

                total++
                if (won) wins++
                i = exitIdx + 1                      // no overlapping trades
            }

            if (total >= 5) QuickStats(wins, total, size - 1 - firstScan) else null
        }.getOrNull()
    }
}
