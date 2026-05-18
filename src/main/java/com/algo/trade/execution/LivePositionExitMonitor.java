package com.algo.trade.execution;

import com.algo.trade.config.GlobalConfigService;
import com.algo.trade.config.PositionSyncProperties;
import com.algo.trade.config.TradingProperties;
import com.algo.trade.execution.exit.ExitEvaluationRegistry;
import com.algo.trade.execution.exit.ExitEvaluationSnapshot;
import com.algo.trade.execution.exit.ExitMode;
import com.algo.trade.execution.exit.ExitParamResolver;
import com.algo.trade.execution.exit.MarketSessionHelper;
import com.algo.trade.execution.exit.PositionPnlCalculator;
import com.algo.trade.execution.exit.TrailingMode;
import com.algo.trade.domain.CandleClosedEvent;
import com.algo.trade.domain.IndexType;
import com.algo.trade.domain.Quote;
import com.algo.trade.domain.Timeframe;
import com.algo.trade.domain.TradeStatus;
import com.algo.trade.indicator.VwapIndicator;
import com.algo.trade.marketdata.ExpiryCalendar;
import com.algo.trade.marketdata.MarketDataService;
import com.algo.trade.notification.TelegramAlertService;
import com.algo.trade.persistence.TradeEntity;
import com.algo.trade.persistence.TradeRepository;
import com.algo.trade.strategy.StrategyConfig;
import com.algo.trade.strategy.StrategyConfigService;
import com.algo.trade.strategy.StrategyType;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Live position exit monitor — evaluates SL, target, and trailing stop
 * on every candle close for all open trades.
 *
 * Triggered by CandleClosedEvent so exits are checked at the same cadence
 * as entries — no separate polling needed.
 *
 * Exit priority:
 *   1. Stop loss hit     → close immediately + Telegram alert
 *   2. Target hit        → close immediately + Telegram alert
 *   3. Trailing stop hit → close immediately + Telegram alert
 */
@Component
public class LivePositionExitMonitor {

    private static final Logger log = LoggerFactory.getLogger(LivePositionExitMonitor.class);
    private static final MathContext MC = MathContext.DECIMAL64;

    private final TradeRepository tradeRepository;
    private final ExecutionEngine executionEngine;
    private final MarketDataService marketDataService;
    private final StrategyConfigService strategyConfigService;
    private final TrailingStopService trailingStopService;
    private final TelegramAlertService telegramAlertService;
    private final ExpiryCalendar expiryCalendar;
    private final com.algo.trade.strategy.DynamicExitManager dynamicExitManager;
    private final com.algo.trade.marketdata.LiveCandleBuilder liveCandleBuilder;
    private final PositionSyncProperties positionSyncProperties;
    private final GlobalConfigService globalConfigService;
    private final TradingStateService tradingStateService;
    private final VwapIndicator vwapIndicator;
    private final com.algo.trade.monitoring.ErrorEventService errorEventService;
    private final com.algo.trade.monitoring.SchedulerRegistry schedulerRegistry;
    private final com.algo.trade.ml.MlExitShadowRecorder mlExitShadowRecorder;
    private final com.algo.trade.risk.MarketGuard marketGuard;
    private final com.algo.trade.marketdata.LiveInstrumentCache liveInstrumentCache;
    private final TradingProperties tradingProperties;
    private final ExitParamResolver exitParamResolver;
    private final ExitEvaluationRegistry exitEvaluationRegistry;
    private final com.algo.trade.execution.exit.LiquidityEmergencyGate liquidityEmergencyGate;

    // tradeId → best price seen since entry (high for long, low for short)
    private final Map<String, BigDecimal> peakPrices = new ConcurrentHashMap<>();
    // tradeId → current trailing stop price
    private final Map<String, BigDecimal> trailingStops = new ConcurrentHashMap<>();
    // tradeId → set of progressive exit layer names that have already fired
    private final Map<String, Set<String>> firedLayers = new ConcurrentHashMap<>();
    // per-trade mutex — serializes concurrent evaluations when multiple CandleClosedEvents fire simultaneously
    private final Map<String, Object> evaluationLocks = new ConcurrentHashMap<>();

    public LivePositionExitMonitor(TradeRepository tradeRepository,
                                    ExecutionEngine executionEngine,
                                    MarketDataService marketDataService,
                                    StrategyConfigService strategyConfigService,
                                    TrailingStopService trailingStopService,
                                    TelegramAlertService telegramAlertService,
                                    ExpiryCalendar expiryCalendar,
                                    com.algo.trade.strategy.DynamicExitManager dynamicExitManager,
                                    com.algo.trade.marketdata.LiveCandleBuilder liveCandleBuilder,
                                    PositionSyncProperties positionSyncProperties,
                                    GlobalConfigService globalConfigService,
                                    TradingStateService tradingStateService,
                                    VwapIndicator vwapIndicator,
                                    com.algo.trade.monitoring.ErrorEventService errorEventService,
                                    com.algo.trade.monitoring.SchedulerRegistry schedulerRegistry,
                                    com.algo.trade.ml.MlExitShadowRecorder mlExitShadowRecorder,
                                    com.algo.trade.risk.MarketGuard marketGuard,
                                    com.algo.trade.marketdata.LiveInstrumentCache liveInstrumentCache,
                                    TradingProperties tradingProperties,
                                    ExitParamResolver exitParamResolver,
                                    ExitEvaluationRegistry exitEvaluationRegistry,
                                    com.algo.trade.execution.exit.LiquidityEmergencyGate liquidityEmergencyGate) {
        this.tradeRepository = tradeRepository;
        this.executionEngine = executionEngine;
        this.marketDataService = marketDataService;
        this.strategyConfigService = strategyConfigService;
        this.trailingStopService = trailingStopService;
        this.telegramAlertService = telegramAlertService;
        this.expiryCalendar = expiryCalendar;
        this.dynamicExitManager = dynamicExitManager;
        this.liveCandleBuilder = liveCandleBuilder;
        this.positionSyncProperties = positionSyncProperties;
        this.globalConfigService = globalConfigService;
        this.tradingStateService = tradingStateService;
        this.vwapIndicator = vwapIndicator;
        this.errorEventService = errorEventService;
        this.schedulerRegistry = schedulerRegistry;
        this.mlExitShadowRecorder = mlExitShadowRecorder;
        this.marketGuard = marketGuard;
        this.liveInstrumentCache = liveInstrumentCache;
        this.tradingProperties = tradingProperties;
        this.exitParamResolver = exitParamResolver;
        this.exitEvaluationRegistry = exitEvaluationRegistry;
        this.liquidityEmergencyGate = liquidityEmergencyGate;
        schedulerRegistry.register("exitBackup", "Scheduled backup exit evaluation (60s)", 60_000, this::scheduledBackupCheck);
    }

