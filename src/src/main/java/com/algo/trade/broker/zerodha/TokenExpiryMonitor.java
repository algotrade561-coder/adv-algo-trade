package com.algo.trade.broker.zerodha;

import com.algo.trade.notification.TelegramAlertService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Monitors Kite access token validity.
 * Kite tokens are flushed by Zerodha daily (around 6 AM IST) — a fresh login is required each trading day.
 *
 * - 8:00 AM: validates token against Zerodha API, clears stale cached tokens
 * - 8:45 AM: reminder if still not authenticated
 * - Mid-day: periodic health check during market hours
 */
@Component
public class TokenExpiryMonitor {

    private static final Logger log = LoggerFactory.getLogger(TokenExpiryMonitor.class);

    private final KiteAccessTokenStore tokenStore;
    private final KiteWebSocketClient webSocketClient;
    private final TelegramAlertService telegramAlertService;
    private final com.algo.trade.config.TradingProperties properties;
    private final KiteAuthService kiteAuthService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.monitoring.ErrorEventService errorEventService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.monitoring.SchedulerRegistry schedulerRegistry;

    public TokenExpiryMonitor(KiteAccessTokenStore tokenStore,
                               KiteWebSocketClient webSocketClient,
                               TelegramAlertService telegramAlertService,
                               com.algo.trade.config.TradingProperties properties,
                               KiteAuthService kiteAuthService) {
        this.tokenStore = tokenStore;
        this.webSocketClient = webSocketClient;
        this.telegramAlertService = telegramAlertService;
        this.properties = properties;
        this.kiteAuthService = kiteAuthService;
    }

    /** 8:00 AM — validate token against Zerodha API before market open. */
    @Scheduled(cron = "0 0 8 * * MON-FRI", zone = "Asia/Kolkata")
    public void checkTokenAtOpen() {
        if (schedulerRegistry != null) schedulerRegistry.recordRun("tokenMonitor");
        if (!tokenStore.authenticated()) {
            sendLoginAlert("Access token missing at market open");
            return;
        }
        // Token is present in memory/file — but is it actually valid on Zerodha's side?
        // A cached token from yesterday will pass authenticated() but fail on any API call.
        log.info("[TokenMonitor] Access token present — validating against Zerodha API...");
        boolean valid = kiteAuthService.validateCurrentSession();
        if (!valid) {
            // validateCurrentSession() already calls tokenStore.clear() for the primary user,
            // so the stale cached token is wiped. Alert the user to re-login.
            sendLoginAlert("Cached access token is INVALID (expired overnight). Token cleared");
        } else {
            log.info("[TokenMonitor] Access token validated successfully at 8:00 AM");
        }
    }

    private void sendLoginAlert(String reason) {
        String loginUrl = properties.broker().loginUrl()
                + "?api_key=" + properties.broker().apiKey() + "&v=3";
        telegramAlertService.systemAlert(
                "🔑 Kite access token expired or missing!\n"
                + reason + "\n"
                + "Re-authenticate now:\n" + loginUrl);
        log.warn("[TokenMonitor] {}", reason);
        if (errorEventService != null) errorEventService.high("TokenMonitor", reason + " — trading blocked");
    }

    /** 8:45 AM — reminder if still not authenticated. */
    @Scheduled(cron = "0 45 8 * * MON-FRI", zone = "Asia/Kolkata")
    public void reminderIfNotAuthenticated() {
        if (!tokenStore.authenticated() || !webSocketClient.isConnected()) {
            telegramAlertService.systemAlert(
                    "⚠️ Still not authenticated! Market opens in 30 minutes.\n"
                    + "WebSocket connected: " + webSocketClient.isConnected());
            log.warn("[TokenMonitor] 8:45 AM reminder — still not authenticated");
            if (errorEventService != null) errorEventService.high("TokenMonitor", "8:45 AM — still not authenticated, market opens in 30 min");
        }
    }

    /**
     * Mid-day token health check (every 30 min during market hours).
     * Validates token against Zerodha API to detect revocation mid-session.
     * Note: Programmatic re-auth not possible until Kite provides refresh tokens.
     */
    @Scheduled(cron = "0 0/30 9-15 * * MON-FRI", zone = "Asia/Kolkata")
    public void midDayTokenHealthCheck() {
        if (!tokenStore.authenticated()) {
            log.error("[TokenMonitor] MID-DAY: Access token lost! Trading is broken.");
            telegramAlertService.systemAlert(
                    "🚨 CRITICAL: Kite access token lost mid-session!\n"
                    + "All exits are client-side — positions at risk.\n"
                    + "Re-authenticate immediately.");
            if (errorEventService != null) {
                errorEventService.critical("TokenMonitor",
                        "Access token lost mid-session — manual re-auth required");
            }
        } else if (!kiteAuthService.validateCurrentSession()) {
            // Token was present but Zerodha rejected it (revoked, IP change, etc.)
            log.error("[TokenMonitor] MID-DAY: Cached token INVALID — Zerodha rejected it!");
            telegramAlertService.systemAlert(
                    "🚨 CRITICAL: Kite access token revoked mid-session!\n"
                    + "Token was cached but Zerodha returned 403.\n"
                    + "Re-authenticate immediately.");
            if (errorEventService != null) {
                errorEventService.critical("TokenMonitor",
                        "Cached token revoked mid-session — cleared, manual re-auth required");
            }
        } else if (!webSocketClient.isConnected()) {
            log.warn("[TokenMonitor] MID-DAY: WebSocket disconnected but token valid");
        }
    }
}
