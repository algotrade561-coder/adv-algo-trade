package com.algo.trade.reporting;

import com.algo.trade.broker.BrokerClient;
import com.algo.trade.domain.PnlSnapshot;
import com.algo.trade.domain.Position;
import com.algo.trade.persistence.OrderEntity;
import com.algo.trade.persistence.OrderRepository;
import com.algo.trade.persistence.StrategyDecisionEntity;
import com.algo.trade.persistence.StrategyDecisionRepository;
import com.algo.trade.persistence.TradeEntity;
import com.algo.trade.persistence.TradeRepository;
import java.math.BigDecimal;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Read-side reporting facade for REST endpoints and journals.
 */
@Service
public class ReportingService {

    private static final Logger log = LoggerFactory.getLogger(ReportingService.class);
    private static final Path ENTRY_SIGNALS_DIR = Path.of("reports", "entry-signals");
    private static final Path ARCHIVE_DIR = Path.of("reports", "archive");
    private static final DateTimeFormatter ARCHIVE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneId.systemDefault());

    private final BrokerClient brokerClient;
    private final TradeRepository tradeRepository;
    private final OrderRepository orderRepository;
    private final StrategyDecisionRepository decisionRepository;
    private final EntrySignalReplayReportService replayReportService;
    private final SignalTuningReportService signalTuningReportService;
    private final com.algo.trade.marketdata.MarketDataService marketDataService;

    public ReportingService(BrokerClient brokerClient, TradeRepository tradeRepository, OrderRepository orderRepository,
                            StrategyDecisionRepository decisionRepository,
                            EntrySignalReplayReportService replayReportService,
                            SignalTuningReportService signalTuningReportService,
                            com.algo.trade.marketdata.MarketDataService marketDataService) {
        this.brokerClient = brokerClient;
        this.tradeRepository = tradeRepository;
        this.orderRepository = orderRepository;
        this.decisionRepository = decisionRepository;
        this.replayReportService = replayReportService;
        this.signalTuningReportService = signalTuningReportService;
        this.marketDataService = marketDataService;
    }

    public List<Position> positions() {
        log.debug("Reporting positions requested");
        List<Position> livePositions = brokerClient.positions();
        // Merge open paper trades as positions — Zerodha has no record of them
        List<Position> paperPositions = tradeRepository
                .findByStatus(com.algo.trade.domain.TradeStatus.OPEN).stream()
                .filter(TradeEntity::isPaperTrade)
                .map(t -> {
                    BigDecimal entry = t.getEntryPrice() != null ? t.getEntryPrice() : BigDecimal.ZERO;
                    BigDecimal last = marketDataService.quote(t.getInstrumentKey())
                            .map(q -> q.lastPrice())
                            .filter(p -> p != null && p.signum() > 0)
                            .orElse(entry);
                    boolean isShort = isShortTrade(t);
                    BigDecimal unrealized = isShort
                            ? entry.subtract(last).multiply(BigDecimal.valueOf(t.getQuantity()))
                            : last.subtract(entry).multiply(BigDecimal.valueOf(t.getQuantity()));
                    return new Position(t.getInstrumentKey(), t.getQuantity(), entry, last, unrealized);
                })
                .toList();
        List<Position> combined = new java.util.ArrayList<>(livePositions);
        combined.addAll(paperPositions);
        log.debug("Reporting positions completed: live={} paper={}", livePositions.size(), paperPositions.size());
        return combined;
    }

    public List<OrderEntity> orders() {
        List<OrderEntity> orders = orderRepository.findAll();
        log.debug("Reporting orders completed: count={}", orders.size());
        return orders;
    }

    public List<TradeEntity> trades() {
        // Return only today's trades for the monitoring page (not all historical)
        java.time.LocalDate today = java.time.LocalDate.now(ZoneId.of("Asia/Kolkata"));
        Instant dayStart = today.atStartOfDay(ZoneId.of("Asia/Kolkata")).toInstant();
        Instant dayEnd = today.plusDays(1).atStartOfDay(ZoneId.of("Asia/Kolkata")).toInstant();
        List<TradeEntity> trades = tradeRepository.findByEntryTimeBetween(dayStart, dayEnd);
        // Also include any OPEN trades from previous days (carry-forward positions)
        List<TradeEntity> openFromPrevDays = tradeRepository.findByStatus(com.algo.trade.domain.TradeStatus.OPEN).stream()
                .filter(t -> t.getEntryTime() != null && t.getEntryTime().isBefore(dayStart))
                .toList();
        List<TradeEntity> combined = new java.util.ArrayList<>(trades);
        combined.addAll(openFromPrevDays);
        log.debug("Reporting trades completed: today={}, openPrevDays={}, total={}",
                trades.size(), openFromPrevDays.size(), combined.size());
        return combined;
    }

    public PnlSnapshot pnl() {
        log.debug("Reporting PnL calculation started");
        java.time.LocalDate today = java.time.LocalDate.now(ZoneId.of("Asia/Kolkata"));
        Instant dayStart = today.atStartOfDay(ZoneId.of("Asia/Kolkata")).toInstant();
        Instant dayEnd = today.plusDays(1).atStartOfDay(ZoneId.of("Asia/Kolkata")).toInstant();

        var todayTrades = tradeRepository.findByEntryTimeBetween(dayStart, dayEnd).stream()
                .filter(t -> !t.isPaperTrade()).toList();

        // Realized: only from CLOSED trades
        BigDecimal closedRealized = todayTrades.stream()
                .filter(t -> t.getStatus() == com.algo.trade.domain.TradeStatus.CLOSED)
                .map(TradeEntity::getRealizedPnl)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Booked from partial exits on OPEN trades (already realized but trade still open)
        BigDecimal openBooked = todayTrades.stream()
                .filter(t -> t.getStatus() == com.algo.trade.domain.TradeStatus.OPEN)
                .map(TradeEntity::getBookedPnl)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Unrealized: calculated from OPEN TradeEntity records using live quotes.
        // Single source of truth — avoids double-counting with broker positions API.
        BigDecimal unrealized = todayTrades.stream()
                .filter(t -> t.getStatus() == com.algo.trade.domain.TradeStatus.OPEN)
                .map(t -> {
                    if (t.getEntryPrice() == null || t.getQuantity() <= 0) return BigDecimal.ZERO;
                    var quote = marketDataService.quote(t.getInstrumentKey());
                    if (quote.isEmpty() || quote.get().lastPrice().signum() <= 0) return BigDecimal.ZERO;
                    BigDecimal currentPrice = quote.get().lastPrice();
                    // Short positions: profit when price drops (entry - current)
                    // Long positions: profit when price rises (current - entry)
                    boolean isShort = "SHORT_POSITION".equals(t.getStrategyType())
                            || (t.getStrategyType() != null && !t.getStrategyType().isBlank()
                                && isSellingStrategyType(t.getStrategyType()));
                    return isShort
                            ? t.getEntryPrice().subtract(currentPrice).multiply(BigDecimal.valueOf(t.getQuantity()))
                            : currentPrice.subtract(t.getEntryPrice()).multiply(BigDecimal.valueOf(t.getQuantity()));
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Total = closed realized + open booked + open unrealized
        BigDecimal realized = closedRealized.add(openBooked);
        PnlSnapshot snapshot = new PnlSnapshot(Instant.now(), realized, unrealized, realized.add(unrealized));
        log.debug("Reporting PnL calculation completed: realized={}, unrealized={}, total={}",
                snapshot.realizedPnl(), snapshot.unrealizedPnl(), snapshot.totalPnl());
        return snapshot;
    }

    public Optional<StrategyDecisionEntity> latestDecision() {
        Optional<StrategyDecisionEntity> decision = decisionRepository.findTopByOrderByTimestampDesc();
        log.debug("Reporting latest decision completed: present={}", decision.isPresent());
        return decision;
    }

    public List<StrategyDecisionEntity> recentDecisions() {
        List<StrategyDecisionEntity> decisions = decisionRepository.findTop50ByOrderByTimestampDesc();
        log.debug("Reporting recent decisions completed: count={}", decisions.size());
        return decisions;
    }

    public List<StrategyDecisionEntity> entrySignals() {
        List<StrategyDecisionEntity> decisions = decisionRepository.findTop200BySignalTypeInOrderByTimestampDesc(
                List.of("BUY_CE", "BUY_PE"));
        log.debug("Reporting entry signals completed: count={}", decisions.size());
        return decisions;
    }

    public org.springframework.data.domain.Page<StrategyDecisionEntity> entrySignalsPaged(int page, int size) {
        return decisionRepository.findBySignalTypeInOrderByTimestampDesc(
                List.of("BUY_CE", "BUY_PE"),
                org.springframework.data.domain.PageRequest.of(page, size));
    }

    public org.springframework.data.domain.Page<StrategyDecisionEntity> entrySignalsPaged(
            int page, int size, java.time.Instant from, java.time.Instant to) {
        return decisionRepository.findBySignalTypeInAndTimestampBetweenOrderByTimestampDesc(
                List.of("BUY_CE", "BUY_PE"), from, to,
                org.springframework.data.domain.PageRequest.of(page, size));
    }

    public List<StrategyDecisionEntity> rejectedSignals() {
        List<StrategyDecisionEntity> decisions = decisionRepository.findTop200BySignalTypeOrderByTimestampDesc("NO_TRADE");
        log.debug("Reporting rejected signals completed: count={}", decisions.size());
        return decisions;
    }

    public org.springframework.data.domain.Page<StrategyDecisionEntity> rejectedSignalsPaged(int page, int size) {
        return decisionRepository.findBySignalTypeOrderByTimestampDesc(
                "NO_TRADE",
                org.springframework.data.domain.PageRequest.of(page, size));
    }

    public org.springframework.data.domain.Page<StrategyDecisionEntity> rejectedSignalsPaged(
            int page, int size, java.time.Instant from, java.time.Instant to) {
        return decisionRepository.findBySignalTypeAndTimestampBetweenOrderByTimestampDesc(
                "NO_TRADE", from, to,
                org.springframework.data.domain.PageRequest.of(page, size));
    }

    public List<StrategyDecisionEntity> signalsByStrategy(String strategyType) {
        return decisionRepository.findTop100ByStrategyTypeOrderByTimestampDesc(strategyType);
    }

    public long countEntrySignalsSince(java.time.Instant since) {
        return decisionRepository.countEntrySignalsSince(since);
    }

    public org.springframework.data.domain.Page<StrategyDecisionEntity> filteredSignalsPaged(
            List<String> signalTypes, int page, int size,
            java.time.Instant from, java.time.Instant to,
            String strategyType, String underlying, String optionType, String mode) {
        return decisionRepository.findFilteredSignals(
                signalTypes, from, to, strategyType, underlying, optionType, mode,
                org.springframework.data.domain.PageRequest.of(page, size));
    }

    public long countRejectedSignalsSince(java.time.Instant since) {
        return decisionRepository.countRejectedSignalsSince(since);
    }

    public List<java.util.Map<String, Object>> signalSummaryToday() {
        java.time.Instant since = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"))
                .atStartOfDay(java.time.ZoneId.of("Asia/Kolkata")).toInstant();
        List<Object[]> rows = decisionRepository.countByStrategyTypeSince(since);
        return rows.stream().map(row -> java.util.Map.<String, Object>of(
                "strategyType", row[0] != null ? row[0].toString() : "UNKNOWN",
                "count", ((Number) row[1]).longValue()
        )).toList();
    }

    public String tradeJournalCsv() {
        log.debug("Reporting trade journal CSV generation started");
        java.time.ZoneId ist = java.time.ZoneId.of("Asia/Kolkata");
        java.time.Instant startOfDay = java.time.LocalDate.now(ist).atStartOfDay(ist).toInstant();

        // ── Section 1: All signals today (entries + rejections) ────────────────
        StringBuilder csv = new StringBuilder();
        csv.append("=== SIGNALS (Today) ===\n");
        csv.append("timestamp,strategyType,signalType,underlying,optionType,instrument,strike,")
           .append("underlyingPrice,optionPrice,confidenceScore,executionStage,executionReason,firstFailedFilter,reasons\n");
        java.util.List<com.algo.trade.persistence.StrategyDecisionEntity> signals =
                decisionRepository.findByTimestampGreaterThanEqualOrderByTimestampAsc(startOfDay);
        for (com.algo.trade.persistence.StrategyDecisionEntity s : signals) {
            csv.append(s.getTimestamp()).append(',')
               .append(nullSafe(s.getStrategyType())).append(',')
               .append(nullSafe(s.getSignalType())).append(',')
               .append(nullSafe(s.getUnderlying())).append(',')
               .append(nullSafe(s.getOptionType())).append(',')
               .append(nullSafe(s.getSelectedInstrumentKey())).append(',')
               .append(nullSafe(s.getSelectedStrike())).append(',')
               .append(nullSafe(s.getUnderlyingPrice())).append(',')
               .append(nullSafe(s.getOptionPrice())).append(',')
               .append(nullSafe(s.getConfidenceScore())).append(',')
               .append(nullSafe(s.getExecutionStage())).append(',')
               .append(escape(s.getExecutionReason())).append(',')
               .append(nullSafe(s.getFirstFailedFilter())).append(',')
               .append(escape(s.getReasons())).append('\n');
        }

        // ── Section 2: All trades today ────────────────────────────────────────
        csv.append("\n=== TRADES (Today) ===\n");
        csv.append("tradeId,instrumentKey,underlying,optionType,status,quantity,")
           .append("entryPrice,exitPrice,entryTime,exitTime,realizedPnl,strategyType,entryReason,exitReason\n");
        for (TradeEntity trade : tradeRepository.findAll()) {
            if (trade.getEntryTime() == null || trade.getEntryTime().isBefore(startOfDay)) continue;
            csv.append(trade.getTradeId()).append(',')
               .append(nullSafe(trade.getInstrumentKey())).append(',')
               .append(nullSafe(trade.getUnderlying())).append(',')
               .append(nullSafe(trade.getOptionType())).append(',')
               .append(nullSafe(trade.getStatus())).append(',')
               .append(trade.getQuantity()).append(',')
               .append(nullSafe(trade.getEntryPrice())).append(',')
               .append(nullSafe(trade.getExitPrice())).append(',')
               .append(nullSafe(trade.getEntryTime())).append(',')
               .append(nullSafe(trade.getExitTime())).append(',')
               .append(nullSafe(trade.getRealizedPnl())).append(',')
               .append(nullSafe(trade.getStrategyType())).append(',')
               .append(escape(trade.getEntryReason())).append(',')
               .append(escape(trade.getExitReason())).append('\n');
        }

        log.debug("Reporting trade journal CSV generation completed: signals={} bytes={}", signals.size(), csv.length());
        return csv.toString();
    }

    private static String nullSafe(Object v) { return v == null ? "" : v.toString(); }

    public synchronized ReportArchiveResult archiveEntrySignalReports() {
        log.info("Entry signal report archive requested: sourceDir={}", ENTRY_SIGNALS_DIR);
        if (Files.notExists(ENTRY_SIGNALS_DIR)) {
            return new ReportArchiveResult(false, "", 0, 0, "No entry signal report directory found");
        }

        try {
            Files.createDirectories(ARCHIVE_DIR);
            List<Path> files;
            try (Stream<Path> paths = Files.list(ENTRY_SIGNALS_DIR)) {
                files = paths
                        .filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".csv"))
                        .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                        .toList();
            }
            if (files.isEmpty()) {
                return new ReportArchiveResult(false, "", 0, 0, "No entry signal CSV files found");
            }

            Path archive = ARCHIVE_DIR.resolve("entry-signals-" + ARCHIVE_FORMAT.format(Instant.now()) + ".zip");
            long bytes = writeZip(files, archive);
            for (Path file : files) {
                Files.deleteIfExists(file);
            }
            log.info("Entry signal reports archived: archive={}, files={}, bytes={}", archive, files.size(), bytes);
            return new ReportArchiveResult(true, archive.toString(), files.size(), bytes, "Archived and cleared active report files");
        } catch (IOException ex) {
            log.warn("Entry signal report archive failed: {}", ex.getMessage(), ex);
            throw new UncheckedIOException("Failed to archive entry signal reports", ex);
        }
    }

    public EntrySignalReplayReportService.ReplayRunResult generateEntrySignalReplayReport() {
        return replayReportService.generate();
    }

    public Optional<Path> latestEntrySignalReplayHtml() {
        return replayReportService.latestHtmlReport();
    }

    public String replayHtml(Path path) {
        return replayReportService.readHtml(path);
    }

    public SignalTuningReportService.TuningRunResult generateSignalTuningReport() {
        return signalTuningReportService.generate();
    }

    public Optional<Path> latestSignalTuningHtml() {
        return signalTuningReportService.latestHtmlReport();
    }

    public String signalTuningHtml(Path path) {
        return signalTuningReportService.readHtml(path);
    }

    private long writeZip(List<Path> files, Path archive) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            for (Path file : files) {
                ZipEntry entry = new ZipEntry(file.getFileName().toString());
                zip.putNextEntry(entry);
                Files.copy(file, zip);
                zip.closeEntry();
            }
        }
        return Files.size(archive);
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    public record ReportArchiveResult(
            boolean archived,
            String archivePath,
            int fileCount,
            long archiveBytes,
            String message
    ) {
    }

    /** Paper positions are identified by their instrument key matching an open paper trade. */
    private boolean isPaperPosition(Position position) {
        return tradeRepository.findByInstrumentKeyAndStatus(position.instrumentKey(), com.algo.trade.domain.TradeStatus.OPEN)
                .stream().anyMatch(TradeEntity::isPaperTrade);
    }

    /** Determine if a trade is a short entry based on strategy type, with fallback to entry reason. */
    private boolean isShortTrade(TradeEntity trade) {
        if ("SHORT_POSITION".equals(trade.getStrategyType())) return true;
        if (trade.getStrategyType() != null && !trade.getStrategyType().isBlank()) {
            try {
                return com.algo.trade.strategy.StrategyType.valueOf(trade.getStrategyType()).isSellingStrategy();
            } catch (IllegalArgumentException ignored) {}
        }
        String reason = trade.getEntryReason();
        return reason != null && (reason.contains("[SELL_CE]") || reason.contains("[SELL_PE]")
                || reason.contains("SHORT position"));
    }

    private boolean isSellingStrategyType(String strategyType) {
        if ("SHORT_POSITION".equals(strategyType)) return true;
        try {
            return com.algo.trade.strategy.StrategyType.valueOf(strategyType).isSellingStrategy();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
