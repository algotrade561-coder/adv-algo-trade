package com.algo.trade.strategy.oimomentum.v3;

import com.algo.trade.domain.OptionType;

import java.util.Set;

/**
 * V3 OPERATOR — Final entry decision from {@link V3EntryPipeline}.
 *
 * <p>Either an actionable entry (skip=false, strike + optionType + lots populated)
 * or a skip with a structured reason.</p>
 */
public record V3EntryDecision(
        boolean skip,
        String reason,
        int strike,
        OptionType optionType,
        int lots,
        OiSignal oiSignal,
        OperatorFilterGate.Verdict gateVerdict,
        TimeOfDayMode timeMode,
        Set<Regime> regimes,
        OiPattern pattern,
        double conviction
) {
    public static V3EntryDecision skip(String reason) {
        return new V3EntryDecision(true, reason, 0, null, 0, null, null, null, null, null, 0);
    }

    public static V3EntryDecision enter(int strike, OptionType ot, int lots,
                                          OiSignal signal, OperatorFilterGate.Verdict verdict,
                                          TimeOfDayMode mode, Set<Regime> regimes,
                                          OiPattern pattern, double conviction) {
        return new V3EntryDecision(false, "OK", strike, ot, lots,
                signal, verdict, mode, regimes, pattern, conviction);
    }
}
