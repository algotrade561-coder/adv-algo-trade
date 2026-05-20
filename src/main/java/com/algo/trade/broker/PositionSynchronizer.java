package com.algo.trade.broker;

import com.algo.trade.broker.zerodha.KiteAccessTokenStore;
import com.algo.trade.domain.Position;
import com.algo.trade.domain.TradeStatus;
import com.algo.trade.persistence.TradeEntity;
import com.algo.trade.persistence.TradeRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Reconciles DB trade positions with actual broker (Kite) positions.
 *
 * <ul>
 *   <li>Creates missing {@link TradeEntity} rows for positions found in the broker but not in the DB.</li>
 *   <li>Closes {@link TradeEntity} rows for positions that are OPEN in the DB but no longer present in the broker.</li>
 * </ul>
 *
 * Runs on application startup and every 5 minutes while the broker session is active.
 */
@Component
public class PositionSynchronizer {

    private static final Logger log = LoggerFactory.getLogger(PositionSynchronizer.class);

    private final BrokerClient brokerClient;
    private final TradeRepository tradeRepository;
    private final com.algo.trade.persistence.OrderRepository orderRepository;
    private final KiteAccessTokenStore tokenStore;
    private final com.algo.trade.marketdata.MarketDataService marketDataService;
    private final com.algo.trade.notification.TelegramAlertService telegramAlertService;
    private final com.algo.trade.monitoring.ErrorEventService errorEventService;

    @org.springframework.beans.factory.annotation.Autowired
    private com.algo.trade.monitoring.SchedulerRegistry schedulerRegistry;

    /** Prevents concurrent sync runs from the event listener and the scheduled timer. */
    private final java.util.concurrent.atomic.AtomicBoolean syncInProgress = new java.util.concurrent.atomic.AtomicBoolean(false);

    public PositionSynchronizer(BrokerClient brokerClient,
                                TradeRepository tradeRepository,
                                com.algo.trade.persistence.OrderRepository orderRepository,
                                KiteAccessTokenStore tokenStore,
                                com.algo.trade.marketdata.MarketDataService marketDataService,
                                com.algo.trade.notification.TelegramAlertService telegramAlertService,
                                com.algo.trade.monitoring.ErrorEventService errorEventService) {
        this.brokerClient = brokerClient;
        this.tradeRepository = tradeRepository;
        this.orderRepository = orderRepository;
        this.tokenStore = tokenStore;
        this.marketDataService = marketDataService;
        this.telegramAlertService = telegramAlertService;
        this.errorEventService = errorEventService;
    }

    /**
     * Sync on application startup.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        schedulerRegistry.register("positionSync", "Broker position reconciliation (60s)", 60_000, this::syncPositions);
        syncPositions();
    }

    /**
     * Immediate sync when any order completes — catches manual broker closes in real-time.
     */
    @EventListener
    public void onOrderCompleted(com.algo.trade.domain.OrderCompletedEvent event) {
        log.info("Order completed event received: orderId={} symbol={} — triggering immediate position sync",
                event.orderId(), event.tradingSymbol());
        syncPositions();
    }

    /**
     * Periodic sync every 60 seconds — backup for when WebSocket events are missed.
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 10_000)
    public void onSchedule() {
        if (!schedulerRegistry.isEnabled("positionSync")) return;
        syncPositions();
    }

    /**
     * Core reconciliation logic. Guarded by broker session check and concurrency lock.
     */
    public void syncPositions() {
        if (!tokenStore.authenticated()) {
            log.debug("Position sync skipped: broker session not active");
            return;
        }
        if (!syncInProgress.compareAndSet(false, true)) {
            log.debug("Position sync skipped: another sync is already running");
            return;
        }
        try {
            doSyncPositions();
        } finally {
            syncInProgress.set(false);
        }
    }

