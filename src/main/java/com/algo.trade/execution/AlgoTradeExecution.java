package com.algo.trade.execution;

import com.algo.trade.config.GlobalConfigService;
import com.algo.trade.config.TradingProperties;
import com.algo.trade.broker.zerodha.KiteAccessTokenStore;
import com.algo.trade.domain.*;
import com.algo.trade.indicator.VwapIndicator;
import com.algo.trade.marketdata.InstrumentCache;
import com.algo.trade.marketdata.ExpiryCalendar;
import com.algo.trade.marketdata.MarketDataService;
import com.algo.trade.persistence.StrategyDecisionEntity;
import com.algo.trade.strategy.AlgoFlowOrchestrator;
import com.algo.trade.strategy.MomentumStrategy;
import com.algo.trade.marketdata.ScanContextBuilder;
import com.algo.trade.marketdata.ScanContextBuilder.ScanContext;
import com.algo.trade.persistence.StrategyDecisionRepository;
import com.algo.trade.strategy.EventDrivenBuyStrategy;
import com.algo.trade.strategy.ExpiryGammaStrategy;
import com.algo.trade.strategy.ExpiryReversalStrategy;
import com.algo.trade.strategy.GapAndGoStrategy;
import com.algo.trade.strategy.OiShiftTrapStrategy;
import com.algo.trade.strategy.ReversalBuyStrategy;
import com.algo.trade.strategy.RuleBasedOptionsStrategy;
import com.algo.trade.strategy.ScalpingStrategy;
import com.algo.trade.strategy.SpreadStrategyEvaluator;
import com.algo.trade.strategy.StrategyConfig;
import com.algo.trade.strategy.StrategyConfigService;
import com.algo.trade.strategy.StrategyContext;
import com.algo.trade.strategy.StrategySignalCsvRecorder;
import com.algo.trade.strategy.StrategyEvaluationRequest;
import com.algo.trade.strategy.StrategyType;
import com.algo.trade.strategy.VolatilityBreakoutStrategy;
import com.algo.trade.strategy.spread.AbstractSpreadStrategy;
import com.algo.trade.risk.MarketGuard;
import com.algo.trade.util.IstDateTimes;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Scheduled live/paper orchestration: market data -> strategy -> risk/execution.
 */
@Service
public class AlgoTradeExecution {

    private static final Logger log = LoggerFactory.getLogger(AlgoTradeExecution.class);
    private static final MathContext MATH_CONTEXT = MathContext.DECIMAL64;

    private final TradingProperties properties;
    private final GlobalConfigService globalConfigService;
    private final TradingStateService tradingStateService;
    private final InstrumentCache instrumentCache;
    private final MarketDataService marketDataService;
    private final RuleBasedOptionsStrategy strategy;
    private final ExecutionEngine executionEngine;
    private final KiteAccessTokenStore tokenStore;
    private final StrategyDecisionRepository decisionRepository;
    private final StrategyConfigService strategyConfigService;
    private final ScalpingStrategy scalpingStrategy;
    private final VolatilityBreakoutStrategy volatilityBreakoutStrategy;
    private final SpreadStrategyEvaluator spreadStrategyEvaluator;
    private final EventDrivenBuyStrategy eventDrivenBuyStrategy;
    private final GapAndGoStrategy gapAndGoStrategy;
    private final ReversalBuyStrategy reversalBuyStrategy;
    private final OiShiftTrapStrategy oiShiftTrapStrategy;
    private final ExpiryGammaStrategy expiryGammaStrategy;
    private final ExpiryReversalStrategy expiryReversalStrategy;
    private final MomentumStrategy momentumStrategy;
    private final MarketGuard marketGuard;
    private final com.algo.trade.marketdata.LiveInstrumentCache liveInstrumentCache;
    private final com.algo.trade.marketdata.LiveCandleBuilder candleBuilder;
    private final StrategySignalCsvRecorder signalCsvRecorder;
    private final ExpiryCalendar expiryCalendar;
    private final com.algo.trade.news.NewsFeedService newsFeedService;
    private final com.algo.trade.risk.WeeklyExposureTracker weeklyExposureTracker;
    private final com.algo.trade.risk.SafeWeekPredictor safeWeekPredictor;
    private final com.algo.trade.indicator.IVRankTracker ivRankTracker;
    private final com.algo.trade.indicator.RealizedVolatilityCalculator realizedVolatilityCalculator;
    private final com.algo.trade.persistence.GreeksSampleRepository greeksSampleRepository;
    private final AtomicBoolean scanInProgress = new AtomicBoolean(false);
    private final Map<StrategyType, AbstractSpreadStrategy> spreadStrategyMap;
    private final StrategyExecutionPipeline executionPipeline;
    private final AlgoFlowOrchestrator algoFlowOrchestrator;
    private final ScanContextBuilder scanContextBuilder;
    private final VwapIndicator vwapIndicator;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.monitoring.ErrorEventService errorEventService;

    @org.springframework.beans.factory.annotation.Autowired
    private com.algo.trade.monitoring.SchedulerRegistry schedulerRegistry;

    // Short-lived cache for underlying REST candles — avoids repeated REST calls within the same candle period.
    // Keyed by "instrumentKey:TIMEFRAME", expires after 60 seconds.
    private record CachedCandles(List<Candle> candles, Instant fetchedAt) {}
    private final Map<String, CachedCandles> candleRestCache = new ConcurrentHashMap<>();
    private static final Duration CANDLE_CACHE_TTL = Duration.ofSeconds(60);

    // Debounce map for stale market data warnings — log at most once per 60s per underlying.
    private final Map<String, Instant> lastStaleWarnTime = new ConcurrentHashMap<>();
    private static final Duration STALE_WARN_DEBOUNCE = Duration.ofSeconds(60);

    public AlgoTradeExecution(
            TradingProperties properties,
            GlobalConfigService globalConfigService,
            TradingStateService tradingStateService,
            InstrumentCache instrumentCache,
            MarketDataService marketDataService,
            RuleBasedOptionsStrategy strategy,
            ExecutionEngine executionEngine,
            KiteAccessTokenStore tokenStore,
            StrategyDecisionRepository decisionRepository,
            StrategyConfigService strategyConfigService,
            ScalpingStrategy scalpingStrategy,
            VolatilityBreakoutStrategy volatilityBreakoutStrategy,
            SpreadStrategyEvaluator spreadStrategyEvaluator,
            EventDrivenBuyStrategy eventDrivenBuyStrategy,
            GapAndGoStrategy gapAndGoStrategy,
            ReversalBuyStrategy reversalBuyStrategy,
            OiShiftTrapStrategy oiShiftTrapStrategy,
            ExpiryGammaStrategy expiryGammaStrategy,
            ExpiryReversalStrategy expiryReversalStrategy,
            MomentumStrategy momentumStrategy,
            MarketGuard marketGuard,
            com.algo.trade.marketdata.LiveInstrumentCache liveInstrumentCache,
            com.algo.trade.marketdata.LiveCandleBuilder candleBuilder,
            StrategySignalCsvRecorder signalCsvRecorder,
            ExpiryCalendar expiryCalendar,
            com.algo.trade.news.NewsFeedService newsFeedService,
            com.algo.trade.risk.WeeklyExposureTracker weeklyExposureTracker,
            com.algo.trade.risk.SafeWeekPredictor safeWeekPredictor,
            com.algo.trade.indicator.IVRankTracker ivRankTracker,
            java.util.List<AbstractSpreadStrategy> spreadStrategies,
            StrategyExecutionPipeline executionPipeline,
            com.algo.trade.indicator.RealizedVolatilityCalculator realizedVolatilityCalculator,
            com.algo.trade.persistence.GreeksSampleRepository greeksSampleRepository,
            AlgoFlowOrchestrator algoFlowOrchestrator,
            ScanContextBuilder scanContextBuilder,
            VwapIndicator vwapIndicator
    ) {
        this.properties = properties;
        this.globalConfigService = globalConfigService;
        this.tradingStateService = tradingStateService;
        this.instrumentCache = instrumentCache;
        this.marketDataService = marketDataService;
        this.strategy = strategy;
        this.executionEngine = executionEngine;
        this.tokenStore = tokenStore;
        this.decisionRepository = decisionRepository;
        this.strategyConfigService = strategyConfigService;
        this.scalpingStrategy = scalpingStrategy;
        this.volatilityBreakoutStrategy = volatilityBreakoutStrategy;
        this.spreadStrategyEvaluator = spreadStrategyEvaluator;
        this.eventDrivenBuyStrategy = eventDrivenBuyStrategy;
        this.gapAndGoStrategy = gapAndGoStrategy;
        this.reversalBuyStrategy = reversalBuyStrategy;
        this.oiShiftTrapStrategy = oiShiftTrapStrategy;
        this.expiryGammaStrategy = expiryGammaStrategy;
        this.expiryReversalStrategy = expiryReversalStrategy;
        this.momentumStrategy = momentumStrategy;
        this.marketGuard = marketGuard;
        this.liveInstrumentCache = liveInstrumentCache;
        this.candleBuilder = candleBuilder;
        this.signalCsvRecorder = signalCsvRecorder;
        this.expiryCalendar = expiryCalendar;
        this.newsFeedService = newsFeedService;
        this.weeklyExposureTracker = weeklyExposureTracker;
        this.safeWeekPredictor = safeWeekPredictor;
        this.ivRankTracker = ivRankTracker;
        Map<StrategyType, AbstractSpreadStrategy> map = new java.util.EnumMap<>(StrategyType.class);
        for (AbstractSpreadStrategy s : spreadStrategies) {
            map.put(s.strategyType(), s);
        }
        this.spreadStrategyMap = java.util.Collections.unmodifiableMap(map);
        this.executionPipeline = executionPipeline;
        this.realizedVolatilityCalculator = realizedVolatilityCalculator;
        this.greeksSampleRepository = greeksSampleRepository;
        this.algoFlowOrchestrator = algoFlowOrchestrator;
        this.scanContextBuilder = scanContextBuilder;
        this.vwapIndicator = vwapIndicator;
    }