    @EventListener
    public void onCandleClose(CandleClosedEvent event) {
        if (!tradingStateService.isExitAllowed()) return;
        List<TradeEntity> openTrades = tradeRepository.findByStatus(TradeStatus.OPEN);
        if (openTrades.isEmpty()) return;
        for (TradeEntity trade : openTrades) {
            if (!positionSyncProperties.manageSyncedTrades() && trade.getTradeId().startsWith("SYNC-")) continue;
            try {
                evaluate(trade);
            } catch (Exception e) {
                log.error("[ExitMonitor] Error evaluating trade {}: {}", trade.getTradeId(), e.getMessage());
                errorEventService.critical("ExitMonitor", "Error evaluating trade " + trade.getTradeId() + ": " + e.getMessage(), e);
            }
        }
    }

    /**
     * Public entry point for scheduled backup evaluation.
     * Delegates to the same evaluate() pipeline used by CandleClosedEvent.
     * This ensures a single unified exit flow — no duplicated logic.
     */
    public void evaluateForScheduledCheck(TradeEntity trade) {
        evaluate(trade);
    }

    /**
     * Scheduled backup — evaluates all open trades every 60 seconds.
     * Safety net for when WebSocket ticks stop flowing (no CandleClosedEvent = no primary evaluation).
     * Uses the same evaluate() pipeline as the candle-driven path.
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 60_000, initialDelay = 20_000)
    public void scheduledBackupCheck() {
        if (!schedulerRegistry.isEnabled("exitBackup")) return;
        schedulerRegistry.recordRun("exitBackup");
        if (!MarketSessionHelper.isRegularSessionNow()) return;
        if (!tradingStateService.isExitAllowed()) return;
        List<TradeEntity> openTrades = tradeRepository.findByStatus(TradeStatus.OPEN);
        if (openTrades.isEmpty()) return;

        log.debug("[ExitMonitor-Backup] Evaluating {} open trades via scheduled backup", openTrades.size());
        for (TradeEntity trade : openTrades) {
            if (!positionSyncProperties.manageSyncedTrades() && trade.getTradeId().startsWith("SYNC-")) continue;
            try {
                evaluate(trade);
            } catch (Exception e) {
                log.error("[ExitMonitor-Backup] Error evaluating trade {}: {}", trade.getTradeId(), e.getMessage());
                errorEventService.critical("ExitMonitor-Backup", "Error evaluating trade " + trade.getTradeId() + ": " + e.getMessage(), e);
            }
        }
    }

    private void evaluate(TradeEntity trade) {
        Object lock = evaluationLocks.computeIfAbsent(trade.getTradeId(), id -> new Object());
        synchronized (lock) {
            // Re-fetch to get the latest @Version and status — the batch-loaded entity can be stale
            // when multiple CandleClosedEvent threads evaluate the same trade concurrently,
            // causing OptimisticLockException on subsequent saves.
            TradeEntity t = tradeRepository.findById(trade.getTradeId()).orElse(null);
            if (t == null || t.getStatus() != TradeStatus.OPEN) return;
            try {
                evaluateInternal(t);
            } catch (org.springframework.orm.ObjectOptimisticLockingFailureException e) {
                // Another thread (closeTrade, PositionSync) modified this trade concurrently.
                // This is harmless — the next evaluation cycle will pick up the fresh state.
                log.debug("[ExitMonitor] OptimisticLock on trade {} — concurrent modification, will retry next cycle",
                        trade.getTradeId());
            }
        }
    }

    private void evaluateInternal(TradeEntity trade) {
        // Re-check status — trade may have been closed by another monitor since the batch query
        if (trade.getStatus() != TradeStatus.OPEN) return;

        // Spread positions are managed exclusively by SpreadPositionExitMonitor
        StrategyConfig cfg = resolveConfig(trade);
        if (cfg.getStrategyType().isSpreadStrategy()) {
            log.debug("[ExitMonitor] Skipping spread trade {} ({}): managed by SpreadPositionExitMonitor",
                    trade.getTradeId(), cfg.getStrategyType());
            return;
        }

        // OI_MOMENTUM trades are managed by their own 1-sec loop (OIMomentumStrategy.managePosition)
        if (cfg.getStrategyType() == StrategyType.OI_MOMENTUM) {
            log.debug("[ExitMonitor] Skipping OI_MOMENTUM trade {} — managed by OIMomentumStrategy",
                    trade.getTradeId());
            return;
        }

        BigDecimal entryPrice = trade.getEntryPrice();
        if (entryPrice == null || entryPrice.signum() <= 0) {
            log.warn("[ExitMonitor] Invalid entry price for trade {}: {} — skipping evaluation",
                    trade.getTradeId(), entryPrice);
            return;
        }
        StrategyConfig config = cfg;

        // ── Global exit override: when enabled, global config values replace per-strategy exit params ──
        if (globalConfigService.isGlobalExitOverride()) {
            config = applyGlobalExitOverride(config);
        }

        LocalTime now = LocalTime.now(MarketSessionHelper.ist());
        LocalTime squareoffTime = LocalTime.of(config.getSquareoffHour(), config.getSquareoffMinute());

        Optional<Quote> quoteOpt = marketDataService.quote(trade.getInstrumentKey());

        // ── Tier 1: Liquidity emergency before time/SL exits when a quote exists ──
        if (quoteOpt.isPresent()) {
            Quote quote = quoteOpt.get();
            BigDecimal currentPrice = quote.lastPrice();
            if (currentPrice != null && currentPrice.signum() > 0) {
                var liquidityExit = liquidityEmergencyGate.checkSingleLegEmergency(trade, quote);
                if (liquidityExit.isPresent()) {
                    var signal = liquidityExit.get();
                    log.warn("[ExitMonitor] {} — tradeId={} {}", signal.reason(), trade.getTradeId(), signal.detail());
                    telegramAlertService.systemAlert(String.format(
                            "🚨 %s: %s | %s", signal.reason(), trade.getInstrumentKey(), signal.detail()));
                    close(trade, currentPrice, signal.reason());
                    return;
                }
            }
        } else {
            var noQuoteExit = liquidityEmergencyGate.checkSingleLegMissingQuote(trade);
            if (noQuoteExit.isPresent()) {
                var signal = noQuoteExit.get();
                log.warn("[ExitMonitor] {} — tradeId={} {}", signal.reason(), trade.getTradeId(), signal.detail());
                close(trade, resolveExitPrice(trade), signal.reason());
                return;
            }
        }

        // ── Time-based exits (after liquidity; do not require a live quote) ─────
        if (config.getMaxHoldMinutes() > 0 && trade.getEntryTime() != null) {
            long holdMinutes = java.time.Duration.between(trade.getEntryTime(), java.time.Instant.now()).toMinutes();
            if (holdMinutes >= config.getMaxHoldMinutes()) {
                BigDecimal exitPrice = resolveExitPrice(trade);
                double holdProfitPct = PositionPnlCalculator.profitPercent(entryPrice, exitPrice,
                        PositionPnlCalculator.isShortEntry(trade));
                log.info("[ExitMonitor] MAX HOLD TIME reached: tradeId={} instrument={} hold={}min max={}min profit={}%",
                        trade.getTradeId(), trade.getInstrumentKey(), holdMinutes, config.getMaxHoldMinutes(),
                        String.format("%.1f", holdProfitPct));
                telegramAlertService.systemAlert(String.format(
                        "⏱️ Max Hold Time: %s | Hold %dmin (max %d) | P&L %.1f%%",
                        trade.getInstrumentKey(), holdMinutes, config.getMaxHoldMinutes(), holdProfitPct));
                close(trade, exitPrice, "MAX_HOLD_TIME");
                return;
            }
        }

        if (now.isAfter(squareoffTime) || now.equals(squareoffTime)) {
            BigDecimal exitPrice = resolveExitPrice(trade);
            log.info("[ExitMonitor] SQUAREOFF TIME reached: tradeId={} instrument={} squareoff={}",
                    trade.getTradeId(), trade.getInstrumentKey(), squareoffTime);
            telegramAlertService.systemAlert(String.format(
                    "⏰ Squareoff Time: %s | Current ₹%.2f | Time %s",
                    trade.getInstrumentKey(), exitPrice.doubleValue(), squareoffTime));
            close(trade, exitPrice, "SQUAREOFF_TIME");
            return;
        }

        if (quoteOpt.isEmpty()) {
            log.debug("[ExitMonitor] No quote for {} — price exits deferred", trade.getInstrumentKey());
            return;
        }
        Quote quote = quoteOpt.get();
        BigDecimal currentPrice = quote.lastPrice();
        if (currentPrice == null || currentPrice.signum() <= 0) {
            return;
        }

        // Expiry danger zone: force-exit all positions after 3 PM on expiry day
        IndexType indexType = IndexType.fromName(trade.getUnderlying());
        if (expiryCalendar.isExpiryDangerZone(indexType)) {
            log.warn("[ExitMonitor] EXPIRY DANGER ZONE — force-closing: tradeId={} instrument={}",
                    trade.getTradeId(), trade.getInstrumentKey());
            telegramAlertService.systemAlert(String.format(
                    "⚠️ Expiry Danger Zone — force-closing: %s | Current ₹%.2f",
                    trade.getInstrumentKey(), currentPrice.doubleValue()));
            close(trade, currentPrice, "EXPIRY_DANGER_ZONE");
            return;
        }

        // Expiry afternoon: force-exit after 2 PM on expiry day (gamma risk escalates)
        if (expiryCalendar.isExpiryAfternoon(indexType)) {
            log.warn("[ExitMonitor] EXPIRY AFTERNOON — force-closing: tradeId={} instrument={}",
                    trade.getTradeId(), trade.getInstrumentKey());
            telegramAlertService.systemAlert(String.format(
                    "⚠️ Expiry Afternoon Exit — force-closing: %s | Current ₹%.2f",
                    trade.getInstrumentKey(), currentPrice.doubleValue()));
            close(trade, currentPrice, "EXPIRY_AFTERNOON_EXIT");
            return;
        }

        // Populate entry Greeks if not yet set (first evaluation after entry)
        populateEntryGreeksIfMissing(trade);

        // Days-to-expiry SL scaling removed — ATR already reflects current volatility.
        // Kept for reference: previously tightened SL near expiry (0.5× on expiry day).
        long daysToExpiry = expiryCalendar.daysToExpiry(indexType);

        boolean shortEntry = PositionPnlCalculator.isShortEntry(trade);

        // Track peak price (load from DB if available for restart recovery)
        BigDecimal savedPeak = trade.getPeakPrice();
        if (savedPeak != null) {
            peakPrices.putIfAbsent(trade.getTradeId(), savedPeak);
        }
        BigDecimal peak = peakPrices.merge(trade.getTradeId(), currentPrice,
                (existing, incoming) -> PositionPnlCalculator.updatePeak(incoming, existing, shortEntry));
        boolean betterPeak = savedPeak == null || savedPeak.signum() <= 0
                || (shortEntry ? peak.compareTo(savedPeak) < 0 : peak.compareTo(savedPeak) > 0);
        if (betterPeak) {
            trade.setPeakPrice(peak);
            tradeRepository.save(trade);
        }

        double profitPct = PositionPnlCalculator.profitPercent(entryPrice, currentPrice, shortEntry);
        double peakPct = PositionPnlCalculator.profitPercent(entryPrice, peak, shortEntry);

        long spotToken = indexType.spotToken();
        List<com.algo.trade.domain.Candle> candles15m = liveCandleBuilder.getHistory(spotToken, Timeframe.FIFTEEN_MINUTE);
        List<com.algo.trade.domain.Candle> candles1m = liveCandleBuilder.getHistory(spotToken, Timeframe.ONE_MINUTE);
        double underlyingAtr = candles15m.size() >= 15
                ? dynamicExitManager.calculateATR(candles15m, 14) : 0;

        double delta = 0.5;
        if (trade.getEntryDelta() != null && trade.getEntryDelta() > 0) {
            delta = trade.getEntryDelta();
        } else if (underlyingAtr > 0) {
            double spotPrice = liveInstrumentCache.getFuturesPrice(indexType);
            if (spotPrice > 0 && entryPrice.doubleValue() > 0) {
                double premiumRatio = entryPrice.doubleValue() / spotPrice;
                if (premiumRatio > 0.008) delta = 0.50;
                else if (premiumRatio > 0.004) delta = 0.35;
                else if (premiumRatio > 0.002) delta = 0.20;
                else delta = 0.10;
            }
        }

        ExitMode exitMode = ExitMode.fromString(tradingProperties.exit().exitModeSetting());
        TrailingMode trailingMode = TrailingMode.fromString(tradingProperties.exit().trailingModeSetting());
        boolean useAtrExits = underlyingAtr > 0 && entryPrice.doubleValue() > 0;

        double configSlPct = trade.getAppliedStopLossPercent() != null
                ? trade.getAppliedStopLossPercent().doubleValue()
                : config.getStopLossPercent().doubleValue();
        double configTargetPct = trade.getAppliedTargetPercent() != null
                ? trade.getAppliedTargetPercent().doubleValue()
                : config.getTargetPercent().doubleValue();
        double configTrailAct = config.getTrailingStopActivationPercent().doubleValue();
        double configTrailGap = config.getTrailingGapPercent().doubleValue();
        if (trade.getAppliedTrailingStopActivationPercent() != null) {
            configTrailAct = trade.getAppliedTrailingStopActivationPercent().doubleValue();
        }
        if (trade.getAppliedTrailingGapPercent() != null) {
            configTrailGap = trade.getAppliedTrailingGapPercent().doubleValue();
        }

        boolean preferConfigTrail = trade.getAppliedTrailingStopActivationPercent() != null
                || trade.getAppliedTrailingGapPercent() != null;

        ExitParamResolver.ResolvedExits resolved = exitParamResolver.resolveSingleLeg(
                exitMode, configSlPct, configTargetPct, configTrailAct, configTrailGap,
                entryPrice.doubleValue(), underlyingAtr, 0, delta, (int) daysToExpiry, preferConfigTrail);
        double slPct = resolved.stopLossPercent();
        double targetPct = resolved.targetPercent();

        publishEvaluationSnapshot(trade, profitPct, peakPct, resolved, "EVALUATING");

        log.debug("[ExitMonitor] tradeId={} short={} profit={}% sl={}% target={}% mode={} atrUsed={}",
                trade.getTradeId(), shortEntry, String.format("%.1f", profitPct),
                String.format("%.1f", slPct), String.format("%.1f", targetPct), exitMode, resolved.atrUsed());

        // ── 1. Stop Loss ──────────────────────────────────────────────────────
        if (profitPct <= -slPct) {
            log.warn("[ExitMonitor] STOP LOSS hit: tradeId={} instrument={} entry={} current={} profit={}% sl={}%{}",
                    trade.getTradeId(), trade.getInstrumentKey(), entryPrice, currentPrice,
                    String.format("%.1f", profitPct), String.format("%.1f", slPct), "");
            telegramAlertService.systemAlert(String.format(
                    "\uD83D\uDD34 SL Hit: %s | Entry \u20B9%.2f \u2192 \u20B9%.2f | P&L %.1f%%",
                    trade.getInstrumentKey(), entryPrice.doubleValue(), currentPrice.doubleValue(), profitPct));
            close(trade, currentPrice, "STOP_LOSS");
            return;
        }

        // ── 2. Target ─────────────────────────────────────────────────────────
        if (profitPct >= targetPct) {
            log.info("[ExitMonitor] TARGET hit: tradeId={} instrument={} entry={} current={} profit={}% target={}%",
                    trade.getTradeId(), trade.getInstrumentKey(), entryPrice, currentPrice,
                    String.format("%.1f", profitPct), targetPct);
            telegramAlertService.systemAlert(String.format(
                    "\uD83D\uDFE2 Target Hit: %s | Entry \u20B9%.2f \u2192 \u20B9%.2f | P&L +%.1f%%",
                    trade.getInstrumentKey(), entryPrice.doubleValue(), currentPrice.doubleValue(), profitPct));
            close(trade, currentPrice, "TARGET");
            return;
        }

        // ── 3. Trailing stop (single path: PRICE-level or ATR-percent, not both) ──
        if (trailingMode == TrailingMode.PRICE) {
            BigDecimal trailActivation = BigDecimal.valueOf(resolved.trailActivationPercent());
            BigDecimal trailGap = BigDecimal.valueOf(resolved.trailGapPercent());

            if (!trailingStops.containsKey(trade.getTradeId()) && trade.getTrailingStopPrice() != null) {
                trailingStops.put(trade.getTradeId(), trade.getTrailingStopPrice());
            }
            Optional<BigDecimal> currentStop = Optional.ofNullable(trailingStops.get(trade.getTradeId()));
            Optional<BigDecimal> updatedStop = trailingStopService.nextStop(entryPrice, peak, currentStop,
                    trailActivation, trailGap, shortEntry);

            if (updatedStop.isPresent()) {
                trailingStops.put(trade.getTradeId(), updatedStop.get());
                if (!updatedStop.get().equals(trade.getTrailingStopPrice())) {
                    trade.setTrailingStopPrice(updatedStop.get());
                    tradeRepository.save(trade);
                }
                if (trailingStopService.isStopHit(currentPrice, updatedStop.get(), shortEntry)) {
                    log.info("[ExitMonitor] TRAILING STOP hit: tradeId={} profit={}%",
                            trade.getTradeId(), String.format("%.1f", profitPct));
                    telegramAlertService.systemAlert(String.format(
                            "\uD83D\uDFE1 Trailing Stop: %s | P&L %.1f%%",
                            trade.getInstrumentKey(), profitPct));
                    close(trade, currentPrice, "TRAILING_STOP");
                    return;
                }
            }
        } else if (useAtrExits && peakPct >= resolved.trailActivationPercent()) {
            double atrTrailLevel = dynamicExitManager.calculateTrailingSL(
                    profitPct, peakPct, underlyingAtr, entryPrice.doubleValue());
            if (atrTrailLevel > -900 && profitPct < atrTrailLevel) {
                log.info("[ExitMonitor] ATR TRAILING STOP hit: tradeId={} profit={}% < trail={}%",
                        trade.getTradeId(), String.format("%.1f", profitPct), String.format("%.1f", atrTrailLevel));
                close(trade, currentPrice, "ATR_TRAILING_STOP");
                return;
            }
        }

        // ── 4b. IV Collapse Exit — exit when IV drops significantly from entry ──
        // Options lose value when IV crushes even if the underlying hasn't moved against you.
        // Common after events (budget, RBI, earnings) where IV was elevated at entry.
        if (trade.getEntryIV() != null && trade.getEntryIV() > 0) {
            Optional<java.math.BigDecimal> currentIvOpt = quote.impliedVolatility();
            if (currentIvOpt.isPresent() && currentIvOpt.get().doubleValue() > 0) {
                double entryIV = trade.getEntryIV();
                double currentIV = currentIvOpt.get().doubleValue();
                double ivDropPct = ((entryIV - currentIV) / entryIV) * 100;
                double ivCollapseThreshold = globalConfigService.getIvCollapseExitThresholdPercent().doubleValue();
                double ivCollapseMaxProfit = globalConfigService.getIvCollapseMaxProfitPercent().doubleValue();
                // Exit if IV has dropped more than threshold AND trade is not already profitable beyond max profit gate
                if (ivDropPct >= ivCollapseThreshold && profitPct < ivCollapseMaxProfit) {
                    log.info("[ExitMonitor] IV COLLAPSE EXIT: tradeId={} entryIV={} currentIV={} drop={}% profit={}%",
                            trade.getTradeId(), String.format("%.1f", entryIV),
                            String.format("%.1f", currentIV), String.format("%.1f", ivDropPct),
                            String.format("%.1f", profitPct));
                    telegramAlertService.systemAlert(String.format(
                            "📉 IV Collapse Exit: %s | IV %.1f%% → %.1f%% (drop %.1f%%) | P&L %.1f%%",
                            trade.getInstrumentKey(), entryIV, currentIV, ivDropPct, profitPct));
                    close(trade, currentPrice, "IV_COLLAPSE_EXIT");
                    return;
                }
            }
        }

        // ── 5. Progressive profit booking ──────────────────────────────────────
        if (useAtrExits && profitPct > 0) {
            Set<String> fired = firedLayers.computeIfAbsent(trade.getTradeId(),
                    id -> loadFiredLayers(trade));
            Optional<com.algo.trade.strategy.DynamicExitManager.ExitLayer> layer =
                    dynamicExitManager.nextExitLayer(profitPct, fired);
            if (layer.isPresent()) {
                com.algo.trade.strategy.DynamicExitManager.ExitLayer l = layer.get();
                int lotSize = IndexType.fromName(trade.getUnderlying()).lotSize();
                int rawQty = (int) Math.round(trade.getQuantity() * l.exitFraction());
                // F&O orders must be whole-lot multiples — round DOWN to nearest lot
                int partialQty = (rawQty / lotSize) * lotSize;
                if (partialQty > 0) {
                    fired.add(l.name());
                    log.info("[ExitMonitor] PROGRESSIVE BOOKING {}: tradeId={} profit={}% qty={} lotSize={}",
                            l.name(), trade.getTradeId(), String.format("%.1f", profitPct), partialQty, lotSize);
                    executionEngine.closePartialTrade(trade.getTradeId(), partialQty, currentPrice, l.name());
                } else if (trade.getQuantity() <= lotSize && profitPct >= l.triggerProfitPct()) {
                    // Position is 1 lot — can't partial close. If this is the last layer (80%+), do a full close.
                    if (l.triggerProfitPct() >= 80.0) {
                        fired.add(l.name());
                        log.info("[ExitMonitor] PROGRESSIVE BOOKING {} → FULL CLOSE (1-lot position): tradeId={} profit={}%",
                                l.name(), trade.getTradeId(), String.format("%.1f", profitPct));
                        close(trade, currentPrice, "PROGRESSIVE_FULL_CLOSE_" + l.name());
                        return;
                    }
                    // Earlier layers on 1-lot: skip without marking fired — let higher layers try
                    log.debug("[ExitMonitor] PROGRESSIVE BOOKING {}: deferred — 1-lot position, waiting for higher layer",
                            l.name());
                }
            }
        }

        // ── 6. Stall detection — exit dead trades bleeding theta ───────────────
        // Extended range: -10% to +15% (was +8%). Trades above +8% that stall also bleed theta.
        if (useAtrExits && profitPct > -10 && profitPct < 15 && !candles1m.isEmpty()) {
            if (dynamicExitManager.isStalled(candles1m, trade.getEntryTime(), underlyingAtr, 10)) {
                log.info("[ExitMonitor] STALL EXIT: tradeId={} — underlying flat for 10 candles, profit={}%",
                        trade.getTradeId(), String.format("%.1f", profitPct));
                telegramAlertService.systemAlert(String.format(
                        "💤 Stall Exit: %s | Underlying flat 10 min | P&L %.1f%%",
                        trade.getInstrumentKey(), profitPct));
                close(trade, currentPrice, "STALL_EXIT");
                return;
            }
        }

        // ── 7. Gamma spike exit — expiry day only, before EXPIRY_AFTERNOON gate ──
        if (useAtrExits && expiryCalendar.isExpiryDay(indexType)
                && !expiryCalendar.isExpiryAfternoon(indexType)
                && dynamicExitManager.isGammaSpike(candles15m, underlyingAtr)) {
            if (profitPct >= 20) {
                log.info("[ExitMonitor] GAMMA SPIKE PROFIT: tradeId={} profit={}% — locking in expiry spike",
                        trade.getTradeId(), String.format("%.1f", profitPct));
                telegramAlertService.systemAlert(String.format(
                        "⚡ Gamma Spike Exit (profit): %s | Spike detected | P&L +%.1f%%",
                        trade.getInstrumentKey(), profitPct));
                close(trade, currentPrice, "GAMMA_SPIKE_PROFIT");
                return;
            } else if (profitPct <= -25) {
                log.warn("[ExitMonitor] GAMMA SPIKE PROTECTION: tradeId={} loss={}% — cutting before escalation",
                        trade.getTradeId(), String.format("%.1f", profitPct));
                telegramAlertService.systemAlert(String.format(
                        "⚡ Gamma Spike Exit (protection): %s | Spike against position | P&L %.1f%%",
                        trade.getInstrumentKey(), profitPct));
                close(trade, currentPrice, "GAMMA_SPIKE_PROTECTION");
                return;
            }
        }

        // ── 8. Momentum breakout exit (for short premium strategies) ──────────
        if (useAtrExits && candles15m.size() >= 15) {
            if (cfg.getStrategyType().isSellingStrategy() && dynamicExitManager.isBreakout(candles15m, 14)) {
                log.warn("[ExitMonitor] BREAKOUT EXIT: tradeId={} — market trending, exiting short premium",
                        trade.getTradeId());
                telegramAlertService.systemAlert(String.format(
                        "⚡ Breakout Exit: %s | Market moved > 2.5x ATR — exiting short premium position",
                        trade.getInstrumentKey()));
                close(trade, currentPrice, "MOMENTUM_BREAKOUT_EXIT");
                return;
            }
        }

        // ── 9. VWAP reversal exit — close long options when underlying crosses back through VWAP ──
        if (globalConfigService.isVwapExitEnabled() && profitPct > 0) {
            checkVwapReversal(trade, currentPrice, profitPct);
        }

        // ── ML Exit Shadow: record HOLD evaluation (no exit triggered this cycle) ──
        recordExitShadow(trade, entryPrice, currentPrice, profitPct, peakPct, underlyingAtr,
                useAtrExits, false, "HOLD");
        publishEvaluationSnapshot(trade, profitPct, peakPct, resolved, "HOLD");
    }

    private void publishEvaluationSnapshot(TradeEntity trade, double profitPct, double peakPct,
                                           ExitParamResolver.ResolvedExits resolved, String decision) {
        if (exitEvaluationRegistry == null) {
            return;
        }
        exitEvaluationRegistry.put(new ExitEvaluationSnapshot(
                trade.getTradeId(),
                "SINGLE_LEG",
                trade.getStrategyType() != null ? trade.getStrategyType() : "UNKNOWN",
                trade.getUnderlying() != null ? trade.getUnderlying() : "NIFTY",
                java.time.Instant.now(),
                profitPct,
                peakPct,
                resolved.stopLossPercent(),
                resolved.targetPercent(),
                resolved.trailActivationPercent(),
                resolved.trailGapPercent(),
                resolved.atrUsed(),
                resolved.mode(),
                decision,
                decision));
    }

    /**
     * Exit a profitable long position when the underlying spot crosses back through VWAP,
     * indicating the directional thesis has reversed.
     * CE: exit when spot drops back below VWAP (bullish momentum broken).
     * PE: exit when spot rallies back above VWAP (bearish momentum broken).
     */
    private void checkVwapReversal(TradeEntity trade, BigDecimal currentPrice, double profitPct) {
        try {
            long spotToken = IndexType.fromName(trade.getUnderlying()).spotToken();
            List<com.algo.trade.domain.Candle> spotCandles = liveCandleBuilder.getHistory(spotToken, Timeframe.ONE_MINUTE);
            if (spotCandles.isEmpty()) return;

            BigDecimal vwap = vwapIndicator.calculateSessionAnchored(
                    spotCandles, java.time.ZoneId.of("Asia/Kolkata"));
            if (vwap == null || vwap.signum() <= 0) return;

            BigDecimal spotPrice = spotCandles.getLast().close();
            String optionType = trade.getOptionType();
            boolean triggered = false;

            if ("CE".equalsIgnoreCase(optionType) && spotPrice.compareTo(vwap) < 0) {
                triggered = true; // spot below VWAP — bullish CE thesis broken
            } else if ("PE".equalsIgnoreCase(optionType) && spotPrice.compareTo(vwap) > 0) {
                triggered = true; // spot above VWAP — bearish PE thesis broken
            }

            if (triggered) {
                log.info("[ExitMonitor] VWAP REVERSAL: tradeId={} instrument={} optionType={} spot={} vwap={} profit={}%",
                        trade.getTradeId(), trade.getInstrumentKey(), optionType,
                        spotPrice, vwap, String.format("%.1f", profitPct));
                telegramAlertService.systemAlert(String.format(
                        "🔄 VWAP Reversal Exit: %s | Spot ₹%.2f crossed VWAP ₹%.2f | P&L +%.1f%%",
                        trade.getInstrumentKey(), spotPrice.doubleValue(), vwap.doubleValue(), profitPct));
                close(trade, currentPrice, "VWAP_REVERSAL");
                return;
            }
        } catch (Exception e) {
            log.debug("[ExitMonitor] VWAP reversal check error for {}: {}", trade.getTradeId(), e.getMessage());
        }
    }

