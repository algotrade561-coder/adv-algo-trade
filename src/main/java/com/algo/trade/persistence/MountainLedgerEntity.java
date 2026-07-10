package com.algo.trade.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Market-Memory V5 §14.1–2 — WEEK memory: per strike+expiry mountain (cumulative OI build) and
 * cost-basis pain ledger ("where the money sits"). One row per INDEX:strike:TYPE:expiry, upserted
 * every ~5 min by MarketMemoryEngine so the week memory SURVIVES RESTARTS; rows die at expiry.
 * Tiny by design (≤ a few hundred rows) — never per-tick data (design §17).
 */
@Entity
@Table(name = "mountain_ledger")
public class MountainLedgerEntity {

    /** "NIFTY:24500:PE:2026-07-14" */
    @Id
    private String id;
    private String indexName;
    private int strike;
    private String optionType;
    /** ISO date of the contract expiry — rows with expiry < today are purged. */
    private String expiry;
    /** Cumulative contracts WRITTEN this expiry cycle (sum of positive OI deltas). */
    private long cumBuild;
    /** Weighted basis for the writers' average sale premium: sum(deltaOI x premium). */
    private double writtenValue;
    /** Latest observed OI + premium (for restart continuity). */
    private long lastOi;
    private double lastPremium;
    /** (currentPremium - avgWritePremium)/avgWritePremium x100 — writers' live pain (+ = underwater). */
    private double painPct;
    /** Warm-start baselines (design §14.5): yesterday's learned normals seed today's open so the
     *  strike is armed from 09:25 instead of blind for the 25-min warmup. */
    private double madRet;
    private double oiMed;
    private double oiMad;
    private Instant updatedAt;

    protected MountainLedgerEntity() { }

    public MountainLedgerEntity(String id, String indexName, int strike, String optionType, String expiry) {
        this.id = id; this.indexName = indexName; this.strike = strike;
        this.optionType = optionType; this.expiry = expiry;
    }

    public String getId() { return id; }
    public String getIndexName() { return indexName; }
    public int getStrike() { return strike; }
    public String getOptionType() { return optionType; }
    public String getExpiry() { return expiry; }
    public long getCumBuild() { return cumBuild; }
    public double getWrittenValue() { return writtenValue; }
    public long getLastOi() { return lastOi; }
    public double getLastPremium() { return lastPremium; }
    public double getPainPct() { return painPct; }
    public Instant getUpdatedAt() { return updatedAt; }

    public double avgWritePremium() { return cumBuild > 0 ? writtenValue / cumBuild : 0; }
    public double getMadRet() { return madRet; }
    public double getOiMed() { return oiMed; }
    public double getOiMad() { return oiMad; }

    public void update(long cumBuild, double writtenValue, long lastOi, double lastPremium, double painPct,
                       double madRet, double oiMed, double oiMad) {
        this.cumBuild = cumBuild; this.writtenValue = writtenValue;
        this.lastOi = lastOi; this.lastPremium = lastPremium; this.painPct = painPct;
        this.madRet = madRet; this.oiMed = oiMed; this.oiMad = oiMad;
        this.updatedAt = Instant.now();
    }
}
