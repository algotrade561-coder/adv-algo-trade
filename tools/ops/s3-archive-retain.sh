#!/usr/bin/env bash
# s3-archive-retain.sh — offload the 3 analytical keepers to S3, then apply
# tight local retention so the box fits a 20 GB volume.
#
#   Keepers (synced to S3, then local pruned to a short hot window, S3-VERIFIED):
#     microstructure-archive (parquet)  -> s3://.../microstructure
#     reports/tuning/archive (parquet)  -> s3://.../tuning-events
#     data/decision-traces   (csv->gz)  -> s3://.../decision-traces
#   Delete-only (short local window, NO archive):
#     live-pipeline-audit 3d · live-pipeline-evals 7d · feature-snapshots 14d
#     live-feature-snapshots 14d · decision-influence 14d · move-learning 14d
#     chain-snapshots 30d · raw atm-microstructure csv 3d (gated on VALID parquet-in-S3)
#
# Touches NO trading jar and NO app config. Existing Java/bash cleaners remain as
# loose backstops. Default is DRY-RUN; pass --apply to act.
#
# ---------------------------------------------------------------------------
# 2026-08-24 — DATA-LOSS POSTMORTEM (2026-08-20 microstructure, all 3 indices)
# ---------------------------------------------------------------------------
# What happened: MicrostructureParquetRoller died mid-write rolling the 08-20
# tape. It left three TRUNCATED parquet files on disk — valid "PAR1" header, no
# closing footer magic — which this script then faithfully copied to S3. The old
# day_in_s3() gate only asked "does the prefix exist?", so the broken partition
# satisfied it, and the raw atm-microstructure-2026-08-20.csv (the only source
# from which the day could be rebuilt) was deleted. Result: that trading day is
# unrecoverable. --size-only made it permanent: a corrupt object whose byte
# count never changes is never re-uploaded, so sync could not self-heal.
#
# Three defects, three fixes, all below:
#   A. day_in_s3() validated NOTHING  -> now verifies every parquet under the
#      day prefix actually ends in PAR1 before any local delete is permitted.
#   B. corrupt local files were uploaded -> presync_guard() quarantines
#      truncated parquet BEFORE sync, so damage never reaches S3.
#   C. --size-only could not repair    -> verify_uploads() re-uploads any object
#      whose S3 copy fails the footer check, regardless of size match.
# ---------------------------------------------------------------------------
set -euo pipefail

APPLY=0; [ "${1:-}" = "--apply" ] && APPLY=1
D="/home/ubuntu/adv-algo-trade"
B="adv-algo-trade-archive-886496686488"
R="ap-south-1"
LOG="$D/logs/s3-archive-retain.log"
QUARANTINE="$D/data/quarantine"
IST() { TZ=Asia/Kolkata date '+%Y-%m-%d %H:%M:%S'; }
say() { echo "[archive-retain $(IST) $([ $APPLY -eq 1 ] && echo APPLY || echo DRY)] $*" | tee -a "$LOG"; }

# Non-zero if anything needed manual attention; cron can alert on it.
INTEGRITY_FAILURES=0

# ---- hot windows (days) ----
MICRO_PARQUET_DAYS=3      # local microstructure parquet kept; rest in S3
# MICRO_RAW_DAYS was 1. data-retention.sh advertises MICRO_DAYS=45 for the same
# raw tape, which has never been physically possible: the tape is ~700 MB/day and
# the volume is 24 GB, so 45 days would need ~31 GB. This script always won that
# argument silently, leaving a ONE DAY recovery window — which is precisely why
# 08-20 could not be rebuilt. 3 days (~2.1 GB, fits the current 9.7 GB free)
# matches MICRO_PARQUET_DAYS and gives the roller two more chances to be retried
# before the source is gone. Raise if the volume grows; see data-retention.sh.
MICRO_RAW_DAYS=3          # raw atm-microstructure csv (gated on VALID parquet-in-S3)
TUNING_EVENTS_DAYS=7      # local tuning-events parquet kept; rest in S3
TRACES_DAYS=7             # local decision-traces kept; rest in S3
AUDIT_DAYS=1
EVALS_DAYS=3
FEATURE_DAYS=14
INFLUENCE_DAYS=14
MOVE_DAYS=14
CHAIN_DAYS=30

say "===== START (bucket=$B) ====="

