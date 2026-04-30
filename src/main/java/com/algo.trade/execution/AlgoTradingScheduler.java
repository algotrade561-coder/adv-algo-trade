package com.algo.trade.execution;

import com.algo.trade.config.GlobalConfigService;
import com.algo.trade.config.TradingProperties;
import com.algo.trade.broker.zerodha.KiteAccessTokenStore;
import com.algo.trade.domain.*;
import com.algo.trade.marketdata.InstrumentCache;
import com.algo.trade.marketdata.ExpiryCalendar;
import com.algo.trade.marketdata.MarketDataService;
import com.algo.trade.persistence.StrategyDecisionEntity;
import com.algo.trade.persistence.StrategyDecisionRepository;
import com.algo.trade.strategy.EventDrivenBuyStrategy;
import com.algo.trade.strategy.ExpiryGammaStrategy;
import com.algo.trade.strategy.ExpiryReversalStrategy;
import com.algo.trade.strategy.GapAndGoStrategy;
import com.algo.trade.strategy.ItmConvictionStrategy;
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
public class AlgoTradingScheduler {

    private static final Logger log = LoggerFactory.getLogger(AlgoTradingScheduler.class);
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
    private final ItmConvictionStrategy itmConvictionStrategy;
    private final GapAndGoStrategy gapAndGoStrategy;
    private final ReversalBuyStrategy reversalBuyStrategy;
    private final OiShiftTrapStrategy oiShiftTrapStrategy;
    private final ExpiryGammaStrategy expiryGammaStrategy;
    private final ExpiryReversalStrategy expiryReversalStrategy;
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
    private final Map<String, Quote> previousQuotes = new ConcurrentHashMap<>();
    private final AtomicBoolean scanInProgress = new AtomicBoolean(false);
    private final Map<StrategyType, AbstractSpreadStrategy> spreadStrategyMap;
    private final StrategyExecutionPipeline executionPipeline;

    // Short-lived cache for underlying REST candles — avoids repeated REST calls within the same candle period.
    // Keyed by "instrumentKey:TIMEFRAME", expires after 60 seconds.
    private record CachedCandles(List<Candle> candles, Instant fetchedAt) {}
    private final Map<String, CachedCandles> candleRestCache = new ConcurrentHashMap<>();
    private static final Duration CANDLE_CACHE_TTL = Duration.ofSeconds(60);

    // Debounce map for stale market data warnings — log at most once per 60s per underlying.
    private final Map<String, Instant> lastStaleWarnTime = new ConcurrentHashMap<>();
    private static final Duration STALE_WARN_DEBOUNCE = Duration.ofSeconds(60);

