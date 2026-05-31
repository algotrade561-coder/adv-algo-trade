package com.algo.trade.tuning.store;

import com.algo.trade.tuning.TuningEventType;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Retention + rollup defaults for {@link ParquetRollerService}.
 *
 * <pre>
 * tuning:
 *   roller:
 *     enabled: true
 *     archive-base-dir: reports/tuning/archive
 *     summary-base-dir: reports/tuning/summary
 *     delete-csv-after-roll: true
 *     retention-days:
 *       EVALUATION: 90
 *       SIGNAL: 365
 *       EXECUTION: 365
 *       EXIT: 365
 *       FORWARD_CHECKPOINT: 90
 *       SHADOW_GATE: 30
 * </pre>
 *
 * <p>Defaults match the locked decisions in
 * {@code important/SIGNAL_CAPTURE_TUNING_REDESIGN.md} § 12.2.</p>
 */
@ConfigurationProperties(prefix = "tuning.roller")
public class ParquetRollerProperties {

    private boolean enabled = true;
    private String archiveBaseDir = "reports/tuning/archive";
    private String summaryBaseDir = "reports/tuning/summary";
    private boolean deleteCsvAfterRoll = true;

    /** Per-event-type retention in days. Older Parquet files get deleted. */
    private Map<TuningEventType, Integer> retentionDays = defaultRetention();

    private static EnumMap<TuningEventType, Integer> defaultRetention() {
        EnumMap<TuningEventType, Integer> m = new EnumMap<>(TuningEventType.class);
        m.put(TuningEventType.EVALUATION, 90);
        m.put(TuningEventType.SIGNAL, 365);
        m.put(TuningEventType.EXECUTION, 365);
        m.put(TuningEventType.EXIT, 365);
        m.put(TuningEventType.FORWARD_CHECKPOINT, 90);
        m.put(TuningEventType.SHADOW_GATE, 30);
        return m;
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { this.enabled = v; }

    public String getArchiveBaseDir() { return archiveBaseDir; }
    public void setArchiveBaseDir(String v) { this.archiveBaseDir = v; }

    public String getSummaryBaseDir() { return summaryBaseDir; }
    public void setSummaryBaseDir(String v) { this.summaryBaseDir = v; }

    public boolean isDeleteCsvAfterRoll() { return deleteCsvAfterRoll; }
    public void setDeleteCsvAfterRoll(boolean v) { this.deleteCsvAfterRoll = v; }

    public Map<TuningEventType, Integer> getRetentionDays() { return retentionDays; }
    public void setRetentionDays(Map<TuningEventType, Integer> v) {
        // Merge with defaults so a partial YAML map doesn't blow away the others.
        EnumMap<TuningEventType, Integer> merged = defaultRetention();
        if (v != null) merged.putAll(v);
        this.retentionDays = merged;
    }

    public int retentionDaysFor(TuningEventType type) {
        return retentionDays.getOrDefault(type, 90);
    }
}
