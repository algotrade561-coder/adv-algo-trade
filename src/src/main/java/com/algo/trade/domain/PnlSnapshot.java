package com.algo.trade.domain;

import com.algo.trade.util.Validation;
import java.math.BigDecimal;
import java.time.Instant;

public record PnlSnapshot(
        Instant timestamp,
        BigDecimal realizedPnl,
        BigDecimal unrealizedPnl,
        BigDecimal totalPnl
) {
    public PnlSnapshot {
        Validation.notNull(timestamp, "timestamp");
        Validation.notNull(realizedPnl, "realizedPnl");
        Validation.notNull(unrealizedPnl, "unrealizedPnl");
        Validation.notNull(totalPnl, "totalPnl");
    }
}
