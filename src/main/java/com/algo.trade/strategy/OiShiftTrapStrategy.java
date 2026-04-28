package com.algo.trade.strategy;

import com.algo.trade.domain.*;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Detects "trapped" option writers at strikes near the current price.
 * Call-heavy strike just above spot → call writers exposed → price squeeze up → BUY_CE.
 * Put-heavy strike just below spot → put writers exposed → price squeeze down → BUY_PE.
 * Requires option chain snapshot from buildScanContext (like ITM_CONVICTION).
 */
@Component
public class OiShiftTrapStrategy {

    private static final double OI_IMBALANCE_RATIO = 2.5;
    private static final double PROXIMITY_PERCENT = 0.6;

    public Optional<StrategyDecision> evaluate(OptionChainSnapshot snapshot, BigDecimal spotPrice,
                                                StrategyConfig config, UnderlyingSymbol underlying) {
        if (snapshot == null || snapshot.levels().isEmpty()) {
            return Optional.empty();
        }

        List<OptionChainLevel> sorted = snapshot.levels().stream()
                .sorted(Comparator.comparing(OptionChainLevel::strike))
                .toList();

        OptionChainLevel atm = sorted.stream()
                .min(Comparator.comparing(l -> l.strike().subtract(spotPrice).abs()))
                .orElse(null);
        if (atm == null) return Optional.empty();

        double spot = spotPrice.doubleValue();

        // Levels above ATM: look for call-heavy strikes price is approaching
        for (OptionChainLevel level : sorted) {
            if (level.strike().compareTo(atm.strike()) <= 0) continue;
            if (level.callOpenInterest() == 0) continue;
            double proximity = (level.strike().doubleValue() - spot) / spot * 100;
            if (proximity > PROXIMITY_PERCENT) break;

            double ceOi = level.callOpenInterest();
            double peOi = Math.max(level.putOpenInterest(), 1);
            double imbalance = ceOi / peOi;
            if (imbalance >= OI_IMBALANCE_RATIO) {
                return Optional.of(new StrategyDecision(
                        Instant.now(), underlying, SignalType.BUY_CE, spotPrice,
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.empty(), Optional.of(level.strike()), Optional.of(OptionType.CE),
                        false, Optional.of(BigDecimal.valueOf(imbalance)), false,
                        BigDecimal.valueOf(70),
                        List.of(
                                "OI Shift Trap: call-heavy strike=" + level.strike()
                                        + " imbalance=" + String.format("%.1f", imbalance) + "x",
                                "Spot=" + spotPrice + " proximity=" + String.format("%.2f", proximity) + "% — call writers exposed"
                        )
                ));
            }
        }

        // Levels below ATM: look for put-heavy strikes price is approaching
        List<OptionChainLevel> belowAtm = sorted.stream()
                .filter(l -> l.strike().compareTo(atm.strike()) < 0)
                .sorted(Comparator.comparing(OptionChainLevel::strike, Comparator.reverseOrder()))
                .toList();

        for (OptionChainLevel level : belowAtm) {
            if (level.putOpenInterest() == 0) continue;
            double proximity = (spot - level.strike().doubleValue()) / spot * 100;
            if (proximity > PROXIMITY_PERCENT) break;

            double peOi = level.putOpenInterest();
            double ceOi = Math.max(level.callOpenInterest(), 1);
            double imbalance = peOi / ceOi;
            if (imbalance >= OI_IMBALANCE_RATIO) {
                return Optional.of(new StrategyDecision(
                        Instant.now(), underlying, SignalType.BUY_PE, spotPrice,
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.empty(), Optional.of(level.strike()), Optional.of(OptionType.PE),
                        false, Optional.of(BigDecimal.valueOf(imbalance)), false,
                        BigDecimal.valueOf(70),
                        List.of(
                                "OI Shift Trap: put-heavy strike=" + level.strike()
                                        + " imbalance=" + String.format("%.1f", imbalance) + "x",
                                "Spot=" + spotPrice + " proximity=" + String.format("%.2f", proximity) + "% — put writers exposed"
                        )
                ));
            }
        }

        return Optional.empty();
    }
}
