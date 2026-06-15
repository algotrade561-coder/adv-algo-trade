package com.algo.trade.execution;

import com.algo.trade.broker.zerodha.KiteAccessTokenStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Auth Startup Gate — blocks order placement until authentication is confirmed.
 *
 * Prevents orders from being sent before the system has a valid Kite access token.
 * Checked by ExecutionEngine before every order placement.
 */
@Component
public class AuthStartupGate {

    private static final Logger log = LoggerFactory.getLogger(AuthStartupGate.class);

    private final KiteAccessTokenStore tokenStore;

    public AuthStartupGate(KiteAccessTokenStore tokenStore) {
        this.tokenStore = tokenStore;
    }

    /**
     * Check if the system is ready to place orders.
     * Returns true if access token is available and not expired.
     */
    public boolean isReady() {
        return tokenStore.accessToken().isPresent() && !tokenStore.accessToken().get().isBlank();
    }

    /**
     * Get the reason why the system is not ready.
     */
    public String getBlockReason() {
        if (tokenStore.accessToken().isEmpty() || tokenStore.accessToken().get().isBlank()) {
            return "No Kite access token — please complete authentication";
        }
        return null;
    }
}