    @jakarta.annotation.PostConstruct
    void registerScheduler() {
        if (schedulerRegistry != null) schedulerRegistry.register("algoScan", "REST fallback algo scan (configurable)", properties.algo().scanIntervalMs(), this::scan);
    }

    /**
     * PRIMARY trigger: fires when a candle closes from WebSocket tick data.
     * Each strategy is triggered on its own timeframe:
     *   1-min  → Directional Buy (needs fresh 1-min underlying candles)
     *   5-min  → Scalping (EMA 9/21 crossover on 5-min candles)
     *   15-min → Volatility Breakout + Spreads + Event-Driven (use 15-min candles)
     */
    @EventListener
    public void onCandleClose(CandleClosedEvent event) {
        Timeframe tf = event.timeframe();
        if (tf != Timeframe.ONE_MINUTE && tf != Timeframe.FIVE_MINUTE && tf != Timeframe.FIFTEEN_MINUTE) return;
        if (tradingStateService.killSwitchEnabled()) return;
        if (tradingStateService.haltMode() == com.algo.trade.risk.HaltMode.HARD) return;
        if (!tradingStateService.running()) return;
        if (!scanInProgress.compareAndSet(false, true)) return;
        try {
            log.info("CandleClosedEvent triggered scan: token={} tf={}", event.instrumentToken(), tf);
            runScan(tf);
        } catch (Exception ex) {
            log.warn("CandleClosedEvent scan failed: {}", ex.getMessage(), ex);
            if (errorEventService != null) errorEventService.high("AlgoTradeExecution", "CandleClosedEvent scan failed: " + ex.getMessage(), ex);
        } finally {
            scanInProgress.set(false);
        }
    }

    /**
     * FALLBACK trigger: REST poll every 60s when WebSocket is not connected.
     */
    @Scheduled(
            initialDelayString = "${trading.algo.initial-delay-ms:5}",
            fixedDelayString = "${trading.algo.scan-interval-ms:60000}"
    )
    public void scan() {
        if (schedulerRegistry != null && !schedulerRegistry.isEnabled("algoScan")) return;
        if (!tradingStateService.schedulerEnabled()) {
            log.debug("Algo scan skipped: REST poll scheduler disabled at runtime");
            return;
        }
        if (!tradingStateService.running()) {
            log.info("Algo scan skipped: trading state is stopped");
            return;
        }
        if (tradingStateService.killSwitchEnabled()) {
            log.info("Algo scan skipped: kill switch is enabled");
            return;
        }
        if (properties.mode() == TradingMode.BACKTEST) {
            log.info("Algo scan skipped: configured mode is BACKTEST");
            return;
        }
        if (tradingStateService.marketDataMode() == MarketDataMode.ZERODHA && !tokenStore.authenticated()) {
            log.info("Algo scan skipped: Zerodha access token is missing. Set KITE_ACCESS_TOKEN or call /auth/kite/session before /start.");
            return;
        }
        if (!scanInProgress.compareAndSet(false, true)) {
            log.info("Algo scan skipped: previous scan is still running");
            return;
        }

        try {
            // REST fallback: simulate all three candle-close timeframes in sequence.
            // Each runScan() applies market-window and market-guard checks before evaluating.
            runScan(Timeframe.ONE_MINUTE);    // directional buy + 1-min strategies
            runScan(Timeframe.FIVE_MINUTE);   // scalping and other 5-min strategies
            runScan(Timeframe.FIFTEEN_MINUTE); // volatility breakout, spreads, event-driven
        } catch (Exception ex) {
            log.warn("Algo scan failed: {}", ex.getMessage(), ex);
            if (errorEventService != null) errorEventService.high("AlgoTradeExecution", "Algo REST scan failed: " + ex.getMessage(), ex);
        } finally {
            scanInProgress.set(false);
            if (schedulerRegistry != null) schedulerRegistry.recordRun("algoScan");
        }
    }

