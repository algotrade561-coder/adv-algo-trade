package com.algo.trade.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Market-Memory V5 §14.4 — MONTH memory raw material: one row per closed memory-pattern trade.
 * The scoreboard learner (gated: runs only once >=2-3 weeks of rows exist) reads THIS table joined
 * with the decisions CSV fingerprints to learn P(win | context). Never resets; learner applies
 * exponential decay at read time. Tiny (~10-50 rows/day — design §17).
 */
@Entity
@Table(name = "memory_episode", indexes = {
        @Index(name = "idx_mem_episode_day", columnList = "tradeDate"),
        @Index(name = "idx_mem_episode_key", columnList = "indexName, side, pattern")
})
public class MemoryEpisodeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String tradeDate;      // IST yyyy-MM-dd
    private String indexName;
    private String side;           // CE | PE
    private String pattern;        // AVALANCHE (more patterns later)
    private int strike;
    private double netPct;         // premium % net of the trade
    /** Context at close (entry fingerprint lives in the decisions CSV, joined by ts). */
    private double painPct;
    private long mountainBuild;
    private Instant closedAt;

    protected MemoryEpisodeEntity() { }

    public MemoryEpisodeEntity(String tradeDate, String indexName, String side, String pattern,
                               int strike, double netPct, double painPct, long mountainBuild) {
        this.tradeDate = tradeDate; this.indexName = indexName; this.side = side;
        this.pattern = pattern; this.strike = strike; this.netPct = netPct;
        this.painPct = painPct; this.mountainBuild = mountainBuild;
        this.closedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getTradeDate() { return tradeDate; }
    public String getIndexName() { return indexName; }
    public String getSide() { return side; }
    public String getPattern() { return pattern; }
    public int getStrike() { return strike; }
    public double getNetPct() { return netPct; }
    public double getPainPct() { return painPct; }
    public long getMountainBuild() { return mountainBuild; }
    public Instant getClosedAt() { return closedAt; }
}
