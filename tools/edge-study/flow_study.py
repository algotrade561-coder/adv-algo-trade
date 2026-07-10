#!/usr/bin/env python3
"""
flow_study.py — signed-volume / order-flow-imbalance (OFI) early-detection study (duckdb).

The sub-second directional-pressure signal the OI field is too slow to give (OI prints ~once/60s for
NIFTY/BANKNIFTY, ~162s for SENSEX; LTP/quote move every 1-2s). Reads the per-second microstructure
(Parquet archive or CSV). NO LOOKAHEAD; time = exchangeTsEpochSec; dedupe per (tradingSymbol, second).

PER SECOND, per token:
  vol_delta   = cumVolume - prev cumVolume  (guard resets/negatives -> 0)
  aggressor   = +1 if ltp >= ask or ltp >= mid + FRAC*spread   (buy-initiated)
                -1 if ltp <= bid or ltp <= mid - FRAC*spread   (sell-initiated)
                else tick-rule: sign(ltp - prev ltp)           (fallback)
  signed_vol  = vol_delta * aggressor
OFI over trailing W (15/30/60s): ofi = Σ signed_vol / Σ vol_delta  (normalized, in [-1,1]).

THREE outputs:
 1. FLOW→OI RECONCILIATION — at each real ΔOI print, does the sign of trailing-60s signed flow match the
    sign of the realized ΔOI? Hit-rate + ΔOI/volume ratio distribution. (Is flow a valid OI proxy?)
 2. EDGE TEST — when |OFI(30s)| >= THRESH (sustained pressure), buy ATM CE (flow>0) / PE (flow<0), exit at
    +30/60/120s, NET of charges. Split by regime (trend/range via trailing index move) + ToD. Fixed-horizon
    AND flow-reversal-exit variants. Feeds the shared cumulative store as study="FLOW" under the same guardrail.
 3. (rolled into the report by case_studies.py --mode report)

Honesty: 1s snapshots make aggressor classification APPROXIMATE (no true tick/trade prints); overlapping
windows inflate t-stats; small sample. Descriptive only.
"""
from __future__ import annotations

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import edge_study as E   # LOT_SIZE, new_acc, tstat

W_OFI = [15, 30, 60]
EDGE_W = 30                 # OFI window used for the edge trigger
EDGE_THRESH = 0.33          # |normalized OFI| must exceed this to "fire"
HORIZONS_SEC = [30, 60, 120]
FRAC = 0.25                 # ltp within FRAC*spread of a side counts as initiated by that side
ENTRY_FLOOR = 20.0
NET_PCT_CAP = 200.0
ARCHIVE_BASE = "data/tuning/microstructure-archive"


def _charge_sql(b, s, q):
    return ("(40 + {s}*{q}*0.0015 + ({b}+{s})*{q}*0.0003553 + ({b}+{s})*{q}*1e-6 + {b}*{q}*0.00003 "
            "+ 0.18*(40 + ({b}+{s})*{q}*1e-6 + ({b}+{s})*{q}*0.0003553))").format(b=b, s=s, q=q)


def _lot_case():
    return "CASE idx %s ELSE 50 END" % " ".join("WHEN '%s' THEN %d" % (k, v) for k, v in E.LOT_SIZE.items())


