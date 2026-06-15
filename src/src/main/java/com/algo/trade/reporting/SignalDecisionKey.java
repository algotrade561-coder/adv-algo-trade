package com.algo.trade.reporting;

import com.algo.trade.domain.StrategyDecision;

/**
 * Stable decision key shared by signal CSV, execution outcomes, and OI tuning files.
 */
public final class SignalDecisionKey {

    private SignalDecisionKey() {
    }

    public static String from(StrategyDecision decision) {
        if (decision == null) {
            return "";
        }
        String optType = decision.optionType().map(Enum::name).orElse("");
        String instKey = decision.selectedInstrumentKey().orElse("");
        String raw = decision.timestamp() + "|" + decision.underlying() + "|" + optType + "|" + instKey;
        return Integer.toUnsignedString(raw.hashCode(), 16);
    }
}
