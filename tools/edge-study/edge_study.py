#!/usr/bin/env python3
"""
edge_study.py — no-lookahead edge study over the bot's option-chain snapshots.

Reads gzipped-JSON snapshots written by the trading app:
    {snapshot-dir}/<YYYY-MM-DD>/<UNDERLYING>_<HHmm>.json.gz
each with: timestamp, spot(=future), atmStrike, vix, expiry,
           strikes[] {strike, ceLTP, ceOI, ceOiChange, ceBid, ceAsk, peLTP, peOI, peOiChange, peBid, peAsk, ...}

For each snapshot i (NO LOOKAHEAD: signal uses data <= i; forward return strictly after i) it computes
candidate directional signals (+1 bullish / -1 bearish / 0 flat), forward SPOT returns at +1/+2/+3/+6
snapshots (=+5/+10/+15/+30 min), AND a tradability leg: simulate buying the ATM CE (bullish) / ATM PE
(bearish) at signal time and exiting at the horizon, NET OF COSTS (charges model mirrors the project's
ZerodhaChargesCalculator / OptionsChargesEstimator) + bid/ask or slippage haircut.

Outputs a per-day CSV + text report and appends sufficient-statistics rows to a cumulative store so
multi-day stats build up. `--rollup` pools the cumulative store and reports pooled hit-rate / expectancy
with a (descriptive) t-stat, flagging small samples.

Pure stdlib. Lightweight, idempotent, no daemon.
"""
from __future__ import annotations

import argparse
import csv
import glob
import gzip
import json
import math
import os
from collections import defaultdict
from datetime import datetime, timezone, timedelta

IST = timezone(timedelta(hours=5, minutes=30))
HORIZONS = [1, 2, 3, 6]            # snapshots; *5 min each
SIGNALS = ["oi_lean", "pcr_slope", "spot_mom", "range_fade"]  # + "oi_velocity" hook (returns 0 for now)
SMALL_SAMPLE_N = 30
SLIP_FALLBACK = 0.005             # 0.5% premium haircut when bid/ask missing

# Contract lot sizes (units per 1 lot). Configurable; mirror IndexType. Used for net-cost realism.
LOT_SIZE = {"NIFTY": 65, "BANKNIFTY": 35, "SENSEX": 20, "FINNIFTY": 65, "MIDCPNIFTY": 140}

# ── charges (mirror util/ZerodhaChargesCalculator.calculateRoundTrip, F&O options) ───────
BROKERAGE_PER_ORDER = 20.0
STT_SELL = 0.0015          # 0.15% sell premium
TXN_RATE = 0.0003553       # 0.03553% both sides
SEBI_RATE = 10.0 / 1e7     # Rs10 per crore
STAMP_BUY = 0.00003        # 0.003% buy
GST_RATE = 0.18            # on brokerage + sebi + txn


def round_trip_charges(buy_prem: float, sell_prem: float, qty: int) -> float:
    if qty <= 0 or buy_prem <= 0 or sell_prem <= 0:
        return 0.0
    buy_to, sell_to = buy_prem * qty, sell_prem * qty
    total_to = buy_to + sell_to
    brokerage = BROKERAGE_PER_ORDER * 2
    stt = sell_to * STT_SELL
    txn = total_to * TXN_RATE
    sebi = total_to * SEBI_RATE
    stamp = buy_to * STAMP_BUY
    gst = GST_RATE * (brokerage + sebi + txn)
    return brokerage + stt + txn + sebi + stamp + gst


def _num(v):
    try:
        return float(v)
    except (TypeError, ValueError):
        return float("nan")


