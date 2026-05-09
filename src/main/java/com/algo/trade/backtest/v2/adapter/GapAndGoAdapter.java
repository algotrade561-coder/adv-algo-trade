package com.algo.trade.backtest.v2.adapter;

import com.algo.trade.backtest.v2.StrategySignal;
import com.algo.trade.data.ChainSnapshot;
import com.algo.trade.domain.OptionType;
import com.algo.trade.strategy.StrategyConfig;
import com.algo.trade.strategy.StrategyType;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

/**
 * Gap & Go adapter: Buys CE/PE in the first 30 min when the first session candle
 * shows a strong directional gap — momentum continuation.
 */
@Component
public class GapAndGoAdapter extends AbstractStrategyAdapter {

    private static final LocalTime GAP_WINDOW_START = LocalTime.of(9, 20);
    private static final LocalTime GAP_WINDOW_END = LocalTime.of(9, 50);
    private static final double MIN_GAP_PERCENT = 0.3; // 0.3% gap from previous close
    private static final double MIN_CONTINUATION_PERCENT = 0.1; // Continuation after gap

    @Override
    public StrategyType strategyType() {
        return StrategyType.GAP_AND_GO;
    }

    @Override
    public Optional<StrategySignal> evaluateEntry(ChainSnapshot current, List<ChainSnapshot> history,
                                                   StrategyConfig config) {
        // Only trade in the first 30 minutes
        LocalTime marketTime = getMarketTime(current);
        if (marketTime.isBefore(GAP_WINDOW_START) || marketTime.isAfter(GAP_WINDOW_END)) {
            return Optional.empty();
        }

        if (history.isEmpty()) return Optional.empty();

        // Detect gap: compare first snapshot of today vs last snapshot of previous day
        // In practice, we compare current vs the oldest available snapshot in history
        ChainSnapshot reference = history.get(history.size() - 1);

        // Only use reference if it's from a different day (previous session)
        boolean isDifferentDay = !current.timestamp().atZone(IST).toLocalDate()
                .equals(reference.timestamp().atZone(IST).toLocalDate());

        double gapPercent;
        if (isDifferentDay) {
            gapPercent = spotChangePercent(current, reference);
        } else if (history.size() >= 2) {
            // Use first snapshot of today as reference
            ChainSnapshot firstToday = history.get(history.size() - 1);
            gapPercent = spotChangePercent(current, firstToday);
        } else {
            return Optional.empty();
        }

        if (Math.abs(gapPercent) < MIN_GAP_PERCENT) return Optional.empty();

        // Continuation check — price should continue in gap direction
        if (history.size() >= 2) {
            double recentMove = spotChangePercent(current, history.get(0));
            boolean gapUp = gapPercent > 0;
            if (gapUp && recentMove < MIN_CONTINUATION_PERCENT) return Optional.empty();
            if (!gapUp && recentMove > -MIN_CONTINUATION_PERCENT) return Optional.empty();
        }

        boolean bullish = gapPercent > 0;
        OptionType optionType = bullish ? OptionType.CE : OptionType.PE;
        int targetStrike = current.atmStrike();

        double entryPrice = getOptionPrice(current, targetStrike, optionType);
        if (entryPrice <= 0) return Optional.empty();

        // Volume should be high in gap direction
        long volume = getOptionVolume(current, targetStrike, optionType);
        if (volume < 500) return Optional.empty();

        double iv = getOptionIV(current, targetStrike, optionType);
        double confidence = Math.min(1.0, 0.3 + Math.abs(gapPercent) * 1.5);

        return Optional.of(new StrategySignal(
                current.timestamp(), StrategyType.GAP_AND_GO, optionType,
                targetStrike, entryPrice, current.spot(),
                String.format("Gap & Go %s: gap=%.2f%%, continuation confirmed",
                        bullish ? "BULL" : "BEAR", gapPercent),
                confidence, iv, getOptionDelta(current, targetStrike, optionType),
                getOptionOI(current, targetStrike, optionType), volume
        ));
    }
}
