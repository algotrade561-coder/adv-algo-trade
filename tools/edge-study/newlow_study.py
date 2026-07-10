#!/usr/bin/env python3
"""
newlow_study.py — "buy the new intraday LOW on near-ATM options, with confirmation, expecting a ~15-pt
retracement." duckdb; no-lookahead; exchangeTs time; per-(token,second) dedup.

Universe: near-ATM tokens (ATM ±2 strikes), CE+PE, per index (ATM proxy = the most-traded strike that day).
EVENT: a second where ltp prints a NEW running intraday low (ltp < prior running min by a buffer sweep
       {0, 0.25%, 0.5%}). Running min uses only data <= t (no lookahead).
CONFIRMATION (configurable; tested separately): none (falling-knife baseline) / ofi_up (OFI(30s)>=0) /
       not_downtrend (option 30s slope >= 0, i.e. premium not still dropping) / volume present.
ENTRY: buy at the new-low price (price actually traded there).
OUTCOME: forward premium move in POINTS. P(reach entry+{5,10,15,20} pts) within {30/60/180/300s} BEFORE a
       stop {-5,-10,-15 pts}. Reports +15 hit-rate, MFE (max favorable excursion) mean/median, mean fwd
       move per horizon, and NET-OF-COST expectancy for a "+15 target / stop / timeout" trade (sell at
       target / stop / bid). On ties (both target & stop in window) assume STOP first (conservative).
SPLIT by regime (range vs trend-DOWN on the option: 30s slope) and time-of-day.

HONESTY: buying new lows is maximally exposed to adverse selection (a new low often precedes more lows,
esp. in a downtrend = falling knife). We test the "+15 is easy" claim empirically (no assumption), show
the confirmation's lift, and apply Bonferroni + positive-on-all-3-days persistence to any positive. Per-day
execution (fits the call limit) then `--report`. Standalone; no trading-app code.
"""
from __future__ import annotations

import math
import os
import sys
from collections import defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import edge_study as E  # LOT_SIZE, tstat

OUT = "reports/case-studies/newlow.csv"
EV_CSV = "reports/case-studies/newlow_event_days.csv"   # per (idx,regime,conf,buffer): events, target/MFE stats
VAR_CSV = "reports/case-studies/newlow_var_days.csv"    # per full trade variant: n, win, snet, snet2
ATM_N = 2
ENTRY_FLOOR = 20.0
NET_PCT_CAP = 200.0
OFI_W = 30
SLOPE_W = 30
BUFFERS = [0.0, 0.0025, 0.005]          # new-low must beat prior min by this fraction
CONFS = ["none", "ofi_up", "not_down", "volpos"]
TARGETS = [5, 10, 15, 20]               # premium POINTS
STOPS = [5, 10, 15]                     # premium POINTS (negative)
HORIZONS = [30, 60, 180, 300]
ALPHA = 0.05


def _charge(b, s, q):
    return ("(40 + {s}*{q}*0.0015 + ({b}+{s})*{q}*0.0003553 + ({b}+{s})*{q}*1e-6 + {b}*{q}*0.00003 "
            "+ 0.18*(40 + ({b}+{s})*{q}*1e-6 + ({b}+{s})*{q}*0.0003553))").format(b=b, s=s, q=q)


def _lot():
    return "CASE idx %s ELSE 50 END" % " ".join("WHEN '%s' THEN %d" % (k, v) for k, v in E.LOT_SIZE.items())


