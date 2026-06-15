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
 * Directional Buy adapter: VWAP + breakout + volume + OI + IV rank.
 * Buys CE when bullish breakout detected, PE when bearish.
 */
@Component
public class DirectionalBuyAdapter extends AbstractStrategyAdapter {

    private static final double MIN_SPOT_MOVE_PERCENT = 0.15;
    private static final double MAX_IV_RANK = 40.0;
    private static final double MIN_VOLUME_SPIKE = 1.5;
    private static final int LOOKBACK_SNAPSHOTS = 6; // 30 min at 5-min intervals

    @Override
    public StrategyType strategyType() {
        return StrategyType.DIRECTIONAL_BUY;
    }

    @Override
    public Optional<StrategySignal> evaluateEntry(ChainSnapshot current, List<ChainSnapshot> history,
                                                   StrategyConfig config) {
        if (history.size() < LOOKBACK_SNAPSHOTS) return Optional.empty();

        // Determine direction from spot movement
        ChainSnapshot lookbackSnapshot = history.get(LOOKBACK_SNAPSHOTS - 1);
        double spotMove = spotChangePercent(current, lookbackSnapshot);

        // Need meaningful directional move
        if (Math.abs(spotMove) < MIN_SPOT_MOVE_PERCENT) return Optional.empty();

        boolean bullish = spotMove > 0;
        OptionType optionType = bullish ? OptionType.CE : OptionType.PE;
        int targetStrike = current.atmStrike();

        // IV filter — don't buy when IV is too high
        double iv = getOptionIV(current, targetStrike, optionType);
        if (iv > MAX_IV_RANK) return Optional.empty();

        // Volume confirmation — need volume spike
        long currentVolume = getOptionVolume(current, targetStrike, optionType);
        long avgVolume = averageVolume(history, targetStrike, optionType);
        if (avgVolume > 0 && currentVolume < avgVolume * MIN_VOLUME_SPIKE) return Optional.empty();

        // OI confirmation — OI should be building in our direction
        long oiChange = getOiChange(current, targetStrike, optionType);
        if (oiChange < 0) return Optional.empty(); // OI unwinding = not confirming

        // Breakout confirmation — price should be making new highs/lows
        if (!isBreakout(current, history, bullish)) return Optional.empty();

        double entryPrice = getOptionPrice(current, targetStrike, optionType);
        if (entryPrice <= 0) return Optional.empty();

        double confidence = calculateConfidence(spotMove, currentVolume, avgVolume, oiChange, iv);

        return Optional.of(new StrategySignal(
                current.timestamp(), StrategyType.DIRECTIONAL_BUY, optionType,
                targetStrike, entryPrice, current.spot(),
                String.format("Directional %s: spot move %.2f%%, vol spike %.1fx, OI+%d",
                        bullish ? "BULL" : "BEAR", spotMove,
                        avgVolume > 0 ? (double) currentVolume / avgVolume : 0, oiChange),
                confidence, iv, getOptionDelta(current, targetStrike, optionType),
                getOptionOI(current, targetStrike, optionType), currentVolume
        ));
    }

    private boolean isBreakout(ChainSnapshot current, List<ChainSnapshot> history, boolean bullish) {
        double currentSpot = current.spot();
        double highestHigh = history.stream().limit(LOOKBACK_SNAPSHOTS)
                .mapToDouble(ChainSnapshot::spot).max().orElse(currentSpot);
        double lowestLow = history.stream().limit(LOOKBACK_SNAPSHOTS)
                .mapToDouble(ChainSnapshot::spot).min().orElse(currentSpot);

        if (bullish) {
            return currentSpot >= highestHigh; // Breaking above recent high
        } else {
            return currentSpot <= lowestLow; // Breaking below recent low
        }
    }

    private long averageVolume(List<ChainSnapshot> history, int strike, OptionType optionType) {
        return (long) history.stream().limit(LOOKBACK_SNAPSHOTS)
                .mapToLong(s -> getOptionVolume(s, strike, optionType))
                .average().orElse(0);
    }

    private double calculateConfidence(double spotMove, long volume, long avgVolume,
                                        long oiChange, double iv) {
        double conf = 0.3; // Base
        if (Math.abs(spotMove) > 0.3) conf += 0.2;
        if (avgVolume > 0 && volume > avgVolume * 2) conf += 0.2;
        if (oiChange > 10000) conf += 0.15;
        if (iv < 20) conf += 0.15;
        return Math.min(1.0, conf);
    }
}
