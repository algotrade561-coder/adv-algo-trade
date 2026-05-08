package com.algo.trade.strategy;

import com.algo.trade.config.GlobalConfigService;
import com.algo.trade.domain.IndexType;
import com.algo.trade.domain.UnderlyingSymbol;
import com.algo.trade.indicator.IVRankTracker;
import com.algo.trade.marketdata.ExpiryCalendar;
import com.algo.trade.marketdata.LiveCandleBuilder;
import com.algo.trade.marketdata.PcrCalculator;
import com.algo.trade.news.NewsFeedService;
import com.algo.trade.notification.TelegramAlertService;
import com.algo.trade.regime.RegimeFilter;
import com.algo.trade.risk.MarketGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Algo Flow Orchestrator — unified pre-trade filter pipeline.
 *
 * Three-phase evaluation before any strategy runs:
 *   Phase 1 (Pre-Trade):  VIX regime → Session window → Event/news
 *   Phase 2 (Entry Logic): Volume activity → PCR sentiment
 *   Phase 3 (Risk):        Handled by existing RiskEngine (unchanged)
 *
 * Each filter can be independently enabled/disabled via application.yml.
 * The orchestrator does NOT replace existing strategy-internal filters
 * (VWAP, breakout, OI, etc.) — it adds a market-wide gate that runs
 * BEFORE any strategy evaluates.
 *
 * Returns an EntryDecision with pass/fail, passed/failed filter names,
 * and a recommended trailing stop mode based on market conditions.
 */
