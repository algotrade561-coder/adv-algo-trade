package com.algo.trade.strategy;

import com.algo.trade.domain.*;
import com.algo.trade.marketdata.LiveInstrumentCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Buys CE/PE in the first 30 minutes when the opening shows a genuine gap
 * with strong volume and directional follow-through.
 *
 * Quality gates:
 *   1. Actual gap: open vs previous day close (not just first candle body)
 *   2. Strong body on first candle (min 0.40%)
 *   3. Second candle confirms direction
 *   4. Volume on first candle must be above average (gap on thin volume = fake)
 *   5. Graduated confidence score
 */
@Component
public class GapAndGoStrategy {

    private static final Logger log = LoggerFactory.getLogger(GapAndGoStrategy.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime ENTRY_START = LocalTime.of(9, 16);
    private static final LocalTime ENTRY_CUTOFF = LocalTime.of(9, 45);
    private static final double MIN_BODY_PERCENT = 0.40;
    /** Minimum gap from previous close to today's open as % of previous close. */
    private static final double MIN_GAP_PERCENT = 0.15;
    /** First candle volume must be at least this multiple of average volume. */
    private static final double MIN_VOLUME_RATIO = 1.3;

    private final LiveInstrumentCache liveInstrumentCache;

    public GapAndGoStrategy(LiveInstrumentCache liveInstrumentCache) {
        this.liveInstrumentCache = liveInstrumentCache;
    }

    public Optional<StrategyDecision> evaluate(List<Candle> candles5m, LocalTime marketTime,
                                                StrategyConfig config, UnderlyingSymbol underlying) {
        return evaluateWithDiagnostics(candles5m, marketTime, config, underlying).signal();
    }

    public StrategyDiagnostics.WithSignal evaluateWithDiagnostics(List<Candle> candles5m, LocalTime marketTime,
                                                                    StrategyConfig config, UnderlyingSymbol underlying) {
        if (marketTime.isBefore(ENTRY_START) || marketTime.isAfter(ENTRY_CUTOFF)) {
            return noTrade("timeWindow");
        }
        if (candles5m.size() < 5) {
            return noTrade("notEnoughCandles(" + candles5m.size() + ")");
        }

        LocalDate today = LocalDate.now(IST);
        List<Candle> sessionCandles = candles5m.stream()
                .filter(c -> c.timestamp().atZone(IST).toLocalDate().equals(today))
                .sorted(Comparator.comparing(Candle::timestamp))
                .toList();

        if (sessionCandles.size() < 2) {
            return noTrade("notEnoughSessionCandles");
        }

        // Previous day candles for gap calculation
        List<Candle> prevDayCandles = candles5m.stream()
                .filter(c -> c.timestamp().atZone(IST).toLocalDate().isBefore(today))
                .sorted(Comparator.comparing(Candle::timestamp))
                .toList();

        Candle first = sessionCandles.get(0);
        Candle second = sessionCandles.get(1);
        BigDecimal spotPrice = candles5m.getLast().close();

        if (first.open().signum() == 0) {
            return noTrade("zeroOpenPrice");
        }

        // ── Gate 1: Actual gap from previous close ──
        BigDecimal prevClose;
        if (!prevDayCandles.isEmpty()) {
            prevClose = prevDayCandles.getLast().close();
        } else {
            // Use stored previous day close from LiveInstrumentCache (seeded at startup)
            IndexType idx = IndexType.fromName(underlying.name());
            double storedClose = liveInstrumentCache.getPreviousDayClose(idx);
            prevClose = storedClose > 0 ? BigDecimal.valueOf(storedClose) : first.open();
        }
        double gapPct = 0;
        if (prevClose.signum() > 0) {
            gapPct = first.open().subtract(prevClose)
                    .divide(prevClose, 6, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100)).doubleValue();
        }
        boolean hasGap = Math.abs(gapPct) >= MIN_GAP_PERCENT;

        // ── Gate 2: Strong first candle body ──
        BigDecimal body = first.close().subtract(first.open());
        double bodyPct = body.divide(first.open(), 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100)).doubleValue();

