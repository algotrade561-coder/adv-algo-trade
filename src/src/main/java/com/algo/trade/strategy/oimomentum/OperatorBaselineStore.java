package com.algo.trade.strategy.oimomentum;

import com.algo.trade.domain.IndexType;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.Map;

/**
 * Persists the OperatorAccumulationDetector's opening baseline (09:15 strike OI snapshot)
 * to disk so that a JVM restart after 09:22 can recover it instead of capturing a
 * mid-day "Late opening baseline" that silently degrades the operator framework for the
 * rest of the session.
 *
 * <p>File layout: {base-dir}/YYYY-MM-DD.json — one file per trading day, all indices in
 * a single JSON object keyed by IndexType.
 */
@Component
public class OperatorBaselineStore {

    private static final Logger log = LoggerFactory.getLogger(OperatorBaselineStore.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final TypeReference<Map<IndexType, Map<Integer, OperatorAccumulationDetector.StrikeSnapshot>>> TYPE =
            new TypeReference<>() {};

    private final ObjectMapper objectMapper;
    private final Path baseDir;

    public OperatorBaselineStore(ObjectMapper objectMapper,
                                 @Value("${operator.baseline.base-dir:reports/state/operator-baselines}")
                                 String baseDirStr) {
        this.objectMapper = objectMapper;
        this.baseDir = Path.of(baseDirStr);
    }

    /** Save (or overwrite) the baseline for a single index on a given trading date. */
    public synchronized void save(IndexType index, LocalDate date,
                                  Map<Integer, OperatorAccumulationDetector.StrikeSnapshot> snapshot) {
        if (snapshot == null || snapshot.isEmpty()) {
            return;
        }
        try {
            Files.createDirectories(baseDir);
            Map<IndexType, Map<Integer, OperatorAccumulationDetector.StrikeSnapshot>> all = readAll(date);
            all.put(index, snapshot);
            Path file = fileFor(date);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), all);
            log.info("[OperatorBaseline] Saved {} baseline for {} ({} strikes) -> {}",
                    date, index, snapshot.size(), file);
        } catch (IOException e) {
            log.warn("[OperatorBaseline] Save failed for {} on {}: {}", index, date, e.getMessage());
        }
    }

    /** Load all per-index baselines for today (IST). Empty map if file missing/invalid. */
    public Map<IndexType, Map<Integer, OperatorAccumulationDetector.StrikeSnapshot>> loadToday() {
        return readAll(LocalDate.now(IST));
    }

    private Map<IndexType, Map<Integer, OperatorAccumulationDetector.StrikeSnapshot>> readAll(LocalDate date) {
        Path file = fileFor(date);
        if (Files.notExists(file)) {
            return new EnumMap<>(IndexType.class);
        }
        try {
            Map<IndexType, Map<Integer, OperatorAccumulationDetector.StrikeSnapshot>> loaded =
                    objectMapper.readValue(file.toFile(), TYPE);
            return loaded == null ? new EnumMap<>(IndexType.class) : new EnumMap<>(loaded);
        } catch (IOException e) {
            log.warn("[OperatorBaseline] Read failed for {}: {}", date, e.getMessage());
            return new EnumMap<>(IndexType.class);
        }
    }

    private Path fileFor(LocalDate date) {
        return baseDir.resolve(date.toString() + ".json");
    }
}
