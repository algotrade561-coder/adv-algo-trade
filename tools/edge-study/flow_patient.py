#!/usr/bin/env python3
"""
flow_patient.py — PATIENT LIMIT-EXECUTION buy-timing study (duckdb). The signal is a BIAS FILTER, not a
market order: on a bullish OFI fire, place a LIMIT BUY below price and only enter if it fills cheap; PASS
if it never fills. Exit via LIMIT SELL at a target, with a stop + timeout fallback. Fills are modeled
TICK-BY-TICK from the per-second data (no-lookahead; exchangeTs time; per-(token,second) dedup).

Fill model (honest, conservative):
  ENTRY limit at P (offset below ref): fills iff the option TRADES at/through P within the wait window
        -> MIN(ltp) over (t, t+W] <= P  ; fill price = P. Else PASS (no trade).
  EXIT  over (t, t+hold]:  target=P*(1+tgt) hit iff MAX(ltp) >= target -> sell at target;
        stop=P*(1-stop) hit iff MIN(ltp) <= stop -> sell at stop; if BOTH, assume STOP first (conservative);
        else timeout -> sell at the bid near +hold (market). Hold measured from signal second (slightly
        conservative vs from fill). Costs (brokerage/STT/GST/...) on the ACHIEVED prices, not mid.

ADVERSE SELECTION is reported: fill-rate, pass-rate, and mean forward move of FILLED vs ALL fired signals
(limit buys disproportionately fill when price keeps dropping). Multiple-testing: big sweep -> Bonferroni
+ positive-on-all-3-days persistence; any positive = "candidate, likely overfit on 3 days — validate fwd".

Per-day mode (`--day --src`, one scan/day) then `--report`. Standalone; no trading-app code.
"""
from __future__ import annotations

import math
import os
import sys
from collections import defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import edge_study as E  # LOT_SIZE, tstat

OUT = "reports/case-studies/flow_patient.csv"
VAR_CSV = "reports/case-studies/patient_var_days.csv"
FILL_CSV = "reports/case-studies/patient_fill_days.csv"
ENTRY_FLOOR = 20.0
NET_PCT_CAP = 200.0
FRAC = 0.25
OFI_W = 30
EDGE_THRESH = 0.33
TREND_BAND = 0.05
OFFSETS = {"bid": "bid", "mid": "mid", "midq": "mid-0.25*spr", "midh": "mid-0.5*spr", "prevlow": "prevltp"}
WAITS = [30, 60]
TGTS = [0.05, 0.10, 0.20]
STOPS = [0.0, 0.5]
HOLDS = [180, 300]
ALPHA = 0.05


def _charge(b, s, q):
    return ("(40 + {s}*{q}*0.0015 + ({b}+{s})*{q}*0.0003553 + ({b}+{s})*{q}*1e-6 + {b}*{q}*0.00003 "
            "+ 0.18*(40 + ({b}+{s})*{q}*1e-6 + ({b}+{s})*{q}*0.0003553))").format(b=b, s=s, q=q)


def _lot():
    return "CASE idx %s ELSE 50 END" % " ".join("WHEN '%s' THEN %d" % (k, v) for k, v in E.LOT_SIZE.items())


