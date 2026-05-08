package com.algo.trade.strategy;

import com.algo.trade.strategy.AlgoFlowOrchestrator.SessionWindow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Computes a market-wide environment quality score (0–100) from multiple signals.
 *
 * <p>The score is a weighted sum of 5 sub-scores:
 * <ul>
 *   <li>Regime score (30%) — direct from {@link com.algo.trade.regime.RegimeFilter}</li>
 *   <li>PCR alignment (25%) — how well the put-call ratio aligns with trade direction</li>
 *   <li>IV rank (20%) — implied volatility percentile favorability</li>
 *   <li>OI change (15%) — open interest change confirming or contradicting direction</li>
 *   <li>Session window (10%) — time-of-day quality factor</li>
 * </ul>
 *
 * <p>Edge cases: PCR=0 (no data) returns a neutral sub-score of 50.
 * Division by zero in any sub-score computation returns 50 (neutral).
 */
@Component
public class MarketEnvironmentScorer {

    private static final Logger log = LoggerFactory.getLogger(MarketEnvironmentScorer.class);

    /** Immutable result record holding the total score and a JSON breakdown. */
    public record EnvironmentScore(
        int totalScore,       // 0-100
        String breakdown      // JSON: {"regime":72,"pcr":80,"ivRank":60,"oiChange":55,"session":100}
    ) {}

    /**
     * Compute market-wide environment quality score.
     *
     * @param regimeScore      regime score from RegimeFilter (0–100)
     * @param pcr              put-call ratio (0 = no data sentinel)
     * @param optionType       "CE" or "PE"
     * @param ivRank           implied volatility rank (0–100)
     * @param oiChangePercent  open interest change percentage (-100 to +100)
     * @param session          current session window
     * @return EnvironmentScore with totalScore clamped to [0,100] and JSON breakdown
     */
    public EnvironmentScore compute(int regimeScore, double pcr, String optionType,
                                     double ivRank, double oiChangePercent,
                                     SessionWindow session) {
        int pcrScore = pcrAlignmentScore(pcr, optionType);
        int ivScore = ivRankScore(ivRank);
        int oiScore = oiChangeScore(oiChangePercent, optionType);
        int sessScore = sessionScore(session);

        // Weighted formula
        double raw = regimeScore * 0.30
                   + pcrScore * 0.25
                   + ivScore * 0.20
                   + oiScore * 0.15
                   + sessScore * 0.10;

        int totalScore = (int) Math.round(raw);

        // Clamp to [0, 100]
        totalScore = Math.max(0, Math.min(100, totalScore));

        // Build JSON breakdown
        String breakdown = String.format(
            "{\"regime\":%d,\"pcr\":%d,\"ivRank\":%d,\"oiChange\":%d,\"session\":%d}",
            regimeScore, pcrScore, ivScore, oiScore, sessScore
        );

        log.debug("EnvironmentScore: total={}, breakdown={}", totalScore, breakdown);
        return new EnvironmentScore(totalScore, breakdown);
    }

    /**
     * Compute PCR alignment sub-score (0–100).
     *
     * <p>For CE trades: PCR 0.8–1.2 = 100 (ideal range), contradicts = 0.
     * For PE trades: PCR 1.0–1.5 = 100 (ideal range), contradicts = 0.
     * Linear interpolation between ideal and contradiction zones.
     * PCR = 0 (no data sentinel) returns 50 (neutral).
     */
    int pcrAlignmentScore(double pcr, String optionType) {
        // Edge case: PCR=0 means no data available
        if (pcr == 0) {
            return 50;
        }

        if ("CE".equalsIgnoreCase(optionType)) {
            return pcrScoreCE(pcr);
        } else if ("PE".equalsIgnoreCase(optionType)) {
            return pcrScorePE(pcr);
        }

        // Unknown option type — return neutral
        log.warn("Unknown optionType='{}' for PCR scoring — returning neutral 50", optionType);
        return 50;
    }

    private int pcrScoreCE(double pcr) {
        // CE ideal range: 0.8–1.2 → score 100
        // Below 0.4 → contradicts (too bullish, no put support) → 0
        // Above 2.0 → contradicts (too bearish) → 0
        // Linear interpolation in between

        if (pcr >= 0.8 && pcr <= 1.2) {
            return 100;
        } else if (pcr < 0.8) {
            // From 0.4 (score=0) to 0.8 (score=100), linear
            if (pcr <= 0.4) return 0;
            return (int) Math.round((pcr - 0.4) / (0.8 - 0.4) * 100);
        } else {
            // From 1.2 (score=100) to 2.0 (score=0), linear
            if (pcr >= 2.0) return 0;
            return (int) Math.round((2.0 - pcr) / (2.0 - 1.2) * 100);
        }
    }

