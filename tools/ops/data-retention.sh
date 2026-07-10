#!/usr/bin/env bash
# data-retention.sh — single periodic cleanup so research + data collection stay bounded on the box.
# gzips aged files and deletes beyond each retention window. SAFE by design:
#   • DRY-RUN by default — prints exactly what it WOULD do; pass --apply to actually gzip/delete.
#   • Never touches the live H2 DB, secrets, config, the jar, or the ACTIVE (current) log/CSV
#     (age filters use mtime, and the live algo-trade.log/.json has mtime "now" so it's always skipped).
#   • Every action is logged to logs/retention.log, with df before/after so the reclaim is auditable.
#
# Windows (override via env):
#   LOG_DAYS=10  MICRO_DAYS=14  EVENT_DAYS=14  SHADOW_DAYS=30  RESEARCH_DAYS=60  SNAP_DAYS=120  GZIP_AFTER=1
#
# Usage:
#   bash tools/ops/data-retention.sh            # DRY-RUN (default)
#   bash tools/ops/data-retention.sh --apply    # execute
set -uo pipefail
ROOT="${RETENTION_ROOT:-/home/ubuntu/adv-algo-trade}"; cd "$ROOT" 2>/dev/null || { echo "no $ROOT"; exit 1; }
APPLY=0; [ "${1:-}" = "--apply" ] && APPLY=1
LOG_DAYS=${LOG_DAYS:-10}         # logs: keep 10 days (was 30 — not needed)
MICRO_DAYS=${MICRO_DAYS:-14}     # atm-microstructure CSVs (feed the weekly MICRO study)
EVENT_DAYS=${EVENT_DAYS:-14}     # raw tuning event CSV dirs (parquet archive keeps the history)
SHADOW_DAYS=${SHADOW_DAYS:-30}   # shadow CSVs (small)
RESEARCH_DAYS=${RESEARCH_DAYS:-60}
SNAP_DAYS=${SNAP_DAYS:-120}      # chain-snapshots (needed for research pooling + IV backfill) — keep long
GZIP_AFTER=${GZIP_AFTER:-1}      # compress anything older than this many days
MODE=$([ "$APPLY" -eq 1 ] && echo APPLY || echo DRY-RUN)
LOGF="logs/retention.log"; mkdir -p logs
say(){ echo "[retention $(date '+%F %T') $MODE] $*" | tee -a "$LOGF"; }

# gzip aged, uncompressed files (dir, name-glob). mtime skips today's active file automatically.
do_gzip(){
  local dir="$1" pat="$2"; [ -d "$dir" ] || return 0
  mapfile -t items < <(find "$dir" -type f -name "$pat" ! -name "*.gz" -mtime +"$GZIP_AFTER" 2>/dev/null)
  local n=${#items[@]}; [ "$n" -eq 0 ] && { say "gzip   $dir/$pat: none"; return 0; }
  local sz; sz=$(printf '%s\0' "${items[@]}" | du -ch --files0-from=- 2>/dev/null | tail -1 | cut -f1)
  say "gzip   $dir/$pat >${GZIP_AFTER}d: $n files ($sz → ~10x smaller)"
  [ "$APPLY" -eq 1 ] && printf '%s\0' "${items[@]}" | xargs -0 -r gzip -f
}
# delete aged files (type f) or dirs (type d) beyond a window
do_delete(){
  local dir="$1" pat="$2" days="$3" typ="${4:-f}"; [ -d "$dir" ] || return 0
  local depth=(); [ "$typ" = d ] && depth=(-maxdepth 1)
  mapfile -t items < <(find "$dir" -mindepth 1 "${depth[@]}" -type "$typ" -name "$pat" -mtime +"$days" 2>/dev/null)
  local n=${#items[@]}; [ "$n" -eq 0 ] && { say "delete $dir/$pat >${days}d: none"; return 0; }
  local sz; sz=$(printf '%s\0' "${items[@]}" | du -ch --files0-from=- 2>/dev/null | tail -1 | cut -f1)
  say "delete $dir/$pat >${days}d ($typ): $n items, $sz"
  [ "$APPLY" -eq 1 ] && printf '%s\0' "${items[@]}" | xargs -0 -r rm -rf
}

say "===== START — disk: $(df -h "$ROOT" | awk 'NR==2{print $4" free of "$2}') ====="

# 1. LOGS — gzip rolled files, then delete anything (gz/log/json) older than LOG_DAYS. Active files skipped by mtime.
for d in application json audit errors; do
  do_gzip   "logs/$d" "*.log"
  do_gzip   "logs/$d" "*.json"
  do_delete "logs/$d" "*.gz"   "$LOG_DAYS"
  do_delete "logs/$d" "*.log"  "$LOG_DAYS"
  do_delete "logs/$d" "*.json" "$LOG_DAYS"
done

# 2. atm-microstructure (biggest daily grower; feeds the demoted weekly MICRO study) — gzip >1d, delete >MICRO_DAYS
do_gzip   "data/tuning" "atm-microstructure-*.csv"
do_delete "data/tuning" "atm-microstructure-*.csv.gz" "$MICRO_DAYS"

# 3. shadow CSVs (early-detection / oi-prediction / future cross-index) — gzip >1d, delete >SHADOW_DAYS
do_gzip   "data/tuning" "*-shadow-*.csv"
do_delete "data/tuning" "*-shadow-*.csv.gz" "$SHADOW_DAYS"

# 3b. Market-Memory V5 decision records (design §17: ~1-2 MB/day; the learning corpus — pulled
# weekly by fetch-data.sh before deletion) — gzip >1d, keep same window as the shadows.
do_gzip   "data/tuning" "market-memory-decisions-*.csv"
do_delete "data/tuning" "market-memory-decisions-*.csv.gz" "$SHADOW_DAYS"

# 4. raw tuning EVENT CSV dirs — parquet archive (reports/tuning/summary) keeps the history for research
do_delete "reports/tuning/events" "2*-*-*" "$EVENT_DAYS" d

# 5. research report dirs — keep RESEARCH_DAYS
do_delete "reports/research" "2*-*-*" "$RESEARCH_DAYS" d

# 6. chain-snapshots — keep SNAP_DAYS (research pools all days + IV backfill uses them; already gzipped)
do_delete "data/chain-snapshots" "2*-*-*" "$SNAP_DAYS" d

say "===== END — disk: $(df -h "$ROOT" | awk 'NR==2{print $4" free of "$2}') ====="
[ "$APPLY" -eq 0 ] && say "DRY-RUN only. Re-run with --apply to execute."
