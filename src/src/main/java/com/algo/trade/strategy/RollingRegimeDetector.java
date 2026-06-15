package com.algo.trade.strategy;

import com.algo.trade.domain.Candle;
import com.algo.trade.domain.IndexType;
import com.algo.trade.marketdata.LiveCandleBuilder;
import com.algo.trade.risk.MarketGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rolling Regime Detector — continuously classifies market into regimes
 * and provides regime-specific parameter overrides.
 *
 * Regimes (detected from 15-min candles + VIX + ATR):
 *   TRENDING_UP    — strong directional move up
 *   TRENDING_DOWN  — strong directional move down
 *   RANGE_BOUND    — narrow range, low ATR, mean-reverting
 *   HIGH_VOL       — VIX > 18, wide swings
 *   LOW_VOL        — VIX < 12, compressed, slow decay
 */
@Component
public class RollingRegimeDetector {

    private static final Logger log = LoggerFactory.getLogger(RollingRegimeDetector.class);

    private final LiveCandleBuilder candleBuilder;
    private final MarketGuard marketGuard;

    public enum MarketRegime { TRENDING_UP, TRENDING_DOWN, RANGE_BOUND, HIGH_VOL, LOW_VOL }

    public record RegimeState(
        MarketRegime regime, double trendStrength, double volatility, double vix, long detectedAt
    ) {}

    private final Map<IndexType, RegimeState> regimeMap = new ConcurrentHashMap<>();

    private static final Map<MarketRegime, Map<String, Double>> PARAM_OVERRIDES = Map.of(
        MarketRegime.TRENDING_UP, Map.of(
            "trailPercent", 1.4, "slPercent", 1.2, "emaFast", 0.8, "signalThreshold", 0.7, "lotMultiplier", 1.2),
        MarketRegime.TRENDING_DOWN, Map.of(
            "trailPercent", 1.4, "slPercent", 1.2, "emaFast", 0.8, "signalThreshold", 0.7, "lotMultiplier", 1.2),
        MarketRegime.RANGE_BOUND, Map.of(
            "trailPercent", 0.7, "slPercent", 0.8, "emaFast", 1.2, "signalThreshold", 1.5, "lotMultiplier", 0.8),
        MarketRegime.HIGH_VOL, Map.of(
            "trailPercent", 1.5, "slPercent", 1.5, "emaFast", 1.0, "signalThreshold", 0.8, "lotMultiplier", 0.6),
        MarketRegime.LOW_VOL, Map.of(
            "trailPercent", 0.6, "slPercent", 0.7, "emaFast", 1.0, "signalThreshold", 1.8, "lotMultiplier", 1.0)
    );

    public RollingRegimeDetector(LiveCandleBuilder candleBuilder, MarketGuard marketGuard) {
        this.candleBuilder = candleBuilder;
        this.marketGuard = marketGuard;
    }

    @Scheduled(fixedDelay = 120_000)
    public void detectRegimes() {
        LocalTime now = LocalTime.now();
        if (now.isBefore(LocalTime.of(9, 30)) || now.isAfter(LocalTime.of(15, 25))) return;

        for (IndexType idx : new IndexType[]{IndexType.NIFTY, IndexType.BANKNIFTY}) {
            try {
                RegimeState state = classifyRegime(idx);
                RegimeState prev = regimeMap.get(idx);
                regimeMap.put(idx, state);

                if (prev != null && prev.regime != state.regime) {
                    log.info("[Regime] {} changed: {} → {} (trend={} vol={} VIX={})",
                            idx, prev.regime, state.regime,
                            String.format("%.2f", state.trendStrength),
                            String.format("%.3f", state.volatility),
                            String.format("%.1f", state.vix));
                }
            } catch (Exception e) {
                log.debug("[Regime] Detection failed for {}: {}", idx, e.getMessage());
            }
        }
    }

    private RegimeState classifyRegime(IndexType idx) {
        long token = idx == IndexType.BANKNIFTY ? 260105L : 256265L;
        List<Candle> candles15m = candleBuilder.getHistory(token, com.algo.trade.domain.Timeframe.FIFTEEN_MINUTE);
        List<Candle> candles5m = candleBuilder.getHistory(token, com.algo.trade.domain.Timeframe.FIVE_MINUTE);
        double vix = marketGuard.getCurrentVix();

        double trendStrength = 0;
        if (candles5m.size() >= 13) {
            double ema5 = calculateEMA(candles5m, 5);
            double ema13 = calculateEMA(candles5m, 13);
            if (ema13 > 0) trendStrength = Math.abs((ema5 - ema13) / ema13) * 100;
        }

        double volatility = 0;
        if (candles15m.size() >= 5) {
            double atrSum = 0;
            for (int i = candles15m.size() - 4; i < candles15m.size(); i++) {
                Candle c = candles15m.get(i);
                atrSum += (c.high().doubleValue() - c.low().doubleValue());
            }
            double atr = atrSum / 4;
            double price = candles15m.get(candles15m.size() - 1).close().doubleValue();
            if (price > 0) volatility = (atr / price) * 100;
        }

        boolean trendUp = false, trendDown = false;
        if (candles5m.size() >= 13) {
            double ema5 = calculateEMA(candles5m, 5);
            double ema13 = calculateEMA(candles5m, 13);
            trendUp = ema5 > ema13;
            trendDown = ema5 < ema13;
        }

        MarketRegime regime;
        if (vix > 18 || volatility > 0.5) regime = MarketRegime.HIGH_VOL;
        else if (vix < 12 && volatility < 0.15) regime = MarketRegime.LOW_VOL;
        else if (trendStrength > 0.08 && trendUp) regime = MarketRegime.TRENDING_UP;
        else if (trendStrength > 0.08 && trendDown) regime = MarketRegime.TRENDING_DOWN;
        else regime = MarketRegime.RANGE_BOUND;

        return new RegimeState(regime, trendStrength, volatility, vix, System.currentTimeMillis());
    }

    public MarketRegime getRegime(IndexType indexType) {
        RegimeState state = regimeMap.get(indexType);
        return state != null ? state.regime : MarketRegime.RANGE_BOUND;
    }

    public double getParamMultiplier(IndexType indexType, String param) {
        MarketRegime regime = getRegime(indexType);
        Map<String, Double> overrides = PARAM_OVERRIDES.get(regime);
        if (overrides == null) return 1.0;
        return overrides.getOrDefault(param, 1.0);
    }

    public boolean isTrending(IndexType indexType) {
        MarketRegime regime = getRegime(indexType);
        return regime == MarketRegime.TRENDING_UP || regime == MarketRegime.TRENDING_DOWN;
    }

    public Map<String, Object> getRegimeStatus() {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        regimeMap.forEach((idx, state) -> {
            Map<String, Object> entry = new java.util.LinkedHashMap<>();
            entry.put("regime", state.regime.name());
            entry.put("trendStrength", Math.round(state.trendStrength * 1000) / 1000.0);
            entry.put("volatility", Math.round(state.volatility * 1000) / 1000.0);
            entry.put("vix", state.vix);
            entry.put("params", PARAM_OVERRIDES.get(state.regime));
            result.put(idx.name(), entry);
        });
        return result;
    }

    private double calculateEMA(List<Candle> candles, int period) {
        if (candles.size() < period) return 0;
        double multiplier = 2.0 / (period + 1);
        double ema = candles.get(0).close().doubleValue();
        for (int i = 1; i < candles.size(); i++) {
            ema = (candles.get(i).close().doubleValue() - ema) * multiplier + ema;
        }
        return ema;
    }
}
