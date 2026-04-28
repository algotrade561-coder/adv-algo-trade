package com.algo.trade.persistence;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Persists every strategy evaluation — both entry signals and NO_TRADE decisions.
 * Extended to support all strategy types (directional, spread, scalping, event-driven).
 * H2 will auto-add new columns via ddl-auto: update.
 */
@Entity
@Table(name = "strategy_decision_entity", indexes = {
    @Index(name = "idx_sde_timestamp", columnList = "timestamp"),
    @Index(name = "idx_sde_signal_type", columnList = "signalType"),
    @Index(name = "idx_sde_strategy_type", columnList = "strategyType"),
    @Index(name = "idx_sde_underlying", columnList = "underlying")
})
public class StrategyDecisionEntity {

    @Id
    @GeneratedValue
    private Long id;

    // ── Core fields (all strategies) ─────────────────────────────────────────
    private Instant timestamp;
    private String underlying;
    private String signalType;

    /** Which strategy produced this signal: DIRECTIONAL_BUY, SCALPING, BULL_CALL_SPREAD, etc. */
    private String strategyType;

    private BigDecimal underlyingPrice;
    private BigDecimal optionPrice;
    private Long optionOpenInterest;
    private Integer lotSize;
    private BigDecimal lotPrice;
    private String selectedInstrumentKey;
    private BigDecimal selectedStrike;
    private String optionType;
    private BigDecimal confidenceScore;

    @Lob
    private String reasons;

    // ── Directional strategy fields ───────────────────────────────────────────
    private boolean vwapConditionPassed;
    private BigDecimal imbalance;
    private boolean volumeSpike;

    // ── Spread / multi-leg fields ─────────────────────────────────────────────
    /** For spreads: the sell leg instrument key */
    private String sellLegInstrumentKey;
    /** For spreads: the sell leg strike */
    private BigDecimal sellLegStrike;
    /** For spreads: net debit or net credit */
    private BigDecimal netPremium;
    /** Number of strikes between legs (spread width) */
    private Integer spreadStrikes;

    // ── Volatility / IV fields ────────────────────────────────────────────────
    private Double ivRank;
    /** "TRACKER" = real Black-Scholes IV rank from IVRankTracker; "NEUTRAL" = 50.0 default (no history yet) */
    private String ivRankSource;
    private Double bollingerBandwidth;
    private Double bbUpper;
    private Double bbLower;
    private Boolean bbSqueeze;

    // ── Scalping fields ───────────────────────────────────────────────────────
    private Double fastEma;
    private Double slowEma;
    private String emaCrossType;
    private Integer emaCrossConfirmCount;

    // ── Option quote depth (bid/ask/ATP at decision time) ─────────────────────
    private BigDecimal optionBid;
    private BigDecimal optionAsk;
    private BigDecimal optionAtp;

    // ── Diagnostic fields (filter-level analysis) ─────────────────────────────
    /** Machine-readable first gate that blocked entry: noEmaCross, thetaGuard, noSqueeze, etc. */
    private String firstFailedFilter;

    // ── Config snapshot (key params active at decision time — for replay/audit) ─
    @Lob
    private String configSnapshot;

    // ── Execution outcome (populated after execution attempt) ─────────────────
    /** RISK_REJECTED, SIZING_REJECTED, ORDER_FILLED, ORDER_NOT_FILLED, BROKER_ERROR, NOT_EXECUTED */
    private String executionStage;
    /** Rejection reason from execution engine (risk, sizing, broker) */
    private String executionReason;

    protected StrategyDecisionEntity() {}

    // ── Constructor for existing directional strategy (backward compatible) ───
    public StrategyDecisionEntity(Instant timestamp, String underlying, String signalType,
                                   BigDecimal underlyingPrice, BigDecimal optionPrice,
                                   Long optionOpenInterest, Integer lotSize, BigDecimal lotPrice,
                                   String selectedInstrumentKey, BigDecimal selectedStrike,
                                   String optionType, boolean vwapConditionPassed,
                                   BigDecimal imbalance, boolean volumeSpike,
                                   BigDecimal confidenceScore, String reasons) {
        this.timestamp = timestamp;
        this.underlying = underlying;
        this.signalType = signalType;
        this.strategyType = "DIRECTIONAL_BUY";
        this.underlyingPrice = underlyingPrice;
        this.optionPrice = optionPrice;
        this.optionOpenInterest = optionOpenInterest;
        this.lotSize = lotSize;
        this.lotPrice = lotPrice;
        this.selectedInstrumentKey = selectedInstrumentKey;
        this.selectedStrike = selectedStrike;
        this.optionType = optionType;
        this.vwapConditionPassed = vwapConditionPassed;
        this.imbalance = imbalance;
        this.volumeSpike = volumeSpike;
        this.confidenceScore = confidenceScore;
        this.reasons = reasons;
    }

    // Backward-compat constructor without confidenceScore
    public StrategyDecisionEntity(Instant timestamp, String underlying, String signalType,
                                   BigDecimal underlyingPrice, BigDecimal optionPrice,
                                   Long optionOpenInterest, Integer lotSize, BigDecimal lotPrice,
                                   String selectedInstrumentKey, BigDecimal selectedStrike,
                                   String optionType, boolean vwapConditionPassed,
                                   BigDecimal imbalance, boolean volumeSpike, String reasons) {
        this(timestamp, underlying, signalType, underlyingPrice, optionPrice, optionOpenInterest,
                lotSize, lotPrice, selectedInstrumentKey, selectedStrike, optionType,
                vwapConditionPassed, imbalance, volumeSpike, BigDecimal.ZERO, reasons);
    }

