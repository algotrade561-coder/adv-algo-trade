package com.algo.trade.execution;

import com.algo.trade.config.PositionSyncProperties;
import com.algo.trade.domain.CandleClosedEvent;
import com.algo.trade.domain.IndexType;
import com.algo.trade.domain.Quote;
import com.algo.trade.domain.TradeStatus;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    // tradeId → highest price seen since entry
    private final Map<String, BigDecimal> peakPrices = new ConcurrentHashMap<>();
    // tradeId → current trailing stop price
    private final Map<String, BigDecimal> trailingStops = new ConcurrentHashMap<>();
    // tradeId → whether partial profit has been taken
    private final Map<String, Boolean> partialExited = new ConcurrentHashMap<>();

    public LivePositionExitMonitor(TradeRepository tradeRepository,
                                    ExecutionEngine executionEngine,
                                    MarketDataService marketDataService,
                                    StrategyConfigService strategyConfigService,
                                    TrailingStopService trailingStopService,
                                    TelegramAlertService telegramAlertService,
                                    ExpiryCalendar expiryCalendar,
                                    com.algo.trade.strategy.DynamicExitManager dynamicExitManager,
                                    com.algo.trade.marketdata.LiveCandleBuilder liveCandleBuilder,
                                    PositionSyncProperties positionSyncProperties) {
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
    }

    @EventListener
    public void onCandleClose(CandleClosedEvent event) {
        List<TradeEntity> openTrades = tradeRepository.findByStatus(TradeStatus.OPEN);
        if (openTrades.isEmpty()) return;
        for (TradeEntity trade : openTrades) {
            if (!positionSyncProperties.manageSyncedTrades() && trade.getTradeId().startsWith("SYNC-")) continue;
            try {
                evaluate(trade);
            } catch (Exception e) {
                log.error("[ExitMonitor] Error evaluating trade {}: {}", trade.getTradeId(), e.getMessage());
            }
        }
    }

    private void evaluate(TradeEntity trade) {
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

        BigDecimal entryPrice = trade.getEntryPrice();
        StrategyConfig config = resolveConfig(trade);

        // Populate entry Greeks if not yet set (first evaluation after entry)
        populateEntryGreeksIfMissing(trade);

        // Per-strategy squareoff time check (non-expiry days)
        LocalTime now = LocalTime.now(java.time.ZoneId.of("Asia/Kolkata"));
        LocalTime squareoffTime = LocalTime.of(config.getSquareoffHour(), config.getSquareoffMinute());
        if (now.isAfter(squareoffTime) || now.equals(squareoffTime)) {
            log.info("[ExitMonitor] SQUAREOFF TIME reached: tradeId={} instrument={} squareoff={}",
                    trade.getTradeId(), trade.getInstrumentKey(), squareoffTime);
            telegramAlertService.systemAlert(String.format(
                    "⏰ Squareoff Time: %s | Current ₹%.2f | Time %s",
                    trade.getInstrumentKey(), currentPrice.doubleValue(), squareoffTime));
            close(trade, currentPrice, "SQUAREOFF_TIME");
            return;
        }

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

        // Dynamic SL: ATR-based when available, fixed % as fallback
        double slPct;
        if (useAtrExits) {
            slPct = dynamicExitManager.calculateDynamicSL(entryPrice.doubleValue(), atr, (int) daysToExpiry);
            log.debug("[ExitMonitor] ATR-based SL: {}% (ATR={}, entry={})", String.format("%.1f", slPct), String.format("%.1f", atr), entryPrice);
        } else {
            slPct = config.getStopLossPercent().doubleValue() * slMultiplier;
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
        Optional<BigDecimal> currentStop = Optional.ofNullable(trailingStops.get(trade.getTradeId()));
        Optional<BigDecimal> updatedStop = trailingStopService.nextStop(entryPrice, peak, currentStop,
                config.getTrailingStopActivationPercent(), config.getTrailingGapPercent());

        if (updatedStop.isPresent()) {
            trailingStops.put(trade.getTradeId(), updatedStop.get());
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

        // ── 5. Partial profit at 30% (exit half, trail rest) ──────────────────
        if (useAtrExits) {
            boolean alreadyPartial = partialExited.getOrDefault(trade.getTradeId(), false);
            double fraction = dynamicExitManager.partialProfitFraction(profitPct, alreadyPartial);
            if (fraction > 0) {
                partialExited.put(trade.getTradeId(), true);
                log.info("[ExitMonitor] PARTIAL PROFIT: tradeId={} profit={}% — booking {}% of position (logged only, partial close not yet implemented)",
                        trade.getTradeId(), String.format("%.1f", profitPct), String.format("%.0f", fraction * 100));
                // Note: actual partial close requires splitting the trade — not yet implemented.
                // No Telegram alert to avoid confusion until ExecutionEngine.closePartial() is built.
            }
        }

        // ── 6. Momentum breakout exit (for short premium strategies) ──────────
        if (useAtrExits && candles15m.size() >= 15) {
            StrategyConfig cfg = resolveConfig(trade);
            if (cfg.getStrategyType().isSellingStrategy() && dynamicExitManager.isBreakout(candles15m, 14)) {
                log.warn("[ExitMonitor] BREAKOUT EXIT: tradeId={} — market trending, exiting short premium",
                        trade.getTradeId());
                telegramAlertService.systemAlert(String.format(
                        "⚡ Breakout Exit: %s | Market moved > 2.5x ATR — exiting short premium position",
                        trade.getInstrumentKey()));
                close(trade, currentPrice, "MOMENTUM_BREAKOUT_EXIT");
            }
        }
    }

    private void close(TradeEntity trade, BigDecimal price, String reason) {
        try {
            executionEngine.closeTrade(trade.getTradeId(), price, reason);
            // Only clear in-memory state after confirmed successful close
            trailingStops.remove(trade.getTradeId());
            peakPrices.remove(trade.getTradeId());
            partialExited.remove(trade.getTradeId());
        } catch (Exception e) {
            log.warn("[ExitMonitor] Failed to close trade {} — retaining trailing stop state for next evaluation: {}",
                    trade.getTradeId(), e.getMessage());
        }
    }

    /**
     * Resolve strategy config for a trade.
     * Uses the persisted strategyType field first, falls back to entryReason text parsing.
     */
    private StrategyConfig resolveConfig(TradeEntity trade) {
        // Prefer the explicit strategyType field (set at entry time)
        if (trade.getStrategyType() != null && !trade.getStrategyType().isBlank()) {
            try {
                com.algo.trade.strategy.StrategyType type =
                        com.algo.trade.strategy.StrategyType.valueOf(trade.getStrategyType());
                return strategyConfigService.getAll().stream()
                        .filter(c -> c.getStrategyType() == type)
                        .findFirst()
                        .orElseGet(() -> strategyConfigService.getDirectionalBuyConfig());
            } catch (IllegalArgumentException ignored) { /* fall through to text parsing */ }
        }
        // Fallback: parse from entryReason text
        String entryReason = trade.getEntryReason() != null ? trade.getEntryReason().toUpperCase() : "";
        return strategyConfigService.getAll().stream()
                .filter(c -> entryReason.contains(c.getStrategyType().name()))
                .findFirst()
                .orElseGet(() -> strategyConfigService.getDirectionalBuyConfig());
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
