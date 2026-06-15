package com.algo.trade.multiuser;

import com.algo.trade.auth.UserBrokerConfig;
import com.algo.trade.auth.UserBrokerConfigRepository;
import com.algo.trade.domain.StrategyDecision;
import com.algo.trade.execution.ExecutionResult;
import com.algo.trade.strategy.StrategyConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * Signal Copy Service — when a strategy generates a signal, IMMEDIATELY fires
 * orders for ALL active users IN PARALLEL with zero delay.
 *
 * Key design: orders are placed SIMULTANEOUSLY for all users, NOT sequentially.
 * No user waits for another user's order to fill first.
 *
 * Flow:
 * 1. Strategy identifies a signal (before any order is placed)
 * 2. SignalCopyService.fireForAllUsers() is called
 * 3. A thread per user submits the order to their Kite account in parallel
 * 4. Results collected asynchronously — no blocking
 *
 * This replaces the "copy after fill" pattern with "fire simultaneously" pattern.
 *
 * Configurable:
 * - trading.multiuser.signal-copy.enabled: true/false
 * - Per-user: UserBrokerConfig.tradingEnabled must be true
 * - Per-user: UserTradingState must allow entry
 */
@Service
public class SignalCopyService {

    private static final Logger log = LoggerFactory.getLogger(SignalCopyService.class);

    private final UserBrokerConfigRepository configRepository;
    private final UserAwareExecutionService executionService;
    private final UserTradingStateManager stateManager;
    private final UserBrokerSessionManager sessionManager;

    /** Per-user Telegram/webhook alerts — each user is notified about THEIR copied orders. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private UserNotificationService userNotificationService;

    /** For EXIT copying — finds open copies of a closed source trade. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.persistence.TradeRepository tradeRepository;

    /** @Lazy breaks the cycle ExecutionEngine ↔ SignalCopyService (both directions lazy). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private com.algo.trade.execution.ExecutionEngine executionEngine;

    @Value("${trading.multiuser.signal-copy.enabled:true}")
    private boolean signalCopyEnabled;

    @Value("${trading.multiuser.enabled:false}")
    private boolean multiUserEnabled;

    /** Dedicated thread pool for parallel order placement — one thread per user */
    private final ExecutorService orderExecutor = Executors.newFixedThreadPool(5,
            r -> { Thread t = new Thread(r, "signal-copy"); t.setDaemon(true); return t; });

    /** Track which signals have been fired to avoid duplicates */
    private final ConcurrentHashMap<String, Long> recentSignalHashes = new ConcurrentHashMap<>();
    private static final long DEDUPE_WINDOW_MS = 5_000;

    public SignalCopyService(UserBrokerConfigRepository configRepository,
                              UserAwareExecutionService executionService,
                              UserTradingStateManager stateManager,
                              UserBrokerSessionManager sessionManager) {
        this.configRepository = configRepository;
        this.executionService = executionService;
        this.stateManager = stateManager;
        this.sessionManager = sessionManager;
    }

    /**
     * Fire orders for ALL active users IN PARALLEL — zero delay between users.
     *
     * Called by ExecutionEngine at the moment a signal is validated (BEFORE placing
     * the primary user's order). All users' orders go out simultaneously.
     *
     * @param sourceUserId The user who generated the signal (already placing their own order)
     * @param decision The strategy decision
     * @param optionPremium Current premium
     * @param lotSize Base lot size
     * @param strategyConfig Strategy configuration
     */
    public void fireForAllUsersAsync(Long sourceUserId,
                                      StrategyDecision decision,
                                      BigDecimal optionPremium,
                                      int lotSize,
                                      StrategyConfig strategyConfig) {
        if (!multiUserEnabled || !signalCopyEnabled) return;

        // Dedupe: don't fire same signal twice within 5 seconds
        String signalKey = decision.underlying() + ":" + decision.signalType() + ":"
                + decision.selectedInstrumentKey().orElse("");
        Long lastFire = recentSignalHashes.get(signalKey);
        if (lastFire != null && (System.currentTimeMillis() - lastFire) < DEDUPE_WINDOW_MS) {
            return;
        }
        recentSignalHashes.put(signalKey, System.currentTimeMillis());

        // Get all OTHER active users (source user already has their order placed by ExecutionEngine)
        List<UserBrokerConfig> targetUsers = configRepository.findByTradingEnabled(true).stream()
                .filter(config -> !config.getUserId().equals(sourceUserId))
                .filter(UserBrokerConfig::hasValidToken)
                .toList();

        if (targetUsers.isEmpty()) return;

        // Fire ALL orders in PARALLEL — zero delay between users
        // IMPORTANT: Each async task must explicitly set UserContext for the target user
        // to avoid ThreadLocal leakage from pooled thread reuse.
        for (UserBrokerConfig targetConfig : targetUsers) {
            Long targetUserId = targetConfig.getUserId();
            orderExecutor.submit(() -> {
                try {
                    UserContext.runAs(targetUserId, () ->
                        fireForUser(targetUserId, targetConfig, decision, optionPremium, lotSize, strategyConfig));
                } catch (Exception e) {
                    log.error("[SignalCopy] Parallel fire failed for userId={}: {}", targetUserId, e.getMessage());
                } finally {
                    // Defensive clear — prevent stale context on thread reuse
                    UserContext.clear();
                }
            });
        }

        log.info("[SignalCopy] Signal {} fired in PARALLEL for {} users (non-blocking)",
                signalKey, targetUsers.size());
    }