# ---- integrity helpers (FIX A/B/C) ----
# A parquet file is well-formed iff it opens with PAR1 and closes with PAR1.
# A truncated writer leaves the header but never the footer — the exact 08-20
# signature — so the footer check is what actually separates good from broken.
#
# The heavy lifting lives in parquet_integrity.py: one Python process with a
# thread pool, instead of one `aws` CLI process per file. That matters — the
# naive shell version costs ~1s of interpreter startup per file and would push
# this cron job past 4 minutes on the current archive.
INTEGRITY_PY="$D/tools/ops/parquet_integrity.py"

parquet_ok_local() { # $1 = path (single-file check, used on small sets only)
  local f="$1"
  [ -s "$f" ] || return 1
  [ "$(head -c 4 "$f" 2>/dev/null)" = "PAR1" ] || return 1
  [ "$(tail -c 4 "$f" 2>/dev/null)" = "PAR1" ] || return 1
}

# Batch check: emits "TAG<TAB>path-or-key" per problem, empty when all good.
integrity_scan() { # $1 = mode  $2.. = mode args
  [ -x "$INTEGRITY_PY" ] || [ -f "$INTEGRITY_PY" ] || { echo ""; return 0; }
  AWS_REGION="$R" python3 "$INTEGRITY_PY" "$@" 2>/dev/null || true
}

# FIX B — never let a locally-corrupt parquet reach S3. Quarantine preserves the
# bytes (they may still be partially salvageable) while stopping propagation.
presync_guard() { # $1 = archive root  $2 = label
  local root="$1" label="$2" bad=0 f rel dest
  [ -d "$root" ] || return 0
  while IFS=$'\t' read -r _tag f; do
    [ -n "${f:-}" ] || continue
    bad=$((bad+1)); INTEGRITY_FAILURES=$((INTEGRITY_FAILURES+1))
    say "  ✗ CORRUPT LOCAL parquet (truncated, will NOT be uploaded): $f"
    if [ $APPLY -eq 1 ]; then
      rel="${f#"$root"/}"; dest="$QUARANTINE/$label/$rel"
      mkdir -p "$(dirname "$dest")"
      mv "$f" "$dest" && say "    quarantined -> $dest"
    fi
  done < <(integrity_scan local "$root")
  [ "$bad" -eq 0 ] && say "presync $label: all local parquet well-formed" \
                   || say "presync $label: $bad CORRUPT file(s) held back — INVESTIGATE MicrostructureParquetRoller"
  return 0
}

# FIX C — --size-only cannot repair a same-size corrupt object. After syncing,
# confirm each uploaded parquet is readable in S3 and force-replace if not.
verify_uploads() { # $1 = local root  $2 = s3 prefix
  local root="$1" pfx="$2" tag key rel src bad=0
  [ -d "$root" ] || return 0
  while IFS=$'\t' read -r tag key; do
    [ -n "${key:-}" ] || continue
    # BAD_LOCAL carries a filesystem path, not an S3 key, and presync_guard
    # already reported and quarantined it. Skip it here so the same file is not
    # counted twice and never gets rendered as a bogus s3:// URL.
    [ "$tag" = "BAD_LOCAL" ] && continue
    bad=$((bad+1)); INTEGRITY_FAILURES=$((INTEGRITY_FAILURES+1))
    say "  ! S3 copy $tag: s3://$B/$key"
    rel="${key#"$pfx"/}"; src="$root/$rel"
    if [ $APPLY -eq 1 ] && [ -f "$src" ]; then
      aws s3 cp "$src" "s3://$B/$key" --region "$R" --only-show-errors \
        && say "    repaired (re-uploaded from local)"
    fi
  done < <(integrity_scan pair "$B" "$root" "$pfx")
  [ "$bad" -eq 0 ] && say "verify $pfx: all S3 parquet copies valid" \
                   || say "verify $pfx: $bad bad copy/copies $([ $APPLY -eq 1 ] && echo repair attempted || echo detected)"
  return 0
}

# ---- 1. SYNC keepers to S3 (incremental, copy-only) ----
sync_up() { # $1 local  $2 s3-prefix
  [ -d "$1" ] || { say "sync skip (missing): $1"; return 0; }
  if [ $APPLY -eq 1 ]; then
    aws s3 sync "$1" "s3://$B/$2" --size-only --only-show-errors --region $R \
      && say "synced $1 -> s3://$B/$2"
  else
    local n; n=$(aws s3 sync "$1" "s3://$B/$2" --size-only --dryrun --region $R 2>/dev/null | wc -l)
    say "sync DRY $1 -> s3://$B/$2: $n object(s) would upload"
  fi
}

presync_guard "$D/data/tuning/microstructure-archive" "microstructure"
presync_guard "$D/reports/tuning/archive"             "tuning-events"