    void doSyncPositions() {
        try {
            List<Position> brokerPositions = brokerClient.positions();
            List<TradeEntity> openTrades = tradeRepository.findByStatus(TradeStatus.OPEN);

            // Index open trades by instrumentKey for fast lookup
            Map<String, List<TradeEntity>> openTradesByInstrument = openTrades.stream()
                    .collect(Collectors.groupingBy(TradeEntity::getInstrumentKey));

            // Index broker positions by instrumentKey (only non-zero quantity)
            Map<String, Position> brokerByInstrument = brokerPositions.stream()
                    .filter(p -> p.quantity() != 0)
                    .collect(Collectors.toMap(Position::instrumentKey, Function.identity(), (a, b) -> a));

            // Today's date range for closed position tracking
            java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"));
            java.time.Instant dayStart = today.atStartOfDay(java.time.ZoneId.of("Asia/Kolkata")).toInstant();
            java.time.Instant dayEnd = today.plusDays(1).atStartOfDay(java.time.ZoneId.of("Asia/Kolkata")).toInstant();
            List<TradeEntity> todayTrades = tradeRepository.findByEntryTimeBetween(dayStart, dayEnd);
            Set<String> todayTrackedInstruments = todayTrades.stream()
                    .map(TradeEntity::getInstrumentKey)
                    .collect(java.util.stream.Collectors.toSet());

            int matched = 0;
            int created = 0;
            int closed = 0;
            int closedRecorded = 0;

            // 1. Broker positions not in DB → create TradeEntity
            for (Position pos : brokerByInstrument.values()) {
                if (openTradesByInstrument.containsKey(pos.instrumentKey())) {
                    matched++;
                } else {
                    createTradeFromBrokerPosition(pos);
                    created++;
                }
            }

            // 2. DB OPEN trades not in broker → close them (skip paper trades)
            for (TradeEntity trade : openTrades) {
                if (trade.isPaperTrade()) {
                    continue;
                }
                if (!brokerByInstrument.containsKey(trade.getInstrumentKey())) {
                    closeStaleTrade(trade);
                    closed++;
                }
            }

            // 3. Closed broker positions (qty=0, P&L != 0) not tracked in DB → create closed record
            // This captures manually traded positions that were opened and closed while app was down
            for (Position pos : brokerPositions) {
                if (pos.quantity() == 0 && pos.unrealizedPnl() != null
                        && pos.unrealizedPnl().signum() != 0
                        && !todayTrackedInstruments.contains(pos.instrumentKey())) {
                    createClosedTradeFromBrokerPosition(pos);
                    closedRecorded++;
                }
            }

            log.info("Position sync complete: matched={}, created={}, closed={}, closedRecorded={}",
                    matched, created, closed, closedRecorded);

        } catch (Exception ex) {
            log.error("Position sync failed: {}", ex.getMessage(), ex);
            errorEventService.critical("PositionSynchronizer", "Position sync failed: " + ex.getMessage(), ex);
            schedulerRegistry.recordError("positionSync", ex.getMessage());
            return;
        }
        schedulerRegistry.recordRun("positionSync");
    }

