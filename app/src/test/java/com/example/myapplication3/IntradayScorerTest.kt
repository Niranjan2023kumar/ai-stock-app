package com.example.myapplication3

import com.example.myapplication3.intraday.IntradayScorer
import com.example.myapplication3.smartapi.Candle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Protection for the pure-math intraday scorer: VWAP, opening range, and the
 * -20..+20 read() contract (fail-safe: null instead of throwing, volume surge
 * only amplifies an existing lean). Pure JVM — no Android.
 */
class IntradayScorerTest {

    private fun candle(open: Double, high: Double, low: Double, close: Double, volume: Long, ts: Long = 0L) =
        Candle(ts = ts, open = open, high = high, low = low, close = close, volume = volume)

    /** Steady riser: OR breakout up, +2.5% session, price well above VWAP. Score = 7+5+5 = 17. */
    private fun bullishCandles(volumes: List<Long>): List<Candle> {
        val bars = listOf(
            candle(100.0, 100.5, 99.5, 100.0, 0),
            candle(100.0, 100.5, 99.5, 100.2, 0),
            candle(100.2, 100.6, 100.0, 100.5, 0),   // opening range: high 100.6 / low 99.5
            candle(100.5, 101.0, 100.4, 101.0, 0),
            candle(101.0, 101.5, 100.9, 101.5, 0),
            candle(101.5, 102.0, 101.4, 102.0, 0),
            candle(102.0, 102.5, 101.9, 102.5, 0)
        )
        return bars.mapIndexed { i, c -> c.copy(volume = volumes[i]) }
    }

    private val flatVolumes = List(7) { 1_000L }
    private val surgedVolumes = listOf(1_000L, 1_000L, 1_000L, 1_000L, 9_000L, 9_000L, 9_000L)

    // ── VWAP ──────────────────────────────────────────────────────────────────

    @Test
    fun vwapIsNullOnEmptyInput() {
        assertNull(IntradayScorer.vwap(emptyList()))
    }

    @Test
    fun vwapIsCorrectOnAKnownTwoCandleCase() {
        // typical1 = (110+90+100)/3 = 100 @ vol 100; typical2 = (220+180+200)/3 = 200 @ vol 300
        // VWAP = (100*100 + 200*300) / 400 = 175.0
        val vwap = IntradayScorer.vwap(
            listOf(
                candle(open = 100.0, high = 110.0, low = 90.0, close = 100.0, volume = 100),
                candle(open = 200.0, high = 220.0, low = 180.0, close = 200.0, volume = 300)
            )
        )
        assertEquals(175.0, vwap!!, 1e-9)
    }

    // ── Opening range ─────────────────────────────────────────────────────────

    @Test
    fun openingRangeNeedsThreeBars() {
        val twoBars = bullishCandles(flatVolumes).take(2)
        assertNull("2 bars cannot form the 09:15-09:30 range", IntradayScorer.openingRange(twoBars))
        val range = IntradayScorer.openingRange(bullishCandles(flatVolumes).take(3))
        assertEquals(100.6 to 99.5, range)
    }

    // ── read(): fail-safe contract ────────────────────────────────────────────

    @Test
    fun readIsNullWithoutEnoughCandles() {
        assertNull(IntradayScorer.read(emptyList()))
        assertNull("opening range + 1 bar is the minimum", IntradayScorer.read(bullishCandles(flatVolumes).take(3)))
    }

    // ── read(): scoring ───────────────────────────────────────────────────────

    @Test
    fun steadyRiserScoresSeventeenWithoutAVolumeSurge() {
        val read = IntradayScorer.read(bullishCandles(flatVolumes))!!
        assertEquals(IntradayScorer.OR_BULLISH, read.orState)
        // VWAP +7, opening-range breakout +5, momentum +5, no surge bonus.
        assertEquals(17, read.score)
    }

    @Test
    fun volumeSurgeAmplifiesAnExistingLeanByThree() {
        val withoutSurge = IntradayScorer.read(bullishCandles(flatVolumes))!!
        val withSurge = IntradayScorer.read(bullishCandles(surgedVolumes))!!
        assertTrue("test setup must actually be a surge", withSurge.volumeSurgeRatio >= 1.5)
        assertEquals("surge adds exactly +3 to a bullish lean", withoutSurge.score + 3, withSurge.score)
    }

    @Test
    fun volumeSurgeWithFlatPriceAddsNothing() {
        // Dead-flat session (every bar OHLC = 100) but volume explodes at the end:
        // churn without direction must NOT move the score off zero.
        val flat = List(7) { i -> candle(100.0, 100.0, 100.0, 100.0, surgedVolumes[i]) }
        val read = IntradayScorer.read(flat)
        assertNotNull("a flat-but-valid session is still a valid read", read)
        assertTrue("test setup must actually be a surge", read!!.volumeSurgeRatio >= 1.5)
        assertEquals("neutral market -> score 0, surge or not", 0, read.score)
    }

    @Test
    fun scoreIsClampedToMinusTwentyOnAHardSellOff() {
        // Mirror image of the riser: OR breakdown, -2.5%, below VWAP, surge on the way down.
        val bars = listOf(
            candle(100.0, 100.5, 99.5, 100.0, 1_000),
            candle(100.0, 100.5, 99.5, 99.8, 1_000),
            candle(99.8, 100.0, 99.4, 99.5, 1_000),   // opening range: high 100.5 / low 99.4
            candle(99.5, 99.6, 99.0, 99.0, 1_000),
            candle(99.0, 99.1, 98.5, 98.5, 9_000),
            candle(98.5, 98.6, 98.0, 98.0, 9_000),
            candle(98.0, 98.1, 97.5, 97.5, 9_000)
        )
        val read = IntradayScorer.read(bars)!!
        // VWAP -7, OR breakdown -5, momentum -5, surge amplifies -3 => -20 (floor).
        assertEquals(-20, read.score)
        assertTrue("score must always stay within -20..+20", read.score in -20..20)
    }
}
