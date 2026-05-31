package com.algo.trade.tuning;

/**
 * Event type tag — primarily used by {@code TuningEventRecorder} (Phase 1, Commit 3)
 * to route writes to the correct per-type CSV file. The {@link #fileBaseName()} value
 * matches the on-disk file name (e.g. {@code EVALUATION} → {@code evaluation.csv.gz}).
 *
 * <p>Adding a new event type is intentionally a single point of change: extend this
 * enum, add a sealed-interface subtype in {@link TuningEvent}, and the recorder
 * routes the new type automatically.</p>
 */
public enum TuningEventType {

    EVALUATION("evaluation"),
    SIGNAL("signal"),
    EXECUTION("execution"),
    EXIT("exit"),
    FORWARD_CHECKPOINT("forward_checkpoint"),
    SHADOW_GATE("shadow_gate"),
    LEG("leg");

    private final String fileBaseName;

    TuningEventType(String fileBaseName) {
        this.fileBaseName = fileBaseName;
    }

    /** File base name (no extension) used by the recorder when laying out CSV files on disk. */
    public String fileBaseName() {
        return fileBaseName;
    }
}
