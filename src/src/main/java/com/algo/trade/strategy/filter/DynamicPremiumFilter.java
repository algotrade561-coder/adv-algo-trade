package com.algo.trade.strategy.filter;

import com.algo.trade.domain.IndexType;
import com.algo.trade.domain.OptionInstrument;
import com.algo.trade.marketdata.ExpiryCalendar;
import com.algo.trade.marketdata.LiveInstrumentCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Dynamic Premium Filter — context-aware premium validation for option buying.
 *
 * Replaces static min/max premium checks with intelligent thresholds based on:
 * 1. Premium as % of underlying index value (relative cost)
 * 2. Expiry type (weekly vs monthly)
 * 3. Strategy context (scalp vs breakout vs BTST)
 * 4. Time of day (cheaper options acceptable near expiry close)
 */
@Component
public class DynamicPremiumFilter {

    private static final Logger log = LoggerFactory.getLogger(DynamicPremiumFilter.class);

    private final LiveInstrumentCache instrumentCache;
    private final ExpiryCalendar expiryCalendar;

    @Value("${premium-filter.max-percent-scalp:0.8}") private double maxPercentScalp;
    @Value("${premium-filter.max-percent-breakout:1.2}") private double maxPercentBreakout;
    @Value("${premium-filter.max-percent-btst:1.5}") private double maxPercentBtst;
    @Value("${premium-filter.min-premium-normal:20}") private double minPremiumNormal;
    @Value("${premium-filter.min-premium-expiry-scalp:3}") private double minPremiumExpiryScalp;
    @Value("${premium-filter.monthly-block-intraday:true}") private boolean blockMonthlyIntraday;

    public enum StrategyType { SCALP, BREAKOUT, REVERSAL, BTST, EXPIRY_SCALP }

    public record PremiumValidation(
        boolean acceptable,
        String reason,
        double premium,
        double premiumPercent,
        double maxAllowed,
        String expiryType
    ) {
        public static PremiumValidation accepted(double premium, double pct, double max, String expiry) {
            return new PremiumValidation(true, null, premium, pct, max, expiry);
        }
        public static PremiumValidation rejected(String reason, double premium, double pct, double max, String expiry) {
            return new PremiumValidation(false, reason, premium, pct, max, expiry);
        }
    }

    public DynamicPremiumFilter(LiveInstrumentCache instrumentCache, ExpiryCalendar expiryCalendar) {
        this.instrumentCache = instrumentCache;
        this.expiryCalendar = expiryCalendar;
    }

    public PremiumValidation validate(OptionInstrument instrument, IndexType indexType,
                                       StrategyType strategyType, boolean isBtst) {
        if (instrument == null) {
            return PremiumValidation.rejected("NULL_INSTRUMENT", 0, 0, 0, "UNKNOWN");
        }

        double premium = instrument.getLastPrice();
        if (premium <= 0) {
            return PremiumValidation.rejected("ZERO_PREMIUM", 0, 0, 0, "UNKNOWN");
        }

        double indexValue = instrumentCache.getFuturesPrice(indexType);
        if (indexValue <= 0) indexValue = getApproxIndexValue(indexType);

        double premiumPercent = indexValue > 0 ? (premium / indexValue) * 100 : 0;
        String expiryType = determineExpiryType(instrument, indexType);

        // Expiry filter: only trade contracts ≤5 days to expiry for buying
        if (strategyType != StrategyType.BTST) {
            long daysToExpiry = instrument.getExpiry() != null
                    ? ChronoUnit.DAYS.between(LocalDate.now(), instrument.getExpiry()) : 0;
            if (daysToExpiry > 5) {
                return PremiumValidation.rejected(
                        "EXPIRY_TOO_FAR: " + daysToExpiry + " days to expiry (max 5 for buying). Use weekly contracts.",
                        premium, premiumPercent, 0, expiryType);
            }
        }

        // Minimum premium check
        double minPremium = (strategyType == StrategyType.EXPIRY_SCALP) ? minPremiumExpiryScalp : minPremiumNormal;
        java.time.LocalTime timeNow = java.time.LocalTime.now();
        if (strategyType != StrategyType.EXPIRY_SCALP && timeNow.isAfter(java.time.LocalTime.of(14, 30))) {
            minPremium = Math.max(minPremium, 20);
        }

        if (premium < minPremium) {
            return PremiumValidation.rejected(
                    "TOO_CHEAP: ₹" + String.format("%.0f", premium) + " < min ₹" + String.format("%.0f", minPremium),
                    premium, premiumPercent, minPremium, expiryType);
        }

        // Maximum premium (relative to index)
        double maxPercent = switch (strategyType) {
            case SCALP -> maxPercentScalp;
            case EXPIRY_SCALP -> maxPercentBreakout;
            case BREAKOUT -> maxPercentBreakout;
            case REVERSAL -> maxPercentScalp;
            case BTST -> maxPercentBtst;
        };
        if (isBtst) maxPercent = maxPercentBtst;

        double maxPremium = indexValue > 0 ? indexValue * maxPercent / 100.0 : getAbsoluteMax(indexType, strategyType);

        if (premium > maxPremium) {
            return PremiumValidation.rejected(
                    String.format("TOO_EXPENSIVE: ₹%.0f > max ₹%.0f (%.2f%% of %s, limit %.2f%%)",
                            premium, maxPremium, premiumPercent, indexType, maxPercent),
                    premium, premiumPercent, maxPremium, expiryType);
        }

        // Absolute caps
        double absoluteMax = getAbsoluteMax(indexType, strategyType);
        if (premium > absoluteMax) {
            return PremiumValidation.rejected(
                    String.format("ABSOLUTE_CAP: ₹%.0f > hard cap ₹%.0f for %s %s",
                            premium, absoluteMax, indexType, strategyType),
                    premium, premiumPercent, absoluteMax, expiryType);
        }

        return PremiumValidation.accepted(premium, premiumPercent, maxPremium, expiryType);
    }

