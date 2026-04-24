package com.algo.trade.persistence;

import com.algo.trade.domain.OrderStatus;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
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
    private String rejectionReason;
    private Instant updatedAt;

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
}
