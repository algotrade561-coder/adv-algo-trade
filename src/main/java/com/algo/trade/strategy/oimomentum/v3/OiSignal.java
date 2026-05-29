package com.algo.trade.strategy.oimomentum.v3;

/**
 * V3 OPERATOR — Chain-wide OI signal with named pattern label.
 *
 * <p>The {@code direction} is +1 bullish, −1 bearish, 0 ambiguous/insufficient.</p>
 *
 * <p>{@code label} is one of {@link OiPattern} values; carried as a string for serialization.</p>
 *
 * <p>{@code strength} is a dimensionless ratio of the dominant flow to the opposite-side
 * flow (e.g. |sumCeChg| / sumPeChg). Higher = more conviction.</p>
 *
 * <p>{@code supportStrike} / {@code resistanceStrike} are the chain strikes with the
 * largest OI build on each side (used by downstream MultiStrikePicker as targets).</p>
 */
public record OiSignal(
        int direction,
        String label,
        double strength,
        int supportStrike,
        int resistanceStrike,
        // ── Telemetry for end-of-day validation ──
        long sumCeChg,
        long sumPeChg,
        long maxCeBuild,
        long maxPeBuild
) {
    public static final OiSignal EMPTY = new OiSignal(0, OiPattern.INSUFFICIENT.name(),
            0, 0, 0, 0, 0, 0, 0);

    public boolean isActionable() { return direction != 0; }
    public boolean isSqueeze() {
        return label.equals(OiPattern.WRITER_SQUEEZE.name()) ||
               label.equals(OiPattern.PE_SQUEEZE.name());
    }
}
