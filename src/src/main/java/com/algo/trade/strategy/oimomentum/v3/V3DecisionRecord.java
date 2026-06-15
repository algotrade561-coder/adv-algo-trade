package com.algo.trade.strategy.oimomentum.v3;

import com.algo.trade.domain.IndexType;

import java.time.Instant;
import java.util.Set;

/**
 * V3 OPERATOR — Full per-decision telemetry record.
 *
 * <p>Captures every input + intermediate + output value for end-of-day validation.
 * Serialized to a CSV by {@link V3DecisionRecorder}.</p>
 *
 * <p>One record per evaluation attempt — whether it resulted in ENTER, SKIP_GATE, or
 * SKIP_HARD (anti-pyramid / OTM cutoff / halted etc.).</p>
 */
public record V3DecisionRecord(
        Instant timestamp,
        IndexType indexType,
        // ── Inputs ──
        double spot,
        int atm,
        double vix,
        int momentumDir,
        String momentumType,
        double momentumMagnitudePct,
        // ── Context ──
        Set<Regime> regimes,
        TimeOfDayMode timeMode,
        double dailyAtrPct,
        double sessionOpen,
        double previousClose,
        double ivPercentile,
        double vixSlope15Min,
        int spotVsVwap,
        int gammaWallAbove,
        int gammaWallBelow,
        int maxPainStrike,
        double pcrSlope5Min,
        // ── OI Signal ──
        OiSignal oiSignal,
        // ── Gates ──
        boolean g1Pass, String g1Reason,
        boolean g2Pass, String g2Reason,
        boolean g3Pass, String g3Reason,
        boolean g4Pass, String g4Reason,
        int gatesPassed,
        int gatesRequired,
        // ── Picker ──
        int chosenStrike,
        String chosenStrikeRole,
        double chosenDelta,
        long chosenOi,
        double chosenIv,
        double pickerScore,
        // ── Sizer ──
        int baseLots,
        int finalLots,
        double conviction,
        String sizingBreakdown,
        // ── Outcome ──
        String verdict,         // ENTER | SKIP_GATE_<n>OF4 | SKIP_HARD_<reason> | SKIP_PATTERN
        String verdictReason,
        // ── LIQUIDITY_REROUTE telemetry (review nit #8) ──
        int rerouteAttempts,    // how many lower-ranked candidates were tried after top failed G4
        boolean rerouteSucceeded // true if the final chosen candidate cleared G4 via reroute
) {
    /** CSV header for {@link V3DecisionRecorder}. */
    public static final String CSV_HEADER = String.join(",",
            "timestamp","indexType","spot","atm","vix",
            "momentumDir","momentumType","momentumMagPct",
            "regimes","timeMode","dailyAtrPct","sessionOpen","previousClose",
            "ivPercentile","vixSlope15Min","spotVsVwap",
            "gammaWallAbove","gammaWallBelow","maxPainStrike","pcrSlope5Min",
            "oiPattern","oiDirection","oiStrength","sumCeChg","sumPeChg",
            "oiSupportStrike","oiResistanceStrike",
            "g1Pass","g1Reason","g2Pass","g2Reason",
            "g3Pass","g3Reason","g4Pass","g4Reason",
            "gatesPassed","gatesRequired",
            "chosenStrike","chosenRole","chosenDelta","chosenOi","chosenIv","pickerScore",
            "baseLots","finalLots","conviction","sizingBreakdown",
            "verdict","verdictReason",
            "rerouteAttempts","rerouteSucceeded");

    /** Render as a CSV row. */
    public String toCsv() {
        return String.join(",",
                timestamp.toString(),
                indexType.name(),
                String.format("%.2f", spot),
                String.valueOf(atm),
                String.format("%.2f", vix),
                String.valueOf(momentumDir),
                csvSafe(momentumType),
                String.format("%.3f", momentumMagnitudePct),
                regimes.stream().map(Enum::name).reduce((a, b) -> a + "|" + b).orElse(""),
                timeMode.name(),
                String.format("%.3f", dailyAtrPct),
                String.format("%.2f", sessionOpen),
                String.format("%.2f", previousClose),
                String.format("%.1f", ivPercentile),
                String.format("%.4f", vixSlope15Min),
                String.valueOf(spotVsVwap),
                String.valueOf(gammaWallAbove),
                String.valueOf(gammaWallBelow),
                String.valueOf(maxPainStrike),
                String.format("%.4f", pcrSlope5Min),
                oiSignal.label(),
                String.valueOf(oiSignal.direction()),
                String.format("%.2f", oiSignal.strength()),
                String.valueOf(oiSignal.sumCeChg()),
                String.valueOf(oiSignal.sumPeChg()),
                String.valueOf(oiSignal.supportStrike()),
                String.valueOf(oiSignal.resistanceStrike()),
                String.valueOf(g1Pass),
                csvSafe(g1Reason),
                String.valueOf(g2Pass),
                csvSafe(g2Reason),
                String.valueOf(g3Pass),
                csvSafe(g3Reason),
                String.valueOf(g4Pass),
                csvSafe(g4Reason),
                String.valueOf(gatesPassed),
                String.valueOf(gatesRequired),
                String.valueOf(chosenStrike),
                csvSafe(chosenStrikeRole),
                String.format("%.3f", chosenDelta),
                String.valueOf(chosenOi),
                String.format("%.2f", chosenIv),
                String.format("%.1f", pickerScore),
                String.valueOf(baseLots),
                String.valueOf(finalLots),
                String.format("%.3f", conviction),
                csvSafe(sizingBreakdown),
                csvSafe(verdict),
                csvSafe(verdictReason),
                String.valueOf(rerouteAttempts),
                String.valueOf(rerouteSucceeded));
    }

    private static String csvSafe(String v) {
        if (v == null) return "";
        return v.replace(',', ';').replace('\n', ' ');
    }
}
