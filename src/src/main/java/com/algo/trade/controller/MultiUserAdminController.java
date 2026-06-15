package com.algo.trade.controller;

import com.algo.trade.auth.AppUser;
import com.algo.trade.auth.AppUserRepository;
import com.algo.trade.auth.UserBrokerConfig;
import com.algo.trade.auth.UserBrokerConfigRepository;
import com.algo.trade.multiuser.MultiUserStrategyLoop;
import com.algo.trade.multiuser.UserBrokerSessionManager;
import com.algo.trade.multiuser.UserTradingState;
import com.algo.trade.multiuser.UserTradingStateManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Multi-User Admin Controller — manages users, broker configs, and trading state.
 *
 * Endpoints:
 *   GET  /admin/users          — list all users with status
 *   POST /admin/users          — add new user (email + Kite credentials)
 *   PUT  /admin/users/{id}/config — update user's broker config
 *   POST /admin/users/{id}/start  — start trading for a user
 *   POST /admin/users/{id}/stop   — stop trading for a user
 *   POST /admin/users/{id}/halt   — emergency halt for a user
 *   POST /admin/halt-all          — halt ALL users
 *   POST /admin/resume-all        — resume ALL users
 *   GET  /admin/users/{id}/login-url — get Kite login URL for a user
 */
@RestController
@RequestMapping("/admin")
public class MultiUserAdminController {

    private static final Logger log = LoggerFactory.getLogger(MultiUserAdminController.class);

    private final AppUserRepository userRepository;
    private final UserBrokerConfigRepository brokerConfigRepository;
    private final UserBrokerSessionManager sessionManager;
    private final UserTradingStateManager stateManager;
    private final MultiUserStrategyLoop strategyLoop;

    public MultiUserAdminController(AppUserRepository userRepository,
                                     UserBrokerConfigRepository brokerConfigRepository,
                                     UserBrokerSessionManager sessionManager,
                                     UserTradingStateManager stateManager,
                                     MultiUserStrategyLoop strategyLoop) {
        this.userRepository = userRepository;
        this.brokerConfigRepository = brokerConfigRepository;
        this.sessionManager = sessionManager;
        this.stateManager = stateManager;
        this.strategyLoop = strategyLoop;
    }

    /**
     * List all users with their trading status.
     */
    @GetMapping("/users")
    public ResponseEntity<?> listUsers() {
        List<Map<String, Object>> users = userRepository.findAll().stream().map(user -> {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("id", user.getId());
            info.put("email", user.getEmail());
            info.put("name", user.getName());
            info.put("role", user.getRole());
            info.put("lastLogin", user.getLastLoginAt());

            var brokerConfig = brokerConfigRepository.findByUserId(user.getId());
            if (brokerConfig.isPresent()) {
                UserBrokerConfig bc = brokerConfig.get();
                info.put("apiKey", maskApiKey(bc.getApiKey()));
                info.put("authenticated", bc.hasValidToken());
                info.put("tradingEnabled", bc.isTradingEnabled());
                info.put("totalCapital", bc.getTotalCapital());
                info.put("dailyMaxLoss", bc.getDailyMaxLoss());

                UserTradingState state = stateManager.getState(user.getId());
                info.put("scannerRunning", state.isRunning());
                info.put("haltMode", state.getHaltMode().name());
                info.put("killSwitch", state.isKillSwitchEnabled());
            } else {
                info.put("brokerConfigured", false);
            }
            return info;
        }).toList();

        return ResponseEntity.ok(users);
    }

