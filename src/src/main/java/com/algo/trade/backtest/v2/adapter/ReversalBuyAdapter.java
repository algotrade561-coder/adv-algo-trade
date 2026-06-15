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
 * Reversal Buy adapter: RSI(14) mean reversion.
 * Buys CE when oversold (<30), PE when overbought (>70) — fades extreme intraday moves.
 */
@Component
public class ReversalBuyAdapter extends AbstractStrategyAdapter {

    private static final int RSI_PERIOD = 14;
    private static final double OVERSOLD_THRESHOLD = 30.0;
    private static final double OVERBOUGHT_THRESHOLD = 70.0;
    private static final double MIN_MOVE_PERCENT = 0.4; // Need significant move to fade

    @Override
    public StrategyType strategyType() {
        return StrategyType.REVERSAL_BUY;
    }

    @Override
    public Optional<StrategySignal> evaluateEntry(ChainSnapshot current, List<ChainSnapshot> history,
                                                   StrategyConfig config) {
        if (history.size() < RSI_PERIOD + 1) return Optional.empty();

        // Calculate RSI from spot price changes
        double rsi = calculateRsi(current, history);

        boolean oversold = rsi < OVERSOLD_THRESHOLD;
        boolean overbought = rsi > OVERBOUGHT_THRESHOLD;

        if (!oversold && !overbought) return Optional.empty();

        // Confirm with significant move (don't fade small moves)
        double recentMove = spotChangePercent(current, history.get(RSI_PERIOD - 1));
        if (Math.abs(recentMove) < MIN_MOVE_PERCENT) return Optional.empty();

        // Oversold → buy CE (expecting bounce), Overbought → buy PE (expecting pullback)
        OptionType optionType = oversold ? OptionType.CE : OptionType.PE;
        int targetStrike = current.atmStrike();

        double entryPrice = getOptionPrice(current, targetStrike, optionType);
        if (entryPrice <= 0) return Optional.empty();

        double iv = getOptionIV(current, targetStrike, optionType);
        double confidence = Math.min(1.0, 0.3 + Math.abs(rsi - 50) / 50 * 0.5
                + Math.abs(recentMove) / 2 * 0.2);

        return Optional.of(new StrategySignal(
                current.timestamp(), StrategyType.REVERSAL_BUY, optionType,
                targetStrike, entryPrice, current.spot(),
                String.format("Reversal %s: RSI=%.1f, move=%.2f%%, fading extreme",
                        oversold ? "OVERSOLD→CE" : "OVERBOUGHT→PE", rsi, recentMove),
                confidence, iv, getOptionDelta(current, targetStrike, optionType),
                getOptionOI(current, targetStrike, optionType),
                getOptionVolume(current, targetStrike, optionType)
        ));
    }

    /**
     * Calculate RSI from spot price changes across snapshots.
     */
    private double calculateRsi(ChainSnapshot current, List<ChainSnapshot> history) {
        double avgGain = 0;
        double avgLoss = 0;

        // First, calculate changes
        double prevSpot = history.get(RSI_PERIOD - 1).spot();
        for (int i = RSI_PERIOD - 2; i >= 0; i--) {
            double currentSpot = history.get(i).spot();
            double change = currentSpot - prevSpot;
            if (change > 0) avgGain += change;
            else avgLoss += Math.abs(change);
            prevSpot = currentSpot;
        }

        // Include current snapshot
        double lastChange = current.spot() - history.get(0).spot();
        if (lastChange > 0) avgGain += lastChange;
        else avgLoss += Math.abs(lastChange);

        avgGain /= RSI_PERIOD;
        avgLoss /= RSI_PERIOD;

        if (avgLoss == 0) return 100;
        double rs = avgGain / avgLoss;
        return 100 - (100 / (1 + rs));
    }
}
