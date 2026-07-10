package com.algo.trade.strategy.oimomentum;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Z-score normalization of ignition features per regime.
 *
 * <p>Every input (PCR slope, VIX percentile, OI delta, premium velocity, spread) is normalized
 * per regime so dimensional bias is prevented (e.g., VIX scale doesn't swamp PCR). Running mean/std
 * are maintained using Welford's online algorithm — zero allocation per observation.</p>
 *
 * <h2>Direction vs Magnitude separation</h2>
 * <ul>
 *   <li><b>Direction features</b>: OI delta, premium velocity, microstructure → sign matters (long/short)</li>
 *   <li><b>Magnitude features</b>: VIX, PCR → regime/sizing/stand-down decisions only</li>
 * </ul>
 * Callers tag features as direction vs magnitude; the normalizer stores separate running stats per regime.
 */
@Component
public class FeatureNormalizer {

    private static final Logger log = LoggerFactory.getLogger(FeatureNormalizer.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** Minimum observations before z-scores are meaningful (warm-up). */
    private static final int MIN_OBSERVATIONS = 20;

    /** Clamp z-scores to ±3 to prevent outlier dominance. */
    private static final double Z_CLAMP = 3.0;

    // Running stats per (regime, featureName)
    private final Map<String, RunningStats> stats = new ConcurrentHashMap<>();
    private volatile LocalDate statsDay = null;

    public enum FeatureType { DIRECTION, MAGNITUDE }

    /** Observation: feed a raw value for a feature under a given regime. */
    public void observe(String regime, String featureName, double value) {
        resetIfNewDay();
        String key = regime + "|" + featureName;
        stats.computeIfAbsent(key, k -> new RunningStats()).add(value);
    }

    /**
     * Get the z-score of a raw value for a feature under the current regime.
     * Returns 0 if insufficient data (warm-up period).
     */
    public double zScore(String regime, String featureName, double rawValue) {
        resetIfNewDay();
        String key = regime + "|" + featureName;
        RunningStats rs = stats.get(key);
        if (rs == null) return 0.0;
        double[] snap = rs.snapshot();          // [count, mean, std] read atomically under lock
        if (snap[0] < MIN_OBSERVATIONS) return 0.0;
        double std = snap[2];
        if (std < 1e-9) return 0.0;
        double z = (rawValue - snap[1]) / std;
        return Math.max(-Z_CLAMP, Math.min(Z_CLAMP, z));
    }

    /**
     * Normalized bias contribution: z-score × weight, separated by type.
     * Direction features contribute to long/short bias; magnitude features contribute to sizing/regime.
     */
    public double weightedScore(String regime, String featureName, double rawValue, double weight) {
        return zScore(regime, featureName, rawValue) * weight;
    }

    /** Check if a feature has warmed up (enough observations for meaningful z-scores). */
    public boolean isWarmedUp(String regime, String featureName) {
        String key = regime + "|" + featureName;
        RunningStats rs = stats.get(key);
        return rs != null && rs.count >= MIN_OBSERVATIONS;
    }

    /** Number of observations for a feature/regime. */
    public int observationCount(String regime, String featureName) {
        String key = regime + "|" + featureName;
        RunningStats rs = stats.get(key);
        return rs != null ? (int) rs.count : 0;
    }

    /** Snapshot of all stats for diagnostics/logging. */
    public Map<String, Map<String, Object>> snapshot() {
        Map<String, Map<String, Object>> snap = new ConcurrentHashMap<>();
        stats.forEach((key, rs) -> {
            Map<String, Object> m = new ConcurrentHashMap<>();
            m.put("count", rs.count);
            m.put("mean", String.format("%.4f", rs.mean()));
            m.put("std", String.format("%.4f", rs.standardDeviation()));
            snap.put(key, m);
        });
        return snap;
    }

    /** Reset stats on new trading day (features should be re-learned each day). */
    private void resetIfNewDay() {
        LocalDate today = LocalDate.now(IST);
        if (!today.equals(statsDay)) {
            if (statsDay != null && !stats.isEmpty()) {
                log.info("[FeatureNorm] New day {} — resetting {} feature stats from {}",
                        today, stats.size(), statsDay);
            }
            stats.clear();
            statsDay = today;
        }
    }

    /**
     * Welford's online algorithm for running mean and variance (numerically stable).
     */
    static class RunningStats {
        // volatile so the unlocked count reads (isWarmedUp / observationCount / zScore gate) can't tear
        // or see a stale value; mean/m2 are always read via synchronized accessors below.
        volatile long count = 0;
        double mean = 0.0;
        double m2 = 0.0;

        synchronized void add(double value) {
            count++;
            double delta = value - mean;
            mean += delta / count;
            double delta2 = value - mean;
            m2 += delta * delta2;
        }

        synchronized double mean() { return mean; }

        synchronized double variance() {
            return count < 2 ? 0.0 : m2 / (count - 1);
        }

        synchronized double standardDeviation() {
            return Math.sqrt(variance());
        }

        /** Atomic snapshot of {@code [count, mean, std]} so a z-score is computed from one consistent
         *  state — never a mean from before an {@link #add} paired with a std from after it. */
        synchronized double[] snapshot() {
            double var = count < 2 ? 0.0 : m2 / (count - 1);
            return new double[]{ (double) count, mean, Math.sqrt(var) };
        }
    }
}
