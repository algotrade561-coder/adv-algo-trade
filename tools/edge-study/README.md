# edge-study — no-lookahead edge & net-of-cost tradability study

Standalone Python 3 utility (Linux/EC2). Reads the bot's option-chain snapshots and measures whether
candidate signals actually predict forward moves — and, critically, whether **buying the ATM option on
each signal is profitable NET OF COSTS**. Pure stdlib, idempotent, no daemon. **No trading-app code.**

Part of `tools/` (standalone utilities — see `tools/README.md`).

## What it does
Snapshots: `data/chain-snapshots/<YYYY-MM-DD>/<UNDERLYING>_<HHmm>.json.gz`
(`timestamp, spot(=future), atmStrike, vix, expiry, strikes[]` with `ceLTP/ceOI/ceOiChange/ceBid/ceAsk`
+ pe* + Greeks).

For each snapshot **i** (NO LOOKAHEAD — signal uses data ≤ i; forward return strictly after i):
- **Signals** (dir +1 bullish / −1 bearish / 0):
  - `oi_lean` = sign(ΣpeOiChange − ΣceOiChange) — PE build > CE build ⇒ bullish
  - `pcr_slope` = sign(PCRᵢ − PCRᵢ₋₁), PCR = ΣpeOI/ΣceOI — rising ⇒ bullish
  - `spot_mom` = sign(spotᵢ − spotᵢ₋₃) — 15-min continuation
  - `range_fade` = trailing-30m position; pos>0.8 ⇒ −1, pos<0.2 ⇒ +1, else 0
  - `oi_velocity` — **hook** left in code (returns 0) for when microstructure is wired in
- **Forward spot returns** at +1/+2/+3/+6 snapshots (= +5/+10/+15/+30 min), signed by the signal.
- **Metrics** per signal × horizon: N, hit-rate, mean signed return (% and points), **t-stat
  (DESCRIPTIVE only — overlapping windows)**. Split by **regime** (trend if trailing-30m range >0.20%
  else range) and **time-of-day** (morn <11:00, mid <14:00, aft).
- **Tradability leg (decision-relevant):** buy ATM CE (bullish) / ATM PE (bearish) at signal time, exit
  at horizon. Entry = ATM ask, exit = ATM bid (fallback LTP ± 0.5% when depth missing); **net of real
  charges** (the cost model mirrors `util/ZerodhaChargesCalculator` / `OptionsChargesEstimator` —
  brokerage ₹20×2, STT 0.15% sell, txn 0.03553%, GST 18%, SEBI ₹10/cr, stamp 0.003% buy) over one
  contract lot. Reports **net win-rate, mean net %, mean net ₹** per signal × horizon × regime × tod.

> The net-option leg is the point: a signal can be "right" on spot direction yet **lose money** once
> premium spread + theta + charges are paid. On the 06-22 sample every signal's net ATM-option
> expectancy was negative — that is the honest, decision-relevant output.

## Output
- Per day: `reports/edge-study/<date>/edge_study_<date>.csv` (+ readable `.txt`).
- Cumulative store: `reports/edge-study/cumulative.csv` — sufficient-statistics rows per
  signal×horizon×regime×tod per day (idempotent: re-running a date replaces its rows).
- `--rollup`: pools the cumulative store across all days → `reports/edge-study/_rollup/edge_rollup.csv`
  with pooled hit-rate / net expectancy + descriptive t-stat, and a `small_sample` flag (N_opt < 30).

## CLI
```bash
python3 tools/edge-study/edge_study.py                 # today (IST), NIFTY
python3 tools/edge-study/edge_study.py --date 2026-06-22 --underlyings NIFTY,BANKNIFTY,SENSEX
python3 tools/edge-study/edge_study.py --rollup        # pool all days, print top net-expectancy signals
# options: --snapshot-dir (default data/chain-snapshots), --out-root, --cumulative
```
Lot sizes used for net cost: NIFTY 65, BANKNIFTY 35, SENSEX 20 (edit `LOT_SIZE` in `edge_study.py`).

## EC2 install & scheduling (15:30 IST, weekdays)

> ⚠️ **Timezone caveat — check the OS first:** `timedatectl` (look at "Time zone").
> - OS = **UTC** → 15:30 IST = **10:00 UTC** → cron `0 10 * * 1-5`.
> - OS = **Asia/Kolkata** → cron `30 15 * * 1-5`.
> `run-edge-study.sh` always studies *today in IST* (`TZ=Asia/Kolkata date`) and prints the resolved
> server/IST time + OS timezone so you can confirm.
>
> **Ordering:** the microstructure Parquet roller runs **15:35 IST**; this study reads chain snapshots
> (independent) at **15:30**, so 15:30 is fine. If you later enable the `oi_velocity` microstructure
> signal, move this to **after 15:40**.

### Option A — systemd timer (recommended; TZ-robust)
```bash
# from the repo on the box:
sudo cp tools/edge-study/edge-study.service /etc/systemd/system/
sudo cp tools/edge-study/edge-study.timer   /etc/systemd/system/
sudo nano /etc/systemd/system/edge-study.service   # set User= and WorkingDirectory= to your repo path
sudo systemctl daemon-reload
sudo systemctl enable --now edge-study.timer
systemctl list-timers edge-study.timer             # confirm next run
```
The timer uses `OnCalendar=Mon..Fri 15:30 Asia/Kolkata` (explicit TZ, systemd v240+). If your systemd is
older, edit the timer per the comments (10:00 for UTC OS, or 15:30 for Asia/Kolkata OS).