    private int pcrScorePE(double pcr) {
        // PE ideal range: 1.0–1.5 → score 100
        // Below 0.5 → contradicts (too bullish, no put support for PE) → 0
        // Above 2.5 → contradicts (extreme bearish) → 0
        // Linear interpolation in between

        if (pcr >= 1.0 && pcr <= 1.5) {
            return 100;
        } else if (pcr < 1.0) {
            // From 0.5 (score=0) to 1.0 (score=100), linear
            if (pcr <= 0.5) return 0;
            return (int) Math.round((pcr - 0.5) / (1.0 - 0.5) * 100);
        } else {
            // From 1.5 (score=100) to 2.5 (score=0), linear
            if (pcr >= 2.5) return 0;
            return (int) Math.round((2.5 - pcr) / (2.5 - 1.5) * 100);
        }
    }

    /**
     * Compute IV rank sub-score (0–100).
     *
     * <p>Lower IV rank is better for buying options:
     * <ul>
     *   <li>&lt;30 → 100 (cheap IV, ideal for buying)</li>
     *   <li>30–50 → linear interpolation 100→50</li>
     *   <li>50–70 → linear interpolation 50→0</li>
     *   <li>&gt;70 → 0 (expensive IV, unfavorable)</li>
     * </ul>
     */
    int ivRankScore(double ivRank) {
        if (ivRank < 30) {
            return 100;
        } else if (ivRank <= 50) {
            // Linear from 100 (at 30) to 50 (at 50)
            return (int) Math.round(100 - (ivRank - 30) / (50 - 30) * 50);
        } else if (ivRank <= 70) {
            // Linear from 50 (at 50) to 0 (at 70)
            return (int) Math.round(50 - (ivRank - 50) / (70 - 50) * 50);
        } else {
            return 0;
        }
    }

    /**
     * Compute OI change sub-score (0–100).
     *
     * <p>OI change confirms or contradicts the trade direction:
     * <ul>
     *   <li>Confirms direction &gt;5% → 100</li>
     *   <li>Flat (±2%) → 50</li>
     *   <li>Contradicts direction &gt;5% → 0</li>
     *   <li>Linear interpolation between zones</li>
     * </ul>
     *
     * <p>For CE: positive OI change confirms (call writing/building).
     * For PE: negative OI change confirms (put unwinding = bullish for PE buyers... 
     * actually for PE buyers, positive OI change in puts confirms bearish direction).
     */
    int oiChangeScore(double oiChangePercent, String optionType) {
        // Determine directional alignment:
        // CE trades benefit from positive OI change (call OI building = more activity)
        // PE trades benefit from positive OI change (put OI building = bearish confirmation)
        // The sign of oiChangePercent already represents the direction for the given option type
        double alignedChange = oiChangePercent;

        if (alignedChange > 5) {
            return 100;  // Confirms direction strongly
        } else if (alignedChange >= 2) {
            // Linear from 50 (at 2%) to 100 (at 5%)
            return (int) Math.round(50 + (alignedChange - 2) / (5 - 2) * 50);
        } else if (alignedChange >= -2) {
            // Flat zone: ±2% → 50
            return 50;
        } else if (alignedChange >= -5) {
            // Linear from 50 (at -2%) to 0 (at -5%)
            return (int) Math.round(50 - (-2 - alignedChange) / (5 - 2) * 50);
        } else {
            return 0;  // Contradicts direction strongly
        }
    }

    /**
     * Compute session window sub-score (0–100).
     *
     * <p>Fixed scores per session window reflecting trading quality:
     * <ul>
     *   <li>MORNING_MOMENTUM = 100</li>
     *   <li>POST_OPEN = 80</li>
     *   <li>GAMMA_SCALPING = 75</li>
     *   <li>AFTERNOON = 70</li>
     *   <li>PRE_EXPIRY = 60</li>
     *   <li>MIDDAY_CHOP = 40</li>
     * </ul>
     */
    int sessionScore(SessionWindow session) {
        if (session == null) {
            log.warn("Null session window — returning neutral 50");
            return 50;
        }

        return switch (session) {
            case MORNING_MOMENTUM -> 100;
            case POST_OPEN -> 80;
            case GAMMA_SCALPING -> 75;
            case AFTERNOON -> 70;
            case PRE_EXPIRY -> 60;
            case MIDDAY_CHOP -> 40;
        };
    }
}
