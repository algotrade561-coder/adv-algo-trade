#!/usr/bin/env python3
"""
case_studies.py — enumerate & run FEASIBLE edge case-studies across all available data, with a
multiple-testing / cross-day-persistence guardrail. Standalone; imports primitives from edge_study.py.

Modes:
  --mode chain   : extended chain-snapshot signals over ALL available dates x underlyings.
  --mode cross   : cross-index lead-lag (NIFTY signal -> SENSEX/BANKNIFTY forward return).
  --mode micro   : microstructure (per-second) OI-velocity / queue-imbalance -> forward option move.
  --mode report  : pool the case-study cumulative store, apply Bonferroni + cross-day persistence.
  --mode all     : chain + cross, then report.

Honesty guardrail: we test MANY combinations. The report prints raw stats AND a corrected view
(Bonferroni alpha/M) and requires the net edge to PERSIST across days before flagging a candidate.
Descriptive t-stats only (overlapping windows). Edge != future.
"""
from __future__ import annotations

import argparse
import csv
import math
import os
import sys
from collections import defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import edge_study as E  # noqa: E402
try:
    import flow_study as FS  # noqa: E402 (signed-volume / order-flow-imbalance)
except Exception:
    FS = None
try:
    import micro_study as MS  # noqa: E402 (duckdb-backed microstructure tier)
except Exception:
    MS = None
try:
    import h2_attrib as H2    # noqa: E402 (ground-truth realized P&L)
except Exception:
    H2 = None
import glob as _glob  # noqa: E402  (load_day, option_net, round_trip_charges, tstat, regime_at, tod_at, _num, HORIZONS, LOT_SIZE)

CASE_CUM = "reports/case-studies/cumulative.csv"
CASE_ROOT = "reports/case-studies"
CUM_FIELDS = ["date", "study", "underlying", "signal", "horizon", "regime", "tod",
              "n", "hits", "sret", "sret2", "no", "owins", "snet", "snet2", "snetrs"]
ALPHA = 0.05


def _sgn(x):
    return 1 if x > 0 else (-1 if x < 0 else 0)


ENTRY_FLOOR = 20.0   # rupees; realistically-tradeable ATM premium. Below this the % denominator
                     # is unstable (near-expiry ₹2 options) so we skip — honest, conservative.
NET_PCT_CAP = 200.0  # winsorize per-trade net% so a few expiry-day explosions can't dominate the mean


def guarded_option_net(snaps, i, h, d, ul):
    """edge_study.option_net but skip when the ATM entry premium is implausibly small (denominator blow-up)."""
    if d == 0 or i + h >= len(snaps):
        return None
    side = "CE" if d > 0 else "PE"
    entry = E.atm_option_price(snaps[i], side, "entry")
    if math.isnan(entry) or entry < ENTRY_FLOOR:
        return None
    res = E.option_net(snaps, i, h, d, ul)
    if res is None:
        return None
    np_, nr_ = res
    np_ = max(-NET_PCT_CAP, min(NET_PCT_CAP, np_))   # winsorize the percentage tail
    return (np_, nr_)


# ── extended chain signals (data <= i) — beyond edge_study's core four ─────────────────────
def atm_iv(snap):
    row = snap["strikes"].get(snap["atm"])
    if not row and snap["strikes"]:
        k = min(snap["strikes"], key=lambda x: abs(x - snap["atm"]))
        row = snap["strikes"][k]
    if not row:
        return (float("nan"), float("nan"))
    return (E._num(row.get("ceIV")), E._num(row.get("peIV")))


def atm_straddle(snap):
    row = snap["strikes"].get(snap["atm"])
    if not row and snap["strikes"]:
        k = min(snap["strikes"], key=lambda x: abs(x - snap["atm"]))
        row = snap["strikes"][k]
    if not row:
        return float("nan")
    ce, pe = E._num(row.get("ceLTP")), E._num(row.get("peLTP"))
    if math.isnan(ce) or math.isnan(pe):
        return float("nan")
    return ce + pe


