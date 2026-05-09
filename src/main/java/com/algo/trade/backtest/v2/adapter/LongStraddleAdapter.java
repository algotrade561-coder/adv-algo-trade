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
 * Long Straddle adapter: Buy ATM CE + PE.
 * Profits from big moves in either direction when IV is low.
 * For V2 backtesting, we simulate as a single-leg CE or PE based on direction.
 */
@Component
public class LongStraddleAdapter extends AbstractStrategyAdapter {

    private static final double MAX_IV_FOR_ENTRY = 25.0; // Buy when IV is cheap
    private static final double MIN_COMBINED_PREMIUM_RATIO = 0.02; // Min premium as % of spot
    private static final int CONSOLIDATION_LOOKBACK = 12; // 1 hour
    private static final double MAX_RANGE_PERCENT = 0.3; // Tight range = expecting breakout

    @Override
    public StrategyType strategyType() {
        return StrategyType.LONG_STRADDLE;
    }

    @Override
    public Optional<StrategySignal> evaluateEntry(ChainSnapshot current, List<ChainSnapshot> history,
                                                   StrategyConfig config) {
        if (history.size() < CONSOLIDATION_LOOKBACK) return Optional.empty();

        int atmStrike = current.atmStrike();
        Optional<ChainSnapshot.StrikeData> atmData = getAtmStrike(current);
        if (atmData.isEmpty()) return Optional.empty();

        // IV filter — buy straddle when IV is low (expecting expansion)
        double ceIV = atmData.get().ceIV();
        double peIV = atmData.get().peIV();
        double avgIV = (ceIV + peIV) / 2;
        double maxIv = config.getMaxIvRankForBuying() != null
                ? config.getMaxIvRankForBuying().doubleValue() : MAX_IV_FOR_ENTRY;
        if (avgIV > maxIv) return Optional.empty();

        // Consolidation check — market should be in tight range (expecting breakout)
        double high = history.stream().limit(CONSOLIDATION_LOOKBACK)
                .mapToDouble(ChainSnapshot::spot).max().orElse(current.spot());
        double low = history.stream().limit(CONSOLIDATION_LOOKBACK)
                .mapToDouble(ChainSnapshot::spot).min().orElse(current.spot());
        double rangePercent = ((high - low) / current.spot()) * 100;

        if (rangePercent > MAX_RANGE_PERCENT) return Optional.empty(); // Not consolidated enough

        // Combined premium check
        double cePremium = atmData.get().ceLTP();
        double pePremium = atmData.get().peLTP();
        double combinedPremium = cePremium + pePremium;
        double premiumRatio = combinedPremium / current.spot();

        if (premiumRatio < MIN_COMBINED_PREMIUM_RATIO) return Optional.empty();

        // For single-leg simulation, pick the cheaper side (better risk/reward)
        OptionType optionType = cePremium <= pePremium ? OptionType.CE : OptionType.PE;
        double entryPrice = optionType == OptionType.CE ? cePremium : pePremium;

        double confidence = Math.min(1.0, 0.4 + (MAX_RANGE_PERCENT - rangePercent) * 3
                + (maxIv - avgIV) / maxIv * 0.3);

        return Optional.of(new StrategySignal(
                current.timestamp(), StrategyType.LONG_STRADDLE, optionType,
                atmStrike, entryPrice, current.spot(),
                String.format("Long straddle: IV=%.1f, range=%.2f%%, combined=%.1f",
                        avgIV, rangePercent, combinedPremium),
                confidence, avgIV, getOptionDelta(current, atmStrike, optionType),
                getOptionOI(current, atmStrike, optionType),
                getOptionVolume(current, atmStrike, optionType)
        ));
    }
}
