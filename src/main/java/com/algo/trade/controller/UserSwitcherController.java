package com.algo.trade.controller;

import com.algo.trade.auth.AppUser;
import com.algo.trade.auth.AppUserRepository;
import com.algo.trade.auth.UserBrokerConfig;
import com.algo.trade.auth.UserBrokerConfigRepository;
import com.algo.trade.multiuser.UserBrokerSessionManager;
import com.algo.trade.multiuser.UserContext;
import com.algo.trade.multiuser.UserTradingState;
import com.algo.trade.multiuser.UserTradingStateManager;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Provides user switching and info endpoints for the Angular UI.
 *
 * Used by the Angular dashboard to:
 * - Show current user info in the header
 * - Display a user dropdown (admin only)
 * - Switch which user's data is being viewed
 *
 * Endpoints:
 * - GET /user/current — current user info (id, email, name, broker status)
 * - GET /user/all — list all users (for admin dropdown)
 * - POST /user/switch/{userId} — switch session to view another user's data
 */
@RestController
@RequestMapping("/user")
public class UserSwitcherController {

    private static final Logger log = LoggerFactory.getLogger(UserSwitcherController.class);

    private final AppUserRepository userRepository;
    private final UserBrokerConfigRepository brokerConfigRepository;
    private final UserBrokerSessionManager sessionManager;
    private final UserTradingStateManager stateManager;
    private final com.algo.trade.broker.zerodha.KiteWebSocketClient webSocketClient;

    public UserSwitcherController(AppUserRepository userRepository,
                                   UserBrokerConfigRepository brokerConfigRepository,
                                   UserBrokerSessionManager sessionManager,
                                   UserTradingStateManager stateManager,
                                   com.algo.trade.broker.zerodha.KiteWebSocketClient webSocketClient) {
        this.userRepository = userRepository;
        this.brokerConfigRepository = brokerConfigRepository;
        this.sessionManager = sessionManager;
        this.stateManager = stateManager;
        this.webSocketClient = webSocketClient;
    }

    /**
     * GET /user/current — returns current user info (from UserContext).
     */
    @GetMapping("/current")
    public ResponseEntity<?> getCurrentUser() {
        Long userId = UserContext.getUserId();
        AppUser user = userRepository.findById(userId).orElse(null);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("userId", userId);
        response.put("email", user != null ? user.getEmail() : null);
        response.put("name", user != null ? user.getName() : null);
        response.put("role", user != null ? user.getRole() : "USER");

        // Broker status
        boolean authenticated = sessionManager.isAuthenticated(userId);
        response.put("brokerAuthenticated", authenticated);

        // Trading state
        UserTradingState state = stateManager.getState(userId);
        response.put("scannerRunning", state.isRunning());
        response.put("killSwitch", state.isKillSwitchEnabled());
        response.put("haltMode", state.getHaltMode().name());
        response.put("entryAllowed", state.isEntryAllowed());

        return ResponseEntity.ok(response);
    }

    /**
     * GET /user/all — returns list of all users with their status.
     * For admin dropdown in Angular UI.
     */
    @GetMapping("/all")
    public ResponseEntity<?> getAllUsers() {
        List<AppUser> users = userRepository.findAll();
        List<Map<String, Object>> result = new ArrayList<>();

        for (AppUser user : users) {
            Map<String, Object> userInfo = new LinkedHashMap<>();
            userInfo.put("userId", user.getId());
            userInfo.put("email", user.getEmail());
            userInfo.put("name", user.getName());
            userInfo.put("role", user.getRole());

            // Broker config
            Optional<UserBrokerConfig> config = brokerConfigRepository.findByUserId(user.getId());
            userInfo.put("hasBrokerConfig", config.isPresent());
            userInfo.put("tradingEnabled", config.map(UserBrokerConfig::isTradingEnabled).orElse(false));
            userInfo.put("brokerAuthenticated", sessionManager.isAuthenticated(user.getId()));
            // Market data is a SHARED feed (the primary KiteWebSocketClient), so per-user WS
            // status reflects that single live socket — not the legacy UserBrokerConfig.wsConnected
            // flag, which is only ever written by the unused UserWebSocketManager stub and so was
            // always false. This keeps the per-user view consistent with the dashboard/diagnostics.
            userInfo.put("wsConnected", webSocketClient.isConnected());

            // Trading state
            UserTradingState state = stateManager.getState(user.getId());
            userInfo.put("scannerRunning", state.isRunning());
            userInfo.put("entryAllowed", state.isEntryAllowed());

            result.add(userInfo);
        }

        return ResponseEntity.ok(result);
    }

    /**
     * POST /user/switch/{userId} — switch the session to view another user's data.
     * Admin only — sets the viewed userId in the HTTP session.
     */
    @PostMapping("/switch/{userId}")
    public ResponseEntity<?> switchUser(@PathVariable Long userId, HttpSession session) {
        // Verify the target user exists
        Optional<AppUser> targetUser = userRepository.findById(userId);
        if (targetUser.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "User not found: " + userId));
        }

        // Verify current user is admin
        Long currentUserId = UserContext.getUserId();
        AppUser currentUser = userRepository.findById(currentUserId).orElse(null);
        if (currentUser == null || !"ADMIN".equalsIgnoreCase(currentUser.getRole())) {
            return ResponseEntity.status(403)
                    .body(Map.of("error", "Only admins can switch users"));
        }

        // Store the switched user in HTTP session
        session.setAttribute("viewAsUserId", userId);
        UserContext.setUserId(userId);

        log.info("[UserSwitch] Admin {} switched view to userId={} ({})",
                currentUser.getEmail(), userId, targetUser.get().getEmail());

        return ResponseEntity.ok(Map.of(
                "status", "switched",
                "viewAsUserId", userId,
                "email", targetUser.get().getEmail(),
                "name", targetUser.get().getName()
        ));
    }
}
