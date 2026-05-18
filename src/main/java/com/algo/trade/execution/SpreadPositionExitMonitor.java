package com.algo.trade.execution;

import com.algo.trade.config.SpreadTradingProperties;
import com.algo.trade.domain.CandleClosedEvent;
import com.algo.trade.domain.IndexType;
import com.algo.trade.domain.PositionGroupStatus;
import com.algo.trade.domain.Quote;
import com.algo.trade.execution.exit.MarketSessionHelper;
import com.algo.trade.execution.exit.LiquidityEmergencyGate;
import com.algo.trade.marketdata.LiveInstrumentCache;
import com.algo.trade.persistence.PositionGroupEntity;
import com.algo.trade.persistence.PositionGroupRepository;
import com.algo.trade.strategy.StrategyType;
import com.algo.trade.strategy.spread.SpreadStrategyRegistry;
import com.algo.trade.strategy.spread.StraddleAdjustmentEngine;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Event-driven exit monitor for multi-leg spreads — shared policy, backup scheduler,
 * optional straddle adjustment before exit checks.
 */
@Component
public class SpreadPositionExitMonitor {

    private static final Logger log = LoggerFactory.getLogger(SpreadPositionExitMonitor.class);

    private final PositionGroupRepository positionGroupRepository;
    private final SpreadStrategyRegistry registry;
    private final com.algo.trade.marketdata.MarketDataService marketDataService;
    private final TradingStateService tradingStateService;
    private final com.algo.trade.monitoring.ErrorEventService errorEventService;
    private final SpreadTradingProperties spreadProperties;
    private final LiveInstrumentCache liveInstrumentCache;
    private final StraddleAdjustmentEngine straddleAdjustmentEngine;
    private final com.algo.trade.monitoring.SchedulerRegistry schedulerRegistry;
    private final LiquidityEmergencyGate liquidityEmergencyGate;
    private final SpreadGroupLockRegistry groupLockRegistry;

    public SpreadPositionExitMonitor(PositionGroupRepository positionGroupRepository,
                                     SpreadStrategyRegistry registry,
                                     com.algo.trade.marketdata.MarketDataService marketDataService,
                                     TradingStateService tradingStateService,
                                     com.algo.trade.monitoring.ErrorEventService errorEventService,
                                     SpreadTradingProperties spreadProperties,
                                     LiveInstrumentCache liveInstrumentCache,
                                     StraddleAdjustmentEngine straddleAdjustmentEngine,
                                     com.algo.trade.monitoring.SchedulerRegistry schedulerRegistry,
                                     LiquidityEmergencyGate liquidityEmergencyGate,
                                     SpreadGroupLockRegistry groupLockRegistry) {
        this.positionGroupRepository = positionGroupRepository;
        this.registry = registry;
        this.marketDataService = marketDataService;
        this.tradingStateService = tradingStateService;
        this.errorEventService = errorEventService;
        this.spreadProperties = spreadProperties;
        this.liveInstrumentCache = liveInstrumentCache;
        this.straddleAdjustmentEngine = straddleAdjustmentEngine;
        this.schedulerRegistry = schedulerRegistry;
        this.liquidityEmergencyGate = liquidityEmergencyGate;
        this.groupLockRegistry = groupLockRegistry;
        if (schedulerRegistry != null) {
            schedulerRegistry.register("spreadExitBackup", "Spread exit backup (60s)", 60_000,
                    this::scheduledBackupCheck);
        }
    }

    @EventListener
    public void onCandleClose(CandleClosedEvent event) {
        if (!tradingStateService.isExitAllowed()) {
            return;
        }
        evaluateAllOpen();
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 25_000)
    public void scheduledBackupCheck() {
        if (!spreadProperties.exitBackupEnabled()) {
            return;
        }
        if (schedulerRegistry != null && !schedulerRegistry.isEnabled("spreadExitBackup")) {
            return;
        }
        if (schedulerRegistry != null) {
            schedulerRegistry.recordRun("spreadExitBackup");
        }
        if (!MarketSessionHelper.isRegularSessionNow()) {
            return;
        }
        if (!tradingStateService.isExitAllowed()) {
            return;
        }
        log.debug("[SpreadExitMonitor-Backup] Running scheduled backup");
        evaluateAllOpen();
    }

    private void evaluateAllOpen() {
        List<PositionGroupEntity> openGroups;
        try {
            openGroups = positionGroupRepository.findByOpenTrue();
        } catch (Exception e) {
            log.warn("[SpreadExitMonitor] DB unavailable — skipping: {}", e.getMessage());
            return;
        }
        for (PositionGroupEntity entity : openGroups) {
            if (entity.getStatus() != PositionGroupStatus.OPEN) {
                continue;
            }
            try {
                registry.get(entity.getStrategyType()).ifPresentOrElse(
                        strategy -> evaluateGroup(entity, strategy),
                        () -> log.warn("[SpreadExitMonitor] No strategy for {} — group {} orphaned",
                                entity.getStrategyType(), entity.getGroupId()));
            } catch (Exception e) {
                log.error("[SpreadExitMonitor] Error evaluating group {}: {}",
                        entity.getGroupId(), e.getMessage(), e);
                errorEventService.critical("SpreadExitMonitor",
                        "Error evaluating group " + entity.getGroupId() + ": " + e.getMessage(), e);
            }
        }
    }

