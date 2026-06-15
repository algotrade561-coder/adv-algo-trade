package com.algo.trade.strategy;

import com.algo.trade.domain.*;
import com.algo.trade.indicator.EmaIndicator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * EMA Crossover Scalping Strategy — candle-based, 5-min timeframe.
 *
 * Requires 2 consecutive candles confirming EMA 9/21 crossover before entry.
 * Tight stops, 30-min max hold, no entries after 14:00.
 */
@Component
public class ScalpingStrategy implements StrategyEvaluator {

    private static final Logger log = LoggerFactory.getLogger(ScalpingStrategy.class);
    private static final LocalTime CUTOFF = LocalTime.of(14, 0);
    private static final int MIN_CANDLES = 14;
    private static final int CONFIRM_CANDLES = 2;

    private final EmaIndicator emaIndicator;

    private final Map<String, Integer> bullishConfirm = new ConcurrentHashMap<>();
    private final Map<String, Integer> bearishConfirm = new ConcurrentHashMap<>();

    public ScalpingStrategy(EmaIndicator emaIndicator) {
        this.emaIndicator = emaIndicator;
    }

    @Override
    public String strategyName() { return StrategyType.SCALPING.name(); }

    @Override
    public boolean isEnabled() { return true; }

    public StrategyDiagnostics.WithSignal evaluate(StrategyContext ctx, StrategyConfig config) {
        String configuredTf = config.getCandleTimeframe();
        Timeframe tf = (configuredTf == null || configuredTf.isBlank()) ? Timeframe.FIVE_MINUTE : Timeframe.valueOf(configuredTf);
        return evaluateWithDiagnostics(ctx.candles(tf), ctx.marketTime(), config, ctx.underlying());
    }

    public Optional<StrategyDecision> evaluate(List<Candle> candles5m, LocalTime marketTime,
                                               StrategyConfig config, UnderlyingSymbol underlying) {
        return evaluateWithDiagnostics(candles5m, marketTime, config, underlying).signal();
    }