    /**
     * Add a new trading user with broker credentials.
     */
    @PostMapping("/users")
    public ResponseEntity<?> addUser(@RequestBody Map<String, String> body) {
        String email = body.get("email");
        String apiKey = body.get("apiKey");
        String apiSecret = body.get("apiSecret");

        if (email == null || apiKey == null || apiSecret == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "email, apiKey, apiSecret are required"));
        }

        // Create AppUser if not exists
        AppUser user = userRepository.findByEmail(email.toLowerCase()).orElseGet(() -> {
            AppUser newUser = new AppUser(email.toLowerCase(), "USER");
            return userRepository.save(newUser);
        });

        // Create or update broker config
        UserBrokerConfig config = brokerConfigRepository.findByUserId(user.getId())
                .orElse(new UserBrokerConfig(user.getId(), apiKey, apiSecret));
        config.setApiKey(apiKey);
        config.setApiSecret(apiSecret);

        // Set defaults from request body if provided
        if (body.containsKey("totalCapital")) {
            config.setTotalCapital(Double.parseDouble(body.get("totalCapital")));
        }
        if (body.containsKey("dailyMaxLoss")) {
            config.setDailyMaxLoss(Double.parseDouble(body.get("dailyMaxLoss")));
        }
        if (body.containsKey("telegramBotToken")) {
            config.setTelegramBotToken(body.get("telegramBotToken"));
        }
        if (body.containsKey("telegramChatId")) {
            config.setTelegramChatId(body.get("telegramChatId"));
        }

        brokerConfigRepository.save(config);
        log.info("[Admin] User added/updated: email={} apiKey={}", email, maskApiKey(apiKey));

        return ResponseEntity.ok(Map.of(
                "status", "User configured",
                "userId", user.getId(),
                "loginUrl", sessionManager.getLoginUrl(user.getId())
        ));
    }

    /**
     * Update a user's broker configuration.
     */
    @PutMapping("/users/{userId}/config")
    public ResponseEntity<?> updateConfig(@PathVariable Long userId, @RequestBody Map<String, Object> body) {
        var configOpt = brokerConfigRepository.findByUserId(userId);
        if (configOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        UserBrokerConfig config = configOpt.get();

        if (body.containsKey("totalCapital")) config.setTotalCapital(((Number) body.get("totalCapital")).doubleValue());
        if (body.containsKey("dailyMaxLoss")) config.setDailyMaxLoss(((Number) body.get("dailyMaxLoss")).doubleValue());
        if (body.containsKey("maxOpenPositions")) config.setMaxOpenPositions(((Number) body.get("maxOpenPositions")).intValue());
        if (body.containsKey("maxLotsPerTrade")) config.setMaxLotsPerTrade(((Number) body.get("maxLotsPerTrade")).intValue());
        if (body.containsKey("tradingEnabled")) config.setTradingEnabled((Boolean) body.get("tradingEnabled"));
        if (body.containsKey("telegramBotToken")) config.setTelegramBotToken((String) body.get("telegramBotToken"));
        if (body.containsKey("telegramChatId")) config.setTelegramChatId((String) body.get("telegramChatId"));
        if (body.containsKey("webhookUrl")) config.setWebhookUrl((String) body.get("webhookUrl"));

        brokerConfigRepository.save(config);
        return ResponseEntity.ok(Map.of("status", "Config updated for userId " + userId));
    }

    /**
     * Get Kite login URL for a user.
     */
    @GetMapping("/users/{userId}/login-url")
    public ResponseEntity<?> getLoginUrl(@PathVariable Long userId) {
        String url = sessionManager.getLoginUrl(userId);
        if (url == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(Map.of("loginUrl", url));
    }

    /**
     * Start scanner for a user.
     */
    @PostMapping("/users/{userId}/start")
    public ResponseEntity<?> startUser(@PathVariable Long userId) {
        stateManager.getState(userId).start();
        return ResponseEntity.ok(Map.of("status", "Scanner started for userId " + userId));
    }

    /**
     * Stop scanner for a user.
     */
    @PostMapping("/users/{userId}/stop")
    public ResponseEntity<?> stopUser(@PathVariable Long userId) {
        stateManager.getState(userId).stop();
        return ResponseEntity.ok(Map.of("status", "Scanner stopped for userId " + userId));
    }

    /**
     * Emergency halt for a user.
     */
    @PostMapping("/users/{userId}/halt")
    public ResponseEntity<?> haltUser(@PathVariable Long userId) {
        stateManager.getState(userId).hardHalt("Admin halt");
        return ResponseEntity.ok(Map.of("status", "User " + userId + " halted"));
    }

    /**
     * Halt ALL users.
     */
    @PostMapping("/halt-all")
    public ResponseEntity<?> haltAll() {
        stateManager.haltAllUsers("Admin: halt all");
        return ResponseEntity.ok(Map.of("status", "All users halted"));
    }

    /**
     * Resume ALL users.
     */
    @PostMapping("/resume-all")
    public ResponseEntity<?> resumeAll() {
        stateManager.resumeAllUsers();
        return ResponseEntity.ok(Map.of("status", "All users resumed"));
    }

    private String maskApiKey(String apiKey) {
        if (apiKey == null || apiKey.length() < 6) return "***";
        return apiKey.substring(0, 4) + "..." + apiKey.substring(apiKey.length() - 4);
    }
}
