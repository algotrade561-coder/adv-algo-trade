package com.algo.trade.regime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Classifies the current market regime using a multi-factor scoring model.
 * Factors: VIX level, IV rank, PCR balance, trend signal, OI wall distance.
 * Score range: [0, 100] → mapped to IDEAL / GOOD / NEUTRAL / RISKY / DANGER.
 */
@Component
public class RegimeFilter {

    private static final Logger log = LoggerFactory.getLogger(RegimeFilter.class);

    public enum MarketRegime {
        IDEAL, GOOD, NEUTRAL, RISKY, DANGER
    }

    /**
     * Compute regime score (0–100) from market indicators.
     *
     * <p>Scoring rules (7 factors):</p>
     * <ul>
     *   <li>Base: 50</li>
     *   <li>VIX 13–18: +15 | VIX 11–21: +8 | VIX > 22: −20</li>
     *   <li>IV rank 30–70: +10 | IV rank > 80: −10</li>
     *   <li>PCR 0.8–1.3: +10 | PCR > 1.5 or < 0.5: −15</li>
     *   <li>Trend signal 0 (range-bound): +10</li>
     *   <li>OI wall distance: +5 to +15 based on proximity</li>
     *   <li>VIX trend: rising −10, falling +5</li>
     *   <li>Final score clamped to [0, 100]</li>
     * </ul>
     */
    public int computeScore(double vix, double ivRank, double pcr,
                            int trendSignal, double oiWallDistancePercent) {
        return computeScore(vix, ivRank, pcr, trendSignal, oiWallDistancePercent, 0);
    }

    /**
     * Extended scoring with VIX trend factor.
     * @param vixTrend 1 = VIX rising (danger), -1 = VIX falling (opportunity), 0 = flat
     */
    public int computeScore(double vix, double ivRank, double pcr,
                            int trendSignal, double oiWallDistancePercent, int vixTrend) {
        int score = 50;

        // VIX contribution
        if (vix >= 13 && vix <= 18) {
            score += 15;
        } else if (vix >= 11 && vix <= 21) {
            score += 8;
        } else if (vix > 22) {
            score -= 20;
        }

        // IV rank contribution
        if (ivRank >= 30 && ivRank <= 70) {
            score += 10;
        } else if (ivRank > 80) {
            score -= 10;
        }

        // PCR contribution
        if (pcr >= 0.8 && pcr <= 1.3) {
            score += 10;
        } else if (pcr > 1.5 || pcr < 0.5) {
            score -= 15;
        }

        // Trend signal contribution
        if (trendSignal == 0) {
            score += 10;
        }

        // OI wall distance contribution: closer walls → more points
        if (oiWallDistancePercent <= 1.0) {
            score += 15;
        } else if (oiWallDistancePercent <= 2.0) {
            score += 12;
        } else if (oiWallDistancePercent <= 3.0) {
            score += 10;
        } else if (oiWallDistancePercent <= 5.0) {
            score += 8;
        } else {
            score += 5;
        }

        // VIX trend contribution: rising VIX = danger, falling = opportunity
        if (vixTrend > 0) {
            score -= 10; // VIX rising — increased risk
        } else if (vixTrend < 0) {
            score += 5;  // VIX falling — calming market
        }

        // Clamp to [0, 100]
        score = Math.max(0, Math.min(100, score));

        log.debug("RegimeFilter score={} | vix={}, ivRank={}, pcr={}, trend={}, oiWall={}, vixTrend={}",
                score, vix, ivRank, pcr, trendSignal, oiWallDistancePercent, vixTrend);
        return score;
    }

    /**
     * Classify a score into a market regime.
     * 80–100 = IDEAL, 60–79 = GOOD, 40–59 = NEUTRAL, 20–39 = RISKY, 0–19 = DANGER.
     */
    public MarketRegime classify(int score) {
        if (score >= 80) return MarketRegime.IDEAL;
        if (score >= 60) return MarketRegime.GOOD;
        if (score >= 40) return MarketRegime.NEUTRAL;
        if (score >= 20) return MarketRegime.RISKY;
        return MarketRegime.DANGER;
    }

    /**
     * Convenience method: compute score and classify in one call.
     */
    public MarketRegime detectRegime(double vix, double ivRank, double pcr,
                                      int trendSignal, double oiWallDistancePercent) {
        int score = computeScore(vix, ivRank, pcr, trendSignal, oiWallDistancePercent);
        MarketRegime regime = classify(score);
        log.debug("RegimeFilter: detected regime={} (score={})", regime, score);
        return regime;
    }

    /**
     * Extended regime detection with VIX trend.
     */
    public MarketRegime detectRegime(double vix, double ivRank, double pcr,
                                      int trendSignal, double oiWallDistancePercent, int vixTrend) {
        int score = computeScore(vix, ivRank, pcr, trendSignal, oiWallDistancePercent, vixTrend);
        return classify(score);
    }

    /** Should we enter short premium trades? Score >= 60. */
    public boolean isShortPremiumAllowed(int score) {
        return score >= 60;
    }

    /** Should we use defined risk only (iron condor) instead of naked (straddle)? Score 40-60. */
    public boolean useDefinedRiskOnly(int score) {
        return score >= 40 && score < 60;
    }

    /** Is it too dangerous for any new trades? Score < 20. */
    public boolean isDangerZone(int score) {
        return score < 20;
    }
}
