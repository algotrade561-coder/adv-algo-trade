package com.algo.trade.strategy;

import com.algo.trade.domain.IndexType;
import com.algo.trade.domain.OptionInstrument;
import com.algo.trade.domain.SignalType;
import com.algo.trade.marketdata.ExpiryCalendar;
import com.algo.trade.marketdata.LiveInstrumentCache;
import com.algo.trade.risk.MarketGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Option-Leads-Index Detector — makes options the PRIMARY signal source.
 *
 * <p><b>Core premise:</b> Options move before the index because they reflect
 * trader positioning, hedging, and operator intent. By the time the spot index
 * breaks out, the option premium has already spiked.</p>
 *
 * <h3>Detection Logic:</h3>
 * <ol>
 *   <li><b>Premium Breakout</b>: ATM option LTP breaks its intraday session high</li>
 *   <li><b>OI Confirmation</b>: OI is rising (new positions, not short covering)</li>
 *   <li><b>Index Lag Check</b>: Spot index hasn't moved proportionally yet (option leading)</li>
 *   <li><b>Volume Confirmation</b>: Premium breakout accompanied by volume</li>
 * </ol>
 *
 * <h3>Signal Types:</h3>
 * <ul>
 *   <li><b>OPTION_BREAKOUT</b>: Option LTP broke session high + OI rising → anticipate index move</li>
 *   <li><b>ABNORMAL_SPIKE</b>: Premium jumped abnormally fast (>10% in 60s) → event/news anticipated</li>
 *   <li><b>INDEX_CONFIRMING</b>: Index now following the option-led direction → confidence UP</li>
 *   <li><b>INDEX_DIVERGING</b>: Index NOT following option direction → reduce/hedge</li>
 * </ul>
 *
 * <h3>Premium Floor:</h3>
 * Avoid options < ₹20 premium (except on expiry day for gamma scalps).
 *
 * <h3>Debounce:</h3>
 * Requires 3–5 ticks above the breakout level before confirming.
 */
@Component
public class OptionLeadsIndexDetector implements com.algo.trade.execution.DailyResettable {

