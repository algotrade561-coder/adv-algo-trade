package com.algo.trade.monitoring;

import com.algo.trade.broker.zerodha.KiteAccessTokenStore;
import com.algo.trade.broker.zerodha.KiteWebSocketClient;
import com.algo.trade.config.GlobalConfigService;
import com.algo.trade.domain.*;
import com.algo.trade.execution.TradingStateService;
import com.algo.trade.marketdata.LiveCandleBuilder;
import com.algo.trade.marketdata.LiveInstrumentCache;
import com.algo.trade.persistence.*;
import com.algo.trade.risk.MarketGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * System Diagnostics Service — monitors all critical application health metrics.
 *
 * Tracks:
 *   - WebSocket connection state + last tick time + tick rate
 *   - REST API call counts + error rates
 *   - DB query latency + connection health
 *   - Order lifecycle stages + failure counts
 *   - Component health (scheduler, exit monitors, position sync)
 *   - Data freshness (quotes, candles, OI)
 *
 * Updated every 10 seconds. Exposed via REST for the diagnostics UI page.
 */
@Service
public class SystemDiagnosticsService {

    private static final Logger log = LoggerFactory.getLogger(SystemDiagnosticsService.class);
    private static final Logger diagLog = LoggerFactory.getLogger("DIAGNOSTICS");
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final KiteWebSocketClient webSocketClient;
    private final KiteAccessTokenStore tokenStore;
    private final LiveInstrumentCache liveInstrumentCache;
    private final LiveCandleBuilder liveCandleBuilder;
    private final TradingStateService tradingStateService;
    private final MarketGuard marketGuard;
    private final GlobalConfigService globalConfigService;
    private final TradeRepository tradeRepository;
    private final OrderRepository orderRepository;
    private final ErrorEventRepository errorEventRepository;
    private final com.algo.trade.marketdata.ExpiryCalendar expiryCalendar;
    private final com.algo.trade.marketdata.InstrumentCache instrumentCache;

    // Counters
    private final AtomicLong restCallCount = new AtomicLong();
    private final AtomicLong restErrorCount = new AtomicLong();
    private final AtomicLong wsTickCount = new AtomicLong();
    private final AtomicLong wsDisconnectCount = new AtomicLong();
    private final AtomicLong orderPlacedCount = new AtomicLong();
    private final AtomicLong orderFilledCount = new AtomicLong();
    private final AtomicLong orderRejectedCount = new AtomicLong();
    private final AtomicLong orderFailedCount = new AtomicLong();
    private final AtomicLong dbErrorCount = new AtomicLong();

    private final AtomicReference<DiagnosticSnapshot> latestSnapshot =
            new AtomicReference<>(DiagnosticSnapshot.EMPTY);

    private final com.algo.trade.notification.TelegramAlertService telegramAlertService;

    // Component health tracking
    private final Map<String, Instant> componentLastActive = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile boolean criticalAlertSentThisCycle = false;
    /** Tracks the last alert content to detect changes (P4 #47). */
    private volatile String lastAlertContent = "";

    @org.springframework.beans.factory.annotation.Autowired
    private SchedulerRegistry schedulerRegistry;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.marketdata.OiRestFallbackService oiRestFallbackService;

    public SystemDiagnosticsService(KiteWebSocketClient webSocketClient,
                                     KiteAccessTokenStore tokenStore,
                                     LiveInstrumentCache liveInstrumentCache,
                                     LiveCandleBuilder liveCandleBuilder,
                                     TradingStateService tradingStateService,
                                     MarketGuard marketGuard,
                                     GlobalConfigService globalConfigService,
                                     TradeRepository tradeRepository,
                                     OrderRepository orderRepository,
                                     ErrorEventRepository errorEventRepository,
                                     com.algo.trade.notification.TelegramAlertService telegramAlertService,
                                     com.algo.trade.marketdata.ExpiryCalendar expiryCalendar,
                                     com.algo.trade.marketdata.InstrumentCache instrumentCache) {
        this.webSocketClient = webSocketClient;
        this.tokenStore = tokenStore;
        this.liveInstrumentCache = liveInstrumentCache;
        this.liveCandleBuilder = liveCandleBuilder;
        this.tradingStateService = tradingStateService;
        this.marketGuard = marketGuard;
        this.globalConfigService = globalConfigService;
        this.tradeRepository = tradeRepository;
        this.orderRepository = orderRepository;
        this.errorEventRepository = errorEventRepository;
        this.telegramAlertService = telegramAlertService;
        this.expiryCalendar = expiryCalendar;
        this.instrumentCache = instrumentCache;
    }

