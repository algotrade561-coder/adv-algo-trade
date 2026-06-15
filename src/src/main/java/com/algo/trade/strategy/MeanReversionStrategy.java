package com.algo.trade.strategy;

import com.algo.trade.domain.*;
import com.algo.trade.indicator.VwapIndicator;
import com.algo.trade.config.TradingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * Mean-Reversion Strategy for midday sessions (11:00–13:30 IST).
 *
 * Instead of waiting for breakout confirmation (which rarely comes during
 * midday chop), this strategy enters when price stretches too far from its
 * session VWAP and is likely to snap back.
 *
 * Entry logic:
 *   - Price deviates beyond threshold from VWAP (default 0.25%)
 *   - OI imbalance confirms institutions are fading the move
 *   - IV rank is acceptable (not overpaying for premium)
 *   - Latest candle shows reversal body (exhaustion starting)
 *
 * Characteristics vs. Directional Buy:
 *   - Does NOT require breakout or volume spike
 *   - Enters AGAINST the recent move (fade)
 *   - Tighter stops (8%) + quicker targets (12%)
 *   - Higher win rate, smaller per-trade gains
 *   - Only active during midday when other strategies are idle
 *
 * Integration with OI Momentum:
 *   - Uses the same LiveInstrumentCache OI data
 *   - Evaluates PCR shift direction for confirmation
 *   - Complementary: OI Momentum catches momentum, this catches mean-reversion
 */
@Component
public class MeanReversionStrategy implements TimeBoundedStrategy {

    private static final Logger log = LoggerFactory.getLogger(MeanReversionStrategy.class);
    private static final MathContext MC = MathContext.DECIMAL64;
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final VwapIndicator vwapIndicator;
    private final TradingProperties properties;

    @Value("${strategy.mean-reversion.deviation-percent:0.25}")
    private double deviationThresholdPercent;

    @Value("${strategy.mean-reversion.max-iv-rank:60}")
    private double maxIvRank;

    @Value("${strategy.mean-reversion.min-confidence:55}")
    private int minConfidence;

    @Value("${strategy.mean-reversion.session-start:11:00}")
    private String sessionStart;

    @Value("${strategy.mean-reversion.session-end:13:30}")
    private String sessionEnd;

    @Value("${strategy.mean-reversion.min-candles:10}")
    private int minCandles;

    public MeanReversionStrategy(VwapIndicator vwapIndicator, TradingProperties properties) {
        this.vwapIndicator = vwapIndicator;
        this.properties = properties;
    }

    // ── TimeBoundedStrategy interface ─────────────────────────────────────

    @Override
    public LocalTime entryStartTime() {
        return LocalTime.parse(sessionStart);
    }

    @Override
    public LocalTime entryCutoffTime() {
        return LocalTime.parse(sessionEnd);
    }

    // ── Evaluation ────────────────────────────────────────────────────────

    public Optional<StrategyDecision> evaluate(List<Candle> candles, double ivRank,
                                                StrategyConfig config, UnderlyingSymbol underlying,
                                                BigDecimal oiImbalance, LocalTime marketTime) {
        return evaluateWithDiagnostics(candles, ivRank, config, underlying, oiImbalance, marketTime).signal();
    }

