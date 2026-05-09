package com.algo.trade.domain;

import com.algo.trade.util.Validation;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

public record OrderResponse(
        String clientOrderId,
        Optional<String> brokerOrderId,
        String instrumentKey,
        OrderSide side,
        OrderStatus status,
        int requestedQuantity,
        int filledQuantity,
        Optional<BigDecimal> averageFillPrice,
        Optional<String> rejectionReason,
        Instant updatedAt
) {
    public OrderResponse {
        Validation.notBlank(clientOrderId, "clientOrderId");
        Validation.notBlank(instrumentKey, "instrumentKey");
        Validation.notNull(side, "side");
        Validation.notNull(status, "status");
        Validation.notNull(updatedAt, "updatedAt");
        brokerOrderId = brokerOrderId == null ? Optional.empty() : brokerOrderId;
        averageFillPrice = averageFillPrice == null ? Optional.empty() : averageFillPrice;
        rejectionReason = rejectionReason == null ? Optional.empty() : rejectionReason;
        if (requestedQuantity <= 0 || filledQuantity < 0) {
            throw new IllegalArgumentException("order quantities are invalid");
        }
    }
}
