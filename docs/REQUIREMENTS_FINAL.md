# AI Stock Intelligence — FINAL Complete Requirements
## (Version FINAL — the single source of truth)

> **STATUS: FINAL OK — locked 2026-07-17.** Implementation proceeds in PART F order.
>
> Amended 2026-07-18: aligned to docs/USER_EXPECTATIONS.md (U0–U9) — English-only copy, zero-effort corrections, consistency fixes.
>
> **Owner decisions locked (2026-07-17):**
> 1. **Scope = personal use only** (not a public release yet). No SEBI/RIA compliance layer required now; honesty rule B6 still applies. Revisit legal framing before any public release, based on app performance.
> 2. **Background protection = "both, auto"**: a foreground service (~90s price checks) runs only while a tracked position is open; a light ~15-min WorkManager scan runs otherwise. Protection is ALERT-ONLY — the app can never place/cancel orders (B0.4), and every protection line must say so honestly ("We will alert you — you do the selling yourself in Groww.").
> 3. **Order-confirm fill price**: on "Order placed? Yes", the app pre-fills the live price and asks the user to confirm/adjust the actual fill; SL/targets/quantity/P&L recompute from that real price. (True auto-capture is impossible without broker integration, which B0.4 forbids.)


---

## PART A — THE VISION

**A1.** The app is a full-power stock intelligence institution in the user's pocket. It thinks like professional analysts, but speaks like a friend.

**A2.** The user has ZERO stock knowledge to start. The user's mind does nothing for decisions — the app decides everything and tells the user, in real time, exactly what to do. Along the way, the user naturally learns — but **EARN comes first, LEARN comes second**. Learning happens while earning, never instead of earning.

**A3.** The goal of every feature: maximum chance of profit, minimum chance of loss. Loss protection is automatic and can never be switched off by mistake.

**A4.** *(UPDATED 2026-07-17 — overrides the original bilingual rule.)* The UI is **simple English only** — one short, plain sentence per line that a complete beginner can read. NO Hindi on screen, and NO "Hindi • English" dual-language mixing (the owner found the mixing cluttered). Voice (TTS) also speaks in simple English so it matches the screen. The screen must never feel crowded or complex — one clear thing to do, plain words, big text.

**A5.** The app can never guarantee profit — no one on earth can. The app's promise is: the smartest possible guidance, the strongest possible protection, and total honesty.

---

## PART B0 — THE EARNING PATH (where the money comes from and how — the most important part)

**B0.1 How money is actually made:** This app gives the decision; the user places the real order in their broker app (Groww). The full earning loop is:
1. App says (and speaks): "Buy this stock at this price — this many shares, stop-loss here."
2. User opens **Groww** and places exactly that order
3. User taps "I bought it" in our app → journal starts tracking it live
4. App watches the price in real time → notification + voice the moment stop-loss or a target hits: "Open Groww now and sell."
5. User sells in Groww → taps the result in our app → profit/loss is counted toward the daily limit

**B0.2 Groww step on every card:** Every BUY/SELL recommendation card ends with the action line "Now place this order in Groww" and a button that opens the Groww app **directly on that same stock's page** (deep link with the stock symbol), so the user lands on the right stock in one tap and places the order themselves. If direct stock-page opening is not possible for a symbol, the button opens Groww's search with the symbol pre-filled; if Groww is not installed, it opens Groww's Play Store page. No long tutorials — one tap, right stock, user does the rest.

**B0.2a Order-type label:** Wherever an order is to be placed, the card clearly shows which type to choose in Groww — "INTRADAY order" or "DELIVERY order" — big and unmissable, so the wrong type is never selected.

**B0.2b Order-confirm on return:** The moment the user comes back from Groww to our app, the app itself asks: "Order placed? Yes / No" — one tap. On Yes, the app pre-fills the live price and the user confirms or adjusts the actual fill in one tap (per locked owner decision 3); then journal tracking starts automatically. Tracking must never depend on the user remembering to tell the app.

**B0.2c Exact counts, never fractions:** The app never says "sell half". It always computes and speaks exact numbers: "Sell 1 share at Target 1, 1 share at Target 2" — for any quantity, the split is pre-calculated and shown per target.

**B0.2d Alert-survival setup (critical on Indian phones):** On first launch, the app detects the phone brand (Xiaomi/Vivo/Oppo/Realme/Samsung etc.) and walks the user through allowing background running + battery "No restriction" + autostart, with that brand's exact steps in simple English (rule A4) — because without this, the phone silently kills the scanner and stop-loss alerts never arrive. The app re-checks this setting and warns if it turns off.

