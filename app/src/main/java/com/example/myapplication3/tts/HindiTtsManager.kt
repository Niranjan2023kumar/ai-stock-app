package com.example.myapplication3.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The app's one talking mouth.
 *
 * Rule A4 (amended by owner's word, 2026-07-18): the SCREEN always stays in
 * simple English, but the user may flip a visible Settings switch ("Speak
 * alerts in Hindi") and then the VOICE speaks Hindi for the core money lines
 * — buy, sell, stop-loss, target, daily-limit. The switch is a plain
 * SharedPreferences boolean ("voice_hindi") re-read on EVERY speak call, so
 * flipping it works instantly with no restart and no observer plumbing.
 *
 * B8 fail-safe: if the phone's TTS engine has no Hindi voice, we fall back to
 * the Indian-English voice AND the English words — a Hindi sentence read by
 * an English voice would be gibberish, and honest English beats broken Hindi
 * (A5). Free-form [speakText] always stays English: that text comes straight
 * off the English screen, and machine-translating it on the fly would be
 * dishonest guesswork.
 */
@Singleton
class HindiTtsManager @Inject constructor(
    @ApplicationContext private val context: Context
) : TextToSpeech.OnInitListener {

    companion object {
        private const val PREFS_FILE = "voice_prefs"
        private const val KEY_VOICE_HINDI = "voice_hindi"
        private const val KEY_VOICE_ON = "voice_enabled"

        /** MASTER voice switch (owner order): default TRUE = the app speaks. */
        fun isVoiceOn(context: Context): Boolean = runCatching {
            context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
                .getBoolean(KEY_VOICE_ON, true)
        }.getOrDefault(true)

        /** Flip the master voice switch — SettingsScreen writes through here so
         *  the screen and this manager can never disagree on the key. */
        fun setVoiceOn(context: Context, on: Boolean) {
            runCatching {
                context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_VOICE_ON, on).apply()
            }
        }

        private val HINDI = Locale("hi", "IN")
        // Prefer Indian English so numbers/rupees sound natural; then any English.
        private val ENGLISH_CANDIDATES =
            listOf(Locale("en", "IN"), Locale.ENGLISH, Locale.US, Locale.UK)

        /** Read the Hindi-voice switch. Default OFF = today's English behaviour. */
        fun isHindiVoiceOn(context: Context): Boolean = runCatching {
            context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
                .getBoolean(KEY_VOICE_HINDI, false)
        }.getOrDefault(false)

        /** Flip the Hindi-voice switch — SettingsScreen writes through here so
         *  the screen and this manager can never disagree on the key. */
        fun setHindiVoiceOn(context: Context, on: Boolean) {
            runCatching {
                context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_VOICE_HINDI, on).apply()
            }
        }
    }

    private var tts: TextToSpeech? = null
    @Volatile private var isReady = false

    /** null = never probed; false = this engine has no Hindi voice (B8: we then
     *  stop asking and stay honestly English until the app restarts). */
    @Volatile private var hindiAvailable: Boolean? = null

    /** What the engine is set to RIGHT NOW — so we only call setLanguage on a
     *  real change, not before every sentence. */
    @Volatile private var engineSpeaksHindi = false

    init {
        tts = TextToSpeech(context, this)
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        // Start in English every time; Hindi is applied per-utterance when the
        // switch is ON (the pref is re-read on each speak call).
        isReady = setEnglish()
        tts?.setSpeechRate(0.9f)
    }

    /** Point the engine at English. Returns true if ANY English voice stuck. */
    private fun setEnglish(): Boolean {
        val engine = tts ?: return false
        for (locale in ENGLISH_CANDIDATES) {
            val result = runCatching { engine.setLanguage(locale) }.getOrNull()
            if (result != null &&
                result != TextToSpeech.LANG_MISSING_DATA &&
                result != TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                engineSpeaksHindi = false
                return true
            }
        }
        return false
    }

    /**
     * Aim the engine at the language the NEXT utterance needs and report which
     * one is truly active: true = Hindi voice ready, false = English (either
     * the switch is off, or Hindi is unavailable and we fell back — B8).
     * Callers pick their sentence from the return value, never from the wish,
     * so voice and words always match.
     */
    private fun useHindiNow(wantHindi: Boolean): Boolean {
        val engine = tts ?: return false
        if (!wantHindi) {
            if (engineSpeaksHindi) setEnglish()
            return false
        }
        if (engineSpeaksHindi) return true
        if (hindiAvailable == false) return false // probed before: no Hindi voice
        val ok = runCatching {
            val result = engine.setLanguage(HINDI)
            result != TextToSpeech.LANG_MISSING_DATA &&
                result != TextToSpeech.LANG_NOT_SUPPORTED
        }.getOrDefault(false)
        hindiAvailable = ok
        if (ok) engineSpeaksHindi = true else setEnglish() // fall back to en-IN chain
        return ok
    }

    /** The switch, re-read fresh on every speak — flipping it in Settings takes
     *  effect on the very next sentence. */
    private fun hindiRequested(): Boolean = isHindiVoiceOn(context)

    fun speakBuySignal(symbol: String, price: Double, target: Double, stopLoss: Double) {
        if (!isVoiceOn(context)) return   // master voice switch: OFF = fully silent
        if (!isReady) return
        // Spoken guidance — the app's users listen, they don't read
        val text = if (useHindiNow(hindiRequested())) {
            "$symbol abhi ${price.toInt()} rupaye par kharido. " +
                    "${target.toInt()} rupaye par bechkar munafa banao. " +
                    "Agar ${stopLoss.toInt()} se neeche gire to turant bech do."
        } else {
            "Buy $symbol now at ${price.toInt()} rupees. " +
                    "Sell at ${target.toInt()} rupees to make money. " +
                    "If it drops below ${stopLoss.toInt()}, sell right away."
        }
        speak(text, symbol)
    }

    fun speakSellSignal(symbol: String, price: Double, target: Double, stopLoss: Double) {
        if (!isVoiceOn(context)) return   // master voice switch: OFF = fully silent
        if (!isReady) return
        val text = if (useHindiNow(hindiRequested())) {
            "$symbol abhi ${price.toInt()} rupaye par bech do. " +
                    "Daam neeche ja sakta hai. Dhyan rakho."
        } else {
            "Sell $symbol now at ${price.toInt()} rupees. " +
                    "The price may go down. Be careful."
        }
        speak(text, symbol)
    }

    /**
     * Speak any free-form text — used by the simple Stock tab cards.
     * HONEST NOTE: this text comes straight off the ENGLISH screen, so it is
     * always spoken in English, even when the Hindi switch is ON. The Hindi
     * switch covers the fixed money lines above, whose Hindi wording is
     * hand-written — not machine-guessed translations of arbitrary text (A5).
     */
    fun speakText(text: String, utteranceId: String = "free-text") {
        if (!isVoiceOn(context)) return   // master voice switch: OFF = fully silent
        if (!isReady) return
        useHindiNow(false) // make sure the English words get the English voice
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    fun speakStopLossAlert(symbol: String, price: Double) {
        if (!isVoiceOn(context)) return   // master voice switch: OFF = fully silent
        if (!isReady) return
        // A4: plain English only — TTS must match screen text exactly (no Hindi)
        val text = "Sell $symbol now — it has reached your safety stop at ${price.toInt()} rupees. Open Groww and sell."
        speak(text, "$symbol-sl")
    }

    fun speakTargetAlert(symbol: String, targetNumber: Int, price: Double) {
        if (!isVoiceOn(context)) return   // master voice switch: OFF = fully silent
        if (!isReady) return
        // A4: plain English only
        val text = "$symbol reached Target $targetNumber at ${price.toInt()} rupees. Open Groww and sell to take your profit."
        speak(text, "$symbol-t$targetNumber")
    }

    fun speakDailyLimitWarning() {
        if (!isVoiceOn(context)) return   // master voice switch: OFF = fully silent
        if (!isReady) return
        // A4: plain English only
        val text = "You have lost enough today. The app has stopped new suggestions to protect your money."
        speak(text, "daily-limit")
    }

    private fun speak(text: String, utteranceId: String) {
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isReady = false
        engineSpeaksHindi = false
        hindiAvailable = null
    }
}