def _base(src, idx_filter=""):
    return """
    WITH raw AS (
      SELECT index idx, tradingSymbol tok,
        CASE WHEN exchangeTsEpochSec>0 THEN exchangeTsEpochSec ELSE CAST(recvEpochMs/1000 AS BIGINT) END ts,
        ltp, bestBid bid, bestAsk ask, cumVolume vol, recvEpochMs FROM {src} WHERE ltp>0 {idxf}),
    d AS (SELECT * EXCLUDE rn FROM (SELECT *, row_number() OVER (PARTITION BY tok,ts ORDER BY recvEpochMs DESC) rn FROM raw) WHERE rn=1),
    f AS (
      SELECT idx,tok,ts,ltp,bid,ask,(bid+ask)/2.0 mid,GREATEST(ask-bid,0) spr,
        lag(ltp) OVER w prevltp, GREATEST(vol-lag(vol) OVER w,0) vdelta,
        CASE WHEN bid>0 AND ask>0 AND ltp>=ask THEN 1 WHEN bid>0 AND ask>0 AND ltp<=bid THEN -1
             WHEN bid>0 AND ask>0 AND ltp>=(bid+ask)/2.0+{frac}*GREATEST(ask-bid,0) THEN 1
             WHEN bid>0 AND ask>0 AND ltp<=(bid+ask)/2.0-{frac}*GREATEST(ask-bid,0) THEN -1
             WHEN lag(ltp) OVER w IS NOT NULL AND ltp>lag(ltp) OVER w THEN 1
             WHEN lag(ltp) OVER w IS NOT NULL AND ltp<lag(ltp) OVER w THEN -1 ELSE 0 END aggr
      FROM d WINDOW w AS (PARTITION BY tok ORDER BY ts)),
    sv AS (SELECT *, vdelta*aggr signed_vol FROM f),
    o AS (
      SELECT *,
        CASE WHEN SUM(vdelta) OVER r>0 THEN SUM(signed_vol) OVER r/SUM(vdelta) OVER r ELSE 0 END ofi,
        (ltp-(first_value(ltp) OVER r))/NULLIF(first_value(ltp) OVER r,0)*100.0 prem_move_w
      FROM sv WINDOW r AS (PARTITION BY tok ORDER BY ts RANGE BETWEEN {ofiw} PRECEDING AND CURRENT ROW)),
    fwd AS (
      SELECT idx,tok,ts,ltp,bid,mid,spr,prevltp,ofi,
        CASE WHEN abs(prem_move_w)>={band} THEN 'trend' ELSE 'range' END regime,
        MIN(ltp) OVER (PARTITION BY tok ORDER BY ts RANGE BETWEEN 1 FOLLOWING AND 30 FOLLOWING) fmin30,
        MIN(ltp) OVER (PARTITION BY tok ORDER BY ts RANGE BETWEEN 1 FOLLOWING AND 60 FOLLOWING) fmin60,
        MAX(ltp) OVER (PARTITION BY tok ORDER BY ts RANGE BETWEEN 1 FOLLOWING AND 180 FOLLOWING) fmax180,
        MAX(ltp) OVER (PARTITION BY tok ORDER BY ts RANGE BETWEEN 1 FOLLOWING AND 300 FOLLOWING) fmax300,
        MIN(ltp) OVER (PARTITION BY tok ORDER BY ts RANGE BETWEEN 1 FOLLOWING AND 180 FOLLOWING) fmn180,
        MIN(ltp) OVER (PARTITION BY tok ORDER BY ts RANGE BETWEEN 1 FOLLOWING AND 300 FOLLOWING) fmn300,
        last_value(bid) OVER (PARTITION BY tok ORDER BY ts RANGE BETWEEN 1 FOLLOWING AND 180 FOLLOWING) lbid180,
        last_value(bid) OVER (PARTITION BY tok ORDER BY ts RANGE BETWEEN 1 FOLLOWING AND 300 FOLLOWING) lbid300,
        last_value(ltp) OVER (PARTITION BY tok ORDER BY ts RANGE BETWEEN 1 FOLLOWING AND 60 FOLLOWING) fltp60
      FROM o)
    SELECT *, {lot} q, (ofi>={th} AND ltp>={floor}) AS fired,
        (fltp60-ltp)/ltp*100.0 fchg60
    FROM fwd
    """.format(src=src, frac=FRAC, ofiw=OFI_W, band=TREND_BAND, lot=_lot(), th=EDGE_THRESH, floor=ENTRY_FLOOR, idxf=idx_filter)


def _pe(off):
    return OFFSETS[off]


