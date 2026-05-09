package com.algo.trade.monitoring;

import com.algo.trade.domain.*;
import com.algo.trade.persistence.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Order Audit Service — traces every order from signal to fill/rejection.
 *
 * Provides:
 *   - Full order lifecycle: signal → risk check → order placed → fill/reject → trade open → exit → trade closed
 *   - Search by tradeId, instrumentKey, strategyType, date range
 *   - Failure analysis: which stage failed and why
 */
@Service
public class OrderAuditService {

    private static final Logger log = LoggerFactory.getLogger(OrderAuditService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final TradeRepository tradeRepository;
    private final OrderRepository orderRepository;
    private final StrategyDecisionRepository decisionRepository;
    private final ErrorEventRepository errorEventRepository;

    public OrderAuditService(TradeRepository tradeRepository,
                              OrderRepository orderRepository,
                              StrategyDecisionRepository decisionRepository,
                              ErrorEventRepository errorEventRepository) {
        this.tradeRepository = tradeRepository;
        this.orderRepository = orderRepository;
        this.decisionRepository = decisionRepository;
        this.errorEventRepository = errorEventRepository;
    }

    /**
     * Get full audit trail for a specific trade.
     */
    public Map<String, Object> auditByTradeId(String tradeId) {
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("tradeId", tradeId);

        // Trade entity
        tradeRepository.findById(tradeId).ifPresent(trade -> {
            audit.put("trade", tradeToMap(trade));

            // Find matching orders
            List<OrderEntity> orders = orderRepository.findAll().stream()
                    .filter(o -> o.getInstrumentKey() != null && o.getInstrumentKey().equals(trade.getInstrumentKey()))
                    .sorted(Comparator.comparing(o -> o.getUpdatedAt() != null ? o.getUpdatedAt() : Instant.MIN))
                    .toList();
            audit.put("orders", orders.stream().map(this::orderToMap).toList());

            // Find matching decisions
            List<StrategyDecisionEntity> decisions = decisionRepository
                    .findBySelectedInstrumentKeyAndTimestampBetween(
                            trade.getInstrumentKey(),
                            trade.getEntryTime() != null ? trade.getEntryTime().minusSeconds(300) : Instant.now().minusSeconds(3600),
                            trade.getEntryTime() != null ? trade.getEntryTime().plusSeconds(60) : Instant.now());
            audit.put("decisions", decisions.stream().map(this::decisionToMap).toList());

            // Build lifecycle timeline
            audit.put("lifecycle", buildLifecycle(trade, orders, decisions));
        });

        if (!audit.containsKey("trade")) {
            audit.put("error", "Trade not found: " + tradeId);
        }
        return audit;
    }

    /**
     * Search orders/trades by instrument key.
     */
    public Map<String, Object> searchByInstrument(String instrumentKey, String period) {
        Instant[] range = periodToRange(period);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("instrumentKey", instrumentKey);
        result.put("period", period);

        List<TradeEntity> trades = tradeRepository.findByEntryTimeBetween(range[0], range[1]).stream()
                .filter(t -> instrumentKey.equals(t.getInstrumentKey()))
                .toList();
        result.put("trades", trades.stream().map(this::tradeToMap).toList());

        List<OrderEntity> orders = orderRepository.findAll().stream()
                .filter(o -> instrumentKey.equals(o.getInstrumentKey()))
                .filter(o -> o.getUpdatedAt() != null && o.getUpdatedAt().isAfter(range[0]))
                .toList();
        result.put("orders", orders.stream().map(this::orderToMap).toList());

        return result;
    }

    /**
     * Search by strategy type.
     */
    public Map<String, Object> searchByStrategy(String strategyType, String period) {
        Instant[] range = periodToRange(period);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("strategyType", strategyType);

        List<TradeEntity> trades = tradeRepository.findByEntryTimeBetween(range[0], range[1]).stream()
                .filter(t -> strategyType.equals(t.getStrategyType()))
                .toList();
        result.put("trades", trades.stream().map(this::tradeToMap).toList());
        result.put("totalTrades", trades.size());
        result.put("openTrades", trades.stream().filter(t -> t.getStatus() == TradeStatus.OPEN).count());
        result.put("closedTrades", trades.stream().filter(t -> t.getStatus() == TradeStatus.CLOSED).count());

        return result;
    }

    /**
     * Get all failures today — orders that were rejected, failed, or had errors.
     */
    public Map<String, Object> failures(String period) {
        Instant[] range = periodToRange(period);
        Map<String, Object> result = new LinkedHashMap<>();

        // Rejected/failed orders
        List<OrderEntity> failedOrders = orderRepository.findAll().stream()
                .filter(o -> o.getUpdatedAt() != null && o.getUpdatedAt().isAfter(range[0]))
                .filter(o -> o.getStatus() == OrderStatus.REJECTED || o.getStatus() == OrderStatus.CANCELLED)
                .sorted(Comparator.comparing(o -> o.getUpdatedAt() != null ? o.getUpdatedAt() : Instant.MIN, Comparator.reverseOrder()))
                .toList();
        result.put("failedOrders", failedOrders.stream().map(this::orderToMap).toList());

        // Error events
        List<ErrorEventEntity> errors = errorEventRepository.findByTimestampAfter(range[0]);
        result.put("errorEvents", errors.stream().map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("timestamp", e.getTimestamp());
            m.put("source", e.getComponent());
            m.put("message", e.getMessage());
            return m;
        }).toList());

