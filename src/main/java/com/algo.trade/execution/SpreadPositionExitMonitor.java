package com.algo.trade.execution;

import com.algo.trade.domain.CandleClosedEvent;
import com.algo.trade.domain.Quote;
import com.algo.trade.domain.SpreadLeg;
import com.algo.trade.marketdata.MarketDataService;
import com.algo.trade.persistence.PositionGroupEntity;
import com.algo.trade.persistence.PositionGroupRepository;
import com.algo.trade.strategy.spread.SpreadStrategyRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Event-driven exit monitor for all multi-leg spread positions.
 *
 * <p>Mirrors {@link LivePositionExitMonitor} for single-leg trades.
 * On every candle close this monitor:
 * <ol>
 *   <li>Loads all open {@link PositionGroupEntity} records from the DB</li>
 *   <li>Fetches live quotes for each group's legs</li>
 *   <li>Delegates to the owning strategy's {@code shouldExit()} logic</li>
 *   <li>Calls {@code exitAllLegs()} which closes both the in-memory cache
 *       and the DB record, logging paper P&L</li>
 * </ol>
 *
 * <p>Spread positions survive JVM restarts because they are persisted to DB
 * on entry. On startup, each strategy's {@code @PostConstruct} restores
 * its open groups into the in-memory cache.
 */
@Component
public class SpreadPositionExitMonitor {

    private static final Logger log = LoggerFactory.getLogger(SpreadPositionExitMonitor.class);

    private final PositionGroupRepository positionGroupRepository;
    private final SpreadStrategyRegistry registry;
    private final MarketDataService marketDataService;
    private final TradingStateService tradingStateService;

    public SpreadPositionExitMonitor(PositionGroupRepository positionGroupRepository,
                                     SpreadStrategyRegistry registry,
                                     MarketDataService marketDataService,
                                     TradingStateService tradingStateService) {
        this.positionGroupRepository = positionGroupRepository;
        this.registry = registry;
        this.marketDataService = marketDataService;
        this.tradingStateService = tradingStateService;
    }

    @EventListener
    public void onCandleClose(CandleClosedEvent event) {
        if (!tradingStateService.isExitAllowed()) return;
        List<PositionGroupEntity> openGroups = positionGroupRepository.findByOpenTrue();
        if (openGroups.isEmpty()) return;

        for (PositionGroupEntity entity : openGroups) {
            try {
                registry.get(entity.getStrategyType()).ifPresentOrElse(
                        strategy -> evaluateGroup(entity, strategy),
                        () -> log.warn("[SpreadExitMonitor] No strategy registered for type {} — group {} orphaned",
                                entity.getStrategyType(), entity.getGroupId())
                );
            } catch (Exception e) {
                log.error("[SpreadExitMonitor] Error evaluating group {}: {}",
                        entity.getGroupId(), e.getMessage(), e);
            }
        }
    }

    private void evaluateGroup(PositionGroupEntity entity,
                               com.algo.trade.strategy.spread.AbstractSpreadStrategy strategy) {
        Map<String, BigDecimal> prices = fetchPrices(entity);
        if (prices.size() < entity.getLegs().size()) {
            log.warn("[SpreadExitMonitor] Missing quotes for group {} ({}/{} legs) — skipping",
                    entity.getGroupId(), prices.size(), entity.getLegs().size());
            return;
        }
        strategy.checkAndExit(entity.toDomain(), prices);
    }

    private Map<String, BigDecimal> fetchPrices(PositionGroupEntity entity) {
        List<String> keys = entity.getLegs().stream()
                .map(com.algo.trade.persistence.SpreadLegEntity::getInstrumentKey)
                .toList();
        Map<String, Quote> quotes = marketDataService.quotes(keys);
        Map<String, BigDecimal> prices = new HashMap<>();
        quotes.forEach((k, q) -> prices.put(k, q.lastPrice()));
        return prices;
    }
}
