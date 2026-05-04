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
    private final com.algo.trade.indicator.IVRankTracker ivRankTracker;
    private final com.algo.trade.marketdata.LiveCandleBuilder liveCandleBuilder;
    private final com.algo.trade.marketdata.LiveInstrumentCache liveInstrumentCache;
    private final com.algo.trade.marketdata.ExpiryCalendar expiryCalendar;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.monitoring.SchedulerRegistry schedulerRegistry;

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
                            TelegramAlertService telegramAlertService,
                            com.algo.trade.indicator.IVRankTracker ivRankTracker,
                            com.algo.trade.marketdata.LiveCandleBuilder liveCandleBuilder,
                            com.algo.trade.marketdata.LiveInstrumentCache liveInstrumentCache,
                            com.algo.trade.marketdata.ExpiryCalendar expiryCalendar) {
        this.regimeFilter = regimeFilter;
        this.marketGuard = marketGuard;
        this.telegramAlertService = telegramAlertService;
        this.ivRankTracker = ivRankTracker;
        this.liveCandleBuilder = liveCandleBuilder;
        this.liveInstrumentCache = liveInstrumentCache;
        this.expiryCalendar = expiryCalendar;
    }

    /**
     * Checks for regime changes every 30 seconds during 09:16–15:25 IST.
     * Evaluates regime for both NIFTY and BANKNIFTY independently.
     * Sends Telegram alert on regime change.
     */
    @Scheduled(fixedDelay = 30_000)
    public void checkRegimeChanges() {
        if (schedulerRegistry != null) schedulerRegistry.recordRun("regimeSelector");
        LocalTime now = ZonedDateTime.now(IST).toLocalTime();
        if (now.isBefore(CHECK_START) || now.isAfter(CHECK_END)) {
            return;
        }

        double vix = marketGuard.getCurrentVix();
        double pcr = marketGuard.getCurrentPcr();

        // Evaluate for all supported indices
        for (com.algo.trade.domain.IndexType indexType : com.algo.trade.domain.IndexType.values()) {
            try {
                com.algo.trade.domain.UnderlyingSymbol.valueOf(indexType.underlyingSymbol());
                evaluateIndex(indexType.underlyingSymbol(), vix, pcr);
            } catch (IllegalArgumentException ignored) {
                // IndexType exists but has no UnderlyingSymbol (e.g. FINNIFTY, MIDCPNIFTY) — skip
            }
        }
    }

    private void evaluateIndex(String index, double vix, double pcr) {
        com.algo.trade.domain.IndexType indexType = com.algo.trade.domain.IndexType.fromName(index);
        double ivRank = ivRankTracker.getIVRank(indexType);

        // Live multi-TF trend from candle builder
        long spotToken = indexType.spotToken();
        int trendSignal = liveCandleBuilder.detectMultiTFTrend(spotToken);

        // VIX trend from candle builder
        int vixTrend = liveCandleBuilder.detectVixTrend(264969L);

        // Live OI wall distance from option chain
        double oiWallDistance = computeOiWallDistance(indexType);

        // IV skew adjustment: if CE IV >> PE IV, bearish bias → reduce score
        double ivSkewAdjustment = computeIvSkewAdjustment(indexType);

        int rawScore = regimeFilter.computeScore(vix, ivRank, pcr, trendSignal, oiWallDistance, vixTrend);
        // Apply IV skew adjustment (±5 points)
        int adjustedScore = Math.max(0, Math.min(100, rawScore + (int) ivSkewAdjustment));

        MarketRegime currentRegime = regimeFilter.classify(adjustedScore);
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

    // ── Live data computation helpers ─────────────────────────────────────────

    /**
     * Compute OI wall distance from live option chain.
     * Finds the nearest high-OI CE strike above ATM (resistance) and PE strike below ATM (support),
     * returns the average distance as % of spot price.
     */
    private double computeOiWallDistance(com.algo.trade.domain.IndexType indexType) {
        try {
            double spot = liveInstrumentCache.getFuturesPrice(indexType);
            if (spot <= 0) return 3.0; // default
            int atm = indexType.roundToATM(spot);
            java.time.LocalDate expiry = expiryCalendar.getCurrentWeeklyExpiry(indexType);
            var chain = liveInstrumentCache.getStrikeChain(indexType, expiry);
            if (chain.isEmpty()) return 3.0;

            // Find nearest CE wall above ATM (highest OI within 3% of spot)
            double maxDist = spot * 0.03;
            var ceWall = chain.stream()
                    .filter(o -> o.isCE() && o.getStrikePrice() > atm)
                    .filter(o -> (o.getStrikePrice() - atm) <= maxDist)
                    .filter(o -> o.getOpenInterest() > 0)
                    .max(java.util.Comparator.comparingLong(com.algo.trade.domain.OptionInstrument::getOpenInterest));

            var peWall = chain.stream()
                    .filter(o -> o.isPE() && o.getStrikePrice() < atm)
                    .filter(o -> (atm - o.getStrikePrice()) <= maxDist)
                    .filter(o -> o.getOpenInterest() > 0)
                    .max(java.util.Comparator.comparingLong(com.algo.trade.domain.OptionInstrument::getOpenInterest));

            double ceDistance = ceWall.map(o -> Math.abs(o.getStrikePrice() - spot) / spot * 100).orElse(5.0);
            double peDistance = peWall.map(o -> Math.abs(spot - o.getStrikePrice()) / spot * 100).orElse(5.0);
            return (ceDistance + peDistance) / 2.0;
        } catch (Exception e) {
            return 3.0; // default on error
        }
    }

    /**
     * Compute IV skew adjustment for regime scoring.
     * CE IV > PE IV → bearish bias (call writers active) → reduce score
     * PE IV > CE IV → bullish bias (put writers active) → increase score
     * Returns adjustment in points: -5 to +5.
     */
    private double computeIvSkewAdjustment(com.algo.trade.domain.IndexType indexType) {
        try {
            double spot = liveInstrumentCache.getFuturesPrice(indexType);
            if (spot <= 0) return 0;
            int atm = indexType.roundToATM(spot);
            java.time.LocalDate expiry = expiryCalendar.getCurrentWeeklyExpiry(indexType);

            var atmCe = liveInstrumentCache.getOption(indexType, atm, "CE", expiry);
            var atmPe = liveInstrumentCache.getOption(indexType, atm, "PE", expiry);
            if (atmCe.isEmpty() || atmPe.isEmpty()) return 0;

            double ceIv = atmCe.get().getImpliedVolatility();
            double peIv = atmPe.get().getImpliedVolatility();
            if (ceIv <= 0 || peIv <= 0) return 0;

            // Skew ratio: CE IV / PE IV
            // > 1.1 = bearish skew (calls expensive, market expects downside)
            // < 0.9 = bullish skew (puts expensive, market expects upside)
            double skewRatio = ceIv / peIv;
            if (skewRatio > 1.15) return -5;  // strong bearish skew
            if (skewRatio > 1.05) return -2;  // mild bearish skew
            if (skewRatio < 0.85) return 5;   // strong bullish skew
            if (skewRatio < 0.95) return 2;   // mild bullish skew
            return 0; // balanced
        } catch (Exception e) {
            return 0;
        }
    }
}
