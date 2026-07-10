#!/usr/bin/env python3
"""
micro_study.py — sub-minute microstructure edge tier (the early-detection core). duckdb-backed.

Reads the per-second ATM microstructure (Parquet archive
  data/tuning/microstructure-archive/year=/month=/day=/index=/*.parquet
or the live CSV data/tuning/atm-microstructure-<date>.csv). Columns:
  recvEpochMs, exchangeTsEpochSec, index, strike, tradingSymbol, optionType,
  ltp, bestBid, bestAsk, bidQty, askQty, cumVolume, oi.

Canonical time = exchangeTsEpochSec (fallback recvEpochMs/1000 when 0). DEDUPE per
(tradingSymbol, second) keeping the latest recvEpochMs (kills DATA-1 inflation). NO LOOKAHEAD:
signals use trailing data; forward option-premium returns are strictly later (+30/60/180s).

Signals per token (directional, +1/-1/0):
  oi_velocity     = sign(oi_t - oi_{t-60s})   # 60s = the empirical coverage knee (see TRAIL_SEC)
  microprice_drift= sign(microprice - mid), microprice=(bid*askQty+ask*bidQty)/(askQty+bidQty)
  queue_imb       = sign(bidQty - askQty)
  microprice_x_vol= microprice_drift gated on rising cumVolume (volume velocity > 0)

Tradable leg: a premium BUYER profits only when premium rises, so the NET option expectancy is
computed for dir=+1 trades (buy this token), exit at +H sec, net of the project's charges model.
Also emits the GATE-0 OI-cadence (how often OI actually changes => the real resolution).

Feeds the SAME cumulative store as study="MICRO" so `case_studies.py --mode report` pools chain + micro
under one multiple-testing + cross-day-persistence guardrail. Requires duckdb.
"""
from __future__ import annotations

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import edge_study as E   # LOT_SIZE, IST

HORIZONS_SEC = [30, 60, 180]
# TRAIL_SEC = OI-velocity lookback. Set to 60s (was 30s) to match the empirical coverage knee AND the
# live bot's fast-OI window: band-aggregate OI blackout by trailing window = 84%@10s, 60%@30s, 29%@60s,
# 28%@180s (data/tuning/atm-microstructure-2026-06-24.csv) — the ~57s exchange OI-refresh heartbeat means
# a 30s velocity reads flat >half the time. 60s = same signal OIMomentumStrategy now trades on. gate0_cadence
# below independently reports the measured avg OI gap for cross-check. (NOTE: cumulative-store rows for
# oi_velocity computed before this change used 30s; reset the MICRO store if you need a clean 60s series.)
TRAIL_SEC = 60
ENTRY_FLOOR = 20.0       # tradeable premium floor (mirror case_studies)
NET_PCT_CAP = 200.0      # winsorize per-trade net%
ARCHIVE_GLOB = "data/tuning/microstructure-archive/year=*/month=*/day=*/index=*/*.parquet"

SIGNALS = {
    "oi_velocity":      "CASE WHEN oi - oi_p > 0 THEN 1 WHEN oi - oi_p < 0 THEN -1 ELSE 0 END",
    "microprice_drift": "CASE WHEN micro - mid > 0 THEN 1 WHEN micro - mid < 0 THEN -1 ELSE 0 END",
    "queue_imb":        "CASE WHEN bq - aq > 0 THEN 1 WHEN bq - aq < 0 THEN -1 ELSE 0 END",
    "microprice_x_vol": "CASE WHEN vol - vol_p > 0 AND micro - mid > 0 THEN 1 "
                        "WHEN vol - vol_p > 0 AND micro - mid < 0 THEN -1 ELSE 0 END",
}

# round-trip charges in SQL (mirror ZerodhaChargesCalculator), b=entry ltp, s=exit ltp, q=lot
def _charge_sql(b, s, q):
    return ("(40 + {s}*{q}*0.0015 + ({b}+{s})*{q}*0.0003553 + ({b}+{s})*{q}*1e-6 + {b}*{q}*0.00003 "
            "+ 0.18*(40 + ({b}+{s})*{q}*1e-6 + ({b}+{s})*{q}*0.0003553))").format(b=b, s=s, q=q)


