package com.algo.trade.tuning.capture;

/** Lifecycle states for a tuning report job. */
public enum TuningReportJobStatus {

    /** Submitted via UI / REST; not yet picked up by the @Async runner. */
    QUEUED,

    /** Analyzer task running; report generation in progress. */
    RUNNING,

    /** Report HTML written to disk; success. */
    COMPLETE,

    /** A section threw an uncaught exception. */
    FAILED,

    /** Job was forcibly cancelled (reserved for future operator override). */
    KILLED
}
