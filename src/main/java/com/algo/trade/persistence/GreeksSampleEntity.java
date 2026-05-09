package com.algo.trade.persistence;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Persisted Greeks snapshot for an ATM option at scan time.
 * Enables historical Greeks time-series analysis and ML feature enrichment.
 */
@Entity
@Table(name = "greeks_samples", indexes = {
    @Index(name = "idx_greeks_index_time", columnList = "indexType, capturedAt")
})
public class GreeksSampleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String indexType;
    private String optionType;
    private int strikePrice;
    private Instant capturedAt;

    private double delta;
    private double gamma;
    private double theta;
    private double vega;
    private double impliedVolatility;
    private double underlyingPrice;

    protected GreeksSampleEntity() {}

    public GreeksSampleEntity(String indexType, String optionType, int strikePrice,
                               Instant capturedAt, double delta, double gamma,
                               double theta, double vega, double impliedVolatility,
                               double underlyingPrice) {
        this.indexType = indexType;
        this.optionType = optionType;
        this.strikePrice = strikePrice;
        this.capturedAt = capturedAt;
        this.delta = delta;
        this.gamma = gamma;
        this.theta = theta;
        this.vega = vega;
        this.impliedVolatility = impliedVolatility;
        this.underlyingPrice = underlyingPrice;
    }

    public Long getId() { return id; }
    public String getIndexType() { return indexType; }
    public String getOptionType() { return optionType; }
    public int getStrikePrice() { return strikePrice; }
    public Instant getCapturedAt() { return capturedAt; }
    public double getDelta() { return delta; }
    public double getGamma() { return gamma; }
    public double getTheta() { return theta; }
    public double getVega() { return vega; }
    public double getImpliedVolatility() { return impliedVolatility; }
    public double getUnderlyingPrice() { return underlyingPrice; }
}
