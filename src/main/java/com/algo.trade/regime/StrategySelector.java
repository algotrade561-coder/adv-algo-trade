package com.algo.trade.regime;

import com.algo.trade.notification.TelegramAlertService;
import com.algo.trade.regime.RegimeFilter.MarketRegime;
import com.algo.trade.risk.MarketGuard;
import com.algo.trade.strategy.StrategyType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maps detected market regimes to recommended strategy types.
 * Periodically checks for regime changes and sends alerts.
 */
@Component
public class StrategySelector {

    private static final Logger log = LoggerFactory.getLogger(StrategySelector.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime CHECK_START = LocalTime.of(9, 16);
    private static final LocalTime CHECK_END = LocalTime.of(15, 25);

    private final RegimeFilter regimeFilter;
    private final MarketGuard marketGuard;
    private final TelegramAlertService telegramAlertService;

    /** Previous regime per index (e.g. "NIFTY" → IDEAL). */
    private final ConcurrentHashMap<String, MarketRegime> previousRegimes = new ConcurrentHashMap<>();

    /**
     * Regime → recommended strategies mapping.
     * IDEAL  → all buying + short straddle/strangle
     * GOOD   → directional spreads + iron condor + calendar
     * NEUTRAL → short straddle/strangle + butterfly
     * RISKY  → iron condor + directional spreads
     * DANGER → empty (no new trades)
     */
    private static final Map<MarketRegime, List<StrategyType>> REGIME_STRATEGY_MAP = Map.of(
            MarketRegime.IDEAL, List.of(
                    StrategyType.DIRECTIONAL_BUY,
                    StrategyType.VOLATILITY_BREAKOUT,
                    StrategyType.EVENT_DRIVEN_BUY,
                    StrategyType.SCALPING,
                    StrategyType.LONG_STRADDLE,
                    StrategyType.LONG_STRANGLE,
                    StrategyType.BULL_CALL_SPREAD,
                    StrategyType.BEAR_PUT_SPREAD,
                    StrategyType.SHORT_STRADDLE,
                    StrategyType.SHORT_STRANGLE
            ),
            MarketRegime.GOOD, List.of(
                    StrategyType.BULL_CALL_SPREAD,
                    StrategyType.BEAR_PUT_SPREAD,
                    StrategyType.IRON_CONDOR,
                    StrategyType.CALENDAR_SPREAD
            ),
            MarketRegime.NEUTRAL, List.of(
                    StrategyType.SHORT_STRADDLE,
                    StrategyType.SHORT_STRANGLE,
                    StrategyType.BUTTERFLY
            ),
            MarketRegime.RISKY, List.of(
                    StrategyType.IRON_CONDOR,
                    StrategyType.BULL_CALL_SPREAD,
                    StrategyType.BEAR_PUT_SPREAD
            ),
            MarketRegime.DANGER, List.of()
    );

    public StrategySelector(RegimeFilter regimeFilter,
                            MarketGuard marketGuard,
                            TelegramAlertService telegramAlertService) {
        this.regimeFilter = regimeFilter;
        this.marketGuard = marketGuard;
        this.telegramAlertService = telegramAlertService;
    }

    /**
     * Checks for regime changes every 30 seconds during 09:16–15:25 IST.
     * Evaluates regime for both NIFTY and BANKNIFTY independently.
     * Sends Telegram alert on regime change.
     */
    @Scheduled(fixedDelay = 30_000)
    public void checkRegimeChanges() {
        LocalTime now = ZonedDateTime.now(IST).toLocalTime();
        if (now.isBefore(CHECK_START) || now.isAfter(CHECK_END)) {
            return;
        }

        double vix = marketGuard.getCurrentVix();
        double pcr = marketGuard.getCurrentPcr();

        // Evaluate for both indices
        evaluateIndex("NIFTY", vix, pcr);
        evaluateIndex("BANKNIFTY", vix, pcr);
    }

    private void evaluateIndex(String index, double vix, double pcr) {
        // Use neutral defaults for indicators we don't have real-time data for
        double ivRank = 50;
        int trendSignal = 0;
        double oiWallDistance = 3.0;

        MarketRegime currentRegime = regimeFilter.detectRegime(vix, ivRank, pcr, trendSignal, oiWallDistance);
        MarketRegime previousRegime = previousRegimes.get(index);

        if (previousRegime != null && previousRegime != currentRegime) {
            List<StrategyType> recommended = recommendedStrategies(currentRegime);
            String strategies = recommended.isEmpty() ? "None (manage existing only)"
                    : recommended.stream().map(StrategyType::displayName)
                    .reduce((a, b) -> a + ", " + b).orElse("None");

            String alert = "Regime change detected"
                    + System.lineSeparator() + "Index: " + index
                    + System.lineSeparator() + "Previous: " + previousRegime
                    + System.lineSeparator() + "Current: " + currentRegime
                    + System.lineSeparator() + "Recommended: " + strategies;

            telegramAlertService.systemAlert(alert);
            log.info("StrategySelector: {} regime changed {} → {} | recommended: {}",
                    index, previousRegime, currentRegime, strategies);
        }

        previousRegimes.put(index, currentRegime);
    }

    /**
     * Returns true if the given strategy type is appropriate for the given regime.
     */
    public boolean isAppropriate(StrategyType type, MarketRegime regime) {
        return recommendedStrategies(regime).contains(type);
    }

    /**
     * Returns the list of recommended strategies for a given regime.
     */
    public List<StrategyType> recommendedStrategies(MarketRegime regime) {
        return REGIME_STRATEGY_MAP.getOrDefault(regime, List.of());
    }
}
