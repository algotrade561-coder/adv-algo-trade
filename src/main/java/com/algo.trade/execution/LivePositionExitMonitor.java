package com.algo.trade.execution;

import com.algo.trade.config.GlobalConfigService;
import com.algo.trade.config.PositionSyncProperties;
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

    // tradeId → highest price seen since entry
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
                                    com.algo.trade.monitoring.SchedulerRegistry schedulerRegistry) {
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
        schedulerRegistry.recordRun("exitBackup");
    }

    private void evaluate(TradeEntity trade) {
        Object lock = evaluationLocks.computeIfAbsent(trade.getTradeId(), id -> new Object());
        synchronized (lock) {
            // Re-fetch to get the latest @Version and status — the batch-loaded entity can be stale
            // when multiple CandleClosedEvent threads evaluate the same trade concurrently,
            // causing OptimisticLockException on subsequent saves.
            TradeEntity t = tradeRepository.findById(trade.getTradeId()).orElse(null);
            if (t == null || t.getStatus() != TradeStatus.OPEN) return;
            evaluateInternal(t);
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

        BigDecimal entryPrice = trade.getEntryPrice();
        StrategyConfig config = cfg;

        // ── Global exit override: when enabled, global config values replace per-strategy exit params ──
        if (globalConfigService.isGlobalExitOverride()) {
            config = applyGlobalExitOverride(config);
        }

        // ── Time-based exits run FIRST — they don't need a live quote ─────────

        // Per-strategy squareoff time check (non-expiry days)
        LocalTime now = LocalTime.now(java.time.ZoneId.of("Asia/Kolkata"));
        LocalTime squareoffTime = LocalTime.of(config.getSquareoffHour(), config.getSquareoffMinute());

        // Max hold time: close if trade has been open longer than configured maxHoldMinutes
        // Checked BEFORE quote lookup — time-based exits must not be blocked by missing quotes
        if (config.getMaxHoldMinutes() > 0 && trade.getEntryTime() != null) {
            long holdMinutes = java.time.Duration.between(trade.getEntryTime(), java.time.Instant.now()).toMinutes();
            if (holdMinutes >= config.getMaxHoldMinutes()) {
                BigDecimal exitPrice = resolveExitPrice(trade);
                double holdProfitPct = profitPercent(entryPrice, exitPrice);
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

        // ── Price-based exits require a live quote ────────────────────────────

        Optional<Quote> quoteOpt = marketDataService.quote(trade.getInstrumentKey());
        if (quoteOpt.isEmpty()) {
            log.debug("[ExitMonitor] No quote for {}", trade.getInstrumentKey());
            return;
        }
        BigDecimal currentPrice = quoteOpt.get().lastPrice();
        if (currentPrice == null || currentPrice.signum() <= 0) return;

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

        // Days-to-expiry SL scaling: tighter SL as expiry approaches
        long daysToExpiry = expiryCalendar.daysToExpiry(indexType);
        double slMultiplier = switch ((int) Math.min(daysToExpiry, 3)) {
            case 0 -> 0.5;   // expiry day: very tight
            case 1 -> 0.7;   // day before expiry
            case 2 -> 0.85;  // 2 days before
            default -> 1.0;  // normal
        };

        // Track peak price (load from DB if available for restart recovery)
        BigDecimal savedPeak = trade.getPeakPrice();
        if (savedPeak != null && savedPeak.compareTo(currentPrice) > 0) {
            peakPrices.putIfAbsent(trade.getTradeId(), savedPeak);
        }
        BigDecimal peak = peakPrices.merge(trade.getTradeId(), currentPrice,
                (existing, incoming) -> incoming.compareTo(existing) > 0 ? incoming : existing);
        // Persist peak price for restart recovery
        if (peak.compareTo(trade.getPeakPrice() != null ? trade.getPeakPrice() : BigDecimal.ZERO) > 0) {
            trade.setPeakPrice(peak);
            tradeRepository.save(trade);
        }

        double profitPct = profitPercent(entryPrice, currentPrice);
        double peakPct   = profitPercent(entryPrice, peak);

        // ── Compute ATR-based dynamic exits when candle data is available ─────
        // Resolve instrument token for candle lookup
        long instrumentToken = resolveInstrumentToken(trade);
        List<com.algo.trade.domain.Candle> candles15m = liveCandleBuilder.getHistory(instrumentToken, com.algo.trade.domain.Timeframe.FIFTEEN_MINUTE);
        double atr = candles15m.size() >= 15 ? dynamicExitManager.calculateATR(candles15m, 14) : 0;
        boolean useAtrExits = atr > 0 && entryPrice.doubleValue() > 0;
        List<com.algo.trade.domain.Candle> candles1m = liveCandleBuilder.getHistory(instrumentToken, com.algo.trade.domain.Timeframe.ONE_MINUTE);

        // Dynamic SL: ATR-based when available, capped by config SL (never wider than configured)
        double configSlPct = config.getStopLossPercent().doubleValue() * slMultiplier;
        double slPct;
        if (useAtrExits) {
            double atrSl = dynamicExitManager.calculateDynamicSL(entryPrice.doubleValue(), atr, (int) daysToExpiry);
            slPct = Math.min(atrSl, configSlPct);
            log.debug("[ExitMonitor] ATR-based SL: {}% (ATR={}, entry={}, configCap={}%)", String.format("%.1f", slPct), String.format("%.1f", atr), entryPrice, String.format("%.1f", configSlPct));
        } else {
            slPct = configSlPct;
        }

        // Dynamic target: ATR-based when available
        double targetPct;
        if (useAtrExits) {
            targetPct = dynamicExitManager.calculateDynamicTarget(entryPrice.doubleValue(), atr, (int) daysToExpiry);
        } else {
            targetPct = config.getTargetPercent().doubleValue();
        }

        log.debug("[ExitMonitor] tradeId={} instrument={} entry={} current={} profit={}% peak={}% sl={}% target={}% atr={}",
                trade.getTradeId(), trade.getInstrumentKey(), entryPrice, currentPrice,
                String.format("%.1f", profitPct), String.format("%.1f", peakPct),
                String.format("%.1f", slPct), String.format("%.1f", targetPct),
                useAtrExits ? String.format("%.1f", atr) : "N/A");

        // ── 1. Stop Loss ──────────────────────────────────────────────────────
        if (profitPct <= -slPct) {
            log.warn("[ExitMonitor] STOP LOSS hit: tradeId={} instrument={} entry={} current={} profit={}% sl={}%{}",
                    trade.getTradeId(), trade.getInstrumentKey(), entryPrice, currentPrice,
                    String.format("%.1f", profitPct), String.format("%.1f", slPct),
                    slMultiplier < 1.0 ? " (expiry-day tightened)" : "");
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

        // ── 3. Trailing Stop ──────────────────────────────────────────────────
        // When global exit override is active, use global trailing params regardless of entry-locked values.
        // Otherwise, use trailing params locked at entry time (appliedTrailing*) so mid-trade config changes
        // don't alter the trailing behavior. Fall back to current config if not set (legacy trades).
        BigDecimal trailActivation;
        BigDecimal trailGap;
        if (globalConfigService.isGlobalExitOverride()) {
            // Global override takes priority over entry-locked values
            trailActivation = config.getTrailingStopActivationPercent();
            trailGap = config.getTrailingGapPercent();
        } else {
            trailActivation = trade.getAppliedTrailingStopActivationPercent() != null
                    ? trade.getAppliedTrailingStopActivationPercent()
                    : config.getTrailingStopActivationPercent();
            trailGap = trade.getAppliedTrailingGapPercent() != null
                    ? trade.getAppliedTrailingGapPercent()
                    : config.getTrailingGapPercent();
        }

        // Recover trailing stop from DB if not in memory (restart recovery)
        if (!trailingStops.containsKey(trade.getTradeId()) && trade.getTrailingStopPrice() != null) {
            trailingStops.put(trade.getTradeId(), trade.getTrailingStopPrice());
        }

        Optional<BigDecimal> currentStop = Optional.ofNullable(trailingStops.get(trade.getTradeId()));
        Optional<BigDecimal> updatedStop = trailingStopService.nextStop(entryPrice, peak, currentStop,
                trailActivation, trailGap);

        if (updatedStop.isPresent()) {
            trailingStops.put(trade.getTradeId(), updatedStop.get());
            // Persist trailing stop price to DB for restart recovery
            if (!updatedStop.get().equals(trade.getTrailingStopPrice())) {
                trade.setTrailingStopPrice(updatedStop.get());
                tradeRepository.save(trade);
            }
            if (trailingStopService.isStopHit(currentPrice, updatedStop.get())) {
                log.info("[ExitMonitor] TRAILING STOP hit: tradeId={} instrument={} current={} stop={} profit={}%",
                        trade.getTradeId(), trade.getInstrumentKey(), currentPrice,
                        updatedStop.get(), String.format("%.1f", profitPct));
                telegramAlertService.systemAlert(String.format(
                        "\uD83D\uDFE1 Trailing Stop Hit: %s | Entry \u20B9%.2f \u2192 \u20B9%.2f | Stop \u20B9%.2f | P&L %.1f%%",
                        trade.getInstrumentKey(), entryPrice.doubleValue(), currentPrice.doubleValue(),
                        updatedStop.get().doubleValue(), profitPct));
                close(trade, currentPrice, "TRAILING_STOP");
            }
        }

        // ── 4. ATR-based trailing stop (when candle data available) ───────────
        if (useAtrExits && peakPct > 10) {
            double atrTrailLevel = dynamicExitManager.calculateTrailingSL(
                    profitPct, peakPct, atr, entryPrice.doubleValue());
            if (atrTrailLevel > -900 && profitPct < atrTrailLevel) {
                log.info("[ExitMonitor] ATR TRAILING STOP hit: tradeId={} profit={}% < trail={}%",
                        trade.getTradeId(), String.format("%.1f", profitPct), String.format("%.1f", atrTrailLevel));
                telegramAlertService.systemAlert(String.format(
                        "📉 ATR Trail Stop: %s | Profit %.1f%% dropped below trail %.1f%%",
                        trade.getInstrumentKey(), profitPct, atrTrailLevel));
                close(trade, currentPrice, "ATR_TRAILING_STOP");
                return;
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
            if (dynamicExitManager.isStalled(candles1m, trade.getEntryTime(), atr, 10)) {
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
                && dynamicExitManager.isGammaSpike(candles15m, atr)) {
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

        // ── 10. Bid-ask spread widening exit — liquidity evaporating ──────────
        BigDecimal bid = quoteOpt.get().bid().orElse(BigDecimal.ZERO);
        BigDecimal ask = quoteOpt.get().ask().orElse(BigDecimal.ZERO);
        if (bid.signum() > 0 && ask.signum() > 0 && currentPrice.signum() > 0) {
            double spreadPct = ask.subtract(bid).doubleValue() / currentPrice.doubleValue() * 100;
            if (spreadPct > 5.0) {
                // Spread > 5% of premium — liquidity is evaporating (common near expiry)
                log.warn("[ExitMonitor] SPREAD WIDENING: tradeId={} spread={}% bid={} ask={} — liquidity deteriorating",
                        trade.getTradeId(), String.format("%.1f", spreadPct), bid, ask);
                if (spreadPct > 10.0) {
                    // Spread > 10% — exit immediately to avoid being trapped
                    telegramAlertService.systemAlert(String.format(
                            "⚠️ Spread Widening Exit: %s | Spread %.1f%% (bid ₹%.2f / ask ₹%.2f) — liquidity gone",
                            trade.getInstrumentKey(), spreadPct, bid.doubleValue(), ask.doubleValue()));
                    close(trade, currentPrice, "SPREAD_WIDENING_EXIT");
                }
            }
        }
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
            }
        } catch (Exception e) {
            log.debug("[ExitMonitor] VWAP reversal check error for {}: {}", trade.getTradeId(), e.getMessage());
        }
    }

    private void close(TradeEntity trade, BigDecimal price, String reason) {
        try {
            executionEngine.closeTrade(trade.getTradeId(), price, reason);
            // Only clear in-memory state after confirmed successful close
            trailingStops.remove(trade.getTradeId());
            peakPrices.remove(trade.getTradeId());
            firedLayers.remove(trade.getTradeId());
        } catch (Exception e) {
            log.warn("[ExitMonitor] Failed to close trade {} — retaining trailing stop state for next evaluation: {}",
                    trade.getTradeId(), e.getMessage());
        }
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

    private double profitPercent(BigDecimal entry, BigDecimal current) {
        if (entry == null || entry.signum() <= 0) return 0;
        return current.subtract(entry)
                .multiply(BigDecimal.valueOf(100), MC)
                .divide(entry, MC)
                .doubleValue();
    }

    /**
     * Resolve instrument token for candle history lookup.
     * Uses the underlying's spot token (NIFTY=256265, BANKNIFTY=260105).
     */
    private long resolveInstrumentToken(TradeEntity trade) {
        String underlying = trade.getUnderlying();
        return com.algo.trade.domain.IndexType.fromName(underlying).spotToken();
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
