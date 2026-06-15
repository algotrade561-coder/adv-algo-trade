package com.algo.trade.auth;

import com.algo.trade.broker.zerodha.KiteAccessTokenStore;
import com.algo.trade.broker.zerodha.KiteLoginSuccessEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Reacts to PrimaryAccountChangedEvent — published by MyBrokerController when the
 * user flagged primaryAccount=true saves new Kite credentials. We:
 *   1. Push their access token into KiteAccessTokenStore (so REST + WS use it).
 *   2. Re-publish KiteLoginSuccessEvent so KiteStartupLogin reconnects the
 *      WebSocket feed against the new credentials — market analysis switches
 *      over without restarting the app.
 *
 * Fallback: if no primary account is configured, market analysis continues to
 * use whatever KiteCredentialResolver returns (data/trading-secrets.properties).
 */
@Component
public class PrimaryAccountSwapListener {

    private static final Logger log = LoggerFactory.getLogger(PrimaryAccountSwapListener.class);

    private final UserBrokerConfigRepository configRepository;
    private final KiteAccessTokenStore tokenStore;
    private final ApplicationEventPublisher events;

    public PrimaryAccountSwapListener(UserBrokerConfigRepository configRepository,
                                       KiteAccessTokenStore tokenStore,
                                       ApplicationEventPublisher events) {
        this.configRepository = configRepository;
        this.tokenStore = tokenStore;
        this.events = events;
    }

    /**
     * Cold-boot path: if a primary user is already configured in the DB with a valid
     * access token, push that token into KiteAccessTokenStore BEFORE the WS spins up.
     * This makes the market-data session DB-driven from the very first boot, not just
     * after a runtime change.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onAppReady() {
        java.util.Optional<UserBrokerConfig> primary = configRepository.findAll().stream()
                .filter(UserBrokerConfig::isPrimaryAccount)
                .findFirst();

        if (primary.isEmpty()) {
            // Loud, actionable boot warning. Without a primary account NOTHING pushes a token
            // into the shared KiteAccessTokenStore, so the shared market-data WebSocket never
            // connects and the dashboard shows "WS disconnected" / "no access token" all day.
            log.warn("==================================================================");
            log.warn("[PrimaryAccount] ⚠ NO PRIMARY ACCOUNT CONFIGURED.");
            log.warn("[PrimaryAccount] The shared market-data WebSocket will NOT connect and");
            log.warn("[PrimaryAccount] strategies will run blind. Fix: Admin → Users → Set");
            log.warn("[PrimaryAccount] Primary on the account whose Kite login should drive the");
            log.warn("[PrimaryAccount] market-data feed (then ensure it has a valid token).");
            log.warn("==================================================================");
            return;
        }

        UserBrokerConfig c = primary.get();
        boolean missingCreds = c.getApiKey() == null || c.getApiKey().isBlank()
                || c.getApiSecret() == null || c.getApiSecret().isBlank();
        if (missingCreds) {
            log.warn("[PrimaryAccount] ⚠ Primary account userId={} is missing API key/secret — "
                    + "the shared market-data WebSocket cannot authenticate. Complete its broker "
                    + "config (Settings → My Broker).", c.getUserId());
        }

        if (c.getAccessToken() != null && !c.getAccessToken().isBlank()) {
            tokenStore.update(c.getAccessToken(), null, String.valueOf(c.getUserId()));
            log.info("[PrimaryAccount] Boot: pushed DB access token for userId={} into KiteAccessTokenStore", c.getUserId());
            events.publishEvent(new KiteLoginSuccessEvent(String.valueOf(c.getUserId())));
        } else {
            log.warn("[PrimaryAccount] ⚠ Boot: primary account userId={} has NO access token yet — "
                    + "the shared market-data WebSocket will not connect until this account completes "
                    + "today's Kite login. (Falling back to trading-secrets.properties if present.)", c.getUserId());
        }
    }

    @EventListener
    public void onPrimaryChanged(MyBrokerController.PrimaryAccountChangedEvent event) {
        UserBrokerConfig c = configRepository.findByUserId(event.userId()).orElse(null);
        if (c == null || !c.isPrimaryAccount()) return;

        log.info("[PrimaryAccount] userId={} primary credentials updated — hot-swapping market data session", event.userId());

        // If the primary user already has a fresh Kite access token, push it now.
        if (c.getAccessToken() != null && !c.getAccessToken().isBlank()) {
            tokenStore.update(c.getAccessToken(), null, String.valueOf(c.getUserId()));
            log.info("[PrimaryAccount] Access token pushed into KiteAccessTokenStore — reconnecting WS feed");
            events.publishEvent(new KiteLoginSuccessEvent(String.valueOf(c.getUserId())));
        } else {
            // No token yet — primary user needs to complete Kite OAuth.
            // KiteCredentialResolver now returns their apiKey/apiSecret, so the
            // login URL will be theirs. WS will start once they finish OAuth.
            log.info("[PrimaryAccount] No access token yet — Kite login flow will use the primary user's API key");
        }
    }
}
