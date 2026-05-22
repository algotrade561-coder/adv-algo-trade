package com.algo.trade.reporting;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;

/**
 * Builds compact option-chain context from {@code option-chain-levels.csv} rows for one evaluation.
 */
final class SignalTuningChainSummarizer {

    private static final MathContext MC = MathContext.DECIMAL64;

    private SignalTuningChainSummarizer() {
    }

    static ChainSummary summarizeOi(SignalTuningCsvLoader.OiMomentumSignalRow signal,
                                      List<SignalTuningCsvLoader.ChainLevelRow> levels) {
        SignalTuningCsvLoader.SignalRow adapter = new SignalTuningCsvLoader.SignalRow(
                signal.decisionKey(),
                signal.timestamp(),
                "OI_MOMENTUM",
                signal.signalType(),
                signal.underlying(),
                signal.optionType(),
                signal.instrumentKey(),
                signal.premium(),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                "",
                "",
                false,
                false,
                false,
                signal.oiAvailable(),
                true,
                true,
                true,
                false,
                "");
        return summarize(adapter, levels);
    }

    static ChainSummary summarize(SignalTuningCsvLoader.SignalRow signal, List<SignalTuningCsvLoader.ChainLevelRow> levels) {
        if (levels == null || levels.isEmpty()) {
            return ChainSummary.missing();
        }
        BigDecimal spot = levels.stream()
                .map(SignalTuningCsvLoader.ChainLevelRow::spotPrice)
                .filter(p -> p != null && p.signum() > 0)
                .findFirst()
                .orElse(BigDecimal.ZERO);

        SignalTuningCsvLoader.ChainLevelRow atm = levels.stream()
                .filter(l -> l.strike() > 0 && spot.signum() > 0)
                .min(Comparator.comparingLong(l -> Math.abs(l.strike() - spot.longValue())))
                .orElse(levels.get(0));

        long totalCallOi = 0;
        long totalPutOi = 0;
        long maxCallChange = 0;
        long maxPutChange = 0;
        long maxCallChangeStrike = 0;
        long maxPutChangeStrike = 0;
        boolean anyOiDelta = false;

        for (SignalTuningCsvLoader.ChainLevelRow level : levels) {
            totalCallOi += Math.max(0, level.callOpenInterest());
            totalPutOi += Math.max(0, level.putOpenInterest());
            long cc = Math.abs(level.callOpenInterestChange());
            long pc = Math.abs(level.putOpenInterestChange());
            if (cc > 0 || pc > 0) {
                anyOiDelta = true;
            }
            if (cc > maxCallChange) {
                maxCallChange = cc;
                maxCallChangeStrike = level.strike();
            }
            if (pc > maxPutChange) {
                maxPutChange = pc;
                maxPutChangeStrike = level.strike();
            }
        }

        BigDecimal pcr = totalCallOi > 0
                ? new BigDecimal(totalPutOi).divide(new BigDecimal(totalCallOi), MC)
                : BigDecimal.ZERO;

        boolean oiMismatch = oiMismatch(signal, anyOiDelta, maxCallChange, maxPutChange);
        String alignment = alignmentLabel(signal, anyOiDelta, oiMismatch);

        return new ChainSummary(
                true,
                spot,
                atm.strike(),
                pcr.setScale(2, RoundingMode.HALF_UP),
                totalCallOi,
                totalPutOi,
                maxCallChangeStrike,
                maxCallChange,
                maxPutChangeStrike,
                maxPutChange,
                anyOiDelta,
                atm.callOpenInterestChange(),
                atm.putOpenInterestChange(),
                alignment,
                oiMismatch
        );
    }

    private static boolean oiMismatch(SignalTuningCsvLoader.SignalRow signal, boolean anyOiDelta,
                                      long maxCallChange, long maxPutChange) {
        if (!signal.oiPassed() || !anyOiDelta) {
            return false;
        }
        if (maxCallChange == 0 && maxPutChange == 0) {
            return false;
        }
        if ("BUY_CE".equals(signal.signalType()) || "CE".equalsIgnoreCase(signal.optionType())) {
            return maxPutChange > maxCallChange && maxPutChange > 0;
        }
        if ("BUY_PE".equals(signal.signalType()) || "PE".equalsIgnoreCase(signal.optionType())) {
            return maxCallChange > maxPutChange && maxCallChange > 0;
        }
        return false;
    }

    private static String alignmentLabel(SignalTuningCsvLoader.SignalRow signal, boolean anyOiDelta, boolean mismatch) {
        if (!anyOiDelta) {
            return "no OI Δ";
        }
        if (!signal.oiPassed()) {
            return "OI blocked";
        }
        return mismatch ? "mismatch" : "aligned";
    }

    record ChainSummary(
            boolean present,
            BigDecimal spotPrice,
            long atmStrike,
            BigDecimal pcr,
            long totalCallOi,
            long totalPutOi,
            long maxCallChangeStrike,
            long maxCallChange,
            long maxPutChangeStrike,
            long maxPutChange,
            boolean oiDeltaPresent,
            long atmCallOiChange,
            long atmPutOiChange,
            String alignment,
            boolean oiMismatch
    ) {
        static ChainSummary missing() {
            return new ChainSummary(false, BigDecimal.ZERO, 0, BigDecimal.ZERO, 0, 0,
                    0, 0, 0, 0, false, 0, 0, "—", false);
        }

        String shortSummary() {
            if (!present) {
                return "chain n/a";
            }
            return "PCR " + pcr + " ATM " + atmStrike
                    + " | CEΔ@" + maxCallChangeStrike + "=" + maxCallChange
                    + " PEΔ@" + maxPutChangeStrike + "=" + maxPutChange
                    + " | " + alignment;
        }
    }
}
