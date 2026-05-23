    package com.algo.trade.strategy.oimomentum;

import com.algo.trade.data.ChainSnapshot;
import com.algo.trade.domain.IndexType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Operator Framework Service
 *
 * Bridges the ChainSnapshot capture system with the OperatorAccumulationDetector.
 * Wired into OIMomentumStrategy for early institutional footprint detection.
 *
 * === WHY THIS EXISTS — MAY 22 ROOT CAUSE ===
 *
 * May 22 timeline:
 *   09:15  Spot 23685  |  PE OI@23750 = 964K,    PE OI@23700 = 5.76M
 *   11:30  Spot 23757  |  PE OI@23750 = 6.94M (+619%), PE OI@23700 = 11.85M (+106%)
 *                         CE OI@23800 = 12.60M (+50%),  CE OI@23900 = 7.28M (+57%)
 *
 *   11:42  CASE5_SKIP (ceΔ=0, peΔ=0 — WS ticks stale) → signal blocked
 *   11:46  OI Momentum fires BUY CE @23800, spot already at 23825 → 25pt LATE ENTRY
 *
 * The operator buildup was screaming BULLISH for 90+ minutes before the entry fired.
 * The current system's 3-minute rolling OI window from WebSocket ticks missed all of it.
 *
 * === HOW IT FIXES THIS ===
 *
 * 1. Opening baseline from first 09:15 ChainSnapshot
 * 2. Every subsequent snapshot → delta analysis vs opening
 * 3. Operator score fed into entry case matrix:
 *    - CASE5_OI_UNAVAILABLE → upgraded to CASE2/CASE3 if score ≥ 65
 *    - CASE5_PCR_VS_MOMENTUM → overridden if score ≥ 75
 *    - Confidence bonus (+5 to +20 pts) added to DIRECTIONAL_BUY scorer
 */
@Service
public class OperatorFrameworkService {