    @jakarta.annotation.PostConstruct
    void registerScheduler() {
        schedulerRegistry.register("diagnostics", "System health snapshot (10s)", 10_000, this::compute);
    }

    // ── Public API for counters (called by other components) ──────────────

    public void recordRestCall() { restCallCount.incrementAndGet(); }
    public void recordRestError() { restErrorCount.incrementAndGet(); }
    public void recordWsTick() { wsTickCount.incrementAndGet(); }
    public void recordWsDisconnect() { wsDisconnectCount.incrementAndGet(); }
    public void recordOrderPlaced() { orderPlacedCount.incrementAndGet(); }
    public void recordOrderFilled() { orderFilledCount.incrementAndGet(); }
    public void recordOrderRejected() { orderRejectedCount.incrementAndGet(); }
    public void recordOrderFailed() { orderFailedCount.incrementAndGet(); }
    public void recordDbError() { dbErrorCount.incrementAndGet(); }
    public void recordComponentActive(String component) { componentLastActive.put(component, Instant.now()); }

    public DiagnosticSnapshot getSnapshot() { return latestSnapshot.get(); }

    // ── Scheduled computation ─────────────────────────────────────────────

    @Scheduled(fixedDelay = 10_000, initialDelay = 15_000)
    public void compute() {
        if (!schedulerRegistry.isEnabled("diagnostics")) return;
        try {
            DiagnosticSnapshot snapshot = buildSnapshot();
            latestSnapshot.set(snapshot);
            logDiagnostics(snapshot);
            schedulerRegistry.recordRun("diagnostics");
        } catch (Exception e) {
            log.debug("[Diagnostics] Compute failed: {}", e.getMessage());
        }
    }