# ── snapshot loading ─────────────────────────────────────────────────────────────────────
def load_day(snapshot_dir: str, date: str, underlying: str):
    """Return list of snapshots for date/underlying, ordered by filename HHmm (chronological)."""
    d = os.path.join(snapshot_dir, date)
    if not os.path.isdir(d):
        return []
    files = sorted(glob.glob(os.path.join(d, underlying.upper() + "_*.json.gz")))
    snaps = []
    for f in files:
        try:
            with gzip.open(f, "rt", encoding="utf-8") as fh:
                raw = json.load(fh)
        except Exception:
            continue
        base = os.path.basename(f)
        hhmm = base.split("_")[-1].split(".")[0]   # e.g. 0915
        strikes = {}
        sum_ce_oi = sum_pe_oi = sum_ce_chg = sum_pe_chg = 0.0
        for s in raw.get("strikes", []):
            k = _num(s.get("strike"))
            strikes[k] = s
            sum_ce_oi += _num(s.get("ceOI")) if not math.isnan(_num(s.get("ceOI"))) else 0
            sum_pe_oi += _num(s.get("peOI")) if not math.isnan(_num(s.get("peOI"))) else 0
            c = _num(s.get("ceOiChange")); sum_ce_chg += 0 if math.isnan(c) else c
            p = _num(s.get("peOiChange")); sum_pe_chg += 0 if math.isnan(p) else p
        snaps.append({
            "hhmm": hhmm,
            "minute": int(hhmm[:2]) * 60 + int(hhmm[2:]),
            "spot": _num(raw.get("spot")),
            "atm": _num(raw.get("atmStrike")),
            "vix": _num(raw.get("vix")),
            "strikes": strikes,
            "sumCeOi": sum_ce_oi, "sumPeOi": sum_pe_oi,
            "sumCeChg": sum_ce_chg, "sumPeChg": sum_pe_chg,
            "pcr": (sum_pe_oi / sum_ce_oi) if sum_ce_oi > 0 else float("nan"),
        })
    return snaps


# ── signals (data <= i only) ──────────────────────────────────────────────────────────────
def sign(x):
    return 1 if x > 0 else (-1 if x < 0 else 0)


def signals_at(snaps, i):
    out = {s: 0 for s in SIGNALS}
    cur = snaps[i]
    # OI lean: PE build > CE build => bullish
    out["oi_lean"] = sign(cur["sumPeChg"] - cur["sumCeChg"])
    # PCR slope: rising PCR => bullish
    if i >= 1 and not math.isnan(cur["pcr"]) and not math.isnan(snaps[i - 1]["pcr"]):
        out["pcr_slope"] = sign(cur["pcr"] - snaps[i - 1]["pcr"])
    # Spot momentum: 15m continuation
    if i >= 3 and cur["spot"] > 0 and snaps[i - 3]["spot"] > 0:
        out["spot_mom"] = sign(cur["spot"] - snaps[i - 3]["spot"])
    # Range-edge fade over trailing 6 snapshots (incl i)
    if i >= 5:
        window = [snaps[j]["spot"] for j in range(i - 5, i + 1) if snaps[j]["spot"] > 0]
        if len(window) >= 2:
            lo, hi = min(window), max(window)
            if hi > lo:
                pos = (cur["spot"] - lo) / (hi - lo)
                out["range_fade"] = -1 if pos > 0.8 else (1 if pos < 0.2 else 0)
    # out["oi_velocity"] = 0  # hook: wire microstructure OI-velocity here when available
    return out


def regime_at(snaps, i):
    """trend if trailing-30m range > 0.20% else range. Needs >=6 points."""
    if i < 5:
        return "unknown"
    window = [snaps[j]["spot"] for j in range(i - 5, i + 1) if snaps[j]["spot"] > 0]
    if len(window) < 2:
        return "unknown"
    lo, hi = min(window), max(window)
    rng = (hi - lo) / lo * 100 if lo > 0 else 0
    return "trend" if rng > 0.20 else "range"


def tod_at(snaps, i):
    m = snaps[i]["minute"]
    if m < 11 * 60:
        return "morn"
    if m < 14 * 60:
        return "mid"
    return "aft"


# ── option tradability leg ─────────────────────────────────────────────────────────────────
def atm_option_price(snap, side, leg):
    """side 'CE'/'PE'; leg 'entry'(buy@ask) or 'exit'(sell@bid). Fallback to LTP+/-slippage."""
    row = snap["strikes"].get(snap["atm"])
    if not row:
        # nearest strike to atm
        if not snap["strikes"]:
            return float("nan")
        k = min(snap["strikes"], key=lambda x: abs(x - snap["atm"]))
        row = snap["strikes"][k]
    pre = "ce" if side == "CE" else "pe"
    bid, ask, ltp = _num(row.get(pre + "Bid")), _num(row.get(pre + "Ask")), _num(row.get(pre + "LTP"))
    if leg == "entry":  # we BUY -> pay ask
        if ask > 0:
            return ask
        return ltp * (1 + SLIP_FALLBACK) if ltp > 0 else float("nan")
    else:               # we SELL -> receive bid
        if bid > 0:
            return bid
        return ltp * (1 - SLIP_FALLBACK) if ltp > 0 else float("nan")


