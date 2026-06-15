package com.algo.trade.execution;

import com.algo.trade.broker.BrokerClient;
import com.algo.trade.config.SpreadTradingProperties;
import com.algo.trade.domain.OrderRequest;
import com.algo.trade.domain.OrderResponse;
import com.algo.trade.domain.OrderSide;
import com.algo.trade.domain.OrderStatus;
import com.algo.trade.domain.OrderType;
import com.algo.trade.domain.PositionGroupStatus;
import com.algo.trade.domain.ProductType;
import com.algo.trade.domain.SpreadLeg;
import com.algo.trade.persistence.PositionGroupEntity;
import com.algo.trade.persistence.PositionGroupRepository;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Places broker-side SL-M (Stop-Loss Market) orders on SELL legs of credit spreads
 * as a catastrophe floor. If the JVM crashes, these orders fire at the broker level
 * to cap tail risk.
 *
 * <p>Trigger price = entry credit per unit × {@code brokerSideSlMultiplier} (default 2×).
 * This matches the SHORT_LEG_DOUBLED semantics used by the client-side exit policy.</p>
 *
 * <p>On normal client-side exit, the SL-M orders are cancelled before/after the exit
 * to prevent double-firing.</p>
 *
 * <p>SL-M order IDs are persisted to {@code PositionGroupEntity.brokerSlmOrderIds} so they
 * survive JVM restarts. On startup, the in-memory map is rehydrated from DB.</p>
 */
@Service
public class BrokerSideStopLossService {

    private static final Logger log = LoggerFactory.getLogger(BrokerSideStopLossService.class);
    private static final MathContext MC = MathContext.DECIMAL64;

    private final BrokerClient brokerClient;
    private final SpreadTradingProperties spreadProperties;
    private final PositionGroupRepository positionGroupRepository;

    /** Maps groupId → list of broker order IDs for the SL-M orders placed. */
    private final ConcurrentHashMap<String, List<String>> slOrdersByGroup = new ConcurrentHashMap<>();

    public BrokerSideStopLossService(BrokerClient brokerClient,
                                     SpreadTradingProperties spreadProperties,
                                     PositionGroupRepository positionGroupRepository) {
        this.brokerClient = brokerClient;
        this.spreadProperties = spreadProperties;
        this.positionGroupRepository = positionGroupRepository;
    }

