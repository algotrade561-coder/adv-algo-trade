package com.algo.trade.strategy;

import com.algo.trade.domain.*;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Buys CE/PE in the first 30 minutes when the first session 5-min candle shows
 * a strong directional body — momentum continuation of the opening gap.
 * Entry window: 9:16–9:45 IST only. Requires second candle to confirm direction.
 */
@Component
public class GapAndGoStrategy {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime ENTRY_START = LocalTime.of(9, 16);
    private static final LocalTime ENTRY_CUTOFF = LocalTime.of(9, 45);
    private static final double MIN_BODY_PERCENT = 0.40;

    public Optional<StrategyDecision> evaluate(List<Candle> candles5m, LocalTime marketTime,
                                                StrategyConfig config, UnderlyingSymbol underlying) {
        return evaluateWithDiagnostics(candles5m, marketTime, config, underlying).signal();
    }

    public StrategyDiagnostics.WithSignal evaluateWithDiagnostics(List<Candle> candles5m, LocalTime marketTime,
                                                                    StrategyConfig config, UnderlyingSymbol underlying) {
        if (marketTime.isBefore(ENTRY_START) || marketTime.isAfter(ENTRY_CUTOFF)) {
            return noTrade("timeWindow");
        }
        if (candles5m.size() < 2) {
            return noTrade("notEnoughCandles");
        }

        LocalDate today = LocalDate.now(IST);
        List<Candle> sessionCandles = candles5m.stream()
                .filter(c -> c.timestamp().atZone(IST).toLocalDate().equals(today))
                .sorted(Comparator.comparing(Candle::timestamp))
                .toList();

        if (sessionCandles.size() < 2) {
            return noTrade("notEnoughSessionCandles");
        }

        Candle first = sessionCandles.get(0);
        Candle second = sessionCandles.get(1);
        BigDecimal spotPrice = candles5m.getLast().close();

        if (first.open().signum() == 0) {
            return noTrade("zeroOpenPrice");
        }

        BigDecimal body = first.close().subtract(first.open());
        double bodyPct = body.divide(first.open(), 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100)).doubleValue();

        if (Math.abs(bodyPct) < MIN_BODY_PERCENT) {
            return noTrade("bodyTooSmall(" + String.format("%.2f", bodyPct) + "%)");
        }

        boolean gapUp = bodyPct > 0;
        boolean secondConfirms = gapUp
                ? second.close().compareTo(second.open()) > 0
                : second.close().compareTo(second.open()) < 0;

        if (!secondConfirms) {
            return noTrade("secondCandleNoConfirm");
        }

        SignalType signalType = gapUp ? SignalType.BUY_CE : SignalType.BUY_PE;
        OptionType optionType = gapUp ? OptionType.CE : OptionType.PE;
        String direction = gapUp ? "BULLISH" : "BEARISH";

        StrategyDecision signal = new StrategyDecision(
                Instant.now(), underlying, signalType, spotPrice,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of(optionType),
                false, Optional.empty(), false,
                BigDecimal.valueOf(72),
                List.of(
                        "Gap & Go: body=" + String.format("%.2f", bodyPct) + "% direction=" + direction,
                        "firstCandleOpen=" + first.open() + " close=" + first.close()
                )
        );
        return new StrategyDiagnostics.WithSignal(Optional.of(signal),
                new StrategyDiagnostics(null, null, null, direction, null,
                        null, null, Math.abs(bodyPct), bodyPct > 0));
    }

    private static StrategyDiagnostics.WithSignal noTrade(String reason) {
        return new StrategyDiagnostics.WithSignal(Optional.empty(),
                new StrategyDiagnostics(reason, null, null, null, null, null, null, null, null));
    }
}
