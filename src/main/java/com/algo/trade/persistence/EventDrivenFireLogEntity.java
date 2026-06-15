package com.algo.trade.persistence;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Persisted record of an EventDrivenBuyStrategy fire.
 *
 * <p>The strategy enforces one-shot per (underlying, day). Before 4 Jun 2026 PM
 * the tracking was an in-memory map that got cleared on every restart — multiple
 * restarts per day let the strategy fire multiple times on the same index. This
 * entity backs the in-memory map so the gate survives restarts.</p>
 *
 * <p>Unique constraint on (underlying, fire_date) guarantees one row per day per
 * index. Insert is idempotent — duplicate inserts are caught by the constraint
 * and treated as "already fired today".</p>
 */
@Entity
@Table(name = "event_driven_fire_log",
       uniqueConstraints = @UniqueConstraint(name = "uk_edb_fire_underlying_date",
                                             columnNames = {"underlying", "fire_date"}),
       indexes = @Index(name = "idx_edb_fire_date", columnList = "fire_date"))
public class EventDrivenFireLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String underlying;

    @Column(name = "fire_date", nullable = false)
    private LocalDate fireDate;

    @Column(name = "fired_at", nullable = false)
    private Instant firedAt;

    @Column(name = "option_type", length = 2)
    private String optionType;

    @Column(name = "spot_at_entry", precision = 14, scale = 2)
    private BigDecimal spotAtEntry;

    @Column(name = "iv_rank")
    private Double ivRank;

    @Column(name = "vix")
    private Double vix;

    protected EventDrivenFireLogEntity() {}

    public EventDrivenFireLogEntity(String underlying, LocalDate fireDate, Instant firedAt,
                                     String optionType, BigDecimal spotAtEntry,
                                     Double ivRank, Double vix) {
        this.underlying = underlying;
        this.fireDate = fireDate;
        this.firedAt = firedAt;
        this.optionType = optionType;
        this.spotAtEntry = spotAtEntry;
        this.ivRank = ivRank;
        this.vix = vix;
    }

    public Long getId() { return id; }
    public String getUnderlying() { return underlying; }
    public LocalDate getFireDate() { return fireDate; }
    public Instant getFiredAt() { return firedAt; }
    public String getOptionType() { return optionType; }
    public BigDecimal getSpotAtEntry() { return spotAtEntry; }
    public Double getIvRank() { return ivRank; }
    public Double getVix() { return vix; }
}
