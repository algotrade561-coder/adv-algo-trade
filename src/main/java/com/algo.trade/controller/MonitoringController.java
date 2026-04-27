package com.algo.trade.controller;

import com.algo.trade.domain.IndexType;
import com.algo.trade.domain.PnlSnapshot;
import com.algo.trade.domain.Position;
import com.algo.trade.marketdata.LiveInstrumentCache;
import com.algo.trade.persistence.OrderEntity;
import com.algo.trade.persistence.StrategyDecisionEntity;
import com.algo.trade.persistence.TradeEntity;
import com.algo.trade.reporting.EntrySignalReplayReportService.ReplayRunResult;
import com.algo.trade.reporting.ReportingService;
import com.algo.trade.reporting.ReportingService.ReportArchiveResult;
import com.algo.trade.risk.MarketGuard;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

@RestController
public class MonitoringController {

    private static final Logger log = LoggerFactory.getLogger(MonitoringController.class);

    private final ReportingService reportingService;
    private final MarketGuard marketGuard;
    private final LiveInstrumentCache liveInstrumentCache;
    private final com.algo.trade.marketdata.ExpiryCalendar expiryCalendar;

    public MonitoringController(ReportingService reportingService, MarketGuard marketGuard,
                                 LiveInstrumentCache liveInstrumentCache,
                                 com.algo.trade.marketdata.ExpiryCalendar expiryCalendar) {
        this.reportingService = reportingService;
        this.marketGuard = marketGuard;
        this.liveInstrumentCache = liveInstrumentCache;
        this.expiryCalendar = expiryCalendar;
    }

    @GetMapping("/market")
    public Map<String, Object> market() {
        double vix      = marketGuard.getCurrentVix();
        double nifty    = liveInstrumentCache.getFuturesPrice(IndexType.NIFTY);
        double banknifty = liveInstrumentCache.getFuturesPrice(IndexType.BANKNIFTY);

        // Compute PCR live from option OI in LiveInstrumentCache
        // This updates on every tick, not just every candle close
        double pcr = computeLivePcr(IndexType.NIFTY);
        if (pcr > 0) marketGuard.updatePcr(pcr); // keep MarketGuard in sync
        else pcr = marketGuard.getCurrentPcr();   // fall back to last known value

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("vix", vix);
        result.put("pcr", pcr);
        result.put("nifty", nifty);
        result.put("banknifty", banknifty);
        result.put("vixStatus", vixStatus(vix));
        result.put("pcrBias", pcrBias(pcr));
        result.put("circuitBreakerTriggered", marketGuard.isCircuitBreakerTriggered());
        result.put("eventDay", marketGuard.isEventDay());
        result.put("preEventDay", marketGuard.isPreEventDay());
        result.put("safeForLongPremium", marketGuard.isSafeForLongPremium());
        result.put("longPremiumBlockReason", marketGuard.longPremiumBlockReason());
        return result;
    }

