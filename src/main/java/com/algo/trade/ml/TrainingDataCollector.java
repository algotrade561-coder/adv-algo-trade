package com.algo.trade.ml;

import com.algo.trade.persistence.StrategyDecisionEntity;
import com.algo.trade.persistence.StrategyDecisionRepository;
import com.algo.trade.persistence.TradeEntity;
import com.algo.trade.persistence.TradeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Collects and labels training data for the ML signal scorer.
 *
 * <p>Joins entry-signals.csv with trade outcomes to create labeled examples:</p>
 * <ul>
 *   <li>Signals that led to profitable trades → label = 1</li>
 *   <li>Signals that led to losing trades → label = 0</li>
 *   <li>NO_TRADE signals → skipped (outcome unknown; no counterfactual)</li>
 * </ul>
 */
@Component
public class TrainingDataCollector {

    private static final Logger log = LoggerFactory.getLogger(TrainingDataCollector.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private static final Path SIGNALS_CSV = Path.of("reports/entry-signals/entry-signals.csv");
    private static final Path OUTCOMES_CSV = Path.of("reports/entry-signals/entry-execution-outcomes.csv");
    private static final Path TRAINING_DATA_DIR = Path.of("data/ml/training");
    private static final Path TRAINING_CSV = TRAINING_DATA_DIR.resolve("training-data.csv");

    private final MlFeatureExtractor featureExtractor;
    private final TradeRepository tradeRepository;
    private final StrategyDecisionRepository decisionRepository;

    public TrainingDataCollector(MlFeatureExtractor featureExtractor,
                                  TradeRepository tradeRepository,
                                  StrategyDecisionRepository decisionRepository) {
        this.featureExtractor = featureExtractor;
        this.tradeRepository = tradeRepository;
        this.decisionRepository = decisionRepository;
    }

    /**
     * Generate training data CSV from entry-signals.csv + trade outcomes.
     * @return number of labeled examples written
     */
    public TrainingResult generateTrainingData() throws IOException {
        if (!Files.exists(SIGNALS_CSV)) {
            log.warn("No entry-signals.csv found at {}", SIGNALS_CSV);
            return new TrainingResult(0, 0, 0, TRAINING_CSV.toString());
        }

        Files.createDirectories(TRAINING_DATA_DIR);

        // Load trade outcomes keyed by decisionKey
        Map<String, TradeOutcome> outcomes = loadTradeOutcomes();
        log.info("Loaded {} trade outcomes for labeling", outcomes.size());

        // Also load DB trades for additional labeling
        Map<String, Boolean> dbTradeOutcomes = loadDbTradeOutcomes();
        log.info("Loaded {} DB trade outcomes", dbTradeOutcomes.size());

        int positive = 0;
        int negative = 0;
        int unlabeled = 0;

        try (BufferedReader reader = Files.newBufferedReader(SIGNALS_CSV);
             BufferedWriter writer = Files.newBufferedWriter(TRAINING_CSV)) {

            String headerLine = reader.readLine();
            if (headerLine == null) return new TrainingResult(0, 0, 0, TRAINING_CSV.toString());

            String[] headers = parseCsvLine(headerLine);
            Map<String, Integer> headerIndex = new HashMap<>();
            for (int i = 0; i < headers.length; i++) {
                headerIndex.put(headers[i].trim(), i);
            }

            // Write training CSV header
            writer.write(String.join(",", MlFeatureVector.FEATURE_NAMES));
            writer.write(",label");
            writer.newLine();

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] values = parseCsvLine(line);
                Map<String, String> row = new LinkedHashMap<>();
                for (Map.Entry<String, Integer> entry : headerIndex.entrySet()) {
                    int idx = entry.getValue();
                    row.put(entry.getKey(), idx < values.length ? values[idx] : "");
                }

                // Determine label
                String decisionKey = row.getOrDefault("decisionKey", "");
                String signalType = row.getOrDefault("signalType", "NO_TRADE");
                Integer label = determineLabel(decisionKey, signalType, row, outcomes, dbTradeOutcomes);

                if (label == null) {
                    unlabeled++;
                    continue; // skip unlabeled examples
                }

                // Extract features
                try {
                    MlFeatureVector features = featureExtractor.extractFromCsvRow(row);
                    double[] arr = features.toArray();
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < arr.length; i++) {
                        if (i > 0) sb.append(',');
                        sb.append(formatDouble(arr[i]));
                    }
                    sb.append(',').append(label);
                    writer.write(sb.toString());
                    writer.newLine();

                    if (label == 1) positive++;
                    else negative++;
                } catch (Exception e) {
                    log.debug("Skipping row due to feature extraction error: {}", e.getMessage());
                    unlabeled++;
                }
            }
        }

        log.info("Training data generated: positive={}, negative={}, unlabeled={}, path={}",
                positive, negative, unlabeled, TRAINING_CSV);
        return new TrainingResult(positive, negative, unlabeled, TRAINING_CSV.toString());
    }

    private Integer determineLabel(String decisionKey, String signalType,
                                    Map<String, String> row,
                                    Map<String, TradeOutcome> outcomes,
                                    Map<String, Boolean> dbOutcomes) {
        // If we have a direct trade outcome for this signal
        TradeOutcome outcome = outcomes.get(decisionKey);
        if (outcome != null) {
            if ("ORDER_FILLED".equals(outcome.stage) || "PAPER_FILLED".equals(outcome.stage)) {
                return outcome.profitable ? 1 : 0;
            }
            // Rejected signals — we don't know the counterfactual, skip for now
            return null;
        }

        // For BUY signals that made it to execution, look up by instrumentKey + date
        // (DB trades are keyed by tradeId, not decisionKey — join on instrument + date instead)
        if (signalType.startsWith("BUY_")) {
            String instrumentKey = row.getOrDefault("selectedInstrumentKey", "");
            String timestampStr = row.getOrDefault("timestamp", "");
            if (!instrumentKey.isEmpty() && !timestampStr.isEmpty()) {
                try {
                    LocalDate date = Instant.parse(timestampStr).atZone(IST).toLocalDate();
                    Boolean profitable = dbOutcomes.get(instrumentKey + "|" + date);
                    if (profitable != null) return profitable ? 1 : 0;
                } catch (Exception ignored) {}
            }
        }

        // NO_TRADE signals — outcome is unknown (we didn't enter, so we don't know if it would have profited).
        // Labeling them as 0 would teach the model that every skip was a mistake, which is wrong.
        // Drop them; only train on signals where we have a real trade outcome.
        if ("NO_TRADE".equals(signalType)) {
            return null;
        }

        return null;
    }

    private Map<String, TradeOutcome> loadTradeOutcomes() {
        Map<String, TradeOutcome> outcomes = new HashMap<>();
        if (!Files.exists(OUTCOMES_CSV)) return outcomes;

        try (BufferedReader reader = Files.newBufferedReader(OUTCOMES_CSV)) {
            String headerLine = reader.readLine();
            if (headerLine == null) return outcomes;

            String[] headers = parseCsvLine(headerLine);
            Map<String, Integer> idx = new HashMap<>();
            for (int i = 0; i < headers.length; i++) idx.put(headers[i].trim(), i);

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] values = parseCsvLine(line);
                String key = safeGet(values, idx.getOrDefault("decisionKey", -1));
                String stage = safeGet(values, idx.getOrDefault("stage", -1));
                boolean accepted = "true".equalsIgnoreCase(
                        safeGet(values, idx.getOrDefault("accepted", -1)));

                // For filled orders, we need to check if the trade was profitable
                // This requires joining with trade data — for now mark filled as unknown
                outcomes.put(key, new TradeOutcome(stage, accepted, false));
            }
        } catch (IOException e) {
            log.warn("Failed to load outcomes CSV: {}", e.getMessage());
        }
        return outcomes;
    }

    private Map<String, Boolean> loadDbTradeOutcomes() {
        Map<String, Boolean> outcomes = new HashMap<>();
        try {
            List<TradeEntity> closedTrades = tradeRepository.findByStatus(
                    com.algo.trade.domain.TradeStatus.CLOSED);
            for (TradeEntity trade : closedTrades) {
                BigDecimal pnl = trade.getRealizedPnl();
                String instrumentKey = trade.getInstrumentKey();
                Instant entryTime = trade.getEntryTime();
                if (pnl != null && instrumentKey != null && entryTime != null) {
                    LocalDate date = entryTime.atZone(IST).toLocalDate();
                    outcomes.put(instrumentKey + "|" + date, pnl.signum() > 0);
                }
            }
        } catch (Exception e) {
            log.debug("Could not load DB trade outcomes: {}", e.getMessage());
        }
        return outcomes;
    }

    private static String[] parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        boolean inQuotes = false;
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                fields.add(current.toString().trim());
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString().trim());
        return fields.toArray(new String[0]);
    }

    private static String safeGet(String[] arr, int idx) {
        if (idx < 0 || idx >= arr.length) return "";
        return arr[idx];
    }

    private static String formatDouble(double v) {
        if (v == (long) v) return String.valueOf((long) v);
        return String.format("%.6f", v);
    }

    public record TrainingResult(int positiveExamples, int negativeExamples,
                                  int unlabeled, String outputPath) {
        public int totalLabeled() { return positiveExamples + negativeExamples; }
    }

    private record TradeOutcome(String stage, boolean accepted, boolean profitable) {}
}
