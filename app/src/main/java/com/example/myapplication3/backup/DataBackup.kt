package com.example.myapplication3.backup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.example.myapplication3.database.dao.TrackedTradeDao
import com.example.myapplication3.database.entity.TrackedTradeEntity
import com.example.myapplication3.mutualfunds.SipRecord
import com.example.myapplication3.mutualfunds.SipStore
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What happened when the user brought a backup file back in. Counts, in plain
 * numbers the UI can turn into one honest sentence (U9.5 — never an error code).
 */
data class ImportResult(
    /** Trades copied into the diary. */
    val tradesAdded: Int,
    /** Trades that were already here (same id, or same stock + same entry time). */
    val tradesSkipped: Int,
    /** How many saved settings (capital, risk, practice, budget, guide) came back. */
    val prefsRestored: Int,
    /** True when the tracked SIP record was restored. */
    val sipRestored: Boolean,
    /** Plain-English problems. Empty = everything worked. */
    val errors: List<String>
) {
    /** True when the file was a real backup and nothing went wrong. */
    val ok: Boolean get() = errors.isEmpty()
}

/**
 * P0 #10 — the trade diary must survive a phone change (C21a/E6: local-only, no
 * cloud). One versioned JSON file holds everything the user would cry to lose:
 *
 *  • every tracked trade (open + closed, real + practice) — the honest record;
 *  • the money settings (capital, risk %, loss limits), practice mode, today's
 *    budget check-in, the one-time battery-guide flag, guide lesson progress;
 *  • the tracked SIP record (invested ₹, units, last paid month).
 *
 * DELIBERATELY NEVER EXPORTED (security decision, do not "fix"):
 *  • Angel One / SmartAPI credentials (api key, client id, MPIN, TOTP secret —
 *    the smartapi_* DataStore keys). A backup file travels through Downloads,
 *    email, WhatsApp; broker keys must never ride along. After a restore the
 *    user re-enters them once in Settings.
 *  • Transient day-scoped state: the pending-order marker (stale after 1 hour)
 *    and the intraday entry anchors (valid for one day only). Restoring those on
 *    a new phone could resurface a bogus "Order placed?" dialog or a dead entry
 *    zone — worse than losing them.
 *
 * Fail-safe (B8): a bad row in the file skips that row with a note; it never
 * aborts the whole restore or crashes the app.
 */
