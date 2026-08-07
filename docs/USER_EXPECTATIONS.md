# USER EXPECTATION LIST — the measuring stick for every screen and button
*(2026-07-18. Derived from REQUIREMENTS_FINAL.md + the owner's words. Every UI audit and every fix MUST validate against THIS list — not against Groww, not against generic design rules. The user: zero stock knowledge, zero analysis, zero effort. The app thinks; the user only reads and does.)*

## U0. The one sentence that defines the app
"I open the app, and within 10 seconds — without thinking — I know exactly what to do with my money today, and I trust that the app is protecting me."

## U1. When I open the app (Stock tab)
- U1.1 The FIRST thing I understand is today's decision: buy this / don't buy / market closed. One sentence.
- U1.2 If there is a buy: it tells me the stock, the price, HOW MANY shares for MY money, where to sell if it falls, where to sell for profit, and the profit AFTER charges. All computed. I calculate NOTHING.
- U1.3 One button takes me to Groww on the right stock. It tells me which order type to pick there.
- U1.4 If there is nothing to do, it says so kindly and tells me what IS possible (SIP, practice, tomorrow).
- U1.5 I can hear all of it by pressing one speaker button — the voice says the same thing as the screen.
- U1.6 I always see whether prices are fresh or old, in words, never a guess.
- U1.7 Nothing on the screen is a number without a meaning I understand instantly.

## U2. When I press ANY button
- U2.1 Something visible happens immediately (a screen, a dialog, a state change). No dead buttons, no mystery.
- U2.2 If the app refuses (blocked, closed, risky), the button LOOKS off and one line tells me WHY and what to do instead.
- U2.3 No button ever asks me to compute, choose between numbers I don't understand, or "adjust settings" I don't know.

## U3. When the app asks ME something
- U3.1 Only ONE question ever: "how much money today?" — with ready amounts to tap. I can skip it. I can change it later by tapping the amount on top.
- U3.2 It never asks me twice in a day, never on holidays.

## U4. When I have bought a stock (via Groww)
- U4.1 One tap tells the app "I bought it" — it pre-fills price and shares; I just confirm.
- U4.2 From then on the app watches it FOR me. The screen shows my stock with a plain verdict every day: HOLD or SELL.
- U4.3 If it hits danger (stop-loss) or success (target), my phone ALERTS me loudly, even if the app is closed, and tells me exactly: "Open Groww and sell now."
- U4.4 Today's profit/loss is always visible at the top in ₹ — real trades in green/red, practice clearly marked PRACTICE.
- U4.5 If alerts cannot work (permission off), the app WARNS me visibly — it never stays silent about being silent.

## U5. When the market is closed / internet is off / data is old
- U5.1 The app says it plainly and never shows a buy button it can't stand behind.
- U5.2 It still gives me something: what's possible now, when the market opens, my learning, my SIP.
- U5.3 Old prices are ALWAYS labeled old. The voice refuses to advise on old prices.

## U6. Mutual Fund tab (the safe path)
- U6.1 It tells me in one sentence why SIP is the safe way, then gives me ONE simplest fund first, with a plain "why".
- U6.2 It answers "if I put ₹X monthly, what could it become" honestly — labeled as a guess, never a promise.
- U6.3 One button opens Groww to start it. The app tells me the steps in 3 lines.
- U6.4 It remembers my SIP and reminds me every month — even if I don't open the app.
- U6.5 Risky funds are not just labeled — the start button is OFF for them.
- U6.6 No word like "NAV" — only words I know ("price per unit").

## U7. Intraday tab (the risky path)
- U7.1 The FIRST thing it does with small money is protect me from intraday ("small money is safer in Stock/SIP").
- U7.2 If a trade exists: same complete instruction as U1.2, plus "sell today" clarity and the 3:15 square-off promise.
- U7.3 A missed chance says "chance passed — do not buy now". It never tempts me to chase.
- U7.4 Setup words ("Strong/Okay/Weak") — never bare numbers pretending to be probability.

## U8. Guide tab
- U8.1 Lessons in MY language: what saves me money, what loses money, the scam warning. Each ends with "how this makes you money".
- U8.2 Every lesson can be listened to.
- U8.3 Nothing here is needed to use the app — it's optional depth.

## U9. Everywhere, always
- U9.1 Simple English. No RSI/ATR/VWAP/NAV/T1/breadth — ever — unless translated ("the stock looks tired").
- U9.2 Every ₹ number uses Indian grouping. Indices are points, not ₹.
- U9.3 Green = up/gain. Red = down/loss/danger. Amber = caution/practice. Never mixed meanings.
- U9.4 Big text for the numbers that matter; nothing cut off, colliding, or cramped; everything lined up.
- U9.5 The app NEVER blames me, never shows an error code, never leaves me stuck without a next step.
- U9.6 The app never promises profit. It promises honesty and protection — and shows its real weekly record.

## How to use this list
Every audit/validation walks the app pressing every button, and for each expectation marks:
PASS (works exactly like this) / PARTIAL (works but with friction) / FAIL (violated).
A release is ready when U0–U9 are all PASS.
