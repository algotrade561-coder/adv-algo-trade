package com.algo.trade.multiuser;

import com.algo.trade.auth.UserBrokerConfig;
import com.algo.trade.auth.UserBrokerConfigRepository;
import com.algo.trade.risk.HaltMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages per-user trading states. Each user has independent:
 * - Scanner state (running/stopped)
 * - Kill switch
 * - Halt mode
 * - Daily approval gate
 *
 * The existing TradingStateService can delegate to this for multi-user mode.
 */
@Service
public class UserTradingStateManager {

    private static final Logger log = LoggerFactory.getLogger(UserTradingStateManager.class);

    private final UserBrokerConfigRepository configRepository;
    private final ConcurrentHashMap<Long, UserTradingState> states = new ConcurrentHashMap<>();

    public UserTradingStateManager(UserBrokerConfigRepository configRepository) {
        this.configRepository = configRepository;
    }

    /**
     * Get or create trading state for a user.
     */
    public UserTradingState getState(Long userId) {
        return states.computeIfAbsent(userId, UserTradingState::new);
    }

    /**
     * Get state for the current thread's user.
     */
    public UserTradingState getCurrentState() {
        return getState(UserContext.getUserId());
    }

    /**
     * Get all user states (for admin dashboard).
     */
    public Collection<UserTradingState> getAllStates() {
        return states.values();
    }

    /**
     * Get all active (trading-enabled + authenticated) user IDs.
     */
    public java.util.List<Long> getActiveUserIds() {
        return configRepository.findByTradingEnabled(true).stream()
                .filter(UserBrokerConfig::hasValidToken)
                .map(UserBrokerConfig::getUserId)
                .toList();
    }

    /**
     * Auto-approve daily trading for all users at 10:30 AM (weekdays).
     */
    @Scheduled(cron = "0 30 10 * * MON-FRI")
    public void autoApproveAll() {
        for (UserTradingState state : states.values()) {
            state.autoApprove();
        }
        log.info("[MultiUser] Auto-approved trading for {} users at 10:30 AM", states.size());
    }

    /**
     * Reset daily state for all users at midnight.
     */
    @Scheduled(cron = "0 0 0 * * *")
    public void resetDailyAll() {
        for (UserTradingState state : states.values()) {
            state.resetDaily();
        }
        log.info("[MultiUser] Daily reset for {} users", states.size());
    }

    /**
     * Admin: halt all users immediately.
     */
    public void haltAllUsers(String reason) {
        for (UserTradingState state : states.values()) {
            state.hardHalt(reason);
        }
        log.warn("[MultiUser] ALL USERS HALTED: {}", reason);
    }

    /**
     * Admin: resume all users.
     */
    public void resumeAllUsers() {
        for (UserTradingState state : states.values()) {
            state.resumeFromHalt();
        }
        log.info("[MultiUser] ALL USERS RESUMED");
    }
}
