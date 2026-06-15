package com.algo.trade.multiuser;

import com.algo.trade.auth.UserBrokerConfig;
import com.algo.trade.auth.UserBrokerConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

/**
 * Multi-User Strategy Loop — iterates through all active users and evaluates
 * strategies for each one independently.
 *
 * This is the multi-user wrapper around the existing strategy evaluation.
 * For each user with a valid broker session:
 * 1. Set UserContext to their userId
 * 2. Run strategy evaluation (same candle data, but per-user positions/config)
 * 3. Execute orders against their broker account
 * 4. Clear UserContext
 *
 * Market data (candles, OI snapshots) is SHARED — computed once, used by all users.
 * Only execution (orders, positions, P&L) is per-user.
 *
 * Note: In the current architecture, the OIMomentumStrategy has its own 1-second
 * loop. This service handles the "outer loop" that ensures each user's state is
 * evaluated. The actual strategy logic remains in OIMomentumStrategy.
 */
@Service
public class MultiUserStrategyLoop {

    private static final Logger log = LoggerFactory.getLogger(MultiUserStrategyLoop.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final UserBrokerConfigRepository configRepository;
    private final UserTradingStateManager stateManager;
    private final UserBrokerSessionManager sessionManager;
    private final com.algo.trade.broker.zerodha.KiteWebSocketClient webSocketClient;

    @Value("${trading.multiuser.enabled:false}")
    private boolean multiUserEnabled;

    public MultiUserStrategyLoop(UserBrokerConfigRepository configRepository,
                                  UserTradingStateManager stateManager,
                                  UserBrokerSessionManager sessionManager,
                                  com.algo.trade.broker.zerodha.KiteWebSocketClient webSocketClient) {
        this.configRepository = configRepository;
        this.stateManager = stateManager;
        this.sessionManager = sessionManager;
        this.webSocketClient = webSocketClient;
    }

    /**
     * Periodic check: ensure all active users have their strategy loops running.
     * Runs every 30 seconds during market hours.
     *
     * For each active user:
     * 1. Verifies token validity and scanner state
     * 2. Auto-starts scanner for users with valid tokens who haven't started yet
     * 3. Auto-approves daily trading if within market hours
     *
     * Actual strategy evaluation runs in the SHARED AlgoTradeExecution loop
     * (triggered by CandleClosedEvent). The SignalCopyService fans out signals
     * to all active users. This loop just manages per-user operational state.
     */
    @Scheduled(fixedDelay = 30_000)
    public void ensureUserLoopsRunning() {
        if (!multiUserEnabled) return;

        LocalTime now = LocalTime.now(IST);
        if (now.isBefore(LocalTime.of(9, 10)) || now.isAfter(LocalTime.of(15, 35))) return;

        List<UserBrokerConfig> activeUsers = configRepository.findByTradingEnabled(true);

        for (UserBrokerConfig userConfig : activeUsers) {
            if (!userConfig.hasValidToken()) {
                log.debug("[MultiUser] User {} — no valid token, skipping", userConfig.getUserId());
                continue;
            }

            UserTradingState state = stateManager.getState(userConfig.getUserId());

            // Auto-start: if user has valid token and trading is enabled but scanner not running,
            // start it automatically during market hours (mimics KiteStartupLogin behavior)
            if (!state.isRunning() && now.isAfter(LocalTime.of(9, 14))) {
                state.start();
                log.info("[MultiUser] Auto-started scanner for userId={} (valid token + market hours)", userConfig.getUserId());
            }

            // Auto-approve: if market is open and daily not yet approved, approve
            if (!state.isDailyApproved() && now.isAfter(LocalTime.of(10, 30))) {
                state.approveToday();
                log.info("[MultiUser] Auto-approved daily trading for userId={}", userConfig.getUserId());
            }
        }
    }

    /**
     * Get status of all users for admin dashboard.
     */
    public List<UserStatusSnapshot> getAllUserStatus() {
        return configRepository.findAll().stream().map(config -> {
            UserTradingState state = stateManager.getState(config.getUserId());
            return new UserStatusSnapshot(
                    config.getUserId(),
                    config.getApiKey(),
                    config.hasValidToken(),
                    config.isTradingEnabled(),
                    state.isRunning(),
                    state.getHaltMode().name(),
                    // Shared market-data feed state, not the unused per-user wsConnected flag
                    webSocketClient.isConnected()
            );
        }).toList();
    }

    public record UserStatusSnapshot(
            Long userId,
            String apiKey,
            boolean authenticated,
            boolean tradingEnabled,
            boolean scannerRunning,
            String haltMode,
            boolean wsConnected
    ) {}
}
