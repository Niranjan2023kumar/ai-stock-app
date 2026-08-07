package com.example.myapplication3.core.common

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MarketCalendar @Inject constructor() {

    private val istTimeZone = TimeZone.getTimeZone(Constants.NSE_TIMEZONE)

    // NSE trading holidays, keyed by year — the guaranteed FALLBACK baseline.
    // Future years arrive via refreshHolidaysIfNeeded() (yearly NSE fetch below);
    // these lists are still updated annually as a belt-and-braces backstop.
    // Unknown years fall back to an empty set (weekend + hours checks still apply),
    // which fails safe: the scanner may run on an unlisted holiday but will simply
    // find a closed market, instead of using another year's wrong dates.
    private val nseHolidaysByYear = mapOf(
        2025 to setOf(
            "2025-01-26", "2025-02-26", "2025-03-14", "2025-03-31",
            "2025-04-10", "2025-04-14", "2025-04-18", "2025-05-01",
            "2025-08-15", "2025-08-27", "2025-10-02",
            "2025-10-20", "2025-10-21", "2025-10-22", "2025-11-05",
            "2025-12-25"
        ),
        2026 to setOf(
            "2026-01-26", "2026-03-20", "2026-04-02", "2026-04-03",
            "2026-04-14", "2026-05-01", "2026-08-15", "2026-09-15",
            "2026-10-02", "2026-10-28", "2026-11-11", "2026-12-25"
        )
    )

    // Remote holidays fetched from NSE (see refreshHolidaysIfNeeded). Purely an
    // ADDITIVE overlay: holidaysFor() unions it with the hardcoded lists, so a
    // bad/partial fetch can never remove a known holiday, and total failure
    // (no network, NSE blocks us, JSON changes shape) = exactly the old behavior.
    @Volatile
    private var remoteHolidaysByYear: Map<Int, Set<String>> = emptyMap()

    /** Hardcoded holidays for [year] plus any remotely fetched ones. */
    private fun holidaysFor(year: Int): Set<String> {
        val hardcoded = nseHolidaysByYear[year] ?: emptySet()
        val remote = remoteHolidaysByYear[year] ?: return hardcoded
        return hardcoded + remote
    }

    fun isMarketOpen(timestampMs: Long = System.currentTimeMillis()): Boolean {
        val cal = Calendar.getInstance(istTimeZone)
        cal.timeInMillis = timestampMs

        val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
        if (dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY) return false

        val dateStr = "%04d-%02d-%02d".format(
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH)
        )
        if (dateStr in holidaysFor(cal.get(Calendar.YEAR))) return false

        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)
        val totalMinutes = hour * 60 + minute
        val openMinutes = Constants.NSE_OPEN_HOUR * 60 + Constants.NSE_OPEN_MINUTE
        val closeMinutes = Constants.NSE_CLOSE_HOUR * 60 + Constants.NSE_CLOSE_MINUTE

        return totalMinutes in openMinutes..closeMinutes
    }

    fun isPreMarketSession(timestampMs: Long = System.currentTimeMillis()): Boolean {
        val cal = Calendar.getInstance(istTimeZone)
        cal.timeInMillis = timestampMs
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)
        val totalMinutes = hour * 60 + minute
        return totalMinutes in (9 * 60)..(9 * 60 + 15)
    }

    fun nextMarketOpenMs(): Long {
        val cal = Calendar.getInstance(istTimeZone)
        cal.set(Calendar.HOUR_OF_DAY, Constants.NSE_OPEN_HOUR)
        cal.set(Calendar.MINUTE, Constants.NSE_OPEN_MINUTE)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        if (cal.timeInMillis <= System.currentTimeMillis()) {
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }
        while (!isMarketOpen(cal.timeInMillis)) {
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }
        return cal.timeInMillis
    }

    fun getCurrentTradingDay(timestampMs: Long = System.currentTimeMillis()): String {
        val cal = Calendar.getInstance(istTimeZone)
        cal.timeInMillis = timestampMs
        return "%04d-%02d-%02d".format(
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH)
        )
    }

    /** True when the given IST date is a listed NSE trading holiday. */
    fun isHoliday(timestampMs: Long = System.currentTimeMillis()): Boolean {
        val cal = Calendar.getInstance(istTimeZone)
        cal.timeInMillis = timestampMs
        return getCurrentTradingDay(timestampMs) in holidaysFor(cal.get(Calendar.YEAR))
    }

    // ══════════════════════════════════════════════════════════════════
    //  Holiday longevity — yearly self-refresh from NSE's public list
    // ══════════════════════════════════════════════════════════════════
    //
    // The hardcoded lists above stop at 2026. Without a refresh, 2027 would be a
    // blind spot (weekend + hours checks still apply, but listed holidays would
    // be treated as normal days). refreshHolidaysIfNeeded() closes that gap:
    //   • at most ONE network fetch per IST calendar year (guarded by prefs),
    //   • fetched dates are cached in SharedPreferences, so they survive app
    //     restarts for the whole year without another network call,
    //   • EVERY failure mode is swallowed — this function never throws, and on
    //     total failure the calendar behaves exactly as it does today.

    /**
     * Loads any cached NSE holiday list and, at most once per IST year, fetches
     * a fresh one from NSE's public holiday API. Safe to call from anywhere
     * (runs on Dispatchers.IO) and as often as the caller likes — it is cheap
     * after the first call of the year. Never throws.
     */
    suspend fun refreshHolidaysIfNeeded(context: Context) = withContext(Dispatchers.IO) {
        runCatching {
            val prefs = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

            // 1) Hydrate the in-memory overlay from last successful fetch, so a
            //    fetch done in January still protects an app restart in December.
            if (remoteHolidaysByYear.isEmpty()) {
                prefs.getString(KEY_CACHED_JSON, null)?.let { cached ->
                    runCatching { remoteHolidaysByYear = decodeHolidayJson(cached) }
                }
            }

            // 2) At most one real fetch per IST calendar year.
            val currentYear = Calendar.getInstance(istTimeZone).get(Calendar.YEAR)
            if (prefs.getInt(KEY_FETCHED_YEAR, -1) == currentYear) return@runCatching

            val fetched = fetchNseHolidays()
            if (fetched.isEmpty()) return@runCatching // don't burn the yearly slot on junk

            remoteHolidaysByYear = remoteHolidaysByYear + fetched // fresh years win
            prefs.edit()
                .putInt(KEY_FETCHED_YEAR, currentYear)
                .putString(KEY_CACHED_JSON, encodeHolidayJson(remoteHolidaysByYear))
                .apply()
        } // any failure: keep hardcoded behavior, retry next call
        Unit
    }

    /**
     * GET NSE's trading-holiday JSON. NSE requires browser-like headers and a
     * primed cookie, so we hit the homepage first. Throws on any problem —
     * the caller wraps it.
     */
    private fun fetchNseHolidays(): Map<Int, Set<String>> {
        // Step 1 — prime cookies from the NSE homepage (NSE rejects cookieless API hits).
        val cookies = runCatching {
            withNseConnection(NSE_HOME_URL) { conn ->
                conn.inputStream.use { it.skip(Long.MAX_VALUE) } // drain; body unused
                conn.headerFields
                    .filterKeys { it.equals("Set-Cookie", ignoreCase = true) }
                    .values.flatten()
                    .mapNotNull { it.substringBefore(';').takeIf(String::isNotBlank) }
                    .joinToString("; ")
            }
        }.getOrDefault("")

        // Step 2 — the actual holiday API.
        val body = withNseConnection(NSE_HOLIDAY_URL) { conn ->
            conn.setRequestProperty("Accept", "application/json, text/plain, */*")
            conn.setRequestProperty("Referer", "https://www.nseindia.com/")
            if (cookies.isNotEmpty()) conn.setRequestProperty("Cookie", cookies)
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                error("NSE holiday API HTTP ${conn.responseCode}")
            }
            conn.inputStream.bufferedReader().use(BufferedReader::readText)
        }
        return parseNseHolidayJson(body)
    }

    /** Opens [url] with browser-like headers + timeouts, runs [block], always disconnects. */
    private fun <T> withNseConnection(url: String, block: (HttpURLConnection) -> T): T {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
            )
            conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            block(conn)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * NSE's payload is `{"CM":[{"tradingDate":"26-Jan-2026",...},...], ...}` —
     * "CM" (Capital Market) is the equities segment. Dates are dd-MMM-yyyy.
     * Unparseable entries are skipped one by one, never failing the whole batch.
     */
    private fun parseNseHolidayJson(body: String): Map<Int, Set<String>> {
        val inFormat = SimpleDateFormat("dd-MMM-yyyy", Locale.ENGLISH)
            .apply { timeZone = istTimeZone; isLenient = false }
        val outFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            .apply { timeZone = istTimeZone }

        val result = mutableMapOf<Int, MutableSet<String>>()
        val segment = JSONObject(body).optJSONArray("CM") ?: return emptyMap()
        for (i in 0 until segment.length()) {
            runCatching {
                val raw = segment.optJSONObject(i)?.optString("tradingDate").orEmpty()
                if (raw.isEmpty()) return@runCatching
                val date = inFormat.parse(raw) ?: return@runCatching
                val iso = outFormat.format(date) // "2026-01-26"
                val year = iso.substring(0, 4).toIntOrNull() ?: return@runCatching
                result.getOrPut(year) { mutableSetOf() }.add(iso)
            }
        }
        return result
    }

    /** {"2026":["2026-01-26",...],"2027":[...]} */
    private fun encodeHolidayJson(map: Map<Int, Set<String>>): String {
        val root = JSONObject()
        for ((year, dates) in map) root.put(year.toString(), JSONArray(dates.sorted()))
        return root.toString()
    }

    private fun decodeHolidayJson(json: String): Map<Int, Set<String>> {
        val root = JSONObject(json)
        val result = mutableMapOf<Int, Set<String>>()
        for (key in root.keys()) {
            val year = key.toIntOrNull() ?: continue
            val arr = root.optJSONArray(key) ?: continue
            val dates = mutableSetOf<String>()
            for (i in 0 until arr.length()) {
                arr.optString(i).takeIf { it.matches(ISO_DATE_REGEX) }?.let(dates::add)
            }
            if (dates.isNotEmpty()) result[year] = dates
        }
        return result
    }

    private companion object {
        const val PREFS_NAME = "market_calendar"
        const val KEY_FETCHED_YEAR = "nse_holidays_fetched_year"
        const val KEY_CACHED_JSON = "nse_holidays_json"
        const val NSE_HOME_URL = "https://www.nseindia.com/"
        const val NSE_HOLIDAY_URL = "https://www.nseindia.com/api/holiday-master?type=trading"
        val ISO_DATE_REGEX = Regex("""\d{4}-\d{2}-\d{2}""")
    }
}
