package com.algo.trade.execution;

import com.algo.trade.config.GlobalConfigService;
import com.algo.trade.config.TradingProperties;
import com.algo.trade.domain.ExecutionMode;
import com.algo.trade.domain.MarketDataMode;
import com.algo.trade.domain.TradingMode;
import com.algo.trade.domain.UnderlyingSymbol;
import com.algo.trade.risk.HaltMode;
import com.algo.trade.underlying.UnderlyingConfigService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Runtime control state used by REST endpoints and execution gates.
 */
@Service
public class TradingStateService {

    private static final Logger log = LoggerFactory.getLogger(TradingStateService.class);

    private final TradingProperties properties;
    private final GlobalConfigService globalConfigService;
    private final UnderlyingConfigService underlyingConfigService;
    private final AtomicReference<TradingMode> requestedMode;
    private final AtomicReference<MarketDataMode> marketDataMode;
    private final AtomicReference<ExecutionMode> executionMode;
    private final AtomicReference<EnumSet<UnderlyingSymbol>> enabledUnderlyings;
    private final AtomicReference<RuntimeState> runtimeState =
            new AtomicReference<>(new RuntimeState(false, false, Instant.now()));
    private final AtomicReference<Instant> updatedAt = new AtomicReference<>(Instant.now());
    private final AtomicReference<Instant> lastScanAt = new AtomicReference<>(null);
    private volatile boolean schedulerEnabled;
    private volatile HaltMode haltMode = HaltMode.NONE;
    private volatile boolean dailyApproved = false;
    private volatile LocalDate approvalDate = null;
    private volatile double dailyLossExtension = 0;
    private volatile int extensionsUsedToday = 0;
    private static final int MAX_EXTENSIONS = 2;

    /** Hourly trade timestamps for max-trades-per-hour cap. */
    private final java.util.Deque<Instant> recentTradeTimestamps = new java.util.concurrent.ConcurrentLinkedDeque<>();
    /** Rolling win/loss counters for win-rate auto-pause. */
    private final java.util.concurrent.atomic.AtomicInteger rollingWins = new java.util.concurrent.atomic.AtomicInteger(0);
    private final java.util.concurrent.atomic.AtomicInteger rollingTotal = new java.util.concurrent.atomic.AtomicInteger(0);

    public TradingStateService(TradingProperties properties, GlobalConfigService globalConfigService,
                               UnderlyingConfigService underlyingConfigService) {
        this.properties = properties;
        this.globalConfigService = globalConfigService;
        this.underlyingConfigService = underlyingConfigService;
        // Read persisted underlyings from DB; fall back to YAML if DB has none
        List<UnderlyingSymbol> persisted = globalConfigService.getEnabledUnderlyings();
        EnumSet<UnderlyingSymbol> configuredUnderlyings = persisted.isEmpty()
                ? EnumSet.of(UnderlyingSymbol.NIFTY)
                : EnumSet.copyOf(persisted);
        this.enabledUnderlyings = new AtomicReference<>(configuredUnderlyings);
        this.requestedMode = new AtomicReference<>(properties.mode());
        this.marketDataMode = new AtomicReference<>(properties.marketDataMode());
        this.executionMode = new AtomicReference<>(properties.executionMode());
        this.schedulerEnabled = properties.algo().schedulerEnabled();
        // Auto-approve on startup so trading is not blocked by default.
        // The daily approval gate resets at midnight and auto-approves at 10:30 AM on weekdays.
        this.dailyApproved = true;
        this.approvalDate = LocalDate.now();
    }

    public void start() {
        RuntimeState previous = runtimeState.getAndUpdate(state ->
                state.killSwitch ? state.withTimestamp(Instant.now()) : new RuntimeState(true, state.killSwitch, Instant.now()));
        RuntimeState current = runtimeState.get();
        Instant timestamp = Instant.now();
        updatedAt.set(timestamp);
        log.info("Trading state started: wasRunning={}, running={}, requestedMode={}, killSwitch={}, updatedAt={}",
                previous.running, current.running, requestedMode.get(), current.killSwitch, current.updatedAt);
    }

