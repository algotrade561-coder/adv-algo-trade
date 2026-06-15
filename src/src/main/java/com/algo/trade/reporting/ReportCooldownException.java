package com.algo.trade.reporting;

import java.time.Duration;

/** Thrown when a second report is submitted before the cool-down elapses. */
public class ReportCooldownException extends RuntimeException {

    private final Duration remaining;

    public ReportCooldownException(Duration remaining) {
        super("Tuning report cool-down active — retry in " + remaining.toMinutes() + " minutes");
        this.remaining = remaining;
    }

    public Duration remaining() {
        return remaining;
    }
}
