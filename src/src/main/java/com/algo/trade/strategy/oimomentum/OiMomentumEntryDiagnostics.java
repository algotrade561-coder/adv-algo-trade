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
        /** Detection path: LEGACY, SPIKE, CASE0, RANGE_FADE, V3, SUSTAINED_DRIFT, … */
        String entryPath,
        int operatorScore,
        int biasScore,
        /** D2 SUSTAINED_DRIFT — signed 60-min spot drift as % of spot. 0 when not measured. */
        double sustainedDriftPct,
        /** D2 SUSTAINED_DRIFT — actual window measured in minutes (≤ 60). 0 when not measured. */
        int sustainedDriftWindowMin,
        /** T2 PCR slope per 5 minutes (from MarketContextService). 0 when unavailable. */
        double pcrSlope5m,
        /** T3 — true when bias floor was conditionally lowered (coil break + slope agree). */
        boolean biasFloorRelaxed,
        /** T3 — actual bias floor used at this evaluation (e.g. 65 default, 55 relaxed). */
        int biasFloorUsed
) {
    /**
     * Overload that keeps every legacy 32-arg call-site working. Defaults the
     * 5 trailing fields added on 2 Jun 2026 to neutral values so existing
     * builders (forSpike, V3 pipeline, etc.) compile unchanged. Use the
     * {@code with...()} helpers below to overlay real values once the new
     * detectors have computed them.
     */
    public OiMomentumEntryDiagnostics(
            IndexType indexType, String entryCase, int momentumDir, String momentumType,
            double momentumMagnitudePct, int oiDir, int pcrDir, double pcr,
            long ceOiChange, long peOiChange, boolean oiAvailable, double spot, int atm,
            double spot30mHigh, double spot30mLow, double breakoutDistancePct,
            String spikeEpisodeId, double vix, long daysToExpiry, boolean expiryDay,
            boolean paperTrading, String signalReason, boolean oiAdvanced, double rangePct30m,
            String blockDetail, double atmCeLast, double atmPeLast, String timeOfDayMode,
            String matrixCase, String entryPath, int operatorScore, int biasScore) {
        this(indexType, entryCase, momentumDir, momentumType, momentumMagnitudePct,
             oiDir, pcrDir, pcr, ceOiChange, peOiChange, oiAvailable, spot, atm,
             spot30mHigh, spot30mLow, breakoutDistancePct, spikeEpisodeId, vix,
             daysToExpiry, expiryDay, paperTrading, signalReason, oiAdvanced,
             rangePct30m, blockDetail, atmCeLast, atmPeLast, timeOfDayMode,
             matrixCase, entryPath, operatorScore, biasScore,
             0.0, 0, 0.0, false, 0);
    }
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
        if (reason == null) return "";
        int idx = reason.indexOf("case=");
        if (idx < 0) {
            if (reason.startsWith("SPIKE:")) {
                int sp = reason.indexOf(' ', 6);
                return sp > 0 ? reason.substring(0, sp).trim() : reason.trim();
            }
            return "";
        }
        String tail = reason.substring(idx + 5);
        int end = tail.indexOf(' ');
        return end < 0 ? tail.trim() : tail.substring(0, end).trim();
    }

    public OiMomentumEntryDiagnostics withScores(int operatorScore, int biasScore) {
        return new OiMomentumEntryDiagnostics(
                indexType, entryCase, momentumDir, momentumType, momentumMagnitudePct,
                oiDir, pcrDir, pcr, ceOiChange, peOiChange, oiAvailable,
                spot, atm, spot30mHigh, spot30mLow, breakoutDistancePct, spikeEpisodeId,
                vix, daysToExpiry, expiryDay, paperTrading, signalReason, oiAdvanced,
                rangePct30m, blockDetail, atmCeLast, atmPeLast,
                timeOfDayMode, matrixCase, entryPath, operatorScore, biasScore,
                sustainedDriftPct, sustainedDriftWindowMin, pcrSlope5m,
                biasFloorRelaxed, biasFloorUsed);
    }

    public OiMomentumEntryDiagnostics withSustainedDrift(double driftPct, int windowMin) {
        return new OiMomentumEntryDiagnostics(
                indexType, entryCase, momentumDir, momentumType, momentumMagnitudePct,
                oiDir, pcrDir, pcr, ceOiChange, peOiChange, oiAvailable,
                spot, atm, spot30mHigh, spot30mLow, breakoutDistancePct, spikeEpisodeId,
                vix, daysToExpiry, expiryDay, paperTrading, signalReason, oiAdvanced,
                rangePct30m, blockDetail, atmCeLast, atmPeLast,
                timeOfDayMode, matrixCase, entryPath, operatorScore, biasScore,
                driftPct, windowMin, pcrSlope5m, biasFloorRelaxed, biasFloorUsed);
    }

    public OiMomentumEntryDiagnostics withPcrSlope(double slope) {
        return new OiMomentumEntryDiagnostics(
                indexType, entryCase, momentumDir, momentumType, momentumMagnitudePct,
                oiDir, pcrDir, pcr, ceOiChange, peOiChange, oiAvailable,
                spot, atm, spot30mHigh, spot30mLow, breakoutDistancePct, spikeEpisodeId,
                vix, daysToExpiry, expiryDay, paperTrading, signalReason, oiAdvanced,
                rangePct30m, blockDetail, atmCeLast, atmPeLast,
                timeOfDayMode, matrixCase, entryPath, operatorScore, biasScore,
                sustainedDriftPct, sustainedDriftWindowMin, slope,
                biasFloorRelaxed, biasFloorUsed);
    }

    public OiMomentumEntryDiagnostics withBiasFloor(boolean relaxed, int floorUsed) {
        return new OiMomentumEntryDiagnostics(
                indexType, entryCase, momentumDir, momentumType, momentumMagnitudePct,
                oiDir, pcrDir, pcr, ceOiChange, peOiChange, oiAvailable,
                spot, atm, spot30mHigh, spot30mLow, breakoutDistancePct, spikeEpisodeId,
                vix, daysToExpiry, expiryDay, paperTrading, signalReason, oiAdvanced,
                rangePct30m, blockDetail, atmCeLast, atmPeLast,
                timeOfDayMode, matrixCase, entryPath, operatorScore, biasScore,
                sustainedDriftPct, sustainedDriftWindowMin, pcrSlope5m,
                relaxed, floorUsed);
    }
}
