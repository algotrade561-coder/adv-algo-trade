package com.algo.trade.ml;

import com.algo.trade.domain.StrategyDecision;
import com.algo.trade.persistence.StrategyDecisionEntity;
import com.algo.trade.persistence.StrategyDecisionRepository;
import com.algo.trade.persistence.TradeEntity;
import com.algo.trade.persistence.TradeRepository;
import com.algo.trade.domain.TradeStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.BufferedWriter;
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
 * Shadow recorder: runs ML scoring on every signal WITHOUT affecting decisions.
 * Records both system score and ML score side-by-side in a dedicated CSV.
 * Periodically enriches rows with actual trade outcomes for comparison.
 *
 * <p>CSV columns:</p>
 * <pre>
 * timestamp, decisionKey, underlying, optionType, signalType,
 * systemScore, mlScore, mlProbability, systemDecision, mlWouldDecide,
 * decisionsAgree, tradeOutcome, tradePnl, tradeExitReason,
 * mlWouldHaveBeenRight, systemWasRight
 * </pre>
 */
@Component
public class MlShadowRecorder {

    private static final Logger log = LoggerFactory.getLogger(MlShadowRecorder.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    static final Path OUTPUT_DIR = Path.of("reports/ml-shadow");
    static final Path SHADOW_CSV = OUTPUT_DIR.resolve("ml-shadow-scores.csv");

    static final String HEADER = String.join(",",
            "timestamp", "decisionKey", "underlying", "optionType", "strategyType",
            "signalType", "instrumentKey", "underlyingPrice", "optionPremium",
            "systemScore", "mlScore", "mlProbability",
            "systemDecision", "mlWouldDecide", "decisionsAgree",
            "minScoreThreshold",
            "tradeOutcome", "tradePnl", "tradeExitReason",
            "mlWouldHaveBeenRight", "systemWasRight"
    ) + System.lineSeparator();

    private final MlSignalScorer scorer;
    private final MlFeatureExtractor featureExtractor;
    private final TradeRepository tradeRepository;
    private final StrategyDecisionRepository decisionRepository;
    private final MlVirtualTradeTracker virtualTradeTracker;

    /** Pending rows that need trade outcome enrichment. */
    private final ConcurrentLinkedQueue<PendingRow> pendingOutcomes = new ConcurrentLinkedQueue<>();

    public MlShadowRecorder(MlSignalScorer scorer,
                             MlFeatureExtractor featureExtractor,
                             TradeRepository tradeRepository,
                             StrategyDecisionRepository decisionRepository,
                             MlVirtualTradeTracker virtualTradeTracker) {
        this.scorer = scorer;
        this.featureExtractor = featureExtractor;
        this.tradeRepository = tradeRepository;
        this.decisionRepository = decisionRepository;
        this.virtualTradeTracker = virtualTradeTracker;
    }

    /**
     * Record a shadow ML score for a signal. Called from the scheduler after each evaluation.
     * Does NOT affect the trading decision in any way.
     */
    public void recordShadowScore(
            StrategyDecision decision,
            Map<String, String> csvRowData,
            String decisionKey,
            BigDecimal minScoreThreshold,
            BigDecimal mlVirtualTradeThreshold
    ) {
        if (!scorer.isModelLoaded()) return;

        try {
            MlFeatureVector features = featureExtractor.extractFromCsvRow(csvRowData);
            double[] featureArray = features.toArray();

            GradientBoostedTreeModel model = getModel();
            if (model == null) return;

            double mlProbability = model.predictProbability(featureArray);
            int mlScore = model.predictConfidenceScore(featureArray);
            int systemScore = decision.confidenceScore().intValue();

            boolean systemDecision = decision.signalType().name().startsWith("BUY_");
            boolean mlWouldDecide = mlScore >= minScoreThreshold.intValue();
            boolean agree = systemDecision == mlWouldDecide;

            // For virtual trades, use the separate (lower) ML threshold
            // Fall back to minScoreThreshold if mlVirtualTradeThreshold is not yet set (pre-migration DB rows)
            BigDecimal effectiveMlThreshold = mlVirtualTradeThreshold != null ? mlVirtualTradeThreshold : minScoreThreshold;
            boolean mlWouldEnterVirtual = mlScore >= effectiveMlThreshold.intValue();

            String row = String.join(",",
                    csv(decision.timestamp().toString()),
                    csv(decisionKey),
                    csv(decision.underlying().name()),
                    csv(decision.optionType().map(Enum::name).orElse("")),
                    csv("DIRECTIONAL_BUY"),
                    csv(decision.signalType().name()),
                    csv(decision.selectedInstrumentKey().orElse("")),
                    csv(decision.underlyingPrice()),
                    csv(decision.optionPrice().orElse(BigDecimal.ZERO)),
                    csv(systemScore),
                    csv(mlScore),
                    csv(String.format("%.4f", mlProbability)),
                    csv(systemDecision ? "ENTER" : "SKIP"),
                    csv(mlWouldDecide ? "ENTER" : "SKIP"),
                    csv(agree),
                    csv(minScoreThreshold.intValue()),
                    csv(""), csv(""), csv(""),  // trade outcome — filled later
                    csv(""), csv("")             // right/wrong — filled later
            ) + System.lineSeparator();

            appendRow(row);

            // Queue for outcome enrichment if system actually entered
            if (systemDecision) {
                pendingOutcomes.add(new PendingRow(decisionKey, decision.timestamp(),
                        systemDecision, mlWouldDecide));
            }

            // ML says ENTER (at virtual trade threshold) but system says SKIP → open a virtual trade
            if (mlWouldEnterVirtual && !systemDecision) {
                String instrumentKey = decision.selectedInstrumentKey().orElse(null);
                BigDecimal premium = decision.optionPrice().orElse(null);
                if (instrumentKey != null && premium != null && premium.signum() > 0) {
                    String strategyType = csvRowData.getOrDefault("strategyType", "DIRECTIONAL_BUY");
                    virtualTradeTracker.openVirtualTrade(decision, instrumentKey, premium,
                            systemScore, mlScore, mlProbability, strategyType);
                }
            }

            log.debug("ML shadow: system={} ml={} prob={} agree={} signal={}",
                    systemScore, mlScore, String.format("%.2f", mlProbability),
                    agree, decision.signalType());

        } catch (Exception e) {
            log.debug("ML shadow recording failed: {}", e.getMessage());
        }
    }

    /**
     * Enrich pending rows with actual trade outcomes.
     * Runs every 5 minutes to check if trades have closed.
     */
    @Scheduled(fixedDelay = 300_000, initialDelay = 60_000)
    public void enrichOutcomes() {
        if (pendingOutcomes.isEmpty()) return;

        List<PendingRow> toProcess = new ArrayList<>();
        PendingRow row;
        while ((row = pendingOutcomes.poll()) != null) {
            toProcess.add(row);
        }

        int enriched = 0;
        for (PendingRow pending : toProcess) {
            // Check if there's a closed trade for this decision
            Optional<TradeEntity> trade = findTradeForDecision(pending.decisionKey, pending.timestamp);
            if (trade.isPresent() && trade.get().getStatus() == TradeStatus.CLOSED) {
                TradeEntity t = trade.get();
                boolean profitable = t.getRealizedPnl() != null && t.getRealizedPnl().signum() > 0;
                updateOutcome(pending.decisionKey, profitable, t.getRealizedPnl(), t.getExitReason(),
                        pending.systemEntered, pending.mlWouldEnter);
                enriched++;
            } else {
                // Not closed yet — re-queue (but only if less than 24h old)
                if (pending.timestamp.isAfter(Instant.now().minusSeconds(86400))) {
                    pendingOutcomes.add(pending);
                }
            }
        }

        if (enriched > 0) {
            log.info("ML shadow: enriched {} rows with trade outcomes", enriched);
        }
    }

    /**
     * Get summary statistics for the UI.
     */
    public MlShadowSummary getSummary(String period) {
        if (!Files.exists(SHADOW_CSV)) {
            return MlShadowSummary.empty(scorer.status());
        }

        try (BufferedReader reader = Files.newBufferedReader(SHADOW_CSV)) {
            String headerLine = reader.readLine();
            if (headerLine == null) return MlShadowSummary.empty(scorer.status());

            String[] headers = headerLine.split(",");
            Map<String, Integer> idx = new HashMap<>();
            for (int i = 0; i < headers.length; i++) idx.put(headers[i].trim(), i);

            int total = 0, agree = 0, disagree = 0;
            int systemEnter = 0, mlEnter = 0;
            int bothEnter = 0, mlEnterSystemSkip = 0, systemEnterMlSkip = 0, bothSkip = 0;
            int systemRight = 0, mlRight = 0;
            int outcomeKnown = 0;
            List<Map<String, Object>> recentRows = new ArrayList<>();

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

                total++;
                boolean agreed = "true".equalsIgnoreCase(safeGet(vals, idx.getOrDefault("decisionsAgree", -1)));
                if (agreed) agree++; else disagree++;

                if ("ENTER".equals(safeGet(vals, idx.getOrDefault("systemDecision", -1)))) systemEnter++;
                if ("ENTER".equals(safeGet(vals, idx.getOrDefault("mlWouldDecide", -1)))) mlEnter++;

                boolean sysEnter = "ENTER".equals(safeGet(vals, idx.getOrDefault("systemDecision", -1)));
                boolean mlWouldEnterVal = "ENTER".equals(safeGet(vals, idx.getOrDefault("mlWouldDecide", -1)));
                if (sysEnter && mlWouldEnterVal) bothEnter++;
                else if (!sysEnter && mlWouldEnterVal) mlEnterSystemSkip++;
                else if (sysEnter && !mlWouldEnterVal) systemEnterMlSkip++;
                else bothSkip++;

                String sysRight = safeGet(vals, idx.getOrDefault("systemWasRight", -1));
                String mlWouldRight = safeGet(vals, idx.getOrDefault("mlWouldHaveBeenRight", -1));
                if (!sysRight.isEmpty()) {
                    outcomeKnown++;
                    if ("true".equalsIgnoreCase(sysRight)) systemRight++;
                    if ("true".equalsIgnoreCase(mlWouldRight)) mlRight++;
                }

                // Keep ALL disagreement rows + last 200 agreement rows for the table
                Map<String, Object> rowMap = new LinkedHashMap<>();
                for (Map.Entry<String, Integer> e : idx.entrySet()) {
                    rowMap.put(e.getKey(), safeGet(vals, e.getValue()));
                }
                if (!agreed) {
                    recentRows.add(rowMap); // always keep disagreements
                } else if (recentRows.size() < 500) {
                    recentRows.add(rowMap);
                }
            }

            // Reverse so newest first, keep up to 500
            Collections.reverse(recentRows);
            if (recentRows.size() > 500) recentRows = recentRows.subList(0, 500);

            return new MlShadowSummary(
                    total, agree, disagree,
                    systemEnter, mlEnter,
                    bothEnter, mlEnterSystemSkip, systemEnterMlSkip, bothSkip,
                    outcomeKnown, systemRight, mlRight,
                    outcomeKnown > 0 ? (double) systemRight / outcomeKnown * 100 : 0,
                    outcomeKnown > 0 ? (double) mlRight / outcomeKnown * 100 : 0,
                    scorer.status(),
                    recentRows
            );
        } catch (IOException e) {
            log.warn("Failed to read ML shadow CSV: {}", e.getMessage());
            return MlShadowSummary.empty(scorer.status());
        }
    }

