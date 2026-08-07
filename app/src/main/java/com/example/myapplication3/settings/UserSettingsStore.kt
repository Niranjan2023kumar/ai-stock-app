package com.example.myapplication3.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Everything the user has told the app about their money, in one plain object. */
data class RiskSettings(
    val capital: Double = UserSettingsStore.DEFAULT_CAPITAL,
    val riskPerTradePercent: Double = UserSettingsStore.DEFAULT_RISK_PCT,
    val dailyLossPercent: Double = UserSettingsStore.DEFAULT_DAILY_LOSS_PCT,
    val weeklyLossPercent: Double = UserSettingsStore.DEFAULT_WEEKLY_LOSS_PCT
) {
    /** ₹ the app may risk in ONE trade (capital × percent ÷ 100). */
    val riskPerTradeRupees: Double get() = capital * riskPerTradePercent / 100.0
    /** ₹ lost in one DAY at which the app must stop all new trades. */
    val dailyLossLimitRupees: Double get() = capital * dailyLossPercent / 100.0
    /** ₹ lost in one WEEK at which the app must stop for the week (E3e). */
    val weeklyLossLimitRupees: Double get() = capital * weeklyLossPercent / 100.0
}

/**
 * The ONE source of truth for the user's money settings (spec E5). The Settings
 * screen writes here; the protection logic (loss limits, quantity sizing) reads
 * here — so what the user sets is what the app actually enforces.
 *
 * Backed by the app's existing Preferences DataStore ("stock_intelligence_prefs",
 * bound in di/AppModule.kt) and REUSES the keys SettingsViewModel already wrote
 * (pref_capital, pref_risk_per_trade, pref_max_daily_loss, pref_max_weekly_loss),
 * so values users saved before this class existed carry over unchanged.
 *
 * UNIT NOTE (the conversion, documented): the stored keys hold PERCENTAGES of
 * capital (that is what the Settings screen has always collected) — only
 * pref_capital is rupees. The `…Rupees` accessors convert on the way out:
 * rupees = capital × storedPercent ÷ 100. Consumers who need the raw percent can
 * use the `…Percent` flows instead.
 */
@Singleton
class UserSettingsStore @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    companion object {
        // Defaults match the values SettingsViewModel has always used, so a user
        // who never saved sees no change.
        const val DEFAULT_CAPITAL = 100_000.0
        const val DEFAULT_RISK_PCT = 1.0
        const val DEFAULT_DAILY_LOSS_PCT = 3.0
        const val DEFAULT_WEEKLY_LOSS_PCT = 8.0
    }

    // Legacy-compatible keys — same names SettingsViewModel wrote from day one.
    private val keyCapital = doublePreferencesKey("pref_capital")
    private val keyRiskPct = doublePreferencesKey("pref_risk_per_trade")
    private val keyDailyLossPct = doublePreferencesKey("pref_max_daily_loss")
    private val keyWeeklyLossPct = doublePreferencesKey("pref_max_weekly_loss")

    // The daily check-in amount (written by InterdayViewModel, B7b) — the REAL
    // "my money" the live app sizes from. This is the single source of truth
    // (E5/C13): preferred over the legacy typed pref_capital so Settings, the
    // loss limits, and the tabs can never show two contradictory money figures.
    private val keyDailyCapital = intPreferencesKey("daily_capital")

    /** All four values together — collect once, get a consistent snapshot. */
    val settings: Flow<RiskSettings> = dataStore.data.map { p ->
        RiskSettings(
            // ONE money value: today's check-in when set, else the legacy fallback.
            capital = (p[keyDailyCapital]?.takeIf { it > 0 }?.toDouble())
                ?: p[keyCapital] ?: DEFAULT_CAPITAL,
            riskPerTradePercent = p[keyRiskPct] ?: DEFAULT_RISK_PCT,
            dailyLossPercent = p[keyDailyLossPct] ?: DEFAULT_DAILY_LOSS_PCT,
            weeklyLossPercent = p[keyWeeklyLossPct] ?: DEFAULT_WEEKLY_LOSS_PCT
        )
    }

    // ── Typed accessors (rupees where the app spends/loses rupees) ────────────

    /** The user's total money, in ₹. */
    val capital: Flow<Double> = settings.map { it.capital }

    /** ₹ allowed to be risked in ONE trade (converted from the stored percent). */
    val riskPerTradeRupees: Flow<Double> = settings.map { it.riskPerTradeRupees }

    /** ₹ of loss in one DAY at which all new trades must stop (converted from percent). */
    val dailyLossLimitRupees: Flow<Double> = settings.map { it.dailyLossLimitRupees }

    /** ₹ of loss in one WEEK at which the app stops for the week (converted from percent). */
    val weeklyLossLimitRupees: Flow<Double> = settings.map { it.weeklyLossLimitRupees }

    /** Raw stored percentages, for the Settings screen display. */
    val riskPerTradePercent: Flow<Double> = settings.map { it.riskPerTradePercent }
    val dailyLossPercent: Flow<Double> = settings.map { it.dailyLossPercent }
    val weeklyLossPercent: Flow<Double> = settings.map { it.weeklyLossPercent }

    // ── One-shot getters (for workers / one-time computations) ────────────────

    suspend fun settingsOnce(): RiskSettings = settings.first()
    suspend fun capitalOnce(): Double = settingsOnce().capital
    suspend fun riskPerTradeRupeesOnce(): Double = settingsOnce().riskPerTradeRupees
    suspend fun dailyLossLimitRupeesOnce(): Double = settingsOnce().dailyLossLimitRupees
    suspend fun weeklyLossLimitRupeesOnce(): Double = settingsOnce().weeklyLossLimitRupees

    // ── Writers ───────────────────────────────────────────────────────────────

    suspend fun setCapital(rupees: Double) {
        dataStore.edit { it[keyCapital] = rupees }
    }

    suspend fun setRiskPerTradePercent(percent: Double) {
        dataStore.edit { it[keyRiskPct] = percent }
    }

    suspend fun setDailyLossPercent(percent: Double) {
        dataStore.edit { it[keyDailyLossPct] = percent }
    }

    suspend fun setWeeklyLossPercent(percent: Double) {
        dataStore.edit { it[keyWeeklyLossPct] = percent }
    }

    /** Save all four in ONE DataStore edit, so readers never see a half-saved mix. */
    suspend fun saveAll(
        capital: Double,
        riskPerTradePercent: Double,
        dailyLossPercent: Double,
        weeklyLossPercent: Double
    ) {
        dataStore.edit { p ->
            p[keyCapital] = capital
            p[keyRiskPct] = riskPerTradePercent
            p[keyDailyLossPct] = dailyLossPercent
            p[keyWeeklyLossPct] = weeklyLossPercent
        }
    }
}