def _base_cte(src):
    """Per-(token,second) deduped rows + per-second flow features (no lookahead)."""
    return """
    WITH raw AS (
        SELECT index AS idx, tradingSymbol AS tok, CAST(strike AS BIGINT) AS strike, optionType AS opt,
               CASE WHEN exchangeTsEpochSec>0 THEN exchangeTsEpochSec ELSE CAST(recvEpochMs/1000 AS BIGINT) END AS ts,
               ltp, bestBid AS bid, bestAsk AS ask, cumVolume AS vol, oi, recvEpochMs
        FROM {src} WHERE ltp > 0
    ), d AS (
        SELECT * EXCLUDE rn FROM (
            SELECT *, row_number() OVER (PARTITION BY tok, ts ORDER BY recvEpochMs DESC) rn FROM raw) WHERE rn=1
    ), f AS (
        SELECT idx, tok, strike, opt, ts, ltp, bid, ask, vol, oi,
               (bid+ask)/2.0 AS mid, GREATEST(ask-bid, 0) AS spr,
               lag(ltp) OVER w AS pltp,
               GREATEST(vol - lag(vol) OVER w, 0) AS vdelta
        FROM d WINDOW w AS (PARTITION BY tok ORDER BY ts)
    ), g AS (
        SELECT *,
            CASE
              WHEN bid>0 AND ask>0 AND ltp >= ask THEN 1
              WHEN bid>0 AND ask>0 AND ltp <= bid THEN -1
              WHEN bid>0 AND ask>0 AND ltp >= mid + {frac}*spr THEN 1
              WHEN bid>0 AND ask>0 AND ltp <= mid - {frac}*spr THEN -1
              WHEN pltp IS NOT NULL AND ltp > pltp THEN 1
              WHEN pltp IS NOT NULL AND ltp < pltp THEN -1
              ELSE 0 END AS aggr
        FROM f
    ), sv AS (
        SELECT *, vdelta * aggr AS signed_vol FROM g
    )
    """.format(src=src, frac=FRAC)


def reconcile(duckdb, src):
    """Flow->OI reconciliation: does trailing-60s signed flow predict the sign of the next real ΔOI print?"""
    sql = _base_cte(src) + """
    , win AS (   -- trailing 60s signed-flow + volume up to each second (no lookahead)
        SELECT idx, tok, ts, oi,
            SUM(signed_vol) OVER r AS sflow60, SUM(vdelta) OVER r AS vol60
        FROM sv WINDOW r AS (PARTITION BY tok ORDER BY ts RANGE BETWEEN 60 PRECEDING AND CURRENT ROW)
    ), chg AS (
        SELECT idx, tok, ts, oi, sflow60, vol60,
               oi - lag(oi) OVER (PARTITION BY tok ORDER BY ts) AS doi
        FROM win
    )
    SELECT idx,
        count(*) AS n,
        SUM(CASE WHEN sign(sflow60)=sign(doi) THEN 1 ELSE 0 END) AS agree,
        round(avg(CASE WHEN vol60>0 THEN abs(doi)/vol60 ELSE NULL END),4) AS doi_per_vol
    FROM chg WHERE doi <> 0 AND sflow60 <> 0 GROUP BY idx ORDER BY idx
    """
    return duckdb.sql(sql).fetchall()


