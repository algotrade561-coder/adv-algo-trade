package com.algo.trade.strategy;

import com.algo.trade.domain.Candle;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Dynamic Exit Manager — ATR-based trailing stops, progressive profit booking,
 * stall detection, and gamma spike exits.
 */
@Component
public class DynamicExitManager {

    public record ExitLayer(String name, double triggerProfitPct, double exitFraction) {}

    /** Progressive profit booking ladder — exit fraction of CURRENT remaining quantity at each trigger. */
    public static final List<ExitLayer> PROGRESSIVE_LAYERS = List.of(
            new ExitLayer("PARTIAL_1", 30.0, 0.25),
            new ExitLayer("PARTIAL_2", 50.0, 0.33),
            new ExitLayer("PARTIAL_3", 80.0, 0.50)
    );

    public double calculateATR(List<Candle> candles, int periods) {
        if (candles.size() < periods + 1) return 0;
        double atrSum = 0;
        for (int i = candles.size() - periods; i < candles.size(); i++) {
            Candle c = candles.get(i);
            Candle prev = candles.get(i - 1);
            double tr = Math.max(
                c.high().subtract(c.low()).doubleValue(),
                Math.max(
                    Math.abs(c.high().subtract(prev.close()).doubleValue()),
                    Math.abs(c.low().subtract(prev.close()).doubleValue())
                )
            );
            atrSum += tr;
        }
        return atrSum / periods;
    }

    public double calculateDynamicSL(double entryPremium, double atr, int daysToExpiry) {
        if (entryPremium <= 0 || atr <= 0) return 30;
        double atrBasedSL = (2 * atr / entryPremium) * 100;
        // Pure ATR-based — no DTE adjustment. ATR already reflects current volatility.
        return Math.max(15, Math.min(60, atrBasedSL));
    }

    /**
     * Delta-adjusted SL using the UNDERLYING's ATR.
     * Formula: SL% = (1.5 × underlyingATR × delta) / optionEntryPrice × 100
     * This gives tighter SL for ATM (high delta) and wider for OTM (low delta).
     * Clamps between 10% and 40% for safety.
     */
    public double calculateDeltaAdjustedSL(double entryPremium, double underlyingAtr, double delta) {
        if (entryPremium <= 0 || underlyingAtr <= 0 || delta <= 0) return 25; // safe default
        double expectedMove = underlyingAtr * delta;
        double slPct = (1.5 * expectedMove / entryPremium) * 100;
        return Math.max(10, Math.min(40, slPct));
    }

    /**
     * Delta-adjusted target using the UNDERLYING's ATR.
     * Formula: Target% = (2.5 × underlyingATR × delta) / optionEntryPrice × 100
     * Clamps between 15% and 80%.
     */
    public double calculateDeltaAdjustedTarget(double entryPremium, double underlyingAtr, double delta, int daysToExpiry) {
        if (entryPremium <= 0 || underlyingAtr <= 0 || delta <= 0) return 40; // safe default
        double expectedMove = underlyingAtr * delta;
        double targetPct = (2.5 * expectedMove / entryPremium) * 100;
        // DTE multiplier: reduce target near expiry (less time for move to develop)
        double timeMultiplier = switch (daysToExpiry) {
            case 0 -> 0.5;
            case 1 -> 0.7;
            case 2 -> 0.85;
            default -> 1.0;
        };
        return Math.max(15, Math.min(80, targetPct * timeMultiplier));
    }

    /**
     * Delta-adjusted trailing stop activation.
     * Activates trailing when profit reaches 1.5 × expected move.
     * Clamps between 8% and 35%.
     */
    public double calculateDeltaAdjustedTrailActivation(double entryPremium, double underlyingAtr, double delta) {
        if (entryPremium <= 0 || underlyingAtr <= 0 || delta <= 0) return 20;
        double expectedMove = underlyingAtr * delta;
        double activationPct = (1.5 * expectedMove / entryPremium) * 100;
        return Math.max(8, Math.min(35, activationPct));
    }