    public PremiumValidation validate(OptionInstrument instrument, IndexType indexType, String strategyName) {
        StrategyType type = inferStrategyType(strategyName);
        boolean isBtst = strategyName != null && strategyName.contains("BTST");
        return validate(instrument, indexType, type, isBtst);
    }

    public double getMaxPremium(IndexType indexType, StrategyType strategyType, boolean isBtst) {
        double indexValue = instrumentCache.getFuturesPrice(indexType);
        if (indexValue <= 0) return getAbsoluteMax(indexType, strategyType);

        double maxPercent = isBtst ? maxPercentBtst : switch (strategyType) {
            case SCALP -> maxPercentScalp;
            case EXPIRY_SCALP -> maxPercentBreakout;
            case BREAKOUT -> maxPercentBreakout;
            case REVERSAL -> maxPercentScalp;
            case BTST -> maxPercentBtst;
        };

        double relativeCap = indexValue * maxPercent / 100.0;
        double absoluteCap = getAbsoluteMax(indexType, strategyType);
        return Math.min(relativeCap, absoluteCap);
    }

    private String determineExpiryType(OptionInstrument instrument, IndexType indexType) {
        if (instrument.getExpiry() == null) return "WEEKLY";
        long daysToExpiry = ChronoUnit.DAYS.between(LocalDate.now(), instrument.getExpiry());
        return daysToExpiry <= 7 ? "WEEKLY" : "MONTHLY";
    }

    private double getAbsoluteMax(IndexType indexType, StrategyType strategyType) {
        return switch (indexType) {
            case NIFTY -> switch (strategyType) {
                case SCALP -> 200;
                case EXPIRY_SCALP -> 300;
                case BREAKOUT -> 250;
                case REVERSAL -> 200;
                case BTST -> 400;
            };
            case BANKNIFTY -> switch (strategyType) {
                case SCALP -> 300;
                case EXPIRY_SCALP -> 500;
                case BREAKOUT -> 400;
                case REVERSAL -> 300;
                case BTST -> 500;
            };
            case SENSEX -> switch (strategyType) {
                case SCALP -> 600;
                case EXPIRY_SCALP -> 800;
                case BREAKOUT -> 800;
                case REVERSAL -> 600;
                case BTST -> 900;
            };
            default -> 400;
        };
    }

    private double getApproxIndexValue(IndexType indexType) {
        return switch (indexType) {
            case NIFTY -> 24000;
            case BANKNIFTY -> 52000;
            case SENSEX -> 79000;
            default -> 24000;
        };
    }

    private StrategyType inferStrategyType(String strategyName) {
        if (strategyName == null) return StrategyType.BREAKOUT;
        String upper = strategyName.toUpperCase();
        if (upper.contains("SCALP") || upper.contains("FALLBACK")) return StrategyType.SCALP;
        if (upper.contains("EXPIRY_GAMMA")) return StrategyType.EXPIRY_SCALP;
        if (upper.contains("REVERSAL")) return StrategyType.REVERSAL;
        if (upper.contains("BTST") || upper.contains("EVENT")) return StrategyType.BTST;
        if (upper.contains("UNIVERSAL_OI") || upper.contains("OI_MOMENTUM")) {
            return isAnyExpiryDay() ? StrategyType.EXPIRY_SCALP : StrategyType.BREAKOUT;
        }
        return StrategyType.BREAKOUT;
    }

    private boolean isAnyExpiryDay() {
        try {
            for (IndexType idx : new IndexType[]{IndexType.NIFTY, IndexType.BANKNIFTY, IndexType.SENSEX}) {
                if (expiryCalendar.isExpiryDay(idx)) return true;
            }
        } catch (Exception e) { /* ignore */ }
        return false;
    }
}