def max_pain(snap):
    """Strike minimizing total option-holder payout (sum of ITM intrinsic*OI). Cheap proxy from chain."""
    ks = sorted(snap["strikes"])
    if len(ks) < 3:
        return float("nan")
    best_k, best_pain = None, None
    for kx in ks:
        pain = 0.0
        for k2 in ks:
            row = snap["strikes"][k2]
            ce_oi, pe_oi = E._num(row.get("ceOI")), E._num(row.get("peOI"))
            ce_oi = 0 if math.isnan(ce_oi) else ce_oi
            pe_oi = 0 if math.isnan(pe_oi) else pe_oi
            pain += max(0.0, kx - k2) * ce_oi      # CE writers pay when spot(kx) > strike
            pain += max(0.0, k2 - kx) * pe_oi      # PE writers pay when spot(kx) < strike
        if best_pain is None or pain < best_pain:
            best_pain, best_k = pain, kx
    return best_k


def extended_signals(snaps, i):
    """Core four (from edge_study) + iv_skew, iv_meanrev, maxpain_drift, straddle_lead."""
    out = dict(E.signals_at(snaps, i))    # oi_lean, pcr_slope, spot_mom, range_fade
    cur = snaps[i]
    ce_iv, pe_iv = atm_iv(cur)
    # IV skew: CE IV richer than PE IV => unusual call demand => bullish
    if not (math.isnan(ce_iv) or math.isnan(pe_iv)):
        out["iv_skew"] = _sgn(ce_iv - pe_iv)
    # IV mean-reversion (vol, not direction): high vix vs trailing mean => expect fade (flag only; no dir)
    # maxpain drift: spot below maxpain => pin pull up => bullish (and vice versa), stronger near expiry
    mp = max_pain(cur)
    if not math.isnan(mp) and cur["spot"] > 0:
        gap = (mp - cur["spot"]) / cur["spot"]
        out["maxpain_drift"] = _sgn(gap) if abs(gap) > 0.0005 else 0   # >0.05% gap
    # straddle lead: ATM straddle premium rising faster than spot is flat => coming move; sign from prior dir
    if i >= 1:
        s_now, s_prev = atm_straddle(cur), atm_straddle(snaps[i - 1])
        if not (math.isnan(s_now) or math.isnan(s_prev)) and s_prev > 0:
            strad_vel = (s_now - s_prev) / s_prev
            spot_vel = (cur["spot"] - snaps[i - 1]["spot"]) / snaps[i - 1]["spot"] if snaps[i - 1]["spot"] > 0 else 0
            # premium expanding while spot quiet => directional energy in the direction of recent spot drift
            out["straddle_lead"] = _sgn(spot_vel) if (strad_vel > 0.01 and abs(spot_vel) < 0.0005) else 0
    return out


# ── study loops ────────────────────────────────────────────────────────────────────────────
def study_chain(snaps, underlying):
    acc = defaultdict(E.new_acc)
    n = len(snaps)
    for i in range(n):
        sig = extended_signals(snaps, i)
        reg, tod = E.regime_at(snaps, i), E.tod_at(snaps, i)
        for sname, d in sig.items():
            if d == 0:
                continue
            for h in E.HORIZONS:
                if i + h >= n:
                    continue
                si, sf = snaps[i]["spot"], snaps[i + h]["spot"]
                if not (si > 0 and sf > 0):
                    continue
                _accumulate(acc, sname, h, reg, tod, d * (sf - si) / si * 100.0,
                            guarded_option_net(snaps, i, h, d, underlying))
    return acc


def study_cross(snaps_by_ul, leader="NIFTY"):
    """Leader's signal at i -> follower's forward return at i+h (aligned by snapshot index)."""
    acc = defaultdict(E.new_acc)
    if leader not in snaps_by_ul:
        return acc
    lead = snaps_by_ul[leader]
    for follower, fsn in snaps_by_ul.items():
        if follower == leader:
            continue
        n = min(len(lead), len(fsn))
        for i in range(n):
            sig = {k: v for k, v in E.signals_at(lead, i).items() if k in ("oi_lean", "spot_mom", "pcr_slope")}
            reg, tod = E.regime_at(lead, i), E.tod_at(lead, i)
            for sname, d in sig.items():
                if d == 0:
                    continue
                for h in E.HORIZONS:
                    if i + h >= n:
                        continue
                    si, sf = fsn[i]["spot"], fsn[i + h]["spot"]
                    if not (si > 0 and sf > 0):
                        continue
                    _accumulate(acc, "%s->%s_%s" % (leader, follower, sname), h, reg, tod,
                                d * (sf - si) / si * 100.0, guarded_option_net(fsn, i, h, d, follower))
    return acc


