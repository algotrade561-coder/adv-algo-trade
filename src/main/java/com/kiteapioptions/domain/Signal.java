package com.kiteapioptions.domain;

import com.kiteapioptions.util.Validation;
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
