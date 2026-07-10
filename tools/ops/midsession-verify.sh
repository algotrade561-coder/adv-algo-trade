#!/usr/bin/env bash
# midsession-verify.sh — mid-session (e.g. 11:00 IST) health check of the TUNING + RESEARCH loop against
# TODAY's PARTIAL data, so issues are caught and fixed live instead of waiting for EOD/next day.
# Read-only except for running research (which is nice/ionice-capped). Run on the box:
#   bash tools/ops/midsession-verify.sh
# Exit 0 always; prints [OK]/[⚠] per check + an ISSUES summary at the end.
set -uo pipefail
cd "$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DATE="$(TZ=Asia/Kolkata date +%F)"
EV="reports/tuning/events/$DATE"
ISSUES=0
ok(){   echo "  [OK] $*"; }
bad(){  echo "  [⚠] $*"; ISSUES=$((ISSUES+1)); }
hdr(){  echo; echo "== $* =="; }

echo "########## MID-SESSION VERIFY  $DATE  ($(TZ=Asia/Kolkata date '+%H:%M') IST) ##########"

hdr "1. Capture present for today"
if [ -d "$EV" ]; then
  ok "events dir exists: $EV"
  ls -1 "$EV" | sed 's/^/     strategy: /' | head -20
else
  bad "NO events dir for today ($EV) — capture not writing. Check app up + capture toggles."
fi

