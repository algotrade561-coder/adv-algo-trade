package com.algo.trade.risk;

import java.math.BigDecimal;

public record PositionSizingResult(
        boolean allowed,
        int quantity,
        BigDecimal riskAmount,
        BigDecimal estimatedCost,
        String reason
) {
}
