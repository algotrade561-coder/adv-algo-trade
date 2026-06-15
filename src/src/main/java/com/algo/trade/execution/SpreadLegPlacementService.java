package com.algo.trade.execution;

import com.algo.trade.broker.BrokerClient;
import com.algo.trade.config.SpreadTradingProperties;
import com.algo.trade.domain.OrderRequest;
import com.algo.trade.domain.OrderResponse;
import com.algo.trade.domain.OrderSide;
import com.algo.trade.domain.OrderStatus;
import com.algo.trade.domain.OrderType;
import com.algo.trade.domain.ProductType;
import com.algo.trade.domain.Quote;
import com.algo.trade.domain.SpreadLeg;
import com.algo.trade.marketdata.MarketDataService;
import com.algo.trade.persistence.OrderEntity;
import com.algo.trade.persistence.OrderRepository;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Places spread legs with retry, optional fill confirmation, and order persistence.
 */
@Service
public class SpreadLegPlacementService {

    private static final Logger log = LoggerFactory.getLogger(SpreadLegPlacementService.class);
    private static final MathContext MC = MathContext.DECIMAL64;
    private static final double SPREAD_PROTECTION_PERCENT = 1.5;

    private final BrokerClient brokerClient;
    private final MarketDataService marketDataService;
    private final OrderRepository orderRepository;
    private final SpreadTradingProperties spreadProperties;
    private final OrderRateLimiter orderRateLimiter;
    private final com.algo.trade.monitoring.TradingMetrics tradingMetrics;

    public SpreadLegPlacementService(BrokerClient brokerClient,
                                     MarketDataService marketDataService,
                                     OrderRepository orderRepository,
                                     SpreadTradingProperties spreadProperties,
                                     OrderRateLimiter orderRateLimiter,
                                     com.algo.trade.monitoring.TradingMetrics tradingMetrics) {
        this.brokerClient = brokerClient;
        this.marketDataService = marketDataService;
        this.orderRepository = orderRepository;
        this.spreadProperties = spreadProperties;
        this.orderRateLimiter = orderRateLimiter;
        this.tradingMetrics = tradingMetrics;
    }

    public record PlacementResult(
            boolean success,
            SpreadLeg leg,
            Optional<BigDecimal> fillPrice,
            String reason,
            Optional<String> brokerOrderId
    ) {}