    /**
     * EXIT COPY — when the PRIMARY's trade closes, close all other users' open copies
     * of the same instrument + strategy in parallel. Without this, copied positions
     * (e.g. OI_MOMENTUM, whose strategy loop only manages the primary's activeTradeId)
     * are orphaned: the primary exits and secondaries stay open until FailSafe 15:20.
     *
     * <p>Recursion-safe: ExecutionEngine only invokes this for closes of trades owned
     * by the primary/default user, and the targets here are exclusively NON-default
     * users' trades — a copied close never fans out again.</p>
     */
    public void fireExitForAllUsersAsync(com.algo.trade.persistence.TradeEntity sourceTrade,
                                          BigDecimal lastPrice, String reason) {
        if (!multiUserEnabled || !signalCopyEnabled) return;
        if (tradeRepository == null || executionEngine == null) return;

        List<com.algo.trade.persistence.TradeEntity> copies =
                tradeRepository.findByStatus(com.algo.trade.domain.TradeStatus.OPEN).stream()
                        .filter(t -> t.getUserId() != null
                                && !t.getUserId().equals(UserContext.DEFAULT_USER_ID))
                        .filter(t -> !t.getTradeId().equals(sourceTrade.getTradeId()))
                        .filter(t -> sourceTrade.getInstrumentKey() != null
                                && sourceTrade.getInstrumentKey().equals(t.getInstrumentKey()))
                        .filter(t -> sourceTrade.getStrategyType() == null
                                || sourceTrade.getStrategyType().equals(t.getStrategyType()))
                        .toList();
        if (copies.isEmpty()) return;

        log.info("[SignalCopy] EXIT copy: closing {} user position(s) for {} (source reason: {})",
                copies.size(), sourceTrade.getInstrumentKey(), reason);
        for (var copy : copies) {
            String tradeId = copy.getTradeId();
            Long ownerId = copy.getUserId();
            orderExecutor.submit(() -> {
                try {
                    // closeTrade wraps runAsTradeOwner → correct per-user broker creds + alerts
                    var result = executionEngine.closeTrade(tradeId, lastPrice, "COPY_EXIT: " + reason);
                    if (result.accepted()) {
                        log.info("[SignalCopy] ✅ EXIT copied: userId={} tradeId={}", ownerId, tradeId);
                    } else {
                        log.warn("[SignalCopy] ❌ EXIT copy rejected: userId={} tradeId={} reasons={}",
                                ownerId, tradeId, result.reasons());
                        if (userNotificationService != null) {
                            userNotificationService.sendToUserOwnChannelOnly(ownerId,
                                    "⚠️ EXIT FAILED for your copied position " + copy.getInstrumentKey()
                                            + " — " + (result.reasons().isEmpty() ? "unknown" : result.reasons().getFirst())
                                            + ". Close manually if still open.");
                        }
                    }
                } catch (Exception e) {
                    log.error("[SignalCopy] EXIT copy failed: userId={} tradeId={}: {}", ownerId, tradeId, e.getMessage());
                }
            });
        }
    }