@Component
public class AlgoFlowOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AlgoFlowOrchestrator.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final MarketGuard marketGuard;
    private final GlobalConfigService globalConfigService;
    private final NewsFeedService newsFeedService;
    private final ExpiryCalendar expiryCalendar;
    private final PcrCalculator pcrCalculator;
    private final IVRankTracker ivRankTracker;
    private final LiveCandleBuilder liveCandleBuilder;
    private final com.algo.trade.indicator.VolumeDeltaTracker volumeDeltaTracker;
    private final com.algo.trade.indicator.RangeBoundDetector rangeBoundDetector;
    private final com.algo.trade.indicator.OIPriceActionFilter oiPriceActionFilter;
    private final RegimeFilter regimeFilter;
    private final MarketEnvironmentScorer environmentScorer;
    private final TelegramAlertService telegramAlertService;

    // ── Per-filter enable/disable flags ───────────────────────────────────

    @Value("${algo-flow.vix-filter-enabled:true}")
    private boolean vixFilterEnabled;

    @Value("${algo-flow.session-filter-enabled:true}")
    private boolean sessionFilterEnabled;

    @Value("${algo-flow.event-filter-enabled:true}")
    private boolean eventFilterEnabled;

    @Value("${algo-flow.volume-activity-enabled:true}")
    private boolean volumeActivityEnabled;

    @Value("${algo-flow.pcr-filter-enabled:true}")
    private boolean pcrFilterEnabled;

    @Value("${algo-flow.oi-price-action-filter-enabled:true}")
    private boolean oiPriceActionFilterEnabled;

    // ── Regime + Session Gate flags ───────────────────────────────────────

    @Value("${algo-flow.regime-session-gate-enabled:true}")
    private boolean regimeSessionGateEnabled;

    @Value("${algo-flow.regime-session-gate-log-only:false}")
    private boolean regimeSessionGateLogOnly;

    /** Throttle map: "underlying|strategyType" → last notification timestamp */
    private final Map<String, Instant> regimeGateNotificationThrottle = new ConcurrentHashMap<>();
    private static final long THROTTLE_MINUTES = 5;

    // ── VIX regime thresholds ─────────────────────────────────────────────

    /** VIX below this = "dead" market — options are cheap but no movement expected. */
    @Value("${vix-regime.dead-threshold:12.0}")
    private double vixDeadThreshold;

    /** VIX above this = extreme volatility — reduce size or skip. */
    @Value("${vix-regime.extreme-threshold:25.0}")
    private double vixExtremeThreshold;

    /** VIX direction lookback: if VIX is rising fast, caution even in normal range. */
    @Value("${vix-regime.direction-lookback-minutes:15}")
    private int vixDirectionLookbackMinutes;

    // ── Session window boundaries ─────────────────────────────────────────

    @Value("${session.morning-momentum-start:09:16}")
    private String morningMomentumStart;

    @Value("${session.morning-momentum-end:09:45}")
    private String morningMomentumEnd;

    @Value("${session.midday-chop-start:11:30}")
    private String middayChopStart;

    @Value("${session.midday-chop-end:13:00}")
    private String middayChopEnd;

    @Value("${session.pre-close-cutoff:14:45}")
    private String preCloseCutoff;

    // ── PCR sentiment thresholds ──────────────────────────────────────────

    @Value("${pcr-tracker.extreme-bullish:0.5}")
    private double pcrExtremeBullish;

    @Value("${pcr-tracker.extreme-bearish:1.5}")
    private double pcrExtremeBearish;

    public AlgoFlowOrchestrator(MarketGuard marketGuard,
                                 GlobalConfigService globalConfigService,
                                 NewsFeedService newsFeedService,
                                 ExpiryCalendar expiryCalendar,
                                 PcrCalculator pcrCalculator,
                                 IVRankTracker ivRankTracker,
                                 LiveCandleBuilder liveCandleBuilder,
                                 com.algo.trade.indicator.VolumeDeltaTracker volumeDeltaTracker,
                                 com.algo.trade.indicator.RangeBoundDetector rangeBoundDetector,
                                 com.algo.trade.indicator.OIPriceActionFilter oiPriceActionFilter,
                                 RegimeFilter regimeFilter,
                                 MarketEnvironmentScorer environmentScorer,
                                 TelegramAlertService telegramAlertService) {
        this.marketGuard = marketGuard;
        this.globalConfigService = globalConfigService;
        this.newsFeedService = newsFeedService;
        this.expiryCalendar = expiryCalendar;
        this.pcrCalculator = pcrCalculator;
        this.ivRankTracker = ivRankTracker;
        this.liveCandleBuilder = liveCandleBuilder;
        this.volumeDeltaTracker = volumeDeltaTracker;
        this.rangeBoundDetector = rangeBoundDetector;
        this.oiPriceActionFilter = oiPriceActionFilter;
        this.regimeFilter = regimeFilter;
        this.environmentScorer = environmentScorer;
        this.telegramAlertService = telegramAlertService;
    }

    // ── Result type ───────────────────────────────────────────────────────

    public record EntryDecision(
            boolean allowed,
            String trailingStopMode,
            List<String> passedFilters,
            List<String> failedFilters,
            String blockReason,
            VixRegime vixRegime,
            SessionWindow sessionWindow,
            int environmentScore,
            String environmentBreakdown
    ) {
        public static EntryDecision blocked(String reason, List<String> passed, List<String> failed) {
            return new EntryDecision(false, null, List.copyOf(passed), List.copyOf(failed), reason, null, null, -1, null);
        }

        public static EntryDecision blocked(String reason, List<String> passed, List<String> failed,
                                             int envScore, String envBreakdown) {
            return new EntryDecision(false, null, List.copyOf(passed), List.copyOf(failed), reason, null, null, envScore, envBreakdown);
        }

        public static EntryDecision approved(String trailMode, List<String> passed,
                                              VixRegime vixRegime, SessionWindow sessionWindow) {
            return new EntryDecision(true, trailMode, List.copyOf(passed), List.of(), null, vixRegime, sessionWindow, -1, null);
        }

        public static EntryDecision approved(String trailMode, List<String> passed,
                                              VixRegime vixRegime, SessionWindow sessionWindow,
                                              int envScore, String envBreakdown) {
            return new EntryDecision(true, trailMode, List.copyOf(passed), List.of(), null, vixRegime, sessionWindow, envScore, envBreakdown);
        }
    }

    public enum VixRegime { DEAD, LOW, NORMAL, ELEVATED, EXTREME }
    public enum SessionWindow { MORNING_MOMENTUM, POST_OPEN, MIDDAY_CHOP, AFTERNOON, PRE_EXPIRY, GAMMA_SCALPING }

    // ── Main evaluation ───────────────────────────────────────────────────

    /**
     * Evaluate whether market conditions allow entry for a given underlying.
     * Called by AlgoTradeExecution BEFORE any strategy evaluates.
     *
     * @param underlying the index being traded
     * @param marketTime current IST time
     * @param strategyType the strategy requesting entry (for logging)
     * @return EntryDecision with allowed/blocked status and filter audit trail
     */
    public EntryDecision evaluateEntry(UnderlyingSymbol underlying, LocalTime marketTime,
                                        StrategyType strategyType) {
        List<String> passed = new ArrayList<>();
        List<String> failed = new ArrayList<>();

        // ═══════════════════════════════════════════════════════════════════
        // PHASE 1: PRE-TRADE FILTERS
        // ═══════════════════════════════════════════════════════════════════

        // 1a. VIX Regime
        VixRegime vixRegime = classifyVixRegime();
        if (vixFilterEnabled) {
            if (vixRegime == VixRegime.DEAD) {
                failed.add("VIX_REGIME:DEAD(vix=" + String.format("%.1f", marketGuard.getCurrentVix()) + ")");
                // Dead VIX = soft block for buying strategies (options are cheap but won't move)
                if (!strategyType.isSellingStrategy()) {
                    return EntryDecision.blocked(
                            "VIX too low (" + String.format("%.1f", marketGuard.getCurrentVix())
                                    + ") — options cheap but no movement expected",
                            passed, failed);
                }
            } else if (vixRegime == VixRegime.EXTREME) {
                failed.add("VIX_REGIME:EXTREME(vix=" + String.format("%.1f", marketGuard.getCurrentVix()) + ")");
                return EntryDecision.blocked(
                        "VIX extreme (" + String.format("%.1f", marketGuard.getCurrentVix())
                                + ") — too volatile for new entries",
                        passed, failed);
            } else {
                passed.add("VIX_REGIME:" + vixRegime + "(vix=" + String.format("%.1f", marketGuard.getCurrentVix()) + ")");
            }

            // VIX direction check: if VIX is rising fast, add caution
            if (isVixRising()) {
                // Don't block, but log — strategies can use this info
                log.info("[AlgoFlow] VIX rising — caution for {} on {}", strategyType, underlying);
            }
        } else {
            passed.add("VIX_REGIME:DISABLED");
        }

        // 1b. Session Window
        SessionWindow sessionWindow = classifySession(marketTime, underlying);
        if (sessionFilterEnabled) {
            if (sessionWindow == SessionWindow.MIDDAY_CHOP) {
                // Midday chop: block scalping and momentum strategies, allow others
                if (strategyType == StrategyType.SCALPING || strategyType == StrategyType.GAP_AND_GO) {
                    failed.add("SESSION:MIDDAY_CHOP(time=" + marketTime + ")");
                    return EntryDecision.blocked(
                            "Midday chop session (" + marketTime + ") — " + strategyType.displayName() + " blocked",
                            passed, failed);
                }
                passed.add("SESSION:MIDDAY_CHOP(allowed_for_" + strategyType.name() + ")");
            } else {
                passed.add("SESSION:" + sessionWindow + "(time=" + marketTime + ")");
            }
        } else {
            passed.add("SESSION:DISABLED");
        }

        // 1c. Event/News Filter
        if (eventFilterEnabled) {
            if (marketGuard.isEventDay()) {
                // Event day: block selling strategies entirely, warn buying strategies
                if (strategyType.isSellingStrategy()) {
                    failed.add("EVENT:EVENT_DAY");
                    return EntryDecision.blocked("Event day — selling strategies blocked", passed, failed);
                }
                passed.add("EVENT:EVENT_DAY(buying_allowed_with_caution)");
            } else if (marketGuard.isPreEventDay()) {
                passed.add("EVENT:PRE_EVENT_DAY(caution)");
            } else if (newsFeedService.hasHighImpactEvent()) {
                // High-impact news: block real trades, allow paper
                passed.add("EVENT:HIGH_IMPACT_NEWS(paper_only)");
            } else {
                passed.add("EVENT:CLEAR");
            }
        } else {
            passed.add("EVENT:DISABLED");
        }

        // ═══════════════════════════════════════════════════════════════════
        // PHASE 2: ENTRY LOGIC FILTERS
        // ═══════════════════════════════════════════════════════════════════

        // 2a. Volume Activity — underlying must show minimum activity
        if (volumeActivityEnabled) {
            IndexType idx = IndexType.from(underlying);
            long spotToken = idx.spotToken();
            var candles1m = liveCandleBuilder.getHistory(spotToken, com.algo.trade.domain.Timeframe.ONE_MINUTE);
            if (!candles1m.isEmpty()) {
                long latestVolume = candles1m.getLast().volume();
                // Compare to average of last 5 candles
                double avgVolume = candles1m.stream()
                        .skip(Math.max(0, candles1m.size() - 5))
                        .mapToLong(c -> c.volume())
                        .average().orElse(0);
                if (avgVolume > 0 && latestVolume < avgVolume * 0.3) {
                    failed.add("VOLUME:LOW_ACTIVITY(latest=" + latestVolume + ",avg=" + String.format("%.0f", avgVolume) + ")");
                    // Soft block: don't reject, but log. Very low volume is suspicious.
                    log.warn("[AlgoFlow] Very low volume for {} — latest={} avg={}", underlying, latestVolume, String.format("%.0f", avgVolume));
                } else {
                    passed.add("VOLUME:OK(latest=" + latestVolume + ")");
                }

                // Volume Delta — directional conviction
                var deltaSnapshot = volumeDeltaTracker.getSnapshot(idx);
                if (deltaSnapshot.totalVolume() > 0) {
                    passed.add("VOLUME_DELTA:" + deltaSnapshot.bias() + "(delta=" + String.format("%.1f", deltaSnapshot.deltaPercent()) + "%)");
                }

                // Range-bound detection — skip directional strategies in choppy markets
                if (rangeBoundDetector.isRangeBound(candles1m)) {
                    // Only block directional buying strategies, not spreads or event-driven
                    if (!strategyType.isSellingStrategy()
                            && strategyType != StrategyType.EVENT_DRIVEN_BUY
                            && strategyType != StrategyType.LONG_STRADDLE
                            && strategyType != StrategyType.LONG_STRANGLE) {
                        failed.add("RANGE_BOUND:CHOPPY_MARKET");
                        return EntryDecision.blocked(
                                "Market is range-bound/choppy — directional strategies blocked",
                                passed, failed);
                    }
                    passed.add("RANGE_BOUND:CHOPPY(allowed_for_" + strategyType.name() + ")");
                } else {
                    passed.add("RANGE_BOUND:TRENDING");
                }
            } else {
                passed.add("VOLUME:NO_DATA");
            }
        } else {
            passed.add("VOLUME:DISABLED");
        }

        // 2b. PCR Sentiment
        if (pcrFilterEnabled) {
            double pcr = pcrCalculator.getPcr();
            if (pcr > 0) {
                String pcrBias;
                if (pcr < pcrExtremeBullish) {
                    pcrBias = "EXTREME_BULLISH";
                } else if (pcr > pcrExtremeBearish) {
                    pcrBias = "EXTREME_BEARISH";
                } else if (pcr < 0.8) {
                    pcrBias = "BULLISH";
                } else if (pcr > 1.2) {
                    pcrBias = "BEARISH";
                } else {
                    pcrBias = "NEUTRAL";
                }
                passed.add("PCR:" + pcrBias + "(pcr=" + String.format("%.2f", pcr) + ")");
                // PCR is informational — doesn't block, but strategies can use the bias
            } else {
                passed.add("PCR:NO_DATA");
            }
        } else {
            passed.add("PCR:DISABLED");
        }

        // 2c. OI Price Action — confirm breakout direction with OI shifts
        if (oiPriceActionFilterEnabled) {
            IndexType idx = IndexType.from(underlying);
            // For buying strategies, check if OI supports the likely direction
            // CE strategies = bullish, PE strategies = bearish
            // For non-directional strategies (spreads), skip this filter
            if (!strategyType.isSellingStrategy() && !strategyType.isSpreadStrategy()) {
                // Determine direction from PCR bias or default to bullish for CE strategies
                boolean isBullish = true; // default; strategies will refine at entry time
                String rejectReason = oiPriceActionFilter.getRejectReason(idx, isBullish);
                if (rejectReason != null) {
                    // Soft block: log as failed filter but don't reject outright
                    // The strategy's own OI divergence check is the hard gate
                    failed.add("OI_PRICE_ACTION:" + (isBullish ? "BULLISH" : "BEARISH") + "_NOT_CONFIRMED");
                    log.info("[AlgoFlow] OI Price Action soft-block for {} on {}: {}", strategyType, underlying, rejectReason);
                } else {
                    passed.add("OI_PRICE_ACTION:CONFIRMED(snapshots=" + oiPriceActionFilter.getSnapshotCount(idx) + ")");
                }
            } else {
                passed.add("OI_PRICE_ACTION:SKIPPED(non_directional)");
            }
        } else {
            passed.add("OI_PRICE_ACTION:DISABLED");
        }

        // ═══════════════════════════════════════════════════════════════════
        // PHASE 2B: REGIME + SESSION ENTRY GATE
        // ═══════════════════════════════════════════════════════════════════

        // Compute current regime using RegimeFilter
        double currentVix = marketGuard.getCurrentVix();
        double ivRank = ivRankTracker.getIVRank(IndexType.from(underlying));
        double pcr = pcrCalculator.getPcr();
        int regimeScore = regimeFilter.computeScore(currentVix, ivRank, pcr, 0, 5.0);
        RegimeFilter.MarketRegime regime = regimeFilter.classify(regimeScore);

        // Environment score computation (use neutral "CE" since option type is not yet known)
        int envScore = -1;
        String envBreakdown = null;
        try {
            // Use neutral PCR approach: "CE" as default since direction is unknown at this stage
            MarketEnvironmentScorer.EnvironmentScore envResult = environmentScorer.compute(
                    regimeScore, pcr, "CE", ivRank, 0.0, sessionWindow);
            envScore = envResult.totalScore();
            envBreakdown = envResult.breakdown();
            passed.add("ENV_SCORE:" + envScore + "(breakdown=" + envBreakdown + ")");
        } catch (Exception ex) {
            log.error("[AlgoFlow] Environment score computation failed for {} on {}: {}",
                    strategyType, underlying, ex.getMessage());
            envScore = -1;
            envBreakdown = "COMPUTATION_FAILED";
            passed.add("ENV_SCORE:COMPUTATION_FAILED");
        }

        // Regime + Session Gate evaluation
        if (regimeSessionGateEnabled) {
            if (isRegimeSessionBlocked(regime, sessionWindow)) {
                if (!regimeSessionGateLogOnly) {
                    // Hard block
                    failed.add("REGIME_SESSION_GATE:BLOCKED(regime=" + regime + ",session=" + sessionWindow + ")");
                    sendRegimeGateNotification(strategyType, underlying, regime, regimeScore,
                            sessionWindow, envScore, false);
                    return EntryDecision.blocked(
                            "Regime+Session gate blocked: " + regime + " + " + sessionWindow,
                            passed, failed, envScore, envBreakdown);
                } else {
                    // Log-only mode: allow entry but notify
                    passed.add("REGIME_SESSION_GATE:[LOG-ONLY] would block (regime=" + regime + ",session=" + sessionWindow + ")");
                    sendRegimeGateNotification(strategyType, underlying, regime, regimeScore,
                            sessionWindow, envScore, true);
                }
            } else {
                passed.add("REGIME_SESSION_GATE:PASSED");
            }
        } else {
            passed.add("REGIME_SESSION_GATE:DISABLED");
        }

        // Environment score gating (threshold check)
        int minEnvScore = globalConfigService.getMinEnvironmentScore();
        if (envScore >= 0 && envScore < minEnvScore) {
            failed.add("ENV_SCORE_GATE:BELOW_THRESHOLD(score=" + envScore + ",threshold=" + minEnvScore + ")");
            return EntryDecision.blocked(
                    "Environment score " + envScore + " below threshold " + minEnvScore,
                    passed, failed, envScore, envBreakdown);
        }

        // ═══════════════════════════════════════════════════════════════════
        // DETERMINE TRAILING STOP MODE
        // ═══════════════════════════════════════════════════════════════════

        String trailingStopMode = determineTrailingStopMode(vixRegime, sessionWindow, underlying);

        log.info("[AlgoFlow] {} on {} — ALLOWED | regime={} session={} trail={} envScore={} | passed={} failed={}",
                strategyType, underlying, vixRegime, sessionWindow, trailingStopMode, envScore,
                passed.size(), failed.size());

        return EntryDecision.approved(trailingStopMode, passed, vixRegime, sessionWindow, envScore, envBreakdown);
    }

    // ── VIX Regime Classification ─────────────────────────────────────────

    private VixRegime classifyVixRegime() {
        double vix = marketGuard.getCurrentVix();
        if (vix <= 0) return VixRegime.NORMAL; // no data — assume normal
        if (vix < vixDeadThreshold) return VixRegime.DEAD;
        if (vix < 14) return VixRegime.LOW;
        if (vix <= 18) return VixRegime.NORMAL;
        if (vix <= vixExtremeThreshold) return VixRegime.ELEVATED;
        return VixRegime.EXTREME;
    }

    /**
     * Check if VIX is rising by comparing current VIX to the VIX candle from N minutes ago.
     * Uses the VIX instrument token (264969) candle history.
     */
    private boolean isVixRising() {
        long vixToken = 264969L;
        var candles = liveCandleBuilder.getHistory(vixToken, com.algo.trade.domain.Timeframe.FIVE_MINUTE);
        if (candles.size() < 3) return false;
        double ema3 = liveCandleBuilder.calculateEMA(vixToken, com.algo.trade.domain.Timeframe.FIVE_MINUTE, 3);
        double ema8 = liveCandleBuilder.calculateEMA(vixToken, com.algo.trade.domain.Timeframe.FIVE_MINUTE, 8);
        if (ema3 == 0 || ema8 == 0) return false;
        return (ema3 - ema8) / ema8 * 100 > 3.0; // VIX rising > 3%
    }

    // ── Session Window Classification ─────────────────────────────────────

    private SessionWindow classifySession(LocalTime time, UnderlyingSymbol underlying) {
        IndexType idx = IndexType.from(underlying);
        boolean isExpiryDay = expiryCalendar.isExpiryDay(idx);

        LocalTime mmStart = LocalTime.parse(morningMomentumStart);
        LocalTime mmEnd = LocalTime.parse(morningMomentumEnd);
        LocalTime mcStart = LocalTime.parse(middayChopStart);
        LocalTime mcEnd = LocalTime.parse(middayChopEnd);
        LocalTime pcCutoff = LocalTime.parse(preCloseCutoff);

        if (!time.isBefore(mmStart) && !time.isAfter(mmEnd)) {
            return SessionWindow.MORNING_MOMENTUM;
        }
        if (time.isAfter(mmEnd) && time.isBefore(LocalTime.of(11, 0))) {
            return SessionWindow.POST_OPEN;
        }
        if (!time.isBefore(mcStart) && !time.isAfter(mcEnd)) {
            if (isExpiryDay && time.isAfter(LocalTime.of(13, 0))) {
                return SessionWindow.GAMMA_SCALPING;
            }
            return SessionWindow.MIDDAY_CHOP;
        }
        if (time.isAfter(mcEnd) && time.isBefore(pcCutoff)) {
            if (isExpiryDay) {
                return SessionWindow.PRE_EXPIRY;
            }
            return SessionWindow.AFTERNOON;
        }
        // After pre-close cutoff
        return SessionWindow.PRE_EXPIRY;
    }

    // ── Trailing Stop Mode ────────────────────────────────────────────────

    /**
     * Recommend trailing stop mode based on market conditions.
     * Strategies can use this to adjust their exit behavior.
     */
    private String determineTrailingStopMode(VixRegime vixRegime, SessionWindow sessionWindow,
                                              UnderlyingSymbol underlying) {
        IndexType idx = IndexType.from(underlying);
        boolean isExpiryDay = expiryCalendar.isExpiryDay(idx);

        // Expiry day gamma scalping window: very tight trailing
        if (isExpiryDay && sessionWindow == SessionWindow.GAMMA_SCALPING) {
            return "GAMMA_RAPID";
        }
        // Expiry day pre-expiry: tight trailing
        if (isExpiryDay && sessionWindow == SessionWindow.PRE_EXPIRY) {
            return "GAMMA_RAPID";
        }
        // Elevated VIX: use ATR-based trailing (wider stops for volatile market)
        if (vixRegime == VixRegime.ELEVATED) {
            return "ATR";
        }
        // Morning momentum: let profits run with percent-of-peak trailing
        if (sessionWindow == SessionWindow.MORNING_MOMENTUM) {
            return "PERCENT_OF_PEAK";
        }
        // Default: ATR-based
        return "ATR";
    }

    // ── Regime + Session Gate Logic ───────────────────────────────────────

    /**
     * Determine if the given regime + session combination should block entry.
     *
     * <p>Blocking rules:
     * <ul>
     *   <li>DANGER → always blocked</li>
     *   <li>RISKY + MIDDAY_CHOP → blocked</li>
     *   <li>RISKY + AFTERNOON → blocked</li>
     *   <li>Everything else → not blocked</li>
     * </ul>
     */
    boolean isRegimeSessionBlocked(RegimeFilter.MarketRegime regime, SessionWindow session) {
        if (regime == RegimeFilter.MarketRegime.DANGER) return true;
        if (regime == RegimeFilter.MarketRegime.RISKY) {
            return session == SessionWindow.MIDDAY_CHOP || session == SessionWindow.AFTERNOON;
        }
        return false;
    }

    /**
     * Send a throttled Telegram notification when the regime+session gate blocks an entry.
     * Throttle: skip if same underlying|strategyType key was notified within 5 minutes.
     */
    private void sendRegimeGateNotification(StrategyType strategyType, UnderlyingSymbol underlying,
                                             RegimeFilter.MarketRegime regime, int regimeScore,
                                             SessionWindow sessionWindow, int envScore,
                                             boolean logOnly) {
        String throttleKey = underlying.name() + "|" + strategyType.name();
        Instant now = Instant.now();
        Instant lastNotified = regimeGateNotificationThrottle.get(throttleKey);
        if (lastNotified != null && lastNotified.plusSeconds(THROTTLE_MINUTES * 60).isAfter(now)) {
            log.debug("[AlgoFlow] Regime gate notification throttled for key={}", throttleKey);
            return;
        }
        regimeGateNotificationThrottle.put(throttleKey, now);

        String prefix = logOnly ? "[LOG-ONLY] 🚫" : "🚫";
        String message = prefix + " Entry Blocked — Regime+Session Gate"
                + System.lineSeparator() + "Strategy: " + strategyType.displayName()
                + System.lineSeparator() + "Underlying: " + underlying.name()
                + System.lineSeparator() + "Regime: " + regime + " (score: " + regimeScore + ")"
                + System.lineSeparator() + "Session: " + sessionWindow
                + System.lineSeparator() + "Environment Score: " + envScore + "/" + globalConfigService.getMinEnvironmentScore();

        telegramAlertService.systemAlert(message);
    }
}
