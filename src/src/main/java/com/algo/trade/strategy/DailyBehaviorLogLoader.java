package com.algo.trade.strategy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.*;

/**
 * Loads the daily behaviour log from data/daily-behavior-log.yml and provides
 * historical operator-pattern statistics to the bias engine and orchestrator.
 *
 * <p>This dataset encodes observed NIFTY/BANKNIFTY/SENSEX daily behaviours
 * (March–June 2026) so the bot can self-tune based on historical patterns:</p>
 * <ul>
 *   <li>Expiry pinning frequency and confidence</li>
 *   <li>Sensex expiry reversal pattern success rate</li>
 *   <li>Crash halt trigger accuracy</li>
 *   <li>PCR unwind reversal occurrences</li>
 *   <li>Option-leads-index signal reliability</li>
 * </ul>
 *
 * <p>The tuning rules section provides statistical backing for the
 * ExpiryBehaviorTuner, PcrMomentumReversalStrategy, and OptionLeadsIndexDetector
 * thresholds.</p>
 */
@Component
public class DailyBehaviorLogLoader {

    private static final Logger log = LoggerFactory.getLogger(DailyBehaviorLogLoader.class);

    private static final List<String> MONTHS = List.of("march", "april", "may", "june",
            "july", "august", "september", "october", "november", "december");

    @Value("${trading.behavior-log.path:./data/daily-behavior-log.yml}")
    private String logFilePath;

    private volatile Map<String, TuningRule> tuningRules = Map.of();
    private volatile int totalEntries = 0;
    private volatile boolean loaded = false;

    @jakarta.annotation.PostConstruct
    public void load() {
        File file = new File(logFilePath);
        if (!file.exists()) {
            log.info("[BehaviorLog] No daily-behavior-log.yml found at {} — using default thresholds.", logFilePath);
            return;
        }

        try (InputStream in = new FileInputStream(file)) {
            Yaml yaml = new Yaml();
            Object loadedRoot = yaml.load(in);
            Map<String, Object> root;
            if (loadedRoot instanceof Map<?, ?> rootMap) {
                @SuppressWarnings("unchecked")
                Map<String, Object> rm = (Map<String, Object>) rootMap;
                root = rm;
            } else {
                // Defensive (2026-06-15): a legacy text-append bug could leave the curated
                // file with a sequence at the root — SnakeYAML then returns a List, and the
                // old hard cast threw "ArrayList cannot be cast to Map", silently disabling
                // all tuning rules. Now we degrade gracefully to the auto-log + defaults.
                if (loadedRoot != null) {
                    log.warn("[BehaviorLog] {} root is a {} not a mapping — ignoring curated sections "
                            + "(file likely corrupted by legacy append). Auto-log + defaults still apply.",
                            logFilePath, loadedRoot.getClass().getSimpleName());
                }
                root = new LinkedHashMap<>();
            }

            // Append-safe auto-records live in a sibling sequence file (see DailyBehaviorRecorder),
            // kept separate so neither file's structure is ever corrupted.
            List<?> autoEntries = loadAutoLog();

            // Count entries across all months + auto-records
            int count = 0;
            for (String month : MONTHS) {
                Object section = root.get(month);
                if (section instanceof List<?> list) {
                    count += list.size();
                }
            }
            count += autoEntries.size();
            totalEntries = count;

            // Load tuning rules
            Object rulesObj = root.get("tuningRules");
            if (rulesObj instanceof Map<?, ?> rulesMap) {
                Map<String, TuningRule> rules = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : rulesMap.entrySet()) {
                    String name = String.valueOf(entry.getKey());
                    if (entry.getValue() instanceof Map<?, ?> ruleMapRaw) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> ruleMap = (Map<String, Object>) ruleMapRaw;
                        String description = String.valueOf(ruleMap.getOrDefault("description", ""));
                        int occurrences = toInt(ruleMap.get("occurrences"));
                        int confidence = toInt(ruleMap.get("confidence"));
                        String botAction = String.valueOf(ruleMap.getOrDefault("botAction", ""));
                        String trigger = String.valueOf(ruleMap.getOrDefault("triggerCondition", ""));
                        rules.put(name, new TuningRule(name, description, occurrences, confidence, botAction, trigger));
                    }
                }
                tuningRules = Map.copyOf(rules);
            }

