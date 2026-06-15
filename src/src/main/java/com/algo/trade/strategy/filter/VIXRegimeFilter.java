package com.algo.trade.strategy.filter;

import com.algo.trade.risk.MarketGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * VIX Regime Filter — gates entries based on India VIX level and direction.
 *
 * Regimes:
 *   DEAD (< 11)      → Block all entries (premiums erode, no movement)
 *   CAUTIOUS (11–14)  → Allow only strong signals, reduce lots to 75%
 *   FAVORABLE (14–25) → Allow all entries (premiums expand, good for buyers)
 *   EXTREME (> 25)    → Allow entries but reduce lot size to 50%
 *
 * India VIX often sits 10–14. Dead threshold lowered to 11 (was 13)
 * to avoid blocking trades in normal calm markets.
 *
 * Background VIX snapshots recorded every 30s for consistent direction detection.
 */
@Component
public class VIXRegimeFilter {

    private static final Logger log = LoggerFactory.getLogger(VIXRegimeFilter.class);

    private final MarketGuard marketGuard;

    @Value("${vix-regime.dead-threshold:11.0}") private double deadThreshold;
    @Value("${vix-regime.cautious-low:11.0}") private double cautiousLow;
    @Value("${vix-regime.cautious-high:14.0}") private double cautiousHigh;
    @Value("${vix-regime.extreme-threshold:25.0}") private double extremeThreshold;
    @Value("${vix-regime.extreme-lot-multiplier:0.5}") private double extremeLotMultiplier;
    @Value("${vix-regime.cautious-lot-multiplier:0.75}") private double cautiousLotMultiplier;
    @Value("${vix-regime.direction-lookback-minutes:15}") private int directionLookbackMinutes;

    public enum Regime { DEAD, CAUTIOUS, FAVORABLE, EXTREME }

    private record VIXSnapshot(long timestampMs, double vix) {}
    private final Deque<VIXSnapshot> vixHistory = new ConcurrentLinkedDeque<>();

    public VIXRegimeFilter(MarketGuard marketGuard) {
        this.marketGuard = marketGuard;
    }

    /**
     * Background VIX snapshot recorder — runs every 30s during market hours.
     */
    @Scheduled(fixedDelay = 30_000)
    public void recordBackgroundSnapshot() {
        double vix = marketGuard.getCurrentVix();
        if (vix > 0) {
            recordSnapshot(vix);
        }
    }

    public Regime classifyRegime() {
        double vix = marketGuard.getCurrentVix();
        if (vix <= 0) return Regime.FAVORABLE;
        if (vix < deadThreshold) return Regime.DEAD;
        if (vix >= deadThreshold && vix < cautiousHigh) return Regime.CAUTIOUS;
        if (vix >= extremeThreshold) return Regime.EXTREME;
        return Regime.FAVORABLE;
    }

    public boolean isEntryAllowed(double signalStrength) {
        Regime regime = classifyRegime();
        return switch (regime) {
            case DEAD -> signalStrength >= 0.12;
            case CAUTIOUS -> signalStrength >= 0.10;
            case FAVORABLE, EXTREME -> true;
        };
    }

    public String getBlockReason() {
        Regime regime = classifyRegime();
        double vix = marketGuard.getCurrentVix();
        return switch (regime) {
            case DEAD -> "VIX_LOW_WEAK_SIGNAL (" + String.format("%.1f", vix) + " < " + deadThreshold + ", need strong signal)";
            case CAUTIOUS -> "VIX_CAUTIOUS_WEAK_SIGNAL (" + String.format("%.1f", vix) + " in " + cautiousLow + "-" + cautiousHigh + ")";
            default -> null;
        };
    }

    public double getLotMultiplier() {
        Regime regime = classifyRegime();
        return switch (regime) {
            case EXTREME -> extremeLotMultiplier;
            case CAUTIOUS -> cautiousLotMultiplier;
            default -> 1.0;
        };
    }

    public boolean isVIXRising() {
        long now = System.currentTimeMillis();
        long lookbackMs = directionLookbackMinutes * 60 * 1000L;
        double currentVix = marketGuard.getCurrentVix();
        if (currentVix <= 0) return false;

        VIXSnapshot old = null;
        for (VIXSnapshot snap : vixHistory) {
            if (snap.timestampMs() <= now - lookbackMs) { old = snap; break; }
        }
        return old != null && currentVix > old.vix() * 1.02;
    }

    private void recordSnapshot(double vix) {
        if (vix <= 0) return;
        vixHistory.addFirst(new VIXSnapshot(System.currentTimeMillis(), vix));
        while (vixHistory.size() > 60) vixHistory.removeLast();
    }
}
