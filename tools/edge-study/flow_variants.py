#!/usr/bin/env python3
"""
flow_variants.py — SELECTIVE + REGIME-GATED + CONFIRMED order-flow-imbalance (OFI) buy-timing sweep.

Question: does a stricter / regime-gated / confirmed OFI variant flip the net-of-cost option-buy
expectancy positive on the captured microstructure (06-22..24)? duckdb; no-lookahead; exchangeTs time;
per-(token,second) dedup; net of the project's charges model.

Dimensions swept (per index, per day, then pooled + per-day persistence):
  SELECTIVITY   : abs-OFI threshold in {0.33(base), 0.50, 0.70, 0.85} and percentile {top5%, top2%, top1%}
  REGIME        : all / trend / range   (intraday: trailing-60s |index/ATM move| vs a small band)
  CONFIRMATION  : none / vol_burst (vol_delta in token's top quartile) / drift (microprice drift agrees) /
                  vol+drift (both)
  EXIT          : fixed +30/60/120s  (flow-reversal & trailing-stop variants noted; fixed is the headline)

HONESTY: this is a large sweep on only 3 days → it WILL throw up spurious positives. We therefore:
  * count total variants tested, apply Bonferroni (alpha/M) to any positive,
  * require POSITIVE NET ON EACH OF THE 3 DAYS (per-day persistence), not just pooled,
  * label any survivor "candidate, likely overfit on 3 days — validate forward OOS", never "edge".
Descriptive t-stats (overlapping windows). Standalone; no trading-app code.
"""
from __future__ import annotations

import math
import os
import sys
from collections import defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import edge_study as E  # LOT_SIZE, tstat

ARCHIVE_BASE = "data/tuning/microstructure-archive"
OUT = "reports/case-studies/flow_variants.csv"
ENTRY_FLOOR = 20.0
NET_PCT_CAP = 200.0
FRAC = 0.25
EDGE_W = 30
HORIZONS = [30, 60, 120]
ABS_THRESH = [0.33, 0.50, 0.70, 0.85]
PCTL = {"top5": 0.95, "top2": 0.98, "top1": 0.99}
REGIMES = ["all", "trend", "range"]
CONFS = ["none", "volburst", "drift", "vol+drift"]
TREND_BAND = 0.05   # % trailing-60s underlying move splitting trend vs range (per-token premium proxy)


def _charge_sql(b, s, q):
    return ("(40 + {s}*{q}*0.0015 + ({b}+{s})*{q}*0.0003553 + ({b}+{s})*{q}*1e-6 + {b}*{q}*0.00003 "
            "+ 0.18*(40 + ({b}+{s})*{q}*1e-6 + ({b}+{s})*{q}*0.0003553))").format(b=b, s=s, q=q)


def _lot_case():
    return "CASE idx %s ELSE 50 END" % " ".join("WHEN '%s' THEN %d" % (k, v) for k, v in E.LOT_SIZE.items())