    public StrategyDiagnostics.WithSignal evaluateWithDiagnostics(
            List<Candle> candles, double ivRank, StrategyConfig config,
            UnderlyingSymbol underlying, BigDecimal oiImbalance, LocalTime marketTime) {

        // ── Gate 1: Session window ──
        if (marketTime.isBefore(entryStartTime()) || marketTime.isAfter(entryCutoffTime())) {
            return noTrade("outsideSession(" + marketTime + ")");
        }

        // ── Gate 2: Minimum candle data ──
        if (candles.size() < minCandles) {
            return noTrade("notEnoughCandles(" + candles.size() + "<" + minCandles + ")");
        }

        // ── Gate 3: IV rank check — don't buy expensive options ──
        double effectiveMaxIv = config.getMaxIvRankForBuying() != null
                ? Math.min(config.getMaxIvRankForBuying().doubleValue(), maxIvRank)
                : maxIvRank;
        if (ivRank > effectiveMaxIv) {
            return noTrade("ivRankTooHigh(" + String.format("%.1f", ivRank) + ">" + effectiveMaxIv + ")");
        }

        // ── Compute VWAP and deviation ──
        BigDecimal vwap = vwapIndicator.calculateSessionAnchored(candles, properties.timezone());
        if (vwap.signum() <= 0) {
            return noTrade("vwapUnavailable");
        }

        BigDecimal spotPrice = candles.getLast().close();
        double deviationPct = spotPrice.subtract(vwap).doubleValue() / vwap.doubleValue() * 100.0;
        double absDeviation = Math.abs(deviationPct);

        // ── Gate 4: Deviation must exceed threshold ──
        double effectiveThreshold = deviationThresholdPercent;
        if (absDeviation < effectiveThreshold) {
            return noTrade("deviationInsufficient(" + String.format("%.3f", absDeviation)
                    + "%<" + effectiveThreshold + "%)");
        }

        // ── Determine direction: price below VWAP = buy CE (expect bounce), above = buy PE (expect fade) ──
        boolean priceBelow = deviationPct < 0; // price below VWAP → expect reversion UP → buy CE
        SignalType signalType = priceBelow ? SignalType.BUY_CE : SignalType.BUY_PE;
        OptionType optionType = priceBelow ? OptionType.CE : OptionType.PE;

        // ── Gate 5: OI imbalance must confirm reversion direction ──
        // For CE (bullish reversion): high put-call imbalance = puts are heavy = support below = expect bounce
        // For PE (bearish reversion): low put-call imbalance = calls are heavy = resistance above = expect fade
        boolean oiConfirms = false;
        if (oiImbalance != null && oiImbalance.signum() > 0) {
            if (priceBelow) {
                // Buying CE: need high PCR (put writers supporting) → imbalance > 1.1
                oiConfirms = oiImbalance.doubleValue() > 1.1;
            } else {
                // Buying PE: need low PCR (call writers resisting) → imbalance < 0.9
                oiConfirms = oiImbalance.doubleValue() < 0.9;
            }
        }

        // ── Gate 6: Reversal candle — latest candle body must oppose the stretch ──
        Candle latest = candles.getLast();
        boolean reversalCandle;
        if (priceBelow) {
            // Price below VWAP: latest candle should be bullish (close > open) — bounce starting
            reversalCandle = latest.close().compareTo(latest.open()) > 0;
        } else {
            // Price above VWAP: latest candle should be bearish (close < open) — pullback starting
            reversalCandle = latest.close().compareTo(latest.open()) < 0;
        }

        // ── Graduated confidence score ──
        int score = 30; // base
        // Deviation strength: larger deviation = stronger reversion signal
        if (absDeviation >= 0.40) score += 20;
        else if (absDeviation >= 0.30) score += 15;
        else score += 10;
        // OI confirmation
        if (oiConfirms) score += 20;
        // Reversal candle
        if (reversalCandle) score += 15;
        // IV is cheap (more room for premium to move)
        if (ivRank < 30) score += 10;
        else if (ivRank < 50) score += 5;
        // Recent volume declining (exhaustion of the stretch)
        if (candles.size() >= 4) {
            boolean volumeDeclining = candles.get(candles.size() - 1).volume()
                    < candles.get(candles.size() - 3).volume();
            if (volumeDeclining) score += 5;
        }

        score = Math.min(95, score);

        // ── Gate 7: Minimum confidence threshold ──
        if (score < minConfidence) {
            return noTrade("scoreTooLow(" + score + "<" + minConfidence
                    + " dev=" + String.format("%.3f", absDeviation) + "%"
                    + " oi=" + oiConfirms + " reversal=" + reversalCandle + ")");
        }

        // ── Signal ──
        String direction = priceBelow ? "BULLISH_REVERSION" : "BEARISH_REVERSION";
        log.info("[MeanReversion] Signal: {} dev={}% vwap={} spot={} oiConfirm={} reversal={} score={}",
                signalType, String.format("%.3f", deviationPct), vwap.setScale(2, java.math.RoundingMode.HALF_UP),
                spotPrice, oiConfirms, reversalCandle, score);

        StrategyDecision signal = new StrategyDecision(
                Instant.now(), underlying, signalType, spotPrice,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of(optionType),
                false, Optional.of(oiImbalance != null ? oiImbalance : BigDecimal.ZERO),
                false, BigDecimal.valueOf(score),
                List.of(
                        "Mean Reversion: spot=" + spotPrice + " vwap=" + vwap.setScale(2, java.math.RoundingMode.HALF_UP)
                                + " deviation=" + String.format("%.3f", deviationPct) + "%"
                                + " (threshold=" + effectiveThreshold + "%)",
                        "OI imbalance=" + (oiImbalance != null ? oiImbalance.setScale(3, java.math.RoundingMode.HALF_UP) : "N/A")
                                + " confirms=" + oiConfirms,
                        "Reversal candle=" + reversalCandle + " IV rank=" + String.format("%.1f", ivRank),
                        "Signal: " + signalType + " — expecting " + direction.toLowerCase().replace("_", " ")
                                + " (score=" + score + "%)"
                )
        );
        return new StrategyDiagnostics.WithSignal(Optional.of(signal),
                new StrategyDiagnostics("MEAN_REVERSION:" + direction, null, null, direction,
                        null, null, null, null, null));
    }

    private static StrategyDiagnostics.WithSignal noTrade(String reason) {
        return new StrategyDiagnostics.WithSignal(Optional.empty(),
                new StrategyDiagnostics(reason, null, null, null, null, null, null, null, null));
    }
}
