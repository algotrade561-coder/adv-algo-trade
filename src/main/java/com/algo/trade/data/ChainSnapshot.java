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
     * 27 fields per strike capturing everything needed for backtest replay and ML.
     */
    public record StrikeData(
            int strike,

            // ── CE (Call) ──
            double ceLTP,
            long ceOI,
            long ceVolume,
            double ceIV,          // Implied volatility (percentage, e.g. 18.5)
            double ceDelta,       // Range: 0 to 1
            double ceGamma,
            double ceTheta,       // Negative (time decay)
            double ceVega,
            double ceBid,
            double ceAsk,
            long ceOiChange,      // OI change from previous day (positive = buildup)
            double ceHigh5m,      // Highest price in this 5-min interval
            double ceLow5m,       // Lowest price in this 5-min interval

            // ── PE (Put) ──
            double peLTP,
            long peOI,
            long peVolume,
            double peIV,
            double peDelta,       // Range: -1 to 0
            double peGamma,
            double peTheta,
            double peVega,
            double peBid,
            double peAsk,
            long peOiChange,
            double peHigh5m,
            double peLow5m
    ) {}
}