def _events_sql(src):
    """One row per (token,second) with OFI, confirmations, regime, forward premiums, day-percentile of |OFI|."""
    return """
    WITH raw AS (
      SELECT index idx, tradingSymbol tok, CAST(strike AS BIGINT) strike,
        CASE WHEN exchangeTsEpochSec>0 THEN exchangeTsEpochSec ELSE CAST(recvEpochMs/1000 AS BIGINT) END ts,
        ltp, bestBid bid, bestAsk ask, cumVolume vol, oi, recvEpochMs
      FROM {src} WHERE ltp>0),
    d AS (SELECT * EXCLUDE rn FROM (SELECT *, row_number() OVER (PARTITION BY tok,ts ORDER BY recvEpochMs DESC) rn FROM raw) WHERE rn=1),
    f AS (
      SELECT idx,tok,strike,ts,ltp,bid,ask,vol,oi,(bid+ask)/2.0 mid, GREATEST(ask-bid,0) spr,
        lag(ltp) OVER w pltp, GREATEST(vol-lag(vol) OVER w,0) vdelta
      FROM d WINDOW w AS (PARTITION BY tok ORDER BY ts)),
    g AS (
      SELECT *,
        CASE WHEN bid>0 AND ask>0 AND ltp>=ask THEN 1 WHEN bid>0 AND ask>0 AND ltp<=bid THEN -1
             WHEN bid>0 AND ask>0 AND ltp>=mid+{frac}*spr THEN 1 WHEN bid>0 AND ask>0 AND ltp<=mid-{frac}*spr THEN -1
             WHEN pltp IS NOT NULL AND ltp>pltp THEN 1 WHEN pltp IS NOT NULL AND ltp<pltp THEN -1 ELSE 0 END aggr
      FROM f),
    sv AS (SELECT *, vdelta*aggr signed_vol FROM g),
    ofi AS (
      SELECT idx,tok,strike,ts,ltp,vdelta,mid,
        CASE WHEN SUM(vdelta) OVER r>0 THEN SUM(signed_vol) OVER r/SUM(vdelta) OVER r ELSE 0 END ofi,
        SUM(vdelta) OVER r vol_w,
        (ltp - (first_value(ltp) OVER r)) / NULLIF(first_value(ltp) OVER r,0) * 100.0 prem_move_w
      FROM sv WINDOW r AS (PARTITION BY tok ORDER BY ts RANGE BETWEEN {w} PRECEDING AND CURRENT ROW)),
    e AS (
      SELECT o.idx,o.tok,o.ts,o.ltp,o.ofi,o.vol_w,o.prem_move_w, {lot} q,
        CASE WHEN o.ofi>0 THEN 1 WHEN o.ofi<0 THEN -1 ELSE 0 END dir,
        lead(o.ltp,30) OVER w s30, lead(o.ltp,60) OVER w s60, lead(o.ltp,120) OVER w s120
      FROM ofi o WINDOW w AS (PARTITION BY o.tok ORDER BY o.ts)),
    pct AS (  -- per (idx,day) |OFI| percentile thresholds
      SELECT idx,
        quantile_cont(abs(ofi),0.95) p95, quantile_cont(abs(ofi),0.98) p98, quantile_cont(abs(ofi),0.99) p99
      FROM e GROUP BY idx)
    SELECT e.*, p.p95, p.p98, p.p99,
      -- regime proxy: |trailing-60s premium move| big => trend, small => range
      CASE WHEN abs(e.prem_move_w) >= {band} THEN 'trend' ELSE 'range' END regime,
      -- volburst: vol in trailing window above the token's median trailing volume (approx via vol_w>0 and large)
      (e.vol_w > 0) AS has_vol,
      -- drift confirmation: short-horizon premium drift agrees with OFI sign
      CASE WHEN (e.prem_move_w>0 AND e.ofi>0) OR (e.prem_move_w<0 AND e.ofi<0) THEN 1 ELSE 0 END drift_agree
    FROM e JOIN pct p USING(idx)
    """.format(src=src, frac=FRAC, w=EDGE_W, lot=_lot_case(), band=TREND_BAND)


def _fire_pred(sel, regime, conf):
    """SQL boolean for 'fires under this variant'."""
    if sel in PCTL:
        col = {"top5": "p95", "top2": "p98", "top1": "p99"}[sel]
        base = "abs(ofi) >= %s AND dir<>0" % col
    else:
        base = "abs(ofi) >= %s AND dir<>0" % sel
    if regime != "all":
        base += " AND regime='%s'" % regime
    if conf == "volburst":
        base += " AND vol_w > 0"   # conservative volume-present gate (1s data lacks true burst granularity)
    elif conf == "drift":
        base += " AND drift_agree=1"
    elif conf == "vol+drift":
        base += " AND vol_w > 0 AND drift_agree=1"
    return base


