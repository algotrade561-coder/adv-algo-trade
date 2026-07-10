package com.algo.trade.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Market-Memory V5 §14.5 — analog-days library: ONE row per index per day, written at EOD.
 * The analog matcher (gated: needs weeks of rows) compares today-as-it-unfolds against these
 * fingerprints to set the day's avalanche budget/expectations. 2-3 rows/day — negligible space.
 */
@Entity
@Table(name = "day_fingerprint")
public class DayFingerprintEntity {

    /** "2026-07-08:NIFTY" */
    @Id
    private String id;
    private String tradeDate;
    private String indexName;
    /** Days to the index's nearest weekly/monthly expiry at that date. */
    private int daysToExpiry;
    private int avalanchesSeen;        // AVALANCHE state ignitions across the index's strikes
    private int episodesClosed;        // memory-pattern trades closed
    private double episodesNetPct;     // sum of their net %
    private long totalOiBuild;         // sum of positive OI deltas across strikes (activity proxy)
    private double maxWriterPainPct;   // deepest writer pain seen
    private int battleMinutes;         // minutes with >=1 strike in BATTLE
    private Instant writtenAt;

    protected DayFingerprintEntity() { }

    public DayFingerprintEntity(String tradeDate, String indexName, int daysToExpiry,
                                int avalanchesSeen, int episodesClosed, double episodesNetPct,
                                long totalOiBuild, double maxWriterPainPct, int battleMinutes) {
        this.id = tradeDate + ":" + indexName;
        this.tradeDate = tradeDate; this.indexName = indexName; this.daysToExpiry = daysToExpiry;
        this.avalanchesSeen = avalanchesSeen; this.episodesClosed = episodesClosed;
        this.episodesNetPct = episodesNetPct; this.totalOiBuild = totalOiBuild;
        this.maxWriterPainPct = maxWriterPainPct; this.battleMinutes = battleMinutes;
        this.writtenAt = Instant.now();
    }

    public String getId() { return id; }
    public String getTradeDate() { return tradeDate; }
    public String getIndexName() { return indexName; }
    public int getDaysToExpiry() { return daysToExpiry; }
    public int getAvalanchesSeen() { return avalanchesSeen; }
    public int getEpisodesClosed() { return episodesClosed; }
    public double getEpisodesNetPct() { return episodesNetPct; }
    public long getTotalOiBuild() { return totalOiBuild; }
    public double getMaxWriterPainPct() { return maxWriterPainPct; }
    public int getBattleMinutes() { return battleMinutes; }
    public Instant getWrittenAt() { return writtenAt; }
}