def _base(src, idx_filter=""):
    """Per-(token,sec) dedup; running intraday min/max; OFI; option 30s slope; near-ATM filter; forward extrema."""
    return """
    WITH raw AS (
      SELECT index idx, tradingSymbol tok, CAST(strike AS BIGINT) strike,
        CASE WHEN exchangeTsEpochSec>0 THEN exchangeTsEpochSec ELSE CAST(recvEpochMs/1000 AS BIGINT) END ts,
        ltp, bestBid bid, bestAsk ask, cumVolume vol, recvEpochMs FROM {src} WHERE ltp>0 {idxf}),
    d AS (SELECT * EXCLUDE rn FROM (SELECT *, row_number() OVER (PARTITION BY tok,ts ORDER BY recvEpochMs DESC) rn FROM raw) WHERE rn=1),
    atm AS (SELECT idx, arg_max(strike, v) atm FROM (SELECT idx,strike,max(vol) v FROM d GROUP BY idx,strike) GROUP BY idx),
    near AS (SELECT d.* FROM d JOIN atm a USING(idx)
             WHERE abs(d.strike-a.atm) <= {atmn}*(CASE d.idx WHEN 'NIFTY' THEN 50 ELSE 100 END)),
    f AS (
      SELECT idx,tok,strike,ts,ltp,bid,ask,vol,
        MIN(ltp) OVER (PARTITION BY tok ORDER BY ts ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING) run_min_prev,
        lag(ltp) OVER w prevltp, GREATEST(vol-lag(vol) OVER w,0) vdelta,
        ltp - (first_value(ltp) OVER s) AS slope30,   -- option premium change over last 30s (pts)
        CASE WHEN bid>0 AND ask>0 AND ltp>=ask THEN 1 WHEN bid>0 AND ask>0 AND ltp<=bid THEN -1
             WHEN lag(ltp) OVER w IS NOT NULL AND ltp>lag(ltp) OVER w THEN 1
             WHEN lag(ltp) OVER w IS NOT NULL AND ltp<lag(ltp) OVER w THEN -1 ELSE 0 END aggr
      FROM near
      WINDOW w AS (PARTITION BY tok ORDER BY ts),
             s AS (PARTITION BY tok ORDER BY ts RANGE BETWEEN {sl} PRECEDING AND CURRENT ROW)),
    sv AS (SELECT *, vdelta*aggr signed_vol FROM f),
    o AS (
      SELECT *, CASE WHEN SUM(vdelta) OVER r>0 THEN SUM(signed_vol) OVER r/SUM(vdelta) OVER r ELSE 0 END ofi
      FROM sv WINDOW r AS (PARTITION BY tok ORDER BY ts RANGE BETWEEN {ofiw} PRECEDING AND CURRENT ROW)),
    fwd AS (
      SELECT idx,tok,ts,ltp,bid,run_min_prev,ofi,slope30,vdelta,
        MAX(ltp) OVER (PARTITION BY tok ORDER BY ts RANGE BETWEEN 1 FOLLOWING AND 30  FOLLOWING) mx30,
        MAX(ltp) OVER (PARTITION BY tok ORDER BY ts RANGE BETWEEN 1 FOLLOWING AND 60  FOLLOWING) mx60,
        MAX(ltp) OVER (PARTITION BY tok ORDER BY ts RANGE BETWEEN 1 FOLLOWING AND 180 FOLLOWING) mx180,
        MAX(ltp) OVER (PARTITION BY tok ORDER BY ts RANGE BETWEEN 1 FOLLOWING AND 300 FOLLOWING) mx300,
        MIN(ltp) OVER (PARTITION BY tok ORDER BY ts RANGE BETWEEN 1 FOLLOWING AND 30  FOLLOWING) mn30,
        MIN(ltp) OVER (PARTITION BY tok ORDER BY ts RANGE BETWEEN 1 FOLLOWING AND 60  FOLLOWING) mn60,
        MIN(ltp) OVER (PARTITION BY tok ORDER BY ts RANGE BETWEEN 1 FOLLOWING AND 180 FOLLOWING) mn180,
        MIN(ltp) OVER (PARTITION BY tok ORDER BY ts RANGE BETWEEN 1 FOLLOWING AND 300 FOLLOWING) mn300,
        last_value(bid) OVER (PARTITION BY tok ORDER BY ts RANGE BETWEEN 1 FOLLOWING AND 30  FOLLOWING) lb30,
        last_value(bid) OVER (PARTITION BY tok ORDER BY ts RANGE BETWEEN 1 FOLLOWING AND 60  FOLLOWING) lb60,
        last_value(bid) OVER (PARTITION BY tok ORDER BY ts RANGE BETWEEN 1 FOLLOWING AND 180 FOLLOWING) lb180,
        last_value(bid) OVER (PARTITION BY tok ORDER BY ts RANGE BETWEEN 1 FOLLOWING AND 300 FOLLOWING) lb300
      FROM o)
    SELECT *, {lot} q,
      CASE WHEN abs(slope30) >= 0.30*ltp/100.0*30 THEN 'trend' ELSE 'range' END regime  -- crude: 30s move big => trend
    FROM fwd WHERE ltp >= {floor}
    """.format(src=src, idxf=idx_filter, atmn=ATM_N, sl=SLOPE_W, ofiw=OFI_W, lot=_lot(), floor=ENTRY_FLOOR)


