package com.kiteapioptions.controller;

import com.kiteapioptions.domain.PnlSnapshot;
import com.kiteapioptions.domain.Position;
import com.kiteapioptions.persistence.OrderEntity;
import com.kiteapioptions.persistence.StrategyDecisionEntity;
import com.kiteapioptions.persistence.TradeEntity;
import com.kiteapioptions.reporting.ReportingService;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MonitoringController {

    private final ReportingService reportingService;

    public MonitoringController(ReportingService reportingService) {
        this.reportingService = reportingService;
    }

    @GetMapping("/positions")
    public List<Position> positions() {
        return reportingService.positions();
    }

    @GetMapping("/orders")
    public List<OrderEntity> orders() {
        return reportingService.orders();
    }

    @GetMapping("/trades")
    public List<TradeEntity> trades() {
        return reportingService.trades();
    }

    @GetMapping("/pnl")
    public PnlSnapshot pnl() {
        return reportingService.pnl();
    }

    @GetMapping("/signals/latest")
    public StrategyDecisionEntity latestSignal() {
        return reportingService.latestDecision().orElse(null);
    }

    @GetMapping(value = "/trades/journal.csv", produces = "text/csv")
    public String tradeJournalCsv() {
        return reportingService.tradeJournalCsv();
    }
}