            // Recompute pattern frequencies from actual daily entries
            // This makes the confidence scores shift as new days are added
            recomputeFromEntries(root, autoEntries);

            loaded = true;
            log.info("[BehaviorLog] Loaded daily behaviour log: {} entries, {} tuning rules from {}",
                    totalEntries, tuningRules.size(), logFilePath);

        } catch (Exception e) {
            log.warn("[BehaviorLog] Failed to load {}: {}", logFilePath, e.getMessage());
        }
    }

    // ── Public API ────────────────────────────────────────────────────────

    /** Is the log loaded and available? */
    public boolean isLoaded() { return loaded; }

    /** Get total daily entries in the dataset. */
    public int getTotalEntries() { return totalEntries; }

    /** Get a specific tuning rule by name. */
    public Optional<TuningRule> getRule(String name) {
        return Optional.ofNullable(tuningRules.get(name));
    }

    /** Get all tuning rules. */
    public Map<String, TuningRule> getAllRules() { return tuningRules; }

    /**
     * Get the historical confidence for a specific pattern.
     * Used by strategies to validate their own threshold settings.
     */
    public int getPatternConfidence(String patternName) {
        TuningRule rule = tuningRules.get(patternName);
        return rule != null ? rule.confidence : 0;
    }

    /**
     * Get the number of times a pattern was observed in the dataset.
     */
    public int getPatternOccurrences(String patternName) {
        TuningRule rule = tuningRules.get(patternName);
        return rule != null ? rule.occurrences : 0;
    }

    /** Status for diagnostics API. */
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("loaded", loaded);
        status.put("totalEntries", totalEntries);
        status.put("tuningRules", tuningRules.size());
        status.put("rules", tuningRules);
        return status;
    }

    // ── Internal ──────────────────────────────────────────────────────────

    /**
     * Recompute pattern occurrences by scanning actual daily entries.
     * As new days are appended by DailyBehaviorRecorder, the confidence shifts.
     *
     * Patterns detected from the 'behaviour' and 'operatorSignals' fields:
     *   - "expiry pinning" / "pinning" → expiryPinning
     *   - "reversal" + sensex → sensexExpiryReversal
     *   - "decline" + >1.5% → crashHalt
     *   - "rally" + >1.0% → momentumMode
     *   - "PCR unwind" / "PCR drop" → pcrUnwindReversal
     *   - "option breakout" / "option-led" → optionLeadsIndex
     *   - "cross-index" → crossIndexAlignment
     */
    @SuppressWarnings("unchecked")
    private void recomputeFromEntries(Map<String, Object> root, List<?> autoEntries) {
        int pinningCount = 0, reversalCount = 0, crashCount = 0;
        int momentumCount = 0, pcrUnwindCount = 0, optionLeadCount = 0, crossIndexCount = 0;

        // Merge curated month sections + append-safe auto-records into one entry stream.
        List<Object> allEntries = new ArrayList<>();
        for (String month : MONTHS) {
            Object section = root.get(month);
            if (section instanceof List<?> entries) allEntries.addAll(entries);
        }
        if (autoEntries != null) allEntries.addAll(autoEntries);

        {
            for (Object entryObj : allEntries) {
                if (!(entryObj instanceof Map<?, ?> entryRaw)) continue;
                Map<String, Object> entry = (Map<String, Object>) entryRaw;

                String behaviour = String.valueOf(entry.getOrDefault("behaviour", "")).toLowerCase();
                String signals = String.valueOf(entry.getOrDefault("operatorSignals", "")).toLowerCase();
                double changePct = toDouble(entry.get("changePct"));
                String index = String.valueOf(entry.getOrDefault("index", ""));

                // Classify patterns from entry text
                if (behaviour.contains("pinning")) pinningCount++;
                if (behaviour.contains("reversal") && index.equalsIgnoreCase("SENSEX")) reversalCount++;
                if (behaviour.contains("decline") && changePct <= -1.5) crashCount++;
                if ((behaviour.contains("rally") || behaviour.contains("momentum")) && changePct >= 1.0) momentumCount++;
                if (signals.contains("pcr") && (signals.contains("unwind") || signals.contains("drop"))) pcrUnwindCount++;
                if (signals.contains("option") && (signals.contains("breakout") || signals.contains("led"))) optionLeadCount++;
                if (signals.contains("cross-index") || signals.contains("cross index")) crossIndexCount++;
            }
        }

        // Auto-recorded entries are now included above via the sibling autolog sequence file.

        // Recompute confidence: confidence = min(95, 60 + occurrences * 4)
        // More occurrences → higher confidence, capped at 95
        Map<String, TuningRule> updated = new LinkedHashMap<>(tuningRules);
        updateRule(updated, "expiryPinning", pinningCount);
        updateRule(updated, "sensexExpiryReversal", reversalCount);
        updateRule(updated, "crashHalt", crashCount);
        updateRule(updated, "momentumMode", momentumCount);
        updateRule(updated, "pcrUnwindReversal", pcrUnwindCount);
        updateRule(updated, "optionLeadsIndex", optionLeadCount);
        updateRule(updated, "crossIndexAlignment", crossIndexCount);
        tuningRules = Map.copyOf(updated);

        log.info("[BehaviorLog] Recomputed from entries: pinning={}, reversal={}, crash={}, momentum={}, pcrUnwind={}, optLead={}, crossIdx={}",
                pinningCount, reversalCount, crashCount, momentumCount, pcrUnwindCount, optionLeadCount, crossIndexCount);
    }

    private void updateRule(Map<String, TuningRule> map, String key, int newOccurrences) {
        TuningRule original = map.get(key);
        if (original != null && newOccurrences > 0) {
            map.put(key, withRecomputed(original, newOccurrences));
        }
    }

    private TuningRule withRecomputed(TuningRule original, int newOccurrences) {
        if (newOccurrences <= 0) return original;
        // Confidence formula: base 60 + 4 per occurrence, capped at 95
        // More data = higher confidence in the pattern
        int newConfidence = Math.min(95, 60 + newOccurrences * 4);
        return new TuningRule(original.name(), original.description(),
                newOccurrences, newConfidence, original.botAction(), original.triggerCondition());
    }

    /** Sibling append-safe sequence file holding auto-recorded daily entries. */
    private File autoLogFile() {
        File curated = new File(logFilePath);
        File dir = curated.getParentFile();
        return new File(dir != null ? dir : new File("."), "daily-behavior-autolog.yml");
    }

    /** Load auto-records (a root-level YAML sequence). Returns empty list if absent/unreadable. */
    private List<?> loadAutoLog() {
        File auto = autoLogFile();
        if (!auto.exists()) return List.of();
        try (InputStream in = new FileInputStream(auto)) {
            Object loaded = new Yaml().load(in);
            if (loaded instanceof List<?> list) return list;
            if (loaded instanceof Map<?, ?> single) return List.of(single); // tolerate a lone entry
            return List.of();
        } catch (Exception e) {
            log.warn("[BehaviorLog] Failed to read auto-log {}: {}", auto, e.getMessage());
            return List.of();
        }
    }

    private int toInt(Object obj) {
        if (obj instanceof Number n) return n.intValue();
        if (obj instanceof String s) { try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; } }
        return 0;
    }

    private double toDouble(Object obj) {
        if (obj instanceof Number n) return n.doubleValue();
        if (obj instanceof String s) { try { return Double.parseDouble(s); } catch (NumberFormatException e) { return 0; } }
        return 0;
    }

    // ── Record ────────────────────────────────────────────────────────────

    public record TuningRule(
            String name,
            String description,
            int occurrences,
            int confidence,
            String botAction,
            String triggerCondition
    ) {}
}
