package com.kiteapioptions.domain;

import com.kiteapioptions.util.Validation;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

public record Trade(
        String tradeId,
        String instrumentKey,
        UnderlyingSymbol underlying,
        OptionType optionType,
        TradeStatus status,
        int quantity,
        BigDecimal entryPrice,
        Optional<BigDecimal> exitPrice,
        Instant entryTime,
        Optional<Instant> exitTime,
        Optional<BigDecimal> realizedPnl,
        String entryReason,
        String exitReason
) {
    public Trade {
        Validation.notBlank(tradeId, "tradeId");
        Validation.notBlank(instrumentKey, "instrumentKey");
        Validation.notNull(underlying, "underlying");
        Validation.notNull(optionType, "optionType");
        Validation.notNull(status, "status");
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        Validation.nonNegative(entryPrice, "entryPrice");
        exitPrice = exitPrice == null ? Optional.empty() : exitPrice;
        Validation.notNull(entryTime, "entryTime");
        exitTime = exitTime == null ? Optional.empty() : exitTime;
        realizedPnl = realizedPnl == null ? Optional.empty() : realizedPnl;
        entryReason = entryReason == null ? "" : entryReason;
        exitReason = exitReason == null ? "" : exitReason;
    }
}
