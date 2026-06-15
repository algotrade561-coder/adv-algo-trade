package com.algo.trade.monitoring;

import com.algo.trade.broker.BrokerClient;
import com.algo.trade.domain.OrderResponse;
import com.algo.trade.persistence.OrderEntity;
import com.algo.trade.persistence.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Slippage Tracker — measures execution quality by comparing intended vs actual fill prices.
 *
 * Tracks:
 * - Per-trade slippage (intended LIMIT price vs actual fill price)
 * - Per-strategy average slippage
 * - Per-time-window slippage (morning vs midday vs afternoon)
 * - Total daily slippage cost in ₹
 *
 * Provides recommended LIMIT buffer based on historical slippage data.
 */
@Component
public class SlippageTracker {

    private static final Logger log = LoggerFactory.getLogger(SlippageTracker.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final OrderRepository orderRepository;
    private final BrokerClient brokerClient;

    private final Map<String, SlippageRecord> slippageRecords = new ConcurrentHashMap<>();
    private final Map<String, List<Double>> strategySlippage = new ConcurrentHashMap<>();
    private final Map<Integer, List<Double>> hourlySlippage = new ConcurrentHashMap<>();

    public record SlippageRecord(
            String orderId,
            String symbol,
            String strategy,
            String side,
            double intendedPrice,
            double actualFillPrice,
            double slippagePercent,
            double slippageCost,
            int quantity,
            int hour
    ) {}

    public SlippageTracker(OrderRepository orderRepository, BrokerClient brokerClient) {
        this.orderRepository = orderRepository;
        this.brokerClient = brokerClient;
    }

    /**
     * Record slippage for a completed order.
     * Called by ExecutionEngine when an order fill is confirmed.
     */
    public void recordFill(String orderId, String symbol, String strategyType,
                           String side, BigDecimal intendedPrice, BigDecimal fillPrice, int quantity) {
        if (intendedPrice == null || fillPrice == null || intendedPrice.signum() <= 0) return;
        if (slippageRecords.containsKey(orderId)) return;

        boolean isBuy = "BUY".equalsIgnoreCase(side);
        double intended = intendedPrice.doubleValue();
        double filled = fillPrice.doubleValue();

        double slippagePct;
        if (isBuy) {
            slippagePct = ((filled - intended) / intended) * 100;
        } else {
            slippagePct = ((intended - filled) / intended) * 100;
        }

        double slippageCost = Math.abs(filled - intended) * quantity;
        int hour = LocalTime.now(IST).getHour();

        SlippageRecord record = new SlippageRecord(
                orderId, symbol, strategyType, side, intended, filled,
                slippagePct, slippageCost, quantity, hour);

        slippageRecords.put(orderId, record);
        strategySlippage.computeIfAbsent(strategyType, k -> new ArrayList<>()).add(slippagePct);
        hourlySlippage.computeIfAbsent(hour, k -> new ArrayList<>()).add(slippagePct);

        if (Math.abs(slippagePct) > 1.0) {
            log.info("[Slippage] {} {} {}: intended=₹{} filled=₹{} slip={}%",
                    strategyType, side, symbol,
                    String.format("%.2f", intended), String.format("%.2f", filled),
                    String.format("%.2f", slippagePct));
        }
    }

    /**
     * Get recommended LIMIT price buffer for the current hour.
     * Based on historical slippage data.
     * Returns a multiplier (e.g., 1.005 for BUY = 0.5% above LTP).
     */
    public double getRecommendedBuffer(boolean isBuy) {
        int currentHour = LocalTime.now(IST).getHour();
        List<Double> hourData = hourlySlippage.get(currentHour);

        double baseBuffer = 1.002;

        if (hourData == null || hourData.size() < 3) {
            if (currentHour == 9) baseBuffer = 1.005;
            else if (currentHour >= 14) baseBuffer = 1.003;
            return baseBuffer;
        }

        double avgAbsSlippage = hourData.stream()
                .mapToDouble(Math::abs)
                .average()
                .orElse(0.2);

        double dynamicBuffer = 0.2 + Math.min(avgAbsSlippage, 0.6);
        return 1.0 + (dynamicBuffer / 100.0);
    }

    /**
     * Get worst-case slippage for a strategy (95th percentile).
     */
    public double getWorstCaseSlippage(String strategy) {
        List<Double> data = strategySlippage.get(strategy);
        if (data == null || data.isEmpty()) return 0.5;
        List<Double> sorted = new ArrayList<>(data);
        Collections.sort(sorted);
        int p95Index = (int) (sorted.size() * 0.95);
        return sorted.get(Math.min(p95Index, sorted.size() - 1));
    }

    /**
     * Get slippage summary for dashboard/monitoring.
     */
    public Map<String, Object> getSlippageSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();

        double totalSlippageCost = slippageRecords.values().stream()
                .mapToDouble(r -> Math.abs(r.slippageCost)).sum();
        double avgSlippage = slippageRecords.values().stream()
                .mapToDouble(SlippageRecord::slippagePercent).average().orElse(0);

        summary.put("totalTrades", slippageRecords.size());
        summary.put("totalSlippageCost", Math.round(totalSlippageCost));
        summary.put("avgSlippagePercent", Math.round(avgSlippage * 100) / 100.0);

        Map<String, Map<String, Object>> byStrategy = new LinkedHashMap<>();
        strategySlippage.forEach((strategy, slippages) -> {
            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put("trades", slippages.size());
            stats.put("avgSlippage", Math.round(slippages.stream().mapToDouble(d -> d).average().orElse(0) * 100) / 100.0);
            byStrategy.put(strategy, stats);
        });
        summary.put("byStrategy", byStrategy);

        return summary;
    }

    @Scheduled(cron = "0 0 0 * * *")
    public void resetDaily() {
        slippageRecords.clear();
        strategySlippage.clear();
        hourlySlippage.clear();
    }
}