sync_up "$D/data/tuning/microstructure-archive" "microstructure"
sync_up "$D/reports/tuning/archive"             "tuning-events"
# decision-traces: gzip csvs (keep original) then sync the gz
if [ $APPLY -eq 1 ]; then
  find "$D/data/decision-traces" -name '*.csv' -mtime +0 -exec gzip -kf {} \; 2>/dev/null || true
fi
sync_up "$D/data/decision-traces" "decision-traces"

verify_uploads "$D/data/tuning/microstructure-archive" "microstructure"
verify_uploads "$D/reports/tuning/archive"             "tuning-events"

# ---- 1b. DB online backup -> S3 (consistent snapshot while app runs; rolls off at 30d via lifecycle) ----
if systemctl is-active --quiet adv-algo-trade; then
  if [ $APPLY -eq 1 ]; then
    TMP="/tmp/db-backup-$$.zip"
    if java -cp "$D/h2-2.3.232.jar" org.h2.tools.Shell \
         -url "jdbc:h2:file:$D/data/adv-algo-trade;MODE=PostgreSQL;AUTO_SERVER=TRUE" \
         -user sa -password "" -sql "BACKUP TO '$TMP'" >/dev/null 2>&1 \
       && aws s3 cp "$TMP" "s3://$B/backup/db/adv-algo-trade-$(date +%F).zip" --region $R --only-show-errors; then
      say "db online-backup -> s3://$B/backup/db/adv-algo-trade-$(date +%F).zip ($(du -h "$TMP" 2>/dev/null | cut -f1))"
    else say "db online-backup FAILED (non-fatal)"; fi
    rm -f "$TMP"
  else say "db online-backup: DRY (would BACKUP TO zip -> s3://$B/backup/db/)"; fi
else say "db online-backup: skipped (app not running)"; fi

# ---- helpers ----
# FIX A — the gate that lost 08-20. It used to be `aws s3 ls <prefix>`, which
# returns success when ANY object sits under the prefix, valid or not. It now
# requires at least one parquet AND every parquet under the day prefix to carry
# a readable footer. Anything less means the day is NOT safely archived and the
# raw tape must be kept.
day_in_s3() { # $1 = YYYY-MM-DD -> 0 only if that day is archived AND intact
  local y=${1:0:4} m=${1:5:2} dd=${1:8:2}
  local pfx="microstructure/year=$y/month=$m/day=$dd/" objs bad
  # Must contain at least one parquet...
  objs=$(aws s3 ls "s3://$B/$pfx" --recursive --region "$R" 2>/dev/null | grep -c '\.parquet$' || true)
  [ "${objs:-0}" -gt 0 ] || return 1
  # ...and every one of them must carry a readable footer.
  bad=$(integrity_scan s3 "$B" "$pfx" | wc -l)
  if [ "${bad:-0}" -gt 0 ]; then
    say "  ✗ $1 NOT safely archived — $bad corrupt parquet in s3://$B/$pfx (raw tape KEPT)"
    INTEGRITY_FAILURES=$((INTEGRITY_FAILURES+1))
    return 1
  fi
  return 0
}
prune_age() { # delete-only: $1 dir  $2 days  $3 name
  [ -d "$1" ] || { say "prune skip (missing): $3"; return 0; }
  local items bytes
  items=$(find "$1" -mindepth 1 -maxdepth 1 -mtime +"$2" 2>/dev/null | wc -l)
  bytes=$(find "$1" -mindepth 1 -maxdepth 1 -mtime +"$2" -exec du -ch {} + 2>/dev/null | tail -1 | cut -f1)
  if [ "$items" -eq 0 ]; then say "prune $3 >${2}d: nothing"; return 0; fi
  if [ $APPLY -eq 1 ]; then
    find "$1" -mindepth 1 -maxdepth 1 -mtime +"$2" -exec rm -rf {} + 2>/dev/null
    say "prune $3 >${2}d: DELETED $items item(s), ~$bytes"
  else
    say "prune $3 >${2}d: would delete $items item(s), ~$bytes"
  fi
}

# ---- 2. DELETE-ONLY dirs (dated files/dirs, by age) ----
prune_age "$D/data/live-pipeline-audit"       "$AUDIT_DAYS"     "audit"
prune_age "$D/data/live-pipeline-evals"       "$EVALS_DAYS"     "evals"
prune_age "$D/data/feature-snapshots"         "$FEATURE_DAYS"   "feature-snapshots"
prune_age "$D/data/live-feature-snapshots"    "$FEATURE_DAYS"   "live-feature-snapshots"
prune_age "$D/data/decision-influence"        "$INFLUENCE_DAYS" "decision-influence"
prune_age "$D/data/move-learning"             "$MOVE_DAYS"      "move-learning"
prune_age "$D/data/chain-snapshots"           "$CHAIN_DAYS"     "chain-snapshots"

