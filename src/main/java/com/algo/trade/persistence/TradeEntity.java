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
        @Index(name = "idx_trade_entry_time", columnList = "entryTime"),
        @Index(name = "idx_trade_user_id", columnList = "userId"),
        @Index(name = "idx_trade_user_status", columnList = "userId, status")
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
    @jakarta.persistence.Column(length = 2000)
    private String entryReason;
    @jakarta.persistence.Column(length = 1000)
    private String exitReason;
    /** Strategy type that generated this trade (e.g. DIRECTIONAL_BUY, ITM_CONVICTION). */
    private String strategyType;
    /**
     * Tuning correlationKey of the ENTRY signal (SignalDecisionKey.from(decision)) — the same key the
     * signal + execution tuning events were written under. Stamped at entry so an exit emitted from a
     * context without strategy state (OrderFillWatchdog) can key the ExitEvent back to the entry, closing
     * the eval→signal→execution→EXIT join. Null for legacy/pre-fix trades (exit then falls back to tradeId).
     */
    @jakarta.persistence.Column(name = "entry_correlation_key", length = 64)
    private String entryCorrelationKey;
    /** Product type used for entry (MIS, CNC, NRML). Exit orders must use the same type. */
    @jakarta.persistence.Column(name = "product_type", length = 8)
    private String productType;
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
    @jakarta.persistence.Column(length = 500)
    private String partialExitLayers;
    /** Trailing stop activation % applied at entry time — stored so exit monitors use consistent params after config changes. */
    private BigDecimal appliedTrailingStopActivationPercent;
    /** Trailing gap % applied at entry time — stored so exit monitors use consistent params after config changes. */
    private BigDecimal appliedTrailingGapPercent;
    /** Current trailing stop price — persisted so it survives restarts. Null until trailing stop activates. */
    private BigDecimal trailingStopPrice;

    /** Market environment score (0-100) at entry time, or -1 if computation failed. Write-once. */
    private Integer environmentScore;
    /** JSON breakdown of environment sub-scores at entry time. Write-once. */
    @jakarta.persistence.Column(length = 500)
    private String environmentBreakdown;
    /** Session window name at entry time (e.g. MORNING_MOMENTUM). Write-once. */
    private String entrySessionWindow;

    /** User who owns this trade (multi-user support). Null = legacy/default user. */
    @jakarta.persistence.Column(name = "user_id")
    private Long userId;

    /** Broker the entry order was placed on (e.g. ZERODHA). Captured at entry. */
    @jakarta.persistence.Column(name = "broker_name")
    private String brokerName;
    /** Broker-side client/login id the entry was placed under (e.g. Zerodha SX0602).
     *  Exit flow verifies it fires on the SAME broker account. Captured at entry. */
    @jakarta.persistence.Column(name = "broker_client_id")
    private String brokerClientId;

    /** Bid–ask spread % of mid at entry — for liquidity collapse exits. */
    private Double entryBidAskSpreadPercent;
    private Long entryVolume;
    private Long entryOpenInterest;
    /** SL/target % frozen at entry (audit + hybrid resolver baseline). */
    private BigDecimal appliedStopLossPercent;
    private BigDecimal appliedTargetPercent;

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
        this.entryReason = entryReason != null && entryReason.length() > 2000 ? entryReason.substring(0, 2000) : entryReason;
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

    /**
     * Reduce the open quantity after a PARTIAL manual close (the reduced lots were already sold on
     * the owner's broker account). Deliberately reduce-only — never grows a position and never
     * flattens it to zero (a full close must go through ExecutionEngine.closeTrade so exit
     * price/reason/P&L bookkeeping happens). All later bot exits then sell the reduced quantity,
     * keeping the bot in sync with the broker.
     */
    public void reduceQuantity(int soldQty) {
        if (soldQty <= 0 || soldQty >= this.quantity) {
            throw new IllegalArgumentException("reduceQuantity(" + soldQty + ") invalid for open qty " + this.quantity);
        }
        this.quantity -= soldQty;
    }
    public BigDecimal getEntryPrice() { return entryPrice; }
    public BigDecimal getExitPrice() { return exitPrice; }
    public Instant getEntryTime() { return entryTime; }
    public Instant getExitTime() { return exitTime; }
    public BigDecimal getRealizedPnl() { return realizedPnl == null ? BigDecimal.ZERO : realizedPnl; }
    public String getEntryReason() { return entryReason; }
    public String getExitReason() { return exitReason; }
    public String getStrategyType() { return strategyType; }
    public void setStrategyType(String strategyType) { this.strategyType = strategyType; }

    public String getEntryCorrelationKey() { return entryCorrelationKey; }
    public void setEntryCorrelationKey(String entryCorrelationKey) { this.entryCorrelationKey = entryCorrelationKey; }
    public String getProductType() { return productType; }
    public void setProductType(String productType) { this.productType = productType; }
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

    public Integer getEnvironmentScore() { return environmentScore; }
    public void setEnvironmentScore(Integer v) { this.environmentScore = v; }
    public String getEnvironmentBreakdown() { return environmentBreakdown; }
    public void setEnvironmentBreakdown(String v) { this.environmentBreakdown = v; }
    public String getEntrySessionWindow() { return entrySessionWindow; }
    public void setEntrySessionWindow(String v) { this.entrySessionWindow = v; }

    public Double getEntryBidAskSpreadPercent() { return entryBidAskSpreadPercent; }
    public void setEntryBidAskSpreadPercent(Double v) { this.entryBidAskSpreadPercent = v; }
    public Long getEntryVolume() { return entryVolume; }
    public void setEntryVolume(Long v) { this.entryVolume = v; }
    public Long getEntryOpenInterest() { return entryOpenInterest; }
    public void setEntryOpenInterest(Long v) { this.entryOpenInterest = v; }
    public BigDecimal getAppliedStopLossPercent() { return appliedStopLossPercent; }
    public void setAppliedStopLossPercent(BigDecimal v) { this.appliedStopLossPercent = v; }
    public BigDecimal getAppliedTargetPercent() { return appliedTargetPercent; }
    public void setAppliedTargetPercent(BigDecimal v) { this.appliedTargetPercent = v; }

    /** Set exit reason without closing the trade — used to flag positions for exit by monitors. */
    public void setExitReason(String exitReason) { this.exitReason = exitReason; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getBrokerName() { return brokerName; }
    public void setBrokerName(String brokerName) { this.brokerName = brokerName; }
    public String getBrokerClientId() { return brokerClientId; }
    public void setBrokerClientId(String brokerClientId) { this.brokerClientId = brokerClientId; }
}
