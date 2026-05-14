package com.algo.trade.strategy;

import com.algo.trade.domain.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Detects "trapped" option writers at strikes near the current price.
 *
 * Thesis: When a strike near ATM has disproportionately heavy call/put OI AND
 * that OI is still building (not unwinding), writers are exposed. If price
 * approaches that strike, a short-cover squeeze can produce a sharp directional move.
 *
 * Quality gates (vs. the original version):
 *   1. Minimum absolute OI threshold — filters out illiquid noise
 *   2. OI must be building (change > 0) — unwinding writers don't get squeezed
 *   3. Proximity tightened — strike must be within 1-2 strikes of spot
 *   4. Volume confirmation — underlying must show activity
 *   5. Trend alignment — price should be moving toward the trap strike
 *   6. Graduated confidence score based on signal quality
 */
@Component
public class OiShiftTrapStrategy {

    private static final Logger log = LoggerFactory.getLogger(OiShiftTrapStrategy.class);
    private static final MathContext MC = MathContext.DECIMAL64;

    /** Minimum OI imbalance ratio (trapped side / opposite side). */
    private static final double OI_IMBALANCE_RATIO = 1.5;

    /** Maximum distance from spot to trap strike as % of spot price. */
    private static final double PROXIMITY_PERCENT = 0.75;

    /** Minimum absolute OI on the trapped side to be meaningful. */
    private static final long MIN_ABSOLUTE_OI = 50_000;

    /** Minimum OI change (buildup) on the trapped side — writers must be adding, not unwinding. */
    private static final long MIN_OI_CHANGE = 3_000;

    /** Minimum underlying volume in the last candle to confirm market activity. */
    private static final long MIN_UNDERLYING_VOLUME = 1_000;

    private final com.algo.trade.underlying.UnderlyingConfigService underlyingConfigService;

    public OiShiftTrapStrategy(
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            com.algo.trade.underlying.UnderlyingConfigService underlyingConfigService) {
        this.underlyingConfigService = underlyingConfigService;
    }