    private void close(TradeEntity trade, BigDecimal price, String reason) {
        // Record ML exit shadow BEFORE closing (trade still has OPEN status and all data)
        boolean shortEntry = PositionPnlCalculator.isShortEntry(trade);
        double profitPct = PositionPnlCalculator.profitPercent(trade.getEntryPrice(), price, shortEntry);
        BigDecimal peak = peakPrices.getOrDefault(trade.getTradeId(), price);
        double peakPct = PositionPnlCalculator.profitPercent(trade.getEntryPrice(), peak, shortEntry);
        recordExitShadow(trade, trade.getEntryPrice(), price, profitPct, peakPct, 0, false, true, reason);

        try {
            executionEngine.closeTrade(trade.getTradeId(), price, reason);
            // Only clear in-memory state after confirmed successful close
            trailingStops.remove(trade.getTradeId());
            peakPrices.remove(trade.getTradeId());
            firedLayers.remove(trade.getTradeId());
            if (exitEvaluationRegistry != null) {
                exitEvaluationRegistry.remove(trade.getTradeId());
            }
        } catch (Exception e) {
            log.warn("[ExitMonitor] Failed to close trade {} — retaining trailing stop state for next evaluation: {}",
                    trade.getTradeId(), e.getMessage());
        }
    }

    /**
     * Record exit evaluation to ML shadow CSV for future model training.
     */
    private void recordExitShadow(TradeEntity trade, BigDecimal entryPrice, BigDecimal currentPrice,
                                   double profitPct, double peakPct, double atr, boolean useAtr,
                                   boolean systemExited, String exitReason) {
        try {
            double holdMinutes = trade.getEntryTime() != null
                    ? java.time.Duration.between(trade.getEntryTime(), java.time.Instant.now()).toMinutes() : 0;
            double vix = marketGuard.getCurrentVix();
            IndexType idx = IndexType.fromName(trade.getUnderlying());
            long dte = expiryCalendar.daysToExpiry(idx);
            double entryIV = trade.getEntryIV() != null ? trade.getEntryIV() : 0;
            double currentIV = marketDataService.quote(trade.getInstrumentKey())
                    .flatMap(q -> q.impliedVolatility()).map(BigDecimal::doubleValue).orElse(0.0);
            double ivChange = entryIV > 0 ? ((currentIV - entryIV) / entryIV) * 100 : 0;

            BigDecimal trailStop = trailingStops.get(trade.getTradeId());
            double trailActive = trailStop != null ? 1.0 : 0.0;
            double trailDistance = trailStop != null && currentPrice.signum() > 0
                    ? currentPrice.subtract(trailStop).doubleValue() / currentPrice.doubleValue() * 100 : 0;

            double optType = "PE".equals(trade.getOptionType()) ? 1.0 : 0.0;
            double minSinceOpen = java.time.Duration.between(
                    java.time.LocalTime.of(9, 15),
                    java.time.LocalTime.now(java.time.ZoneId.of("Asia/Kolkata"))).toMinutes();
            double isExpiry = expiryCalendar.isExpiryDay(idx) ? 1.0 : 0.0;

            double bidAskSpread = 0;
            var quoteOpt = marketDataService.quote(trade.getInstrumentKey());
            if (quoteOpt.isPresent()) {
                BigDecimal bid = quoteOpt.get().bid().orElse(BigDecimal.ZERO);
                BigDecimal ask = quoteOpt.get().ask().orElse(BigDecimal.ZERO);
                if (bid.signum() > 0 && ask.signum() > 0 && currentPrice.signum() > 0) {
                    bidAskSpread = ask.subtract(bid).doubleValue() / currentPrice.doubleValue() * 100;
                }
            }

            double strategyEncoded = encodeStrategy(trade.getStrategyType());
            double underlyingEncoded = encodeUnderlying(trade.getUnderlying());

            var features = new com.algo.trade.ml.MlExitFeatureVector(
                    profitPct, peakPct, peakPct - profitPct, holdMinutes,
                    entryPrice.doubleValue(), currentPrice.doubleValue(),
                    vix, atr, dte,
                    entryIV, currentIV, ivChange,
                    trailActive, trailDistance,
                    strategyEncoded, optType, underlyingEncoded,
                    minSinceOpen, isExpiry, bidAskSpread,
                    systemExited ? 1.0 : 0.0, encodeExitReason(exitReason)
            );

            mlExitShadowRecorder.recordExitEvaluation(
                    trade.getTradeId(), trade.getInstrumentKey(),
                    trade.getStrategyType(), trade.getUnderlying(),
                    features, systemExited, exitReason);
        } catch (Exception e) {
            log.debug("[ExitMonitor] ML exit shadow recording failed: {}", e.getMessage());
        }
    }