def _accumulate(acc, sname, h, reg, tod, signed_pct, onet):
    for rk in (reg, "all"):
        for tk in (tod, "all"):
            a = acc[(sname, h, rk, tk)]
            a["n"] += 1
            a["hits"] += 1 if signed_pct > 0 else 0
            a["sret"] += signed_pct
            a["sret2"] += signed_pct * signed_pct
            if onet is not None:
                np_, nr_ = onet
                a["no"] += 1
                a["owins"] += 1 if np_ > 0 else 0
                a["snet"] += np_
                a["snet2"] += np_ * np_
                a["snetrs"] += nr_


# ── microstructure (per-second) ──────────────────────────────────────────────────────────────
def study_micro(csv_path, horizons_sec=(30, 60, 180), trail_sec=60, max_rows=2_000_000):
    """OI-velocity + queue-imbalance at tick t -> forward LTP move of the SAME option (gross premium %).
    trail_sec=60 (was 30) = the empirical OI coverage knee / the live bot's fast-OI window; a 30s OI-velocity
    reads flat >half the session (band-aggregate blackout 60%@30s vs 29%@60s, ~57s exchange refresh heartbeat)."""
    series = defaultdict(list)   # (index,strike,opt) -> list[(ts, ltp, oi, imb)]
    rows = 0
    with open(csv_path, newline="") as fh:
        r = csv.DictReader(fh)
        for row in r:
            rows += 1
            if rows > max_rows:
                break
            try:
                ts = int(float(row["exchangeTsEpochSec"]))
                ltp = float(row["ltp"]); oi = float(row["oi"])
                bq, aq = float(row["bidQty"]), float(row["askQty"])
            except (KeyError, ValueError):
                continue
            imb = (bq - aq) / (bq + aq) if (bq + aq) > 0 else 0.0
            series[(row["index"], row["strike"], row["optionType"])].append((ts, ltp, oi, imb))
    acc = defaultdict(E.new_acc)
    for key, pts in series.items():
        idx = key[0]
        pts.sort(key=lambda x: x[0])
        ts = [p[0] for p in pts]
        for j in range(len(pts)):
            # OI velocity over trailing window
            jb = _idx_at_or_before(ts, ts[j] - trail_sec)
            if jb is None or jb == j:
                continue
            oivel = pts[j][2] - pts[jb][2]
            imb = pts[j][3]
            # signal: OI build (rising OI) with positive queue imbalance => bullish for the option's premium
            d = _sgn(oivel) if abs(oivel) > 0 else 0
            d_imb = _sgn(imb)
            if d == 0:
                continue
            for sname, direction in (("oi_velocity", d), ("queue_imb", d_imb), ("oivel_x_imb", d if d == d_imb else 0)):
                if direction == 0:
                    continue
                for H in horizons_sec:
                    jf = _idx_at_or_after(ts, ts[j] + H)
                    if jf is None:
                        continue
                    p0, p1 = pts[j][1], pts[jf][1]
                    if not (p0 > 0 and p1 > 0):
                        continue
                    signed = direction * (p1 - p0) / p0 * 100.0
                    a = acc[(sname, H, "all", "all")]
                    a["n"] += 1
                    a["hits"] += 1 if signed > 0 else 0
                    a["sret"] += signed
                    a["sret2"] += signed * signed
    return {idx_from_csv(csv_path): acc}, rows


def idx_from_csv(p):
    return "MICRO"  # micro CSV mixes indices in the 'index' column; we pool under MICRO study


def _idx_at_or_before(ts, target):
    lo, hi, res = 0, len(ts) - 1, None
    while lo <= hi:
        m = (lo + hi) // 2
        if ts[m] <= target:
            res = m; lo = m + 1
        else:
            hi = m - 1
    return res


def _idx_at_or_after(ts, target):
    lo, hi, res = 0, len(ts) - 1, None
    while lo <= hi:
        m = (lo + hi) // 2
        if ts[m] >= target:
            res = m; hi = m - 1
        else:
            lo = m + 1
    return res


