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
        String signalReason
) {
    static OiMomentumEntryDiagnostics forSpike(IndexType indexType, TickMomentumDetector.MomentumSignal spike,
                                               double pcr, int pcrDir, long ceOi, long peOi, boolean oiAvailable,
                                               int oiDir, double vix, long dte, boolean expiryDay, boolean paper) {
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
                "SPIKE:" + spike.type());
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
}