    private static final Logger log = LoggerFactory.getLogger(OptionLeadsIndexDetector.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    public enum SignalPhase {
        NONE,
        /** Option premium broke session high with OI support — anticipate index breakout. */
        OPTION_BREAKOUT,
        /** Premium spiked abnormally (event/news anticipated) — immediate entry. */
        ABNORMAL_SPIKE,
        /** Index now confirming (following option direction) — confidence high. */
        INDEX_CONFIRMING,
        /** Index diverging (not following option) — reduce/hedge. */
        INDEX_DIVERGING
    }

    private final LiveInstrumentCache liveInstrumentCache;
    private final ExpiryCalendar expiryCalendar;
    private final MarketGuard marketGuard;

    /** IV rank tracker for dynamic premium floor. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.indicator.IVRankTracker ivRankTracker;

    /** Historical behaviour data for confidence scaling. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private DailyBehaviorLogLoader behaviorLog;

    // ── Configuration ─────────────────────────────────────────────────────

    @Value("${trading.option-leads.enabled:true}")
    private boolean enabled;

    /** Minimum premium (₹) to consider an option tradeable. Expiry scalps exempt. */
    @Value("${trading.option-leads.min-premium:20}")
    private double minPremium;

    /** Premium velocity % over 60s to classify as "abnormal spike" (event anticipation). */
    @Value("${trading.option-leads.abnormal-spike-pct:10.0}")
    private double abnormalSpikePct;

    /** Number of consecutive ticks above breakout to confirm (debounce). */
    @Value("${trading.option-leads.confirmation-ticks:3}")
    private int confirmationTicks;

    /** Minimum OI increase % over 3 min to confirm "fresh positions" (not short covering). */
    @Value("${trading.option-leads.min-oi-increase-pct:1.0}")
    private double minOiIncreasePct;

    /** Index lag threshold: if index moved less than this % while option moved >breakoutPct,
     *  the option is "leading". */
    @Value("${trading.option-leads.index-lag-threshold-pct:0.05}")
    private double indexLagThresholdPct;

    /** Premium must exceed session high by this % to count as a breakout. */
    @Value("${trading.option-leads.breakout-margin-pct:2.0}")
    private double breakoutMarginPct;

    /** Max time (seconds) to wait for index confirmation before flagging divergence. */
    @Value("${trading.option-leads.index-confirm-window-seconds:180}")
    private int indexConfirmWindowSeconds;

    /** Cooldown between signals per index (seconds). */
    @Value("${trading.option-leads.cooldown-seconds:120}")
    private int cooldownSeconds;

    /** Volume spike multiplier: option volume must be Nx average to confirm breakout. */
    @Value("${trading.option-leads.volume-spike-multiplier:1.5}")
    private double volumeSpikeMultiplier;

    /** Enable cross-index confirmation (+10 if NIFTY + SENSEX agree). */
    @Value("${trading.option-leads.cross-index-confirm-enabled:true}")
    private boolean crossIndexConfirmEnabled;

    /** Cross-index confirmation bonus added to confidence. */
    @Value("${trading.option-leads.cross-index-bonus:10}")
    private int crossIndexBonus;

    /** Dynamic premium floor: IV rank above this raises the floor from ₹20 to ₹30-40. */
    @Value("${trading.option-leads.iv-rank-high-threshold:60}")
    private double ivRankHighThreshold;

    /** Premium floor multiplier when IV is high (floor × this). */
    @Value("${trading.option-leads.high-iv-floor-multiplier:1.75}")
    private double highIvFloorMultiplier;

    /** Enable option reversal candle exit detection. */
    @Value("${trading.option-leads.reversal-exit-enabled:true}")
    private boolean reversalExitEnabled;

    // ── Per-index state ───────────────────────────────────────────────────

    private final Map<IndexType, OptionLeadState> states = new ConcurrentHashMap<>();

    public OptionLeadsIndexDetector(LiveInstrumentCache liveInstrumentCache,
                                    ExpiryCalendar expiryCalendar,
                                    MarketGuard marketGuard) {
        this.liveInstrumentCache = liveInstrumentCache;
        this.expiryCalendar = expiryCalendar;
        this.marketGuard = marketGuard;
    }

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Main evaluation — called every tick from OIMomentumStrategy.
     * Updates state and returns the current signal phase + direction.
     *
     * @param indexType the index being tracked
     * @param atm      current ATM strike
     * @param spot     current spot price
     * @return OptionLeadSignal with phase, direction, and confidence
     */
    public OptionLeadSignal evaluate(IndexType indexType, int atm, double spot) {
        if (!enabled || spot <= 0) return OptionLeadSignal.NONE;

        OptionLeadState state = states.computeIfAbsent(indexType, k -> new OptionLeadState());
        boolean isExpiryDay = expiryCalendar.isExpiryDay(indexType);

        // Get ATM option premiums
        double cePremium = getOptionPremium(indexType, atm, "CE");
        double pePremium = getOptionPremium(indexType, atm, "PE");

        // Dynamic premium floor: adapt to IV regime
        // High IV → raise floor (₹30-40) to avoid overpaying for noise
        // Low IV → keep at ₹20 (base)
        // Expiry → drop to ₹5 for gamma scalps
        double effectiveMinPremium;
        if (isExpiryDay) {
            effectiveMinPremium = 5.0;
        } else {
            effectiveMinPremium = minPremium;
            if (ivRankTracker != null) {
                double ivRank = ivRankTracker.getIVRank(indexType);
                if (ivRank >= ivRankHighThreshold) {
                    effectiveMinPremium = minPremium * highIvFloorMultiplier;
                }
            }
        }
        boolean ceValid = cePremium >= effectiveMinPremium;
        boolean peValid = pePremium >= effectiveMinPremium;

        if (!ceValid && !peValid) return OptionLeadSignal.NONE;

        // Track spot for index lag detection
        if (state.spotAtBreakout == 0) state.spotAtBreakout = spot;

        // ── Phase 1: Detect option breakout (premium > session high) ──
        // IMPORTANT: check breakout BEFORE updating session highs,
        // otherwise the new high becomes the baseline and breakout is never detected.
        int breakoutDir = detectBreakout(state, cePremium, pePremium, ceValid, peValid);

        // Update session highs ONLY if no breakout detected on this tick
        // (breakout premium becomes the reference; normal ticks build the baseline)
        if (breakoutDir == 0) {
            if (ceValid && cePremium > state.ceSessionHigh) state.ceSessionHigh = cePremium;
            if (peValid && pePremium > state.peSessionHigh) state.peSessionHigh = pePremium;
        }

        if (breakoutDir != 0 && state.phase == SignalPhase.NONE) {
            // Check debounce
            if (state.lastBreakoutDir == breakoutDir) {
                state.breakoutTickCount++;
            } else {
                state.lastBreakoutDir = breakoutDir;
                state.breakoutTickCount = 1;
            }

            if (state.breakoutTickCount >= confirmationTicks) {
                // Check OI confirmation
                boolean oiConfirmed = checkOiConfirmation(indexType, atm, breakoutDir);
                // Check volume confirmation (price↑ + volume↑ = strongest conviction)
                boolean volumeConfirmed = checkVolumeSpike(indexType, atm, breakoutDir);
                // Check index lag
                double spotChange = Math.abs(spot - state.spotAtBreakout) / state.spotAtBreakout * 100;
                boolean indexLagging = spotChange < indexLagThresholdPct;

                if (oiConfirmed && indexLagging) {
                    state.phase = SignalPhase.OPTION_BREAKOUT;
                    state.signalDirection = breakoutDir;
                    state.signalTime = Instant.now();
                    state.spotAtBreakout = spot;
                    state.breakoutPremium = breakoutDir > 0 ? cePremium : pePremium;
                    state.volumeConfirmed = volumeConfirmed;

                    // Auto-scale confidence based on breakout strength
                    double sessionHigh = breakoutDir > 0 ? state.ceSessionHigh : state.peSessionHigh;
                    double breakoutStrength = sessionHigh > 0
                            ? (state.breakoutPremium - sessionHigh) / sessionHigh * 100 : 0;
                    state.breakoutStrengthPct = breakoutStrength;
                    state.confidenceBoost = computeBreakoutBoost(breakoutStrength, volumeConfirmed);

                    // Cross-index confirmation: check if other index also shows same bias
                    if (crossIndexConfirmEnabled) {
                        boolean crossConfirmed = checkCrossIndexAlignment(indexType, breakoutDir, atm);
                        if (crossConfirmed) {
                            state.confidenceBoost += crossIndexBonus;
                            state.crossIndexConfirmed = true;
                        }
                    }

                    log.info("[OptionLeads] {} BREAKOUT: {} premium={} sessionHigh={} OI✓ vol={} indexLag✓ cross={} boost={}",
                            indexType, breakoutDir > 0 ? "CE" : "PE",
                            String.format("%.1f", state.breakoutPremium),
                            String.format("%.1f", sessionHigh),
                            volumeConfirmed ? "✓" : "○",
                            state.crossIndexConfirmed ? "✓" : "○",
                            state.confidenceBoost);

                    return buildSignal(state, spot);
                }
            }
        }

        // ── Phase 2: Detect abnormal premium spike (news/event anticipation) ──
        if (state.phase == SignalPhase.NONE) {
            int spikeDir = detectAbnormalSpike(state, cePremium, pePremium, ceValid, peValid);
            if (spikeDir != 0 && !isCooldownActive(state)) {
                state.phase = SignalPhase.ABNORMAL_SPIKE;
                state.signalDirection = spikeDir;
                state.signalTime = Instant.now();
                state.spotAtBreakout = spot;

                log.warn("[OptionLeads] {} ABNORMAL SPIKE: {} — event/news anticipated. Immediate entry.",
                        indexType, spikeDir > 0 ? "CE" : "PE");

                return buildSignal(state, spot);
            }
        }

        // ── Phase 3: Track index confirmation / divergence / premium reversal ──
        if (state.phase == SignalPhase.OPTION_BREAKOUT || state.phase == SignalPhase.ABNORMAL_SPIKE) {
            double spotMove = (spot - state.spotAtBreakout) / state.spotAtBreakout * 100;
            int spotDir = spotMove > 0.03 ? 1 : spotMove < -0.03 ? -1 : 0;

            long secondsSinceSignal = java.time.Duration.between(state.signalTime, Instant.now()).getSeconds();

            // Check for option premium reversal (engulfing / sharp drop against position)
            if (reversalExitEnabled) {
                boolean premiumReversed = checkPremiumReversal(indexType, atm, state);
                if (premiumReversed) {
                    state.phase = SignalPhase.INDEX_DIVERGING;
                    state.confidenceBoost = -15;
                    log.warn("[OptionLeads] {} PREMIUM_REVERSAL: Option reversed sharply against signal. Exit/hedge.",
                            indexType);
                    return buildSignal(state, spot);
                }
            }

            if (spotDir == state.signalDirection) {
                state.phase = SignalPhase.INDEX_CONFIRMING;
                state.confidenceBoost = computeConfirmationBoost(spotMove, secondsSinceSignal);
                // Cross-index adds extra boost
                if (state.crossIndexConfirmed) state.confidenceBoost += 5;
                log.info("[OptionLeads] {} INDEX_CONFIRMING: spot moved {}% in signal direction. Confidence +{}",
                        indexType, String.format("%.2f", spotMove), state.confidenceBoost);
            } else if (secondsSinceSignal > indexConfirmWindowSeconds && spotDir != state.signalDirection) {
                state.phase = SignalPhase.INDEX_DIVERGING;
                state.confidenceBoost = -10;
                log.info("[OptionLeads] {} INDEX_DIVERGING: spot NOT following option after {}s. Reduce position.",
                        indexType, secondsSinceSignal);
            }

            return buildSignal(state, spot);
        }

        // ── Phase 4: Reset after cooldown ──
        if (state.phase == SignalPhase.INDEX_CONFIRMING || state.phase == SignalPhase.INDEX_DIVERGING) {
            long secondsSinceSignal = java.time.Duration.between(state.signalTime, Instant.now()).getSeconds();
            if (secondsSinceSignal > cooldownSeconds) {
                state.reset();
            } else {
                return buildSignal(state, spot);
            }
        }

        return OptionLeadSignal.NONE;
    }

    /**
     * Is the current signal suggesting an immediate PE entry? (For PCR reversal overlay)
     */
    public boolean isPeBreakoutActive(IndexType indexType) {
        OptionLeadState state = states.get(indexType);
        return state != null && state.signalDirection < 0
                && (state.phase == SignalPhase.OPTION_BREAKOUT || state.phase == SignalPhase.ABNORMAL_SPIKE);
    }

    /**
     * Is the current signal suggesting an immediate CE entry?
     */
    public boolean isCeBreakoutActive(IndexType indexType) {
        OptionLeadState state = states.get(indexType);
        return state != null && state.signalDirection > 0
                && (state.phase == SignalPhase.OPTION_BREAKOUT || state.phase == SignalPhase.ABNORMAL_SPIKE);
    }

    /**
     * Get the confidence boost/penalty from option-leads-index analysis.
     * Positive = option confirmed by index, Negative = diverging.
     */
    public int getConfidenceBoost(IndexType indexType) {
        OptionLeadState state = states.get(indexType);
        if (state == null) return 0;
        return state.confidenceBoost;
    }

    /**
     * Should we take an immediate position based on abnormal spike?
     */
    public boolean isAbnormalSpikeActive(IndexType indexType) {
        OptionLeadState state = states.get(indexType);
        return state != null && state.phase == SignalPhase.ABNORMAL_SPIKE;
    }

    /**
     * Should we reduce position size or hedge due to index divergence?
     */
    public boolean isIndexDiverging(IndexType indexType) {
        OptionLeadState state = states.get(indexType);
        return state != null && state.phase == SignalPhase.INDEX_DIVERGING;
    }

    /**
     * Get current signal direction (+1 CE/bullish, -1 PE/bearish, 0 none).
     */
    public int getSignalDirection(IndexType indexType) {
        OptionLeadState state = states.get(indexType);
        return state != null ? state.signalDirection : 0;
    }

    /**
     * Reset at start of day.
     */
    public void resetDaily() {
        states.clear();
        log.info("[OptionLeads] Daily state reset.");
    }

    /**
     * Diagnostics for API/monitoring.
     */
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", enabled);
        status.put("minPremium", minPremium);
        for (Map.Entry<IndexType, OptionLeadState> entry : states.entrySet()) {
            OptionLeadState s = entry.getValue();
            Map<String, Object> idxStatus = new LinkedHashMap<>();
            idxStatus.put("phase", s.phase.name());
            idxStatus.put("direction", s.signalDirection);
            idxStatus.put("confidenceBoost", s.confidenceBoost);
            idxStatus.put("ceSessionHigh", String.format("%.1f", s.ceSessionHigh));
            idxStatus.put("peSessionHigh", String.format("%.1f", s.peSessionHigh));
            idxStatus.put("breakoutTickCount", s.breakoutTickCount);
            status.put(entry.getKey().name(), idxStatus);
        }
        return status;
    }

