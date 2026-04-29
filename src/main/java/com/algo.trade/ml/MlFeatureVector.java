package com.algo.trade.ml;

import java.math.BigDecimal;
import java.time.LocalTime;

/**
 * Feature vector extracted from a strategy evaluation for ML scoring.
 * All features are numeric doubles for model consumption.
 */
public record MlFeatureVector(
        // Price & premium features
        double underlyingPrice,
        double optionLastPrice,
        double optionVolume,
        double optionOpenInterest,
        double optionImpliedVolatility,

        // Option chain features
        double nearbyPutCallOiImbalance,
        double nearbyCallOpenInterest,
        double nearbyPutOpenInterest,
        double resistanceCallOiChange,
        double supportPutOiChange,

        // Technical indicator features
        double ivRank,

        // Rule-based filter results (binary 0/1)
        double vwapPassed,
        double breakoutPassed,
        double volumeSpike,
        double oiPassed,
        double ivPassed,
        double liquidityPassed,
        double rsiPassed,

        // Context features
        double optionType,       // 0 = CE, 1 = PE
        double minutesSinceOpen, // minutes since 09:15
        double underlying,       // 0 = NIFTY, 1 = BANKNIFTY, 2 = SENSEX

        // Derived features
        double premiumToUnderlyingRatio,
        double oiImbalanceAbs,
        double ruleBasedScore,     // original rule-based confidence score

        // ML-enrichment features (new)
        double rsiValue,
        double atrValue,
        double ema9Ema21Gap,
        double bidAskSpread,
        double vixLevel,
        double daysToExpiry
) {

    /** Feature names matching the order of {@link #toArray()}. */
    public static final String[] FEATURE_NAMES = {
            "underlyingPrice", "optionLastPrice", "optionVolume", "optionOpenInterest",
            "optionImpliedVolatility", "nearbyPutCallOiImbalance", "nearbyCallOpenInterest",
            "nearbyPutOpenInterest", "resistanceCallOiChange", "supportPutOiChange",
            "ivRank", "vwapPassed", "breakoutPassed", "volumeSpike", "oiPassed",
            "ivPassed", "liquidityPassed", "rsiPassed", "optionType", "minutesSinceOpen",
            "underlying", "premiumToUnderlyingRatio", "oiImbalanceAbs", "ruleBasedScore",
            "rsiValue", "atrValue", "ema9Ema21Gap", "bidAskSpread", "vixLevel", "daysToExpiry"
    };

    public double[] toArray() {
        return new double[]{
                underlyingPrice, optionLastPrice, optionVolume, optionOpenInterest,
                optionImpliedVolatility, nearbyPutCallOiImbalance, nearbyCallOpenInterest,
                nearbyPutOpenInterest, resistanceCallOiChange, supportPutOiChange,
                ivRank, vwapPassed, breakoutPassed, volumeSpike, oiPassed,
                ivPassed, liquidityPassed, rsiPassed, optionType, minutesSinceOpen,
                underlying, premiumToUnderlyingRatio, oiImbalanceAbs, ruleBasedScore,
                rsiValue, atrValue, ema9Ema21Gap, bidAskSpread, vixLevel, daysToExpiry
        };
    }

    public static int featureCount() {
        return FEATURE_NAMES.length;
    }
}
