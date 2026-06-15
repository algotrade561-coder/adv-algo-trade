package com.algo.trade.strategy;

import com.algo.trade.config.GlobalConfigService;
import com.algo.trade.domain.*;
import com.algo.trade.marketdata.ExpiryCalendar;
import com.algo.trade.risk.AdaptiveHaltManager;
import com.algo.trade.risk.MarketGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Universal Call Orchestrator — centralized signal aggregation and immediate execution layer.
 *
 * <p>Sits above ALL individual strategies. Instead of each strategy independently deciding
 * whether to place trades, this orchestrator:</p>
 * <ol>
 *   <li>Collects signal objects from all active strategies into a shared queue</li>
 *   <li>Aggregates confidence scores when multiple strategies fire simultaneously</li>
 *   <li>Resolves conflicts (CALL vs PUT) using net confidence</li>
 *   <li>Scales lot size proportionally to aggregated confidence</li>
 *   <li>Executes immediately when any signal arrives (no consensus waiting)</li>
 * </ol>
 *
 * <h3>Execution Rules:</h3>
 * <ul>
 *   <li>Single strategy fires → base lot size, immediate execution</li>
 *   <li>Multiple strategies fire same direction → aggregate confidence, scale lots</li>
 *   <li>Conflicting signals → net confidence decides direction; equal → straddle/hedge</li>
 * </ul>
 *
 * <h3>Lot Sizing Formula:</h3>
 * <pre>
 *   LotSize = BaseLot × min(maxLotMultiplier, sum(confidence) / 100)
 * </pre>
 *
 * <h3>Safeguards:</h3>
 * <ul>
 *   <li>Duplicate strike prevention: no double orders on same strike within window</li>
 *   <li>Cooldown: 120s between identical signals from same strategy</li>
 *   <li>Max position cap: respects global maxOpenTrades</li>
 *   <li>Expiry rules: allows OTM scalps after 14:30</li>
 * </ul>
 */
@Component
public class UniversalCallOrchestrator implements com.algo.trade.execution.DailyResettable {