    private DiagnosticSnapshot buildSnapshot() {
        Instant now = Instant.now();
        Instant todayStart = LocalDate.now(IST).atStartOfDay(IST).toInstant();

        // WebSocket health + subscription details
        boolean wsConnected = webSocketClient.isConnected();
        boolean brokerAuthenticated = tokenStore.authenticated();
        long ticksTotal = wsTickCount.get();
        long disconnects = wsDisconnectCount.get();
        int subscribedTokenCount = webSocketClient.getSubscribedTokenCount();
        int wsSubscribeCount = webSocketClient.getSubscribeCount();
        int wsReconnectCount = webSocketClient.getReconnectCount();
        int wsZombieReconnectCount = webSocketClient.getZombieReconnectCount();
        Instant lastTickTime = webSocketClient.getLastTickTime();
        Instant lastConnectTime = webSocketClient.getLastConnectTime();
        Instant lastSubscribeTime = webSocketClient.getLastSubscribeTime();
        long tickAgeSec = lastTickTime != null ? Duration.between(lastTickTime, now).toSeconds() : -1;
        long connectAgeSec = lastConnectTime != null ? Duration.between(lastConnectTime, now).toSeconds() : -1;
        long subscribeAgeSec = lastSubscribeTime != null ? Duration.between(lastSubscribeTime, now).toSeconds() : -1;

        // Enabled underlyings → IndexType list
        List<IndexType> enabledIndices = tradingStateService.enabledUnderlyings().stream()
                .map(IndexType::from).toList();

        // Data freshness
        double vix = marketGuard.getCurrentVix();
        boolean instrumentCacheReady = liveInstrumentCache.isReady();

        // Scheduler health
        Instant lastScan = tradingStateService.lastScanAt();
        long scanGapSeconds = lastScan != null ? Duration.between(lastScan, now).toSeconds() : -1;
        boolean scannerRunning = tradingStateService.running();
        boolean schedulerEnabled = tradingStateService.schedulerEnabled();

        // Order stats today
        List<OrderEntity> todayOrders = orderRepository.findBySideAndUpdatedAtBetween(
                OrderSide.BUY.name(), todayStart, now);
        todayOrders.addAll(orderRepository.findBySideAndUpdatedAtBetween(
                OrderSide.SELL.name(), todayStart, now));
        long pendingOrders = todayOrders.stream()
                .filter(o -> o.getStatus() == OrderStatus.OPEN || o.getStatus() == OrderStatus.NEW).count();
        long completedOrders = todayOrders.stream()
                .filter(o -> o.getStatus() == OrderStatus.COMPLETE).count();
        long rejectedOrders = todayOrders.stream()
                .filter(o -> o.getStatus() == OrderStatus.REJECTED).count();
        long cancelledOrders = todayOrders.stream()
                .filter(o -> o.getStatus() == OrderStatus.CANCELLED).count();

        // Trade stats today
        List<TradeEntity> todayTrades = tradeRepository.findByEntryTimeBetween(todayStart, now);
        long openLive = todayTrades.stream().filter(t -> t.getStatus() == TradeStatus.OPEN && !t.isPaperTrade()).count();
        long openPaper = todayTrades.stream().filter(t -> t.getStatus() == TradeStatus.OPEN && t.isPaperTrade()).count();
        long closedLive = todayTrades.stream().filter(t -> t.getStatus() == TradeStatus.CLOSED && !t.isPaperTrade()).count();
        long closedPaper = todayTrades.stream().filter(t -> t.getStatus() == TradeStatus.CLOSED && t.isPaperTrade()).count();

        // Error events today
        long errorsToday = 0;
        try {
            errorsToday = errorEventRepository.countByTimestampAfter(todayStart);
        } catch (Exception ignored) {}

        // ── Build SYSTEM HEALTH map ───────────────────────────────────────
        Map<String, Object> systemHealth = new LinkedHashMap<>();
        // WebSocket
        systemHealth.put("wsConnected", wsConnected);
        systemHealth.put("wsSubscribedTokens", subscribedTokenCount);
        systemHealth.put("wsSubscribeCount", wsSubscribeCount);
        systemHealth.put("wsReconnectCount", wsReconnectCount);
        systemHealth.put("wsZombieReconnectCount", wsZombieReconnectCount);
        systemHealth.put("wsLastTickAge", tickAgeSec >= 0 ? tickAgeSec + "s" : "never");
        systemHealth.put("wsLastConnectAge", connectAgeSec >= 0 ? connectAgeSec + "s" : "never");
        systemHealth.put("wsLastSubscribeAge", subscribeAgeSec >= 0 ? subscribeAgeSec + "s" : "never");
        systemHealth.put("wsTickCount", ticksTotal);
        systemHealth.put("wsDisconnectCount", disconnects);
        // Broker
        systemHealth.put("brokerAuthenticated", brokerAuthenticated);
        // Scanner
        systemHealth.put("scannerRunning", scannerRunning);
        systemHealth.put("schedulerEnabled", schedulerEnabled);
        systemHealth.put("lastScanAt", lastScan != null ? lastScan.toString() : null);
        systemHealth.put("scanGapSeconds", scanGapSeconds);
        // Infrastructure
        systemHealth.put("instrumentCacheReady", instrumentCacheReady);
        systemHealth.put("restCallCount", restCallCount.get());
        systemHealth.put("restErrorCount", restErrorCount.get());
        if (oiRestFallbackService != null) {
            systemHealth.put("oiRestFallbackActive", oiRestFallbackService.isRestFallbackActive());
            systemHealth.put("oiRestFallbackCallCount", oiRestFallbackService.getRestCallCount());
            systemHealth.put("oiRestFallbackLastBatchAt",
                    oiRestFallbackService.getLastBatchAt() != null
                            ? oiRestFallbackService.getLastBatchAt().toString() : null);
            systemHealth.put("oiRestFallbackLastBatchSummary", oiRestFallbackService.getLastBatchSummary());
            systemHealth.put("oiRestFallbackWsTickAgeSec", oiRestFallbackService.getWsTickAgeSec());
        }
        systemHealth.put("dbErrorCount", dbErrorCount.get());
        systemHealth.put("errorsToday", errorsToday);
        // Halt/Kill state
        systemHealth.put("haltMode", tradingStateService.haltMode().name());
        systemHealth.put("killSwitch", tradingStateService.killSwitchEnabled());
        systemHealth.put("dailyApproved", tradingStateService.isDailyApproved());
        // Component last-active
        for (Map.Entry<String, Instant> entry : componentLastActive.entrySet()) {
            long ageSec = Duration.between(entry.getValue(), now).toSeconds();
            systemHealth.put("component_" + entry.getKey(), ageSec < 120 ? "ACTIVE (" + ageSec + "s)" : "STALE (" + ageSec + "s)");
        }

        // ── Build DATA HEALTH map (everything the scanner needs) ──────────
        Map<String, Object> dataHealth = new LinkedHashMap<>();
        // Enabled underlyings list (for UI to iterate)
        dataHealth.put("enabledUnderlyings", enabledIndices.stream().map(Enum::name).toList());
        // Spot prices per enabled underlying
        for (IndexType idx : enabledIndices) {
            String key = idx.name().toLowerCase();
            double spot = liveInstrumentCache.getFuturesPrice(idx);
            dataHealth.put(key + "Spot", spot);
            dataHealth.put(key + "SpotStatus", spot > 0 ? "OK" : "NO_DATA");
        }
        // VIX
        dataHealth.put("vix", vix);
        dataHealth.put("vixStatus", vix > 0 ? "OK" : "NO_DATA");
        // PCR — use MarketGuard's cached PCR
        double cachedPcr = marketGuard.getCurrentPcr();
        dataHealth.put("pcr", cachedPcr);
        dataHealth.put("pcrStatus", cachedPcr > 0 ? "OK" : "NO_DATA");
        // Option chain health per enabled underlying
        for (IndexType idx : enabledIndices) {
            String key = idx.name().toLowerCase();
            try {
                // Use instrument cache's nearest expiry (matches actual Zerodha contracts)
                // Fall back to ExpiryCalendar's weekly expiry if instrument cache has no data
                var underlying = com.algo.trade.domain.UnderlyingSymbol.valueOf(idx.name());
                LocalDate expiry = instrumentCache.nearestExpiry(underlying, LocalDate.now())
                        .orElseGet(() -> expiryCalendar.getCurrentExpiry(idx));
                var chain = liveInstrumentCache.getStrikeChain(idx, expiry);
                dataHealth.put(key + "OptionChainSize", chain.size());
                long optionsWithOI = chain.stream().filter(o -> o.getOpenInterest() > 0).count();
                dataHealth.put(key + "OptionsWithLiveOI", optionsWithOI);
                long optionsWithPrice = chain.stream().filter(o -> o.getLastPrice() > 0).count();
                dataHealth.put(key + "OptionsWithLivePrice", optionsWithPrice);
                dataHealth.put(key + "OptionDataStatus",
                        optionsWithPrice > 5 ? "OK" : optionsWithPrice > 0 ? "PARTIAL" : "NO_DATA");
                if (oiRestFallbackService != null) {
                    dataHealth.put(key + "MaxAtmOiSampleAgeSec", oiRestFallbackService.getMaxAtmOiSampleAgeSec(idx));
                }
            } catch (Exception ignored) {
                dataHealth.put(key + "OptionDataStatus", "ERROR");
            }
        }
        // Candle data freshness per enabled underlying
        for (IndexType idx : enabledIndices) {
            String key = idx.name().toLowerCase();
            long spotToken = idx.spotToken();
            var candles1m = liveCandleBuilder.getHistory(spotToken, com.algo.trade.domain.Timeframe.ONE_MINUTE);
            var candles5m = liveCandleBuilder.getHistory(spotToken, com.algo.trade.domain.Timeframe.FIVE_MINUTE);
            var candles15m = liveCandleBuilder.getHistory(spotToken, com.algo.trade.domain.Timeframe.FIFTEEN_MINUTE);
            dataHealth.put(key + "Candles1m", candles1m.size());
            dataHealth.put(key + "Candles5m", candles5m.size());
            dataHealth.put(key + "Candles15m", candles15m.size());
            if (!candles1m.isEmpty()) {
                long candleAge = Duration.between(candles1m.getLast().timestamp(), now).toSeconds();
                dataHealth.put(key + "LastCandleAge", candleAge + "s");
                dataHealth.put(key + "CandleStatus",
                        candleAge < 120 ? "FRESH" : candleAge < 300 ? "STALE" : "VERY_STALE");
            } else {
                dataHealth.put(key + "CandleStatus", "NO_DATA");
            }
        }
        // Market Guard status
        dataHealth.put("safeForLongPremium", marketGuard.isSafeForLongPremium());
        // 4 Jun 2026: allowEventDay=true so this matches the entry-gate
        // semantics (pre-event day no longer blocks long-buy intraday MIS).
        // The eventDay / preEventDay booleans below are still exposed.
        dataHealth.put("longPremiumBlockReason", marketGuard.longPremiumBlockReason(true));
        dataHealth.put("safeForShortPremium", marketGuard.isSafeForShortPremium());
        dataHealth.put("circuitBreakerTriggered", marketGuard.isCircuitBreakerTriggered());
        dataHealth.put("eventDay", marketGuard.isEventDay());
        dataHealth.put("preEventDay", marketGuard.isPreEventDay());

        // ── Build ORDER STATS map ─────────────────────────────────────────
        Map<String, Object> orderStats = new LinkedHashMap<>();
        orderStats.put("totalOrders", todayOrders.size());
        orderStats.put("pendingOrders", pendingOrders);
        orderStats.put("completedOrders", completedOrders);
        orderStats.put("rejectedOrders", rejectedOrders);
        orderStats.put("cancelledOrders", cancelledOrders);
        orderStats.put("openLiveTrades", openLive);
        orderStats.put("openPaperTrades", openPaper);
        orderStats.put("closedLiveTrades", closedLive);
        orderStats.put("closedPaperTrades", closedPaper);
        orderStats.put("orderPlacedCount", orderPlacedCount.get());
        orderStats.put("orderFilledCount", orderFilledCount.get());
        orderStats.put("orderRejectedCount", orderRejectedCount.get());
        orderStats.put("orderFailedCount", orderFailedCount.get());

        // ── ALERTS ────────────────────────────────────────────────────────
        List<String> alerts = new ArrayList<>();
        // System alerts
        if (!wsConnected) alerts.add("🔴 WebSocket disconnected — using REST fallback");
        if (wsConnected && subscribedTokenCount == 0) alerts.add("🔴 WebSocket connected but NO tokens subscribed");
        if (wsConnected && tickAgeSec > 30) alerts.add("🟡 Last tick " + tickAgeSec + "s ago — data may be stale");
        if (wsConnected && tickAgeSec > 120) alerts.add("🔴 ZOMBIE WebSocket — connected but no ticks for " + tickAgeSec + "s");
        if (!brokerAuthenticated) alerts.add("🔴 Broker not authenticated — orders will fail");
        if (scanGapSeconds > 300) alerts.add("🔴 No scan in " + scanGapSeconds + "s — scanner stuck");
        if (wsReconnectCount > 5) alerts.add("🟡 WebSocket reconnected " + wsReconnectCount + "x today — unstable");
        if (wsZombieReconnectCount > 0) alerts.add("🟡 WebSocket zombie recovered " + wsZombieReconnectCount + "x today");
        // Data alerts
        if (vix <= 0) alerts.add("🔴 VIX feed unavailable — entries blocked");
        for (IndexType idx : enabledIndices) {
            double spot = liveInstrumentCache.getFuturesPrice(idx);
            if (spot <= 0 && wsConnected) alerts.add("🔴 " + idx.name() + " spot unavailable despite WS connected");
        }
        if (cachedPcr <= 0) alerts.add("🟡 PCR data unavailable");
        // Order alerts
        if (pendingOrders > 3) alerts.add("🟡 " + pendingOrders + " pending orders — possible fill issues");
        if (rejectedOrders > 3) alerts.add("🟡 " + rejectedOrders + " rejected orders today");
        if (errorsToday > 10) alerts.add("🟡 " + errorsToday + " errors today — check error log");

        return new DiagnosticSnapshot(now.toString(), systemHealth, dataHealth, orderStats, alerts);
    }

