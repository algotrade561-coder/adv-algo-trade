package com.algo.trade.controller;

import com.algo.trade.domain.IndexType;
import com.algo.trade.domain.PnlSnapshot;
import com.algo.trade.domain.Position;
import com.algo.trade.marketdata.LiveInstrumentCache;
import com.algo.trade.persistence.OrderEntity;
import com.algo.trade.persistence.StrategyDecisionEntity;
import com.algo.trade.persistence.TradeEntity;
import com.algo.trade.reporting.EntrySignalReplayReportService.ReplayRunResult;
import com.algo.trade.reporting.DailyReportBundleService;
import com.algo.trade.reporting.ReportingService;
import com.algo.trade.reporting.SignalTuningReportService.TuningRunResult;
import com.algo.trade.reporting.ReportingService.ReportArchiveResult;
import com.algo.trade.risk.MarketGuard;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.http.HttpStatus;

@RestController
public class MonitoringController {

    private static final Logger log = LoggerFactory.getLogger(MonitoringController.class);

    private final ReportingService reportingService;
    private final DailyReportBundleService dailyReportBundleService;
    private final MarketGuard marketGuard;
    private final LiveInstrumentCache liveInstrumentCache;
    private final com.algo.trade.marketdata.ExpiryCalendar expiryCalendar;
    private final com.algo.trade.persistence.StrategyDecisionRepository decisionRepository;
    private final com.algo.trade.marketdata.PcrCalculator pcrCalculator;
    private final com.algo.trade.indicator.IVRankTracker ivRankTracker;
    private final com.algo.trade.reporting.PerformanceMetricsService performanceMetricsService;
    private final com.algo.trade.monitoring.PositionStrengthService positionStrengthService;
    private final com.algo.trade.commodity.BrentCrudeService brentCrudeService;
    private final com.algo.trade.monitoring.HedgeCostTracker hedgeCostTracker;
    private final com.algo.trade.reporting.DailyBlockerSummaryService dailyBlockerSummaryService;

    public MonitoringController(ReportingService reportingService,
                                 DailyReportBundleService dailyReportBundleService,
                                 MarketGuard marketGuard,
                                 LiveInstrumentCache liveInstrumentCache,
                                 com.algo.trade.marketdata.ExpiryCalendar expiryCalendar,
                                 com.algo.trade.persistence.StrategyDecisionRepository decisionRepository,
                                 com.algo.trade.marketdata.PcrCalculator pcrCalculator,
                                 com.algo.trade.reporting.PerformanceMetricsService performanceMetricsService,
                                 com.algo.trade.monitoring.PositionStrengthService positionStrengthService,
                                 com.algo.trade.indicator.IVRankTracker ivRankTracker,
                                 com.algo.trade.commodity.BrentCrudeService brentCrudeService,
                                 com.algo.trade.monitoring.HedgeCostTracker hedgeCostTracker,
                                 com.algo.trade.reporting.DailyBlockerSummaryService dailyBlockerSummaryService) {
        this.reportingService = reportingService;
        this.dailyReportBundleService = dailyReportBundleService;
        this.marketGuard = marketGuard;
        this.liveInstrumentCache = liveInstrumentCache;
        this.expiryCalendar = expiryCalendar;
        this.decisionRepository = decisionRepository;
        this.pcrCalculator = pcrCalculator;
        this.performanceMetricsService = performanceMetricsService;
        this.positionStrengthService = positionStrengthService;
        this.ivRankTracker = ivRankTracker;
        this.brentCrudeService = brentCrudeService;
        this.hedgeCostTracker = hedgeCostTracker;
        this.dailyBlockerSummaryService = dailyBlockerSummaryService;
    }

    /**
     * Manually run the Daily Blocker Summary for a specific date. Useful for replaying
     * a past day to verify fix outcomes without waiting for the 15:35 IST cron.
     * Date format: yyyy-MM-dd. Defaults to today (IST) if omitted.
     */
    @GetMapping({"/reports/blocker-summary", "/monitoring/reports/blocker-summary"})
    public com.algo.trade.reporting.DailyBlockerSummaryService.DigestPayload blockerSummary(
            @RequestParam(value = "date", required = false) String dateStr) {
        LocalDate date = (dateStr == null || dateStr.isBlank())
                ? LocalDate.now(ZoneId.of("Asia/Kolkata"))
                : LocalDate.parse(dateStr);
        return dailyBlockerSummaryService.runSummary(date);
    }