    public StrategyDiagnostics.WithSignal evaluateWithDiagnostics(List<Candle> candles5m, LocalTime marketTime,
                                                                   StrategyConfig config, UnderlyingSymbol underlying) {
        if (marketTime.isBefore(LocalTime.of(9, 30)) || marketTime.isAfter(CUTOFF)) {
            return new StrategyDiagnostics.WithSignal(Optional.empty(),
                    new StrategyDiagnostics("timeWindow", null, null, null, null, null, null, null, null));
        }
        if (candles5m.size() < MIN_CANDLES) {
            return new StrategyDiagnostics.WithSignal(Optional.empty(),
                    new StrategyDiagnostics("insufficientCandles(" + candles5m.size() + "/" + MIN_CANDLES + ")",
                            null, null, null, null, null, null, null, null));
        }

        String key = underlying.name();
        List<BigDecimal> closes = candles5m.stream().map(Candle::close).toList();
        List<BigDecimal> prevCloses = closes.subList(0, closes.size() - 1);

        double fastEma  = emaIndicator.calculate(closes, 9).doubleValue();
        double slowEma  = emaIndicator.calculate(closes, 21).doubleValue();
        double prevFast = emaIndicator.calculate(prevCloses, 9).doubleValue();
        double prevSlow = emaIndicator.calculate(prevCloses, 21).doubleValue();

        boolean bullishCross = prevFast <= prevSlow && fastEma > slowEma;
        boolean bearishCross = prevFast >= prevSlow && fastEma < slowEma;

        if (bullishCross) {
            bullishConfirm.merge(key, 1, Integer::sum);
            bearishConfirm.put(key, 0);
        } else if (bearishCross) {
            bearishConfirm.merge(key, 1, Integer::sum);
            bullishConfirm.put(key, 0);
        } else {
            bullishConfirm.put(key, 0);
            bearishConfirm.put(key, 0);
        }

        int bullCount = bullishConfirm.getOrDefault(key, 0);
        int bearCount = bearishConfirm.getOrDefault(key, 0);
        String crossType = bullishCross ? "BULLISH" : bearishCross ? "BEARISH" : "NONE";
        int confirmCount = bullishCross ? bullCount : (bearishCross ? bearCount : 0);

        // ── Tightening filters ──────────────────────────────────────────────
        // 1. Minimum EMA gap: EMA9 must be at least 0.05% away from EMA21 (filters weak/noisy crosses)
        double emaGapPct = Math.abs(fastEma - slowEma) / slowEma * 100;
        double minEmaGapPct = 0.05; // 0.05% = ~12 pts on NIFTY, ~26 pts on BANKNIFTY

        // 2. Price must be on the right side of the cross (momentum confirmation)
        double lastClose = candles5m.getLast().close().doubleValue();
        boolean priceConfirmsBull = lastClose > fastEma; // Price above fast EMA for bullish
        boolean priceConfirmsBear = lastClose < fastEma; // Price below fast EMA for bearish

        // 3. Trend filter: 15m trend via EMA21 slope (last 3 candles of slow EMA must slope in direction)
        double prevPrevSlow = candles5m.size() >= 16
                ? emaIndicator.calculate(closes.subList(0, closes.size() - 2), 21).doubleValue()
                : prevSlow;
        boolean slowEmaSlopingUp = slowEma > prevSlow && prevSlow >= prevPrevSlow;
        boolean slowEmaSlopingDown = slowEma < prevSlow && prevSlow <= prevPrevSlow;

        if (bullCount >= CONFIRM_CANDLES) {
            bullishConfirm.put(key, 0);
            // Reject if EMA gap too small, price doesn't confirm, or trend opposes
            if (emaGapPct < minEmaGapPct) {
                log.debug("[Scalping {}] Bullish cross rejected: EMA gap {:.3f}% < {}%", underlying, emaGapPct, minEmaGapPct);
                return new StrategyDiagnostics.WithSignal(Optional.empty(),
                        new StrategyDiagnostics("emaGapTooSmall(" + String.format("%.3f", emaGapPct) + "%)",
                                fastEma, slowEma, crossType, confirmCount, null, null, null, null));
            }
            if (!priceConfirmsBull) {
                log.debug("[Scalping {}] Bullish cross rejected: price {} below EMA9 {}", underlying, lastClose, fastEma);
                return new StrategyDiagnostics.WithSignal(Optional.empty(),
                        new StrategyDiagnostics("priceNotConfirming(close=" + String.format("%.1f", lastClose) + "<EMA9)",
                                fastEma, slowEma, crossType, confirmCount, null, null, null, null));
            }
            if (slowEmaSlopingDown) {
                log.debug("[Scalping {}] Bullish cross rejected: EMA21 sloping down (counter-trend)", underlying);
                return new StrategyDiagnostics.WithSignal(Optional.empty(),
                        new StrategyDiagnostics("counterTrend(EMA21_sloping_down)",
                                fastEma, slowEma, crossType, confirmCount, null, null, null, null));
            }
            log.info("[Scalping {}] Bullish EMA crossover confirmed (gap={:.2f}%, price confirms, trend OK)", underlying, emaGapPct);
            StrategyDiagnostics diag = new StrategyDiagnostics(null, fastEma, slowEma, crossType, confirmCount,
                    null, null, null, null);
            return new StrategyDiagnostics.WithSignal(
                    Optional.of(signal(underlying, SignalType.BUY_CE, OptionType.CE,
                            candles5m.getLast().close(), fastEma, slowEma)), diag);
        }
        if (bearCount >= CONFIRM_CANDLES) {
            bearishConfirm.put(key, 0);
            if (emaGapPct < minEmaGapPct) {
                log.debug("[Scalping {}] Bearish cross rejected: EMA gap {:.3f}% < {}%", underlying, emaGapPct, minEmaGapPct);
                return new StrategyDiagnostics.WithSignal(Optional.empty(),
                        new StrategyDiagnostics("emaGapTooSmall(" + String.format("%.3f", emaGapPct) + "%)",
                                fastEma, slowEma, crossType, confirmCount, null, null, null, null));
            }
            if (!priceConfirmsBear) {
                log.debug("[Scalping {}] Bearish cross rejected: price {} above EMA9 {}", underlying, lastClose, fastEma);
                return new StrategyDiagnostics.WithSignal(Optional.empty(),
                        new StrategyDiagnostics("priceNotConfirming(close=" + String.format("%.1f", lastClose) + ">EMA9)",
                                fastEma, slowEma, crossType, confirmCount, null, null, null, null));
            }
            if (slowEmaSlopingUp) {
                log.debug("[Scalping {}] Bearish cross rejected: EMA21 sloping up (counter-trend)", underlying);
                return new StrategyDiagnostics.WithSignal(Optional.empty(),
                        new StrategyDiagnostics("counterTrend(EMA21_sloping_up)",
                                fastEma, slowEma, crossType, confirmCount, null, null, null, null));
            }
            log.info("[Scalping {}] Bearish EMA crossover confirmed (gap={:.2f}%, price confirms, trend OK)", underlying, emaGapPct);
            StrategyDiagnostics diag = new StrategyDiagnostics(null, fastEma, slowEma, crossType, confirmCount,
                    null, null, null, null);
            return new StrategyDiagnostics.WithSignal(
                    Optional.of(signal(underlying, SignalType.BUY_PE, OptionType.PE,
                            candles5m.getLast().close(), fastEma, slowEma)), diag);
        }

        String firstFailed = (bullCount == 0 && bearCount == 0) ? "noEmaCross" : "needsConfirmation(" + confirmCount + "/" + CONFIRM_CANDLES + ")";
        return new StrategyDiagnostics.WithSignal(Optional.empty(),
                new StrategyDiagnostics(firstFailed, fastEma, slowEma, crossType, confirmCount,
                        null, null, null, null));
    }

    private StrategyDecision signal(UnderlyingSymbol underlying, SignalType signalType,
                                     OptionType optionType, BigDecimal price,
                                     double fastEma, double slowEma) {
        return new StrategyDecision(
                Instant.now(), underlying, signalType, price,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of(optionType),
                true, Optional.empty(), true,
                BigDecimal.valueOf(70),
                List.of("Scalping: EMA9/21 crossover (2-candle confirmation)",
                        "EMA9=" + String.format("%.1f", fastEma) +
                        " EMA21=" + String.format("%.1f", slowEma))
        );
    }
}
