"""
exit_study.py — which EXIT rule keeps the most net P&L on the SAME entries?

Aims research squarely at the live give-back leak: the bot wins >50% of trades yet loses money
(OI_MOMENTUM: 55% win, −₹20k) because the losers are bigger than the winners — i.e. the EXIT, not the
entry, is the problem. The entry studies (chain/cross) never test exits. This does.

For every entry where a base signal fires (default: spot_mom in a TREND regime), we buy the ATM option
in the signal direction, then simulate the SAME trade under several exit rules and compare realized net:

  FIXED_<m>m : hold to +MAXH snapshots (baseline — what a fixed-horizon exit gets)
  TARGET_<t> : exit the first time premium >= entry*(1+t)      (book winners early)
  TRAIL_<x>  : exit when premium <= running-peak*(1-x)          (lock gains, let it run)
  STOP_<s>   : hard stop at entry*(1-s), else hold to MAXH      (cut losers)

Reports per rule: n, win%, avg net%, and avg GIVE-BACK (peak − realized) — the exact quantity the live
book is bleeding. If TRAIL/TARGET beat FIXED on net, that's direct evidence for how to change the live
exit. No-lookahead: the premium path is strictly AFTER entry; entry@ask, exit@bid, net of real charges.

Standalone; reuses edge_study primitives. Writes reports/case-studies/exit_study.csv.
"""
import csv, os, sys
import edge_study as E

MAXH = 6                        # look up to +30 min (6 × 5-min snapshots) for an exit
TARGETS = [0.15, 0.25, 0.40]    # +15 / +25 / +40 % premium
TRAILS = [0.15, 0.25, 0.40]
STOPS = [0.15, 0.25]
ENTRY_FLOOR = 20.0              # ₹ — same guard as case_studies: below this the %-denominator blows up
NET_PCT_CAP = 200.0            # winsorize per-trade net% so a few expiry-day explosions can't dominate
BASE_SIGNAL = os.environ.get("EXIT_BASE_SIGNAL", "spot_mom")
BASE_REGIME = os.environ.get("EXIT_BASE_REGIME", "trend")
OUT = "reports/case-studies/exit_study.csv"


def _exit_prem(snaps, j, side):
    """What we'd RECEIVE selling the ATM option now (bid, or LTP−slippage)."""
    return E.atm_option_price(snaps[j], side, "exit")


def simulate(snaps, i, side, ul):
    """Return {rule: (net_pct, give_back_pct)} for one entry at snapshot i, or None."""
    entry = E.atm_option_price(snaps[i], side, "entry")
    if entry != entry or entry < ENTRY_FLOOR:   # skip implausibly-small premiums (denominator blow-up)
        return None
    qty = E.LOT_SIZE.get(ul.upper(), 50)
    path = []
    for j in range(i + 1, min(i + 1 + MAXH, len(snaps))):
        p = _exit_prem(snaps, j, side)
        if p == p and p > 0:
            path.append(p)
    if not path:
        return None
    peak_prem = max(path)

    def net(exit_px):
        charges = E.round_trip_charges(entry, exit_px, qty)
        r = ((exit_px - entry) * qty - charges) / (entry * qty) * 100.0
        return max(-NET_PCT_CAP, min(NET_PCT_CAP, r))   # winsorize the tail

    peak_net = net(peak_prem)  # best net achievable — give-back is measured against this
    out = {}

    def record(rule, exit_px):
        r = net(exit_px)
        out[rule] = (r, max(0.0, peak_net - r))   # give-back never negative

    record("FIXED_%dm" % (MAXH * 5), path[-1])
    for t in TARGETS:
        ex = next((p for p in path if p >= entry * (1 + t)), path[-1])
        record("TARGET_%d" % int(t * 100), ex)
    for x in TRAILS:
        peak, ex = entry, path[-1]
        for p in path:
            peak = max(peak, p)
            if p <= peak * (1 - x):
                ex = p
                break
        record("TRAIL_%d" % int(x * 100), ex)
    for s in STOPS:
        ex = next((p for p in path if p <= entry * (1 - s)), path[-1])
        record("STOP_%d" % int(s * 100), ex)
    return out


def run(snapshot_dir="data/chain-snapshots", uls=("NIFTY", "BANKNIFTY", "SENSEX")):
    if not os.path.isdir(snapshot_dir):
        print("[exit-study] no snapshot dir %s" % snapshot_dir)
        return
    dates = sorted(d for d in os.listdir(snapshot_dir) if os.path.isdir(os.path.join(snapshot_dir, d)))
    nets = {}      # rule -> list of net% (for mean + median)
    gbs = {}       # rule -> list of give-back%
    entries = 0
    for d in dates:
        for ul in uls:
            snaps = E.load_day(snapshot_dir, d, ul)
            if not snaps or len(snaps) < 8:
                continue
            for i in range(len(snaps)):
                sig = E.signals_at(snaps, i).get(BASE_SIGNAL, 0)
                if sig == 0 or E.regime_at(snaps, i) != BASE_REGIME:
                    continue
                res = simulate(snaps, i, "CE" if sig > 0 else "PE", ul)
                if not res:
                    continue
                entries += 1
                for rule, (netpct, gb) in res.items():
                    nets.setdefault(rule, []).append(netpct)
                    gbs.setdefault(rule, []).append(gb)

    def _median(v):
        if not v:
            return 0.0
        s = sorted(v); m = len(s) // 2
        return s[m] if len(s) % 2 else (s[m - 1] + s[m]) / 2.0

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    rows = []
    for rule, v in nets.items():
        n = len(v); wins = sum(1 for x in v if x > 0)
        rows.append({"exit_rule": rule, "n": n,
                     "win_pct": round(100.0 * wins / n, 1) if n else 0,
                     "avg_net_pct": round(sum(v) / n, 3) if n else 0,
                     "median_net_pct": round(_median(v), 3),
                     "avg_giveback_pct": round(sum(gbs[rule]) / n, 3) if n else 0})
    # rank by MEDIAN net — robust to the few remaining fat-tail winners; the rule that keeps the most
    # money on the typical trade is the one we'd want the live exit to emulate.
    rows.sort(key=lambda r: r["median_net_pct"], reverse=True)
    cols = ["exit_rule", "n", "win_pct", "avg_net_pct", "median_net_pct", "avg_giveback_pct"]
    with open(OUT, "w", newline="", encoding="utf-8") as fh:
        w = csv.DictWriter(fh, fieldnames=cols); w.writeheader(); w.writerows(rows)
    print("[exit-study] base=%s@%s  entries=%d  rules=%d -> %s" % (BASE_SIGNAL, BASE_REGIME, entries, len(rows), OUT))
    for r in rows:
        print("  %-12s n=%-5d win%%=%-5s medNet%%=%-7s avgNet%%=%-7s giveBack%%=%s"
              % (r["exit_rule"], r["n"], r["win_pct"], r["median_net_pct"], r["avg_net_pct"], r["avg_giveback_pct"]))


if __name__ == "__main__":
    base = sys.argv[1] if len(sys.argv) > 1 else "data/chain-snapshots"
    run(base)
