package com.example.myapplication3.tracking

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.myapplication3.database.dao.TrackedTradeDao
import com.example.myapplication3.database.entity.TrackedTradeEntity
import com.example.myapplication3.groww.OrderType
import com.example.myapplication3.intraday.PendingOrder
import com.example.myapplication3.intraday.roundTripCostRs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Calendar
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists trades the user actually placed in Groww (B0.2b) so today's P/L, the
 * loss limits, and background stop-loss alerts survive app restarts. The app never
 * touches the order — it only records and watches (B0.4).
 */
@Singleton
class TradeTrackerRepository @Inject constructor(
    private val dao: TrackedTradeDao,
    private val outcomeRecorder: OutcomeRecorder,
    private val dataStore: DataStore<Preferences>
) {

    /** Open positions for the given mode (real vs practice), newest first. */
    fun observeOpen(practice: Boolean): Flow<List<TrackedTrade>> =
        dao.observeOpen(practice).map { list -> list.map { it.toDomain() } }

    /** Today's realized P/L for the mode — the number the top P/L bar shows (B7b). */
    fun observeTodayRealized(practice: Boolean): Flow<Double> =
        dao.observeRealizedSince(todayStartMs(), practice)

    /** This week's realized P/L (from Monday) — for the weekly loss limit (E3e). */
    fun observeWeekRealized(practice: Boolean): Flow<Double> =
        dao.observeRealizedSince(weekStartMs(), practice)

    /**
     * This week's honest scorecard (E1a): right vs wrong picks + net P/L.
     *
     * B0.3b: ONE mode per report, never mixed — the DAO query filters on
     * isPractice, so practice fills can never inflate (or dilute) the real
     * record. The returned report carries [WeekReport.isPractice] so the UI
     * can label practice numbers as practice; for trust surfaces that must
     * ALWAYS show the real record use [observeWeekReportReal].
     */
    fun observeWeekReport(practice: Boolean): Flow<WeekReport> =
        dao.observeClosedSince(weekStartMs(), practice).map { closed ->
            WeekReport(
                wins = closed.count { (it.realizedPnl ?: 0.0) > 0.0 },
                losses = closed.count { (it.realizedPnl ?: 0.0) < 0.0 },
                netPnl = closed.sumOf { it.realizedPnl ?: 0.0 },
                isPractice = practice
            )
        }

    /** REAL-only weekly scorecard — practice excluded by construction (B0.3b). */
    fun observeWeekReportReal(): Flow<WeekReport> = observeWeekReport(practice = false)

    /** Practice-only weekly scorecard — must be labelled as practice wherever shown. */
    fun observeWeekReportPractice(): Flow<WeekReport> = observeWeekReport(practice = true)

    suspend fun getOpenOnce(practice: Boolean): List<TrackedTrade> =
        dao.getOpenOnce(practice).map { it.toDomain() }

    suspend fun getAllOpenOnce(): List<TrackedTrade> =
        dao.getAllOpenOnce().map { it.toDomain() }

    /**
     * Record a newly-placed trade. Returns its id. entryPrice = the user's REAL fill.
     *
     * [confidence] (P0 #12): the signal engine's 0–100 confidence for this pick, if
     * the caller has it. It is persisted on the trade so that when the trade later
     * closes, [OutcomeRecorder] can bucket the real outcome by its pick-time
     * confidence (calibration table + per-confidence trust line). Default null keeps
     * every existing caller source-compatible; a null simply means "confidence
     * unknown" and the outcome stays un-bucketed.
     */
    suspend fun open(
        symbol: String,
        name: String,
        orderType: OrderType,
        entryPrice: Double,
        quantity: Int,
        stopLoss: Double,
        target1: Double,
        target2: Double = 0.0,
        target3: Double = 0.0,
        isPractice: Boolean = false,
        confidence: Int? = null
    ): String {
        val id = UUID.randomUUID().toString()
        dao.insert(
            TrackedTradeEntity(
                id = id, symbol = symbol, name = name, orderType = orderType.name,
                action = "BUY", isPractice = isPractice, entryPrice = entryPrice,
                quantity = quantity, stopLoss = stopLoss,
                target1 = target1, target2 = target2, target3 = target3,
                openedAt = System.currentTimeMillis(), status = "OPEN",
                confidenceAtPick = confidence
            )
        )
        return id
    }

    /**
     * Correct an OPEN trade with the user's ACTUAL fill (price and/or quantity) so the
     * P/L bar, loss limits, and stop-loss alerts track what was really bought — not the
     * app's suggested numbers (B0.2b). Pass null (default) to leave a field unchanged;
     * non-positive values are ignored. Uses the DAO's REPLACE insert, so no schema change.
     */
    suspend fun confirmFill(id: String, fillPrice: Double? = null, quantity: Int? = null) {
        val t = dao.getById(id) ?: return
        if (t.status != "OPEN") return   // never rewrite history on a closed trade
        dao.insert(
            t.copy(
                entryPrice = fillPrice?.takeIf { it > 0.0 } ?: t.entryPrice,
                quantity = quantity?.takeIf { it > 0 } ?: t.quantity
            )
        )
    }

    /**
     * Close a tracked trade at the user's real sell price. The stored realized P/L is
     * NET OF CHARGES (B0.3a after-charges truth): the round-trip cost — brokerage, STT,
     * exchange txn, GST, SEBI, stamp + slippage buffer — is subtracted via the same
     * cost model every pre-trade screen uses ([roundTripCostRs]), so the Today P/L bar,
     * weekly report and both loss limits agree with what Groww actually debits.
     *
     * Cost-model choices, documented:
     *  • Trade value base = entryPrice × quantity — the same base the pre-trade
     *    estimatedCostFor() showed the user, so pre-trade and post-trade figures match.
     *  • INTRADAY orders use the intraday rate; DELIVERY (and any unknown/legacy
     *    orderType string) uses the higher delivery rate — the conservative default.
     *  • Practice trades pay the same virtual charges (H11: practice must teach the
     *    real game, and real trades always pay charges).
     */
    suspend fun close(id: String, exitPrice: Double) {
        val t = dao.getById(id) ?: return
        val grossPnl = (exitPrice - t.entryPrice) * t.quantity   // long position
        val charges = roundTripCostRs(
            tradeValueRs = t.entryPrice * t.quantity,
            intraday = t.orderType == OrderType.INTRADAY.name
        )
        val pnl = grossPnl - charges                             // net of charges
        val closedAtMs = System.currentTimeMillis()
        dao.close(id, exitPrice, closedAtMs, pnl)
        // H13 learning loop: record the REAL outcome so the app can honestly report
        // "picks like this were right X of Y times". Practice trades are excluded —
        // fake fills must not inflate the real track record. confidenceAtPick is now
        // persisted on the trade (P0 #12), so we pass the REAL pick-time confidence
        // through; OutcomeRecorder takes a 0–100 Double? and buckets by it (a null
        // stored trade — old rows or callers that omitted it — stays un-bucketed).
        // Fail-safe: recording can never break the user's close-trade flow.
        if (!t.isPractice) {
            runCatching {
                outcomeRecorder.record(
                    symbol = t.symbol,
                    confidenceAtPick = t.confidenceAtPick?.toDouble(),
                    action = t.action,
                    netPnl = pnl,
                    closedAt = closedAtMs
                )
            }
        }
    }

    suspend fun delete(id: String) = dao.delete(id)

    // ── B0.2b pending-order marker (locked decision #3) ──────────────────────
    // The "Order placed?" confirm must survive process death while the user is
    // inside Groww, so the marker lives in DataStore — never in composition
    // state. These methods read/write the SAME record (same key, same JSON
    // fields) that InterdayViewModel already persists for the Trading-tab buy
    // path, so EVERY buy path — including the research screen's trackBuy flow —
    // can persist its marker before Groww opens. Because the key is shared, a
    // marker saved here resurfaces in the home screen's
    // PendingOrderConfirmDialog even if Android killed the app in between.

    /** Persist the marker BEFORE Groww opens, so the confirm can never be lost. */
    suspend fun savePendingOrder(
        symbol: String,
        name: String,
        orderType: OrderType,
        price: Double,
        qty: Int,
        stopLoss: Double,
        target1: Double,
        target2: Double = 0.0,
        target3: Double = 0.0
    ) {
        val json = JSONObject()
            .put("symbol", symbol)
            .put("name", name.ifEmpty { symbol })
            .put("orderType", orderType.name)
            .put("price", price)
            .put("qty", qty.coerceAtLeast(1))
            .put("stopLoss", stopLoss)
            .put("target1", target1)
            .put("target2", target2)
            .put("target3", target3)
            .put("ts", System.currentTimeMillis())
            .toString()
        // Fail-safe: a storage hiccup must never crash the buy flow — the worst
        // case is the old (in-memory) behavior, never a broken screen.
        runCatching { dataStore.edit { it[PENDING_ORDER_KEY] = json } }
    }

    /** The live pending marker, or null when none / stale (> 1 hour old). */
    fun observePendingOrder(): Flow<PendingOrder?> =
        dataStore.data.map { prefs -> parsePendingOrder(prefs[PENDING_ORDER_KEY]) }

    /** "No" / "I didn't buy" / tracked — drop the marker. */
    suspend fun clearPendingOrder() {
        runCatching { dataStore.edit { it.remove(PENDING_ORDER_KEY) } }
    }

    private fun parsePendingOrder(json: String?): PendingOrder? {
        if (json.isNullOrBlank()) return null
        return runCatching {
            val o = JSONObject(json)
            val ts = o.optLong("ts", 0L)
            // An hours-old marker is noise, not a real round-trip — ignore it.
            if (System.currentTimeMillis() - ts > PENDING_MAX_AGE_MS) return null
            PendingOrder(
                symbol = o.getString("symbol"),
                name = o.optString("name", o.getString("symbol")),
                orderType = o.optString("orderType", OrderType.DELIVERY.name),
                price = o.optDouble("price", 0.0),
                qty = o.optInt("qty", 1),
                stopLoss = o.optDouble("stopLoss", 0.0),
                target1 = o.optDouble("target1", 0.0),
                target2 = o.optDouble("target2", 0.0),
                target3 = o.optDouble("target3", 0.0),
                timestampMs = ts
            )
        }.getOrNull()
    }

    /**
     * H9 psychology guard: count of consecutive REAL losses at the start of
     * today's closed-trade list (DAO returns newest-first). Returns 0 when no
     * real trades have been closed today. Fail-safe — returns 0 on any error so
     * a DB hiccup never incorrectly blocks a good trade.
     */
    suspend fun todayConsecutiveLosses(): Int = withContext(Dispatchers.IO) {
        runCatching {
            val today = dao.getClosedSinceOnce(todayStartMs(), false) // DESC by closedAt
            var count = 0
            for (t in today) {
                if ((t.realizedPnl ?: 0.0) < 0.0) count++ else break
            }
            count
        }.getOrDefault(0)
    }

    private fun todayStartMs(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun weekStartMs(): Long = Calendar.getInstance().apply {
        set(Calendar.DAY_OF_WEEK, firstDayOfWeek)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun TrackedTradeEntity.toDomain() = TrackedTrade(
        id = id, symbol = symbol, name = name,
        orderType = runCatching { OrderType.valueOf(orderType) }.getOrDefault(OrderType.DELIVERY),
        isPractice = isPractice, entryPrice = entryPrice, quantity = quantity,
        stopLoss = stopLoss, target1 = target1, target2 = target2, target3 = target3,
        openedAt = openedAt, isOpen = status == "OPEN",
        exitPrice = exitPrice, closedAt = closedAt, realizedPnl = realizedPnl
    )

    private companion object {
        // SAME key InterdayViewModel uses ("pending_order_confirm") — one shared
        // marker, whichever screen wrote it. Do not change either side alone.
        val PENDING_ORDER_KEY = stringPreferencesKey("pending_order_confirm")
        const val PENDING_MAX_AGE_MS = 60 * 60 * 1000L   // stale after 1 hour
    }
}
