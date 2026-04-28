package com.algo.trade.strategy;

import com.algo.trade.domain.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * ITM Conviction Strategy — compares ITM vs ATM option strength using ATP and LTP.
 *
 * Core formula: StrengthDiff = ATP - LTP
 * - Smaller/negative StrengthDiff = stronger option (buyers pushing price above average)
 * - If ITM option is stronger than ATM + underlying moving in that direction → entry signal
 *
 * This strategy only generates entry signals (BUY_CE, BUY_PE, NO_TRADE).
 * Exit and risk management are handled by the existing global system.
 *
 * Requires live ATP data from Kite quote API — not backtestable with historical OHLCV data.
 */
@Component
public class ItmConvictionStrategy {

    private static final Logger log = LoggerFactory.getLogger(ItmConvictionStrategy.class);

    // Track previous underlying price for direction detection
    private volatile BigDecimal previousUnderlyingPrice = null;

    /**
     * Evaluate ITM conviction signal.
     *
     * @param underlying      the underlying symbol (NIFTY, BANKNIFTY)
     * @param underlyingPrice current underlying LTP
     * @param optionQuotes    map of instrument key → Quote (must include ATP)
     * @param config          per-strategy configuration
     * @return BUY_CE, BUY_PE, or empty (NO_TRADE)
     */
    public Optional<StrategyDecision> evaluate(UnderlyingSymbol underlying,
                                               BigDecimal underlyingPrice,
                                               Map<String, Quote> optionQuotes,
                                               StrategyConfig config) {
        if (optionQuotes == null || optionQuotes.isEmpty()) {
            return Optional.empty();
        }

        // Expiry proximity guard — don't enter within 2 days of expiry (high gamma risk for ITM options)
        IndexType indexType = IndexType.from(underlying);
        java.time.LocalDate today = java.time.LocalDate.now();
        java.time.DayOfWeek expiryDay = indexType.expiryDay();
        long daysToExpiry = 0;
        java.time.LocalDate candidate = today;
        for (int i = 0; i < 7; i++) {
            if (candidate.getDayOfWeek() == expiryDay) {
                daysToExpiry = java.time.temporal.ChronoUnit.DAYS.between(today, candidate);
                break;
            }
            candidate = candidate.plusDays(1);
        }
        if (daysToExpiry <= 1) {
            log.debug("[ItmConviction] Skipping — too close to expiry: daysToExpiry={}", daysToExpiry);
            return Optional.empty();
        }
        int atmStrike = indexType.roundToATM(underlyingPrice.doubleValue());
        int interval = indexType.strikeInterval();
        int itmDepth = config.getItmDepth();

        // ── Direction detection ───────────────────────────────────────────
        Direction direction = detectDirection(underlyingPrice, config.getMinimumMove());
        previousUnderlyingPrice = underlyingPrice;

        if (direction == Direction.NONE) {
            log.debug("[ItmConviction] No directional move: price={} prev={} minMove={}",
                    underlyingPrice, previousUnderlyingPrice, config.getMinimumMove());
            return Optional.empty();
        }

        // ITM CE = strike BELOW ATM; ITM PE = strike ABOVE ATM
        int itmCeStrike = atmStrike - (itmDepth * interval);
        int itmPeStrike = atmStrike + (itmDepth * interval);

        if (direction == Direction.UP) {
            return evaluateCeSignal(underlying, underlyingPrice, optionQuotes,
                    atmStrike, itmCeStrike, config);
        }
        if (direction == Direction.DOWN) {
            return evaluatePeSignal(underlying, underlyingPrice, optionQuotes,
                    atmStrike, itmPeStrike, config);
        }
        return Optional.empty();
    }

