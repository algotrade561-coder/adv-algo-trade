package com.algo.trade.tuning.capture;

/**
 * Lifecycle states for a tuning report job. The forked analyzer JVM transitions
 * jobs through these states; the trading JVM observes for UI display.
 */
public enum TuningReportJobStatus {

    /** Submitted via UI / REST; not yet picked up by the runner. */
    QUEUED,

    /** Forked analyzer JVM started; report generation in progress. */
    RUNNING,

    /** Report HTML written to disk; success. */
    COMPLETE,

    /** Analyzer exited non-zero or a section threw an uncaught exception. */
    FAILED,

    /** Parent process killed the forked JVM (e.g. exceeded total wall-clock cap). */
    KILLED
}
