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
        /** Trigger price for SL-M orders. Ignored for MARKET/LIMIT. */
        Optional<BigDecimal> triggerPrice,
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
        triggerPrice = triggerPrice == null ? Optional.empty() : triggerPrice;
        tag = tag == null ? "" : tag;
    }

    /** Convenience constructor without triggerPrice (backward compatible). */
    public OrderRequest(String clientOrderId, String instrumentKey, OrderSide side,
                        OrderType orderType, ProductType productType, int quantity,
                        Optional<BigDecimal> limitPrice, String tag) {
        this(clientOrderId, instrumentKey, side, orderType, productType, quantity,
                limitPrice, Optional.empty(), tag);
    }
}
