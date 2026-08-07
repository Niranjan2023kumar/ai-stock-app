package com.example.myapplication3.core.common

object Constants {
    const val DATABASE_NAME = "ai_stock_intelligence.db"
    // Room's version lives in AppDatabase (@Database(version = ...)) — the single source of truth.

    const val POLYGON_BASE_URL = "https://api.polygon.io/"
    const val FINNHUB_BASE_URL = "https://finnhub.io/api/v1/"
    const val ALPHAVANTAGE_BASE_URL = "https://www.alphavantage.co/"
    const val OPENAI_BASE_URL = "https://api.openai.com/v1/"
    const val ANTHROPIC_BASE_URL = "https://api.anthropic.com/v1/"

    const val NSE_OPEN_HOUR = 9
    const val NSE_OPEN_MINUTE = 15
    const val NSE_CLOSE_HOUR = 15
    const val NSE_CLOSE_MINUTE = 30
    const val NSE_TIMEZONE = "Asia/Kolkata"

    const val MIN_CONFIDENCE_SCORE = 0.6
    const val MIN_RISK_REWARD_RATIO = 2.0
    const val MAX_RISK_SCORE = 0.5
    const val MIN_LIQUIDITY_VOLUME = 100_000L
    const val MAX_SPREAD_PERCENT = 0.5

    const val CIRCUIT_BREAKER_FAILURE_THRESHOLD = 5
    const val CIRCUIT_BREAKER_TIMEOUT_MS = 60_000L
    const val RATE_LIMIT_REQUESTS_PER_MINUTE = 5

    // WorkManager's periodic minimum is 15 minutes — ScannerWorker requests exactly that.
    const val SCANNER_INTERVAL_MINUTES = 15L
    const val NEWS_REFRESH_INTERVAL_MINUTES = 15L
    const val MARKET_DATA_CACHE_MINUTES = 1L

    const val PREFS_NAME = "ai_stock_prefs"
    const val PREF_CAPITAL = "capital"
    const val PREF_RISK_PER_TRADE = "risk_per_trade"
    const val PREF_MAX_DAILY_LOSS = "max_daily_loss"
    const val PREF_MAX_WEEKLY_LOSS = "max_weekly_loss"
    const val PREF_MAX_OPEN_POSITIONS = "max_open_positions"
    const val PREF_MAX_SECTOR_EXPOSURE = "max_sector_exposure"

    const val CHANNEL_ID_OPPORTUNITIES = "channel_opportunities"
    const val CHANNEL_ID_ALERTS = "channel_alerts"
    const val CHANNEL_ID_REGIME = "channel_regime"
}
