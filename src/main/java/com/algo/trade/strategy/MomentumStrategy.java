package com.algo.trade.strategy;

import com.algo.trade.domain.*;
import com.algo.trade.indicator.AtrIndicator;
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
 * Momentum Strategy — buys in the direction of sustained price momentum.
 *
 * Unlike EMA crossover (lagging) or breakout (single event), this tracks
 * the RATE of price change across multiple timeframes and enters when
 * momentum is strong, accelerating, and confirmed by volume.
 *
 * Entry conditions (all must pass):
 *   1. Rate of Change (ROC) over 5 candles > threshold (0.30%)
 *   2. ROC is accelerating (current ROC > prior ROC)
 *   3. Price above EMA-21 for CE, below for PE (trend alignment)
 *   4. Volume on latest candle > 1.2x average (momentum has participation)
 *   5. ATR > minimum threshold (market is moving, not dead)
 */
@Component
public class MomentumStrategy {

    private static final Logger log = LoggerFactory.getLogger(MomentumStrategy.class);
    private static final int ROC_PERIOD = 5;
    /** Raised 0.25 → 0.40 (2026-06-01) — 0.305% entries reliably picked tops. */
    private static final double MIN_ROC_PERCENT = 0.25;
    private static final int EMA_PERIOD = 21;
    private static final double MIN_VOLUME_RATIO = 1.2;
    private static final int ATR_PERIOD = 14;
    /** Below 65 means base score only with zero quality bonuses — filter out. */
    private static final int MIN_CONFIDENCE_SCORE = 65;
    /** Per-(underlying, direction) cooldown — blocks identical entries seconds apart. */
    private static final java.time.Duration ENTRY_COOLDOWN = java.time.Duration.ofMinutes(5);

    /** Last entry time keyed by "{underlying}:{direction}". Lock-free read. */
    private final java.util.concurrent.ConcurrentHashMap<String, Instant> lastEntryAt =
            new java.util.concurrent.ConcurrentHashMap<>();

    private final com.algo.trade.underlying.UnderlyingConfigService underlyingConfigService;

    public MomentumStrategy(
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            com.algo.trade.underlying.UnderlyingConfigService underlyingConfigService) {
        this.underlyingConfigService = underlyingConfigService;
    }

    public Optional<StrategyDecision> evaluate(List<Candle> candles, LocalTime marketTime,
                                                StrategyConfig config, UnderlyingSymbol underlying) {
        return evaluateWithDiagnostics(candles, marketTime, config, underlying).signal();
    }

    // Relaxed: allow entries from 9:20 (after auction noise) through 15:15 (before squareoff)
    private static final LocalTime MARKET_OPEN_GUARD  = LocalTime.of(9, 20);
    private static final LocalTime MARKET_CLOSE_GUARD = LocalTime.of(15, 15);

    // 3 Jun 2026: midday entry block REPLACED with stricter ROC threshold.
    // Previously hard-blocked 12:00-13:00; now allows entries with ROC >= 0.45%.
    // This keeps participation during midday while still requiring stronger conviction.
    private static final LocalTime MIDDAY_BLOCK_START = LocalTime.of(12, 0);
    private static final LocalTime MIDDAY_BLOCK_END   = LocalTime.of(13, 0);

    // 3 Jun 2026: raise the score-equivalent threshold during midday window —
    // both losing trades had ROC just over the 0.40% floor (0.62%, 0.65%).
    // During the midday block we require a stronger move to overcome the
    // higher-than-usual reversal risk.
    private static final double MIN_ROC_PERCENT_MIDDAY = 0.45;

    public StrategyDiagnostics.WithSignal evaluateWithDiagnostics(List<Candle> candles, LocalTime marketTime,
                                                                    StrategyConfig config, UnderlyingSymbol underlying) {
        // Bug fix: time filter was accepted but never applied
        if (marketTime != null && (marketTime.isBefore(MARKET_OPEN_GUARD) || marketTime.isAfter(MARKET_CLOSE_GUARD))) {
            return noTrade("outsideTradingWindow(" + marketTime + ")");
        }

        // Midday: no hard block — stricter ROC threshold (MIN_ROC_PERCENT_MIDDAY) is applied
        // downstream instead. This replaces the blanket 12:00-13:00 veto that starved entries.

        if (candles.size() < Math.max(ROC_PERIOD * 2 + 1, EMA_PERIOD + 1)) {
            return noTrade("notEnoughCandles(" + candles.size() + ")");
        }

        int size = candles.size();
        BigDecimal current = candles.get(size - 1).close();
        BigDecimal priorForRoc = candles.get(size - 1 - ROC_PERIOD).close();
        BigDecimal prevRocBase = candles.get(size - 2 - ROC_PERIOD).close();
        BigDecimal prevRocEnd = candles.get(size - 2).close();

        if (priorForRoc.signum() == 0 || prevRocBase.signum() == 0) {
            return noTrade("zeroPriceInHistory");
        }

        // 1. Rate of Change
        double roc = current.subtract(priorForRoc)
                .divide(priorForRoc, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100)).doubleValue();

