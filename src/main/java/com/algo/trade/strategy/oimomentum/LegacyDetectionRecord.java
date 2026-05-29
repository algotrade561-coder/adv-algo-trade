package com.algo.trade.strategy.oimomentum;

import com.algo.trade.domain.IndexType;
import com.algo.trade.strategy.oimomentum.v3.TimeOfDayMode;

import java.time.Instant;

/**
 * Per-evaluation row captured by the legacy CASE 1–5 + CASE 0 detection path.
 *
 * <p>Every momentum-detection cycle that runs (V3 off, or V3 shadow) writes one row
 * to {@code data/oi-decisions/YYYY-MM-DD.csv}. The schema gives end-of-day analysis
 * enough signal to:</p>
 * <ul>
 *   <li>compute the same hit-rate / forward-return tables the replay produced;</li>
 *   <li>compare baseline (CASE 1–5) firing decisions against CASE 0 fires on the
 *       same timestamp — verify the additive-volume claim live;</li>
 *   <li>diagnose why the strategy stayed silent during a known intraday move (the
 *       {@code finalDecision} + {@code skipReason} columns carry every gate that
 *       contributed to a skip).</li>
 * </ul>
 *
 * <p>Columns mirror the replay CSV ({@code replay_all_signals.csv}) so production
 * and replay outputs can be diff-compared directly.</p>
 */
public record LegacyDetectionRecord(
        Instant timestamp,
        IndexType index,
        double spot,
        int atmStrike,
        double vix,
        TimeOfDayMode mode,

        // Operator framework signal at this moment
        int operatorScore,
        int operatorDirection,

        // 20-min spot coil (CASE 0 input)
        double range20mPct,

        // PCR + slope (CASE 0 input + baseline signal)
        double pcr,
        double pcrSlope5Min,
        int pcrDirection,

        // OI-tick window (baseline input)
        long ceOiChange,
        long peOiChange,
        boolean oiAvailable,
        int oiDirection,

        // Momentum signal (baseline input)
        int momentumDirection,
        String momentumType,
        double momentumMagnitudePct,

        // Bias engine + matrix case label
        int biasScore,
        String matrixCase,           // CASE1..5, or CASE0_SHADOW / CASE0_LIVE if CASE 0
        int biasConfidenceThreshold,

        // CASE 0 detector verdict at this snapshot
        boolean case0Fires,
        int case0Direction,
        String case0SkipReason,

        // CASE 4 watch-list (P1-3): does this snapshot record a watchlist add or
        // does it consume one set earlier? Carries the prior signal's timestamp.
        boolean case4WatchlistActive,
        Instant case4WatchlistAt,

        // Final decision summary
        String finalDecision,        // "ENTER" | "SKIP"
        String finalReason,          // entryCase label OR skip reason
        int finalLotCount            // 0 if skip; else the lot count sent to executor
) {

    /** Header line for the CSV (must stay in sync with {@link #toCsv()}). */
    public static final String CSV_HEADER = String.join(",",
            "ts", "index", "spot", "atm", "vix", "mode",
            "op_score", "op_dir",
            "range_20m_pct",
            "pcr", "pcr_slope_5m", "pcr_dir",
            "ce_oi_chg", "pe_oi_chg", "oi_avail", "oi_dir",
            "mom_dir", "mom_type", "mom_mag_pct",
            "bias_score", "matrix_case", "bias_threshold",
            "case0_fires", "case0_dir", "case0_skip_reason",
            "case4_watch_active", "case4_watch_at",
            "final_decision", "final_reason", "final_lots"
    );

    public String toCsv() {
        return String.join(",",
                ts(timestamp), index.name(), f(spot), String.valueOf(atmStrike), f(vix),
                mode == null ? "" : mode.name(),
                String.valueOf(operatorScore), String.valueOf(operatorDirection),
                f(range20mPct),
                f(pcr), f(pcrSlope5Min), String.valueOf(pcrDirection),
                String.valueOf(ceOiChange), String.valueOf(peOiChange),
                String.valueOf(oiAvailable), String.valueOf(oiDirection),
                String.valueOf(momentumDirection), safe(momentumType), f(momentumMagnitudePct),
                String.valueOf(biasScore), safe(matrixCase), String.valueOf(biasConfidenceThreshold),
                String.valueOf(case0Fires), String.valueOf(case0Direction),
                safe(case0SkipReason),
                String.valueOf(case4WatchlistActive),
                case4WatchlistAt == null ? "" : ts(case4WatchlistAt),
                safe(finalDecision), safe(finalReason),
                String.valueOf(finalLotCount)
        );
    }

    private static String ts(Instant i) { return i == null ? "" : i.toString(); }
    private static String f(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "";
        return String.format("%.4f", v);
    }
    private static String safe(String s) {
        if (s == null) return "";
        // CSV-safe: strip commas and newlines (skip-reason strings sometimes carry them)
        return s.replace(',', ';').replace('\n', ' ').replace('\r', ' ');
    }
}
