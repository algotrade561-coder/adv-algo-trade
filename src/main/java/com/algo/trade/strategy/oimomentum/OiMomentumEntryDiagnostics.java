package com.algo.trade.strategy.oimomentum;

import com.algo.trade.domain.IndexType;

/**
 * Snapshot of OI Momentum evaluation at entry or reject time — tuning only, no trading effect.
 */
public record OiMomentumEntryDiagnostics(
        IndexType indexType,
        String entryCase,
        int momentumDir,
        String momentumType,
        double momentumMagnitudePct,
        int oiDir,
        int pcrDir,
        double pcr,
        long ceOiChange,
        long peOiChange,
        boolean oiAvailable,
        double spot,
        int atm,
        double spot30mHigh,
        double spot30mLow,
        double breakoutDistancePct,
        String spikeEpisodeId,
        double vix,
        long daysToExpiry,
        boolean expiryDay,
        boolean paperTrading,
        String signalReason,
        boolean oiAdvanced,
        double rangePct30m,
        String blockDetail,
        double atmCeLast,
        double atmPeLast,
        /** Time-of-day mode at evaluation (OPENING_DRIVE, MIDDAY_DISCIPLINE, …). */
        String timeOfDayMode,
        /** Normalized matrix case label (CASE1, CASE2, SPIKE, CASE0, …). */
        String matrixCase,
        /** Detection path: LEGACY, SPIKE, CASE0, RANGE_FADE, V3, … */
        String entryPath,
        int operatorScore,
        int biasScore
) {
    static OiMomentumEntryDiagnostics forSpike(IndexType indexType, TickMomentumDetector.MomentumSignal spike,
                                               double pcr, int pcrDir, long ceOi, long peOi, boolean oiAvailable,
                                               int oiDir, boolean oiAdvanced, double vix, long dte, boolean expiryDay,
                                               boolean paper) {
        double high = 0;
        double low = 0;
        double dist = 0;
        return new OiMomentumEntryDiagnostics(
                indexType, "SPIKE:" + spike.type(), spike.direction(), spike.type(), spike.magnitude(),
                oiDir, pcrDir, pcr, ceOi, peOi, oiAvailable,
                spike.spotPrice(), indexType.roundToATM(spike.spotPrice()),
                high, low, dist,
                spikeEpisodeId(indexType, spike),
                vix, dte, expiryDay, paper,
                "SPIKE:" + spike.type(),
                oiAdvanced, 0, "", 0, 0,
                "", "SPIKE", "SPIKE", 0, 0);
    }

    static String spikeEpisodeId(IndexType indexType, TickMomentumDetector.MomentumSignal spike) {
        long bucket = spike.spotPrice() > 0
                ? Math.round(spike.spotPrice() / indexType.strikeInterval())
                : 0;
        return indexType.name() + "-SPIKE-" + bucket + "-" + spike.direction();
    }

    static String parseEntryCase(String reason) {
        if (reason == null) {
            return "";
        }
        int idx = reason.indexOf("case=");
        if (idx < 0) {
            if (reason.startsWith("SPIKE:")) {
                return reason.contains(":") ? reason.substring(0, reason.indexOf(' ', 6) > 0
                        ? reason.indexOf(' ', 6) : reason.length()).trim() : reason;
            }
            return "";
        }
        String tail = reason.substring(idx + 5);
        int end = tail.indexOf(' ');
        return end < 0 ? tail.trim() : tail.substring(0, end).trim();
    }

    /** Copy with updated operator/bias scores for reject-row tuning. */
    public OiMomentumEntryDiagnostics withScores(int operatorScore, int biasScore) {
        return new OiMomentumEntryDiagnostics(
                indexType, entryCase, momentumDir, momentumType, momentumMagnitudePct,
                oiDir, pcrDir, pcr, ceOiChange, peOiChange, oiAvailable,
                spot, atm, spot30mHigh, spot30mLow, breakoutDistancePct, spikeEpisodeId,
                vix, daysToExpiry, expiryDay, paperTrading, signalReason, oiAdvanced,
                rangePct30m, blockDetail, atmCeLast, atmPeLast,
                timeOfDayMode, matrixCase, entryPath, operatorScore, biasScore);
    }
}