def run_day(duckdb, src, date):
    """Return dict variant_key -> per-index per-horizon stats for one day."""
    duckdb.sql("CREATE OR REPLACE TEMP TABLE ev AS " + _events_sql(src))  # materialize once -> fast re-scans
    sels = [str(t) for t in ABS_THRESH] + list(PCTL.keys())
    results = {}
    # build one aggregation query per (sel,regime,conf) covering all idx,horizon via conditional sums
    for sel in sels:
        for regime in REGIMES:
            for conf in CONFS:
                pred = _fire_pred(sel, regime, conf)
                cols = []
                for h in HORIZONS:
                    s = "s%d" % h
                    net_rs = "(({s}-ltp)*q - {ch})".format(s=s, ch=_charge_sql("ltp", s, "q"))
                    net_pct = "GREATEST(-%g,LEAST(%g,%s/(ltp*q)*100.0))" % (NET_PCT_CAP, NET_PCT_CAP, net_rs)
                    condbuy = "(%s AND dir=1 AND %s IS NOT NULL AND ltp>=%g)" % (pred, s, ENTRY_FLOOR)
                    cols += [
                        "SUM(CASE WHEN %s THEN 1 ELSE 0 END) n%d" % (condbuy, h),
                        "SUM(CASE WHEN %s AND %s>0 THEN 1 ELSE 0 END) w%d" % (condbuy, net_pct, h),
                        "SUM(CASE WHEN %s THEN %s ELSE 0 END) sn%d" % (condbuy, net_pct, h),
                        "SUM(CASE WHEN %s THEN (%s)*(%s) ELSE 0 END) sn2%d" % (condbuy, net_pct, net_pct, h),
                    ]
                q = "SELECT idx, %s FROM ev GROUP BY idx" % ", ".join(cols)
                for rec in duckdb.sql(q).df().to_dict("records"):
                    idx = rec["idx"]
                    for h in HORIZONS:
                        n = int(rec["n%d" % h] or 0)
                        if n == 0:
                            continue
                        key = (idx, sel, regime, conf, h)
                        results[key] = {"n": n, "w": int(rec["w%d" % h] or 0),
                                        "sn": float(rec["sn%d" % h] or 0), "sn2": float(rec["sn2%d" % h] or 0)}
    return results


def archive_partitions(base=ARCHIVE_BASE):
    import glob
    out = []
    for d in sorted(glob.glob(os.path.join(base, "year=*", "month=*", "day=*"))):
        parts = {kv.split("=")[0]: kv.split("=")[1] for kv in d.split(os.sep) if "=" in kv}
        if {"year", "month", "day"} <= parts.keys():
            date = "%s-%s-%s" % (parts["year"], parts["month"].zfill(2), parts["day"].zfill(2))
            g = os.path.join(d, "index=*", "*.parquet")
            if glob.glob(g):
                out.append((date, g))
    return out


DAYS_CSV = "reports/case-studies/flow_variant_days.csv"
DAY_FIELDS = ["date", "idx", "sel", "regime", "conf", "h", "n", "w", "sn", "sn2"]


def _append_day(date, results):
    import csv as _csv
    os.makedirs(os.path.dirname(DAYS_CSV), exist_ok=True)
    existing = []
    if os.path.exists(DAYS_CSV):
        with open(DAYS_CSV, newline="") as fh:
            existing = [r for r in _csv.DictReader(fh) if r.get("date") != date]
    with open(DAYS_CSV, "w", newline="") as fh:
        w = _csv.DictWriter(fh, fieldnames=DAY_FIELDS); w.writeheader()
        for r in existing:
            w.writerow({k: r.get(k, "") for k in DAY_FIELDS})
        for (idx, sel, reg, conf, h), v in results.items():
            w.writerow({"date": date, "idx": idx, "sel": sel, "regime": reg, "conf": conf, "h": h,
                        "n": v["n"], "w": v["w"], "sn": v["sn"], "sn2": v["sn2"]})


def run_one_day(date, src_glob):
    import duckdb
    src = "read_parquet('%s')" % src_glob if (src_glob.endswith(".parquet") or "*" in src_glob)           else "read_csv_auto('%s')" % src_glob
    res = run_day(duckdb, src, date)
    _append_day(date, res)
    print("[flow-var] %s: %d variant cells appended to %s" % (date, len(res), DAYS_CSV))


