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
- [x] Fix waves v1.1–v1.7: full jargon sweep, crash safety, dead API removal
- [x] GitHub Actions CI: builds APK on every push, releases/latest always has latest APK
- [x] "stop-loss" → "safety stop" in ALL user-facing strings, TTS, notifications, OutcomeRecorder, LearningScreen
- [x] "book profit" → "take your profit"
- [x] "SIP" → "monthly investment" in user-facing strings
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
- [x] F2.3: Stale-entry protection — buyChancePassed() used in TradingScreen + HomeScreen; "From yesterday" cache invalidation in IntradayRepository

---

## BUILD ORDER — WHAT TO DO NEXT
Work through these IN ORDER. One item per wave. Update this file after each item.

### F1 — Money loop ✅ MOSTLY DONE
- [x] **F1.1** Groww deep-link button on every BUY card (GrowwActionRow in GrowwAction.kt)
- [x] **F1.2** "INTRADAY order" / "DELIVERY order" label on every BUY card (GrowwActionRow)
- [x] **F1.3** "Order placed? Yes / No" confirm on return from Groww (GrowwActionRow + PendingOrderConfirmDialog)
- [x] **F1.4** Per-target exact share counts (computeTargetSplit in GrowwAction.kt)
- [ ] **F1.5** Today's running P&L bar always visible at top of every main tab (START HERE)

### F2 — Protection on real phones
- [ ] **F2.1** Vibrating repeat alerts (2–3 times) for stop-loss + target hits
- [ ] **F2.2** 3:15 PM vibrating alarm for any open intraday position ("Open Groww and sell now")
- [x] **F2.3** Stale-entry protection: "This chance passed — wait for the next one." (TradingScreen + HomeScreen)
- [ ] **F2.4** Battery/autostart setup guide on first launch (Xiaomi/Vivo/Oppo/Realme/Samsung)

### F3 — Right path for user's money
- [ ] **F3.1** Capital-path rule: ₹500–2k → show SIP only; ₹2k–10k → delivery; ₹10k+ → full app
- [ ] **F3.2** Daily "How much money today?" check-in — tap ₹1k/₹2k/₹5k/₹10k/Other/Skip
- [ ] **F3.3** Budget-fit filter: never show a stock the user cannot afford

### F4 — Profit completers
- [ ] **F4.1** "My Stocks" daily HOLD/SELL verdicts for confirmed delivery positions
- [ ] **F4.2** SIP reminder notification + AMFI growth line ("Your ₹12,000 is now ₹13,100 +9%")
- [ ] **F4.3** Weekly loss limit (stops all recommendations when crossed)

### F5 — Practice Mode
- [ ] **F5.1** Practice Mode — virtual money, always labeled PRACTICE, never mixed with real
- [ ] **F5.2** "How to open Groww account" 3-step first-launch flow

### F6 — Simpler UI
- [ ] **F6.1** Remove/hide from navigation: Backtesting, Sector, Breadth, News, Portfolio,
              Dashboard, Recommendations screens
- [ ] **F6.2** Contextual news: stock news INSIDE Stock tab, intraday news INSIDE Intraday tab
- [ ] **F6.3** Report card: "This week's picks: 7 right, 3 wrong — total +₹450"

### F7 — More Ways to Earn (in Guide tab Advanced section)
- [ ] **F7.1** Gold/Silver verdict card (Yahoo Finance free data, 200-day average rule)
- [ ] **F7.2** IPO alerts (NSE free listings, affordability check)
- [ ] **F7.3** ETF/Index SIP card ("One fund that owns India's 50 biggest companies")
- [ ] **F7.4** US Stocks screen (night hours, Yahoo Finance, ₹ context)
- [ ] **F7.5** Market-closed screen with countdown ("Opens in X hours")

### F8 — Polish
- [ ] **F8.1** B0.2d: phone brand detection + battery/autostart deep-link per brand
- [ ] **F8.2** 10-second test: any first-time user knows what to do within 10 seconds of opening

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
*Last updated by operator: 2026-08-13 (session 2 — jargon sweep complete, F1.1–F1.4 done, next: F1.5 P&L bar)*
*Source of truth: docs/REQUIREMENTS_FINAL.md — never contradict it*