def run_day(duckdb, src, date, idx_filter=""):
    duckdb.sql("CREATE OR REPLACE TEMP TABLE ev AS " + _base(src, idx_filter))
    cols = ["SUM(CASE WHEN fired THEN 1 ELSE 0 END) fires",
            "SUM(CASE WHEN fired THEN fchg60 ELSE 0 END) sfchg_all"]
    # per (offset,wait): fills + adverse
    for off in OFFSETS:
        pe = _pe(off)
        for W in WAITS:
            fm = "fmin30" if W == 30 else "fmin60"
            fillcond = "(fired AND %s>0 AND %s<=%s)" % (pe, fm, pe)
            tag = "%s_%d" % (off, W)
            cols += ["SUM(CASE WHEN %s THEN 1 ELSE 0 END) fl_%s" % (fillcond, tag),
                     "SUM(CASE WHEN %s THEN fchg60 ELSE 0 END) flfchg_%s" % (fillcond, tag)]
    # per variant: nfill, win, snet, snet2
    for off in OFFSETS:
        pe = _pe(off)
        for W in WAITS:
            fm = "fmin30" if W == 30 else "fmin60"
            fill = "(fired AND %s>0 AND %s<=%s)" % (pe, fm, pe)
            for tgt in TGTS:
                for stp in STOPS:
                    for hold in HOLDS:
                        fmax = "fmax180" if hold == 180 else "fmax300"
                        fmn = "fmn180" if hold == 180 else "fmn300"
                        lbid = "lbid180" if hold == 180 else "lbid300"
                        P = pe
                        target = "(%s)*(1+%g)" % (P, tgt)
                        stoppx = "NULL" if stp == 0 else "(%s)*(1-%g)" % (P, stp)
                        stop_hit = "FALSE" if stp == 0 else "(%s<=%s)" % (fmn, stoppx)
                        exitpx = "CASE WHEN %s THEN %s WHEN %s>=%s THEN %s ELSE %s END" % (
                            stop_hit, (stoppx if stp else "0"), fmax, target, target, lbid)
                        net_rs = "(({ex})-({P}))*q - {ch}".format(ex=exitpx, P=P, ch=_charge("("+P+")", "("+exitpx+")", "q"))
                        net_pct = "GREATEST(-%g,LEAST(%g,(%s)/((%s)*q)*100.0))" % (NET_PCT_CAP, NET_PCT_CAP, net_rs, P)
                        cond = "(%s AND (%s) IS NOT NULL)" % (fill, exitpx)
                        v = "%s_%d_%d_%d_%d" % (off, W, int(tgt*100), int(stp*100), hold)
                        cols += [
                            "SUM(CASE WHEN %s THEN 1 ELSE 0 END) nf_%s" % (cond, v),
                            "SUM(CASE WHEN %s AND (%s)>0 THEN 1 ELSE 0 END) wn_%s" % (cond, net_pct, v),
                            "SUM(CASE WHEN %s THEN %s ELSE 0 END) sn_%s" % (cond, net_pct, v),
                            "SUM(CASE WHEN %s THEN (%s)*(%s) ELSE 0 END) s2_%s" % (cond, net_pct, net_pct, v),
                        ]
    q = "SELECT idx, regime, " + ", ".join(cols) + " FROM ev GROUP BY idx, regime"
    recs = duckdb.sql(q).df().to_dict("records")
    _write_day(date, recs)
    return len(recs)


