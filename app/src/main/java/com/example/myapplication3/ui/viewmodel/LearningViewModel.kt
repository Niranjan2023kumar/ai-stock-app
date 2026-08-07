package com.example.myapplication3.ui.viewmodel

import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication3.tts.HindiTtsManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

/**
 * Guide tab ViewModel — lesson progress + voice (C21, C22).
 * The old Yahoo news feed was removed per E3c: news lives inside the
 * Stock and Intraday tabs, not here.
 */
@HiltViewModel
class LearningViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val ttsManager: HindiTtsManager,
    private val dataStore: DataStore<Preferences>
) : ViewModel() {

    // ── Lesson progress — persisted so a returning user sees what is done ────
    private val readLessonsKey = stringSetPreferencesKey("guide_lessons_read")
    val readLessons: StateFlow<Set<String>> =
        dataStore.data.map { it[readLessonsKey] ?: emptySet() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    fun markLessonRead(id: String) {
        viewModelScope.launch {
            dataStore.edit { prefs ->
                prefs[readLessonsKey] = (prefs[readLessonsKey] ?: emptySet()) + id
            }
        }
    }

    // ── Voice readiness probe ────────────────────────────────────────────────
    // HindiTtsManager keeps its ready flag private and speakText() fails
    // silently. Probe the phone's default TTS engine once so the screen can
    // show "Voice not ready" instead of a dead speaker button.
    private val _voiceReady = MutableStateFlow(true) // assume OK until the probe says no
    val voiceReady: StateFlow<Boolean> = _voiceReady.asStateFlow()

    private var probeTts: TextToSpeech? = null

    init {
        probeTts = TextToSpeech(context) { status ->
            val engine = probeTts
            // Same English-first locale candidates as HindiTtsManager (rule A4)
            val ok = status == TextToSpeech.SUCCESS && engine != null &&
                listOf(Locale("en", "IN"), Locale.ENGLISH, Locale.US, Locale.UK).any { locale ->
                    val res = engine.isLanguageAvailable(locale)
                    res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED
                }
            _voiceReady.value = ok
            engine?.shutdown()
            probeTts = null
        }
    }

    /** Speak one lesson out loud. Returns false when the voice is not ready. */
    fun speakLesson(text: String): Boolean {
        if (!_voiceReady.value) return false
        ttsManager.speakText(text, "guide-lesson")
        return true
    }

    override fun onCleared() {
        super.onCleared()
        probeTts?.shutdown()
        probeTts = null
    }
}