    // ── Internal Detection Logic ──────────────────────────────────────────

    private int detectBreakout(OptionLeadState state, double cePremium, double pePremium,
                               boolean ceValid, boolean peValid) {
        // CE breakout: premium exceeded session high by breakoutMarginPct
        if (ceValid && state.ceSessionHigh > 0) {
            double marginThreshold = state.ceSessionHigh * (1 + breakoutMarginPct / 100.0);
            if (cePremium > marginThreshold) return 1;
        }
        // PE breakout: premium exceeded session high by breakoutMarginPct
        if (peValid && state.peSessionHigh > 0) {
            double marginThreshold = state.peSessionHigh * (1 + breakoutMarginPct / 100.0);
            if (pePremium > marginThreshold) return -1;
        }
        return 0;
    }

    private int detectAbnormalSpike(OptionLeadState state, double cePremium, double pePremium,
                                    boolean ceValid, boolean peValid) {
        // Track previous premiums for velocity check
        long now = System.currentTimeMillis();
        if (state.lastSampleTime > 0 && (now - state.lastSampleTime) < 60_000) {
            // Calculate velocity since last sample
            if (ceValid && state.lastCePremium > 0) {
                double ceChange = (cePremium - state.lastCePremium) / state.lastCePremium * 100;
                if (ceChange >= abnormalSpikePct) {
                    state.lastSampleTime = now;
                    state.lastCePremium = cePremium;
                    state.lastPePremium = pePremium;
                    return 1;
                }
            }
            if (peValid && state.lastPePremium > 0) {
                double peChange = (pePremium - state.lastPePremium) / state.lastPePremium * 100;
                if (peChange >= abnormalSpikePct) {
                    state.lastSampleTime = now;
                    state.lastCePremium = cePremium;
                    state.lastPePremium = pePremium;
                    return -1;
                }
            }
        }
        state.lastSampleTime = now;
        state.lastCePremium = cePremium;
        state.lastPePremium = pePremium;
        return 0;
    }

