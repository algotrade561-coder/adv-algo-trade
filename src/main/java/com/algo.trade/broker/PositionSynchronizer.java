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

    public PositionSynchronizer(BrokerClient brokerClient,
                                TradeRepository tradeRepository,
                                KiteAccessTokenStore tokenStore,
                                com.algo.trade.marketdata.MarketDataService marketDataService) {
        this.brokerClient = brokerClient;
        this.tradeRepository = tradeRepository;
        this.tokenStore = tokenStore;
        this.marketDataService = marketDataService;
    }

    /**
     * Sync on application startup.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        syncPositions();
    }

    /**
     * Periodic sync every 5 minutes.
     */
    @Scheduled(fixedDelay = 300_000)
    public void onSchedule() {
        syncPositions();
    }

    /**
     * Core reconciliation logic. Guarded by broker session check.
     */
    public void syncPositions() {
        if (!tokenStore.authenticated()) {
            log.debug("Position sync skipped: broker session not active");
            return;
        }

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
        }
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
    }

    private void closeStaleTrade(TradeEntity trade) {
        // Use live market price if available, fallback to entry price
        BigDecimal exitPrice = marketDataService.quote(trade.getInstrumentKey())
                .map(q -> q.lastPrice())
                .filter(p -> p != null && p.signum() > 0)
                .orElse(trade.getEntryPrice());
        BigDecimal realizedPnl = exitPrice.subtract(trade.getEntryPrice())
                .multiply(BigDecimal.valueOf(trade.getQuantity()));
        trade.close(
                exitPrice,
                Instant.now(),
                realizedPnl,
                "position-sync: not found in broker"
        );
        tradeRepository.save(trade);
        log.info("Position sync closed trade: tradeId={}, instrument={}",
                trade.getTradeId(), trade.getInstrumentKey());
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
