package com.algo.trade.reporting;

import com.algo.trade.domain.TradeStatus;
import com.algo.trade.persistence.TradeEntity;
import com.algo.trade.persistence.TradeRepository;
import com.algo.trade.notification.TelegramAlertService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Performance Metrics Service — computes live trading analytics.
 *
 * Provides: profit factor, win rate, drawdown, avg win/loss, rolling metrics.
 * Exposed via REST for the dashboard. Also monitors thresholds and sends alerts.
 */
@Service
public class PerformanceMetricsService {

    private static final Logger log = LoggerFactory.getLogger(PerformanceMetricsService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final MathContext MC = MathContext.DECIMAL64;

    private final TradeRepository tradeRepository;
    private final TelegramAlertService telegramAlertService;

    private final AtomicReference<PerformanceSnapshot> latestSnapshot = new AtomicReference<>(PerformanceSnapshot.EMPTY);

    /** Alert thresholds. */
    private static final double MIN_PROFIT_FACTOR_ALERT = 0.8;
    private static final double MAX_DRAWDOWN_ALERT_PERCENT = 5.0;
    private volatile boolean alertSentToday = false;

    public PerformanceMetricsService(TradeRepository tradeRepository,
                                      TelegramAlertService telegramAlertService) {
        this.tradeRepository = tradeRepository;
        this.telegramAlertService = telegramAlertService;
    }

    public record PerformanceSnapshot(
            // Today's metrics
            int tradesToday,
            int winsToday,
            int lossesToday,
            double winRateToday,
            double profitFactorToday,
            double totalPnlToday,
            double avgWinToday,
            double avgLossToday,
            double maxDrawdownToday,
            double peakPnlToday,
            // Rolling 7-day metrics
            int tradesLast7,
            double winRateLast7,
            double profitFactorLast7,
            double totalPnlLast7,
            double maxDrawdownLast7,
            // Rolling 30-day metrics
            int tradesLast30,
            double winRateLast30,
            double profitFactorLast30,
            double totalPnlLast30,
            double maxDrawdownLast30,
            // Per-strategy today
            Map<String, StrategyMetrics> strategyBreakdown,
            // Timestamp
            String updatedAt
    ) {
        static final PerformanceSnapshot EMPTY = new PerformanceSnapshot(
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0,
                0, 0, 0, 0, 0,
                Map.of(), ""
        );
    }

    public record StrategyMetrics(
            int trades, int wins, int losses, double winRate,
            double totalPnl, double avgPnl, double profitFactor
    ) {}

    public PerformanceSnapshot getSnapshot() {
        return latestSnapshot.get();
    }

    @Scheduled(fixedDelay = 15_000, initialDelay = 10_000)
    public void compute() {
        try {
            Instant todayStart = LocalDate.now(IST).atStartOfDay(IST).toInstant();
            Instant last7Start = LocalDate.now(IST).minusDays(6).atStartOfDay(IST).toInstant();
            Instant last30Start = LocalDate.now(IST).minusDays(29).atStartOfDay(IST).toInstant();
            Instant now = Instant.now();

            List<TradeEntity> todayTrades = closedTradesBetween(todayStart, now);
            List<TradeEntity> last7Trades = closedTradesBetween(last7Start, now);
            List<TradeEntity> last30Trades = closedTradesBetween(last30Start, now);

            // Today
            var todayMetrics = computeMetrics(todayTrades);
            double peakPnl = computePeakPnl(todayTrades);
            double maxDrawdown = computeMaxDrawdown(todayTrades);

            // Rolling
            var last7Metrics = computeMetrics(last7Trades);
            double maxDrawdown7 = computeMaxDrawdown(last7Trades);
            var last30Metrics = computeMetrics(last30Trades);
            double maxDrawdown30 = computeMaxDrawdown(last30Trades);

            // Per-strategy breakdown (today)
            Map<String, StrategyMetrics> breakdown = new java.util.LinkedHashMap<>();
            todayTrades.stream()
                    .collect(java.util.stream.Collectors.groupingBy(
                            t -> t.getStrategyType() != null ? t.getStrategyType() : "UNKNOWN"))
                    .forEach((strategy, trades) -> {
                        var m = computeMetrics(trades);
                        breakdown.put(strategy, new StrategyMetrics(
                                trades.size(), m.wins, m.losses, m.winRate,
                                m.totalPnl, trades.isEmpty() ? 0 : m.totalPnl / trades.size(), m.profitFactor));
                    });

            PerformanceSnapshot snapshot = new PerformanceSnapshot(
                    todayTrades.size(), todayMetrics.wins, todayMetrics.losses,
                    todayMetrics.winRate, todayMetrics.profitFactor, todayMetrics.totalPnl,
                    todayMetrics.avgWin, todayMetrics.avgLoss, maxDrawdown, peakPnl,
                    last7Trades.size(), last7Metrics.winRate, last7Metrics.profitFactor, last7Metrics.totalPnl, maxDrawdown7,
                    last30Trades.size(), last30Metrics.winRate, last30Metrics.profitFactor, last30Metrics.totalPnl, maxDrawdown30,
                    Map.copyOf(breakdown),
                    Instant.now().toString()
            );
            latestSnapshot.set(snapshot);

            // Performance threshold alerts
            checkAlerts(snapshot);

        } catch (Exception e) {
            log.debug("[PerformanceMetrics] Compute failed: {}", e.getMessage());
        }
    }

    @Scheduled(cron = "0 0 0 * * *")
    public void resetDailyAlerts() {
        alertSentToday = false;
    }

    private void checkAlerts(PerformanceSnapshot s) {
        if (alertSentToday || s.tradesToday() < 3) return; // need minimum trades for meaningful metrics

        if (s.profitFactorToday() > 0 && s.profitFactorToday() < MIN_PROFIT_FACTOR_ALERT) {
            telegramAlertService.systemAlert(String.format(
                    "⚠️ Performance Alert: Profit factor %.2f (below %.1f threshold) | Win rate %.0f%% | P&L ₹%.0f",
                    s.profitFactorToday(), MIN_PROFIT_FACTOR_ALERT, s.winRateToday(), s.totalPnlToday()));
            alertSentToday = true;
        }
        if (s.maxDrawdownToday() > MAX_DRAWDOWN_ALERT_PERCENT) {
            telegramAlertService.systemAlert(String.format(
                    "⚠️ Drawdown Alert: Max drawdown %.1f%% (threshold %.1f%%) | P&L ₹%.0f",
                    s.maxDrawdownToday(), MAX_DRAWDOWN_ALERT_PERCENT, s.totalPnlToday()));
            alertSentToday = true;
        }
    }

    private List<TradeEntity> closedTradesBetween(Instant from, Instant to) {
        return tradeRepository.findByEntryTimeBetween(from, to).stream()
                .filter(t -> t.getStatus() == TradeStatus.CLOSED)
                .filter(t -> !t.isPaperTrade())
                .filter(t -> t.getRealizedPnl() != null)
                .toList();
    }

    private record MetricsResult(int wins, int losses, double winRate, double totalPnl,
                                  double avgWin, double avgLoss, double profitFactor) {}

    private MetricsResult computeMetrics(List<TradeEntity> trades) {
        if (trades.isEmpty()) return new MetricsResult(0, 0, 0, 0, 0, 0, 0);

        int wins = 0, losses = 0;
        double totalWin = 0, totalLoss = 0;
        for (TradeEntity t : trades) {
            double pnl = t.getRealizedPnl().doubleValue();
            if (pnl > 0) { wins++; totalWin += pnl; }
            else { losses++; totalLoss += Math.abs(pnl); }
        }
        double winRate = trades.size() > 0 ? (double) wins / trades.size() * 100 : 0;
        double avgWin = wins > 0 ? totalWin / wins : 0;
        double avgLoss = losses > 0 ? totalLoss / losses : 0;
        double profitFactor = totalLoss > 0 ? totalWin / totalLoss : (totalWin > 0 ? 999 : 0);
        double totalPnl = totalWin - totalLoss;

        return new MetricsResult(wins, losses, winRate, totalPnl, avgWin, avgLoss, profitFactor);
    }

    private double computePeakPnl(List<TradeEntity> trades) {
        double cumPnl = 0, peak = 0;
        for (TradeEntity t : trades) {
            cumPnl += t.getRealizedPnl().doubleValue();
            peak = Math.max(peak, cumPnl);
        }
        return peak;
    }

    private double computeMaxDrawdown(List<TradeEntity> trades) {
        double cumPnl = 0, peak = 0, maxDd = 0;
        for (TradeEntity t : trades) {
            cumPnl += t.getRealizedPnl().doubleValue();
            peak = Math.max(peak, cumPnl);
            double dd = peak - cumPnl;
            maxDd = Math.max(maxDd, dd);
        }
        return maxDd;
    }
}