    /**
     * @param triggerTimeframe the candle timeframe that triggered this scan.
     *   ONE_MINUTE  → Directional Buy only
     *   FIVE_MINUTE → Scalping only
     *   FIFTEEN_MINUTE → Volatility Breakout + Spreads + Event-Driven
     *   (REST fallback passes ONE_MINUTE to run all strategies)
     */
    private void runScan(Timeframe triggerTimeframe) {
        LocalTime marketTime = LocalTime.now(properties.timezone());

        // Skip scan outside configured entry window
        if (marketTime.isBefore(globalConfigService.getEntryStartTime())
                || marketTime.isAfter(globalConfigService.getForcedExitTime())) {
            log.debug("Algo scan skipped: outside trading window ({}, allowed {}-{})",
                    marketTime, globalConfigService.getEntryStartTime(), globalConfigService.getForcedExitTime());
            return;
        }

        // Market guard safety: circuit breaker, event day, VIX
        String blockReason = marketGuard.longPremiumBlockReason();
        if (blockReason != null) {
            log.info("Algo scan skipped: MarketGuard blocked — {}", blockReason);
            return;
        }

        List<UnderlyingSymbol> enabledUnderlyings = tradingStateService.enabledUnderlyings();
        tradingStateService.recordScan();
        log.info("Algo scan started: mode={}, marketTime={}, underlyings={}, trigger={}",
                properties.mode(), marketTime, enabledUnderlyings, triggerTimeframe);

        scanContextBuilder.ensureInstrumentsLoaded();

        // ═══════════════════════════════════════════════════════════════════════
        // BEST-INDEX SELECTION — for each strategy, determine which index has
        // the highest environment score. Only the winning index proceeds to
        // strategy evaluation. This prevents the same strategy from spraying
        // across multiple correlated indices in a single scan cycle.
        // ═══════════════════════════════════════════════════════════════════════
        int maxPerStrategy = globalConfigService.getMaxOpenPositionsPerStrategy();
        // Map: StrategyType → best UnderlyingSymbol (highest environment score)
        Map<StrategyType, UnderlyingSymbol> bestIndexPerStrategy = new EnumMap<>(StrategyType.class);
        if (maxPerStrategy > 0) {
            // Collect environment scores for each strategy × index combination
            Map<StrategyType, Map<UnderlyingSymbol, Integer>> strategyIndexScores = new EnumMap<>(StrategyType.class);

            for (UnderlyingSymbol underlying : enabledUnderlyings) {
                List<StrategyConfig> configs = strategyConfigService.getEnabledFor(underlying);
                // Include DIRECTIONAL_BUY if enabled
                boolean hasDb = configs.stream().anyMatch(c -> c.getStrategyType() == StrategyType.DIRECTIONAL_BUY);
                if (!hasDb) {
                    StrategyConfig dbConfig = strategyConfigService.getDirectionalBuyConfig(underlying.name());
                    if (dbConfig.isEnabled()) {
                        configs = new ArrayList<>(configs);
                        configs.add(dbConfig);
                    }
                }

                for (StrategyConfig config : configs) {
                    StrategyType type = config.getStrategyType();

                    // Skip if strategy already has open positions at limit
                    long openForThisStrategy = executionEngine.countOpenTradesForStrategy(type.name());
                    if (openForThisStrategy >= maxPerStrategy) continue;

                    // Quick environment score for this underlying (lightweight — no full strategy eval)
                    var flowDecision = algoFlowOrchestrator.evaluateEntry(underlying, marketTime, type);
                    if (!flowDecision.allowed()) continue;

                    int envScore = flowDecision.environmentScore();
                    // Treat -1 (computation failed) as 0
                    if (envScore < 0) envScore = 0;

                    // Compute lightweight signal quality proxy
                    Timeframe proxyTimeframe = resolveTimeframe(config.getCandleTimeframe(), Timeframe.ONE_MINUTE);
                    int signalProxy = computeSignalQualityProxy(underlying, proxyTimeframe);

                    // Combined ranking: signal quality (60%) + environment (40%)
                    int combinedScore = (int) Math.round(signalProxy * 0.6 + envScore * 0.4);

                    strategyIndexScores
                        .computeIfAbsent(type, k -> new EnumMap<>(UnderlyingSymbol.class))
                        .put(underlying, combinedScore);

                    log.debug("[BestIndex] Pre-eval: {} on {} → signal={}, env={}, combined={}",
                        type, underlying, signalProxy, envScore, combinedScore);
                }
            }

            // For each strategy, pick the index with highest environment score
            for (var entry : strategyIndexScores.entrySet()) {
                StrategyType type = entry.getKey();
                Map<UnderlyingSymbol, Integer> scores = entry.getValue();
                if (scores.isEmpty()) continue;

                UnderlyingSymbol best = scores.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse(null);
                if (best != null) {
                    bestIndexPerStrategy.put(type, best);
                    log.debug("[BestIndex] {} → {} (combinedScore={}, candidates={})",
                        type, best, scores.get(best), scores.keySet());
                }
            }

            log.info("[BestIndex] Selection: {}", bestIndexPerStrategy);
        }

        // ═══════════════════════════════════════════════════════════════════════
        // UNIFIED STRATEGY LOOP — single entry point for ALL strategies
        // ═══════════════════════════════════════════════════════════════════════
        int totalEntries = 0;
        // Track entries per underlying within this scan to prevent concentration
        Map<UnderlyingSymbol, Integer> entriesPerUnderlying = new EnumMap<>(UnderlyingSymbol.class);
        for (UnderlyingSymbol underlying : enabledUnderlyings) {
            if (totalEntries >= globalConfigService.getMaxEntriesPerScan()) break;

            // Get ALL enabled strategies for this underlying (including DIRECTIONAL_BUY)
            List<StrategyConfig> allConfigs = strategyConfigService.getEnabledFor(underlying);
            // Also include DIRECTIONAL_BUY if it's enabled (it may not be in strategy_configs yet)
            boolean hasDirectionalBuy = allConfigs.stream()
                    .anyMatch(c -> c.getStrategyType() == StrategyType.DIRECTIONAL_BUY);
            if (!hasDirectionalBuy) {
                StrategyConfig dbConfig = strategyConfigService.getDirectionalBuyConfig(underlying.name());
                if (dbConfig.isEnabled()) {
                    allConfigs = new ArrayList<>(allConfigs);
                    allConfigs.add(0, dbConfig); // DIRECTIONAL_BUY first (highest priority)
                }
            }

            if (allConfigs.isEmpty()) continue;

            // Expiry-day filter: after 1 PM only allow expiry-specific strategies
            IndexType idx = com.algo.trade.domain.IndexType.from(underlying);
            boolean expiryAfternoon = expiryCalendar.isExpiryDay(idx) && marketTime.isAfter(LocalTime.of(13, 0));

            // Pre-compute shared data once per underlying
            Map<Timeframe, List<Candle>> candleCache = new java.util.EnumMap<>(Timeframe.class);
            List<Candle> defaultCandles = underlyingLiveCandles(underlying, Timeframe.FIVE_MINUTE);
            IvRankResult ivRankResult = computeLiveIvRank(underlying, defaultCandles);
            double ivRank = ivRankResult.rank();
            String ivRankSource = ivRankResult.source();

            for (StrategyConfig config : allConfigs) {
                if (totalEntries >= globalConfigService.getMaxEntriesPerScan()) break;
                // Max entries per underlying per scan cycle — configurable from GlobalConfig
                // Prevents multiple strategies from piling into the same underlying in a single candle close
                int maxPerUnderlying = globalConfigService.getMaxEntriesPerScanPerUnderlying();
                if (entriesPerUnderlying.getOrDefault(underlying, 0) >= maxPerUnderlying) break;

                StrategyType type = config.getStrategyType();

                // ── Gate 1: Expiry afternoon filter ──
                if (expiryAfternoon && type != StrategyType.EXPIRY_GAMMA && type != StrategyType.EXPIRY_REVERSAL) {
                    log.debug("{} skipped: expiry day after 1 PM for {}", type, underlying);
                    continue;
                }

                // ── Gate 2: Timeframe match ──
                if (!matchesConfiguredTimeframe(config, triggerTimeframe)
                        && type != StrategyType.DIRECTIONAL_BUY) {
                    // DIRECTIONAL_BUY has its own timeframe logic inside evaluateAndExecute
                    continue;
                }
                // DIRECTIONAL_BUY only runs on 1-min trigger
                if (type == StrategyType.DIRECTIONAL_BUY && triggerTimeframe != Timeframe.ONE_MINUTE) {
                    continue;
                }

                // ── Gate 3: Algo Flow Orchestrator — unified pre-trade filter pipeline ──
                var flowDecision = algoFlowOrchestrator.evaluateEntry(underlying, marketTime, type);
                if (!flowDecision.allowed()) {
                    log.info("{} blocked by AlgoFlow: underlying={} reason={}", type, underlying, flowDecision.blockReason());
                    Timeframe candleTf = resolveTimeframe(config.getCandleTimeframe(), Timeframe.ONE_MINUTE);
                    List<Candle> spotCandles = candleCache.computeIfAbsent(candleTf,
                            tf -> underlyingLiveCandles(underlying, tf));
                    BigDecimal spotPrice = spotCandles.isEmpty() ? BigDecimal.ZERO : spotCandles.getLast().close();
                    signalCsvRecorder.recordAdditionalNoTrade(
                            type.name(), underlying.name(), spotPrice,
                            "AlgoFlow:" + flowDecision.blockReason(), spotCandles,
                            com.algo.trade.strategy.StrategyDiagnostics.NONE, ivRank);
                    continue;
                }

                // ── Gate: Best-index selection — only allow the winning index for this strategy ──
                if (maxPerStrategy > 0 && !bestIndexPerStrategy.isEmpty()) {
                    UnderlyingSymbol bestIndex = bestIndexPerStrategy.get(type);
                    if (bestIndex != null && !bestIndex.equals(underlying)) {
                        log.debug("{} skipped for {}: best index is {} (best-index selection)",
                                type, underlying, bestIndex);
                        continue;
                    }
                    // Also check if strategy already has open positions at limit
                    long openForThisStrategy = executionEngine.countOpenTradesForStrategy(type.name());
                    if (openForThisStrategy >= maxPerStrategy) {
                        log.info("{} skipped for {}: already has {} open position(s) (max={})",
                                type, underlying, openForThisStrategy, maxPerStrategy);
                        continue;
                    }
                } else if (maxPerStrategy > 0) {
                    // Fallback: bestIndexPerStrategy is empty (no candidates passed pre-eval)
                    // Still enforce the max open positions per strategy limit
                    long openForThisStrategy = executionEngine.countOpenTradesForStrategy(type.name());
                    if (openForThisStrategy >= maxPerStrategy) {
                        log.info("{} skipped for {}: already has {} open position(s) (max={})",
                                type, underlying, openForThisStrategy, maxPerStrategy);
                        continue;
                    }
                }

                // ── Gate 4: Strategy-specific pre-checks ──
                try {
                    int entries = evaluateStrategy(type, config, underlying, marketTime,
                            triggerTimeframe, candleCache, ivRank, ivRankSource, flowDecision);
                    totalEntries += entries;
                    entriesPerUnderlying.merge(underlying, entries, Integer::sum);
                } catch (Exception e) {
                    log.warn("Strategy evaluation failed: type={} underlying={}: {}", type, underlying, e.getMessage());
                    if (errorEventService != null) errorEventService.medium("AlgoTradeExecution", "Strategy " + type + " evaluation failed for " + underlying + ": " + e.getMessage(), e);
                }
            }

            // Evict stale quote keys after processing this underlying
            if (candleCache.containsKey(Timeframe.ONE_MINUTE)) {
                // Only for DIRECTIONAL_BUY which uses previousQuotes
            }
        }

        log.info("Algo scan completed: totalEntries={}", totalEntries);
    }