    private boolean checkOiConfirmation(IndexType indexType, int atm, int direction) {
        String optType = direction > 0 ? "CE" : "PE";
        LocalDate expiry = expiryCalendar.getCurrentExpiry(indexType);
        Optional<OptionInstrument> opt = liveInstrumentCache.getOption(indexType, atm, optType, expiry);
        if (opt.isEmpty()) return false;

        double oiChangePct = opt.get().getOiChangePercentSince(3);
        // Rising OI + rising price = new positions being opened (bullish for direction)
        // Falling OI + rising price = short covering (weaker signal, still tradeable)
        return oiChangePct >= minOiIncreasePct;
    }

    /**
     * Volume spike confirmation: option volume must be significantly above recent average.
     * Price ↑ + OI ↑ + Volume ↑ → strongest conviction.
     * Price ↑ + Volume flat → reduce bias score slightly (signal still valid but weaker).
     */
    private boolean checkVolumeSpike(IndexType indexType, int atm, int direction) {
        String optType = direction > 0 ? "CE" : "PE";
        LocalDate expiry = expiryCalendar.getCurrentExpiry(indexType);
        Optional<OptionInstrument> opt = liveInstrumentCache.getOption(indexType, atm, optType, expiry);
        if (opt.isEmpty()) return false;

        long currentVolume = opt.get().getVolume();
        // Compare to volume of the opposite side as a proxy for "normal" volume
        String oppositeType = direction > 0 ? "PE" : "CE";
        long oppositeVolume = liveInstrumentCache.getOption(indexType, atm, oppositeType, expiry)
                .map(OptionInstrument::getVolume).orElse(0L);

        // If direction-side volume exceeds opposite by multiplier → volume spike confirmed
        if (oppositeVolume > 0 && currentVolume > oppositeVolume * volumeSpikeMultiplier) {
            return true;
        }
        // Also check if volume is substantial on its own (>1000 contracts for liquid indices)
        return currentVolume > 1000;
    }

