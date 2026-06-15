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
 * Scalping adapter: EMA 9/21 crossover with 2-candle confirmation.
 * Uses spot price movement across snapshots to detect short-term momentum.
 */
@Component
public class ScalpingAdapter extends AbstractStrategyAdapter {

    private static final int SHORT_EMA_PERIODS = 3;  // ~15 min at 5-min intervals
    private static final int LONG_EMA_PERIODS = 7;   // ~35 min
    private static final double MIN_CROSSOVER_GAP = 0.05; // Minimum % gap between EMAs
    private static final int CONFIRMATION_SNAPSHOTS = 2;

    @Override
    public StrategyType strategyType() {
        return StrategyType.SCALPING;
    }

    @Override
    public Optional<StrategySignal> evaluateEntry(ChainSnapshot current, List<ChainSnapshot> history,
                                                   StrategyConfig config) {
        if (history.size() < LONG_EMA_PERIODS + CONFIRMATION_SNAPSHOTS) return Optional.empty();

        // Calculate EMAs from spot prices
        double shortEma = calculateEma(current, history, SHORT_EMA_PERIODS);
        double longEma = calculateEma(current, history, LONG_EMA_PERIODS);

        double emaGapPercent = ((shortEma - longEma) / longEma) * 100;

        // Need clear crossover
        if (Math.abs(emaGapPercent) < MIN_CROSSOVER_GAP) return Optional.empty();

        // Check previous snapshot for confirmation (crossover just happened)
        double prevShortEma = calculateEmaFromHistory(history, SHORT_EMA_PERIODS, 1);
        double prevLongEma = calculateEmaFromHistory(history, LONG_EMA_PERIODS, 1);
        double prevGap = ((prevShortEma - prevLongEma) / prevLongEma) * 100;

        boolean bullishCrossover = emaGapPercent > 0 && prevGap <= 0;
        boolean bearishCrossover = emaGapPercent < 0 && prevGap >= 0;

        if (!bullishCrossover && !bearishCrossover) return Optional.empty();

        // 2-candle confirmation: spot should continue in direction
        if (history.size() >= 2) {
            double recentMove = spotChangePercent(current, history.get(1));
            if (bullishCrossover && recentMove < 0) return Optional.empty();
            if (bearishCrossover && recentMove > 0) return Optional.empty();
        }

        OptionType optionType = bullishCrossover ? OptionType.CE : OptionType.PE;
        int targetStrike = current.atmStrike();
        double entryPrice = getOptionPrice(current, targetStrike, optionType);
        if (entryPrice <= 0) return Optional.empty();

        double iv = getOptionIV(current, targetStrike, optionType);
        double confidence = Math.min(1.0, 0.4 + Math.abs(emaGapPercent) * 2);

        return Optional.of(new StrategySignal(
                current.timestamp(), StrategyType.SCALPING, optionType,
                targetStrike, entryPrice, current.spot(),
                String.format("EMA crossover %s: short=%.1f, long=%.1f, gap=%.2f%%",
                        bullishCrossover ? "BULL" : "BEAR", shortEma, longEma, emaGapPercent),
                confidence, iv, getOptionDelta(current, targetStrike, optionType),
                getOptionOI(current, targetStrike, optionType),
                getOptionVolume(current, targetStrike, optionType)
        ));
    }

    private double calculateEma(ChainSnapshot current, List<ChainSnapshot> history, int periods) {
        double multiplier = 2.0 / (periods + 1);
        double ema = history.get(Math.min(periods - 1, history.size() - 1)).spot();

        for (int i = Math.min(periods - 2, history.size() - 2); i >= 0; i--) {
            ema = (history.get(i).spot() - ema) * multiplier + ema;
        }
        ema = (current.spot() - ema) * multiplier + ema;
        return ema;
    }

    private double calculateEmaFromHistory(List<ChainSnapshot> history, int periods, int offset) {
        if (history.size() < periods + offset) return history.get(0).spot();

        double multiplier = 2.0 / (periods + 1);
        double ema = history.get(Math.min(periods + offset - 1, history.size() - 1)).spot();

        for (int i = Math.min(periods + offset - 2, history.size() - 2); i >= offset; i--) {
            ema = (history.get(i).spot() - ema) * multiplier + ema;
        }
        return ema;
    }
}