def _agg_exprs():
    """Build conditional-aggregate SQL for every signal x horizon -> sufficient statistics columns."""
    cols = []
    for sname, dexpr in SIGNALS.items():
        cols.append("(%s) AS dir_%s" % (dexpr, sname))
    sel = []
    for sname in SIGNALS:
        d = "dir_%s" % sname
        for h in HORIZONS_SEC:
            s = "s%d" % h
            gross = "({d}*({s}-ltp)/ltp*100.0)".format(d=d, s=s)   # signed gross %
            q = "q"
            net_rs = "(({s}-ltp)*{q} - {ch})".format(s=s, q=q, ch=_charge_sql("ltp", s, q))
            net_pct = "GREATEST(-%g, LEAST(%g, %s/(ltp*%s)*100.0))" % (NET_PCT_CAP, NET_PCT_CAP, net_rs, q)
            cond = "({s} IS NOT NULL AND ltp > 0 AND {d} <> 0)".format(s=s, d=d)
            condbuy = "({s} IS NOT NULL AND ltp >= %g AND {d} = 1)".format(s=s, d=d) % ENTRY_FLOOR
            pfx = "%s__%d" % (sname, h)
            sel += [
                "SUM(CASE WHEN %s THEN 1 ELSE 0 END) AS %s__n" % (cond, pfx),
                "SUM(CASE WHEN %s AND %s > 0 THEN 1 ELSE 0 END) AS %s__hits" % (cond, gross, pfx),
                "SUM(CASE WHEN %s THEN %s ELSE 0 END) AS %s__sret" % (cond, gross, pfx),
                "SUM(CASE WHEN %s THEN (%s)*(%s) ELSE 0 END) AS %s__sret2" % (cond, gross, gross, pfx),
                "SUM(CASE WHEN %s THEN 1 ELSE 0 END) AS %s__no" % (condbuy, pfx),
                "SUM(CASE WHEN %s AND %s > 0 THEN 1 ELSE 0 END) AS %s__owins" % (condbuy, net_pct, pfx),
                "SUM(CASE WHEN %s THEN %s ELSE 0 END) AS %s__snet" % (condbuy, net_pct, pfx),
                "SUM(CASE WHEN %s THEN (%s)*(%s) ELSE 0 END) AS %s__snet2" % (condbuy, net_pct, net_pct, pfx),
                "SUM(CASE WHEN %s THEN %s ELSE 0 END) AS %s__snetrs" % (condbuy, net_rs, pfx),
            ]
    return cols, sel


def _partition_sql(source_expr):
    feat, sel = _agg_exprs()
    return """
    WITH raw AS (
        SELECT index AS idx, tradingSymbol AS tok,
               CASE WHEN exchangeTsEpochSec > 0 THEN exchangeTsEpochSec ELSE CAST(recvEpochMs/1000 AS BIGINT) END AS ts,
               ltp, bestBid AS bid, bestAsk AS ask, bidQty AS bq, askQty AS aq, cumVolume AS vol, oi, recvEpochMs
        FROM {src}
        WHERE ltp > 0
    ), dedup AS (   -- one row per (token, second): keep latest recvEpochMs (DATA-1 guard)
        SELECT * EXCLUDE rn FROM (
            SELECT *, row_number() OVER (PARTITION BY tok, ts ORDER BY recvEpochMs DESC) rn FROM raw
        ) WHERE rn = 1
    ), s AS (
        SELECT idx, tok, ts, ltp, bid, ask, bq, aq, vol, oi,
               CASE WHEN (bq+aq) > 0 THEN (bid*aq + ask*bq)/(bq+aq) ELSE (bid+ask)/2.0 END AS micro,
               (bid+ask)/2.0 AS mid
        FROM dedup
    ), w AS (
        SELECT *,
            lag(oi, {tr})  OVER win AS oi_p,
            lag(vol, {tr}) OVER win AS vol_p,
            lead(ltp, 30)  OVER win AS s30,
            lead(ltp, 60)  OVER win AS s60,
            lead(ltp, 180) OVER win AS s180
        FROM s WINDOW win AS (PARTITION BY tok ORDER BY ts)
    ), f AS (
        SELECT idx, ltp, s30, s60, s180, bq, aq, vol, vol_p, oi, oi_p, micro, mid,
               {q} AS q, {feat}
        FROM w WHERE oi_p IS NOT NULL
    )
    SELECT idx, {sel} FROM f GROUP BY idx
    """.format(src=source_expr, tr=TRAIL_SEC, q="CAST(NULL AS INTEGER)", feat=",\n               ".join(feat),
               sel=",\n           ".join(sel))


