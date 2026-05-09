package com.algo.trade.backtest;

import com.algo.trade.domain.SpreadLeg;
import com.algo.trade.strategy.StrategyType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A single trade from a spread backtest — tracks multiple legs with per-leg
 * entry/exit prices and a combined P&amp;L across all legs.
 */
public record SpreadBacktestTrade(
        String tradeId,
        StrategyType strategyType,
        List<SpreadLeg> legs,
        Instant entryTime,
        Instant exitTime,
        Map<String, BigDecimal> entryPrices,
        Map<String, BigDecimal> exitPrices,
        BigDecimal combinedPnl,
        String entryReason,
        String exitReason
) {
    public SpreadBacktestTrade {
        legs = List.copyOf(legs == null ? List.of() : legs);
        entryPrices = Map.copyOf(entryPrices == null ? Map.of() : entryPrices);
        exitPrices = Map.copyOf(exitPrices == null ? Map.of() : exitPrices);
    }

    /**
     * Converts this spread trade to a unified {@link BacktestTrade} for metrics
     * computation via the existing {@code BacktestMetrics} pipeline.
     * <ul>
     *   <li>{@code instrumentKey} — strategy type name (e.g. "IRON_CONDOR")</li>
     *   <li>{@code entryPrice} — net debit/credit at entry (sum of signed leg prices)</li>
     *   <li>{@code exitPrice} — net debit/credit at exit</li>
     *   <li>{@code pnl} — combined P&amp;L across all legs</li>
     *   <li>{@code quantity} — total quantity across all legs</li>
     * </ul>
     */
    public BacktestTrade toBacktestTrade() {
        BigDecimal netEntry = netPrice(entryPrices);
        BigDecimal netExit = netPrice(exitPrices);
        int totalQuantity = legs.stream().mapToInt(SpreadLeg::quantity).sum();

        return new BacktestTrade(
                tradeId,
                strategyType.name(),
                entryTime,
                exitTime,
                totalQuantity,
                netEntry,
                netExit,
                combinedPnl,
                entryReason,
                exitReason
        );
    }

    /**
     * Sums all prices in the map to produce a net debit/credit value.
     */
    private static BigDecimal netPrice(Map<String, BigDecimal> prices) {
        return prices.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