# ── cumulative store ──────────────────────────────────────────────────────────────────────
def append_cumulative(study, date, acc_by_ul, path=CASE_CUM):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    existing = []
    if os.path.exists(path):
        with open(path, newline="") as fh:
            existing = [r for r in csv.DictReader(fh)
                        if not (r.get("date") == date and r.get("study") == study)]
    with open(path, "w", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=CUM_FIELDS)
        w.writeheader()
        for r in existing:
            w.writerow({k: r.get(k, "") for k in CUM_FIELDS})
        for ul, acc in acc_by_ul.items():
            for (sname, h, reg, tod), a in sorted(acc.items()):
                w.writerow({"date": date, "study": study, "underlying": ul, "signal": sname,
                            "horizon": h, "regime": reg, "tod": tod,
                            "n": a["n"], "hits": a["hits"], "sret": a["sret"], "sret2": a["sret2"],
                            "no": a["no"], "owins": a["owins"], "snet": a["snet"],
                            "snet2": a["snet2"], "snetrs": a["snetrs"]})


def all_dates(snapshot_dir):
    if not os.path.isdir(snapshot_dir):
        return []
    return sorted(d for d in os.listdir(snapshot_dir)
                  if os.path.isdir(os.path.join(snapshot_dir, d)) and d[:4].isdigit())


# ── report with multiple-testing + persistence ────────────────────────────────────────────
def _day_level_tstat(perdate_combo):
    """Honest t-stat: one observation per DAY (the day's mean net per signal), so autocorrelated
    intra-day overlapping windows can't inflate it. t = mean(dailyMeans) / (sd/sqrt(#days))."""
    means = [s / n for (s, n) in perdate_combo.values() if n > 0]
    k = len(means)
    if k < 2:
        return float("nan")
    m = sum(means) / k
    var = sum((x - m) ** 2 for x in means) / (k - 1)
    if var <= 0:
        return float("nan")
    return m / (math.sqrt(var) / math.sqrt(k))