    public Optional<StrategyDecision> evaluate(OptionChainSnapshot snapshot, BigDecimal spotPrice,
                                                StrategyConfig config, UnderlyingSymbol underlying,
                                                List<Candle> underlyingCandles) {
        if (snapshot == null || snapshot.levels().isEmpty()) {
            return Optional.empty();
        }
        if (spotPrice == null || spotPrice.signum() <= 0) {
            return Optional.empty();
        }

        // Volume confirmation: underlying must be active
        // Skip volume gate when volumeSpikeMode=OI_PROXY (index spots like BANKNIFTY have no volume)
        if (underlyingCandles == null || underlyingCandles.isEmpty()) {
            return Optional.empty();
        }
        String volumeMode = underlyingConfigService != null
                ? underlyingConfigService.getVolumeSpikeMode(underlying) : "NORMAL";
        if (!"OI_PROXY".equals(volumeMode) && !"DISABLED".equals(volumeMode)) {
            long latestVolume = underlyingCandles.getLast().volume();
            if (latestVolume < MIN_UNDERLYING_VOLUME) {
                log.debug("[OiShiftTrap] Skipped: low underlying volume {} < {}", latestVolume, MIN_UNDERLYING_VOLUME);
                return Optional.empty();
            }
        }

        // Trend direction from last 3 candles: +1 bullish, -1 bearish, 0 flat
        int trendDirection = detectShortTermTrend(underlyingCandles);

        List<OptionChainLevel> sorted = snapshot.levels().stream()
                .sorted(Comparator.comparing(OptionChainLevel::strike))
                .toList();

        OptionChainLevel atm = sorted.stream()
                .min(Comparator.comparing(l -> l.strike().subtract(spotPrice).abs()))
                .orElse(null);
        if (atm == null) return Optional.empty();

        double spot = spotPrice.doubleValue();

        // ── CE trap: call-heavy strike just above spot, price moving up toward it ──
        if (trendDirection >= 0) { // only when price is flat or moving up
            for (OptionChainLevel level : sorted) {
                if (level.strike().compareTo(atm.strike()) <= 0) continue;
                if (level.callOpenInterest() == 0) continue;

                double proximity = (level.strike().doubleValue() - spot) / spot * 100;
                if (proximity > PROXIMITY_PERCENT) break; // too far — stop scanning

                long ceOi = level.callOpenInterest();
                long peOi = level.putOpenInterest();
                long ceOiChange = level.callOpenInterestChange();

                // Gate 1: minimum absolute OI
                if (ceOi < MIN_ABSOLUTE_OI) continue;

                // Gate 2: OI must be building (writers adding, not unwinding)
                if (ceOiChange < MIN_OI_CHANGE) continue;

                // Gate 3: imbalance ratio
                double imbalance = ceOi / (double) Math.max(peOi, 1);
                if (imbalance < OI_IMBALANCE_RATIO) continue;

                // Graduated confidence score
                int score = calculateScore(imbalance, ceOi, ceOiChange, proximity, trendDirection);
                if (score < 50) continue; // minimum quality threshold

                List<String> reasons = new ArrayList<>();
                reasons.add(String.format("OI Shift Trap: call-heavy strike=%s OI=%,d change=+%,d imbalance=%.1fx",
                        level.strike(), ceOi, ceOiChange, imbalance));
                reasons.add(String.format("Spot=%s proximity=%.2f%% trend=%s — call writers exposed",
                        spotPrice, proximity, trendDirection > 0 ? "BULLISH" : "FLAT"));

                log.info("[OiShiftTrap] CE signal: strike={} OI={} change=+{} imbalance={}x score={} proximity={}%",
                        level.strike(), ceOi, ceOiChange, String.format("%.1f", imbalance), score,
                        String.format("%.2f", proximity));

                return Optional.of(new StrategyDecision(
                        Instant.now(), underlying, SignalType.BUY_CE, spotPrice,
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.empty(), Optional.of(level.strike()), Optional.of(OptionType.CE),
                        false, Optional.of(BigDecimal.valueOf(imbalance)), false,
                        BigDecimal.valueOf(score), reasons
                ));
            }
        }

        // ── PE trap: put-heavy strike just below spot, price moving down toward it ──
        if (trendDirection <= 0) { // only when price is flat or moving down
            List<OptionChainLevel> belowAtm = sorted.stream()
                    .filter(l -> l.strike().compareTo(atm.strike()) < 0)
                    .sorted(Comparator.comparing(OptionChainLevel::strike, Comparator.reverseOrder()))
                    .toList();

            for (OptionChainLevel level : belowAtm) {
                if (level.putOpenInterest() == 0) continue;

                double proximity = (spot - level.strike().doubleValue()) / spot * 100;
                if (proximity > PROXIMITY_PERCENT) break;

                long peOi = level.putOpenInterest();
                long ceOi = level.callOpenInterest();
                long peOiChange = level.putOpenInterestChange();

                // Gate 1: minimum absolute OI
                if (peOi < MIN_ABSOLUTE_OI) continue;

                // Gate 2: OI must be building
                if (peOiChange < MIN_OI_CHANGE) continue;

                // Gate 3: imbalance ratio
                double imbalance = peOi / (double) Math.max(ceOi, 1);
                if (imbalance < OI_IMBALANCE_RATIO) continue;

                // Graduated confidence score
                int score = calculateScore(imbalance, peOi, peOiChange, proximity, Math.abs(trendDirection));
                if (score < 50) continue;

                List<String> reasons = new ArrayList<>();
                reasons.add(String.format("OI Shift Trap: put-heavy strike=%s OI=%,d change=+%,d imbalance=%.1fx",
                        level.strike(), peOi, peOiChange, imbalance));
                reasons.add(String.format("Spot=%s proximity=%.2f%% trend=%s — put writers exposed",
                        spotPrice, proximity, trendDirection < 0 ? "BEARISH" : "FLAT"));

                log.info("[OiShiftTrap] PE signal: strike={} OI={} change=+{} imbalance={}x score={} proximity={}%",
                        level.strike(), peOi, peOiChange, String.format("%.1f", imbalance), score,
                        String.format("%.2f", proximity));

                return Optional.of(new StrategyDecision(
                        Instant.now(), underlying, SignalType.BUY_PE, spotPrice,
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.empty(), Optional.of(level.strike()), Optional.of(OptionType.PE),
                        false, Optional.of(BigDecimal.valueOf(imbalance)), false,
                        BigDecimal.valueOf(score), reasons
                ));
            }
        }

        return Optional.empty();
    }

    /** Backward-compatible overload for callers that don't pass candles. */
    public Optional<StrategyDecision> evaluate(OptionChainSnapshot snapshot, BigDecimal spotPrice,
                                                StrategyConfig config, UnderlyingSymbol underlying) {
        return evaluate(snapshot, spotPrice, config, underlying, null);
    }

    /**
     * Graduated confidence score based on signal quality.
     *   Base: 50
     *   +10 if imbalance > 4x (very heavy)
     *   +10 if absolute OI > 200K (institutional size)
     *   +5  if OI change > 50K (aggressive buildup)
     *   +10 if proximity < 0.15% (very close to strike)
     *   +5  if trend aligns (price moving toward trap)
     *   Max: 90
     */
    private int calculateScore(double imbalance, long absoluteOi, long oiChange,
                                double proximity, int trendStrength) {
        int score = 50;
        if (imbalance > 4.0) score += 10;
        if (absoluteOi > 200_000) score += 10;
        if (oiChange > 50_000) score += 5;
        if (proximity < 0.15) score += 10;
        if (trendStrength > 0) score += 5;
        return Math.min(90, score);
    }

    /**
     * Short-term trend from last 3 candles.
     * @return +1 bullish (higher closes), -1 bearish (lower closes), 0 flat/mixed
     */
    private int detectShortTermTrend(List<Candle> candles) {
        if (candles.size() < 3) return 0;
        List<Candle> recent = candles.subList(candles.size() - 3, candles.size());
        int up = 0, down = 0;
        for (int i = 1; i < recent.size(); i++) {
            int cmp = recent.get(i).close().compareTo(recent.get(i - 1).close());
            if (cmp > 0) up++;
            else if (cmp < 0) down++;
        }
        if (up >= 2) return 1;
        if (down >= 2) return -1;
        return 0;
    }
}
