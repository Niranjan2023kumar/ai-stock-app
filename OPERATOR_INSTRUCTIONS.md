# OPERATOR INSTRUCTIONS — AI Stock Intelligence App
## The permanent brain of every automated agent and phone session.
## READ THIS FIRST on every session before touching any code.

---

## PROJECT IDENTITY
- **App:** AI Stock Intelligence — zero-knowledge Indian users
- **Repo:** https://github.com/Niranjan2023kumar/ai-stock-app  (ONLY this repo)
- **Branch:** main (ONLY ever push to main)
- **Owner:** Niranjan2023kumar — shantiniranjan1@gmail.com
- **Token:** stored in trigger prompts and phone prompt only — NOT in this file (GitHub blocks it)
- **APK:** https://github.com/Niranjan2023kumar/ai-stock-app/releases/latest
- **Build:** GitHub Actions auto-builds on every push → `.github/workflows/build-apk.yml`

---

## SETUP (run at start of every session)
```bash
git config --global user.email "shantiniranjan1@gmail.com"
git config --global user.name "Niranjan2023kumar"
# Repo already cloned in your working directory.
# If not: git clone https://Niranjan2023kumar:$TOKEN@github.com/Niranjan2023kumar/ai-stock-app.git
# cd ai-stock-app
```
Note: $TOKEN = the PAT from your trigger prompt or phone prompt. Never hardcode it here.

---

## PUSH COMMAND (use every time)
```bash
git add -A
git commit -m "Wave X.Y: [describe what was done]"
# $TOKEN comes from the trigger prompt environment — never hardcode here
git push https://Niranjan2023kumar:$TOKEN@github.com/Niranjan2023kumar/ai-stock-app.git main
```

---

## HARDCODED RULES — NEVER BREAK (owner's law)

### Language (I2 — zero jargon)
Every user-facing string must pass the "12-year-old shopkeeper" test.
| Forbidden word | Use instead |
|---|---|
| stop-loss / stop loss | safety stop |
| book profit | take your profit |
| P&L | profit or loss |
| SIP | monthly investment |
| NAV | price per unit |
| CAGR | yearly growth |
| RSI / MACD / ATR | describe plainly or remove |
| HOLD (verdict label) | Watching |
| PASSED (verdict label) | Too late |
| breakout | price jump |
| square-off | close the trade |
| candlestick | price chart |
| T1 / T2 / T3 (bare labels) | Target 1 / Target 2 / Target 3 |
| ETF / NAV / price band | plain words (see requirements) |
| volatility (user-facing) | "the market is jumpy" |
| bearish / bullish | falling / rising |

### Crash safety
- Every `.first()` call MUST be inside `runCatching{}` — no exceptions
- Every network/API call MUST be inside `runCatching{}`
- Pattern: `runCatching { dataStore.data.first() }.getOrElse { defaultValue }`

### Data sources
- Yahoo Finance **v8 ONLY** — v7 is DEAD (HTTP 401)
  - v8 URL: `https://query2.finance.yahoo.com/v8/finance/chart/{symbol}?interval=1d&range=1d`
- Angel One SmartAPI — user enters credentials at runtime (Settings screen)
- AMFI mfapi.in — mutual fund NAV (free, no key)
- **NO dummy/hardcoded price data ever shown to users**
- **NO paid APIs required** (app works 100% on free data)

### Architecture
- App decides everything — user reads ONE sentence and acts
- No raw numbers without a plain-English verdict sentence next to them
- Confidence scores shown only as plain English, not bare numbers

---

## CODE STRUCTURE
```
app/src/main/java/com/example/myapplication3/
  ui/screen/          ← All screens (HomeScreen, TradingScreen, SettingsScreen, etc.)
  ui/viewmodel/       ← ViewModels
  ui/component/       ← Reusable composables (CommonComponents.kt)
  tracking/           ← TradeTrackerRepository, TradeWatchService, OutcomeRecorder
  smartapi/           ← Angel One SmartAPI client + feed
  intraday/           ← IntradayRepository (main Yahoo Finance v8 data source)
  mutualfunds/        ← SIP/MF logic
  ledger/             ← SuggestionLedger (PASS/FAIL record)
notifications/src/main/java/com/example/myapplication3/notifications/
  manager/            ← StockNotificationManager (all push notifications)
network/src/main/java/com/example/myapplication3/network/
  di/NetworkModule.kt ← API key injection from BuildConfig
docs/REQUIREMENTS_FINAL.md  ← THE source of truth for all features
```