    public PlacementResult placeLeg(SpreadLeg leg, String groupId, String strategyTag, boolean waitForFill) {
        Optional<Quote> quoteOpt = marketDataService.quote(leg.instrumentKey());
        BigDecimal lastPrice = quoteOpt.map(Quote::lastPrice).orElse(BigDecimal.ZERO);
        if (lastPrice.signum() <= 0) {
            return new PlacementResult(false, leg, Optional.empty(), "No quote", Optional.empty());
        }

        BigDecimal protection = lastPrice.multiply(BigDecimal.valueOf(SPREAD_PROTECTION_PERCENT / 100), MC);
        BigDecimal limitPrice = leg.side() == OrderSide.BUY
                ? lastPrice.add(protection)
                : lastPrice.subtract(protection).max(BigDecimal.ONE);
        limitPrice = ExecutionEngine.roundToTick(limitPrice, leg.side());

        OrderRequest request = new OrderRequest(
                clientOrderId(groupId, leg),
                leg.instrumentKey(),
                leg.side(),
                OrderType.LIMIT,
                ProductType.MIS,
                leg.quantity(),
                Optional.of(limitPrice),
                strategyTag);

        int maxRetries = spreadProperties.spreadLegMaxRetries();
        RuntimeException lastError = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                if (attempt > 0 && hasOpenOrderFor(request.clientOrderId(), leg.instrumentKey())) {
                    return new PlacementResult(false, leg, Optional.empty(),
                            "Duplicate order suspected — not retrying", Optional.empty());
                }
                // Rate-limit: acquire token before broker call to stay within Kite's 10/sec limit
                if (!orderRateLimiter.acquire()) {
                    tradingMetrics.recordRateLimitExceeded();
                    return new PlacementResult(false, leg, Optional.empty(),
                            "Order rate limit exceeded — try again later", Optional.empty());
                }
                var timerSample = tradingMetrics.startOrderTimer();
                OrderResponse response = brokerClient.placeOrder(request);
                tradingMetrics.stopOrderTimer(timerSample);
                tradingMetrics.recordOrderPlaced();
                persistOrder(response, strategyTag);
                if (response.status() == OrderStatus.REJECTED) {
                    String reason = response.rejectionReason().orElse("Rejected");
                    if (attempt < maxRetries && isTransient(reason)) {
                        sleep(backoffMs(attempt));
                        continue;
                    }
                    return new PlacementResult(false, leg, Optional.empty(), reason, response.brokerOrderId());
                }
                if (!waitForFill || !spreadProperties.fillConfirmationEnabled()) {
                    return new PlacementResult(true, leg, Optional.of(limitPrice), "Order accepted",
                            response.brokerOrderId());
                }
                Optional<OrderResponse> filled = waitForFill(response, spreadProperties.legFillTimeout().toMillis());
                if (filled.isPresent() && filled.get().status() == OrderStatus.COMPLETE
                        && filled.get().filledQuantity() >= leg.quantity()) {
                    BigDecimal avg = filled.get().averageFillPrice().orElse(limitPrice);
                    updateOrderFilled(response.clientOrderId(), filled.get());
                    return new PlacementResult(true, leg, Optional.of(avg), "Filled", filled.get().brokerOrderId());
                }
                String reason = filled.map(o -> "Fill timeout status=" + o.status())
                        .orElse("Fill timeout — no status");
                if (attempt < maxRetries) {
                    sleep(backoffMs(attempt));
                    continue;
                }
                return new PlacementResult(false, leg, Optional.empty(), reason, response.brokerOrderId());
            } catch (RuntimeException ex) {
                lastError = ex;
                if (attempt >= maxRetries || !isTransient(ex.getMessage())) {
                    break;
                }
                sleep(backoffMs(attempt));
            }
        }
        String msg = lastError != null ? lastError.getMessage() : "Placement failed";
        return new PlacementResult(false, leg, Optional.empty(), msg, Optional.empty());
    }

    private Optional<OrderResponse> waitForFill(OrderResponse placed, long timeoutMs) {
        Optional<String> brokerId = placed.brokerOrderId();
        if (brokerId.isEmpty()) {
            return Optional.empty();
        }
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Optional<OrderResponse> latest = brokerClient.orderStatus(brokerId.get());
            if (latest.isPresent()) {
                OrderStatus status = latest.get().status();
                if (status == OrderStatus.COMPLETE) {
                    return latest;
                }
                if (status == OrderStatus.REJECTED || status == OrderStatus.CANCELLED) {
                    return latest;
                }
            }
            sleep(500);
        }
        return brokerClient.orderStatus(brokerId.get());
    }

    private boolean hasOpenOrderFor(String clientOrderId, String instrumentKey) {
        return brokerClient.orders().stream()
                .anyMatch(o -> instrumentKey.equals(o.instrumentKey())
                        && (o.status() == OrderStatus.OPEN || o.status() == OrderStatus.NEW)
                        && (clientOrderId.equals(o.clientOrderId())
                        || o.clientOrderId().startsWith("SPREAD-")));
    }

    private void persistOrder(OrderResponse order, String strategyTag) {
        OrderEntity entity = new OrderEntity(
                order.clientOrderId(),
                order.brokerOrderId().orElse(null),
                order.instrumentKey(),
                order.side().name(),
                order.status(),
                order.requestedQuantity(),
                order.filledQuantity(),
                order.averageFillPrice().orElse(null),
                order.rejectionReason().orElse(null),
                Instant.now());
        entity.setStrategyType(strategyTag);
        entity.setOrderPlacedAt(Instant.now());
        orderRepository.save(entity);
    }

    private void updateOrderFilled(String clientOrderId, OrderResponse filled) {
        orderRepository.findById(clientOrderId).ifPresent(entity -> {
            entity.setStatus(OrderStatus.COMPLETE);
            entity.setFilledQuantity(filled.filledQuantity());
            filled.averageFillPrice().ifPresent(entity::setAverageFillPrice);
            entity.setUpdatedAt(Instant.now());
            orderRepository.save(entity);
        });
    }

    private static String clientOrderId(String groupId, SpreadLeg leg) {
        // Use strike+side+optionType to avoid hash collisions between instruments
        return "SPREAD-" + groupId + "-" + leg.strike() + leg.side().name().charAt(0) + leg.optionType().name();
    }

    private static long backoffMs(int attempt) {
        return 500L * (1L << attempt);
    }

    private static boolean isTransient(String message) {
        if (message == null) {
            return false;
        }
        String m = message.toLowerCase();
        return m.contains("timeout") || m.contains("timed out") || m.contains("connection")
                || m.contains("socket") || m.contains("429") || m.contains("503");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
