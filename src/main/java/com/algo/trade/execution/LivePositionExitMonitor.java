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
    /** Hard ceiling on the single-leg exit stop-loss %, matching DynamicGateEngine.MAX_SL. The global
     *  stopLossPercent (12, not a per-user-profile field) would otherwise let exits fire above this. */
    private static final double MAX_EXIT_SL_PCT = 10.0;
    /** Fresh-trade grace: defer profit-taking/trailing/theta tiers for this long after entry (protective
     *  tiers — liquidity, squareoff, stop-loss — still fire during grace). Applied inside evaluateInternal. */
    private static final long ENTRY_GRACE_SECONDS = 60;

    /** Conviction Override Engine — GLOBAL microstructure reversal exit for any non-strategy-managed
     *  position (all strategies except spreads and strategy-managed OI-momentum, which are skipped above).
     *  Optional bean; live + config-gated. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.marketdata.ConvictionOverrideEngine convictionOverrideEngine;
    @org.springframework.beans.factory.annotation.Value("${conviction-override.exit.global-enabled:true}")
    private boolean coeExitGlobalEnabled;
    @org.springframework.beans.factory.annotation.Value("${conviction-override.exit.min-hold-sec:3}")
    private long coeExitMinHoldSec;

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

    /** ORPHAN SAFETY (2026-07-01): to decide whether a primary OI_MOMENTUM trade is actually being managed
     *  by the OI-momentum loop (adopted into an IndexState) vs orphaned/unmanaged. @Lazy + optional to avoid
     *  any construction-order cycle. When the strategy is NOT tracking a primary OI_MOMENTUM trade, this
     *  monitor manages it as the safety net instead of skipping it. */
    @org.springframework.context.annotation.Lazy
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.strategy.oimomentum.OIMomentumStrategy oiMomentumStrategy;
    private final com.algo.trade.monitoring.ErrorEventService errorEventService;
    private final com.algo.trade.monitoring.SchedulerRegistry schedulerRegistry;
    private final com.algo.trade.ml.MlExitShadowRecorder mlExitShadowRecorder;
    private final com.algo.trade.risk.MarketGuard marketGuard;
    private final com.algo.trade.marketdata.LiveInstrumentCache liveInstrumentCache;
    private final TradingProperties tradingProperties;
    private final ExitParamResolver exitParamResolver;
    private final ExitEvaluationRegistry exitEvaluationRegistry;
    private final com.algo.trade.execution.exit.LiquidityEmergencyGate liquidityEmergencyGate;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.strategy.oishifttrap.ShiftTrapMaeMfeTracker shiftTrapMaeMfeTracker;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.strategy.oishifttrap.OiShiftTrapExitRecorder oiShiftTrapExitRecorder;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.strategy.oishifttrap.OiShiftTrapConfig oiShiftTrapConfig;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.strategy.oishifttrap.ShiftTrapOiUnwindExitDetector shiftTrapOiUnwindExitDetector;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.strategy.oishifttrap.OiShiftTrapTuneRecorder oiShiftTrapTuneRecorder;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.strategy.ExpiryOperatorTrapDetector expiryTrapDetector;

    // tradeId → best price seen since entry (high for long, low for short)
    private final Map<String, BigDecimal> peakPrices = new ConcurrentHashMap<>();
    // tradeId → current trailing stop price
    private final Map<String, BigDecimal> trailingStops = new ConcurrentHashMap<>();
    // tradeId → set of progressive exit layer names that have already fired
    private final Map<String, Set<String>> firedLayers = new ConcurrentHashMap<>();
    // per-trade mutex — serializes concurrent evaluations when multiple CandleClosedEvents fire simultaneously
    private final Map<String, Object> evaluationLocks = new ConcurrentHashMap<>();

    // ── Tiered trailing (Finding 4): tighten the trail gap as the peak grows, so large winners
    // give back less. Default OFF → flat gap unchanged. Only ever tightens (never loosens). ──
    @org.springframework.beans.factory.annotation.Value("${exit.tiered-trailing.enabled:false}")
    private boolean tieredTrailingEnabled;
    @org.springframework.beans.factory.annotation.Value("${exit.tiered-trailing.tier1-peak:8.0}")
    private double tieredTier1Peak;
    @org.springframework.beans.factory.annotation.Value("${exit.tiered-trailing.tier1-gap:3.0}")
    private double tieredTier1Gap;
    @org.springframework.beans.factory.annotation.Value("${exit.tiered-trailing.tier2-peak:15.0}")
    private double tieredTier2Peak;
    @org.springframework.beans.factory.annotation.Value("${exit.tiered-trailing.tier2-gap:2.0}")
    private double tieredTier2Gap;

    /**
     * Tiered trail gap (Finding 4). Returns the configured flat gap unless tiered trailing is on,
     * in which case the gap is tightened in steps as {@code peakPct} grows. Never returns a gap
     * wider than the base, so enabling it can only protect more profit, never less.
     */
    private double tieredTrailGap(double baseGap, double peakPct) {
        if (!tieredTrailingEnabled) return baseGap;
        double gap = baseGap;
        if (peakPct >= tieredTier1Peak) gap = Math.min(gap, tieredTier1Gap);
        if (peakPct >= tieredTier2Peak) gap = Math.min(gap, tieredTier2Gap);
        return gap;
    }

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
            if (!globalConfigService.isManageSyncedTrades() && trade.getTradeId().startsWith("SYNC-")) continue;
            // NOTE: the fresh-trade grace period is now applied INSIDE evaluateInternal — AFTER the
            // protective tiers (liquidity / squareoff / stop-loss) — so a crash still stops out in the
            // first 60s. It must NOT short-circuit the whole evaluation here (that blocked STOP_LOSS and
            // let fresh entries run to ~-20% before the first post-grace tick). (2026-07-02)
            try {
                // Multi-user: evaluate each trade in its owner's context for correct broker routing
                Long ownerId = trade.getUserId();
                if (ownerId != null) {
                    com.algo.trade.multiuser.UserContext.runAs(ownerId, () -> evaluate(trade));
                } else {
                    evaluate(trade);
                }
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
            if (!globalConfigService.isManageSyncedTrades() && trade.getTradeId().startsWith("SYNC-")) continue;
            // Fresh-trade grace is applied inside evaluateInternal (after the protective tiers), not here —
            // see onCandleClose note. Blanket-skipping fresh trades here would also block their stop-loss.
            try {
                // Multi-user: evaluate each trade in the context of its owner so that
                // exit orders are placed against the correct user's broker session.
                Long ownerId = trade.getUserId();
                if (ownerId != null) {
                    com.algo.trade.multiuser.UserContext.runAs(ownerId, () -> evaluate(trade));
                } else {
                    evaluate(trade);
                }
            } catch (Exception e) {
                log.error("[ExitMonitor-Backup] Error evaluating trade {}: {}", trade.getTradeId(), e.getMessage());
                errorEventService.critical("ExitMonitor-Backup", "Error evaluating trade " + trade.getTradeId() + ": " + e.getMessage(), e);
            }
        }
    }

    /**
     * FAST global exit sweep (every 500ms) — runs ONLY the Conviction Override reversal check, not the
     * full candle-cadence exit pipeline. This is what makes the microstructure reversal exit fast: it
     * fires within ~½s of the spoof/absorption reversal instead of waiting for the next candle close
     * (~1 min). Cheap — the option LTP/quote comes from the WebSocket cache, no broker REST call. All
     * other exit tiers (SL / trailing / squareoff / theta) stay on their proven candle cadence.
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 500, initialDelay = 20_000)
    public void fastConvictionExitSweep() {
        if (!coeExitGlobalEnabled || convictionOverrideEngine == null || !convictionOverrideEngine.isEnabled()) return;
        if (!MarketSessionHelper.isRegularSessionNow() || !tradingStateService.isExitAllowed()) return;
        List<TradeEntity> openTrades = tradeRepository.findByStatus(TradeStatus.OPEN);
        if (openTrades.isEmpty()) return;
        for (TradeEntity trade : openTrades) {
            if (trade.getTradeId() == null) continue;
            if (!globalConfigService.isManageSyncedTrades() && trade.getTradeId().startsWith("SYNC-")) continue;
            try {
                Long ownerId = trade.getUserId();
                if (ownerId != null) {
                    com.algo.trade.multiuser.UserContext.runAs(ownerId, () -> tryConvictionExit(trade));
                } else {
                    tryConvictionExit(trade);
                }
            } catch (Exception e) {
                log.debug("[COE] fast sweep error for {}: {}", trade.getTradeId(), e.toString());
            }
        }
    }

    /**
     * The GLOBAL Conviction Override reversal exit for one trade. Uses the SAME per-trade lock + fresh
     * re-fetch as evaluate() (so it can't race a candle-close evaluation of the same trade), and applies
     * the same skips as evaluateInternal: spreads (managed by SpreadPositionExitMonitor) and
     * strategy-managed OI-momentum (handled by the OI-momentum per-tick loop) are excluded, and only
     * LONG option buys are eligible (the reversal signal is long-oriented). Never throws.
     */
    private void tryConvictionExit(TradeEntity trade) {
        Object lock = evaluationLocks.computeIfAbsent(trade.getTradeId(), id -> new Object());
        synchronized (lock) {
            try {
                TradeEntity t = tradeRepository.findById(trade.getTradeId()).orElse(null);
                if (t == null || t.getStatus() != TradeStatus.OPEN) return;
                if (t.getEntryTime() == null
                        || java.time.Duration.between(t.getEntryTime(), java.time.Instant.now()).getSeconds() < coeExitMinHoldSec) return;
                if (PositionPnlCalculator.isShortEntry(t)) return; // long-only (signal is long-oriented)

                // OI_SHIFT_TRAP: the trap thesis needs 5-15 minutes to develop (writer-squeeze is slow).
                // COE spoof/absorption signals are calibrated for momentum entries (3s reaction) and
                // frequently misfire on trap strikes where heavy OI churn is the EXPECTED environment.
                // Grace: defer COE exit for trap trades until held ≥ 5 minutes (300s). The trap's own
                // exit logic (ShiftTrapOiUnwindExitDetector) remains the authority for early trap exits.
                if ("OI_SHIFT_TRAP".equals(t.getStrategyType()) && t.getEntryTime() != null
                        && java.time.Duration.between(t.getEntryTime(), java.time.Instant.now()).getSeconds() < 300) {
                    return;
                }

                StrategyConfig cfg = resolveConfig(t);
                if (cfg.getStrategyType().isSpreadStrategy()) return; // spreads managed by SpreadPositionExitMonitor
                if (cfg.getStrategyType() == StrategyType.OI_MOMENTUM
                        && (t.getUserId() == null || t.getUserId().equals(com.algo.trade.multiuser.UserContext.DEFAULT_USER_ID))
                        && oiMomentumStrategy != null
                        && oiMomentumStrategy.activeManagedTradeIds().contains(t.getTradeId())) return; // managed by OI-momentum loop

                IndexType indexType = IndexType.fromName(t.getUnderlying());
                if (indexType == null) return;
                com.algo.trade.domain.OptionInstrument coeOpt = liveInstrumentCache.getBySymbol(
                        t.getInstrumentKey().contains(":") ? t.getInstrumentKey().split(":", 2)[1] : t.getInstrumentKey())
                        .orElse(null);
                if (coeOpt == null || coeOpt.getLastPrice() <= 0) return;
                if (!convictionOverrideEngine.isReversing(indexType, coeOpt.getStrikePrice(), coeOpt.getOptionType())) return;

                BigDecimal price = BigDecimal.valueOf(coeOpt.getLastPrice());
                log.warn("[COE] CONVICTION_EXIT_OVERRIDE (global,fast) — microstructure reversal on {} {}{} strategy={} tradeId={} — closing @{}",
                        indexType, coeOpt.getStrikePrice(), coeOpt.getOptionType(), t.getStrategyType(), t.getTradeId(), price);
                telegramAlertService.systemAlert(String.format(
                        "⚡ Conviction Exit Override: %s | %s | ₹%.2f (spoof/absorption reversal)",
                        t.getInstrumentKey(), t.getStrategyType(), price.doubleValue()));
                close(t, price, "CONVICTION_EXIT_OVERRIDE");
            } catch (Exception e) {
                log.debug("[COE] tryConvictionExit skipped for {}: {}", trade.getTradeId(), e.toString());
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
            if (t == null || t.getStatus() != TradeStatus.OPEN) {
                // Fix #7 (2026-06-02): If we discover the trade is gone or
                // already CLOSED, release any in-memory state we may have been
                // holding for it. close() normally clears these, but when a
                // close path bypasses ExitMonitor.close (watchdog, manual,
                // failsafe, shutdown) and close() itself threw earlier,
                // these maps would otherwise retain orphan entries forever.
                String tid = trade.getTradeId();
                peakPrices.remove(tid);
                trailingStops.remove(tid);
                firedLayers.remove(tid);
                evaluationLocks.remove(tid);
                if (exitEvaluationRegistry != null) {
                    exitEvaluationRegistry.remove(tid);
                }
                return;
            }
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

        // OI_MOMENTUM trades are managed by OIMomentumStrategy's 1-sec loop — but ONLY the PRIMARY's trade
        // AND ONLY when the loop is actually tracking it (adopted into an IndexState). Copied trades owned by
        // secondary users have no strategy-loop manager, so this monitor MUST manage them. ORPHAN SAFETY
        // (2026-07-01): a primary OI_MOMENTUM trade the strategy is NOT tracking (orphan / un-adopted) was
        // previously skipped here too → managed by NOBODY (no trailing/SL). Now: skip only if the strategy
        // is truly managing it; otherwise fall through and protect it as the safety net.
        if (cfg.getStrategyType() == StrategyType.OI_MOMENTUM
                && (trade.getUserId() == null
                    || trade.getUserId().equals(com.algo.trade.multiuser.UserContext.DEFAULT_USER_ID))) {
            boolean managedByStrategy = oiMomentumStrategy != null
                    && trade.getTradeId() != null
                    && oiMomentumStrategy.activeManagedTradeIds().contains(trade.getTradeId());
            if (managedByStrategy) {
                log.debug("[ExitMonitor] Skipping primary OI_MOMENTUM trade {} — managed by OIMomentumStrategy",
                        trade.getTradeId());
                return;
            }
            log.warn("[ExitMonitor] Managing UN-ADOPTED primary OI_MOMENTUM trade {} as safety net "
                    + "(strategy not tracking it — would otherwise have no SL/trailing)", trade.getTradeId());
            // fall through → apply SL/target/trailing here
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
        // SYNC- trades are manual orders imported by PositionSynchronizer. They have no
        // strategyType set (for LONG entries), so resolveConfig() falls back to
        // DIRECTIONAL_BUY's config and an algo's maxHoldMinutes would otherwise cut them
        // off arbitrarily. Manual trades should be governed only by SL/target/squareoff —
        // never by a strategy's hold-time window. Other exit layers (liquidity, SL,
        // target, trailing, squareoff) still apply.
        boolean isSyncTrade = trade.getTradeId() != null && trade.getTradeId().startsWith("SYNC-");
        // Max-hold from the user's RESOLVED PROFILE (per-user), not per-strategy config. Runs under the
        // trade owner's UserContext, so getMaxHoldMinutes() resolves the owner's profile. (2026-06-25)
        int profileMaxHold = globalConfigService.getMaxHoldMinutes();
        if (!isSyncTrade && profileMaxHold > 0 && trade.getEntryTime() != null) {
            long holdMinutes = java.time.Duration.between(trade.getEntryTime(), java.time.Instant.now()).toMinutes();
            if (holdMinutes >= profileMaxHold) {
                BigDecimal exitPrice = resolveExitPrice(trade);
                double holdProfitPct = PositionPnlCalculator.profitPercent(entryPrice, exitPrice,
                        PositionPnlCalculator.isShortEntry(trade));
                log.info("[ExitMonitor] MAX HOLD TIME reached: tradeId={} instrument={} hold={}min max={}min profit={}%",
                        trade.getTradeId(), trade.getInstrumentKey(), holdMinutes, profileMaxHold,
                        String.format("%.1f", holdProfitPct));
                telegramAlertService.systemAlert(String.format(
                        "⏱️ Max Hold Time: %s | Hold %dmin (max %d) | P&L %.1f%%",
                        trade.getInstrumentKey(), holdMinutes, config.getMaxHoldMinutes(), holdProfitPct));
                close(trade, exitPrice, "MAX_HOLD_TIME");
                return;
            }
        } else if (isSyncTrade && config.getMaxHoldMinutes() > 0 && trade.getEntryTime() != null) {
            long holdMinutes = java.time.Duration.between(trade.getEntryTime(), java.time.Instant.now()).toMinutes();
            if (holdMinutes >= config.getMaxHoldMinutes()) {
                log.debug("[ExitMonitor] Skipping MAX_HOLD_TIME for manual SYNC trade: tradeId={} instrument={} hold={}min configMax={}min",
                        trade.getTradeId(), trade.getInstrumentKey(), holdMinutes, config.getMaxHoldMinutes());
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

        // ── OI Shift Trap unwind exit (Phase 3 feature 11) — thesis-broken exit ──
        if (checkOiShiftTrapUnwindExit(trade, quote, currentPrice)) {
            return;
        }

        IndexType indexType = IndexType.fromName(trade.getUnderlying());

        // (The GLOBAL Conviction Override reversal exit runs on a dedicated 500ms sweep — see
        //  tryConvictionExit() / fastConvictionExitSweep() — so it fires within ~½s, not at candle cadence.)

        // ── Expiry operator trap: OI unwind collapse or squareoff deadline → exit NOW ──
        // ExpiryOperatorTrapDetector flags >30% OI drops at key strikes (operator trap)
        // and the 15:20 squareoff deadline on expiry days.
        // (2026-07-09: a 180s "entered-during-collapse" grace was added here mid-day and REVERTED the
        // same day — forward-path check on the 10:43 cluster showed the trap exits were SAVES, not
        // churn: all four sold CEs collapsed 31-67% within 30 min. On expiry day an index-wide OI
        // collapse is unwind-into-expiry, not a squeeze; the trap keeps FULL authority. The entry-side
        // conflict is fixed where it belongs: detectAvalanche suppresses NEW entries while the trap is
        // latched — see OIMomentumStrategy.)
        if (expiryTrapDetector != null && expiryTrapDetector.shouldForceExit(indexType)) {
            boolean collapse = expiryTrapDetector.assess(indexType).oiUnwindCollapse();
            String reason = collapse ? "EXPIRY_TRAP_OI_COLLAPSE" : "EXPIRY_TRAP_SQUAREOFF";
            log.warn("[ExitMonitor] EXPIRY TRAP FORCE EXIT ({}) — closing: tradeId={} instrument={}",
                    reason, trade.getTradeId(), trade.getInstrumentKey());
            telegramAlertService.systemAlert(String.format(
                    "🪤 Expiry Trap Force Exit (%s): %s | Current ₹%.2f",
                    collapse ? "OI unwind collapse" : "15:20 deadline",
                    trade.getInstrumentKey(), currentPrice.doubleValue()));
            close(trade, currentPrice, reason);
            return;
        }

        // Expiry danger zone: force-exit all positions after 3 PM on expiry day
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

        updateShiftTrapMaeMfe(trade, currentPrice);

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

        // Exit baseline comes from the user's RESOLVED RISK PROFILE — NOT per-strategy config and NOT
        // the values frozen on the trade at entry. (2026-06-25: per-strategy exit config retired; the
        // superuser-defined, per-user profile is the single source of exit SL/target/trail. This runs
        // inside UserContext.runAs(ownerId), so getStopLossPercent() etc. resolve the owner's profile.)
        // ATR HYBRID may still TIGHTEN these (a safety floor); the profile trail is preferred over ATR.
        // Cap the exit stop at 10% — the DynamicGateEngine MAX_SL intent. The global stopLossPercent
        // (12) is NOT a per-user-profile field, so it bypassed that cap and let OI_MOMENTUM exits fire
        // at 12% (overshooting to ~-15% on fast moves). Clamp so no single-leg exit stop exceeds 10%.
        // (Manual SYNC- trades are already skipped earlier in this method.)
        double configSlPct = Math.min(globalConfigService.getStopLossPercent().doubleValue(), MAX_EXIT_SL_PCT);
        double configTargetPct = globalConfigService.getTargetPercent().doubleValue();
        double configTrailAct = globalConfigService.getTrailingStopActivationPercent().doubleValue();
        double configTrailGap = globalConfigService.getTrailingGapPercent().doubleValue();

        boolean preferConfigTrail = true; // trail comes from the profile, not ATR

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

        // ── UNIFIED EXIT (2026-07-09, user directive) ─────────────────────────
        // ALL users follow the SAME OI-momentum exit. The primary account is only where the market
        // analysis runs — it is NOT special. So a secondary user's OI_MOMENTUM copy must NOT run this
        // monitor's SEPARATE discretionary rulebook (theta / target / trailing / ATR-trail / IV-collapse
        // / progressive-book / STALL_EXIT / gamma-spike / momentum-breakout / VWAP-reversal / OI-unwind)
        // — those diverge from the primary's OI-momentum strategy exit (the 24050 PE STALL_EXIT that
        // closed u:8 at −185 while u:1 rode on). The primary's strategy is the SINGLE exit brain and its
        // every exit fans out to all users via COPY_EXIT (doCloseTrade → fireExitAlignedNow). Above this
        // line the monitor already ran the HARD safety nets (liquidity, max-hold, squareoff, expiry
        // danger/afternoon, STOP_LOSS) — those stay as catastrophe protection for an orphaned copy or a
        // mirror that failed to fire. Everything discretionary below defers to the mirror.
        if (cfg.getStrategyType() == StrategyType.OI_MOMENTUM
                && trade.getUserId() != null
                && !trade.getUserId().equals(com.algo.trade.multiuser.UserContext.DEFAULT_USER_ID)) {
            log.debug("[ExitMonitor] UNIFIED-EXIT: secondary OI_MOMENTUM copy {} — discretionary exits "
                    + "deferred to the primary's strategy (mirrored via COPY_EXIT); safety nets only",
                    trade.getTradeId());
            return;
        }

        // ── Fresh-trade grace ─────────────────────────────────────────────────
        // The protective tiers above (liquidity emergency, squareoff, expiry-trap, STOP_LOSS) have all had
        // their chance. Defer the profit-taking / theta / trailing tiers below for the first
        // ENTRY_GRACE_SECONDS so a just-imported (PositionSynchronizer) or just-filled copied trade isn't
        // churned on transient data. Because this sits AFTER stop-loss, a real crash still stops out in the
        // first minute — the old blanket 60s skip blocked STOP_LOSS and let fresh entries run to ~-20%. (2026-07-02)
        if (trade.getEntryTime() != null
                && java.time.Duration.between(trade.getEntryTime(), java.time.Instant.now()).getSeconds() < ENTRY_GRACE_SECONDS) {
            return;
        }

        // ── 1b. Theta-cost exit (long-buy only) ───────────────────────────────
        // 4 Jun 2026 PM: if the trade has been bleeding theta for long enough
        // that cumulative theta-burn equals 50% of the entry premium AND we
        // are still in loss territory (profitPct <= 0), close. This protects
        // against the "held too long while underwater" pattern that amplified
        // today's losses (SENSEX 73900 PE held -36% before SL fired).
        // Theta is per day; we use elapsed minutes / 375 (one session) to
        // pro-rate the burn. Activates only after at least 5 minutes of hold
        // so we don't immediately stop out fresh entries.
        if (!shortEntry && profitPct <= 0) {
            com.algo.trade.domain.OptionInstrument liveOpt = liveInstrumentCache.getBySymbol(
                    trade.getInstrumentKey().contains(":")
                            ? trade.getInstrumentKey().split(":", 2)[1]
                            : trade.getInstrumentKey()).orElse(null);
            if (liveOpt != null && liveOpt.getTheta() != 0.0 && trade.getEntryTime() != null) {
                long holdMinutes = java.time.Duration.between(
                        trade.getEntryTime(), java.time.Instant.now()).toMinutes();
                if (holdMinutes >= 5) {
                    double thetaPerDay = Math.abs(liveOpt.getTheta());
                    double thetaBurnSoFar = thetaPerDay * (holdMinutes / 375.0);
                    double thetaBurnPct = (thetaBurnSoFar / entryPrice.doubleValue()) * 100.0;
                    if (thetaBurnPct >= 50.0) {
                        log.warn("[ExitMonitor] THETA_COST exit: tradeId={} entry={} current={} "
                                + "profit={}% holdMin={} thetaPerDay={} thetaBurn={}% (>=50%)",
                                trade.getTradeId(), entryPrice, currentPrice,
                                String.format("%.1f", profitPct), holdMinutes,
                                String.format("%.2f", thetaPerDay), String.format("%.1f", thetaBurnPct));
                        telegramAlertService.systemAlert(String.format(
                                "Theta exit: %s | P&L %.1f%% | held %d min | theta burn %.0f%%",
                                trade.getInstrumentKey(), profitPct, holdMinutes, thetaBurnPct));
                        close(trade, currentPrice, "THETA_COST");
                        return;
                    }
                }
            }
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
            // Finding 4: tighten the gap as the peak grows (flat gap when the flag is off).
            double effGap = tieredTrailGap(resolved.trailGapPercent(), peakPct);
            BigDecimal trailGap = BigDecimal.valueOf(effGap);
            if (tieredTrailingEnabled && effGap < resolved.trailGapPercent()) {
                log.debug("[ExitMonitor] tiered trail: tradeId={} peak={}% gap {}→{}",
                        trade.getTradeId(), String.format("%.1f", peakPct),
                        resolved.trailGapPercent(), effGap);
            }

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
            Optional<BigDecimal> currentIvOpt = quote.impliedVolatility();
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
        // Now honored from the user's RESOLVED PROFILE flag (partialProfitBookingEnabled). BALANCED is
        // seeded ON so this stays behavior-neutral (the monitor previously always booked when ATR was
        // available); superuser can disable it per profile. (2026-06-25)
        if (useAtrExits && profitPct > 0 && globalConfigService.isPartialProfitBookingEnabled()) {
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

    private void updateShiftTrapMaeMfe(TradeEntity trade, BigDecimal currentPrice) {
        if (shiftTrapMaeMfeTracker == null || oiShiftTrapConfig == null || !oiShiftTrapConfig.isExitMaeMfeEnabled()) {
            return;
        }
        if (!"OI_SHIFT_TRAP".equals(trade.getStrategyType())) {
            return;
        }
        if (shiftTrapMaeMfeTracker.get(trade.getTradeId()) == null) {
            var ctx = shiftTrapMaeMfeTracker.resolveContext(
                    trade.getUnderlying(), trade.getOptionType(), trade.getEntryTime());
            shiftTrapMaeMfeTracker.startTracking(
                    trade.getTradeId(), ctx, trade.getEntryPrice(), trade.getEntryTime());
        }
        IndexType indexType = IndexType.fromName(trade.getUnderlying());
        double spot = liveInstrumentCache.getFuturesPrice(indexType);
        long oi = trade.getEntryOpenInterest() != null ? trade.getEntryOpenInterest() : 0;
        shiftTrapMaeMfeTracker.update(trade.getTradeId(), currentPrice, spot, oi);
    }

    private void close(TradeEntity trade, BigDecimal price, String reason) {
        // Record ML exit shadow BEFORE closing (trade still has OPEN status and all data)
        boolean shortEntry = PositionPnlCalculator.isShortEntry(trade);
        double profitPct = PositionPnlCalculator.profitPercent(trade.getEntryPrice(), price, shortEntry);
        BigDecimal peak = peakPrices.getOrDefault(trade.getTradeId(), price);
        double peakPct = PositionPnlCalculator.profitPercent(trade.getEntryPrice(), peak, shortEntry);
        recordExitShadow(trade, trade.getEntryPrice(), price, profitPct, peakPct, 0, false, true, reason);

        com.algo.trade.strategy.oishifttrap.ShiftTrapMaeMfeTracker.State maeState = null;
        if ("OI_SHIFT_TRAP".equals(trade.getStrategyType()) && shiftTrapMaeMfeTracker != null) {
            maeState = shiftTrapMaeMfeTracker.remove(trade.getTradeId());
        }

        try {
            executionEngine.closeTrade(trade.getTradeId(), price, reason);
            if (maeState != null && oiShiftTrapExitRecorder != null
                    && oiShiftTrapConfig != null && oiShiftTrapConfig.isExitMaeMfeEnabled()) {
                oiShiftTrapExitRecorder.recordExit(trade, price, reason, maeState);
            }
            // Phase 5+ — emit ExitEvent into tuning capture for OI_SHIFT_TRAP trades.
            // Includes signal-path tag derived from entryReason and (when applicable) OI
            // unwind evidence so the tuning analyzer can correlate exit outcomes with
            // both the entry path and the threshold-triggering data.
            recordOiShiftTrapExitToTuning(trade, price, reason, maeState);
            // Only clear in-memory state after confirmed successful close
            trailingStops.remove(trade.getTradeId());
            peakPrices.remove(trade.getTradeId());
            firedLayers.remove(trade.getTradeId());
            if (exitEvaluationRegistry != null) {
                exitEvaluationRegistry.remove(trade.getTradeId());
            }
            // Per-user exit alert is handled by TelegramAlertService's fan-out:
            // executionEngine.closeTrade runs under the trade owner's context
            // (runAsTradeOwner) and emits tradeClosed there.
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
                    LocalTime.of(9, 15),
                    LocalTime.now(java.time.ZoneId.of("Asia/Kolkata"))).toMinutes();
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
            case "OI_UNWIND_EXIT" -> 14;
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
                StrategyType type =
                        StrategyType.valueOf(trade.getStrategyType());
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
        LocalTime forcedExit = globalConfigService.getForcedExitTime();
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

    /**
     * Phase 3 feature 11 — OI unwind exit. Closes an OI Shift Trap trade when the
     * trapped-side OI on the entry strike has dropped sharply within the configured window
     * (default 10% drop within 5 minutes of entry). Auto-no-op when enhancements are OFF,
     * the trade isn't OI_SHIFT_TRAP, the detector bean is missing, or the trade is a
     * manual SYNC- import.
     *
     * @return true if the trade was closed (caller must return immediately).
     */
    private boolean checkOiShiftTrapUnwindExit(TradeEntity trade, Quote quote, BigDecimal currentPrice) {
        if (oiShiftTrapConfig == null || !oiShiftTrapConfig.isEnhancementsEnabled()) {
            return false;
        }
        if (shiftTrapOiUnwindExitDetector == null) {
            return false;
        }
        if (!"OI_SHIFT_TRAP".equals(trade.getStrategyType())) {
            return false;
        }
        if (trade.getTradeId() != null && trade.getTradeId().startsWith("SYNC-")) {
            return false;
        }
        try {
            long currentOi = quote.openInterest();
            long fallbackOi = trade.getEntryOpenInterest() != null ? trade.getEntryOpenInterest() : 0L;
            double dropThreshold = oiShiftTrapConfig.getOiUnwindExitDropPercent();
            int windowMinutes = oiShiftTrapConfig.getOiUnwindExitWindowMinutes();
            Optional<com.algo.trade.strategy.oishifttrap.ShiftTrapOiUnwindExitDetector.UnwindEvidence> ev =
                    shiftTrapOiUnwindExitDetector.checkUnwind(trade.getTradeId(), currentOi,
                            dropThreshold, windowMinutes, fallbackOi, trade.getEntryTime());
            if (ev.isEmpty()) {
                return false;
            }
            var evidence = ev.get();
            log.warn("[ExitMonitor] OI UNWIND EXIT: tradeId={} entryOi={} currentOi={} drop={}% within {}m",
                    trade.getTradeId(), evidence.entryOi(), evidence.currentOi(),
                    String.format("%.1f", evidence.dropPercent()), evidence.minutesSinceEntry());
            telegramAlertService.systemAlert(String.format(
                    "📉 OI Unwind Exit: %s | OI %d → %d (-%.1f%%) within %dm",
                    trade.getInstrumentKey(), evidence.entryOi(), evidence.currentOi(),
                    evidence.dropPercent(), evidence.minutesSinceEntry()));
            close(trade, currentPrice, "OI_UNWIND_EXIT");
            shiftTrapOiUnwindExitDetector.onTradeClosed(trade.getTradeId());
            return true;
        } catch (Exception e) {
            log.debug("[ExitMonitor] OI unwind check failed for {}: {}", trade.getTradeId(), e.getMessage());
            return false;
        }
    }
    /**
     * Phase 5+ — build the exit-tuning attrs map and emit an {@code ExitEvent} for an
     * OI Shift Trap trade. Pulls the signal-path tag from {@code trade.entryReason}
     * (e.g. "[imbalance-only]") and, when reason == OI_UNWIND_EXIT, attaches the most
     * recent {@link com.algo.trade.strategy.oishifttrap.ShiftTrapOiUnwindExitDetector.UnwindEvidence}
     * fields (drop%, entry/current OI, minutes since entry). NO-OP when the tune recorder
     * bean is missing.
     */
    private void recordOiShiftTrapExitToTuning(TradeEntity trade, BigDecimal price, String reason,
                                                com.algo.trade.strategy.oishifttrap.ShiftTrapMaeMfeTracker.State maeState) {
        if (oiShiftTrapTuneRecorder == null) return;
        if (!"OI_SHIFT_TRAP".equals(trade.getStrategyType())) return;
        try {
            Map<String, Object> attrs = new java.util.LinkedHashMap<>();
            // Signal-path tag derived from entryReason (set at strategy fire time).
            String entryReason = trade.getEntryReason() != null ? trade.getEntryReason() : "";
            String signalPath = "standard";
            if (entryReason.contains("[imbalance-only]")) signalPath = "imbalance_only";
            else if (entryReason.contains("[distant-OI]")) signalPath = "distant_oi";
            else if (entryReason.contains("[pending-resolved]")) signalPath = "pending_resolved";
            attrs.put("signalPath", signalPath);

            // Score (from entryReason if parseable). Best-effort.
            if (trade.getEntryDelta() != null) attrs.put("entryDelta", trade.getEntryDelta());
            if (trade.getEntryIV() != null) attrs.put("entryIV", trade.getEntryIV());
            if (trade.getEntryOpenInterest() != null) attrs.put("entryOpenInterest", trade.getEntryOpenInterest());

            // OI unwind evidence — only present when the OI unwind detector caused the exit.
            // We pull the last evidence from the detector before the post-close cleanup
            // path removes it (checkOiShiftTrapUnwindExit calls onTradeClosed AFTER the
            // close() call returns, so the entry snapshot is still in the detector at the
            // time we're building these attrs).
            if ("OI_UNWIND_EXIT".equals(reason)) {
                attrs.put("exitTrigger", "oi_unwind");
            }

            // MAE/MFE summary (already in ExitEvent's structured fields; surface a couple of
            // human-readable fields for at-a-glance tuning).
            if (maeState != null) {
                attrs.put("maePctSnapshot", maeState.maePct());
                attrs.put("mfePctSnapshot", maeState.mfePct());
            }

            // Build a synthetic MaeMfeTracker.Snapshot from the per-trade MaeState if needed.
            com.algo.trade.tuning.infra.MaeMfeTracker.Snapshot tuneSnapshot = null;
            // (Strategy currently uses its own ShiftTrapMaeMfeTracker.State; we leave the
            // generic snapshot null and rely on the structured attrs above. The ExitEvent
            // will still report realizedPnlPct, holdSec, instrumentKey, etc.)

            oiShiftTrapTuneRecorder.recordExit(trade, price, reason, tuneSnapshot, attrs);
        } catch (Exception ex) {
            log.debug("[ExitMonitor] OI Shift Trap exit-tuning record failed: {}", ex.getMessage());
        }
    }
}