    public AlgoTradingScheduler(
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
            ItmConvictionStrategy itmConvictionStrategy,
            GapAndGoStrategy gapAndGoStrategy,
            ReversalBuyStrategy reversalBuyStrategy,
            OiShiftTrapStrategy oiShiftTrapStrategy,
            ExpiryGammaStrategy expiryGammaStrategy,
            ExpiryReversalStrategy expiryReversalStrategy,
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
            com.algo.trade.persistence.GreeksSampleRepository greeksSampleRepository
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
        this.itmConvictionStrategy = itmConvictionStrategy;
        this.gapAndGoStrategy = gapAndGoStrategy;
        this.reversalBuyStrategy = reversalBuyStrategy;
        this.oiShiftTrapStrategy = oiShiftTrapStrategy;
        this.expiryGammaStrategy = expiryGammaStrategy;
        this.expiryReversalStrategy = expiryReversalStrategy;
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
        } finally {
            scanInProgress.set(false);
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

        // News sentiment: log high-impact events but don't block scan
        // (blocking happens per-strategy for real trades only)
        if (newsFeedService.hasHighImpactEvent()) {
            log.info("Algo scan: high-impact news event detected — real entries will be blocked, paper trades continue");
        }

        List<UnderlyingSymbol> enabledUnderlyings = tradingStateService.enabledUnderlyings();
        tradingStateService.recordScan();
        log.info("Algo scan started: mode={}, marketTime={}, underlyings={}, maxEntriesPerScan={}",
                properties.mode(), marketTime, enabledUnderlyings, properties.algo().maxEntriesPerScan());

        ensureInstrumentsLoaded();

        int entriesSubmitted = 0;
        // Directional Buy only runs on 1-min candle trigger
        if (triggerTimeframe == Timeframe.ONE_MINUTE) {
            for (UnderlyingSymbol underlying : enabledUnderlyings) {
                IndexType dbIdx = com.algo.trade.domain.IndexType.from(underlying);
                if (expiryCalendar.isExpiryDay(dbIdx) && marketTime.isAfter(LocalTime.of(13, 0))) {
                    log.info("Directional Buy skipped: expiry day for {} after 1 PM (gamma risk)", underlying);
                    continue;
                }
                if (entriesSubmitted >= properties.algo().maxEntriesPerScan()) {
                    log.info("Algo scan entry limit reached for this cycle: entriesSubmitted={}", entriesSubmitted);
                    break;
                }

                String[] dbScanFailReason = {"buildScanContext:unknown"};
                Optional<ScanContext> context = buildScanContext(underlying, marketTime, dbScanFailReason);
                if (context.isEmpty()) {
                    List<Candle> spotCandles = underlyingLiveCandles(underlying, Timeframe.ONE_MINUTE);
                    BigDecimal spotPrice = spotCandles.isEmpty() ? BigDecimal.ZERO : spotCandles.getLast().close();
                    signalCsvRecorder.recordAdditionalNoTrade(
                            "DIRECTIONAL_BUY", underlying.name(), spotPrice,
                            dbScanFailReason[0], spotCandles,
                            com.algo.trade.strategy.StrategyDiagnostics.NONE, 0.0);
                    continue;
                }

                List<Candle> dbDefaultCandles = underlyingLiveCandles(underlying, Timeframe.FIVE_MINUTE);
                double dbIvRank = computeLiveIvRank(underlying, dbDefaultCandles).rank();
                entriesSubmitted += evaluateAndExecute(underlying, context.get(), marketTime,
                        properties.algo().maxEntriesPerScan() - entriesSubmitted, dbIvRank);
                // Evict stale keys (expired strikes from prior weeks) before recording current quotes
                Map<String, Quote> currentQuotes = context.get().quotes();
                previousQuotes.keySet().retainAll(currentQuotes.keySet());
                previousQuotes.putAll(currentQuotes);
            }
        } else {
            log.debug("Directional Buy skipped: trigger timeframe is {} (needs ONE_MINUTE)", triggerTimeframe);
        }

        log.info("Algo scan completed: entriesSubmitted={}", entriesSubmitted);

        // ── Evaluate additional enabled strategies ────────────────────────────
        runAdditionalStrategies(marketTime, enabledUnderlyings, triggerTimeframe);
    }