def option_net(snaps, i, h, direction, underlying):
    """Net option P&L of buying ATM CE/PE at i, exiting at i+h. Returns (net_pct, net_rs) or None."""
    if direction == 0 or i + h >= len(snaps):
        return None
    side = "CE" if direction > 0 else "PE"
    entry = atm_option_price(snaps[i], side, "entry")
    exit_ = atm_option_price(snaps[i + h], side, "exit")
    if math.isnan(entry) or math.isnan(exit_) or entry <= 0:
        return None
    qty = LOT_SIZE.get(underlying.upper(), 50)
    gross_rs = (exit_ - entry) * qty
    charges = round_trip_charges(entry, exit_, qty)
    net_rs = gross_rs - charges
    net_pct = net_rs / (entry * qty) * 100.0
    return net_pct, net_rs


# ── accumulation ────────────────────────────────────────────────────────────────────────
def new_acc():
    return {"n": 0, "hits": 0, "sret": 0.0, "sret2": 0.0, "spts": 0.0,
            "no": 0, "owins": 0, "snet": 0.0, "snet2": 0.0, "snetrs": 0.0}


def tstat(n, s, s2):
    if n < 2:
        return float("nan")
    mean = s / n
    var = max(0.0, (s2 - n * mean * mean) / (n - 1))
    sd = math.sqrt(var)
    return mean / (sd / math.sqrt(n)) if sd > 0 else float("nan")


def study_day(snaps, underlying):
    """Return dict keyed (signal,horizon,regime,tod) -> acc, over one day's snapshots."""
    acc = defaultdict(new_acc)
    n = len(snaps)
    for i in range(n):
        sig = signals_at(snaps, i)
        reg = regime_at(snaps, i)
        tod = tod_at(snaps, i)
        for sname, d in sig.items():
            if d == 0:
                continue
            for h in HORIZONS:
                if i + h >= n:
                    continue
                si, sf = snaps[i]["spot"], snaps[i + h]["spot"]
                if not (si > 0 and sf > 0):
                    continue
                signed_pts = d * (sf - si)
                signed_pct = signed_pts / si * 100.0
                onet = option_net(snaps, i, h, d, underlying)
                for reg_key in (reg, "all"):
                    for tod_key in (tod, "all"):
                        a = acc[(sname, h, reg_key, tod_key)]
                        a["n"] += 1
                        a["hits"] += 1 if signed_pct > 0 else 0
                        a["sret"] += signed_pct
                        a["sret2"] += signed_pct * signed_pct
                        a["spts"] += signed_pts
                        if onet is not None:
                            np_, nr_ = onet
                            a["no"] += 1
                            a["owins"] += 1 if np_ > 0 else 0
                            a["snet"] += np_
                            a["snet2"] += np_ * np_
                            a["snetrs"] += nr_
    return acc


# ── reporting ───────────────────────────────────────────────────────────────────────────
CUM_FIELDS = ["date", "underlying", "signal", "horizon", "regime", "tod",
              "n", "hits", "sret", "sret2", "spts", "no", "owins", "snet", "snet2", "snetrs"]


