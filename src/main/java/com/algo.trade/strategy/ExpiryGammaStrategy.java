package com.algo.trade.strategy;

import com.algo.trade.domain.*;
import com.algo.trade.marketdata.ExpiryCalendar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 *
 * Quality gates:
 *   1. Must be actual expiry day
 *   2. Momentum must be strong (0.50%+ over 3 candles, raised from 0.30%)
 *   3. Volume must confirm the move (not a thin-market spike)
 *   4. Momentum must be accelerating (latest candle stronger than prior)
 *   5. Graduated confidence score
 */
@Component
public class ExpiryGammaStrategy {

    private static final Logger log = LoggerFactory.getLogger(ExpiryGammaStrategy.class);
    private static final LocalTime GAMMA_START = LocalTime.of(13, 0);
    private static final LocalTime GAMMA_CUTOFF = LocalTime.of(14, 30);
    private static final int MOMENTUM_CANDLES = 3;
    /** Minimum momentum % over MOMENTUM_CANDLES — raised from 0.30 to filter noise. */
    private static final double MIN_MOMENTUM_PERCENT = 0.50;
    /** Minimum volume on the latest candle to confirm the move is real. */
    private static final long MIN_VOLUME = 500;

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
        if (candles1m.size() < MOMENTUM_CANDLES + 1) {
            return noTrade("notEnoughCandles");
        }

        int size = candles1m.size();
        Candle latest = candles1m.get(size - 1);
        Candle prior = candles1m.get(size - MOMENTUM_CANDLES);
        BigDecimal recent = latest.close();
        BigDecimal priorOpen = prior.open();
        if (priorOpen.signum() == 0) {
            return noTrade("zeroPriorPrice");
        }

        double momentumPct = recent.subtract(priorOpen)
                .divide(priorOpen, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100)).doubleValue();

        if (Math.abs(momentumPct) < MIN_MOMENTUM_PERCENT) {
            return noTrade("momentumTooWeak(" + String.format("%.2f", momentumPct) + "%)");
        }

        // ── Gate: Volume confirmation ──
        long latestVolume = latest.volume();
        if (latestVolume < MIN_VOLUME) {
            return noTrade("lowVolume(" + latestVolume + ")");
        }

        // ── Gate: Momentum acceleration — latest candle move should be >= prior candle move ──
        boolean bullish = momentumPct > 0;
        Candle prevCandle = candles1m.get(size - 2);
        double latestMove = latest.close().subtract(latest.open()).doubleValue();
        double prevMove = prevCandle.close().subtract(prevCandle.open()).doubleValue();
        boolean accelerating;
        if (bullish) {
            accelerating = latestMove > 0 && latestMove >= prevMove * 0.7; // latest candle at least 70% as strong
        } else {
            accelerating = latestMove < 0 && latestMove <= prevMove * 0.7;
        }
        if (!accelerating) {
            return noTrade("momentumDecelerating");
        }

        // ── Graduated confidence score ──
        int score = 60;
        if (Math.abs(momentumPct) > 0.80) score += 10;  // very strong momentum
        if (latestVolume > MIN_VOLUME * 3) score += 10;  // exceptional volume
        else if (latestVolume > MIN_VOLUME * 2) score += 5;
        if (accelerating) score += 5;
        // Closer to 13:30-14:00 is the sweet spot for gamma
        if (marketTime.isAfter(LocalTime.of(13, 20)) && marketTime.isBefore(LocalTime.of(14, 10))) score += 5;
        score = Math.min(90, score);

        SignalType signalType = bullish ? SignalType.BUY_CE : SignalType.BUY_PE;
        OptionType optionType = bullish ? OptionType.CE : OptionType.PE;
        String direction = bullish ? "BULLISH" : "BEARISH";

        log.info("[ExpiryGamma] Signal: {} momentum={}% vol={} accelerating={} score={}",
                signalType, String.format("%.2f", momentumPct), latestVolume, accelerating, score);

        StrategyDecision signal = new StrategyDecision(
                Instant.now(), underlying, signalType, recent,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of(optionType),
                false, Optional.empty(), false,
                BigDecimal.valueOf(score),
                List.of(
                        "Expiry Gamma: momentum=" + String.format("%.2f", momentumPct) + "% vol=" + latestVolume
                                + " accelerating=" + accelerating + " direction=" + direction,
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