    /**
     * Cross-index confirmation: when NIFTY options lead, check SENSEX (and vice versa).
     * If both show same directional bias → extra confidence.
     */
    private boolean checkCrossIndexAlignment(IndexType primaryIdx, int direction, int atm) {
        // Determine the cross index
        IndexType crossIdx = switch (primaryIdx) {
            case NIFTY -> IndexType.SENSEX;
            case SENSEX -> IndexType.NIFTY;
            default -> null;
        };
        if (crossIdx == null) return false;

        // Check if cross index option is also moving in the same direction
        double crossSpot = liveInstrumentCache.getFuturesPrice(crossIdx);
        if (crossSpot <= 0) return false;

        int crossAtm = crossIdx.roundToATM(crossSpot);
        String optType = direction > 0 ? "CE" : "PE";
        LocalDate crossExpiry = expiryCalendar.getCurrentExpiry(crossIdx);
        Optional<OptionInstrument> crossOpt = liveInstrumentCache.getOption(crossIdx, crossAtm, optType, crossExpiry);
        if (crossOpt.isEmpty()) return false;

        // Cross index OI should also be building in same direction
        double crossOiChange = crossOpt.get().getOiChangePercentSince(3);
        return crossOiChange >= 0.5; // Softer threshold for cross-index (0.5% vs 1%)
    }

