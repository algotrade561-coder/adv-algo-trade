package com.algo.trade.execution;

import com.algo.trade.domain.CandleClosedEvent;
import com.algo.trade.domain.Quote;
import com.algo.trade.domain.TradeStatus;
import com.algo.trade.marketdata.MarketDataService;
import com.algo.trade.notification.TelegramAlertService;
import com.algo.trade.persistence.TradeEntity;
import com.algo.trade.persistence.TradeRepository;
import com.algo.trade.strategy.StrategyConfig;
import com.algo.trade.strategy.StrategyConfigService;
import java.math.BigDecimal;
import java.math.MathContext;
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

    // tradeId → highest price seen since entry
    private final Map<String, BigDecimal> peakPrices = new ConcurrentHashMap<>();
    // tradeId → current trailing stop price
    private final Map<String, BigDecimal> trailingStops = new ConcurrentHashMap<>();

    public LivePositionExitMonitor(TradeRepository tradeRepository,
                                    ExecutionEngine executionEngine,
                                    MarketDataService marketDataService,
                                    StrategyConfigService strategyConfigService,
                                    TrailingStopService trailingStopService,
                                    TelegramAlertService telegramAlertService) {
        this.tradeRepository = tradeRepository;
        this.executionEngine = executionEngine;
        this.marketDataService = marketDataService;
        this.strategyConfigService = strategyConfigService;
        this.trailingStopService = trailingStopService;
        this.telegramAlertService = telegramAlertService;
    }

    @EventListener
    public void onCandleClose(CandleClosedEvent event) {
        List<TradeEntity> openTrades = tradeRepository.findByStatus(TradeStatus.OPEN);
        if (openTrades.isEmpty()) return;
        for (TradeEntity trade : openTrades) {
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

        BigDecimal entryPrice = trade.getEntryPrice();
        StrategyConfig config = resolveConfig(trade);

        // Track peak price
        BigDecimal peak = peakPrices.merge(trade.getTradeId(), currentPrice,
                (existing, incoming) -> incoming.compareTo(existing) > 0 ? incoming : existing);

        double profitPct = profitPercent(entryPrice, currentPrice);
        double peakPct   = profitPercent(entryPrice, peak);

        log.debug("[ExitMonitor] tradeId={} instrument={} entry={} current={} profit={}% peak={}% sl={}% target={}%",
                trade.getTradeId(), trade.getInstrumentKey(), entryPrice, currentPrice,
                String.format("%.1f", profitPct), String.format("%.1f", peakPct),
                config.getStopLossPercent(), config.getTargetPercent());

        // ── 1. Stop Loss ──────────────────────────────────────────────────────
        double slPct = config.getStopLossPercent().doubleValue();
        if (profitPct <= -slPct) {
            log.warn("[ExitMonitor] STOP LOSS hit: tradeId={} instrument={} entry={} current={} profit={}% sl={}%",
                    trade.getTradeId(), trade.getInstrumentKey(), entryPrice, currentPrice,
                    String.format("%.1f", profitPct), slPct);
            telegramAlertService.systemAlert(String.format(
                    "\uD83D\uDD34 SL Hit: %s | Entry \u20B9%.2f \u2192 \u20B9%.2f | P&L %.1f%%",
                    trade.getInstrumentKey(), entryPrice.doubleValue(), currentPrice.doubleValue(), profitPct));
            close(trade, currentPrice, "STOP_LOSS");
            return;
        }

        // ── 2. Target ─────────────────────────────────────────────────────────
        double targetPct = config.getTargetPercent().doubleValue();
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
        Optional<BigDecimal> updatedStop = trailingStopService.nextStop(entryPrice, peak, currentStop);

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
    }

    private void close(TradeEntity trade, BigDecimal price, String reason) {
        try {
            executionEngine.closeTrade(trade.getTradeId(), price, reason);
            trailingStops.remove(trade.getTradeId());
            peakPrices.remove(trade.getTradeId());
        } catch (Exception e) {
            log.error("[ExitMonitor] Failed to close trade {}: {}", trade.getTradeId(), e.getMessage());
        }
    }

    /**
     * Resolve strategy config for a trade.
     * Matches by strategyType name found in the entryReason string.
     * Falls back to DIRECTIONAL_BUY config if no match.
     */
    private StrategyConfig resolveConfig(TradeEntity trade) {
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
}
