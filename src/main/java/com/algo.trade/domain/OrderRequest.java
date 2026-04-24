package com.algo.trade.domain;

import com.algo.trade.util.Validation;
import java.math.BigDecimal;
import java.util.Optional;

/**
 * Broker-neutral order request. Live adapters must enforce safety gates before placement.
 */
public record OrderRequest(
        String clientOrderId,
        String instrumentKey,
        OrderSide side,
        OrderType orderType,
        ProductType productType,
        int quantity,
        Optional<BigDecimal> limitPrice,
        String tag
) {
    public OrderRequest {
        Validation.notBlank(clientOrderId, "clientOrderId");
        Validation.notBlank(instrumentKey, "instrumentKey");
        Validation.notNull(side, "side");
        Validation.notNull(orderType, "orderType");
        Validation.notNull(productType, "productType");
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        limitPrice = limitPrice == null ? Optional.empty() : limitPrice;
        tag = tag == null ? "" : tag;
    }
}
