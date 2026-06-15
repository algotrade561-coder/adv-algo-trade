package com.algo.trade.execution;

import com.algo.trade.broker.BrokerClient;
import com.algo.trade.domain.OrderResponse;
import com.algo.trade.domain.OrderStatus;
import com.algo.trade.persistence.OrderEntity;
import com.algo.trade.persistence.OrderRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Background watchdog that polls broker for pending limit order fills.
 * Runs every 2 seconds. When a pending order transitions to COMPLETE,
 * it creates the TradeEntity via ExecutionEngine so exit monitors
 * (max hold, trailing stop, forced exit) can manage the position.
 */
@Service
public class OrderFillWatchdog {

    private static final Logger log = LoggerFactory.getLogger(OrderFillWatchdog.class);

    private final OrderRepository orderRepository;
    private final BrokerClient brokerClient;
    private final ExecutionEngine executionEngine;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.monitoring.SchedulerRegistry schedulerRegistry;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.notification.TelegramAlertService telegramAlertService;

    /** Multi-user EXIT copy — watchdog exit fills bypass ExecutionEngine.doCloseTrade.
     *  @Lazy avoids the OrderFillWatchdog → SignalCopyService → … → ExecutionEngine cycle. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private com.algo.trade.multiuser.SignalCopyService signalCopyService;

    /**
     * 2 Jun 2026 — emit ExitEvent to the unified tune pipeline when the
     * watchdog closes a trade. Manual exit orders (and any non-monitor-driven
     * close) previously skipped tune-event recording entirely; this wires
     * them in.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.tuning.recorder.TuningEventRecorder tuningEventRecorder;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.tuning.adapter.strategies.OiShiftTrapCaptureAdapter oiShiftTrapCaptureAdapter;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.tuning.adapter.strategies.OiMomentumCaptureAdapter oiMomentumCaptureAdapter;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.tuning.infra.MaeMfeTracker maeMfeTracker;

    /** Prevents concurrent watchdog runs from creating duplicate trades. */
    private final java.util.concurrent.atomic.AtomicBoolean checkInProgress =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** Tracks orders currently being processed to prevent duplicate handling. */
    private final java.util.Set<String> processingOrders = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public OrderFillWatchdog(OrderRepository orderRepository, BrokerClient brokerClient,
                             ExecutionEngine executionEngine) {
        this.orderRepository = orderRepository;
        this.brokerClient = brokerClient;
        this.executionEngine = executionEngine;
    }

    @Scheduled(fixedDelay = 2000, initialDelay = 5000)
    public void checkPendingOrders() {
        if (!checkInProgress.compareAndSet(false, true)) {
            log.debug("OrderFillWatchdog: previous check still running, skipping");
            return;
        }
        if (schedulerRegistry != null) schedulerRegistry.recordRun("orderFillWatchdog");
        try {
            List<OrderEntity> pending = orderRepository.findByStatusIn(
                    List.of(OrderStatus.OPEN, OrderStatus.NEW));
            if (pending.isEmpty()) {
                // Safety net: check for COMPLETE BUY orders that have no matching trade
                reconcileOrphanedFilledOrders();
                return;
            }

            log.debug("OrderFillWatchdog checking {} pending orders", pending.size());

            for (OrderEntity order : pending) {
                try {
                    // Multi-user: poll the broker AS THE ORDER'S OWNER. The watchdog runs on
                    // a scheduler thread (no UserContext), so without this the status lookup
                    // would use the primary/file token — a non-primary user's order id does
                    // not exist in that account and their fills would NEVER be detected.
                    Long ownerId = order.getUserId();
                    if (ownerId != null) {
                        com.algo.trade.multiuser.UserContext.runAs(ownerId, () -> checkOrder(order));
                    } else {
                        checkOrder(order); // legacy/pre-multiuser order — current behavior
                    }
                } catch (org.springframework.orm.ObjectOptimisticLockingFailureException
                         | org.hibernate.StaleObjectStateException ex) {
                    // 4 Jun 2026 PM: Hibernate optimistic-lock race between watchdog
                    // poll and concurrent OrderEntity save. checkOrder is idempotent
                    // and the next 1s poll will retry — log DEBUG, not WARN, so we
                    // don't trigger ops alerts on benign races. Today's 09:20 orphan
                    // recoveries fell out of THIS race, not a deeper bug.
                    log.debug("OrderFillWatchdog optimistic-lock race on order {} — will retry next cycle",
                            order.getClientOrderId());
                } catch (Exception ex) {
                    log.warn("OrderFillWatchdog failed for order {}: {}", order.getClientOrderId(), ex.getMessage());
                }
            }
        } finally {
            checkInProgress.set(false);
        }
    }

