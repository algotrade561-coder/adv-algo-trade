package com.algo.trade.execution;

import com.algo.trade.domain.TradeStatus;
import com.algo.trade.notification.TelegramAlertService;
import com.algo.trade.persistence.TradeEntity;
import com.algo.trade.persistence.TradeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

/**
 * Fail-Safe Square-off Daemon — independent watchdog.
 *
 * Runs every minute after 15:10 IST. If any open trades remain after 15:20,
 * it forces them closed even if the main AlgoTradingScheduler has crashed.
 *
 * This is a safety net — the primary forced exit is in ExecutionEngine at 15:15.
 */
@Component
public class FailSafeSquareoffDaemon {

    private static final Logger log = LoggerFactory.getLogger(FailSafeSquareoffDaemon.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime FAILSAFE_TIME = LocalTime.of(15, 20);

    private final TradeRepository tradeRepository;
    private final ExecutionEngine executionEngine;
    private final TelegramAlertService alertService;

    public FailSafeSquareoffDaemon(TradeRepository tradeRepository,
                                    ExecutionEngine executionEngine,
                                    TelegramAlertService alertService) {
        this.tradeRepository = tradeRepository;
        this.executionEngine = executionEngine;
        this.alertService = alertService;
    }

    @Scheduled(cron = "0 * 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void check() {
        LocalTime now = LocalTime.now(IST);
        if (now.isBefore(FAILSAFE_TIME)) return;

        List<TradeEntity> openTrades = tradeRepository.findByStatus(TradeStatus.OPEN);
        if (openTrades.isEmpty()) return;

        log.warn("[FailSafe] {} open trades remain after {} — forcing close", openTrades.size(), FAILSAFE_TIME);
        alertService.systemAlert("🚨 FailSafe: " + openTrades.size() + " open trades after " + FAILSAFE_TIME + " — forcing close");

        for (TradeEntity trade : openTrades) {
            try {
                executionEngine.closeTrade(trade.getTradeId(), trade.getEntryPrice(), "FailSafe square-off 15:20");
                log.warn("[FailSafe] Force-closed: tradeId={} instrument={}", trade.getTradeId(), trade.getInstrumentKey());
            } catch (Exception e) {
                log.error("[FailSafe] Failed to close trade {}: {}", trade.getTradeId(), e.getMessage());
            }
        }
    }
}
