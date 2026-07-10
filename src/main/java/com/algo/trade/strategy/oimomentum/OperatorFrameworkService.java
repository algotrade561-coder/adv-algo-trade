    package com.algo.trade.strategy.oimomentum;

import com.algo.trade.data.ChainSnapshot;
import com.algo.trade.domain.IndexType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
    private final OperatorBaselineStore baselineStore;

    private final ConcurrentHashMap<IndexType, Boolean> openingBaselineSet = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<IndexType, Instant> lastAnalysisTime = new ConcurrentHashMap<>();

    // ── FAST-OI PATH (2026-07-01) ──────────────────────────────────────────────
    // The operator framework was built (see class header) to beat the "3-minute rolling
    // OI window" that missed the May-22 buildup — yet it still only re-analyses when a
    // 5-minute ChainSnapshot lands (onChainSnapshot, throttled a further 4 min). Empirical
    // microstructure capture (data/tuning/atm-microstructure-*.csv) shows ATM OI actually
    // changes ~every 55s (up to 9 changes/min in bursts). So we ALSO refresh the operator
    // signal directly from per-tick LiveInstrumentCache OI on the 1-second OI-momentum loop,
    // against the same 09:15 baseline. detector.analyze() writes the shared latestSignals
    // store, so every consumer (getConfidenceBonus / getOperatorSignal / evaluateCase5Override)
    // transparently sees the fresher score with no extra wiring.
    //   Flag-gated: oi-momentum.fast-oi.enabled (default true = LIVE) — instant revert to the
    //   5-min-snapshot-only behaviour by setting it false. Grep "[FastOI]" on EC2 to verify.
    private final ConcurrentHashMap<IndexType, Instant> lastFastAnalysisTime = new ConcurrentHashMap<>();

    @Value("${oi-momentum.fast-oi.enabled:true}")
    private boolean fastOiEnabled;

    /** How often (seconds) the per-tick live-cache operator refresh may re-run per index. */
    @Value("${oi-momentum.fast-oi.operator-refresh-seconds:15}")
    private int fastOperatorRefreshSeconds;

    /**
     * Minimum operator score to CREATE an entry when OI is genuinely unavailable (CASE5_OI_UNAVAILABLE /
     * NO_RULE). 2026-07-03: previously the ≥50 "confirm" threshold ({@code canConfirmOISignal}) was misused
     * to create no-OI entries — a confirm bar should confirm an EXISTING OI reading, not stand in for a
     * missing one. A no-OI entry has zero OI confirmation, so it must clear a HIGH-conviction bar. Default
     * 70; raise toward 100 (or effectively disable) to be stricter. This is a dedicated threshold — it does
     * NOT reuse {@code canOverrideCase5()} (65), which is shared by two other call sites.
     */
    @Value("${oi-momentum.operator.oi-unavailable-min-score:70}")
    private int oiUnavailableMinScore;

    public OperatorFrameworkService(OperatorAccumulationDetector detector,
                                    OperatorBaselineStore baselineStore) {
        this.detector = detector;
        this.baselineStore = baselineStore;
    }

    public boolean isFastOiEnabled() {
        return fastOiEnabled;
    }

    /**
     * On startup, if a baseline file for today already exists on disk (i.e. an earlier
     * JVM instance captured the 09:15 baseline before crashing/restarting), restore it
     * into the detector so the rest of the session continues with the original opening
     * reference instead of falling back to a stale "Late opening baseline".
     */
    @jakarta.annotation.PostConstruct
    void restoreBaselineFromDisk() {
        Map<IndexType, Map<Integer, OperatorAccumulationDetector.StrikeSnapshot>> persisted =
                baselineStore.loadToday();
        if (persisted.isEmpty()) {
            return;
        }
        for (Map.Entry<IndexType, Map<Integer, OperatorAccumulationDetector.StrikeSnapshot>> e
                : persisted.entrySet()) {
            IndexType index = e.getKey();
            Map<Integer, OperatorAccumulationDetector.StrikeSnapshot> snap = e.getValue();
            if (snap == null || snap.isEmpty()) continue;
            detector.registerOpeningSnapshot(index, snap);
            openingBaselineSet.put(index, true);
            log.info("[OperatorFW] Recovered opening baseline for {} from disk ({} strikes) "
                    + "- restart will not degrade operator framework", index, snap.size());
        }
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
            // Persist immediately so a subsequent restart recovers this baseline
            // instead of falling back to a mid-day "Late opening baseline".
            baselineStore.save(index, snapshot.timestamp().atZone(IST).toLocalDate(), strikeMap);
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
     * FAST-OI PATH — refresh the operator signal from per-tick OI (LiveInstrumentCache) rather
     * than waiting for the next 5-minute ChainSnapshot. Called from the 1-second OI-momentum
     * loop with a strike→OI map built from the live cache (ATM±N band). Compares against the
     * SAME 09:15 opening baseline the snapshot path uses, so scores are directly comparable —
     * only fresher. No-op unless {@code oi-momentum.fast-oi.enabled=true} AND the opening
     * baseline has already been registered (which happens off the first 09:15 snapshot).
     *
     * @param index         the index.
     * @param liveStrikeMap strike → current per-tick OI snapshot (IV fields may be 0; analyze()
     *                      uses OI only).
     * @param spot          current spot.
     * @param atmStrike     current ATM strike.
     */
    public void refreshFromLiveCache(IndexType index,
                                     Map<Integer, OperatorAccumulationDetector.StrikeSnapshot> liveStrikeMap,
                                     double spot, int atmStrike) {
        if (!fastOiEnabled) return;
        if (liveStrikeMap == null || liveStrikeMap.isEmpty()) return;
        // Baseline must exist first — the fast path measures buildup vs the 09:15 opening, which
        // is only registered off a ChainSnapshot. Before that, do nothing (snapshot path owns it).
        if (!openingBaselineSet.getOrDefault(index, false)) return;

        int throttle = Math.max(3, fastOperatorRefreshSeconds);
        Instant last = lastFastAnalysisTime.get(index);
        if (last != null && Instant.now().isBefore(last.plusSeconds(throttle))) return;

        OperatorAccumulationDetector.OperatorSignal prev = detector.getSignal(index);
        int prevScore = prev.getScore();
        int prevDir = prev.getDirection();
        Instant prevComputed = prev.getComputedAt();

        OperatorAccumulationDetector.OperatorSignal signal =
                detector.analyze(index, liveStrikeMap, spot, atmStrike);
        lastFastAnalysisTime.put(index, Instant.now());

        // Lead-time = how much staler the previous (snapshot-cycle) signal was. This is the whole
        // point of the feature: how many seconds earlier the fast path surfaces an operator move.
        long stalenessSec = prevComputed.getEpochSecond() > 0
                ? Math.max(0, Instant.now().getEpochSecond() - prevComputed.getEpochSecond())
                : -1;

        boolean scoreChanged = signal.getScore() != prevScore || signal.getDirection() != prevDir;
        boolean actionable = signal.getScore() >= 40 || prevScore >= 40;
        if (scoreChanged || actionable) {
            log.info("[FastOI] {} live-tick operator refresh: score {}→{} dir {}→{} | prevSignalAge={}s window={} strikes | {}",
                    index, prevScore, signal.getScore(), prevDir, signal.getDirection(),
                    stalenessSec < 0 ? "n/a" : stalenessSec, liveStrikeMap.size(), signal.getSummary());
            if (signal.canOverrideCase5() && !prev.canOverrideCase5()) {
                log.info("[FastOI] *** {} CASE5-OVERRIDE now ELIGIBLE via fast path *** dir={} score={} "
                        + "(snapshot path would still be at {}) — early institutional footprint captured",
                        index, signal.getDirection(), signal.getScore(), prevScore);
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

        // OI genuinely unavailable → NO OI reading to confirm. A no-OI entry is created solely from the
        // operator signal, so it must clear the dedicated high-conviction bar (oiUnavailableMinScore, def 70).
        // 2026-07-03 FIX: removed the old CASE3 ≥50 "confirm" door — a confirm threshold must not CREATE an
        // entry from a missing OI reading (it let a score-63 SENSEX 78000CE trade through into a flat-OI,
        // light-volume tape). canOverrideCase5()'s 65 is intentionally NOT reused here (2 other call sites).
        if (case5Reason.contains("OI_UNAVAILABLE") || case5Reason.contains("NO_RULE")) {
            if (signal.isFresh() && signal.getScore() >= oiUnavailableMinScore) {
                log.info("[OperatorFW] {} CASE5_OI_UNAVAILABLE → CASE2_OPERATOR (score={} >= {})",
                        index, signal.getScore(), oiUnavailableMinScore);
                return "CASE2_OPERATOR[score=" + signal.getScore() + "]";
            }
            log.info("[OperatorFW] {} CASE5_OI_UNAVAILABLE BLOCKED — operator score {} < {} (no OI confirmation)",
                    index, signal.getScore(), oiUnavailableMinScore);
            return null;
        }

        // PCR vs momentum: operator conviction overrides PCR noise (PCR lags behind smart money)
        if (case5Reason.contains("PCR_VS_MOMENTUM")) {
            if (signal.getScore() >= 65) {
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
     * @return Bonus points (0–25). At operator score 65 → +10; score 80 → +17; score 95 → +25.
     */
    public int getConfidenceBonus(IndexType index, int momentumDir) {
        OperatorAccumulationDetector.OperatorSignal signal = detector.getSignal(index);
        if (!signal.isFresh() || !signal.alignsWith(momentumDir)) {
            return 0;
        }
        // Linear scale: 0 pts at score=45, 25 pts at score=95 (was capped at 20).
        // Increased cap to ensure strong operator conviction (80+) can push borderline
        // entries over the bias floor in flat-spot regimes (June 19 starvation).
        int bonus = (int) Math.min(25, Math.max(0, (signal.getScore() - 45) * 0.5));
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