    private void logDiagnostics(DiagnosticSnapshot s) {
        var sys = s.systemHealth();
        var data = s.dataHealth();
        var ord = s.orderStats();
        // Build spot summary for log
        StringBuilder spotSummary = new StringBuilder();
        for (String key : data.keySet()) {
            if (key.endsWith("Spot") && !key.endsWith("SpotStatus")) {
                spotSummary.append(key.replace("Spot", "").toUpperCase()).append("=").append(data.get(key)).append(" ");
            }
        }
        diagLog.info("SYS: WS={} tokens={} lastTick={} reconnects={} | Auth={} Scanner={} scanGap={}s | " +
                        "DATA: {} VIX={} PCR={} | " +
                        "ORDERS: pending={} filled={} rejected={} | TRADES: openLive={} closedLive={} | Alerts={}",
                sys.getOrDefault("wsConnected", false), sys.getOrDefault("wsSubscribedTokens", 0),
                sys.getOrDefault("wsLastTickAge", "?"), sys.getOrDefault("wsReconnectCount", 0),
                sys.getOrDefault("brokerAuthenticated", false), sys.getOrDefault("scannerRunning", false),
                sys.getOrDefault("scanGapSeconds", -1),
                spotSummary.toString().trim(),
                data.getOrDefault("vix", 0), data.getOrDefault("pcr", 0),
                ord.getOrDefault("pendingOrders", 0), ord.getOrDefault("completedOrders", 0),
                ord.getOrDefault("rejectedOrders", 0),
                ord.getOrDefault("openLiveTrades", 0), ord.getOrDefault("closedLiveTrades", 0),
                s.alerts().isEmpty() ? "none" : s.alerts().size());

        for (String alert : s.alerts()) {
            diagLog.warn("ALERT: {}", alert);
        }

        // Send Telegram for critical issues (max once per cycle to avoid spam)
        if (!s.alerts().isEmpty() && !criticalAlertSentThisCycle) {
            boolean hasCritical = s.alerts().stream().anyMatch(a -> a.contains("🔴"));
            if (hasCritical) {
                telegramAlertService.systemAlert("🚨 System Diagnostics Alert:\n" + String.join("\n", s.alerts()));
                criticalAlertSentThisCycle = true;
                lastAlertContent = String.join("|", s.alerts());
            }
        }
        // Re-alert if alert content changed (new/different failures) even if flag was already set
        if (!s.alerts().isEmpty() && criticalAlertSentThisCycle) {
            String currentContent = String.join("|", s.alerts());
            if (!currentContent.equals(lastAlertContent)) {
                boolean hasCritical = s.alerts().stream().anyMatch(a -> a.contains("🔴"));
                if (hasCritical) {
                    telegramAlertService.systemAlert("🚨 System Diagnostics Alert (changed):\n" + String.join("\n", s.alerts()));
                    lastAlertContent = currentContent;
                }
            }
        }
        // Reset alert flag when all clear
        if (s.alerts().isEmpty()) {
            criticalAlertSentThisCycle = false;
            lastAlertContent = "";
        }
    }

    @Scheduled(cron = "0 0 0 * * *")
    public void resetDailyCounters() {
        restCallCount.set(0);
        restErrorCount.set(0);
        wsTickCount.set(0);
        wsDisconnectCount.set(0);
        orderPlacedCount.set(0);
        orderFilledCount.set(0);
        orderRejectedCount.set(0);
        orderFailedCount.set(0);
        dbErrorCount.set(0);
    }

    // ── Snapshot record ───────────────────────────────────────────────────

    public record DiagnosticSnapshot(
            String timestamp,

            // ── SYSTEM HEALTH ─────────────────────────────────────────
            Map<String, Object> systemHealth,

            // ── DATA HEALTH (everything the scanner needs) ────────────
            Map<String, Object> dataHealth,

            // ── ORDERS & TRADES ───────────────────────────────────────
            Map<String, Object> orderStats,

            // ── ALERTS ────────────────────────────────────────────────
            List<String> alerts
    ) {
        static final DiagnosticSnapshot EMPTY = new DiagnosticSnapshot(
                "", Map.of(), Map.of(), Map.of(), List.of()
        );
    }
}