    private static final Logger log = LoggerFactory.getLogger(OperatorFrameworkService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime OPENING_WINDOW_END = LocalTime.of(9, 22);
    private static final LocalTime MARKET_OPEN = LocalTime.of(9, 15);

    private final OperatorAccumulationDetector detector;

    private final ConcurrentHashMap<IndexType, Boolean> openingBaselineSet = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<IndexType, Instant> lastAnalysisTime = new ConcurrentHashMap<>();

    public OperatorFrameworkService(OperatorAccumulationDetector detector) {
        this.detector = detector;
    }

    /**
     * Called whenever a new ChainSnapshot is captured (every ~5 minutes).
     * Handles baseline registration and incremental operator analysis.
     */
    public void onChainSnapshot(IndexType index, ChainSnapshot snapshot) {
        if (snapshot == null || snapshot.strikes() == null || snapshot.strikes().isEmpty()) {
            return;
        }

        LocalTime snapshotTime = snapshot.timestamp().atZone(IST).toLocalTime();
        Map<Integer, OperatorAccumulationDetector.StrikeSnapshot> strikeMap = buildStrikeMap(snapshot);

        // Register opening baseline: preferred 09:15–09:22; fallback = first post-open snapshot
        if (!openingBaselineSet.getOrDefault(index, false)) {
            if (snapshotTime.isBefore(MARKET_OPEN)) {
                return;
            }
            detector.registerOpeningSnapshot(index, strikeMap);
            openingBaselineSet.put(index, true);
            if (snapshotTime.isBefore(OPENING_WINDOW_END)) {
                log.info("[OperatorFW] Opening baseline registered for {} at {} spot={}",
                        index, snapshotTime, snapshot.spot());
                return; // baseline-only on first snapshot in the preferred window
            }
            log.warn("[OperatorFW] Late opening baseline for {} at {} (missed 09:15–09:22 window) spot={}",
                    index, snapshotTime, snapshot.spot());
            // fall through — analyze this snapshot immediately
        }

        // Throttle: don't re-analyze more than once every 4 minutes
        Instant last = lastAnalysisTime.get(index);
        if (last != null && Instant.now().isBefore(last.plusSeconds(240))) {
            return;
        }

        // Run accumulation analysis
        OperatorAccumulationDetector.OperatorSignal signal =
                detector.analyze(index, strikeMap, snapshot.spot(), snapshot.atmStrike());
        lastAnalysisTime.put(index, Instant.now());

        if (signal.getScore() >= 50) {
            log.info("[OperatorFW] ACTIONABLE: {} | {}", index, signal.getSummary());
            if (signal.canOverrideCase5()) {
                log.info("[OperatorFW] *** CASE5 OVERRIDE ELIGIBLE *** {} dir={} score={}",
                        index, signal.getDirection(), signal.getScore());
            }
        }
    }

    /**
     * Get the latest operator signal for an index.
     */
    public OperatorAccumulationDetector.OperatorSignal getOperatorSignal(IndexType index) {
        return detector.getSignal(index);
    }

    /**
     * Evaluate whether operator conviction can upgrade a CASE5_SKIP to an actionable case.
     *
     * @param index       The index.
     * @param momentumDir The momentum direction (+1 up, -1 down).
     * @param case5Reason The exact CASE5 block reason from evaluateEntryCaseDetail().
     * @return Upgrade label (e.g. "CASE2_OPERATOR[score=72]") or null if no upgrade.
     */
    public String evaluateCase5Override(IndexType index, int momentumDir, String case5Reason) {
        OperatorAccumulationDetector.OperatorSignal signal = detector.getSignal(index);

        if (!signal.isFresh()) {
            log.debug("[OperatorFW] Case5 override skipped: signal stale for {}", index);
            return null;
        }
        if (!signal.alignsWith(momentumDir)) {
            log.debug("[OperatorFW] Case5 override skipped: operator dir={} vs momentum dir={} for {}",
                    signal.getDirection(), momentumDir, index);
            return null;
        }

        // OI ticks stale (WebSocket zero) → operator chain data stands in
        if (case5Reason.contains("OI_UNAVAILABLE") || case5Reason.contains("NO_RULE")) {
            if (signal.canOverrideCase5()) {  // score >= 65
                log.info("[OperatorFW] {} CASE5_OI_UNAVAILABLE → CASE2_OPERATOR (score={})",
                        index, signal.getScore());
                return "CASE2_OPERATOR[score=" + signal.getScore() + "]";
            }
            if (signal.canConfirmOISignal()) {  // score >= 50
                log.info("[OperatorFW] {} CASE5_OI_UNAVAILABLE → CASE3_OPERATOR (score={})",
                        index, signal.getScore());
                return "CASE3_OPERATOR[score=" + signal.getScore() + "]";
            }
        }

        // PCR vs momentum: operator conviction overrides PCR noise (PCR lags behind smart money)
        if (case5Reason.contains("PCR_VS_MOMENTUM")) {
            if (signal.getScore() >= 75) {
                log.info("[OperatorFW] {} CASE5_PCR_VS_MOMENTUM → CASE1_OPERATOR_OVERRIDE (score={})",
                        index, signal.getScore());
                return "CASE1_OPERATOR_OVERRIDE[score=" + signal.getScore() + "]";
            }
        }

        return null;
    }

    /**
     * Confidence bonus points to add to the DIRECTIONAL_BUY signal score.
     * Helps signals stuck at 60% cross the 70% entry threshold.
     *
     * @param index       Index.
     * @param momentumDir The momentum direction.
     * @return Bonus points (0–20). At operator score 65 → +8; score 70 → +10 (lifts 60% base to 70%).
     */
    public int getConfidenceBonus(IndexType index, int momentumDir) {
        OperatorAccumulationDetector.OperatorSignal signal = detector.getSignal(index);
        if (!signal.isFresh() || !signal.alignsWith(momentumDir)) {
            return 0;
        }
        // Linear scale: 0 pts at score=45, 20 pts at score=95
        int bonus = (int) Math.min(20, Math.max(0, (signal.getScore() - 45) * 0.4));
        if (bonus > 0) {
            log.debug("[OperatorFW] Confidence bonus for {} dir={}: +{}pts (score={})",
                    index, momentumDir, bonus, signal.getScore());
        }
        return bonus;
    }

    /**
     * Convert ChainSnapshot to the StrikeSnapshot map format expected by the detector.
     */
    private Map<Integer, OperatorAccumulationDetector.StrikeSnapshot> buildStrikeMap(ChainSnapshot snapshot) {
        Map<Integer, OperatorAccumulationDetector.StrikeSnapshot> map = new HashMap<>();
        for (ChainSnapshot.StrikeData s : snapshot.strikes()) {
            map.put(s.strike(), new OperatorAccumulationDetector.StrikeSnapshot(
                    s.strike(),
                    s.ceOI(),
                    s.peOI(),
                    s.ceIV(),
                    s.peIV(),
                    snapshot.timestamp()
            ));
        }
        return map;
    }

    /**
     * Reset at start of each trading day.
     */
    public void resetForNewDay() {
        openingBaselineSet.clear();
        lastAnalysisTime.clear();
        detector.resetForNewDay();
        log.info("[OperatorFW] Reset for new trading day");
    }
}