    private static double encodeStrategy(String strategyType) {
        if (strategyType == null) return 0;
        return switch (strategyType) {
            case "DIRECTIONAL_BUY" -> 0;
            case "SCALPING" -> 1;
            case "VOLATILITY_BREAKOUT" -> 2;
            case "EVENT_DRIVEN_BUY" -> 3;
            case "GAP_AND_GO" -> 4;
            case "REVERSAL_BUY" -> 5;
            case "OI_SHIFT_TRAP" -> 6;
            case "EXPIRY_GAMMA" -> 7;
            case "EXPIRY_REVERSAL" -> 8;
            case "MOMENTUM" -> 9;
            case "ITM_CONVICTION" -> 10;
            default -> 99;
        };
    }

    private static double encodeUnderlying(String underlying) {
        if (underlying == null) return 0;
        return switch (underlying) {
            case "NIFTY" -> 0;
            case "BANKNIFTY" -> 1;
            case "SENSEX" -> 2;
            case "FINNIFTY" -> 3;
            case "MIDCPNIFTY" -> 4;
            default -> 0;
        };
    }

    private static double encodeExitReason(String reason) {
        if (reason == null) return 0;
        return switch (reason) {
            case "HOLD" -> 0;
            case "STOP_LOSS" -> 1;
            case "TARGET" -> 2;
            case "TRAILING_STOP" -> 3;
            case "ATR_TRAILING_STOP" -> 4;
            case "MAX_HOLD_TIME" -> 5;
            case "SQUAREOFF_TIME" -> 6;
            case "IV_COLLAPSE_EXIT" -> 7;
            case "STALL_EXIT" -> 8;
            case "GAMMA_SPIKE_PROFIT", "GAMMA_SPIKE_PROTECTION" -> 9;
            case "EXPIRY_DANGER_ZONE", "EXPIRY_AFTERNOON_EXIT" -> 10;
            case "SPREAD_WIDENING_EXIT" -> 11;
            case "VWAP_REVERSAL" -> 12;
            case "MOMENTUM_BREAKOUT_EXIT" -> 13;
            default -> 99;
        };
    }

