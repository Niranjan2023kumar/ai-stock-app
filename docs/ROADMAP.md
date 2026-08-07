# ROADMAP — what remains, in priority order
*(2026-07-18, from the internal-truth audit + gap/vision analysis. Measured against USER_EXPECTATIONS.md. Status: U-scorecard 38/47 PASS, 9 PARTIAL, 0 FAIL.)*

## P0 — MUST (protects money/trust; do next)
1. **After-loss trust card (E1c — missing entirely).** When a stop-loss closes a trade in loss: "Your stop-loss saved you from a bigger ₹X loss — the system is working. Tomorrow is a new day." + spoken. The single biggest emotional-safety gap.
2. **News truth fix.** Live probes show Yahoo news returns ZERO stock-specific headlines for most NSE symbols (or generic world news). (a) Add relevance guard (symbol/company-name must appear in headline, else NONE), (b) 72h freshness cutoff, (c) 10–15 min news TTL cache (currently up to 30 calls/30s!), (d) H4 3-tier event weights (fraud ±10-15 / guidance ±5-8 / dividend ±0-2) — phrase lists already exist.
3. **"Sell today — signs of falling" verdict (C7a).** My Stocks currently says HOLD until the stop physically hits; re-score owned stocks daily.
4. **Repeat protection alerts (C15a).** Target alert fires exactly once today; must repeat 2–3 times until seen.
5. **TradeWatchService must start the Angel feed itself** after process restart (today: silently Yahoo-only until app opened).
6. **Money-math JVM test suite (~40 tests)** over roundTripCostRs, computeTargetSplit, applyRiskSizing, IntradayScorer, SignalEngine thresholds, OutcomeRecorder, quickWinRate. Zero tests exist today.
7. **Backup ritual (no-git risk):** zip source to backups/ after each successful build, keep last 15.
8. **Local crash log** (uncaught-handler → filesDir/crashlog.txt, surfaced in Settings ▸ About).
9. **First-launch walkthrough (3 skippable screens)**: app decides→you order in Groww; tap I-bought-it; allow alerts+battery.
10. **Data export/import (SAF file)** so a phone change never wipes the journal again.
11. **B0.2d completion:** battery re-check every launch + Realme/ColorOS-specific steps + warn when protection off.
12. **Persist confidenceAtPick on tracked trades** (schema v4 + migration) — without it the calibration buckets stay empty forever.

## P1 — SHOULD
- Live ticks overlay on Watchlist + screener/movers rows (feed already subscribes all 200 symbols; pure gain).
- Show "Using live 5-minute data" badge (intradayPowered flag exposed, unrendered) + wire dailyPicksLive.
- H2 sector-strength scoring (±5) — computeSectorPerformance exists, dead.
- H8 earnings blackout + H9 psychology guard (2–3 losses → pause).
- Closed-market contextual earning line (US stocks at night / gold) on the Stock tab (B7c).
- Speakers on voiceless surfaces: MyStockCard verdict, Home LossLimitCard, Watchlist rows, NoTradeCard.
- NotificationsOffBanner on Guide tab; square-off promise on card face; CapitalNotSetCard pointer fix; MoreWays Indian grouping.
- Refresh-cost diet: cache 1-yr closes daily, fetch range=5d in loops (huge data saving).
- NSE fallback source activation (v8 single-point-of-failure); holiday-list auto-refresh (2027 gap).
- Tax awareness line on realized profit (info, not advice); Hindi voice search on Intraday/research; why-it-fell one-liner after stop-loss.
- TalkBack pass; Room exportSchema; release-build R8 dry run.
- Practice→real graduation nudge.

## P2 — NICE
- Home-screen widget (stale-labeled, never naked BUY); holiday names in banner; SIP step-up yearly nudge; "Got a WhatsApp tip? Check it here" → search verdict + scam line; Done-for-today state (B0.3c); combined block card on Intraday; instrumented 4-tab smoke test; performance baseline; Baseline Profiles.

## OWNER DECISIONS (do not build without the owner's word)
- Weekly summary notification (amends E1's notification whitelist).
- Optional "Speak alerts in Hindi" toggle (conflicts with A4 voice-matches-screen; helps users who understand spoken Hindi best).

## Structurally impossible on free data (say honestly, never fake)
- Groww-speed 1s ticks for the whole 200-stock list (broker feed = hero/pick/protection surfaces only).
- Exchange filings/results-wire news coverage; 95% win rates; auto order placement (B0.4 forbids).

## Pending audit reruns (hit session limit; resume after reset)
- internal-truth: real-data dimension, ui-truth dimension + 5 verifiers (resumeFromRunId wf_a79ee932-ff5).
- gap-vision: lens A spec-vs-built ledger (resumeFromRunId wf_d9dcef4b-fc6).