def _write_day(date, recs):
    import csv as _csv
    os.makedirs("reports/case-studies", exist_ok=True)
    # fill/adverse rows
    fdr = ["date", "idx", "regime", "offset", "wait", "fires", "fills", "sfchg_all", "sfchg_fill"]
    vdr = ["date", "idx", "regime", "offset", "wait", "tgt", "stop", "hold", "nfill", "win", "snet", "snet2"]
    idxset = {rec["idx"] for rec in recs}
    def reload_drop(path, fields):
        rows = []
        if os.path.exists(path):
            with open(path, newline="") as fh:
                rows = [r for r in _csv.DictReader(fh)
                        if not (r.get("date") == date and r.get("idx") in idxset)]
        return rows
    fills = reload_drop(FILL_CSV, fdr); vars_ = reload_drop(VAR_CSV, vdr)
    for rec in recs:
        idx, reg = rec["idx"], rec["regime"]
        fires = int(rec["fires"] or 0); sall = float(rec["sfchg_all"] or 0)
        for off in OFFSETS:
            for W in WAITS:
                tag = "%s_%d" % (off, W)
                fills.append({"date": date, "idx": idx, "regime": reg, "offset": off, "wait": W,
                              "fires": fires, "fills": int(rec["fl_%s" % tag] or 0),
                              "sfchg_all": sall, "sfchg_fill": float(rec["flfchg_%s" % tag] or 0)})
                for tgt in TGTS:
                    for stp in STOPS:
                        for hold in HOLDS:
                            v = "%s_%d_%d_%d_%d" % (off, W, int(tgt*100), int(stp*100), hold)
                            nf = int(rec["nf_%s" % v] or 0)
                            if nf == 0:
                                continue
                            vars_.append({"date": date, "idx": idx, "regime": reg, "offset": off, "wait": W,
                                          "tgt": tgt, "stop": stp, "hold": hold, "nfill": nf,
                                          "win": int(rec["wn_%s" % v] or 0), "snet": float(rec["sn_%s" % v] or 0),
                                          "snet2": float(rec["s2_%s" % v] or 0)})
    with open(FILL_CSV, "w", newline="") as fh:
        w = _csv.DictWriter(fh, fieldnames=fdr); w.writeheader(); w.writerows(fills)
    with open(VAR_CSV, "w", newline="") as fh:
        w = _csv.DictWriter(fh, fieldnames=vdr); w.writeheader(); w.writerows(vars_)


