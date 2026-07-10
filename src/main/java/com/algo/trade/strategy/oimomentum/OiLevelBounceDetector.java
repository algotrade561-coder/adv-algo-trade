package com.algo.trade.strategy.oimomentum;

import com.algo.trade.domain.IndexType;
import com.algo.trade.domain.OptionInstrument;
import com.algo.trade.marketdata.LiveInstrumentCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OI Level Bounce Detector — identifies support/resistance entries from OI structure.
 *
 * <p>On range-bound days, the profitable trades are bounces off high-OI levels:
 * - Price approaches max PUT OI strike (support) → bounces UP → BUY CE signal
 * - Price approaches max CALL OI strike (resistance) → rejects DOWN → BUY PE signal
 *
 * <p>This is a MEAN-REVERSION entry, opposite of the breakout/momentum signals.
 * It fires when:
 * 1. Spot is within 0.2% of a max-OI support/resistance level
 * 2. Price shows rejection (moves away from the level for ≥2 consecutive ticks)
 * 3. OI at that level is genuinely dominant (top 3 by OI)
 *
 * <p>The signal is conservative: it only fires at STRONG OI levels where operators
 * are clearly defending (high put OI = they don't want price below, high call OI =
 * they don't want price above). This is how operators pin prices intraday.</p>
 */
@Component
public class OiLevelBounceDetector {

    private static final Logger log = LoggerFactory.getLogger(OiLevelBounceDetector.class);

    private final LiveInstrumentCache liveInstrumentCache;

    /** Reversal detector — used to confirm bounce signals (optional, strengthens conviction). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private OperatorReversalDetector operatorReversalDetector;

    /** Premium velocity — used to confirm bounce signals (premium acceleration in bounce direction). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private PremiumVelocityTracker premiumVelocityTracker;

    @Value("${oi-momentum.level-bounce.enabled:true}")
    private boolean enabled;

    /** How close spot must be to the OI level to start watching for a bounce (% of spot). */
    @Value("${oi-momentum.level-bounce.proximity-pct:0.40}")
    private double proximityPct;

    /** Minimum consecutive ticks showing price moving AWAY from the level (rejection). */
    @Value("${oi-momentum.level-bounce.rejection-ticks:5}")
    private int rejectionTicks;

    /** Minimum OI at the level relative to total ATM±5 OI (must be a dominant level). */
    @Value("${oi-momentum.level-bounce.min-oi-dominance-pct:15}")
    private double minOiDominancePct;

    private final Map<IndexType, BounceState> states = new ConcurrentHashMap<>();

    public record BounceSignal(
            int direction,       // +1 = bounce UP (buy CE), -1 = bounce DOWN (buy PE)
            int levelStrike,     // the OI level that caused the bounce
            double levelOiPct,   // OI dominance % at that level
            double distancePct,  // how close spot was to the level
            String type,         // "SUPPORT_BOUNCE" or "RESISTANCE_REJECT"
            Instant detectedAt
    ) {
        public static final BounceSignal NONE = new BounceSignal(0, 0, 0, 0, "", Instant.EPOCH);
        public boolean isActive() { return direction != 0; }
    }

    private static class BounceState {
        int maxPutOiStrike = 0;    // support level
        int maxCallOiStrike = 0;   // resistance level
        long maxPutOi = 0;
        long maxCallOi = 0;
        long totalOi = 0;
        Instant lastLevelCalcTime = null;

        // Rejection tracking
        int supportRejectionTicks = 0;  // consecutive ticks moving away from support
        int resistanceRejectionTicks = 0;
        double lastSpot = 0;

        BounceSignal lastSignal = BounceSignal.NONE;
        Instant lastSignalTime = Instant.EPOCH;
    }

    public OiLevelBounceDetector(LiveInstrumentCache liveInstrumentCache) {
        this.liveInstrumentCache = liveInstrumentCache;
    }

    // ── Public API ──

    public BounceSignal getSignal(IndexType indexType) {
        if (!enabled) return BounceSignal.NONE;
        BounceState state = states.get(indexType);
        if (state == null) return BounceSignal.NONE;
        // Signal valid for 60 seconds
        if (state.lastSignal.isActive()
                && Duration.between(state.lastSignalTime, Instant.now()).getSeconds() < 60) {
            return state.lastSignal;
        }
        return BounceSignal.NONE;
    }

    public boolean isEnabled() { return enabled; }

    /**
     * Tick — called every second. Updates OI levels (every 60s) and tracks price rejection.
     */
    public void tick(IndexType indexType) {
        if (!enabled) return;
        BounceState state = states.computeIfAbsent(indexType, k -> new BounceState());
        Instant now = Instant.now();

        // Refresh OI levels every 60 seconds
        if (state.lastLevelCalcTime == null
                || Duration.between(state.lastLevelCalcTime, now).getSeconds() >= 60) {
            refreshLevels(indexType, state);
            state.lastLevelCalcTime = now;
        }

        if (state.maxPutOiStrike == 0 && state.maxCallOiStrike == 0) return;

        double spot = liveInstrumentCache.getFuturesPrice(indexType);
        if (spot <= 0) return;

        // Check proximity to support (max put OI)
        if (state.maxPutOiStrike > 0) {
            double distToSupport = (spot - state.maxPutOiStrike) / spot * 100;
            if (distToSupport >= 0 && distToSupport <= proximityPct) {
                // Price is near support — check for bounce (price moving UP/away)
                if (state.lastSpot > 0 && spot > state.lastSpot) {
                    state.supportRejectionTicks++;
                } else {
                    state.supportRejectionTicks = 0;
                }

                if (state.supportRejectionTicks >= rejectionTicks) {
                    double oiPct = state.totalOi > 0 ? (double) state.maxPutOi / state.totalOi * 100 : 0;
                    if (oiPct >= minOiDominancePct && isReversalConfirmed(indexType, 1)) {
                        // Cooldown: don't fire same signal within 5 minutes
                        if (Duration.between(state.lastSignalTime, now).getSeconds() >= 300) {
                            state.lastSignal = new BounceSignal(1, state.maxPutOiStrike, oiPct,
                                    distToSupport, "SUPPORT_BOUNCE", now);
                            state.lastSignalTime = now;
                            state.supportRejectionTicks = 0;
                            log.info("[LevelBounce][{}] SUPPORT_BOUNCE: spot={} near putOI={} ({}% of total), " +
                                            "price bouncing UP ({} ticks, reversal confirmed)",
                                    indexType, String.format("%.0f", spot), state.maxPutOiStrike,
                                    String.format("%.1f", oiPct), rejectionTicks);
                        }
                    }
                }
            } else {
                state.supportRejectionTicks = 0;
            }
        }

        // Check proximity to resistance (max call OI)
        if (state.maxCallOiStrike > 0) {
            double distToResistance = (state.maxCallOiStrike - spot) / spot * 100;
            if (distToResistance >= 0 && distToResistance <= proximityPct) {
                // Price is near resistance — check for rejection (price moving DOWN/away)
                if (state.lastSpot > 0 && spot < state.lastSpot) {
                    state.resistanceRejectionTicks++;
                } else {
                    state.resistanceRejectionTicks = 0;
                }

                if (state.resistanceRejectionTicks >= rejectionTicks) {
                    double oiPct = state.totalOi > 0 ? (double) state.maxCallOi / state.totalOi * 100 : 0;
                    if (oiPct >= minOiDominancePct && isReversalConfirmed(indexType, -1)) {
                        if (Duration.between(state.lastSignalTime, now).getSeconds() >= 300) {
                            state.lastSignal = new BounceSignal(-1, state.maxCallOiStrike, oiPct,
                                    distToResistance, "RESISTANCE_REJECT", now);
                            state.lastSignalTime = now;
                            state.resistanceRejectionTicks = 0;
                            log.info("[LevelBounce][{}] RESISTANCE_REJECT: spot={} near callOI={} ({}% of total), " +
                                            "price rejecting DOWN ({} ticks, reversal confirmed)",
                                    indexType, String.format("%.0f", spot), state.maxCallOiStrike,
                                    String.format("%.1f", oiPct), rejectionTicks);
                        }
                    }
                }
            } else {
                state.resistanceRejectionTicks = 0;
            }
        }

        state.lastSpot = spot;
    }

    /**
     * Reversal confirmation — requires at least ONE of:
     * 1. OperatorReversalDetector has an active signal in the same direction
     * 2. PremiumVelocityTracker shows premium acceleration in the bounce direction (≥2%)
     * 3. Rejection ticks are very strong (≥8) — self-confirming through price action alone
     *
     * This prevents premature entries on the first touch — waits for genuine confirmation.
     */
    private boolean isReversalConfirmed(IndexType indexType, int bounceDirection) {
        // Self-confirming: very strong rejection (8+ ticks = 8 seconds of consistent bounce)
        BounceState state = states.get(indexType);
        if (state != null) {
            int ticks = bounceDirection > 0 ? state.supportRejectionTicks : state.resistanceRejectionTicks;
            if (ticks >= 8) return true; // strong enough to self-confirm
        }

        // Operator reversal detector confirms
        if (operatorReversalDetector != null && operatorReversalDetector.isEnabled()) {
            var revSig = operatorReversalDetector.getSignal(indexType);
            if (revSig.isActive() && revSig.direction() == bounceDirection) {
                return true;
            }
        }

        // Premium velocity confirms (≥2% acceleration in bounce direction)
        if (premiumVelocityTracker != null) {
            var premVel = premiumVelocityTracker.getVelocity(indexType);
            if (premVel.hasSignal() && premVel.direction() == bounceDirection
                    && premVel.maxPremiumVelocityPct() >= 2.0) {
                return true;
            }
        }

        return false;
    }

    private void refreshLevels(IndexType indexType, BounceState state) {
        long maxCeOi = 0, maxPeOi = 0;
        int maxCeStrike = 0, maxPeStrike = 0;
        long totalOi = 0;

        for (OptionInstrument opt : liveInstrumentCache.allOptions()) {
            if (opt.getIndexType() != indexType) continue;
            if (opt.getOpenInterest() <= 0) continue;
            totalOi += opt.getOpenInterest();
            if ("CE".equals(opt.getOptionType()) && opt.getOpenInterest() > maxCeOi) {
                maxCeOi = opt.getOpenInterest();
                maxCeStrike = opt.getStrikePrice();
            }
            if ("PE".equals(opt.getOptionType()) && opt.getOpenInterest() > maxPeOi) {
                maxPeOi = opt.getOpenInterest();
                maxPeStrike = opt.getStrikePrice();
            }
        }

        state.maxCallOiStrike = maxCeStrike;
        state.maxPutOiStrike = maxPeStrike;
        state.maxCallOi = maxCeOi;
        state.maxPutOi = maxPeOi;
        state.totalOi = totalOi;
    }
}
