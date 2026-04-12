package com.kiteapioptions.persistence;

import com.kiteapioptions.domain.TradeStatus;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
public class TradeEntity {

    @Id
    private String tradeId;
    private String instrumentKey;
    private String underlying;
    private String optionType;
    @Enumerated(EnumType.STRING)
    private TradeStatus status;
    private int quantity;
    private BigDecimal entryPrice;
    private BigDecimal exitPrice;
    private Instant entryTime;
    private Instant exitTime;
    private BigDecimal realizedPnl;
    private String entryReason;
    private String exitReason;

    protected TradeEntity() {
    }

    public TradeEntity(String tradeId, String instrumentKey, String underlying, String optionType,
                       TradeStatus status, int quantity, BigDecimal entryPrice, Instant entryTime, String entryReason) {
        this.tradeId = tradeId;
        this.instrumentKey = instrumentKey;
        this.underlying = underlying;
        this.optionType = optionType;
        this.status = status;
        this.quantity = quantity;
        this.entryPrice = entryPrice;
        this.entryTime = entryTime;
        this.entryReason = entryReason;
        this.realizedPnl = BigDecimal.ZERO;
    }

    public void close(BigDecimal exitPrice, Instant exitTime, BigDecimal realizedPnl, String exitReason) {
        this.status = TradeStatus.CLOSED;
        this.exitPrice = exitPrice;
        this.exitTime = exitTime;
        this.realizedPnl = realizedPnl;
        this.exitReason = exitReason;
    }

    public String getTradeId() { return tradeId; }
    public String getInstrumentKey() { return instrumentKey; }
    public String getUnderlying() { return underlying; }
    public String getOptionType() { return optionType; }
    public TradeStatus getStatus() { return status; }
    public int getQuantity() { return quantity; }
    public BigDecimal getEntryPrice() { return entryPrice; }
    public BigDecimal getExitPrice() { return exitPrice; }
    public Instant getEntryTime() { return entryTime; }
    public Instant getExitTime() { return exitTime; }
    public BigDecimal getRealizedPnl() { return realizedPnl == null ? BigDecimal.ZERO : realizedPnl; }
    public String getEntryReason() { return entryReason; }
    public String getExitReason() { return exitReason; }
}