def report(path=CASE_CUM, out_root=CASE_ROOT):
    if not os.path.exists(path):
        print("[case-studies] no cumulative store at %s" % path)
        return
    # combo -> date -> [net_sum, opt_count]. We keep BOTH so we can compute the honest DAY-LEVEL t-stat
    # (one observation per day = the daily mean net), instead of the overlapping-window t across every
    # intra-day signal. Overlapping windows are autocorrelated, so the old t was inflated by ~sqrt(N):
    # sub-minute MICRO/FLOW combos showed t=300-587 on millions of rows yet held on 0 days. Day-level t
    # collapses those to ~1 and only rewards signals that repeat across DISTINCT days.
    perdate = defaultdict(lambda: defaultdict(lambda: [0.0, 0]))
    pooled = defaultdict(E.new_acc)
    days = set()
    with open(path, newline="") as fh:
        for r in csv.DictReader(fh):
            combo = (r["study"], r["underlying"], r["signal"], int(r["horizon"]), r["regime"], r["tod"])
            days.add(r["date"])
            a = pooled[combo]
            for k in ("n", "hits", "no", "owins"):
                a[k] += int(float(r[k] or 0))
            for k in ("sret", "sret2", "snet", "snet2", "snetrs"):
                a[k] += float(r[k] or 0)
            no_d = int(float(r["no"] or 0))
            if no_d > 0:
                pd = perdate[combo][r["date"]]
                pd[0] += float(r["snet"] or 0); pd[1] += no_d

    # MICRO/FLOW are sub-minute monitoring studies that have produced 0 persistent edges over the whole
    # sample (they re-confirm the settled "sub-minute isn't tradeable after costs" finding) — exclude
    # them from the edge search so their thousands of horizon-variants don't inflate the Bonferroni
    # surface and bury the chain/cross signals. They still get pooled + printed for monitoring.
    EDGE_STUDIES = ("chain", "cross")
    edge_combos = [c for c in pooled if c[0] in EDGE_STUDIES]
    # Bonferroni surface = the edge hypotheses only. Headline (all/all) for the unconditional test.
    headline = [c for c in edge_combos if c[4] == "all" and c[5] == "all"]
    M = max(1, len(headline))
    z_crit = _z_for_two_sided(ALPHA / M)
    M_all = max(1, len(edge_combos))
    z_crit_all = _z_for_two_sided(ALPHA / M_all)
    MIN_COND_DAYS = 10
    WATCH_T = 2.0   # shadow-worthy day-level significance (raw 95%); NOT proven — just worth tracking

    # TWO tiers so the report is both VALID and USEFUL:
    #  • candidate_edge = day-level t survives Bonferroni + persistence + net>0 → "proven enough to
    #    consider promoting". Honestly ~empty at 27 days — that's correct, not a failure.
    #  • watch = day-level t≥2 + persistence + net>0 + ≥10 days → the SHADOW watchlist (worth tracking
    #    live, no orders). This is the actionable output while the sample is still young.
    rows = []
    for combo in sorted(pooled):
        st, ul, sg, h, reg, tod = combo
        a = pooled[combo]
        no = a["no"]
        opt_t_overlap = E.tstat(no, a["snet"], a["snet2"])   # legacy: inflated by overlapping windows
        opt_t = _day_level_tstat(perdate[combo])             # HONEST: across days — used for all gates
        ndays = len(perdate[combo]); pos_days = sum(1 for (s, n) in perdate[combo].values() if s > 0)
        persist = (ndays >= 2 and pos_days / ndays >= 0.6)
        net_mean = (a["snet"] / no if no else 0)
        is_edge = st in EDGE_STUDIES
        is_headline = combo in headline
        t_ok = (not math.isnan(opt_t))
        at = abs(opt_t) if t_ok else 0.0
        sig_bonf = is_edge and is_headline and at >= z_crit
        sig_raw = is_edge and t_ok and at >= 1.96
        base = is_edge and persist and net_mean > 0 and ndays >= (2 if is_headline else MIN_COND_DAYS)
        # proven candidate (strict Bonferroni, day-level)
        head_cand = base and is_headline and at >= z_crit
        cond_cand = base and (not is_headline) and at >= z_crit_all
        candidate = head_cand or cond_cand
        # shadow watchlist (lower, honest bar) — excludes anything already a proven candidate
        watch = base and (not candidate) and at >= WATCH_T
        cand_tier = "headline" if head_cand else ("conditional" if cond_cand else ("watch" if watch else ""))
        rows.append({
            "study": st, "underlying": ul, "signal": sg, "horizon_min": (h if st in ("MICRO", "FLOW") else h * 5),
            "regime": reg, "tod": tod, "n_opt": no,
            "opt_winrate": round(a["owins"] / no, 4) if no else 0,
            "opt_mean_net_pct": round(net_mean, 4),
            "opt_mean_net_rs": round(a["snetrs"] / no, 0) if no else 0,
            "opt_t": round(opt_t, 2) if opt_t == opt_t else 0,
            "opt_t_overlap": round(opt_t_overlap, 2) if opt_t_overlap == opt_t_overlap else 0,
            "days": ndays, "pos_days": pos_days,
            "raw_sig": "Y" if sig_raw else "", "bonf_sig": "Y" if sig_bonf else "",
            "persistent": "Y" if persist else "",
            "candidate_edge": "Y" if candidate else "",
            "watch": "Y" if watch else "",
            "cand_tier": cand_tier})
    os.makedirs(out_root, exist_ok=True)
    outp = os.path.join(out_root, "matrix_report.csv")
    cols = ["study", "underlying", "signal", "horizon_min", "regime", "tod", "n_opt", "opt_winrate",
            "opt_mean_net_pct", "opt_mean_net_rs", "opt_t", "opt_t_overlap", "days", "pos_days",
            "raw_sig", "bonf_sig", "persistent", "candidate_edge", "watch", "cand_tier"]
    with open(outp, "w", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=cols); w.writeheader(); w.writerows(rows)

    print("[case-studies] MATRIX REPORT  days=%d  headline-combos M=%d  Bonferroni alpha=%.5f (|z|>=%.2f)"
          % (len(days), M, ALPHA / M, z_crit))
    print("Multiple-testing guardrail: with M combos, expect ~%.1f false 'raw-significant' hits by chance."
          % (M * 0.05))
    raw = sum(1 for r in rows if r["raw_sig"] == "Y" and r["regime"] == "all" and r["tod"] == "all")
    bonf_n = sum(1 for r in rows if r["bonf_sig"] == "Y")
    cand = sum(1 for r in rows if r["candidate_edge"] == "Y")
    print("Headline: raw-significant=%d  bonferroni-significant=%d  candidate-edge(persistent+bonf+net>0)=%d"
          % (raw, bonf_n, cand))
    head = [r for r in rows if r["regime"] == "all" and r["tod"] == "all" and r["n_opt"] > 0]
    head.sort(key=lambda r: r["opt_mean_net_pct"], reverse=True)
    print("\nTop 12 by net option expectancy (regime=all,tod=all):")
    print("%-7s %-9s %-18s %5s %5s %7s %6s %5s %5s %5s %5s" %
          ("study", "undl", "signal", "hMin", "Nopt", "net%", "t", "days", "+days", "bonf", "cand"))
    for r in head[:12]:
        print("%-7s %-9s %-18s %5s %5d %7.3f %6.2f %5d %5d %5s %5s" % (
            r["study"], r["underlying"], r["signal"], r["horizon_min"], r["n_opt"],
            r["opt_mean_net_pct"], r["opt_t"], r["days"], r["pos_days"], r["bonf_sig"], r["candidate_edge"]))
    print("\n-> matrix_report.csv written. NOTE: 'candidate_edge' still needs MORE days; nothing here is")
    print("   tradable until it persists out-of-sample. t-stats are now DAY-LEVEL (one obs/day — honest);")
    print("   opt_t_overlap keeps the old inflated intra-day t for reference only.")


