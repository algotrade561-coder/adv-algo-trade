package com.algo.trade.domain;

import java.time.Duration;

public enum Timeframe {
    ONE_MINUTE(Duration.ofMinutes(1)),
    FIVE_MINUTE(Duration.ofMinutes(5)),
    FIFTEEN_MINUTE(Duration.ofMinutes(15)),
    ONE_HOUR(Duration.ofHours(1));

    private final Duration duration;

    Timeframe(Duration duration) {
        this.duration = duration;
    }

    public Duration duration() {
        return duration;
    }
}
