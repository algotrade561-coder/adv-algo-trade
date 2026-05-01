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
                                KiteAccessTokenStore tokenStore,
                                com.algo.trade.marketdata.MarketDataService marketDataService,
                                com.algo.trade.notification.TelegramAlertService telegramAlertService,
                                com.algo.trade.monitoring.ErrorEventService errorEventService) {
        this.brokerClient = brokerClient;
        this.tradeRepository = tradeRepository;
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
            // Use list-based grouping to handle multiple trades on same instrument
            Map<String, List<TradeEntity>> openTradesByInstrument = openTrades.stream()
                    .collect(Collectors.groupingBy(TradeEntity::getInstrumentKey));

            // Index broker positions by instrumentKey (only non-zero quantity)
            Map<String, Position> brokerByInstrument = brokerPositions.stream()
                    .filter(p -> p.quantity() != 0)
                    .collect(Collectors.toMap(Position::instrumentKey, Function.identity(), (a, b) -> a));

            int matched = 0;
            int created = 0;
            int closed = 0;

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
                    continue; // Paper trades have no broker position — don't close them
                }
                if (!brokerByInstrument.containsKey(trade.getInstrumentKey())) {
                    closeStaleTrade(trade);
                    closed++;
                }
            }

            log.info("Position sync complete: matched={}, created={}, closed={}", matched, created, closed);

        } catch (Exception ex) {
            log.error("Position sync failed: {}", ex.getMessage(), ex);
            errorEventService.critical("PositionSynchronizer", "Position sync failed: " + ex.getMessage(), ex);
            schedulerRegistry.recordError("positionSync", ex.getMessage());
            return;
        }
        schedulerRegistry.recordRun("positionSync");
    }

    private void createTradeFromBrokerPosition(Position pos) {
        String tradeId = "SYNC-" + UUID.randomUUID();
        String underlying = extractUnderlying(pos.instrumentKey());
        String optionType = extractOptionType(pos.instrumentKey());

        TradeEntity entity = new TradeEntity(
                tradeId,
                pos.instrumentKey(),
                underlying,
                optionType,
                TradeStatus.OPEN,
                pos.quantity(),
                pos.averagePrice(),
                Instant.now(),
                "position-sync: found in broker"
        );
        tradeRepository.save(entity);
        log.info("Position sync created trade: tradeId={}, instrument={}, qty={}, avgPrice={}",
                tradeId, pos.instrumentKey(), pos.quantity(), pos.averagePrice());
        telegramAlertService.systemAlert(String.format(
                "🔄 Position Sync: Found %s in broker (not in DB)\nQty: %d | Avg Price: ₹%.2f\nCreated trade: %s",
                pos.instrumentKey(), pos.quantity(), pos.averagePrice().doubleValue(), tradeId));
    }

    private void closeStaleTrade(TradeEntity trade) {
        // Re-read from DB to catch races (another sync or exit monitor may have closed it)
        TradeEntity fresh = tradeRepository.findById(trade.getTradeId()).orElse(null);
        if (fresh == null || fresh.getStatus() != TradeStatus.OPEN) {
            log.debug("Position sync: trade {} already closed — skipping", trade.getTradeId());
            return;
        }

        // Use live market price — best approximation of the manual close price
        BigDecimal exitPrice = marketDataService.quote(fresh.getInstrumentKey())
                .map(q -> q.lastPrice())
                .filter(p -> p != null && p.signum() > 0)
                .orElse(fresh.getEntryPrice());

        // Account for short entries (selling strategies)
        boolean isShort = false;
        if (fresh.getStrategyType() != null && !fresh.getStrategyType().isBlank()) {
            try {
                isShort = com.algo.trade.strategy.StrategyType.valueOf(fresh.getStrategyType()).isSellingStrategy();
            } catch (IllegalArgumentException ignored) {}
        }
        if (!isShort && fresh.getEntryReason() != null) {
            isShort = fresh.getEntryReason().contains("[SELL_CE]") || fresh.getEntryReason().contains("[SELL_PE]");
        }

        // P&L on remaining quantity only (after any partial closes)
        BigDecimal realizedPnl = isShort
                ? fresh.getEntryPrice().subtract(exitPrice).multiply(BigDecimal.valueOf(fresh.getQuantity()))
                : exitPrice.subtract(fresh.getEntryPrice()).multiply(BigDecimal.valueOf(fresh.getQuantity()));

        fresh.close(exitPrice, Instant.now(), realizedPnl,
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