    @GetMapping("/market")
    public Map<String, Object> market() {
        double vix      = marketGuard.getCurrentVix();
        double nifty    = liveInstrumentCache.getFuturesPrice(IndexType.NIFTY);
        double banknifty = liveInstrumentCache.getFuturesPrice(IndexType.BANKNIFTY);
        double sensex   = liveInstrumentCache.getFuturesPrice(IndexType.SENSEX);

        // Use full-chain PCR from PcrCalculator (all strikes, not just subscribed)
        double pcr = pcrCalculator.getPcr();
        if (pcr <= 0) pcr = marketGuard.getCurrentPcr(); // fall back to last known value

        // IV Rank from tracker
        double ivRankNifty = 0;
        try {
            ivRankNifty = ivRankTracker.getIVRank(IndexType.NIFTY);
        } catch (Exception ignored) {}

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("vix", vix);
        result.put("pcr", pcr);
        result.put("nifty", nifty);
        result.put("banknifty", banknifty);
        result.put("sensex", sensex);
        result.put("ivRank", ivRankNifty);
        result.put("vixStatus", vixStatus(vix));
        result.put("pcrBias", pcrBias(pcr));
        result.put("circuitBreakerTriggered", marketGuard.isCircuitBreakerTriggered());
        result.put("eventDay", marketGuard.isEventDay());
        result.put("preEventDay", marketGuard.isPreEventDay());
        result.put("safeForLongPremium", marketGuard.isSafeForLongPremium());
        result.put("longPremiumBlockReason", marketGuard.longPremiumBlockReason());
        result.put("brentCrude", brentCrudeService.getLastPriceUSD());
        result.put("brentCrudeAvailable", brentCrudeService.isAvailable());
        result.put("hedgeCost", hedgeCostSummary());
        return result;
    }

    @GetMapping("/hedge-cost/summary")
    public com.algo.trade.monitoring.HedgeCostTracker.DailySummary hedgeCostSummary() {
        return hedgeCostTracker.dailySummary();
    }

    @GetMapping("/performance")
    public com.algo.trade.reporting.PerformanceMetricsService.PerformanceSnapshot performance() {
        return performanceMetricsService.getSnapshot();
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

    @GetMapping("/positions/health")
    public List<com.algo.trade.monitoring.PositionStrengthService.StrengthResult> positionHealth() {
        log.info("Position health endpoint called");
        List<com.algo.trade.monitoring.PositionStrengthService.StrengthResult> results = positionStrengthService.evaluateAll();
        log.info("Position health endpoint completed: count={}", results.size());
        return results;
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
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String period,
            @RequestParam(required = false) String strategyType,
            @RequestParam(required = false) String underlying,
            @RequestParam(required = false) String optionType,
            @RequestParam(required = false) String mode) {
        Instant[] range = periodToRange(period);
        return reportingService.filteredSignalsPaged(
                List.of("BUY_CE", "BUY_PE"), page, Math.min(size, 200),
                range != null ? range[0] : null, range != null ? range[1] : null,
                normalizeFilter(strategyType), normalizeFilter(underlying), normalizeFilter(optionType),
                normalizeFilter(mode));
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
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String period,
            @RequestParam(required = false) String strategyType,
            @RequestParam(required = false) String underlying,
            @RequestParam(required = false) String optionType,
            @RequestParam(required = false) String mode) {
        Instant[] range = periodToRange(period);
        return reportingService.filteredSignalsPaged(
                List.of("NO_TRADE"), page, Math.min(size, 200),
                range != null ? range[0] : null, range != null ? range[1] : null,
                normalizeFilter(strategyType), normalizeFilter(underlying), normalizeFilter(optionType),
                normalizeFilter(mode));
    }

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private static Instant[] periodToRange(String period) {
        if (period == null || period.isBlank() || "ALL".equalsIgnoreCase(period)) return null;
        LocalDate today = LocalDate.now(IST);
        return switch (period.toUpperCase()) {
            case "TODAY"     -> dayRange(today);
            case "YESTERDAY" -> dayRange(today.minusDays(1));
            case "LAST7"     -> new Instant[]{ today.minusDays(6).atStartOfDay(IST).toInstant(), Instant.now() };
            case "LAST30"    -> new Instant[]{ today.minusDays(29).atStartOfDay(IST).toInstant(), Instant.now() };
            default          -> null;
        };
    }

    private static Instant[] dayRange(LocalDate date) {
        return new Instant[]{ date.atStartOfDay(IST).toInstant(), date.plusDays(1).atStartOfDay(IST).toInstant() };
    }

    /** Normalize filter param: treat blank/ALL as empty so JPQL COALESCE skips the condition. */
    private static String normalizeFilter(String value) {
        return (value == null || value.isBlank() || "ALL".equalsIgnoreCase(value)) ? "" : value;
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

    /** Filter funnel — why NO_TRADE? Returns counts grouped by firstFailedFilter. */
    @GetMapping("/signals/filter-funnel")
    public List<Map<String, Object>> filterFunnel(
            @RequestParam(defaultValue = "TODAY") String period) {
        Instant[] range = periodToRange(period);
        Instant since = range != null ? range[0]
                : LocalDate.now(IST).atStartOfDay(IST).toInstant();
        List<Object[]> rows = decisionRepository.countByFirstFailedFilterSince(since);
        return rows.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("filter", r[0] != null ? r[0].toString() : "unknown");
            m.put("count", ((Number) r[1]).longValue());
            return m;
        }).collect(java.util.stream.Collectors.toList());
    }

