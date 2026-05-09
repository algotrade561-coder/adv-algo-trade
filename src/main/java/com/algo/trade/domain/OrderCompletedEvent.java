package com.algo.trade.domain;

/**
 * Published by KiteWebSocketClient when an order_update with status=COMPLETE arrives.
 * PositionSynchronizer listens to this for immediate reconciliation.
 */
public record OrderCompletedEvent(
        String orderId,
        String tradingSymbol,
        String status,
        int filledQuantity,
        double averagePrice
) {}