    /**
     * Auto-scaling boost based on breakout strength.
     * Breakout margin 2% → +10; margin 5% → +20; margin 8%+ → +25.
     * Volume confirmed adds +5 extra.
     * Scaled by historical data confidence (78% for optionLeadsIndex pattern).
     */
    private int computeBreakoutBoost(double breakoutStrengthPct, boolean volumeConfirmed) {
        int boost;
        if (breakoutStrengthPct >= 8.0) boost = 25;
        else if (breakoutStrengthPct >= 5.0) boost = 20;
        else if (breakoutStrengthPct >= 3.0) boost = 15;
        else boost = 10;

        // Volume confirmation: strongest conviction
        if (volumeConfirmed) boost += 5;

        // Scale by historical data confidence
        if (behaviorLog != null && behaviorLog.isLoaded()) {
            int dataConf = behaviorLog.getPatternConfidence("optionLeadsIndex");
            if (dataConf > 0) {
                boost = (int) (boost * dataConf / 100.0);
            }
        }

        return Math.min(30, Math.max(5, boost));
    }

    private int computeConfirmationBoost(double spotMovePct, long secondsElapsed) {
        int boost = 10; // base boost for confirmation
        // Faster confirmation = higher confidence
        if (secondsElapsed < 30) boost += 10;
        else if (secondsElapsed < 60) boost += 5;
        // Larger spot move = stronger confirmation
        if (Math.abs(spotMovePct) > 0.15) boost += 5;
        return Math.min(25, boost);
    }

    /**
     * Premium reversal detection: if the option that broke out now reverses sharply
     * (drops >5% from its breakout level), it signals an operator trap — exit early.
     * This catches engulfing-style reversals faster than waiting for SL.
     */
    private boolean checkPremiumReversal(IndexType indexType, int atm, OptionLeadState state) {
        if (state.breakoutPremium <= 0) return false;

        String optType = state.signalDirection > 0 ? "CE" : "PE";
        double currentPremium = getOptionPremium(indexType, atm, optType);
        if (currentPremium <= 0) return false;

        // Premium reversed: dropped more than 5% from breakout level
        double reversalPct = (state.breakoutPremium - currentPremium) / state.breakoutPremium * 100;
        return reversalPct >= 5.0;
    }

