package com.example.myapplication3.ui.screen

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication3.ui.theme.DarkBackground
import com.example.myapplication3.ui.theme.DarkCard
import com.example.myapplication3.ui.theme.GoldAccent
import com.example.myapplication3.ui.theme.GoldContainer
import com.example.myapplication3.ui.theme.GoldLight
import com.example.myapplication3.ui.theme.GreenPrimary
import com.example.myapplication3.ui.theme.TextMuted
import com.example.myapplication3.ui.theme.TextOnGold
import com.example.myapplication3.ui.theme.TextPrimary
import com.example.myapplication3.ui.theme.TextSecondary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * First-launch walkthrough (F5.2) — shown ONCE on the very first open.
 *
 * Flow:
 *   "Do you have Groww?" →
 *     "Yes" → real mode, mark done, open app
 *     "No"  → 3-step setup guide → "Start with Practice Money"
 *              → practice mode, mark done, open app
 *
 * Persistence: plain SharedPreferences boolean (readable synchronously in
 * MainActivity.onCreate BEFORE any composition).
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

@HiltViewModel
class FirstLaunchViewModel @Inject constructor(
    private val dataStore: DataStore<Preferences>
) : ViewModel() {

    private val practiceKey = booleanPreferencesKey("practice_mode")

    /** Called when user says "Yes, I have Groww" — disable practice mode (real money). */
    fun setRealMode() {
        viewModelScope.launch { dataStore.edit { it[practiceKey] = false } }
    }
}

private enum class OnboardingStep { GROWW_QUESTION, SETUP_STEPS }

@Composable
fun FirstLaunchScreen(
    onDone: () -> Unit,
    vm: FirstLaunchViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    var step by rememberSaveable { mutableStateOf(OnboardingStep.GROWW_QUESTION) }

    // System back on the setup-steps page goes back to the question, not out of the app
    BackHandler(enabled = step == OnboardingStep.SETUP_STEPS) {
        step = OnboardingStep.GROWW_QUESTION
    }

    fun finishReal() {
        vm.setRealMode()
        FirstLaunchPrefs.markDone(context)
        onDone()
    }

    fun finishPractice() {
        // practiceMode defaults to true in DataStore — nothing to set, just mark done
        FirstLaunchPrefs.markDone(context)
        onDone()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(Modifier.height(32.dp))

        when (step) {
            OnboardingStep.GROWW_QUESTION -> GrowwQuestionStep(
                onHaveGroww   = { finishReal() },
                onNoGroww     = { step = OnboardingStep.SETUP_STEPS }
            )
            OnboardingStep.SETUP_STEPS -> SetupStepsStep(
                onStartPractice = { finishPractice() }
            )
        }

        Spacer(Modifier.height(32.dp))
    }
}

// ── Step 1: Do you have a Groww account? ──────────────────────────────────────

@Composable
private fun GrowwQuestionStep(
    onHaveGroww: () -> Unit,
    onNoGroww: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkCard)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("📈", fontSize = 56.sp, textAlign = TextAlign.Center)

            Text(
                text = "Welcome to your investing guide",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                textAlign = TextAlign.Center
            )

            Text(
                text = "This app guides you on what to buy and when to sell. " +
                       "You place the order in Groww — we never touch your money.",
                fontSize = 16.sp,
                lineHeight = 24.sp,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )

            Text(
                text = "Do you have a Groww account?",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = GoldLight,
                textAlign = TextAlign.Center
            )
        }
    }

    // "Yes" — real mode
    Button(
        onClick = onHaveGroww,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = GreenPrimary,
            contentColor = TextOnGold
        )
    ) {
        Text("Yes, I have Groww", fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }

    // "Not yet" — setup flow
    OutlinedButton(
        onClick = onNoGroww,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = GoldAccent)
    ) {
        Text("Not yet — show me how", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ── Step 2: 3-step Groww setup guide ──────────────────────────────────────────

@Composable
private fun SetupStepsStep(onStartPractice: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkCard)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Text(
                text = "How to open a Groww account",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )

            SetupStep(
                number = "1",
                title = "Download Groww",
                body  = "Search 'Groww' in the Play Store and install it."
            )
            SetupStep(
                number = "2",
                title = "Sign up and verify",
                body  = "Sign up with your phone number. You will need your PAN card. " +
                        "Groww verifies accounts in 1–2 days."
            )
            SetupStep(
                number = "3",
                title = "Add money and you are ready",
                body  = "Once verified, add money from your bank. You can start with as little as ₹100."
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(containerColor = GoldContainer)
            ) {
                Text(
                    text = "While you wait for Groww to verify: use Practice Mode below. " +
                           "You will trade with virtual money — zero risk, real learning.",
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = GoldLight,
                    modifier = Modifier.padding(14.dp)
                )
            }
        }
    }

    Button(
        onClick = onStartPractice,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = GoldAccent,
            contentColor = TextOnGold
        )
    ) {
        Text("Start with Practice Money →", fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }

    Text(
        text = "Your practice trades are completely separate from real money. " +
               "Switch to real mode any time from settings.",
        fontSize = 12.sp,
        color = TextMuted,
        textAlign = TextAlign.Center,
        lineHeight = 18.sp
    )
}

@Composable
private fun SetupStep(number: String, title: String, body: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Step number circle
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = GoldAccent)
        ) {
            Text(
                text = number,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = TextOnGold,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
            Text(body, fontSize = 13.sp, color = TextSecondary, lineHeight = 18.sp)
        }
    }
}