def _z_for_two_sided(alpha):
    # inverse normal via rational approx (Acklam) for the (1 - alpha/2) quantile
    p = 1 - alpha / 2.0
    a = [-3.969683028665376e+01, 2.209460984245205e+02, -2.759285104469687e+02,
         1.383577518672690e+02, -3.066479806614716e+01, 2.506628277459239e+00]
    b = [-5.447609879822406e+01, 1.615858368580409e+02, -1.556989798598866e+02,
         6.680131188771972e+01, -1.328068155288572e+01]
    c = [-7.784894002430293e-03, -3.223964580411365e-01, -2.400758277161838e+00,
         -2.549732539343734e+00, 4.374664141464968e+00, 2.938163982698783e+00]
    d = [7.784695709041462e-03, 3.224671290700398e-01, 2.445134137142996e+00, 3.754408661907416e+00]
    plow, phigh = 0.02425, 1 - 0.02425
    if p < plow:
        q = math.sqrt(-2 * math.log(p))
        return (((((c[0]*q+c[1])*q+c[2])*q+c[3])*q+c[4])*q+c[5]) / ((((d[0]*q+d[1])*q+d[2])*q+d[3])*q+1)
    if p <= phigh:
        q = p - 0.5; r = q*q
        return (((((a[0]*r+a[1])*r+a[2])*r+a[3])*r+a[4])*r+a[5])*q / (((((b[0]*r+b[1])*r+b[2])*r+b[3])*r+b[4])*r+1)
    q = math.sqrt(-2 * math.log(1 - p))
    return -(((((c[0]*q+c[1])*q+c[2])*q+c[3])*q+c[4])*q+c[5]) / ((((d[0]*q+d[1])*q+d[2])*q+d[3])*q+1)



ARCHIVE_BASE = "data/tuning/microstructure-archive"


def micro_day_partitions(base=ARCHIVE_BASE):
    """Yield (date_str, parquet_glob) per day in the rolled microstructure archive."""
    out = []
    for d in sorted(_glob.glob(os.path.join(base, "year=*", "month=*", "day=*"))):
        parts = {kv.split("=")[0]: kv.split("=")[1] for kv in d.split(os.sep) if "=" in kv}
        if not {"year", "month", "day"} <= parts.keys():
            continue
        date = "%s-%s-%s" % (parts["year"], parts["month"].zfill(2), parts["day"].zfill(2))
        g = os.path.join(d, "index=*", "*.parquet")
        if _glob.glob(g):
            out.append((date, g))
    return out


def run_flow_archive():
    if FS is None:
        print("[case-studies] flow tier needs duckdb. Skipping."); return
    parts = micro_day_partitions()
    if not parts:
        print("[case-studies] no microstructure archive partitions for flow."); return
    for date, g in parts:
        try:
            FS.run(g, date, CASE_CUM, append_cumulative)
        except Exception as e:  # noqa: BLE001
            print("[case-studies] flow %s failed: %s" % (date, repr(e)[:160]))