    // ── Internals ───────────────────────────────────────────────────────────

    private void appendRow(String row) {
        try {
            Files.createDirectories(OUTPUT_DIR);
            if (!Files.exists(SHADOW_CSV)) {
                Files.writeString(SHADOW_CSV, HEADER, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            }
            Files.writeString(SHADOW_CSV, row, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("ML shadow CSV write failed: {}", e.getMessage());
        }
    }

    private void updateOutcome(String decisionKey, boolean profitable, BigDecimal pnl,
                                String exitReason, boolean systemEntered, boolean mlWouldEnter) {
        // Read the file, find the row, update it
        try {
            if (!Files.exists(SHADOW_CSV)) return;
            List<String> lines = Files.readAllLines(SHADOW_CSV);
            boolean updated = false;
            for (int i = 1; i < lines.size(); i++) {
                if (lines.get(i).contains(decisionKey)) {
                    String[] parts = parseCsvLine(lines.get(i));
                    if (parts.length >= 21) {
                        String outcome = profitable ? "PROFIT" : "LOSS";
                        // mlWouldHaveBeenRight: if ML said ENTER and trade was profitable, or ML said SKIP and trade was loss
                        boolean mlRight = (mlWouldEnter && profitable) || (!mlWouldEnter && !profitable);
                        boolean sysRight = (systemEntered && profitable) || (!systemEntered && !profitable);

                        parts[16] = outcome;
                        parts[17] = pnl != null ? pnl.toPlainString() : "";
                        parts[18] = exitReason != null ? exitReason : "";
                        parts[19] = String.valueOf(mlRight);
                        parts[20] = String.valueOf(sysRight);
                        lines.set(i, String.join(",", parts));
                        updated = true;
                        break;
                    }
                }
            }
            if (updated) {
                Files.writeString(SHADOW_CSV, String.join(System.lineSeparator(), lines) + System.lineSeparator());
            }
        } catch (IOException e) {
            log.debug("ML shadow outcome update failed: {}", e.getMessage());
        }
    }

    private Optional<TradeEntity> findTradeForDecision(String decisionKey, Instant timestamp) {
        try {
            // Find trades opened around the same time as the decision
            Instant from = timestamp.minusSeconds(30);
            Instant to = timestamp.plusSeconds(60);
            return tradeRepository.findByStatus(TradeStatus.CLOSED).stream()
                    .filter(t -> t.getEntryTime() != null
                            && t.getEntryTime().isAfter(from)
                            && t.getEntryTime().isBefore(to))
                    .findFirst();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private GradientBoostedTreeModel getModel() {
        return scorer.getModel();
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

    record PendingRow(String decisionKey, Instant timestamp, boolean systemEntered, boolean mlWouldEnter) {}

    public record MlShadowSummary(
            int totalSignals,
            int decisionsAgree,
            int decisionsDisagree,
            int systemEntries,
            int mlWouldEnter,
            int bothEnter,
            int mlEnterSystemSkip,
            int systemEnterMlSkip,
            int bothSkip,
            int outcomesKnown,
            int systemCorrect,
            int mlCorrect,
            double systemAccuracyPercent,
            double mlAccuracyPercent,
            MlSignalScorer.MlScorerStatus modelStatus,
            List<Map<String, Object>> recentSignals
    ) {
        static MlShadowSummary empty(MlSignalScorer.MlScorerStatus status) {
            return new MlShadowSummary(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, status, List.of());
        }
    }
}
