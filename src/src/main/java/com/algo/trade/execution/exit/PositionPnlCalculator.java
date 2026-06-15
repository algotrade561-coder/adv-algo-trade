package com.algo.trade.execution.exit;

import com.algo.trade.persistence.TradeEntity;
import com.algo.trade.strategy.StrategyType;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Long/short-aware P&amp;L % for option positions (single-leg).
 */
public final class PositionPnlCalculator {

    private static final MathContext MC = MathContext.DECIMAL64;

    private PositionPnlCalculator() {}

    public static boolean isShortEntry(TradeEntity trade) {
        if (trade.getStrategyType() != null && !trade.getStrategyType().isBlank()) {
            if ("SHORT_POSITION".equals(trade.getStrategyType())) {
                return true;
            }
            try {
                return StrategyType.valueOf(trade.getStrategyType()).isSellingStrategy();
            } catch (IllegalArgumentException ignored) {
                // fall through
            }
        }
        String reason = trade.getEntryReason();
        return reason != null && (reason.contains("[SELL_CE]") || reason.contains("[SELL_PE]")
                || reason.contains("SELL_CE") || reason.contains("SELL_PE")
                || reason.contains("SHORT position"));
    }

    /**
     * Signed profit %: positive when the trade is winning.
     */
    public static double profitPercent(BigDecimal entry, BigDecimal current, boolean shortEntry) {
        if (entry == null || current == null || entry.signum() <= 0 || current.signum() <= 0) {
            return 0;
        }
        if (shortEntry) {
            return entry.subtract(current, MC)
                    .multiply(BigDecimal.valueOf(100), MC)
                    .divide(entry, MC)
                    .doubleValue();
        }
        return current.subtract(entry, MC)
                .multiply(BigDecimal.valueOf(100), MC)
                .divide(entry, MC)
                .doubleValue();
    }

    /** Updates peak price (best price seen for this direction). */
    public static BigDecimal updatePeak(BigDecimal current, BigDecimal existingPeak, boolean shortEntry) {
        if (existingPeak == null || existingPeak.signum() <= 0) {
            return current;
        }
        if (shortEntry) {
            return current.compareTo(existingPeak) < 0 ? current : existingPeak;
        }
        return current.compareTo(existingPeak) > 0 ? current : existingPeak;
    }

    public static BigDecimal scale(BigDecimal value, double fraction) {
        return value.multiply(BigDecimal.valueOf(fraction), MC)
                .setScale(0, RoundingMode.DOWN);
    }
}