def run_micro_archive():
    if MS is None:
        print("[case-studies] micro tier needs duckdb (pip install duckdb). Skipping.")
        return
    parts = micro_day_partitions()
    if not parts:
        print("[case-studies] no microstructure archive partitions found.")
        return
    for date, g in parts:
        try:
            MS.run(g, date, CASE_CUM, append_cumulative)
        except Exception as e:  # noqa: BLE001
            print("[case-studies] micro %s failed: %s" % (date, repr(e)[:160]))


def main():
    ap = argparse.ArgumentParser(description="Run feasible edge case-studies with a multiple-testing guardrail.")
    ap.add_argument("--mode", choices=["chain", "cross", "micro", "micro_archive", "flow", "h2", "report", "all"], default="all")
    ap.add_argument("--snapshot-dir", default="data/chain-snapshots")
    ap.add_argument("--underlyings", default="NIFTY,BANKNIFTY,SENSEX")
    ap.add_argument("--micro-csv", default=None)
    ap.add_argument("--max-rows", type=int, default=2_000_000)
    args = ap.parse_args()
    uls = [u.strip().upper() for u in args.underlyings.split(",") if u.strip()]

    if args.mode in ("chain", "all"):
        for date in all_dates(args.snapshot_dir):
            acc_by_ul = {}
            for ul in uls:
                snaps = E.load_day(args.snapshot_dir, date, ul)
                if len(snaps) >= 7:
                    acc_by_ul[ul] = study_chain(snaps, ul)
            if acc_by_ul:
                append_cumulative("chain", date, acc_by_ul)
        print("[case-studies] chain study done over %d dates." % len(all_dates(args.snapshot_dir)))

    if args.mode in ("cross", "all"):
        for date in all_dates(args.snapshot_dir):
            snaps_by_ul = {ul: E.load_day(args.snapshot_dir, date, ul) for ul in uls}
            snaps_by_ul = {k: v for k, v in snaps_by_ul.items() if len(v) >= 7}
            if "NIFTY" in snaps_by_ul and len(snaps_by_ul) >= 2:
                acc = study_cross(snaps_by_ul, leader="NIFTY")
                if acc:
                    append_cumulative("cross", date, {"XIDX": acc})
        print("[case-studies] cross-index study done.")

    if args.mode == "micro":
        if MS is not None and args.micro_csv:
            date = os.path.basename(args.micro_csv).replace("atm-microstructure-", "").replace(".csv", "")
            MS.run(args.micro_csv, date, CASE_CUM, append_cumulative)
        elif MS is not None:
            run_micro_archive()
        elif args.micro_csv:   # stdlib fallback (no duckdb)
            date = os.path.basename(args.micro_csv).replace("atm-microstructure-", "").replace(".csv", "")
            acc_by_ul, rows = study_micro(args.micro_csv, max_rows=args.max_rows)
            append_cumulative("MICRO", date, acc_by_ul)
            print("[case-studies] (stdlib) micro on %s (%d rows)." % (args.micro_csv, rows))
        else:
            print("[case-studies] no duckdb and no --micro-csv; nothing to do.")

    if args.mode == "micro_archive":
        run_micro_archive()

    if args.mode == "flow":
        run_flow_archive()

    if args.mode == "h2":
        if H2 is not None:
            try:
                rows = H2._rows_from_jdbc(); print("[h2-attrib] %d closed trades." % len(rows))
                H2.write_report(H2.attribute(rows))
            except Exception as e:  # noqa: BLE001
                print("[h2-attrib] JDBC unavailable: %s -- run tools/edge-study/h2_attrib.py --csv as fallback." % repr(e)[:160])
        else:
            print("[case-studies] h2_attrib module unavailable.")

    # NOTE: micro_archive + flow_archive are NO LONGER part of --mode all. They scan millions of
    # sub-minute rows (the daily timeout) and have produced 0 persistent edges over the whole sample —
    # they're monitoring-only and excluded from the edge search. Run them explicitly (--mode micro_archive
    # / --mode flow), which run-research.sh only does in the weekly "full" tier.
    if args.mode in ("report", "all"):
        report()


if __name__ == "__main__":
    main()