    private void createTradeFromBrokerPosition(Position pos) {
        // Late-day guard: don't import NEW positions after 15:00 IST
        java.time.LocalTime now = java.time.LocalTime.now(java.time.ZoneId.of("Asia/Kolkata"));
        if (now.isAfter(java.time.LocalTime.of(15, 0)) && now.isBefore(java.time.LocalTime.of(15, 30))) {
            log.info("Position sync skipped late-day import: instrument={}, time={} (15:00-15:30 window)",
                    pos.instrumentKey(), now);
            return;
        }

        // Already tracked check
        boolean alreadyTracked = !tradeRepository.findByInstrumentKeyAndStatus(pos.instrumentKey(), TradeStatus.OPEN).isEmpty();
        if (alreadyTracked) {
            log.debug("Position sync: {} already has an OPEN trade — skipping", pos.instrumentKey());
            return;
        }

        String underlying = extractUnderlying(pos.instrumentKey());
        String optionType = extractOptionType(pos.instrumentKey());
        boolean isShort = pos.quantity() < 0;
        int absQuantity = Math.abs(pos.quantity());
        com.algo.trade.domain.OrderSide entrySide = isShort
                ? com.algo.trade.domain.OrderSide.SELL
                : com.algo.trade.domain.OrderSide.BUY;

        try {
            var brokerOrders = brokerClient.orders();

            // Collect all broker order IDs already tracked in our local orders table
            java.util.Set<String> trackedBrokerOrderIds = new java.util.HashSet<>();
            try {
                for (var lo : orderRepository.findAll()) {
                    if (lo.getBrokerOrderId() != null && !lo.getBrokerOrderId().isBlank()) {
                        trackedBrokerOrderIds.add(lo.getBrokerOrderId());
                    }
                }
            } catch (Exception e) {
                log.debug("Position sync: could not load local orders: {}", e.getMessage());
            }

            // Find ALL untracked completed entry orders for this instrument, sorted most recent first.
            // "Untracked" = broker order ID not in our orders table = manual/external order.
            // We apply a recency filter: only consider orders filled within the last 5 minutes.
            // This prevents picking up stale orders from earlier in the day that happen to be untracked.
            Instant recencyCutoff = Instant.now().minus(java.time.Duration.ofMinutes(5));
            var untrackedOrders = brokerOrders.stream()
                    .filter(o -> pos.instrumentKey().equals(o.instrumentKey()))
                    .filter(o -> o.side() == entrySide)
                    .filter(o -> o.status() == com.algo.trade.domain.OrderStatus.COMPLETE)
                    .filter(o -> o.filledQuantity() > 0)
                    .filter(o -> o.brokerOrderId().isPresent())
                    .filter(o -> !trackedBrokerOrderIds.contains(o.brokerOrderId().get()))
                    .sorted((a, b) -> b.updatedAt().compareTo(a.updatedAt()))
                    .toList();

            // First try: most recent order within recency window (5 min)
            var untrackedOrder = untrackedOrders.stream()
                    .filter(o -> o.updatedAt().isAfter(recencyCutoff))
                    .findFirst()
                    .orElse(null);

            if (untrackedOrder == null && !untrackedOrders.isEmpty()) {
                // Fallback: if no recent order found but untracked orders exist,
                // this might be a position opened earlier today that we missed.
                // Use the most recent untracked order but log a warning.
                untrackedOrder = untrackedOrders.getFirst();
                log.warn("Position sync: no recent (<5min) untracked order for {} — using older order from {} (age={}s, price={})",
                        pos.instrumentKey(), untrackedOrder.updatedAt(),
                        java.time.Duration.between(untrackedOrder.updatedAt(), Instant.now()).getSeconds(),
                        untrackedOrder.averageFillPrice().orElse(BigDecimal.ZERO));
            }

            if (untrackedOrder == null) {
                // No untracked order found at all — defer to next cycle (API lag)
                log.info("Position sync: no untracked {} order for {} — deferring (tracked IDs={}, broker orders for instrument={})",
                        entrySide, pos.instrumentKey(), trackedBrokerOrderIds.size(),
                        brokerOrders.stream().filter(o -> pos.instrumentKey().equals(o.instrumentKey())).count());
                return;
            }

            BigDecimal entryPrice = untrackedOrder.averageFillPrice().orElse(pos.averagePrice());
            String tradeId = "SYNC-" + java.util.UUID.randomUUID().toString();

            TradeEntity entity = new TradeEntity(
                    tradeId,
                    pos.instrumentKey(),
                    underlying,
                    optionType,
                    TradeStatus.OPEN,
                    absQuantity,
                    entryPrice,
                    untrackedOrder.updatedAt() != null ? untrackedOrder.updatedAt() : Instant.now(),
                    isShort ? "position-sync: SHORT position found in broker" : "position-sync: found in broker"
            );
            entity.setProductType(pos.productType() != null ? pos.productType() : "MIS");
            if (isShort) {
                entity.setStrategyType("SHORT_POSITION");
            }
            tradeRepository.save(entity);

            log.info("Position sync created trade: tradeId={}, instrument={}, qty={} ({}), entryPrice={} (from order {} filled at {})",
                    tradeId, pos.instrumentKey(), absQuantity, isShort ? "SHORT" : "LONG",
                    entryPrice, untrackedOrder.brokerOrderId().orElse("?"), untrackedOrder.updatedAt());
            telegramAlertService.systemAlert(String.format(
                    "🔄 Position Sync: Found %s in broker\nSide: %s | Qty: %d | Entry: ₹%.2f (from order fill at %s)\nTrade: %s",
                    pos.instrumentKey(), isShort ? "SHORT" : "LONG", absQuantity,
                    entryPrice.doubleValue(), untrackedOrder.updatedAt(), tradeId));

        } catch (Exception ex) {
            log.warn("Position sync: order-driven sync failed for {} — deferring: {}",
                    pos.instrumentKey(), ex.getMessage());
        }
    }

