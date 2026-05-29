package com.algo.trade.strategy.oimomentum.v3;

/**
 * V3 OPERATOR — Named OI confluence patterns.
 *
 * <p>Each pattern carries an implicit direction (CE-side or PE-side) and a quality rank
 * used by ConvictionSizer to size positions.</p>
 */
public enum OiPattern {

    /**
     * Bullish — heavy CE OI unwinding ({@code sumCeΔ < −significance}) combined with
     * PE OI growing. Call writers are covering forcefully. Strongest bullish signal.
     */
    WRITER_SQUEEZE(1.00, +1),

    /**
     * Bearish — heavy PE OI unwinding combined with CE OI growing. Put writers cashing
     * out as spot falls. Highest-quality bearish pattern.
     */
    PE_SQUEEZE(1.00, -1),

    /**
     * Bullish — PE OI building dominantly (1.5× CE side) AND max PE build at-or-below
     * spot. Writers defending a support level the price will gravitate toward.
     */
    PE_SUPPORT(0.85, +1),

    /**
     * Bearish — CE OI building dominantly AND max CE build at-or-above spot. Writers
     * stacking resistance.
     */
    CE_RESISTANCE(0.85, -1),

    /**
     * Either direction — flagged when OI_SHIFT_TRAP scan reports a high-imbalance
     * trapped strike AND spot is moving toward it. Direction set externally.
     */
    TRAP_APPROACH(0.70, 0),

    /**
     * Reversal — the dominant OI-writing side has flipped vs a fresh prior baseline.
     * Direction set externally (sign of the flip).
     */
    WRITER_REVERSAL(0.65, 0),

    /** OI direction is in conflict (both sides building significantly). Skip. */
    CONFLICTING(0.0, 0),

    /** No clear directional signal; do not enter. */
    INSUFFICIENT(0.0, 0);

    private final double qualityScore;
    private final int impliedDirection;

    OiPattern(double qualityScore, int impliedDirection) {
        this.qualityScore = qualityScore;
        this.impliedDirection = impliedDirection;
    }

    public double qualityScore() { return qualityScore; }
    public int impliedDirection() { return impliedDirection; }
    public boolean isActionable() { return qualityScore > 0; }
}
