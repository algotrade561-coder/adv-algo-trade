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
 * Momentum adapter: Rate-of-change momentum with acceleration, EMA trend alignment,
 * and volume confirmation. Rides sustained directional moves.
 */
@Component
public class MomentumAdapter extends AbstractStrategyAdapter {

    private static final int ROC_PERIOD = 6;  // 30 min at 5-min intervals
    private static final double MIN_ROC_PERCENT = 0.2; // Minimum rate of change
    private static final double ACCELERATION_THRESHOLD = 0.05; // ROC increasing
    private static final int TREND_EMA_PERIOD = 12; // 1 hour EMA for trend

    @Override
    public StrategyType strategyType() {
        return StrategyType.MOMENTUM;
    }

    @Override
    public Optional<StrategySignal> evaluateEntry(ChainSnapshot current, List<ChainSnapshot> history,
                                                   StrategyConfig config) {
        if (history.size() < ROC_PERIOD + 2) return Optional.empty();

        // Calculate Rate of Change
        double currentRoc = calculateRoc(current, history, ROC_PERIOD);
        double prevRoc = calculateRocFromHistory(history, ROC_PERIOD, 1);

        // Need minimum momentum
        if (Math.abs(currentRoc) < MIN_ROC_PERCENT) return Optional.empty();

        // Acceleration check — momentum should be increasing
        double acceleration = currentRoc - prevRoc;
        boolean bullish = currentRoc > 0;
        if (bullish && acceleration < ACCELERATION_THRESHOLD) return Optional.empty();
        if (!bullish && acceleration > -ACCELERATION_THRESHOLD) return Optional.empty();

        // EMA trend alignment
        double trendEma = calculateTrendEma(current, history);
        if (bullish && current.spot() < trendEma) return Optional.empty(); // Against trend
        if (!bullish && current.spot() > trendEma) return Optional.empty();

        // Volume confirmation
        OptionType optionType = bullish ? OptionType.CE : OptionType.PE;
        int targetStrike = current.atmStrike();
        long volume = getOptionVolume(current, targetStrike, optionType);
        long avgVolume = averageVolume(history, targetStrike, optionType, 6);
        if (avgVolume > 0 && volume < avgVolume) return Optional.empty(); // Below average volume

        double entryPrice = getOptionPrice(current, targetStrike, optionType);
        if (entryPrice <= 0) return Optional.empty();

        double iv = getOptionIV(current, targetStrike, optionType);
        double confidence = Math.min(1.0, 0.3 + Math.abs(currentRoc) * 2 + Math.abs(acceleration) * 5);

        return Optional.of(new StrategySignal(
                current.timestamp(), StrategyType.MOMENTUM, optionType,
                targetStrike, entryPrice, current.spot(),
                String.format("Momentum %s: ROC=%.2f%%, accel=%.3f%%, vol=%dx avg",
                        bullish ? "BULL" : "BEAR", currentRoc, acceleration,
                        avgVolume > 0 ? volume / avgVolume : 0),
                confidence, iv, getOptionDelta(current, targetStrike, optionType),
                getOptionOI(current, targetStrike, optionType), volume
        ));
    }

    private double calculateRoc(ChainSnapshot current, List<ChainSnapshot> history, int period) {
        if (history.size() < period) return 0;
        double pastSpot = history.get(period - 1).spot();
        return pastSpot > 0 ? ((current.spot() - pastSpot) / pastSpot) * 100 : 0;
    }

    private double calculateRocFromHistory(List<ChainSnapshot> history, int period, int offset) {
        if (history.size() < period + offset) return 0;
        double current = history.get(offset - 1).spot();
        double past = history.get(period + offset - 1).spot();
        return past > 0 ? ((current - past) / past) * 100 : 0;
    }

    private double calculateTrendEma(ChainSnapshot current, List<ChainSnapshot> history) {
        int periods = Math.min(TREND_EMA_PERIOD, history.size());
        double multiplier = 2.0 / (periods + 1);
        double ema = history.get(periods - 1).spot();
        for (int i = periods - 2; i >= 0; i--) {
            ema = (history.get(i).spot() - ema) * multiplier + ema;
        }
        ema = (current.spot() - ema) * multiplier + ema;
        return ema;
    }

    private long averageVolume(List<ChainSnapshot> history, int strike, OptionType optionType, int periods) {
        return (long) history.stream().limit(periods)
                .mapToLong(s -> getOptionVolume(s, strike, optionType))
                .average().orElse(0);
    }
}
