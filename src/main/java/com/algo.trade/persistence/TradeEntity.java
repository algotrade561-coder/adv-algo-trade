package com.algo.trade.persistence;

import com.algo.trade.domain.TradeStatus;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(indexes = {
        @Index(name = "idx_trade_status", columnList = "status"),
        @Index(name = "idx_trade_instrument", columnList = "instrumentKey"),
        @Index(name = "idx_trade_entry_time", columnList = "entryTime")
})
public class TradeEntity {

    @Id
    private String tradeId;
    @Version
    private Long version;
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
    public Long getVersion() { return version; }
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
