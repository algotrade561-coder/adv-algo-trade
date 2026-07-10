package com.algo.trade.multiuser;

import com.algo.trade.auth.UserBrokerConfig;
import com.algo.trade.auth.UserBrokerConfigRepository;
import com.algo.trade.execution.ExecutionEngine;
import com.algo.trade.execution.ExecutionResult;
import com.algo.trade.domain.StrategyDecision;
import com.algo.trade.domain.TradeStatus;
import com.algo.trade.persistence.TradeRepository;
import com.algo.trade.strategy.StrategyConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * Multi-user aware execution wrapper — validates per-user broker session
 * and trading state before delegating to ExecutionEngine.
 *
 * Before placing any order:
 * 1. Checks UserContext.getUserId() for the current user
 * 2. Verifies the user's broker session is authenticated (valid access token)
 * 3. Checks UserTradingState (entry allowed, kill switch, halt mode)
 * 4. Delegates to ExecutionEngine for actual order placement
 *
 * This service is the entry point for multi-user order execution.
 * Single-user mode still works via DEFAULT_USER_ID.
 */
@Service
public class UserAwareExecutionService {

    private static final Logger log = LoggerFactory.getLogger(UserAwareExecutionService.class);

    private final ExecutionEngine executionEngine;
    private final UserBrokerSessionManager sessionManager;
    private final UserTradingStateManager stateManager;
    private final UserBrokerConfigRepository configRepository;
    private final TradeRepository tradeRepository;
    private final com.algo.trade.config.GlobalConfigService globalConfigService;

    public UserAwareExecutionService(ExecutionEngine executionEngine,
                                      UserBrokerSessionManager sessionManager,
                                      UserTradingStateManager stateManager,
                                      UserBrokerConfigRepository configRepository,
                                      TradeRepository tradeRepository,
                                      com.algo.trade.config.GlobalConfigService globalConfigService) {
        this.executionEngine = executionEngine;
        this.sessionManager = sessionManager;
        this.stateManager = stateManager;
        this.configRepository = configRepository;
        this.tradeRepository = tradeRepository;
        this.globalConfigService = globalConfigService;
    }

    /**
     * Execute an entry for the current user (from UserContext thread-local).
     */
    public ExecutionResult executeEntry(StrategyDecision decision, BigDecimal optionPremium,
                                         int lotSize, StrategyConfig strategyConfig) {
        Long userId = UserContext.getUserId();
        return executeEntryForUser(userId, decision, optionPremium, lotSize, strategyConfig);
    }

    /**
     * Execute an entry for a specific user.
     */
    public ExecutionResult executeEntryForUser(Long userId, StrategyDecision decision,
                                                BigDecimal optionPremium, int lotSize,
                                                StrategyConfig strategyConfig) {
        // 1. Validate broker session
        UserBrokerSessionManager.UserBrokerSession session = sessionManager.getSession(userId);
        if (!session.isReady()) {
            String reason = "User " + userId + " broker session not ready — "
                    + (session.config == null ? "no broker config" : "no valid access token");
            log.warn("[MultiUser] Entry rejected: {}", reason);
            return ExecutionResult.rejected(List.of(reason));
        }

        // 2. Validate trading state
        UserTradingState state = stateManager.getState(userId);
        if (!state.isEntryAllowed()) {
            String reason = buildEntryBlockedReason(userId, state);
            log.warn("[MultiUser] Entry rejected for userId={}: {}", userId, reason);
            return ExecutionResult.rejected(List.of(reason));
        }

        // 3. Enforce per-user position limits (hard block, not advisory).
        // Max-open comes from the user's RESOLVED RISK PROFILE (risk_profile_definition), NOT the legacy
        // UserBrokerConfig.maxOpenPositions — the risk profile is the single authority for lots/open/count.
        // Resolve under the user's own context so it maps to THEIR profile (e.g. AGGRESSIVE=2). (2026-07-02)
        int profileMaxOpen = com.algo.trade.multiuser.UserContext.callAs(userId, globalConfigService::getMaxOpenTrades);
        if (profileMaxOpen > 0) {
            int openTradesForUser = tradeRepository.findByUserIdAndStatus(userId, TradeStatus.OPEN).size();
            if (openTradesForUser >= profileMaxOpen) {
                String reason = "User " + userId + " max open positions reached ("
                        + openTradesForUser + "/" + profileMaxOpen + ")";
                log.warn("[MultiUser] Entry rejected: {}", reason);
                return ExecutionResult.rejected(List.of(reason));
            }
            log.debug("[MultiUser] User {} positions: open={}, maxOpenTrades(profile)={}",
                    userId, openTradesForUser, profileMaxOpen);
        }

        // 4. Set user context and delegate to ExecutionEngine
        log.info("[MultiUser] Executing entry for userId={}: signal={}, instrument={}, premium={}",
                userId, decision.signalType(), decision.selectedInstrumentKey().orElse(""), optionPremium);

        return UserContext.isSet()
                ? executionEngine.executeEntry(decision, optionPremium, lotSize, strategyConfig)
                : executeWithContext(userId, decision, optionPremium, lotSize, strategyConfig);
    }

    /**
     * Execute a paper entry for the current user.
     */
    public ExecutionResult executePaperEntry(StrategyDecision decision, BigDecimal optionPremium,
                                              int lotSize, StrategyConfig strategyConfig) {
        Long userId = UserContext.getUserId();
        UserTradingState state = stateManager.getState(userId);
        if (!state.isEntryAllowed()) {
            String reason = buildEntryBlockedReason(userId, state);
            log.warn("[MultiUser] Paper entry rejected for userId={}: {}", userId, reason);
            return ExecutionResult.rejected(List.of(reason));
        }
        return executionEngine.executePaperEntry(decision, optionPremium, lotSize, strategyConfig);
    }

    /**
     * Check if a specific user can trade right now.
     */
    public boolean canUserTrade(Long userId) {
        if (!sessionManager.isAuthenticated(userId)) return false;
        UserTradingState state = stateManager.getState(userId);
        return state.isEntryAllowed();
    }

    private ExecutionResult executeWithContext(Long userId, StrategyDecision decision,
                                               BigDecimal optionPremium, int lotSize,
                                               StrategyConfig strategyConfig) {
        final ExecutionResult[] result = new ExecutionResult[1];
        UserContext.runAs(userId, () -> {
            result[0] = executionEngine.executeEntry(decision, optionPremium, lotSize, strategyConfig);
        });
        return result[0];
    }

    private String buildEntryBlockedReason(Long userId, UserTradingState state) {
        if (state.isKillSwitchEnabled()) {
            return "Kill switch active for user " + userId;
        }
        if (state.getHaltMode() != com.algo.trade.risk.HaltMode.NONE) {
            return "Halt mode " + state.getHaltMode() + " for user " + userId;
        }
        if (!state.isRunning()) {
            return "Scanner stopped for user " + userId;
        }
        if (!state.isDailyApproved()) {
            return "Daily approval pending for user " + userId;
        }
        return "Entry not allowed for user " + userId;
    }
}
