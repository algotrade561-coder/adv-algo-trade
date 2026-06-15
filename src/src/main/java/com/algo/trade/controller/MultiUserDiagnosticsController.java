package com.algo.trade.controller;

import com.algo.trade.auth.AppUser;
import com.algo.trade.auth.AppUserRepository;
import com.algo.trade.auth.UserBrokerConfig;
import com.algo.trade.auth.UserBrokerConfigRepository;
import com.algo.trade.broker.zerodha.KiteWebSocketClient;
import com.algo.trade.multiuser.UserBrokerSessionManager;
import com.algo.trade.multiuser.UserContext;
import com.algo.trade.multiuser.UserTradingState;
import com.algo.trade.multiuser.UserTradingStateManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.time.Instant;
import java.util.*;

/**
 * Multi-user diagnostics — verifies the full connection chain for all users.
 * Checks:
 * 1. User broker config (API key, secret, access token present)
 * 2. Source IP configured + reachable on this machine
 * 3. Token validity (not expired)
 * 4. WebSocket connection state
 * 5. Network interfaces available (ENIs)
 *
 * Call: GET /health/multi-user
 * Access: ADMIN/SUPERUSER only (shows sensitive config state)
 */
@RestController
public class MultiUserDiagnosticsController {

    private static final Logger log = LoggerFactory.getLogger(MultiUserDiagnosticsController.class);

    private final AppUserRepository userRepository;
    private final UserBrokerConfigRepository configRepository;
    private final UserBrokerSessionManager sessionManager;
    private final UserTradingStateManager stateManager;
    private final KiteWebSocketClient webSocketClient;

    @Value("${trading.broker.redirect-url:}")
    private String redirectUrl;

    @Value("${trading.multiuser.enabled:false}")
    private boolean multiUserEnabled;

    public MultiUserDiagnosticsController(AppUserRepository userRepository,
                                           UserBrokerConfigRepository configRepository,
                                           UserBrokerSessionManager sessionManager,
                                           UserTradingStateManager stateManager,
                                           KiteWebSocketClient webSocketClient) {
        this.userRepository = userRepository;
        this.configRepository = configRepository;
        this.sessionManager = sessionManager;
        this.stateManager = stateManager;
        this.webSocketClient = webSocketClient;
    }

    /**
     * Full multi-user connectivity diagnostic.
     * Checks every layer of the connection chain for all configured users.
     */
    @GetMapping("/health/multi-user")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERUSER')")
    public ResponseEntity<Map<String, Object>> multiUserHealth() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("timestamp", Instant.now().toString());
        result.put("multiUserEnabled", multiUserEnabled);
        result.put("redirectUrl", redirectUrl);

        // System-level checks
        result.put("webSocket", webSocketDiagnostics());
        result.put("networkInterfaces", networkInterfaceDiagnostics());

        // Per-user checks
        List<Map<String, Object>> users = new ArrayList<>();
        List<UserBrokerConfig> allConfigs = configRepository.findAll();

        for (UserBrokerConfig config : allConfigs) {
            users.add(userDiagnostics(config));
        }
        result.put("users", users);

        // Overall verdict
        long readyCount = users.stream()
                .filter(u -> Boolean.TRUE.equals(u.get("ready")))
                .count();
        result.put("usersReady", readyCount);
        result.put("usersTotal", users.size());
        result.put("verdict", readyCount == users.size() ? "ALL_CONNECTED" :
                readyCount > 0 ? "PARTIAL" : "NONE_CONNECTED");

