package com.example.myapplication3

import com.example.myapplication3.ui.component.TargetExit
import com.example.myapplication3.ui.component.computeTargetSplit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Protection for computeTargetSplit (B0.2c — exact share counts, never "half"):
 * every share must be accounted for, no bucket may be empty, and the remainder
 * is front-loaded to Target 1 so profit locks sooner. Pure JVM — no Android.
 */
class TargetSplitTest {

    @Test
    fun tenSharesSplitFourThreeThreeAcrossThreeTargets() {
        val split = computeTargetSplit(qty = 10, t1 = 105.0, t2 = 110.0, t3 = 115.0)
        assertEquals(listOf(1, 2, 3), split.map { it.index })
        assertEquals("remainder goes to Target 1 first", listOf(4, 3, 3), split.map { it.shares })
        assertEquals("every share must be accounted for", 10, split.sumOf { it.shares })
    }

    @Test
    fun remainderOfTwoFrontLoadsTargetsOneAndTwo() {
        val split = computeTargetSplit(qty = 11, t1 = 105.0, t2 = 110.0, t3 = 115.0)
        assertEquals(listOf(4, 4, 3), split.map { it.shares })
    }

    @Test
    fun singleShareSellsFullyAtTargetOne() {
        val split = computeTargetSplit(qty = 1, t1 = 105.0, t2 = 110.0, t3 = 115.0)
        assertEquals(1, split.size)
        assertEquals(TargetExit(index = 1, price = 105.0, shares = 1), split[0])
    }

    @Test
    fun zeroOrNegativeQuantityYieldsNoExits() {
        assertTrue(computeTargetSplit(qty = 0, t1 = 105.0, t2 = 110.0, t3 = 115.0).isEmpty())
        assertTrue(computeTargetSplit(qty = -3, t1 = 105.0, t2 = 110.0, t3 = 115.0).isEmpty())
    }

    @Test
    fun neverMoreBucketsThanSharesAndNeverAnEmptyBucket() {
        val split = computeTargetSplit(qty = 2, t1 = 105.0, t2 = 110.0, t3 = 115.0)
        assertEquals("2 shares can fill at most 2 targets", 2, split.size)
        assertTrue("no bucket may hold zero or negative shares", split.all { it.shares > 0 })
    }

    @Test
    fun zeroPricedTargetsAreSkipped() {
        // t2 has no real price -> only T1 and T3 get buckets, remainder still first.
        val split = computeTargetSplit(qty = 5, t1 = 105.0, t2 = 0.0, t3 = 115.0)
        assertEquals(listOf(1, 3), split.map { it.index })
        assertEquals(listOf(3, 2), split.map { it.shares })
    }
}