    /** Strategy scorecard — fill rate, avg IV rank, avg spread per strategy type. */
    @GetMapping("/signals/strategy-scorecard")
    public List<Map<String, Object>> strategyScorecard(
            @RequestParam(defaultValue = "LAST30") String period) {
        Instant[] range = periodToRange(period);
        Instant since = range != null ? range[0]
                : Instant.now().minus(30, java.time.temporal.ChronoUnit.DAYS);
        List<com.algo.trade.persistence.StrategyDecisionEntity> entries =
                decisionRepository.findEntrySignalsSince(since);
        Map<String, List<com.algo.trade.persistence.StrategyDecisionEntity>> byStrategy =
                entries.stream().collect(java.util.stream.Collectors.groupingBy(
                        e -> e.getStrategyType() != null ? e.getStrategyType() : "UNKNOWN"));
        List<Map<String, Object>> result = new java.util.ArrayList<>();
        for (var kv : byStrategy.entrySet()) {
            List<com.algo.trade.persistence.StrategyDecisionEntity> list = kv.getValue();
            long filled   = list.stream().filter(e -> isFilledStage(e.getExecutionStage())).count();
            long rejected = list.stream().filter(e -> isRejectedStage(e.getExecutionStage())).count();
            double avgIvRank = list.stream()
                    .filter(e -> e.getIvRank() != null && e.getIvRank() > 0)
                    .mapToDouble(com.algo.trade.persistence.StrategyDecisionEntity::getIvRank)
                    .average().orElse(0);
            double avgSpread = list.stream()
                    .filter(e -> e.getOptionAsk() != null && e.getOptionBid() != null
                              && e.getOptionAsk().compareTo(java.math.BigDecimal.ZERO) > 0)
                    .mapToDouble(e -> e.getOptionAsk().subtract(e.getOptionBid()).doubleValue())
                    .average().orElse(0);
            String ivSrc = list.stream()
                    .filter(e -> "TRACKER".equals(e.getIvRankSource())).findAny()
                    .map(e -> "TRACKER").orElse("NEUTRAL");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("strategyType", kv.getKey());
            m.put("totalEntries", list.size());
            m.put("filled", filled);
            m.put("rejected", rejected);
            m.put("fillRate", list.isEmpty() ? 0 : Math.round(filled * 100.0 / list.size()));
            m.put("avgIvRank", Math.round(avgIvRank * 10.0) / 10.0);
            m.put("avgSpread", Math.round(avgSpread * 100.0) / 100.0);
            m.put("ivRankSource", ivSrc);
            result.add(m);
        }
        result.sort(java.util.Comparator.<Map<String, Object>, Long>comparing(
                m -> (long) ((Number) m.get("totalEntries")).longValue()).reversed());
        return result;
    }

    private static boolean isFilledStage(String stage) {
        return "ORDER_FILLED".equals(stage) || "PAPER_FILLED".equals(stage);
    }

    private static boolean isRejectedStage(String stage) {
        return stage != null && (stage.contains("REJECTED") || "TRADING_STOPPED".equals(stage)
                || "BROKER_ERROR".equals(stage));
    }

    @GetMapping(value = "/trades/journal.csv", produces = "text/csv")
    public String tradeJournalCsv() {
        log.info("Trade journal CSV endpoint called");
        String csv = reportingService.tradeJournalCsv();
        log.info("Trade journal CSV endpoint completed: bytes={}", csv.length());
        return csv;
    }

    /** Report APIs: {@code /reports/...} and alias {@code /monitoring/reports/...}. */

    @GetMapping({"/reports/download/today/summary", "/monitoring/reports/download/today/summary"})
    public Map<String, Object> todayAnalysisDownloadSummary() {
        return dailyReportBundleService.summary(dailyReportBundleService.todayIst()).toMap();
    }

    @GetMapping({"/reports/download/today/signals", "/monitoring/reports/download/today/signals"})
    public ResponseEntity<StreamingResponseBody> downloadTodaySignals() {
        var summary = dailyReportBundleService.summary(dailyReportBundleService.todayIst());
        return todayZipResponse("entry-signals", dailyReportBundleService::writeSignalsZip, summary.signals().fileCount());
    }

