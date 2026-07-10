package com.algo.trade.strategy.oimomentum;

import com.algo.trade.domain.IndexType;
import com.algo.trade.marketdata.LiveInstrumentCache;
import com.algo.trade.risk.MarketGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Decision Aggregator — combines normalized ignition features into a unified bias score
 * and a size multiplier, implementing the Direction vs Magnitude split.
 *
 * <h2>Direction Features → Bias Score</h2>
 * <ul>
 *   <li>OI delta (normalized % change in 5 min) — strongest directional signal</li>
 *   <li>Premium velocity (ATM premium % change) — gamma ignition signal</li>
 *   <li>Microstructure (bid/ask spread imbalance) — trap/accumulation detection</li>
 * </ul>
 *
 * <h2>Magnitude Features → Size Multiplier</h2>
 * <ul>
 *   <li>PCR slope (put-call ratio change rate) — regime/magnitude indicator</li>
 *   <li>VIX percentile — lot sizing, SL/target width scaling</li>
 * </ul>
 *
 * <p>Direction features drive long/short (additive to bias). Magnitude features drive
 * sizing and floor adjustments (multiplicative). This separation prevents VIX from
 * deciding trade direction and OI from deciding lot size.</p>
 *
 * <h2>Weights</h2>
 * Per-regime weights with shrinkage toward uniform. Updated by OutcomeFeedbackEngine
 * after minimum sample window (no raw static weights).
 */
@Component
public class DecisionAggregator {

    private static final Logger log = LoggerFactory.getLogger(DecisionAggregator.class);

    private final FeatureNormalizer normalizer;
    private final PremiumVelocityTracker premiumVelocityTracker;
    private final MarketGuard marketGuard;
    private final LiveInstrumentCache liveInstrumentCache;

    // ── Per-regime direction weights (OI delta, premium velocity, microstructure) ──
    // These are initial estimates; OutcomeFeedbackEngine refines them over time.
    private static final double W_OI_DELTA_LOW_VOL = 0.50;
    private static final double W_PREM_VEL_LOW_VOL = 0.30;
    private static final double W_MICRO_LOW_VOL = 0.20;

    private static final double W_OI_DELTA_NORMAL = 0.45;
    private static final double W_PREM_VEL_NORMAL = 0.35;
    private static final double W_MICRO_NORMAL = 0.20;

    private static final double W_OI_DELTA_HIGH_VOL = 0.35;
    private static final double W_PREM_VEL_HIGH_VOL = 0.45;
    private static final double W_MICRO_HIGH_VOL = 0.20;

    private static final double W_OI_DELTA_EXTREME = 0.30;
    private static final double W_PREM_VEL_EXTREME = 0.50;
    private static final double W_MICRO_EXTREME = 0.20;

    // ── Magnitude scaling functions ──
    private static final double PCR_NEUTRAL = 1.0;     // PCR at which sizing is neutral
    private static final double VIX_NEUTRAL = 14.0;    // VIX at which sizing is neutral

    public DecisionAggregator(FeatureNormalizer normalizer,
                              PremiumVelocityTracker premiumVelocityTracker,
                              MarketGuard marketGuard,
                              LiveInstrumentCache liveInstrumentCache) {
        this.normalizer = normalizer;
        this.premiumVelocityTracker = premiumVelocityTracker;
        this.marketGuard = marketGuard;
        this.liveInstrumentCache = liveInstrumentCache;
    }

    /**
     * Aggregated result — direction bias (additive score points) + size multiplier.
     */
    public record AggregatedSignal(
            double directionBias,    // [-30, +30] additive points to bias score (positive = bullish)
            double sizeMultiplier,   // [0.5, 1.5] multiplicative lot scaling
            double confidence,       // [0, 1] overall feature confidence (warm-up fraction)
            String regime,
            String directionFeatures,  // diagnostic: which features contributed
            String magnitudeFeatures   // diagnostic: magnitude feature state
    ) {
        public static AggregatedSignal neutral(String regime) {
            return new AggregatedSignal(0, 1.0, 0, regime, "none", "none");
        }

        public boolean hasSignal() { return Math.abs(directionBias) > 2.0 && confidence > 0.3; }
    }

