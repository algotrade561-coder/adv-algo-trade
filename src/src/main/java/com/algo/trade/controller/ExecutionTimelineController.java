package com.algo.trade.controller;

import com.algo.trade.persistence.OrderEntity;
import com.algo.trade.persistence.OrderRepository;
import com.algo.trade.persistence.TradeEntity;
import com.algo.trade.persistence.TradeRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Execution Timeline Controller — provides order-level timeline view for the dashboard.
 *
 * Returns chronological list of all orders placed today with:
 * - Timestamp, symbol, side, quantity, price, status
 * - Strategy attribution
 * - Fill latency (signal → order → fill)
 */
@RestController
@RequestMapping("/timeline")
public class ExecutionTimelineController {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final OrderRepository orderRepository;
    private final TradeRepository tradeRepository;

    public ExecutionTimelineController(OrderRepository orderRepository, TradeRepository tradeRepository) {
        this.orderRepository = orderRepository;
        this.tradeRepository = tradeRepository;
    }

    /**
     * GET /timeline/today — all orders placed today, chronological
     */
    @GetMapping("/today")
    public ResponseEntity<?> getTodayTimeline() {
        Instant todayStart = LocalDate.now(IST).atStartOfDay(IST).toInstant();

        List<OrderEntity> orders = orderRepository.findAll().stream()
                .filter(o -> o.getOrderPlacedAt() != null && o.getOrderPlacedAt().isAfter(todayStart))
                .sorted(Comparator.comparing(OrderEntity::getOrderPlacedAt))
                .toList();

        List<Map<String, Object>> timeline = orders.stream().map(o -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("time", o.getOrderPlacedAt().atZone(IST).toLocalTime().toString());
            entry.put("symbol", o.getInstrumentKey());
            entry.put("side", o.getSide());
            entry.put("quantity", o.getRequestedQuantity());
            entry.put("price", o.getAverageFillPrice());
            entry.put("status", o.getStatus());
            entry.put("strategy", o.getStrategyType());
            entry.put("orderId", o.getBrokerOrderId());
            return entry;
        }).collect(Collectors.toList());

        return ResponseEntity.ok(Map.of(
                "date", LocalDate.now(IST).toString(),
                "totalOrders", timeline.size(),
                "timeline", timeline
        ));
    }

    /**
     * GET /timeline/trades — all completed trades today with entry/exit pairs
     */
    @GetMapping("/trades")
    public ResponseEntity<?> getTodayTrades() {
        Instant todayStart = LocalDate.now(IST).atStartOfDay(IST).toInstant();

        List<TradeEntity> trades = tradeRepository.findAll().stream()
                .filter(t -> t.getEntryTime() != null && t.getEntryTime().isAfter(todayStart))
                .sorted(Comparator.comparing(TradeEntity::getEntryTime))
                .toList();

        List<Map<String, Object>> tradeSummaries = trades.stream().map(t -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("entryTime", t.getEntryTime() != null ? t.getEntryTime().atZone(IST).toLocalTime().toString() : null);
            entry.put("exitTime", t.getExitTime() != null ? t.getExitTime().atZone(IST).toLocalTime().toString() : null);
            entry.put("symbol", t.getInstrumentKey());
            entry.put("strategy", t.getStrategyType());
            entry.put("optionType", t.getOptionType());
            entry.put("quantity", t.getQuantity());
            entry.put("entryPrice", t.getEntryPrice());
            entry.put("exitPrice", t.getExitPrice());
            entry.put("pnl", t.getRealizedPnl());
            entry.put("exitReason", t.getExitReason());
            entry.put("holdMinutes", t.getEntryTime() != null && t.getExitTime() != null
                    ? java.time.Duration.between(t.getEntryTime(), t.getExitTime()).toMinutes() : null);
            return entry;
        }).collect(Collectors.toList());

        double totalPnl = trades.stream()
                .filter(t -> t.getRealizedPnl() != null)
                .mapToDouble(t -> t.getRealizedPnl().doubleValue())
                .sum();

        return ResponseEntity.ok(Map.of(
                "date", LocalDate.now(IST).toString(),
                "totalTrades", tradeSummaries.size(),
                "totalPnl", totalPnl,
                "trades", tradeSummaries
        ));
    }
}