    private void closeStaleTrade(TradeEntity trade) {
        // Re-read from DB to catch races (another sync or exit monitor may have closed it)
        TradeEntity fresh = tradeRepository.findById(trade.getTradeId()).orElse(null);
        if (fresh == null || fresh.getStatus() != TradeStatus.OPEN) {
            log.debug("Position sync: trade {} already closed — skipping", trade.getTradeId());
            return;
        }

        // Determine if this is a short position (needed for exit side and P&L calculation)
        boolean isShort = false;
        if (fresh.getStrategyType() != null && !fresh.getStrategyType().isBlank()) {
            try {
                isShort = com.algo.trade.strategy.StrategyType.valueOf(fresh.getStrategyType()).isSellingStrategy();
            } catch (IllegalArgumentException ignored) {}
        }
        if (!isShort && fresh.getEntryReason() != null) {
            isShort = fresh.getEntryReason().contains("[SELL_CE]") || fresh.getEntryReason().contains("[SELL_PE]");
        }

        // Try to find the actual exit fill price from broker order history
        // Match by instrument + exit side + COMPLETE + most recent fill time
        // This gives accurate P&L instead of using stale LTP
        BigDecimal exitPrice = null;
        Instant exitTime = Instant.now();
        try {
            // Exit side is opposite of entry: long position exits with SELL, short exits with BUY
            com.algo.trade.domain.OrderSide exitSide = isShort
                    ? com.algo.trade.domain.OrderSide.BUY
                    : com.algo.trade.domain.OrderSide.SELL;
            var orders = brokerClient.orders();

            // Collect tracked broker order IDs to find the untracked exit order
            java.util.Set<String> trackedBrokerOrderIds = new java.util.HashSet<>();
            try {
                for (var lo : orderRepository.findAll()) {
                    if (lo.getBrokerOrderId() != null && !lo.getBrokerOrderId().isBlank()) {
                        trackedBrokerOrderIds.add(lo.getBrokerOrderId());
                    }
                }
            } catch (Exception e) {
                log.debug("Position sync: could not load local orders for exit matching: {}", e.getMessage());
            }

            // Find the most recent completed exit order for this instrument
            // Prefer untracked orders (manual exits), but fall back to any exit order
            var exitOrder = orders.stream()
                    .filter(o -> fresh.getInstrumentKey().equals(o.instrumentKey()))
                    .filter(o -> o.side() == exitSide)
                    .filter(o -> o.status() == com.algo.trade.domain.OrderStatus.COMPLETE)
                    .filter(o -> o.filledQuantity() > 0)
                    .filter(o -> o.updatedAt().isAfter(fresh.getEntryTime())) // must be after entry
                    .sorted((a, b) -> b.updatedAt().compareTo(a.updatedAt())) // most recent first
                    .findFirst()
                    .orElse(null);

            if (exitOrder != null) {
                exitPrice = exitOrder.averageFillPrice().orElse(null);
                exitTime = exitOrder.updatedAt();
                log.info("Position sync: exit price ₹{} from order {} at {}",
                        exitPrice, exitOrder.brokerOrderId().orElse("?"), exitTime);
            }
        } catch (Exception ex) {
            log.debug("Position sync: could not fetch order history for fill price: {}", ex.getMessage());
        }

        // Fallback to LTP if order history didn't yield a fill price
        if (exitPrice == null || exitPrice.signum() <= 0) {
            exitPrice = marketDataService.quote(fresh.getInstrumentKey())
                    .map(q -> q.lastPrice())
                    .filter(p -> p != null && p.signum() > 0)
                    .orElse(fresh.getEntryPrice());
            exitTime = Instant.now();
            log.debug("Position sync: using LTP {} as exit price (order history unavailable)", exitPrice);
        }

        // P&L on remaining quantity only (after any partial closes)
        BigDecimal realizedPnl = isShort
                ? fresh.getEntryPrice().subtract(exitPrice).multiply(BigDecimal.valueOf(fresh.getQuantity()))
                : exitPrice.subtract(fresh.getEntryPrice()).multiply(BigDecimal.valueOf(fresh.getQuantity()));

        fresh.close(exitPrice, exitTime, realizedPnl,
                "position-sync: manually closed from broker app");
        tradeRepository.save(fresh);

        log.warn("Position sync closed trade (manual broker close): tradeId={}, instrument={}, entry={}, exit={}, pnl={}",
                fresh.getTradeId(), fresh.getInstrumentKey(), fresh.getEntryPrice(), exitPrice, fresh.getRealizedPnl());

        // Alert — operator should know the system detected a manual close
        telegramAlertService.systemAlert(String.format(
                "🔄 Position Sync: %s manually closed from broker\nEntry ₹%.2f → Exit ₹%.2f | P&L ₹%.2f\nTrade: %s",
                fresh.getInstrumentKey(), fresh.getEntryPrice().doubleValue(),
                exitPrice.doubleValue(), fresh.getRealizedPnl().doubleValue(), fresh.getTradeId()));
    }

