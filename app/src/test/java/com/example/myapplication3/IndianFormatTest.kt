package com.example.myapplication3

import com.example.myapplication3.ui.component.formatIndianRupees
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Protection for the ONE shared rupee formatter (I9): true Indian lakh/crore
 * grouping, honest handling of broken numbers, no phantom minus signs.
 * Pure JVM — no Android.
 */
class IndianFormatTest {

    @Test
    fun oneLakhGroupsIndianStyle() {
        assertEquals("1,00,000", formatIndianRupees(100_000.0))
    }

    @Test
    fun decimalsSurviveGroupingUnchanged() {
        assertEquals("12,34,567.89", formatIndianRupees(1_234_567.89, decimals = 2))
    }

    @Test
    fun zeroIsJustZero() {
        assertEquals("0", formatIndianRupees(0.0))
    }

    @Test
    fun negativeAmountsKeepTheirSign() {
        assertEquals("-4,500", formatIndianRupees(-4_500.0))
    }

    @Test
    fun brokenNumbersNeverMasqueradeAsMoney() {
        assertEquals("NaN must render as a dash, never a rupee figure", "—", formatIndianRupees(Double.NaN))
        assertEquals("Infinity must render as a dash too", "—", formatIndianRupees(Double.POSITIVE_INFINITY))
    }

    @Test
    fun tinyNegativeThatRoundsToZeroDropsTheMinus() {
        // -0.4 rounds to "0" at 0 decimals; "-0" must never be shown.
        assertEquals("0", formatIndianRupees(-0.4))
    }

    @Test
    fun roundsFirstThenGroups() {
        // 999.95 rounds half-up to 1000 BEFORE grouping.
        assertEquals("1,000", formatIndianRupees(999.95, decimals = 0))
    }
}