        if (Math.abs(bodyPct) < MIN_BODY_PERCENT) {
            return noTrade("bodyTooSmall(" + String.format("%.2f", bodyPct) + "%)");
        }

        // ── Gate 3: Gap and body must agree on direction ──
        boolean gapUp = gapPct > 0;
        boolean bodyUp = bodyPct > 0;
        // Bug fix: check direction even for sub-threshold gaps — a -0.10% gap is still gap-down.
        // Original only checked when hasGap=true (>= 0.15%), allowing CE signals on small gap-down days.
        if (gapPct != 0 && gapUp != bodyUp) {
            return noTrade("gapBodyConflict(gap=" + String.format("%.2f", gapPct)
                    + "% body=" + String.format("%.2f", bodyPct) + "%)");
        }
        boolean bullish = bodyUp;

        // ── Gate 4: Second candle confirms direction ──
        boolean secondConfirms = bullish
                ? second.close().compareTo(second.open()) > 0
                : second.close().compareTo(second.open()) < 0;

        if (!secondConfirms) {
            return noTrade("secondCandleNoConfirm");
        }

        // ── Gate 5: Volume confirmation ──
        long firstVolume = first.volume();
        double avgVolume = prevDayCandles.isEmpty() ? firstVolume
                : prevDayCandles.stream().mapToLong(Candle::volume).average().orElse(firstVolume);
        double volumeRatio = avgVolume > 0 ? firstVolume / avgVolume : 0;
        boolean volumeConfirmed = volumeRatio >= MIN_VOLUME_RATIO;

        if (!volumeConfirmed) {
            return noTrade("lowVolume(ratio=" + String.format("%.1f", volumeRatio) + "x)");
        }

        // ── Graduated confidence score ──
        int score = 55;
        if (hasGap && Math.abs(gapPct) > 0.30) score += 10;  // strong gap
        else if (hasGap) score += 5;                           // moderate gap
        if (Math.abs(bodyPct) > 0.80) score += 10;            // very strong body
        if (volumeRatio > 2.0) score += 10;                   // exceptional volume
        else if (volumeRatio > 1.5) score += 5;               // good volume
        if (second.volume() > first.volume() * 0.7) score += 5; // second candle has decent volume too
        score = Math.min(90, score);

        SignalType signalType = bullish ? SignalType.BUY_CE : SignalType.BUY_PE;
        OptionType optionType = bullish ? OptionType.CE : OptionType.PE;
        String direction = bullish ? "BULLISH" : "BEARISH";

        log.info("[GapAndGo] Signal: {} gap={}% body={}% volRatio={}x score={}",
                signalType, String.format("%.2f", gapPct), String.format("%.2f", bodyPct),
                String.format("%.1f", volumeRatio), score);

        StrategyDecision signal = new StrategyDecision(
                Instant.now(), underlying, signalType, spotPrice,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of(optionType),
                false, Optional.empty(), false,
                BigDecimal.valueOf(score),
                List.of(
                        "Gap & Go: gap=" + String.format("%.2f", gapPct) + "% body=" + String.format("%.2f", bodyPct)
                                + "% volume=" + String.format("%.1f", volumeRatio) + "x direction=" + direction,
                        "firstCandle: open=" + first.open() + " close=" + first.close() + " vol=" + firstVolume
                )
        );
        return new StrategyDiagnostics.WithSignal(Optional.of(signal),
                new StrategyDiagnostics(null, null, null, direction, null,
                        null, null, Math.abs(bodyPct), bullish));
    }

    private static StrategyDiagnostics.WithSignal noTrade(String reason) {
        return new StrategyDiagnostics.WithSignal(Optional.empty(),
                new StrategyDiagnostics(reason, null, null, null, null, null, null, null, null));
    }
}
