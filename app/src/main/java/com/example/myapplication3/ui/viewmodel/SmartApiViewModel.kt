package com.example.myapplication3.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication3.smartapi.SmartApiClient
import com.example.myapplication3.smartapi.SmartApiCredentials
import com.example.myapplication3.smartapi.SmartApiFeed
import com.example.myapplication3.smartapi.SmartApiStore
import com.example.myapplication3.smartapi.Totp
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject

data class SmartApiUiState(
    val apiKey: String = "",
    val clientCode: String = "",
    val mpin: String = "",
    val totpSecret: String = "",
    val configured: Boolean = false,
    val testing: Boolean = false,
    val testResult: String? = null
)

/** Drives the "Live data (Angel One)" settings card (SmartAPI Stage 1). */
@HiltViewModel
class SmartApiViewModel @Inject constructor(
    private val store: SmartApiStore,
    private val feed: SmartApiFeed,
    private val client: SmartApiClient
) : ViewModel() {

    private val _state = MutableStateFlow(SmartApiUiState())
    val state: StateFlow<SmartApiUiState> = _state.asStateFlow()

    // Test-only HTTP client: the test logs in with the TYPED values, never the
    // saved ones, and never writes anything to disk.
    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    init {
        viewModelScope.launch {
            runCatching { store.current() }.getOrNull()?.let { c ->
                _state.update {
                    it.copy(apiKey = c.apiKey, clientCode = c.clientCode, mpin = c.mpin,
                        totpSecret = c.totpSecret, configured = c.isComplete)
                }
            }
        }
    }

    fun setApiKey(v: String) = _state.update { it.copy(apiKey = v, testResult = null) }
    fun setClientCode(v: String) = _state.update { it.copy(clientCode = v, testResult = null) }
    fun setMpin(v: String) = _state.update { it.copy(mpin = v, testResult = null) }
    fun setTotpSecret(v: String) = _state.update { it.copy(totpSecret = v, testResult = null) }

    private fun creds() = SmartApiCredentials(
        _state.value.apiKey, _state.value.clientCode, _state.value.mpin, _state.value.totpSecret
    )

    /** The ONLY writer: Save stores the keys (encrypted) and wakes the live feed —
     *  so corrected keys start working now, not after an app restart. */
    fun save() {
        val c = creds()
        if (!c.isComplete) {
            _state.update { it.copy(testResult = "✗ Fill all four boxes first.") }
            return
        }
        viewModelScope.launch {
            store.save(c)
            feed.refreshCredentials()
            _state.update { it.copy(configured = true, testResult = "Saved on this phone.") }
        }
    }

    /**
     * Try a real Angel One login with the values as typed — WITHOUT saving them.
     * A failed test can never overwrite keys that were working; nothing is stored
     * until the user taps Save.
     */
    fun test() {
        val c = creds()
        if (!c.isComplete) {
            _state.update { it.copy(testResult = "✗ Fill all four boxes first.") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(testing = true, testResult = null) }
            val result = testLogin(c)
            _state.update {
                it.copy(
                    testing = false,
                    testResult = result.fold(
                        onSuccess = { "✓ Keys work. Tap Save to switch on live data." },
                        onFailure = { e -> "✗ ${friendlyError(e)}" }
                    )
                )
            }
        }
    }

    fun clear() {
        viewModelScope.launch {
            store.clear()
            feed.stop()
            client.logout()
            _state.value = SmartApiUiState()
        }
    }

    /** One real loginByPassword call with candidate credentials — persists nothing. */
    private suspend fun testLogin(c: SmartApiCredentials): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val totp = runCatching { Totp.generate(c.totpSecret.trim().replace(" ", "")) }
                .getOrElse { throw Exception("totp secret unreadable") }
            val bodyJson = JSONObject().apply {
                put("clientcode", c.clientCode.trim().uppercase())
                put("password", c.mpin.trim())   // SmartAPI names the PIN field "password"
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
                .header("X-PrivateKey", c.apiKey.trim())
                .build()
            http.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val root = runCatching { JSONObject(text) }.getOrElse {
                    // HTML/empty body from a WAF or outage — never show parser noise.
                    throw IOException("server unreachable")
                }
                if (!root.optBoolean("status", false)) {
                    val msg = root.optString("message")
                        .ifBlank { root.optString("errorcode") }
                        .ifBlank { root.optString("errorCode") }
                    throw Exception(msg.ifBlank { "login failed" })
                }
            }
        }
    }

    /** Angel One / network errors → one line in words a beginner knows (I2/I6). */
    private fun friendlyError(e: Throwable): String {
        val msg = e.message.orEmpty().lowercase()
        return when {
            e is IOException || "unable to resolve host" in msg || "timeout" in msg ||
                "failed to connect" in msg || "unreachable" in msg ->
                "Could not reach Angel One — check your internet and try again."
            "totp" in msg ->
                "TOTP looks wrong. Check the secret, and set your phone's Date & Time to automatic."
            "password" in msg || "pin" in msg || "client" in msg || "user" in msg ->
                "Client code or MPIN looks wrong — please check them."
            "key" in msg || "private" in msg ->
                "API Key looks wrong — copy it again from the Angel One website."
            else ->
                "Login did not work — check all four values and try again."
        }
    }

    private companion object {
        const val LOGIN_URL =
            "https://apiconnect.angelone.in/rest/auth/angelbroking/user/v1/loginByPassword"
    }
}