    private static final Logger log = LoggerFactory.getLogger(UniversalCallOrchestrator.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    // ── Dependencies ──────────────────────────────────────────────────────

    private final GlobalConfigService globalConfigService;
    private final ExpiryCalendar expiryCalendar;
    private final MarketGuard marketGuard;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private AdaptiveHaltManager adaptiveHaltManager;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.notification.TelegramAlertService alertService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ExpiryBehaviorTuner expiryBehaviorTuner;

    // ── Configuration ─────────────────────────────────────────────────────

    @Value("${trading.orchestrator.enabled:true}")
    private boolean enabled;

    /** Base lot count for single-strategy signals. */
    @Value("${trading.orchestrator.base-lots:1}")
    private int baseLots;

    /** Maximum lot multiplier cap (even with 20 strategies firing, don't exceed this). */
    @Value("${trading.orchestrator.max-lot-multiplier:5}")
    private int maxLotMultiplier;

    /** Cooldown between identical signals from the same strategy (seconds). */
    @Value("${trading.orchestrator.signal-cooldown-seconds:120}")
    private int signalCooldownSeconds;

    /** Aggregation window: signals within this window are aggregated (milliseconds). */
    @Value("${trading.orchestrator.aggregation-window-ms:2000}")
    private long aggregationWindowMs;

    /** Minimum net confidence to execute when signals conflict. Below this → hedge. */
    @Value("${trading.orchestrator.min-net-confidence-for-direction:20}")
    private int minNetConfidenceForDirection;

    /** Maximum open positions across all indices. */
    @Value("${trading.orchestrator.max-open-positions:6}")
    private int maxOpenPositions;

    // ── Signal Queue & State ──────────────────────────────────────────────

    /** Incoming signal queue — strategies push signals here. */
    private final Deque<StrategySignal> signalQueue = new ConcurrentLinkedDeque<>();

    /** Cooldown tracker: "strategy|index|direction" → last signal timestamp. */
    private final Map<String, Instant> cooldownMap = new ConcurrentHashMap<>();

    /** Active strikes: "index|strike|optType" → orderId (prevents duplicates). */
    private final Map<String, String> activeStrikes = new ConcurrentHashMap<>();

    /** Today's execution count per index. */
    private final Map<IndexType, Integer> executionsToday = new ConcurrentHashMap<>();

    public UniversalCallOrchestrator(GlobalConfigService globalConfigService,
                                     ExpiryCalendar expiryCalendar,
                                     MarketGuard marketGuard) {
        this.globalConfigService = globalConfigService;
        this.expiryCalendar = expiryCalendar;
        this.marketGuard = marketGuard;
    }

    // ── Public API: Signal Submission ─────────────────────────────────────

    /**
     * Submit a signal from any strategy. This is the universal entry point.
     * The orchestrator will decide whether to execute immediately or aggregate.
     *
     * @param signal the strategy signal to submit
     * @return the orchestrator's decision (execute, aggregate, reject)
     */
    public OrchestratorDecision submitSignal(StrategySignal signal) {
        if (!enabled) return OrchestratorDecision.disabled();
        if (signal == null || signal.confidence <= 0) return OrchestratorDecision.rejected("null_or_zero_confidence");

        // Safeguard: adaptive halt
        if (adaptiveHaltManager != null && adaptiveHaltManager.isAdaptiveHalted()) {
            return OrchestratorDecision.rejected("adaptive_halt_active");
        }

        // Safeguard: circuit breaker
        if (marketGuard.isCircuitBreakerTriggered()) {
            return OrchestratorDecision.rejected("circuit_breaker");
        }

        // Safeguard: expiry behavior crash halt
        if (expiryBehaviorTuner != null) {
            UnderlyingSymbol underlying = mapToUnderlying(signal.indexType);
            if (underlying != null && expiryBehaviorTuner.currentMode(underlying)
                    == ExpiryBehaviorTuner.ExpiryMode.CRASH_HALT) {
                return OrchestratorDecision.rejected("crash_halt");
            }
        }

        // Safeguard: cooldown check
        String cooldownKey = signal.strategy + "|" + signal.indexType + "|" + signal.direction;
        Instant lastSignal = cooldownMap.get(cooldownKey);
        if (lastSignal != null && Duration.between(lastSignal, Instant.now()).getSeconds() < signalCooldownSeconds) {
            return OrchestratorDecision.rejected("cooldown(" + cooldownKey + ")");
        }

        // Safeguard: duplicate strike prevention
        if (signal.strike > 0) {
            String strikeKey = signal.indexType + "|" + signal.strike + "|" + signal.optionType;
            if (activeStrikes.containsKey(strikeKey)) {
                return OrchestratorDecision.rejected("duplicate_strike(" + strikeKey + ")");
            }
        }

        // Add to queue
        signalQueue.addLast(signal);
        cooldownMap.put(cooldownKey, Instant.now());

        // Aggregate and decide
        return aggregate(signal.indexType);
    }

    /**
     * Force-evaluate the signal queue (called by the tick loop or scheduler).
     * Processes any pending signals that arrived within the aggregation window.
     */
    public Optional<OrchestratorDecision> evaluatePending(IndexType indexType) {
        if (!enabled) return Optional.empty();
        if (signalQueue.isEmpty()) return Optional.empty();

        OrchestratorDecision decision = aggregate(indexType);
        return decision.action != Action.NONE ? Optional.of(decision) : Optional.empty();
    }

    // ── Public API: Position Management ───────────────────────────────────

    /**
     * Record that a position was opened (for duplicate prevention).
     */
    public void recordPositionOpen(IndexType indexType, int strike, String optionType, String orderId) {
        String key = indexType + "|" + strike + "|" + optionType;
        activeStrikes.put(key, orderId);
        executionsToday.merge(indexType, 1, Integer::sum);
    }

    /**
     * Record that a position was closed (releases the strike lock).
     */
    public void recordPositionClose(IndexType indexType, int strike, String optionType) {
        String key = indexType + "|" + strike + "|" + optionType;
        activeStrikes.remove(key);
    }

    /**
     * Reset daily state.
     */
    public void resetDaily() {
        signalQueue.clear();
        cooldownMap.clear();
        activeStrikes.clear();
        executionsToday.clear();
        log.info("[Orchestrator] Daily state reset.");
    }

    /**
     * Diagnostics / API status.
     */
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", enabled);
        status.put("pendingSignals", signalQueue.size());
        status.put("activeStrikes", activeStrikes.size());
        status.put("executionsToday", new LinkedHashMap<>(executionsToday));
        status.put("baseLots", baseLots);
        status.put("maxLotMultiplier", maxLotMultiplier);
        return status;
    }

