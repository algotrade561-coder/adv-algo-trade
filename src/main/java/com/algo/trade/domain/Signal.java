package com.algo.trade.domain;

import com.algo.trade.util.Validation;
import java.time.Instant;

public record Signal(
        Instant timestamp,
        UnderlyingSymbol underlying,
        SignalType signalType,
        String reason
) {
    public Signal {
        Validation.notNull(timestamp, "timestamp");
        Validation.notNull(underlying, "underlying");
        Validation.notNull(signalType, "signalType");
        reason = reason == null ? "" : reason;
    }
}
