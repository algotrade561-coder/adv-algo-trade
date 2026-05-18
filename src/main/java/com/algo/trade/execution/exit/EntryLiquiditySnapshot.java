package com.algo.trade.execution.exit;

import com.algo.trade.domain.Quote;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Liquidity baseline captured at entry for relative exit checks.
 */
public record EntryLiquiditySnapshot(
        double bidAskSpreadPercent,
        long volume,
        long openInterest,
        boolean hasBidAsk
) {
    private static final MathContext MC = MathContext.DECIMAL64;

    public static EntryLiquiditySnapshot fromQuote(Quote quote) {
        if (quote == null) {
            return empty();
        }
        BigDecimal bid = quote.bid().orElse(BigDecimal.ZERO);
        BigDecimal ask = quote.ask().orElse(BigDecimal.ZERO);
        boolean hasBidAsk = bid.signum() > 0 && ask.signum() > 0 && ask.compareTo(bid) >= 0;
        BigDecimal mid = hasBidAsk
                ? bid.add(ask, MC).divide(BigDecimal.valueOf(2), MC)
                : quote.lastPrice();
        double spreadPct = 0;
        if (hasBidAsk && mid.signum() > 0) {
            spreadPct = ask.subtract(bid, MC)
                    .divide(mid, MC)
                    .multiply(BigDecimal.valueOf(100), MC)
                    .doubleValue();
        }
        return new EntryLiquiditySnapshot(spreadPct, quote.volume(), quote.openInterest(), hasBidAsk);
    }

    public static EntryLiquiditySnapshot empty() {
        return new EntryLiquiditySnapshot(0, 0, 0, false);
    }

    public static double currentSpreadPercent(Quote quote) {
        return fromQuote(quote).bidAskSpreadPercent();
    }
}
