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
 * Near expiry (0–1 days), fades a sharp intraday spike — buys against the move
 * expecting a pin to the nearest key strike or reversal pullback.
 * Spike up → BUY_PE. Spike down → BUY_CE.
 */
@Component
public class ExpiryReversalStrategy {

    private static final int NEAR_EXPIRY_DAYS = 1;
    private static final int LOOK_BACK_CANDLES = 5;
    private static final double MIN_SPIKE_PERCENT = 0.50;

    private final ExpiryCalendar expiryCalendar;

    public ExpiryReversalStrategy(ExpiryCalendar expiryCalendar) {
        this.expiryCalendar = expiryCalendar;
    }

    public Optional<StrategyDecision> evaluate(List<Candle> candles1m, LocalTime marketTime,
                                                StrategyConfig config, UnderlyingSymbol underlying) {
        return evaluateWithDiagnostics(candles1m, marketTime, config, underlying).signal();
    }

    public StrategyDiagnostics.WithSignal evaluateWithDiagnostics(List<Candle> candles1m, LocalTime marketTime,
                                                                    StrategyConfig config, UnderlyingSymbol underlying) {
        IndexType idx = IndexType.from(underlying);

        if (!expiryCalendar.isNearExpiry(idx, NEAR_EXPIRY_DAYS)) {
            return noTrade("notNearExpiry(daysToExpiry=" + expiryCalendar.daysToExpiry(idx) + ")");
        }
        if (candles1m.size() < LOOK_BACK_CANDLES) {
            return noTrade("notEnoughCandles");
        }

        int size = candles1m.size();
        BigDecimal current = candles1m.get(size - 1).close();
        BigDecimal baseline = candles1m.get(size - LOOK_BACK_CANDLES).open();
        if (baseline.signum() == 0) {
            return noTrade("zeroBaselinePrice");
        }

        double spikePct = current.subtract(baseline)
                .divide(baseline, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100)).doubleValue();

        if (Math.abs(spikePct) < MIN_SPIKE_PERCENT) {
            return noTrade("spikeInsufficient(spike=" + String.format("%.2f", spikePct) + "%)");
        }

        // Fade the spike: spike up → expect reversal → BUY_PE; spike down → BUY_CE
        boolean spikeUp = spikePct > 0;
        SignalType signalType = spikeUp ? SignalType.BUY_PE : SignalType.BUY_CE;
        OptionType optionType = spikeUp ? OptionType.PE : OptionType.CE;
        String direction = spikeUp ? "FADING_SPIKE_UP" : "FADING_SPIKE_DOWN";

        StrategyDecision signal = new StrategyDecision(
                Instant.now(), underlying, signalType, current,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of(optionType),
                false, Optional.empty(), false,
                BigDecimal.valueOf(65),
                List.of(
                        "Expiry Reversal: spike=" + String.format("%.2f", spikePct) + "% direction=" + direction,
                        "daysToExpiry=" + expiryCalendar.daysToExpiry(idx) + " — fade expected near key strike"
                )
        );
        return new StrategyDiagnostics.WithSignal(Optional.of(signal),
                new StrategyDiagnostics(null, null, null, direction, LOOK_BACK_CANDLES,
                        null, null, Math.abs(spikePct), spikePct > MIN_SPIKE_PERCENT));
    }

    private static StrategyDiagnostics.WithSignal noTrade(String reason) {
        return new StrategyDiagnostics.WithSignal(Optional.empty(),
                new StrategyDiagnostics(reason, null, null, null, null, null, null, null, null));
    }
}
