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

    /**
     * When true, a normal (non-kill-switch, non-HARD-halt) STOP keeps the scan loops running so every
     * strategy still EVALUATES and RECORDS tuning data — only order placement is suppressed. Lets a
     * data-collection week run with orders paused but full capture flowing. Default false = legacy
     * behaviour (stop halts scanning + capture too). Kill switch / HARD halt always stop everything.
     */
    @org.springframework.beans.factory.annotation.Value("${trading.capture-when-stopped:false}")
    private boolean captureWhenStopped;

    // ── Rolling win-rate auto-pause config ───────────────────────────────────
    // All keys default to the historical hardcoded behaviour (enabled, 5-trade window, 25% threshold,
    // per-user blended, NO scratch dead-band) so there is ZERO behaviour change out of the box. Tune via
    // application.yml (trading.winrate-pause.*) or environment to relax/tighten the auto-pause.

    /** Master enable flag for the rolling win-rate auto-pause gate. Default true (historical behaviour). */
    @org.springframework.beans.factory.annotation.Value("${trading.winrate-pause.enabled:true}")
    private boolean winratePauseEnabled;

    /** Minimum number of trades (in the relevant bucket) before the win-rate gate can pause. Default 5. */
    @org.springframework.beans.factory.annotation.Value("${trading.winrate-pause.min-trades:5}")
    private int winratePauseMinTrades;

    /** Win-rate percent (0-100) below which entries are paused once the min-trades window is reached. Default 25. */
    @org.springframework.beans.factory.annotation.Value("${trading.winrate-pause.threshold-percent:25.0}")
    private double winratePauseThresholdPercent;

    /** When true the win-rate is computed PER (user, strategy) so one strategy's losing streak does not pause
     *  unrelated strategies. Default false = per-user blended across strategies (historical behaviour). */
    @org.springframework.beans.factory.annotation.Value("${trading.winrate-pause.per-strategy:false}")
    private boolean winratePausePerStrategy;

    /** Scratch dead-band, absolute ₹: a closed trade whose |realized P&L| is ≤ this counts as NEITHER a win
     *  nor a loss (excluded from the ratio). Default 0 = OFF (every non-positive P&L is a full loss, as before).
     *  Set e.g. 50 so a ~flat sub-minute stop-out no longer drags the win rate. */
    @org.springframework.beans.factory.annotation.Value("${trading.winrate-pause.scratch-abs:0}")
    private double winratePauseScratchAbs;

    /** Scratch dead-band, percent of entry notional: a close whose |realized P&L| is ≤ this % of (entryPrice ×
     *  qty) counts as a scratch. Default 0 = OFF. OR-combined with the absolute band. Set e.g. 0.25 for 0.25%. */
    @org.springframework.beans.factory.annotation.Value("${trading.winrate-pause.scratch-percent:0}")
    private double winratePauseScratchPercent;

    /** Hourly trade timestamps for max-trades-per-hour cap. */
    private final java.util.Deque<Instant> recentTradeTimestamps = new java.util.concurrent.ConcurrentLinkedDeque<>();
    /** Rolling win/loss counters for win-rate auto-pause. Global (all users) kept for the legacy/UI aggregate. */
    private final java.util.concurrent.atomic.AtomicInteger rollingWins = new java.util.concurrent.atomic.AtomicInteger(0);
    private final java.util.concurrent.atomic.AtomicInteger rollingTotal = new java.util.concurrent.atomic.AtomicInteger(0);
    /** PER-USER rolling win/loss — so the RiskEngine auto-pause gate uses THIS user's own win-rate, not a pooled
     *  figure that lets one user's losing streak auto-pause a profitable independent user. userId → [wins,total]. */
    private final java.util.concurrent.ConcurrentHashMap<Long, int[]> rollingByUser = new java.util.concurrent.ConcurrentHashMap<>();
    /** PER-(user, strategy) rolling win/loss for the optional per-strategy auto-pause mode. Key = userId + "|" +
     *  strategyType. Only consulted when {@code trading.winrate-pause.per-strategy=true}. int[]{wins,total}. */
    private final java.util.concurrent.ConcurrentHashMap<String, int[]> rollingByUserStrategy = new java.util.concurrent.ConcurrentHashMap<>();
    /** Scratch counters (trades excluded from the win/loss ratio by the dead-band) — observability only. */
    private final java.util.concurrent.atomic.AtomicInteger rollingScratch = new java.util.concurrent.atomic.AtomicInteger(0);
    private final java.util.concurrent.ConcurrentHashMap<Long, java.util.concurrent.atomic.AtomicInteger> rollingScratchByUser = new java.util.concurrent.ConcurrentHashMap<>();

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

    /** Capture-only mode flag (see field doc). Scanners consult this to keep evaluating+recording while stopped. */
    public boolean captureWhenStopped() { return captureWhenStopped; }

    /**
     * Should the scan loops run this cycle? True when trading is running, OR when capture-only mode is on
     * and we are NOT in an emergency stop (kill switch / HARD halt). Order placement is gated separately by
     * {@link #running()} at each execution dispatch — this only governs whether evaluation+capture proceeds.
     */
    public boolean scanForCaptureAllowed() {
        if (running()) return true;
        return captureWhenStopped && !killSwitchEnabled() && haltMode != HaltMode.HARD;
    }
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

    /** Record a trade outcome for rolling win-rate tracking. Keyed to the CURRENT user (runs under the trade
     *  owner's context in doCloseTrade), plus the global aggregate for the UI and the optional per-strategy
     *  bucket. A trade inside the SCRATCH dead-band (|P&L| below the configured absolute/percent band) is
     *  recorded as neither win nor loss — it is excluded from the ratio so a ~flat sub-minute stop-out does
     *  not drag the win rate. With the default band (0/0 = OFF) every non-positive P&L is a full loss, exactly
     *  as before.
     *
     * @param realizedPnl the trade's realized P&L (signed)
     * @param entryPrice  entry price per unit (for the percent-of-notional dead-band; may be null)
     * @param quantity    filled quantity (for the percent-of-notional dead-band)
     * @param strategyType strategy identifier for the per-strategy bucket (may be null → blended only) */
    public void recordTradeOutcome(BigDecimal realizedPnl, BigDecimal entryPrice, int quantity, String strategyType) {
        Long uid = com.algo.trade.multiuser.UserContext.getUserId();
        if (isScratch(realizedPnl, entryPrice, quantity)) {
            rollingScratch.incrementAndGet();
            rollingScratchByUser.computeIfAbsent(uid, k -> new java.util.concurrent.atomic.AtomicInteger())
                    .incrementAndGet();
            log.info("Rolling win-rate: trade recorded as SCRATCH (|P&L| ₹{} within dead-band abs=₹{}/pct={}%) — "
                    + "excluded from win/loss ratio", (realizedPnl == null ? BigDecimal.ZERO : realizedPnl.abs()),
                    winratePauseScratchAbs, winratePauseScratchPercent);
            return;
        }
        boolean win = realizedPnl != null && realizedPnl.signum() > 0;
        rollingTotal.incrementAndGet();
        if (win) rollingWins.incrementAndGet();
        int[] wt = rollingByUser.computeIfAbsent(uid, k -> new int[2]);
        synchronized (wt) { wt[1]++; if (win) wt[0]++; }
        if (strategyType != null && !strategyType.isBlank()) {
            int[] swt = rollingByUserStrategy.computeIfAbsent(userStrategyKey(uid, strategyType), k -> new int[2]);
            synchronized (swt) { swt[1]++; if (win) swt[0]++; }
        }
    }

    /** Back-compat shim: a bare win/loss outcome with no P&L magnitude, strategy, or dead-band context.
     *  Records as before (scratch dead-band cannot apply without the P&L magnitude). */
    public void recordTradeOutcome(boolean win) {
        rollingTotal.incrementAndGet();
        if (win) rollingWins.incrementAndGet();
        Long uid = com.algo.trade.multiuser.UserContext.getUserId();
        int[] wt = rollingByUser.computeIfAbsent(uid, k -> new int[2]);
        synchronized (wt) { wt[1]++; if (win) wt[0]++; }
    }

    /** True if this trade falls inside the scratch dead-band (excluded from win/loss). Absolute-₹ and
     *  percent-of-notional bands are OR-combined; each is skipped when its config value is ≤ 0 (OFF). */
    private boolean isScratch(BigDecimal realizedPnl, BigDecimal entryPrice, int quantity) {
        if (realizedPnl == null) return false;
        double abs = realizedPnl.abs().doubleValue();
        if (winratePauseScratchAbs > 0 && abs <= winratePauseScratchAbs) return true;
        if (winratePauseScratchPercent > 0 && entryPrice != null && quantity > 0) {
            double notional = entryPrice.abs().doubleValue() * quantity;
            if (notional > 0 && (abs / notional) * 100.0 <= winratePauseScratchPercent) return true;
        }
        return false;
    }

    private static String userStrategyKey(Long userId, String strategyType) {
        return userId + "|" + strategyType;
    }

    /** Number of trades placed in the last 60 minutes. */
    public int tradesInLastHour() {
        Instant oneHourAgo = Instant.now().minus(java.time.Duration.ofHours(1));
        return (int) recentTradeTimestamps.stream().filter(t -> t.isAfter(oneHourAgo)).count();
    }

    /** Rolling win rate as percentage (0-100), GLOBAL across all users. Returns 100 if no trades yet.
     *  Kept for the UI aggregate — the RiskEngine per-user auto-pause should use {@link #rollingWinRate(Long)}. */
    public double rollingWinRate() {
        int total = rollingTotal.get();
        return total > 0 ? (double) rollingWins.get() / total * 100 : 100.0;
    }

    /** PER-USER rolling win rate as percentage (0-100). Returns 100 if this user has no recorded trades yet,
     *  so a user is never auto-paused off another user's losing streak. */
    public double rollingWinRate(Long userId) {
        int[] wt = rollingByUser.get(userId);
        if (wt == null) return 100.0;
        synchronized (wt) { return wt[1] > 0 ? (double) wt[0] / wt[1] * 100 : 100.0; }
    }

    /** PER-(user, strategy) rolling win rate (0-100). Returns 100 if this (user, strategy) has no recorded
     *  trades yet. Falls back to the blended per-user rate when {@code strategyType} is null. */
    public double rollingWinRate(Long userId, String strategyType) {
        if (strategyType == null || strategyType.isBlank()) return rollingWinRate(userId);
        int[] wt = rollingByUserStrategy.get(userStrategyKey(userId, strategyType));
        if (wt == null) return 100.0;
        synchronized (wt) { return wt[1] > 0 ? (double) wt[0] / wt[1] * 100 : 100.0; }
    }

    /** Number of recorded (non-scratch) outcomes in the bucket the auto-pause gate uses: the per-(user,
     *  strategy) bucket in per-strategy mode, else the blended per-user bucket. */
    public int rollingTradeCount(Long userId, String strategyType) {
        if (winratePausePerStrategy && strategyType != null && !strategyType.isBlank()) {
            int[] wt = rollingByUserStrategy.get(userStrategyKey(userId, strategyType));
            if (wt == null) return 0;
            synchronized (wt) { return wt[1]; }
        }
        int[] wt = rollingByUser.get(userId);
        if (wt == null) return 0;
        synchronized (wt) { return wt[1]; }
    }

    /** Win rate the auto-pause gate should use for THIS user: per-strategy when the mode is on and a strategy
     *  is known, else the blended per-user rate (historical behaviour). */
    public double rollingWinRateForGate(Long userId, String strategyType) {
        return (winratePausePerStrategy && strategyType != null && !strategyType.isBlank())
                ? rollingWinRate(userId, strategyType)
                : rollingWinRate(userId);
    }

    // ── Win-rate auto-pause config accessors (read by RiskEngine) ─────────────
    public boolean isWinratePauseEnabled() { return winratePauseEnabled; }
    public boolean isWinratePausePerStrategy() { return winratePausePerStrategy; }
    public int getWinratePauseMinTrades() { return winratePauseMinTrades; }
    public double getWinratePauseThresholdPercent() { return winratePauseThresholdPercent; }

    /**
     * Reset the rolling win-rate counters so a win-rate auto-pause can be cleared without a restart or waiting
     * for the daily reset. Called by the /winrate-pause/reset endpoint (privilege-checked). A null userId
     * resets EVERY user's counters (and the global aggregate); a non-null userId resets only that user's
     * blended + per-strategy + scratch counters.
     */
    public void resetRollingWinRate(Long userId) {
        if (userId == null) {
            rollingByUser.clear();
            rollingByUserStrategy.clear();
            rollingScratchByUser.clear();
            rollingWins.set(0);
            rollingTotal.set(0);
            rollingScratch.set(0);
            log.warn("Rolling win-rate counters reset for ALL users");
            return;
        }
        rollingByUser.remove(userId);
        rollingByUserStrategy.keySet().removeIf(k -> k.startsWith(userId + "|"));
        rollingScratchByUser.remove(userId);
        log.warn("Rolling win-rate counters reset for userId={}", userId);
    }

    /** Reset daily counters at midnight. */
    @Scheduled(cron = "0 0 0 * * *")
    public void resetDailyCounters() {
        rollingWins.set(0);
        rollingTotal.set(0);
        rollingScratch.set(0);
        // Bug fix: previously the GLOBAL counters were cleared but the PER-USER maps were not, so a user's
        // win/loss (and the auto-pause it drives) carried across days until restart. Clear them all.
        rollingByUser.clear();
        rollingByUserStrategy.clear();
        rollingScratchByUser.clear();
        recentTradeTimestamps.clear();
        log.info("Daily trade counters reset (global + per-user + per-strategy + scratch)");
    }
}