    private boolean isCooldownActive(OptionLeadState state) {
        if (state.signalTime == null) return false;
        long elapsed = java.time.Duration.between(state.signalTime, Instant.now()).getSeconds();
        return elapsed < cooldownSeconds;
    }

    private OptionLeadSignal buildSignal(OptionLeadState state, double spot) {
        return new OptionLeadSignal(
                state.phase,
                state.signalDirection,
                state.confidenceBoost,
                state.breakoutPremium,
                spot,
                state.signalTime
        );
    }

    private double getOptionPremium(IndexType indexType, int atm, String optType) {
        LocalDate expiry = expiryCalendar.getCurrentExpiry(indexType);
        return liveInstrumentCache.getOption(indexType, atm, optType, expiry)
                .map(OptionInstrument::getLastPrice)
                .orElse(0.0);
    }

    // ── State & Signal Records ────────────────────────────────────────────

    static class OptionLeadState {
        volatile SignalPhase phase = SignalPhase.NONE;
        volatile int signalDirection = 0;
        volatile int confidenceBoost = 0;
        volatile double ceSessionHigh = 0;
        volatile double peSessionHigh = 0;
        volatile double spotAtBreakout = 0;
        volatile double breakoutPremium = 0;
        volatile double breakoutStrengthPct = 0;
        volatile boolean volumeConfirmed = false;
        volatile boolean crossIndexConfirmed = false;
        volatile Instant signalTime = null;
        volatile int breakoutTickCount = 0;
        volatile int lastBreakoutDir = 0;

        // For velocity tracking (abnormal spike detection)
        volatile long lastSampleTime = 0;
        volatile double lastCePremium = 0;
        volatile double lastPePremium = 0;

        void reset() {
            phase = SignalPhase.NONE;
            signalDirection = 0;
            confidenceBoost = 0;
            breakoutTickCount = 0;
            lastBreakoutDir = 0;
            spotAtBreakout = 0;
            breakoutPremium = 0;
            breakoutStrengthPct = 0;
            volumeConfirmed = false;
            crossIndexConfirmed = false;
            signalTime = null;
            // Don't reset session highs — they persist for the day
        }
    }

    /**
     * The signal result from option-leads-index analysis.
     */
    public record OptionLeadSignal(
            SignalPhase phase,
            int direction,
            int confidenceBoost,
            double breakoutPremium,
            double spot,
            Instant signalTime
    ) {
        public static final OptionLeadSignal NONE = new OptionLeadSignal(
                SignalPhase.NONE, 0, 0, 0, 0, null);

        public boolean hasSignal() { return phase != SignalPhase.NONE && direction != 0; }
        public boolean isBullish() { return direction > 0; }
        public boolean isBearish() { return direction < 0; }
        public boolean isImmediate() { return phase == SignalPhase.ABNORMAL_SPIKE; }
        public boolean isConfirmed() { return phase == SignalPhase.INDEX_CONFIRMING; }
        public boolean isDiverging() { return phase == SignalPhase.INDEX_DIVERGING; }

        public String reason() {
            return switch (phase) {
                case OPTION_BREAKOUT -> String.format("OPTION_LEADS: %s breakout at ₹%.1f — index lag, anticipating follow",
                        direction > 0 ? "CE" : "PE", breakoutPremium);
                case ABNORMAL_SPIKE -> String.format("OPTION_LEADS: ABNORMAL %s spike — event/news anticipated, immediate entry",
                        direction > 0 ? "CE" : "PE");
                case INDEX_CONFIRMING -> String.format("OPTION_LEADS: Index confirming %s direction — confidence +%d",
                        direction > 0 ? "bullish" : "bearish", confidenceBoost);
                case INDEX_DIVERGING -> "OPTION_LEADS: Index DIVERGING — reduce position or hedge";
                case NONE -> "";
            };
        }
    }
}
