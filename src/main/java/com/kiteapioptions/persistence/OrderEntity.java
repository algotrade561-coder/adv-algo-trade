package com.kiteapioptions.persistence;

import com.kiteapioptions.domain.OrderStatus;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
public class OrderEntity {

    @Id
    private String clientOrderId;
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
    public String getBrokerOrderId() { return brokerOrderId; }
    public String getInstrumentKey() { return instrumentKey; }
    public String getSide() { return side; }
    public OrderStatus getStatus() { return status; }
    public int getRequestedQuantity() { return requestedQuantity; }
    public int getFilledQuantity() { return filledQuantity; }
    public BigDecimal getAverageFillPrice() { return averageFillPrice; }
    public String getRejectionReason() { return rejectionReason; }
    public Instant getUpdatedAt() { return updatedAt; }
}
