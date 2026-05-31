package com.algo.trade.reporting;

/**
 * Normalizes strategy rejection reason text into a stable filter key for tuning capture.
 * Extracted from the former {@code SignalTuningCsvLoader} (Phase 6).
 */
public final class SignalTuningFilterUtils {

    private SignalTuningFilterUtils() {
    }

    public static String inferFailedFilter(String reasons) {
        if (reasons == null || reasons.isBlank()) {
            return "unknown";
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
        return "other";
    }
}
