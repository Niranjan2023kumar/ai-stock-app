package com.example.myapplication3.smartapi

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * RFC 6238 TOTP generator (HMAC-SHA1, 6 digits, 30s step) — the same code an
 * authenticator app runs. Angel One SmartAPI login needs a fresh TOTP each time,
 * so the app generates it from the user's saved Base32 TOTP secret (no external app).
 */
object Totp {

    fun generate(
        secretBase32: String,
        atMillis: Long = System.currentTimeMillis(),
        stepSeconds: Long = 30L,
        digits: Int = 6
    ): String {
        val key = base32Decode(secretBase32)
        val counter = atMillis / 1000L / stepSeconds
        val msg = ByteArray(8)
        var v = counter
        for (i in 7 downTo 0) { msg[i] = (v and 0xffL).toByte(); v = v shr 8 }

        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key, "HmacSHA1"))
        val hash = mac.doFinal(msg)

        val offset = hash[hash.size - 1].toInt() and 0x0f
        val binary = ((hash[offset].toInt() and 0x7f) shl 24) or
                ((hash[offset + 1].toInt() and 0xff) shl 16) or
                ((hash[offset + 2].toInt() and 0xff) shl 8) or
                (hash[offset + 3].toInt() and 0xff)

        var mod = 1
        repeat(digits) { mod *= 10 }
        return (binary % mod).toString().padStart(digits, '0')
    }

    /** RFC 4648 Base32 decode (uppercase, no padding needed). */
    private fun base32Decode(input: String): ByteArray {
        val clean = input.trim().replace(" ", "").replace("-", "").uppercase().trimEnd('=')
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        var buffer = 0
        var bitsLeft = 0
        val out = ArrayList<Byte>(clean.length * 5 / 8)
        for (c in clean) {
            val idx = alphabet.indexOf(c)
            if (idx < 0) continue                 // skip stray chars
            buffer = (buffer shl 5) or idx
            bitsLeft += 5
            if (bitsLeft >= 8) {
                bitsLeft -= 8
                out.add(((buffer shr bitsLeft) and 0xff).toByte())
            }
        }
        return out.toByteArray()
    }
}
