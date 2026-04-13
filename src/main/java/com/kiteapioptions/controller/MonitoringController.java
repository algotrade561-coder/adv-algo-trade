package com.kiteapioptions.controller;

import com.kiteapioptions.domain.PnlSnapshot;
import com.kiteapioptions.domain.Position;
import com.kiteapioptions.persistence.OrderEntity;
import com.kiteapioptions.persistence.StrategyDecisionEntity;
import com.kiteapioptions.persistence.TradeEntity;
import com.kiteapioptions.reporting.ReportingService;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MonitoringController {

    private static final Logger log = LoggerFactory.getLogger(MonitoringController.class);

    private final ReportingService reportingService;

    public MonitoringController(ReportingService reportingService) {
        this.reportingService = reportingService;
    }

    @GetMapping("/positions")
    public List<Position> positions() {
        log.info("Positions endpoint called");
        List<Position> positions = reportingService.positions();
        log.info("Positions endpoint completed: count={}", positions.size());
        return positions;
    }

    @GetMapping("/orders")
    public List<OrderEntity> orders() {
        log.info("Orders endpoint called");
        List<OrderEntity> orders = reportingService.orders();
        log.info("Orders endpoint completed: count={}", orders.size());
        return orders;
    }

    @GetMapping("/trades")
    public List<TradeEntity> trades() {
        log.info("Trades endpoint called");
        List<TradeEntity> trades = reportingService.trades();
        log.info("Trades endpoint completed: count={}", trades.size());
        return trades;
    }

    @GetMapping("/pnl")
    public PnlSnapshot pnl() {
        log.info("PnL endpoint called");
        PnlSnapshot pnl = reportingService.pnl();
        log.info("PnL endpoint completed: realized={}, unrealized={}, total={}",
                pnl.realizedPnl(), pnl.unrealizedPnl(), pnl.totalPnl());
        return pnl;
    }

    @GetMapping("/signals/latest")
    public StrategyDecisionEntity latestSignal() {
        log.info("Latest signal endpoint called");
        StrategyDecisionEntity latest = reportingService.latestDecision().orElse(null);
        log.info("Latest signal endpoint completed: present={}", latest != null);
        return latest;
    }

    @GetMapping("/signals/recent")
    public List<StrategyDecisionEntity> recentSignals() {
        log.info("Recent signals endpoint called");
        List<StrategyDecisionEntity> decisions = reportingService.recentDecisions();
        log.info("Recent signals endpoint completed: count={}", decisions.size());
        return decisions;
    }

    @GetMapping(value = "/trades/journal.csv", produces = "text/csv")
    public String tradeJournalCsv() {
        log.info("Trade journal CSV endpoint called");
        String csv = reportingService.tradeJournalCsv();
        log.info("Trade journal CSV endpoint completed: bytes={}", csv.length());
        return csv;
    }
}
