package com.algo.trade.strategy;

import com.algo.trade.domain.*;
import com.algo.trade.marketdata.ExpiryCalendar;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

/**
 * Exploits high ATM gamma on expiry day — buys in the direction of momentum
 * between 13:00 and 14:30 IST when option premiums move explosively.
 * Only fires on the actual expiry day of the given underlying.
 */
@Component
public class ExpiryGammaStrategy {

    private static final LocalTime GAMMA_START = LocalTime.of(13, 0);
    private static final LocalTime GAMMA_CUTOFF = LocalTime.of(14, 30);
    private static final int MOMENTUM_CANDLES = 3;
    private static final double MIN_MOMENTUM_PERCENT = 0.30;

    private final ExpiryCalendar expiryCalendar;

    public ExpiryGammaStrategy(ExpiryCalendar expiryCalendar) {
        this.expiryCalendar = expiryCalendar;
    }

    public Optional<StrategyDecision> evaluate(List<Candle> candles1m, LocalTime marketTime,
                                                StrategyConfig config, UnderlyingSymbol underlying) {
        return evaluateWithDiagnostics(candles1m, marketTime, config, underlying).signal();
    }

    public StrategyDiagnostics.WithSignal evaluateWithDiagnostics(List<Candle> candles1m, LocalTime marketTime,
                                                                    StrategyConfig config, UnderlyingSymbol underlying) {
        IndexType idx = IndexType.from(underlying);

        if (!expiryCalendar.isExpiryDay(idx)) {
            return noTrade("notExpiryDay");
        }
        if (marketTime.isBefore(GAMMA_START) || marketTime.isAfter(GAMMA_CUTOFF)) {
            return noTrade("outsideGammaWindow(" + marketTime + ")");
        }
        if (candles1m.size() < MOMENTUM_CANDLES) {
            return noTrade("notEnoughCandles");
        }

        int size = candles1m.size();
        BigDecimal recent = candles1m.get(size - 1).close();
        BigDecimal prior = candles1m.get(size - MOMENTUM_CANDLES).open();
        if (prior.signum() == 0) {
            return noTrade("zeroPriorPrice");
        }

        double momentumPct = recent.subtract(prior)
                .divide(prior, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100)).doubleValue();

        if (Math.abs(momentumPct) < MIN_MOMENTUM_PERCENT) {
            return noTrade("momentumTooWeak(" + String.format("%.2f", momentumPct) + "%)");
        }

        boolean bullish = momentumPct > 0;
        SignalType signalType = bullish ? SignalType.BUY_CE : SignalType.BUY_PE;
        OptionType optionType = bullish ? OptionType.CE : OptionType.PE;
        String direction = bullish ? "BULLISH" : "BEARISH";

        StrategyDecision signal = new StrategyDecision(
                Instant.now(), underlying, signalType, recent,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of(optionType),
                false, Optional.empty(), false,
                BigDecimal.valueOf(78),
                List.of(
                        "Expiry Gamma: expiry day momentum=" + String.format("%.2f", momentumPct) + "% direction=" + direction,
                        "Window: 13:00-14:30 — high gamma zone, ATM premium explosive"
                )
        );
        return new StrategyDiagnostics.WithSignal(Optional.of(signal),
                new StrategyDiagnostics(null, null, null, direction, MOMENTUM_CANDLES,
                        null, null, Math.abs(momentumPct), true));
    }

    private static StrategyDiagnostics.WithSignal noTrade(String reason) {
        return new StrategyDiagnostics.WithSignal(Optional.empty(),
                new StrategyDiagnostics(reason, null, null, null, null, null, null, null, null));
    }
}
