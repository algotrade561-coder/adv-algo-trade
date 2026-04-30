package com.algo.trade.execution;

import com.algo.trade.config.PositionSyncProperties;
import com.algo.trade.domain.TradeStatus;
import com.algo.trade.persistence.TradeEntity;
import com.algo.trade.persistence.TradeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Scheduled backup for exit evaluation — runs every 60 seconds.
 *
 * Delegates ALL exit logic to LivePositionExitMonitor.evaluateForScheduledCheck().
 * This ensures a single exit pipeline — no duplicated logic.
 *
 * Purpose: catch trades that the CandleClosedEvent-driven LivePositionExitMonitor
 * might miss if WebSocket ticks stop flowing (no candle events = no evaluation).
 */
@Component
public class MaxHoldExitMonitor {

    private static final Logger log = LoggerFactory.getLogger(MaxHoldExitMonitor.class);

    private final TradeRepository tradeRepository;
    private final LivePositionExitMonitor livePositionExitMonitor;
    private final TradingStateService tradingStateService;
    private final PositionSyncProperties positionSyncProperties;

    public MaxHoldExitMonitor(TradeRepository tradeRepository,
                               LivePositionExitMonitor livePositionExitMonitor,
                               TradingStateService tradingStateService,
                               PositionSyncProperties positionSyncProperties) {
        this.tradeRepository = tradeRepository;
        this.livePositionExitMonitor = livePositionExitMonitor;
        this.tradingStateService = tradingStateService;
        this.positionSyncProperties = positionSyncProperties;
    }

    /**
     * Scheduled backup — evaluates all open trades through the unified exit pipeline.
     * Runs every 60 seconds as a safety net for when CandleClosedEvent stops firing.
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 20_000)
    public void check() {
        if (!tradingStateService.isExitAllowed()) return;
        List<TradeEntity> openTrades = tradeRepository.findByStatus(TradeStatus.OPEN);
        if (openTrades.isEmpty()) return;

        log.debug("[MaxHoldBackup] Evaluating {} open trades via unified exit pipeline", openTrades.size());
        for (TradeEntity trade : openTrades) {
            if (!positionSyncProperties.manageSyncedTrades() && trade.getTradeId().startsWith("SYNC-")) continue;
            try {
                livePositionExitMonitor.evaluateForScheduledCheck(trade);
            } catch (Exception e) {
                log.error("[MaxHoldBackup] Error evaluating trade {}: {}", trade.getTradeId(), e.getMessage());
            }
        }
    }
}
