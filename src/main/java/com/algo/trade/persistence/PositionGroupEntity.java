package com.algo.trade.persistence;

import com.algo.trade.domain.PositionGroup;
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

    @Column(precision = 19, scale = 4)
    private BigDecimal pnl;

    private Instant exitTime;

    @OneToMany(mappedBy = "positionGroup", cascade = CascadeType.ALL,
               fetch = FetchType.EAGER, orphanRemoval = true)
    private List<SpreadLegEntity> legs = new ArrayList<>();

    protected PositionGroupEntity() {}

    /** Build entity from domain record + resolved entry prices. */
    public static PositionGroupEntity from(PositionGroup group, Map<String, BigDecimal> entryPrices) {
        PositionGroupEntity entity = new PositionGroupEntity();
        entity.groupId = group.groupId();
        entity.strategyType = group.strategyType();
        entity.underlying = group.underlying();
        entity.entryTime = group.entryTime();
        entity.open = true;
        for (SpreadLeg leg : group.legs()) {
            BigDecimal price = entryPrices.getOrDefault(leg.instrumentKey(), BigDecimal.ZERO);
            entity.legs.add(new SpreadLegEntity(entity, leg.instrumentKey(), leg.strike(),
                    leg.optionType(), leg.side(), leg.quantity(), leg.expiry(), price));
        }
        return entity;
    }

    /** Mark this group as closed with final P&L. */
    public void close(BigDecimal finalPnl) {
        this.open = false;
        this.pnl = finalPnl;
        this.exitTime = Instant.now();
    }

    /** Reconstruct the immutable domain record from persisted state. */
    public PositionGroup toDomain() {
        List<SpreadLeg> domainLegs = legs.stream()
                .map(l -> new SpreadLeg(l.getInstrumentKey(), l.getStrike(),
                        l.getOptionType(), l.getOrderSide(), l.getQuantity(), l.getExpiry()))
                .toList();
        Map<String, BigDecimal> entryPrices = legs.stream()
                .collect(Collectors.toMap(SpreadLegEntity::getInstrumentKey, SpreadLegEntity::getEntryPrice));
        return new PositionGroup(groupId, strategyType, underlying, domainLegs, entryPrices, entryTime, open);
    }

    public Long getId() { return id; }
    public String getGroupId() { return groupId; }
    public StrategyType getStrategyType() { return strategyType; }
    public UnderlyingSymbol getUnderlying() { return underlying; }
    public Instant getEntryTime() { return entryTime; }
    public boolean isOpen() { return open; }
    public BigDecimal getPnl() { return pnl; }
    public Instant getExitTime() { return exitTime; }
    public List<SpreadLegEntity> getLegs() { return legs; }
}
