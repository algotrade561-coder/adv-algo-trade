package com.algo.trade.execution;

import com.algo.trade.broker.zerodha.KiteWebSocketClient;
import com.algo.trade.config.PositionSyncProperties;
import com.algo.trade.domain.TradeStatus;
import com.algo.trade.notification.TelegramAlertService;
import com.algo.trade.persistence.TradeEntity;
import com.algo.trade.persistence.TradeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Graceful shutdown handler — disconnects WebSocket and optionally closes single-leg
 * {@link TradeEntity} rows when the JVM exits. Spread {@code PositionGroup} positions are
 * left open at the broker and restored from DB on the next startup (EOD square-off is
 * handled by {@link FailSafeSquareoffDaemon}).
 */
@Component
public class GracefulShutdownHandler {

    private static final Logger log = LoggerFactory.getLogger(GracefulShutdownHandler.class);

    private final TradeRepository tradeRepository;
    private final ExecutionEngine executionEngine;
    private final KiteWebSocketClient webSocketClient;
    private final TelegramAlertService alertService;
    private final com.algo.trade.marketdata.MarketDataService marketDataService;
    private final PositionSyncProperties positionSyncProperties;
    private final com.algo.trade.monitoring.ErrorEventService errorEventService;

    public GracefulShutdownHandler(TradeRepository tradeRepository,
                                    ExecutionEngine executionEngine,
                                    KiteWebSocketClient webSocketClient,
                                    TelegramAlertService alertService,
                                    com.algo.trade.marketdata.MarketDataService marketDataService,
                                    PositionSyncProperties positionSyncProperties,
                                    com.algo.trade.monitoring.ErrorEventService errorEventService) {
        this.tradeRepository = tradeRepository;
        this.executionEngine = executionEngine;
        this.webSocketClient = webSocketClient;
        this.alertService = alertService;
        this.marketDataService = marketDataService;
        this.positionSyncProperties = positionSyncProperties;
        this.errorEventService = errorEventService;
    }

    @EventListener
    public void onContextClosed(ContextClosedEvent event) {
        log.warn("[Shutdown] Application shutting down (spread groups remain at broker; restored on restart)");
        alertService.systemAlert("⚠️ Application shutting down — spread positions kept open; single-leg trades may be closed");

        // Disconnect WebSocket FIRST — stops incoming ticks from firing CandleClosedEvents
        // against an EntityManagerFactory that is about to be torn down.
        try {
            webSocketClient.disconnect();
            log.info("[Shutdown] WebSocket disconnected");
        } catch (Exception e) {
            log.debug("[Shutdown] WebSocket disconnect error: {}", e.getMessage());
        }

        List<TradeEntity> openTrades = tradeRepository.findByStatus(TradeStatus.OPEN);
        if (!openTrades.isEmpty()) {
            log.warn("[Shutdown] {} open trades to close", openTrades.size());
            for (TradeEntity trade : openTrades) {
                if (!positionSyncProperties.manageSyncedTrades() && trade.getTradeId().startsWith("SYNC-")) {
                    log.info("[Shutdown] Skipping SYNC trade (manage-synced-trades=false): tradeId={}", trade.getTradeId());
                    continue;
                }
                try {
                    java.math.BigDecimal exitPrice = marketDataService.quote(trade.getInstrumentKey())
                            .map(q -> q.lastPrice())
                            .filter(p -> p != null && p.signum() > 0)
                            .orElse(trade.getEntryPrice());
                    executionEngine.closeTrade(trade.getTradeId(), exitPrice, "Graceful shutdown");
                    log.info("[Shutdown] Closed: tradeId={} exitPrice={}", trade.getTradeId(), exitPrice);
                } catch (Exception e) {
                    log.error("[Shutdown] Failed to close trade {}: {}", trade.getTradeId(), e.getMessage());
                    errorEventService.critical("GracefulShutdown", "Failed to close trade " + trade.getTradeId() + ": " + e.getMessage(), e);
                }
            }
        }

        log.info("[Shutdown] Graceful shutdown complete");
    }
}