    /**
     * Unified strategy evaluation — single entry point for ALL strategy types.
     * DIRECTIONAL_BUY uses its own ScanContext + evaluateAndExecute path.
     * All other strategies use the StrategyExecutionPipeline.
     *
     * @return number of entries submitted (0 or 1)
     */
    private int evaluateStrategy(StrategyType type, StrategyConfig config, UnderlyingSymbol underlying,
                                  LocalTime marketTime, Timeframe triggerTimeframe,
                                  Map<Timeframe, List<Candle>> candleCache,
                                  double ivRank, String ivRankSource,
                                  AlgoFlowOrchestrator.EntryDecision flowDecision) {

        // ── DIRECTIONAL_BUY — uses its own ScanContext path ──
        if (type == StrategyType.DIRECTIONAL_BUY) {
            String[] failReason = {"buildScanContext:unknown"};
            Optional<ScanContext> context = scanContextBuilder.build(underlying, failReason);
            if (context.isEmpty()) {
                List<Candle> spotCandles = underlyingLiveCandles(underlying, Timeframe.ONE_MINUTE);
                BigDecimal spotPrice = spotCandles.isEmpty() ? BigDecimal.ZERO : spotCandles.getLast().close();
                signalCsvRecorder.recordAdditionalNoTrade(
                        "DIRECTIONAL_BUY", underlying.name(), spotPrice,
                        failReason[0], spotCandles,
                        com.algo.trade.strategy.StrategyDiagnostics.NONE, 0.0);
                return 0;
            }
            int entries = evaluateAndExecute(underlying, context.get(), marketTime, 1, ivRank);
            scanContextBuilder.updatePreviousQuotes(context.get().quotes());
            return entries;
        }

        // ── All other strategies — unified path ──
        Timeframe candleTf = resolveTimeframe(config.getCandleTimeframe(), Timeframe.ONE_MINUTE);
        Timeframe trendTf = resolveTimeframe(config.getTrendTimeframe(), Timeframe.FIVE_MINUTE);
        List<Candle> strategyCandles = candleCache.computeIfAbsent(candleTf,
                tf -> underlyingLiveCandles(underlying, tf));
        List<Candle> trendCandles = candleCache.computeIfAbsent(trendTf,
                tf -> underlyingLiveCandles(underlying, tf));

        // VB theta guard: don't buy options within 3 days of expiry
        IndexType idx = com.algo.trade.domain.IndexType.from(underlying);
        if (type == StrategyType.VOLATILITY_BREAKOUT
                && expiryCalendar.isNearExpiry(idx, 3)
                && !expiryCalendar.isExpiryDay(idx)) {
            log.debug("VB theta guard: skipping {} — {} days to expiry", underlying, expiryCalendar.daysToExpiry(idx));
            BigDecimal vbSpotPrice = strategyCandles.isEmpty() ? BigDecimal.ZERO : strategyCandles.getLast().close();
            String vbReason = "Theta guard: " + expiryCalendar.daysToExpiry(idx) + " days to expiry (max 3)";
            com.algo.trade.strategy.StrategyDiagnostics thetaDiag =
                    new com.algo.trade.strategy.StrategyDiagnostics("thetaGuard", null, null, null, null, null, null, null, null);
            for (OptionType ot : new OptionType[]{OptionType.CE, OptionType.PE}) {
                Instrument vbAtm = scanContextBuilder.resolveAtmInstrument(underlying, ot, vbSpotPrice).orElse(null);
                StrategyDecisionEntity thetaNoTrade = StrategyDecisionEntity.forStrategy(
                        type.name(), Instant.now(), underlying.name(), "NO_TRADE",
                        ot.name(), vbSpotPrice, BigDecimal.ZERO, vbReason);
                thetaNoTrade.setPaperTrade(config.isPaperTrading());
                thetaNoTrade.setIvRank(ivRank);
                thetaNoTrade.setFirstFailedFilter("thetaGuard");
                thetaNoTrade.setExecutionStage("NO_TRADE");
                thetaNoTrade.setExecutionReason(vbReason);
                if (vbAtm != null) {
                    thetaNoTrade.setSelectedInstrumentKey(vbAtm.instrumentKey());
                    thetaNoTrade.setSelectedStrike(vbAtm.strike().orElse(null));
                }
                decisionRepository.save(thetaNoTrade);
                signalCsvRecorder.recordAdditionalNoTrade(type.name(), underlying.name(), vbSpotPrice,
                        vbReason, trendCandles, thetaDiag, ivRank,
                        vbAtm != null ? vbAtm.instrumentKey() : null,
                        vbAtm != null ? vbAtm.strike().orElse(null) : null);
            }
            return 0;
        }

        com.algo.trade.strategy.StrategyDiagnostics[] diagHolder =
                {com.algo.trade.strategy.StrategyDiagnostics.NONE};
        boolean[] evaluated = {false};

        Optional<StrategyDecision> signal = switch (type) {
            case SCALPING -> {
                evaluated[0] = true;
                var result = scalpingStrategy.evaluateWithDiagnostics(strategyCandles, marketTime, config, underlying);
                diagHolder[0] = result.diagnostics();
                yield result.signal();
            }
            case VOLATILITY_BREAKOUT -> {
                evaluated[0] = true;
                var result = volatilityBreakoutStrategy.evaluateWithDiagnostics(trendCandles, ivRank, config, underlying, marketTime);
                diagHolder[0] = result.diagnostics();
                yield result.signal();
            }
            case EVENT_DRIVEN_BUY -> {
                evaluated[0] = true;
                BigDecimal eventSpotPrice = trendCandles.isEmpty() ? BigDecimal.ZERO : trendCandles.getLast().close();
                yield eventDrivenBuyStrategy.evaluate(ivRank, config, underlying, eventSpotPrice);
            }
            case BULL_CALL_SPREAD, BEAR_PUT_SPREAD,
                 LONG_STRADDLE, LONG_STRANGLE,
                 SHORT_STRADDLE, SHORT_STRANGLE,
                 IRON_CONDOR, BUTTERFLY, CALENDAR_SPREAD,
                 DIAGONAL_SPREAD, JADE_LIZARD, SYNTHETIC_FUTURES -> {
                evaluated[0] = true;
                AbstractSpreadStrategy spreadStrategy = spreadStrategyMap.get(type);
                if (spreadStrategy == null) {
                    yield spreadStrategyEvaluator.evaluate(trendCandles, ivRank, config, underlying);
                }
                BigDecimal spotPrice = trendCandles.isEmpty()
                        ? BigDecimal.ZERO : trendCandles.getLast().close();
                com.algo.trade.domain.IndexType spreadIdx =
                        com.algo.trade.domain.IndexType.from(underlying);
                com.algo.trade.domain.SpreadEvaluationContext spreadCtx =
                        new com.algo.trade.domain.SpreadEvaluationContext(
                                spotPrice, ivRank, null, config, underlying, spreadIdx, trendCandles);
                yield spreadStrategy.evaluateAndEnter(spreadCtx);
            }
            case GAP_AND_GO -> {
                evaluated[0] = true;
                var ggResult = gapAndGoStrategy.evaluateWithDiagnostics(strategyCandles, marketTime, config, underlying);
                diagHolder[0] = ggResult.diagnostics();
                yield ggResult.signal();
            }
            case REVERSAL_BUY -> {
                evaluated[0] = true;
                var rbResult = reversalBuyStrategy.evaluateWithDiagnostics(trendCandles, ivRank, config, underlying);
                diagHolder[0] = rbResult.diagnostics();
                yield rbResult.signal();
            }
            case OI_SHIFT_TRAP -> {
                evaluated[0] = true;
                String[] oiFailReason = {"buildScanContext:unknown"};
                Optional<ScanContext> oiCtx = scanContextBuilder.build(underlying, oiFailReason);
                if (oiCtx.isEmpty()) {
                    diagHolder[0] = new com.algo.trade.strategy.StrategyDiagnostics(oiFailReason[0], null, null, null, null, null, null, null, null);
                    yield Optional.<StrategyDecision>empty();
                }
                BigDecimal oiSpot = trendCandles.isEmpty() ? BigDecimal.ZERO : trendCandles.getLast().close();
                yield oiShiftTrapStrategy.evaluate(oiCtx.get().optionChainSnapshot(), oiSpot, config, underlying, trendCandles);
            }
            case EXPIRY_GAMMA -> {
                evaluated[0] = true;
                var egResult = expiryGammaStrategy.evaluateWithDiagnostics(strategyCandles, marketTime, config, underlying);
                diagHolder[0] = egResult.diagnostics();
                yield egResult.signal();
            }
            case EXPIRY_REVERSAL -> {
                evaluated[0] = true;
                var erResult = expiryReversalStrategy.evaluateWithDiagnostics(strategyCandles, marketTime, config, underlying);
                diagHolder[0] = erResult.diagnostics();
                yield erResult.signal();
            }
            case MOMENTUM -> {
                evaluated[0] = true;
                var momResult = momentumStrategy.evaluateWithDiagnostics(strategyCandles, marketTime, config, underlying);
                diagHolder[0] = momResult.diagnostics();
                yield momResult.signal();
            }
            default -> Optional.empty();
        };

        // Spread strategies: persist signal but don't execute via pipeline
        if (signal.isPresent() && spreadStrategyMap.containsKey(type)) {
            StrategyDecisionEntity spreadEntity = persistStrategyDecision(signal.get(), type.name(), config, ivRankSource);
            spreadEntity.setExecutionStage("NOT_EXECUTED");
            spreadEntity.setExecutionReason("Spread recorded — multi-leg broker execution not yet implemented");
            decisionRepository.save(spreadEntity);
            log.info("Spread entry recorded: type={} groupId={}", type, signal.get().selectedInstrumentKey().orElse(""));
            return 0;
        }

        // All non-spread strategies: unified pipeline post-processing
        if (evaluated[0]) {
            StrategyContext pipelineCtx = buildLiteStrategyContext(
                    underlying, marketTime, strategyCandles, trendCandles,
                    ivRank, ivRankSource, candleTf, trendTf);
            boolean executed = executionPipeline.process(
                    new com.algo.trade.strategy.StrategyDiagnostics.WithSignal(signal, diagHolder[0]),
                    pipelineCtx, config, type, ivRankSource);
            return executed ? 1 : 0;
        }

        return 0;
    }