    private Optional<StrategyDecision> evaluateCeSignal(UnderlyingSymbol underlying,
                                                         BigDecimal underlyingPrice,
                                                         Map<String, Quote> quotes,
                                                         int atmStrike, int itmStrike,
                                                         StrategyConfig config) {
        QuotePair atm = findQuote(quotes, atmStrike, OptionType.CE);
        QuotePair itm = findQuote(quotes, itmStrike, OptionType.CE);
        if (atm == null || itm == null) {
            log.debug("[ItmConviction CE] Missing quotes: ATM{}={} ITM{}={}", atmStrike, atm != null, itmStrike, itm != null);
            return Optional.empty();
        }

        BigDecimal itmDiff = itm.atp.subtract(itm.ltp);
        BigDecimal atmDiff = atm.atp.subtract(atm.ltp);
        BigDecimal gap = atmDiff.subtract(itmDiff);

        log.debug("[ItmConviction CE] ATM{} diff={} ITM{} diff={} gap={} vol={}",
                atmStrike, atmDiff, itmStrike, itmDiff, gap, itm.volume);

        if (itmDiff.compareTo(atmDiff) >= 0) return Optional.empty();
        if (gap.compareTo(config.getMinimumStrengthGap()) < 0) return Optional.empty();
        if (itm.volume < config.getMinimumVolume()) return Optional.empty();

        log.info("[ItmConviction] BUY_CE: underlying={} ATM{}_diff={} ITM{}_diff={} gap={} vol={}",
                underlyingPrice, atmStrike, atmDiff, itmStrike, itmDiff, gap, itm.volume);

        return Optional.of(signal(underlying, SignalType.BUY_CE, OptionType.CE, underlyingPrice,
                String.format("ITM CE conviction: ATM%d diff=%.2f, ITM%d diff=%.2f, gap=%.2f, vol=%d",
                        atmStrike, atmDiff.doubleValue(), itmStrike, itmDiff.doubleValue(),
                        gap.doubleValue(), itm.volume)));
    }

    private Optional<StrategyDecision> evaluatePeSignal(UnderlyingSymbol underlying,
                                                         BigDecimal underlyingPrice,
                                                         Map<String, Quote> quotes,
                                                         int atmStrike, int itmStrike,
                                                         StrategyConfig config) {
        QuotePair atm = findQuote(quotes, atmStrike, OptionType.PE);
        QuotePair itm = findQuote(quotes, itmStrike, OptionType.PE);
        if (atm == null || itm == null) {
            log.warn("[ItmConviction PE] Missing quotes (ATP likely absent — token not in WebSocket cache): ATM{}={} ITM{}={}",
                    atmStrike, atm != null, itmStrike, itm != null);
            return Optional.empty();
        }

        BigDecimal itmDiff = itm.atp.subtract(itm.ltp);
        BigDecimal atmDiff = atm.atp.subtract(atm.ltp);
        BigDecimal gap = atmDiff.subtract(itmDiff);

        if (itmDiff.compareTo(atmDiff) >= 0) return Optional.empty();
        if (gap.compareTo(config.getMinimumStrengthGap()) < 0) return Optional.empty();
        if (itm.volume < config.getMinimumVolume()) return Optional.empty();

        log.info("[ItmConviction] BUY_PE: underlying={} ATM{}_diff={} ITM{}_diff={} gap={} vol={}",
                underlyingPrice, atmStrike, atmDiff, itmStrike, itmDiff, gap, itm.volume);

        return Optional.of(signal(underlying, SignalType.BUY_PE, OptionType.PE, underlyingPrice,
                String.format("ITM PE conviction: ATM%d diff=%.2f, ITM%d diff=%.2f, gap=%.2f, vol=%d",
                        atmStrike, atmDiff.doubleValue(), itmStrike, itmDiff.doubleValue(),
                        gap.doubleValue(), itm.volume)));
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private Direction detectDirection(BigDecimal currentPrice, BigDecimal minimumMove) {
        if (previousUnderlyingPrice == null) return Direction.NONE;
        BigDecimal diff = currentPrice.subtract(previousUnderlyingPrice);
        if (diff.compareTo(minimumMove) > 0) return Direction.UP;
        if (diff.compareTo(minimumMove.negate()) < 0) return Direction.DOWN;
        return Direction.NONE;
    }

    /** Find a quote matching the given strike and option type from the quotes map. */
    private QuotePair findQuote(Map<String, Quote> quotes, int strike, OptionType optionType) {
        String suffix = strike + optionType.name();
        for (Map.Entry<String, Quote> entry : quotes.entrySet()) {
            if (entry.getKey().toUpperCase().contains(suffix)) {
                Quote q = entry.getValue();
                BigDecimal atp = q.averageTradedPrice().orElse(null);
                if (atp == null || atp.signum() <= 0) {
                    log.warn("[ItmConviction] ATP missing for {} — quote came from REST fallback, not WebSocket",
                            entry.getKey());
                    continue;
                }
                if (q.lastPrice().signum() <= 0) continue;
                return new QuotePair(q.lastPrice(), atp, q.volume());
            }
        }
        return null;
    }

    private StrategyDecision signal(UnderlyingSymbol underlying, SignalType signalType,
                                     OptionType optionType, BigDecimal price, String reason) {
        return new StrategyDecision(
                Instant.now(), underlying, signalType, price,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of(optionType),
                true, Optional.empty(), true,
                BigDecimal.valueOf(75),
                List.of(reason));
    }

    private enum Direction { UP, DOWN, NONE }
    private record QuotePair(BigDecimal ltp, BigDecimal atp, long volume) {}
}
