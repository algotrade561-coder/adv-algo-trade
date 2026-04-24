package com.algo.trade.strategy;

import com.algo.trade.domain.*;
import com.algo.trade.indicator.EmaIndicator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Evaluates spread and multi-leg strategies using 15-min EMA crossover
 * for directional spreads and IV rank for non-directional strategies.
 *
 * Handles: BullCallSpread, BearPutSpread, LongStraddle, LongStrangle,
 *          ShortStraddle, ShortStrangle, IronCondor, Butterfly, CalendarSpread
 */
@Component
public class SpreadStrategyEvaluator {

    private static final Logger log = LoggerFactory.getLogger(SpreadStrategyEvaluator.class);
    private final EmaIndicator emaIndicator;

    public SpreadStrategyEvaluator(EmaIndicator emaIndicator) {
        this.emaIndicator = emaIndicator;
    }

    public Optional<StrategyDecision> evaluate(List<Candle> candles15m, double ivRank,
                                               StrategyConfig config, UnderlyingSymbol underlying) {
        if (candles15m.size() < 22) return Optional.empty();

        StrategyType type = config.getStrategyType();
        List<BigDecimal> closes = candles15m.stream().map(Candle::close).toList();
        double ema9  = emaIndicator.calculate(closes, 9).doubleValue();
        double ema21 = emaIndicator.calculate(closes, 21).doubleValue();
        boolean bullish = ema9 > ema21;
        boolean bearish = ema9 < ema21;
        BigDecimal latestClose = candles15m.getLast().close();

        return switch (type) {
            case BULL_CALL_SPREAD -> bullish
                    ? Optional.of(signal(underlying, SignalType.BUY_CE, OptionType.CE, latestClose,
                        "Bull call spread: EMA9 > EMA21 (bullish trend)"))
                    : Optional.empty();

            case BEAR_PUT_SPREAD -> bearish
                    ? Optional.of(signal(underlying, SignalType.BUY_PE, OptionType.PE, latestClose,
                        "Bear put spread: EMA9 < EMA21 (bearish trend)"))
                    : Optional.empty();

            case LONG_STRADDLE -> ivRank < config.getMaxIvRankForBuying().doubleValue()
                    ? Optional.of(signal(underlying, SignalType.BUY_CE, OptionType.CE, latestClose,
                        "Long straddle: IV rank=" + String.format("%.0f", ivRank) + " (cheap)"))
                    : Optional.empty();

            case LONG_STRANGLE -> ivRank < config.getMaxIvRankForBuying().doubleValue()
                    ? Optional.of(signal(underlying, SignalType.BUY_CE, OptionType.CE, latestClose,
                        "Long strangle: IV rank=" + String.format("%.0f", ivRank) +
                        " OTM=" + config.getOtmStrikes() + " strikes"))
                    : Optional.empty();

            // Selling strategies — require high IV
            case SHORT_STRADDLE -> ivRank > 50
                    ? Optional.of(signal(underlying, SignalType.BUY_CE, OptionType.CE, latestClose,
                        "Short straddle: IV rank=" + String.format("%.0f", ivRank) + " (expensive, sell premium)"))
                    : Optional.empty();

            case SHORT_STRANGLE -> ivRank > 50
                    ? Optional.of(signal(underlying, SignalType.BUY_CE, OptionType.CE, latestClose,
                        "Short strangle: IV rank=" + String.format("%.0f", ivRank) +
                        " OTM=" + config.getOtmStrikes()))
                    : Optional.empty();

            case IRON_CONDOR -> ivRank > 40
                    ? Optional.of(signal(underlying, SignalType.BUY_CE, OptionType.CE, latestClose,
                        "Iron condor: IV rank=" + String.format("%.0f", ivRank) +
                        " OTM=" + config.getOtmStrikes() + " hedge=" + config.getSpreadStrikes()))
                    : Optional.empty();

            case BUTTERFLY -> Optional.of(signal(underlying, SignalType.BUY_CE, OptionType.CE, latestClose,
                    "Butterfly: low cost, high reward if market stays near strike"));

            case CALENDAR_SPREAD -> Optional.of(signal(underlying, SignalType.BUY_CE, OptionType.CE, latestClose,
                    "Calendar spread: theta decay play"));

            default -> Optional.empty();
        };
    }

    private StrategyDecision signal(UnderlyingSymbol underlying, SignalType signalType,
                                     OptionType optionType, BigDecimal price, String reason) {
        return new StrategyDecision(
                Instant.now(), underlying, signalType, price,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of(optionType),
                true, Optional.empty(), true,
                BigDecimal.valueOf(70),
                List.of(reason)
        );
    }
}