    @GetMapping({"/reports/download/today/logs", "/monitoring/reports/download/today/logs"})
    public ResponseEntity<StreamingResponseBody> downloadTodayLogs() {
        var summary = dailyReportBundleService.summary(dailyReportBundleService.todayIst());
        return todayZipResponse("application-logs", dailyReportBundleService::writeLogsZip, summary.logs().fileCount());
    }

    @GetMapping({"/reports/download/today/chain-snapshots", "/monitoring/reports/download/today/chain-snapshots"})
    public ResponseEntity<StreamingResponseBody> downloadTodayChainSnapshots() {
        var summary = dailyReportBundleService.summary(dailyReportBundleService.todayIst());
        return todayZipResponse("chain-snapshots", dailyReportBundleService::writeChainSnapshotsZip,
                summary.chainSnapshots().fileCount());
    }

    @GetMapping({"/reports/download/today/all", "/monitoring/reports/download/today/all"})
    public ResponseEntity<StreamingResponseBody> downloadTodayFullAnalysisPack() {
        var summary = dailyReportBundleService.summary(dailyReportBundleService.todayIst());
        return todayZipResponse("analysis-pack", dailyReportBundleService::writeFullAnalysisZip, summary.totalFiles());
    }

    private ResponseEntity<StreamingResponseBody> todayZipResponse(
            String filenamePrefix,
            ZipWriter writer,
            int fileCount) {
        if (fileCount == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "No files found for today (IST). Nothing to download yet.");
        }
        var date = dailyReportBundleService.todayIst();
        String filename = dailyReportBundleService.zipFilename(filenamePrefix, date);
        log.info("Today analysis ZIP download: prefix={}, date={}, files={}", filenamePrefix, date, fileCount);
        StreamingResponseBody body = out -> writer.write(date, out);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(body);
    }

    @FunctionalInterface
    private interface ZipWriter {
        void write(LocalDate date, java.io.OutputStream out);
    }

    @PostMapping({"/reports/entry-signals/archive", "/monitoring/reports/entry-signals/archive"})
    public ReportArchiveResult archiveEntrySignalReports() {
        log.info("Entry signal report archive endpoint called");
        ReportArchiveResult result = reportingService.archiveEntrySignalReports();
        log.info("Entry signal report archive endpoint completed: archived={}, archivePath={}, fileCount={}, archiveBytes={}",
                result.archived(), result.archivePath(), result.fileCount(), result.archiveBytes());
        return result;
    }

    @PostMapping({"/reports/entry-signals/replay", "/monitoring/reports/entry-signals/replay"})
    public ReplayRunResult replayEntrySignalReports() {
        log.info("Entry signal replay endpoint called");
        ReplayRunResult result = reportingService.generateEntrySignalReplayReport();
        log.info("Entry signal replay endpoint completed: evaluations={}, trades={}, htmlPath={}",
                result.summary().totalEvaluations(), result.summary().executedTrades(), result.htmlReportPath());
        return result;
    }

    @GetMapping(value = {"/reports/entry-signals/replay/report", "/monitoring/reports/entry-signals/replay/report"},
            produces = MediaType.TEXT_HTML_VALUE)
    public String replayEntrySignalReportHtml(@RequestParam(required = false) String path) {
        log.info("Entry signal replay HTML report endpoint called: path={}", path);
        Path resolved = path == null || path.isBlank()
                ? reportingService.latestEntrySignalReplayHtml().orElseThrow(
                () -> new IllegalArgumentException("No replay HTML report has been generated yet"))
                : Path.of(path);
        return reportingService.replayHtml(resolved);
    }

    @PostMapping({"/reports/signal-tuning/generate", "/monitoring/reports/signal-tuning/generate"})
    public TuningRunResult generateSignalTuningReport() {
        log.info("Signal tuning report generate endpoint called");
        TuningRunResult result = reportingService.generateSignalTuningReport();
        log.info("Signal tuning report generate completed: evals={}, buys={}, htmlPath={}",
                result.totalEvaluations(), result.buySignals(), result.htmlReportPath());
        return result;
    }

    @GetMapping(value = {"/reports/signal-tuning/report", "/monitoring/reports/signal-tuning/report"},
            produces = MediaType.TEXT_HTML_VALUE)
    public String signalTuningReportHtml(@RequestParam(required = false) String path) {
        log.info("Signal tuning HTML report endpoint called: path={}", path);
        Path resolved = path == null || path.isBlank()
                ? reportingService.latestSignalTuningHtml().orElseThrow(
                () -> new IllegalArgumentException("No signal tuning HTML report has been generated yet"))
                : Path.of(path);
        return reportingService.signalTuningHtml(resolved);
    }
}
