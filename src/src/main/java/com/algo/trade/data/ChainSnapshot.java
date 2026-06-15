package com.algo.trade.data;

import java.time.Instant;
import java.util.List;

/**
 * Immutable snapshot of the option chain at a point in time.
 * Captured every 5 minutes during market hours for backtesting and ML training.
 *
 * Storage: GZIP-compressed JSON at data/chain-snapshots/YYYY-MM-DD/{underlying}_{HHmm}.json.gz
 * Retention: 60 days (configurable).
 */
public record ChainSnapshot(
        Instant timestamp,
        String underlying,       // NIFTY, BANKNIFTY, SENSEX
        double spot,             // Underlying spot price
        double vix,              // India VIX at capture time
        String expiry,           // ISO date e.g. "2026-05-12"
        int atmStrike,           // Computed ATM strike
        List<StrikeData> strikes // ATM ± 10 strikes
) {

    /**
     * Per-strike option chain data — both CE and PE at a single strike.
     * Captures everything needed for backtest replay and ML.
     *
     * <p>OI-change semantics: {@code ceOiChange}/{@code peOiChange} is the snapshot-to-snapshot
     * OI delta. A value of {@code 0} is ambiguous on its own — it can mean "genuinely unchanged",
     * "first observation of this strike" (no prior baseline), or "data gap". The companion
     * {@code ceOiChangeValid}/{@code peOiChangeValid} flags resolve this: when {@code false}, the
     * delta is NOT trustworthy (no prior baseline / stale OI) and tuning code must ignore it rather
     * than treat it as a real zero. Analysis of historical captures showed ~52% of OI-change fields
     * were 0 with no way to distinguish real zeros from gaps — these flags fix that.
     *
     * <p>Volume semantics: {@code ceVolume}/{@code peVolume} remain the broker's CUMULATIVE daily
     * traded volume. {@code ceVolumeInterval}/{@code peVolumeInterval} are the per-snapshot traded
     * volume (cumulative diff), which is what momentum/absorption logic actually needs.
     */
    public record StrikeData(
            int strike,

            // ── CE (Call) ──
            double ceLTP,
            long ceOI,
            long ceVolume,        // Cumulative daily volume
            double ceIV,          // Implied volatility (percentage, e.g. 18.5)
            double ceDelta,       // Range: 0 to 1
            double ceGamma,
            double ceTheta,       // Negative (time decay)
            double ceVega,
            double ceBid,
            double ceAsk,
            long ceOiChange,      // Snapshot-to-snapshot OI delta (positive = buildup)
            double ceHigh5m,      // Highest price in this interval
            double ceLow5m,       // Lowest price in this interval

            // ── PE (Put) ──
            double peLTP,
            long peOI,
            long peVolume,        // Cumulative daily volume
            double peIV,
            double peDelta,       // Range: -1 to 0
            double peGamma,
            double peTheta,
            double peVega,
            double peBid,
            double peAsk,
            long peOiChange,      // Snapshot-to-snapshot OI delta
            double peHigh5m,
            double peLow5m,

            // ── Capture-quality / interval fields (added for accurate tuning) ──
            long ceVolumeInterval,    // Traded volume in THIS interval (cumulative diff)
            long peVolumeInterval,
            boolean ceOiChangeValid,  // true => ceOiChange is a trustworthy delta (real prior baseline)
            boolean peOiChangeValid   // true => peOiChange is a trustworthy delta
    ) {
        /**
         * Backward-compatible constructor (pre-capture-quality-fields signature). Existing
         * callers and tests that build a StrikeData with the original field set continue to
         * compile; the new fields default to interval-volume 0 and OI-change-valid = true
         * (an explicitly-supplied oiChange is treated as a real delta).
         */
        public StrikeData(
                int strike,
                double ceLTP, long ceOI, long ceVolume, double ceIV, double ceDelta,
                double ceGamma, double ceTheta, double ceVega, double ceBid, double ceAsk,
                long ceOiChange, double ceHigh5m, double ceLow5m,
                double peLTP, long peOI, long peVolume, double peIV, double peDelta,
                double peGamma, double peTheta, double peVega, double peBid, double peAsk,
                long peOiChange, double peHigh5m, double peLow5m) {
            this(strike,
                 ceLTP, ceOI, ceVolume, ceIV, ceDelta, ceGamma, ceTheta, ceVega, ceBid, ceAsk,
                 ceOiChange, ceHigh5m, ceLow5m,
                 peLTP, peOI, peVolume, peIV, peDelta, peGamma, peTheta, peVega, peBid, peAsk,
                 peOiChange, peHigh5m, peLow5m,
                 0L, 0L, true, true);
        }
    }
}
