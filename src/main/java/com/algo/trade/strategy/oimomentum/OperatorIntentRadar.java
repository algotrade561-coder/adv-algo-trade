package com.algo.trade.strategy.oimomentum;

import com.algo.trade.domain.IndexType;
import com.algo.trade.domain.OptionInstrument;
import com.algo.trade.marketdata.ExpiryCalendar;
import com.algo.trade.marketdata.LiveInstrumentCache;
import com.algo.trade.risk.MarketGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OPERATOR INTENT RADAR v2 — anticipatory layer on top of raw OI/PCR momentum.
 *
 * Predicts where operators will push price by detecting institutional OI accumulation
 * patterns BEFORE price moves. Designed to catch moves earlier and maximize positions.
 *
 * 8 detection modules:
 *
 * 1. OI MAGNET ENGINE — heavy OI buildup >100pts from spot = "magnet levels"
 * 2. HOURLY TRIGGER WATCHER — focused scan at operator cycle times (9:30, 10:30...)
 * 3. CROSS-INDEX RADAR — confirms intent across NIFTY/BANKNIFTY/SENSEX
 * 4. DYNAMIC MAX PAIN — 15-min rolling recalc, detects 50+pt shifts
 * 5. REVERSAL GOVERNOR — flags when spot hits magnet (exit acceleration + flip)
 * 6. BIAS PERSISTENCE & DECAY — locks "operator intent active" after 3 persistent ticks
 * 7. OI LADDERING DETECTOR — sequential OI additions across multiple strikes = roadmap
 * 8. LIQUIDITY AWARENESS — widening spreads + OI build = stealth positioning
 *
 * Position Strategy:
 * - PRE-EMPTIVE SCALING: probe entries at bias ≥10 when magnet + cycle align
 * - DUAL-LEG CAPTURE: reversal zone + flip signal for both-direction profits
 * - EXPIRY PINNING OVERRIDE: reduced thresholds on expiry days
 *
 * Integration: [+0..30] bonus in OIMomentumStrategy.computeBiasScore()
 */
@Service
public class OperatorIntentRadar {

