package com.algo.trade.strategy.oimomentum.v3;

import java.util.Set;

/**
 * V3 OPERATOR — Market regime classification.
 *
 * <p>Multiple regimes can coexist (e.g. {@code EXPIRY_DAY} + {@code LOW_VOL}). The strategy
 * merges regime-specific thresholds in priority order so the most-specific overlay wins.</p>
 */
public enum Regime {

    /** Daily ATR < 0.6%. Smaller scalps, wider stops (12% × 1.5). */
    LOW_VOL,

    /** Daily ATR 0.6%–1.2%. Default profile. */
    NORMAL,

    /** Daily ATR > 1.2%. Fewer entries, tighter stops. */
    HIGH_VOL,

    /** Today is the resolved expiry day for this index. Gamma rules apply. */
    EXPIRY_DAY,

    /** Gap-open > 0.5% from previous close. Opening drive often decides the day. */
    GAP_OPEN;

    /** Convenience: pick the dominant volatility regime out of a set. */
    public static Regime dominantVol(Set<Regime> regimes) {
        if (regimes.contains(HIGH_VOL)) return HIGH_VOL;
        if (regimes.contains(LOW_VOL)) return LOW_VOL;
        return NORMAL;
    }
}
