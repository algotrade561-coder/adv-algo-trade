package com.algo.trade.persistence;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Persisted full-chain PCR sample — one row per index per compute cycle (~every 2 min).
 * Survives deploys/restarts so the "Intraday PCR" chart retains the full day's history
 * without in-memory data loss. Indexed on (indexType, capturedAt) for fast today-only queries.
 */
@Entity
@Table(name = "pcr_samples", indexes = {
    @Index(name = "idx_pcr_index_time", columnList = "indexType, capturedAt")
})
public class PcrSampleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String indexType;

    @Column(nullable = false)
    private Instant capturedAt;

    @Column(nullable = false)
    private double pcr;

    protected PcrSampleEntity() {}

    public PcrSampleEntity(String indexType, Instant capturedAt, double pcr) {
        this.indexType = indexType;
        this.capturedAt = capturedAt;
        this.pcr = pcr;
    }

    public Long getId() { return id; }
    public String getIndexType() { return indexType; }
    public Instant getCapturedAt() { return capturedAt; }
    public double getPcr() { return pcr; }
}
