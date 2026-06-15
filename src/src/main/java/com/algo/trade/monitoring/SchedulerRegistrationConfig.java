package com.algo.trade.monitoring;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Registers all remaining scheduled tasks with the SchedulerRegistry.
 * Tasks that were already registered in their own @PostConstruct (LivePositionExitMonitor,
 * PositionSynchronizer, SystemDiagnosticsService, etc.) are NOT duplicated here.
 *
 * This centralizes registration for tasks where modifying the class would be too invasive.
 */
@Component
public class SchedulerRegistrationConfig {

    private static final Logger log = LoggerFactory.getLogger(SchedulerRegistrationConfig.class);

    private final SchedulerRegistry registry;

    // Inject all scheduled components
    private final com.algo.trade.risk.SafeWeekPredictor safeWeekPredictor;
    private final com.algo.trade.reporting.PerformanceMetricsService performanceMetricsService;
    private final com.algo.trade.regime.StrategySelector strategySelector;
    private final com.algo.trade.news.NewsFeedService newsFeedService;
    private final com.algo.trade.execution.OrderFillWatchdog orderFillWatchdog;
    private final com.algo.trade.indicator.VolumeDeltaTracker volumeDeltaTracker;
    private final com.algo.trade.indicator.IVRankTracker ivRankTracker;
    private final com.algo.trade.broker.zerodha.TokenExpiryMonitor tokenExpiryMonitor;
    private final com.algo.trade.insights.AiInsightsScheduler aiInsightsScheduler;
    private final com.algo.trade.reporting.SignalTuningScheduler signalTuningScheduler;
    private final com.algo.trade.reporting.SignalTuningProperties signalTuningProperties;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.execution.TailHedgeManager tailHedgeManager;

    public SchedulerRegistrationConfig(
            SchedulerRegistry registry,
            com.algo.trade.risk.SafeWeekPredictor safeWeekPredictor,
            com.algo.trade.reporting.PerformanceMetricsService performanceMetricsService,
            com.algo.trade.regime.StrategySelector strategySelector,
            com.algo.trade.news.NewsFeedService newsFeedService,
            com.algo.trade.execution.OrderFillWatchdog orderFillWatchdog,
            com.algo.trade.indicator.VolumeDeltaTracker volumeDeltaTracker,
            com.algo.trade.indicator.IVRankTracker ivRankTracker,
            com.algo.trade.broker.zerodha.TokenExpiryMonitor tokenExpiryMonitor,
            com.algo.trade.insights.AiInsightsScheduler aiInsightsScheduler,
            com.algo.trade.reporting.SignalTuningScheduler signalTuningScheduler,
            com.algo.trade.reporting.SignalTuningProperties signalTuningProperties
    ) {
        this.registry = registry;
        this.safeWeekPredictor = safeWeekPredictor;
        this.performanceMetricsService = performanceMetricsService;
        this.strategySelector = strategySelector;
        this.newsFeedService = newsFeedService;
        this.orderFillWatchdog = orderFillWatchdog;
        this.volumeDeltaTracker = volumeDeltaTracker;
        this.ivRankTracker = ivRankTracker;
        this.tokenExpiryMonitor = tokenExpiryMonitor;
        this.aiInsightsScheduler = aiInsightsScheduler;
        this.signalTuningScheduler = signalTuningScheduler;
        this.signalTuningProperties = signalTuningProperties;
    }

    @PostConstruct
    void registerAll() {
        registry.register("safeWeekPredictor", "Safe week risk assessment (60s)", 60_000, safeWeekPredictor::evaluate);
        registry.register("performanceMetrics", "Performance metrics snapshot (15s)", 15_000, performanceMetricsService::compute);
        registry.register("regimeSelector", "Market regime change detection (30s)", 30_000, strategySelector::checkRegimeChanges);
        registry.register("newsFeed", "News feed fetch (5min)", 300_000, newsFeedService::scheduledFetch);
        registry.register("orderFillWatchdog", "Pending order fill check (2s)", 2_000, orderFillWatchdog::checkPendingOrders);
        if (tailHedgeManager != null) {
            registry.register("tailHedge", "Tail hedge check (60s)", 60_000, tailHedgeManager::checkAndHedge);
        }
        registry.register("volumeDelta", "Volume delta tracker update (30s)", 30_000, volumeDeltaTracker::update);
        registry.register("ivRankTracker", "IV rank intraday snapshot (cron hourly)", 0, ivRankTracker::recordIntradaySnapshot);
        registry.register("tokenMonitor", "Kite token expiry check (cron 8:00/8:45)", 0, tokenExpiryMonitor::checkTokenAtOpen);
        registry.register("aiInsights", "AI insights analysis (cron 9/12/15)", 0, aiInsightsScheduler::runHourlyAnalysis);
        registry.register("signalTuning", "Signal tuning report (cron 15:30 IST)", 0, signalTuningScheduler::generatePostMarketReport);
        registry.setEnabled("signalTuning", signalTuningProperties.isSchedulerEnabled());
        log.info("[SchedulerRegistry] Registered additional tasks via SchedulerRegistrationConfig (tailHedge={}, signalTuningScheduler={})",
                tailHedgeManager != null, signalTuningProperties.isSchedulerEnabled());
    }
}
