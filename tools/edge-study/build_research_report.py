#!/usr/bin/env python3
"""
build_research_report.py — turn the edge-study CSV outputs into a single readable HTML "decision brief"
plus a machine-readable summary.json, written to reports/research/<date>/.

Reads (all optional; degrades cleanly if missing):
  reports/case-studies/matrix_report.csv   (pooled combos + Bonferroni/persistence/candidate flags)
  reports/case-studies/cumulative.csv       (per-date sufficient statistics → coverage)
  reports/case-studies/h2_attribution.csv   (realized net P&L by strategy — ground truth)

Design goals: NEVER crash (the daily cron must always produce a report), and surface the
*decisions* (candidate edges, persisting hypotheses, realized P&L) not raw stats.
"""
import csv, json, os, sys, html, datetime, glob

CASE = "reports/case-studies"
OUT_ROOT = "reports/research"


def read_csv(path):
    if not os.path.exists(path):
        return []
    try:
        with open(path, newline="") as fh:
            return list(csv.DictReader(fh))
    except Exception as e:
        print(f"[research] WARN could not read {path}: {e}")
        return []


def num(x, d=0.0):
    try:
        return float(x)
    except Exception:
        return d


def esc(x):
    return html.escape("" if x is None else str(x))


def table(rows, cols, labels=None):
    """Render a list-of-dict subset as an HTML table. cols = keys to show."""
    if not rows:
        return "<p class='muted'><em>none</em></p>"
    labels = labels or {c: c for c in cols}
    th = "".join(f"<th>{esc(labels.get(c, c))}</th>" for c in cols)
    trs = []
    for r in rows:
        tds = "".join(f"<td>{esc(r.get(c, ''))}</td>" for c in cols)
        trs.append(f"<tr>{tds}</tr>")
    return f"<table><thead><tr>{th}</tr></thead><tbody>{''.join(trs)}</tbody></table>"


