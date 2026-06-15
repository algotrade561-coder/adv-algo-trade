package com.algo.trade.monitoring;

import com.algo.trade.persistence.TradeEntity;
import com.algo.trade.persistence.TradeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Strategy Attribution Service — per-strategy P&L bucketing and performance tracking.
 *
 * Provides:
 * - Daily P&L per strategy
 * - Win rate per strategy
 * - Average holding time
 * - Best/worst performing strategies
 *
 * Used by:
 * - Dashboard (strategy performance view)
 * - StrategyGovernor (lot multiplier decisions)
 * - Daily report (end-of-day summary)
 */
@Service
public class StrategyAttributionService {

    private static final Logger log = LoggerFactory.getLogger(StrategyAttributionService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final TradeRepository tradeRepository;

    public record StrategyPerformance(
            String strategyType,
            int totalTrades,
            int wins,
            int losses,
            double winRate,
            double totalPnl,
            double avgPnl,
            double maxWin,
            double maxLoss,
            double avgHoldMinutes
    ) {}

    private final Map<String, StrategyPerformance> performanceCache = new ConcurrentHashMap<>();

    public StrategyAttributionService(TradeRepository tradeRepository) {
        this.tradeRepository = tradeRepository;
    }

    /**
     * Get performance for a specific strategy.
     */
    public Optional<StrategyPerformance> getPerformance(String strategyType) {
        return Optional.ofNullable(performanceCache.get(strategyType));
    }

    /**
     * Get performance for all strategies.
     */
    public List<StrategyPerformance> getAllPerformance() {
        return new ArrayList<>(performanceCache.values());
    }

    /**
     * Get today's P&L per strategy.
     */
    public Map<String, Double> getTodayPnlByStrategy() {
        Instant todayStart = LocalDate.now(IST).atStartOfDay(IST).toInstant();
        return tradeRepository.findAll().stream()
                .filter(t -> t.getEntryTime() != null && t.getEntryTime().isAfter(todayStart))
                .filter(t -> t.getRealizedPnl() != null)
                .collect(Collectors.groupingBy(
                        t -> t.getStrategyType() != null ? t.getStrategyType() : "UNKNOWN",
                        Collectors.summingDouble(t -> t.getRealizedPnl().doubleValue())
                ));
    }

    /**
     * Recalculate performance metrics every 60 seconds.
     */
    @Scheduled(fixedDelay = 60_000)
    public void recalculate() {
        try {
            Instant lookback = Instant.now().minus(7, ChronoUnit.DAYS);
            List<TradeEntity> recentTrades = tradeRepository.findAll().stream()
                    .filter(t -> t.getEntryTime() != null && t.getEntryTime().isAfter(lookback))
                    .filter(t -> t.getRealizedPnl() != null)
                    .toList();

            Map<String, List<TradeEntity>> byStrategy = recentTrades.stream()
                    .collect(Collectors.groupingBy(
                            t -> t.getStrategyType() != null ? t.getStrategyType() : "UNKNOWN"));

            for (Map.Entry<String, List<TradeEntity>> entry : byStrategy.entrySet()) {
                String strategyType = entry.getKey();
                List<TradeEntity> trades = entry.getValue();

                int wins = 0, losses = 0;
                double totalPnl = 0, maxWin = 0, maxLoss = 0;

                for (TradeEntity t : trades) {
                    double pnl = t.getRealizedPnl().doubleValue();
                    totalPnl += pnl;
                    if (pnl > 0) { wins++; maxWin = Math.max(maxWin, pnl); }
                    else { losses++; maxLoss = Math.min(maxLoss, pnl); }
                }

                double winRate = trades.isEmpty() ? 0 : (double) wins / trades.size() * 100;
                double avgPnl = trades.isEmpty() ? 0 : totalPnl / trades.size();

                double avgHoldMinutes = trades.stream()
                        .filter(t -> t.getEntryTime() != null && t.getExitTime() != null)
                        .mapToLong(t -> ChronoUnit.MINUTES.between(t.getEntryTime(), t.getExitTime()))
                        .average()
                        .orElse(0);

                performanceCache.put(strategyType, new StrategyPerformance(
                        strategyType, trades.size(), wins, losses, winRate,
                        totalPnl, avgPnl, maxWin, maxLoss, avgHoldMinutes));
            }
        } catch (Exception e) {
            log.debug("[StrategyAttribution] Recalculation error: {}", e.getMessage());
        }
    }
}
