package com.example.myapplication3.smartapi

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The live tick feed (SmartAPI WebSocket 2.0). After login it opens the streaming
 * socket, subscribes to the requested NSE equity tokens in LTP mode, and parses the
 * binary frames into `livePrices` (symbol → last traded price). This is the true
 * real-time source; the rest of the app falls back to Yahoo when it isn't connected.
 *
 * Reliability: a single supervised control loop keeps the socket alive. On any drop
 * it reconnects with exponential backoff, and every (re)connect re-validates the
 * session via [SmartApiClient.ensureSession] — so a network blip or Angel One's
 * daily token expiry can no longer leave the feed silently dead (the two bugs the
 * old one-shot start() had). Login is retried only a few times so wrong keys can
 * never hammer Angel's login and lock the account — but the give-up is no longer
 * forever: the feed watches the credential VALUES, and the moment the user saves
 * corrected keys (or re-saves the same ones via [refreshCredentials]) the failure
 * counter resets and the loop starts again.
 *
 * Binary LTP packet (51 bytes): [0] mode, [1] exchange, [2..27) token (ascii),
 * [27..35) seq, [35..43) timestamp, [43..51) LTP as int64 little-endian (÷100 = ₹).
 */
@Singleton
class SmartApiFeed @Inject constructor(
    private val store: SmartApiStore,
    private val client: SmartApiClient,
    private val master: InstrumentMaster
) {
    companion object {
        private const val TAG = "SmartApiFeed"
        private const val WS_URL = "wss://smartapisocket.angelone.in/smart-stream"
        private const val LTP_PACKET_MIN = 51
        private const val INITIAL_BACKOFF_MS = 2_000L
        private const val MAX_BACKOFF_MS = 60_000L
        // Give up (until keys change) after this many consecutive login failures,
        // so wrong credentials cannot repeatedly hit Angel's login and lock the account.
        private const val MAX_LOGIN_FAILURES = 3
    }

    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)   // long-lived socket
        .build()

    private val _livePrices = MutableStateFlow<Map<String, Double>>(emptyMap())
    val livePrices: StateFlow<Map<String, Double>> = _livePrices.asStateFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var ws: WebSocket? = null
    private var pingJob: Job? = null
    private var controlJob: Job? = null
    private var credsWatchJob: Job? = null

    @Volatile private var shouldRun = false
    @Volatile private var desiredSymbols: List<String> = emptyList()
    @Volatile private var backoffMs = INITIAL_BACKOFF_MS
    @Volatile private var loginFailures = 0
    @Volatile private var tokenToSymbol: Map<String, String> = emptyMap()

    /**
     * Begin (or update) the live feed for [symbols]. Idempotent: if the control loop
     * is already running it just adopts the new symbol set on the next (re)connect.
     * Calling start() again after the loop gave up (3 login failures) relaunches it
     * with a fresh failure budget — a config change is a reason to try again.
     */
    fun start(symbols: List<String>) {
        val changed = desiredSymbols != symbols
        desiredSymbols = symbols
        shouldRun = true
        ensureCredsWatch()
        if (controlJob?.isActive == true) {
            // WRONG-5 fix: if the symbol set changed while the feed is already running,
            // drop the current WebSocket so the control loop reconnects and re-subscribes
            // with the new symbol set immediately — without this, new stocks get no live
            // ticks until the next natural reconnect (which could be many minutes away).
            if (changed) ws?.cancel()
            return
        }
        loginFailures = 0
        backoffMs = INITIAL_BACKOFF_MS
        controlJob = scope.launch { runFeed() }
    }

    /**
     * The user saved (possibly corrected) keys: forget the old session, reset the
     * failure counter, and make sure the loop is running so the fix takes effect
     * NOW — not after an app restart. Also called automatically whenever the
     * stored credential values change.
     */
    fun refreshCredentials() {
        loginFailures = 0
        backoffMs = INITIAL_BACKOFF_MS
        client.logout()                  // old session belonged to the old keys
        if (!shouldRun) return           // feed was never asked to run — nothing to do
        val job = controlJob
        if (job?.isActive == true) {
            // Loop alive: drop the current socket so it reconnects and logs in
            // again with the new credentials right away.
            ws?.cancel()
            // If the loop was in the middle of giving up at this exact moment,
            // relaunch it once it finishes so the new keys still take effect.
            scope.launch {
                job.join()
                if (shouldRun && controlJob === job) {
                    controlJob = scope.launch { runFeed() }
                }
            }
        } else {
            controlJob = scope.launch { runFeed() }
        }
    }

    /** Watch the credential VALUES — a changed MPIN/TOTP/key restarts the feed even
     *  though isConfigured (a Boolean) never flips. */
    private fun ensureCredsWatch() {
        if (credsWatchJob?.isActive == true) return
        credsWatchJob = scope.launch {
            store.credentials.distinctUntilChanged().drop(1).collect { c ->
                if (c.isComplete) refreshCredentials()
            }
        }
    }

    fun stop() {
        shouldRun = false
        controlJob?.cancel(); controlJob = null
        pingJob?.cancel(); pingJob = null
        ws?.cancel(); ws = null
        _connected.value = false
    }

    /** Supervised connect → stream → (drop) → backoff → reconnect loop. */
    private suspend fun runFeed() {
        if (!store.current().isComplete) return
        loginFailures = 0
        while (shouldRun) {
            // A fresh-enough session every time — re-logs in on first run and on daily expiry.
            val sessionResult = client.ensureSession()
            val session = sessionResult.getOrNull()
            if (session == null) {
                loginFailures++
                Log.w(TAG, "feed login failed ($loginFailures/$MAX_LOGIN_FAILURES): ${sessionResult.exceptionOrNull()?.message}")
                _connected.value = false
                if (loginFailures >= MAX_LOGIN_FAILURES) {
                    // Not forever: saving keys again (refreshCredentials) or a new
                    // start() relaunches the loop with a fresh failure budget.
                    Log.w(TAG, "giving up live feed until keys are saved again")
                    break
                }
                if (!waitBackoff()) break
                continue
            }
            loginFailures = 0   // logged in — reset the guard

            tokenToSymbol = master.tokensFor(desiredSymbols)
            if (tokenToSymbol.isEmpty()) {
                Log.w(TAG, "no tokens resolved for feed")
                if (!waitBackoff()) break
                continue
            }

            val creds = store.current()
            val request = Request.Builder()
                .url(WS_URL)
                .header("Authorization", session.jwtToken)
                .header("x-api-key", creds.apiKey)
                .header("x-client-code", creds.clientCode)
                .header("x-feed-token", session.feedToken)
                .build()

            // Open the socket and suspend here until THIS connection fails or closes.
            val closed = CompletableDeferred<Unit>()
            ws?.cancel()
            ws = http.newWebSocket(request, listenerFor(closed))
            closed.await()
            _connected.value = false
            pingJob?.cancel()
            ws = null

            if (!shouldRun) break
            if (!waitBackoff()) break    // reconnect after backoff
        }
        _connected.value = false
    }

    /** Wait the current backoff, then double it (capped). Returns false if we should stop. */
    private suspend fun waitBackoff(): Boolean {
        val wait = backoffMs
        backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        delay(wait)
        return shouldRun
    }

    private fun listenerFor(closed: CompletableDeferred<Unit>) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            _connected.value = true
            backoffMs = INITIAL_BACKOFF_MS          // healthy connection — reset backoff
            // Subscribe (action 1) in LTP mode (1), NSE cash (exchangeType 1).
            val tokens = JSONArray().apply { tokenToSymbol.keys.forEach { put(it) } }
            val msg = JSONObject().apply {
                put("correlationID", "livefeed")
                put("action", 1)
                put("params", JSONObject().apply {
                    put("mode", 1)
                    put("tokenList", JSONArray().put(JSONObject().apply {
                        put("exchangeType", 1)
                        put("tokens", tokens)
                    }))
                })
            }
            webSocket.send(msg.toString())
            // SmartAPI closes idle sockets — heartbeat every 25s.
            pingJob?.cancel()
            pingJob = scope.launch {
                while (isActive) { delay(25_000); runCatching { webSocket.send("ping") } }
            }
            Log.d(TAG, "feed connected, subscribed ${tokenToSymbol.size} tokens")
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            val b = bytes.toByteArray()
            if (b.size < LTP_PACKET_MIN) return   // text acks / short frames
            val token = buildString {
                for (i in 2 until 27) { val c = b[i].toInt(); if (c == 0) break; append(c.toChar()) }
            }
            val sym = tokenToSymbol[token] ?: return
            var ltp = 0L
            for (i in 0 until 8) ltp = ltp or ((b[43 + i].toLong() and 0xff) shl (8 * i))
            val price = ltp / 100.0
            if (price > 0.0) _livePrices.update { it + (sym to price) }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "feed failure: ${t.message}")
            _connected.value = false
            pingJob?.cancel()
            closed.complete(Unit)          // wake runFeed() → backoff → reconnect
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            _connected.value = false
            pingJob?.cancel()
            closed.complete(Unit)          // wake runFeed() → backoff → reconnect
        }
    }
}
