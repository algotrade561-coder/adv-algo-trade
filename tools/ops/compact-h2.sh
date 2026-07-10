#!/usr/bin/env bash
# compact-h2.sh — SELF-CONTAINED H2 MVStore compaction (reclaims disk from DB file bloat).
#
# Does the WHOLE job so you can run it occasionally (~monthly) in a market-CLOSED window:
#   1. refuses to run during market hours (IST 09:10–15:35 Mon–Fri) unless FORCE=1
#   2. STOPS the app (systemd) and waits for the JVM to fully exit
#   3. clears any stale H2 lock, backs up the .mv.db
#   4. runs SHUTDOWN COMPACT (rewrites the store compactly)
#   5. ALWAYS restarts the app afterwards (even if compaction failed) + health-checks it
#
# Run as the `ubuntu` user (passwordless sudo assumed):
#     bash tools/ops/compact-h2.sh          # normal (market-closed)
#     FORCE=1 bash tools/ops/compact-h2.sh  # bypass the market-hours guard
#
# NOTE: this supersedes the old data/compact-h2.sh (which required a manual stop first).
set -uo pipefail   # deliberately NOT -e: we must always reach the app-restart step

APP_DIR="/home/ubuntu/adv-algo-trade"
DB_BASE="${APP_DIR}/data/adv-algo-trade"          # H2 base path — no .mv.db suffix
MVDB="${DB_BASE}.mv.db"
LOCK="${DB_BASE}.lock.db"
H2_JAR="${APP_DIR}/h2/h2-2.3.232.jar"
H2_USER="sa"; H2_PASS=""
SERVICE="adv-algo-trade"
HEALTH="http://localhost:8089/advalgotrade/actuator/health"
LOGF="${APP_DIR}/logs/compact-h2.log"
JVM_PAT="adv-algo-trade.*SNAPSHOT.jar"            # the running app JVM (not the H2 Shell)

log(){ echo "[$(date '+%F %T')] $*" | tee -a "$LOGF"; }

log "===================== H2 COMPACTION START ====================="
command -v java >/dev/null || { log "[ERROR] java not on PATH"; exit 1; }
[ -f "$MVDB" ]   || { log "[ERROR] DB not found: $MVDB"; exit 1; }
[ -f "$H2_JAR" ] || { log "[ERROR] H2 jar not found: $H2_JAR"; exit 1; }

# 0) Market-hours guard (IST) — never stop the app while the market is open.
DOW=$(TZ=Asia/Kolkata date +%u); HHMM=$(TZ=Asia/Kolkata date +%H%M); HHMM=$((10#$HHMM))
if [ "${FORCE:-0}" != "1" ] && [ "$DOW" -le 5 ] && [ "$HHMM" -ge 910 ] && [ "$HHMM" -le 1535 ]; then
  log "[ABORT] Market hours (IST, now $(TZ=Asia/Kolkata date '+%a %H:%M')). Run in a closed window or FORCE=1."
  exit 1
fi

BEFORE=$(du -h "$MVDB" | cut -f1)
log "DB before: $BEFORE   disk: $(df -h "$APP_DIR" | awk 'NR==2{print $4" free"}')"

# 1) STOP the app + wait for the JVM to actually exit (up to 40s).
log "Stopping $SERVICE ..."
sudo systemctl stop "$SERVICE"
for i in $(seq 1 40); do pgrep -af java 2>/dev/null | grep -q "$JVM_PAT" || break; sleep 1; done
if pgrep -af java 2>/dev/null | grep -q "$JVM_PAT"; then
  log "[ERROR] App JVM still alive after 40s — NOT compacting a live DB. Restarting service and aborting."
  sudo systemctl start "$SERVICE"; exit 1
fi
log "App stopped (JVM exited)."

# 2) Clear a stale lock (safe: no app running).
[ -f "$LOCK" ] && { log "Removing stale lock: $LOCK"; rm -f "$LOCK"; }

# 3) Safety backup of the DB.
BAK="${MVDB}.bak.$(date +%Y%m%d-%H%M%S)"
log "Backing up → $BAK"
cp -p "$MVDB" "$BAK" || log "[WARN] backup copy failed (continuing)"

# 4) Compact (empty output = success).
log "Running SHUTDOWN COMPACT (may take ~30s–few min) ..."
printf 'SHUTDOWN COMPACT;\n' | java -cp "$H2_JAR" org.h2.tools.Shell \
  -url "jdbc:h2:file:${DB_BASE};MODE=PostgreSQL;IFEXISTS=TRUE" \
  -user "$H2_USER" -password "$H2_PASS" 2>&1 | tee -a "$LOGF"
AFTER=$(du -h "$MVDB" | cut -f1)
log "DB after:  $AFTER  (was $BEFORE)"

# 5) START the app again — ALWAYS, even if compaction failed — and health-check.
log "Starting $SERVICE ..."
sudo systemctl start "$SERVICE"
OK=0
for i in $(seq 1 12); do
  code=$(curl -s -o /dev/null -w '%{http_code}' "$HEALTH" 2>/dev/null)
  log "health attempt $i: ${code:-none}"
  [ "$code" = "200" ] && { OK=1; break; }
  sleep 8
done
if [ "$OK" = 1 ]; then
  log "App HEALTHY after compaction. Backup at $BAK — delete once confident: rm -f $BAK"
else
  log "[WARN] App not healthy yet — check logs. To ROLLBACK: sudo systemctl stop $SERVICE; cp $BAK $MVDB; sudo systemctl start $SERVICE"
fi
log "===================== H2 COMPACTION DONE  ====================="
