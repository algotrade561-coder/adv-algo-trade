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
 * Long Strangle adapter: Buy OTM CE + PE.
 * Cheaper than straddle, needs bigger move. Profits from large directional moves.
 * For V2 backtesting, simulates as single-leg based on eventual direction.
 */
@Component
public class LongStrangleAdapter extends AbstractStrategyAdapter {

    private static final double MAX_IV_FOR_ENTRY = 30.0;
    private static final int CONSOLIDATION_LOOKBACK = 18; // 1.5 hours
    private static final double MAX_RANGE_PERCENT = 0.4; // Tight range
    private static final int OTM_STRIKES = 2; // 2 strikes OTM

    @Override
    public StrategyType strategyType() {
        return StrategyType.LONG_STRANGLE;
    }

    @Override
    public Optional<StrategySignal> evaluateEntry(ChainSnapshot current, List<ChainSnapshot> history,
                                                   StrategyConfig config) {
        if (history.size() < CONSOLIDATION_LOOKBACK) return Optional.empty();

        int interval = getStrikeInterval(current.underlying());
        int otmStrikes = config.getOtmStrikes() > 0 ? config.getOtmStrikes() : OTM_STRIKES;
        int ceStrike = current.atmStrike() + (otmStrikes * interval); // OTM CE
        int peStrike = current.atmStrike() - (otmStrikes * interval); // OTM PE

        // IV filter — buy when IV is low
        double ceIV = getOptionIV(current, ceStrike, OptionType.CE);
        double peIV = getOptionIV(current, peStrike, OptionType.PE);
        double avgIV = (ceIV + peIV) / 2;
        double maxIv = config.getMaxIvRankForBuying() != null
                ? config.getMaxIvRankForBuying().doubleValue() : MAX_IV_FOR_ENTRY;
        if (avgIV > maxIv) return Optional.empty();

        // Consolidation check
        double high = history.stream().limit(CONSOLIDATION_LOOKBACK)
                .mapToDouble(ChainSnapshot::spot).max().orElse(current.spot());
        double low = history.stream().limit(CONSOLIDATION_LOOKBACK)
                .mapToDouble(ChainSnapshot::spot).min().orElse(current.spot());
        double rangePercent = ((high - low) / current.spot()) * 100;

        if (rangePercent > MAX_RANGE_PERCENT) return Optional.empty();

        // Check premiums are reasonable
        double cePremium = getOptionPrice(current, ceStrike, OptionType.CE);
        double pePremium = getOptionPrice(current, peStrike, OptionType.PE);
        if (cePremium <= 0 || pePremium <= 0) return Optional.empty();

        // Pick the cheaper leg for simulation (better risk/reward)
        OptionType optionType;
        int targetStrike;
        double entryPrice;
        if (cePremium <= pePremium) {
            optionType = OptionType.CE;
            targetStrike = ceStrike;
            entryPrice = cePremium;
        } else {
            optionType = OptionType.PE;
            targetStrike = peStrike;
            entryPrice = pePremium;
        }

        double confidence = Math.min(1.0, 0.35 + (MAX_RANGE_PERCENT - rangePercent) * 2
                + (maxIv - avgIV) / maxIv * 0.3);

        return Optional.of(new StrategySignal(
                current.timestamp(), StrategyType.LONG_STRANGLE, optionType,
                targetStrike, entryPrice, current.spot(),
                String.format("Long strangle: IV=%.1f, range=%.2f%%, CE@%d=%.1f, PE@%d=%.1f",
                        avgIV, rangePercent, ceStrike, cePremium, peStrike, pePremium),
                confidence, avgIV, getOptionDelta(current, targetStrike, optionType),
                getOptionOI(current, targetStrike, optionType),
                getOptionVolume(current, targetStrike, optionType)
        ));
    }
}