    // ── Builder-style factory for new strategies ──────────────────────────────

    public static StrategyDecisionEntity forStrategy(String strategyType, Instant timestamp,
                                                      String underlying, String signalType,
                                                      String optionType, BigDecimal underlyingPrice,
                                                      BigDecimal confidenceScore, String reasons) {
        StrategyDecisionEntity e = new StrategyDecisionEntity();
        e.strategyType = strategyType;
        e.timestamp = timestamp;
        e.underlying = underlying;
        e.signalType = signalType;
        e.optionType = optionType;
        e.underlyingPrice = underlyingPrice;
        e.confidenceScore = confidenceScore;
        e.reasons = reasons;
        return e;
    }

    // ── Getters ───────────────────────────────────────────────────────────────
    public Long getId() { return id; }
    public Instant getTimestamp() { return timestamp; }
    public String getUnderlying() { return underlying; }
    public String getSignalType() { return signalType; }
    public String getStrategyType() { return strategyType; }
    public BigDecimal getUnderlyingPrice() { return underlyingPrice; }
    public BigDecimal getOptionPrice() { return optionPrice; }
    public Long getOptionOpenInterest() { return optionOpenInterest; }
    public Integer getLotSize() { return lotSize; }
    public BigDecimal getLotPrice() { return lotPrice; }
    public String getSelectedInstrumentKey() { return selectedInstrumentKey; }
    public BigDecimal getSelectedStrike() { return selectedStrike; }
    public String getOptionType() { return optionType; }
    public boolean isVwapConditionPassed() { return vwapConditionPassed; }
    public BigDecimal getImbalance() { return imbalance; }
    public boolean isVolumeSpike() { return volumeSpike; }
    public BigDecimal getConfidenceScore() { return confidenceScore; }
    public String getReasons() { return reasons; }
    public String getSellLegInstrumentKey() { return sellLegInstrumentKey; }
    public BigDecimal getSellLegStrike() { return sellLegStrike; }
    public BigDecimal getNetPremium() { return netPremium; }
    public Integer getSpreadStrikes() { return spreadStrikes; }
    public Double getIvRank() { return ivRank; }
    public String getIvRankSource() { return ivRankSource; }
    public Double getBollingerBandwidth() { return bollingerBandwidth; }
    public Double getBbUpper() { return bbUpper; }
    public Double getBbLower() { return bbLower; }
    public Boolean getBbSqueeze() { return bbSqueeze; }
    public Double getFastEma() { return fastEma; }
    public Double getSlowEma() { return slowEma; }
    public String getEmaCrossType() { return emaCrossType; }
    public Integer getEmaCrossConfirmCount() { return emaCrossConfirmCount; }
    public BigDecimal getOptionBid() { return optionBid; }
    public BigDecimal getOptionAsk() { return optionAsk; }
    public BigDecimal getOptionAtp() { return optionAtp; }
    public String getFirstFailedFilter() { return firstFailedFilter; }
    public String getExecutionStage() { return executionStage; }
    public String getExecutionReason() { return executionReason; }
    public String getConfigSnapshot() { return configSnapshot; }

    // ── Setters for enrichment ────────────────────────────────────────────────
    public void setStrategyType(String v) { this.strategyType = v; }
    public void setSelectedInstrumentKey(String v) { this.selectedInstrumentKey = v; }
    public void setSelectedStrike(BigDecimal v) { this.selectedStrike = v; }
    public void setOptionPrice(BigDecimal v) { this.optionPrice = v; }
    public void setSellLegInstrumentKey(String v) { this.sellLegInstrumentKey = v; }
    public void setSellLegStrike(BigDecimal v) { this.sellLegStrike = v; }
    public void setNetPremium(BigDecimal v) { this.netPremium = v; }
    public void setSpreadStrikes(Integer v) { this.spreadStrikes = v; }
    public void setIvRank(Double v) { this.ivRank = v; }
    public void setIvRankSource(String v) { this.ivRankSource = v; }
    public void setBollingerBandwidth(Double v) { this.bollingerBandwidth = v; }
    public void setBbUpper(Double v) { this.bbUpper = v; }
    public void setBbLower(Double v) { this.bbLower = v; }
    public void setBbSqueeze(Boolean v) { this.bbSqueeze = v; }
    public void setFastEma(Double v) { this.fastEma = v; }
    public void setSlowEma(Double v) { this.slowEma = v; }
    public void setEmaCrossType(String v) { this.emaCrossType = v; }
    public void setEmaCrossConfirmCount(Integer v) { this.emaCrossConfirmCount = v; }
    public void setOptionBid(BigDecimal v) { this.optionBid = v; }
    public void setOptionAsk(BigDecimal v) { this.optionAsk = v; }
    public void setOptionAtp(BigDecimal v) { this.optionAtp = v; }
    public void setFirstFailedFilter(String v) { this.firstFailedFilter = v; }
    public void setConfigSnapshot(String v) { this.configSnapshot = v; }
    public void setLotSize(Integer v) { this.lotSize = v; }
    public void setOptionOpenInterest(Long v) { this.optionOpenInterest = v; }
    public void setVwapConditionPassed(boolean v) { this.vwapConditionPassed = v; }
    public void setVolumeSpike(boolean v) { this.volumeSpike = v; }
    public void setImbalance(BigDecimal v) { this.imbalance = v; }
    public void setExecutionStage(String v) { this.executionStage = v; }
    public void setExecutionReason(String v) { this.executionReason = v; }
}