    /**
     * Create a CLOSED trade record from a broker position that was opened and closed
     * while the app was not tracking it (e.g., manual trades done on Kite app).
     * Uses broker order history to find actual entry and exit fill prices for accurate P&L.
     */
    private void createClosedTradeFromBrokerPosition(Position pos) {
        String tradeId = "SYNC-CLOSED-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        String underlying = extractUnderlying(pos.instrumentKey());
        String optionType = extractOptionType(pos.instrumentKey());

        // Determine likely trade direction from the position's sell/buy quantities
        // For a closed position (qty=0), we infer from the P&L sign and option type
        boolean wasShort = pos.unrealizedPnl() != null && pos.unrealizedPnl().signum() > 0
                && "PE".equals(optionType); // Simplified heuristic

        BigDecimal entryPrice = pos.averagePrice();
        BigDecimal exitPrice = pos.lastPrice().signum() > 0 ? pos.lastPrice() : pos.averagePrice();
        Instant entryTime = Instant.now();
        Instant exitTime = Instant.now();

        // Try to find actual entry and exit orders from broker order history
        try {
            var brokerOrders = brokerClient.orders();
            var instrumentOrders = brokerOrders.stream()
                    .filter(o -> pos.instrumentKey().equals(o.instrumentKey()))
                    .filter(o -> o.status() == com.algo.trade.domain.OrderStatus.COMPLETE)
                    .filter(o -> o.filledQuantity() > 0)
                    .sorted((a, b) -> a.updatedAt().compareTo(b.updatedAt())) // chronological
                    .toList();

            if (!instrumentOrders.isEmpty()) {
                // Find BUY orders (entry for long, exit for short)
                var buyOrders = instrumentOrders.stream()
                        .filter(o -> o.side() == com.algo.trade.domain.OrderSide.BUY)
                        .toList();
                var sellOrders = instrumentOrders.stream()
                        .filter(o -> o.side() == com.algo.trade.domain.OrderSide.SELL)
                        .toList();

                if (!buyOrders.isEmpty() && !sellOrders.isEmpty()) {
                    // Normal long trade: first BUY = entry, last SELL = exit
                    var entryOrder = buyOrders.getFirst();
                    var exitOrder = sellOrders.getLast();
                    entryPrice = entryOrder.averageFillPrice().orElse(entryPrice);
                    exitPrice = exitOrder.averageFillPrice().orElse(exitPrice);
                    entryTime = entryOrder.updatedAt();
                    exitTime = exitOrder.updatedAt();
                    log.info("Position sync (closed): {} entry from order {} at ₹{}, exit from order {} at ₹{}",
                            pos.instrumentKey(),
                            entryOrder.brokerOrderId().orElse("?"), entryPrice,
                            exitOrder.brokerOrderId().orElse("?"), exitPrice);
                } else if (!buyOrders.isEmpty()) {
                    // Only BUY orders found — use first as entry
                    var entryOrder = buyOrders.getFirst();
                    entryPrice = entryOrder.averageFillPrice().orElse(entryPrice);
                    entryTime = entryOrder.updatedAt();
                } else if (!sellOrders.isEmpty()) {
                    // Only SELL orders — might be a short trade
                    var entryOrder = sellOrders.getFirst();
                    entryPrice = entryOrder.averageFillPrice().orElse(entryPrice);
                    entryTime = entryOrder.updatedAt();
                    wasShort = true;
                }
            }
        } catch (Exception ex) {
            log.debug("Position sync (closed): could not fetch order history for {}: {}",
                    pos.instrumentKey(), ex.getMessage());
        }

        // Calculate P&L from actual prices
        BigDecimal realizedPnl;
        if (pos.unrealizedPnl() != null && pos.unrealizedPnl().signum() != 0) {
            // Use broker-reported P&L as ground truth (accounts for all fills)
            realizedPnl = pos.unrealizedPnl();
        } else {
            // Compute from entry/exit
            realizedPnl = exitPrice.subtract(entryPrice).multiply(BigDecimal.valueOf(Math.max(1, Math.abs(pos.quantity()))));
        }

        TradeEntity entity = new TradeEntity(
                tradeId,
                pos.instrumentKey(),
                underlying,
                optionType,
                TradeStatus.OPEN,
                Math.max(1, Math.abs(pos.quantity())),
                entryPrice,
                entryTime,
                "position-sync: closed position found in broker (traded while app was down)"
        );
        entity.setProductType(pos.productType() != null ? pos.productType() : "MIS");
        // Close immediately with actual prices
        entity.close(exitPrice, exitTime, realizedPnl,
                "position-sync: already closed in broker");
        tradeRepository.save(entity);

        log.info("Position sync recorded closed trade: tradeId={}, instrument={}, entry=₹{}, exit=₹{}, pnl=₹{}",
                tradeId, pos.instrumentKey(), entryPrice, exitPrice, realizedPnl);
    }

    /**
     * Best-effort extraction of underlying symbol from an instrument key.
     * E.g. "NIFTY26JAN24500CE" → "NIFTY", "BANKNIFTY26FEB45000PE" → "BANKNIFTY".
     */
    static String extractUnderlying(String instrumentKey) {
        if (instrumentKey == null || instrumentKey.isBlank()) {
            return "UNKNOWN";
        }
        // Find the first digit — everything before it is the underlying
        for (int i = 0; i < instrumentKey.length(); i++) {
            if (Character.isDigit(instrumentKey.charAt(i))) {
                return i > 0 ? instrumentKey.substring(0, i) : "UNKNOWN";
            }
        }
        return instrumentKey;
    }

    /**
     * Best-effort extraction of option type (CE/PE) from an instrument key.
     */
    static String extractOptionType(String instrumentKey) {
        if (instrumentKey == null || instrumentKey.length() < 2) {
            return "UNKNOWN";
        }
        String suffix = instrumentKey.substring(instrumentKey.length() - 2).toUpperCase();
        if ("CE".equals(suffix) || "PE".equals(suffix)) {
            return suffix;
        }
        return "UNKNOWN";
    }
}
