package com.algo.trade.strategy.oishifttrap;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Per-scan snapshot for OI Shift Trap tuning. Does not affect trading logic.
 *
 * <p>The optional {@link LadderInfo} component carries limit-ladder state
 * when the ladder entry layer is active. It is {@code null} for any
 * diagnostic emitted by the legacy gate matrix in its raw form — preserving
 * wire and test compatibility with everything that consumes the 14-arg
 * shape.</p>
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
        LadderInfo ladderInfo
) {

    /** Backward-compatible 14-arg constructor — delegates with {@code ladderInfo=null}. */
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
                signalGenerated, trapSide, signalStrike, signalScore, null);
    }

    public static OiShiftTrapDiagnostics blocked(String underlying, String outcome, String blocker, BigDecimal spot) {
        return new OiShiftTrapDiagnostics(
                underlying, spot != null ? spot : BigDecimal.ZERO, 0, "", 0,
                outcome, blocker, 0,
                CandidateSnapshot.empty(), CandidateSnapshot.empty(),
                false, "", BigDecimal.ZERO, 0, null);
    }

    /** Return a copy with {@code ladderInfo} replaced. */
    public OiShiftTrapDiagnostics withLadderInfo(LadderInfo info) {
        return new OiShiftTrapDiagnostics(
                underlying, spot, trendDirection, volumeMode, latestVolume,
                outcome, primaryBlocker, chainLevels, bestCe, bestPe,
                signalGenerated, trapSide, signalStrike, signalScore, info);
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

    /**
     * Limit-ladder state snapshot. Emitted both when the ladder arms (event
     * = {@code ARMED}) and when a tier resolves (event = {@code TIER_FILLED}
     * / {@code CANCELLED}). The tuning pipeline keys off this record to
     * compute the ladder-effectiveness analyzer section.
     */
    public record LadderInfo(
            String event,            // ARMED | TIER_FILLED | CANCELLED
            String mode,             // OFF | SHADOW | LIVE — config at arm time
            Instant armedAt,
            double armLtp,
            int armOpScore,
            int armOpDirection,
            double tier1Price,
            double tier2Price,
            double tier3Price,
            int tier1FilledQty,
            int tier2FilledQty,
            int tier3FilledQty,
            double effectiveFillPrice,
            double discountVsArm,
            String cancelReason,     // null for non-cancel events
            int currentOpScore
    ) {
        public static LadderInfo armed(Instant armedAt, double armLtp, int armOpScore, int armDir,
                                       String mode, double t1, double t2, double t3) {
            return new LadderInfo("ARMED", mode, armedAt, armLtp, armOpScore, armDir,
                    t1, t2, t3, 0, 0, 0, 0.0, 0.0, null, armOpScore);
        }
    }
}