    // ── Aggregation Engine ────────────────────────────────────────────────

    private OrchestratorDecision aggregate(IndexType indexType) {
        Instant cutoff = Instant.now().minusMillis(aggregationWindowMs);

        // Collect signals within the aggregation window for this index
        List<StrategySignal> windowSignals = new ArrayList<>();
        for (StrategySignal s : signalQueue) {
            if (s.indexType == indexType && s.timestamp.isAfter(cutoff)) {
                windowSignals.add(s);
            }
        }

        if (windowSignals.isEmpty()) return OrchestratorDecision.none();

        // Separate into CALL (bullish) and PUT (bearish)
        int callConfidence = 0;
        int putConfidence = 0;
        List<String> callStrategies = new ArrayList<>();
        List<String> putStrategies = new ArrayList<>();

        for (StrategySignal s : windowSignals) {
            if (s.direction > 0) {
                callConfidence += s.confidence;
                callStrategies.add(s.strategy + "(" + s.confidence + ")");
            } else if (s.direction < 0) {
                putConfidence += s.confidence;
                putStrategies.add(s.strategy + "(" + s.confidence + ")");
            }
        }

        // Conflict resolution: net confidence decides
        int netConfidence = callConfidence - putConfidence;
        int direction;
        int totalConfidence;
        List<String> contributors;

        if (Math.abs(netConfidence) < minNetConfidenceForDirection) {
            // Nearly equal → hedge (straddle both sides)
            direction = 0;
            totalConfidence = callConfidence + putConfidence;
            contributors = new ArrayList<>();
            contributors.addAll(callStrategies);
            contributors.addAll(putStrategies);

            log.info("[Orchestrator] {} HEDGE: CALL={} vs PUT={} (net={} < threshold {}). Straddle recommended.",
                    indexType, callConfidence, putConfidence, netConfidence, minNetConfidenceForDirection);

            // Clean up processed signals
            cleanupProcessedSignals(windowSignals);

            return new OrchestratorDecision(Action.HEDGE, indexType, direction,
                    totalConfidence, computeLots(totalConfidence / 2), // half lots each side
                    contributors, "CALL=" + callConfidence + " vs PUT=" + putConfidence + " → hedge");
        }

        if (netConfidence > 0) {
            direction = 1;
            totalConfidence = callConfidence;
            contributors = callStrategies;
        } else {
            direction = -1;
            totalConfidence = putConfidence;
            contributors = putStrategies;
        }

        // Compute lot size
        int lots = computeLots(totalConfidence);

        // Position cap check
        int totalActive = activeStrikes.size();
        if (totalActive >= maxOpenPositions) {
            cleanupProcessedSignals(windowSignals);
            return OrchestratorDecision.rejected("max_positions_reached(" + totalActive + "/" + maxOpenPositions + ")");
        }

        // Expiry rules: allow OTM scalps after 14:30 on expiry day
        boolean isExpiryDay = expiryCalendar.isExpiryDay(indexType);
        LocalTime now = LocalTime.now(IST);
        boolean expiryScalpWindow = isExpiryDay && now.isAfter(LocalTime.of(14, 30));

        String reason = String.format("%d strategies fired %s (confidence=%d, lots=%d)%s",
                contributors.size(), direction > 0 ? "CALL" : "PUT",
                totalConfidence, lots,
                expiryScalpWindow ? " [EXPIRY_SCALP_WINDOW]" : "");

        log.info("[Orchestrator] {} EXECUTE: dir={} confidence={} lots={} contributors={}",
                indexType, direction > 0 ? "CALL" : "PUT", totalConfidence, lots, contributors);

        if (alertService != null && contributors.size() >= 3) {
            alertService.systemAlert(String.format(
                    "🎯 ORCHESTRATOR — %s %s\n%d strategies aligned (confidence: %d)\nLots: %d\nContributors: %s",
                    indexType, direction > 0 ? "CALL" : "PUT",
                    contributors.size(), totalConfidence, lots,
                    String.join(", ", contributors)));
        }

        // Clean up processed signals
        cleanupProcessedSignals(windowSignals);

        return new OrchestratorDecision(Action.EXECUTE, indexType, direction,
                totalConfidence, lots, contributors, reason);
    }

