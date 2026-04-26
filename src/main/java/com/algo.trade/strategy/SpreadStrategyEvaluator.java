package com.algo.trade.strategy;

import com.algo.trade.domain.*;
import com.algo.trade.indicator.EmaIndicator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
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

            // Selling strategies — require elevated IV (premium worth selling)
            case SHORT_STRADDLE -> ivRank > 30
                    ? Optional.of(signal(underlying, SignalType.SELL_CE, OptionType.CE, latestClose,
                        "Short straddle: IV rank=" + String.format("%.0f", ivRank) + " (sell premium)"))
                    : Optional.empty();

            case SHORT_STRANGLE -> ivRank > 50
                    ? Optional.of(signal(underlying, SignalType.SELL_CE, OptionType.CE, latestClose,
                        "Short strangle: IV rank=" + String.format("%.0f", ivRank) +
                        " OTM=" + config.getOtmStrikes()))
                    : Optional.empty();

            case IRON_CONDOR -> ivRank > 40
                    ? Optional.of(signal(underlying, SignalType.SELL_CE, OptionType.CE, latestClose,
                        "Iron condor: IV rank=" + String.format("%.0f", ivRank) +
                        " OTM=" + config.getOtmStrikes() + " hedge=" + config.getSpreadStrikes()))
                    : Optional.empty();

            case BUTTERFLY -> ivRank < config.getMaxIvRankForBuying().doubleValue()
                    ? Optional.of(signal(underlying, SignalType.BUY_CE, OptionType.CE, latestClose,
                        "Butterfly: low cost, high reward if market stays near strike, IV rank=" + String.format("%.0f", ivRank)))
                    : Optional.empty();

            case CALENDAR_SPREAD -> ivRank < config.getMaxIvRankForBuying().doubleValue()
                    ? Optional.of(signal(underlying, SignalType.BUY_CE, OptionType.CE, latestClose,
                        "Calendar spread: theta decay play, IV rank=" + String.format("%.0f", ivRank)))
                    : Optional.empty();

            case DIAGONAL_SPREAD -> bullish
                    ? Optional.of(signal(underlying, SignalType.BUY_CE, OptionType.CE, latestClose,
                        "Diagonal spread: bullish bias + theta decay, EMA9 > EMA21"))
                    : Optional.empty();

            case JADE_LIZARD -> ivRank > 30
                    ? Optional.of(signal(underlying, SignalType.SELL_CE, OptionType.CE, latestClose,
                        "Jade lizard: IV rank=" + String.format("%.0f", ivRank) +
                        " (premium collection with downside hedge)"))
                    : Optional.empty();

            case SYNTHETIC_FUTURES -> {
                // Enter synthetic futures on strong directional signal
                double emaDiff = Math.abs(ema9 - ema21) / ema21 * 100;
                if (emaDiff > 0.15 && (bullish || bearish)) {
                    yield Optional.of(signal(underlying,
                            bullish ? SignalType.BUY_CE : SignalType.BUY_PE,
                            bullish ? OptionType.CE : OptionType.PE,
                            latestClose,
                            "Synthetic futures: strong " + (bullish ? "bullish" : "bearish") +
                            " trend, EMA diff=" + String.format("%.2f%%", emaDiff)));
                }
                yield Optional.empty();
            }

            case SCALPING -> {
                // Scalping: quick momentum trades on short-term EMA crossover
                // Use tighter EMA (5/13) for faster signals
                double ema5  = emaIndicator.calculate(closes, 5).doubleValue();
                double ema13 = emaIndicator.calculate(closes, 13).doubleValue();
                boolean scalpBullish = ema5 > ema13;
                boolean scalpBearish = ema5 < ema13;
                double emaDiffPct = Math.abs(ema5 - ema13) / ema13 * 100;
                // Require minimum momentum (0.05% EMA divergence)
                if (emaDiffPct > 0.05 && (scalpBullish || scalpBearish)) {
                    yield Optional.of(signal(underlying,
                            scalpBullish ? SignalType.BUY_CE : SignalType.BUY_PE,
                            scalpBullish ? OptionType.CE : OptionType.PE,
                            latestClose,
                            "Scalping: EMA5/13 " + (scalpBullish ? "bullish" : "bearish") +
                            " crossover, diff=" + String.format("%.3f%%", emaDiffPct)));
                }
                yield Optional.empty();
            }

            case EVENT_DRIVEN_BUY -> {
                // Only fire near known event dates (RBI MPC, Budget, etc.)
                // In backtest, approximate by checking if it's within 2 days of month-end
                // (many events cluster around month boundaries)
                // Derive the date from the latest candle timestamp (works for both live and backtest)
                LocalDate evalDate = candles15m.getLast().timestamp()
                        .atZone(java.time.ZoneId.of("Asia/Kolkata")).toLocalDate();
                int dayOfMonth = evalDate.getDayOfMonth();
                int monthLength = evalDate.lengthOfMonth();
                boolean nearEvent = dayOfMonth <= 3 || dayOfMonth >= (monthLength - 2);
                if (nearEvent && ivRank < config.getMaxIvRankForBuying().doubleValue()) {
                    yield Optional.of(signal(underlying, SignalType.BUY_CE, OptionType.CE, latestClose,
                        "Event-driven buy: IV rank=" + String.format("%.0f", ivRank) +
                        " (cheap straddle near event, day=" + dayOfMonth + ")"));
                }
                yield Optional.empty();
            }

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
