package com.algo.trade.reporting;

/**
 * Normalizes strategy rejection reason text into a stable filter key for tuning capture.
 * 2026-06-01 enhancements: AlgoFlow classification + per-strategy reason patterns
 * (Gap and Go, Scalping, Reversal Buy, Momentum, spreads) so the tuning report no
 * longer collapses 90%+ of rejections to "other".
 */
public final class SignalTuningFilterUtils {

    private SignalTuningFilterUtils() {
    }

    public static String inferFailedFilter(String reasons) {
        if (reasons == null || reasons.isBlank()) {
            return "unknown";
        }
        // AlgoFlow pre-evaluator block (top priority - checked first).
        if (reasons.contains("AlgoFlow:")) {
            return classifyAlgoFlow(reasons);
        }
        if (reasons.contains("Signal score failed")) {
            return "signalScore";
        }
        if (reasons.contains("RSI momentum gate failed")) {
            return "rsi";
        }
        if (reasons.contains("Entry time window failed") || reasons.contains("timeWindow")) {
            return "timeWindow";
        }
        if (reasons.contains("Trend condition failed")) {
            return "trend";
        }
        if (reasons.contains("Breakout condition failed")) {
            return "breakout";
        }
        if (reasons.contains("Breakout confirmation failed")) {
            return "breakoutConfirm";
        }
        if (reasons.contains("Volume spike missing")) {
            return "volumeSpike";
        }
        if (reasons.contains("OI behavior does not support")) {
            return "oi";
        }
        if (reasons.contains("Side-specific entry filter failed")) {
            return "sideFilter";
        }
        if (reasons.contains("Environment score")) {
            return "environmentScore";
        }
        if (reasons.contains("noEmaCross") || reasons.contains("insufficientCandles")) {
            return "scalpSetup";
        }
        if (reasons.contains("rocTooWeak")) {
            return "momentumRoc";
        }
        if (reasons.contains("ivRankTooHigh")) {
            return "ivRank";
        }
        // Gap and Go own gates.
        if (reasons.contains("notEnoughCandles") || reasons.contains("notEnoughSessionCandles")) {
            return "insufficientHistory";
        }
        if (reasons.contains("firstCandleNoTicks") || reasons.contains("zeroOpenPrice")) {
            return "noOpeningTick";
        }
        if (reasons.contains("noPrevClose")) {
            return "noPrevClose";
        }
        if (reasons.contains("bodyTooSmall") || reasons.contains("gapBodyConflict")) {
            return "weakCandle";
        }
        if (reasons.contains("secondCandleNoConfirm")) {
            return "noConfirmCandle";
        }
        if (reasons.contains("lowVolume")) {
            return "lowVolume";
        }
        // Scalping additional reasons.
        if (reasons.contains("emaGapTooSmall")) {
            return "emaGapTooSmall";
        }
        if (reasons.contains("priceNotConfirming")) {
            return "priceNotConfirming";
        }
        if (reasons.contains("needsConfirmation")) {
            return "needsConfirmation";
        }
        // Common Momentum / strategy-wide gates.
        if (reasons.contains("trendMisaligned") || reasons.contains("vwapMisaligned")) {
            return "trendMisaligned";
        }
        if (reasons.contains("rocDecelerating")) {
            return "rocDecelerating";
        }
        if (reasons.contains("scoreTooLow")) {
            return "scoreTooLow";
        }
        if (reasons.contains("entryCooldown")) {
            return "entryCooldown";
        }
        if (reasons.contains("atrTooLow")) {
            return "atrTooLow";
        }
        if (reasons.contains("zeroPriceInHistory")) {
            return "zeroPriceInHistory";
        }
        // Reversal Buy gates.
        if (reasons.contains("noVolumeExhaustion")) {
            return "noVolumeExhaustion";
        }
        if (reasons.contains("rsiNeutral")) {
            return "rsiNeutral";
        }
        // Spread / DTE gates.
        if (reasons.contains("dteTooLow") || reasons.contains("dteTooHigh")) {
            return "dteOutOfRange";
        }
        if (reasons.contains("spreadEntryBlocked")) {
            return "spreadEntryBlocked";
        }
        return "other";
    }

    /**
     * Classifies an AlgoFlow reason string into a stable subkey.
     */
    private static String classifyAlgoFlow(String reasons) {
        String r = reasons.toLowerCase();
        if (r.contains("midday chop") || r.contains("midday")) {
            return "algoFlow:midday";
        }
        if (r.contains("range-bound") || r.contains("rangebound") || r.contains("choppy")) {
            return "algoFlow:rangeBound";
        }
        if (r.contains("dte") || r.contains("expiry")) {
            return "algoFlow:dte";
        }
        if (r.contains("regime")) {
            return "algoFlow:regime";
        }
        if (r.contains("vix")) {
            return "algoFlow:vix";
        }
        if (r.contains("session") || r.contains("squareoff") || r.contains("window")) {
            return "algoFlow:session";
        }
        if (r.contains("liquidity") || r.contains("affordable")) {
            return "algoFlow:liquidity";
        }
        return "algoFlow:other";
    }
}
