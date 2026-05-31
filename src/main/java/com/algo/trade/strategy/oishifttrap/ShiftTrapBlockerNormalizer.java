package com.algo.trade.strategy.oishifttrap;

/**
 * Stable tokens for eval episode grouping in the tuning analyzer.
 */
final class ShiftTrapBlockerNormalizer {

    private ShiftTrapBlockerNormalizer() {
    }

    static String normalize(String blocker) {
        if (blocker == null || blocker.isBlank()) {
            return "";
        }
        return switch (blocker) {
            case "MIN_OI" -> "gate:MIN_OI";
            case "MIN_OI_CHANGE" -> "gate:MIN_OI_CHANGE";
            case "IMBALANCE" -> "gate:IMBALANCE";
            case "SCORE" -> "gate:SCORE";
            default -> blocker;
        };
    }
}
