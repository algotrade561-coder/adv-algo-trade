# Dormant-strategy probes — 2026-07-09 (test ALL testable strategies, report + solution)

**Method**: same verdict standard as V5Memory/HoldScan — 3-day tick tape (07-06 range / 07-07 trend /
07-08 crash), strictly causal, ask/bid fills, −₹50/rt, 1 lot. Spot series from momentum-regime CSVs
(1-min, NIFTY+SENSEX). Harness: scripts/StratProbes.java. Plus live-trade forensics from prod H2.

## Results

| Strategy | Probe | Trades | Win% | Net 3d | Day pattern | Avalanche overlap |
|---|---|---|---|---|---|---|
| MomentumStrategy (faithful replication) | MOMO | **0** | — | 0 | never fired | — |
| MomentumStrategy (relaxed score gate) | MOMO-R | 2 | 100% | +₹2,505 | both 07-08 13:52 (crash) | **2 of 2** |
| OiShiftTrap (core thesis; live has ~15 extra vetoes) | TRAP | 11 | 64% | +₹3,118 | −₹506 / −₹443 / **+₹4,067** | **9 of 11** |
| Premium mean-reversion (MR cousin) | MRFADE | 785 | 41% | **−₹119,333** | negative all 3 days | — |
| DIRECTIONAL_BUY | live forensics (21d, prod) | 12 | 33% | −₹3,171 | see below | — |

## Findings per strategy

**MomentumStrategy — RETIRE (evidence: strict subset of avalanche).** Its faithful live conditions
(ROC5≥0.25/0.45, same-direction acceleration, EMA-21, score gate) fired ZERO times in 3 days of 1-min
bars — matching its near-dead live record (7 trades/21d). Relaxed to the loosest defensible gate it
produced exactly 2 trades: both in the 07-08 crash afternoon, both overlapping live avalanche entries
minute-for-minute. Spot-ROC momentum detects only the events V5 already owns, later and with less
context. Disabling it loses nothing.

**OiShiftTrap — LEAVE DORMANT, do NOT invest tuning (crash-day impostor).** The core trap thesis is
mildly positive ONLY because of 07-08: 9 of its 11 trades and all of its profit sit on the crash day,
overlapping avalanche entries; the range and trend days are both negative. It is a weaker avalanche
detector. Live it fires ~1/21d through its 15 vetoes — harmless. The proposed retune (vol-unit exits,
episode memory etc.) would be tuning a redundant signal.

**Premium mean-reversion — NEVER BUILD (anti-edge).** Fading ≥3-volUnit premium drops with flat OI =
knife-catching: −₹119k over 785 trades, negative every day. The live MeanReversion (index-VWAP based)
remains untestable offline (no VWAP/volume series in the tape) and fires zero live — leave off.

**DIRECTIONAL_BUY — PROBATION, not retune.** The 12 live trades (−₹3,171) decompose into already-fixed
plumbing, not signal evidence: (a) 6 of 12 were closed MANUALLY from the broker app (the bot's exits
never acted); (b) the worst cluster is 07-01 SENSEX 77000CE bought 4× in ~100 min (−₹1,442 net) —
same-strike churn that the 2026-07-03 churn/price rules now block; (c) the 07-02 pair is a same-second
duplicate (multi-user copy, doubled a loser). Post-churn-rules record: 2 trades (+₹172 COE-booked,
−₹612 SL) — behaving, sample too small to judge. SOLUTION: leave enabled 2 more weeks to accumulate a
post-fix sample, then verdict. A BacktestSuite sweep now (3 days of candles) would only overfit.

**Untestable yet — stay dormant, data accumulates passively**: GapAndGo (n=3 gaps in the tape —
needs ~15-20 sessions of opens), ExpiryGamma/Reversal (zero expiry days in the tape; TODAY adds the
first, with V5 events interleaved), Spreads (wrong data shape — needs IV surfaces + multi-day holds),
EventSpike (already answered: spike-footprint research — volume leads spikes but no post-cost edge).

## The pattern across ALL probes this week

Six rule-sets have now been tested against this tape (7 fixed variants, IMPULSE, PRESSFLIP, HOLD,
TRAP, MOMO, MRFADE). Every single one either (a) never fires, (b) loses outright, or (c) earns only
on the 07-08 crash day that the avalanche already harvests. The tape keeps giving the same answer:
in this market's microstructure, the tradeable edge is OI-capitulation with price confirmation —
everything else is either noise or an echo of it.

## Solution summary

1. **Now**: no live changes (V5's first OOS day stays clean).
2. **After the OOS week**: disable MOMENTUM (subset of avalanche, zero coverage loss); DIRECTIONAL_BUY
   on 2-week probation (post-churn-fix sample); OiShiftTrap stays as-is (dormant, harmless);
   MR/Gap/Event/Spreads stay off.
3. **Weekly**: re-fetch the tape (fetch-data.sh) and re-run StratProbes — every session adds one gap
   observation, and expiry days accumulate; verdicts upgrade automatically as n grows.
4. **The tuning investment stays where the edge is**: V5/OI-momentum, per the §14 gated ladder.
