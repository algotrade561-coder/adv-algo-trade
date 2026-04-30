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
    /** Strategy type that generated this trade (e.g. DIRECTIONAL_BUY, ITM_CONVICTION). */
    private String strategyType;
    /** Highest price seen since entry — for trailing stop recovery after restart. */
    private BigDecimal peakPrice;
    /** Greeks at entry time — for post-trade analysis. */
    private Double entryDelta;
    private Double entryTheta;
    private Double entryIV;
    private Double entryGamma;
    /** Cumulative P&L from partial exits already booked. */
    private BigDecimal bookedPnl;
    /** Comma-separated progressive exit layer names that have already fired (e.g. "PARTIAL_1,PARTIAL_2"). */
    private String partialExitLayers;
    /** Trailing stop activation % applied at entry time — stored so exit monitors use consistent params after config changes. */
    private BigDecimal appliedTrailingStopActivationPercent;
    /** Trailing gap % applied at entry time — stored so exit monitors use consistent params after config changes. */
    private BigDecimal appliedTrailingGapPercent;
    /** Current trailing stop price — persisted so it survives restarts. Null until trailing stop activates. */
    private BigDecimal trailingStopPrice;

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
        // Add any previously booked partial exit P&L to the final realized P&L
        BigDecimal partialPnl = this.bookedPnl != null ? this.bookedPnl : BigDecimal.ZERO;
        this.realizedPnl = realizedPnl.add(partialPnl);
        this.exitReason = exitReason;
    }

    public void partialClose(int quantitySold, BigDecimal partialPnl, String layerName) {
        this.quantity -= quantitySold;
        this.bookedPnl = (this.bookedPnl == null ? BigDecimal.ZERO : this.bookedPnl).add(partialPnl);
        this.realizedPnl = (this.realizedPnl == null ? BigDecimal.ZERO : this.realizedPnl).add(partialPnl);
        String existing = this.partialExitLayers == null ? "" : this.partialExitLayers;
        this.partialExitLayers = existing.isEmpty() ? layerName : existing + "," + layerName;
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
    public String getStrategyType() { return strategyType; }
    public void setStrategyType(String strategyType) { this.strategyType = strategyType; }
    public BigDecimal getPeakPrice() { return peakPrice; }
    public void setPeakPrice(BigDecimal peakPrice) { this.peakPrice = peakPrice; }
    public Double getEntryDelta() { return entryDelta; }
    public void setEntryDelta(Double v) { this.entryDelta = v; }
    public Double getEntryTheta() { return entryTheta; }
    public void setEntryTheta(Double v) { this.entryTheta = v; }
    public Double getEntryIV() { return entryIV; }
    public void setEntryIV(Double v) { this.entryIV = v; }
    public Double getEntryGamma() { return entryGamma; }
    public void setEntryGamma(Double v) { this.entryGamma = v; }

    /** Check if this is a paper (simulated) trade. */
    public boolean isPaperTrade() { return tradeId != null && tradeId.startsWith("PAPER-"); }
    public BigDecimal getBookedPnl() { return bookedPnl == null ? BigDecimal.ZERO : bookedPnl; }
    public String getPartialExitLayers() { return partialExitLayers; }
    public BigDecimal getAppliedTrailingStopActivationPercent() { return appliedTrailingStopActivationPercent; }
    public void setAppliedTrailingStopActivationPercent(BigDecimal v) { this.appliedTrailingStopActivationPercent = v; }
    public BigDecimal getAppliedTrailingGapPercent() { return appliedTrailingGapPercent; }
    public void setAppliedTrailingGapPercent(BigDecimal v) { this.appliedTrailingGapPercent = v; }
    public BigDecimal getTrailingStopPrice() { return trailingStopPrice; }
    public void setTrailingStopPrice(BigDecimal v) { this.trailingStopPrice = v; }
}
