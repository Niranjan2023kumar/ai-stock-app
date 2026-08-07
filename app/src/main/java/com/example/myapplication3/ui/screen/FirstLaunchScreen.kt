package com.example.myapplication3.ui.screen

import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.example.myapplication3.tts.HindiTtsManager
import com.example.myapplication3.ui.theme.DarkBackground
import com.example.myapplication3.ui.theme.DarkBorder
import com.example.myapplication3.ui.theme.DarkCard
import com.example.myapplication3.ui.theme.GoldAccent
import com.example.myapplication3.ui.theme.GoldLight
import com.example.myapplication3.ui.theme.TextMuted
import com.example.myapplication3.ui.theme.TextOnGold
import com.example.myapplication3.ui.theme.TextPrimary
import com.example.myapplication3.ui.theme.TextSecondary
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * First-launch walkthrough (ROADMAP P1 #9) — three skippable cards, shown ONCE.
 *
 * One idea per card, in the user's own words (U9.1 simple English, U9.4 big
 * text): the app only advises (U0 trust), "I bought it" starts the watch
 * (U4.1/U4.2), and alerts + battery are how the protection reaches a closed
 * app (U4.3/U4.5). Skip is always one tap away — zero-effort users are never
 * trapped in a tutorial (U2.1).
 *
 * Persistence is a plain SharedPreferences boolean — readable synchronously in
 * MainActivity.onCreate BEFORE any composition, so a returning user starts
 * straight on the Stock tab and this screen never even builds.
 */
object FirstLaunchPrefs {
    private const val FILE = "first_launch"
    private const val KEY_DONE = "onboarding_done"

    fun isDone(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_DONE, false)

    fun markDone(context: Context) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DONE, true).apply()
    }
}

private data class WalkthroughCard(
    val emoji: String,
    val title: String,
    val body: String
)

// The exact three ideas from ROADMAP P1 #9 — do not add cards; three is the
// whole story (advise → track → protect).
private val walkthroughCards = listOf(
    WalkthroughCard(
        emoji = "🤝", // handshake
        title = "Your money stays in YOUR hands",
        body  = "This app tells you what to do. YOU place the order in Groww. " +
                "The app never touches your money."
    ),
    WalkthroughCard(
        emoji = "👀", // eyes
        title = "After you buy, the app watches for you",
        body  = "After you buy, tap 'I bought it' — then the app watches your " +
                "trade and warns you when to sell."
    ),
    WalkthroughCard(
        emoji = "🛡️", // shield
        title = "Let the app protect you",
        body  = "Say yes to alerts and battery — that is how the app protects " +
                "your money even when closed."
    )
)

/**
 * Tiny ViewModel whose only job is reaching the @Singleton HindiTtsManager so
 * every card has a speaker (U8.2 "every lesson can be listened to").
 * Same voice-readiness probe as LearningViewModel: HindiTtsManager keeps its
 * ready flag private and speakText() fails silently, so we probe the phone's
 * default TTS engine once and HIDE the Listen button when there is no voice —
 * a dead button would break U2.1.
 */
@HiltViewModel
class FirstLaunchViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val ttsManager: HindiTtsManager
) : ViewModel() {

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

    fun speak(text: String) = ttsManager.speakText(text, "walkthrough")

    override fun onCleared() {
        super.onCleared()
        probeTts?.shutdown()
        probeTts = null
    }
}

@Composable
fun FirstLaunchScreen(
    onDone: () -> Unit,
    vm: FirstLaunchViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val voiceReady by vm.voiceReady.collectAsState()

    // rememberSaveable — a rotation mid-walkthrough must not restart it at card 1
    var page by rememberSaveable { mutableStateOf(0) }
    val lastPage = walkthroughCards.lastIndex
    val card = walkthroughCards[page]

    // Both Skip and the final Continue land here: set the once-only flag FIRST,
    // then hand control back to normal navigation. If the process dies before
    // this runs, the walkthrough simply shows again — safe either way (B8).
    fun finish() {
        FirstLaunchPrefs.markDone(context)
        onDone()
    }

    // System back walks one card back instead of quitting the app mid-intro;
    // on the first card it falls through to the default (leave the app).
    BackHandler(enabled = page > 0) { page-- }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // The card area scrolls on tiny screens / huge accessibility fonts,
        // while the dots + buttons below stay pinned and always reachable.
        // heightIn(min = viewport) keeps the card vertically centered when it
        // fits — a bare weight-Spacer would collapse inside verticalScroll.
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val viewportHeight = maxHeight
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = viewportHeight),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // ── The one idea of this card ────────────────────────────────
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape    = RoundedCornerShape(16.dp),
                    colors   = CardDefaults.cardColors(containerColor = DarkCard)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(card.emoji, fontSize = 56.sp)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            card.title,
                            style      = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color      = TextPrimary,
                            textAlign  = TextAlign.Center
                        )
                        Spacer(Modifier.height(16.dp))
                        // Big text (U9.4) — the sentence the user must remember
                        Text(
                            card.body,
                            fontSize   = 20.sp,
                            lineHeight = 30.sp,
                            color      = TextSecondary,
                            textAlign  = TextAlign.Center
                        )
                        // Voice-parity: the speaker reads the SAME words the
                        // card shows (U1.5). Hidden entirely when the phone has
                        // no English voice — never a dead button (U2.1).
                        if (voiceReady) {
                            Spacer(Modifier.height(12.dp))
                            TextButton(onClick = { vm.speak("${card.title}. ${card.body}") }) {
                                Text("🔊  Listen", fontSize = 16.sp, color = GoldLight)
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── Where am I? — three dots ─────────────────────────────────────────
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            walkthroughCards.indices.forEach { i ->
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (i == page) GoldAccent else DarkBorder)
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        Button(
            onClick  = { if (page == lastPage) finish() else page++ },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape    = RoundedCornerShape(12.dp),
            colors   = ButtonDefaults.buttonColors(
                containerColor = GoldAccent,
                contentColor   = TextOnGold
            )
        ) {
            Text(
                if (page == lastPage) "Continue" else "Next",
                fontSize   = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }

        if (page < lastPage) {
            TextButton(
                onClick  = { finish() },
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Text("Skip — take me to the app", fontSize = 16.sp, color = TextMuted)
            }
        } else {
            // Same height as the Skip button so Continue does not jump
            // when the last card removes Skip.
            Spacer(Modifier.height(48.dp))
        }
    }
}