**B0.3 Where each feature earns:**
- Intraday tab → same-day trading profit (the fastest earning, highest protection needed)
- Stock tab (4 daily picks + Cheap / Costly finder) → short-term delivery profit
- Mutual Fund tab → monthly SIP wealth (slow, safest earning)
- More Ways to Earn → Gold/Silver, IPO listing gains, ETF compounding, US stock growth
Every one of these ends in a concrete order the user places in Groww (or their SIP app), and the app must always say the exact order.

**B0.3a Capital-path rule (profit math first):** Charges (~₹50–60 per round trip) eat small-capital intraday profit — so the app itself chooses the RIGHT earning path from the user's money and shows only that path first:
- ₹500–2,000 → SIP / ETF only ("For your money, this is the smartest way to earn.")
- ₹2,000–10,000 → Delivery picks (charges small share of profit); intraday hidden or clearly discouraged
- ₹10,000+ → full app including Intraday
The app explains this in one honest line, so the user never loses money to charges without knowing.

**B0.3b Practice Mode (practice money, real learning):** From day one — and always available — the user can follow every pick with virtual money. The app tracks it exactly like real trades: "You earned ₹350 in practice today." Perfect for: brand-new users, users whose Groww/demat is not ready yet, and anyone after a bad week rebuilding confidence. First launch also shows "How to open your Groww account" in 3 simple steps (it takes 1–2 days), with Practice Mode active meanwhile. Practice results are always clearly labeled PRACTICE — never mixed with real money.

**B0.3c SL-ignore protection (the app never gives up):** If the price is below the stop-loss and the journal position is still open, the app escalates: strong vibration + voice every few minutes, with live numbers — "Sell now — your loss has grown from ₹300 to ₹450." It stops only when the user marks the position closed. And when the day's recommended trades are done, the app firmly says "Done for today — come back tomorrow." and shows no more intraday temptations that day.

**B0.4 The app never touches the user's money:** no broker login inside our app, no order placing, no payment. Advice + tracking + protection only. The user's money stays only in Groww.

---

## PART B — THE GOLDEN RULES (apply everywhere, forever)

**B1. One-line answer rule:** Every screen must answer "What should I do now?" in one sentence at the top — English only; A4 bans the dual-language pair. Data without a decision is banned.

**B2. Real-time rule (free data only, as fast as possible):** During market hours (9:15–15:30 IST), prices and signals auto-refresh every ~30 seconds, and a manual refresh is always one tap away. Data comes ONLY from free sources (Yahoo Finance and other free public feeds) with smart parallel fetching, host rotation, and caching — the user will NEVER be asked to buy any paid API or subscription. Free NSE data can lag the exchange by a few seconds to a couple of minutes; the app always shows the "last updated" time honestly, and stale data always carries a plain-words label: "Old price — from X minutes ago". When prices are old/cached, the 🔊 voice never speaks buy advice — it says only: "Prices are old — wait for fresh prices."

**B3. Complete instruction rule:** Every BUY/SELL recommendation must include ALL of these together, or it must not be shown at all:
- Buy/Sell price (entry zone)
- Stop-loss price with plain meaning: "If the price falls to here, sell immediately."
- Target 1, Target 2, Target 3 — each with the exact pre-computed number of shares to sell there (rule B0.2c), never "half"
- Exact quantity (how many shares) based on the user's money and the ₹-risk cap
- Risk level (LOW / MEDIUM / HIGH) and "Beginner Safe" badge when LOW
- Setup quality as a plain word — Strong / Okay / Weak (mapped from the internal confidence: 90+ Strong, 84–89 Okay, 70–83 Weak) — with the numeric /100 score one tap away under "See more" (rule B10)
- Simple reasons in plain English (why the app is saying this)
- Expected profit in ₹ for the user's OWN computed quantity, with ~₹50 charges already deducted
- The Groww action line + open-Groww button (rule B0.2)

**B4. Automatic blocking rule:** The app itself blocks trading — the user cannot make these mistakes:
- Market closed (before 9:15, after 15:30, weekends, NSE holidays)
- After 3:00 PM: hard block on new intraday trades ("Try again tomorrow")
- 2:30–3:00 PM: strong warning (very little time left)
- Daily loss limit reached: ALL trading recommendations (intraday + new stock buys) disappear for the rest of the day, with voice warning. Long-term saving guidance (SIP / ETF / Gold) stays visible — saving is protection, not risk.
- Very risky conditions stack up (big gap >3%, stock already moved >5%, very low volume, India VIX >20, stop-loss too wide >3%): warnings, and full block when 3+ serious warnings combine
The VIX/gap/volume thresholds stay as internal criteria, but any warning text shown to the user is translated per I2 (e.g. "the market is too jumpy today", never "India VIX > 20").

