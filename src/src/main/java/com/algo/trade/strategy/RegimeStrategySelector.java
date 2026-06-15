package com.algo.trade.strategy;

import com.algo.trade.domain.IndexType;
import com.algo.trade.notification.TelegramAlertService;
import com.algo.trade.risk.MarketGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Regime-based auto-strategy selector.
 *
 * Monitors volatility regime and logs recommended strategy changes.
 * Caches regime per index to avoid redundant detectRegime() calls.
 */
@Component
public class RegimeStrategySelector {

    private static final Logger log = LoggerFactory.getLogger(RegimeStrategySelector.class);

    private final RollingRegimeDetector regimeDetector;
    private final MarketGuard marketGuard;
    private final TelegramAlertService alertService;

    @Value("${regime-selector.check-interval-ms:60000}") private long checkIntervalMs;

    private final Map<IndexType, RollingRegimeDetector.MarketRegime> lastRegime = new ConcurrentHashMap<>();
    private final Map<IndexType, Long> regimeCacheTime = new ConcurrentHashMap<>();
    private static final long REGIME_CACHE_TTL_MS = 10_000;

    public RegimeStrategySelector(RollingRegimeDetector regimeDetector,
                                  MarketGuard marketGuard,
                                  TelegramAlertService alertService) {
        this.regimeDetector = regimeDetector;
        this.marketGuard = marketGuard;
        this.alertService = alertService;
    }

    @Scheduled(fixedDelayString = "${regime-selector.check-interval-ms:60000}")
    public void checkRegimeChanges() {
        LocalTime now = LocalTime.now();
        if (now.isBefore(LocalTime.of(9, 16)) || now.isAfter(LocalTime.of(15, 25))) return;

        for (IndexType idx : new IndexType[]{IndexType.NIFTY, IndexType.BANKNIFTY}) {
            try {
                RollingRegimeDetector.MarketRegime current = getCachedRegime(idx);
                RollingRegimeDetector.MarketRegime previous = lastRegime.get(idx);

                if (previous != null && previous != current) {
                    String recommendation = getRecommendationForRegime(current);
                    String msg = String.format("[RegimeSwitch] %s: %s → %s | Recommended: %s",
                            idx, previous, current, recommendation);
                    log.warn(msg);
                    alertService.systemAlert(msg);
                }
                lastRegime.put(idx, current);
            } catch (Exception e) {
                log.debug("[RegimeSelector] Error for {}: {}", idx, e.getMessage());
            }
        }
    }

    public RollingRegimeDetector.MarketRegime getCachedRegime(IndexType indexType) {
        long now = System.currentTimeMillis();
        Long lastCheck = regimeCacheTime.get(indexType);
        RollingRegimeDetector.MarketRegime cached = lastRegime.get(indexType);

        if (cached != null && lastCheck != null && (now - lastCheck) < REGIME_CACHE_TTL_MS) {
            return cached;
        }

        RollingRegimeDetector.MarketRegime fresh = regimeDetector.getRegime(indexType);
        lastRegime.put(indexType, fresh);
        regimeCacheTime.put(indexType, now);
        return fresh;
    }

    private String getRecommendationForRegime(RollingRegimeDetector.MarketRegime regime) {
        return switch (regime) {
            case LOW_VOL -> "Iron Condor, Butterfly, Calendar Spread — range-bound, sell premium";
            case RANGE_BOUND -> "Bull Call/Bear Put Spreads — directional with defined risk";
            case HIGH_VOL -> "Long Straddle/Strangle, Jade Lizard — capture big moves or hedge";
            case TRENDING_UP, TRENDING_DOWN -> "Directional Spreads — ride the trend";
        };
    }

    public String getRecommendation(IndexType indexType) {
        return getRecommendationForRegime(getCachedRegime(indexType));
    }

    public boolean isStrategyAppropriate(IndexType indexType, String strategyType) {
        RollingRegimeDetector.MarketRegime regime = getCachedRegime(indexType);
        String type = strategyType.toUpperCase();

        if (isAlwaysAllowed(type)) return true;

        return switch (type) {
            case "IRON_CONDOR", "HEDGED_SELLING" ->
                    regime == RollingRegimeDetector.MarketRegime.LOW_VOL
                    || regime == RollingRegimeDetector.MarketRegime.RANGE_BOUND;
            case "BUTTERFLY" ->
                    regime == RollingRegimeDetector.MarketRegime.LOW_VOL
                    || regime == RollingRegimeDetector.MarketRegime.RANGE_BOUND;
            case "BULL_CALL_SPREAD", "BEAR_PUT_SPREAD" ->
                    regime == RollingRegimeDetector.MarketRegime.RANGE_BOUND
                    || regime == RollingRegimeDetector.MarketRegime.HIGH_VOL;
            case "LONG_STRADDLE", "LONG_STRANGLE" ->
                    regime == RollingRegimeDetector.MarketRegime.HIGH_VOL;
            case "RATIO_SPREAD" ->
                    regime == RollingRegimeDetector.MarketRegime.RANGE_BOUND;
            default -> true;
        };
    }

    private boolean isAlwaysAllowed(String type) {
        return switch (type) {
            case "DIRECTIONAL_BUY", "MOMENTUM", "MOMENTUM_BREAKOUT", "GAP_AND_GO",
                 "REVERSAL", "MORNING_MOMENTUM", "OI_MOMENTUM", "EVENT_SPIKE",
                 "HEDGE_MODE", "SCALPING", "EXPIRY_SCALP", "GAMMA_SCALP",
                 "EXPIRY_REVERSAL", "OI_SHIFT_TRAP", "FALLBACK", "EVENT_BUY",
                 "VOLATILITY_BREAKOUT" -> true;
            default -> false;
        };
    }
}
