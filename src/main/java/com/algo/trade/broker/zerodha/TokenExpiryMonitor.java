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

    // Per-user (secondary) token health — the primary is covered by the checks above; a SECONDARY user's
    // dead token means the bot cannot place THEIR exits → overnight stranding. (2026-07-02)
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.multiuser.UserBrokerSessionManager userBrokerSessionManager;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.auth.UserBrokerConfigRepository userBrokerConfigRepository;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.persistence.TradeRepository tradeRepository;

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
        // Use the PRIMARY account's api_key (DB) so the re-login link is valid even when creds live
        // only in the DB and the env KITE_API_KEY is blank/stale.
        String loginUrl = properties.broker().loginUrl()
                + "?api_key=" + tokenStore.primaryApiKey().orElse(properties.broker().apiKey()) + "&v=3";
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
            // Token is valid but the live tick feed is down (zombie/closed/stuck in extended backoff).
            // The freshness gate blocks all entries while ticks are stale, so a silent log here means
            // the system sits idle for up to 30 min. Make this 30-min health check a RECOVERY action:
            // reconnect now. connect() is idempotent (no-op if already connected) and re-reads the
            // current token, so it safely picks up a feed that died without a fresh login.
            log.error("[TokenMonitor] MID-DAY: token valid but WebSocket DOWN — forcing reconnect to restore tick feed");
            telegramAlertService.systemAlert(
                    "🔄 Tick feed down (token still valid) — auto-reconnecting WebSocket to restore live data.");
            if (errorEventService != null) {
                errorEventService.high("TokenMonitor",
                        "WebSocket down with valid token — auto-reconnect triggered by mid-day health check", null);
            }
            try {
                webSocketClient.forceReconnect("mid-day health check: token valid but WS disconnected");
            } catch (Exception e) {
                log.error("[TokenMonitor] Auto-reconnect attempt failed: {}", e.getMessage());
            }
        }
    }

    /**
     * Mid-day PER-USER (secondary) token health check — offset 15 min from the primary check so they don't
     * collide. A secondary user's token can die mid-session (revoked / IP change) while the primary's is fine;
     * the bot then CANNOT place that user's exits, and it silently warn-fails until the 15:20 FailSafe — an
     * overnight-stranding risk. This proactively verifies each trading-enabled non-primary user's token and
     * alerts, escalating to CRITICAL when that user holds OPEN positions. (2026-07-02)
     */
    @Scheduled(cron = "0 15/30 9-15 * * MON-FRI", zone = "Asia/Kolkata")
    public void midDaySecondaryTokenHealthCheck() {
        if (userBrokerSessionManager == null || userBrokerConfigRepository == null) {
            return; // multi-user not wired in this context
        }
        java.util.List<com.algo.trade.auth.UserBrokerConfig> users;
        try {
            users = userBrokerConfigRepository.findByTradingEnabled(true);
        } catch (Exception e) {
            log.debug("[TokenMonitor] secondary token check skipped: {}", e.toString());
            return;
        }
        for (com.algo.trade.auth.UserBrokerConfig cfg : users) {
            if (cfg == null || cfg.getUserId() == null || cfg.isPrimaryAccount()) {
                continue; // primary is covered by midDayTokenHealthCheck
            }
            Long uid = cfg.getUserId();
            try {
                var v = userBrokerSessionManager.verifyToken(uid); // live /user/profile check
                if (v != null && v.valid()) {
                    continue;
                }
                String reason = v != null ? v.message() : "verification unavailable";
                int openPos = 0;
                if (tradeRepository != null) {
                    try {
                        openPos = tradeRepository.findByUserIdAndStatus(
                                uid, com.algo.trade.domain.TradeStatus.OPEN).size();
                    } catch (Exception ignore) { /* count best-effort */ }
                }
                if (openPos > 0) {
                    log.error("[TokenMonitor] MID-DAY: user {} token INVALID with {} OPEN position(s) — bot cannot place their exits! reason={}",
                            uid, openPos, reason);
                    telegramAlertService.systemAlert(String.format(
                            "🚨 CRITICAL: User %d Kite token invalid mid-session with %d OPEN position(s)!%n"
                            + "The bot CANNOT place their exits (%s).%n"
                            + "Re-login that user or square off manually NOW.", uid, openPos, reason));
                    if (errorEventService != null) {
                        errorEventService.critical("TokenMonitor", "User " + uid + " token invalid with "
                                + openPos + " open position(s) — exits at risk: " + reason);
                    }
                } else {
                    log.warn("[TokenMonitor] MID-DAY: user {} token invalid ({}) — no open positions", uid, reason);
                    telegramAlertService.systemAlert(String.format(
                            "⚠️ User %d Kite token invalid mid-session (%s). No open positions, but their "
                            + "new entries/exits are paused until re-login.", uid, reason));
                }
            } catch (Exception e) {
                log.warn("[TokenMonitor] secondary token check failed for user {}: {}", uid, e.toString());
            }
        }
    }
}