    /** Compute PCR from live OI in LiveInstrumentCache — updates on every tick. */
    private double computeLivePcr(IndexType indexType) {
        try {
            double spot = liveInstrumentCache.getFuturesPrice(indexType);
            if (spot <= 0) return 0;
            java.time.LocalDate expiry = expiryCalendar.getCurrentWeeklyExpiry(indexType);
            var chain = liveInstrumentCache.getStrikeChain(indexType, expiry);
            if (chain.isEmpty()) return 0;
            long totalCallOi = chain.stream()
                    .filter(o -> "CE".equals(o.getOptionType()))
                    .mapToLong(com.algo.trade.domain.OptionInstrument::getOpenInterest)
                    .sum();
            long totalPutOi = chain.stream()
                    .filter(o -> "PE".equals(o.getOptionType()))
                    .mapToLong(com.algo.trade.domain.OptionInstrument::getOpenInterest)
                    .sum();
            return totalCallOi > 0 ? (double) totalPutOi / totalCallOi : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private String vixStatus(double vix) {
        if (vix <= 0) return "UNKNOWN";
        if (vix < 12) return "LOW";
        if (vix <= 18) return "NORMAL";
        if (vix <= 21) return "ELEVATED";
        return "HIGH";
    }

    private String pcrBias(double pcr) {
        if (pcr <= 0) return "UNKNOWN";
        if (pcr > 1.3) return "BULLISH";
        if (pcr < 0.7) return "BEARISH";
        return "NEUTRAL";
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

    @GetMapping("/signals/entries")
    public List<StrategyDecisionEntity> entrySignals() {
        log.info("Entry signals endpoint called");
        List<StrategyDecisionEntity> decisions = reportingService.entrySignals();
        log.info("Entry signals endpoint completed: count={}", decisions.size());
        return decisions;
    }

    @GetMapping("/signals/entries/paged")
    public org.springframework.data.domain.Page<StrategyDecisionEntity> entrySignalsPaged(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return reportingService.entrySignalsPaged(page, Math.min(size, 200));
    }

    @GetMapping("/signals/rejected")
    public List<StrategyDecisionEntity> rejectedSignals() {
        log.info("Rejected signals endpoint called");
        List<StrategyDecisionEntity> decisions = reportingService.rejectedSignals();
        log.info("Rejected signals endpoint completed: count={}", decisions.size());
        return decisions;
    }

    @GetMapping("/signals/rejected/paged")
    public org.springframework.data.domain.Page<StrategyDecisionEntity> rejectedSignalsPaged(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return reportingService.rejectedSignalsPaged(page, Math.min(size, 200));
    }

    /** Signals filtered by strategy type — new endpoint for strategy-aware UI. */
    @GetMapping("/signals/by-strategy/{strategyType}")
    public List<StrategyDecisionEntity> signalsByStrategy(@PathVariable String strategyType) {
        log.info("Signals by strategy endpoint called: strategyType={}", strategyType);
        return reportingService.signalsByStrategy(strategyType);
    }

    /** Signal counts per strategy type for today — for the monitoring dashboard. */
    @GetMapping("/signals/summary")
    public List<java.util.Map<String, Object>> signalSummary() {
        log.info("Signal summary endpoint called");
        return reportingService.signalSummaryToday();
    }

    @GetMapping(value = "/trades/journal.csv", produces = "text/csv")
    public String tradeJournalCsv() {
        log.info("Trade journal CSV endpoint called");
        String csv = reportingService.tradeJournalCsv();
        log.info("Trade journal CSV endpoint completed: bytes={}", csv.length());
        return csv;
    }

    @PostMapping("/reports/entry-signals/archive")
    public ReportArchiveResult archiveEntrySignalReports() {
        log.info("Entry signal report archive endpoint called");
        ReportArchiveResult result = reportingService.archiveEntrySignalReports();
        log.info("Entry signal report archive endpoint completed: archived={}, archivePath={}, fileCount={}, archiveBytes={}",
                result.archived(), result.archivePath(), result.fileCount(), result.archiveBytes());
        return result;
    }

    @PostMapping("/reports/entry-signals/replay")
    public ReplayRunResult replayEntrySignalReports() {
        log.info("Entry signal replay endpoint called");
        ReplayRunResult result = reportingService.generateEntrySignalReplayReport();
        log.info("Entry signal replay endpoint completed: evaluations={}, trades={}, htmlPath={}",
                result.summary().totalEvaluations(), result.summary().executedTrades(), result.htmlReportPath());
        return result;
    }

    @GetMapping(value = "/reports/entry-signals/replay/report", produces = MediaType.TEXT_HTML_VALUE)
    public String replayEntrySignalReportHtml(@RequestParam(required = false) String path) {
        log.info("Entry signal replay HTML report endpoint called: path={}", path);
        Path resolved = path == null || path.isBlank()
                ? reportingService.latestEntrySignalReplayHtml().orElseThrow(
                () -> new IllegalArgumentException("No replay HTML report has been generated yet"))
                : Path.of(path);
        return reportingService.replayHtml(resolved);
    }
}