def write_day_reports(acc_by_ul, date, out_root):
    out_dir = os.path.join(out_root, date)
    os.makedirs(out_dir, exist_ok=True)
    csv_path = os.path.join(out_dir, "edge_study_%s.csv" % date)
    txt_path = os.path.join(out_dir, "edge_study_%s.txt" % date)
    rows = []
    for ul, acc in acc_by_ul.items():
        for (sname, h, reg, tod), a in sorted(acc.items()):
            rows.append(_summary_row(date, ul, sname, h, reg, tod, a))
    cols = ["date", "underlying", "signal", "horizon_min", "regime", "tod", "n", "hit_rate",
            "mean_ret_pct", "mean_ret_pts", "t_stat", "n_opt", "opt_winrate",
            "opt_mean_net_pct", "opt_mean_net_rs", "opt_t_stat"]
    with open(csv_path, "w", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=cols)
        w.writeheader()
        w.writerows(rows)
    with open(txt_path, "w") as fh:
        fh.write("Edge study %s  (NO-LOOKAHEAD; t-stats DESCRIPTIVE — overlapping windows)\n" % date)
        fh.write("Signal trade = buy ATM CE/PE at signal, exit at horizon, NET of charges+slippage.\n\n")
        # headline: regime=all, tod=all
        for ul in sorted(acc_by_ul):
            fh.write("== %s ==\n" % ul)
            fh.write("%-11s %4s %6s %6s %8s %8s | %5s %8s %9s\n" %
                     ("signal", "hMin", "N", "hit%", "ret%", "tstat", "Nopt", "netOpt%", "netRs"))
            for r in rows:
                if r["underlying"] == ul and r["regime"] == "all" and r["tod"] == "all":
                    fh.write("%-11s %4d %6d %6.1f %8.3f %8.2f | %5d %8.2f %9.0f\n" % (
                        r["signal"], r["horizon_min"], r["n"], r["hit_rate"] * 100,
                        r["mean_ret_pct"], r["t_stat"], r["n_opt"],
                        r["opt_mean_net_pct"], r["opt_mean_net_rs"]))
            fh.write("\n")
    return csv_path, txt_path, rows


def _summary_row(date, ul, sname, h, reg, tod, a):
    n, no = a["n"], a["no"]
    return {
        "date": date, "underlying": ul, "signal": sname, "horizon_min": h * 5,
        "regime": reg, "tod": tod, "n": n,
        "hit_rate": round(a["hits"] / n, 4) if n else 0,
        "mean_ret_pct": round(a["sret"] / n, 4) if n else 0,
        "mean_ret_pts": round(a["spts"] / n, 2) if n else 0,
        "t_stat": round(tstat(n, a["sret"], a["sret2"]), 2),
        "n_opt": no,
        "opt_winrate": round(a["owins"] / no, 4) if no else 0,
        "opt_mean_net_pct": round(a["snet"] / no, 3) if no else 0,
        "opt_mean_net_rs": round(a["snetrs"] / no, 0) if no else 0,
        "opt_t_stat": round(tstat(no, a["snet"], a["snet2"]), 2),
    }


def append_cumulative(acc_by_ul, date, cum_path):
    os.makedirs(os.path.dirname(cum_path), exist_ok=True)
    exists = os.path.exists(cum_path)
    # idempotent: drop any existing rows for this date, then re-append
    existing = []
    if exists:
        with open(cum_path, newline="") as fh:
            existing = [r for r in csv.DictReader(fh) if r.get("date") != date]
    with open(cum_path, "w", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=CUM_FIELDS)
        w.writeheader()
        for r in existing:
            w.writerow({k: r.get(k, "") for k in CUM_FIELDS})
        for ul, acc in acc_by_ul.items():
            for (sname, h, reg, tod), a in sorted(acc.items()):
                w.writerow({"date": date, "underlying": ul, "signal": sname, "horizon": h,
                            "regime": reg, "tod": tod, "n": a["n"], "hits": a["hits"],
                            "sret": a["sret"], "sret2": a["sret2"], "spts": a["spts"],
                            "no": a["no"], "owins": a["owins"], "snet": a["snet"],
                            "snet2": a["snet2"], "snetrs": a["snetrs"]})