---

## WHAT HAS BEEN DONE (do not redo)
- [x] Fix waves v1.1–v1.9: full jargon sweep, crash safety, dead API removal
- [x] GitHub Actions CI: builds APK on every push, releases/latest always has latest APK
- [x] "stop-loss" → "safety stop" in ALL user-facing strings, TTS, notifications, OutcomeRecorder, LearningScreen
- [x] "book profit" → "take your profit"
- [x] "SIP" → "monthly investment" in ALL user-facing strings (HomeScreen, MutualFundsScreen, LearningScreen, notifications)
- [x] PASSED verdict → "Too late" in CommonComponents
- [x] HOLD → "Checking the price…" / "Watching this for you" / "Still watching" / "Watching" badge
- [x] "(bullish)"/"(bearish)" → "(rising trend)"/"(falling trend)" in StockResearchViewModel
- [x] HindiTtsManager: all TTS is plain English (A4 compliance) — no Hindi speech
- [x] NotificationManager: "Price fell to your safety level" (was "Stop-loss hit")
- [x] TradeWatchService TTS: "safety level" (was "stop-loss")
- [x] OutcomeRecorder: "Your safety stop worked" (was "stop-loss did its job")
- [x] SettingsScreen: "(safety stops, monthly investment day)"
- [x] LedgerScreen result chips: "It worked" / "Didn't work" / "Still waiting"
- [x] CI: contents:write permission for GitHub Releases
- [x] CI: strips Windows-only gradle.properties paths (org.gradle.java.home, Avast truststore)
- [x] CI: API keys from GitHub Secrets (not hardcoded)
- [x] F1.1: GrowwLauncher.openStock() — Groww deep-link on every BUY card (GrowwActionRow)
- [x] F1.2: INTRADAY/DELIVERY order type label on every BUY card (GrowwActionRow)
- [x] F1.3: "Order placed? Yes/No" confirm on return from Groww (GrowwActionRow + PendingOrderConfirmDialog)
- [x] F1.4: Per-target exact share counts (computeTargetSplit() in GrowwAction.kt)
- [x] F1.5: TodayPnlBar on all 4 main tabs (Home, Trading, MutualFunds, Learning)
- [x] F2.1/F2.2/F2.4: Vibrating alerts, 3:15 PM alarm, BatteryGuard — all in notification layer
- [x] F2.3: Stale-entry protection — buyChancePassed() used in TradingScreen + HomeScreen; "From yesterday" cache invalidation in IntradayRepository
- [x] F4.2: AMFI growth line in MutualFundsScreen (real NAV data from mfapi.in); "SIP" jargon sweep in that screen; SipReminderWorker wording already clean
- [x] F5.1: Practice Mode (labeled PRACTICE, never mixed with real)
- [x] F5.2: First-launch Groww onboarding — "Do you have Groww?" Yes→real mode, No→3-step setup+Practice Money
- [x] F6.1: Unused screens (Backtesting, Sector, Breadth, etc.) already removed from navigation
- [x] F6.3: Weekly report card on HomeScreen (WeeklyReportLine + TradeTrackerRepository); thisWeekStats() added to SuggestionLedger for suggestion-level pass/fail
- [x] F7.1: Gold/Silver verdict card in MoreWaysScreen (GOLDBEES.NS, SILVERBEES.NS via Yahoo v8, 200-day MA rule)
- [x] F7.2: IPO alerts in MoreWaysScreen (NseRepository.fetchIpos, affordability check)
- [x] F7.3: ETF/Index SIP card in MoreWaysScreen (IndexSipCard, UTI Nifty 50)
- [x] F8.2: OneDecisionCard 10-second order — BUY→stock name→spend→profit→safety stop→button; "SIP" swept from HomeScreen

---

## BUILD ORDER — CURRENT STATE (as of 2026-08-13)
Most features are done. Remaining work listed below.