    /** Rehydrate SL-M order IDs from DB on startup so we can cancel them on exit. */
    @PostConstruct
    void rehydrateFromDb() {
        if (!spreadProperties.brokerSideSlEnabled()) {
            return;
        }
        List<PositionGroupEntity> openGroups = positionGroupRepository.findByOpenTrue();
        int restored = 0;
        for (PositionGroupEntity group : openGroups) {
            if (group.getStatus() != PositionGroupStatus.OPEN) continue;
            String ids = group.getBrokerSlmOrderIds();
            if (ids != null && !ids.isBlank()) {
                List<String> orderIds = Arrays.stream(ids.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList();
                if (!orderIds.isEmpty()) {
                    slOrdersByGroup.put(group.getGroupId(), orderIds);
                    restored++;
                }
            }
        }
        if (restored > 0) {
            log.info("[BrokerSideSL] Rehydrated SL-M order IDs for {} open groups", restored);
        }
    }

    /**
     * Places SL-M BUY orders for each SELL leg of a credit spread after the SELL legs fill.
     * Trigger price = fillPrice × multiplier (e.g., sold at ₹100, trigger at ₹200 for 2× multiplier).
     */
    public void placeBrokerStopLosses(String groupId, List<SpreadLeg> legs,
                                      Map<String, BigDecimal> fillPrices) {
        if (!spreadProperties.brokerSideSlEnabled()) {
            log.debug("[BrokerSideSL] Disabled — skipping SL-M placement for group {}", groupId);
            return;
        }

        double multiplier = spreadProperties.brokerSideSlMultiplier();
        List<String> placedOrderIds = new java.util.ArrayList<>();

        for (SpreadLeg leg : legs) {
            if (leg.side() != OrderSide.SELL) {
                continue;
            }
            BigDecimal entryPrice = fillPrices.get(leg.instrumentKey());
            if (entryPrice == null || entryPrice.signum() <= 0) {
                log.warn("[BrokerSideSL] No fill price for SELL leg {} — cannot place SL-M", leg.instrumentKey());
                continue;
            }

            BigDecimal triggerPrice = entryPrice.multiply(BigDecimal.valueOf(multiplier), MC)
                    .setScale(2, RoundingMode.UP);
            triggerPrice = roundToTick(triggerPrice);

            String clientOrderId = "SLM-" + groupId + "-" + leg.strike() + leg.optionType().name();

            OrderRequest slmRequest = new OrderRequest(
                    clientOrderId,
                    leg.instrumentKey(),
                    OrderSide.BUY,
                    OrderType.SL_M,
                    ProductType.MIS,
                    leg.quantity(),
                    Optional.empty(),
                    Optional.of(triggerPrice),
                    "spread-slm-" + groupId
            );

            try {
                OrderResponse response = brokerClient.placeOrder(slmRequest);
                if (response.status() == OrderStatus.REJECTED) {
                    log.error("[BrokerSideSL] SL-M REJECTED for group={} leg={}: {}",
                            groupId, leg.instrumentKey(), response.rejectionReason().orElse("unknown"));
                } else {
                    response.brokerOrderId().ifPresent(placedOrderIds::add);
                    log.info("[BrokerSideSL] SL-M placed: group={}, instrument={}, trigger={}, brokerOrderId={}",
                            groupId, leg.instrumentKey(), triggerPrice, response.brokerOrderId().orElse("?"));
                }
            } catch (Exception ex) {
                log.error("[BrokerSideSL] Failed to place SL-M for group={} leg={}: {}",
                        groupId, leg.instrumentKey(), ex.getMessage());
            }
        }

        if (!placedOrderIds.isEmpty()) {
            slOrdersByGroup.put(groupId, List.copyOf(placedOrderIds));
            // Persist to DB so IDs survive JVM restart
            persistSlmOrderIds(groupId, placedOrderIds);
        }
    }

    /**
     * Cancels all broker-side SL-M orders for a group (called before/after client-side exit).
     */
    public void cancelBrokerStopLosses(String groupId) {
        if (!spreadProperties.brokerSideSlEnabled()) {
            return;
        }
        List<String> orderIds = slOrdersByGroup.remove(groupId);
        if (orderIds == null || orderIds.isEmpty()) {
            return;
        }
        for (String brokerOrderId : orderIds) {
            try {
                Optional<OrderResponse> status = brokerClient.orderStatus(brokerOrderId);
                if (status.isPresent() && (status.get().status() == OrderStatus.OPEN
                        || status.get().status() == OrderStatus.NEW)) {
                    brokerClient.cancelOrder(brokerOrderId);
                    log.info("[BrokerSideSL] Cancelled SL-M: group={}, brokerOrderId={}", groupId, brokerOrderId);
                } else {
                    log.debug("[BrokerSideSL] SL-M already terminal: group={}, brokerOrderId={}, status={}",
                            groupId, brokerOrderId, status.map(s -> s.status().name()).orElse("unknown"));
                }
            } catch (Exception ex) {
                log.warn("[BrokerSideSL] Failed to cancel SL-M: group={}, brokerOrderId={}: {}",
                        groupId, brokerOrderId, ex.getMessage());
            }
        }
        // Clear from DB
        clearSlmOrderIds(groupId);
    }

    public boolean hasActiveStopLosses(String groupId) {
        return slOrdersByGroup.containsKey(groupId);
    }

    private void persistSlmOrderIds(String groupId, List<String> orderIds) {
        try {
            positionGroupRepository.findByGroupId(groupId).ifPresent(entity -> {
                entity.setBrokerSlmOrderIds(String.join(",", orderIds));
                positionGroupRepository.save(entity);
            });
        } catch (Exception ex) {
            log.warn("[BrokerSideSL] Failed to persist SL-M order IDs for group {}: {}", groupId, ex.getMessage());
        }
    }

    private void clearSlmOrderIds(String groupId) {
        try {
            positionGroupRepository.findByGroupId(groupId).ifPresent(entity -> {
                entity.setBrokerSlmOrderIds(null);
                positionGroupRepository.save(entity);
            });
        } catch (Exception ex) {
            log.warn("[BrokerSideSL] Failed to clear SL-M order IDs for group {}: {}", groupId, ex.getMessage());
        }
    }

    private static BigDecimal roundToTick(BigDecimal price) {
        BigDecimal tick = new BigDecimal("0.05");
        return price.divide(tick, 0, RoundingMode.CEILING).multiply(tick);
    }
}