def rollup(cum_path, out_root):
    if not os.path.exists(cum_path):
        print("[edge-study] no cumulative store at %s — run a day first." % cum_path)
        return
    pooled = defaultdict(new_acc)
    days = set()
    with open(cum_path, newline="") as fh:
        for r in csv.DictReader(fh):
            days.add(r["date"])
            key = (r["underlying"], r["signal"], int(r["horizon"]), r["regime"], r["tod"])
            a = pooled[key]
            for k in ("n", "hits", "no", "owins"):
                a[k] += int(float(r[k]))
            for k in ("sret", "sret2", "spts", "snet", "snet2", "snetrs"):
                a[k] += float(r[k])
    out_dir = os.path.join(out_root, "_rollup")
    os.makedirs(out_dir, exist_ok=True)
    csv_path = os.path.join(out_dir, "edge_rollup.csv")
    cols = ["underlying", "signal", "horizon_min", "regime", "tod", "days", "n", "hit_rate",
            "mean_ret_pct", "t_stat", "n_opt", "opt_winrate", "opt_mean_net_pct",
            "opt_mean_net_rs", "opt_t_stat", "small_sample"]
    rows = []
    for (ul, sname, h, reg, tod), a in sorted(pooled.items()):
        n, no = a["n"], a["no"]
        rows.append({
            "underlying": ul, "signal": sname, "horizon_min": h * 5, "regime": reg, "tod": tod,
            "days": len(days), "n": n,
            "hit_rate": round(a["hits"] / n, 4) if n else 0,
            "mean_ret_pct": round(a["sret"] / n, 4) if n else 0,
            "t_stat": round(tstat(n, a["sret"], a["sret2"]), 2),
            "n_opt": no, "opt_winrate": round(a["owins"] / no, 4) if no else 0,
            "opt_mean_net_pct": round(a["snet"] / no, 3) if no else 0,
            "opt_mean_net_rs": round(a["snetrs"] / no, 0) if no else 0,
            "opt_t_stat": round(tstat(no, a["snet"], a["snet2"]), 2),
            "small_sample": "YES" if no < SMALL_SAMPLE_N else "",
        })
    with open(csv_path, "w", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=cols)
        w.writeheader()
        w.writerows(rows)
    print("[edge-study] rollup over %d day(s) -> %s" % (len(days), csv_path))
    print("Top net-expectancy signals (regime=all,tod=all, sorted by opt_mean_net_pct):")
    head = [r for r in rows if r["regime"] == "all" and r["tod"] == "all"]
    head.sort(key=lambda r: r["opt_mean_net_pct"], reverse=True)
    print("%-9s %-11s %5s %5s %6s %8s %8s %5s" %
          ("undl", "signal", "hMin", "Nopt", "win%", "net%", "netRs", "small"))
    for r in head[:15]:
        print("%-9s %-11s %5d %5d %6.1f %8.3f %8.0f %5s" % (
            r["underlying"], r["signal"], r["horizon_min"], r["n_opt"],
            r["opt_winrate"] * 100, r["opt_mean_net_pct"], r["opt_mean_net_rs"], r["small_sample"]))


def main():
    ap = argparse.ArgumentParser(description="No-lookahead edge study over chain snapshots.")
    ap.add_argument("--date", default=datetime.now(IST).strftime("%Y-%m-%d"))
    ap.add_argument("--snapshot-dir", default="data/chain-snapshots")
    ap.add_argument("--underlyings", default="NIFTY")
    ap.add_argument("--out-root", default="reports/edge-study")
    ap.add_argument("--cumulative", default="reports/edge-study/cumulative.csv")
    ap.add_argument("--rollup", action="store_true", help="aggregate the cumulative store and exit")
    args = ap.parse_args()

    if args.rollup:
        rollup(args.cumulative, args.out_root)
        return

    uls = [u.strip().upper() for u in args.underlyings.split(",") if u.strip()]
    acc_by_ul = {}
    for ul in uls:
        snaps = load_day(args.snapshot_dir, args.date, ul)
        if len(snaps) < 7:
            print("[edge-study] %s %s: only %d snapshots (<7) — skipped." % (args.date, ul, len(snaps)))
            continue
        acc_by_ul[ul] = study_day(snaps, ul)
        print("[edge-study] %s %s: %d snapshots studied." % (args.date, ul, len(snaps)))
    if not acc_by_ul:
        print("[edge-study] nothing to study for %s (dir=%s)." % (args.date, args.snapshot_dir))
        return
    csv_path, txt_path, _ = write_day_reports(acc_by_ul, args.date, args.out_root)
    append_cumulative(acc_by_ul, args.date, args.cumulative)
    print("[edge-study] wrote %s and %s" % (csv_path, txt_path))
    print("[edge-study] appended to cumulative store %s (use --rollup to pool)." % args.cumulative)


if __name__ == "__main__":
    main()
