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
# atm-microstructure = THE OPTION-PREMIUM TAPE. Raised 14 → 45 on 2026-07-27 when the momentum
# END_OF_MOVE exit went LIVE: validating it means joining each EXIT_TELEMETRY row to the held strike's
# FORWARD premium path, i.e. reading the tape for days that have already been purged under the old
# window. The Parquet archive (see MICRO_ARCHIVE below) is the long-term copy; this is the raw-CSV
# window that keeps a recent study runnable without a Parquet read.
#
# NOTE (2026-08-24): s3-archive-retain.sh independently deletes the raw .csv at MICRO_RAW_DAYS=3 once
# the day is verified in S3, so the effective raw-CSV window is 3 days, not 45. 45 days of tape is
# ~31 GB against a 24 GB volume and has never been physically possible. MICRO_DAYS below still governs
# the .csv.gz window, which is what actually survives to 45 days.
MICRO_DAYS=${MICRO_DAYS:-45}     # atm-microstructure CSVs (option-premium tape; EXIT_TELEMETRY joins)
# Parquet archive written by MicrostructureParquetRoller (15:35 IST Mon-Fri, retention 180d, row-count
# verified before it deletes its CSV). A day is only safe to gzip/delete here once THIS partition exists
# AND its parquet files are readable — see micro_archived() for why existence alone is not enough.
MICRO_ARCHIVE=${MICRO_ARCHIVE:-data/tuning/microstructure-archive}
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

# 2. atm-microstructure = THE OPTION-PREMIUM TAPE (biggest daily grower).
#
# ARCHIVE-GUARDED since 2026-07-27. Previously this gzipped every CSV >1d and deleted the .gz at
# MICRO_DAYS with NO check that the day had been archived. That is a silent-loss hole with two live
# triggers: (a) gzip renames the file to .csv.gz and MicrostructureParquetRoller only ever matches
# "atm-microstructure-<date>.csv", so a CSV gzipped before its roll can NEVER be rolled — it just ages
# out and is deleted; (b) any missed roller run (box down at 15:35 IST, DuckDB failure, a Friday tape
# when the box happens to be up over the weekend) leaves exactly that orphan. In practice the box being
# off at weekends has been masking (a); it is not a design.
#
# New rule: a day's CSV/.gz is only gzipped or deleted once its Parquet day-partition EXISTS. An
# unarchived day is KEPT and reported loudly, so the failure mode is "disk grows and we notice", never
# "the tape we need to validate END_OF_MOVE is gone". Everything else in this script is unchanged.
#
# ---------------------------------------------------------------------------
# 2026-08-24 — the "EXISTS" test was not strong enough.
# ---------------------------------------------------------------------------
# On 2026-08-21 MicrostructureParquetRoller died mid-write rolling the 08-20 tape and left three
# TRUNCATED parquet files (valid "PAR1" header, no closing footer) in day=20. The directory existed,
# so micro_archived() returned true and the guard above considered 08-20 safely archived.
#
# This script did not in fact gzip that particular tape — at the 08-21 10:20 run the CSV had not yet
# aged past GZIP_AFTER, and the CSV was gone by the next run — so it is not the proven cause of the
# 08-20 loss. The hole is real regardless: any truncated partition that survives one more day opens
# trigger (a), and that is terminal — once the CSV becomes .csv.gz the roller can never match it
# again, so a crashed roll can never be retried and the day quietly ages out.
#
# A day is now archived only if the partition holds at least one parquet AND every parquet in it is
# well-formed. A truncated partition reads as UNARCHIVED, which keeps the raw .csv on disk in exactly
# the shape the roller needs to try again. Checked in pure bash (head/tail are ~1ms) so this script
# keeps no dependency on python/boto3 — the S3-side equivalent lives in s3-archive-retain.sh.
micro_parquet_ok(){  # $1 = path to a .parquet -> 0 if it opens AND closes with the magic
  local f="$1"
  [ -s "$f" ] || return 1
  [ "$(head -c 4 "$f" 2>/dev/null)" = "PAR1" ] || return 1
  [ "$(tail -c 4 "$f" 2>/dev/null)" = "PAR1" ] || return 1
}
micro_archived(){    # $1 = a date like 2026-07-24 → 0 if that day is in the Parquet archive AND intact
  # Keep these two `local` statements separate. Arguments to the `local` builtin are expanded BEFORE
  # the builtin runs, so `local d="$1" part="...${d}..."` would expand ${d} against the *enclosing*
  # d — which section 1 leaves set to "errors" from `for d in application json audit errors`. That
  # silently yields year=erro/month=s/day= and makes this guard always return false.
  local d="$1"
  local part="$MICRO_ARCHIVE/year=${d:0:4}/month=${d:5:2}/day=${d:8:2}"
  [ -d "$part" ] || return 1
  local n=0 f
  while IFS= read -r f; do
    [ -n "$f" ] || continue
    micro_parquet_ok "$f" || {
      say "micro  ✗ $d partition is CORRUPT (truncated parquet: $f) — treating as UNARCHIVED so the raw tape is kept"
      return 1
    }
    n=$((n+1))
  done < <(find "$part" -type f -name '*.parquet' 2>/dev/null)
  [ "$n" -gt 0 ]
}
micro_retain(){      # gzip/delete atm-microstructure files ONLY for archived days
  local action="$1" pat="$2" days="${3:-}"
  local kept=0 acted=0 f base date
  while IFS= read -r f; do
    base=$(basename "$f")
    date=$(echo "$base" | sed -n 's/^atm-microstructure-\([0-9-]\{10\}\).*$/\1/p')
    if [ -z "$date" ] || ! micro_archived "$date"; then
      kept=$((kept+1)); continue
    fi
    acted=$((acted+1))
    if [ "$APPLY" -eq 1 ]; then
      [ "$action" = gzip ] && gzip -f "$f"
      [ "$action" = delete ] && rm -f "$f"
    fi
  done < <(find "data/tuning" -maxdepth 1 -type f -name "$pat" \
             ${days:+-mtime +"$days"} ${days:+} 2>/dev/null)
  say "micro  $action $pat${days:+ >${days}d}: $acted archived (acted), $kept UNARCHIVED (kept)"
  [ "$kept" -gt 0 ] && say "micro  NOTE: $kept atm-microstructure file(s) have no readable Parquet partition yet — \
kept on purpose. Check MicrostructureParquetRoller ran (15:35 IST) and that its output is not truncated."
  return 0
}
micro_retain gzip   "atm-microstructure-*.csv"    "$GZIP_AFTER"
micro_retain delete "atm-microstructure-*.csv.gz" "$MICRO_DAYS"