    private void evaluateGroup(PositionGroupEntity entity,
                                 com.algo.trade.strategy.spread.AbstractSpreadStrategy strategy) {
        java.util.concurrent.locks.ReentrantLock lock = groupLockRegistry.getLock(entity.getGroupId());
        if (!lock.tryLock()) {
            log.debug("[SpreadExitMonitor] Could not acquire lock for group {} — skipping", entity.getGroupId());
            return;
        }
        try {
            PositionGroupEntity fresh = positionGroupRepository.findByGroupId(entity.getGroupId()).orElse(null);
            if (fresh == null || fresh.getStatus() != PositionGroupStatus.OPEN) {
                return;
            }

            Map<String, Quote> quotes = fetchQuotes(fresh);
            Map<String, BigDecimal> prices = new HashMap<>();
            quotes.forEach((k, q) -> {
                if (q != null && q.lastPrice() != null) {
                    prices.put(k, q.lastPrice());
                }
            });

            tryStraddleAdjustment(fresh, strategy, prices);
            quotes = fetchQuotes(fresh);
            prices.clear();
            quotes.forEach((k, q) -> {
                if (q != null && q.lastPrice() != null) {
                    prices.put(k, q.lastPrice());
                }
            });

            if (prices.isEmpty()) {
                handleNoQuotes(fresh, strategy);
                return;
            }

            var liquidity = liquidityEmergencyGate.checkSpreadEmergency(fresh, quotes);
            if (liquidity.isPresent()) {
                var signal = liquidity.get();
                log.warn("[SpreadExitMonitor] {} — group={} {}", signal.reason(), fresh.getGroupId(), signal.detail());
                if (signal.force()) {
                    forceLiquidityExit(fresh, strategy, prices, signal.reason());
                    return;
                }
            }

            strategy.checkAndExit(fresh, prices, quotes);
        } finally {
            lock.unlock();
        }
    }

    private void handleNoQuotes(PositionGroupEntity fresh,
                                com.algo.trade.strategy.spread.AbstractSpreadStrategy strategy) {
        var missing = liquidityEmergencyGate.checkSpreadMissingQuote(fresh);
        if (missing.isPresent() && missing.get().force()) {
            var signal = missing.get();
            log.warn("[SpreadExitMonitor] {} — group={} {}", signal.reason(), fresh.getGroupId(), signal.detail());
            errorEventService.high("SpreadExitMonitor",
                    signal.reason() + " on " + fresh.getGroupId() + ": " + signal.detail());
            Map<String, BigDecimal> marks = entryMarkPrices(fresh);
            forceLiquidityExit(fresh, strategy, marks, signal.reason());
            return;
        }
        log.warn("[SpreadExitMonitor] No quotes for group {} — exit deferred", fresh.getGroupId());
    }

    private void forceLiquidityExit(PositionGroupEntity fresh,
                                    com.algo.trade.strategy.spread.AbstractSpreadStrategy strategy,
                                    Map<String, BigDecimal> prices,
                                    String reason) {
        strategy.forceLiquidityExit(fresh, prices, reason);
    }

    private static Map<String, BigDecimal> entryMarkPrices(PositionGroupEntity fresh) {
        Map<String, BigDecimal> marks = new HashMap<>();
        fresh.getLegs().forEach(leg -> marks.put(leg.getInstrumentKey(), leg.getEntryPrice()));
        return marks;
    }

    private void tryStraddleAdjustment(PositionGroupEntity entity,
                                       com.algo.trade.strategy.spread.AbstractSpreadStrategy strategy,
                                       Map<String, BigDecimal> prices) {
        if (!spreadProperties.straddleAdjustmentEnabled()) {
            return;
        }
        StrategyType type = entity.getStrategyType();
        if (type != StrategyType.SHORT_STRADDLE && type != StrategyType.SHORT_STRANGLE) {
            return;
        }
        IndexType indexType = IndexType.from(entity.getUnderlying());
        BigDecimal spot = BigDecimal.valueOf(liveInstrumentCache.getFuturesPrice(indexType));
        if (spot.signum() <= 0) {
            return;
        }
        var group = strategy.getActivePositions().get(entity.getGroupId());
        if (group == null) {
            group = entity.toDomain();
        }
        straddleAdjustmentEngine.evaluateAdjustment(group, spot,
                spreadProperties.straddleAdjustmentTriggerPoints(), indexType);
    }

    private Map<String, Quote> fetchQuotes(PositionGroupEntity entity) {
        List<String> keys = entity.getLegs().stream()
                .map(com.algo.trade.persistence.SpreadLegEntity::getInstrumentKey)
                .toList();
        return marketDataService.quotes(keys);
    }
}
