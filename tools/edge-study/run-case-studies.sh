#!/usr/bin/env bash
# run-case-studies.sh — daily case-study matrix (chain + cross-index + microstructure + H2 attribution).
# Run AFTER the 15:35 IST microstructure Parquet roll — schedule ~15:45 IST so the day's per-second
# ticks are rolled to the archive the micro tier reads. Idempotent, no daemon.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; REPO_ROOT="$(cd "$HERE/../.." && pwd)"; cd "$REPO_ROOT"
PY="${EDGE_PY:-python3}"

echo "[case-studies] server $(date '+%F %T %Z') | IST $(TZ=Asia/Kolkata date '+%F %T %Z')"
echo "[case-studies] tz=$(timedatectl 2>/dev/null | awk -F': ' '/Time zone/{print $2}' || echo unknown)"

# chain (all snapshot dates) + cross-index + report (pools under the multiple-testing guardrail)
"$PY" tools/edge-study/case_studies.py --mode all || true
# microstructure tier over the rolled Parquet archive (needs duckdb)
"$PY" tools/edge-study/case_studies.py --mode micro_archive || true
# signed-volume / order-flow-imbalance (OFI) early-detection tier + flow->OI reconciliation (needs duckdb)
"$PY" tools/edge-study/case_studies.py --mode flow || true
# re-pool with micro + flow included
"$PY" tools/edge-study/case_studies.py --mode report || true
# ground-truth realized net-P&L attribution from H2 (needs JayDeBeApi + h2 jar; degrades cleanly)
"$PY" tools/edge-study/h2_attrib.py || true

echo "[case-studies] done -> reports/case-studies/{cumulative.csv, matrix_report.csv, h2_attribution.csv}"