        return ResponseEntity.ok(result);
    }

    private Map<String, Object> userDiagnostics(UserBrokerConfig config) {
        Map<String, Object> u = new LinkedHashMap<>();
        Long userId = config.getUserId();
        u.put("userId", userId);

        // Resolve email
        AppUser appUser = userRepository.findById(userId).orElse(null);
        u.put("email", appUser != null ? appUser.getEmail() : "unknown");

        // Config checks
        u.put("apiKeyConfigured", config.getApiKey() != null && !config.getApiKey().isBlank());
        u.put("apiKeyMasked", mask(config.getApiKey()));
        u.put("apiSecretConfigured", config.getApiSecret() != null && !config.getApiSecret().isBlank());
        u.put("accessTokenPresent", config.getAccessToken() != null && !config.getAccessToken().isBlank());
        u.put("brokerClientId", config.getBrokerClientId());
        u.put("tradingEnabled", config.isTradingEnabled());
        u.put("primaryAccount", config.isPrimaryAccount());

        // Token expiry check
        boolean tokenValid = config.hasValidToken();
        u.put("tokenValid", tokenValid);
        u.put("tokenExpiresAt", config.getTokenExpiresAt() != null ? config.getTokenExpiresAt().toString() : null);
        if (config.getTokenExpiresAt() != null) {
            long minutesUntilExpiry = java.time.Duration.between(Instant.now(), config.getTokenExpiresAt()).toMinutes();
            u.put("tokenExpiresInMinutes", minutesUntilExpiry);
            u.put("tokenExpired", minutesUntilExpiry <= 0);
        }

        // Source IP check
        String sourceIp = config.getSourceIp();
        u.put("sourceIp", sourceIp);
        if (sourceIp != null && !sourceIp.isBlank()) {
            u.put("sourceIpReachable", isLocalAddress(sourceIp.trim()));
        } else {
            u.put("sourceIpReachable", "N/A (using default interface)");
        }

        // Session manager check
        boolean authenticated = sessionManager.isAuthenticated(userId);
        u.put("sessionAuthenticated", authenticated);

        // Trading state
        UserTradingState state = stateManager.getState(userId);
        u.put("scannerRunning", state.isRunning());
        u.put("killSwitch", state.isKillSwitchEnabled());
        u.put("haltMode", state.getHaltMode().name());
        u.put("dailyApproved", state.isDailyApproved());
        u.put("entryAllowed", state.isEntryAllowed());

        // Overall readiness
        boolean ready = config.isTradingEnabled()
                && tokenValid
                && authenticated
                && config.getApiKey() != null && !config.getApiKey().isBlank();
        u.put("ready", ready);

        // Issues list
        List<String> issues = new ArrayList<>();
        if (config.getApiKey() == null || config.getApiKey().isBlank()) issues.add("API key not configured");
        if (config.getApiSecret() == null || config.getApiSecret().isBlank()) issues.add("API secret not configured");
        if (!tokenValid) issues.add("Access token missing or expired — re-login to Kite required");
        if (!config.isTradingEnabled()) issues.add("Trading disabled for this user");
        if (sourceIp != null && !sourceIp.isBlank() && !isLocalAddress(sourceIp.trim())) {
            issues.add("Source IP " + sourceIp + " is NOT available on this machine — check ENI attachment");
        }
        if (!authenticated) issues.add("Broker session not authenticated — complete Kite login");
        u.put("issues", issues);

        return u;
    }

    private Map<String, Object> webSocketDiagnostics() {
        Map<String, Object> ws = new LinkedHashMap<>();
        ws.put("connected", webSocketClient.isConnected());
        ws.put("lastTickTime", webSocketClient.getLastTickTime() != null ? webSocketClient.getLastTickTime().toString() : null);
        ws.put("lastConnectTime", webSocketClient.getLastConnectTime() != null ? webSocketClient.getLastConnectTime().toString() : null);
        ws.put("subscribedTokens", webSocketClient.getSubscribedTokenCount());

        if (webSocketClient.getLastTickTime() != null) {
            long tickAge = java.time.Duration.between(webSocketClient.getLastTickTime(), Instant.now()).getSeconds();
            ws.put("lastTickAgeSeconds", tickAge);
            ws.put("ticksFlowing", tickAge < 30);
        } else {
            ws.put("ticksFlowing", false);
        }
        return ws;
    }

    private List<Map<String, String>> networkInterfaceDiagnostics() {
        List<Map<String, String>> interfaces = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> nets = NetworkInterface.getNetworkInterfaces();
            while (nets.hasMoreElements()) {
                NetworkInterface ni = nets.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr.isLoopbackAddress() || addr.isLinkLocalAddress()) continue;
                    interfaces.add(Map.of(
                            "interface", ni.getName(),
                            "ip", addr.getHostAddress(),
                            "displayName", ni.getDisplayName() != null ? ni.getDisplayName() : ni.getName()
                    ));
                }
            }
        } catch (Exception e) {
            interfaces.add(Map.of("error", e.getMessage()));
        }
        return interfaces;
    }

    /**
     * Check if a given IP address exists on the local machine (i.e., is bound to an ENI).
     */
    private boolean isLocalAddress(String ip) {
        try {
            InetAddress target = InetAddress.getByName(ip);
            NetworkInterface ni = NetworkInterface.getByInetAddress(target);
            return ni != null && ni.isUp();
        } catch (Exception e) {
            return false;
        }
    }

    private String mask(String s) {
        if (s == null || s.isBlank()) return null;
        if (s.length() <= 4) return "****";
        return "****" + s.substring(s.length() - 4);
    }
}