@Singleton
class DataBackup @Inject constructor(
    private val tradeDao: TrackedTradeDao,
    private val dataStore: DataStore<Preferences>,
    private val sipStore: SipStore
) {

    // ── The DataStore keys worth keeping (must match their owners exactly) ────
    // UserSettingsStore (money settings, stored as percents + rupees capital):
    private val keyCapital = doublePreferencesKey("pref_capital")
    private val keyRiskPct = doublePreferencesKey("pref_risk_per_trade")
    private val keyDailyLossPct = doublePreferencesKey("pref_max_daily_loss")
    private val keyWeeklyLossPct = doublePreferencesKey("pref_max_weekly_loss")
    // InterdayViewModel (practice mode + today's budget check-in + battery guide):
    private val keyPractice = booleanPreferencesKey("practice_mode")
    private val keyDailyCapital = intPreferencesKey("daily_capital")
    private val keyDailyCapitalDate = stringPreferencesKey("daily_capital_date")
    private val keyDailyCapitalSkipDate = stringPreferencesKey("daily_capital_skip_date")
    private val keyBatteryGuideDone = booleanPreferencesKey("battery_guide_done")
    // LearningViewModel (guide progress):
    private val keyGuideLessonsRead = stringSetPreferencesKey("guide_lessons_read")

    /**
     * Build the full backup as one JSON string:
     * `{ schema: 1, exportedAt, exportedAtText, trades: [...], prefs: {...}, sip: {...}? }`.
     *
     * All trades = every OPEN row (both modes) + every CLOSED row (both modes,
     * since epoch 0). The DAO has no "select all" query and this class owns no
     * schema, so the union of those three existing queries IS the full table
     * (every row is either OPEN or CLOSED with a closedAt set by dao.close()).
     */
    suspend fun exportJson(): String {
        val trades = allTrades().sortedBy { it.openedAt }
        val tradesJson = JSONArray()
        trades.forEach { tradesJson.put(it.toJson()) }

        val now = System.currentTimeMillis()
        val root = JSONObject()
            .put("schema", SCHEMA_VERSION)
            .put("app", APP_TAG)
            .put("exportedAt", now)
            .put(
                "exportedAtText",
                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(now))
            )
            .put("trades", tradesJson)
            .put("prefs", prefsToJson())

        sipStore.load()?.let { root.put("sip", it.toJson()) }
        return root.toString(2)
    }

    /**
     * Restore from a backup file's text. Validates the schema first; then inserts
     * trades (skipping ones already present by id, or by symbol + entry time),
     * restores the saved settings in ONE DataStore edit, and restores the SIP
     * record. Never touches Angel One credentials (they are not in the file).
     */
    suspend fun importJson(json: String): ImportResult {
        val root = runCatching { JSONObject(json) }.getOrNull()
            ?: return ImportResult(
                0, 0, 0, false,
                listOf("This file is not a backup from this app.")
            )

        val schema = root.optInt("schema", -1)
        if (schema < 1 || schema > SCHEMA_VERSION) {
            return ImportResult(
                0, 0, 0, false,
                listOf("This file is not a backup from this app, or it was made by a newer version.")
            )
        }

        val errors = mutableListOf<String>()

        // ── Trades: skip what is already here, add the rest ───────────────────
        var added = 0
        var skipped = 0
        val existing = allTrades()
        val existingIds = existing.mapTo(HashSet()) { it.id }
        val existingKeys = existing.mapTo(HashSet()) { dupKey(it.symbol, it.openedAt) }

        val tradesJson = root.optJSONArray("trades") ?: JSONArray()
        for (i in 0 until tradesJson.length()) {
            val t = runCatching { tradeFromJson(tradesJson.getJSONObject(i)) }.getOrNull()
            if (t == null) {
                errors += "One trade in the file could not be read, so it was left out."
                continue
            }
            if (t.id in existingIds || dupKey(t.symbol, t.openedAt) in existingKeys) {
                skipped++
                continue
            }
            // Fail-safe: one bad insert must not sink the rest of the diary.
            runCatching { tradeDao.insert(t) }
                .onSuccess {
                    added++
                    existingIds += t.id
                    existingKeys += dupKey(t.symbol, t.openedAt)
                }
                .onFailure { errors += "One trade (${t.symbol}) could not be saved." }
        }

        // ── Settings: one edit, so readers never see a half-restored mix ──────
        var prefsRestored = 0
        val prefs = root.optJSONObject("prefs")
        if (prefs != null) {
            runCatching {
                dataStore.edit { p -> prefsRestored = restorePrefsInto(p, prefs) }
            }.onFailure {
                prefsRestored = 0
                errors += "The saved settings could not be restored."
            }
        }

        // ── SIP record ─────────────────────────────────────────────────────────
        var sipRestored = false
        val sipJson = root.optJSONObject("sip")
        if (sipJson != null) {
            val fromFile = runCatching { sipFromJson(sipJson) }.getOrNull()
            if (fromFile == null) {
                errors += "The SIP record in the file could not be read."
            } else {
                // Never let an OLD backup file wipe out a NEWER diary: if a SIP is
                // already tracked here with more money confirmed in, keep it.
                val local = sipStore.load()
                if (local == null || local.totalInvested <= fromFile.totalInvested) {
                    runCatching { sipStore.save(fromFile) }
                        .onSuccess { sipRestored = true }
                        .onFailure { errors += "The SIP record could not be restored." }
                }
            }
        }

        return ImportResult(
            tradesAdded = added,
            tradesSkipped = skipped,
            prefsRestored = prefsRestored,
            sipRestored = sipRestored,
            errors = errors
        )
    }

    // ── Trades ────────────────────────────────────────────────────────────────

    /** Every row in tracked_trades: open (both modes) + closed since 0 (both modes). */
    private suspend fun allTrades(): List<TrackedTradeEntity> {
        val all = tradeDao.getAllOpenOnce() +
            tradeDao.getClosedSinceOnce(0L, practice = false) +
            tradeDao.getClosedSinceOnce(0L, practice = true)
        return all.distinctBy { it.id }
    }

    /** Duplicate identity: same stock bought at the same millisecond = same trade. */
    private fun dupKey(symbol: String, openedAt: Long) =
        "${symbol.trim().uppercase(Locale.US)}|$openedAt"

    private fun TrackedTradeEntity.toJson(): JSONObject {
        val o = JSONObject()
            .put("id", id)
            .put("symbol", symbol)
            .put("name", name)
            .put("orderType", orderType)
            .put("action", action)
            .put("isPractice", isPractice)
            .put("entryPrice", entryPrice)
            .put("quantity", quantity)
            .put("stopLoss", stopLoss)
            .put("target1", target1)
            .put("target2", target2)
            .put("target3", target3)
            .put("openedAt", openedAt)
            .put("status", status)
        exitPrice?.let { o.put("exitPrice", it) }
        closedAt?.let { o.put("closedAt", it) }
        realizedPnl?.let { o.put("realizedPnl", it) }
        confidenceAtPick?.let { o.put("confidenceAtPick", it) }
        return o
    }

    /** Throws on a row that is not a valid trade — the caller skips it with a note. */
    private fun tradeFromJson(o: JSONObject): TrackedTradeEntity {
        val id = o.getString("id")
        val symbol = o.getString("symbol")
        val entryPrice = o.getDouble("entryPrice")
        val quantity = o.getInt("quantity")
        val openedAt = o.getLong("openedAt")
        val status = o.optString("status", "OPEN")
        require(id.isNotBlank()) { "blank id" }
        require(symbol.isNotBlank()) { "blank symbol" }
        require(entryPrice > 0.0) { "bad entry price" }
        require(quantity >= 1) { "bad quantity" }
        require(openedAt > 0L) { "bad open time" }
        require(status == "OPEN" || status == "CLOSED") { "bad status" }
        return TrackedTradeEntity(
            id = id,
            symbol = symbol,
            name = o.optString("name", symbol),
            orderType = o.optString("orderType", "DELIVERY"),
            action = o.optString("action", "BUY"),
            isPractice = o.optBoolean("isPractice", false),
            entryPrice = entryPrice,
            quantity = quantity,
            stopLoss = o.optDouble("stopLoss", 0.0),
            target1 = o.optDouble("target1", 0.0),
            target2 = o.optDouble("target2", 0.0),
            target3 = o.optDouble("target3", 0.0),
            openedAt = openedAt,
            status = status,
            exitPrice = if (o.has("exitPrice")) o.getDouble("exitPrice") else null,
            closedAt = if (o.has("closedAt")) o.getLong("closedAt") else null,
            realizedPnl = if (o.has("realizedPnl")) o.getDouble("realizedPnl") else null,
            confidenceAtPick = if (o.has("confidenceAtPick")) o.getInt("confidenceAtPick") else null
        )
    }

    // ── Prefs ─────────────────────────────────────────────────────────────────

    private suspend fun prefsToJson(): JSONObject {
        val p = runCatching { dataStore.data.first() }.getOrNull() ?: return JSONObject()
        val o = JSONObject()
        p[keyCapital]?.let { o.put("capital", it) }
        p[keyRiskPct]?.let { o.put("riskPerTradePercent", it) }
        p[keyDailyLossPct]?.let { o.put("dailyLossPercent", it) }
        p[keyWeeklyLossPct]?.let { o.put("weeklyLossPercent", it) }
        p[keyPractice]?.let { o.put("practiceMode", it) }
        p[keyDailyCapital]?.let { o.put("dailyCapital", it) }
        p[keyDailyCapitalDate]?.let { o.put("dailyCapitalDate", it) }
        p[keyDailyCapitalSkipDate]?.let { o.put("dailyCapitalSkipDate", it) }
        p[keyBatteryGuideDone]?.let { o.put("batteryGuideDone", it) }
        p[keyGuideLessonsRead]?.let { set ->
            o.put("guideLessonsRead", JSONArray().apply { set.forEach { put(it) } })
        }
        return o
    }

    /** Writes only the keys present in the file. Returns how many were restored. */
    private fun restorePrefsInto(
        p: androidx.datastore.preferences.core.MutablePreferences,
        o: JSONObject
    ): Int {
        var count = 0
        if (o.has("capital")) { p[keyCapital] = o.getDouble("capital"); count++ }
        if (o.has("riskPerTradePercent")) { p[keyRiskPct] = o.getDouble("riskPerTradePercent"); count++ }
        if (o.has("dailyLossPercent")) { p[keyDailyLossPct] = o.getDouble("dailyLossPercent"); count++ }
        if (o.has("weeklyLossPercent")) { p[keyWeeklyLossPct] = o.getDouble("weeklyLossPercent"); count++ }
        if (o.has("practiceMode")) { p[keyPractice] = o.getBoolean("practiceMode"); count++ }
        if (o.has("dailyCapital")) { p[keyDailyCapital] = o.getInt("dailyCapital"); count++ }
        if (o.has("dailyCapitalDate")) { p[keyDailyCapitalDate] = o.getString("dailyCapitalDate"); count++ }
        if (o.has("dailyCapitalSkipDate")) { p[keyDailyCapitalSkipDate] = o.getString("dailyCapitalSkipDate"); count++ }
        if (o.has("batteryGuideDone")) { p[keyBatteryGuideDone] = o.getBoolean("batteryGuideDone"); count++ }
        o.optJSONArray("guideLessonsRead")?.let { arr ->
            val set = buildSet { for (i in 0 until arr.length()) add(arr.getString(i)) }
            p[keyGuideLessonsRead] = set
            count++
        }
        return count
    }

    // ── SIP ───────────────────────────────────────────────────────────────────

    private fun SipRecord.toJson(): JSONObject = JSONObject()
        .put("schemeCode", schemeCode)
        .put("fundName", fundName)
        .put("monthlyAmount", monthlyAmount)
        .put("sipDay", sipDay)
        .put("totalInvested", totalInvested)
        .put("totalUnits", totalUnits)
        .put("lastPaidYearMonth", lastPaidYearMonth)
        .put("hasLateConfirms", hasLateConfirms)

    /** Throws on a malformed record — the caller reports it and moves on. */
    private fun sipFromJson(o: JSONObject): SipRecord = SipRecord(
        schemeCode = o.getInt("schemeCode"),
        fundName = o.getString("fundName"),
        monthlyAmount = o.getInt("monthlyAmount"),
        sipDay = o.getInt("sipDay").coerceIn(1, 28),
        totalInvested = o.getDouble("totalInvested"),
        totalUnits = o.getDouble("totalUnits"),
        lastPaidYearMonth = o.optString("lastPaidYearMonth", ""),
        hasLateConfirms = o.optBoolean("hasLateConfirms", false)
    )

    private companion object {
        /** Bump ONLY when the file format changes shape; import accepts 1..this. */
        const val SCHEMA_VERSION = 1

        /** Identifies the file's producer for a human peeking at the JSON. */
        const val APP_TAG = "ai-stock-intelligence-backup"
    }
}
