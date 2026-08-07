# CONTINUATION PROMPT — paste this into Claude Code on the new PC
*(Project: AI Stock Intelligence. This file travels with the code so any session can resume. Last updated 2026-07-23 after the full validation + fix cycle.)*

## Paste this whole block as your first message on the new PC:

---

You are the MAINTAINER and project head of this Android app: "AI Stock Intelligence".
I am the owner. Continue exactly from where the previous session left off.

STEP 1 — READ YOUR BRAIN (read these fully before anything else):
1. docs/REQUIREMENTS_FINAL.md — the locked spec (amended 2026-07-18)
2. docs/USER_EXPECTATIONS.md — U0–U9, THE measuring stick for everything
3. docs/ROADMAP.md — prioritized remaining work
4. docs/VALIDATION_2026-07-23.md — the last full audit: 126 PASS, 28 findings (ALL FIXED)
5. docs/CONTINUE_PROMPT.md — this handoff

STEP 2 — BINDING RULES (never break):
- The app user has ZERO stock knowledge and ZERO effort: the app does ALL thinking; the
  user only READS and places orders in Groww himself.
- Judge everything by USER EXPECTATION (docs/USER_EXPECTATIONS.md), never by "Groww does it".
- METHOD for every change: VALIDATE against user expectations → if not met, think from
  every angle → implement → build + tests (gradlew :app:testDebugUnitTest must stay green)
  → snapshot (powershell -ExecutionPolicy Bypass -File backup_source.ps1) → adb install -r
  → verify on the phone with foreground-checked screenshots.
- NEVER use git (no init/commit) — use the backup_source.ps1 snapshot ritual instead.
- NEVER uninstall the app from the phone (wipes encrypted Angel One keys + trade journal).
  Always "adb install -r". This PC's debug.keystore must match the phone's installed signature
  (it did on the previous PC); if a NEW PC has a different debug.keystore, install -r will FAIL
  on signature mismatch — copy the working ~/.android/debug.keystore across, do NOT uninstall.
- HONESTY above all: never an invented number; PASS/FAIL and trust lines only from real
  recorded outcomes; stale data always labeled; the app never promises profit.
- KEYS NEVER IN CODE (rule E6): never hardcode Angel One (or any) credentials in source or
  docs. They live ONLY in the phone's encrypted in-app store. The owner may ask you to
  hardcode — decline and explain (source travels in backup zips + the APK in plain text; the
  keys can place trades). Enter creds only in the app's Settings ▸ Live data card, by the owner.
- Simple English on screen. Voice has two Settings switches: master Voice ON/OFF and
  "Speak alerts in Hindi" (owner-approved).
- Use MANY parallel agents (workflows) with strictly disjoint file ownership to work fast;
  hand-do interconnected/safety/money-math edits yourself; ALWAYS build + test after agent
  edits and review their diffs (an agent once left a stray "\" comment marker — caught by review).
- Device scope: drive ONLY this app (com.example.myapplication3). Foreground-check before every
  screenshot; if focus is another app, HOME + relaunch, never interact/capture.

STEP 3 — NEW-PC SETUP:
- gradle.properties is clean of machine-specific lines. If Gradle can't find Java, set
  JAVA_HOME (or org.gradle.java.home) to this PC's Android Studio jbr.
- Let Android Studio create local.properties (SDK path), or write sdk.dir yourself.
- Build once: gradlew :app:assembleDebug — then install -r to the phone.
- Two phones may be connected (a Realme RMX3998 = the real journal phone, and a spare vivo
  V2420). ALWAYS target with `adb -s <id>`. If a device is missing, `adb kill-server && adb
  start-server`. ColorOS/Realme may show an on-device "Install via USB" confirmation — enable
  "Install via USB" in Developer options to stop the prompt.

STEP 4 — CURRENT STATE + WHAT'S NEXT:
- DONE (2026-07-23): a full 10-dimension adversarial validation (126 PASS, 28 findings) then
  ALL 28 fixes implemented + integrated (BUILD SUCCESSFUL, 31/31 tests GREEN, snapshot
  src_2026-07-23_1407.zip) and on-device verified. Headliners fixed: the HIGH single-money-source
  bug (Settings money now read-only from the daily check-in), H5 target-clamp to real
  support/resistance, honest stop-loss escalation (re-alerts until closed), a flat brokerage
  floor in the cost model, per-signal intraday risk on searched stocks, small-money protection
  before any profit teaser, the whole More Ways build-out (Gold digital-gold line, ETF per-user
  ₹/mo, US ₹ conversion, IPO card), Mutual-Fund plain words, Indian ₹ formatting everywhere,
  the wall-clock freshness guard, and brand autostart for Xiaomi/Vivo/Oppo/OnePlus/Samsung.
  Full detail: docs/VALIDATION_2026-07-23.md.
- ANGEL ONE LIVE FEED — BLOCKED ON THE OWNER, NOT ON CODE: the owner's 4 SmartAPI values are
  saved (encrypted) on the phone, but login returns "Invalid totp and client combination".
  Verified: the app's Totp.kt is a correct RFC-6238 generator (matches a reference algorithm
  byte-for-byte), the phone clock is auto-synced, and Client code + MPIN are accepted — ONLY the
  TOTP is rejected. Cause: the TOTP secret is either mis-copied OR not fully ACTIVATED on the
  Angel One SmartAPI TOTP page (you must scan it into an authenticator and type a code back to
  confirm). FIX PATH (owner action): re-activate TOTP on Angel One, then re-enter ONLY the
  corrected TOTP secret in the app's Live data card and Save — the feed self-connects during
  market hours (Mon–Fri 9:15–15:30 IST). NEVER hardcode; the app runs fully on free Yahoo data
  meanwhile (verified live: 182 real NSE stocks fetched, 3 signals generated).
- FOLLOW-UPS QUEUED (do next):
  (1) Wire the LIVE NSE IPO feed for D2 (network module: add ipo endpoints to NseApiService +
      an NseRepository method + inject into MoreWaysViewModel.fetchIpos). Today D2 shows the
      honest "No IPOs open right now" empty-state — no fake rows.
  (2) Double-check the $→₹ forex rate: US cards showed ~96 ₹/$ vs an expected ~86 — verify
      MoreWaysViewModel.fetchUsdInrRate (repository.fetchStockResearch("USDINR=X")).
  (3) ~8 universe symbols 404 on Yahoo (renamed tickers): ZOMATO→ETERNAL, MCDOWELL-N→UNITDSPR,
      GMRINFRA→GMRAIRPORT, IOCL→IOC, CENTRALBANK→CENTRALBK, PIRAMALENT→PPLPHARMA, LTIM, TATAMOTORS
      — refresh the symbol list.
- THEN continue docs/ROADMAP.md P1/P2 (export/import Settings hookup, TalkBack pass, release
  dry run). Owner decision still pending: Sunday weekly summary notification — ask me.
- KNOWN ENV QUIRK: during rapid adb automation the Realme sometimes renders a black screen (app
  still alive) — recover with force-stop + relaunch. Not a normal-user bug.

Confirm you have read the docs, then give me a short status and continue from the follow-ups.

---
