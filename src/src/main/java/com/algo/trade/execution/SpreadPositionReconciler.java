package com.algo.trade.execution;

import com.algo.trade.broker.BrokerClient;
import com.algo.trade.config.SpreadTradingProperties;
import com.algo.trade.domain.OrderSide;
import com.algo.trade.domain.Position;
import com.algo.trade.domain.PositionGroupStatus;
import com.algo.trade.domain.SpreadLeg;
import com.algo.trade.marketdata.MarketDataService;
import com.algo.trade.notification.TelegramAlertService;
import com.algo.trade.persistence.PositionGroupEntity;
import com.algo.trade.persistence.PositionGroupRepository;
import com.algo.trade.persistence.SpreadLegEntity;
import com.algo.trade.strategy.spread.SpreadStrategyRegistry;
import java.math.BigDecimal;
import com.algo.trade.execution.exit.MarketSessionHelper;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Verifies open spread groups against broker positions; closes naked shorts when detected.
 */
@Component
public class SpreadPositionReconciler {

    private static final Logger log = LoggerFactory.getLogger(SpreadPositionReconciler.class);

    private final PositionGroupRepository positionGroupRepository;
    private final BrokerClient brokerClient;
    private final SpreadLegPlacementService legPlacement;
    private final TelegramAlertService telegramAlertService;
    private final SpreadTradingProperties spreadProperties;
    private final SpreadStrategyRegistry strategyRegistry;
    private final MarketDataService marketDataService;
    private final SpreadGroupLockRegistry groupLockRegistry;

    public SpreadPositionReconciler(PositionGroupRepository positionGroupRepository,
                                    BrokerClient brokerClient,
                                    SpreadLegPlacementService legPlacement,
                                    TelegramAlertService telegramAlertService,
                                    SpreadTradingProperties spreadProperties,
                                    SpreadStrategyRegistry strategyRegistry,
                                    MarketDataService marketDataService,
                                    SpreadGroupLockRegistry groupLockRegistry) {
        this.positionGroupRepository = positionGroupRepository;
        this.brokerClient = brokerClient;
        this.legPlacement = legPlacement;
        this.telegramAlertService = telegramAlertService;
        this.spreadProperties = spreadProperties;
        this.strategyRegistry = strategyRegistry;
        this.marketDataService = marketDataService;
        this.groupLockRegistry = groupLockRegistry;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        failStalePendingGroups();
        forceExitPriorDayOpenGroups();
    }