hdr "2. ORDER_FILLED emitted (fills populate) — was 0 forever before 07-02"
FILLED=$(grep -rhc "ORDER_FILLED" "$EV"/*/execution.csv 2>/dev/null | awk '{s+=$1} END{print s+0}')
[ "$FILLED" -gt 0 ] && ok "ORDER_FILLED rows today: $FILLED" || bad "0 ORDER_FILLED rows — fill capture broken (or no fills yet this morning)."

hdr "3. Causal exit reasons (STOP_LOSS/TARGET/... not just WATCHDOG_FILLED_EXIT) — batch-6 fix. exitReason=col6"
if ls "$EV"/*/exit.csv >/dev/null 2>&1; then
  echo "   exitReason distribution (col 6):"
  awk -F, 'FNR>1{print $6}' "$EV"/*/exit.csv 2>/dev/null | sort | uniq -c | sort -rn | sed 's/^/     /' | head
  CAUSAL=$(awk -F, 'FNR>1{print $6}' "$EV"/*/exit.csv 2>/dev/null | grep -icE "STOP_LOSS|TARGET|TRAIL|SCALP|SQUAREOFF|PROFIT|OI_FLIP")
  GENERIC=$(awk -F, 'FNR>1{print $6}' "$EV"/*/exit.csv 2>/dev/null | grep -icE "WATCHDOG_FILLED_EXIT")
  if [ "$CAUSAL" -gt 0 ]; then ok "causal reasons present: $CAUSAL (generic watchdog: $GENERIC)";
  elif [ "$GENERIC" -gt 0 ]; then bad "ALL $GENERIC exits still generic WATCHDOG_FILLED_EXIT — causal-reason fix not effective (only counts trades opened+closed AFTER the batch-6 deploy).";
  else echo "   (no bot exits yet this morning — recheck after a few closes)"; fi
else
  echo "   (no exit.csv yet — no closed trades this morning)"
fi

hdr "4. Pipeline exits carry real MAE/MFE (not 0.0) — batch-3 fix. maePct=col11 mfePct=col12"
if ls "$EV"/*/exit.csv >/dev/null 2>&1; then
  TOT=$(awk -F, 'FNR>1' "$EV"/*/exit.csv 2>/dev/null | wc -l)
  NZ=$(awk -F, 'FNR>1 && (($11+0)!=0 || ($12+0)!=0){n++} END{print n+0}' "$EV"/*/exit.csv 2>/dev/null)
  if [ "$TOT" -gt 0 ] && [ "$NZ" -gt 0 ]; then ok "$NZ / $TOT exits have non-zero MAE/MFE";
  elif [ "$TOT" -gt 0 ]; then bad "all $TOT exits have MAE/MFE = 0 — tracker snapshot not flowing (OK if trades held <1 tick / opened pre-deploy).";
  else echo "   (no exits yet)"; fi
else echo "   (no exits yet)"; fi

hdr "5. Forward-checkpoint reject arm: named blockers + provenance stamps (batches 1/4)"
FC=$(cat "$EV"/*/forward_checkpoint.csv 2>/dev/null | grep -c "reject" )
if [ "${FC:-0}" -gt 0 ]; then
  ok "reject checkpoints today: $FC"
  echo "   sample attr_extra (want: blocker non-empty, rangePoints/anchorDeltaSec present):"
  grep -rh "reject" "$EV"/*/forward_checkpoint.csv 2>/dev/null | grep -oE '\{[^}]*"source"[^}]*\}' | head -2 | sed 's/^/     /'
  BLANK=$(grep -rh "reject" "$EV"/*/forward_checkpoint.csv 2>/dev/null | grep -cE '"blocker":""')
  [ "$BLANK" -eq 0 ] && ok "no blank blockers" || bad "$BLANK reject rows still have blank blocker."
  grep -rhq "rangePoints" "$EV"/*/forward_checkpoint.csv 2>/dev/null && ok "provenance stamps present (rangePoints)" || bad "no provenance stamps (rangePoints) — batch-4 not effective."
else
  echo "   (no reject checkpoints yet — the sweep runs every 5 min with a 31-min ripeness; expect data after ~11:00)"
fi

hdr "6. non-OI eval rows carry spot (missed-opportunity needs it) — batch-1 enrichment"
for s in directional_buy momentum scalping; do
  if [ -f "$EV/$s/evaluation.csv" ]; then
    grep -qE '"spot":[0-9]' "$EV/$s/evaluation.csv" && ok "$s: spot present" || bad "$s: NO spot in eval attr_extra."
  fi
done

hdr "7. RESEARCH lite over today's partial data (case_studies NameError fix + brief health header)"
RESEARCH_TIER=lite RESEARCH_DATE="$DATE" timeout 480 bash tools/edge-study/run-research.sh >/tmp/research-mid.log 2>&1
tail -3 /tmp/research-mid.log | sed 's/^/     /'
grep -qiE "NameError|Traceback" /tmp/research-mid.log && bad "research run threw a Python error (see /tmp/research-mid.log)" || ok "research ran without Python errors"
BRIEF="reports/research/$DATE/research-report.html"
if [ -f "$BRIEF" ]; then
  ok "brief generated: $BRIEF"
  grep -q "Data health" "$BRIEF" && ok "data-health header present" || bad "data-health header MISSING in brief."
else bad "brief NOT generated ($BRIEF)."; fi

hdr "8. Latest TUNING report (generate a FORCED one from the UI first) — plugin failures / new sections"
RPT=$(ls -1t reports/tuning/html/*.html 2>/dev/null | head -1)
if [ -n "$RPT" ]; then
  AGE=$(( ( $(date +%s) - $(date -r "$RPT" +%s) ) / 60 ))
  echo "   latest report: $RPT (${AGE} min old)"
  [ "$AGE" -gt 60 ] && bad "latest report is ${AGE} min old — generate a fresh FORCED report from the UI for today, then re-run."
  PF=$(grep -oc "Plugin failed\|Query failed\|class=\"error\"" "$RPT" 2>/dev/null); PF=${PF:-0}
  [ "$PF" -eq 0 ] && ok "no plugin/query failures" || bad "$PF plugin/query failures in the report."
  grep -q "Capture health" "$RPT" && ok "capture-health section present" || bad "capture-health section MISSING."
  grep -q "Per-strategy scorecard" "$RPT" && ok "scorecard present" || bad "scorecard MISSING."
else bad "no tuning report HTML found — generate one from the UI (force)."; fi

hdr "9. App-log exceptions in tuning/research/capture paths (last ~90 min)"
LOG=logs/application/algo-trade.log
if [ -f "$LOG" ]; then
  EXC=$(grep -iE "Tuning|ForwardCheckpoint|CaptureBridge|Scorecard|CaptureHealth|OrderFillWatchdog" "$LOG" 2>/dev/null | grep -iE "Exception|ERROR|failed" | grep -viE "403|TokenException" | tail -8)
  if [ -n "$EXC" ]; then bad "exceptions in tuning/capture paths:"; echo "$EXC" | sed 's/^/     /'; else ok "no tuning/capture-path exceptions"; fi
fi

echo; echo "########## RESULT: $ISSUES issue(s) flagged ##########"
[ "$ISSUES" -eq 0 ] && echo "All mid-session checks passed for the partial day." || echo "Fix the [⚠] items above, hot-deploy, and re-run."
