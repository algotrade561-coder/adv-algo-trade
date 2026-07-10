package com.algo.trade.config.usersettings;

/**
 * Risk profile — the single selector that expands into the full bundle of risk / selectivity /
 * concurrency parameters, so the user no longer tunes ~24 individual fields.
 *
 * <p>The bundle values are the AUTHORITATIVE per-user caps (user-provided table). Each is a HARD CEILING
 * the system must never exceed at any moment (open positions included). BALANCED is NO LONGER seeded from
 * {@code GlobalConfig} (that coupling caused the profile caps to conflict with global) — every profile,
 * including BALANCED, uses its own bundle. CONSERVATIVE tightens, AGGRESSIVE loosens. {@link #CUSTOM}
 * carries no bundle — the resolver then falls back to the Layer-1 global config values (power-user path).
 */
public enum RiskProfile {

    // AUTHORITATIVE per-user caps (user table, 2026-07-01). Each value is a HARD CEILING the system must
    // never exceed at any time (open positions included). NOT seeded from GlobalConfig any more — the
    // profile is the single source of truth and must never conflict with global.
    CONSERVATIVE(new Bundle(
            5.0,   // maxRiskPerTradePercent
            3.0,   // maxDailyLossPercent
            10,    // maxTradesPerDay
            2,     // maxConsecutiveLosses
            1,     // maxOpenTrades
            1,     // maxLotsPerTrade
            1,     // maxOpenPositionsPerStrategy
            65.0,  // minSignalScorePercent
            50,    // minEnvironmentScore
            5,     // cooldownMinutes
            90,    // directionFlipCooldownMinutes
            1,     // maxEntriesPerScan
            1      // maxEntriesPerScanPerUnderlying
    )),

    //                     risk%  daily% tr/day cLoss open lots perStrat sig  env cool flip ePS ePSU
    BALANCED(new Bundle(
            8.0,   5.0,    30,    3,    2,   2,   1,      55.0, 40,  0,  45,  1,  1
    )),

    // AGGRESSIVE: optimized for profit — tighter risk per trade, manageable lots, more attempts allowed.
    AGGRESSIVE(new Bundle(
            10.0,  6.0,    50,    6,    2,   2,   1,      50.0,  0,  0,  15,  2,  1
    )),

    /** No bundle — resolver uses the Layer-1 global config values for every profile-driven number. */
    CUSTOM(null);

    private final Bundle bundle;

    RiskProfile(Bundle bundle) {
        this.bundle = bundle;
    }

    /** The parameter bundle for this profile, or {@code null} for {@link #CUSTOM}. */
    public Bundle bundle() {
        return bundle;
    }

    public boolean hasBundle() {
        return bundle != null;
    }

    /** Tolerant parse — unknown / null ⇒ BALANCED (safe default). */
    public static RiskProfile fromString(String s) {
        if (s == null || s.isBlank()) return BALANCED;
        try {
            return RiskProfile.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return BALANCED;
        }
    }

    /**
     * Immutable parameter bundle expanded from a profile. Percent fields are doubles for the bundle;
     * the resolver converts to {@code BigDecimal} where the consuming config expects it.
     */
    public record Bundle(
            double maxRiskPerTradePercent,
            double maxDailyLossPercent,
            int maxTradesPerDay,
            int maxConsecutiveLosses,
            int maxOpenTrades,
            int maxLotsPerTrade,
            int maxOpenPositionsPerStrategy,
            double minSignalScorePercent,
            int minEnvironmentScore,
            int cooldownMinutes,
            int directionFlipCooldownMinutes,
            int maxEntriesPerScan,
            int maxEntriesPerScanPerUnderlying
    ) {}
}
