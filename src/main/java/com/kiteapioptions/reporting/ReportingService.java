package com.kiteapioptions.reporting;

import com.kiteapioptions.broker.BrokerClient;
import com.kiteapioptions.domain.PnlSnapshot;
import com.kiteapioptions.domain.Position;
import com.kiteapioptions.persistence.OrderEntity;
import com.kiteapioptions.persistence.OrderRepository;
import com.kiteapioptions.persistence.StrategyDecisionEntity;
import com.kiteapioptions.persistence.StrategyDecisionRepository;
import com.kiteapioptions.persistence.TradeEntity;
import com.kiteapioptions.persistence.TradeRepository;
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

    public ReportingService(BrokerClient brokerClient, TradeRepository tradeRepository, OrderRepository orderRepository,
                            StrategyDecisionRepository decisionRepository) {
        this.brokerClient = brokerClient;
        this.tradeRepository = tradeRepository;
        this.orderRepository = orderRepository;
        this.decisionRepository = decisionRepository;
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
