# microstructure-tuning

Local analysis workspace for calibrating the **Conviction Override Engine** thresholds
(`conviction-override.*` in `application.yml`; design in `docs/CONVICTION-OVERRIDE-ENGINE.md`) from the
captured `atm-microstructure` tape. Kept in-repo so it's reusable — re-fetch fresh data and re-run any time.

## Layout
```
data/      raw tape pulled from EC2 (gitignored — large; re-fetch with scripts/fetch-data.sh)
scripts/   MicroTune.java (DuckDB analysis) + run/fetch helpers; lib/ holds the duckdb jar (gitignored)
reports/   dated markdown findings (committed)
```

## Why run locally (not on EC2)
The prod box is 2 GB RAM and runs the live bot. A full-history window sort (~12M rows) OOM/swap-thrashes
it — it wedged SSH and needed a stop/start. **Always pull the data down and run DuckDB locally.**

## Fetch data
```bash
scripts/fetch-data.sh            # scp the Parquet archive + latest atm-microstructure CSV from EC2
```
Data captured so far: NIFTY+BANKNIFTY+SENSEX 06-20..06-24, NIFTY-only 06-25..07-02, NIFTY+SENSEX 07-03.
(SENSEX capture was dropped 06-25 and re-added 07-03; SENSEX Thursday expiries are never captured.)

## Run
```bash
scripts/run.sh                   # compiles + runs MicroTune against data/, prints per-day/index tables
```
Requires Java 17+ and the DuckDB JDBC jar (auto-resolved from ~/.m2, else copy into scripts/lib/).

## What it computes
Per day & index: tick count, ΔV p90/p99 (the absorption "large flow" bar, Lee-Ready), L1 book-imbalance
p75, and a robust median-of-daily-p90 + expiry-vs-non-expiry split. Output → interpret into a dated
report in `reports/`.

## Latest result
`reports/conviction-override-threshold-tuning-2026-07-04.md` — tuned the per-index absorption bar
(NIFTY 15k / SENSEX 1.5k / BANKNIFTY 1k) and confirmed `imbalance-min 0.25`. Key finding: NIFTY 0DTE
volume spikes ~12×, so a non-expiry-calibrated bar is deliberately aggressive on expiry (the safe side).