    /** OPEN groups from a prior IST session — force exit before normal monitoring resumes. */
    private void forceExitPriorDayOpenGroups() {
        LocalDate today = LocalDate.now(MarketSessionHelper.ist());
        List<PositionGroupEntity> openGroups = positionGroupRepository.findByOpenTrue();
        for (PositionGroupEntity group : openGroups) {
            if (group.getStatus() != PositionGroupStatus.OPEN || group.getEntryTime() == null) {
                continue;
            }
            LocalDate entryDay = LocalDate.ofInstant(group.getEntryTime(), MarketSessionHelper.ist());
            if (!entryDay.isBefore(today)) {
                continue;
            }
            log.error("[SpreadReconcile] Prior-day OPEN group {} entry={} — forcing exit on startup",
                    group.getGroupId(), group.getEntryTime());
            Map<String, BigDecimal> marks = new HashMap<>();
            group.getLegs().forEach(leg -> marks.put(leg.getInstrumentKey(), leg.getEntryPrice()));
            strategyRegistry.get(group.getStrategyType()).ifPresentOrElse(
                    strategy -> strategy.forceLiquidityExit(group, marks, "PRIOR_DAY_STARTUP_EXIT"),
                    () -> log.warn("[SpreadReconcile] No strategy for prior-day group {}", group.getGroupId()));
            telegramAlertService.systemAlert(String.format(
                    "⚠️ Prior-day spread force-exit\nGroup: %s\nEntry: %s",
                    group.getGroupId(), group.getEntryTime()));
        }
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.monitoring.SchedulerRegistry schedulerRegistry;

    @Scheduled(fixedDelayString = "${trading.spread.reconciliation-interval-ms:120000}")
    public void verifySpreadIntegrity() {
        if (!spreadProperties.reconciliationEnabled()) {
            return;
        }
        if (schedulerRegistry != null) {
            schedulerRegistry.recordRun("spreadReconciler");
        }
        try {
            doVerify();
        } catch (Exception ex) {
            log.warn("Spread reconciliation failed: {}", ex.getMessage());
            if (schedulerRegistry != null) {
                schedulerRegistry.recordError("spreadReconciler", ex.getMessage());
            }
        }
    }

    private void doVerify() {
        List<PositionGroupEntity> openGroups = positionGroupRepository.findByOpenTrue();
        if (openGroups.isEmpty()) {
            return;
        }
        Map<String, Position> brokerByKey = brokerClient.positions().stream()
                .collect(Collectors.toMap(Position::instrumentKey, p -> p, (a, b) -> a));

        for (PositionGroupEntity group : openGroups) {
            if (group.getStatus() != PositionGroupStatus.OPEN) {
                continue;
            }
            verifyGroup(group, brokerByKey);
        }
    }

    private void verifyGroup(PositionGroupEntity group, Map<String, Position> brokerByKey) {
        java.util.concurrent.locks.ReentrantLock lock = groupLockRegistry.getLock(group.getGroupId());
        if (!lock.tryLock()) {
            log.debug("[SpreadReconcile] Could not acquire lock for group {} — skipping", group.getGroupId());
            return;
        }
        try {
            List<SpreadLegEntity> sellLegs = group.getLegs().stream()
                    .filter(l -> l.getOrderSide() == OrderSide.SELL)
                    .toList();
            List<SpreadLegEntity> buyLegs = group.getLegs().stream()
                    .filter(l -> l.getOrderSide() == OrderSide.BUY)
                    .toList();

            for (SpreadLegEntity sell : sellLegs) {
                Position brokerPos = brokerByKey.get(sell.getInstrumentKey());
                int brokerQty = brokerPos == null ? 0 : brokerPos.quantity();
                if (brokerQty >= 0) {
                    continue;
                }
                int shortQty = Math.abs(brokerQty);
                boolean hasHedge = buyLegs.stream().anyMatch(b -> {
                    Position hp = brokerByKey.get(b.getInstrumentKey());
                    return hp != null && hp.quantity() > 0;
                });
                if (hasHedge) {
                    continue;
                }
                log.error("[SpreadReconcile] Naked short detected: group={} instrument={} qty={}",
                        group.getGroupId(), sell.getInstrumentKey(), shortQty);
                autoCloseNakedShort(group.getGroupId(), sell, shortQty);
            }

            closeIfFlatAtBroker(group, brokerByKey);
        } finally {
            lock.unlock();
        }
    }

    private void closeIfFlatAtBroker(PositionGroupEntity group, Map<String, Position> brokerByKey) {
        boolean anyBrokerLeg = group.getLegs().stream().anyMatch(leg -> {
            Position p = brokerByKey.get(leg.getInstrumentKey());
            return p != null && p.quantity() != 0;
        });
        if (anyBrokerLeg) {
            return;
        }
        List<String> keys = group.getLegs().stream().map(SpreadLegEntity::getInstrumentKey).toList();
        Map<String, BigDecimal> markPrices = new HashMap<>();
        marketDataService.quotes(keys).forEach((k, q) -> markPrices.put(k, q.lastPrice()));
        if (markPrices.isEmpty()) {
            group.getLegs().forEach(leg -> markPrices.put(leg.getInstrumentKey(), leg.getEntryPrice()));
        }
        log.info("[SpreadReconcile] Group {} flat at broker — closing in DB", group.getGroupId());
        strategyRegistry.get(group.getStrategyType()).ifPresent(strategy ->
                strategy.closeFlatAtBroker(group.toDomain(), markPrices));
    }

    private void autoCloseNakedShort(String groupId, SpreadLegEntity sellLeg, int shortQty) {
        SpreadLeg closeLeg = new SpreadLeg(
                sellLeg.getInstrumentKey(),
                sellLeg.getStrike(),
                sellLeg.getOptionType(),
                OrderSide.BUY,
                shortQty,
                sellLeg.getExpiry());
        SpreadLegPlacementService.PlacementResult result =
                legPlacement.placeLeg(closeLeg, groupId, "spread-reconcile", true);
        if (result.success()) {
            telegramAlertService.systemAlert(String.format(
                    "⚠️ Naked short auto-closed\nGroup: %s\nInstrument: %s\nQty: %d",
                    groupId, sellLeg.getInstrumentKey(), shortQty));
        } else {
            telegramAlertService.systemAlert(String.format(
                    "🚨 CRITICAL: Naked short auto-close FAILED\nGroup: %s\nInstrument: %s\nManual action required\nReason: %s",
                    groupId, sellLeg.getInstrumentKey(), result.reason()));
        }
    }

    private void failStalePendingGroups() {
        Instant cutoff = Instant.now().minus(
                spreadProperties.startupPendingMaxAge().toMinutes(), ChronoUnit.MINUTES);
        List<PositionGroupEntity> stale = positionGroupRepository.findByStatusAndEntryTimeBefore(
                PositionGroupStatus.PENDING, cutoff);
        for (PositionGroupEntity entity : stale) {
            entity.markFailed();
            positionGroupRepository.save(entity);
            log.warn("[SpreadReconcile] Marked stale PENDING group as FAILED: {}", entity.getGroupId());
        }
    }
}