def _lot_case():
    parts = " ".join("WHEN '%s' THEN %d" % (k, v) for k, v in E.LOT_SIZE.items())
    return "CASE idx %s ELSE 50 END" % parts


def gate0_cadence(duckdb, source_expr):
    sql = """
    WITH raw AS (
      SELECT index idx, tradingSymbol tok,
             CASE WHEN exchangeTsEpochSec>0 THEN exchangeTsEpochSec ELSE CAST(recvEpochMs/1000 AS BIGINT) END ts,
             oi, recvEpochMs FROM {src}),
    d AS (SELECT * EXCLUDE rn FROM (SELECT *, row_number() OVER (PARTITION BY tok,ts ORDER BY recvEpochMs DESC) rn FROM raw) WHERE rn=1),
    w AS (SELECT idx, tok, ts, oi, lag(oi) OVER (PARTITION BY tok ORDER BY ts) oi_p FROM d)
    SELECT idx, count(*) secs, SUM(CASE WHEN oi<>oi_p THEN 1 ELSE 0 END) changes
    FROM w WHERE oi_p IS NOT NULL GROUP BY idx ORDER BY idx
    """.format(src=source_expr)
    out = []
    for r in duckdb.sql(sql).fetchall():
        idx, secs, changes = r[0], r[1], r[2] or 0
        rate = changes / secs if secs else 0
        avg_gap = secs / changes if changes else float("inf")
        out.append((idx, secs, changes, rate, avg_gap))
    return out


def run(source_glob, date, cumulative, append_fn):
    import duckdb
    # NULL lot placeholder replaced by the real per-index lot in the wrapping query
    src = "read_parquet('%s')" % source_glob if source_glob.endswith(".parquet") or "*" in source_glob \
          else "read_csv_auto('%s')" % source_glob
    # GATE-0 cadence
    cad = gate0_cadence(duckdb, src)
    for idx, secs, changes, rate, gap in cad:
        print("[micro][GATE-0] %-9s %s: %d token-seconds, %d OI changes -> change-rate %.1f%%, "
              "~1 OI move / %.1f s" % (date, idx, secs, changes, rate * 100, gap))
    # main aggregation (inject real lot via replace of the NULL placeholder)
    sql = _partition_sql(src).replace("CAST(NULL AS INTEGER) AS q", "%s AS q" % _lot_case())
    rows = duckdb.sql(sql).df().to_dict("records")
    acc_by_ul = {}
    for rec in rows:
        idx = rec["idx"]
        acc = acc_by_ul.setdefault("MICRO", {})
        for sname in SIGNALS:
            for h in HORIZONS_SEC:
                p = "%s__%d" % (sname, h)
                a = E.new_acc()
                a["n"] = int(rec[p + "__n"] or 0)
                a["hits"] = int(rec[p + "__hits"] or 0)
                a["sret"] = float(rec[p + "__sret"] or 0)
                a["sret2"] = float(rec[p + "__sret2"] or 0)
                a["no"] = int(rec[p + "__no"] or 0)
                a["owins"] = int(rec[p + "__owins"] or 0)
                a["snet"] = float(rec[p + "__snet"] or 0)
                a["snet2"] = float(rec[p + "__snet2"] or 0)
                a["snetrs"] = float(rec[p + "__snetrs"] or 0)
                if a["n"] > 0:
                    acc[("%s@%s" % (sname, idx), h, "all", "all")] = a
    if acc_by_ul.get("MICRO"):
        append_fn("MICRO", date, acc_by_ul, cumulative)
        print("[micro] %s: wrote %d signal cells to cumulative." % (date, len(acc_by_ul["MICRO"])))
    return cad
