package com.algo.trade.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Month-memory episode store (design §14.4, §17). */
public interface MemoryEpisodeRepository extends JpaRepository<MemoryEpisodeEntity, Long> {

    List<MemoryEpisodeEntity> findByIndexNameAndSideAndPattern(String indexName, String side, String pattern);

    List<MemoryEpisodeEntity> findByTradeDate(String tradeDate);
}
