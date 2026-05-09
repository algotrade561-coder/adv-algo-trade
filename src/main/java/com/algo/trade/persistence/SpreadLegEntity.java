package com.algo.trade.persistence;

import com.algo.trade.domain.OptionType;
import com.algo.trade.domain.OrderSide;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "spread_legs")
public class SpreadLegEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "position_group_id", nullable = false)
    private PositionGroupEntity positionGroup;

    @Column(nullable = false)
    private String instrumentKey;

    private int strike;

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "VARCHAR(5)")
    private OptionType optionType;

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "VARCHAR(5)")
    private OrderSide orderSide;

    private int quantity;
    private LocalDate expiry;

    @Column(precision = 19, scale = 4, nullable = false)
    private BigDecimal entryPrice;

    protected SpreadLegEntity() {}

    public SpreadLegEntity(PositionGroupEntity positionGroup,
                           String instrumentKey,
                           int strike,
                           OptionType optionType,
                           OrderSide orderSide,
                           int quantity,
                           LocalDate expiry,
                           BigDecimal entryPrice) {
        this.positionGroup = positionGroup;
        this.instrumentKey = instrumentKey;
        this.strike = strike;
        this.optionType = optionType;
        this.orderSide = orderSide;
        this.quantity = quantity;
        this.expiry = expiry;
        this.entryPrice = entryPrice;
    }

    public Long getId() { return id; }
    public PositionGroupEntity getPositionGroup() { return positionGroup; }
    public String getInstrumentKey() { return instrumentKey; }
    public int getStrike() { return strike; }
    public OptionType getOptionType() { return optionType; }
    public OrderSide getOrderSide() { return orderSide; }
    public int getQuantity() { return quantity; }
    public LocalDate getExpiry() { return expiry; }
    public BigDecimal getEntryPrice() { return entryPrice; }
}