    /**
     * Safety net: find COMPLETE BUY orders from today that have no matching TradeEntity.
     * Only creates a trade if the broker still has an open position for that instrument.
     */
    private void reconcileOrphanedFilledOrders() {
        try {
            java.time.Instant todayStart = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"))
                    .atStartOfDay(java.time.ZoneId.of("Asia/Kolkata")).toInstant();
            List<OrderEntity> filledToday = orderRepository.findBySideAndUpdatedAtBetween(
                    com.algo.trade.domain.OrderSide.BUY.name(), todayStart, java.time.Instant.now());

            if (filledToday.stream().noneMatch(o -> o.getStatus() == OrderStatus.COMPLETE && o.getFilledQuantity() > 0)) {
                return; // nothing to reconcile
            }

            // Multi-user: positions are per broker account, so fetch them per ORDER OWNER.
            // Cache one position snapshot per userId to avoid hammering the API.
            java.util.Map<Long, java.util.Set<String>> activeByUser = new java.util.HashMap<>();

            for (OrderEntity order : filledToday) {
                if (order.getStatus() != OrderStatus.COMPLETE) continue;
                if (order.getClientOrderId().startsWith("EXIT-")) continue;
                if (order.isTradeMaterialized()) continue;
                if (order.getFilledQuantity() <= 0) continue;
                if (order.getAverageFillPrice() == null || order.getAverageFillPrice().signum() <= 0) continue;

                String instrumentKey = order.getInstrumentKey();

                // Only create trade if THE OWNER'S broker account still has an open position
                Long ownerKey = order.getUserId(); // null = legacy/default session
                java.util.Set<String> activeInstruments = activeByUser.computeIfAbsent(ownerKey, uid -> {
                    try {
                        final List<com.algo.trade.domain.Position>[] pos = new List[]{ List.<com.algo.trade.domain.Position>of() };
                        if (uid != null) {
                            com.algo.trade.multiuser.UserContext.runAs(uid, () -> pos[0] = brokerClient.positions());
                        } else {
                            pos[0] = brokerClient.positions();
                        }
                        return pos[0].stream()
                                .filter(p -> p.quantity() > 0)
                                .map(com.algo.trade.domain.Position::instrumentKey)
                                .collect(java.util.stream.Collectors.toSet());
                    } catch (Exception ex) {
                        return java.util.Set.of(); // can't verify this user — skip their orders
                    }
                });
                if (!activeInstruments.contains(instrumentKey)) continue;

                // Check if a trade already exists for this instrument
                boolean tradeExists = !executionEngine.findOpenTradesByInstrument(instrumentKey).isEmpty();

                if (!tradeExists) {
                    log.warn("OrderFillWatchdog: orphaned filled order — creating trade: clientOrderId={}, instrument={}, price={}",
                            order.getClientOrderId(), instrumentKey, order.getAverageFillPrice());
                    // Run as the order's owner so the recovered trade is tagged with the
                    // right user_id + broker account (tagOwnership reads UserContext).
                    if (ownerKey != null) {
                        com.algo.trade.multiuser.UserContext.runAs(ownerKey, () -> executionEngine.openTradeFromFilledOrder(order));
                    } else {
                        executionEngine.openTradeFromFilledOrder(order);
                    }
                }
            }
        } catch (Exception ex) {
            log.warn("OrderFillWatchdog reconciliation failed: {}", ex.getMessage());
        }
    }

    private static final long MAX_FILL_WAIT_MINUTES = 1; // fallback if GlobalConfig unavailable

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.config.GlobalConfigService globalConfigService;

    private long getMaxFillWaitMinutes() {
        return globalConfigService != null ? globalConfigService.getLimitOrderCancelMinutes() : MAX_FILL_WAIT_MINUTES;
    }