    private static final Logger log = LoggerFactory.getLogger(OperatorIntentRadar.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final LiveInstrumentCache liveInstrumentCache;
    private final ExpiryCalendar expiryCalendar;
    private final MarketGuard marketGuard;

    /** Cross-segment lag detector — exploits Options → Futures → Spot sequencing */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private CrossSegmentLagDetector lagDetector;

    /** Operator tactics — rotation, event spikes, trap detection, basket awareness */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private OperatorTacticsEngine tacticsEngine;

    /** GEX — dealer hedging regime (dampening vs amplifying) */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private GammaExposureService gexService;

    /** Futures basis — premium/discount expansion as directional lead */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private FuturesBasisTracker basisTracker;

    private final ConcurrentHashMap<IndexType, RadarState> states = new ConcurrentHashMap<>();

    /** Operator hourly cycle times (IST) */
    private static final List<LocalTime> OPERATOR_CYCLE_TIMES = List.of(
            LocalTime.of(9, 30), LocalTime.of(10, 30), LocalTime.of(11, 30),
            LocalTime.of(12, 30), LocalTime.of(13, 30), LocalTime.of(14, 30)
    );
    private static final int CYCLE_WINDOW_MINUTES = 5;

    // ── Magnet thresholds (adjustable for expiry) ──
    private static final long MIN_MAGNET_OI_NORMAL = 500_000L;
    private static final long MIN_MAGNET_OI_EXPIRY = 250_000L;

    private static final int MIN_DISTANCE_NIFTY = 100;
    private static final int MIN_DISTANCE_BANKNIFTY = 200;
    private static final int MIN_DISTANCE_SENSEX = 300;

    // ── Persistence thresholds ──
    private static final int PERSISTENCE_LOCK_TICKS = 2;    // was 3 — faster lock for strong signals
    private static final int PERSISTENCE_LOCK_THRESHOLD = 12; // was 15 — lower bar to start persistence
    private static final int DECAY_RATE_PER_TICK = 1;       // was 2 — slower decay, holds conviction longer
    /** Exit signal: if bias decays below this for 3 consecutive ticks → exit recommended */
    private static final int DECAY_EXIT_THRESHOLD = 10;
    private static final int DECAY_EXIT_TICKS = 3;

    // ── Ladder detection ──
    private static final int MIN_LADDER_STRIKES = 3;
    private static final long MIN_LADDER_OI_PER_STRIKE = 200_000L;

    // ── Max pain shift thresholds ──
    private static final int MAX_PAIN_SHIFT_NIFTY = 50;
    private static final int MAX_PAIN_SHIFT_BANKNIFTY = 100;

    // ── Adaptive threshold regime ──
    /** Tracks cumulative session OI for adaptive thresholds */
    private final ConcurrentHashMap<IndexType, Long> sessionBaselineOi = new ConcurrentHashMap<>();
    private volatile long lastBaselineCalcMs = 0;

    public OperatorIntentRadar(LiveInstrumentCache liveInstrumentCache,
                                ExpiryCalendar expiryCalendar,
                                MarketGuard marketGuard) {
        this.liveInstrumentCache = liveInstrumentCache;
        this.expiryCalendar = expiryCalendar;
        this.marketGuard = marketGuard;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Full radar evaluation — returns bonus + signals + state.
     * Called every tick from computeBiasScore().
     */
    public IntentSignal evaluate(IndexType indexType, int momentumDir) {
        RadarState state = states.computeIfAbsent(indexType, RadarState::new);
        LocalTime now = LocalTime.now(IST);
        boolean isExpiry = expiryCalendar.isExpiryDay(indexType);

        int bonus = 0;
        StringBuilder signals = new StringBuilder();
        Attribution attr = new Attribution();

        // Module 1: OI Magnet Engine (with adaptive thresholds)
        MagnetResult magnet = evaluateMagnets(indexType, state, momentumDir, isExpiry);
        if (magnet.bonus > 0) {
            bonus += magnet.bonus;
            attr.magnetBonus = magnet.bonus;
            signals.append(String.format(" MAGNET(+%d,%d)", magnet.bonus, magnet.targetStrike));
        }

        // Module 2: Hourly Trigger Watcher
        int cycleBonus = evaluateHourlyCycle(indexType, state, momentumDir, now);
        if (cycleBonus > 0) {
            bonus += cycleBonus;
            attr.cycleBonus = cycleBonus;
            signals.append(String.format(" CYCLE(+%d)", cycleBonus));
        }

        // Module 3: Cross-Index Radar
        int crossBonus = evaluateCrossIndex(indexType, momentumDir);
        if (crossBonus > 0) {
            bonus += crossBonus;
            attr.crossBonus = crossBonus;
            signals.append(String.format(" CROSS(+%d)", crossBonus));
        }

        // Module 4: Dynamic Max Pain Shift
        int maxPainBonus = evaluateMaxPainShift(indexType, state, momentumDir);
        if (maxPainBonus > 0) {
            bonus += maxPainBonus;
            attr.maxPainBonus = maxPainBonus;
            signals.append(String.format(" MPSHIFT(+%d)", maxPainBonus));
        }

        // Module 7: OI Laddering
        int ladderBonus = evaluateLaddering(indexType, state, momentumDir, isExpiry);
        if (ladderBonus > 0) {
            bonus += ladderBonus;
            attr.ladderBonus = ladderBonus;
            signals.append(String.format(" LADDER(+%d,%d)", ladderBonus, state.ladderStrikeCount));
        }

        // Module 8: Liquidity Awareness (stealth positioning)
        int liqBonus = evaluateLiquidity(indexType, momentumDir);
        if (liqBonus > 0) {
            bonus += liqBonus;
            attr.liquidityBonus = liqBonus;
            signals.append(String.format(" STEALTH(+%d)", liqBonus));
        }

        // Module 9: Cross-Segment Lag Detection (Options → Futures → Spot sequencing)
        int lagBonus = 0;
        if (lagDetector != null) {
            CrossSegmentLagDetector.LagSignal lag = lagDetector.evaluate(indexType, momentumDir);
            lagBonus = lag.bonus();
            if (lagBonus > 0) {
                bonus += lagBonus;
                signals.append(String.format(" LAG(+%d%s)", lagBonus, lag.signals()));
                if (lag.lagWindowOpen()) {
                    signals.append(String.format(" WINDOW(%ds)", lag.lagRemainingSeconds()));
                }
            }
        }

        // Module 10: Operator Tactics Engine (rotation, events, traps, basket)
        if (tacticsEngine != null) {
            OperatorTacticsEngine.TacticsSignal tactics = tacticsEngine.evaluate(
                    indexType, momentumDir, state.lastMaxPainStrike);
            if (tactics.netBonus() != 0) {
                bonus += tactics.netBonus(); // Can be negative (trap penalty)
                signals.append(String.format(" TACTICS(%+d%s)", tactics.netBonus(), tactics.signals()));
            }
            if (tactics.trapActive()) {
                signals.append(" ⚠TRAP");
            }
        }

        // Module 11: GEX dealer regime — amplifying regime boosts momentum entries
        if (gexService != null) {
            GammaExposureService.GexSnapshot gex = gexService.evaluate(indexType);
            if (gex.isValid()) {
                double spot = liveInstrumentCache.getFuturesPrice(indexType);
                if (gex.isAmplifyingRegime(spot)) {
                    // Dealers amplify moves → momentum entries have higher follow-through
                    bonus += 5;
                    signals.append(" GEX_AMP(+5)");
                } else if (gex.isDampeningRegime(spot) && Math.abs(momentumDir) > 0) {
                    // Dealers dampen → reduce conviction slightly (mean-reversion risk)
                    bonus = Math.max(0, bonus - 3);
                    signals.append(" GEX_DAMP(-3)");
                }
                // Flip point proximity bonus: price within 0.3% of flip → high-conviction breakout zone
                if (gex.flipStrike() > 0 && spot > 0) {
                    double flipDist = Math.abs(spot - gex.flipStrike()) / spot * 100;
                    if (flipDist < 0.3) {
                        bonus += 4;
                        signals.append(String.format(" GEX_FLIP(+4,@%d)", gex.flipStrike()));
                    }
                }
            }
        }

        // Module 12: Futures basis expansion as directional lead
        if (basisTracker != null) {
            FuturesBasisTracker.BasisSignal basis = basisTracker.evaluate(indexType);
            if (basis.hasSignal() && basis.direction() == momentumDir) {
                bonus += basis.bonus();
                signals.append(String.format(" BASIS(+%d,%s)", basis.bonus(),
                        basis.collapsingFromPremium() ? "UNWIND" : basis.direction() > 0 ? "BULL" : "BEAR"));
            }
        }

        // Module 6: Bias Persistence & Decay — smooths jitter, holds conviction
        bonus = applyPersistence(state, bonus, momentumDir);
        if (state.intentLocked) {
            signals.append(" LOCKED");
        }

        // Cap total bonus at 50 (raised from 40 — GEX+Basis add up to 13 more points;
        // raised from 30 originally to allow strong multi-module alignment)
        bonus = Math.min(50, bonus);

        // ── Probe → Scale → Flip Workflow ──────────────────────────────────
        // Phase 1 (PROBE): bias ≥10 AND magnet + cycle both contribute
        boolean probeEntry = bonus >= 10 && magnet.bonus > 0 && cycleBonus > 0;
        // Phase 2 (SCALE): intentLocked + persisted bias ≥20 → scale up signal
        boolean scaleUp = state.intentLocked && state.persistedBias >= 20;
        // Phase 3 (FLIP): reversal zone + intent decayed from locked state
        boolean flipRecommended = state.reversalReady && state.intentDecayedFromLock;

        // ── Decay-Aware Exit Signal ────────────────────────────────────────
        // Only recommend exit when bias has decayed below threshold for N ticks
        boolean exitRecommended = false;
        if (state.intentLocked || state.intentDecayedFromLock) {
            if (bonus < DECAY_EXIT_THRESHOLD) {
                state.decayExitTicks++;
                if (state.decayExitTicks >= DECAY_EXIT_TICKS) {
                    exitRecommended = true;
                    signals.append(" EXIT_DECAY");
                }
            } else {
                state.decayExitTicks = 0;
            }
        }

        // ── Attribution Logging (periodic, not every tick) ─────────────────
        if (bonus >= 15 && state.lastAttrLogMs + 30_000L < System.currentTimeMillis()) {
            state.lastAttrLogMs = System.currentTimeMillis();
            log.info("[Radar][{}] ATTRIBUTION: total={} | {} | phase={}",
                    indexType, bonus, attr.toLogString(),
                    probeEntry ? "PROBE" : scaleUp ? "SCALE" : flipRecommended ? "FLIP" : "WATCH");
        }

        return new IntentSignal(bonus, signals.toString(), state.getActiveMagnets(),
                state.reversalReady, state.lastMaxPainStrike, probeEntry, flipRecommended,
                state.intentLocked, state.persistedBias, scaleUp, exitRecommended, attr);
    }

    /** Check if price is at a magnet strike (for exit acceleration). */
    public boolean isReversalZone(IndexType indexType) {
        RadarState state = states.get(indexType);
        return state != null && state.reversalReady;
    }

    /** Get active magnet strikes. */
    public List<MagnetStrike> getActiveMagnets(IndexType indexType) {
        RadarState state = states.get(indexType);
        return state != null ? state.getActiveMagnets() : List.of();
    }

    /**
     * Reset the bias-persistence / hysteresis lock for an index. Called by
     * {@link OperatorAccumulationDetector} when a capitulation-flip is detected so the
     * decaying persisted bias for the OLD direction doesn't re-damp the freshly flipped
     * operator direction (Module 6 holds conviction for the prior side; on a genuine flip
     * that hold is exactly what we must clear). Idempotent + null-safe: if no state exists
     * yet there is nothing locked to clear. Only touches the persistence sub-state; magnets,
     * max-pain and ladder state are untouched.
     */
    public void resetPersistenceLock(IndexType indexType) {
        RadarState state = states.get(indexType);
        if (state == null) return;
        state.intentLocked = false;
        state.intentDecayedFromLock = false;
        state.persistTicks = 0;
        state.persistedBias = 0;
        state.decayExitTicks = 0;
        state.lastPersistDir = 0; // force applyPersistence() to re-baseline on the next eval
        log.info("[Radar][{}] persistence lock RESET (capitulation-flip) — hysteresis cleared for re-lock", indexType);
    }

    /** Should the exit monitor tighten stops? */
    public boolean shouldTightenExit(IndexType indexType) {
        RadarState state = states.get(indexType);
        if (state == null) return false;
        return state.reversalReady || state.intentDecayedFromLock;
    }

    /**
     * Tick update — call every second from OIMomentumStrategy.
     */
    public void tick(IndexType indexType) {
        RadarState state = states.computeIfAbsent(indexType, RadarState::new);
        double spot = liveInstrumentCache.getFuturesPrice(indexType);
        if (spot <= 0) return;

        boolean isExpiry = expiryCalendar.isExpiryDay(indexType);
        updateMagnets(indexType, state, spot, isExpiry);
        checkReversalTrigger(indexType, state, spot);

        // Cross-segment lag tracking (OI spike → spot lag detection)
        if (lagDetector != null) {
            lagDetector.tick(indexType);
        }

        // Operator tactics (rotation, max pain shift-rate, trap monitoring)
        if (tacticsEngine != null) {
            tacticsEngine.tick(indexType, state.lastMaxPainStrike);
        }

        // Periodic max pain (every 15 min)
        long now = System.currentTimeMillis();
        if (now - state.lastMaxPainCalcMs > 15 * 60 * 1000L) {
            recalculateMaxPain(indexType, state);
            state.lastMaxPainCalcMs = now;
        }

        // Detect OI laddering pattern
        detectLadder(indexType, state, spot, isExpiry);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MODULE 1: OI MAGNET ENGINE
    // ═══════════════════════════════════════════════════════════════════════════

    private MagnetResult evaluateMagnets(IndexType indexType, RadarState state,
                                          int momentumDir, boolean isExpiry) {
        List<MagnetStrike> magnets = state.getActiveMagnets();
        if (magnets.isEmpty()) return new MagnetResult(0, 0);

        double spot = liveInstrumentCache.getFuturesPrice(indexType);
        if (spot <= 0) return new MagnetResult(0, 0);

        // Find nearest magnet in momentum direction
        MagnetStrike best = null;
        for (MagnetStrike m : magnets) {
            if (momentumDir > 0 && m.strike > spot) {
                if (best == null || m.strike < best.strike) best = m;
            } else if (momentumDir < 0 && m.strike < spot) {
                if (best == null || m.strike > best.strike) best = m;
            }
        }
        if (best == null) return new MagnetResult(0, 0);

        double distancePct = Math.abs(best.strike - spot) / spot * 100;
        if (distancePct > 2.0 || distancePct < 0.05) return new MagnetResult(0, 0);

        int bonus = 5;
        if (best.totalOi > 1_000_000L) bonus += 5;
        if (best.totalOi > 2_000_000L) bonus += 3; // very heavy
        if (isExpiry) bonus += 3; // expiry pinning override — magnets matter more
        if (distancePct < 0.5) bonus += 2; // price approaching fast

        return new MagnetResult(Math.min(bonus, 15), best.strike);
    }

    private void updateMagnets(IndexType indexType, RadarState state, double spot, boolean isExpiry) {
        long minOi = getAdaptiveMagnetThreshold(indexType, isExpiry);
        int minDistance = getMinDistance(indexType);
        // On expiry, reduce distance threshold by 50%
        if (isExpiry) minDistance = minDistance / 2;

        LocalDate expiry = expiryCalendar.getCurrentExpiry(indexType);
        List<MagnetStrike> newMagnets = new ArrayList<>();

        for (OptionInstrument opt : liveInstrumentCache.allOptions()) {
            if (opt.getIndexType() != indexType) continue;
            if (opt.getOpenInterest() < minOi) continue;
            if (!opt.getExpiry().equals(expiry)) continue;

            int distance = Math.abs(opt.getStrikePrice() - (int) spot);
            if (distance < minDistance) continue;

            int direction = opt.getStrikePrice() > spot ? +1 : -1;
            newMagnets.add(new MagnetStrike(opt.getStrikePrice(), opt.getOptionType(),
                    opt.getOpenInterest(), direction, distance));
        }

        newMagnets.sort((a, b) -> Long.compare(b.totalOi, a.totalOi));
        List<MagnetStrike> filtered = new ArrayList<>();
        int bullCount = 0, bearCount = 0;
        for (MagnetStrike m : newMagnets) {
            if (m.direction > 0 && bullCount < 3) { filtered.add(m); bullCount++; }
            else if (m.direction < 0 && bearCount < 3) { filtered.add(m); bearCount++; }
        }
        state.activeMagnets = filtered;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MODULE 2: HOURLY TRIGGER WATCHER
    // ═══════════════════════════════════════════════════════════════════════════

    private int evaluateHourlyCycle(IndexType indexType, RadarState state, int momentumDir, LocalTime now) {
        boolean inCycleWindow = OPERATOR_CYCLE_TIMES.stream()
                .anyMatch(t -> Math.abs(Duration.between(t, now).toMinutes()) <= CYCLE_WINDOW_MINUTES);
        if (!inCycleWindow) return 0;

        double spot = liveInstrumentCache.getFuturesPrice(indexType);
        if (spot <= 0) return 0;

        int atm = indexType.roundToATM(spot);
        long[] oiChange = liveInstrumentCache.getAtmOiChange(indexType, atm, 5, 3);
        long totalChange = Math.abs(oiChange[0]) + Math.abs(oiChange[1]);
        // Lowered from 200k → 100k: cycle windows should fire more easily because
        // operators often start with smaller probes before building full positions.
        if (totalChange < 100_000L) return 0;

        int oiDir = deriveOiDir(oiChange[0], oiChange[1]);
        if (oiDir != momentumDir) return 0;

        // Base cycle bonus + escalation for heavy OI
        int bonus = 5;
        if (totalChange > 500_000L) bonus = 8;     // heavy OI at cycle time = strong tell
        else if (totalChange > 200_000L) bonus = 6; // moderate buildup

        // Cross-index escalation: if ≥1 other index also confirms at this cycle time,
        // boost by additional +5 (stacks with the separate cross-index module bonus).
        int crossConfirm = 0;
        for (IndexType other : List.of(IndexType.NIFTY, IndexType.BANKNIFTY, IndexType.SENSEX)) {
            if (other == indexType) continue;
            double otherSpot = liveInstrumentCache.getFuturesPrice(other);
            if (otherSpot <= 0) continue;
            int otherAtm = other.roundToATM(otherSpot);
            long[] otherOi = liveInstrumentCache.getAtmOiChange(other, otherAtm, 3, 3);
            int otherDir = deriveOiDir(otherOi[0], otherOi[1]);
            if (otherDir == momentumDir && (Math.abs(otherOi[0]) + Math.abs(otherOi[1])) > 50_000L) {
                crossConfirm++;
            }
        }
        if (crossConfirm >= 2) bonus += 5;  // all 3 indices at cycle time = institutional basket
        else if (crossConfirm == 1) bonus += 3;

        return bonus;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MODULE 3: CROSS-INDEX RADAR
    // ═══════════════════════════════════════════════════════════════════════════

    private int evaluateCrossIndex(IndexType currentIndex, int momentumDir) {
        int confirming = 0;
        for (IndexType other : List.of(IndexType.NIFTY, IndexType.BANKNIFTY, IndexType.SENSEX)) {
            if (other == currentIndex) continue;
            double otherSpot = liveInstrumentCache.getFuturesPrice(other);
            if (otherSpot <= 0) continue;

            int otherAtm = other.roundToATM(otherSpot);
            long[] otherOi = liveInstrumentCache.getAtmOiChange(other, otherAtm, 3, 3);
            int otherDir = deriveOiDir(otherOi[0], otherOi[1]);
            long total = Math.abs(otherOi[0]) + Math.abs(otherOi[1]);
            // Lowered from 100k to 50k: catches smaller but meaningful cross-index
            // OI moves in low-vol regimes where absolute deltas are naturally smaller.
            if (otherDir == momentumDir && total > 50_000L) confirming++;
        }
        if (confirming >= 2) return 10;  // was 8 — all indices aligned = very strong
        if (confirming == 1) return 6;   // was 5 — one peer confirms
        return 0;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MODULE 4: DYNAMIC MAX PAIN SHIFT
    // ═══════════════════════════════════════════════════════════════════════════

    private int evaluateMaxPainShift(IndexType indexType, RadarState state, int momentumDir) {
        if (state.lastMaxPainStrike <= 0 || state.prevMaxPainStrike <= 0) return 0;
        int shift = state.lastMaxPainStrike - state.prevMaxPainStrike;
        int threshold = (indexType == IndexType.BANKNIFTY) ? MAX_PAIN_SHIFT_BANKNIFTY : MAX_PAIN_SHIFT_NIFTY;
        if (Math.abs(shift) < threshold) return 0;
        int shiftDir = shift > 0 ? 1 : -1;
        return (shiftDir == momentumDir) ? 7 : 0;
    }

    private void recalculateMaxPain(IndexType indexType, RadarState state) {
        LocalDate expiry = expiryCalendar.getCurrentExpiry(indexType);
        double spot = liveInstrumentCache.getFuturesPrice(indexType);
        if (spot <= 0) return;

        int interval = indexType.strikeInterval();
        int atm = indexType.roundToATM(spot);
        int range = 10 * interval;
        int bestStrike = 0;
        double minLoss = Double.MAX_VALUE;

        for (int strike = atm - range; strike <= atm + range; strike += interval) {
            double writerLoss = 0;
            for (OptionInstrument opt : liveInstrumentCache.allOptions()) {
                if (opt.getIndexType() != indexType) continue;
                if (!opt.getExpiry().equals(expiry)) continue;
                if (opt.getOpenInterest() <= 0) continue;
                double intrinsic = "CE".equals(opt.getOptionType())
                        ? Math.max(0, strike - opt.getStrikePrice())
                        : Math.max(0, opt.getStrikePrice() - strike);
                writerLoss += intrinsic * opt.getOpenInterest();
            }
            if (writerLoss < minLoss) { minLoss = writerLoss; bestStrike = strike; }
        }
        if (bestStrike > 0) {
            state.prevMaxPainStrike = state.lastMaxPainStrike;
            state.lastMaxPainStrike = bestStrike;
            if (state.prevMaxPainStrike > 0 && state.prevMaxPainStrike != bestStrike) {
                log.info("[Radar][{}] MaxPain shifted: {} → {} (Δ{})",
                        indexType, state.prevMaxPainStrike, bestStrike, bestStrike - state.prevMaxPainStrike);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MODULE 5: REVERSAL GOVERNOR + DUAL-LEG CAPTURE
    // ═══════════════════════════════════════════════════════════════════════════

    private void checkReversalTrigger(IndexType indexType, RadarState state, double spot) {
        state.reversalReady = false;
        for (MagnetStrike magnet : state.getActiveMagnets()) {
            double distance = Math.abs(magnet.strike - spot);
            double threshold = indexType.strikeInterval() * 0.5;
            if (distance <= threshold) {
                state.reversalReady = true;
                // Dual-leg: if intent was locked and now we hit the magnet, recommend flip
                if (state.intentLocked) {
                    state.intentDecayedFromLock = true;
                    log.info("[Radar][{}] REVERSAL at magnet={} — FLIP recommended (dual-leg capture)",
                            indexType, magnet.strike);
                }
                break;
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MODULE 6: BIAS PERSISTENCE & DECAY
    // ═══════════════════════════════════════════════════════════════════════════

    private int applyPersistence(RadarState state, int rawBonus, int momentumDir) {
        // Track direction continuity
        if (momentumDir != state.lastPersistDir) {
            state.persistTicks = 0;
            state.persistedBias = 0;
            state.intentLocked = false;
            state.intentDecayedFromLock = false;
            state.lastPersistDir = momentumDir;
        }

        if (rawBonus >= PERSISTENCE_LOCK_THRESHOLD) {
            state.persistTicks++;
            state.persistedBias = rawBonus;

            // Lock after N consecutive ticks above threshold
            if (state.persistTicks >= PERSISTENCE_LOCK_TICKS && !state.intentLocked) {
                state.intentLocked = true;
                log.info("[Radar][{}] INTENT LOCKED: bias={} for {} ticks (dir={})",
                        state.indexType, rawBonus, state.persistTicks, momentumDir > 0 ? "BULL" : "BEAR");
            }
        } else if (state.intentLocked) {
            // Decay gradually instead of instant flip — hold conviction longer
            state.persistedBias = Math.max(0, state.persistedBias - DECAY_RATE_PER_TICK);
            if (state.persistedBias <= 5) {
                state.intentLocked = false;
                state.intentDecayedFromLock = true;
                log.debug("[Radar][{}] Intent lock decayed to 0", state.indexType);
            }
            return state.persistedBias; // Use persisted (decaying) value instead of raw
        } else {
            state.persistTicks = 0;
            state.persistedBias = rawBonus;
        }

        return rawBonus;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MODULE 7: OI LADDERING DETECTOR
    // ═══════════════════════════════════════════════════════════════════════════

    private int evaluateLaddering(IndexType indexType, RadarState state,
                                   int momentumDir, boolean isExpiry) {
        if (state.ladderStrikeCount < MIN_LADDER_STRIKES) return 0;
        if (state.ladderDirection != momentumDir) return 0;

        // Ladder detected in momentum direction — strong roadmap signal
        int bonus = 5;
        if (state.ladderStrikeCount >= 4) bonus += 3;
        if (state.ladderStrikeCount >= 5) bonus += 2;
        if (isExpiry) bonus += 2; // on expiry, laddering is very aggressive
        return Math.min(bonus, 10);
    }

    private void detectLadder(IndexType indexType, RadarState state, double spot, boolean isExpiry) {
        long minOiPerStrike = isExpiry ? MIN_LADDER_OI_PER_STRIKE / 2 : MIN_LADDER_OI_PER_STRIKE;
        int interval = indexType.strikeInterval();
        LocalDate expiry = expiryCalendar.getCurrentExpiry(indexType);
        int atm = indexType.roundToATM(spot);

        // Check for sequential CE OI additions above spot (bearish ladder)
        int ceLadderCount = 0;
        for (int i = 1; i <= 8; i++) {
            int strike = atm + (i * interval);
            long ceOi = getStrikeOi(indexType, strike, "CE", expiry);
            if (ceOi >= minOiPerStrike) ceLadderCount++;
            else break; // ladder must be sequential
        }

        // Check for sequential PE OI additions below spot (bullish ladder)
        int peLadderCount = 0;
        for (int i = 1; i <= 8; i++) {
            int strike = atm - (i * interval);
            long peOi = getStrikeOi(indexType, strike, "PE", expiry);
            if (peOi >= minOiPerStrike) peLadderCount++;
            else break;
        }

        // Record the stronger ladder
        if (ceLadderCount >= MIN_LADDER_STRIKES && ceLadderCount > peLadderCount) {
            state.ladderDirection = -1; // CE ladder above = bearish ceiling setup
            state.ladderStrikeCount = ceLadderCount;
        } else if (peLadderCount >= MIN_LADDER_STRIKES) {
            state.ladderDirection = +1; // PE ladder below = bullish floor setup
            state.ladderStrikeCount = peLadderCount;
        } else {
            state.ladderStrikeCount = 0;
            state.ladderDirection = 0;
        }
    }

    private long getStrikeOi(IndexType indexType, int strike, String optionType, LocalDate expiry) {
        return liveInstrumentCache.getOption(indexType, strike, optionType, expiry)
                .map(OptionInstrument::getOpenInterest)
                .orElse(0L);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MODULE 8: LIQUIDITY AWARENESS (Stealth Positioning)
    // ═══════════════════════════════════════════════════════════════════════════

    private int evaluateLiquidity(IndexType indexType, int momentumDir) {
        double spot = liveInstrumentCache.getFuturesPrice(indexType);
        if (spot <= 0) return 0;

        int atm = indexType.roundToATM(spot);
        LocalDate expiry = expiryCalendar.getCurrentExpiry(indexType);

        // Check the ATM option in the momentum direction
        String targetType = momentumDir > 0 ? "CE" : "PE";
        Optional<OptionInstrument> targetOpt = liveInstrumentCache.getOption(indexType, atm, targetType, expiry);
        if (targetOpt.isEmpty()) return 0;

        OptionInstrument opt = targetOpt.get();
        double bid = opt.getBestBid();
        double ask = opt.getBestAsk();
        long bidQty = opt.getBestBidQty();
        long askQty = opt.getBestAskQty();

        if (bid <= 0 || ask <= 0) return 0;

        // Spread widening check: if spread > 1% of mid AND OI is building,
        // operators are positioning stealthily (avoiding detection in narrow spreads)
        double mid = (bid + ask) / 2;
        double spreadPct = (ask - bid) / mid * 100;

        // Stealth signal: wide spread (>0.8%) + heavy OI build + low volume relative to OI
        if (spreadPct > 0.8 && opt.getOpenInterest() > 100_000L) {
            // Check if bid qty is much higher than ask qty (accumulation behind wide spread)
            if (momentumDir > 0 && bidQty > askQty * 1.5) return 4;
            if (momentumDir < 0 && askQty > bidQty * 1.5) return 4;
        }
        return 0;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    private int deriveOiDir(long ceOiChange, long peOiChange) {
        if (peOiChange > ceOiChange && peOiChange > 0) return 1;  // PE build = bullish
        if (ceOiChange > peOiChange && ceOiChange > 0) return -1; // CE build = bearish
        return 0;
    }

    private int getMinDistance(IndexType indexType) {
        return switch (indexType) {
            case NIFTY, FINNIFTY, MIDCPNIFTY -> MIN_DISTANCE_NIFTY;
            case BANKNIFTY -> MIN_DISTANCE_BANKNIFTY;
            case SENSEX -> MIN_DISTANCE_SENSEX;
        };
    }

    /**
     * ADAPTIVE THRESHOLD — dynamically adjusts OI magnet threshold based on session volume.
     * Instead of a fixed 500k, uses the average OI per strike as a baseline and requires
     * magnets to be significantly above average. This self-calibrates across:
     * - Normal days (high baseline → high threshold)
     * - Low-volume days (low baseline → lower threshold, catches smaller-but-significant builds)
     * - Expiry days (always use reduced threshold for pinning detection)
     */
    private long getAdaptiveMagnetThreshold(IndexType indexType, boolean isExpiry) {
        if (isExpiry) return MIN_MAGNET_OI_EXPIRY;

        // Calculate average OI per subscribed strike for this index (cached every 5 min)
        long now = System.currentTimeMillis();
        if (now - lastBaselineCalcMs > 5 * 60 * 1000L) {
            updateBaselines();
            lastBaselineCalcMs = now;
        }

        Long baseline = sessionBaselineOi.get(indexType);
        if (baseline == null || baseline <= 0) return MIN_MAGNET_OI_NORMAL;

        // Magnet threshold = 2.5× average OI per strike (significant above-average buildup)
        // But never below 200k (noise floor) or above 800k (cap for high-volume days)
        long adaptive = (long) (baseline * 2.5);
        return Math.max(200_000L, Math.min(800_000L, adaptive));
    }

    private void updateBaselines() {
        for (IndexType idx : List.of(IndexType.NIFTY, IndexType.BANKNIFTY, IndexType.SENSEX)) {
            long totalOi = 0;
            int strikeCount = 0;
            for (OptionInstrument opt : liveInstrumentCache.allOptions()) {
                if (opt.getIndexType() != idx) continue;
                if (opt.getOpenInterest() <= 0) continue;
                totalOi += opt.getOpenInterest();
                strikeCount++;
            }
            if (strikeCount > 0) {
                sessionBaselineOi.put(idx, totalOi / strikeCount);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // DATA TYPES
    // ═══════════════════════════════════════════════════════════════════════════

    /** Full radar evaluation result with probe/scale/flip workflow and attribution. */
    public record IntentSignal(
            int bonus,
            String signals,
            List<MagnetStrike> activeMagnets,
            boolean reversalReady,
            int currentMaxPain,
            boolean probeEntryRecommended,
            boolean flipRecommended,
            boolean intentLocked,
            int persistedBias,
            boolean scaleUpRecommended,
            boolean exitRecommended,
            Attribution attribution
    ) {}

    /** A detected magnet strike with heavy operator OI. */
    public record MagnetStrike(int strike, String optionType, long totalOi, int direction, int distanceFromSpot) {}

    private record MagnetResult(int bonus, int targetStrike) {}

    /**
     * Bias Attribution — tracks which module contributed how much to the total bonus.
     * Enables debugging, backtesting per-module effectiveness, and future weight tuning.
     */
    public static class Attribution {
        public int magnetBonus = 0;
        public int cycleBonus = 0;
        public int crossBonus = 0;
        public int maxPainBonus = 0;
        public int ladderBonus = 0;
        public int liquidityBonus = 0;

        /** Human-readable attribution breakdown for logging. */
        public String toLogString() {
            StringBuilder sb = new StringBuilder();
            if (magnetBonus > 0) sb.append("Magnet(+").append(magnetBonus).append(") ");
            if (cycleBonus > 0) sb.append("Cycle(+").append(cycleBonus).append(") ");
            if (crossBonus > 0) sb.append("Cross(+").append(crossBonus).append(") ");
            if (maxPainBonus > 0) sb.append("MaxPain(+").append(maxPainBonus).append(") ");
            if (ladderBonus > 0) sb.append("Ladder(+").append(ladderBonus).append(") ");
            if (liquidityBonus > 0) sb.append("Liq(+").append(liquidityBonus).append(") ");
            return sb.toString().trim();
        }

        /** Total raw bonus before persistence. */
        public int rawTotal() {
            return magnetBonus + cycleBonus + crossBonus + maxPainBonus + ladderBonus + liquidityBonus;
        }

        /** Which module contributed the most? */
        public String dominantModule() {
            int max = Math.max(magnetBonus, Math.max(cycleBonus,
                    Math.max(crossBonus, Math.max(maxPainBonus,
                            Math.max(ladderBonus, liquidityBonus)))));
            if (max == 0) return "NONE";
            if (max == magnetBonus) return "MAGNET";
            if (max == cycleBonus) return "CYCLE";
            if (max == crossBonus) return "CROSS";
            if (max == maxPainBonus) return "MAX_PAIN";
            if (max == ladderBonus) return "LADDER";
            return "LIQUIDITY";
        }
    }

    /** Per-index tracking state. */
    private static class RadarState {
        final IndexType indexType;

        // Magnets
        volatile List<MagnetStrike> activeMagnets = List.of();
        volatile boolean reversalReady = false;

        // Max pain
        volatile int lastMaxPainStrike = 0;
        volatile int prevMaxPainStrike = 0;
        volatile long lastMaxPainCalcMs = 0;

        // Persistence & decay
        volatile int persistTicks = 0;
        volatile int persistedBias = 0;
        volatile boolean intentLocked = false;
        volatile boolean intentDecayedFromLock = false;
        volatile int lastPersistDir = 0;
        volatile int decayExitTicks = 0;

        // Laddering
        volatile int ladderStrikeCount = 0;
        volatile int ladderDirection = 0;

        // Attribution logging (throttled)
        volatile long lastAttrLogMs = 0;

        RadarState(IndexType indexType) { this.indexType = indexType; }

        List<MagnetStrike> getActiveMagnets() {
            return activeMagnets != null ? activeMagnets : List.of();
        }
    }
}