    /**
     * Compute the aggregated signal for an index given the current market state.
     *
     * @param indexType the index
     * @param regime current volatility regime (LOW_VOL, NORMAL, HIGH_VOL, EXTREME)
     * @param oiDeltaPct the 5-min OI % change (positive = call build, negative = put build)
     * @param pcrSlope5min PCR change over last 5 minutes
     * @param spreadPctChange bid/ask spread % change from baseline
     * @return aggregated direction bias + size multiplier
     */
    public AggregatedSignal compute(IndexType indexType, String regime,
                                    double oiDeltaPct, double pcrSlope5min,
                                    double spreadPctChange) {

        // Get premium velocity from tracker
        PremiumVelocityTracker.PremiumVelocity premVel = premiumVelocityTracker.getVelocity(indexType);
        double premVelPct = premVel.maxPremiumVelocityPct() * premVel.direction();

        // Get VIX
        double vix = marketGuard.getCurrentVix();

        // ── Feed observations into normalizer ──
        normalizer.observe(regime, "oiDelta", oiDeltaPct);
        normalizer.observe(regime, "premVel", premVelPct);
        normalizer.observe(regime, "spread", spreadPctChange);
        normalizer.observe(regime, "pcrSlope", pcrSlope5min);
        normalizer.observe(regime, "vix", vix);

        // ── Check warm-up (need all direction features warmed up) ──
        boolean oiWarmed = normalizer.isWarmedUp(regime, "oiDelta");
        boolean premWarmed = normalizer.isWarmedUp(regime, "premVel");
        boolean microWarmed = normalizer.isWarmedUp(regime, "spread");
        int warmedCount = (oiWarmed ? 1 : 0) + (premWarmed ? 1 : 0) + (microWarmed ? 1 : 0);
        double confidence = warmedCount / 3.0;

        if (confidence < 0.33) {
            return AggregatedSignal.neutral(regime); // not enough data yet
        }

        // ── Normalize direction features ──
        double normOi = normalizer.zScore(regime, "oiDelta", oiDeltaPct);
        double normPremVel = normalizer.zScore(regime, "premVel", premVelPct);
        double normSpread = normalizer.zScore(regime, "spread", spreadPctChange);

        // ── Compute weighted direction bias ──
        double[] weights = getDirectionWeights(regime);
        double rawBias = normOi * weights[0] + normPremVel * weights[1] + normSpread * weights[2];

        // Scale raw bias (z-score range ±3) to bias points (±30 max)
        double directionBias = Math.max(-30, Math.min(30, rawBias * 10.0));

        // ── Normalize magnitude features ──
        double normPcr = normalizer.zScore(regime, "pcrSlope", pcrSlope5min);
        double normVix = normalizer.zScore(regime, "vix", vix);

        // ── Compute size multiplier from magnitude features ──
        // High VIX → higher multiplier (volatility is opportunity on expiry)
        // PCR slope in momentum direction → confirms size
        double vixFactor = mapVixToMultiplier(vix);
        double pcrFactor = mapPcrSlopeToMultiplier(pcrSlope5min, directionBias > 0 ? 1 : -1);
        double sizeMultiplier = Math.max(0.5, Math.min(1.5, vixFactor * pcrFactor));

        // ── Build diagnostic strings ──
        String dirFeatures = String.format("OI=%.2f(w%.2f) Prem=%.2f(w%.2f) Micro=%.2f(w%.2f)",
                normOi, weights[0], normPremVel, weights[1], normSpread, weights[2]);
        String magFeatures = String.format("VIX=%.1f(×%.2f) PCR_slope=%+.3f(×%.2f)",
                vix, vixFactor, pcrSlope5min, pcrFactor);

        AggregatedSignal signal = new AggregatedSignal(
                directionBias, sizeMultiplier, confidence, regime, dirFeatures, magFeatures);

        if (signal.hasSignal()) {
            log.debug("[DecisionAgg][{}] bias={} sizeMult={} conf={}% regime={} dir=[{}] mag=[{}]",
                    indexType, String.format("%+.1f", directionBias), String.format("%.2f", sizeMultiplier),
                    (int)(confidence * 100), regime, dirFeatures, magFeatures);
        }

        return signal;
    }

    // ── Per-regime weight selection ─────────────────────────────────────────

    private double[] getDirectionWeights(String regime) {
        return switch (regime) {
            case "LOW_VOL" -> new double[]{W_OI_DELTA_LOW_VOL, W_PREM_VEL_LOW_VOL, W_MICRO_LOW_VOL};
            case "HIGH_VOL" -> new double[]{W_OI_DELTA_HIGH_VOL, W_PREM_VEL_HIGH_VOL, W_MICRO_HIGH_VOL};
            case "EXTREME" -> new double[]{W_OI_DELTA_EXTREME, W_PREM_VEL_EXTREME, W_MICRO_EXTREME};
            default -> new double[]{W_OI_DELTA_NORMAL, W_PREM_VEL_NORMAL, W_MICRO_NORMAL};
        };
    }

    // ── Magnitude mapping functions ─────────────────────────────────────────

    /**
     * Map VIX to lot size multiplier.
     * Low VIX → smaller lots (chop); High VIX → larger lots (directional).
     * Expiry-day focus: high VIX = opportunity for gamma runs.
     */
    private double mapVixToMultiplier(double vix) {
        if (vix <= 0) return 1.0;
        if (vix < 11) return 0.7;   // very low vol → reduce size
        if (vix < 13) return 0.85;  // low vol
        if (vix < 16) return 1.0;   // normal
        if (vix < 20) return 1.15;  // elevated → slightly larger
        return 1.3;                  // high vol → max size (directional moves)
    }

    /**
     * Map PCR slope to size multiplier.
     * PCR slope confirming direction → size up; opposing → size down.
     */
    private double mapPcrSlopeToMultiplier(double pcrSlope, int direction) {
        // direction: +1 = bullish bias, -1 = bearish bias
        // PCR rising (puts increasing) while bearish = confirmation → size up
        // PCR falling (calls increasing) while bullish = confirmation → size up
        boolean confirms = (direction > 0 && pcrSlope > 0.03)   // bullish + PCR rising (put heavy → squeeze potential)
                        || (direction < 0 && pcrSlope < -0.03); // bearish + PCR falling (call unwind)
        boolean opposes = (direction > 0 && pcrSlope < -0.05)
                       || (direction < 0 && pcrSlope > 0.05);

        if (confirms) return 1.15;
        if (opposes) return 0.85;
        return 1.0; // neutral
    }
}