    /** Persist a signal from any strategy type to H2. */
    private StrategyDecisionEntity persistStrategyDecision(StrategyDecision decision, String strategyType, StrategyConfig config) {
        return persistStrategyDecision(decision, strategyType, config, null);
    }

    private StrategyDecisionEntity persistStrategyDecision(StrategyDecision decision, String strategyType, StrategyConfig config, String ivRankSrc) {
        StrategyDecisionEntity entity = StrategyDecisionEntity.forStrategy(
                strategyType,
                decision.timestamp(),
                decision.underlying().name(),
                decision.signalType().name(),
                decision.optionType().map(Enum::name).orElse(null),
                decision.underlyingPrice(),
                decision.confidenceScore(),
                String.join("; ", decision.reasons())
        );
        entity.setPaperTrade(config.isPaperTrading());
        entity.setSelectedInstrumentKey(decision.selectedInstrumentKey().orElse(null));
        entity.setSelectedStrike(decision.selectedStrike().orElse(null));
        entity.setOptionPrice(decision.optionPrice().orElse(null));
        entity.setLotSize(config.getLots());
        // Directional fields
        if (decision.vwapConditionPassed()) entity.setVwapConditionPassed(true);
        if (decision.volumeSpike()) entity.setVolumeSpike(true);
        decision.imbalance().ifPresent(entity::setImbalance);
        decision.optionOpenInterest().ifPresent(entity::setOptionOpenInterest);

        // Strategy-specific fields — parse from reasons text
        for (String reason : decision.reasons()) {
            if (reason.startsWith("EMA9=")) {
                try {
                    String[] parts = reason.split("\\s+");
                    for (String p : parts) {
                        if (p.startsWith("EMA9=")) entity.setFastEma(Double.parseDouble(p.substring(5)));
                        if (p.startsWith("EMA21=")) entity.setSlowEma(Double.parseDouble(p.substring(6)));
                    }
                } catch (NumberFormatException ignored) {}
            }
            if (reason.startsWith("BB squeeze breakout: bandwidth=")) {
                try {
                    String bw = reason.substring("BB squeeze breakout: bandwidth=".length()).replace("%", "");
                    entity.setBollingerBandwidth(Double.parseDouble(bw));
                } catch (NumberFormatException ignored) {}
            }
            if (reason.startsWith("IV rank=")) {
                try {
                    String iv = reason.substring("IV rank=".length()).split("\\s")[0];
                    entity.setIvRank(Double.parseDouble(iv));
                } catch (NumberFormatException ignored) {}
            }
        }

        // Capture IV rank source
        if (ivRankSrc != null) entity.setIvRankSource(ivRankSrc);

        // Capture config snapshot for replay/audit
        entity.setConfigSnapshot(configToSnapshot(config));

        // Capture bid/ask/ATP from live WebSocket cache for the selected instrument
        decision.selectedInstrumentKey().ifPresent(key -> {
            marketDataService.quote(key).ifPresent(q -> {
                q.bid().ifPresent(entity::setOptionBid);
                q.ask().ifPresent(entity::setOptionAsk);
                q.averageTradedPrice().ifPresent(entity::setOptionAtp);
            });
        });

        return decisionRepository.save(entity);
    }


    // buildScanContext and related option chain methods extracted to ScanContextBuilder