# 3. shadow CSVs (early-detection / oi-prediction / future cross-index) — gzip >1d, delete >SHADOW_DAYS
do_gzip   "data/tuning" "*-shadow-*.csv"
do_delete "data/tuning" "*-shadow-*.csv.gz" "$SHADOW_DAYS"

# 3b. Market-Memory V5 decision records (design §17: ~1-2 MB/day; the learning corpus — pulled
# weekly by fetch-data.sh before deletion) — gzip >1d, keep same window as the shadows.
do_gzip   "data/tuning" "market-memory-decisions-*.csv"
do_delete "data/tuning" "market-memory-decisions-*.csv.gz" "$SHADOW_DAYS"

# 3c. movement-timeline (largest daily grower after atm-microstructure; ~50 MB/day raw) —
# gzip >1d, delete >MICRO_DAYS (same window as atm-microstructure).
do_gzip   "data/tuning" "movement-timeline-*.csv"
do_delete "data/tuning" "movement-timeline-*.csv.gz" "$MICRO_DAYS"

# 3d. Other daily tuning diagnostics (score-components / momentum-regime / post-exit-drift /
# board-oi) — small individually but unbounded if left forever; same window as shadows.
# board-oi-*.csv added 2026-07-26 with the OI-build capture. This list is an explicit ALLOW-LIST:
# a new data/tuning file that is not named here is neither compressed nor deleted, ever. That is
# the failure mode to remember when adding any future capture — the file does not get "picked up",
# it gets forgotten. ~100 KB/day (2 sides x 3 indices x ~375 min).
# market-memory-minute-panel-*.csv added with the live MarketMemoryMinutePanelRecorder (engine-state
# minute rollup: episode phase/age, avalanche-would, fade-veto-would, board build — the forward-only
# complement to the tape-backfilled minute-panel-*.csv). Tiny (~150 KB/day, 3 indices x ~375 min).
for pat in "score-components-*.csv" "momentum-regime-*.csv" "post-exit-drift-*.csv" "board-oi-*.csv" "minute-panel-*.csv" "market-memory-minute-panel-*.csv"; do
  do_gzip   "data/tuning" "$pat"
  do_delete "data/tuning" "${pat}.gz" "$SHADOW_DAYS"
done

# 4. raw tuning EVENT CSV dirs — parquet archive (reports/tuning/summary) keeps the history for research
do_delete "reports/tuning/events" "2*-*-*" "$EVENT_DAYS" d

# 5. research report dirs — keep RESEARCH_DAYS
do_delete "reports/research" "2*-*-*" "$RESEARCH_DAYS" d

# 6. chain-snapshots — keep SNAP_DAYS (research pools all days + IV backfill uses them; already gzipped)
do_delete "data/chain-snapshots" "2*-*-*" "$SNAP_DAYS" d

say "===== END — disk: $(df -h "$ROOT" | awk 'NR==2{print $4" free of "$2}') ====="
[ "$APPLY" -eq 0 ] && say "DRY-RUN only. Re-run with --apply to execute."
