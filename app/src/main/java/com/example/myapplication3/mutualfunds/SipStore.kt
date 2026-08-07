package com.example.myapplication3.mutualfunds

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** "yyyy-MM" of the given calendar's month — the key stored in [SipRecord.lastPaidYearMonth]. */
fun sipYearMonth(cal: Calendar = Calendar.getInstance()): String =
    String.format(Locale.US, "%04d-%02d", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1)

/**
 * The user's ONE tracked SIP (rule C20a — reminder + honest growth line).
 * One SIP only, by design: one decision, one habit (rule B10).
 *
 * Units are recorded when the user confirms "Yes, money went" on SIP day,
 * at that day's NAV — so "You put in ₹12,000, it is now ₹13,100" is computed
 * from real AMFI NAV data, never invented (rule B6).
 */
data class SipRecord(
    val schemeCode: Int,
    val fundName: String,
    /** Monthly SIP amount in rupees. */
    val monthlyAmount: Int,
    /** Day of month the SIP is taken (1..28 — every month has these days). */
    val sipDay: Int,
    /** Total rupees the user has confirmed as paid so far. */
    val totalInvested: Double,
    /** Total fund units bought with those payments (amount ÷ NAV on confirm day). */
    val totalUnits: Double,
    /** "yyyy-MM" of the last confirmed payment; "" when none yet. */
    val lastPaidYearMonth: String,
    /**
     * True once ANY payment was confirmed well after SIP day (>3 days late).
     * A late confirm is booked at the confirm-day NAV, not the real SIP-day NAV
     * (we never invent historical NAVs — rule B6), so the growth line is
     * approximate. The screen shows this honestly as "approx".
     */
    val hasLateConfirms: Boolean = false
) {
    /** Payment due: SIP day reached this month and this month not confirmed yet (C20a). */
    fun isDueOn(cal: Calendar = Calendar.getInstance()): Boolean =
        cal.get(Calendar.DAY_OF_MONTH) >= sipDay && lastPaidYearMonth != sipYearMonth(cal)
}

/**
 * Tiny SharedPreferences persistence for the tracked SIP — same storage style
 * as MutualFundRepository's scheme-code cache. Local only, no network.
 */
@Singleton
class SipStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val PREFS_NAME = "mf_sip_v1"
        private const val KEY_SIP = "sip_json"
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun load(): SipRecord? {
        val json = prefs.getString(KEY_SIP, null) ?: return null
        return runCatching {
            val o = JSONObject(json)
            SipRecord(
                schemeCode        = o.getInt("schemeCode"),
                fundName          = o.getString("fundName"),
                monthlyAmount     = o.getInt("monthlyAmount"),
                sipDay            = o.getInt("sipDay"),
                totalInvested     = o.getDouble("totalInvested"),
                totalUnits        = o.getDouble("totalUnits"),
                lastPaidYearMonth = o.optString("lastPaidYearMonth", ""),
                hasLateConfirms   = o.optBoolean("hasLateConfirms", false)
            )
        }.getOrNull()
    }

    fun save(record: SipRecord) {
        val o = JSONObject()
            .put("schemeCode", record.schemeCode)
            .put("fundName", record.fundName)
            .put("monthlyAmount", record.monthlyAmount)
            .put("sipDay", record.sipDay)
            .put("totalInvested", record.totalInvested)
            .put("totalUnits", record.totalUnits)
            .put("lastPaidYearMonth", record.lastPaidYearMonth)
            .put("hasLateConfirms", record.hasLateConfirms)
        prefs.edit().putString(KEY_SIP, o.toString()).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_SIP).apply()
    }
}
