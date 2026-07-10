package com.algo.trade.strategy.oimomentum;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Persists anchor-time trade outcomes for cross-session bias learning.
 * The TimeBiasEngine queries recent rows (last 5 days) on startup to preload
 * bias memory — so the bot remembers "9:20 is usually bullish" across restarts.
 */
@Entity
@Table(name = "anchor_bias_history", indexes = {
        @Index(name = "idx_abh_index_anchor", columnList = "indexType,anchorTime")
})
public class AnchorBiasHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String indexType;

    @Column(nullable = false)
    private LocalTime anchorTime;

    @Column(nullable = false)
    private int direction; // +1 bull, -1 bear

    @Column(nullable = false)
    private boolean profitable;

    @Column(nullable = false)
    private LocalDate tradeDate;

    public AnchorBiasHistory() {}

    public AnchorBiasHistory(String indexType, LocalTime anchorTime, int direction,
                             boolean profitable, LocalDate tradeDate) {
        this.indexType = indexType;
        this.anchorTime = anchorTime;
        this.direction = direction;
        this.profitable = profitable;
        this.tradeDate = tradeDate;
    }

    public Long getId() { return id; }
    public String getIndexType() { return indexType; }
    public LocalTime getAnchorTime() { return anchorTime; }
    public int getDirection() { return direction; }
    public boolean isProfitable() { return profitable; }
    public LocalDate getTradeDate() { return tradeDate; }
}