def _conf_pred(conf):
    if conf == "none":
        return "TRUE"
    if conf == "ofi_up":
        return "ofi >= 0"
    if conf == "not_down":
        return "slope30 >= 0"      # premium not still falling over last 30s
    if conf == "volpos":
        return "vdelta > 0"
    return "TRUE"


def run_day(duckdb, src, date, idx_filter=""):
    duckdb.sql("CREATE OR REPLACE TEMP TABLE ev AS " + _base(src, idx_filter))
    mx = {30: "mx30", 60: "mx60", 180: "mx180", 300: "mx300"}
    mn = {30: "mn30", 60: "mn60", 180: "mn180", 300: "mn300"}
    lb = {30: "lb30", 60: "lb60", 180: "lb180", 300: "lb300"}
    cols = []
    # EVENT-level stats per (buffer, conf): n_events, +15-within-300s hit, MFE(300s) sum, mean fwd@horizons
    for bi, buf in enumerate(BUFFERS):
        trig = "(run_min_prev IS NOT NULL AND ltp < run_min_prev*(1-%g))" % buf
        for conf in CONFS:
            fire = "(%s AND %s)" % (trig, _conf_pred(conf))
            tag = "b%d_%s" % (bi, conf)
            cols += [
                "SUM(CASE WHEN %s THEN 1 ELSE 0 END) ev_%s" % (fire, tag),
                "SUM(CASE WHEN %s AND mx300-ltp>=15 THEN 1 ELSE 0 END) h15_%s" % (fire, tag),
                "SUM(CASE WHEN %s THEN (mx300-ltp) ELSE 0 END) mfe_%s" % (fire, tag),
                "SUM(CASE WHEN %s THEN (mn300-ltp) ELSE 0 END) mae_%s" % (fire, tag),
            ]
    # TRADE variants: (buffer,conf,target,stop,horizon) net-of-cost with tick-by-tick target/stop/timeout
    for bi, buf in enumerate(BUFFERS):
        trig = "(run_min_prev IS NOT NULL AND ltp < run_min_prev*(1-%g))" % buf
        for conf in CONFS:
            fire = "(%s AND %s)" % (trig, _conf_pred(conf))
            for tgt in TARGETS:
                for stp in STOPS:
                    for H in HORIZONS:
                        target = "(ltp+%d)" % tgt
                        stoppx = "(ltp-%d)" % stp
                        stop_hit = "(%s <= %s)" % (mn[H], stoppx)
                        tgt_hit = "(%s >= %s)" % (mx[H], target)
                        exitpx = "CASE WHEN %s THEN %s WHEN %s THEN %s ELSE %s END" % (
                            stop_hit, stoppx, tgt_hit, target, lb[H])
                        net_rs = "(({ex})-ltp)*q - {ch}".format(ex=exitpx, ch=_charge("ltp", "("+exitpx+")", "q"))
                        net_pct = "GREATEST(-%g,LEAST(%g,(%s)/(ltp*q)*100.0))" % (NET_PCT_CAP, NET_PCT_CAP, net_rs)
                        cond = "(%s AND (%s) IS NOT NULL)" % (fire, exitpx)
                        v = "b%d_%s_t%d_s%d_h%d" % (bi, conf, tgt, stp, H)
                        cols += [
                            "SUM(CASE WHEN %s THEN 1 ELSE 0 END) n_%s" % (cond, v),
                            "SUM(CASE WHEN %s AND (%s)>0 THEN 1 ELSE 0 END) w_%s" % (cond, net_pct, v),
                            "SUM(CASE WHEN %s THEN %s ELSE 0 END) sn_%s" % (cond, net_pct, v),
                            "SUM(CASE WHEN %s THEN (%s)*(%s) ELSE 0 END) s2_%s" % (cond, net_pct, net_pct, v),
                        ]
    q = "SELECT idx, regime, " + ", ".join(cols) + " FROM ev GROUP BY idx, regime"
    recs = duckdb.sql(q).df().to_dict("records")
    _write(date, recs)
    return len(recs)


