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
        int biasFloorUsed,
        /** FAST-OI (2026-07-01) — whether the fast-OI path was live at this evaluation (config flag). */
        boolean fastOiEnabled,
        /** FAST-OI — the OI-change window (sec) in effect: 60 when fast, ~300 (oldest-sample) legacy. */
        int oiWindowSec,
        /** FAST-OI — age (sec) of the operator signal at decision = its freshness/lead-time. -1 = none. */
        long operatorSignalAgeSec,
        /** MTF (2026-07-01) — combined day+week higher-timeframe directional lean (+1 bull / -1 bear / 0). */
        int mtfBias,
        /** MTF — regime at decision: TRENDING | RANGING | VOLATILE | NEUTRAL. */
        String mtfRegime,
        /** MTF — did the entry direction align with the HTF lean? +1 aligned, -1 counter-trend, 0 neutral. */
        int mtfAligned
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
             0.0, 0, 0.0, false, 0,
             false, 0, -1L,
             0, "NEUTRAL", 0);
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
        // Include date for uniqueness across sessions (same strike+dir can repeat on different days)
        String date = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).toString();
        return indexType.name() + "-SPIKE-" + bucket + "-" + spike.direction() + "-" + date;
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
                biasFloorRelaxed, biasFloorUsed,
                fastOiEnabled, oiWindowSec, operatorSignalAgeSec,
                mtfBias, mtfRegime, mtfAligned);
    }

    public OiMomentumEntryDiagnostics withSustainedDrift(double driftPct, int windowMin) {
        return new OiMomentumEntryDiagnostics(
                indexType, entryCase, momentumDir, momentumType, momentumMagnitudePct,
                oiDir, pcrDir, pcr, ceOiChange, peOiChange, oiAvailable,
                spot, atm, spot30mHigh, spot30mLow, breakoutDistancePct, spikeEpisodeId,
                vix, daysToExpiry, expiryDay, paperTrading, signalReason, oiAdvanced,
                rangePct30m, blockDetail, atmCeLast, atmPeLast,
                timeOfDayMode, matrixCase, entryPath, operatorScore, biasScore,
                driftPct, windowMin, pcrSlope5m, biasFloorRelaxed, biasFloorUsed,
                fastOiEnabled, oiWindowSec, operatorSignalAgeSec,
                mtfBias, mtfRegime, mtfAligned);
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
                biasFloorRelaxed, biasFloorUsed,
                fastOiEnabled, oiWindowSec, operatorSignalAgeSec,
                mtfBias, mtfRegime, mtfAligned);
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
                relaxed, floorUsed,
                fastOiEnabled, oiWindowSec, operatorSignalAgeSec,
                mtfBias, mtfRegime, mtfAligned);
    }

    /**
     * FAST-OI (2026-07-01) — overlay the fast-OI capture context for tuning/research: whether the
     * fast path was live, the OI-change window in effect (sec), and the operator signal's age (its
     * freshness/lead-time). Lets the tuning loop A/B fast-OI vs legacy and correlate operator
     * lead-time with the exit outcome (maePct/mfePct/realizedPnlPct join by correlationKey).
     */
    public OiMomentumEntryDiagnostics withFastOi(boolean enabled, int windowSec, long operatorAgeSec) {
        return new OiMomentumEntryDiagnostics(
                indexType, entryCase, momentumDir, momentumType, momentumMagnitudePct,
                oiDir, pcrDir, pcr, ceOiChange, peOiChange, oiAvailable,
                spot, atm, spot30mHigh, spot30mLow, breakoutDistancePct, spikeEpisodeId,
                vix, daysToExpiry, expiryDay, paperTrading, signalReason, oiAdvanced,
                rangePct30m, blockDetail, atmCeLast, atmPeLast,
                timeOfDayMode, matrixCase, entryPath, operatorScore, biasScore,
                sustainedDriftPct, sustainedDriftWindowMin, pcrSlope5m,
                biasFloorRelaxed, biasFloorUsed,
                enabled, windowSec, operatorAgeSec,
                mtfBias, mtfRegime, mtfAligned);
    }

    /** MTF (2026-07-01) — overlay the multi-timeframe context for tuning/research: the day+week directional
     *  lean, the regime, and whether this entry aligned with the HTF trend. Lets the loop validate whether
     *  trend-aligned entries have better win% / less give-back (join to exit maePct/mfePct/realizedPnlPct). */
    public OiMomentumEntryDiagnostics withMtf(int bias, String regime, int aligned) {
        return new OiMomentumEntryDiagnostics(
                indexType, entryCase, momentumDir, momentumType, momentumMagnitudePct,
                oiDir, pcrDir, pcr, ceOiChange, peOiChange, oiAvailable,
                spot, atm, spot30mHigh, spot30mLow, breakoutDistancePct, spikeEpisodeId,
                vix, daysToExpiry, expiryDay, paperTrading, signalReason, oiAdvanced,
                rangePct30m, blockDetail, atmCeLast, atmPeLast,
                timeOfDayMode, matrixCase, entryPath, operatorScore, biasScore,
                sustainedDriftPct, sustainedDriftWindowMin, pcrSlope5m,
                biasFloorRelaxed, biasFloorUsed,
                fastOiEnabled, oiWindowSec, operatorSignalAgeSec,
                bias, regime != null ? regime : "NEUTRAL", aligned);
    }
}
