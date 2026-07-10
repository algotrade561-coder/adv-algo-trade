# Conviction Override — threshold tuning from full microstructure history

**Date:** 2026-07-04 · **Data:** `atm-microstructure` capture 2026-06-20 → 2026-07-03 (Parquet archive
+ 07-03 CSV) · **Method:** Lee-Ready ΔV reconstruction + L1 book imbalance, per day/index, via DuckDB
(`scripts/MicroTune.java`, run locally — the 2 GB prod box can't sort the full history).

## Why this matters
The Conviction Override Engine's absorption signal drives both the ENTRY veto and the fast EXIT
override. Absorption fires when signed volume is "large" while the book is unchanged. "Large" is
`|signedVol| ≥ threshold` — but per-tick traded volume differs enormously by index and by day, so a
single global/constant threshold mis-fires. This tunes it from real data.

## Per-day ΔV p90 (the absorption bar) + L1 imbalance p75

| date | index | ticks | ΔV p90 | ΔV p99 | imb p75 | note |
|---|---|---|---|---|---|---|
| 06-22 | NIFTY | 483,677 | 30,355 | 104,910 | 0.385 | |
| 06-23 | NIFTY | 203,225 | **204,724** | 770,177 | 0.412 | **0DTE expiry** |
| 06-24 | NIFTY | 550,091 | 13,325 | 45,305 | 0.345 | |
| 06-25 | NIFTY | 524,835 | 13,845 | 50,765 | 0.372 | |
| 06-29 | NIFTY | 558,583 | 34,320 | 124,280 | 0.389 | |
| 06-30 | NIFTY | 379,747 | **120,640** | 700,055 | 0.399 | **0DTE expiry** |
| 07-01 | NIFTY | 540,906 | 9,880 | 36,595 | 0.371 | |
| 07-02 | NIFTY | 548,122 | 10,400 | 40,040 | 0.382 | |
| 07-03 | NIFTY | 583,875 | 20,540 | 79,105 | 0.400 | |
| 06-22 | SENSEX | 533,229 | 1,000 | 3,560 | 0.375 | |
| 06-23 | SENSEX | 269,816 | 1,960 | 7,020 | 0.429 | |
| 06-24 | SENSEX | 956,175 | 5,940 | 18,980 | 0.385 | |
| 07-03 | SENSEX | 372,667 | 1,120 | 4,160 | 0.333 | |
| 06-22..24 | BANKNIFTY | ~250k avg | 510–840 | 2,160–3,540 | 0.333 | not currently traded |

## Robust per-index bar (median of daily ΔV p90, trading days)

| index | days | median p90 | min | max |
|---|---|---|---|---|
| NIFTY | 9 | 20,540 (13,845 non-expiry) | 9,815 | 204,750 |
| SENSEX | 4 | 1,540 | 1,000 | 5,940 |
| BANKNIFTY | 3 | 780 | 510 | 840 |

**NIFTY expiry vs non-expiry:** 0DTE median ΔV p90 = **162,760** vs non-expiry **13,845** — a **~12×** spike.

## Findings
1. **Absorption is per-index by ~15–25×.** `OrderFlowReconstructor`'s hardcoded `≥1000` is below NIFTY's
   *median tick* → fires almost always on NIFTY. Fixed via a per-index bar.
2. **0DTE volume explodes ~12×.** A non-expiry-calibrated NIFTY bar sits far below the expiry p90, so on
   0DTE absorption fires readily → the engine is *deliberately more aggressive* (more exits/vetoes) on
   expiry. That's the **safe** direction given the 81%-whipsaw 0DTE-afternoon finding, so a single
   non-expiry-calibrated bar is preferred over adding expiry-aware config (expiry logic is out of scope).
3. **SENSEX is genuinely ~10× thinner than NIFTY** (top-strike cumVolume 0.7–13.5M vs 1.5–149M) — the low
   SENSEX bar is real, not a capture bug. But SENSEX liquidity is growing (06-24 p90 = 5,940), so revisit.
4. **BANKNIFTY bar ≈ 800** — a NIFTY-scale default would never fire; corrected (matters if re-enabled).
5. **`imbalance-min` 0.25 confirmed** — L1 imbalance p75 is 0.33–0.43 across all days/indices.

## Applied values (`conviction-override.*`)
| knob | old (07-03 guess) | tuned (full history) |
|---|---|---|
| `absorb-min-signed-vol-nifty` | 20,000 | **15,000** (≈ non-expiry median-ish) |
| `absorb-min-signed-vol-sensex` | 1,100 | **1,500** (4-day median) |
| `absorb-min-signed-vol-banknifty` | 20,000 | **1,000** (≈ its p90) |
| `imbalance-min` | 0.25 | 0.25 (confirmed) |
| `spoof-score-threshold` | 0.6 | 0.6 (untuned — prod captures no order counts yet) |

## Caveats / open
- **No SENSEX or BANKNIFTY 0DTE tape** (SENSEX Thursday expiries never captured; BANKNIFTY not traded).
- **Spoof score still unvalidated** — needs a live day of the 5-level-depth capture build (branch, not prod).
- SENSEX bar from only 4 days and growing liquidity — revisit as more data accrues.
