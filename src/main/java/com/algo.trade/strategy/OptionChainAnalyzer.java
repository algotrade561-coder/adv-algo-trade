package com.algo.trade.strategy;

import com.algo.trade.domain.OptionChainLevel;
import com.algo.trade.domain.OptionChainSnapshot;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Analyzes option-chain OI structure for support, resistance, and imbalance.
 */
public class OptionChainAnalyzer {

    private static final MathContext MATH_CONTEXT = MathContext.DECIMAL64;

    public OptionChainAnalysis analyze(OptionChainSnapshot snapshot, int nearbyStrikes) {
        if (nearbyStrikes <= 0) {
            throw new IllegalArgumentException("nearbyStrikes must be positive");
        }

        Optional<OptionChainLevel> resistance = snapshot.levels().stream()
                .max(Comparator.comparingLong(OptionChainLevel::callOpenInterest));
        Optional<OptionChainLevel> support = snapshot.levels().stream()
                .max(Comparator.comparingLong(OptionChainLevel::putOpenInterest));

        BigDecimal atm = nearestStrike(snapshot);
        List<OptionChainLevel> nearby = nearbyLevels(snapshot, atm, nearbyStrikes);
        long callOi = nearby.stream().mapToLong(OptionChainLevel::callOpenInterest).sum();
        long putOi = nearby.stream().mapToLong(OptionChainLevel::putOpenInterest).sum();
        long nearbyCallOiChange = nearby.stream().mapToLong(OptionChainLevel::callOpenInterestChange).sum();
        long nearbyPutOiChange = nearby.stream().mapToLong(OptionChainLevel::putOpenInterestChange).sum();
        BigDecimal imbalance = callOi == 0
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(putOi).divide(BigDecimal.valueOf(callOi), MATH_CONTEXT);

        return new OptionChainAnalysis(
                snapshot.underlying(),
                snapshot.timestamp(),
                resistance.map(OptionChainLevel::strike),
                support.map(OptionChainLevel::strike),
                imbalance,
                callOi,
                putOi,
                nearbyCallOiChange,
                nearbyPutOiChange,
                resistance.map(OptionChainLevel::callOpenInterestChange).orElse(0L),
                support.map(OptionChainLevel::putOpenInterestChange).orElse(0L)
        );
    }

    private BigDecimal nearestStrike(OptionChainSnapshot snapshot) {
        return snapshot.levels().stream()
                .map(OptionChainLevel::strike)
                .min(Comparator.comparing(strike -> strike.subtract(snapshot.underlyingPrice()).abs()))
                .orElse(snapshot.underlyingPrice());
    }

    private List<OptionChainLevel> nearbyLevels(OptionChainSnapshot snapshot, BigDecimal atm, int nearbyStrikes) {
        List<BigDecimal> sortedStrikes = snapshot.levels().stream()
                .map(OptionChainLevel::strike)
                .sorted()
                .toList();
        int atmIndex = sortedStrikes.indexOf(atm);
        if (atmIndex < 0) {
            return List.of();
        }
        int start = Math.max(0, atmIndex - nearbyStrikes);
        int end = Math.min(sortedStrikes.size(), atmIndex + nearbyStrikes + 1);
        List<BigDecimal> selectedStrikes = sortedStrikes.subList(start, end);
        return snapshot.levels().stream()
                .filter(level -> selectedStrikes.contains(level.strike()))
                .toList();
    }
}
