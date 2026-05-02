package com.algo.trade.ml;

import com.algo.trade.domain.TradeStatus;
import com.algo.trade.persistence.TradeEntity;
import com.algo.trade.persistence.TradeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Exit shadow recorder: captures market state at every exit evaluation
 * WITHOUT affecting exit decisions. Records what the system decided (exit or hold)
 * and later enriches with the actual outcome (was holding better or worse?).
 *
 * This is the exit-side equivalent of MlShadowRecorder for entries.
 *
 * CSV columns: timestamp, tradeId, instrument, strategyType, all exit features,
 * systemDecision (EXIT/HOLD), exitReason, actualOutcome (filled later).
 */
@Component
public class MlExitShadowRecorder {

    private static final Logger log = LoggerFactory.getLogger(MlExitShadowRecorder.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    static final Path OUTPUT_DIR = Path.of("reports/ml-shadow");
    static final Path EXIT_SHADOW_CSV = OUTPUT_DIR.resolve("ml-exit-shadow.csv");

    static final String HEADER = String.join(",",
            "timestamp", "tradeId", "instrumentKey", "strategyType", "underlying",
            // Features
            "profitPercent", "peakProfitPercent", "drawdownFromPeak", "holdMinutes",
            "entryPrice", "currentPrice",
            "vixLevel", "atr", "daysToExpiry",
            "entryIV", "currentIV", "ivChangePercent",
            "trailingStopActive", "trailingStopDistance",
            "optionType", "minutesSinceOpen", "isExpiryDay", "bidAskSpreadPercent",
            // System decision
            "systemDecision", "exitReason",
            // Outcome (filled later by enrichment)
            "priceAfter5min", "priceAfter15min", "finalExitPrice", "finalExitReason",
            "holdingWouldHaveBeenBetter"
    ) + System.lineSeparator();

    private final TradeRepository tradeRepository;

    /** Pending rows that need outcome enrichment. */
    private final ConcurrentLinkedQueue<PendingExitRow> pendingOutcomes = new ConcurrentLinkedQueue<>();

    /** Tracks last recording time per trade to avoid flooding CSV (max 1 row per trade per minute). */
    private final Map<String, Instant> lastRecordedTime = new java.util.concurrent.ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.monitoring.SchedulerRegistry schedulerRegistry;

    public MlExitShadowRecorder(TradeRepository tradeRepository) {
        this.tradeRepository = tradeRepository;
    }

    @jakarta.annotation.PostConstruct
    void registerScheduler() {
        if (schedulerRegistry != null) {
            schedulerRegistry.register("mlExitEnrich", "ML exit shadow outcome enrichment (5min)", 300_000, this::enrichOutcomes);
        }
    }

    /**
     * Record an exit evaluation snapshot. Called from LivePositionExitMonitor on every evaluation.
     *
     * @param tradeId the trade being evaluated
     * @param instrumentKey the option instrument
     * @param strategyType strategy that opened this trade
     * @param underlying NIFTY/BANKNIFTY/SENSEX
     * @param features the exit feature vector
     * @param systemExited true if the system decided to exit this evaluation
     * @param exitReason the exit reason (or "HOLD" if not exiting)
     */
    public void recordExitEvaluation(String tradeId, String instrumentKey, String strategyType,
                                      String underlying, MlExitFeatureVector features,
                                      boolean systemExited, String exitReason) {
        // Rate limit: max 1 row per trade per 60 seconds (exit monitor fires every candle close)
        Instant now = Instant.now();
        Instant lastRecorded = lastRecordedTime.get(tradeId);
        if (lastRecorded != null && now.isBefore(lastRecorded.plusSeconds(60))) {
            return; // skip — already recorded recently for this trade
        }
        // Always record exit events (not just holds)
        if (!systemExited && lastRecorded != null && now.isBefore(lastRecorded.plusSeconds(60))) {
            return;
        }
        lastRecordedTime.put(tradeId, now);

        try {
            String row = String.join(",",
                    csv(now.toString()),
                    csv(tradeId),
                    csv(instrumentKey),
                    csv(strategyType),
                    csv(underlying),
                    // Features
                    fmt(features.profitPercent()),
                    fmt(features.peakProfitPercent()),
                    fmt(features.drawdownFromPeak()),
                    fmt(features.holdMinutes()),
                    fmt(features.entryPrice()),
                    fmt(features.currentPrice()),
                    fmt(features.vixLevel()),
                    fmt(features.atr()),
                    fmt(features.daysToExpiry()),
                    fmt(features.entryIV()),
                    fmt(features.currentIV()),
                    fmt(features.ivChangePercent()),
                    fmt(features.trailingStopActive()),
                    fmt(features.trailingStopDistance()),
                    fmt(features.optionType()),
                    fmt(features.minutesSinceOpen()),
                    fmt(features.isExpiryDay()),
                    fmt(features.bidAskSpreadPercent()),
                    // System decision
                    csv(systemExited ? "EXIT" : "HOLD"),
                    csv(exitReason),
                    // Outcome placeholders
                    csv(""), csv(""), csv(""), csv(""), csv("")
            ) + System.lineSeparator();

            appendRow(row);

            // Queue exit events for outcome enrichment
            if (systemExited) {
                pendingOutcomes.add(new PendingExitRow(tradeId, now));
            }
        } catch (Exception e) {
            log.debug("ML exit shadow recording failed: {}", e.getMessage());
        }
    }

    /**
     * Enrich pending exit rows with actual outcomes.
     * For each exit: what was the price 5min and 15min after exit?
     * Was holding better (price went higher) or worse (price went lower)?
     */
    @Scheduled(fixedDelay = 300_000, initialDelay = 120_000)
    public void enrichOutcomes() {
        if (schedulerRegistry != null && !schedulerRegistry.isEnabled("mlExitEnrich")) return;
        if (pendingOutcomes.isEmpty()) return;

        List<PendingExitRow> toProcess = new ArrayList<>();
        PendingExitRow row;
        while ((row = pendingOutcomes.poll()) != null) {
            toProcess.add(row);
        }

        int enriched = 0;
        for (PendingExitRow pending : toProcess) {
            Optional<TradeEntity> trade = tradeRepository.findById(pending.tradeId);
            if (trade.isPresent() && trade.get().getStatus() == TradeStatus.CLOSED) {
                TradeEntity t = trade.get();
                // We can't know the price 5/15 min after exit without historical data
                // For now, record the final exit price and reason
                updateOutcome(pending.tradeId, t.getExitPrice(), t.getExitReason());
                enriched++;
            } else {
                // Not closed yet — re-queue if less than 2 hours old
                if (pending.timestamp.isAfter(Instant.now().minusSeconds(7200))) {
                    pendingOutcomes.add(pending);
                }
            }
        }

        if (enriched > 0) {
            log.info("ML exit shadow: enriched {} rows with outcomes", enriched);
        }
        if (schedulerRegistry != null) schedulerRegistry.recordRun("mlExitEnrich");
    }

    /**
     * Get summary statistics for the UI.
     */
    public ExitShadowSummary getSummary(String period) {
        if (!Files.exists(EXIT_SHADOW_CSV)) {
            return ExitShadowSummary.empty();
        }

        try (BufferedReader reader = Files.newBufferedReader(EXIT_SHADOW_CSV)) {
            String headerLine = reader.readLine();
            if (headerLine == null) return ExitShadowSummary.empty();

            String[] headers = headerLine.split(",");
            Map<String, Integer> idx = new HashMap<>();
            for (int i = 0; i < headers.length; i++) idx.put(headers[i].trim(), i);

            int totalEvaluations = 0, exitDecisions = 0, holdDecisions = 0;
            Map<String, Integer> exitReasonCounts = new LinkedHashMap<>();
            List<Map<String, Object>> recentRows = new ArrayList<>();
            double sumHoldMinutesAtExit = 0, sumProfitAtExit = 0, sumDrawdownAtExit = 0;

            LocalDate cutoff = periodCutoff(period);

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] vals = parseCsvLine(line);

                // Period filter
                String ts = safeGet(vals, idx.getOrDefault("timestamp", -1));
                if (cutoff != null && !ts.isEmpty()) {
                    try {
                        LocalDate rowDate = Instant.parse(ts).atZone(IST).toLocalDate();
                        if (rowDate.isBefore(cutoff)) continue;
                    } catch (Exception ignored) {}
                }

                totalEvaluations++;
                String decision = safeGet(vals, idx.getOrDefault("systemDecision", -1));
                if ("EXIT".equals(decision)) {
                    exitDecisions++;
                    String reason = safeGet(vals, idx.getOrDefault("exitReason", -1));
                    exitReasonCounts.merge(reason, 1, Integer::sum);
                    // Accumulate metrics for exit decisions
                    try {
                        sumHoldMinutesAtExit += Double.parseDouble(safeGet(vals, idx.getOrDefault("holdMinutes", -1)));
                        sumProfitAtExit += Double.parseDouble(safeGet(vals, idx.getOrDefault("profitPercent", -1)));
                        sumDrawdownAtExit += Double.parseDouble(safeGet(vals, idx.getOrDefault("drawdownFromPeak", -1)));
                    } catch (NumberFormatException ignored) {}
                } else {
                    holdDecisions++;
                }

                // Keep recent rows for the table
                if (recentRows.size() < 500) {
                    Map<String, Object> rowMap = new LinkedHashMap<>();
                    for (Map.Entry<String, Integer> e : idx.entrySet()) {
                        rowMap.put(e.getKey(), safeGet(vals, e.getValue()));
                    }
                    recentRows.add(rowMap);
                }
            }

            Collections.reverse(recentRows);
            if (recentRows.size() > 200) recentRows = recentRows.subList(0, 200);

            double exitRate = totalEvaluations > 0 ? (double) exitDecisions / totalEvaluations * 100 : 0;
            double avgHold = exitDecisions > 0 ? sumHoldMinutesAtExit / exitDecisions : 0;
            double avgProfit = exitDecisions > 0 ? sumProfitAtExit / exitDecisions : 0;
            double avgDrawdown = exitDecisions > 0 ? sumDrawdownAtExit / exitDecisions : 0;

            return new ExitShadowSummary(totalEvaluations, exitDecisions, holdDecisions,
                    exitRate, avgHold, avgProfit, avgDrawdown,
                    exitReasonCounts, recentRows);
        } catch (IOException e) {
            log.warn("Failed to read ML exit shadow CSV: {}", e.getMessage());
            return ExitShadowSummary.empty();
        }
    }

    /** Clean up stale tracking entries at midnight. */
    @Scheduled(cron = "0 0 0 * * *")
    public void resetDaily() {
        lastRecordedTime.clear();
        pendingOutcomes.clear();
    }

    // ── Internals ───────────────────────────────────────────────────────────

    private void appendRow(String row) {
        try {
            Files.createDirectories(OUTPUT_DIR);
            if (!Files.exists(EXIT_SHADOW_CSV)) {
                Files.writeString(EXIT_SHADOW_CSV, HEADER, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            }
            Files.writeString(EXIT_SHADOW_CSV, row, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("ML exit shadow CSV write failed: {}", e.getMessage());
        }
    }

    private void updateOutcome(String tradeId, BigDecimal exitPrice, String exitReason) {
        try {
            if (!Files.exists(EXIT_SHADOW_CSV)) return;
            List<String> lines = Files.readAllLines(EXIT_SHADOW_CSV);
            boolean updated = false;
            for (int i = lines.size() - 1; i >= 1; i--) {
                if (lines.get(i).contains(tradeId) && lines.get(i).contains("EXIT")) {
                    String[] parts = parseCsvLine(lines.get(i));
                    if (parts.length >= 30) {
                        parts[27] = exitPrice != null ? exitPrice.toPlainString() : "";
                        parts[28] = exitReason != null ? exitReason : "";
                        lines.set(i, String.join(",", parts));
                        updated = true;
                        break;
                    }
                }
            }
            if (updated) {
                Files.writeString(EXIT_SHADOW_CSV, String.join(System.lineSeparator(), lines) + System.lineSeparator());
            }
        } catch (IOException e) {
            log.debug("ML exit shadow outcome update failed: {}", e.getMessage());
        }
    }

    private static LocalDate periodCutoff(String period) {
        if (period == null) return null;
        return switch (period.toUpperCase()) {
            case "TODAY" -> LocalDate.now(IST);
            case "7D", "WEEK" -> LocalDate.now(IST).minusDays(7);
            case "30D", "MONTH" -> LocalDate.now(IST).minusDays(30);
            default -> null;
        };
    }

    private static String csv(Object v) {
        if (v == null) return "";
        String s = v.toString();
        if (s.contains(",") || s.contains("\"")) return "\"" + s.replace("\"", "\"\"") + "\"";
        return s;
    }

    private static String fmt(double v) {
        return String.format("%.4f", v);
    }

    private static String[] parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        boolean inQuotes = false;
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') inQuotes = !inQuotes;
            else if (c == ',' && !inQuotes) { fields.add(current.toString()); current = new StringBuilder(); }
            else current.append(c);
        }
        fields.add(current.toString());
        return fields.toArray(new String[0]);
    }

    private static String safeGet(String[] arr, int idx) {
        return (idx >= 0 && idx < arr.length) ? arr[idx].trim() : "";
    }

    record PendingExitRow(String tradeId, Instant timestamp) {}

    public record ExitShadowSummary(
            int totalEvaluations,
            int exitDecisions,
            int holdDecisions,
            double exitRatePercent,
            double avgHoldMinutesAtExit,
            double avgProfitAtExit,
            double avgDrawdownAtExit,
            Map<String, Integer> exitReasonCounts,
            List<Map<String, Object>> recentEvaluations
    ) {
        static ExitShadowSummary empty() {
            return new ExitShadowSummary(0, 0, 0, 0, 0, 0, 0, Map.of(), List.of());
        }
    }
}
