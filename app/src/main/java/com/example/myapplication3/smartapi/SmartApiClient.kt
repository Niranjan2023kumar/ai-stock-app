package com.example.myapplication3.smartapi

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** An authenticated SmartAPI session (tokens expire daily → re-login each morning). */
data class SmartApiSession(
    val jwtToken: String,
    val refreshToken: String,
    val feedToken: String,
    val obtainedAtMs: Long
)

/**
 * Angel One SmartAPI auth. login() exchanges the user's client code + MPIN + a
 * freshly-generated TOTP for the JWT + feed tokens used by the live WebSocket (Stage 2).
 * Everything runs against Angel One's own servers with the user's own key.
 */
@Singleton
class SmartApiClient @Inject constructor(
    private val store: SmartApiStore
) {
    companion object {
        private const val TAG = "SmartApiClient"
        private const val LOGIN_URL =
            "https://apiconnect.angelone.in/rest/auth/angelbroking/user/v1/loginByPassword"
        private const val CANDLE_URL =
            "https://apiconnect.angelone.in/rest/secure/angelbroking/historical/v1/getCandleData"
        // Angel One JWT/feed tokens are day-valid; re-login well before that so a
        // reconnect never hands a stale token to the socket.
        private const val SESSION_MAX_AGE_MS = 6 * 60 * 60 * 1000L   // 6 hours
        // After a failed login, automatic re-logins (ensureSession) are refused
        // for this long WITHOUT touching the network — wrong keys must never
        // hammer Angel One's login and lock the user's account. A user-initiated
        // login() (Settings "save keys" → refreshCredentials → logout) clears it.
        private const val LOGIN_FAILURE_COOLDOWN_MS = 2 * 60 * 1000L  // 2 minutes
        // Angel One allows 3 historical requests/sec; 350ms spacing keeps us safely under.
        private const val CANDLE_MIN_SPACING_MS = 350L
        private const val IST_ZONE = "Asia/Kolkata"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    @Volatile
    var session: SmartApiSession? = null
        private set

    // Failed-login cooldown state (rate-limit correctness): armed by ANY failed
    // login, checked only by the automatic ensureSession() path. Volatile — read
    // outside the mutex is fine, writes happen in login()/logout().
    @Volatile private var lastLoginFailureMs = 0L
    @Volatile private var lastLoginFailureMessage: String = "Login failed"

    // ensureSession must be single-flight: the live feed, the intraday reads and
    // the candle fetches can all discover a missing/stale session at the same
    // moment — without this mutex each would fire its own login (double-login).
    private val sessionMutex = Mutex()

    /** Logs in and stores the session. Returns a clear error message on failure. */
    suspend fun login(): Result<SmartApiSession> = withContext(Dispatchers.IO) {
        val creds = store.current()
        if (!creds.isComplete) {
            return@withContext Result.failure(Exception("Add your Angel One keys in Settings first."))
        }
        val totp = runCatching { Totp.generate(creds.totpSecret) }.getOrElse {
            return@withContext Result.failure(Exception("Bad TOTP secret — check it in Settings."))
        }
        val bodyJson = JSONObject().apply {
            put("clientcode", creds.clientCode)
            put("password", creds.mpin)   // SmartAPI names the PIN field "password"
            put("totp", totp)
        }.toString()

        val request = Request.Builder()
            .url(LOGIN_URL)
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("X-UserType", "USER")
            .header("X-SourceID", "WEB")
            .header("X-ClientLocalIP", "192.168.1.1")
            .header("X-ClientPublicIP", "106.193.147.98")
            .header("X-MACAddress", "00:00:00:00:00:00")
            .header("X-PrivateKey", creds.apiKey)
            .build()

        return@withContext runCatching {
            client.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val root = JSONObject(text)
                if (!root.optBoolean("status", false)) {
                    val msg = root.optString("message").ifBlank { root.optString("errorcode").ifBlank { "Login failed" } }
                    throw Exception(msg)
                }
                val data = root.optJSONObject("data") ?: throw Exception("No session data")
                val s = SmartApiSession(
                    jwtToken = data.optString("jwtToken"),
                    refreshToken = data.optString("refreshToken"),
                    feedToken = data.optString("feedToken"),
                    obtainedAtMs = System.currentTimeMillis()
                )
                if (s.jwtToken.isBlank() || s.feedToken.isBlank()) throw Exception("Login returned no token")
                session = s
                lastLoginFailureMs = 0L   // success — disarm the re-login cooldown
                Log.d(TAG, "SmartAPI login OK for ${creds.clientCode}")
                s
            }
        }.recoverCatching { e ->
            // Arm the cooldown on ANY failed login — including a failed RE-login
            // while a stale session object still exists. ensureSession() checks it
            // before trying again, so a bad key can't spam Angel One's login.
            lastLoginFailureMessage = e.message ?: "Login failed — check your keys and internet."
            lastLoginFailureMs = System.currentTimeMillis()
            Log.w(TAG, "SmartAPI login failed: ${e.message}")
            throw Exception(e.message ?: "Login failed — check your keys and internet.")
        }
    }

    /**
     * Return a session guaranteed fresh enough to use, logging in again when the
     * cached one is missing or near Angel One's daily expiry. The live feed calls
     * this on every (re)connect so a day-old token can't leave it silently dead.
     *
     * Rate-limit correctness:
     *  • Single-flight — the whole check-then-login runs under [sessionMutex], so
     *    concurrent callers (feed reconnect + intraday reads + candle fetch) can
     *    never double-login; late arrivals see the fresh session and return it.
     *  • Failed-login cooldown — after ANY failed login (armed inside [login]),
     *    automatic retries within [LOGIN_FAILURE_COOLDOWN_MS] fail fast with the
     *    last real error, WITHOUT a network call. This holds even when a stale
     *    session object still exists (the old bypass: `session != null` skipped
     *    the caller-side cooldown check, then the re-login failed and was retried
     *    immediately). A direct, user-initiated [login] is never gated.
     */
    suspend fun ensureSession(): Result<SmartApiSession> = sessionMutex.withLock {
        val s = session
        if (s != null && System.currentTimeMillis() - s.obtainedAtMs < SESSION_MAX_AGE_MS) {
            return@withLock Result.success(s)
        }
        if (System.currentTimeMillis() - lastLoginFailureMs < LOGIN_FAILURE_COOLDOWN_MS) {
            return@withLock Result.failure(Exception(lastLoginFailureMessage))
        }
        login()
    }

    // Serializes historical-candle calls and enforces CANDLE_MIN_SPACING_MS between
    // request starts (Angel One historical rate limit: 3 req/sec).
    private val candleMutex = Mutex()
    private var lastCandleRequestAtMs = 0L

    /**
     * Fetch historical OHLCV candles from Angel One SmartAPI.
     *
     * API contract (getCandleData):
     * - POST https://apiconnect.angelone.in/rest/secure/angelbroking/historical/v1/getCandleData
     * - Headers: `Authorization: Bearer <jwtToken>` from the current session (auto
     *   refreshed via [ensureSession]) plus the same X-UserType / X-SourceID /
     *   X-ClientLocalIP / X-ClientPublicIP / X-MACAddress / X-PrivateKey headers as login.
     * - Body: `{"exchange","symboltoken","interval","fromdate","todate"}` where dates are
     *   `yyyy-MM-dd HH:mm` in exchange (IST) time, e.g. "2026-07-18 09:15".
     * - Response: `{"status":true,"data":[[timestamp,o,h,l,c,v],...]}` where timestamp is
     *   ISO-8601 with offset, e.g. "2026-07-18T09:15:00+05:30".
     * - Valid intervals: ONE_MINUTE, THREE_MINUTE, FIVE_MINUTE, TEN_MINUTE,
     *   FIFTEEN_MINUTE, THIRTY_MINUTE, ONE_HOUR, ONE_DAY. FIVE_MINUTE allows up to
     *   ~100 days per request.
     * - Rate limit: 3 requests/second — calls through this client are serialized with a
     *   minimum 350ms gap, so callers may fire requests back-to-back safely.
     *
     * Fail-safe by design: returns null on ANY failure (no session, network error,
     * non-2xx, status=false, malformed JSON) so callers fall back to the daily-data
     * path. Never throws.
     *
     * @param symbolToken Angel One numeric instrument token (see InstrumentMaster).
     * @param exchange    Exchange segment, e.g. "NSE".
     * @param interval    Candle interval constant, e.g. "FIVE_MINUTE".
     * @param fromDate    Range start, "yyyy-MM-dd HH:mm" IST (inclusive).
     * @param toDate      Range end, "yyyy-MM-dd HH:mm" IST (inclusive).
     * @return Candles in chronological order, or null on any failure.
     */
    suspend fun getCandles(
        symbolToken: String,
        exchange: String = "NSE",
        interval: String = "FIVE_MINUTE",
        fromDate: String,
        toDate: String
    ): List<Candle>? = withContext(Dispatchers.IO) {
        runCatching {
            val jwt = ensureSession().getOrNull()?.jwtToken
            if (jwt.isNullOrBlank()) return@withContext null
            val creds = store.current()
            if (!creds.isComplete) return@withContext null

            val bodyJson = JSONObject().apply {
                put("exchange", exchange)
                put("symboltoken", symbolToken)
                put("interval", interval)
                put("fromdate", fromDate)
                put("todate", toDate)
            }.toString()

            val request = Request.Builder()
                .url(CANDLE_URL)
                .post(bodyJson.toRequestBody("application/json".toMediaType()))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Authorization", "Bearer $jwt")
                .header("X-UserType", "USER")
                .header("X-SourceID", "WEB")
                .header("X-ClientLocalIP", "192.168.1.1")
                .header("X-ClientPublicIP", "106.193.147.98")
                .header("X-MACAddress", "00:00:00:00:00:00")
                .header("X-PrivateKey", creds.apiKey)
                .build()

            // Rate limit: serialize calls and keep >=350ms between request starts.
            candleMutex.withLock {
                val wait = CANDLE_MIN_SPACING_MS - (System.currentTimeMillis() - lastCandleRequestAtMs)
                if (wait > 0) delay(wait)
                lastCandleRequestAtMs = System.currentTimeMillis()

                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext null
                    val root = JSONObject(resp.body?.string().orEmpty())
                    if (!root.optBoolean("status", false)) return@withContext null
                    val rows = root.optJSONArray("data") ?: return@withContext null
                    val out = ArrayList<Candle>(rows.length())
                    for (i in 0 until rows.length()) {
                        parseCandleRow(rows.optJSONArray(i))?.let { out.add(it) }
                    }
                    out
                }
            }
        }.getOrElse { e ->
            Log.w(TAG, "getCandles failed for $symbolToken: ${e.message}")
            null
        }
    }

    /**
     * Convenience: today's FIVE_MINUTE candles from market open (09:15 IST) to now (IST).
     * Returns null on any failure (including before 09:15 IST Angel just returns an
     * empty/failed range, which surfaces as null or an empty list — both safe).
     */
    suspend fun getTodayFiveMinCandles(symbolToken: String): List<Candle>? {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply {
            timeZone = TimeZone.getTimeZone(IST_ZONE)
        }
        val nowIst = Calendar.getInstance(TimeZone.getTimeZone(IST_ZONE))
        val toDate = fmt.format(nowIst.time)
        val open = (nowIst.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 9)
            set(Calendar.MINUTE, 15)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val fromDate = fmt.format(open.time)
        return getCandles(symbolToken = symbolToken, fromDate = fromDate, toDate = toDate)
    }

    /**
     * Parse one `[timestamp, o, h, l, c, v]` row defensively: any missing/malformed
     * field drops just that row (returns null) instead of failing the whole batch.
     */
    private fun parseCandleRow(row: JSONArray?): Candle? {
        if (row == null || row.length() < 6) return null
        return runCatching {
            val ts = parseAngelTimestamp(row.getString(0)) ?: return null
            Candle(
                ts = ts,
                open = row.getDouble(1),
                high = row.getDouble(2),
                low = row.getDouble(3),
                close = row.getDouble(4),
                volume = row.getLong(5)
            )
        }.getOrNull()
    }

    /** "2026-07-18T09:15:00+05:30" → epoch millis; null when unparseable. */
    private fun parseAngelTimestamp(raw: String): Long? {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        return runCatching { fmt.parse(raw)?.time }.getOrNull()
    }

    /**
     * Drop the session AND the failed-login cooldown: logout is called when the
     * user saves (possibly corrected) keys — the fix must take effect NOW, not
     * two minutes later.
     */
    fun logout() {
        session = null
        lastLoginFailureMs = 0L
    }
}