def _write(date, recs):
    import csv as _csv
    os.makedirs("reports/case-studies", exist_ok=True)
    edr = ["date", "idx", "regime", "buffer", "conf", "events", "hit15_300", "mfe_sum", "mae_sum"]
    vdr = ["date", "idx", "regime", "buffer", "conf", "tgt", "stop", "hzn", "n", "win", "snet", "snet2"]
    idxset = {r["idx"] for r in recs}
    def keep(path):
        if not os.path.exists(path):
            return []
        with open(path, newline="") as fh:
            return [r for r in _csv.DictReader(fh) if not (r["date"] == date and r["idx"] in idxset)]
    ev = keep(EV_CSV); va = keep(VAR_CSV)
    for rec in recs:
        idx, reg = rec["idx"], rec["regime"]
        for bi, buf in enumerate(BUFFERS):
            for conf in CONFS:
                tag = "b%d_%s" % (bi, conf)
                n = int(rec["ev_%s" % tag] or 0)
                if n > 0:
                    ev.append({"date": date, "idx": idx, "regime": reg, "buffer": buf, "conf": conf,
                               "events": n, "hit15_300": int(rec["h15_%s" % tag] or 0),
                               "mfe_sum": float(rec["mfe_%s" % tag] or 0), "mae_sum": float(rec["mae_%s" % tag] or 0)})
                for tgt in TARGETS:
                    for stp in STOPS:
                        for H in HORIZONS:
                            v = "b%d_%s_t%d_s%d_h%d" % (bi, conf, tgt, stp, H)
                            nn = int(rec["n_%s" % v] or 0)
                            if nn == 0:
                                continue
                            va.append({"date": date, "idx": idx, "regime": reg, "buffer": buf, "conf": conf,
                                       "tgt": tgt, "stop": stp, "hzn": H, "n": nn, "win": int(rec["w_%s" % v] or 0),
                                       "snet": float(rec["sn_%s" % v] or 0), "snet2": float(rec["s2_%s" % v] or 0)})
    with open(EV_CSV, "w", newline="") as fh:
        w = _csv.DictWriter(fh, fieldnames=edr); w.writeheader(); w.writerows(ev)
    with open(VAR_CSV, "w", newline="") as fh:
        w = _csv.DictWriter(fh, fieldnames=vdr); w.writeheader(); w.writerows(va)