    private int evaluateAndExecute(
            UnderlyingSymbol underlying,
            ScanContext context,
            LocalTime marketTime,
            int remainingEntries,
            double ivRank
    ) {
        int entriesSubmitted = 0;
        List<EntryCandidate> candidates = new ArrayList<>();
        BigDecimal dbSpotPrice = context.spotQuote().lastPrice();
        StrategyConfig dbConfig = strategyConfigService.getDirectionalBuyConfig(underlying.name());
        for (Map.Entry<OptionType, Instrument> entry : context.selectedOptions().entrySet()) {
            if (!optionTypeEnabled(entry.getKey())) {
                log.info("Strategy evaluation skipped: option type disabled for live execution, underlying={}, optionType={}, enabledOptionTypes={}",
                        underlying, entry.getKey(), globalConfigService.getEnabledOptionTypes());
                signalCsvRecorder.recordAdditionalNoTrade("DIRECTIONAL_BUY", underlying.name(), dbSpotPrice,
                        "optionTypeDisabled(" + entry.getKey() + ")", List.of(),
                        com.algo.trade.strategy.StrategyDiagnostics.NONE, 0.0);
                continue;
            }
            Instrument selectedInstrument = entry.getValue();
            Quote selectedQuote = context.quotes().get(selectedInstrument.instrumentKey());
            if (selectedQuote == null) {
                log.warn("Strategy evaluation skipped: selected option quote missing, instrument={}",
                        selectedInstrument.instrumentKey());
                signalCsvRecorder.recordAdditionalNoTrade("DIRECTIONAL_BUY", underlying.name(), dbSpotPrice,
                        "missingOptionQuote", List.of(),
                        com.algo.trade.strategy.StrategyDiagnostics.NONE, 0.0);
                continue;
            }

            // Use Directional Buy's per-strategy config for candle timeframes (loaded once above loop)
            Timeframe dbCandleTf = resolveTimeframe(dbConfig.getCandleTimeframe(), Timeframe.ONE_MINUTE);
            Timeframe dbTrendTf = resolveTimeframe(dbConfig.getTrendTimeframe(), Timeframe.FIVE_MINUTE);

            List<Candle> underlyingCandles = underlyingLiveCandles(underlying, dbCandleTf);
            List<Candle> trendUnderlyingCandles = underlyingLiveCandles(underlying, dbTrendTf);
            // Option candles from REST are often empty/stale — build a synthetic candle from live WebSocket data
            List<Candle> optionCandles = buildOptionCandles(selectedInstrument, selectedQuote, dbCandleTf);
            if (underlyingCandles.isEmpty() || trendUnderlyingCandles.isEmpty() || optionCandles.isEmpty()) {
                log.warn("Strategy evaluation skipped: missing candles, underlying={}, instrument={}, underlyingCandles={}, trendUnderlyingCandles={}, optionCandles={}",
                        underlying, selectedInstrument.instrumentKey(), underlyingCandles.size(),
                        trendUnderlyingCandles.size(), optionCandles.size());
                signalCsvRecorder.recordAdditionalNoTrade("DIRECTIONAL_BUY", underlying.name(), dbSpotPrice,
                        "missingCandles(u=" + underlyingCandles.size() + ",t=" + trendUnderlyingCandles.size() + ",o=" + optionCandles.size() + ")",
                        underlyingCandles, com.algo.trade.strategy.StrategyDiagnostics.NONE, 0.0);
                continue;
            }
            if (!freshQuote(context.spotQuote()) || !freshQuote(selectedQuote) || !freshCandles(underlyingCandles) || !freshCandles(trendUnderlyingCandles)) {
                String staleKey = underlying.name();
                Instant lastWarn = lastStaleWarnTime.get(staleKey);
                if (lastWarn == null || lastWarn.plus(STALE_WARN_DEBOUNCE).isBefore(Instant.now())) {
                    log.warn("Strategy evaluation skipped: stale market data, underlying={}, instrument={}, spotQuoteTime={}, optionQuoteTime={}, latestUnderlyingCandleTime={}, latestTrendCandleTime={}, threshold={}",
                            underlying, selectedInstrument.instrumentKey(), context.spotQuote().timestamp(),
                            selectedQuote.timestamp(), latestCandleTimestamp(underlyingCandles).orElse(null),
                            latestCandleTimestamp(trendUnderlyingCandles).orElse(null), properties.safety().staleMarketDataThreshold());
                    lastStaleWarnTime.put(staleKey, Instant.now());
                }
                signalCsvRecorder.recordAdditionalNoTrade("DIRECTIONAL_BUY", underlying.name(), dbSpotPrice,
                        "staleMarketData", underlyingCandles,
                        com.algo.trade.strategy.StrategyDiagnostics.NONE, 0.0);
                continue;
            }

            Instant evaluationTimestamp = Instant.now();

            // Greeks from LiveInstrumentCache (updated on every WebSocket tick)
            String symbol = selectedInstrument.instrumentKey().contains(":")
                    ? selectedInstrument.instrumentKey().split(":", 2)[1]
                    : selectedInstrument.instrumentKey();
            com.algo.trade.domain.OptionInstrument liveOption = liveInstrumentCache.getBySymbol(symbol).orElse(null);
            double optDelta = liveOption != null ? liveOption.getDelta() : 0.0;
            double optGamma = liveOption != null ? liveOption.getGamma() : 0.0;
            double optTheta = liveOption != null ? liveOption.getTheta() : 0.0;
            double optVega  = liveOption != null ? liveOption.getVega()  : 0.0;

            // Realized volatility from underlying candles (5-day)
            double rv5d = realizedVolatilityCalculator.calculate5Day(underlyingCandles);

            // IV skew: 1-strike OTM put IV minus 1-strike OTM call IV
            double ivSkew = computeIvSkew(underlying, entry.getKey(), selectedInstrument,
                    context.optionChainSnapshot());

            // Persist Greeks snapshot to DB for time-series analysis
            persistGreeksSample(underlying, entry.getKey(), selectedInstrument,
                    evaluationTimestamp, optDelta, optGamma, optTheta, optVega,
                    liveOption, dbSpotPrice);

            StrategyEvaluationRequest request = new StrategyEvaluationRequest(
                    evaluationTimestamp,
                    IstDateTimes.istTime(evaluationTimestamp),
                    underlying,
                    underlyingCandles,
                    trendUnderlyingCandles,
                    optionCandles,
                    context.optionChainSnapshot(),
                    selectedInstrument.instrumentKey(),
                    selectedInstrument.strike().orElse(null),
                    selectedInstrument.lotSize(),
                    entry.getKey(),
                    selectedQuote,
                    Optional.ofNullable(scanContextBuilder.getPreviousQuotes().get(selectedInstrument.instrumentKey())),
                    ivRank,
                    marketGuard.getCurrentVix(),
                    expiryCalendar.daysToExpiry(com.algo.trade.domain.IndexType.fromName(underlying.name())),
                    optDelta, optGamma, optTheta, optVega, rv5d, ivSkew
            );

            StrategyDecision decision = strategy.evaluateEntry(request);

            // ML shadow recording — observe ML score without affecting the decision
            String decisionKey = Integer.toUnsignedString(
                    (request.timestamp() + "|" + request.underlying() + "|" + entry.getKey() + "|" + request.selectedInstrumentKey()).hashCode(), 16);
            double mlSpread = selectedQuote.ask().orElse(BigDecimal.ZERO)
                    .subtract(selectedQuote.bid().orElse(BigDecimal.ZERO)).doubleValue();
            StrategyContext mlCtx = buildLiteStrategyContext(underlying, request.marketTime(),
                    underlyingCandles, trendUnderlyingCandles, ivRank, null,
                    dbCandleTf, dbTrendTf);
            executionPipeline.recordMlShadow(decision, mlCtx, underlyingCandles, decisionKey,
                    String.valueOf(mlSpread), optDelta, optGamma, optTheta, optVega, rv5d, ivSkew);

            if (decision.signalType().name().startsWith("BUY_")) {
                log.info("Algo scan generated entry signal: underlying={}, instrument={}, signalType={}, premium={}",
                        underlying, selectedInstrument.instrumentKey(), decision.signalType(), selectedQuote.lastPrice());
                log.info("Entry candidate details: instrument={}, token={}, parsedStrike={}, optionType={}, confidenceScore={}",
                        selectedInstrument.instrumentKey(), selectedInstrument.instrumentToken(),
                        selectedInstrument.strike().orElse(null), entry.getKey(), decision.confidenceScore());
                candidates.add(new EntryCandidate(decision, selectedQuote, selectedInstrument));
            } else {
                persistNoTradeDecision(decision);
                log.info("Algo scan no-trade decision: underlying={}, instrument={}, optionType={}, reasons={}",
                        underlying, selectedInstrument.instrumentKey(), entry.getKey(), decision.reasons());
            }
        }

        List<EntryCandidate> selectedCandidates = candidates.stream()
                .sorted(Comparator.comparing((EntryCandidate candidate) -> candidate.decision().confidenceScore()).reversed())
                .limit(remainingEntries)
                .toList();
        for (EntryCandidate candidate : selectedCandidates) {
            if (dbConfig.isPaperTrading()) {
                executionEngine.executePaperEntry(candidate.decision(), candidate.quote().lastPrice(),
                        candidate.instrument().lotSize(), dbConfig);
            } else {
                executionEngine.executeEntry(candidate.decision(), candidate.quote().lastPrice(),
                        candidate.instrument().lotSize(), dbConfig);
            }
            entriesSubmitted++;
        }
        return entriesSubmitted;
    }

    private boolean optionTypeEnabled(OptionType optionType) {
        return globalConfigService.getEnabledOptionTypes().contains(optionType);
    }

    private List<Candle> candles(String instrumentKey, Timeframe timeframe) {
        if (instrumentKey == null || instrumentKey.isBlank()) {
            return List.of();
        }
        String cacheKey = instrumentKey + ":" + timeframe.name();
        CachedCandles cached = candleRestCache.get(cacheKey);
        if (cached != null && cached.fetchedAt().plus(CANDLE_CACHE_TTL).isAfter(Instant.now())) {
            return cached.candles();
        }
        Instant to = Instant.now();
        Instant from = to.minus(timeframe.duration().multipliedBy(properties.algo().candleLookback()));
        try {
            List<Candle> result = marketDataService.historicalCandles(
                    new HistoricalDataRequest(instrumentKey, from, to, timeframe, true));
            if (!result.isEmpty()) {
                candleRestCache.put(cacheKey, new CachedCandles(result, Instant.now()));
            }
            return result;
        } catch (Exception ex) {
            log.warn("Historical candle request failed: instrumentKey={}, timeframe={}, message={}",
                    instrumentKey, timeframe, ex.getMessage());
            if (errorEventService != null) errorEventService.medium("AlgoTradeExecution", "Historical candle fetch failed: " + instrumentKey + " " + timeframe + ": " + ex.getMessage());
            // Return stale cache rather than empty — stale candles are better than no candles
            return cached != null ? cached.candles() : List.of();
        }
    }