### Option B — cron
```bash
chmod +x tools/edge-study/run-edge-study.sh
crontab -e
```
Add the line that matches your OS timezone (study NIFTY; edit the wrapper/env for more underlyings):

**UTC server (15:30 IST = 10:00 UTC):**
```cron
0 10 * * 1-5 cd /opt/adv-algo-trade/unified-algo-trade && EDGE_UNDERLYINGS=NIFTY tools/edge-study/run-edge-study.sh >> reports/edge-study/cron.log 2>&1
```
**Asia/Kolkata server:**
```cron
30 15 * * 1-5 cd /opt/adv-algo-trade/unified-algo-trade && EDGE_UNDERLYINGS=NIFTY tools/edge-study/run-edge-study.sh >> reports/edge-study/cron.log 2>&1
```
(Adjust the repo path. `CRON_TZ=Asia/Kolkata` at the top of the crontab also works on many distros to let
you always write `30 15 * * 1-5`.)

## Notes / honesty
- t-stats are **descriptive** (overlapping forward windows inflate significance) — use the rollup's
  `small_sample` flag and accumulate many days before trusting any number.
- Backtest/edge ≠ future. The net-option leg is a realistic *lower bound-ish* (mid/exit via bid/ask),
  not a guarantee.
- Deploy with the repo (or `scp tools/edge-study` to the box). Reports land under `reports/edge-study/`.

---

## Case-study matrix, microstructure tier & H2 attribution (extends this tool)
- `case_studies.py` — enumerate/run feasible combos with a multiple-testing + cross-day-persistence
  guardrail. Modes: `chain` (all dates), `cross` (NIFTY→SENSEX/BANKNIFTY), `micro`/`micro_archive`
  (sub-minute), `h2` (realized attribution), `report`, `all`.
- `micro_study.py` — **duckdb** sub-minute tier over `data/tuning/microstructure-archive/` (or a CSV):
  dedupe by (token, second), no-lookahead trailing/forward, signals `oi_velocity / microprice_drift /
  queue_imb / microprice_x_vol` → +30/60/180s option-premium net move; prints the **GATE-0 OI cadence**.
  Needs `pip install duckdb`.
- `flow_study.py` — **duckdb** signed-volume / order-flow-imbalance (OFI) early-detection tier:
  per-second `vol_delta × aggressor(ltp vs bid/ask, tick-rule fallback)` → trailing OFI (15/30/60s);
  (1) **flow→OI reconciliation** (does signed flow predict the next ~60s ΔOI sign?), (2) **edge test** —
  OFI(30s) trigger → buy ATM CE/PE, exit +30/60/120s, **net of cost**, into the shared store as
  `study=FLOW`. Run via `case_studies.py --mode flow`. Needs `pip install duckdb`.
- `flow_variants.py` — selective + regime-gated + confirmed OFI **variant sweep** (756 variants, net-of-cost)
  with a strict multiple-testing (Bonferroni) + **positive-on-every-day persistence** guardrail. Per-day
  mode `--day <date> --src <glob>` (fits a single run; per-day store), then `--report`. On 06-22..24:
  **0/756 variants positive** — best only −0.42% vs base −1.0%; nothing wired to the schedule (documented).
- `flow_patient.py` — **patient LIMIT-execution** buy-timing study (signal = bias filter; limit-buy fills
  tick-by-tick or PASS; limit-sell target + stop/timeout; costs on achieved prices). Reports fill/pass-rate
  and **adverse selection** (FILLED forward move vs ALL fired). Per-day `--day <date> --src <glob> [--idx X]`
  then `--report`. On 06-22..24: fill 68–81%, FILLED move 3–6× more negative than ALL (adverse selection),
  **0/1080 variants positive** net-of-cost — nothing wired; documented.
- `newlow_study.py` — **"buy the new intraday LOW on near-ATM options, with confirmation, expecting a ~15-pt
  retracement"** test. Per near-ATM token tracks the running intraday low (data ≤ t only); EVENT = new day-low
  (buffer sweep 0/0.25/0.5%); CONFIRMATION none/ofi_up/not_down/volpos; ENTRY at the new low; OUTCOME =
  P(+5/10/15/20 pts) within 30/60/180/300s before a −5/10/15 stop, MFE/MAE, net-of-cost target/stop/timeout.
  Per-day `--day <date> --src <glob> [--idx X]` then `--report` (Bonferroni + positive-on-all-days). On
  06-22..24: **the "+15 is easy" claim is FALSE** (P(+15)=5% NIFTY / ~30% BANKNIFTY-SENSEX with MAE ≥ MFE);
  **0/4752 variants survive** net-of-cost — falling-knife / adverse selection; nothing wired; documented.
- `h2_attrib.py` — **ground-truth** realized net P&L per strategy from H2 `TRADE_ENTITY`
  (`pip install JayDeBeApi`, h2 jar at `allpack/h2-2.3.232.jar`). On EC2 use `…;AUTO_SERVER=TRUE`
  (joins the running app); offline copy the `.mv.db` + set `GV_H2_DB`, or `--csv` fallback.
- Daily runner: `run-case-studies.sh` — schedule **~15:45 IST** (AFTER the 15:35 Parquet roll).
  cron UTC `15 10 * * 1-5`; cron IST `45 15 * * 1-5`. (The 15:30 `run-edge-study.sh` chain job is separate.)
- Full inventory + matrix + findings: `docs/CASE-STUDY-MATRIX.md`.