    /**
     * Evaluates all enabled non-directional strategies (scalping, volatility breakout,
     * spreads, event-driven) and persists their signals to H2.
     */
    private void runAdditionalStrategies(LocalTime marketTime, List<UnderlyingSymbol> underlyings,
                                          Timeframe triggerTimeframe) {
        for (UnderlyingSymbol underlying : underlyings) {
            List<StrategyConfig> enabledConfigs = strategyConfigService.getEnabledFor(underlying);
            if (enabledConfigs.isEmpty()) continue;
            // Expiry-day theta guard: after 1 PM only allow expiry-specific strategies
            IndexType expiryIdx = com.algo.trade.domain.IndexType.from(underlying);
            if (expiryCalendar.isExpiryDay(expiryIdx) && marketTime.isAfter(LocalTime.of(13, 0))) {
                enabledConfigs = enabledConfigs.stream()
                        .filter(c -> c.getStrategyType() == StrategyType.EXPIRY_GAMMA
                                || c.getStrategyType() == StrategyType.EXPIRY_REVERSAL)
                        .toList();
                if (enabledConfigs.isEmpty()) {
                    log.info("Additional strategies skipped: expiry day for {} after 1 PM — no expiry-specific strategies enabled", underlying);
                    continue;
                }
            }

            // Pre-fetch common candle resolutions (cached by MarketDataService)
            Map<Timeframe, List<Candle>> candleCache = new java.util.EnumMap<>(Timeframe.class);

            // Compute IV rank from live option chain
            List<Candle> defaultCandles = underlyingLiveCandles(underlying,
                    com.algo.trade.domain.Timeframe.FIVE_MINUTE);
            IvRankResult ivRankResult = computeLiveIvRank(underlying, defaultCandles);
            double ivRank = ivRankResult.rank();
            String ivRankSource = ivRankResult.source();

            for (StrategyConfig config : enabledConfigs) {
                StrategyType type = config.getStrategyType();

                // Resolve candle timeframes from per-strategy config
                Timeframe candleTf = resolveTimeframe(config.getCandleTimeframe(), Timeframe.ONE_MINUTE);
                Timeframe trendTf = resolveTimeframe(config.getTrendTimeframe(), Timeframe.FIVE_MINUTE);

                // Fetch candles at the strategy's configured resolution (cached)
                List<Candle> strategyCandles = candleCache.computeIfAbsent(candleTf,
                        tf -> underlyingLiveCandles(underlying, tf));
                List<Candle> trendCandles = candleCache.computeIfAbsent(trendTf,
                        tf -> underlyingLiveCandles(underlying, tf));

                try {
                    // VB theta guard: don't buy options within 3 days of expiry (theta decay)
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
                            Instrument vbAtm = resolveAtmInstrument(underlying, ot, vbSpotPrice).orElse(null);
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
                        continue;
                    }

                    com.algo.trade.strategy.StrategyDiagnostics[] diagHolder =
                            {com.algo.trade.strategy.StrategyDiagnostics.NONE};
                    boolean[] evaluated = {false};

                    Optional<StrategyDecision> signal = switch (type) {
                        case SCALPING -> {
                            if (!matchesConfiguredTimeframe(config, triggerTimeframe)) { yield Optional.empty(); }
                            evaluated[0] = true;
                            var result = scalpingStrategy.evaluateWithDiagnostics(strategyCandles, marketTime, config, underlying);
                            diagHolder[0] = result.diagnostics();
                            yield result.signal();
                        }
                        case VOLATILITY_BREAKOUT -> {
                            if (!matchesConfiguredTimeframe(config, triggerTimeframe)) { yield Optional.empty(); }
                            evaluated[0] = true;
                            var result = volatilityBreakoutStrategy.evaluateWithDiagnostics(trendCandles, ivRank, config, underlying);
                            diagHolder[0] = result.diagnostics();
                            yield result.signal();
                        }
                        case EVENT_DRIVEN_BUY -> {
                            if (!matchesConfiguredTimeframe(config, triggerTimeframe)) { yield Optional.empty(); }
                            evaluated[0] = true;
                            BigDecimal eventSpotPrice = trendCandles.isEmpty() ? BigDecimal.ZERO : trendCandles.getLast().close();
                            yield eventDrivenBuyStrategy.evaluate(ivRank, config, underlying, eventSpotPrice);
                        }
                        case BULL_CALL_SPREAD, BEAR_PUT_SPREAD,
                             LONG_STRADDLE, LONG_STRANGLE,
                             SHORT_STRADDLE, SHORT_STRANGLE,
                             IRON_CONDOR, BUTTERFLY, CALENDAR_SPREAD,
                             DIAGONAL_SPREAD, JADE_LIZARD, SYNTHETIC_FUTURES -> {
                            if (!matchesConfiguredTimeframe(config, triggerTimeframe)) { yield Optional.empty(); }
                            evaluated[0] = true;
                            AbstractSpreadStrategy spreadStrategy = spreadStrategyMap.get(type);
                            if (spreadStrategy == null) {
                                // Fallback: generic signal-only path (no multi-leg construction)
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
                        case ITM_CONVICTION -> {
                            if (!matchesConfiguredTimeframe(config, triggerTimeframe)) { yield Optional.empty(); }
                            evaluated[0] = true;
                            BigDecimal spotPrice = strategyCandles.isEmpty() ? BigDecimal.ZERO : strategyCandles.getLast().close();
                            String[] itmFailReason = {"buildScanContext:unknown"};
                            Optional<ScanContext> ctx = buildScanContext(underlying, marketTime, itmFailReason);
                            if (ctx.isEmpty()) { diagHolder[0] = new com.algo.trade.strategy.StrategyDiagnostics(itmFailReason[0], null, null, null, null, null, null, null, null); yield Optional.empty(); }
                            yield itmConvictionStrategy.evaluate(underlying, spotPrice, ctx.get().quotes(), config);
                        }
                        case GAP_AND_GO -> {
                            if (!matchesConfiguredTimeframe(config, triggerTimeframe)) { yield Optional.empty(); }
                            evaluated[0] = true;
                            var ggResult = gapAndGoStrategy.evaluateWithDiagnostics(strategyCandles, marketTime, config, underlying);
                            diagHolder[0] = ggResult.diagnostics();
                            yield ggResult.signal();
                        }
                        case REVERSAL_BUY -> {
                            if (!matchesConfiguredTimeframe(config, triggerTimeframe)) { yield Optional.empty(); }
                            evaluated[0] = true;
                            var rbResult = reversalBuyStrategy.evaluateWithDiagnostics(trendCandles, ivRank, config, underlying);
                            diagHolder[0] = rbResult.diagnostics();
                            yield rbResult.signal();
                        }
                        case OI_SHIFT_TRAP -> {
                            if (!matchesConfiguredTimeframe(config, triggerTimeframe)) { yield Optional.empty(); }
                            evaluated[0] = true;
                            String[] oiFailReason = {"buildScanContext:unknown"};
                            Optional<ScanContext> oiCtx = buildScanContext(underlying, marketTime, oiFailReason);
                            if (oiCtx.isEmpty()) {
                                diagHolder[0] = new com.algo.trade.strategy.StrategyDiagnostics(oiFailReason[0], null, null, null, null, null, null, null, null);
                                yield Optional.empty();
                            }
                            BigDecimal oiSpot = trendCandles.isEmpty() ? BigDecimal.ZERO : trendCandles.getLast().close();
                            yield oiShiftTrapStrategy.evaluate(oiCtx.get().optionChainSnapshot(), oiSpot, config, underlying, trendCandles);
                        }
                        case EXPIRY_GAMMA -> {
                            if (!matchesConfiguredTimeframe(config, triggerTimeframe)) { yield Optional.empty(); }
                            evaluated[0] = true;
                            var egResult = expiryGammaStrategy.evaluateWithDiagnostics(strategyCandles, marketTime, config, underlying);
                            diagHolder[0] = egResult.diagnostics();
                            yield egResult.signal();
                        }
                        case EXPIRY_REVERSAL -> {
                            if (!matchesConfiguredTimeframe(config, triggerTimeframe)) { yield Optional.empty(); }
                            evaluated[0] = true;
                            var erResult = expiryReversalStrategy.evaluateWithDiagnostics(strategyCandles, marketTime, config, underlying);
                            diagHolder[0] = erResult.diagnostics();
                            yield erResult.signal();
                        }
                        default -> Optional.empty();
                    };

                    // Spread strategies: evaluateAndEnter() already persisted the PositionGroupEntity.
                    // Bypass enrichment (would replace groupId with a phantom CE instrument).
                    if (signal.isPresent() && spreadStrategyMap.containsKey(type)) {
                        StrategyDecisionEntity spreadEntity = persistStrategyDecision(signal.get(), type.name(), config, ivRankSource);
                        spreadEntity.setExecutionStage("NOT_EXECUTED");
                        spreadEntity.setExecutionReason("Spread recorded — multi-leg broker execution not yet implemented");
                        decisionRepository.save(spreadEntity);
                        log.info("Spread entry recorded: type={} groupId={}",
                                type, signal.get().selectedInstrumentKey().orElse(""));
                        continue;
                    }

                    // All non-spread strategies that were evaluated: unified pipeline post-processing
                    // (enrichment, risk gates, ML shadow, execution, CSV, DB persistence)
                    if (evaluated[0] && type != StrategyType.DIRECTIONAL_BUY) {
                        StrategyContext pipelineCtx = buildLiteStrategyContext(
                                underlying, marketTime, strategyCandles, trendCandles,
                                ivRank, ivRankSource, candleTf, trendTf);
                        executionPipeline.process(
                                new com.algo.trade.strategy.StrategyDiagnostics.WithSignal(signal, diagHolder[0]),
                                pipelineCtx, config, type, ivRankSource);
                    }
                } catch (Exception e) {
                    log.warn("Additional strategy evaluation failed: type={} underlying={}: {}",
                            type, underlying, e.getMessage());
                }
            }
        }
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


    private Optional<Instrument> resolveAtmInstrument(UnderlyingSymbol underlying, OptionType optionType, BigDecimal spotPrice) {
        try {
            Optional<LocalDate> expiry = instrumentCache.nearestExpiry(
                    underlying, LocalDate.now(properties.timezone()), properties.symbols().defaultExpiry());
            if (expiry.isEmpty()) return Optional.empty();
            return instrumentCache.all().stream()
                    .filter(Instrument::tradable)
                    .filter(i -> i.underlying().filter(underlying::equals).isPresent())
                    .filter(i -> i.expiry().filter(expiry.get()::equals).isPresent())
                    .filter(i -> i.optionType().filter(optionType::equals).isPresent())
                    .filter(i -> i.strike().isPresent())
                    .min(Comparator.comparing(i -> i.strike().orElse(BigDecimal.ZERO).subtract(spotPrice).abs()));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private void ensureInstrumentsLoaded() {
        if (!instrumentCache.all().isEmpty()) {
            return;
        }
        if (!properties.algo().refreshInstrumentsOnStart()) {
            log.warn("Instrument cache is empty and refreshInstrumentsOnStart=false; scan cannot continue");
            return;
        }
        List<Instrument> instruments = instrumentCache.refresh();
        log.info("Algo scan loaded instrument cache: instrumentCount={}", instruments.size());
    }

    private Optional<ScanContext> buildScanContext(UnderlyingSymbol underlying, LocalTime marketTime, String[] failReason) {
        Optional<Quote> spotQuote = spotQuote(underlying);
        if (spotQuote.isEmpty()) {
            failReason[0] = "noSpotQuote";
            log.warn("Algo scan skipped underlying: no spot quote available, underlying={}", underlying);
            return Optional.empty();
        }

        Optional<LocalDate> expiry = instrumentCache.nearestExpiry(
                underlying,
                LocalDate.now(properties.timezone()),
                properties.symbols().defaultExpiry()
        );
        if (expiry.isEmpty()) {
            failReason[0] = "noExpiry";
            log.warn("Algo scan skipped underlying: no expiry found in instrument cache, underlying={}", underlying);
            return Optional.empty();
        }

        List<Instrument> options = optionUniverse(underlying, expiry.get());
        if (options.isEmpty()) {
            failReason[0] = "noOptionUniverse";
            log.warn("Algo scan skipped underlying: no option instruments found, underlying={}, expiry={}",
                    underlying, expiry.get());
            return Optional.empty();
        }

        BigDecimal underlyingPrice = spotQuote.get().lastPrice();
        List<BigDecimal> selectedStrikes = selectedStrikes(options, underlyingPrice);
        Map<String, Quote> optionQuotes = marketDataService.quotes(options.stream()
                .filter(option -> option.strike().filter(selectedStrikes::contains).isPresent())
                .map(Instrument::instrumentKey)
                .toList());

        List<OptionChainLevel> levels = optionChainLevels(options, selectedStrikes, optionQuotes);
        if (levels.isEmpty()) {
            failReason[0] = "noChainLevels(optionQuotesMissing)";
            log.warn("Algo scan skipped underlying: option chain levels could not be built, underlying={}, expiry={}",
                    underlying, expiry.get());
            return Optional.empty();
        }

        Map<OptionType, Instrument> selectedOptions = selectedOptions(underlying, options, underlyingPrice, optionQuotes);
        if (selectedOptions.isEmpty()) {
            BigDecimal maxPrem = maxTradablePremium(underlying, options.stream().findFirst()
                    .map(Instrument::lotSize).orElse(0));
            failReason[0] = "noAffordableOption(maxPremium=" + maxPrem.setScale(0, java.math.RoundingMode.HALF_UP) + ")";
            log.warn("Algo scan skipped underlying: no selected option instruments, underlying={}, expiry={}",
                    underlying, expiry.get());
            return Optional.empty();
        }

        OptionChainSnapshot snapshot = new OptionChainSnapshot(underlying, Instant.now(), underlyingPrice, levels);
        Map<String, Quote> allQuotes = new LinkedHashMap<>(optionQuotes);
        allQuotes.put(spotQuote.get().instrumentKey(), spotQuote.get());

        // PCR is computed by PcrCalculator from the full option chain — no partial update here
        long totalCallOi = levels.stream().mapToLong(OptionChainLevel::callOpenInterest).sum();
        long totalPutOi  = levels.stream().mapToLong(OptionChainLevel::putOpenInterest).sum();

        log.info("Algo scan context built: underlying={}, spotPrice={}, expiry={}, selectedStrikes={}, chainLevels={}, selectedOptions={}, nearbyPcr={}",
                underlying, underlyingPrice, expiry.get(), selectedStrikes, levels.size(), selectedOptions,
                totalCallOi > 0 ? String.format("%.3f", (double) totalPutOi / totalCallOi) : "N/A");
        return Optional.of(new ScanContext(spotQuote.get(), snapshot, selectedOptions, allQuotes));
    }

    private Optional<Quote> spotQuote(UnderlyingSymbol underlying) {
        String spotQuoteKey = properties.symbols().spotQuoteKeys().get(underlying);
        if (spotQuoteKey == null || spotQuoteKey.isBlank()) {
            log.warn("Spot quote key missing for underlying={}", underlying);
            return Optional.empty();
        }
        // Use WebSocket live price to avoid REST rate limits — spot tokens are always subscribed
        double livePrice = liveInstrumentCache.getFuturesPrice(com.algo.trade.domain.IndexType.from(underlying));
        if (livePrice > 0) {
            return Optional.of(new Quote(spotQuoteKey, Instant.now(),
                    java.math.BigDecimal.valueOf(livePrice), 0, 0,
                    Optional.empty(), Optional.empty(), Optional.empty()));
        }
        return marketDataService.quote(spotQuoteKey);
    }

    private List<Instrument> optionUniverse(UnderlyingSymbol underlying, LocalDate expiry) {
        return instrumentCache.all().stream()
                .filter(Instrument::tradable)
                .filter(instrument -> instrument.underlying().filter(underlying::equals).isPresent())
                .filter(instrument -> instrument.expiry().filter(expiry::equals).isPresent())
                .filter(instrument -> instrument.optionType().isPresent())
                .filter(instrument -> instrument.strike().isPresent())
                .sorted(Comparator.comparing(instrument -> instrument.strike().orElse(BigDecimal.ZERO)))
                .toList();
    }

    private List<BigDecimal> selectedStrikes(List<Instrument> options, BigDecimal underlyingPrice) {
        List<BigDecimal> strikes = options.stream()
                .flatMap(instrument -> instrument.strike().stream())
                .distinct()
                .sorted()
                .toList();
        if (strikes.isEmpty()) {
            return List.of();
        }

        BigDecimal atm = nearestStrike(strikes, underlyingPrice);
        int atmIndex = indexOfStrike(strikes, atm);
        int start = Math.max(0, atmIndex - properties.strike().nearbyStrikes());
        int end = Math.min(strikes.size(), atmIndex + properties.strike().nearbyStrikes() + 1);
        return strikes.subList(start, end);
    }

    private Map<OptionType, Instrument> selectedOptions(UnderlyingSymbol underlying, List<Instrument> options,
                                                        BigDecimal underlyingPrice, Map<String, Quote> quotes) {
        List<BigDecimal> strikes = options.stream()
                .flatMap(instrument -> instrument.strike().stream())
                .distinct()
                .sorted()
                .toList();
        if (strikes.isEmpty()) {
            return Map.of();
        }

        Map<OptionType, Instrument> selected = new EnumMap<>(OptionType.class);
        selected.put(OptionType.CE, selectedAffordableOption(underlying, options, strikes, underlyingPrice, OptionType.CE,
                quotes).orElse(null));
        selected.put(OptionType.PE, selectedAffordableOption(underlying, options, strikes, underlyingPrice, OptionType.PE,
                quotes).orElse(null));
        selected.values().removeIf(java.util.Objects::isNull);
        selected.forEach((optionType, instrument) -> log.info(
                "Selected option: optionType={}, instrument={}, token={}, parsedStrike={}, expiry={}, underlyingPrice={}",
                optionType, instrument.instrumentKey(), instrument.instrumentToken(),
                instrument.strike().orElse(null), instrument.expiry().orElse(null), underlyingPrice));
        return Map.copyOf(selected);
    }

    private Optional<Instrument> selectedAffordableOption(UnderlyingSymbol underlying, List<Instrument> options,
                                                          List<BigDecimal> strikes, BigDecimal underlyingPrice,
                                                          OptionType optionType, Map<String, Quote> quotes) {
        List<BigDecimal> candidateStrikes = candidateStrikes(optionType, strikes, underlyingPrice);
        for (BigDecimal strike : candidateStrikes) {
            Optional<Instrument> instrument = findOption(options, strike, optionType);
            if (instrument.isEmpty()) {
                continue;
            }
            BigDecimal maxPremium = maxTradablePremium(underlying, instrument.get().lotSize());
            Quote quote = quotes.get(instrument.get().instrumentKey());
            if (quote == null || quote.lastPrice() == null || quote.lastPrice().signum() <= 0) {
                log.info("Option selection skipped: missing/invalid quote, optionType={}, instrument={}, strike={}",
                        optionType, instrument.get().instrumentKey(), strike);
                continue;
            }
            if (quote.lastPrice().compareTo(maxPremium) <= 0) {
                log.info("Risk-budget option selected: optionType={}, instrument={}, strike={}, premium={}, maxPremium={}",
                        optionType, instrument.get().instrumentKey(), strike, quote.lastPrice(), maxPremium);
                return instrument;
            }
            log.info("Option selection rejected by premium budget: optionType={}, instrument={}, strike={}, premium={}, maxPremium={}",
                    optionType, instrument.get().instrumentKey(), strike, quote.lastPrice(), maxPremium);
        }
        log.warn("No option fits risk-budget premium: optionType={}, candidateStrikes={}",
                optionType, candidateStrikes);
        return Optional.empty();
    }

    private List<BigDecimal> candidateStrikes(OptionType optionType, List<BigDecimal> strikes,
                                              BigDecimal underlyingPrice) {
        BigDecimal atm = nearestStrike(strikes, underlyingPrice);
        int atmIndex = indexOfStrike(strikes, atm);
        int start = Math.max(0, atmIndex - properties.strike().nearbyStrikes());
        int end = Math.min(strikes.size(), atmIndex + properties.strike().nearbyStrikes() + 1);
        List<BigDecimal> nearby = strikes.subList(start, end);
        if (optionType == OptionType.CE) {
            return nearby.stream()
                    .filter(strike -> strike.compareTo(atm) >= 0)
                    .sorted()
                    .toList();
        }
        return nearby.stream()
                .filter(strike -> strike.compareTo(atm) <= 0)
                .sorted(Comparator.reverseOrder())
                .toList();
    }

    static BigDecimal maxTradablePremium(GlobalConfigService globalConfigService, BigDecimal stopLossPercent, int lotSize) {
        BigDecimal riskAmount = globalConfigService.getTotalCapital()
                .multiply(globalConfigService.getMaxRiskPerTradePercent(), MATH_CONTEXT)
                .divide(BigDecimal.valueOf(100), MATH_CONTEXT);
        if (lotSize <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal riskPerLotPercent = BigDecimal.valueOf(lotSize)
                .multiply(stopLossPercent, MATH_CONTEXT)
                .divide(BigDecimal.valueOf(100), MATH_CONTEXT);
        if (riskPerLotPercent.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return riskAmount.divide(riskPerLotPercent, MATH_CONTEXT);
    }

    private BigDecimal maxTradablePremium(UnderlyingSymbol underlying, int lotSize) {
        return maxTradablePremium(globalConfigService, strategyConfigService.getDirectionalBuyConfig(underlying.name()).getStopLossPercent(), lotSize);
    }

    private BigDecimal selectedStrikeFor(OptionType optionType, List<BigDecimal> strikes, BigDecimal underlyingPrice) {
        BigDecimal atm = nearestStrike(strikes, underlyingPrice);
        int atmIndex = indexOfStrike(strikes, atm);
        return switch (properties.strike().selectionMode()) {
            case ATM -> atm;
            case ONE_STRIKE_ITM -> optionType == OptionType.CE
                    ? strikes.get(Math.max(0, atmIndex - 1))
                    : strikes.get(Math.min(strikes.size() - 1, atmIndex + 1));
            case ONE_STRIKE_OTM -> optionType == OptionType.CE
                    ? strikes.get(Math.min(strikes.size() - 1, atmIndex + 1))
                    : strikes.get(Math.max(0, atmIndex - 1));
        };
    }

    private Optional<Instrument> findOption(List<Instrument> options, BigDecimal strike, OptionType optionType) {
        return options.stream()
                .filter(instrument -> instrument.strike().filter(strike::equals).isPresent())
                .filter(instrument -> instrument.optionType().filter(optionType::equals).isPresent())
                .findFirst();
    }

    private List<OptionChainLevel> optionChainLevels(
            List<Instrument> options,
            List<BigDecimal> selectedStrikes,
            Map<String, Quote> quotes
    ) {
        return selectedStrikes.stream()
                .flatMap(strike -> {
                    Optional<Instrument> call = findOption(options, strike, OptionType.CE);
                    Optional<Instrument> put = findOption(options, strike, OptionType.PE);
                    Optional<Quote> callQuote = call.map(Instrument::instrumentKey).map(quotes::get);
                    Optional<Quote> putQuote = put.map(Instrument::instrumentKey).map(quotes::get);
                    if (callQuote.isEmpty() || putQuote.isEmpty()) {
                        return java.util.stream.Stream.empty();
                    }
                    double callIv = callQuote.get().impliedVolatility()
                            .map(java.math.BigDecimal::doubleValue).orElse(0.0);
                    double putIv = putQuote.get().impliedVolatility()
                            .map(java.math.BigDecimal::doubleValue).orElse(0.0);
                    return java.util.stream.Stream.of(new OptionChainLevel(
                            strike,
                            callQuote.get().openInterest(),
                            putQuote.get().openInterest(),
                            openInterestChange(callQuote.get()),
                            openInterestChange(putQuote.get()),
                            callQuote.get().lastPrice(),
                            putQuote.get().lastPrice(),
                            callIv,
                            putIv
                    ));
                })
                .toList();
    }

    private long openInterestChange(Quote quote) {
        // First try LiveInstrumentCache — updated on every WebSocket tick
        if (quote.instrumentKey() != null && quote.instrumentKey().contains(":")) {
            String symbol = quote.instrumentKey().split(":", 2)[1];
            long liveOiChange = liveInstrumentCache.getBySymbol(symbol)
                    .map(o -> o.getOiChange())
                    .orElse(0L);
            if (liveOiChange != 0) return liveOiChange;
        }
        // Fall back to scan-to-scan previousQuotes delta
        return Optional.ofNullable(previousQuotes.get(quote.instrumentKey()))
                .map(previous -> quote.openInterest() - previous.openInterest())
                .orElse(0L);
    }

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
                    Optional.ofNullable(previousQuotes.get(selectedInstrument.instrumentKey())),
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
            // Return stale cache rather than empty — stale candles are better than no candles
            return cached != null ? cached.candles() : List.of();
        }
    }

    /** @deprecated Unused — strike selection is done via selectedAffordableOption(). */

    private boolean freshQuote(Quote quote) {
        return quote.timestamp().plus(properties.safety().staleMarketDataThreshold()).isAfter(Instant.now());
    }


    private boolean freshCandles(List<Candle> candles) {
        if (candles.isEmpty()) {
            return false;
        }
        Candle latest = candles.getLast();
        Duration allowedAge = latest.timeframe().duration().plus(properties.safety().staleMarketDataThreshold());
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
        return candles(underlyingHistoricalKey(underlying), timeframe);
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
                Map.copyOf(previousQuotes),
                syntheticSpot,
                ivRank, ivRankSource != null ? ivRankSource : "",
                marketGuard.getCurrentVix(),
                expiryCalendar.daysToExpiry(idx)
        );
    }

    private record ScanContext(
            Quote spotQuote,
            OptionChainSnapshot optionChainSnapshot,
            Map<OptionType, Instrument> selectedOptions,
            Map<String, Quote> quotes
    ) {
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
