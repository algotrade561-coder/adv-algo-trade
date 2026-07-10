# Institutional-Hold probe — 2026-07-09 (review-4 "trend without OI unwind" proposal)

**Hypothesis tested**: "OI flat + premium grinding up + large existing OI" (institutions positioned
earlier, now holding) is a tradeable entry that V5's avalanche misses. Proposed trigger:
`|dOI5m| < 0.5% && dP15m rising && big absolute OI`, wide-trail exits.

**Harness**: scripts/HoldScan.java — same strictly-causal pipeline as V5Memory (5s samples, 60s MAD
baselines, 25-min warmup, 09:25–15:10, premium 15–700, abs/spoof veto, 120s cooldown, 5/day/inst),
3 days of full tick data (07-06 range, 07-07 trend, 07-08 crash), 1 lot, ask/bid fills, −₹50/rt.

## Forward mid-returns (no trading, no costs) — the signal IS real

| condition | n | fwd 15m | n | fwd 30m |
|---|---|---|---|---|
| BASELINE (all ticks, 120s cadence) | 51,133 | −0.04% | 49,749 | +1.18% |
| A fixed (dP15m≥2%, \|dOI5m\|<0.5%) | 1,985 | **+4.40%** | 1,945 | **+10.41%** |
| B = A + oi ≥ 0.8×dayMax | 1,702 | +4.30% | 1,669 | +10.73% |
| C self-normalized (dP15m ≥ 3×volUnit) | 457 | +4.51% | 435 | +6.25% |

## Simulated books (proposal's own exits: SL −6%, trail +4%/3%, 45m cap, EOD)

| variant | trades | win% | net 3d | 07-06 | 07-07 | 07-08 |
|---|---|---|---|---|---|---|
| A | 1,157 | 42.4% | **−₹57,745** | −₹63,623 | +₹11,415 | −₹5,537 |
| B | 1,034 | 40.4% | **−₹61,830** | −₹60,110 | +₹4,141 | −₹5,861 |
| C | 817 | 45.3% | +₹17,107 | −₹29,183 | −₹13,233 | +₹59,523 |

## Verdict: REJECTED as an entry — same failure mode as IMPULSE/PRESSFLIP

1. **The signal identifies moves; the trade cannot monetize them.** +4%/15m forward return vs a
   −₹58k book = by the time "grind + flat OI" is confirmed, the entry buys the ask after a 2%+
   run-up; spread + trail whips + SL asymmetry eat the edge. Detection ≠ tradeable edge — the
   central lesson of this whole calibration week, reconfirmed.
2. **The only positive variant (C) is a crash-day impostor.** Its entire profit is 07-08
   (+₹59.5k) — the day the AVALANCHE branch already harvests — while it bleeds −₹29k on the range
   day and −₹13k on the trend day. Adding it live would double exposure on the day-type we already
   capture and reopen the range-day leak V5 was built to close.
3. **The "with operator score + basis + VWAP" version already exists**: that is the live
   sustained-drift / operator-OI-led branch (60-min drift, op ≥ 70). The proposal's increment was
   "fire earlier with lower conviction" — variants A/C are exactly that, and they lose.

## What survives

- The forward-return asymmetry means the HOLD condition has value as **context** (e.g., a future
  ride/defer input, like the AVALANCHE scalp-defer) — not as an entry. Not implemented; revisit
  only if OOS exit attribution shows scalps being booked during live grinds.
- No code or config changes from this probe. Jar @ 9584897 unchanged.