    /**
     * Resolve exit price: live quote if available, fallback to entry price.
     * Used by time-based exits (max hold, squareoff) that must not be blocked by missing quotes.
     */
    private BigDecimal resolveExitPrice(TradeEntity trade) {
        return marketDataService.quote(trade.getInstrumentKey())
                .map(q -> q.lastPrice())
                .filter(p -> p != null && p.signum() > 0)
                .orElse(trade.getEntryPrice());
    }

    /**
     * Resolve strategy config for a trade.
     * Uses the persisted strategyType field first, falls back to entryReason text parsing.
     */
    private StrategyConfig resolveConfig(TradeEntity trade) {
        String underlying = trade.getUnderlying() != null ? trade.getUnderlying() : "NIFTY";
        // Prefer the explicit strategyType field (set at entry time)
        if (trade.getStrategyType() != null && !trade.getStrategyType().isBlank()) {
            try {
                com.algo.trade.strategy.StrategyType type =
                        com.algo.trade.strategy.StrategyType.valueOf(trade.getStrategyType());
                return strategyConfigService.getConfig(type, underlying);
            } catch (IllegalArgumentException ignored) { /* fall through to text parsing */ }
        }
        // Fallback: parse from entryReason text
        String entryReason = trade.getEntryReason() != null ? trade.getEntryReason().toUpperCase() : "";
        return strategyConfigService.getAll().stream()
                .filter(c -> c.getUnderlying().equals(underlying) && entryReason.contains(c.getStrategyType().name()))
                .findFirst()
                .orElseGet(() -> strategyConfigService.getDirectionalBuyConfig(underlying));
    }

