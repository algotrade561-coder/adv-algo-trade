#!/usr/bin/env python3
"""
h2_attrib.py — GROUND-TRUTH realized net-P&L attribution from the trading app's H2 DB (read-only).

Reads TRADE_ENTITY (closed trades) via JDBC (JayDeBeApi + the H2 jar) and computes realized P&L per
strategy x day, NET of costs (the charges model mirrors ZerodhaChargesCalculator). NOTE: the app books
TRADE_ENTITY.REALIZED_PNL as GROSS (no charges) — we subtract estimated round-trip charges here to get net.

This is the ground truth ("which strategy actually made money") to cross-check the signal studies.

CONNECTION (config via env; clean fallbacks):
  GV_H2_DB   default data/adv-algo-trade         (file base, no extension)
  GV_H2_JAR  default allpack/h2-2.3.232.jar
On the EC2 box with the app RUNNING, the URL `...;AUTO_SERVER=TRUE` joins the live server (safe read).
Offline (app stopped) it falls back to a lock-free read; if a stale lock blocks it, copy the .mv.db to a
scratch path and point GV_H2_DB there. If JDBC isn't usable at all, pass --csv <trades.csv> (exported
columns: strategy_type,underlying,entry_price,exit_price,quantity,realized_pnl,exit_time,status).
"""
from __future__ import annotations

import argparse
import csv
import os
import sys
from collections import defaultdict
from datetime import datetime

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import edge_study as E  # round_trip_charges, LOT_SIZE

H2_DB = os.environ.get("GV_H2_DB", "data/adv-algo-trade")
H2_JAR = os.environ.get("GV_H2_JAR", "allpack/h2-2.3.232.jar")
OUT = "reports/case-studies/h2_attribution.csv"

SELECT = ("SELECT STRATEGY_TYPE, UNDERLYING, ENTRY_PRICE, EXIT_PRICE, QUANTITY, REALIZED_PNL, "
          "EXIT_TIME, TRADE_ID FROM TRADE_ENTITY WHERE STATUS = 'CLOSED'")


def _connect_jdbc():
    import jaydebeapi
    base = H2_DB if os.path.isabs(H2_DB) else "./" + H2_DB   # don't prepend ./ to an absolute path
    urls = [
        "jdbc:h2:file:%s;AUTO_SERVER=TRUE" % base,                       # EC2: app running -> join server
        "jdbc:h2:file:%s;ACCESS_MODE_DATA=r;IFEXISTS=TRUE" % base,       # offline read
        "jdbc:h2:file:%s;FILE_LOCK=NO;ACCESS_MODE_DATA=r;IFEXISTS=TRUE" % base,
    ]
    last = None
    for url in urls:
        try:
            return jaydebeapi.connect("org.h2.Driver", url, ["sa", ""], H2_JAR)
        except Exception as e:  # noqa: BLE001
            last = e
    raise RuntimeError("H2 connect failed (tried AUTO_SERVER + read-only). Last: %s\n"
                       "If a stale lock blocks it, copy the .mv.db to a scratch dir and set GV_H2_DB, "
                       "or use --csv. " % repr(last)[:200])


def _rows_from_jdbc():
    conn = _connect_jdbc()
    try:
        cur = conn.cursor()
        cur.execute(SELECT)
        out = cur.fetchall()
        cur.close()
    finally:
        conn.close()
    rows = []
    for r in out:
        rows.append({"strategy_type": r[0], "underlying": r[1], "entry_price": r[2],
                     "exit_price": r[3], "quantity": r[4], "realized_pnl": r[5], "exit_time": r[6],
                     "trade_id": r[7]})
    return rows


def _rows_from_csv(path):
    with open(path, newline="") as fh:
        return list(csv.DictReader(fh))


def _f(v):
    try:
        return float(v)
    except (TypeError, ValueError):
        return 0.0


def _date_of(v):
    s = str(v)
    return s[:10] if len(s) >= 10 else "unknown"


def attribute(rows):
    by = defaultdict(lambda: {"n": 0, "gross": 0.0, "charges": 0.0, "net": 0.0, "wins": 0})
    for r in rows:
        strat = r.get("strategy_type")
        if not strat:
            # 2026-07-03 (D1): a null strategy_type is almost always a MANUAL/broker-synced trade (the
            # position-sync tags those tradeIds SYNC-*). Label them MANUAL/SYNC so the ground-truth net
            # P&L makes clear the big bleed is the manual book, not untagged BOT trades. Genuinely untagged
            # bot trades (no SYNC- prefix) stay UNKNOWN so they still stand out for investigation.
            tid = str(r.get("trade_id") or "")
            strat = "MANUAL/SYNC" if tid.startswith("SYNC-") else "UNKNOWN"
        day = _date_of(r.get("exit_time"))
        entry, exit_, qty = _f(r.get("entry_price")), _f(r.get("exit_price")), int(_f(r.get("quantity")))
        gross = _f(r.get("realized_pnl"))
        ch = E.round_trip_charges(entry, exit_, qty) if (entry > 0 and exit_ > 0 and qty > 0) else 0.0
        net = gross - ch
        k = (strat, day)
        a = by[k]
        a["n"] += 1
        a["gross"] += gross
        a["charges"] += ch
        a["net"] += net
        a["wins"] += 1 if net > 0 else 0
    return by


def write_report(by):
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["strategy", "date", "trades", "gross_pnl", "est_charges", "net_pnl", "win_rate"])
        for (strat, day), a in sorted(by.items()):
            w.writerow([strat, day, a["n"], round(a["gross"], 0), round(a["charges"], 0),
                        round(a["net"], 0), round(a["wins"] / a["n"], 3) if a["n"] else 0])
    # pooled per strategy
    perstrat = defaultdict(lambda: {"n": 0, "gross": 0.0, "charges": 0.0, "net": 0.0, "wins": 0, "days": set()})
    for (strat, day), a in by.items():
        p = perstrat[strat]
        for k in ("n", "gross", "charges", "net", "wins"):
            p[k] += a[k]
        p["days"].add(day)
    print("[h2-attrib] GROUND-TRUTH realized P&L per strategy (NET = booked gross - est charges):")
    print("%-20s %6s %5s %12s %12s %12s %6s" % ("strategy", "trades", "days", "gross", "est_charges", "net", "win%"))
    for strat, p in sorted(perstrat.items(), key=lambda kv: kv[1]["net"]):
        print("%-20s %6d %5d %12.0f %12.0f %12.0f %6.0f" % (
            strat, p["n"], len(p["days"]), p["gross"], p["charges"], p["net"],
            (p["wins"] / p["n"] * 100) if p["n"] else 0))
    print("-> %s" % OUT)


def main():
    ap = argparse.ArgumentParser(description="Realized net-P&L attribution from H2 (ground truth).")
    ap.add_argument("--csv", default=None, help="fallback: a CSV export of closed trades")
    args = ap.parse_args()
    if args.csv:
        rows = _rows_from_csv(args.csv)
        print("[h2-attrib] using CSV fallback: %s (%d rows)" % (args.csv, len(rows)))
    else:
        try:
            rows = _rows_from_jdbc()
            print("[h2-attrib] read %d closed trades from H2 (%s)" % (len(rows), H2_DB))
        except Exception as e:  # noqa: BLE001
            print("[h2-attrib] JDBC unavailable: %s" % e)
            print("[h2-attrib] -> install JayDeBeApi + ensure %s exists, or pass --csv. Skipping." % H2_JAR)
            return
    if not rows:
        print("[h2-attrib] no closed trades found.")
        return
    write_report(attribute(rows))


if __name__ == "__main__":
    main()
