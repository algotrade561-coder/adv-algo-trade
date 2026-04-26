package com.algo.trade.persistence;

import jakarta.persistence.*;
import java.time.LocalDate;

/**
 * Persisted IV sample for IVRankTracker — survives restarts.
 */
@Entity
@Table(name = "iv_samples", indexes = {
    @Index(name = "idx_iv_index_date", columnList = "indexType, sampleDate")
})
public class IVSampleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String indexType;
    private LocalDate sampleDate;
    private double iv;

    protected IVSampleEntity() {}

    public IVSampleEntity(String indexType, LocalDate sampleDate, double iv) {
        this.indexType = indexType;
        this.sampleDate = sampleDate;
        this.iv = iv;
    }

    public Long getId() { return id; }
    public String getIndexType() { return indexType; }
    public LocalDate getSampleDate() { return sampleDate; }
    public double getIv() { return iv; }
}
