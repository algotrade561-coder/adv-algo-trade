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

    /** Risk-profile-aware config — its getters resolve for the CURRENT UserContext user, so when called inside
     *  {@code runAs(targetUserId)} it returns THAT user's assigned-profile values (e.g. maxLotsPerTrade). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.config.GlobalConfigService globalConfigService;

    /** Users table — drives the order-pool size so every user gets a dedicated thread (no queueing). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.auth.AppUserRepository appUserRepository;

    @Value("${trading.multiuser.signal-copy.enabled:true}")
    private boolean signalCopyEnabled;

    @Value("${trading.multiuser.enabled:false}")
    private boolean multiUserEnabled;

    // ---------------------------------------------------------------------------------------------------
    // ROBUST FAN-OUT (2026-07-02) — hardening for scale (many users), all gated behind ONE default-off flag
    // so today's behaviour is unchanged until we deliberately flip it on for a 3rd-user pilot. When ON:
    //   (1) the fan-out pool NEVER back-pressures onto the calling (strategy/tick/WS) thread — a saturated
    //       pool drops-with-alert instead of CallerRunsPolicy running a broker order inline on the tick loop;
    //   (2) each broker call is time-bounded on an isolated pool, so one hung Zerodha call can't pin a
    //       fan-out thread forever and exhaust capacity;
    //   (3) a durable reconciler sweep force-closes any copy left open after its primary went flat — this
    //       survives a JVM restart, unlike the fire-and-forget +2/+5/+12s deferred close timers.
    // ---------------------------------------------------------------------------------------------------
    @Value("${trading.multiuser.fanout.robust.enabled:false}")
    private boolean robustFanoutEnabled;

    /** Hard ceiling on a single broker call inside the fan-out. On timeout the dispatch thread is freed and
     *  the reconciler/exit-monitor backstops; the underlying call may still settle (idempotent guards cover it). */
    @Value("${trading.multiuser.fanout.broker-call-timeout-ms:10000}")
    private long brokerCallTimeoutMs;

    /** Reconciler only force-closes copies whose primary CLOSED within this look-back (avoids re-touching
     *  ancient trades every sweep). */
    @Value("${trading.multiuser.fanout.reconcile-window-min:15}")
    private int reconcileWindowMin;

    /** Per-user circuit breaker: OPEN a user's ENTRY fan-out after this many CONSECUTIVE failures (any type:
     *  rejection / broker exception / not-authenticated), then skip them for {@link #breakerCooldownMin}. */
    @Value("${trading.multiuser.fanout.breaker.consecutive-failures:3}")
    private int breakerFailThreshold;

    @Value("${trading.multiuser.fanout.breaker.cooldown-min:5}")
    private int breakerCooldownMin;

    /** Exit-side backoff: after this many consecutive un-closable rejections for a COPY trade (margin / AMO /
     *  market-closed), pause that trade's copy-exit retries for {@link #exitBackoffCooldownMin} instead of
     *  hammering the broker every cycle. Unlike the entry breaker this is PER-TRADE (a stranded position must
     *  still be attempted — just not in a tight loop) and auto-clears on any successful close or after cooldown. */
    @Value("${trading.multiuser.fanout.exit-backoff.consecutive-rejects:3}")
    private int exitBackoffThreshold;

    @Value("${trading.multiuser.fanout.exit-backoff.cooldown-min:3}")
    private int exitBackoffCooldownMin;

    /** Dedicated thread pool for parallel order placement — sized from the users table in {@link #initPools()}
     *  so every user gets a dedicated thread and no user ever queues behind another. */
    private ExecutorService orderExecutor;

    /** Isolated pool that runs the ACTUAL broker I/O when robust fan-out is on, so {@link #orderExecutor}
     *  threads only ever BLOCK-with-timeout on it (they are freed after {@link #brokerCallTimeoutMs}); a truly
     *  hung socket can pin a thread here, but this pool is bounded + drop-guarded and separate from dispatch. */
    private ExecutorService brokerCallExecutor;

    /** Observability counters (feed the future fan-out ledger + monitoring greps). */
    private final java.util.concurrent.atomic.AtomicLong droppedFanouts = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong timedOutCalls = new java.util.concurrent.atomic.AtomicLong();

    /** Small scheduler for DEFERRED exit-copy retries — closes copies whose trade record was written AFTER
     *  the primary's exit fired (the fill→record lag race that stranded user 8 on 2026-06-29). */
    private final ScheduledExecutorService exitRetryScheduler = Executors.newScheduledThreadPool(2,
            r -> { Thread t = new Thread(r, "exit-copy-retry"); t.setDaemon(true); return t; });

    /** Guard against double-closing the same trade across the immediate + deferred exit passes. A close
     *  places a SELL then waits for the fill (status stays OPEN meanwhile), so without this two passes could
     *  each place a SELL → naked short. tradeId → timestamp; entries expire after {@link #CLOSE_GUARD_MS}. */
    private final ConcurrentHashMap<String, Long> closeInFlight = new ConcurrentHashMap<>();
    private static final long CLOSE_GUARD_MS = 30_000;

    /** Build the order pool sized to the users table (one thread per user + headroom for concurrent
     *  entry/exit fan-out). Runs after field injection, before any signal can fire. Idle threads are
     *  reclaimed so threads are "closed" when not needed, then re-spawned on demand. */
    @jakarta.annotation.PostConstruct
    void initPools() {
        int userCount = 0;
        try {
            if (appUserRepository != null) userCount = (int) appUserRepository.count();
        } catch (Exception e) {
            log.warn("[SignalCopy] could not read users-table count for pool sizing — using floor: {}", e.getMessage());
        }
        int core = Math.max(4, userCount);            // one thread per user, floor 4
        int max = Math.max(16, userCount * 3);        // headroom for overlapping entry + exit fan-out

        if (robustFanoutEnabled) {
            // ROBUST: bounded queue + drop-with-ALERT rejection (NEVER CallerRunsPolicy — that would run a
            // broker order inline on the strategy/tick thread and stall market data for every user). A brief
            // burst queues; sustained overload (a hung broker call pinning threads) is dropped + loudly logged.
            int qcap = Math.max(64, userCount * 8);
            this.orderExecutor = buildRobustPool("signal-copy", core, Math.max(core, max), qcap);
            this.brokerCallExecutor = buildRobustPool("broker-call", core, Math.max(core, max), qcap);
            log.info("[SignalCopy] ROBUST fan-out ENABLED: userCount={} core={} max={} queueCap={} brokerTimeout={}ms reconcile={}min",
                    userCount, core, Math.max(core, max), qcap, brokerCallTimeoutMs, reconcileWindowMin);
        } else {
            // LEGACY (unchanged): SynchronousQueue + CallerRunsPolicy. Kept as the default so flipping the flag
            // is the only behaviour change.
            ThreadPoolExecutor pool = new ThreadPoolExecutor(
                    core, Math.max(core, max), 60L, TimeUnit.SECONDS,
                    new SynchronousQueue<>(),             // never queue — hand off to a thread immediately
                    r -> { Thread t = new Thread(r, "signal-copy"); t.setDaemon(true); return t; },
                    new ThreadPoolExecutor.CallerRunsPolicy());
            pool.allowCoreThreadTimeOut(true);            // reclaim idle threads ("close" when not needed)
            this.orderExecutor = pool;
            log.info("[SignalCopy] order pool sized from users table (legacy): userCount={} → core={}, max={}",
                    userCount, core, Math.max(core, max));
        }
    }

    /** A bounded pool whose overload policy DROPS the task and raises a loud alert — it never runs work on the
     *  submitting thread, so a saturated fan-out can degrade (miss a copy, which the reconciler backstops) but
     *  can never stall the caller (the strategy/tick loop). */
    private ThreadPoolExecutor buildRobustPool(String name, int core, int max, int queueCap) {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                core, max, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(queueCap),
                r -> { Thread t = new Thread(r, name); t.setDaemon(true); return t; },
                (r, ex) -> {
                    droppedFanouts.incrementAndGet();
                    log.error("[SignalCopy] ⚠ FAN-OUT OVERLOAD on '{}' — DROPPED a task (active={}, queued={}, pool={}); "
                                    + "a broker call is likely slow/hung. Exits are backstopped by the reconciler + exit-monitor; "
                                    + "a dropped ENTRY is a missed copy for one user on this signal only.",
                            name, ex.getActiveCount(), ex.getQueue().size(), ex.getPoolSize());
                });
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    /** Run a fan-out body time-bounded on {@link #brokerCallExecutor}. When robust fan-out is off, runs inline
     *  (legacy). On timeout the calling dispatch thread is freed after {@link #brokerCallTimeoutMs}.
     *  @param entryAlertUserId when non-null, a drop/timeout is treated as a MISSED ENTRY for this user and they
     *         are alerted (entries have no reconciler backstop, unlike exits; pass null for exit tasks). */
    private void runBrokerCallWithTimeout(String label, Long entryAlertUserId, Runnable body) {
        if (!robustFanoutEnabled || brokerCallExecutor == null) {
            try { body.run(); }
            catch (Exception e) { log.error("[SignalCopy] fan-out task failed ({}): {}", label, e.getMessage()); }
            return;
        }
        Future<?> f;
        try {
            f = brokerCallExecutor.submit(body);
        } catch (RejectedExecutionException rex) {
            droppedFanouts.incrementAndGet();
            log.error("[SignalCopy] ⚠ broker-call pool saturated — dropped {} (a broker call is hung). Reconciler/monitor backstops.", label);
            alertEntryMiss(entryAlertUserId, "system was saturated");
            return;
        }
        try {
            f.get(brokerCallTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            timedOutCalls.incrementAndGet();
            f.cancel(true);   // best-effort interrupt; the SDK call may ignore it and settle on its own
            log.error("[SignalCopy] ⚠ broker call TIMED OUT >{}ms: {} — dispatch freed; reconciler/exit-monitor backstops.",
                    brokerCallTimeoutMs, label);
            alertEntryMiss(entryAlertUserId, "the broker call timed out");
        } catch (ExecutionException ee) {
            log.error("[SignalCopy] fan-out task failed ({}): {}", label, ee.getCause() != null ? ee.getCause().getMessage() : ee.getMessage());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /** Notify a user their ENTRY was not placed (dropped/timed-out under load). Entries are NOT auto-retried —
     *  a late fill can be worse than a miss — so we make the miss visible instead of silent. Exits pass null
     *  here (they have the reconciler + exit-monitor as backstops). */
    private void alertEntryMiss(Long userId, String why) {
        if (userId == null || userNotificationService == null) return;
        try {
            userNotificationService.sendToUserOwnChannelOnly(userId,
                    "⚠️ An entry signal was NOT placed for you because " + why + ". The bot did not auto-retry "
                            + "(a late entry can be worse than a missed one). Enter manually if you still want it.");
        } catch (Exception ignore) { /* alerting must never break the fan-out */ }
    }

    public long getDroppedFanouts() { return droppedFanouts.get(); }
    public long getTimedOutCalls() { return timedOutCalls.get(); }

    // ---------------------------------------------------------------------------------------------------
    // PER-USER CIRCUIT BREAKER (Tier 2, 2026-07-02) — generalizes the ExecutionEngine margin-only backoff to
    // ANY repeated ENTRY fan-out failure (bad token / broker exception / not-authenticated / rejection). After
    // N consecutive failures a user's ENTRY fan-out is SKIPPED for a cooldown (with ONE alert, not a per-signal
    // storm); the cooldown naturally half-opens (the next signal probes — success closes it, a failure re-opens).
    // EXITS are NEVER gated by the breaker — a position must always be closable. Gated by the robust flag.
    // ---------------------------------------------------------------------------------------------------
    private static final class BreakerState {
        int consecutiveFailures;
        long openUntilMs;
        boolean alerted;
    }
    private final ConcurrentHashMap<Long, BreakerState> userBreakers = new ConcurrentHashMap<>();

    /** @return true if this user's ENTRY fan-out may be attempted; false while the breaker is OPEN (in cooldown). */
    private boolean breakerAllow(Long userId) {
        if (!robustFanoutEnabled) return true;
        BreakerState s = userBreakers.get(userId);
        return s == null || System.currentTimeMillis() >= s.openUntilMs;
    }

    private void breakerRecordSuccess(Long userId) {
        if (!robustFanoutEnabled) return;
        BreakerState s = userBreakers.get(userId);
        if (s == null) return;
        synchronized (s) {
            if (s.openUntilMs != 0 || s.consecutiveFailures != 0) {
                log.info("[SignalCopy] breaker CLOSED for userId={} — entry fan-out recovered", userId);
            }
            s.consecutiveFailures = 0;
            s.openUntilMs = 0;
            s.alerted = false;
        }
    }

    /**
     * A per-user ENTRY reject that is the gate WORKING AS DESIGNED — not an infrastructure/health failure —
     * must NOT trip the circuit breaker. 2026-07-03: benign anti-churn / position-limit rejects (e.g.
     * "re-buy cheaper", max-open, cooldown, own daily-loss cap) were counting toward the 3-strike trip and
     * locking a user (u:8) out of entries for 5 min despite the pipeline being perfectly healthy. Only true
     * failures — broker exceptions, auth loss, timeouts — should accumulate. Conservative: unknown reasons
     * still count (default to treating an unrecognised reject as a real failure).
     */
    private static boolean isBenignReject(String reason) {
        if (reason == null) return false;
        String r = reason.toLowerCase(java.util.Locale.US);
        return r.contains("re-entry above last buy")     // anti-churn: only re-buy cheaper
                || r.contains("re-entry above last sell")  // anti-churn: don't re-buy near where you just sold
                || r.contains("open trade already exists")
                || r.contains("open buy order already exists")
                || r.contains("cooldown")                 // re-entry / direction-flip / same-instrument cooldowns
                || r.contains("max open trades")
                || r.contains("max open positions")       // per-user open-positions cap (message variant)
                || r.contains("lot-cap")                  // §LOT-CAP per-underlying profile guard (2026-06-30) —
                                                          // 09-Jul: tripped u:8's breaker 4× on a healthy pipeline
                || r.contains("max trades per day")
                || r.contains("already holds")
                || r.contains("concentration")
                || r.contains("per-underlying")
                || r.contains("max daily loss")           // user hit their OWN loss cap — correct, not a failure
                || r.contains("daily loss limit")
                || r.contains("max consecutive losses")
                || r.contains("not an entry signal");
    }

    private void breakerRecordFailure(Long userId, String reason) {
        if (!robustFanoutEnabled) return;
        // Benign business rejects (the gate doing its job) prove the pipeline is healthy — they must not
        // accumulate toward the breaker. Only infra/health failures (exception/auth/timeout) trip it.
        if (isBenignReject(reason)) {
            log.debug("[SignalCopy] breaker: benign reject NOT counted for userId={} ({})", userId, reason);
            return;
        }
        BreakerState s = userBreakers.computeIfAbsent(userId, k -> new BreakerState());
        synchronized (s) {
            s.consecutiveFailures++;
            if (s.consecutiveFailures >= breakerFailThreshold) {
                s.openUntilMs = System.currentTimeMillis() + breakerCooldownMin * 60_000L;
                if (!s.alerted) {
                    s.alerted = true;
                    log.error("[SignalCopy] ⚠ breaker OPEN for userId={} after {} consecutive entry fan-out failures "
                                    + "(last: {}) — SKIPPING their ENTRY fan-out for {}min (exits are never skipped).",
                            userId, s.consecutiveFailures, reason, breakerCooldownMin);
                    if (userNotificationService != null) {
                        userNotificationService.sendToUserOwnChannelOnly(userId,
                                "⚠️ Your automated ENTRIES are paused for " + breakerCooldownMin
                                        + " min after repeated failures (" + reason + "). Exits still run normally. "
                                        + "Check your Kite login / margin.");
                    }
                }
            }
        }
    }

    /** True if a close for this tradeId may proceed; false if one is already in flight (within the TTL). */
    private boolean acquireCloseGuard(String tradeId) {
        long now = System.currentTimeMillis();
        if (closeInFlight.size() > 2000) {            // bounded cleanup
            closeInFlight.entrySet().removeIf(e -> now - e.getValue() > CLOSE_GUARD_MS);
        }
        Long prev = closeInFlight.putIfAbsent(tradeId, now);
        if (prev != null && (now - prev) < CLOSE_GUARD_MS) return false;
        closeInFlight.put(tradeId, now);              // refresh (or re-acquire after TTL)
        return true;
    }

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
        fireForAllUsersAsync(sourceUserId, decision, optionPremium, lotSize, strategyConfig, 0L);
    }

    /** Number of OTHER active, token-valid users (besides the source) — used to decide whether aligned-fire
     *  needs to wait at all (a single-user account shouldn't pay the alignment budget). */
    public int otherActiveUserCount(Long sourceUserId) {
        if (!multiUserEnabled || !signalCopyEnabled) return 0;
        return (int) configRepository.findByTradingEnabled(true).stream()
                .filter(c -> sourceUserId == null || !sourceUserId.equals(c.getUserId()))
                .filter(UserBrokerConfig::hasValidToken)
                .count();
    }

    /**
     * @param fireAtNanos ALIGNED-FIRE common instant ({@code System.nanoTime()}). When &gt; 0, each target parks
     *                    until this instant before calling the broker, so the primary + all secondaries dispatch
     *                    their orders TOGETHER (no primary lead). 0 = fire as soon as armed (legacy behaviour).
     */
    public void fireForAllUsersAsync(Long sourceUserId,
                                      StrategyDecision decision,
                                      BigDecimal optionPremium,
                                      int lotSize,
                                      StrategyConfig strategyConfig,
                                      long fireAtNanos) {
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

        // Fire ALL orders in PARALLEL — each target arms (gate+sizing) then parks until fireAtNanos so every
        // user's broker call goes out at the same instant. Each async task sets UserContext for the target.
        for (UserBrokerConfig targetConfig : targetUsers) {
            Long targetUserId = targetConfig.getUserId();
            if (!breakerAllow(targetUserId)) {
                log.debug("[SignalCopy] userId={} skipped — entry circuit breaker OPEN", targetUserId);
                continue;
            }
            orderExecutor.submit(() -> runBrokerCallWithTimeout("entry u=" + targetUserId, targetUserId, () -> {
                try {
                    UserContext.runAs(targetUserId, () -> {
                        if (fireAtNanos > 0) com.algo.trade.execution.ExecutionEngine.setSyncFireAt(fireAtNanos);
                        try {
                            fireForUser(targetUserId, targetConfig, decision, optionPremium, lotSize, strategyConfig);
                        } finally {
                            com.algo.trade.execution.ExecutionEngine.clearSyncFireAt();
                        }
                    });
                } catch (Exception e) {
                    breakerRecordFailure(targetUserId, "exception: " + e.getMessage());
                    log.error("[SignalCopy] Parallel fire failed for userId={}: {}", targetUserId, e.getMessage());
                } finally {
                    // Defensive clear — prevent stale context on thread reuse
                    UserContext.clear();
                }
            }));
        }

        log.info("[SignalCopy] Signal {} fired in PARALLEL for {} users (fireAt={})",
                signalKey, targetUsers.size(), fireAtNanos > 0 ? "aligned" : "asap");
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

        // Immediate pass — close copies that already have an OPEN trade record (mirrors the primary's exit).
        int closed = closeOpenCopies(sourceTrade, lastPrice, reason, false, 0L, false);
        if (closed > 0) {
            log.info("[SignalCopy] EXIT copy: closed {} copy position(s) for {} (reason: {})",
                    closed, sourceTrade.getInstrumentKey(), reason);
        }

        // RACE FIX (2026-06-29): a copy whose entry FILLED but whose trade record was written AFTER this exit
        // fired is invisible to the immediate pass — that stranded user 8 (entry 11:38:07, record 11:38:32,
        // primary exit 11:38:30). Re-scan a few times over ~12s and MARKET-close any copy that surfaces late.
        // Self-limiting (a closed copy stops matching) and double-close-safe (acquireCloseGuard). A null price
        // routes the close as MARKET so the stranded copy is guaranteed to fill.
        for (long delaySec : new long[]{2, 5, 12}) {
            exitRetryScheduler.schedule(() -> {
                try {
                    int late = closeOpenCopies(sourceTrade, null, reason + " (late)", true, 0L, false);
                    if (late > 0) {
                        log.warn("[SignalCopy] EXIT copy DEFERRED(+{}s): MARKET-closed {} late copy(ies) for {}",
                                delaySec, late, sourceTrade.getInstrumentKey());
                    }
                } catch (Exception e) {
                    log.error("[SignalCopy] deferred exit-copy failed for {}: {}",
                            sourceTrade.getInstrumentKey(), e.getMessage());
                }
            }, delaySec, TimeUnit.SECONDS);
        }
    }

    /**
     * Find OPEN copies of the source trade (other users, same instrument + strategy) and close each in
     * parallel on the order pool. Returns the number of close attempts actually submitted.
     *
     * @param marketExit when true, pass a null price so the close routes as a MARKET order (reliable fill for
     *                   a stranded/late copy); when false, use the source's exit price (marketable LIMIT,
     *                   mirroring the primary). The copy filter (non-default user, same instrument + strategy)
     *                   is identical either way, so MANUAL / SYNC positions (null or different strategy) are
     *                   never touched.
     */
    private int closeOpenCopies(com.algo.trade.persistence.TradeEntity sourceTrade,
                                BigDecimal lastPrice, String reason, boolean marketExit, long fireAtNanos,
                                boolean strictKeyMatch) {
        List<com.algo.trade.persistence.TradeEntity> copies =
                tradeRepository.findByStatus(com.algo.trade.domain.TradeStatus.OPEN).stream()
                        .filter(t -> t.getUserId() != null
                                && !t.getUserId().equals(UserContext.DEFAULT_USER_ID))
                        // Leave MANUAL / broker-synced positions alone unless explicitly managed — consistent with
                        // ExecutionEngine / LivePositionExitMonitor / GracefulShutdownHandler, which all gate on
                        // isManageSyncedTrades(). Without this the copy-exit was the ONE path missing the guard: a
                        // primary bot exit (or FailSafe square-off) swept a user's SYNC position on the same
                        // instrument into the copy-close and hammered it with futile margin/AMO rejections
                        // (u:8 SYNC-da685755 NIFTY24150PE, 2026-07-02 15:37–15:59 IST). (2026-07-02)
                        .filter(t -> globalConfigService == null || globalConfigService.isManageSyncedTrades()
                                || t.getTradeId() == null || !t.getTradeId().startsWith("SYNC-"))
                        .filter(t -> !t.getTradeId().equals(sourceTrade.getTradeId()))
                        .filter(t -> sourceTrade.getInstrumentKey() != null
                                && sourceTrade.getInstrumentKey().equals(t.getInstrumentKey()))
                        // Pair the copy by the ENTRY correlation key (same signal → same key across users) OR
                        // by strategyType. Instrument already matched above (never sell a wrong instrument).
                        // ADDITIVE vs the old strategy-only match — it also catches a paired copy whose
                        // strategyType differs (e.g. null-vs-typed after a one-sided position-sync), which the
                        // old filter silently skipped → a secondary left open after the primary went flat. (2026-07-02)
                        .filter(t -> {
                            String srcKey = sourceTrade.getEntryCorrelationKey();
                            boolean keyMatch = srcKey != null && !srcKey.isBlank()
                                    && srcKey.equals(t.getEntryCorrelationKey());
                            if (strictKeyMatch) {
                                // Reconciler runs over a WIDE window — act ONLY on a copy provably paired to
                                // THIS exact signal instance (correlation key). Strategy-only matching here could
                                // wrongly close an unrelated independent re-entry on the same strategy.
                                return keyMatch;
                            }
                            boolean strategyMatch = sourceTrade.getStrategyType() == null
                                    || sourceTrade.getStrategyType().equals(t.getStrategyType());
                            return keyMatch || strategyMatch;
                        })
                        .toList();
        if (copies.isEmpty()) return 0;

        int submitted = 0;
        for (var copy : copies) {
            String tradeId = copy.getTradeId();
            Long ownerId = copy.getUserId();
            // Exit-side backoff: if this copy's close keeps failing (margin/AMO/market-closed), stop hammering it
            // every cycle — it stays paused for the cooldown, then a later pass retries (e.g. at market open).
            if (exitBackoffActive(tradeId)) {
                log.debug("[SignalCopy] userId={} tradeId={} skipped — exit backoff active", ownerId, tradeId);
                continue;
            }
            // Don't fire a second SELL while a close for this exact trade is already in flight (the close
            // places the order then waits for the fill, so status stays OPEN meanwhile) — avoids a naked short.
            if (!acquireCloseGuard(tradeId)) continue;
            submitted++;
            orderExecutor.submit(() -> runBrokerCallWithTimeout("exit u=" + ownerId + " t=" + tradeId, null, () -> {
                // ALIGNED-FIRE: arm this copy's exit, then it parks until the common instant before placing.
                if (fireAtNanos > 0) com.algo.trade.execution.ExecutionEngine.setSyncFireAt(fireAtNanos);
                try {
                    // closeTrade wraps runAsTradeOwner → correct per-user broker creds + alerts.
                    var result = executionEngine.closeTrade(tradeId, lastPrice, "COPY_EXIT: " + reason);
                    if (result.accepted()) {
                        exitRecordSuccess(tradeId);   // closed OK — clear any backoff for this trade
                        log.info("[SignalCopy] ✅ EXIT copied: userId={} tradeId={} ({})",
                                ownerId, tradeId, marketExit ? "MARKET/late" : "limit");
                    } else {
                        closeInFlight.remove(tradeId); // let a later deferred pass retry a rejected close
                        exitRecordReject(tradeId, result.reasons().isEmpty() ? "rejected" : result.reasons().getFirst());
                        log.warn("[SignalCopy] ❌ EXIT copy rejected: userId={} tradeId={} reasons={}",
                                ownerId, tradeId, result.reasons());
                        if (userNotificationService != null && !marketExit) {
                            userNotificationService.sendToUserOwnChannelOnly(ownerId,
                                    "⚠️ EXIT FAILED for your copied position " + copy.getInstrumentKey()
                                            + " — " + (result.reasons().isEmpty() ? "unknown" : result.reasons().getFirst())
                                            + ". Close manually if still open.");
                        }
                    }
                } catch (Exception e) {
                    closeInFlight.remove(tradeId);     // transient failure — allow a later pass to retry
                    exitRecordReject(tradeId, "exception: " + e.getMessage());
                    log.error("[SignalCopy] EXIT copy failed: userId={} tradeId={}: {}", ownerId, tradeId, e.getMessage());
                } finally {
                    com.algo.trade.execution.ExecutionEngine.clearSyncFireAt();
                }
            }));
        }
        return submitted;
    }

    /**
     * ALIGNED-FIRE exit fan-out: close all OPEN copies of this instrument NOW, each armed to fire at the common
     * {@code fireAtNanos} instant (so the primary + all copies place their exits together). Returns the number
     * of copies dispatched — the primary uses this to decide whether to align (park) its own exit. The deferred
     * race-catch retries still run via {@link #fireExitForAllUsersAsync} at exit completion.
     */
    public int fireExitAlignedNow(com.algo.trade.persistence.TradeEntity sourceTrade,
                                  BigDecimal lastPrice, String reason, long fireAtNanos) {
        if (!multiUserEnabled || !signalCopyEnabled) return 0;
        if (tradeRepository == null || executionEngine == null) return 0;
        return closeOpenCopies(sourceTrade, lastPrice, reason, false, fireAtNanos, false);
    }

    /**
     * DURABLE EXIT RECONCILER (2026-07-02) — the fire-and-forget +2/+5/+12s deferred closes in
     * {@link #fireExitForAllUsersAsync} die on a JVM restart, which can strand a copy OPEN while its primary is
     * already flat. This periodic sweep re-derives that state from the DB every run (so it survives restarts):
     * for each primary (default-user) trade that CLOSED within the look-back window, force-close any still-OPEN
     * copy that is provably paired to it by ENTRY CORRELATION KEY (strict match — never strategy-only here, to
     * avoid touching an unrelated independent re-entry). Idempotent (acquireCloseGuard + the OPEN-status filter),
     * so once a copy is closed it stops matching. Gated behind the robust-fan-out flag.
     */
    @org.springframework.scheduling.annotation.Scheduled(
            fixedDelayString = "${trading.multiuser.fanout.reconcile-interval-ms:20000}", initialDelay = 45_000)
    public void reconcileStrandedCopies() {
        if (!robustFanoutEnabled) return;
        if (!multiUserEnabled || !signalCopyEnabled) return;
        if (tradeRepository == null || executionEngine == null) return;
        try {
            java.time.Instant cutoff = java.time.Instant.now().minus(java.time.Duration.ofMinutes(reconcileWindowMin));
            List<com.algo.trade.persistence.TradeEntity> recentlyClosedPrimary =
                    tradeRepository.findByUserIdAndStatus(UserContext.DEFAULT_USER_ID, com.algo.trade.domain.TradeStatus.CLOSED)
                            .stream()
                            .filter(t -> t.getExitTime() != null && t.getExitTime().isAfter(cutoff))
                            .filter(t -> t.getEntryCorrelationKey() != null && !t.getEntryCorrelationKey().isBlank())
                            .toList();
            int strandedClosed = 0;
            for (com.algo.trade.persistence.TradeEntity src : recentlyClosedPrimary) {
                strandedClosed += closeOpenCopies(src, null, "RECONCILE stranded (primary flat)", true, 0L, true);
            }
            if (strandedClosed > 0) {
                log.warn("[SignalCopy] RECONCILE: force-closed {} stranded copy(ies) whose primary was already flat "
                        + "(window={}min) — a restart likely dropped the deferred close.", strandedClosed, reconcileWindowMin);
            }
        } catch (Exception e) {
            log.error("[SignalCopy] reconcile sweep failed: {}", e.getMessage());
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
            breakerRecordFailure(targetUserId, "not-authenticated");  // health failure — trips breaker after N
            return;
        }

        // Per-user capital/risk gate: don't exceed user's max open positions
        int userMaxOpen = targetConfig.getMaxOpenPositions();
        if (userMaxOpen > 0) {
            // Quick count from session manager or trust UserAwareExecutionService to check
            // (it does check in executeEntryForUser, but we gate early to avoid unnecessary work)
            int profileMaxLotsDbg = (globalConfigService != null) ? globalConfigService.getMaxLotsPerTrade() : 0;
            log.debug("[SignalCopy] userId={} maxOpenPositions={}, profileMaxLotsPerTrade={}",
                    targetUserId, userMaxOpen, profileMaxLotsDbg);
        }

        // Cap copy-user lots to THIS user's assigned risk profile only (CONSERVATIVE/BALANCED/AGGRESSIVE).
        // We run inside UserContext.runAs(targetUserId), so globalConfigService.getMaxLotsPerTrade()
        // resolves from TradingConfigResolver → same source primary + RiskEngine use. UserBrokerConfig
        // maxLotsPerTrade is NOT applied here — risk profile is the single lot ceiling for all users.
        int profileMaxLots = (globalConfigService != null) ? globalConfigService.getMaxLotsPerTrade() : 0;
        int targetMaxLots = profileMaxLots > 0 ? profileMaxLots : 1;
        int indexLotSize = getContractLot(decision);
        int adjustedLotSize = Math.min(lotSize, targetMaxLots * indexLotSize);
        // Guarantee a valid lot multiple even if upstream lotSize was mis-folded.
        adjustedLotSize = Math.max(indexLotSize, (adjustedLotSize / indexLotSize) * indexLotSize);

        // Execute with target user's context — uses their Kite token
        log.info("[SignalCopy] Firing for userId={}: {} {} lots={}",
                targetUserId, decision.signalType(), decision.selectedInstrumentKey().orElse(""), adjustedLotSize);

        ExecutionResult result = executionService.executeEntryForUser(
                targetUserId, decision, optionPremium, adjustedLotSize, strategyConfig);

        if (result.accepted()) {
            // Success alert reaches the user via TelegramAlertService's per-user fan-out
            // (ExecutionEngine sends "Entry order filled"/"Limit order placed" under
            // this user's context) — no extra alert here to avoid duplicates.
            breakerRecordSuccess(targetUserId);   // healthy → reset/close the breaker
            log.info("[SignalCopy] ✅ userId={} order placed successfully", targetUserId);
        } else {
            String reason = result.reasons().isEmpty() ? "unknown" : result.reasons().getFirst();
            breakerRecordFailure(targetUserId, reason);
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

    /**
     * True exchange contract-lot size for the decision's underlying, from {@link com.algo.trade.domain.IndexType}
     * (the single source of truth). Replaces the previous hardcoded multiplier whose stale NIFTY=75
     * (vs the real 65) caused every secondary-user NIFTY order to be rejected as a non-multiple of 65.
     */
    private int getContractLot(StrategyDecision decision) {
        return com.algo.trade.domain.IndexType.from(decision.underlying()).lotSize();
    }

    // ---- Exit-side per-trade backoff (Tier 2, 2026-07-02) — stops a tight retry loop on an un-closable copy --
    private static final class ExitBackoff { int consecutiveRejects; long openUntilMs; }
    private final ConcurrentHashMap<String, ExitBackoff> exitBackoffs = new ConcurrentHashMap<>();

    /** True if this copy trade's exit is in a cooldown after repeated un-closable rejections — skip it this pass. */
    private boolean exitBackoffActive(String tradeId) {
        if (!robustFanoutEnabled) return false;
        ExitBackoff b = exitBackoffs.get(tradeId);
        return b != null && System.currentTimeMillis() < b.openUntilMs;
    }

    private void exitRecordReject(String tradeId, String reason) {
        if (!robustFanoutEnabled) return;
        ExitBackoff b = exitBackoffs.computeIfAbsent(tradeId, k -> new ExitBackoff());
        synchronized (b) {
            b.consecutiveRejects++;
            if (b.consecutiveRejects >= exitBackoffThreshold && b.openUntilMs <= System.currentTimeMillis()) {
                b.openUntilMs = System.currentTimeMillis() + exitBackoffCooldownMin * 60_000L;
                log.warn("[SignalCopy] ⏸ exit backoff for tradeId={} after {} consecutive un-closable rejections "
                                + "(last: {}) — pausing copy-exit retries {}min (add margin / wait for market open).",
                        tradeId, b.consecutiveRejects, reason, exitBackoffCooldownMin);
            }
        }
    }

    private void exitRecordSuccess(String tradeId) {
        if (!robustFanoutEnabled) return;
        exitBackoffs.remove(tradeId);
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 60_000)
    public void cleanDedupeCache() {
        long cutoff = System.currentTimeMillis() - (DEDUPE_WINDOW_MS * 10);
        recentSignalHashes.entrySet().removeIf(e -> e.getValue() < cutoff);
        // expire exit-backoff entries whose cooldown has fully elapsed (bounded cleanup)
        long now = System.currentTimeMillis();
        exitBackoffs.entrySet().removeIf(e -> e.getValue().openUntilMs != 0 && now > e.getValue().openUntilMs + 60_000L);
    }

    public Map<String, Long> getRecentSignals() { return Map.copyOf(recentSignalHashes); }
    public boolean isEnabled() { return multiUserEnabled && signalCopyEnabled; }

    public record CopyResult(Long userId, boolean success, String message) {}
}
