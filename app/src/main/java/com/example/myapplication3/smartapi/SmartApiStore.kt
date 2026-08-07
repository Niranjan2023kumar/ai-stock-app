package com.example.myapplication3.smartapi

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/** The four values the user copies from their SmartAPI setup. */
data class SmartApiCredentials(
    val apiKey: String = "",
    val clientCode: String = "",
    val mpin: String = "",
    val totpSecret: String = ""
) {
    val isComplete: Boolean
        get() = apiKey.isNotBlank() && clientCode.isNotBlank() && mpin.isNotBlank() && totpSecret.isNotBlank()
}

/**
 * Stores the user's Angel One SmartAPI credentials ON THE DEVICE ONLY (never in
 * code, never sent anywhere except Angel One's own login). These can place trades,
 * so they are the user's own responsibility — the app uses them read-only for the
 * live price feed (the B0.4 exception the owner approved).
 *
 * SECURITY (E6): the four values are ENCRYPTED with a hardware-backed Android
 * Keystore AES-256-GCM key before they touch disk — the DataStore file holds only
 * ciphertext, and the key never leaves the Keystore. Values saved by older app
 * versions in plaintext are migrated to encrypted form once, then wiped.
 * (androidx.security EncryptedSharedPreferences would do the same job, but that
 * dependency is not wired into this module — Keystore needs no new dependency.)
 */
@Singleton
class SmartApiStore @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    // Old plaintext keys (app versions before encryption) — read-only, wiped on migration.
    private val legacyApi = stringPreferencesKey("smartapi_key")
    private val legacyClient = stringPreferencesKey("smartapi_client")
    private val legacyMpin = stringPreferencesKey("smartapi_mpin")
    private val legacyTotp = stringPreferencesKey("smartapi_totp")

    // Encrypted keys — value format is "base64(iv):base64(ciphertext)".
    private val keyApi = stringPreferencesKey("smartapi_key_enc")
    private val keyClient = stringPreferencesKey("smartapi_client_enc")
    private val keyMpin = stringPreferencesKey("smartapi_mpin_enc")
    private val keyTotp = stringPreferencesKey("smartapi_totp_enc")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // One-time migration: re-save any old plaintext values encrypted, then
        // remove the plaintext copies. Reads fall back to plaintext until then,
        // so nothing is lost if the app dies mid-migration.
        scope.launch { migrateLegacyPlaintext() }
    }

    val credentials: Flow<SmartApiCredentials> = dataStore.data.map { p ->
        SmartApiCredentials(
            apiKey = readValue(p, keyApi, legacyApi),
            clientCode = readValue(p, keyClient, legacyClient),
            mpin = readValue(p, keyMpin, legacyMpin),
            totpSecret = readValue(p, keyTotp, legacyTotp)
        )
    }

    /** True when all four values are present — the app can use the live feed. */
    val isConfigured: Flow<Boolean> = credentials.map { it.isComplete }

    suspend fun current(): SmartApiCredentials = credentials.first()

    suspend fun save(c: SmartApiCredentials) {
        val api = CredentialCipher.encrypt(c.apiKey.trim())
        val client = CredentialCipher.encrypt(c.clientCode.trim().uppercase())
        val mpin = CredentialCipher.encrypt(c.mpin.trim())
        val totp = CredentialCipher.encrypt(c.totpSecret.trim().replace(" ", ""))
        dataStore.edit { p ->
            p[keyApi] = api
            p[keyClient] = client
            p[keyMpin] = mpin
            p[keyTotp] = totp
            // Any plaintext leftovers are gone the moment new values are saved.
            p.remove(legacyApi); p.remove(legacyClient); p.remove(legacyMpin); p.remove(legacyTotp)
        }
    }

    suspend fun clear() {
        dataStore.edit { p ->
            p.remove(keyApi); p.remove(keyClient); p.remove(keyMpin); p.remove(keyTotp)
            p.remove(legacyApi); p.remove(legacyClient); p.remove(legacyMpin); p.remove(legacyTotp)
        }
    }

    /** Encrypted value wins; plaintext only as pre-migration fallback. */
    private fun readValue(
        p: Preferences,
        encKey: Preferences.Key<String>,
        legacyKey: Preferences.Key<String>
    ): String = p[encKey]?.let { CredentialCipher.decrypt(it) } ?: p[legacyKey] ?: ""

    private suspend fun migrateLegacyPlaintext() {
        runCatching {
            val p = dataStore.data.first()
            val hasLegacy = p[legacyApi] != null || p[legacyClient] != null ||
                    p[legacyMpin] != null || p[legacyTotp] != null
            if (!hasLegacy) return
            // Pre-encrypt outside the edit block (Keystore work off the store's writer).
            val api = p[legacyApi]?.let { CredentialCipher.encrypt(it) }
            val client = p[legacyClient]?.let { CredentialCipher.encrypt(it) }
            val mpin = p[legacyMpin]?.let { CredentialCipher.encrypt(it) }
            val totp = p[legacyTotp]?.let { CredentialCipher.encrypt(it) }
            dataStore.edit { e ->
                // Don't clobber already-encrypted values (e.g. user saved during migration).
                if (api != null && e[keyApi] == null) e[keyApi] = api
                if (client != null && e[keyClient] == null) e[keyClient] = client
                if (mpin != null && e[keyMpin] == null) e[keyMpin] = mpin
                if (totp != null && e[keyTotp] == null) e[keyTotp] = totp
                e.remove(legacyApi); e.remove(legacyClient); e.remove(legacyMpin); e.remove(legacyTotp)
            }
            Log.d("SmartApiStore", "migrated plaintext credentials to encrypted storage")
        }.onFailure { Log.w("SmartApiStore", "credential migration failed: ${it.message}") }
    }
}

/**
 * AES-256-GCM using a key that lives ONLY inside the Android Keystore (extraction
 * is blocked by hardware on most devices). A fresh random IV per encryption is
 * stored beside the ciphertext: "base64(iv):base64(ciphertext)".
 */
private object CredentialCipher {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "smartapi_credentials_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    @Synchronized
    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(ciphertext, Base64.NO_WRAP)
    }

    /** Null on any failure (e.g. Keystore key lost after a device restore) — the
     *  caller treats that as "not configured" and the user simply re-enters keys. */
    @Synchronized
    fun decrypt(stored: String): String? = runCatching {
        val sep = stored.indexOf(':')
        if (sep <= 0) return null
        val iv = Base64.decode(stored.substring(0, sep), Base64.NO_WRAP)
        val ciphertext = Base64.decode(stored.substring(sep + 1), Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }.getOrNull()
}