    private boolean freshQuote(Quote quote) {
        return quote.timestamp().plus(properties.safety().staleMarketDataThreshold()).isAfter(Instant.now());
    }


    private boolean freshCandles(List<Candle> candles) {
        if (candles.isEmpty()) {
            return false;
        }
        Candle latest = candles.getLast();
        // Allow the last closed candle to remain valid for the entire next period.
        // Without this, a 5-min candle closing at 10:15 expires at 10:20:30 but the next
        // candle doesn't close until 10:25 — creating a 4.5-minute dead window where
        // all entries are blocked as "stale".
        Duration allowedAge = latest.timeframe().duration().multipliedBy(2).plus(properties.safety().staleMarketDataThreshold());
        return latest.timestamp().plus(allowedAge).isAfter(Instant.now());
    }

    private Optional<Instant> latestCandleTimestamp(List<Candle> candles) {
        return candles.isEmpty() ? Optional.empty() : Optional.of(candles.getLast().timestamp());
    }

    private String historicalKey(Instrument instrument) {
        if (tradingStateService.marketDataMode() == MarketDataMode.ZERODHA) {
            return String.valueOf(instrument.instrumentToken());
        }
        return instrument.instrumentKey();
    }

    /**
     * Returns underlying index candles from WebSocket live feed (always fresh).
     * Falls back to REST only when WebSocket history is empty (e.g., first minute after startup).
     */
    private List<Candle> underlyingLiveCandles(UnderlyingSymbol underlying, Timeframe timeframe) {
        long spotToken = com.algo.trade.domain.IndexType.from(underlying).spotToken();
        List<Candle> live = candleBuilder.getHistory(spotToken, timeframe);
        if (!live.isEmpty()) {
            return live;
        }
        // REST fallback — seed into LiveCandleBuilder so diagnostics and other components see them
        List<Candle> restCandles = candles(underlyingHistoricalKey(underlying), timeframe);
        if (!restCandles.isEmpty()) {
            candleBuilder.seedHistory(spotToken, timeframe, restCandles);
        }
        return restCandles;
    }

    private String underlyingHistoricalKey(UnderlyingSymbol underlying) {
        String configuredKey = properties.symbols().spotHistoricalKeys().get(underlying);
        if (configuredKey == null || configuredKey.isBlank() || tradingStateService.marketDataMode() != MarketDataMode.ZERODHA) {
            return configuredKey;
        }
        if (configuredKey.chars().allMatch(Character::isDigit)) {
            return configuredKey;
        }
        Optional<Instrument> instrument = instrumentCache.findByKey(configuredKey);
        if (instrument.isEmpty()) {
            log.warn("Underlying historical key could not be resolved to an instrument token: underlying={}, configuredKey={}",
                    underlying, configuredKey);
            return configuredKey;
        }
        return String.valueOf(instrument.get().instrumentToken());
    }

    private void persistNoTradeDecision(StrategyDecision decision) {
        StrategyDecisionEntity entity = new StrategyDecisionEntity(
                decision.timestamp(), decision.underlying().name(),
                decision.signalType().name(), decision.underlyingPrice(),
                decision.optionPrice().orElse(null),
                decision.optionOpenInterest().orElse(null),
                decision.lotSize().orElse(null),
                decision.lotPrice().orElse(null),
                decision.selectedInstrumentKey().orElse(null),
                decision.selectedStrike().orElse(null),
                decision.optionType().map(Enum::name).orElse(null),
                decision.vwapConditionPassed(),
                decision.imbalance().orElse(null),
                decision.volumeSpike(),
                decision.confidenceScore(),
                String.join("; ", decision.reasons()));
        entity.setStrategyType("DIRECTIONAL_BUY");
        entity.setExecutionStage("NO_TRADE");
        entity.setExecutionReason(String.join("; ", decision.reasons()));
        decisionRepository.save(entity);
    }

    /**
     * Build option candles from LiveCandleBuilder history (WebSocket ticks).
     * Falls back to a single synthetic candle from the live quote if no history yet.
     * This avoids the REST historical candle API for options which is often empty/delayed.
     */
    private List<Candle> buildOptionCandles(Instrument instrument, Quote liveQuote, Timeframe optionTf) {

        // Try LiveCandleBuilder history first
        List<Candle> history = candleBuilder.getHistory(
                instrument.instrumentToken(), optionTf);
        if (!history.isEmpty()) {
            log.info("buildOptionCandles: token={} symbol={} tf={} wsHistory={}",
                    instrument.instrumentToken(), instrument.tradingSymbol(), optionTf, history.size());
            return history;
        }
        // Fall back: single synthetic candle from live quote so scan is not skipped
        boolean hasOpenCandle = candleBuilder.hasOpenCandle(instrument.instrumentToken(), optionTf);
        log.info("buildOptionCandles: token={} symbol={} tf={} wsHistory=0 openCandle={} → synthetic fallback" +
                        " (openCandle=false means token is NOT subscribed to WebSocket)",
                instrument.instrumentToken(), instrument.tradingSymbol(), optionTf, hasOpenCandle);
        if (liveQuote != null && liveQuote.lastPrice() != null && liveQuote.lastPrice().signum() > 0) {
            Candle synthetic = new Candle(
                    instrument.instrumentKey(), liveQuote.timestamp(),
                    optionTf,
                    liveQuote.lastPrice(), liveQuote.lastPrice(),
                    liveQuote.lastPrice(), liveQuote.lastPrice(),
                    liveQuote.volume(), liveQuote.openInterest());
            return List.of(synthetic);
        }
        return List.of();
    }

    private BigDecimal nearestStrike(List<BigDecimal> strikes, BigDecimal price) {
        return strikes.stream()
                .min(Comparator.comparing(strike -> strike.subtract(price).abs()))
                .orElse(price);
    }

    private int indexOfStrike(List<BigDecimal> strikes, BigDecimal target) {
        for (int i = 0; i < strikes.size(); i++) {
            if (strikes.get(i).compareTo(target) == 0) return i;
        }
        return -1;
    }

