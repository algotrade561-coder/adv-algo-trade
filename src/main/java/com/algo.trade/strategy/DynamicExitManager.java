package com.algo.trade.strategy;

import com.algo.trade.domain.Candle;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Dynamic Exit Manager — ATR-based trailing stops and partial profit taking.
 *
 * Instead of fixed SL/target percentages, uses Average True Range (ATR)
 * to adapt exits to current market volatility.
 *
 * Features:
 * 1. ATR-based trailing SL — wider in volatile markets, tighter in calm
 * 2. Partial profit taking — exit 50% at 30% profit, trail rest
 * 3. Time-based tightening — SL gets tighter as expiry approaches
 */
@Component
public class DynamicExitManager {

    /**
     * Calculate ATR from candles.
     */
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

    /**
     * Calculate dynamic SL based on ATR.
     * High ATR → wider SL to avoid whipsaws.
     * Low ATR → tighter SL to protect profits.
     *
     * @param entryPremium combined premium at entry
     * @param atr current ATR value
     * @param daysToExpiry days remaining
     * @return SL as percentage of entry premium
     */
    public double calculateDynamicSL(double entryPremium, double atr, int daysToExpiry) {
        if (entryPremium <= 0 || atr <= 0) return 30;
        double atrBasedSL = (2 * atr / entryPremium) * 100;
        double timeMultiplier = switch (daysToExpiry) {
            case 0 -> 0.5;   // expiry day: very tight
            case 1 -> 0.7;
            case 2 -> 0.85;
            default -> 1.0;
        };
        double dynamicSL = atrBasedSL * timeMultiplier;
        return Math.max(15, Math.min(60, dynamicSL)); // clamp 15–60%
    }

    /**
     * Calculate trailing SL based on profit level and ATR.
     * As profit grows, trailing SL tightens to lock in more gains.
     */
    public double calculateTrailingSL(double profitPercent, double peakProfitPercent,
                                       double atr, double entryPremium) {
        if (peakProfitPercent < 10) return -999; // not active yet
        double atrTrail = entryPremium > 0 ? (1.5 * atr / entryPremium) * 100 : 15;
        atrTrail = Math.max(8, Math.min(25, atrTrail));
        if (peakProfitPercent > 50) atrTrail *= 0.6;
        else if (peakProfitPercent > 30) atrTrail *= 0.75;
        else if (peakProfitPercent > 20) atrTrail *= 0.85;
        return peakProfitPercent - atrTrail;
    }

    /**
     * Should we take partial profit?
     * Returns fraction to exit (0 = don't, 0.5 = exit half).
     */
    public double partialProfitFraction(double profitPercent, boolean alreadyPartialExited) {
        if (alreadyPartialExited) return 0;
        if (profitPercent >= 30) return 0.5; // exit 50% at 30% profit
        return 0;
    }

    /**
     * Is the market breaking out of range? (momentum exit signal)
     * If price moves > 2.5x ATR from day open, it's trending — exit short premium.
     *
     * @param candles recent candles (at least 15)
     * @param periods ATR period
     * @return true if breakout detected
     */
    public boolean isBreakout(List<Candle> candles, int periods) {
        if (candles.size() < periods + 1) return false;
        double atr = calculateATR(candles, periods);
        if (atr <= 0) return false;
        Candle latest = candles.getLast();
        Candle dayOpen = candles.getFirst();
        double move = Math.abs(latest.close().subtract(dayOpen.open()).doubleValue());
        return move > 2.5 * atr;
    }

    /**
     * Calculate ATR-based dynamic target (not just SL).
     * Target = 3x ATR from entry, adjusted by time-to-expiry.
     */
    public double calculateDynamicTarget(double entryPremium, double atr, int daysToExpiry) {
        if (entryPremium <= 0 || atr <= 0) return 60; // default 60%
        double atrBasedTarget = (3 * atr / entryPremium) * 100;
        // Tighter target near expiry (less time for big moves)
        double timeMultiplier = switch (daysToExpiry) {
            case 0 -> 0.4;
            case 1 -> 0.6;
            case 2 -> 0.8;
            default -> 1.0;
        };
        double dynamicTarget = atrBasedTarget * timeMultiplier;
        return Math.max(20, Math.min(150, dynamicTarget)); // clamp 20–150%
    }
}
