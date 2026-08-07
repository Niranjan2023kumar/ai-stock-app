package com.example.myapplication3.smartapi

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves an NSE equity symbol (e.g. "RELIANCE") to its SmartAPI numeric token,
 * which the live WebSocket needs. Angel One publishes the full instrument list as
 * one JSON; we download it once, keep only NSE cash equities, and cache the
 * symbol→token map on disk so later launches are instant.
 */
@Singleton
class InstrumentMaster @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "InstrumentMaster"
        private const val MASTER_URL =
            "https://margincalculator.angelbroking.com/OpenAPI_File/files/OpenAPIScripMaster.json"
    }

    private val prefs by lazy { context.getSharedPreferences("smartapi_master_v1", Context.MODE_PRIVATE) }
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    @Volatile private var symbolToToken: Map<String, String> = emptyMap()

    suspend fun tokenFor(symbol: String): String? {
        ensureLoaded()
        return symbolToToken[symbol.trim().removeSuffix(".NS").uppercase()]
    }

    /** token → symbol for the given symbols (used to label incoming ticks). */
    suspend fun tokensFor(symbols: List<String>): Map<String, String> {
        ensureLoaded()
        val out = LinkedHashMap<String, String>()
        for (s in symbols) {
            val clean = s.trim().removeSuffix(".NS").uppercase()
            symbolToToken[clean]?.let { out[it] = clean }
        }
        return out
    }

    private suspend fun ensureLoaded() = withContext(Dispatchers.IO) {
        if (symbolToToken.isNotEmpty()) return@withContext
        // 1) disk cache
        runCatching {
            prefs.getString("nse_eq", null)?.let { cached ->
                val obj = JSONObject(cached)
                val m = HashMap<String, String>(obj.length())
                obj.keys().forEach { k -> m[k] = obj.getString(k) }
                if (m.isNotEmpty()) symbolToToken = m
            }
        }
        if (symbolToToken.isNotEmpty()) return@withContext
        // 2) download + parse (NSE cash equities only)
        runCatching {
            val body = client.newCall(Request.Builder().url(MASTER_URL).get().build())
                .execute().use { it.body?.string() ?: "" }
            val arr = JSONArray(body)
            val m = HashMap<String, String>(4096)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("exch_seg") != "NSE") continue
                val sym = o.optString("symbol")
                if (!sym.endsWith("-EQ")) continue          // cash equity series only
                val token = o.optString("token")
                if (token.isBlank()) continue
                m[sym.removeSuffix("-EQ").uppercase()] = token
            }
            if (m.isNotEmpty()) {
                symbolToToken = m
                val out = JSONObject()
                m.forEach { (k, v) -> out.put(k, v) }
                prefs.edit().putString("nse_eq", out.toString()).apply()
                Log.d(TAG, "Loaded ${m.size} NSE equity tokens")
            }
        }.onFailure { Log.w(TAG, "Instrument master load failed: ${it.message}") }
    }
}