        // 3 Jun 2026: stricter midday threshold (after 11:30 IST — the moment
        // institutional positioning typically settles for the day). Forces a
        // higher conviction trigger than the morning threshold.
        double rocThreshold = MIN_ROC_PERCENT;
        if (marketTime != null && !marketTime.isBefore(LocalTime.of(11, 30))
                && marketTime.isBefore(LocalTime.of(14, 30))) {
            rocThreshold = MIN_ROC_PERCENT_MIDDAY;
        }
        if (Math.abs(roc) < rocThreshold) {
            return noTrade("rocTooWeak(" + String.format("%.2f", roc)
                    + "%,floor=" + String.format("%.2f", rocThreshold) + "%)");
        }

        // 2. ROC acceleration
        double prevRoc = prevRocEnd.subtract(prevRocBase)
                .divide(prevRocBase, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100)).doubleValue();

        boolean bullish = roc > 0;
        // Bug fix: require prevRoc to be same-direction so reversals don't pass as "acceleration"
        // e.g. prevRoc=+0.2, roc=-0.35 used to satisfy (roc < prevRoc) even though it's a reversal
        boolean accelerating = bullish
                ? (prevRoc >= 0 && roc > prevRoc)
                : (prevRoc <= 0 && roc < prevRoc);
        if (!accelerating) {
            return noTrade("rocDecelerating(roc=" + String.format("%.2f", roc) + ",prev=" + String.format("%.2f", prevRoc) + ")");
        }

        // 3. EMA trend alignment
        double ema = calculateEma(candles, EMA_PERIOD);
        boolean trendAligned = bullish
                ? current.doubleValue() > ema
                : current.doubleValue() < ema;
        if (!trendAligned) {
            return noTrade("trendMisaligned(price=" + current + ",ema21=" + String.format("%.2f", ema) + ")");
        }

        // 3b. VWAP alignment — price must be on the right side of VWAP for the direction
        double vwap = calculateVwap(candles);
        if (vwap > 0) {
            boolean vwapAligned = bullish
                    ? current.doubleValue() > vwap
                    : current.doubleValue() < vwap;
            if (!vwapAligned) {
                return noTrade("vwapMisaligned(price=" + current + ",vwap=" + String.format("%.2f", vwap) + ")");
            }
        }

        // 4. Volume confirmation
        long latestVolume = candles.get(size - 1).volume();
        // Bug fix: use prior 5 candles (exclude current) so the average isn't self-contaminated
        double avgVolume = candles.subList(Math.max(0, size - 6), size - 1).stream()
                .mapToLong(Candle::volume).average().orElse(0);
        // Skip volume check when volumeSpikeMode=OI_PROXY (index spots like BANKNIFTY have no volume)
        String volumeMode = underlyingConfigService != null
                ? underlyingConfigService.getVolumeSpikeMode(underlying) : "NORMAL";
        if ("OI_PROXY".equals(volumeMode) || "DISABLED".equals(volumeMode)) {
            // Index spot has no volume — skip volume gate, rely on other confirmations
        } else {
            // Bug fix: zero-average means dead market — block rather than skip
            if (avgVolume <= 0 || latestVolume < avgVolume * MIN_VOLUME_RATIO) {
                return noTrade("lowVolume(latest=" + latestVolume + ",avg=" + String.format("%.0f", avgVolume) + ")");
            }
        }

        // 5. ATR minimum (market must be moving)
        double atr = calculateAtr(candles, ATR_PERIOD);
        double atrPercent = current.doubleValue() > 0 ? (atr / current.doubleValue()) * 100 : 0;
        if (atrPercent < 0.10) {
            return noTrade("atrTooLow(" + String.format("%.3f", atrPercent) + "%)");
        }

        // Graduated confidence score — track each bonus so tuning can see what fired.
        int score = 55;
        int rocBonus = 0;
        int accelBonus = 0;
        int volBonus = 0;
        int atrBonus = 0;
        if (Math.abs(roc) > 0.60) rocBonus = 10;
        else if (Math.abs(roc) > 0.40) rocBonus = 5;
        if (accelerating && Math.abs(roc) > Math.abs(prevRoc) * 1.5) accelBonus = 10; // strong acceleration
        if (avgVolume > 0 && latestVolume > avgVolume * 2.0) volBonus = 10;
        else if (avgVolume > 0 && latestVolume > avgVolume * 1.5) volBonus = 5;
        if (atrPercent > 0.25) atrBonus = 5;
        score = Math.min(90, score + rocBonus + accelBonus + volBonus + atrBonus);

        // Score gate (2026-06-01) — reject signals that earned zero bonuses.
        if (score < MIN_CONFIDENCE_SCORE) {
            return noTrade(String.format(
                    "scoreTooLow(score=%d roc=%.2f%% accel=%s vol=%.1fx atr=%.3f%%)",
                    score, roc, accelerating,
                    avgVolume > 0 ? latestVolume / avgVolume : 0.0, atrPercent));
        }

        // Per-(underlying, direction) cooldown — prevents identical re-entries seconds apart.
        boolean bullishLocal = bullish;
        String cooldownKey = underlying.name() + ":" + (bullishLocal ? "CE" : "PE");
        Instant lastAt = lastEntryAt.get(cooldownKey);
        if (lastAt != null && java.time.Duration.between(lastAt, Instant.now()).compareTo(ENTRY_COOLDOWN) < 0) {
            long secsLeft = ENTRY_COOLDOWN.minus(java.time.Duration.between(lastAt, Instant.now())).toSeconds();
            return noTrade("entryCooldown(remaining=" + secsLeft + "s,direction=" + (bullishLocal ? "CE" : "PE") + ")");
        }

        SignalType signalType = bullish ? SignalType.BUY_CE : SignalType.BUY_PE;
        OptionType optionType = bullish ? OptionType.CE : OptionType.PE;
        String direction = bullish ? "BULLISH" : "BEARISH";
        double volRatio = avgVolume > 0 ? (double) latestVolume / avgVolume : 0.0;

        log.info("[Momentum] Signal: {} ROC={}% prevROC={}% EMA21={} vol={}x ATR={}% score={}",
                signalType, String.format("%.2f", roc), String.format("%.2f", prevRoc),
                String.format("%.2f", ema), String.format("%.1f", volRatio),
                String.format("%.3f", atrPercent), score);

        StrategyDecision signal = new StrategyDecision(
                Instant.now(), underlying, signalType, current,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of(optionType),
                false, Optional.empty(), false,
                BigDecimal.valueOf(score),
                List.of(
                        // Reasons string format is parsed by MomentumCaptureAdapter regex.
                        // Keep ROC=, EMA21=, Volume= tokens; new tokens captured below.
                        "Momentum: ROC=" + String.format("%.2f", roc) + "% prevROC=" + String.format("%.2f", prevRoc)
                                + "% accelerating=" + accelerating
                                + " EMA21=" + String.format("%.2f", ema) + " direction=" + direction,
                        "Volume=" + String.format("%.1f", volRatio) + "x ATR=" + String.format("%.3f", atrPercent) + "%",
                        "ScoreBreakdown: base=55 roc=" + rocBonus + " accel=" + accelBonus
                                + " vol=" + volBonus + " atr=" + atrBonus + " total=" + score
                )
        );
        // Stamp the cooldown — block next identical entry for ENTRY_COOLDOWN.
        lastEntryAt.put(cooldownKey, Instant.now());
        return new StrategyDiagnostics.WithSignal(Optional.of(signal),
                // firstFailedFilter = "" (not null) so PipelineSignalCapture doesn't
                // fall back to a junk reason on successful entries.
                new StrategyDiagnostics("", null, null, direction, ROC_PERIOD,
                        null, null, Math.abs(roc), bullish));
    }

    private double calculateEma(List<Candle> candles, int period) {
        if (candles.size() < period) return 0;
        double multiplier = 2.0 / (period + 1);
        // Bug fix: seed with SMA of first `period` candles, not a single price point
        int startIdx = Math.max(0, candles.size() - period * 2);
        double ema = 0;
        int smaEnd = startIdx + period;
        for (int i = startIdx; i < smaEnd && i < candles.size(); i++) {
            ema += candles.get(i).close().doubleValue();
        }
        ema /= period;
        for (int i = smaEnd; i < candles.size(); i++) {
            ema = (candles.get(i).close().doubleValue() - ema) * multiplier + ema;
        }
        return ema;
    }

    private double calculateAtr(List<Candle> candles, int period) {
        if (candles.size() < period + 1) return 0;
        double sum = 0;
        for (int i = candles.size() - period; i < candles.size(); i++) {
            Candle c = candles.get(i);
            Candle prev = candles.get(i - 1);
            double tr = Math.max(c.high().subtract(c.low()).doubleValue(),
                    Math.max(Math.abs(c.high().subtract(prev.close()).doubleValue()),
                            Math.abs(c.low().subtract(prev.close()).doubleValue())));
            sum += tr;
        }
        return sum / period;
    }

    /** Simple VWAP: sum(close × volume) / sum(volume) over available candles. */
    private double calculateVwap(List<Candle> candles) {
        double sumPV = 0;
        long sumV = 0;
        for (Candle c : candles) {
            long vol = c.volume();
            if (vol <= 0) continue;
            double typicalPrice = (c.high().doubleValue() + c.low().doubleValue() + c.close().doubleValue()) / 3.0;
            sumPV += typicalPrice * vol;
            sumV += vol;
        }
        return sumV > 0 ? sumPV / sumV : 0;
    }

    private static StrategyDiagnostics.WithSignal noTrade(String reason) {
        return new StrategyDiagnostics.WithSignal(Optional.empty(),
                new StrategyDiagnostics(reason, null, null, null, null, null, null, null, null));
    }
}
