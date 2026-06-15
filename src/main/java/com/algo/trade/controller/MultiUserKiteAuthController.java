package com.algo.trade.controller;

import com.algo.trade.multiuser.UserBrokerSessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Handles Kite OAuth callback for specific users in multi-user mode.
 *
 * Each user has their own API key + secret. The OAuth flow is:
 * 1. GET /auth/kite/login/{userId} → redirects to Kite login page with that user's API key
 * 2. Kite redirects back to GET /auth/kite/callback/user/{userId}?request_token=XXX
 * 3. We exchange the request token for an access token using the user's API secret
 * 4. Store the access token in UserBrokerConfig
 *
 * This is separate from the main KiteAuthController which handles the default/primary user.
 */
@RestController
@RequestMapping("/auth/kite")
public class MultiUserKiteAuthController {

    private static final Logger log = LoggerFactory.getLogger(MultiUserKiteAuthController.class);

    private final UserBrokerSessionManager sessionManager;

    public MultiUserKiteAuthController(UserBrokerSessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    /**
     * Redirect a specific user to Kite login page (using their API key).
     * GET /auth/kite/login/{userId}
     */
    @GetMapping("/login/{userId}")
    public ResponseEntity<?> loginUser(@PathVariable Long userId) {
        String loginUrl = sessionManager.getLoginUrl(userId);
        if (loginUrl == null) {
            log.warn("[MultiUserAuth] No broker config found for userId={}", userId);
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "No broker configuration found for user " + userId));
        }
        log.info("[MultiUserAuth] Redirecting userId={} to Kite login", userId);
        return ResponseEntity.status(302)
                .header("Location", loginUrl)
                .build();
    }

    /**
     * Handle Kite OAuth callback for a specific user.
     * GET /auth/kite/callback/user/{userId}?request_token=XXX
     */
    @GetMapping("/callback/user/{userId}")
    public ResponseEntity<?> callbackUser(@PathVariable Long userId,
                                           @RequestParam("request_token") String requestToken) {
        log.info("[MultiUserAuth] OAuth callback received for userId={}, requestToken={}...",
                userId, requestToken.substring(0, Math.min(8, requestToken.length())));

        String accessToken = sessionManager.exchangeToken(userId, requestToken);

        if (accessToken != null) {
            log.info("[MultiUserAuth] Token exchange successful for userId={}", userId);
            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "message", "Authentication successful for user " + userId,
                    "userId", userId,
                    "authenticated", true
            ));
        } else {
            log.error("[MultiUserAuth] Token exchange failed for userId={}", userId);
            return ResponseEntity.status(401).body(Map.of(
                    "status", "error",
                    "message", "Token exchange failed for user " + userId,
                    "userId", userId,
                    "authenticated", false
            ));
        }
    }

    /**
     * Check authentication status for a specific user.
     * GET /auth/kite/status/{userId}
     */
    @GetMapping("/status/{userId}")
    public ResponseEntity<?> authStatus(@PathVariable Long userId) {
        boolean authenticated = sessionManager.isAuthenticated(userId);
        UserBrokerSessionManager.UserBrokerSession session = sessionManager.getSession(userId);
        return ResponseEntity.ok(Map.of(
                "userId", userId,
                "authenticated", authenticated,
                "ready", session.isReady(),
                "hasConfig", session.config != null
        ));
    }
}