        // Decisions that were blocked
        var blockedDecisions = decisionRepository.findFilteredSignals(
                List.of("BUY_CE", "BUY_PE"), range[0], range[1],
                "", "", "", "",
                org.springframework.data.domain.PageRequest.of(0, 100));
        List<Map<String, Object>> blocked = blockedDecisions.getContent().stream()
                .filter(d -> d.getExecutionStage() != null && !d.getExecutionStage().equals("ORDER_FILLED"))
                .map(this::decisionToMap)
                .toList();
        result.put("blockedEntries", blocked);

        result.put("summary", Map.of(
                "failedOrders", failedOrders.size(),
                "errorEvents", errors.size(),
                "blockedEntries", blocked.size()
        ));

        return result;
    }

    /**
     * Get available trade IDs and instrument keys for autocomplete dropdowns.
     */
    public Map<String, Object> lookupValues(String period) {
        Instant[] range = periodToRange(period);
        List<TradeEntity> trades = tradeRepository.findByEntryTimeBetween(range[0], range[1]);

        List<String> tradeIds = trades.stream()
                .map(TradeEntity::getTradeId)
                .distinct()
                .sorted()
                .toList();

        List<String> instrumentKeys = trades.stream()
                .map(TradeEntity::getInstrumentKey)
                .filter(k -> k != null && !k.isBlank())
                .distinct()
                .sorted()
                .toList();

        List<String> strategyTypes = trades.stream()
                .map(TradeEntity::getStrategyType)
                .filter(s -> s != null && !s.isBlank())
                .distinct()
                .sorted()
                .toList();

        return Map.of(
                "tradeIds", tradeIds,
                "instrumentKeys", instrumentKeys,
                "strategyTypes", strategyTypes
        );
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private List<Map<String, Object>> buildLifecycle(TradeEntity trade, List<OrderEntity> orders,
                                                      List<StrategyDecisionEntity> decisions) {
        List<Map<String, Object>> timeline = new ArrayList<>();

        // Signal generated
        for (var d : decisions) {
            timeline.add(Map.of(
                    "stage", "SIGNAL",
                    "time", d.getTimestamp() != null ? d.getTimestamp().toString() : "",
                    "detail", d.getSignalType() + " | score=" + d.getConfidenceScore() + " | " + d.getStrategyType(),
                    "status", d.getExecutionStage() != null ? d.getExecutionStage() : "UNKNOWN"
            ));
        }

        // Order placed
        for (var o : orders) {
            timeline.add(Map.of(
                    "stage", "ORDER_" + o.getSide(),
                    "time", o.getOrderPlacedAt() != null ? o.getOrderPlacedAt().toString() : "",
                    "detail", o.getClientOrderId() + " | " + o.getStatus() + " | qty=" + o.getFilledQuantity()
                            + " | price=" + (o.getAverageFillPrice() != null ? o.getAverageFillPrice() : "pending"),
                    "status", o.getStatus().name()
            ));
        }

        // Trade entry
        timeline.add(Map.of(
                "stage", "TRADE_ENTRY",
                "time", trade.getEntryTime() != null ? trade.getEntryTime().toString() : "",
                "detail", "entry=" + trade.getEntryPrice() + " | qty=" + trade.getQuantity()
                        + " | strategy=" + trade.getStrategyType(),
                "status", "OPEN"
        ));

        // Trade exit (if closed)
        if (trade.getStatus() == TradeStatus.CLOSED) {
            timeline.add(Map.of(
                    "stage", "TRADE_EXIT",
                    "time", trade.getExitTime() != null ? trade.getExitTime().toString() : "",
                    "detail", "exit=" + trade.getExitPrice() + " | pnl=" + trade.getRealizedPnl()
                            + " | reason=" + trade.getExitReason(),
                    "status", "CLOSED"
            ));
        }

        // Sort by time
        timeline.sort(Comparator.comparing(m -> String.valueOf(m.get("time"))));
        return timeline;
    }

    private Map<String, Object> tradeToMap(TradeEntity t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tradeId", t.getTradeId());
        m.put("status", t.getStatus());
        m.put("instrumentKey", t.getInstrumentKey());
        m.put("underlying", t.getUnderlying());
        m.put("optionType", t.getOptionType());
        m.put("strategyType", t.getStrategyType());
        m.put("entryPrice", t.getEntryPrice());
        m.put("exitPrice", t.getExitPrice());
        m.put("quantity", t.getQuantity());
        m.put("realizedPnl", t.getRealizedPnl());
        m.put("bookedPnl", t.getBookedPnl());
        m.put("entryTime", t.getEntryTime());
        m.put("exitTime", t.getExitTime());
        m.put("entryReason", t.getEntryReason());
        m.put("exitReason", t.getExitReason());
        m.put("peakPrice", t.getPeakPrice());
        m.put("trailingStopPrice", t.getTrailingStopPrice());
        m.put("paper", t.isPaperTrade());
        return m;
    }

    private Map<String, Object> orderToMap(OrderEntity o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("clientOrderId", o.getClientOrderId());
        m.put("brokerOrderId", o.getBrokerOrderId());
        m.put("instrumentKey", o.getInstrumentKey());
        m.put("side", o.getSide());
        m.put("status", o.getStatus());
        m.put("requestedQuantity", o.getRequestedQuantity());
        m.put("filledQuantity", o.getFilledQuantity());
        m.put("averageFillPrice", o.getAverageFillPrice());
        m.put("rejectionReason", o.getRejectionReason());
        m.put("strategyType", o.getStrategyType());
        m.put("orderPlacedAt", o.getOrderPlacedAt());
        m.put("updatedAt", o.getUpdatedAt());
        m.put("slippage", o.getSlippage());
        return m;
    }

    private Map<String, Object> decisionToMap(StrategyDecisionEntity d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getId());
        m.put("timestamp", d.getTimestamp());
        m.put("underlying", d.getUnderlying());
        m.put("signalType", d.getSignalType());
        m.put("strategyType", d.getStrategyType());
        m.put("confidenceScore", d.getConfidenceScore());
        m.put("optionPrice", d.getOptionPrice());
        m.put("selectedInstrumentKey", d.getSelectedInstrumentKey());
        m.put("executionStage", d.getExecutionStage());
        m.put("executionReason", d.getExecutionReason());
        m.put("paperTrade", d.isPaperTrade());
        return m;
    }

    private Instant[] periodToRange(String period) {
        LocalDate today = LocalDate.now(IST);
        return switch (period != null ? period.toUpperCase() : "TODAY") {
            case "YESTERDAY" -> new Instant[]{today.minusDays(1).atStartOfDay(IST).toInstant(), today.atStartOfDay(IST).toInstant()};
            case "LAST7" -> new Instant[]{today.minusDays(6).atStartOfDay(IST).toInstant(), Instant.now()};
            case "LAST30" -> new Instant[]{today.minusDays(29).atStartOfDay(IST).toInstant(), Instant.now()};
            default -> new Instant[]{today.atStartOfDay(IST).toInstant(), Instant.now()};
        };
    }
}
