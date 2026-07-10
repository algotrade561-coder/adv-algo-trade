package com.algo.trade.strategy.oimomentum.v3;

import java.time.LocalTime;

/**
 * V3 OPERATOR — Time-of-day mode classifier.
 *
 * <p>Operators arrive in three waves: opening drive, pre-noon close-outs, and last-hour
 * positioning. Retail dominates midday. Different windows demand different aggression.</p>
 *
 * <p>Each mode carries a {@code requiredGates} value (number of operator gates that must
 * pass to permit an entry) and a {@code sizeMultiplier} (cap applied to position size).</p>
 */
public enum TimeOfDayMode {

    /** 09:15–09:20 IST — auction noise. No entries. */
    DEAD_OPEN(4, 0.0),

    /** 09:20–10:30 IST — institutional opening drive. Strongest OI flow signals. */
    OPENING_DRIVE(3, 1.0),

    /** 10:30–11:30 IST — trend-continuation phase. */
    TREND_FOLLOW(3, 0.85),

    /** 11:30–13:30 IST — retail-dominant chop. Requires 3-of-4 gates (was 4-of-4, too strict). */
    MIDDAY_DISCIPLINE(3, 0.7),

    /** 13:30–14:45 IST — afternoon institutional positioning + trap setups. */
    AFTERNOON_POSITION(3, 0.9),

    /** 14:45–15:10 IST — last-hour cautious. OTM forbidden on expiry. */
    LAST_HOUR(3, 0.5),

    /** 15:10–15:20 IST — only WRITER_SQUEEZE patterns on ATM strikes. */
    EOD_SQUEEZE_ONLY(4, 0.5),

    /** 15:20–15:30 IST + before 09:15 — close-only zone. No new entries. */
    DEAD_CLOSE(4, 0.0);

    private final int requiredGates;
    private final double sizeMultiplier;

    TimeOfDayMode(int requiredGates, double sizeMultiplier) {
        this.requiredGates = requiredGates;
        this.sizeMultiplier = sizeMultiplier;
    }

    public int requiredGates() { return requiredGates; }
    public double sizeMultiplier() { return sizeMultiplier; }
    public boolean allowsEntry() { return sizeMultiplier > 0; }

    /** Classify the current IST time into a mode. */
    public static TimeOfDayMode classify(LocalTime now) {
        if (now.isBefore(LocalTime.of(9, 15))) return DEAD_CLOSE;
        if (now.isBefore(LocalTime.of(9, 20))) return DEAD_OPEN;
        if (now.isBefore(LocalTime.of(10, 30))) return OPENING_DRIVE;
        if (now.isBefore(LocalTime.of(11, 30))) return TREND_FOLLOW;
        if (now.isBefore(LocalTime.of(13, 30))) return MIDDAY_DISCIPLINE;
        if (now.isBefore(LocalTime.of(14, 45))) return AFTERNOON_POSITION;
        if (now.isBefore(LocalTime.of(15, 10))) return LAST_HOUR;
        if (now.isBefore(LocalTime.of(15, 20))) return EOD_SQUEEZE_ONLY;
        return DEAD_CLOSE;
    }
}
