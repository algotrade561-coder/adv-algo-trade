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

    public ReportingService(BrokerClient brokerClient, TradeRepository tradeRepository, OrderRepository orderRepository,
                            StrategyDecisionRepository decisionRepository,
                            EntrySignalReplayReportService replayReportService) {
        this.brokerClient = brokerClient;
        this.tradeRepository = tradeRepository;
        this.orderRepository = orderRepository;
        this.decisionRepository = decisionRepository;
        this.replayReportService = replayReportService;
    }

    public List<Position> positions() {
        log.debug("Reporting positions requested");
        List<Position> positions = brokerClient.positions();
        log.debug("Reporting positions completed: count={}", positions.size());
        return positions;
    }

    public List<OrderEntity> orders() {
        List<OrderEntity> orders = orderRepository.findAll();
        log.debug("Reporting orders completed: count={}", orders.size());
        return orders;
    }

    public List<TradeEntity> trades() {
        List<TradeEntity> trades = tradeRepository.findAll();
        log.debug("Reporting trades completed: count={}", trades.size());
        return trades;
    }

    public PnlSnapshot pnl() {
        log.debug("Reporting PnL calculation started");
        BigDecimal realized = tradeRepository.findAll().stream()
                .map(TradeEntity::getRealizedPnl)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal unrealized = positions().stream()
                .map(Position::unrealizedPnl)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
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
        List<StrategyDecisionEntity> decisions = decisionRepository.findTop20ByOrderByTimestampDesc();
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

    public List<StrategyDecisionEntity> signalsByStrategy(String strategyType) {
        return decisionRepository.findTop100ByStrategyTypeOrderByTimestampDesc(strategyType);
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
        StringBuilder csv = new StringBuilder("tradeId,instrumentKey,underlying,optionType,status,quantity,entryPrice,exitPrice,entryTime,exitTime,realizedPnl,entryReason,exitReason\n");
        for (TradeEntity trade : tradeRepository.findAll()) {
            csv.append(trade.getTradeId()).append(',')
                    .append(trade.getInstrumentKey()).append(',')
                    .append(trade.getUnderlying()).append(',')
                    .append(trade.getOptionType()).append(',')
                    .append(trade.getStatus()).append(',')
                    .append(trade.getQuantity()).append(',')
                    .append(trade.getEntryPrice()).append(',')
                    .append(trade.getExitPrice()).append(',')
                    .append(trade.getEntryTime()).append(',')
                    .append(trade.getExitTime()).append(',')
                    .append(trade.getRealizedPnl()).append(',')
                    .append(escape(trade.getEntryReason())).append(',')
                    .append(escape(trade.getExitReason())).append('\n');
        }
        log.debug("Reporting trade journal CSV generation completed: bytes={}", csv.length());
        return csv.toString();
    }

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
}
