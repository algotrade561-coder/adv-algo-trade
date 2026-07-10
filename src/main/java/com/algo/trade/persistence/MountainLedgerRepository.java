package com.algo.trade.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Week-memory persistence for the Market-Memory mountain/pain ledger (design §14.1–2, §17). */
public interface MountainLedgerRepository extends JpaRepository<MountainLedgerEntity, String> {

    List<MountainLedgerEntity> findByIndexName(String indexName);

    /** Purge dead contracts — rows whose expiry (ISO yyyy-MM-dd) is before the given date. */
    void deleteByExpiryLessThan(String isoDate);
}
