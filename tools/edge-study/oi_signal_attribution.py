#!/usr/bin/env python3
"""
oi_signal_attribution.py — DATA COMPARISON for the OI-Momentum signal quality, joined outcome-level.

Joins the captured tuning events (reports/tuning/events/<date>/oi_momentum/{signal,exit}.csv) by
correlationKey and reports realized outcome (win% / avg realized P&L% / MAE) broken down by:
  - matrixCase        : CASE1/3 (OI-confirmed) vs CASE2/5 (no-OI) vs SPIKE/RANGE_FADE
  - momentumType      : EARLY_OI_VELOCITY vs OPERATOR_OI_LED vs SUSTAINED_DRIFT …
  - operatorAgeBucket : freshness of the fast-OI operator signal at entry (<=20s / 21-60s / >60s / n/a)
                        → the direct test of whether the 2026-07-01 fast-OI refresh (operatorSignalAgeSec)
                          converts into better outcomes.
  - fastOiRegime      : fastOiEnabled true (60s window, 07-01+) vs n/a (legacy pre-capture) — the A/B.
  - mtfAlignment      : entry vs day+week structure — aligned / counter-trend / neutral (07-01+). The
                        direct test of whether the multi-timeframe context predicts edge.
  - mtfRegime         : higher-timeframe regime at entry — TRENDING / RANGING / VOLATILE / NEUTRAL.

Writes reports/case-studies/oi_signal_attribution.csv (one row per dimension+group) which
build_research_report.py renders in the daily brief. Descriptive only — small samples are noisy;
this is a monitoring lens, not a green light. Requires duckdb; degrades to a no-op if missing/empty.
"""
import os, sys, csv

SIG = "reports/tuning/events/*/oi_momentum/signal.csv"
EXT = "reports/tuning/events/*/oi_momentum/exit.csv"
OUT = "reports/case-studies/oi_signal_attribution.csv"


def main():
    try:
        import duckdb
    except ImportError:
        print("[oi-attrib] duckdb missing — skipping"); return
    con = duckdb.connect()
    base = """
    WITH s AS (
      SELECT correlationKey,
             json_extract_string(attr_extra, '$.matrixCase')            AS matrixCase,
             json_extract_string(attr_extra, '$.momentumType')          AS momType,
             TRY_CAST(json_extract_string(attr_extra, '$.operatorSignalAgeSec') AS BIGINT) AS opAge,
             json_extract_string(attr_extra, '$.fastOiEnabled')         AS fastOi,
             TRY_CAST(json_extract_string(attr_extra, '$.oiWindowSec') AS BIGINT) AS oiWin,
             TRY_CAST(json_extract_string(attr_extra, '$.mtfAligned') AS BIGINT)  AS mtfAligned,
             json_extract_string(attr_extra, '$.mtfRegime')            AS mtfRegime
      FROM read_csv_auto('%s', union_by_name=true, ignore_errors=true)
    ),
    x AS (SELECT correlationKey, realizedPnlPct, holdSec, maePct, mfePct
          FROM read_csv_auto('%s', union_by_name=true, ignore_errors=true)),
    j AS (
      SELECT s.*, x.realizedPnlPct AS pnl, x.holdSec AS hold, x.maePct AS mae, x.mfePct AS mfe,
             CASE WHEN s.opAge IS NULL OR s.opAge < 0 THEN 'n/a'
                  WHEN s.opAge <= 20 THEN 'fresh<=20s'
                  WHEN s.opAge <= 60 THEN 'mid 21-60s'
                  ELSE 'stale>60s' END AS opAgeBucket,
             CASE WHEN s.fastOi = 'true' THEN 'fast-OI(60s)' ELSE 'legacy/n-a' END AS fastRegime,
             CASE WHEN s.mtfAligned = 1 THEN 'aligned'
                  WHEN s.mtfAligned = -1 THEN 'counter-trend'
                  WHEN s.mtfAligned = 0 THEN 'neutral'
                  ELSE 'n/a' END AS mtfAlignBucket,
             COALESCE(NULLIF(s.mtfRegime, ''), 'n/a') AS mtfRegimeBucket
      FROM s JOIN x USING(correlationKey)
    )
    """ % (SIG, EXT)

    def agg(dim_expr):
        q = base + """
        SELECT %s AS grp, COUNT(*) n,
          ROUND(AVG(CASE WHEN pnl>0 THEN 1.0 ELSE 0 END)*100,1) win_pct,
          ROUND(AVG(pnl),2) avg_pnl_pct, ROUND(SUM(pnl),1) sum_pnl_pct,
          ROUND(AVG(hold),0) avg_hold_s, ROUND(AVG(mae),2) avg_mae
        FROM j WHERE %s IS NOT NULL GROUP BY 1 ORDER BY n DESC
        """ % (dim_expr, dim_expr)
        try:
            return con.execute(q).fetchall()
        except Exception as e:
            print("[oi-attrib] agg failed for %s: %r" % (dim_expr, e)[:160]); return []

    dims = [("matrixCase", "matrixCase"), ("momentumType", "momType"),
            ("operatorAgeBucket", "opAgeBucket"), ("fastOiRegime", "fastRegime"),
            ("mtfAlignment", "mtfAlignBucket"), ("mtfRegime", "mtfRegimeBucket")]
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    total = 0
    with open(OUT, "w", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["dimension", "group", "n", "win_pct", "avg_pnl_pct", "sum_pnl_pct", "avg_hold_s", "avg_mae"])
        for label, expr in dims:
            for r in agg(expr):
                w.writerow([label] + list(r)); total += 1
    con.close()
    print("[oi-attrib] wrote %s (%d rows)" % (OUT, total))


if __name__ == "__main__":
    try:
        main()
    except Exception as e:
        print("[oi-attrib] non-fatal error: %r" % e); sys.exit(0)
