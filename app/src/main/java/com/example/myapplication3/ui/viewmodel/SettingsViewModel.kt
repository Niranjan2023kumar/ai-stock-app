package com.example.myapplication3.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication3.settings.UserSettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val capital: Double = UserSettingsStore.DEFAULT_CAPITAL,
    val riskPerTrade: Double = UserSettingsStore.DEFAULT_RISK_PCT,
    val maxDailyLoss: Double = UserSettingsStore.DEFAULT_DAILY_LOSS_PCT,
    val maxWeeklyLoss: Double = UserSettingsStore.DEFAULT_WEEKLY_LOSS_PCT,
    /** True once the saved values have been loaded — fields render only after this. */
    val isLoaded: Boolean = false,
    val isSaved: Boolean = false,
    /** One plain-English line when a value cannot be saved; null when all is fine. */
    val saveError: String? = null
) {
    // One-line, plain-word checks (I2/I6) — shown inline AND enforced on Save.
    val capitalError: String?
        get() = when {
            capital <= 0.0 -> "Your money must be more than 0."
            capital < 100.0 -> "Enter at least ₹100."
            capital > 100_000_000.0 -> "That looks too big — please check the number."
            else -> null
        }
    val riskError: String?
        get() = when {
            riskPerTrade <= 0.0 -> "This must be more than 0."
            riskPerTrade > 10.0 -> "Too risky — keep this 10 or less."
            else -> null
        }
    val dailyLossError: String?
        get() = when {
            maxDailyLoss <= 0.0 -> "This must be more than 0."
            maxDailyLoss > 20.0 -> "Too high — keep this 20 or less."
            else -> null
        }
    val weeklyLossError: String?
        get() = when {
            maxWeeklyLoss <= 0.0 -> "This must be more than 0."
            maxWeeklyLoss > 50.0 -> "Too high — keep this 50 or less."
            maxWeeklyLoss < maxDailyLoss -> "The weekly stop cannot be less than the daily stop."
            else -> null
        }
    val firstError: String?
        get() = capitalError ?: riskError ?: dailyLossError ?: weeklyLossError
}

/**
 * Settings are read and written ONLY through [UserSettingsStore] — the same store
 * the protection logic reads — so a saved limit is a real limit, never a placebo.
 *
 * Saved values are loaded ONCE (first DataStore emission). After that the user's
 * typing is the source of truth, so a save elsewhere on the screen (e.g. the
 * Angel One card, which shares the same DataStore file) can no longer wipe or
 * silently revert unsaved edits here.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val userSettings: UserSettingsStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val saved = userSettings.settingsOnce()
            _uiState.update {
                it.copy(
                    capital = saved.capital,
                    riskPerTrade = saved.riskPerTradePercent,
                    maxDailyLoss = saved.dailyLossPercent,
                    maxWeeklyLoss = saved.weeklyLossPercent,
                    isLoaded = true
                )
            }
        }
    }

    fun updateCapital(value: Double)       = _uiState.update { it.copy(capital = value) }
    fun updateRiskPerTrade(value: Double)  = _uiState.update { it.copy(riskPerTrade = value) }
    fun updateMaxDailyLoss(value: Double)  = _uiState.update { it.copy(maxDailyLoss = value) }
    fun updateMaxWeeklyLoss(value: Double) = _uiState.update { it.copy(maxWeeklyLoss = value) }

    fun saveSettings() {
        val state = _uiState.value
        val error = state.firstError
        if (error != null) {
            // Blocked save with the one-line reason (I7) — nothing bad gets stored.
            _uiState.update { it.copy(saveError = error, isSaved = false) }
            return
        }
        viewModelScope.launch {
            userSettings.saveAll(
                capital = state.capital,
                riskPerTradePercent = state.riskPerTrade,
                dailyLossPercent = state.maxDailyLoss,
                weeklyLossPercent = state.maxWeeklyLoss
            )
            _uiState.update { it.copy(isSaved = true, saveError = null) }
        }
    }

    /** Called after the snackbar has been shown, so it does not repeat. */
    fun consumeSaved() = _uiState.update { it.copy(isSaved = false, saveError = null) }
}