    /**
     * Check if the trigger timeframe matches the strategy's configured scan timeframe.
     */
    private boolean matchesConfiguredTimeframe(StrategyConfig config, Timeframe triggerTimeframe) {
        String configured = config.getScanTimeframe();
        if (configured == null || configured.isBlank()) {
            return triggerTimeframe == Timeframe.FIFTEEN_MINUTE;
        }
        try {
            return triggerTimeframe == Timeframe.valueOf(configured);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid scanTimeframe '{}' for strategy {}, defaulting to FIFTEEN_MINUTE",
                    configured, config.getStrategyType());
            return triggerTimeframe == Timeframe.FIFTEEN_MINUTE;
        }
    }

    /**
     * Lightweight signal quality proxy for best-index ranking.
     * Uses underlying candle data to estimate signal strength without running full strategy evaluation.
     * Scores movement intensity (not direction) to identify which index has the strongest actionable setup.
     * Returns 0-100.
     */
    private int computeSignalQualityProxy(UnderlyingSymbol underlying, Timeframe timeframe) {
        List<Candle> candles = underlyingLiveCandles(underlying, timeframe);
        if (candles.size() < 5) return 50; // neutral if insufficient data

        int score = 0;

        // 1. Price vs VWAP (0-50 points)
        // Either direction is fine — we want MOVEMENT, not direction
        BigDecimal vwap = vwapIndicator.calculate(candles);
        if (vwap.signum() > 0) {
            BigDecimal lastClose = candles.getLast().close();
            double distanceFromVwap = Math.abs(lastClose.subtract(vwap).doubleValue() / vwap.doubleValue() * 100);
            // Further from VWAP = stronger trend = higher score
            // 0% distance = 25 (neutral), 0.5%+ = 50 (strong)
            score += (int) Math.min(50, 25 + distanceFromVwap * 50);
        } else {
            score += 25; // neutral
        }

        // 2. Recent candle momentum — last 3 candles direction consistency (0-30 points)
        Candle last3 = candles.get(Math.max(0, candles.size() - 3));
        Candle lastCandle = candles.getLast();
        boolean sameDirection = lastCandle.close().compareTo(lastCandle.open()) == last3.close().compareTo(last3.open());
        score += sameDirection ? 30 : 15;

        // 3. Volume activity (0-20 points)
        long latestVolume = candles.getLast().volume();
        double avgVolume = candles.stream()
                .skip(Math.max(0, candles.size() - 5))
                .mapToLong(Candle::volume)
                .average().orElse(0);
        if (avgVolume > 0) {
            double volumeRatio = latestVolume / avgVolume;
            score += (int) Math.min(20, volumeRatio * 10); // 2x avg volume = 20 points
        } else {
            score += 10; // neutral
        }

        return Math.max(0, Math.min(100, score));
    }

    /**
     * Resolve a timeframe string from StrategyConfig to a Timeframe enum.
     * Falls back to the provided default if null, blank, or invalid.
     */
    private Timeframe resolveTimeframe(String configured, Timeframe defaultTf) {
        if (configured == null || configured.isBlank()) return defaultTf;
        try {
            return Timeframe.valueOf(configured);
        } catch (IllegalArgumentException e) {
            return defaultTf;
        }
    }

    /**
     * Compute IV rank from IVRankTracker (proper Black-Scholes IV percentile, persisted across sessions).
     * Falls back to ATM straddle/spot proxy when tracker has no history yet (early in session or first day).
     */
    private record IvRankResult(double rank, String source) {}

    /** Serializes the key StrategyConfig fields as a compact JSON-like string for audit replay. */
    private static String configToSnapshot(com.algo.trade.strategy.StrategyConfig c) {
        if (c == null) return null;
        return String.format(
            "{\"type\":\"%s\",\"underlying\":\"%s\",\"lots\":%d,\"sl\":%.0f,\"target\":%.0f," +
            "\"maxHold\":%d,\"paper\":%b,\"otm\":%d,\"spread\":%d," +
            "\"maxIvBuy\":%s,\"minIvSell\":%s,\"candle\":\"%s\",\"trend\":\"%s\"}",
            c.getStrategyType() != null ? c.getStrategyType().name() : "",
            c.getUnderlying() != null ? c.getUnderlying() : "",
            c.getLots(),
            c.getStopLossPercent() != null ? c.getStopLossPercent().doubleValue() : 0,
            c.getTargetPercent() != null ? c.getTargetPercent().doubleValue() : 0,
            c.getMaxHoldMinutes(),
            c.isPaperTrading(),
            c.getOtmStrikes(),
            c.getSpreadStrikes(),
            c.getMaxIvRankForBuying() != null ? c.getMaxIvRankForBuying().toPlainString() : "null",
            c.getMinCombinedPremium() != null ? c.getMinCombinedPremium().toPlainString() : "null",
            c.getCandleTimeframe() != null ? c.getCandleTimeframe() : "",
            c.getTrendTimeframe() != null ? c.getTrendTimeframe() : ""
        );
    }

    private IvRankResult computeLiveIvRank(UnderlyingSymbol underlying, List<Candle> candles) {
        com.algo.trade.domain.IndexType indexType = com.algo.trade.domain.IndexType.from(underlying);
        // Prefer IVRankTracker: real IV rank computed from GreeksCalculator IV, loaded from DB on startup
        if (ivRankTracker.getCurrentIV(indexType) > 0) {
            return new IvRankResult(ivRankTracker.getIVRank(indexType), "TRACKER");
        }
        // IVRankTracker not yet warmed up — return neutral 50.0 until history accumulates.
        log.debug("IV rank unavailable for {} (IVRankTracker not warmed up) — using neutral 50.0", underlying);
        return new IvRankResult(50.0, "NEUTRAL");
    }

    /**
     * Build a lightweight StrategyContext for additional strategies that do not need a live
     * option chain snapshot. Uses synthetic spot quote derived from last candle close.
     */
    private StrategyContext buildLiteStrategyContext(
            UnderlyingSymbol underlying, LocalTime marketTime,
            List<Candle> strategyCandles, List<Candle> trendCandles,
            double ivRank, String ivRankSource,
            Timeframe strategyTf, Timeframe trendTf
    ) {
        java.util.EnumMap<Timeframe, List<Candle>> candlesByTf = new java.util.EnumMap<>(Timeframe.class);
        candlesByTf.put(strategyTf, strategyCandles);
        if (trendTf != strategyTf) candlesByTf.put(trendTf, trendCandles);

        BigDecimal spotPrice = strategyCandles.isEmpty()
                ? BigDecimal.ZERO : strategyCandles.getLast().close();
        Instant now = Instant.now();
        Quote syntheticSpot = new Quote(underlying.name(), now, spotPrice,
                0, 0, Optional.empty(), Optional.empty(), Optional.empty());

        com.algo.trade.domain.IndexType idx = com.algo.trade.domain.IndexType.from(underlying);
        return new StrategyContext(
                now, marketTime, underlying,
                java.util.Collections.unmodifiableMap(candlesByTf),
                null,   // optionChainSnapshot not needed for additional strategies
                Map.of(), Map.of(), Map.of(),
                Map.copyOf(scanContextBuilder.getPreviousQuotes()),
                syntheticSpot,
                ivRank, ivRankSource != null ? ivRankSource : "",
                marketGuard.getCurrentVix(),
                expiryCalendar.daysToExpiry(idx)
        );
    }

    private record EntryCandidate(
            StrategyDecision decision,
            Quote quote,
            Instrument instrument
    ) {
    }

    /**
     * IV skew = 1-strike OTM put IV minus 1-strike OTM call IV from the chain snapshot.
     * Positive skew means puts are more expensive (fear premium); negative means calls are bid up.
     */
    private double computeIvSkew(
            UnderlyingSymbol underlying, OptionType optionType,
            Instrument selectedInstrument, OptionChainSnapshot chainSnapshot
    ) {
        if (chainSnapshot == null || chainSnapshot.levels().isEmpty()) return 0.0;
        try {
            BigDecimal spot = chainSnapshot.underlyingPrice();
            com.algo.trade.domain.IndexType idx = com.algo.trade.domain.IndexType.from(underlying);
            int atmStrike = idx.roundToATM(spot.doubleValue());
            int interval  = idx.strikeInterval();
            BigDecimal otmCallStrike = BigDecimal.valueOf(atmStrike + interval);
            BigDecimal otmPutStrike  = BigDecimal.valueOf(atmStrike - interval);

            double callIv = chainSnapshot.levels().stream()
                    .filter(l -> l.strike().compareTo(otmCallStrike) == 0)
                    .mapToDouble(com.algo.trade.domain.OptionChainLevel::callImpliedVolatility)
                    .findFirst().orElse(0.0);
            double putIv = chainSnapshot.levels().stream()
                    .filter(l -> l.strike().compareTo(otmPutStrike) == 0)
                    .mapToDouble(com.algo.trade.domain.OptionChainLevel::putImpliedVolatility)
                    .findFirst().orElse(0.0);

            return (callIv > 0 && putIv > 0) ? putIv - callIv : 0.0;
        } catch (Exception e) {
            return 0.0;
        }
    }

    private void persistGreeksSample(
            UnderlyingSymbol underlying, OptionType optionType,
            Instrument selectedInstrument, Instant capturedAt,
            double delta, double gamma, double theta, double vega,
            com.algo.trade.domain.OptionInstrument liveOption, BigDecimal spotPrice
    ) {
        if (liveOption == null || delta == 0.0) return;
        try {
            com.algo.trade.persistence.GreeksSampleEntity sample = new com.algo.trade.persistence.GreeksSampleEntity(
                    com.algo.trade.domain.IndexType.from(underlying).name(),
                    optionType.name(),
                    liveOption.getStrikePrice(),
                    capturedAt,
                    delta, gamma, theta, vega,
                    liveOption.getImpliedVolatility(),
                    spotPrice.doubleValue()
            );
            greeksSampleRepository.save(sample);
        } catch (Exception e) {
            log.debug("Greeks sample persistence failed: {}", e.getMessage());
        }
    }
}