    /**
     * Creates a shallow copy of the strategy config with global exit values overlaid.
     * Only overrides SL, target, trailing stop, max hold, and squareoff time.
     * Strategy type, underlying, lots, and entry params are preserved.
     */
    private StrategyConfig applyGlobalExitOverride(StrategyConfig original) {
        StrategyConfig overridden = new StrategyConfig(original.getStrategyType());
        // Copy all fields from original
        overridden.setUnderlying(original.getUnderlying());
        overridden.setEnabled(original.isEnabled());
        overridden.setLots(original.getLots());
        overridden.setPaperTrading(original.isPaperTrading());
        overridden.setMinCombinedPremium(original.getMinCombinedPremium());
        overridden.setMaxIvRankForBuying(original.getMaxIvRankForBuying());
        overridden.setScanTimeframe(original.getScanTimeframe());
        overridden.setCandleTimeframe(original.getCandleTimeframe());
        overridden.setTrendTimeframe(original.getTrendTimeframe());
        // Override exit params with global config values
        overridden.setStopLossPercent(globalConfigService.getStopLossPercent());
        overridden.setTargetPercent(globalConfigService.getTargetPercent());
        overridden.setTrailingStopActivationPercent(globalConfigService.getTrailingStopActivationPercent());
        overridden.setTrailingGapPercent(globalConfigService.getTrailingGapPercent());
        overridden.setMaxHoldMinutes(globalConfigService.getMaxHoldMinutes());
        // Squareoff time from global forcedExitTime
        java.time.LocalTime forcedExit = globalConfigService.getForcedExitTime();
        overridden.setSquareoffHour(forcedExit.getHour());
        overridden.setSquareoffMinute(forcedExit.getMinute());
        log.debug("[ExitMonitor] Global exit override active: SL={}% target={}% trailing={}%/{}% maxHold={}min squareoff={}",
                overridden.getStopLossPercent(), overridden.getTargetPercent(),
                overridden.getTrailingStopActivationPercent(), overridden.getTrailingGapPercent(),
                overridden.getMaxHoldMinutes(), forcedExit);
        return overridden;
    }

