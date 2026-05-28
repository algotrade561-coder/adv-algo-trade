package com.algo.trade.execution;

import com.algo.trade.notification.TelegramAlertService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * Scheduler Watchdog — detects if AlgoTradeExecution has stopped scanning.
 *
 * Checks every 5 minutes during market hours. If no scan has occurred in the
 * last 10 minutes while the scanner is supposed to be running, sends a Telegram alert.
 */
@Component
public class SchedulerWatchdog {

    private static final Logger log = LoggerFactory.getLogger(SchedulerWatchdog.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Duration MAX_SCAN_GAP = Duration.ofMinutes(5);

    private final TradingStateService tradingStateService;
    private final TelegramAlertService alertService;
    private final com.algo.trade.broker.zerodha.KiteWebSocketClient webSocketClient;
    private final com.algo.trade.monitoring.ErrorEventService errorEventService;
    private final com.algo.trade.marketdata.LiveInstrumentCache liveInstrumentCache;
    private final com.algo.trade.marketdata.InstrumentCache instrumentCache;
    private final com.algo.trade.broker.zerodha.KiteStartupLogin kiteStartupLogin;
    private final com.algo.trade.marketdata.ExpiryCalendar expiryCalendar;
    private volatile boolean alertSentThisSession = false;
    private volatile int lowOptionDataCount = 0;
    private static final int LOW_OPTION_THRESHOLD = 5;
    private static final int LOW_OPTION_MAX_CHECKS = 3;

    @org.springframework.beans.factory.annotation.Autowired
    private com.algo.trade.monitoring.SchedulerRegistry schedulerRegistry;

    public SchedulerWatchdog(TradingStateService tradingStateService,
                              TelegramAlertService alertService,
                              com.algo.trade.broker.zerodha.KiteWebSocketClient webSocketClient,
                              com.algo.trade.monitoring.ErrorEventService errorEventService,
                              com.algo.trade.marketdata.LiveInstrumentCache liveInstrumentCache,
                              com.algo.trade.marketdata.InstrumentCache instrumentCache,
                              com.algo.trade.broker.zerodha.KiteStartupLogin kiteStartupLogin,
                              com.algo.trade.marketdata.ExpiryCalendar expiryCalendar) {
        this.tradingStateService = tradingStateService;
        this.alertService = alertService;
        this.webSocketClient = webSocketClient;
        this.errorEventService = errorEventService;
        this.liveInstrumentCache = liveInstrumentCache;
        this.instrumentCache = instrumentCache;
        this.kiteStartupLogin = kiteStartupLogin;
        this.expiryCalendar = expiryCalendar;
    }

    @jakarta.annotation.PostConstruct
    void registerScheduler() {
        if (schedulerRegistry != null) schedulerRegistry.register("watchdog", "Scanner stall detection (5min)", 300_000, this::check);
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 600_000) // every 5 min, start after 10 min
    public void check() {
        if (schedulerRegistry != null && !schedulerRegistry.isEnabled("watchdog")) return;
        if (schedulerRegistry != null) schedulerRegistry.recordRun("watchdog");
        LocalTime now = LocalTime.now(IST);
        if (now.isBefore(LocalTime.of(9, 20)) || now.isAfter(LocalTime.of(15, 25))) {
            alertSentThisSession = false; // reset for next session
            return;
        }

        if (!tradingStateService.running()) return;

        // ── Scan gap detection (only relevant when REST scheduler is enabled) ──
        if (tradingStateService.schedulerEnabled()) {
            Instant lastScan = tradingStateService.lastScanAt();
            if (lastScan == null) {
                if (!alertSentThisSession) {
                    log.warn("[SchedulerWatchdog] No scan recorded since startup — scheduler may not be running");
                    alertService.systemAlert("⚠️ Scheduler Watchdog: No scan recorded since startup. Scanner may be stuck.");
                    errorEventService.high("SchedulerWatchdog", "No scan recorded since startup — scheduler may be stuck");
                    alertSentThisSession = true;
                }
            } else {
                Duration gap = Duration.between(lastScan, Instant.now());
                if (gap.compareTo(MAX_SCAN_GAP) > 0 && !alertSentThisSession) {
                    log.warn("[SchedulerWatchdog] Last scan was {} minutes ago — attempting WebSocket reconnect", gap.toMinutes());
                    alertService.systemAlert(String.format(
                            "⚠️ Scheduler Watchdog: Last scan was %d minutes ago. Attempting WebSocket reconnect.",
                            gap.toMinutes()));
                    // Force reconnect
                    try {
                        if (!webSocketClient.isConnected()) {
                            webSocketClient.connect();
                            log.info("[SchedulerWatchdog] WebSocket reconnect triggered");
                        }
                    } catch (Exception e) {
                        log.error("[SchedulerWatchdog] Reconnect failed: {}", e.getMessage());
                        errorEventService.critical("SchedulerWatchdog", "WebSocket reconnect failed: " + e.getMessage(), e);
                    }
                    alertSentThisSession = true;
                }
            }
        }

        // ── Option chain health check ─────────────────────────────────────
        // If option chain has very few live options during market hours,
        // auto-refresh instruments and resubscribe WebSocket.
        checkOptionChainHealth();

        // ── WebSocket zombie check (secondary safety net) ─────────────────
        // The primary zombie detection is the 30s heartbeat in KiteWebSocketClient.
        // This is a backup in case the heartbeat thread dies or misses.
        checkWebSocketZombie();
    }

    /**
     * Detects when the option chain has too few live options and auto-recovers
     * by refreshing instruments and triggering WebSocket resubscription.
     */
    private void checkOptionChainHealth() {
        if (!liveInstrumentCache.isReady()) return;

        // Count options with live price data across all enabled underlyings
        long optionsWithLivePrice = 0;
        for (com.algo.trade.domain.IndexType idx : com.algo.trade.domain.IndexType.values()) {
            double spot = liveInstrumentCache.getFuturesPrice(idx);
            if (spot <= 0) continue;
            // Use instrument cache's nearest expiry (matches actual Zerodha contracts)
            var underlying = com.algo.trade.domain.UnderlyingSymbol.valueOf(idx.name());
            java.time.LocalDate expiry = instrumentCache.nearestExpiry(underlying, java.time.LocalDate.now())
                    .orElseGet(() -> expiryCalendar.getCurrentExpiry(idx));
            long count = liveInstrumentCache.getStrikeChain(idx, expiry).stream()
                    .filter(o -> o.getLastPrice() > 0 && o.getLastTickTimeMs() > 0)
                    .count();
            optionsWithLivePrice = Math.max(optionsWithLivePrice, count);
        }

        if (optionsWithLivePrice < LOW_OPTION_THRESHOLD) {
            lowOptionDataCount++;
            log.warn("[Watchdog] Low option data: only {} options with live prices (check {}/{})",
                    optionsWithLivePrice, lowOptionDataCount, LOW_OPTION_MAX_CHECKS);

            if (lowOptionDataCount >= LOW_OPTION_MAX_CHECKS) {
                log.warn("[Watchdog] Auto-recovering: refreshing instruments and resubscribing WebSocket");
                alertService.systemAlert("🔄 Watchdog: Only " + optionsWithLivePrice
                        + " options with live data — auto-refreshing instruments");
                try {
                    instrumentCache.refresh();
                    liveInstrumentCache.populate(instrumentCache.all());
                    log.info("[Watchdog] Instruments refreshed: {} total", instrumentCache.all().size());
                    // Trigger immediate WebSocket resubscription
                    kiteStartupLogin.resubscribeIfAtmMoved();
                    log.info("[Watchdog] WebSocket resubscription triggered");
                } catch (Exception e) {
                    log.error("[Watchdog] Instrument refresh failed: {}", e.getMessage());
                    errorEventService.critical("Watchdog", "Instrument auto-refresh failed: " + e.getMessage(), e);
                }
                lowOptionDataCount = 0;
            }
        } else {
            lowOptionDataCount = 0; // reset counter when data is healthy
        }
    }

    /**
     * Secondary zombie detection — backup for KiteWebSocketClient's own heartbeat.
     * If WebSocket reports connected but last tick is older than 2 minutes,
     * force-reconnect via the WebSocket client's forceReconnect() method.
     */
    private void checkWebSocketZombie() {
        if (!webSocketClient.isConnected()) return;
        java.time.Instant lastTick = webSocketClient.getLastTickTime();
        if (lastTick == null) return;
        long tickAgeSec = java.time.Duration.between(lastTick, java.time.Instant.now()).getSeconds();
        // 120s threshold — higher than the primary heartbeat's 60s to avoid stepping on it
        if (tickAgeSec > 120) {
            log.warn("[Watchdog] WebSocket zombie detected: last tick {}s ago — triggering force-reconnect", tickAgeSec);
            try {
                webSocketClient.forceReconnect("Watchdog backup: no ticks for " + tickAgeSec + "s");
            } catch (Exception e) {
                log.error("[Watchdog] Force-reconnect failed: {}", e.getMessage());
                errorEventService.high("Watchdog", "WebSocket force-reconnect failed: " + e.getMessage());
            }
        }
    }
}