### F1 — Money loop ✅ ALL DONE
- [x] F1.1 Groww deep-link, F1.2 order type label, F1.3 order confirm, F1.4 per-target shares
- [x] **F1.5** TodayPnlBar on all 4 main tabs

### F2 — Protection ✅ ALL DONE
- [x] F2.1/F2.2/F2.4 vibrating alerts, 3:15 PM alarm, BatteryGuard
- [x] F2.3 Stale-entry protection

### F3 — Right path for user's money ✅ ALL DONE
- [x] F3.1 capital-path rule, F3.2 daily check-in, F3.3 budget-fit filter

### F4 — Profit completers ✅ ALL DONE
- [x] F4.1 My Stocks daily verdicts (MyStockCard)
- [x] F4.2 AMFI growth line + "SIP" jargon sweep
- [x] F4.3 weekly loss limit

### F5 — Practice Mode ✅ ALL DONE
- [x] F5.1 Practice Mode
- [x] F5.2 First-launch Groww onboarding

### F6 — Simpler UI ✅ MOSTLY DONE
- [x] F6.1 Unused screens removed from navigation
- [x] F6.3 Weekly report card (WeeklyReportLine in HomeScreen)
- [ ] **F6.2** Contextual news: stock news INSIDE Stock tab, intraday news INSIDE Intraday tab

### F7 — More Ways to Earn ✅ ALL DONE
- [x] F7.1 Gold/Silver verdict card (GOLDBEES/SILVERBEES via Yahoo v8)
- [x] F7.2 IPO alerts (NseRepository + affordability check)
- [x] F7.3 ETF/Index SIP card (IndexSipCard, UTI Nifty 50)
- [x] F7.4 US Stocks (watch-only, 4 stocks via Yahoo v8)
- [ ] **F7.5** Market-closed screen with countdown ("Opens in X hours")

### F8 — Polish
- [x] **F8.2** 10-second test — OneDecisionCard restructured: BUY→name→spend→profit→stop→button
- [ ] **F8.1** Phone brand detection + battery/autostart deep-link per brand

### NEXT PRIORITIES (work in this order)
1. **F6.2** — Add contextual news inside Stock tab (use Yahoo news API or NSE)
2. **F7.5** — Market-closed countdown screen (MarketCalendar already has nextMarketOpenMs())
3. **F8.1** — Battery/autostart deep-link per brand (Xiaomi, Samsung, etc.)

---

## QUICK CHECKS (run every maintainer session)

### Jargon scan
```bash
grep -rn --include="*.kt" \
  -e "stop.loss" -e "stop-loss" -e "\"P&L\"" -e "CAGR" \
  -e "\"SIP\"" -e "\"NAV\"" -e "\"MACD\"" -e "\"RSI\"" \
  -e "candlestick" -e "square.off" -e "book profit" \
  -e "\"HOLD\"" -e "\"PASSED\"" -e "breakout" -e "bearish" -e "bullish" \
  app/src notifications/src 2>/dev/null | grep -v "^Binary" | grep -v "//" | grep -v "TODO"
```
Expected result: **no matches**. Fix every match found.

### Crash safety scan
```bash
grep -rn --include="*.kt" "\.first()" app/src notifications/src 2>/dev/null | \
  grep -v "runCatching" | grep -v "//"
```
Expected result: **no matches**. Wrap every match in `runCatching{}`.

### Dead API scan
```bash
grep -rn --include="*.kt" "v7/finance" app/src notifications/src 2>/dev/null | grep -v "//"
```
Expected result: **no matches**. Replace every match with v8.

---

## DEFINITION OF DONE (from REQUIREMENTS_FINAL.md PART G)
A person who has NEVER seen the stock market opens the app and within 10 seconds — without thinking — knows:
what to buy or not buy today, at what price, how many, where the safety stop is, and how much profit to expect.
In simple English. With a voice option. While the app silently protects them from every dangerous mistake.
**When that is true on a real phone, the project is done.**

---
*Last updated by operator: 2026-08-13 (session 3 — F1.5, F4.2, F5.2, F6.1, F6.3, F7.1–7.3, F8.2 done; full jargon+crash+dead-API audit passed)*
*Source of truth: docs/REQUIREMENTS_FINAL.md — never contradict it*
