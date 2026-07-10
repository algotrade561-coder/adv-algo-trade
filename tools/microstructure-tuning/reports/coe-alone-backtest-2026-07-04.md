# COE-alone backtest — 2026-07-04

**Harness:** `scripts/CoeBacktest.java` · **Data:** local L1 microstructure history 06-20→07-03.

## ⚠️ What this is / isn't
The COE is a **gate on the CTO + a fast exit**, not an entry generator. To produce "trades + P&L" this
runs the COE's genuine-move confirm as a **standalone momentum-buy** (enter long on volSurge + thrust +
L1-imbalance + buy-flow, exit on absorption-reversal). It measures whether the COE's *definition* has
standalone edge — **NOT** the deployed gate/exit behaviour.

**Excluded / degraded (data not in history):** spoof leg entirely (no order counts); book imbalance is an
L1 proxy, not 5-level. **Fills** are pessimistic: buy at ask / sell at bid + ₹50 round-trip (live uses
discount LIMIT entries, so real fills are better).

## Results
| Bucket | Trades | Win% | Gross ₹ | Net ₹ | Avg net |
|---|---|---|---|---|---|
| All | 16,594 | 23.2 | −152,171 | −981,871 | −59.2 |
| exit=COE_REVERSAL | 15,286 | 22.2 | −105,870 | −870,170 | −56.9 |
| exit=MAX_HOLD(30m) | 1,258 | 35.4 | −65,000 | −127,901 | −101.7 |
| exit=EOD | 50 | 22.0 | +18,700 | +16,200 | +324.0 |
| NIFTY | 10,173 | 22.2 | −62,715 | −571,365 | −56.2 |
| SENSEX | 4,835 | 20.3 | −91,905 | −333,655 | −69.0 |
| BANKNIFTY | 1,586 | 38.0 | +2,450 | −76,851 | −48.5 |
| NIFTY 0DTE | 2,056 | 16.2 | +57,340 | −45,460 | −22.1 |
| NIFTY non-exp | 8,117 | 23.8 | −120,055 | −525,905 | −64.8 |

## Reading
1. The confirm-as-entry is a momentum-chase → buys spikes that **round-trip** (23% win) — this *confirms*
   the premise behind the COE exit override, it doesn't refute the COE.
2. Gross ≈ −₹9/trade (entries catch a little move; 0DTE gross is **positive**), but spread + ₹50 × 16.6k
   scalps = the loss. **At this frequency, option scalping loses to costs → the COE must cut trade count
   as a gate, not fire often.**
3. Deployed COE value (veto of fake CTO re-entries; faster exits on real positions) is **not measurable**
   from bare L1 tape — needs CTO fire logs + real trade history (neither exists locally yet).

## CTO+COE (mode=cto: base + 1 re-entry/strike/day, re-entry above last exit)
Adds CTO's structural gating on top of the same COE-confirm entry (`scripts/CoeBacktest.java ... cto`).

| Metric | COE-alone | CTO+COE |
|---|---|---|
| Trades | 16,594 | 1,430 (11.6× fewer) |
| Win % | 23.2 | 30.2 |
| Gross ₹ | −152,171 | −3,114 (≈ breakeven) |
| Net ₹ (−₹50/rt) | −981,871 | −74,614 |
| NIFTY 0DTE net ₹ | −45,460 | **+33,438** (+₹178/trade) |

By exit reason (CTO+COE): COE_REVERSAL n=1128 net −84,003 · MAX_HOLD n=291 net −8,625 · EOD n=11 net +18,013.
By index (CTO+COE): NIFTY gross +16,120/net −24,780 · SENSEX gross −130/net −19,330 · BANKNIFTY net −30,504.

**Reading:** CTO's selectivity cuts trades 11.6× and pushes gross from deeply negative to ≈ breakeven
(win 23→30%). Net is still negative — but it's entirely the ₹50/trade friction (gross ≈ 0). NIFTY 0DTE
is genuinely net-positive. The swing factor: fills here are market (ask/bid); the live system uses
discount-LIMIT entries (buys below ask), which on a near-zero gross could flip it net-positive. The
veto (spoof, absent from L1) and exit-speed-on-base-positions values are still unmeasured (would only help).

## v3 selectivity sweep (2026-07-05): entry cap × double-tick × SL/trail exit
`CoeBacktest.java <arc> <csv> v3 <maxEntries> <confirmTicks>` — same COE-confirm entry, but: cap =
TOTAL entries/strike/day (no re-entry-above-last-exit rule), N-consecutive-fireable-tick confirmation,
and a trend-riding exit (SL −12% / trail activate +4% gap 8% / max-hold 30m) instead of absorption.

**Cap sweep (confirm=2):**
| Cap/strike/day | Trades | Gross ₹ | Net ₹ |
|---|---|---|---|
| 1 | 662 | +14,476 | −18,624 |
| **2** | **1,238** | **+44,008** | **−17,892** |
| 5 (user ask) | 2,566 | −39,388 | −167,688 |
| 99 | 4,111 | −100,211 | −305,761 |

**Confirm-tick sweep (cap=5):** 1-tick −₹172.8k → 2-tick −₹152.5k → 3-tick −₹48.1k. Double-tick removes
~26% of the weakest entries and improves net monotonically; on this 1-sec CSV it's a soft filter (nearly
every fire persists 2 ticks) — expect it sharper on live per-tick data.

**Best combo (cap-2 + double-tick + SL/trail):** n=1243, win 30.6%, **gross +₹49,740**, net −₹12,410
(−₹10/trade). SENSEX net **+₹15.1k**, BANKNIFTY net **+₹14.8k**, NIFTY 0DTE +₹188. The single leak:
the fixed −12% STOP_LOSS leg (n=271, 0% win, −₹228k net) — TRAIL (+₹37.9k) and MAX_HOLD (+₹174.7k)
legs are both net-positive. Replace/tighten the fixed stop (the live COE fast-reversal exit is the
structural candidate) and this config is net-positive even at market fills + ₹50/rt.

**24300PE case study (NiftyPeCheck v3, cap5+double-tick):** catches the 07-03 afternoon runner
(13:15 86.8 → 13:37 104.2, +₹1,084) but the instrument still nets −₹8,062 over 9 days — churn entries
#3–5 give back more than the runner adds. Confirms: selectivity > cap.

**Decision (2026-07-05):** live `trading.conviction-trend-override.max-adds` raised 1→4 for the capture
week (deployed jar.bak-maxadds4-20260705) — live CTO fires ~handful/day through depth-confirm+spoof-veto,
unlike this generator's 285/day, and adds #2–4 need REAL fill data for the 07-11 opp-cost study.
Double-tick NOT deployed (keep the capture week's entry logic unchanged; add with the 07-11 retune).

## Next (when data exists)
- Veto study: replay actual CTO fires vs the COE gate (needs live `[CTO]` logs).
- Exit-speed study: real OI-momentum trades, COE-fast-exit vs actual-exit P&L.
- Spoof tuning: from the now-live depth capture (order counts).
- Blocked-entry opportunity-cost per gate + realized P&L of adds #2–4 (score the max-adds=4 decision).
- Stop-loss leg replacement: fixed −12% vs COE fast-reversal vs tighter/structure stop on the cap-2 combo.
