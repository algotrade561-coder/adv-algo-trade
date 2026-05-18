package com.algo.trade.execution.exit;

import com.algo.trade.domain.OrderSide;
import com.algo.trade.domain.PositionGroup;
import com.algo.trade.domain.SpreadLeg;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.List;
import java.util.Map;

/**
 * Shared exit predicates for credit (short-premium) and debit spread groups.
 */
public final class SpreadPremiumExitHelper {

    private static final MathContext MC = MathContext.DECIMAL64;

    private SpreadPremiumExitHelper() {}

    public static boolean anyShortLegDoubled(PositionGroup group, Map<String, BigDecimal> currentPrices) {
        for (SpreadLeg leg : group.legs()) {
            if (leg.side() != OrderSide.SELL) {
                continue;
            }
            BigDecimal entry = group.entryPrices().getOrDefault(leg.instrumentKey(), BigDecimal.ZERO);
            BigDecimal current = currentPrices.getOrDefault(leg.instrumentKey(), BigDecimal.ZERO);
            if (entry.signum() > 0 && current.compareTo(entry.multiply(BigDecimal.valueOf(2), MC)) >= 0) {
                return true;
            }
        }
        return false;
    }

    public static BigDecimal netCredit(List<SpreadLeg> legs, Map<String, BigDecimal> prices) {
        return netDebit(legs, prices).negate();
    }

    public static BigDecimal netDebit(List<SpreadLeg> legs, Map<String, BigDecimal> prices) {
        BigDecimal buy = BigDecimal.ZERO;
        BigDecimal sell = BigDecimal.ZERO;
        for (SpreadLeg leg : legs) {
            BigDecimal px = prices.getOrDefault(leg.instrumentKey(), BigDecimal.ZERO);
            BigDecimal legVal = px.multiply(BigDecimal.valueOf(leg.quantity()), MC);
            if (leg.side() == OrderSide.SELL) {
                sell = sell.add(legVal, MC);
            } else {
                buy = buy.add(legVal, MC);
            }
        }
        return buy.subtract(sell, MC);
    }

    /** Credit decay % (profit for seller): (entryCredit - currentCredit) / entryCredit × 100. */
    public static double creditDecayPercent(BigDecimal entryCredit, BigDecimal currentCredit) {
        if (entryCredit == null || entryCredit.signum() <= 0) {
            return 0;
        }
        return entryCredit.subtract(currentCredit, MC)
                .divide(entryCredit, MC)
                .multiply(BigDecimal.valueOf(100), MC)
                .doubleValue();
    }

    /** Loss % on initial credit: (entryCredit - currentCredit) / entryCredit × 100 when losing. */
    public static double creditLossPercent(BigDecimal entryCredit, BigDecimal currentCredit) {
        return creditDecayPercent(entryCredit, currentCredit);
    }

    public static boolean creditTargetHit(double decayPercent, double targetPercent) {
        return decayPercent >= targetPercent;
    }

    /** Adverse move for short premium: current credit above entry (decay negative). */
    public static double creditAdverseLossPercent(BigDecimal entryCredit, BigDecimal currentCredit) {
        double decay = creditDecayPercent(entryCredit, currentCredit);
        return decay < 0 ? -decay : 0;
    }

    /** {@code decayPercent} from {@link #creditDecayPercent}; SL when adverse widening exceeds {@code slPercent}. */
    public static boolean creditStopLossHit(double decayPercent, double slPercent) {
        return slPercent > 0 && decayPercent < 0 && (-decayPercent) >= slPercent;
    }

    public static boolean creditStopLossHit(BigDecimal entryCredit, BigDecimal currentCredit, double slPercent) {
        return creditAdverseLossPercent(entryCredit, currentCredit) >= slPercent;
    }

    public static boolean debitStopLossHit(BigDecimal entryNet, BigDecimal currentNet, BigDecimal slPercent) {
        if (entryNet.signum() == 0) {
            return false;
        }
        BigDecimal lossPct = entryNet.subtract(currentNet, MC)
                .divide(entryNet.abs(), MC)
                .multiply(BigDecimal.valueOf(100), MC);
        return lossPct.compareTo(slPercent) >= 0;
    }

    public static boolean debitTargetHit(BigDecimal entryNet, BigDecimal currentNet, BigDecimal targetPercent) {
        if (entryNet.signum() == 0) {
            return false;
        }
        BigDecimal profitPct = currentNet.subtract(entryNet, MC)
                .divide(entryNet.abs(), MC)
                .multiply(BigDecimal.valueOf(100), MC);
        return profitPct.compareTo(targetPercent) >= 0;
    }
}
