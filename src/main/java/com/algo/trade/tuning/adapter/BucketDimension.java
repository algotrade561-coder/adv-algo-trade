package com.algo.trade.tuning.adapter;

import java.util.List;
import java.util.Objects;

/**
 * Declarative bucket dimension for analyzer auto-breakdowns.
 *
 * <p>Each {@link TuningCaptureAdapter} declares zero or more {@code BucketDimension}s.
 * The analyzer (Phase 6) reads these declarations and auto-generates per-bucket
 * tables (count, win rate, avg PnL, avg MAE, slippage) without any per-strategy
 * analyzer code. <strong>This is what makes the framework scale to new strategies
 * with ~30 lines of adapter code.</strong></p>
 *
 * <h2>Example declarations</h2>
 * <pre>
 *   // OI Momentum:
 *   new BucketDimension("entryCase", "entryCase", null, BandStyle.CATEGORICAL)
 *   new BucketDimension("biasScore", "biasScore", List.of(0d, 20d, 30d, 40d, 50d), BandStyle.NUMERIC_RANGE)
 *
 *   // OI Shift Trap:
 *   new BucketDimension("score", "score", List.of(50d, 60d, 70d, 80d, 90d), BandStyle.NUMERIC_RANGE)
 *   new BucketDimension("imbalance", "imbalance", List.of(1.5d, 2.0d, 3.0d, 4.0d), BandStyle.NUMERIC_RANGE)
 *   new BucketDimension("trapSide", "trapSide", null, BandStyle.CATEGORICAL)
 *
 *   // Gap-and-Go:
 *   new BucketDimension("gapPct", "gapPct", List.of(0.15d, 0.30d, 0.50d, 1.0d), BandStyle.NUMERIC_RANGE)
 * </pre>
 *
 * @param name short identifier shown in the report (column header, section title)
 * @param attributeKey key in {@code SignalEvent.attributes} to bucket by
 * @param bandEdges sorted lower bounds for NUMERIC_RANGE; ignored for CATEGORICAL / BOOLEAN
 * @param style how to bucket — numeric range, categorical (one bucket per distinct value), boolean
 */
public record BucketDimension(
        String name,
        String attributeKey,
        List<Double> bandEdges,
        BandStyle style
) {

    public BucketDimension {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(attributeKey, "attributeKey");
        Objects.requireNonNull(style, "style");
        if (style == BandStyle.NUMERIC_RANGE) {
            if (bandEdges == null || bandEdges.size() < 2) {
                throw new IllegalArgumentException("NUMERIC_RANGE requires ≥ 2 band edges, got " + bandEdges);
            }
            // Verify edges are sorted ascending.
            for (int i = 1; i < bandEdges.size(); i++) {
                if (bandEdges.get(i) <= bandEdges.get(i - 1)) {
                    throw new IllegalArgumentException("bandEdges must be strictly ascending; got " + bandEdges);
                }
            }
        }
    }

    /**
     * For a given numeric value, returns the band label (e.g. {@code "50–59"} for
     * value 52 with edges {@code [50, 60, 70]}). Used by the analyzer during
     * aggregation. Returns the band index as string for values below the first edge
     * (e.g. {@code "<50"}) or above the last edge (e.g. {@code "90+"}).
     */
    public String bandFor(double value) {
        if (style != BandStyle.NUMERIC_RANGE || bandEdges == null) {
            return String.valueOf(value);
        }
        if (value < bandEdges.get(0)) {
            return "<" + format(bandEdges.get(0));
        }
        for (int i = 0; i < bandEdges.size() - 1; i++) {
            if (value >= bandEdges.get(i) && value < bandEdges.get(i + 1)) {
                return format(bandEdges.get(i)) + "–" + format(bandEdges.get(i + 1) - 1);
            }
        }
        return format(bandEdges.get(bandEdges.size() - 1)) + "+";
    }

    private static String format(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    public enum BandStyle {
        /** Numeric value, bucketed into bands using {@link BucketDimension#bandEdges}. */
        NUMERIC_RANGE,

        /** String value, one bucket per distinct value seen. */
        CATEGORICAL,

        /** Boolean value, two buckets: {@code true} and {@code false}. */
        BOOLEAN
    }
}