**B5. Simple UI rule:** Only useful things on screen. No duplicate cards, no decorative charts, no jargon, no inner tabs. Big readable text. One scroll per screen. Every card either tells the user what to DO or is deleted.

**B6. Honesty rule:** Never invent numbers. RSI is computed internally, but the word "RSI" and its raw number never appear on any user screen (rule I2). Real momentum is shown translated ("the stock looks tired — wait" / "the stock has energy"); an estimate is shown only as "today's move" in plain English, never as a fake technical number. Missing data is said simply. "Beginner Safe" appears only when truly LOW risk. Short-selling is never labeled beginner-safe.

**B7. Same-picks rule (Requirement 1.1):** The day's 4 stock picks are chosen once and stay the SAME all day. Only their live prices/targets refresh. Picks must never appear and vanish between refreshes.

**B7a. Budget-fit rule:** Picks are filtered by the user's own capital — a user with ₹2,000 never sees a ₹1 lakh share as a pick. If fewer than 4 good picks fit the budget, the app says so honestly ("Only this many good stocks fit your money today") instead of showing unaffordable ones.

**B7b. Daily check-in + always-visible P/L:** When the day changes AND the market is open that day, the app asks the user ONCE, simply: "How much money will you invest today?" — and never again that day. The question shows ready amounts to tap (e.g. ₹1,000 / ₹2,000 / ₹5,000 / ₹10,000 / Other) plus a Skip option (yesterday's amount carries over). The chosen amount stays visible at the top beside today's P/L and is tappable to change any time — the user is never made to type. On closed days (weekend/holiday) the question is skipped and the app opens straight to the closed-market earning screen (rule B7c). At the top of the app, today's running profit/loss stays visible at ALL times ("Today: +₹350" green / "Today: −₹120" red), moving toward the daily loss limit — the user always sees where they stand without searching. New/no-demat users enter directly in Practice Mode (B0.3b) and switch to real the moment their Groww account is ready.

**B7c. Market-closed earning rule:** When NSE is closed (night, weekend, holiday), the app is never empty. It shows what earning is possible RIGHT NOW — US stocks (open during Indian night), Gold/Silver action, IPOs currently open, SIP guidance — plus a countdown "The market opens again in X hours." and tomorrow's plan when ready.

**B8. Free-forever rule:** The app runs fully and permanently on free data (Yahoo Finance and free public sources) and its built-in local AI engine. NO paid API is ever required — not for data, not for AI, not for anything. Optional key fields may exist in Settings for the future, but every feature must work at full power with zero keys and zero subscriptions.

**B9. Never empty-handed rule (the glue):** The app now has many protectors (loss limits, auto-caution, earnings blackout, stale-entry, regime caution, capital-path). Whenever they leave the user with no trade, the app must say WHY in one simple line and always point to what IS possible right now: "The system is being careful today — no trades. But your SIP is running, and gold is cheap right now." A blocked user who understands stays; a confused user leaves. No screen may ever show nothing without a reason and a next step.

**B10. Simplicity-first / one-decision-default (progressive disclosure) — THE master UI rule (overrides every feature's wish to be seen):** For a zero-knowledge user, more choices = more confusion = they freeze or leave. So:
- Every main screen opens as exactly ONE decision + ONE action (the Groww button) + the 🔊 voice option — and nothing else visible.
- The beginner sees WORDS, not a dashboard: "Buy RELIANCE at ₹2,940 — 3 shares. Sell if it falls to ₹2,910." Numbers like confidence /100, Target 2/3, RSI, and score breakdowns are HIDDEN by default, one tap away under "See more".
- The app GROWS with the user: ultra-simple on day one; more is revealed only when the user taps for it.
- **Advanced sub-section (lives inside TAB 4 — Guide):** all power features and extra earning avenues — More Ways to Earn (Gold/Silver, IPO, ETF, US stocks — PART D), the "How was this number made?" score breakdown (H13), and the detailed report card (E1a) — live here, out of the beginner's main path, for future/curious users. The market-closed screen (B7c) may still surface the ONE currently-relevant earning option contextually.
- If something is not "the one thing to do right now," it is behind a tap. This rule wins over any individual feature's desire for visibility.

---

## PART C — THE FOUR TABS (bottom navigation, in this order)

### TAB 1: STOCK — daily investing guidance
**C1.** Top: one market line in words first — "Market is up today — look at the picks below" / "Market is falling — be careful" — with the NIFTY/SENSEX numbers small beside it or one tap away, shown in points, never with a ₹ sign (new rule I9).
**C2.** "What to do today?" day verdict card — one line for the whole day (e.g., "Market is good today — see the stocks below." / "Buy nothing today.").
**C3.** One big 🔊 button: one tap reads the whole day's guidance aloud in simple English — the spoken words match the screen text exactly (rules A4, I5) — the zero-reading path.
**C4.** "Today's Decision" — THE one decision of the day, fully computed: which single stock, what price, how many shares for the user's money, expected profit.
**C5.** "Today's 4 Stocks" — the day's 4 picks (same all day, rule B7), each card with the complete instruction set (rule B3).
**C6.** "Cheap / Costly finder" (Requirement 1.2): BOTH lists together — the app fixes the period itself (52-week range) and says it in words: "5 stocks near their lowest price in a year / 5 near their highest," most extreme first. Each listed stock carries a plain verdict line ("near its yearly low — cheap zone: BUY / WAIT"), never a bare list. No user-facing period selector — a beginner cannot judge 20 days vs 52 weeks; the 20-day view lives only under Advanced (C24). Within 5% of the extreme counts as "at" it.
**C7.** Big search button: search ANY stock in the world → the app returns the same simple verdict card (price, trend, buy/wait — in simple English).
**C7a. My Stocks — daily HOLD/SELL verdict (the profit completer):** Every delivery stock the user has bought (confirmed via journal) gets its own card with a fresh verdict EVERY day: "Your TATA MOTORS: HOLD — target ₹X is still ahead." or "Sell today — target reached." or "Sell today — signs of falling, protect your profit." Delivery profit reaches the pocket only when the app also says WHEN TO SELL — buying advice alone is half an app. Sell verdicts trigger a notification + the Groww button.
**C8.** If internet is off and no cache exists: one full-screen simple popup — "No internet — turn it on." — nothing else.

### TAB 2: INTRADAY — today's trading
**C9.** Top: market open/closed banner with NIFTY change (one banner only).
**C10.** Safety Check card: the single yes/no answer — "Can you trade right now?" with the guard's plain reason (rule B4).
**C11.** Daily Loss Protection: when the limit is hit, a red card explains it and every recommendation disappears (rule B4).
**C12.** THE top AI trade (Master card): complete instruction set (rule B3) + 🔊 speak button + "Why AI is saying this" in simple lines.
**C12a. Stale-entry protection (chasing = loss):** Every signal's entry zone is checked against the LIVE price. The moment the price moves beyond the entry zone, the card changes itself to: "This chance has passed — do not buy now. See the next one." and the buy instructions disappear. An old rate must never stay on screen as if it were still buyable.
**C13.** Quantity is automatic — there is NO calculator card, no formula, and no second place to type money (rule I3: the user is never given a calculator). The amount entered once in the daily check-in (B7b) is the single stored amount used everywhere; every signal card already shows "Buy N shares" in English (B3). The user never computes anything.
**C14.** Other opportunities live behind one tap — "More chances today (3) — See more"; the default Intraday screen shows only the master card (C12). Complete instructions on tap.
**C15.** Trade journal: user taps "I bought it" → the app tracks it, alerts on stop-loss/target hits (notification + voice + **vibration**), and counts the day's profit/loss for the loss limit.
**C15a. Alerts the user cannot miss:** stop-loss and target alerts repeat 2–3 times with vibration until seen. At **3:15 PM** a strong vibrating alarm fires for any open intraday position: "Open Groww and sell now — at 3:20 the broker will sell it automatically."
**C16.** Search any stock for an instant intraday verdict.
**C17.** Removed forever from this screen (rule B5): duplicate risk meter, duplicate reward-vs-risk card, duplicate market-health banner, notification settings panel.

### TAB 3: MUTUAL FUND — slow, safe wealth
**C18.** Ready-made beginner answer: ONE simplest recommended fund shown first as THE answer, with a plain one-line why and the minimum SIP amount; 2–4 alternatives live behind "See more". Projection line, clearly marked as an estimate: "If you invest ₹1,000 a month, in 5 years it could become about ₹X (an estimate, not a promise)."
**C19.** Hard safety filter: high-risk categories (sectoral, thematic, small-cap heavy, credit-risk, etc.) are never shown to a beginner.
**C20.** SIP guidance: which date, how to start, what to never do (stop SIP in a crash). The app picks the date. ONE Groww button opens the recommended fund's page directly (B0.2 pattern), and "how to start" is exactly 3 short plain-English lines.
**C20a. SIP reminder + growth line:** On the user's SIP date, a reminder: "Today is your SIP day — was ₹1,000 invested? Yes / No". And a simple always-honest growth line from free official AMFI NAV data: "Your ₹12,000 is now ₹13,100 (+9%)." Consistency is where SIP money is made — the app guards the consistency.

### TAB 4: GUIDE — earn-focused learning
**C21.** Learning exists to increase earning — nothing theoretical. Lessons are short, simple English (A4), and every lesson ends with "How this makes you money". Every lesson has its own 🔊 listen button, and the spoken lesson matches the text (I5 parity). Topics in earning order: what a stop-loss saves you, why the app picks these stocks, how SIP grows money, how targets work, common mistakes that lose money, and **scam protection** — "Never buy on a WhatsApp or Telegram 'sure tip'. Anything outside this app's rules is gambling." This one lesson protects real money.
**C21a. Data honesty line (shown once in Settings/first launch):** "Your data stays only on your phone — nowhere else (for your privacy). If you change phones or reinstall the app, your journal and settings start fresh."
**C22. Learn-while-earning (everywhere, not just this tab):** every recommendation's "Why" lines double as micro-lessons; every blocked trade explains in one line what danger it saved the user from; every stop-loss/target hit notification adds one sentence of what happened and why. The user becomes smarter automatically, just by using the app to earn.
**C23.** Balance rule: EARN > LEARN. The app must remain fully usable and profitable for someone who never opens the Guide tab; and learning content may never delay, hide, or complicate an earning action.
**C24. Advanced sub-section (per rule B10):** The Guide tab also hosts a clearly-separate "Advanced" area — the home for everything kept off the beginner's main path: More Ways to Earn (Gold/Silver, IPO, ETF, US stocks — PART D), the "How was this number made?" score breakdown (H13), and the detailed report card (E1a). It exists for the curious/growing user and must never be forced on a beginner.

---

## PART D — NEW SECTION: MORE WAYS TO EARN
*(Per rule B10, the primary home for this whole section is the **Advanced sub-section inside the Guide tab (TAB 4)** — NOT the beginner's main path. Exception: the market-closed screen (B7c) may surface the ONE currently-relevant option (e.g. US stocks at night) contextually. Same simplicity rules. Each way below has its exact decision rule and free data source — no empty screens.)*

**D1. Gold / Silver:** live gold & silver price in ₹ (free Yahoo Finance data). Verdict rule: price vs its own 200-day average → "Gold is cheap now — good time to buy." / "Gold is costly now — wait." The card pre-computes the amount for the user's money — "Put ₹500 in gold this month" — says it in plain words ("digital gold you hold inside Groww — no locker, no shop"), and ends with the one-tap Groww button (rule B0.2 applies to Gold cards too). The terms "Gold ETF / Sovereign Gold Bond" appear only under "See more" — never physical-metal complexity.

**D2. IPO Alerts:** upcoming and open IPOs from NSE's free listings — dates, the price in plain words (no "price band" jargon — say "shares cost ₹X–₹Y each"), and the app itself checks affordability against the user's stated money: "You need ₹14,500 for one lot — this fits your money." / "This needs more money than you set aside." Verdict APPLY / SKIP with a one-line reason; APPLY appears only when the user can actually afford one lot. Notification when a recommended IPO opens; the apply happens in Groww.

**D3. ETF / Index Funds:** the "invest and forget" path — default recommendation is a NIFTY 50 index fund/ETF monthly SIP; the app says which one and how much per month for the user's budget, and tracks it like C20a. No fund-picking confusion — the index IS the answer for a zero-knowledge user. The CARD says it in shopkeeper words: "One fund that owns India's 50 biggest companies — put ₹X every month." The exact fund name and the words "index fund/ETF" live under "See more" / Advanced.

**D4. US Stocks:** the world's biggest companies (Apple, Google, Microsoft, etc.), analyzed by THE SAME signal engine on US symbols (free Yahoo Finance data), prices shown with ₹ context, same one-line BUY/WAIT verdicts — live during Indian night when the US market is open (feeds rule B7c). A US-stock BUY verdict may be shown ONLY when a working one-tap order path exists for the user (B0.2-style button) AND the card pre-computes shares/amount in ₹ with forex + brokerage charges deducted (B3). If the user's broker offers no US stocks, the verdict is WATCH-only with one honest line: "Buying US stocks needs a special account — watch for now." Never a BUY the user cannot act on.

---

## PART E — ALWAYS-ON SYSTEMS (background)

**E1. Auto-scanner:** background protection runs per locked owner decision 2 — a foreground service checks prices ~every 90 seconds while a tracked position is open; a light ~15-minute WorkManager scan runs otherwise — even when the app is closed — and sends notifications. **Only what is necessary reaches the user:** stop-loss hits, target hits, daily-loss warnings, the day's picks ready, a truly big opportunity, sell verdicts on stocks the user holds (C7a), the SIP-day reminder (C20a — fired by its own scheduled notification so it works even if the app was never opened that day, per U6.4), a recommended IPO opening (D2), watchlist BUY alerts (H10), and the weekly-limit stop notice (E3e) — nothing else. Small updates stay inside the app. Notification permission is requested on first launch. Scanning resumes after phone restart. If notification permission (or the alert channel) is off or gets revoked, a visible warning card stays pinned on every tab until fixed: "Alerts are OFF — I cannot warn you about stop-loss. Tap to fix." The app never stays silent about being silent.

**E1a. Report card (simple, essentials only):** The app shows its own honest scorecard in one line — "This week's picks: 7 right, 3 wrong — total +₹X." — so trust is built on truth, not promises. No heavy statistics, just the one line that matters.

**E1b. Saving is earning:** On a bad market day the app says with full confidence: "Buy nothing today — keeping your money safe IS today's earning." A confident NO is a first-class recommendation, spoken and shown like any BUY.

**E1c. After-loss trust card:** When a stop-loss closes a trade in loss, the app never scolds and never goes silent. It shows the protection in numbers: "Your stop-loss saved you from a bigger ₹800 loss — the system is working. Tomorrow is a new day." Losses handled with respect keep the user in the game — and only a user who stays in the game earns.

**E2. Voice:** English TTS speaks new signals, warnings, and the day summary in the same simple words as the screen (rules A4, I5). If no suitable TTS voice is available on the phone, alerts still fire with vibration + on-screen text. When prices are old/cached, the 🔊 voice never speaks buy advice — it says only: "Prices are old — wait for fresh prices."

**E3. Data engine:** ~200-symbol NSE universe (Nifty 50 + Next 50 + midcap/smallcap picks), two-stage fast fetching with fallbacks, caching so the app opens INSTANTLY with last data, and honest plain-words stale labels: "Old price — from X minutes ago". Sources, all free: Yahoo Finance as the main source, NSE's official free endpoints as backup/cross-check. Future upgrade path (still free, optional): broker APIs like Angel One SmartAPI / Upstox, which give true real-time websocket data with a free account — to be added only if verified working and reliable; never a paid API.

**E3a. Simple-trust rule (replaces the Backtesting screen):** The heavy backtesting engine (Monte Carlo, Sharpe, walk-forward) runs INSIDE the app only. The user never sees that screen or those words. The user sees one honest line on the pick: "This kind of pick was right 7 out of 10 times last year."

**E3b. Removed from user navigation:** Backtesting screen, Sector Rotation screen, Market Breadth screen — analyst clutter, gone from every menu the user can reach. Their engines may keep feeding the AI internally.

**E3c. Screen consolidation + contextual news:** There is NO separate News screen. News lives where it is useful: stock/company news appears inside the Stock tab (with its picks and research), and intraday-relevant news appears inside the Intraday tab (attached to signals — "because of this news"). Watchlist is optional and lives behind "See more" on the Stock tab — never on the beginner's default view. Adding a stock is one tap from any verdict card. H10 daily verdicts and BUY alerts still apply. Portfolio, Dashboard, and Recommendations screens are removed — the Stock tab (TAB 1) and the trade journal already do those jobs in simpler form.

**E3d. Data-saver rule:** Auto-refresh runs only while the app is open on screen; the background scanner stays light per locked owner decision 2 (~90-second foreground checks only while a position is open; ~15-minute WorkManager scan otherwise), small requests. The app must respect the user's mobile data — never heavy downloads, never video, cached data reused whenever fresh enough.

**E3e. Weekly protection:** The weekly loss limit works exactly like the daily one — when the week's total loss crosses the limit, the app stops all recommendations for the rest of the week: "Enough for this week — we start fresh next week."

**E4. AI engine:** local quant engine always available; cloud AI (OpenAI/Anthropic) used only when real keys exist (rule B8), with automatic failover and circuit breakers.

**E5. Settings (the only place for configuration):** every setting ships with a safe automatic default — risk per trade, daily/weekly loss limits, and max open positions are AUTO-DERIVED from the user's stated money (e.g. risk 1–2% of capital); a beginner never needs to open Settings to use the app. Visible items use plain words only: My money (auto-filled from the daily check-in B7b), Alerts on/off + test, and an Advanced area with plain-word presets ("Very safe / Normal") behind which the derived numbers and optional API keys live. Nothing configurable lives on the main tabs.

**E6. Privacy & safety of the app itself:** no cloud backup of data, keys never in code, database encrypted-capable, nothing checked into git.

---

## PART F — BUILD ORDER (final, covers everything above)

**F1. Money loop first:** Groww button (stock deep-link) + INTRADAY/DELIVERY label + "Order placed? Yes / No" confirm + exact per-target share counts + today's P/L bar on top.
**F2. Protection that works on real phones:** vibrating repeat alerts + 3:15 PM alarm + SL-ignore escalation + battery/autostart setup guide + stale-entry protection.
**F3. Right path for the user's money:** capital-path rule + budget-fit picks + daily "How much money will you invest today?" check-in (with tap-ready amounts + Skip, per B7b).
**F4. Profit completers:** "My Stocks" daily HOLD/SELL verdicts + SIP reminder & growth line (AMFI) + weekly loss limit.
**F5. Practice Mode** + "How to open your Groww account" 3-step first-launch flow.
**F6. Simpler UI everywhere:** remove Backtesting/Sector/Breadth/News/Portfolio/Dashboard/Recommendations screens per E3b–E3c; contextual news; simple-trust line; report card; after-loss card; saving-is-earning card; + the A4 language sweep: every on-screen string, card title, dialog, notification, and TTS line converted to simple English; zero Hindi and zero dual-language lines remain anywhere in the app.
**F7. More Ways to Earn:** Gold/Silver verdict, IPO alerts, ETF/Index SIP, US stocks at night + market-closed screen with countdown.
**F8. Polish + phone testing** — then done per PART G.

---

## PART H — THE DECISION ENGINE (the app's brain — the full math behind every BUY)

**H1. Universe:** ~200 liquid NSE stocks (Nifty 50 + Next 50 + selected midcap/smallcap). Only liquid stocks — a zero-knowledge user must never be sent into an illiquid trap.

**H2. Scoring (every stock, every refresh):** points for and against, from real data only:
- Long-term trend: price vs 200-day average → up to ±15 points
- Medium trend: price vs 50-day average → up to ±12
- RSI (real 14-day Wilder from 1-year closes; if history is short an estimate is used but NEVER shown as a numeric RSI) → up to ±12
- Volume vs 3-month average (institutional activity) → up to +15
- Position in 52-week range (near low = value, near high = stretched) → up to ±10
- Today's momentum → up to ±8
- Market trend alignment with the regime engine → ±8. Regimes (from NIFTY move size + India VIX, all free data): Strong Bull / Weak Bull / Sideways / Weak Bear / Strong Bear, plus a High-Volatility overlay (VIX > 20). Stronger regimes allow more aggression; weak/volatile regimes raise the bar for every signal.
- Sector strength (rotation): picks from currently strong sectors get up to +5; weak-sector picks are penalized — money flows with sectors, the app follows the flow

**H3. Confidence (never random):** confidence = 30 + (points ÷ maximum possible) × 67. If moving-average data is missing, confidence is CAPPED (82 without MAs, 88 with one, 97 with both) — a data-poor stock can never look top-tier. Below 70 → rejected, never shown.

**H4. News adjustment (3-tier event engine):** top candidates' latest headlines are classified by event weight — BIG (fraud, arrest, results surprise, merger/acquisition, regulatory action): ±10–15; MEDIUM (guidance, large orders, FDA/approvals, rating change): ±5–8; ROUTINE (dividend, bonus, minor updates): ±0–2. Negative always outweighs positive of the same tier (fear protects money). After adjustment the 70-floor is re-applied.

**H5. Levels (ATR base + real levels):** stop-loss = 1×ATR, Target 1 = 2×ATR (risk-reward 2.0), Target 2 = 3.5×ATR, Target 3 = 5×ATR, trailing stop = 1.2×ATR. When a nearby resistance (recent swing high from 1-year data) sits before an ATR target, the target respects that level — price stops where sellers sit, not where a formula wishes. Expected profit = distance to Target 1 × quantity, minus ~₹50 charges — nothing invented.

**H6. Risk tier:** from confidence (90+ = LOW & Beginner-Safe, 84+ = MEDIUM, else HIGH), with RiskCalculator (ATR, volatility, liquidity) and India VIX feeding the guard. Quantity = auto-derived ₹-risk cap (computed by the app as a safe % of the user's stated money — never a number the user types or chooses) ÷ per-share stop distance, floored — never rounded up.

**H7. The 4 daily picks — extra gates on top of H2–H6:** budget-fit (B7a), capital-path (B0.3a), quality filter for delivery — 3 checks from free data: company profitable, debt sane, no promoter-pledge red flag (as far as free sources report), and sector diversification — never more than 1 pick from the same sector, so one sector crash can't hit all four.

**H8. Timing gates:** no fresh BUY signals 9:15–9:30 (opening volatility), warnings from 2:30 PM, hard block 3:00 PM, square-off alarm 3:15 PM. **Earnings blackout:** no fresh BUY in a stock within 2 days before its results date (free from Yahoo) — results are a gamble, not a trade; the app says so. **Big event days** (Budget, RBI policy) put the whole app in extra-caution mode.

**H9. Psychology guard:** after 2–3 consecutive real losses, the app itself slows down — fewer/no new trades with "Pause today — tomorrow will be better." — because revenge-trading is where beginners lose the most.

**H10. Watchlist verdicts + score alerts:** every watchlist stock gets the same daily one-line verdict as picks do — and when a watchlist stock's score crosses the BUY threshold, the user gets a notification: "A stock you like is now good to BUY."

**H11. Practice honesty:** Practice-mode profit is shown AFTER deducting realistic charges and small slippage, labeled "Real results may be a little more or less." — practice must not create false confidence.

**H12. Local engine (what it is):** not an LLM — a quantitative engine that turns H2–H6 numbers into the simple English thesis, reasons, and risks shown on cards (rule A4). Works free forever.

**H13. Trust engine + self-evaluation:** the internal backtesting engine (E3a) + PerformanceTracker score every pick's outcome; the report card (E1a) and the "right 7 out of 10 times" line come from these real records. **Auto-caution mode:** if the recent win-rate falls below its healthy band, the app itself slows down — fewer or no new signals, with the honest line "The system is not doing well right now — better to pause today." An app that admits its bad streaks is the only app worth trusting. **Score breakdown one tap away:** the card itself stays simple (rule B5); a small "How was this number made?" tap opens the exact arithmetic — Trend +15, Volume +13, Momentum +7, News +3, Risk −2 = 91 — trust from arithmetic, not adjectives, without cluttering the card.

**H14. Phase-2 (planned honestly, not promised as done):** broker free real-time API (Angel One/Upstox), FII/DII daily flows + delivery % + bulk/block deals, chart-pattern & candlestick engines, liquidity-sweep/order-block detection, options-chain inputs (PCR/OI) as signal quality data, multi-timeframe analysis, full macro layer, confidence calibration from realized outcomes, and monthly automatic weight-tuning from PerformanceTracker + backtests. Until each is built and verified, the app says nothing about it — H13's auto-caution mode is the bridge that protects users meanwhile.

---

## PART I — ZERO-KNOWLEDGE IMPLEMENTATION CHECKLIST (added 2026-07-17, owner's word)

> The user ONLY READS. Nothing else. Zero stock knowledge, zero analysis, zero decisions.
> The app does ALL analysis and ALL intelligence, then tells the user exactly what to do.
> Every screen, card, dialog, and notification must pass ALL of these before it ships:

**I1. Decision, not data.** Every element answers "what should I do?" — never "here is a number, you figure it out." A price without a verdict is banned.

**I2. No jargon, ever.** Words a village shopkeeper knows: buy, sell, wait, price, profit, loss, safe, risky. NEVER: RSI, ATR, VWAP, breadth, regime, volatility, P/E, bearish divergence, NAV (say "price per unit"), ETF/index fund on cards (say "ready-made basket of top companies"), square-off (say "the broker will sell it himself at 3:20"), price band (say "shares cost ₹X–₹Y each"), bare "T1/T2/T3" (say "Target 1"). If a technical value must appear, it appears translated: "the stock is tired — wait" not "RSI 78 overbought".

**I3. The app computes, the user obeys.** Quantity, entry, stop-loss, targets, charges, and "can I afford it" are ALL pre-computed. The user is never given a calculator, a formula, or a choice between numbers they can't judge.

**I4. One sentence per instruction.** "Buy 3 shares of TATA MOTORS at ₹950 now." Then the Groww button. Anything longer goes behind "See more".

**I5. Read-aloud parity.** Everything decision-critical has the 🔊 option and the spoken line matches the screen (simple English, rule A4).

**I6. Never blame, never confuse.** Errors say what happened + what to do next in one line ("No internet — turn it on"). No codes, no stack traces, no "try adjusting filters".

**I7. Safety is automatic and visible.** The user cannot take a blocked action (buttons actually disable), and the app says in one line WHY it blocked and what IS possible now (B4/B9).

**I8. Ten-second test (PART G).** A first-time user must know what to do within 10 seconds of opening any screen. If a screen needs explaining, it is wrong.

**I9. Numbers and colors speak Indian.** Every ₹ amount uses Indian digit grouping (₹1,00,000, never ₹100,000); NIFTY/SENSEX values are shown as points, never with a ₹ sign; green = gain/up only, red = loss/danger only, amber = caution and PRACTICE labels — meanings never mixed.

---

## PART G — DEFINITION OF DONE

A person who has never seen the stock market in their life opens the app and, within 10 seconds, without thinking, knows: what to buy or not buy today, at what price, how many, where to put the stop-loss, and how much profit to expect — in simple English, with a voice option (rule A4) — while the app silently protects them from every dangerous mistake. And after a month of using it, they have earned with protection AND quietly become smarter about money — earning first, learning alongside. When that is true on a real phone, the project is done.

---
*The owner said FINAL OK on 2026-07-17. This document is binding. Any change to it needs the owner's word.*
