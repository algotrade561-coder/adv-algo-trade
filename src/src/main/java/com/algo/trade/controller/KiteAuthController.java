package com.algo.trade.controller;

import com.algo.trade.broker.zerodha.KiteAuthService;
import com.algo.trade.broker.zerodha.KiteLoginResult;

import java.io.IOException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class KiteAuthController {

    private static final Logger log = LoggerFactory.getLogger(KiteAuthController.class);

    private final KiteAuthService kiteAuthService;

    /** Optional — present when multi-user wiring is active. Used to mirror the
     *  captured token into the PRIMARY user's DB row (see callback()). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.auth.PrimaryAccountSelector primaryAccountSelector;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.multiuser.UserBrokerSessionManager userBrokerSessionManager;

    public KiteAuthController(KiteAuthService kiteAuthService) {
        this.kiteAuthService = kiteAuthService;
    }

    @GetMapping("/auth/kite/login")
    public Map<String, Object> login() throws IOException {
        log.info("Kite login endpoint called");
        if (!kiteAuthService.apiKeyConfigured()) {
            log.info("Kite login endpoint returning first-run setup details");
            return kiteAuthService.firstRunSetup();
        }
        Map<String, Object> response = kiteAuthService.initiateLoginFlow();
        log.info("Kite login endpoint initiated login flow");
        return response;
    }

    @GetMapping("/auth/kite/session")
    public Map<String, Object> session() {
        log.info("Kite session endpoint called");
        if (!kiteAuthService.apiKeyConfigured()) {
            log.info("Kite session endpoint returning first-run setup details");
            return kiteAuthService.firstRunSessionSetup();
        }
        KiteLoginResult result = kiteAuthService.currentSession();
        log.info("Kite session endpoint completed: authenticated={}, userId={}", result.success(), result.userId());
        return Map.of(
                "authenticated", result.success(),
                "userId", result.userId() == null ? "" : result.userId(),
                "authenticatedAt", result.authenticatedAt(),
                "diagnostics", kiteAuthService.diagnostics()
        );
    }

    @GetMapping(value = "/auth/kite/callback", produces = MediaType.TEXT_PLAIN_VALUE)
    public String callback(@RequestParam(name = "status", required = false) String status,
                           @RequestParam(name = "request_token", required = false) String requestToken) {
        log.info("Kite callback received: status={}, requestTokenPresent={}",
                status, requestToken != null && !requestToken.isBlank());
        if (!"success".equalsIgnoreCase(status) || requestToken == null || requestToken.isBlank()) {
            log.warn("Kite callback rejected: status={}, requestTokenPresent={}",
                    status, requestToken != null && !requestToken.isBlank());
            return "Kite login failed. Close this tab and check the application logs.";
        }
        KiteLoginResult result = kiteAuthService.exchangeRequestToken(requestToken);
        log.info("Kite callback completed: userId={}", result.userId());
        mirrorTokenToPrimaryUser(requestToken, result);
        return "Kite login completed for user " + result.userId()
                + ". Access token captured for this run and persisted to local token storage. You can close this tab.";
    }

    /**
     * Bridge: when a PRIMARY account is configured, KiteCredentialResolver uses the
     * primary user's apiKey/apiSecret for this legacy flow — so the captured access
     * token belongs to the primary user. Mirror it into their UserBrokerConfig row,
     * otherwise the My Broker / Kite Auth pages keep showing "Token: missing" even
     * though the login succeeded (token only landed in the local file store).
     */
    private void mirrorTokenToPrimaryUser(String requestToken, KiteLoginResult result) {
        try {
            if (primaryAccountSelector == null || userBrokerSessionManager == null) return;
            if (result == null || !result.success() || result.accessToken() == null) return;
            var primaryUserId = primaryAccountSelector.userId();
            if (primaryUserId.isEmpty()) return;
            // result.userId() is the Zerodha login id (e.g. SX0602) — store as broker client id
            userBrokerSessionManager.storeAccessToken(primaryUserId.get(), result.accessToken(), requestToken, result.userId());
            log.info("Kite callback: access token mirrored to PRIMARY user's DB row (userId={})", primaryUserId.get());
        } catch (Exception e) {
            log.warn("Kite callback: failed to mirror token to primary user's DB row: {}", e.getMessage());
        }
    }
}