def report():
    import csv as _csv
    if not os.path.exists(EV_CSV):
        print("[newlow] no per-day store; run --day first."); return
    days = set()
    evp = defaultdict(lambda: {"events": 0, "hit15": 0, "mfe": 0.0, "mae": 0.0})
    with open(EV_CSV, newline="") as fh:
        for r in _csv.DictReader(fh):
            days.add(r["date"])
            for reg in (r["regime"], "all"):
                k = (r["idx"], reg, float(r["buffer"]), r["conf"])
                a = evp[k]; a["events"] += int(r["events"]); a["hit15"] += int(r["hit15_300"])
                a["mfe"] += float(r["mfe_sum"]); a["mae"] += float(r["mae_sum"])
    pooled = defaultdict(lambda: {"n": 0, "w": 0, "sn": 0.0, "sn2": 0.0}); perday = defaultdict(dict)
    with open(VAR_CSV, newline="") as fh:
        for r in _csv.DictReader(fh):
            for reg in (r["regime"], "all"):
                k = (r["idx"], reg, float(r["buffer"]), r["conf"], int(r["tgt"]), int(r["stop"]), int(r["hzn"]))
                a = pooled[k]; a["n"] += int(r["n"]); a["w"] += int(r["win"]); a["sn"] += float(r["snet"]); a["sn2"] += float(r["snet2"])
                pd = perday[k].setdefault(r["date"], {"n": 0, "sn": 0.0}); pd["n"] += int(r["n"]); pd["sn"] += float(r["snet"])
    ndays = len(days); M = sum(1 for p in pooled.values() if p["n"] > 0); bonf = ALPHA / M if M else ALPHA
    print("\n==== NEW-DAY-LOW near-ATM retracement study (net-of-cost, %d days) ====" % ndays)
    print("'+15 pts is easy' CLAIM — actual P(+15 within 300s) by confirmation (regime=all, buffer=0):")
    print("%-9s %-9s %9s %8s %9s %9s" % ("idx", "conf", "events", "P(+15)", "MFE_avg", "MAE_avg"))
    for (idx, reg, buf, conf), a in sorted(evp.items()):
        if reg != "all" or buf != 0.0 or a["events"] == 0:
            continue
        print("%-9s %-9s %9d %7.1f%% %9.2f %9.2f" % (idx, conf, a["events"], a["hit15"]/a["events"]*100,
              a["mfe"]/a["events"], a["mae"]/a["events"]))
    # trade variant guardrail
    rows = []; pooled_pos = 0; survivors = []
    for k, p in pooled.items():
        if p["n"] == 0:
            continue
        idx, reg, buf, conf, tgt, stp, H = k
        mean = p["sn"]/p["n"]; t = E.tstat(p["n"], p["sn"], p["sn2"]); dd = perday[k]
        posd = sum(1 for v in dd.values() if v["n"] > 0 and v["sn"]/v["n"] > 0)
        persist = (len(dd) == ndays and posd == ndays); bsig = (not math.isnan(t)) and abs(t) >= 3.5
        cand = mean > 0 and persist and bsig
        if mean > 0: pooled_pos += 1
        if cand: survivors.append((k, mean, t, p["n"]))
        rows.append({"idx": idx, "regime": reg, "buffer": buf, "conf": conf, "tgt": tgt, "stop": stp, "hzn": H,
                     "n": p["n"], "winrate": round(p["w"]/p["n"], 3), "net_pct": round(mean, 4),
                     "t": round(t, 2) if not math.isnan(t) else "", "pos_days": posd, "seen_days": len(dd),
                     "bonf_sig": "Y" if bsig else "", "persistent": "Y" if persist else "",
                     "candidate_overfit": "Y" if cand else ""})
    with open(OUT, "w", newline="") as fh:
        cols = ["idx", "regime", "buffer", "conf", "tgt", "stop", "hzn", "n", "winrate", "net_pct", "t",
                "pos_days", "seen_days", "bonf_sig", "persistent", "candidate_overfit"]
        w = _csv.DictWriter(fh, fieldnames=cols); w.writeheader()
        for r in sorted(rows, key=lambda r: r["net_pct"], reverse=True): w.writerow(r)
    print("\nTRADE variants (+target/stop/timeout, net-of-cost): M=%d  Bonferroni a=%.6f" % (M, bonf))
    print("pooled-positive: %d/%d   survive Bonf + positive-on-ALL-%d-days: %d" % (pooled_pos, M, ndays, len(survivors)))
    top = sorted(rows, key=lambda r: r["net_pct"], reverse=True)[:10]
    print("\nTop 10 trade variants by pooled net%% (ranked by FIT => survivorship; read flags):")
    print("%-8s %-6s %-7s %-8s %3s %4s %4s %7s %7s %6s %6s %5s %4s" %
          ("idx", "regime", "conf", "buf", "tgt", "stp", "hzn", "n", "net%", "win%", "t", "+dys", "cand"))
    for r in top:
        print("%-8s %-6s %-7s %-8.4f %3d %4d %4d %7d %7.3f %6.1f %6s %2d/%d %4s" % (
            r["idx"], r["regime"], r["conf"], r["buffer"], r["tgt"], r["stop"], r["hzn"], r["n"],
            r["net_pct"], r["winrate"]*100, r["t"], r["pos_days"], r["seen_days"], r["candidate_overfit"]))
    print("\n-> %s" % OUT)
    if survivors:
        print("\n*** %d variant(s) passed Bonferroni + positive-on-all-%d-days. CANDIDATE, LIKELY OVERFIT on %d"
              " days -- validate FORWARD OOS. NOT a found edge. ***" % (len(survivors), ndays, ndays))
    else:
        print("\nNothing survives correction + per-day persistence -> no defensible variant; document only.")


def main():
    import argparse
    ap = argparse.ArgumentParser()
    ap.add_argument("--day"); ap.add_argument("--src"); ap.add_argument("--idx", default=""); ap.add_argument("--report", action="store_true")
    a = ap.parse_args()
    if a.report:
        report()
    elif a.day and a.src:
        import duckdb
        src = "read_parquet('%s')" % a.src if (a.src.endswith(".parquet") or "*" in a.src) else "read_csv_auto('%s')" % a.src
        idxf = ("AND index = '%s'" % a.idx) if a.idx else ""
        n = run_day(duckdb, src, a.day, idxf)
        print("[newlow] %s: %d (idx,regime) groups -> stores updated." % (a.day, n))
    else:
        print("use --day <date> --src <glob> [--idx X]  then  --report")


if __name__ == "__main__":
    main()
