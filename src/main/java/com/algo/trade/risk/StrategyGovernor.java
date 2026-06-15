package com.algo.trade.risk;

import com.algo.trade.persistence.TradeEntity;
import com.algo.trade.persistence.TradeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Strategy Governor — per-strategy performance monitoring and governance.
 *
 * Tracks rolling performance (win rate, avg P&L) per strategy and adjusts:
 * - TOP performers (>60% win rate): allowed more lots (2×)
 * - GOOD performers (40-60%): normal lots (1×)
 * - WEAK performers (<40% win rate): reduced lots (0.5×) or disabled
 *
 * Re-evaluated every 60 seconds during market hours.
 */
@Component
public class StrategyGovernor {

    private static final Logger log = LoggerFactory.getLogger(StrategyGovernor.class);

    private final TradeRepository tradeRepository;

    public enum PerformanceRank { TOP, GOOD, WEAK, BLOCKED }

    public record GovernanceAction(
            PerformanceRank rank,
            double lotMultiplier,
            String reason
    ) {}

    // Cached governance decisions per strategy
    private final Map<String, GovernanceAction> governanceCache = new ConcurrentHashMap<>();

    public StrategyGovernor(TradeRepository tradeRepository) {
        this.tradeRepository = tradeRepository;
    }

    /**
     * Get governance action for a strategy — determines lot multiplier.
     */
    public GovernanceAction getGovernance(String strategyType) {
        return governanceCache.getOrDefault(strategyType,
                new GovernanceAction(PerformanceRank.GOOD, 1.0, "No data — default GOOD"));
    }

    /**
     * Get lot multiplier for a strategy (1.0 = normal, 2.0 = double, 0.5 = halved).
     */
    public double getLotMultiplier(String strategyType) {
        return getGovernance(strategyType).lotMultiplier();
    }

    /**
     * Recalculate governance for all strategies every 60 seconds.
     */
    @Scheduled(fixedDelay = 60_000)
    public void recalculate() {
        try {
            Instant lookback = Instant.now().minus(7, ChronoUnit.DAYS);
            List<TradeEntity> recentTrades = tradeRepository.findAll().stream()
                    .filter(t -> t.getEntryTime() != null && t.getEntryTime().isAfter(lookback))
                    .filter(t -> t.getRealizedPnl() != null)
                    .toList();

            if (recentTrades.isEmpty()) return;

            // Group by strategy type
            Map<String, List<TradeEntity>> byStrategy = new HashMap<>();
            for (TradeEntity trade : recentTrades) {
                String type = trade.getStrategyType() != null ? trade.getStrategyType() : "UNKNOWN";
                byStrategy.computeIfAbsent(type, k -> new ArrayList<>()).add(trade);
            }

            for (Map.Entry<String, List<TradeEntity>> entry : byStrategy.entrySet()) {
                String strategyType = entry.getKey();
                List<TradeEntity> trades = entry.getValue();

                if (trades.size() < 3) {
                    governanceCache.put(strategyType,
                            new GovernanceAction(PerformanceRank.GOOD, 1.0, "Insufficient data (<3 trades)"));
                    continue;
                }

                long wins = trades.stream()
                        .filter(t -> t.getRealizedPnl().compareTo(BigDecimal.ZERO) > 0)
                        .count();
                double winRate = (double) wins / trades.size() * 100;

                double avgPnl = trades.stream()
                        .mapToDouble(t -> t.getRealizedPnl().doubleValue())
                        .average()
                        .orElse(0);

                GovernanceAction action;
                if (winRate >= 60 && avgPnl > 0) {
                    action = new GovernanceAction(PerformanceRank.TOP, 2.0,
                            String.format("WIN=%.0f%% avgPnl=₹%.0f — TOP performer", winRate, avgPnl));
                } else if (winRate >= 40) {
                    action = new GovernanceAction(PerformanceRank.GOOD, 1.0,
                            String.format("WIN=%.0f%% avgPnl=₹%.0f — GOOD", winRate, avgPnl));
                } else if (winRate >= 25) {
                    action = new GovernanceAction(PerformanceRank.WEAK, 0.5,
                            String.format("WIN=%.0f%% avgPnl=₹%.0f — WEAK, reduced lots", winRate, avgPnl));
                } else {
                    action = new GovernanceAction(PerformanceRank.BLOCKED, 0.0,
                            String.format("WIN=%.0f%% avgPnl=₹%.0f — BLOCKED, too many losses", winRate, avgPnl));
                }

                governanceCache.put(strategyType, action);
            }
        } catch (Exception e) {
            log.debug("[StrategyGovernor] Recalculation error: {}", e.getMessage());
        }
    }

    public Map<String, GovernanceAction> getAllGovernance() {
        return Map.copyOf(governanceCache);
    }
}