    public void stop() {
        RuntimeState previous = runtimeState.getAndUpdate(state -> new RuntimeState(false, state.killSwitch, Instant.now()));
        RuntimeState current = runtimeState.get();
        updatedAt.set(current.updatedAt);
        log.info("Trading state stopped: wasRunning={}, running={}, requestedMode={}, killSwitch={}, updatedAt={}",
                previous.running, current.running, requestedMode.get(), current.killSwitch, current.updatedAt);
    }

    // ── Halt mode ─────────────────────────────────────────────────────────────

    public void softHalt(String reason) {
        haltMode = HaltMode.SOFT;
        log.warn("Soft halt activated: reason={}", reason);
    }

    public void hardHalt(String reason) {
        haltMode = HaltMode.HARD;
        runtimeState.updateAndGet(s -> new RuntimeState(false, true, Instant.now()));
        updatedAt.set(Instant.now());
        log.warn("Hard halt activated: reason={}", reason);
    }

    public void resumeFromHalt() {
        haltMode = HaltMode.NONE;
        runtimeState.updateAndGet(s -> new RuntimeState(s.running, false, Instant.now()));
        updatedAt.set(Instant.now());
        log.info("Halt cleared — trading resumed");
    }

    public HaltMode haltMode() { return haltMode; }
    public boolean isEntryAllowed() { return running() && !killSwitchEnabled() && haltMode == HaltMode.NONE; }
    public boolean isExitAllowed() { return haltMode != HaltMode.HARD; }

    // ── Daily approval gate ───────────────────────────────────────────────────

    public boolean isDailyApproved() {
        LocalDate today = LocalDate.now();
        if (!today.equals(approvalDate)) { dailyApproved = false; approvalDate = today; }
        return dailyApproved;
    }

    public void approveToday() {
        dailyApproved = true;
        approvalDate = LocalDate.now();
        log.info("Daily trading approved for {}", approvalDate);
    }

    public void revokeApproval() {
        dailyApproved = false;
        log.warn("Daily trading approval revoked");
    }

    @Scheduled(cron = "0 30 10 * * MON-FRI")
    public void autoApproveIfNeeded() {
        if (!isDailyApproved()) {
            approveToday();
            log.warn("Auto-approved trading at 10:30 AM");
        }
    }

    @Scheduled(cron = "0 0 0 * * *")
    public void resetDailyApproval() {
        dailyApproved = false;
        dailyLossExtension = 0;
        extensionsUsedToday = 0;
        log.info("Daily approval and loss extension reset for new day");
    }

    // ── Daily loss limit extension ────────────────────────────────────────────

    public double dailyLossExtension() { return dailyLossExtension; }
    public int extensionsUsedToday() { return extensionsUsedToday; }

    public String extendDailyLimit() {
        if (extensionsUsedToday >= MAX_EXTENSIONS)
            return "Max extensions reached (" + MAX_EXTENSIONS + "/day)";
        double baseLimit = properties.risk().totalCapital().doubleValue()
                * properties.risk().maxDailyLossPercent().doubleValue() / 100.0;
        // Each extension adds 50% of base (not 100%) — max total = 2× base with 2 extensions
        double extensionAmount = baseLimit * 0.5;
        dailyLossExtension += extensionAmount;
        extensionsUsedToday++;
        resumeFromHalt();
        String msg = String.format("Daily loss limit extended by \u20b9%.0f (extension %d/%d, total extension \u20b9%.0f). Trading resumed.",
                extensionAmount, extensionsUsedToday, MAX_EXTENSIONS, dailyLossExtension);
        log.warn(msg);
        return msg;
    }

    // ── Trading readiness status ──────────────────────────────────────────────

