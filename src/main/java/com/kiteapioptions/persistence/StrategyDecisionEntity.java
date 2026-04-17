package com.kiteapioptions.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
public class StrategyDecisionEntity {

    @Id
    @GeneratedValue
    private Long id;
    private Instant timestamp;
    private String underlying;
    private String signalType;
    private BigDecimal underlyingPrice;
    private String selectedInstrumentKey;
    private BigDecimal selectedStrike;
    private String optionType;
    private boolean vwapConditionPassed;
    private BigDecimal imbalance;
    private boolean volumeSpike;
    private BigDecimal confidenceScore;
    @Lob
    private String reasons;

    protected StrategyDecisionEntity() {
    }

    public StrategyDecisionEntity(Instant timestamp, String underlying, String signalType, BigDecimal underlyingPrice,
                                  String selectedInstrumentKey, BigDecimal selectedStrike, String optionType,
                                  boolean vwapConditionPassed, BigDecimal imbalance, boolean volumeSpike,
                                  BigDecimal confidenceScore, String reasons) {
        this.timestamp = timestamp;
        this.underlying = underlying;
        this.signalType = signalType;
        this.underlyingPrice = underlyingPrice;
        this.selectedInstrumentKey = selectedInstrumentKey;
        this.selectedStrike = selectedStrike;
        this.optionType = optionType;
        this.vwapConditionPassed = vwapConditionPassed;
        this.imbalance = imbalance;
        this.volumeSpike = volumeSpike;
        this.confidenceScore = confidenceScore;
        this.reasons = reasons;
    }

    public StrategyDecisionEntity(Instant timestamp, String underlying, String signalType, BigDecimal underlyingPrice,
                                  String selectedInstrumentKey, BigDecimal selectedStrike, String optionType,
                                  boolean vwapConditionPassed, BigDecimal imbalance, boolean volumeSpike,
                                  String reasons) {
        this(timestamp, underlying, signalType, underlyingPrice, selectedInstrumentKey, selectedStrike, optionType,
                vwapConditionPassed, imbalance, volumeSpike, BigDecimal.ZERO, reasons);
    }

    public Long getId() { return id; }
    public Instant getTimestamp() { return timestamp; }
    public String getUnderlying() { return underlying; }
    public String getSignalType() { return signalType; }
    public BigDecimal getUnderlyingPrice() { return underlyingPrice; }
    public String getSelectedInstrumentKey() { return selectedInstrumentKey; }
    public BigDecimal getSelectedStrike() { return selectedStrike; }
    public String getOptionType() { return optionType; }
    public boolean isVwapConditionPassed() { return vwapConditionPassed; }
    public BigDecimal getImbalance() { return imbalance; }
    public boolean isVolumeSpike() { return volumeSpike; }
    public BigDecimal getConfidenceScore() { return confidenceScore; }
    public String getReasons() { return reasons; }
}