def report():
    import csv as _csv
    if not os.path.exists(VAR_CSV):
        print("[patient] no per-day store; run --day first."); return
    days = set()
    # adverse / fill-rate (regime=all aggregation across regimes too)
    fr = defaultdict(lambda: {"fires": 0, "fills": 0, "sall": 0.0, "sfill": 0.0})
    with open(FILL_CSV, newline="") as fh:
        for r in _csv.DictReader(fh):
            days.add(r["date"])
            for reg in (r["regime"], "all"):
                k = (r["idx"], reg, r["offset"], int(r["wait"]))
                a = fr[k]; a["fires"] += int(float(r["fires"])); a["fills"] += int(float(r["fills"]))
                a["sall"] += float(r["sfchg_all"]); a["sfill"] += float(r["sfchg_fill"])
    pooled = defaultdict(lambda: {"n": 0, "w": 0, "sn": 0.0, "sn2": 0.0}); perday = defaultdict(dict)
    with open(VAR_CSV, newline="") as fh:
        for r in _csv.DictReader(fh):
            for reg in (r["regime"], "all"):
                k = (r["idx"], reg, r["offset"], int(r["wait"]), float(r["tgt"]), float(r["stop"]), int(r["hold"]))
                a = pooled[k]; a["n"] += int(float(r["nfill"])); a["w"] += int(float(r["win"]))
                a["sn"] += float(r["snet"]); a["sn2"] += float(r["snet2"])
                pd = perday[k].setdefault(r["date"], {"n": 0, "sn": 0.0}); pd["n"] += int(float(r["nfill"])); pd["sn"] += float(r["snet"])
    ndays = len(days); M = sum(1 for p in pooled.values() if p["n"] > 0)
    bonf = ALPHA / M if M else ALPHA
    rows = []; pooled_pos = 0; survivors = []
    for k, p in pooled.items():
        if p["n"] == 0:
            continue
        idx, reg, off, W, tgt, stp, hold = k
        mean = p["sn"] / p["n"]; t = E.tstat(p["n"], p["sn"], p["sn2"])
        dd = perday[k]; posd = sum(1 for v in dd.values() if v["n"] > 0 and v["sn"]/v["n"] > 0)
        persist = (len(dd) == ndays and posd == ndays)
        bsig = (not math.isnan(t)) and abs(t) >= 3.5
        cand = mean > 0 and persist and bsig
        if mean > 0: pooled_pos += 1
        if cand: survivors.append((k, mean, t, p["n"]))
        rows.append({"idx": idx, "regime": reg, "offset": off, "wait": W, "tgt": tgt, "stop": stp, "hold": hold,
                     "nfill": p["n"], "winrate": round(p["w"]/p["n"], 3), "net_pct": round(mean, 4),
                     "t": round(t, 2) if not math.isnan(t) else "", "pos_days": posd, "seen_days": len(dd),
                     "bonf_sig": "Y" if bsig else "", "persistent": "Y" if persist else "",
                     "candidate_overfit": "Y" if cand else ""})
    with open(OUT, "w", newline="") as fh:
        cols = ["idx", "regime", "offset", "wait", "tgt", "stop", "hold", "nfill", "winrate", "net_pct",
                "t", "pos_days", "seen_days", "bonf_sig", "persistent", "candidate_overfit"]
        w = _csv.DictWriter(fh, fieldnames=cols); w.writeheader()
        for r in sorted(rows, key=lambda r: r["net_pct"], reverse=True): w.writerow(r)

    print("\n==== PATIENT LIMIT-EXECUTION sweep (net-of-cost, %d days) ====" % ndays)
    print("variants (nfill>0) M=%d  Bonferroni alpha=%.6f (|z|>=~3.5)  expected chance positives ~%.1f"
          % (M, bonf, M*0.05))
    print("pooled-positive: %d/%d   survive Bonf + positive-on-ALL-%d-days: %d" % (pooled_pos, M, ndays, len(survivors)))
    print("\n-- FILL / PASS / ADVERSE-SELECTION (regime=all, by offset x wait) --")
    print("%-8s %-7s %4s %9s %7s %7s   fwdMove60s: %8s %8s" %
          ("idx", "offset", "wait", "fires", "fill%", "pass%", "all", "FILLED"))
    for (idx, reg, off, W), a in sorted(fr.items()):
        if reg != "all" or a["fires"] == 0:
            continue
        fillp = a["fills"]/a["fires"]; m_all = a["sall"]/a["fires"]; m_fill = a["sfill"]/a["fills"] if a["fills"] else 0
        if off in ("bid", "midh") and idx in ("NIFTY", "SENSEX"):
            print("%-8s %-7s %4d %9d %6.1f%% %6.1f%%   %+8.3f %+8.3f" %
                  (idx, off, W, a["fires"], fillp*100, (1-fillp)*100, m_all, m_fill))
    print("  (full table in patient_fill_days.csv; FILLED fwd-move more negative than ALL => adverse selection)")
    top = sorted(rows, key=lambda r: r["net_pct"], reverse=True)[:12]
    print("\nTop 12 variants by pooled net%% on FILLED (ranked by FIT => survivorship; read flags):")
    print("%-8s %-6s %-7s %4s %4s %5s %4s %7s %7s %6s %6s %5s %4s" %
          ("idx", "regime", "offset", "wt", "tgt", "stop", "hld", "nfill", "net%", "win%", "t", "+dys", "cand"))
    for r in top:
        print("%-8s %-6s %-7s %4d %4.0f %5.0f %4d %7d %7.3f %6.1f %6s %2d/%d %4s" % (
            r["idx"], r["regime"], r["offset"], r["wait"], r["tgt"]*100, r["stop"]*100, r["hold"],
            r["nfill"], r["net_pct"], r["winrate"]*100, r["t"], r["pos_days"], r["seen_days"], r["candidate_overfit"]))
    print("\n-> %s" % OUT)
    if survivors:
        print("\n*** %d variant(s) passed Bonferroni + positive-on-all-%d-days. CANDIDATE, LIKELY OVERFIT on %d"
              " days -- validate FORWARD out-of-sample. NOT a found edge. ***" % (len(survivors), ndays, ndays))
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
        print("[patient] %s: %d (idx,regime) groups -> per-day stores updated." % (a.day, n))
    else:
        print("use --day <date> --src <glob>  then  --report")


if __name__ == "__main__":
    main()
