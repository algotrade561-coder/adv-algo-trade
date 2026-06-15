package com.algo.trade.strategy.oishifttrap;

import java.math.BigDecimal;

/**
 * Per-scan snapshot for OI Shift Trap tuning — does not affect trading logic.
 *
 * <p><b>2026-06-01 Phase 4 (features 13, 14)</b>: extended with {@code daysToExpiry} and
 * {@code relativeVolumeRatio}. A backward-compatible 14-arg constructor is provided so
 * existing callers compile unchanged with defaults (0 / 0.0).
 */
public record OiShiftTrapDiagnostics(
        String underlying,
        BigDecimal spot,
        int trendDirection,
        String volumeMode,
        long latestVolume,
        String outcome,
        String primaryBlocker,
        int chainLevels,
        CandidateSnapshot bestCe,
        CandidateSnapshot bestPe,
        boolean signalGenerated,
        String trapSide,
        BigDecimal signalStrike,
        int signalScore,
        int daysToExpiry,
        double relativeVolumeRatio
) {
    /** Backward-compat constructor — pre-Phase 4 callers default DTE=0 and relVol=0.0. */
    public OiShiftTrapDiagnostics(
            String underlying,
            BigDecimal spot,
            int trendDirection,
            String volumeMode,
            long latestVolume,
            String outcome,
            String primaryBlocker,
            int chainLevels,
            CandidateSnapshot bestCe,
            CandidateSnapshot bestPe,
            boolean signalGenerated,
            String trapSide,
            BigDecimal signalStrike,
            int signalScore) {
        this(underlying, spot, trendDirection, volumeMode, latestVolume,
                outcome, primaryBlocker, chainLevels, bestCe, bestPe,
                signalGenerated, trapSide, signalStrike, signalScore,
                0, 0.0);
    }

    public static OiShiftTrapDiagnostics blocked(String underlying, String outcome, String blocker, BigDecimal spot) {
        return new OiShiftTrapDiagnostics(
                underlying, spot != null ? spot : BigDecimal.ZERO, 0, "", 0,
                outcome, blocker, 0,
                CandidateSnapshot.empty(), CandidateSnapshot.empty(),
                false, "", BigDecimal.ZERO, 0,
                0, 0.0);
    }

    public record CandidateSnapshot(
            BigDecimal strike,
            long trappedOi,
            long oppositeOi,
            long oiChange,
            double imbalance,
            double proximityPct,
            int score,
            String failedGate
    ) {
        public static CandidateSnapshot empty() {
            return new CandidateSnapshot(BigDecimal.ZERO, 0, 0, 0, 0, 0, 0, "");
        }

        public boolean present() {
            return strike != null && strike.signum() > 0;
        }
    }
}
