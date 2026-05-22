package com.algo.trade.strategy.oishifttrap;

import java.math.BigDecimal;

/**
 * Per-scan snapshot for OI Shift Trap tuning — does not affect trading logic.
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
        int signalScore
) {
    public static OiShiftTrapDiagnostics blocked(String underlying, String outcome, String blocker, BigDecimal spot) {
        return new OiShiftTrapDiagnostics(
                underlying, spot != null ? spot : BigDecimal.ZERO, 0, "", 0,
                outcome, blocker, 0,
                CandidateSnapshot.empty(), CandidateSnapshot.empty(),
                false, "", BigDecimal.ZERO, 0);
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