    private void checkOrder(OrderEntity order) {
        // Guard: skip if trade was already created from this order
        if (order.isTradeMaterialized()) {
            log.debug("OrderFillWatchdog skipping already-materialized order: {}", order.getClientOrderId());
            return;
        }

        String brokerOrderId = order.getBrokerOrderId();
        if (brokerOrderId == null || brokerOrderId.isBlank()) {
            log.debug("OrderFillWatchdog skipping order without broker ID: {}", order.getClientOrderId());
            return;
        }

        // Auto-cancel stale unfilled orders after MAX_FILL_WAIT_MINUTES
        if (order.getOrderPlacedAt() != null) {
            long waitMinutes = java.time.Duration.between(order.getOrderPlacedAt(), java.time.Instant.now()).toMinutes();
            if (waitMinutes >= getMaxFillWaitMinutes()) {
                log.warn("OrderFillWatchdog: order expired after {}min — cancelling: clientOrderId={}, brokerOrderId={}",
                        waitMinutes, order.getClientOrderId(), brokerOrderId);
                try {
                    brokerClient.cancelOrder(brokerOrderId);
                } catch (Exception ex) {
                    log.warn("OrderFillWatchdog: cancel failed for {}: {}", brokerOrderId, ex.getMessage());
                }
                saveOrderWithRetry(order, OrderStatus.CANCELLED);
                // Release entry gate — the order is dead, allow new entries
                executionEngine.releaseEntryInFlightGate();
                return;
            }
        }

        Optional<OrderResponse> polled = brokerClient.orderStatus(brokerOrderId);
        if (polled.isEmpty()) {
            log.debug("OrderFillWatchdog: no status returned for brokerOrderId={}", brokerOrderId);
            return;
        }

        OrderResponse latest = polled.get();

        if (latest.status() == OrderStatus.COMPLETE && latest.filledQuantity() > 0) {
            log.info("OrderFillWatchdog: order filled! clientOrderId={}, brokerOrderId={}, filledQty={}, avgPrice={}",
                    order.getClientOrderId(), brokerOrderId, latest.filledQuantity(),
                    latest.averageFillPrice().orElse(null));

            order.setStatus(OrderStatus.COMPLETE);
            order.setFilledQuantity(latest.filledQuantity());
            order.setAverageFillPrice(latest.averageFillPrice().orElse(null));
            order.setUpdatedAt(latest.updatedAt());
            saveOrderWithRetry(order, null);

            // Only create TradeEntity for BUY (entry) orders.
            // SELL (exit) orders need to close the existing trade instead.
            if (order.getClientOrderId().startsWith("EXIT-")) {
                closeTradeFromFilledExitOrder(order);
            } else {
                // Guard against duplicate trade creation if watchdog runs twice before DB commits
                if (!processingOrders.add(order.getClientOrderId())) {
                    log.debug("OrderFillWatchdog: order {} already being processed, skipping", order.getClientOrderId());
                    return;
                }
                try {
                    // Double-check: does a trade already exist for this instrument?
                    if (!executionEngine.findOpenTradesByInstrument(order.getInstrumentKey()).isEmpty()) {
                        log.info("OrderFillWatchdog: trade already exists for instrument {} — skipping duplicate creation",
                                order.getInstrumentKey());
                        return;
                    }
                    executionEngine.openTradeFromFilledOrder(order);
                } finally {
                    processingOrders.remove(order.getClientOrderId());
                }
            }

        } else if (latest.status() == OrderStatus.REJECTED || latest.status() == OrderStatus.CANCELLED) {
            log.info("OrderFillWatchdog: order terminal — clientOrderId={}, status={}, reason={}",
                    order.getClientOrderId(), latest.status(), latest.rejectionReason().orElse(""));

            // P0 #2: Partial fill followed by REJECTED/CANCELLED — broker filled some lots
            // but then rejected the remainder. Those filled lots are now untracked naked positions.
            if (latest.filledQuantity() > 0) {
                log.error("OrderFillWatchdog: PARTIAL FILL on terminal order! clientOrderId={}, filledQty={}, status={} — creating synthetic trade + alerting",
                        order.getClientOrderId(), latest.filledQuantity(), latest.status());
                // Alert ops about the orphaned partial fill
                if (telegramAlertService != null) {
                    telegramAlertService.systemAlert(String.format(
                            "🚨 PARTIAL FILL on %s order\nInstrument: %s\nFilled: %d lots\nStatus: %s\nCreating synthetic trade to track.",
                            latest.status(), order.getInstrumentKey(), latest.filledQuantity(), latest.status()));
                }
                order.setStatus(OrderStatus.COMPLETE);
                order.setFilledQuantity(latest.filledQuantity());
                order.setAverageFillPrice(latest.averageFillPrice().orElse(null));
                order.setUpdatedAt(latest.updatedAt());
                saveOrderWithRetry(order, null);
                // Create a trade so exit monitors can manage the orphaned position
                if (!order.getClientOrderId().startsWith("EXIT-")) {
                    executionEngine.openTradeFromFilledOrder(order);
                }
            } else {
                order.setUpdatedAt(latest.updatedAt());
                saveOrderWithRetry(order, latest.status());
            }
            // Release entry gate — the order is dead, allow new entries
            executionEngine.releaseEntryInFlightGate();
        }
    }