    /**
     * Lot sizing: BaseLot × min(maxMultiplier, totalConfidence / 100).
     * 1 strategy at 70 confidence → 1 lot.
     * 5 strategies at 70 each (350) → 3 lots.
     * 10 strategies at 80 each (800) → capped at maxLotMultiplier (5) lots.
     */
    private int computeLots(int totalConfidence) {
        double rawMultiplier = (double) totalConfidence / 100.0;
        int multiplier = (int) Math.ceil(Math.min(rawMultiplier, maxLotMultiplier));
        int lots = baseLots * Math.max(1, multiplier);

        // Apply recovery aggressiveness from AdaptiveHaltManager
        if (adaptiveHaltManager != null) {
            double aggressiveness = adaptiveHaltManager.getRecoveryAggressiveness();
            if (aggressiveness < 1.0) {
                lots = Math.max(baseLots, (int) Math.round(lots * aggressiveness));
            }
        }

        // Global cap from config
        int globalMax = globalConfigService.getMaxLotsPerTrade();
        return Math.min(lots, Math.max(1, globalMax));
    }

    private void cleanupProcessedSignals(List<StrategySignal> processed) {
        signalQueue.removeAll(processed);
        // Also purge stale signals (older than 10s) to prevent unbounded growth
        Instant stale = Instant.now().minusSeconds(10);
        signalQueue.removeIf(s -> s.timestamp.isBefore(stale));
    }

    private UnderlyingSymbol mapToUnderlying(IndexType idx) {
        return switch (idx) {
            case NIFTY -> UnderlyingSymbol.NIFTY;
            case BANKNIFTY -> UnderlyingSymbol.BANKNIFTY;
            case SENSEX -> UnderlyingSymbol.SENSEX;
            case FINNIFTY -> UnderlyingSymbol.FINNIFTY;
            case MIDCPNIFTY -> UnderlyingSymbol.MIDCPNIFTY;
        };
    }

    // ── Signal & Decision Records ─────────────────────────────────────────

    /**
     * A signal submitted by any strategy to the orchestrator.
     */
    public record StrategySignal(
            String strategy,
            IndexType indexType,
            int direction,       // +1 = CALL/bullish, -1 = PUT/bearish
            int confidence,      // 0-100 per strategy
            int strike,          // 0 if not yet resolved
            String optionType,   // "CE" or "PE" or null
            Instant timestamp,
            String reason
    ) {
        public static StrategySignal call(String strategy, IndexType idx, int confidence, String reason) {
            return new StrategySignal(strategy, idx, 1, confidence, 0, "CE", Instant.now(), reason);
        }

        public static StrategySignal put(String strategy, IndexType idx, int confidence, String reason) {
            return new StrategySignal(strategy, idx, -1, confidence, 0, "PE", Instant.now(), reason);
        }

        public static StrategySignal withStrike(String strategy, IndexType idx, int direction,
                                                 int confidence, int strike, String optionType, String reason) {
            return new StrategySignal(strategy, idx, direction, confidence, strike, optionType, Instant.now(), reason);
        }
    }

    public enum Action { NONE, EXECUTE, HEDGE, REJECTED, DISABLED }

    /**
     * The orchestrator's decision after aggregating signals.
     */
    public record OrchestratorDecision(
            Action action,
            IndexType indexType,
            int direction,
            int totalConfidence,
            int lots,
            List<String> contributors,
            String reason
    ) {
        public static OrchestratorDecision none() {
            return new OrchestratorDecision(Action.NONE, null, 0, 0, 0, List.of(), "");
        }

        public static OrchestratorDecision rejected(String reason) {
            return new OrchestratorDecision(Action.REJECTED, null, 0, 0, 0, List.of(), reason);
        }

        public static OrchestratorDecision disabled() {
            return new OrchestratorDecision(Action.DISABLED, null, 0, 0, 0, List.of(), "orchestrator_disabled");
        }

        public boolean shouldExecute() { return action == Action.EXECUTE; }
        public boolean shouldHedge() { return action == Action.HEDGE; }
        public boolean isRejected() { return action == Action.REJECTED; }
        public boolean isCall() { return direction > 0; }
        public boolean isPut() { return direction < 0; }
    }
}
