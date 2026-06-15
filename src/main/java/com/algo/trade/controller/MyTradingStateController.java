package com.algo.trade.controller;

import com.algo.trade.auth.AppUser;
import com.algo.trade.auth.AppUserRepository;
import com.algo.trade.auth.UserBrokerConfig;
import com.algo.trade.auth.UserBrokerConfigRepository;
import com.algo.trade.multiuser.UserBrokerSessionManager;
import com.algo.trade.multiuser.UserContext;
import com.algo.trade.multiuser.UserTradingState;
import com.algo.trade.multiuser.UserTradingStateManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-user trading state endpoints — returns the CURRENT user's own
 * scanner/halt/kill-switch/approval state and allows them to control
 * their own trading independently of other users.
 *
 * These endpoints complement the global TradingControlController:
 * - Global controls (scanner, halt, WS) affect the shared infrastructure
 * - Per-user controls affect only THIS user's order execution gate
 */
@RestController
@RequestMapping("/me/trading")
public class MyTradingStateController {

    private static final Logger log = LoggerFactory.getLogger(MyTradingStateController.class);

    private final AppUserRepository userRepository;
    private final UserBrokerConfigRepository configRepository;
    private final UserTradingStateManager stateManager;
    private final UserBrokerSessionManager sessionManager;

    /** Optional — present when Zerodha broker client is active. Used for circuit breaker reset. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.broker.zerodha.ZerodhaBrokerClient zerodhaBrokerClient;

    @Value("${auth.google.enabled:false}")
    private String googleAuthEnabledRaw;

    public MyTradingStateController(AppUserRepository userRepository,
                                     UserBrokerConfigRepository configRepository,
                                     UserTradingStateManager stateManager,
                                     UserBrokerSessionManager sessionManager) {
        this.userRepository = userRepository;
        this.configRepository = configRepository;
        this.stateManager = stateManager;
        this.sessionManager = sessionManager;
    }

    /**
     * Get the current user's trading state — scanner, halt mode, kill switch,
     * daily approval, and broker session status.
     */
    @GetMapping
    public ResponseEntity<?> getState(Authentication auth) {
        AppUser u = currentUser(auth);
        if (u == null) return ResponseEntity.status(401).body(Map.of("error", "not authenticated"));

        UserTradingState state = stateManager.getState(u.getId());
        UserBrokerConfig config = configRepository.findByUserId(u.getId()).orElse(null);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", u.getId());
        result.put("email", u.getEmail());

        // Per-user trading state
        result.put("running", state.isRunning());
        result.put("killSwitch", state.isKillSwitchEnabled());
        result.put("haltMode", state.getHaltMode().name());
        result.put("dailyApproved", state.isDailyApproved());
        result.put("entryAllowed", state.isEntryAllowed());
        result.put("exitAllowed", state.isExitAllowed());
        result.put("schedulerEnabled", state.isSchedulerEnabled());
        result.put("extensionsUsedToday", state.getExtensionsUsedToday());
        result.put("dailyLossExtension", state.getDailyLossExtension());
        result.put("lastScanAt", state.getLastScanAt());

        // Broker session status
        result.put("brokerConfigured", config != null && config.getApiKey() != null);
        result.put("brokerAuthenticated", sessionManager.isAuthenticated(u.getId()));
        result.put("tradingEnabled", config != null && config.isTradingEnabled());
        result.put("sourceIp", config != null ? config.getSourceIp() : null);
        result.put("tokenExpiresAt", config != null ? config.getTokenExpiresAt() : null);
        result.put("primaryAccount", config != null && config.isPrimaryAccount());

        return ResponseEntity.ok(result);
    }

    @PostMapping("/start")
    public ResponseEntity<?> start(Authentication auth) {
        AppUser u = currentUser(auth);
        if (u == null) return ResponseEntity.status(401).body(Map.of("error", "not authenticated"));
        stateManager.getState(u.getId()).start();
        log.info("[MyTrading] user={} started their scanner", u.getEmail());
        return getState(auth);
    }

    @PostMapping("/stop")
    public ResponseEntity<?> stop(Authentication auth) {
        AppUser u = currentUser(auth);
        if (u == null) return ResponseEntity.status(401).body(Map.of("error", "not authenticated"));
        stateManager.getState(u.getId()).stop();
        log.info("[MyTrading] user={} stopped their scanner", u.getEmail());
        return getState(auth);
    }