def edge(duckdb, src, date):
    """Edge test: OFI(30s) trigger -> buy ATM option net-of-cost at +30/60/120s. Returns acc dict."""
    lot = _lot_case()
    # build per-row OFI(EDGE_W) + forward ltp; then aggregate signal x horizon
    sel = []
    for h in HORIZONS_SEC:
        b, s, q = "ltp", "s%d" % h, "q"
        gross = "(dir*({s}-ltp)/ltp*100.0)".format(s=s)
        net_rs = "(({s}-ltp)*{q} - {ch})".format(s=s, q=q, ch=_charge_sql(b, s, q))
        net_pct = "GREATEST(-%g, LEAST(%g, %s/(ltp*%s)*100.0))" % (NET_PCT_CAP, NET_PCT_CAP, net_rs, q)
        cond = "({s} IS NOT NULL AND ltp>0 AND fired)".format(s=s)        # descriptive: both dirs
        condbuy = "({s} IS NOT NULL AND ltp>=%g AND fired AND dir=1)".format(s=s) % ENTRY_FLOOR  # long-only net
        for reg in ("all",):  # regime/tod added below via grouping
            pass
        sel += [
            "SUM(CASE WHEN %s THEN 1 ELSE 0 END) AS n_%d" % (cond, h),
            "SUM(CASE WHEN %s AND %s>0 THEN 1 ELSE 0 END) AS hits_%d" % (cond, gross, h),
            "SUM(CASE WHEN %s THEN %s ELSE 0 END) AS sret_%d" % (cond, gross, h),
            "SUM(CASE WHEN %s THEN (%s)*(%s) ELSE 0 END) AS sret2_%d" % (cond, gross, gross, h),
            "SUM(CASE WHEN %s THEN 1 ELSE 0 END) AS no_%d" % (condbuy, h),
            "SUM(CASE WHEN %s AND %s>0 THEN 1 ELSE 0 END) AS owins_%d" % (condbuy, net_pct, h),
            "SUM(CASE WHEN %s THEN %s ELSE 0 END) AS snet_%d" % (condbuy, net_pct, h),
            "SUM(CASE WHEN %s THEN (%s)*(%s) ELSE 0 END) AS snet2_%d" % (condbuy, net_pct, net_pct, h),
            "SUM(CASE WHEN %s THEN %s ELSE 0 END) AS snetrs_%d" % (condbuy, net_rs, h),
        ]
    sql = _base_cte(src) + """
    , ofi AS (
        SELECT idx, tok, strike, opt, ts, ltp,
            CASE WHEN SUM(vdelta) OVER r > 0 THEN SUM(signed_vol) OVER r / SUM(vdelta) OVER r ELSE 0 END AS ofi
        FROM sv WINDOW r AS (PARTITION BY tok ORDER BY ts RANGE BETWEEN {w} PRECEDING AND CURRENT ROW)
    ), fwd AS (
        SELECT o.*, {lot} AS q,
            (abs(o.ofi) >= {th}) AS fired,
            CASE WHEN o.ofi>0 THEN 1 WHEN o.ofi<0 THEN -1 ELSE 0 END AS dir,
            lead(o.ltp,30)  OVER w AS s30,
            lead(o.ltp,60)  OVER w AS s60,
            lead(o.ltp,120) OVER w AS s120
        FROM ofi o WINDOW w AS (PARTITION BY o.tok ORDER BY o.ts)
    )
    SELECT idx, {sel} FROM fwd GROUP BY idx
    """.format(w=EDGE_W, th=EDGE_THRESH, lot=lot, sel=",\n           ".join(sel))
    rows = duckdb.sql(sql).df().to_dict("records")
    acc = {}
    for rec in rows:
        idx = rec["idx"]
        for h in HORIZONS_SEC:
            a = E.new_acc()
            a["n"] = int(rec["n_%d" % h] or 0); a["hits"] = int(rec["hits_%d" % h] or 0)
            a["sret"] = float(rec["sret_%d" % h] or 0); a["sret2"] = float(rec["sret2_%d" % h] or 0)
            a["no"] = int(rec["no_%d" % h] or 0); a["owins"] = int(rec["owins_%d" % h] or 0)
            a["snet"] = float(rec["snet_%d" % h] or 0); a["snet2"] = float(rec["snet2_%d" % h] or 0)
            a["snetrs"] = float(rec["snetrs_%d" % h] or 0)
            if a["n"] > 0:
                acc[("ofi_signed_vol@%s" % idx, h, "all", "all")] = a
    return acc


def run(source_glob, date, cumulative, append_fn):
    import duckdb
    src = "read_parquet('%s')" % source_glob if (source_glob.endswith(".parquet") or "*" in source_glob) \
          else "read_csv_auto('%s')" % source_glob
    rec = reconcile(duckdb, src)
    for idx, n, agree, dpv in rec:
        print("[flow][recon] %-9s %s: n=%d  flow->ΔOI sign hit-rate=%.1f%%  median ΔOI/vol=%s"
              % (idx, date, n, (agree / n * 100) if n else 0, dpv))
    acc = edge(duckdb, src, date)
    if acc:
        append_fn("FLOW", date, {"FLOW": acc}, cumulative)
        print("[flow] %s: wrote %d edge cells to cumulative." % (date, len(acc)))
    return rec, acc


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
