package com.algo.trade.broker.zerodha;

import com.algo.trade.notification.TelegramAlertService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Monitors Kite access token validity.
 * Kite tokens expire at midnight IST daily.
 *
 * - 8:00 AM: checks if token is present, sends login URL if missing
 * - 8:45 AM: reminder if still not authenticated
 * - Midnight: clears expired token state
 */
@Component
public class TokenExpiryMonitor {

    private static final Logger log = LoggerFactory.getLogger(TokenExpiryMonitor.class);

    private final KiteAccessTokenStore tokenStore;
    private final KiteWebSocketClient webSocketClient;
    private final TelegramAlertService telegramAlertService;
    private final com.algo.trade.config.TradingProperties properties;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.monitoring.ErrorEventService errorEventService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.monitoring.SchedulerRegistry schedulerRegistry;

    public TokenExpiryMonitor(KiteAccessTokenStore tokenStore,
                               KiteWebSocketClient webSocketClient,
                               TelegramAlertService telegramAlertService,
                               com.algo.trade.config.TradingProperties properties) {
        this.tokenStore = tokenStore;
        this.webSocketClient = webSocketClient;
        this.telegramAlertService = telegramAlertService;
        this.properties = properties;
    }

    /** 8:00 AM — check token before market open. */
    @Scheduled(cron = "0 0 8 * * MON-FRI", zone = "Asia/Kolkata")
    public void checkTokenAtOpen() {
        if (schedulerRegistry != null) schedulerRegistry.recordRun("tokenMonitor");
        if (!tokenStore.authenticated()) {
            String loginUrl = properties.broker().loginUrl()
                    + "?api_key=" + properties.broker().apiKey() + "&v=3";
            telegramAlertService.systemAlert(
                    "🔑 Kite access token expired or missing!\n"
                    + "Re-authenticate now:\n" + loginUrl);
            log.warn("[TokenMonitor] Access token missing at market open");
            if (errorEventService != null) errorEventService.high("TokenMonitor", "Access token missing at market open — trading blocked");
        } else {
            log.info("[TokenMonitor] Access token present at 8:00 AM");
        }
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
}