    /**
     * Save order with optimistic lock retry. Re-fetches from DB on conflict
     * (when WebSocket order_update thread modified the same row concurrently).
     */
    private void saveOrderWithRetry(OrderEntity order, OrderStatus statusOverride) {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                if (statusOverride != null) order.setStatus(statusOverride);
                orderRepository.save(order);
                return;
            } catch (org.springframework.orm.ObjectOptimisticLockingFailureException ex) {
                log.debug("OrderFillWatchdog: optimistic lock conflict on {} — retrying (attempt {})",
                        order.getClientOrderId(), attempt + 1);
                // Re-fetch fresh version from DB
                OrderEntity fresh = orderRepository.findById(order.getClientOrderId()).orElse(null);
                if (fresh == null) {
                    log.warn("OrderFillWatchdog: order {} disappeared from DB after lock conflict", order.getClientOrderId());
                    return;
                }
                // If the fresh version already has the target status, another thread handled it
                if (statusOverride != null && fresh.getStatus() == statusOverride) {
                    log.debug("OrderFillWatchdog: order {} already at status {} — skipping", order.getClientOrderId(), statusOverride);
                    return;
                }
                // Apply our updates to the fresh entity and retry
                order = fresh;
            }
        }
        log.warn("OrderFillWatchdog: failed to save order {} after 3 retries", order.getClientOrderId());
    }

    /**
     * Close the matching open trade when a pending SELL (exit) order fills.
     * Finds the open trade by instrument key and computes realized P&L.
     */
    /**
     * Close the matching open trade when a pending SELL (exit) order fills.
     * Finds the open trade by instrument key and computes realized P&L.
     */
    private void closeTradeFromFilledExitOrder(OrderEntity exitOrder) {
        String instrumentKey = exitOrder.getInstrumentKey();
        BigDecimal exitPrice = exitOrder.getAverageFillPrice() != null
                ? exitOrder.getAverageFillPrice() : BigDecimal.ZERO;

        // Find the open trade for this instrument
        var openTrades = executionEngine.findOpenTradesByInstrument(instrumentKey);
        if (openTrades.isEmpty()) {
            log.warn("OrderFillWatchdog: exit order filled but no open trade found for instrument {}",
                    instrumentKey);
            return;
        }

        // Fix #3 (2026-06-02): When two strategies hold the same instrument (e.g.
        // OI Momentum + OI Shift Trap on the same strike), pick the trade whose
        // strategyType matches the order's strategyType. Falls back to first
        // open trade if the order has no strategyType tag.
        var trade = openTrades.stream()
                .filter(t -> exitOrder.getStrategyType() == null
                        || exitOrder.getStrategyType().isBlank()
                        || exitOrder.getStrategyType().equals(t.getStrategyType()))
                .findFirst()
                .orElse(openTrades.getFirst());
        if (openTrades.size() > 1) {
            log.info("OrderFillWatchdog: {} open trades for {} — selected tradeId={} (orderStrategy={}, tradeStrategy={})",
                    openTrades.size(), instrumentKey, trade.getTradeId(),
                    exitOrder.getStrategyType(), trade.getStrategyType());
        }

        // Fix #1 (2026-06-02): Short positions had wrong-sign P&L because this
        // path always computed long P&L. Route through PositionPnlCalculator
        // so SHORT_POSITION / selling strategies get correct realised P&L.
        boolean shortEntry = com.algo.trade.execution.exit.PositionPnlCalculator.isShortEntry(trade);
        BigDecimal realizedPnl = shortEntry
                ? trade.getEntryPrice().subtract(exitPrice)
                        .multiply(BigDecimal.valueOf(trade.getQuantity()))
                : exitPrice.subtract(trade.getEntryPrice())
                        .multiply(BigDecimal.valueOf(trade.getQuantity()));
        trade.close(exitPrice, exitOrder.getUpdatedAt(), realizedPnl, "Watchdog: exit order filled");
        executionEngine.saveTradeEntity(trade);

        log.info("OrderFillWatchdog: closed trade from exit fill — tradeId={}, instrument={}, exitPrice={}, short={}, pnl={}",
                trade.getTradeId(), instrumentKey, exitPrice, shortEntry, realizedPnl);

        emitExitEventToTuning(trade, exitPrice, "WATCHDOG_FILLED_EXIT");

        // EXIT copy: this path closes the trade DIRECTLY (bypassing ExecutionEngine.doCloseTrade),
        // so the multi-user exit fan-out must be invoked here too — otherwise a primary exit
        // that fills late via the watchdog leaves secondary users' copies open.
        if (signalCopyService != null && signalCopyService.isEnabled()
                && (trade.getUserId() == null
                    || trade.getUserId().equals(com.algo.trade.multiuser.UserContext.DEFAULT_USER_ID))) {
            try {
                signalCopyService.fireExitForAllUsersAsync(trade, exitPrice, "Watchdog: exit order filled");
            } catch (Exception e) {
                log.warn("OrderFillWatchdog: exit copy fan-out failed for {}: {}", trade.getTradeId(), e.getMessage());
            }
        }
    }

    /**
     * Build + record an ExitEvent for the unified tune pipeline. Best-effort:
     * any failure is logged and swallowed (never block trade closure on tune).
     */
    private void emitExitEventToTuning(com.algo.trade.persistence.TradeEntity trade,
                                        BigDecimal exitPrice, String reason) {
        if (tuningEventRecorder == null) return;
        try {
            String strategyType = trade.getStrategyType();
            if (strategyType == null) return;
            com.algo.trade.tuning.infra.MaeMfeTracker.Snapshot snapshot =
                    (maeMfeTracker != null)
                            ? maeMfeTracker.onExit(trade.getTradeId()).orElse(null)
                            : null;
            com.algo.trade.tuning.ExitEvent exitEvent = null;
            String correlationKey = trade.getTradeId();
            com.algo.trade.domain.IndexType ix =
                    com.algo.trade.domain.IndexType.fromName(trade.getUnderlying());
            if ("OI_SHIFT_TRAP".equals(strategyType) && oiShiftTrapCaptureAdapter != null) {
                java.util.Map<String, Object> trapAttrs = new java.util.LinkedHashMap<>();
                trapAttrs.put("exitOrigin", "watchdog_filled");
                exitEvent = oiShiftTrapCaptureAdapter.buildExitEvent(
                        ix, trade, snapshot, correlationKey, exitPrice, reason, false, trapAttrs);
            } else if ("OI_MOMENTUM".equals(strategyType) && oiMomentumCaptureAdapter != null) {
                exitEvent = oiMomentumCaptureAdapter.buildExitEvent(
                        ix, trade, snapshot, correlationKey, exitPrice, reason, false);
            }
            if (exitEvent != null) {
                tuningEventRecorder.record(exitEvent);
                log.info("OrderFillWatchdog: recorded ExitEvent tradeId={}, strategy={}, reason={}",
                        trade.getTradeId(), strategyType, reason);
            }
        } catch (Exception ex) {
            log.warn("OrderFillWatchdog: ExitEvent emission failed (non-fatal): tradeId={}, ex={}",
                    trade.getTradeId(), ex.getMessage());
        }
    }
}
