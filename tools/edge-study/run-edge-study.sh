#!/usr/bin/env bash
# run-edge-study.sh — wrapper to run the daily edge study (Linux/EC2).
# Idempotent, no daemon. Intended to be fired by systemd timer or cron at 15:30 IST on weekdays.
#
# Usage:
#   ./run-edge-study.sh                 # today (IST), NIFTY
#   ./run-edge-study.sh 2026-06-22 "NIFTY,BANKNIFTY,SENSEX"
#   EDGE_UNDERLYINGS=NIFTY,SENSEX ./run-edge-study.sh
set -euo pipefail

# Resolve repo root = two levels up from this script (tools/edge-study -> repo).
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$HERE/../.." && pwd)"
cd "$REPO_ROOT"

PY="${EDGE_PY:-python3}"
DATE_ARG="${1:-$(TZ=Asia/Kolkata date +%F)}"          # default today in IST regardless of server TZ
UNDERLYINGS="${2:-${EDGE_UNDERLYINGS:-NIFTY}}"
SNAP_DIR="${EDGE_SNAPSHOT_DIR:-data/chain-snapshots}"

echo "[run-edge-study] server time:        $(date '+%F %T %Z')"
echo "[run-edge-study] IST time:           $(TZ=Asia/Kolkata date '+%F %T %Z')"
echo "[run-edge-study] OS timezone:        $(timedatectl 2>/dev/null | awk -F': ' '/Time zone/{print $2}' || cat /etc/timezone 2>/dev/null || echo unknown)"
echo "[run-edge-study] studying date=$DATE_ARG underlyings=$UNDERLYINGS snapshot-dir=$SNAP_DIR"

"$PY" tools/edge-study/edge_study.py \
    --date "$DATE_ARG" \
    --underlyings "$UNDERLYINGS" \
    --snapshot-dir "$SNAP_DIR"

# Refresh the pooled multi-day rollup after each daily run.
"$PY" tools/edge-study/edge_study.py --rollup || true

echo "[run-edge-study] done. Reports under reports/edge-study/$DATE_ARG/ and reports/edge-study/_rollup/"
