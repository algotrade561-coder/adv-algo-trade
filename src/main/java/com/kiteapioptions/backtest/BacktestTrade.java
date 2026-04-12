package com.kiteapioptions.backtest;

import java.math.BigDecimal;
import java.time.Instant;

public record BacktestTrade(
        String tradeId,
        String instrumentKey,
        Instant entryTime,
        Instant exitTime,
        int quantity,
        BigDecimal entryPrice,
        BigDecimal exitPrice,
        BigDecimal pnl,
        String entryReason,
        String exitReason
) {
}