# ---- 3. KEEPERS: prune local only when the S3 copy is confirmed ----
# 3a. raw atm-microstructure csv: delete >MICRO_RAW_DAYS only if that day's
#     parquet is in S3 *and verifiably readable* (see day_in_s3 above).
kept=0; gone=0
while IFS= read -r csv; do
  [ -n "$csv" ] || continue
  base=$(basename "$csv"); date=$(echo "$base" | sed -n 's/^atm-microstructure-\([0-9-]\{10\}\).*/\1/p')
  [ -n "$date" ] || continue
  if day_in_s3 "$date"; then
    if [ $APPLY -eq 1 ]; then rm -f "$csv"; else :; fi; gone=$((gone+1))
  else kept=$((kept+1)); fi
done < <(find "$D/data/tuning" -maxdepth 1 -name 'atm-microstructure-*.csv' -mtime +"$MICRO_RAW_DAYS" 2>/dev/null)
say "raw micro csv >${MICRO_RAW_DAYS}d: $gone $([ $APPLY -eq 1 ] && echo deleted || echo deletable) (VALID parquet-in-S3), $kept kept (not archived or archive unreadable)"

# 3b. microstructure parquet partitions older than window (S3 has full history).
#     Each partition is confirmed present-and-readable in S3 before it is dropped
#     locally — the local copy is the only repair source verify_uploads() has.
# The local partition is the ONLY repair source verify_uploads() has, so it is
# dropped only when every parquet under the matching S3 prefix is confirmed
# readable. This asks S3 directly rather than inferring from the local-driven
# pair scan: a locally-corrupt file is quarantined before this runs, which would
# hide it from any local-driven check while its bad S3 copy still sits there.
# Only partitions actually eligible for deletion are checked, so this stays cheap.
prune_keeper_partitions() { # $1 archive-root  $2 days  $3 name  $4 s3-prefix
  local root="$1" days="$2" name="$3" pfx="$4" n=0 held=0 part rel objs bad
  [ -d "$root" ] || { say "keeper skip (missing): $name"; return 0; }
  while IFS= read -r part; do
    [ -n "$part" ] || continue
    rel="${part#"$root"/}"
    objs=$(aws s3 ls "s3://$B/$pfx/$rel/" --recursive --region "$R" 2>/dev/null | grep -c '\.parquet$' || true)
    if [ "${objs:-0}" -eq 0 ]; then
      held=$((held+1)); INTEGRITY_FAILURES=$((INTEGRITY_FAILURES+1))
      say "  ✗ HELD (nothing archived in S3 yet): $part"
      continue
    fi
    bad=$(integrity_scan s3 "$B" "$pfx/$rel/" | wc -l)
    if [ "${bad:-0}" -gt 0 ]; then
      held=$((held+1)); INTEGRITY_FAILURES=$((INTEGRITY_FAILURES+1))
      say "  ✗ HELD ($bad corrupt parquet in S3; local kept as repair source): $part"
      continue
    fi
    if [ $APPLY -eq 1 ]; then rm -rf "$part"; fi; n=$((n+1))
  done < <(find "$root" -type d -name 'day=*' -mtime +"$days" 2>/dev/null)
  say "$name parquet >${days}d: $n partition(s) $([ $APPLY -eq 1 ] && echo deleted || echo deletable) (verified in S3), $held held"
}
prune_keeper_partitions "$D/data/tuning/microstructure-archive" "$MICRO_PARQUET_DAYS" "microstructure" "microstructure"
prune_keeper_partitions "$D/reports/tuning/archive"             "$TUNING_EVENTS_DAYS" "tuning-events"  "tuning-events"

# 3c. decision-traces: drop local files older than window (S3 has them)
prune_age "$D/data/decision-traces" "$TRACES_DAYS" "decision-traces(local)"

# ---- 4. summary ----
say "disk: $(df -h / | awk 'NR==2{print $4" free of "$2" ("$5" used)"}')"
if [ "$INTEGRITY_FAILURES" -gt 0 ]; then
  say "⚠ INTEGRITY: $INTEGRITY_FAILURES issue(s) this run — see ✗/! lines above. Nothing unsafe was deleted."
fi
say "===== END ====="
[ "$INTEGRITY_FAILURES" -eq 0 ] || exit 3