    private Set<String> loadFiredLayers(TradeEntity trade) {
        Set<String> set = ConcurrentHashMap.newKeySet();
        String layers = trade.getPartialExitLayers();
        if (layers != null && !layers.isBlank()) {
            set.addAll(Arrays.asList(layers.split(",")));
        }
        return set;
    }

    /** Populate entry Greeks on a trade if not yet set. */
    private void populateEntryGreeksIfMissing(TradeEntity trade) {
        if (trade.getEntryDelta() != null) return; // already populated
        String instrumentKey = trade.getInstrumentKey();
        if (instrumentKey == null || !instrumentKey.contains(":")) return;
        String symbol = instrumentKey.split(":", 2)[1];
        liveCandleBuilder.toString(); // ensure bean is initialized (no-op)
        // Look up from LiveInstrumentCache via marketDataService quote
        // The GreeksCalculator runs on every tick in LiveInstrumentCache
        // We can't access LiveInstrumentCache directly here, but the Greeks
        // are computed and stored on OptionInstrument objects in the cache.
        // For now, store IV from the quote if available.
        marketDataService.quote(instrumentKey).ifPresent(q -> {
            q.impliedVolatility().ifPresent(iv -> {
                trade.setEntryIV(iv.doubleValue());
                tradeRepository.save(trade);
                log.debug("[ExitMonitor] Entry IV populated: tradeId={} iv={}", trade.getTradeId(), iv);
            });
        });
    }
}