    /**
     * Returns a consolidated list of all reasons currently blocking new entries.
     * Covers all 4 layers: scanner state, halt/approval, risk limits, market guard.
     * Empty list = all clear, entries are allowed.
     *
     * @param maxOpenTrades from GlobalConfigService (DB-backed)
     * @param maxTradesPerDay from GlobalConfigService (DB-backed)
     * @param maxConsecutiveLosses from GlobalConfigService (DB-backed)
     * @param dailyLossLimit effective daily loss limit (base + extensions)
     */
    public List<String> entryBlockReasons(BigDecimal dailyPnl, int openTrades, int tradesToday, int consecutiveLosses, boolean wsConnected,
                                           int maxOpenTrades, int maxTradesPerDay, int maxConsecutiveLosses, BigDecimal dailyLossLimit) {
        List<String> reasons = new ArrayList<>();

        // Layer 1 — Scanner / data feed state (only relevant during market hours)
        if (isMarketHours()) {
            // Only block if there is NO active market data trigger at all
            if (!wsConnected && !schedulerEnabled())
                reasons.add("No market data trigger — WebSocket disconnected and REST poll disabled");
        }
        if (killSwitchEnabled()) reasons.add("Kill switch is enabled");

        // Layer 2 — Halt mode and daily approval
        if (haltMode() == HaltMode.HARD) reasons.add("Hard halt is active");
        if (haltMode() == HaltMode.SOFT) reasons.add("Soft halt — no new entries allowed");
        if (!isDailyApproved()) reasons.add("Daily trading not approved yet");

        // Layer 3 — Risk limits (using DB-backed values passed by caller)
        if (openTrades >= maxOpenTrades)
            reasons.add("Max open trades limit reached (" + openTrades + "/" + maxOpenTrades + ")");
        if (tradesToday >= maxTradesPerDay)
            reasons.add("Max trades per day reached (" + tradesToday + "/" + maxTradesPerDay + ")");
        if (consecutiveLosses >= maxConsecutiveLosses)
            reasons.add("Max consecutive losses reached (" + consecutiveLosses + ")");
        if (dailyPnl != null && dailyLossLimit != null && dailyPnl.compareTo(dailyLossLimit.negate()) <= 0)
            reasons.add(String.format("Max daily loss reached (\u20b9%.0f / \u20b9%.0f)",
                    dailyPnl.abs().doubleValue(), dailyLossLimit.doubleValue()));

        return reasons;
    }

    public void enableKillSwitch() {
        RuntimeState previous = runtimeState.getAndUpdate(state -> new RuntimeState(false, true, Instant.now()));
        RuntimeState current = runtimeState.get();
        updatedAt.set(current.updatedAt);
        log.warn("Kill switch enabled: wasKillSwitchEnabled={}, wasRunning={}, running={}, requestedMode={}, updatedAt={}",
                previous.killSwitch, previous.running, current.running, requestedMode.get(), current.updatedAt);
    }

    public void clearKillSwitch() {
        RuntimeState previous = runtimeState.getAndUpdate(state -> new RuntimeState(state.running, false, Instant.now()));
        RuntimeState current = runtimeState.get();
        updatedAt.set(current.updatedAt);
        log.warn("Kill switch cleared: wasKillSwitchEnabled={}, running={}, requestedMode={}, updatedAt={}",
                previous.killSwitch, current.running, requestedMode.get(), current.updatedAt);
    }

    public void setRequestedMode(TradingMode mode) {
        TradingMode previousMode = requestedMode.getAndSet(mode);
        Instant timestamp = Instant.now();
        updatedAt.set(timestamp);
        log.info("Requested trading mode changed: previousMode={}, requestedMode={}, running={}, killSwitch={}, updatedAt={}",
                previousMode, mode, running(), killSwitchEnabled(), timestamp);
    }

    public void setUnderlyingScanEnabled(UnderlyingSymbol underlying, boolean enabled) {
        EnumSet<UnderlyingSymbol> updatedUnderlyings = enabledUnderlyings.updateAndGet(previous -> {
            EnumSet<UnderlyingSymbol> next = EnumSet.copyOf(previous);
            if (enabled) {
                next.add(underlying);
            } else {
                next.remove(underlying);
            }
            if (next.isEmpty()) {
                next.add(UnderlyingSymbol.NIFTY);
            }
            return next;
        });
        globalConfigService.persistEnabledUnderlyings(List.copyOf(updatedUnderlyings));
        if (enabled) underlyingConfigService.enable(underlying);
        else underlyingConfigService.disable(underlying);
        Instant timestamp = Instant.now();
        updatedAt.set(timestamp);
        log.info("Underlying scan state changed: underlying={}, enabled={}, enabledUnderlyings={}, updatedAt={}",
                underlying, enabled, updatedUnderlyings, timestamp);
    }

