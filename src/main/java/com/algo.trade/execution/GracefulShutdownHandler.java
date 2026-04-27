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
 * Graceful shutdown handler — closes open positions and disconnects WebSocket
 * when the Spring context is shutting down (JVM exit, SIGTERM, etc.).
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

    public GracefulShutdownHandler(TradeRepository tradeRepository,
                                    ExecutionEngine executionEngine,
                                    KiteWebSocketClient webSocketClient,
                                    TelegramAlertService alertService,
                                    com.algo.trade.marketdata.MarketDataService marketDataService,
                                    PositionSyncProperties positionSyncProperties) {
        this.tradeRepository = tradeRepository;
        this.executionEngine = executionEngine;
        this.webSocketClient = webSocketClient;
        this.alertService = alertService;
        this.marketDataService = marketDataService;
        this.positionSyncProperties = positionSyncProperties;
    }

    @EventListener
    public void onContextClosed(ContextClosedEvent event) {
        log.warn("[Shutdown] Application shutting down — closing open positions");
        alertService.systemAlert("⚠️ Application shutting down — closing open positions");

        List<TradeEntity> openTrades = tradeRepository.findByStatus(TradeStatus.OPEN);
        if (!openTrades.isEmpty()) {
            log.warn("[Shutdown] {} open trades to close", openTrades.size());
            for (TradeEntity trade : openTrades) {
                if (!positionSyncProperties.manageSyncedTrades() && trade.getTradeId().startsWith("SYNC-")) {
                    log.info("[Shutdown] Skipping SYNC trade (manage-synced-trades=false): tradeId={}", trade.getTradeId());
                    continue;
                }
                try {
                    // Use live market price, not stale entry price
                    java.math.BigDecimal exitPrice = marketDataService.quote(trade.getInstrumentKey())
                            .map(q -> q.lastPrice())
                            .filter(p -> p != null && p.signum() > 0)
                            .orElse(trade.getEntryPrice()); // fallback to entry price if no quote
                    executionEngine.closeTrade(trade.getTradeId(), exitPrice, "Graceful shutdown");
                    log.info("[Shutdown] Closed: tradeId={} exitPrice={}", trade.getTradeId(), exitPrice);
                } catch (Exception e) {
                    log.error("[Shutdown] Failed to close trade {}: {}", trade.getTradeId(), e.getMessage());
                }
            }
        }

        // Disconnect WebSocket
        try {
            webSocketClient.disconnect();
        } catch (Exception e) {
            log.debug("[Shutdown] WebSocket disconnect error: {}", e.getMessage());
        }

        log.info("[Shutdown] Graceful shutdown complete");
    }
}
