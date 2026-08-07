package com.example.myapplication3

import com.example.myapplication3.intraday.RiskLevel
import com.example.myapplication3.intraday.SignalAction
import com.example.myapplication3.intraday.TradingSignal
import com.example.myapplication3.intraday.confidenceMeaning
import com.example.myapplication3.intraday.estimatedCostFor
import com.example.myapplication3.intraday.netProfitFor
import com.example.myapplication3.intraday.roundTripCostRs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Money-math protection for the round-trip cost model (B0.3a, loss cause #1):
 * roundTripCostRs / TradingSignal.estimatedCostFor / TradingSignal.netProfitFor.
 * Pure JVM — no Android.
 */
class MoneyMathTest {

    private fun signal(entryPrice: Double, expectedProfit: Double, confidence: Int = 90): TradingSignal =
        TradingSignal(
            stockSymbol = "TCS",
            stockName = "Tata Consultancy Services",
            action = SignalAction.BUY,
            currentPrice = entryPrice,
            entryPrice = entryPrice,
            targetPrice = entryPrice + expectedProfit,
            stopLoss = entryPrice * 0.99,
            expectedProfit = expectedProfit,
            expectedLoss = entryPrice * 0.01,
            riskLevel = RiskLevel.LOW,
            confidence = confidence,
            reasons = emptyList(),
            validUntil = "3:15 PM",
            isBeginnerSafe = true
        )

    @Test
    fun costIsPositiveAndMatchesTheDocumentedIntradayRate() {
        val cost = roundTripCostRs(100_000.0, intraday = true)
        assertTrue("cost must be positive for a real position", cost > 0.0)
        // Proportional: (0.12% intraday + 0.05% slippage) of 1,00,000 = Rs 170.
        // Brokerage floor: max(0.03% x 1,00,000, Rs 20) = max(30, 20) = Rs 30 per
        // leg -> Rs 60 round trip (the Rs 20 floor does NOT bind at this size).
        // Total = 170 + 60 = Rs 230 (pinned so a silent rate change can't slip by).
        assertEquals(230.0, cost, 1e-9)
    }

    @Test
    fun costScalesLinearlyWithTurnover() {
        val small = roundTripCostRs(100_000.0)
        val big = roundTripCostRs(200_000.0)
        assertEquals("double the turnover must cost exactly double", small * 2.0, big, 1e-9)
    }

    @Test
    fun intradayIsCheaperThanDeliveryForTheSameTurnover() {
        val turnover = 50_000.0
        assertTrue(
            "intraday round trip must cost less than delivery (STT dominates delivery)",
            roundTripCostRs(turnover, intraday = true) < roundTripCostRs(turnover, intraday = false)
        )
    }

    @Test
    fun zeroTurnoverCostsNothing() {
        assertEquals(0.0, roundTripCostRs(0.0), 0.0)
    }

    @Test
    fun estimatedCostForUsesEntryPriceTimesQty() {
        val s = signal(entryPrice = 100.0, expectedProfit = 2.0)
        // 100 x 10 = Rs 1,000 turnover. Proportional 0.17% = Rs 1.70; brokerage
        // floor max(0.03% x 1,000, Rs 20) = max(0.30, 20) = Rs 20 per leg -> Rs 40
        // round trip (the flat floor BINDS on this tiny trade). 1.70 + 40 = Rs 41.70.
        assertEquals(41.70, s.estimatedCostFor(10), 1e-9)
    }

    @Test
    fun netProfitIsExactlyGrossMinusCost() {
        val s = signal(entryPrice = 100.0, expectedProfit = 2.0)
        val qty = 10
        val gross = s.expectedProfit * qty
        assertEquals(gross - s.estimatedCostFor(qty), s.netProfitFor(qty), 1e-9)
        // Same identity on the delivery path.
        assertEquals(
            gross - s.estimatedCostFor(qty, intraday = false),
            s.netProfitFor(qty, intraday = false),
            1e-9
        )
    }

    @Test
    fun zeroQuantityIsCompletelySafe() {
        val s = signal(entryPrice = 100.0, expectedProfit = 2.0)
        assertEquals(0.0, s.estimatedCostFor(0), 0.0)
        assertEquals(0.0, s.netProfitFor(0), 0.0)
    }

    @Test
    fun netProfitGoesNegativeWhenChargesEatTheEdge() {
        // Gross reward 5 paise/share on a Rs 1,000 stock: charges (Rs 41.70) >> gross (Rs 0.05).
        val s = signal(entryPrice = 1_000.0, expectedProfit = 0.05)
        assertTrue(
            "a target smaller than the charges must show a NEGATIVE net profit",
            s.netProfitFor(1) < 0.0
        )
    }

    @Test
    fun confidenceBandsAreLockedAtNinetyAndEightyFour() {
        // Locked spec bands (B3): 90+ Strong / 84-89 Okay / below 84 Weak.
        val s = { c: Int -> signal(entryPrice = 100.0, expectedProfit = 2.0, confidence = c) }
        assertTrue(s(90).confidenceMeaning.startsWith("Strong setup"))
        assertTrue(s(84).confidenceMeaning.startsWith("Okay setup"))
        assertTrue(s(83).confidenceMeaning.startsWith("Weak setup"))
    }
}
