package com.algo.trade.persistence;

import com.algo.trade.domain.PositionGroup;
import com.algo.trade.domain.PositionGroupStatus;
import com.algo.trade.domain.SpreadLeg;
import com.algo.trade.domain.UnderlyingSymbol;
import com.algo.trade.strategy.StrategyType;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Entity
@Table(name = "position_groups", indexes = {
        @Index(name = "idx_pg_group_id", columnList = "groupId", unique = true),
        @Index(name = "idx_pg_open", columnList = "open")
})
public class PositionGroupEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String groupId;

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "VARCHAR(50)", nullable = false)
    private StrategyType strategyType;

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "VARCHAR(20)", nullable = false)
    private UnderlyingSymbol underlying;

    @Column(nullable = false)
    private Instant entryTime;

    private boolean open = true;

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "VARCHAR(20)", nullable = false)
    private PositionGroupStatus status = PositionGroupStatus.OPEN;

    @Column(precision = 19, scale = 4)
    private BigDecimal pnl;

    private Instant exitTime;

    /** Source of P&L calculation: FILLED (from broker fills), RECONCILED (from mark prices), SYNTHETIC. */
    @Column(length = 20)
    private String pnlSource;

    /** Reason for exit (e.g., TRAILING_STOP, MAX_HOLD_TIME, RECONCILE-FLAT, MANUAL_FLATTEN_ALL). */
    @Column(length = 100)
    private String exitReason;

    @OneToMany(mappedBy = "positionGroup", cascade = CascadeType.ALL,
               fetch = FetchType.EAGER, orphanRemoval = true)
    private List<SpreadLegEntity> legs = new ArrayList<>();

    /** Effective SL % persisted at entry / last ATR recompute (audit). */
    @Column(precision = 10, scale = 4)
    private BigDecimal effectiveStopLossPercent;

    @Column(precision = 10, scale = 4)
    private BigDecimal effectiveTargetPercent;

    @Column(precision = 10, scale = 4)
    private BigDecimal effectiveTrailActivationPercent;

    @Column(precision = 10, scale = 4)
    private BigDecimal effectiveTrailGapPercent;

    @Column(precision = 10, scale = 4)
    private BigDecimal peakProfitPercent;

    /** IV rank at entry time — for vega-collapse exit on long-vol structures. */
    @Column(precision = 10, scale = 4)
    private BigDecimal entryIvRank;

    @Column(length = 200)
    private String partialExitLayers;

    /** Comma-separated broker order IDs for SL-M stop losses (survives JVM restart). */
    @Column(length = 500)
    private String brokerSlmOrderIds;

    /**
     * End of the post-event exit window for event-driven entries (long-vol structures).
     * NULL means the entry was not tied to a calendar event.
     */
    private Instant expectedEventEndTime;

    @Version
    private Long version;

    protected PositionGroupEntity() {}

    /** Build entity from domain record + resolved entry prices. */
    public static PositionGroupEntity from(PositionGroup group, Map<String, BigDecimal> entryPrices) {
        PositionGroupEntity entity = new PositionGroupEntity();
        entity.groupId = group.groupId();
        entity.strategyType = group.strategyType();
        entity.underlying = group.underlying();
        entity.entryTime = group.entryTime();
        entity.status = group.status();
        entity.open = group.status() == PositionGroupStatus.OPEN;
        for (SpreadLeg leg : group.legs()) {
            BigDecimal price = entryPrices.getOrDefault(leg.instrumentKey(), BigDecimal.ZERO);
            entity.legs.add(new SpreadLegEntity(entity, leg.instrumentKey(), leg.strike(),
                    leg.optionType(), leg.side(), leg.quantity(), leg.expiry(), price));
        }
        return entity;
    }

    public static PositionGroupEntity pending(PositionGroup group, Map<String, BigDecimal> entryPrices) {
        PositionGroupEntity entity = from(group, entryPrices);
        entity.status = PositionGroupStatus.PENDING;
        entity.open = false;
        return entity;
    }

    public void markOpen() {
        this.status = PositionGroupStatus.OPEN;
        this.open = true;
    }

    public void markFailed() {
        this.status = PositionGroupStatus.FAILED;
        this.open = false;
    }

    /** Mark this group as closed with final P&L. */
    public void close(BigDecimal finalPnl) {
        this.open = false;
        this.status = PositionGroupStatus.CLOSED;
        this.pnl = finalPnl;
        this.exitTime = Instant.now();
    }

    /** Mark this group as closed with final P&L, exit reason, and P&L source. */
    public void close(BigDecimal finalPnl, String exitReason, String pnlSource) {
        this.open = false;
        this.status = PositionGroupStatus.CLOSED;
        this.pnl = finalPnl;
        this.exitTime = Instant.now();
        this.exitReason = exitReason;
        this.pnlSource = pnlSource;
    }

    /** Reconstruct the immutable domain record from persisted state. */
    public PositionGroup toDomain() {
        List<SpreadLeg> domainLegs = legs.stream()
                .filter(SpreadLegEntity::isActive)
                .map(l -> new SpreadLeg(l.getInstrumentKey(), l.getStrike(),
                        l.getOptionType(), l.getOrderSide(), l.getQuantity(), l.getExpiry()))
                .toList();
        Map<String, BigDecimal> entryPrices = legs.stream()
                .filter(SpreadLegEntity::isActive)
                .collect(Collectors.toMap(SpreadLegEntity::getInstrumentKey, SpreadLegEntity::getEntryPrice));
        return new PositionGroup(groupId, strategyType, underlying, domainLegs, entryPrices, entryTime, status,
                peakProfitPercent, effectiveStopLossPercent, effectiveTargetPercent,
                effectiveTrailActivationPercent, effectiveTrailGapPercent);
    }

    public Long getId() { return id; }
    public String getGroupId() { return groupId; }
    public StrategyType getStrategyType() { return strategyType; }
    public UnderlyingSymbol getUnderlying() { return underlying; }
    public Instant getEntryTime() { return entryTime; }
    public boolean isOpen() { return open; }
    public PositionGroupStatus getStatus() { return status; }
    public BigDecimal getPnl() { return pnl; }
    public Instant getExitTime() { return exitTime; }
    public List<SpreadLegEntity> getLegs() { return legs; }

    public BigDecimal getEffectiveStopLossPercent() { return effectiveStopLossPercent; }
    public void setEffectiveStopLossPercent(BigDecimal v) { this.effectiveStopLossPercent = v; }
    public BigDecimal getEffectiveTargetPercent() { return effectiveTargetPercent; }
    public void setEffectiveTargetPercent(BigDecimal v) { this.effectiveTargetPercent = v; }
    public BigDecimal getEffectiveTrailActivationPercent() { return effectiveTrailActivationPercent; }
    public void setEffectiveTrailActivationPercent(BigDecimal v) { this.effectiveTrailActivationPercent = v; }
    public BigDecimal getEffectiveTrailGapPercent() { return effectiveTrailGapPercent; }
    public void setEffectiveTrailGapPercent(BigDecimal v) { this.effectiveTrailGapPercent = v; }
    public BigDecimal getPeakProfitPercent() { return peakProfitPercent; }
    public void setPeakProfitPercent(BigDecimal v) { this.peakProfitPercent = v; }
    public BigDecimal getEntryIvRank() { return entryIvRank; }
    public void setEntryIvRank(BigDecimal v) { this.entryIvRank = v; }
    public String getPartialExitLayers() { return partialExitLayers; }
    public void setPartialExitLayers(String v) { this.partialExitLayers = v; }

    public String getPnlSource() { return pnlSource; }
    public void setPnlSource(String v) { this.pnlSource = v; }
    public String getExitReason() { return exitReason; }
    public void setExitReason(String v) { this.exitReason = v; }
    public String getBrokerSlmOrderIds() { return brokerSlmOrderIds; }
    public void setBrokerSlmOrderIds(String v) { this.brokerSlmOrderIds = v; }
    public Instant getExpectedEventEndTime() { return expectedEventEndTime; }
    public void setExpectedEventEndTime(Instant v) { this.expectedEventEndTime = v; }
}
