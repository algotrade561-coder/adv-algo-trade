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
 * Near expiry (0–1 days), fades a sharp intraday spike — buys against the move
 * expecting a pin to the nearest key strike or reversal pullback.
 *
 * Quality gates:
 *   1. Must be near expiry (0-1 days)
 *   2. Spike must be significant (0.70%+ over 5 candles, raised from 0.50%)
 *   3. Spike must be exhausting — volume declining on the last 2 candles
 *   4. Latest candle must show hesitation (smaller body or opposite direction)
 *   5. Time guard: don't fade after 14:30 (too close to close, gamma dominates)
 *   6. Graduated confidence score
 *
 * Note: This strategy is the opposite of ExpiryGamma. ExpiryGamma rides momentum
 * (13:00-14:30), ExpiryReversal fades exhausted spikes. They should not conflict
 * because ExpiryReversal requires exhaustion signals that ExpiryGamma's acceleration
 * check would reject.
 */
@Component
public class ExpiryReversalStrategy implements TimeBoundedStrategy {

    private static final Logger log = LoggerFactory.getLogger(ExpiryReversalStrategy.class);
    private static final int NEAR_EXPIRY_DAYS = 1;
    private static final int LOOK_BACK_CANDLES = 5;
    /** Minimum spike % — lowered from 0.70 to 0.50 for weekly expiry tradability. */
    private static final double MIN_SPIKE_PERCENT = 0.50;
    /** Don't fade spikes after this time — too close to close, gamma dominates. */
    private static final LocalTime CUTOFF_TIME = LocalTime.of(14, 30);

    private final ExpiryCalendar expiryCalendar;

    public ExpiryReversalStrategy(ExpiryCalendar expiryCalendar) {
        this.expiryCalendar = expiryCalendar;
    }

    @Override
    public boolean validNow(LocalTime marketTime) {
        if (marketTime == null) return true;
        return !marketTime.isAfter(CUTOFF_TIME);
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

        // Don't fade after 14:30 — gamma dominates, reversals are unreliable
        if (marketTime.isAfter(CUTOFF_TIME)) {
            return noTrade("afterCutoff(" + marketTime + ")");
        }

        if (candles1m.size() < LOOK_BACK_CANDLES + 1) {
            return noTrade("notEnoughCandles");
        }

        int size = candles1m.size();
        Candle latest = candles1m.get(size - 1);
        BigDecimal current = latest.close();
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

        boolean spikeUp = spikePct > 0;

        // ── Gate: Volume exhaustion — last 2 candles should have declining volume ──
        boolean volumeExhausted = false;
        if (size >= 3) {
            long vol1 = candles1m.get(size - 3).volume();
            long vol2 = candles1m.get(size - 2).volume();
            long vol3 = latest.volume();
            volumeExhausted = vol3 < vol2 && vol2 < vol1;
        }
        if (!volumeExhausted) {
            return noTrade("noVolumeExhaustion(spike=" + String.format("%.2f", spikePct) + "%)");
        }

        // ── Gate: Hesitation candle — latest candle body should be smaller or opposite ──
        double latestBodyPct = latest.open().signum() > 0
                ? Math.abs(latest.close().subtract(latest.open()).doubleValue() / latest.open().doubleValue() * 100)
                : 0;
        Candle prevCandle = candles1m.get(size - 2);
        double prevBodyPct = prevCandle.open().signum() > 0
                ? Math.abs(prevCandle.close().subtract(prevCandle.open()).doubleValue() / prevCandle.open().doubleValue() * 100)
                : 0;

        boolean hesitation;
        if (spikeUp) {
            // Spike up: latest candle should be bearish or have a smaller bullish body
            boolean latestBearish = latest.close().compareTo(latest.open()) < 0;
            hesitation = latestBearish || latestBodyPct < prevBodyPct * 0.5;
        } else {
            // Spike down: latest candle should be bullish or have a smaller bearish body
            boolean latestBullish = latest.close().compareTo(latest.open()) > 0;
            hesitation = latestBullish || latestBodyPct < prevBodyPct * 0.5;
        }
        if (!hesitation) {
            return noTrade("noHesitation(spike=" + String.format("%.2f", spikePct) + "%)");
        }

        // ── Graduated confidence score ──
        int score = 55;
        if (Math.abs(spikePct) > 1.0) score += 10;   // very sharp spike — stronger reversal expected
        if (volumeExhausted) score += 5;
        if (hesitation) score += 5;
        long daysToExpiry = expiryCalendar.daysToExpiry(idx);
        if (daysToExpiry == 0) score += 5;             // expiry day — pin effect strongest
        // Morning spikes reverse more reliably than afternoon
        if (marketTime.isBefore(LocalTime.of(12, 0))) score += 5;
        score = Math.min(90, score);

        // Fade the spike: spike up → BUY_PE; spike down → BUY_CE
        SignalType signalType = spikeUp ? SignalType.BUY_PE : SignalType.BUY_CE;
        OptionType optionType = spikeUp ? OptionType.PE : OptionType.CE;
        String direction = spikeUp ? "FADING_SPIKE_UP" : "FADING_SPIKE_DOWN";

        log.info("[ExpiryReversal] Signal: {} spike={}% volExhausted={} hesitation={} score={}",
                signalType, String.format("%.2f", spikePct), volumeExhausted, hesitation, score);

        StrategyDecision signal = new StrategyDecision(
                Instant.now(), underlying, signalType, current,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of(optionType),
                false, Optional.empty(), false,
                BigDecimal.valueOf(score),
                List.of(
                        "Expiry Reversal: spike=" + String.format("%.2f", spikePct) + "% volExhausted=" + volumeExhausted
                                + " hesitation=" + hesitation + " direction=" + direction,
                        "daysToExpiry=" + daysToExpiry + " — fade expected near key strike"
                )
        );
        return new StrategyDiagnostics.WithSignal(Optional.of(signal),
                new StrategyDiagnostics(null, null, null, direction, LOOK_BACK_CANDLES,
                        null, null, Math.abs(spikePct), Math.abs(spikePct) > MIN_SPIKE_PERCENT));
    }

    private static StrategyDiagnostics.WithSignal noTrade(String reason) {
        return new StrategyDiagnostics.WithSignal(Optional.empty(),
                new StrategyDiagnostics(reason, null, null, null, null, null, null, null, null));
    }
}