    public void setRoutingModes(MarketDataMode marketDataMode, ExecutionMode executionMode) {
        MarketDataMode previousMarketDataMode = this.marketDataMode.get();
        ExecutionMode previousExecutionMode = this.executionMode.get();
        if (marketDataMode != null) {
            this.marketDataMode.set(marketDataMode);
        }
        if (executionMode != null) {
            this.executionMode.set(executionMode);
        }
        Instant timestamp = Instant.now();
        updatedAt.set(timestamp);
        log.info("Broker routing modes changed: previousMarketDataMode={}, marketDataMode={}, previousExecutionMode={}, executionMode={}, updatedAt={}",
                previousMarketDataMode, this.marketDataMode.get(), previousExecutionMode, this.executionMode.get(), timestamp);
    }

    public boolean running() { return runtimeState.get().running; }
    public boolean killSwitchEnabled() { return runtimeState.get().killSwitch; }
    public boolean schedulerEnabled() { return schedulerEnabled; }
    public TradingMode requestedMode() { return requestedMode.get(); }
    public MarketDataMode marketDataMode() { return marketDataMode.get(); }
    public ExecutionMode executionMode() { return executionMode.get(); }
    public List<UnderlyingSymbol> enabledUnderlyings() { return List.copyOf(enabledUnderlyings.get()); }
    public Instant updatedAt() { return updatedAt.get(); }

    public void recordScan() { lastScanAt.set(Instant.now()); }
    public Instant lastScanAt() { return lastScanAt.get(); }

    // ── Evaluation counters (in-memory, reset daily) ─────────────────────────
    private final java.util.concurrent.atomic.AtomicLong totalEvaluations = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong totalBlocked = new java.util.concurrent.atomic.AtomicLong(0);

    public void recordEvaluation() { totalEvaluations.incrementAndGet(); }
    public void recordBlocked() { totalBlocked.incrementAndGet(); }
    public long getTotalEvaluations() { return totalEvaluations.get(); }
    public long getTotalBlocked() { return totalBlocked.get(); }
    public void resetEvaluationCounters() { totalEvaluations.set(0); totalBlocked.set(0); }

    public void setSchedulerEnabled(boolean enabled) {
        this.schedulerEnabled = enabled;
        updatedAt.set(Instant.now());
        log.info("REST poll scheduler toggled: enabled={}", enabled);
    }

    private boolean isMarketHours() {
        java.time.LocalTime now = java.time.LocalTime.now(properties.timezone());
        java.time.DayOfWeek day = LocalDate.now(properties.timezone()).getDayOfWeek();
        if (day == java.time.DayOfWeek.SATURDAY || day == java.time.DayOfWeek.SUNDAY) return false;
        return now.isAfter(java.time.LocalTime.of(9, 15))
                && now.isBefore(java.time.LocalTime.of(15, 30));
    }

    private record RuntimeState(boolean running, boolean killSwitch, Instant updatedAt) {
        RuntimeState withTimestamp(Instant timestamp) {
            return new RuntimeState(running, killSwitch, timestamp);
        }
    }

    // ── Hourly trade cap + rolling win rate ────────────────────────────────

    /** Record a trade entry for hourly cap tracking. */
    public void recordTradeEntry() {
        recentTradeTimestamps.addLast(Instant.now());
        // Prune entries older than 2 hours
        Instant cutoff = Instant.now().minus(java.time.Duration.ofHours(2));
        while (!recentTradeTimestamps.isEmpty() && recentTradeTimestamps.peekFirst().isBefore(cutoff)) {
            recentTradeTimestamps.pollFirst();
        }
    }

    /** Record a trade outcome for rolling win-rate tracking. */
    public void recordTradeOutcome(boolean win) {
        rollingTotal.incrementAndGet();
        if (win) rollingWins.incrementAndGet();
    }

    /** Number of trades placed in the last 60 minutes. */
    public int tradesInLastHour() {
        Instant oneHourAgo = Instant.now().minus(java.time.Duration.ofHours(1));
        return (int) recentTradeTimestamps.stream().filter(t -> t.isAfter(oneHourAgo)).count();
    }

    /** Rolling win rate as percentage (0-100). Returns 100 if no trades yet. */
    public double rollingWinRate() {
        int total = rollingTotal.get();
        return total > 0 ? (double) rollingWins.get() / total * 100 : 100.0;
    }

    /** Reset daily counters at midnight. */
    @Scheduled(cron = "0 0 0 * * *")
    public void resetDailyCounters() {
        rollingWins.set(0);
        rollingTotal.set(0);
        recentTradeTimestamps.clear();
        log.info("Daily trade counters reset");
    }
}
