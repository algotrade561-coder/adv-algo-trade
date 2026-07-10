#!/usr/bin/env bash
# run-research.sh — daily Edge Research cycle (post-close). Runs the case-study battery against the cumulative
# data, then renders a single readable HTML brief + summary.json into reports/research/<date>/ for the UI.
# Idempotent; never aborts on a single study failure; EVERY study is time-capped so it cannot peg the (small)
# box or overrun the box-stop window.
#
# Tiers (RESEARCH_TIER):
#   lite  (default, the daily cron) — chain + cross + H2 + pooled report + brief. Bounded, light.
#   full  (on-demand "Run now" / weekly) — adds the heavy microstructure + signed-flow tiers.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; REPO_ROOT="$(cd "$HERE/../.." && pwd)"; cd "$REPO_ROOT"
PY="${EDGE_PY:-python3}"
DATE="${RESEARCH_DATE:-$(TZ=Asia/Kolkata date +%F)}"
TIER="${RESEARCH_TIER:-lite}"
LIGHT_TIMEOUT="${LIGHT_TIMEOUT:-420}"   # cap per light study (chain/report) — 7 min
HEAVY_TIMEOUT="${HEAVY_TIMEOUT:-300}"   # cap per heavy study (micro/flow) — 5 min
# keep the small box usable: cap CPU so SSH/app stay responsive while research runs (nice + ionice if present)
NICE="nice -n 15"; command -v ionice >/dev/null 2>&1 && NICE="ionice -c2 -n7 $NICE"
OUT_DIR="reports/research/$DATE"; mkdir -p "$OUT_DIR"
LOG="$OUT_DIR/run.log"
exec > >(tee -a "$LOG") 2>&1
echo "[research] ===== start $(date '+%F %T %Z') | IST $(TZ=Asia/Kolkata date '+%F %T') | date=$DATE tier=$TIER ====="

cap()  { echo "[research] + timeout ${1}s $NICE ${*:2}"; timeout "$1" $NICE "${@:2}" || echo "[research] (non-fatal/timeout) ${*:2}"; }

# ── LIGHT tier (always) ────────────────────────────────────────────────────
cap "$LIGHT_TIMEOUT" "$PY" tools/edge-study/case_studies.py --mode all   # chain + cross + pooled report (NO micro/flow)
cap "$HEAVY_TIMEOUT" "$PY" tools/edge-study/exit_study.py                 # which EXIT rule keeps the most net (give-back leak)
cap 120               "$PY" tools/edge-study/h2_attrib.py                 # realized net P&L (ground truth)
cap 120               "$PY" tools/edge-study/oi_signal_attribution.py     # OI-momentum signal->outcome (fast-OI freshness A/B)

# ── HEAVY tier (full / weekly only) ────────────────────────────────────────
# Sub-minute microstructure + signed-flow: millions of rows, 0 persistent edges over the whole sample
# (re-confirms "sub-minute isn't tradeable after costs"). Monitoring-only — kept off the daily path so
# it can't time out the cron; run weekly via RESEARCH_TIER=full.
if [ "$TIER" = "full" ]; then
  cap "$HEAVY_TIMEOUT" "$PY" tools/edge-study/case_studies.py --mode micro_archive
  cap "$HEAVY_TIMEOUT" "$PY" tools/edge-study/case_studies.py --mode flow
  cap "$LIGHT_TIMEOUT" "$PY" tools/edge-study/case_studies.py --mode report   # re-pool with micro+flow
fi

# ── render the readable brief + machine-readable summary for the UI ────────
cap 120 "$PY" tools/edge-study/build_research_report.py "$DATE"
echo "[research] ===== done $(date '+%F %T %Z') -> $OUT_DIR/{research-report.html, summary.json} ====="