    @PostMapping("/kill-switch")
    public ResponseEntity<?> killSwitch(@RequestBody Map<String, Boolean> body, Authentication auth) {
        AppUser u = currentUser(auth);
        if (u == null) return ResponseEntity.status(401).body(Map.of("error", "not authenticated"));
        boolean enabled = body.getOrDefault("enabled", true);
        if (enabled) {
            stateManager.getState(u.getId()).enableKillSwitch();
        } else {
            stateManager.getState(u.getId()).clearKillSwitch();
        }
        log.info("[MyTrading] user={} kill switch={}", u.getEmail(), enabled);
        return getState(auth);
    }

    @PostMapping("/halt")
    public ResponseEntity<?> halt(@RequestBody Map<String, String> body, Authentication auth) {
        AppUser u = currentUser(auth);
        if (u == null) return ResponseEntity.status(401).body(Map.of("error", "not authenticated"));
        String mode = body.getOrDefault("mode", "SOFT");
        String reason = body.getOrDefault("reason", "Manual halt from UI");
        if ("HARD".equalsIgnoreCase(mode)) {
            stateManager.getState(u.getId()).hardHalt(reason);
        } else {
            stateManager.getState(u.getId()).softHalt(reason);
        }
        log.info("[MyTrading] user={} halted mode={}", u.getEmail(), mode);
        return getState(auth);
    }

    @PostMapping("/resume")
    public ResponseEntity<?> resume(Authentication auth) {
        AppUser u = currentUser(auth);
        if (u == null) return ResponseEntity.status(401).body(Map.of("error", "not authenticated"));
        stateManager.getState(u.getId()).resumeFromHalt();
        log.info("[MyTrading] user={} resumed from halt", u.getEmail());
        return getState(auth);
    }

    @PostMapping("/approve")
    public ResponseEntity<?> approveToday(Authentication auth) {
        AppUser u = currentUser(auth);
        if (u == null) return ResponseEntity.status(401).body(Map.of("error", "not authenticated"));
        stateManager.getState(u.getId()).approveToday();
        log.info("[MyTrading] user={} approved trading for today", u.getEmail());
        return getState(auth);
    }

    @PostMapping("/revoke-approval")
    public ResponseEntity<?> revokeApproval(Authentication auth) {
        AppUser u = currentUser(auth);
        if (u == null) return ResponseEntity.status(401).body(Map.of("error", "not authenticated"));
        stateManager.getState(u.getId()).revokeApproval();
        log.info("[MyTrading] user={} revoked daily approval", u.getEmail());
        return getState(auth);
    }

    @PostMapping("/extend-limit")
    public ResponseEntity<?> extendLimit(Authentication auth) {
        AppUser u = currentUser(auth);
        if (u == null) return ResponseEntity.status(401).body(Map.of("error", "not authenticated"));
        UserTradingState state = stateManager.getState(u.getId());
        if (state.getExtensionsUsedToday() >= 2) {
            return ResponseEntity.badRequest().body(Map.of("error", "Maximum 2 extensions per day"));
        }
        UserBrokerConfig config = configRepository.findByUserId(u.getId()).orElse(null);
        double extendAmount = config != null ? config.getDailyMaxLoss() * 0.5 : 2500;
        state.extendDailyLimit(extendAmount);
        log.info("[MyTrading] user={} extended daily limit by ₹{}", u.getEmail(), extendAmount);
        return getState(auth);
    }

    /**
     * Reset the per-user circuit breaker — use when "Broker API circuit breaker open" error appears.
     * Typically needed after fixing a token/IP issue that caused repeated 5xx failures.
     */
    @PostMapping("/reset-circuit-breaker")
    public ResponseEntity<?> resetCircuitBreaker(Authentication auth) {
        AppUser u = currentUser(auth);
        if (u == null) return ResponseEntity.status(401).body(Map.of("error", "not authenticated"));
        if (zerodhaBrokerClient != null) {
            zerodhaBrokerClient.resetCircuitBreakerForUser(u.getId());
            log.info("[MyTrading] user={} circuit breaker reset", u.getEmail());
        }
        return getState(auth);
    }

    private AppUser currentUser(Authentication auth) {
        if (!"true".equalsIgnoreCase(googleAuthEnabledRaw)) {
            return userRepository.findByEmail("local").orElseGet(() -> {
                AppUser u = new AppUser("local", "SUPERUSER");
                u.setName("Local User");
                return userRepository.save(u);
            });
        }
        if (auth == null || auth.getPrincipal() == null) return null;
        Object p = auth.getPrincipal();
        String email = null;
        if (p instanceof org.springframework.security.oauth2.core.user.OAuth2User o) {
            email = o.getAttribute("email");
        }
        if (email == null) return null;
        return userRepository.findByEmail(email.toLowerCase()).orElse(null);
    }
}
