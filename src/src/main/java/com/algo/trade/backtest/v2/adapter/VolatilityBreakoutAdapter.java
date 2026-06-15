package com.algo.trade.backtest.v2.adapter;

import com.algo.trade.backtest.v2.StrategySignal;
import com.algo.trade.data.ChainSnapshot;
import com.algo.trade.domain.OptionType;
import com.algo.trade.strategy.StrategyConfig;
import com.algo.trade.strategy.StrategyType;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Volatility Breakout adapter: Bollinger Band squeeze breakout.
 * Buys when IV is cheap and market breaks out of a tight range.
 */
@Component
public class VolatilityBreakoutAdapter extends AbstractStrategyAdapter {

    private static final int RANGE_LOOKBACK = 12; // 1 hour at 5-min intervals
    private static final double SQUEEZE_THRESHOLD = 0.3; // Range < 0.3% = squeeze
    private static final double BREAKOUT_THRESHOLD = 0.15; // Break > 0.15% from range
    private static final double MAX_IV_FOR_ENTRY = 25.0;

    @Override
    public StrategyType strategyType() {
        return StrategyType.VOLATILITY_BREAKOUT;
    }

    @Override
    public Optional<StrategySignal> evaluateEntry(ChainSnapshot current, List<ChainSnapshot> history,
                                                   StrategyConfig config) {
        if (history.size() < RANGE_LOOKBACK) return Optional.empty();

        // Calculate recent range (Bollinger Band width proxy)
        double high = history.stream().limit(RANGE_LOOKBACK)
                .mapToDouble(ChainSnapshot::spot).max().orElse(current.spot());
        double low = history.stream().limit(RANGE_LOOKBACK)
                .mapToDouble(ChainSnapshot::spot).min().orElse(current.spot());
        double rangePercent = ((high - low) / current.spot()) * 100;

        // Need squeeze condition (tight range)
        if (rangePercent > SQUEEZE_THRESHOLD) return Optional.empty();

        // Check for breakout from the range
        double breakoutFromHigh = ((current.spot() - high) / high) * 100;
        double breakoutFromLow = ((low - current.spot()) / low) * 100;

        boolean bullishBreakout = breakoutFromHigh > BREAKOUT_THRESHOLD;
        boolean bearishBreakout = breakoutFromLow > BREAKOUT_THRESHOLD;

        if (!bullishBreakout && !bearishBreakout) return Optional.empty();

        OptionType optionType = bullishBreakout ? OptionType.CE : OptionType.PE;
        int targetStrike = current.atmStrike();

        // IV filter — buy when IV is cheap (squeeze = low IV)
        double iv = getOptionIV(current, targetStrike, optionType);
        double maxIv = config.getMaxIvRankForBuying() != null
                ? config.getMaxIvRankForBuying().doubleValue() : MAX_IV_FOR_ENTRY;
        if (iv > maxIv) return Optional.empty();

        double entryPrice = getOptionPrice(current, targetStrike, optionType);
        if (entryPrice <= 0) return Optional.empty();

        // Volume confirmation
        long volume = getOptionVolume(current, targetStrike, optionType);
        if (volume < 1000) return Optional.empty();

        double confidence = calculateConfidence(rangePercent, iv, bullishBreakout ? breakoutFromHigh : breakoutFromLow);

        return Optional.of(new StrategySignal(
                current.timestamp(), StrategyType.VOLATILITY_BREAKOUT, optionType,
                targetStrike, entryPrice, current.spot(),
                String.format("Vol breakout %s: range=%.2f%%, breakout=%.2f%%, IV=%.1f",
                        bullishBreakout ? "BULL" : "BEAR", rangePercent,
                        bullishBreakout ? breakoutFromHigh : breakoutFromLow, iv),
                confidence, iv, getOptionDelta(current, targetStrike, optionType),
                getOptionOI(current, targetStrike, optionType), volume
        ));
    }

    private double calculateConfidence(double rangePercent, double iv, double breakoutStrength) {
        double conf = 0.3;
        if (rangePercent < 0.2) conf += 0.2; // Tighter squeeze = higher confidence
        if (iv < 15) conf += 0.2; // Very cheap IV
        if (breakoutStrength > 0.3) conf += 0.15;
        return Math.min(1.0, conf);
    }
}
