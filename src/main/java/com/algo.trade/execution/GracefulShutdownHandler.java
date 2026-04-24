package com.algo.trade.execution;

import com.algo.trade.broker.zerodha.KiteWebSocketClient;
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

    public GracefulShutdownHandler(TradeRepository tradeRepository,
                                    ExecutionEngine executionEngine,
                                    KiteWebSocketClient webSocketClient,
                                    TelegramAlertService alertService) {
        this.tradeRepository = tradeRepository;
        this.executionEngine = executionEngine;
        this.webSocketClient = webSocketClient;
        this.alertService = alertService;
    }

    @EventListener
    public void onContextClosed(ContextClosedEvent event) {
        log.warn("[Shutdown] Application shutting down — closing open positions");
        alertService.systemAlert("⚠️ Application shutting down — closing open positions");

        List<TradeEntity> openTrades = tradeRepository.findByStatus(TradeStatus.OPEN);
        if (!openTrades.isEmpty()) {
            log.warn("[Shutdown] {} open trades to close", openTrades.size());
            for (TradeEntity trade : openTrades) {
                try {
                    executionEngine.closeTrade(trade.getTradeId(), trade.getEntryPrice(), "Graceful shutdown");
                    log.info("[Shutdown] Closed: tradeId={}", trade.getTradeId());
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