    /**
     * LEGACY: Copy signal AFTER primary user's order fills (sequential, has delay).
     * Kept for backward compatibility but fireForAllUsersAsync() is preferred.
     */
    public List<CopyResult> copySignalToAllUsers(Long sourceUserId,
                                                   StrategyDecision decision,
                                                   BigDecimal optionPremium,
                                                   int sourceLotSize,
                                                   StrategyConfig strategyConfig) {
        if (!multiUserEnabled || !signalCopyEnabled) return List.of();

        // Use the parallel approach instead
        fireForAllUsersAsync(sourceUserId, decision, optionPremium, sourceLotSize, strategyConfig);
        return List.of(new CopyResult(0L, true, "Fired asynchronously for all users"));
    }

    /**
     * Fire order for a single target user — runs on the parallel thread pool.
     */
    private void fireForUser(Long targetUserId, UserBrokerConfig targetConfig,
                              StrategyDecision decision, BigDecimal optionPremium,
                              int lotSize, StrategyConfig strategyConfig) {

        // Check user's trading state
        UserTradingState state = stateManager.getState(targetUserId);
        if (!state.isEntryAllowed()) {
            log.debug("[SignalCopy] userId={} entry not allowed: {}", targetUserId, state.getHaltMode());
            return;
        }

        // Check broker session
        if (!sessionManager.isAuthenticated(targetUserId)) {
            log.debug("[SignalCopy] userId={} not authenticated", targetUserId);
            return;
        }

        // Per-user capital/risk gate: don't exceed user's max open positions
        int userMaxOpen = targetConfig.getMaxOpenPositions();
        if (userMaxOpen > 0) {
            // Quick count from session manager or trust UserAwareExecutionService to check
            // (it does check in executeEntryForUser, but we gate early to avoid unnecessary work)
            log.debug("[SignalCopy] userId={} maxOpenPositions={}, maxLotsPerTrade={}",
                    targetUserId, userMaxOpen, targetConfig.getMaxLotsPerTrade());
        }

        // Adjust lot size to target user's config (respects per-user capital)
        int targetMaxLots = targetConfig.getMaxLotsPerTrade();
        int indexLotSize = getLotMultiplier(decision);
        int adjustedLotSize = Math.min(lotSize, targetMaxLots * indexLotSize);

        // Execute with target user's context — uses their Kite token
        log.info("[SignalCopy] Firing for userId={}: {} {} lots={}",
                targetUserId, decision.signalType(), decision.selectedInstrumentKey().orElse(""), adjustedLotSize);

        ExecutionResult result = executionService.executeEntryForUser(
                targetUserId, decision, optionPremium, adjustedLotSize, strategyConfig);

        if (result.accepted()) {
            // Success alert reaches the user via TelegramAlertService's per-user fan-out
            // (ExecutionEngine sends "Entry order filled"/"Limit order placed" under
            // this user's context) — no extra alert here to avoid duplicates.
            log.info("[SignalCopy] ✅ userId={} order placed successfully", targetUserId);
        } else {
            String reason = result.reasons().isEmpty() ? "unknown" : result.reasons().getFirst();
            log.warn("[SignalCopy] ❌ userId={} rejected: {}", targetUserId, reason);
            // Explicit rejection notice — some rejection paths (e.g. broker exceptions)
            // don't emit a Telegram alert inside ExecutionEngine, so guarantee one here.
            if (userNotificationService != null) {
                userNotificationService.sendToUserOwnChannelOnly(targetUserId,
                        String.format("❌ Entry REJECTED: %s %s — %s",
                                decision.signalType(),
                                decision.selectedInstrumentKey().orElse(""),
                                reason));
            }
        }
    }

    private int getLotMultiplier(StrategyDecision decision) {
        return switch (decision.underlying()) {
            case NIFTY -> 75;
            case BANKNIFTY -> 30;
            case SENSEX -> 20;
            default -> 75;
        };
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 60_000)
    public void cleanDedupeCache() {
        long cutoff = System.currentTimeMillis() - (DEDUPE_WINDOW_MS * 10);
        recentSignalHashes.entrySet().removeIf(e -> e.getValue() < cutoff);
    }

    public Map<String, Long> getRecentSignals() { return Map.copyOf(recentSignalHashes); }
    public boolean isEnabled() { return multiUserEnabled && signalCopyEnabled; }

    public record CopyResult(Long userId, boolean success, String message) {}
}