    /**
     * Delta-adjusted trailing gap (distance from peak before stop triggers).
     * Gap = 1.0 × expected move as % of entry.
     * Tightens as profit grows (lock in more at higher profits).
     * Clamps between 5% and 20%.
     */
    public double calculateDeltaAdjustedTrailGap(double entryPremium, double underlyingAtr, double delta, double peakProfitPct) {
        if (entryPremium <= 0 || underlyingAtr <= 0 || delta <= 0) return 10;
        double expectedMove = underlyingAtr * delta;
        double gapPct = (1.0 * expectedMove / entryPremium) * 100;
        // Tighten gap as profit grows
        if (peakProfitPct > 50) gapPct *= 0.6;
        else if (peakProfitPct > 30) gapPct *= 0.75;
        else if (peakProfitPct > 20) gapPct *= 0.85;
        return Math.max(5, Math.min(20, gapPct));
    }

    public double calculateTrailingSL(double profitPercent, double peakProfitPercent,
                                       double atr, double entryPremium) {
        if (peakProfitPercent < 10) return -999;
        double atrTrail = entryPremium > 0 ? (1.5 * atr / entryPremium) * 100 : 15;
        atrTrail = Math.max(8, Math.min(25, atrTrail));
        if (peakProfitPercent > 50) atrTrail *= 0.6;
        else if (peakProfitPercent > 30) atrTrail *= 0.75;
        else if (peakProfitPercent > 20) atrTrail *= 0.85;
        return peakProfitPercent - atrTrail;
    }

    /** @deprecated Use nextExitLayer() for progressive booking */
    @Deprecated
    public double partialProfitFraction(double profitPercent, boolean alreadyPartialExited) {
        if (alreadyPartialExited) return 0;
        if (profitPercent >= 30) return 0.5;
        return 0;
    }

    public boolean isBreakout(List<Candle> candles, int periods) {
        if (candles.size() < periods + 1) return false;
        double atr = calculateATR(candles, periods);
        if (atr <= 0) return false;
        Candle latest = candles.getLast();
        Candle dayOpen = candles.getFirst();
        double move = Math.abs(latest.close().subtract(dayOpen.open()).doubleValue());
        return move > 2.5 * atr;
    }

    public double calculateDynamicTarget(double entryPremium, double atr, int daysToExpiry) {
        if (entryPremium <= 0 || atr <= 0) return 60;
        double atrBasedTarget = (3 * atr / entryPremium) * 100;
        double timeMultiplier = switch (daysToExpiry) {
            case 0 -> 0.4;
            case 1 -> 0.6;
            case 2 -> 0.8;
            default -> 1.0;
        };
        double dynamicTarget = atrBasedTarget * timeMultiplier;
        return Math.max(20, Math.min(150, dynamicTarget));
    }

    /**
     * Returns the next unfired progressive exit layer if the profit threshold is met.
     * Caller must add the returned layer name to firedLayers and execute the partial close.
     */
    public Optional<ExitLayer> nextExitLayer(double profitPercent, Set<String> firedLayers) {
        return PROGRESSIVE_LAYERS.stream()
                .filter(layer -> !firedLayers.contains(layer.name()))
                .filter(layer -> profitPercent >= layer.triggerProfitPct())
                .findFirst();
    }

    /**
     * Detects premium stall: underlying moved less than 50% of ATR in the last N 1-min candles after entry.
     * Only triggers when trade is neither in significant profit nor near stop-loss.
     */
    public boolean isStalled(List<Candle> underlying1mCandles, Instant entryTime, double atr, int lookbackCandles) {
        if (atr <= 0) return false;
        List<Candle> postEntry = underlying1mCandles.stream()
                .filter(c -> c.timestamp().isAfter(entryTime))
                .toList();
        if (postEntry.size() < lookbackCandles) return false;
        List<Candle> window = postEntry.subList(postEntry.size() - lookbackCandles, postEntry.size());
        double high = window.stream().mapToDouble(c -> c.high().doubleValue()).max().orElse(0);
        double low  = window.stream().mapToDouble(c -> c.low().doubleValue()).min().orElse(0);
        return (high - low) < 0.5 * atr;
    }

    /**
     * Detects a gamma spike: last closed candle moved > 3× ATR from the previous candle close.
     * Relevant on expiry day when ATM gamma causes non-linear premium moves.
     */
    public boolean isGammaSpike(List<Candle> candles, double atr) {
        if (candles.size() < 2 || atr <= 0) return false;
        Candle prev = candles.get(candles.size() - 2);
        Candle curr = candles.getLast();
        double move = Math.abs(curr.close().subtract(prev.close()).doubleValue());
        return move > 3.0 * atr;
    }
}