def report():
    import csv as _csv
    if not os.path.exists(DAYS_CSV):
        print("[flow-var] no per-day store %s" % DAYS_CSV); return
    perday = defaultdict(dict); pooled = defaultdict(lambda: {"n": 0, "w": 0, "sn": 0.0, "sn2": 0.0}); days = set()
    with open(DAYS_CSV, newline="") as fh:
        for r in _csv.DictReader(fh):
            days.add(r["date"])
            k = (r["idx"], r["sel"], r["regime"], r["conf"], int(r["h"]))
            v = {"n": int(float(r["n"])), "w": int(float(r["w"])), "sn": float(r["sn"]), "sn2": float(r["sn2"])}
            perday[k][r["date"]] = v
            for kk in ("n", "w", "sn", "sn2"):
                pooled[k][kk] += v[kk]
    ndays = len(days); M = len(pooled); alpha = 0.05; bonf = alpha / M if M else alpha
    rows = []; pooled_pos = 0; survivors = []
    for k, p in pooled.items():
        idx, sel, reg, conf, h = k; n = p["n"]; mean = p["sn"] / n if n else 0
        t = E.tstat(n, p["sn"], p["sn2"]); dd = perday[k]
        pos_days = sum(1 for v in dd.values() if v["n"] > 0 and v["sn"] / v["n"] > 0)
        persistent = (len(dd) == ndays and pos_days == ndays)
        bonf_sig = (not math.isnan(t)) and abs(t) >= 3.5
        cand = (mean > 0 and persistent and bonf_sig)
        if mean > 0: pooled_pos += 1
        if cand: survivors.append((k, mean, t, n))
        rows.append({"idx": idx, "sel": sel, "regime": reg, "conf": conf, "h": h, "n": n,
                     "winrate": round(p["w"]/n, 3) if n else 0, "net_pct": round(mean, 4),
                     "t": round(t, 2) if not math.isnan(t) else "", "pos_days": pos_days, "seen_days": len(dd),
                     "pooled_pos": "Y" if mean > 0 else "", "bonf_sig": "Y" if bonf_sig else "",
                     "persistent": "Y" if persistent else "", "candidate_overfit": "Y" if cand else ""})
    cols = ["idx", "sel", "regime", "conf", "h", "n", "winrate", "net_pct", "t", "pos_days", "seen_days",
            "pooled_pos", "bonf_sig", "persistent", "candidate_overfit"]
    with open(OUT, "w", newline="") as fh:
        w = _csv.DictWriter(fh, fieldnames=cols); w.writeheader()
        for r in sorted(rows, key=lambda r: r["net_pct"], reverse=True): w.writerow(r)
    print("\n==== FLOW VARIANT SWEEP (net-of-cost, %d days) ====" % ndays)
    print("variants tested M=%d  Bonferroni alpha=%.6f (|z|>=~3.5)  expected chance false-positives ~%.1f" % (M, bonf, M*0.05))
    print("pooled-positive: %d/%d   survive Bonf + positive-on-ALL-%d-days: %d" % (pooled_pos, M, ndays, len(survivors)))
    base = pooled.get(("NIFTY", "0.33", "all", "none", 30))
    if base: print("base (NIFTY OFI>=0.33,all,none,30s): net=%.3f%% n=%d" % (base["sn"]/base["n"], base["n"]))
    top = sorted(rows, key=lambda r: r["net_pct"], reverse=True)[:12]
    print("\nTop 12 by pooled net%% (ranked by FIT => survivorship; read flags):")
    print("%-9s %-5s %-6s %-9s %4s %8s %7s %6s %6s %5s %5s %4s" % ("idx","sel","regime","conf","h","n","net%","win%","t","+dys","bonf","cand"))
    for r in top:
        print("%-9s %-5s %-6s %-9s %4d %8d %7.3f %6.1f %6s %2d/%d %5s %4s" % (
            r["idx"], r["sel"], r["regime"], r["conf"], r["h"], r["n"], r["net_pct"], r["winrate"]*100,
            r["t"], r["pos_days"], r["seen_days"], r["bonf_sig"], r["candidate_overfit"]))
    print("\n-> %s" % OUT)
    if survivors:
        print("\n*** %d variant(s) passed Bonferroni + positive-on-all-%d-days. CANDIDATE, LIKELY OVERFIT on %d days"
              " -- must be validated FORWARD out-of-sample. NOT a found edge. ***" % (len(survivors), ndays, ndays))
    else:
        print("\nNothing survives correction + per-day persistence -> no defensible variant; keep base flow scheduled, document only.")


def main():
    import argparse
    ap = argparse.ArgumentParser()
    ap.add_argument("--day"); ap.add_argument("--src"); ap.add_argument("--report", action="store_true")
    a = ap.parse_args()
    if a.report:
        report()
    elif a.day and a.src:
        run_one_day(a.day, a.src)
    else:
        for date, g in archive_partitions():
            run_one_day(date, g)
        csv24 = "data/tuning/atm-microstructure-2026-06-24.csv"
        if os.path.exists(csv24):
            run_one_day("2026-06-24", csv24)
        report()


if __name__ == "__main__":
    main()
