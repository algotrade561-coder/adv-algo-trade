package com.algo.trade.risk;

import com.algo.trade.config.TradingProperties;
import com.algo.trade.notification.TelegramAlertService;
import com.algo.trade.persistence.TradeEntity;
import com.algo.trade.persistence.TradeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Central risk management module.
 *
 * Responsibilities:
 * - Track daily P&L and enforce max loss / max profit limits
 * - Validate position sizing before order placement
 * - Trigger emergency shutdown when limits are breached
 * - Expose trading-allowed flag to execution layer
 *
 * Ported from AlgoTradingOptions and adapted to use existing
 * TradingProperties and TradeRepository from this project.
 */
@Component
public class RiskManager {

    private static final Logger log = LoggerFactory.getLogger(RiskManager.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final TradingProperties properties;
    private final TradeRepository tradeRepository;
    private final TelegramAlertService alertService;
    private final com.algo.trade.persistence.DailySummaryRepository dailySummaryRepository;
    private final com.algo.trade.persistence.StrategyDecisionRepository decisionRepository;

    private final AtomicBoolean tradingAllowed = new AtomicBoolean(true);
    private final AtomicInteger openPositionCount = new AtomicInteger(0);
    private volatile HaltMode haltMode = HaltMode.NONE;

    public RiskManager(TradingProperties properties, TradeRepository tradeRepository,
                       TelegramAlertService alertService,
                       com.algo.trade.persistence.DailySummaryRepository dailySummaryRepository,
                       com.algo.trade.persistence.StrategyDecisionRepository decisionRepository) {
        this.properties = properties;
        this.tradeRepository = tradeRepository;
        this.alertService = alertService;
        this.dailySummaryRepository = dailySummaryRepository;
        this.decisionRepository = decisionRepository;
    }

    // ── Order validation ──────────────────────────────────────────────────────

    public RiskCheckResult validateEntry(String symbol, int quantity) {
        if (haltMode == HaltMode.HARD)
            return RiskCheckResult.denied("HARD HALT active — all trading stopped");
        if (!tradingAllowed.get())
            return RiskCheckResult.denied("Trading halted due to risk breach");
        if (openPositionCount.get() >= properties.risk().maxTradesPerDay())
            return RiskCheckResult.denied("Max open positions reached: " + properties.risk().maxTradesPerDay());

        BigDecimal dailyPnl = getDailyPnl();
        BigDecimal maxLoss = properties.risk().totalCapital()
                .multiply(properties.risk().maxDailyLossPercent())
                .divide(BigDecimal.valueOf(100));

        if (dailyPnl.compareTo(maxLoss.negate()) <= 0) {
            haltTrading("Daily max loss breached: ₹" + dailyPnl);
            return RiskCheckResult.denied("Daily max loss limit reached");
        }

        return RiskCheckResult.approved();
    }

    // ── Position tracking ─────────────────────────────────────────────────────

    public void incrementOpenPositions() {
        openPositionCount.incrementAndGet();
    }

    public void decrementOpenPositions() {
        int v = openPositionCount.decrementAndGet();
        if (v < 0) openPositionCount.set(0);
    }

    public int getOpenPositionsCount() { return openPositionCount.get(); }

    // ── P&L ───────────────────────────────────────────────────────────────────

    public BigDecimal getDailyPnl() {
        Instant start = LocalDate.now(IST).atStartOfDay(IST).toInstant();
        Instant end   = LocalDate.now(IST).plusDays(1).atStartOfDay(IST).toInstant();
        return tradeRepository.findByEntryTimeBetween(start, end).stream()
                .map(TradeEntity::getRealizedPnl)
                .filter(p -> p != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // ── Halt / resume ─────────────────────────────────────────────────────────

    /** Hard halt — no orders at all. */
    public void haltTrading(String reason) {
        tradingAllowed.set(false);
        haltMode = HaltMode.HARD;
        log.error("TRADING HALTED: {}", reason);
        alertService.systemAlert("🚨 TRADING HALTED: " + reason);
    }

    /** Soft halt — no new entries, existing positions still managed. */
    public void softHalt(String reason) {
        haltMode = HaltMode.SOFT;
        log.warn("SOFT HALT: {} — no new entries, managing existing positions", reason);
        alertService.systemAlert("⚠️ SOFT HALT: " + reason);
    }

    public void resumeTrading() {
        tradingAllowed.set(true);
        haltMode = HaltMode.NONE;
        log.info("Trading resumed");
        alertService.systemAlert("✅ Trading resumed");
    }

    public boolean isTradingAllowed() { return tradingAllowed.get(); }
    public boolean isEntryAllowed() { return tradingAllowed.get() && haltMode == HaltMode.NONE; }
    public boolean isExitAllowed() { return haltMode != HaltMode.HARD; }
    public HaltMode getHaltMode() { return haltMode; }
    public boolean isTradingHalted() { return !tradingAllowed.get(); }

    // ── Scheduled P&L monitor ─────────────────────────────────────────────────

    @Scheduled(fixedDelay = 30_000)
    public void monitorPnl() {
        if (!tradingAllowed.get()) return;
        BigDecimal pnl = getDailyPnl();
        BigDecimal maxLoss = properties.risk().totalCapital()
                .multiply(properties.risk().maxDailyLossPercent())
                .divide(BigDecimal.valueOf(100));
        BigDecimal warningThreshold = maxLoss.multiply(BigDecimal.valueOf(0.8));
        if (pnl.compareTo(warningThreshold.negate()) <= 0) {
            log.warn("[RiskManager] Daily P&L warning: ₹{} (limit: ₹{})", pnl, maxLoss);
            alertService.systemAlert("⚠️ Risk Warning: Daily P&L at ₹" + pnl + " (limit: ₹" + maxLoss + ")");
        }
    }

    /** Write daily summary to H2 every 5 minutes for long-term analysis. */
    @Scheduled(fixedDelay = 300_000)
    public void writeDailySummary() {
        try {
            LocalDate today = LocalDate.now(IST);
            Instant dayStart = today.atStartOfDay(IST).toInstant();
            Instant dayEnd = today.plusDays(1).atStartOfDay(IST).toInstant();

            var trades = tradeRepository.findByEntryTimeBetween(dayStart, dayEnd);
            int tradeCount = trades.size();
            int wins = (int) trades.stream().filter(t -> t.getRealizedPnl() != null && t.getRealizedPnl().signum() > 0).count();
            int losses = (int) trades.stream().filter(t -> t.getRealizedPnl() != null && t.getRealizedPnl().signum() < 0).count();
            BigDecimal pnl = getDailyPnl();

            long signals = decisionRepository.findTop200BySignalTypeInOrderByTimestampDesc(
                    java.util.List.of("BUY_CE", "BUY_PE")).stream()
                    .filter(s -> s.getTimestamp() != null && !s.getTimestamp().isBefore(dayStart) && s.getTimestamp().isBefore(dayEnd))
                    .count();
            long rejected = decisionRepository.findTop200BySignalTypeOrderByTimestampDesc("NO_TRADE").stream()
                    .filter(s -> s.getTimestamp() != null && !s.getTimestamp().isBefore(dayStart) && s.getTimestamp().isBefore(dayEnd))
                    .count();

            var summary = dailySummaryRepository.findById(today)
                    .orElse(new com.algo.trade.persistence.DailySummaryEntity(today, 0, BigDecimal.ZERO, BigDecimal.ZERO));
            summary.setTrades(tradeCount);
            summary.setWins(wins);
            summary.setLosses(losses);
            summary.setRealizedPnl(pnl);
            summary.setSignalsGenerated((int) signals);
            summary.setSignalsRejected((int) rejected);
            dailySummaryRepository.save(summary);
        } catch (Exception e) {
            log.debug("Daily summary write failed: {}", e.getMessage());
        }
    }

    // ── Result DTO ────────────────────────────────────────────────────────────

    public record RiskCheckResult(boolean isApproved, String reason) {
        public static RiskCheckResult approved() { return new RiskCheckResult(true, null); }
        public static RiskCheckResult denied(String reason) { return new RiskCheckResult(false, reason); }
    }
}