def summarize_xshadow(rows):
    """Aggregate the live cross-index shadow's completed virtual trades (NIFTY->SENSEX momentum)."""
    g = [num(r.get("grossPct")) for r in rows if str(r.get("grossPct", "")) not in ("", "None")]
    if not g:
        return None
    n = len(g); wins = sum(1 for x in g if x > 0)
    s = sorted(g); med = s[n // 2] if n % 2 else (s[n // 2 - 1] + s[n // 2]) / 2.0
    hold = [num(r.get("holdMin")) for r in rows if str(r.get("holdMin", "")) not in ("", "None")]
    days = len({str(r.get("entryEpochMs", ""))[:8] for r in rows})  # ~distinct entry-day buckets
    return {"n": n, "days": days, "win_pct": round(100.0 * wins / n, 1),
            "avg_gross_pct": round(sum(g) / n, 3), "median_gross_pct": round(med, 3),
            "avg_hold_min": round(sum(hold) / len(hold), 0) if hold else 0,
            "ce": sum(1 for r in rows if r.get("side") == "CE"),
            "pe": sum(1 for r in rows if r.get("side") == "PE")}


def build(date_str):
    matrix = read_csv(f"{CASE}/matrix_report.csv")
    cumulative = read_csv(f"{CASE}/cumulative.csv")
    h2 = read_csv(f"{CASE}/h2_attribution.csv")
    oi_attrib = read_csv(f"{CASE}/oi_signal_attribution.csv")  # OI-momentum signal→outcome comparison
    exit_study = read_csv(f"{CASE}/exit_study.csv")            # which EXIT rule keeps the most net
    xshadow = []                                              # cross-index shadow virtual trades (live, no orders)
    for _f in sorted(glob.glob("data/tuning/crossindex-shadow-*.csv")):
        xshadow += read_csv(_f)
    xshadow_sum = summarize_xshadow(xshadow)

    # ── coverage ──
    dates = sorted({r.get("date", "") for r in cumulative if r.get("date")})
    studies = sorted({r.get("study", "") for r in cumulative if r.get("study")})
    n_days = len(dates)
    date_range = f"{dates[0]} → {dates[-1]}" if dates else "—"

    # ── data-health / coverage detail (#8): per-day row counts + partial/blind-day flags, so a capture-gap
    # day (e.g. a restart-heavy session) is visible instead of silently pooled into the sample. ──
    per_day = {}
    for _r in cumulative:
        _d = _r.get("date", "")
        if _d:
            per_day[_d] = per_day.get(_d, 0) + 1
    per_day_sorted = sorted(per_day.items())
    _counts = sorted(per_day.values())
    _median = _counts[len(_counts) // 2] if _counts else 0
    blind_days = [d for d, c in per_day_sorted if _median > 0 and c < 0.5 * _median]
    _health_rows = "".join(
        f"<tr><td>{esc(d)}</td><td>{c}</td><td>{'&#9888; partial' if (_median > 0 and c < 0.5 * _median) else 'ok'}</td></tr>"
        for d, c in per_day_sorted)
    health_html = (
        f'<details class="note"><summary>Data health — {n_days} day(s) pooled, '
        f'{len(blind_days)} flagged partial/blind</summary>'
        f'<table><thead><tr><th>date</th><th>study-rows</th><th>status</th></tr></thead>'
        f'<tbody>{_health_rows}</tbody></table>'
        f'<p>Days with &lt;50% of the median row count are flagged partial — likely a capture gap / '
        f'restart-heavy session; their pooled contribution is thin, so treat pooled stats that lean on '
        f'them with caution.</p></details>')

    # ── guardrail counts (defensive on column names) ──
    def flag(r, k):
        return str(r.get(k, "")).strip().upper() in ("Y", "YES", "TRUE", "1")

    # Dedup to ONE row per signal-family (study,signal,regime,tod,underlying) — keep the best by
    # day-level |t|. The matrix has one row per horizon, so a single signal spawns 4 correlated rows;
    # collapsing them declutters the report and stops horizon-variants inflating the counts.
    def dedup_family(rows):
        best = {}
        for r in rows:
            k = (r.get("study"), r.get("signal"), r.get("regime"), r.get("tod"), r.get("underlying"))
            if k not in best or abs(num(r.get("opt_t"))) > abs(num(best[k].get("opt_t"))):
                best[k] = r
        out = list(best.values())
        out.sort(key=lambda r: (num(r.get("pos_days")) / (num(r.get("days"), 1) or 1),
                                abs(num(r.get("opt_t")))), reverse=True)
        return out

    # Edge search is chain+cross only (MICRO/FLOW are monitoring, excluded upstream).
    edge_rows = [r for r in matrix if str(r.get("study")) in ("chain", "cross")]
    candidates = dedup_family([r for r in edge_rows if flag(r, "candidate_edge")])  # PROVEN (Bonferroni, day-level)
    watch = dedup_family([r for r in edge_rows if flag(r, "watch")])                 # SHADOW watchlist (t≥2, day-level)
    n_edge = len(edge_rows)
    bonf_sig = sum(1 for r in edge_rows if flag(r, "bonf_sig"))
    persist_n = len(dedup_family([r for r in edge_rows if flag(r, "persistent")]))

    show_cols = [c for c in ["study", "signal", "regime", "tod", "underlying",
                             "opt_winrate", "opt_mean_net_pct", "opt_t", "opt_t_overlap", "days", "pos_days"]
                 if matrix and c in matrix[0]]
    labels = {"opt_mean_net_pct": "net%/sig", "opt_winrate": "win", "opt_t": "t(day)",
              "opt_t_overlap": "t(raw)", "pos_days": "pos/days"}

    # ── verdict ── (two honest tiers: PROVEN vs SHADOW-WATCH)
    if candidates:
        verdict = (f"🟢 {len(candidates)} PROVEN candidate edge(s): day-level t survived Bonferroni + persistence "
                   f"+ net>0 — review for shadow → live (human approval).")
        vclass = "v-good"
    elif watch:
        verdict = (f"🟡 0 proven edges yet — honest day-level stats need more days to clear Bonferroni over {n_days} "
                   f"days. But {len(watch)} signal-families are on the SHADOW WATCHLIST (day-level t≥2, positive on a "
                   f"majority of days, net>0 after costs) — worth shadow-tracking now, no orders.")
        vclass = "v-warn"
    elif n_days < 10:
        verdict = (f"⏳ Only {n_days} day(s) of pooled data — too little to conclude. Accumulating.")
        vclass = "v-info"
    else:
        verdict = ("🔴 0 proven + 0 watchlist after honest day-level correction — no tradable edge in the sample yet. "
                   "Keep accumulating; nothing acts without surviving the guardrail.")
        vclass = "v-bad"

    # ── H2 realized P&L: aggregate per strategy (the CSV is one row per strategy-per-DAY, columns
    #    strategy,date,trades,gross_pnl,est_charges,net_pnl,win_rate). The old render looked for
    #    "gross"/"net"/"win%" which don't exist → it silently dropped every P&L column and showed ~40
    #    per-day rows with only trades+charges. Aggregate to one row per strategy and surface NET P&L. ──
    def aggregate_h2(rows):
        agg = {}
        for r in rows:
            s = r.get("strategy", "?")
            a = agg.setdefault(s, {"strategy": s, "days": set(), "trades": 0.0,
                                   "gross_pnl": 0.0, "est_charges": 0.0, "net_pnl": 0.0, "wins": 0.0})
            n = num(r.get("trades"))
            a["trades"] += n
            a["gross_pnl"] += num(r.get("gross_pnl"))
            a["est_charges"] += num(r.get("est_charges"))
            a["net_pnl"] += num(r.get("net_pnl"))
            a["wins"] += num(r.get("win_rate")) * n  # win_rate is a per-day fraction → back out wins
            if r.get("date"):
                a["days"].add(r["date"])
        out = []
        for a in agg.values():
            t = a["trades"] or 1
            out.append({"strategy": a["strategy"], "days": len(a["days"]), "trades": int(a["trades"]),
                        "gross_pnl": round(a["gross_pnl"]), "est_charges": round(a["est_charges"]),
                        "net_pnl": round(a["net_pnl"]), "win_pct": round(100.0 * a["wins"] / t, 1)})
        out.sort(key=lambda x: x["net_pnl"])  # worst net first — surface the losers that need attention
        return out

    h2_agg = aggregate_h2(h2) if h2 else []
    h2_cols = ["strategy", "days", "trades", "gross_pnl", "est_charges", "net_pnl", "win_pct"]
    h2_labels = {"gross_pnl": "gross ₹", "est_charges": "charges ₹", "net_pnl": "net ₹", "win_pct": "win%"}

    if xshadow_sum:
        x = xshadow_sum
        xshadow_html = (
            "<table><thead><tr><th>metric</th><th>value</th></tr></thead><tbody>"
            f"<tr><td>completed virtual trades</td><td>{x['n']}</td></tr>"
            f"<tr><td>win %</td><td>{x['win_pct']}%</td></tr>"
            f"<tr><td>median gross %/trade</td><td>{x['median_gross_pct']}</td></tr>"
            f"<tr><td>avg gross %/trade</td><td>{x['avg_gross_pct']}</td></tr>"
            f"<tr><td>avg hold (min)</td><td>{x['avg_hold_min']}</td></tr>"
            f"<tr><td>CE / PE</td><td>{x['ce']} / {x['pe']}</td></tr>"
            "</tbody></table>")
    else:
        xshadow_html = ("<p class='muted'><em>Accumulating — no completed virtual trades yet. Fills in during "
                        "market hours; each virtual trade closes ~30&nbsp;min after entry.</em></p>")

    gen_at = datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    body = f"""<!DOCTYPE html><html><head><meta charset="utf-8"><title>Edge Research Brief {esc(date_str)}</title>
<style>
body{{font-family:system-ui,Segoe UI,Arial,sans-serif;margin:24px;max-width:1100px;color:#1f2328;line-height:1.5}}
h1{{color:#1a237e;margin:0 0 4px}} .sub{{color:#57606a;font-size:13px;margin:0 0 18px}}
h2{{font-size:16px;color:#24292f;border-bottom:1px solid #d0d7de;padding-bottom:4px;margin:26px 0 8px}}
table{{border-collapse:collapse;font-size:13px;margin:6px 0 14px}} th,td{{border:1px solid #d0d7de;padding:5px 9px;text-align:left}}
th{{background:#f6f8fa}} tr:nth-child(even){{background:#fafbfc}}
.verdict{{padding:12px 16px;border-radius:8px;font-size:15px;font-weight:600;margin:10px 0 20px}}
.v-good{{background:#dafbe1;border:1px solid #2da44e}} .v-warn{{background:#fff8c5;border:1px solid #d4a72c}}
.v-info{{background:#ddf4ff;border:1px solid #54aeff}} .v-bad{{background:#ffebe9;border:1px solid #cf222e}}
.muted{{color:#57606a}} .kpis{{display:flex;gap:14px;flex-wrap:wrap;margin:8px 0 18px}}
.kpi{{background:#f6f8fa;border:1px solid #d0d7de;border-radius:8px;padding:10px 16px;min-width:120px}}
.kpi b{{display:block;font-size:22px;color:#1a237e}} .kpi span{{font-size:12px;color:#57606a}}
.note{{font-size:12px;color:#57606a;background:#f6f8fa;border-left:3px solid #d0d7de;padding:8px 12px;margin-top:20px}}
</style></head><body>
<h1>Edge Research Brief</h1>
<p class="sub">Generated {esc(gen_at)} · pooled coverage <strong>{n_days} day(s)</strong> ({esc(date_range)}) · studies: {esc(', '.join(studies) or '—')}</p>
<div class="verdict {vclass}">{esc(verdict)}</div>
<div class="kpis">
  <div class="kpi"><b>{n_edge}</b><span>edge hypotheses (chain+cross)</span></div>
  <div class="kpi"><b>{bonf_sig}</b><span>Bonferroni-sig (day-level)</span></div>
  <div class="kpi"><b>{persist_n}</b><span>persisting families</span></div>
  <div class="kpi"><b>{len(watch)}</b><span>shadow watchlist</span></div>
  <div class="kpi"><b>{len(candidates)}</b><span>PROVEN candidates</span></div>
</div>
{health_html}

<h2>PROVEN candidate edges (day-level t survives Bonferroni + persistence + net&gt;0)</h2>
<p class="muted" style="font-size:12px">The strict bar for promoting toward live. Empty is the honest
answer while the sample is young — a real edge needs enough distinct days to clear multiple-testing.</p>
{table(candidates[:25], show_cols, labels)}

<h2>Shadow watchlist (day-level t≥2, persistent, net&gt;0 — worth tracking, NOT yet proven)</h2>
<p class="muted" style="font-size:12px">Deduped to one row per signal-family (best horizon). <code>t(day)</code>
is the honest across-days t; <code>t(raw)</code> is the old overlapping-window t shown only to expose how
inflated it was. These are the candidates to run in SHADOW (no orders) to build an out-of-sample record.</p>
{table(watch[:25], show_cols, labels)}

<h2>Exit-rule study — which exit keeps the most net (aimed at the give-back leak)</h2>
<p class="muted" style="font-size:12px">Same entries (base signal in a trend regime), different exits, ranked by
<strong>median</strong> net (robust to a few fat-tail winners). <code>giveBack%</code> = peak − realized, the exact
thing the live book bleeds (win% &gt; 50 yet net &lt; 0). This is the evidence for how to change the LIVE exit —
e.g. if TARGET beats TRAIL/FIXED, book winners instead of trailing them.</p>
{table(exit_study, ["exit_rule","n","win_pct","median_net_pct","avg_net_pct","avg_giveback_pct"],
       {"win_pct":"win%","median_net_pct":"med net%","avg_net_pct":"avg net%","avg_giveback_pct":"give-back%"})
 if exit_study else "<p class='muted'><em>Exit-rule study not available yet.</em></p>"}

<h2>Cross-index shadow — NIFTY→SENSEX momentum (LIVE virtual trades, no orders)</h2>
<p class="muted" style="font-size:12px">The strongest watchlist edge, paper-traded live: when NIFTY's ~15-min
momentum turns in an intraday trend, a VIRTUAL SENSEX ATM entry is booked at the real premium and closed after
the horizon. Real premiums, <strong>zero orders</strong> — the honest out-of-sample record. <code>grossPct</code>
is LTP→LTP (gross of spread + charges), so read it vs the synthetic daily-study net (~33.6%) as directional, not
exact. Needs ~2 weeks before it means anything.</p>
{xshadow_html}

<h2>Realized net P&amp;L by strategy (H2 ground truth — worst net first)</h2>
{table(h2_agg, h2_cols, h2_labels) if h2_agg else "<p class='muted'><em>H2 attribution not available (needs JDBC + h2 jar).</em></p>"}

<h2>OI-Momentum signal attribution — data comparison (fast-OI freshness → outcome)</h2>
<p class="muted" style="font-size:12px">Joined signal→exit by correlationKey. Tests whether the 2026-07-01 fast-OI
refresh (fresher operator signal = lower <code>operatorAgeBucket</code>) and the 60s window actually produce better
win% / realized P&amp;L. Descriptive; small samples are noisy — watch the trend as days accumulate.</p>
{table(oi_attrib, ["dimension","group","n","win_pct","avg_pnl_pct","sum_pnl_pct","avg_hold_s","avg_mae"],
       {"win_pct":"win%","avg_pnl_pct":"avg P&L%","sum_pnl_pct":"Σ P&L%","avg_hold_s":"hold s","avg_mae":"MAE"})
 if oi_attrib else "<p class='muted'><em>OI signal attribution not available yet (no joined signal/exit events).</em></p>"}

<div class="note">Guardrail: nothing is called an edge unless it survives a Bonferroni multiple-testing correction
AND is positive on ≥60% of days (cross-day persistence) AND is net-positive after real charges + slippage.
Pooled means are misleading on small samples — a few big days inflate them while the daily sign is a coin-flip.
Candidate edges graduate to a <em>shadow</em> detector (no orders) first; going live always needs human approval.</div>
</body></html>"""

    out_dir = os.path.join(OUT_ROOT, date_str)
    os.makedirs(out_dir, exist_ok=True)
    with open(os.path.join(out_dir, "research-report.html"), "w", encoding="utf-8") as fh:
        fh.write(body)

    summary = {
        "date": date_str,
        "generatedAt": gen_at,
        "coverage": {"days": n_days, "from": dates[0] if dates else None,
                     "to": dates[-1] if dates else None, "combos": n_edge, "studies": studies,
                     "perDay": {d: c for d, c in per_day_sorted}, "blindDays": blind_days},
        "counts": {"edgeHypotheses": n_edge, "bonfSig": bonf_sig,
                   "persisting": persist_n, "watchlist": len(watch), "candidateEdge": len(candidates)},
        "candidateEdges": [{k: r.get(k) for k in show_cols} for r in candidates[:25]],
        "watchlist": [{k: r.get(k) for k in show_cols} for r in watch[:25]],
        "h2": [{k: r.get(k) for k in h2_cols} for r in (h2_agg or [])],
        "verdict": verdict,
    }
    with open(os.path.join(out_dir, "summary.json"), "w", encoding="utf-8") as fh:
        json.dump(summary, fh, indent=2)
    print(f"[research] wrote {out_dir}/research-report.html + summary.json "
          f"(days={n_days}, proven={len(candidates)}, watchlist={len(watch)})")
    return out_dir


if __name__ == "__main__":
    # .strip() guards against a CR/whitespace-contaminated arg (a CRLF-edited caller produced phantom
    # 'reports/research/<date>\r\r/' dirs the UI could never read). (2026-07-02)
    date_str = (sys.argv[1] if len(sys.argv) > 1 else datetime.date.today().isoformat()).strip()
    try:
        build(date_str)
    except Exception as e:
        # never fail the cron — emit a minimal error report so the UI still shows the run happened
        out_dir = os.path.join(OUT_ROOT, date_str)
        os.makedirs(out_dir, exist_ok=True)
        with open(os.path.join(out_dir, "research-report.html"), "w", encoding="utf-8") as fh:
            fh.write(f"<html><body><h1>Edge Research Brief {html.escape(date_str)}</h1>"
                     f"<p style='color:#cf222e'>Report generation error: {html.escape(str(e))}</p></body></html>")
        with open(os.path.join(out_dir, "summary.json"), "w", encoding="utf-8") as fh:
            json.dump({"date": date_str, "error": str(e)}, fh)
        print(f"[research] ERROR {e} — wrote minimal report")
        sys.exit(0)  # exit 0 so the cron chain continues
