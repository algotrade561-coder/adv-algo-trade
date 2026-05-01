package com.algo.trade.risk;

import com.algo.trade.config.GlobalConfigService;
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

    private final GlobalConfigService globalConfigService;
    private final TradingProperties properties;
    private final TradeRepository tradeRepository;
    private final TelegramAlertService alertService;
    private final com.algo.trade.persistence.DailySummaryRepository dailySummaryRepository;
    private final com.algo.trade.persistence.StrategyDecisionRepository decisionRepository;

    private final AtomicBoolean tradingAllowed = new AtomicBoolean(true);
    private final AtomicInteger openPositionCount = new AtomicInteger(0);
    private volatile HaltMode haltMode = HaltMode.NONE;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.monitoring.ErrorEventService errorEventService;

    @org.springframework.beans.factory.annotation.Autowired
    private com.algo.trade.monitoring.SchedulerRegistry schedulerRegistry;

    public RiskManager(GlobalConfigService globalConfigService, TradingProperties properties, TradeRepository tradeRepository,
                       TelegramAlertService alertService,
                       com.algo.trade.persistence.DailySummaryRepository dailySummaryRepository,
                       com.algo.trade.persistence.StrategyDecisionRepository decisionRepository) {
        this.globalConfigService = globalConfigService;
        this.properties = properties;
        this.tradeRepository = tradeRepository;
        this.alertService = alertService;
        this.dailySummaryRepository = dailySummaryRepository;
        this.decisionRepository = decisionRepository;
    }

    @jakarta.annotation.PostConstruct
    void registerSchedulers() {
        if (schedulerRegistry != null) {
            schedulerRegistry.register("pnlMonitor", "Daily P&L monitor + loss warning (30s)", 30_000, this::monitorPnl);
            schedulerRegistry.register("dailySummary", "Write daily summary to DB (5min)", 300_000, this::writeDailySummary);
        }
    }

    // ── Order validation ──────────────────────────────────────────────────────

    public RiskCheckResult validateEntry(String symbol, int quantity) {
        if (haltMode == HaltMode.HARD)
            return RiskCheckResult.denied("HARD HALT active — all trading stopped");
        if (!tradingAllowed.get())
            return RiskCheckResult.denied("Trading halted due to risk breach");
        if (openPositionCount.get() >= globalConfigService.getMaxTradesPerDay())
            return RiskCheckResult.denied("Max open positions reached: " + globalConfigService.getMaxTradesPerDay());

        BigDecimal dailyPnl = getDailyPnl();
        BigDecimal maxLoss = globalConfigService.getTotalCapital()
                .multiply(globalConfigService.getMaxDailyLossPercent())
                .divide(BigDecimal.valueOf(100));

        if (dailyPnl.compareTo(maxLoss.negate()) <= 0) {
            haltTrading("Daily max loss breached: ₹" + dailyPnl);
            return RiskCheckResult.denied("Daily max loss limit reached");
        }

        // Daily profit target check
        BigDecimal profitTarget = globalConfigService.getDailyProfitTarget();
        if (profitTarget.compareTo(BigDecimal.ZERO) > 0 && dailyPnl.compareTo(profitTarget) >= 0) {
            return RiskCheckResult.denied("daily profit target reached");
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
                .filter(t -> !t.isPaperTrade())
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
        if (errorEventService != null) errorEventService.critical("RiskManager", "TRADING HALTED: " + reason);
        alertService.systemAlert("🚨 TRADING HALTED: " + reason);
    }

    /** Soft halt — no new entries, existing positions still managed. */
    public void softHalt(String reason) {
        haltMode = HaltMode.SOFT;
        log.warn("SOFT HALT: {} — no new entries, managing existing positions", reason);
        if (errorEventService != null) errorEventService.high("RiskManager", "SOFT HALT: " + reason);
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
        if (schedulerRegistry != null && !schedulerRegistry.isEnabled("pnlMonitor")) return;
        if (!tradingAllowed.get()) return;
        BigDecimal pnl = getDailyPnl();
        BigDecimal maxLoss = globalConfigService.getTotalCapital()
                .multiply(globalConfigService.getMaxDailyLossPercent())
                .divide(BigDecimal.valueOf(100));
        BigDecimal warningThreshold = maxLoss.multiply(BigDecimal.valueOf(0.8));
        if (pnl.compareTo(warningThreshold.negate()) <= 0) {
            log.warn("[RiskManager] Daily P&L warning: ₹{} (limit: ₹{})", pnl, maxLoss);
            alertService.systemAlert("⚠️ Risk Warning: Daily P&L at ₹" + pnl + " (limit: ₹" + maxLoss + ")");
        }

        // Daily profit target monitoring
        BigDecimal profitTarget = globalConfigService.getDailyProfitTarget();
        if (profitTarget.compareTo(BigDecimal.ZERO) > 0 && pnl.compareTo(profitTarget) >= 0) {
            log.info("[RiskManager] Daily profit target reached: ₹{} (target: ₹{})", pnl, profitTarget);
        }
        if (schedulerRegistry != null) schedulerRegistry.recordRun("pnlMonitor");
    }

    /** Write daily summary to H2 every 5 minutes for long-term analysis. */
    @Scheduled(fixedDelay = 300_000)
    public void writeDailySummary() {
        if (schedulerRegistry != null && !schedulerRegistry.isEnabled("dailySummary")) return;
        try {
            LocalDate today = LocalDate.now(IST);
            Instant dayStart = today.atStartOfDay(IST).toInstant();
            Instant dayEnd = today.plusDays(1).atStartOfDay(IST).toInstant();

            var trades = tradeRepository.findByEntryTimeBetween(dayStart, dayEnd);
            var liveTrades = trades.stream().filter(t -> !t.isPaperTrade()).toList();
            var closedLive = liveTrades.stream()
                    .filter(t -> t.getStatus() == com.algo.trade.domain.TradeStatus.CLOSED)
                    .toList();
            int tradeCount = liveTrades.size();
            int wins = (int) closedLive.stream().filter(t -> t.getRealizedPnl() != null && t.getRealizedPnl().signum() > 0).count();
            int losses = (int) closedLive.stream().filter(t -> t.getRealizedPnl() != null && t.getRealizedPnl().signum() < 0).count();
            BigDecimal realizedPnl = closedLive.stream()
                    .map(t -> t.getRealizedPnl())
                    .filter(p -> p != null)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            // Top strategy by trade count
            String topStrategy = closedLive.stream()
                    .filter(t -> t.getStrategyType() != null)
                    .collect(java.util.stream.Collectors.groupingBy(
                            com.algo.trade.persistence.TradeEntity::getStrategyType,
                            java.util.stream.Collectors.counting()))
                    .entrySet().stream()
                    .max(java.util.Map.Entry.comparingByValue())
                    .map(java.util.Map.Entry::getKey)
                    .orElse(null);

            // Max drawdown: lowest cumulative P&L point during the day
            BigDecimal maxDrawdown = BigDecimal.ZERO;
            BigDecimal runningPnl = BigDecimal.ZERO;
            for (var t : closedLive.stream()
                    .sorted(java.util.Comparator.comparing(tr -> tr.getExitTime() != null ? tr.getExitTime() : tr.getEntryTime()))
                    .toList()) {
                if (t.getRealizedPnl() != null) {
                    runningPnl = runningPnl.add(t.getRealizedPnl());
                    if (runningPnl.compareTo(maxDrawdown) < 0) {
                        maxDrawdown = runningPnl;
                    }
                }
            }

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
            summary.setRealizedPnl(realizedPnl);
            summary.setMaxDrawdown(maxDrawdown.abs());
            summary.setTopStrategy(topStrategy);
            summary.setSignalsGenerated((int) signals);
            summary.setSignalsRejected((int) rejected);
            dailySummaryRepository.save(summary);
            log.debug("Daily summary written: date={} trades={} wins={} losses={} pnl={}", today, tradeCount, wins, losses, realizedPnl);
        } catch (Exception e) {
            log.warn("Daily summary write failed: {}", e.getMessage(), e);
            if (errorEventService != null) errorEventService.medium("RiskManager", "Daily summary write failed: " + e.getMessage());
        }
        if (schedulerRegistry != null) schedulerRegistry.recordRun("dailySummary");
    }

    // ── Result DTO ────────────────────────────────────────────────────────────

    public record RiskCheckResult(boolean isApproved, String reason) {
        public static RiskCheckResult approved() { return new RiskCheckResult(true, null); }
        public static RiskCheckResult denied(String reason) { return new RiskCheckResult(false, reason); }
    }
}
