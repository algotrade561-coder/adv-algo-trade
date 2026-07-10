package com.algo.trade.persistence;

import com.algo.trade.domain.OrderStatus;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(indexes = {
        @Index(name = "idx_order_status", columnList = "status"),
        @Index(name = "idx_order_user_id", columnList = "userId"),
        @Index(name = "idx_order_broker_id", columnList = "brokerOrderId"),
        @Index(name = "idx_order_instrument", columnList = "instrumentKey")
})
public class OrderEntity {

    @Id
    private String clientOrderId;
    @Version
    private Long version;
    private String brokerOrderId;
    private String instrumentKey;
    private String side;
    @Enumerated(EnumType.STRING)
    private OrderStatus status;
    private int requestedQuantity;
    private int filledQuantity;
    private BigDecimal averageFillPrice;
    @jakarta.persistence.Column(length = 1000)
    private String rejectionReason;
    private Instant updatedAt;
    private Instant signalTimestamp;
    private Instant orderPlacedAt;
    private BigDecimal slippage;
    private String strategyType;
    /** Tuning correlationKey of the entry signal — carried through a pending limit order so the TradeEntity
     *  materialized on fill (OrderFillWatchdog.openTradeFromFilledOrder) inherits it for exit-event keying. */
    @jakarta.persistence.Column(name = "entry_correlation_key", length = 64)
    private String entryCorrelationKey;
    /** C3 (2026-07-10): the ORIGINAL signal reason (e.g. "MEMORY_AVALANCHE strike=... vu=...") carried
     *  through a pending limit order so a watchdog-materialized TradeEntity keeps its trade identity —
     *  order text as entryReason silently broke every contains("MEMORY_AVALANCHE") check. */
    @jakarta.persistence.Column(name = "signal_reason", length = 512)
    private String signalReason;
    private boolean tradeMaterialized = false;

    /** User who placed this order (multi-user support). Null = legacy/default user. */
    @jakarta.persistence.Column(name = "user_id")
    private Long userId;

    /** Trade this order belongs to (entry fill or exit). Links orders ↔ trades for exact per-user closing. */
    @jakarta.persistence.Column(name = "trade_id")
    private String tradeId;
    /** Broker the order was routed to (e.g. ZERODHA). */
    @jakarta.persistence.Column(name = "broker_name")
    private String brokerName;
    /** Broker-side client/login id the order was placed under (e.g. Zerodha SX0602). */
    @jakarta.persistence.Column(name = "broker_client_id")
    private String brokerClientId;

    protected OrderEntity() {
    }

    public OrderEntity(String clientOrderId, String brokerOrderId, String instrumentKey, String side,
                       OrderStatus status, int requestedQuantity, int filledQuantity, BigDecimal averageFillPrice,
                       String rejectionReason, Instant updatedAt) {
        this.clientOrderId = clientOrderId;
        this.brokerOrderId = brokerOrderId;
        this.instrumentKey = instrumentKey;
        this.side = side;
        this.status = status;
        this.requestedQuantity = requestedQuantity;
        this.filledQuantity = filledQuantity;
        this.averageFillPrice = averageFillPrice;
        this.rejectionReason = rejectionReason;
        this.updatedAt = updatedAt;
    }

    public String getClientOrderId() { return clientOrderId; }
    public Long getVersion() { return version; }
    public String getBrokerOrderId() { return brokerOrderId; }
    public String getInstrumentKey() { return instrumentKey; }
    public String getSide() { return side; }
    public OrderStatus getStatus() { return status; }
    public int getRequestedQuantity() { return requestedQuantity; }
    public int getFilledQuantity() { return filledQuantity; }
    public BigDecimal getAverageFillPrice() { return averageFillPrice; }
    public String getRejectionReason() { return rejectionReason; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setStatus(OrderStatus status) { this.status = status; }
    public void setFilledQuantity(int filledQuantity) { this.filledQuantity = filledQuantity; }
    public void setAverageFillPrice(BigDecimal averageFillPrice) { this.averageFillPrice = averageFillPrice; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Instant getSignalTimestamp() { return signalTimestamp; }
    public void setSignalTimestamp(Instant v) { this.signalTimestamp = v; }
    public Instant getOrderPlacedAt() { return orderPlacedAt; }
    public void setOrderPlacedAt(Instant v) { this.orderPlacedAt = v; }
    public BigDecimal getSlippage() { return slippage; }
    public void setSlippage(BigDecimal v) { this.slippage = v; }
    public String getStrategyType() { return strategyType; }
    public void setStrategyType(String v) { this.strategyType = v; }
    public String getEntryCorrelationKey() { return entryCorrelationKey; }
    public void setEntryCorrelationKey(String v) { this.entryCorrelationKey = v; }
    public String getSignalReason() { return signalReason; }
    public void setSignalReason(String v) { this.signalReason = v != null && v.length() > 512 ? v.substring(0, 512) : v; }

    /** Signal-to-order latency in milliseconds. */
    public Long getSignalToOrderMs() {
        if (signalTimestamp == null || orderPlacedAt == null) return null;
        return java.time.Duration.between(signalTimestamp, orderPlacedAt).toMillis();
    }

    /** Order-to-fill latency in milliseconds. */
    public Long getOrderToFillMs() {
        if (orderPlacedAt == null || updatedAt == null || status != OrderStatus.COMPLETE) return null;
        return java.time.Duration.between(orderPlacedAt, updatedAt).toMillis();
    }

    public boolean isTradeMaterialized() { return tradeMaterialized; }
    public void setTradeMaterialized(boolean v) { this.tradeMaterialized = v; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getTradeId() { return tradeId; }
    public void setTradeId(String tradeId) { this.tradeId = tradeId; }
    public String getBrokerName() { return brokerName; }
    public void setBrokerName(String brokerName) { this.brokerName = brokerName; }
    public String getBrokerClientId() { return brokerClientId; }
    public void setBrokerClientId(String brokerClientId) { this.brokerClientId = brokerClientId; }
}
