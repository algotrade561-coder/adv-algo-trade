package com.algo.trade.strategy.oimomentum;

import com.algo.trade.domain.*;
import com.algo.trade.execution.ExecutionEngine;
import com.algo.trade.execution.TradingStateService;
import com.algo.trade.marketdata.ExpiryCalendar;
import com.algo.trade.marketdata.InstrumentCache;
import com.algo.trade.marketdata.LiveInstrumentCache;
import com.algo.trade.marketdata.MarketDataService;
import com.algo.trade.persistence.TradeEntity;
import com.algo.trade.persistence.TradeRepository;
import com.algo.trade.risk.MarketGuard;
import com.algo.trade.strategy.StrategyType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.*;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.Collectors;

/**
 * OI Momentum Strategy — 1-second live tracking with OI + PCR + Momentum confluence.
 *
 * Targets 15-20 trades/day with high signal quality.
 * Runs on its own ScheduledExecutorService, independent of candle-close cycle.
 *
 * Entry Cases:
 *   Case 1: Momentum + OI + PCR align → ENTER (highest conviction)
 *   Case 2: Momentum + PCR align, no OI data → ENTER
 *   Case 3: Momentum + OI align, PCR neutral → ENTER
 *   Case 4: Conflict → SKIP (no hedge mode)
 *   Case 5: PCR conflicts, no OI → SKIP
 *
 * Position Management:
 *   - Max 1 position at a time (sequential)
 *   - Reverse on OI flip (max 3 reversals/day)
 *   - Dynamic trailing stop
 *   - Squareoff at 15:10
 */
@Service
public class OIMomentumStrategy {

    private static final Logger log = LoggerFactory.getLogger(OIMomentumStrategy.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final OIMomentumConfig config;
    private final TickMomentumDetector momentumDetector;
    private final PremiumVelocityTracker premiumVelocityTracker;
    private final LiveInstrumentCache liveInstrumentCache;
    private final InstrumentCache instrumentCache;
    private final MarketDataService marketDataService;
    private final ExecutionEngine executionEngine;
    private final TradingStateService tradingStateService;
    private final TradeRepository tradeRepository;
    private final MarketGuard marketGuard;
    private final ExpiryCalendar expiryCalendar;
    /** All candidate indices for OI Momentum — actual enablement controlled via UNDERLYING_CONFIGS table (UI toggle). */
    private static final java.util.List<IndexType> CANDIDATE_INDICES = java.util.List.of(
            IndexType.NIFTY, IndexType.BANKNIFTY, IndexType.SENSEX);

    /** Per-index state tracking — each index runs independently with its own position. */
    private final ConcurrentHashMap<IndexType, IndexState> indexStates = new ConcurrentHashMap<>();

    /** Cached enabled indices from UNDERLYING_CONFIGS table — refreshed every 30s. */
    private volatile java.util.List<IndexType> enabledIndices = java.util.List.of();
    private volatile Instant enabledIndicesCacheTime = null;

    /** Holds all mutable state for one underlying. */
    private static class IndexState {
        volatile String activeTradeId = null;
        volatile int activeDirection = 0;
        volatile Instant lastEntryTime = null;
        volatile Instant lastSlTime = null;
        volatile Instant lastReversalTime = null;
        volatile double peakPrice = 0;
        /** A2 (2026-06-02): consecutive ticks at a new peak. BE-stop arms only at >=2. */
        volatile int peakConfirmationTicks = 0;
        volatile long lastOiCeChange = Long.MIN_VALUE;
        volatile long lastOiPeChange = Long.MIN_VALUE;
        volatile boolean oiAdvanced = false;
        volatile String pendingEntryInstrumentKey = null;
        /** Spike dedupe: time of last spike entry — blocks re-entry for 10 min (one entry per spike episode). */
        volatile Instant lastSpikeEntryTime = null;
        /** Range-edge fade dedupe: time of last fade entry — blocks re-entry for 10 min. */
        volatile Instant lastFadeEntryTime = null;
        volatile String lastEntryDecisionKey = null;
        volatile OiMomentumEntryDiagnostics lastEntryDiagnostics = null;
        volatile Instant lastRejectSampleTime = null;
        volatile String lastRejectReason = "";
        volatile double lastRangePct30m = 0;
        // ── Adaptive Bias Engine (Stage 1) ──────────────────────────────────
        /** Consecutive ticks where bias score ≥ threshold AND same direction. */
        volatile int confirmationCount = 0;
        /** Direction of the current confirmation streak (+1 bullish, -1 bearish). */
        volatile int lastConfirmedDir = 0;
        /** Timestamp of the last OI advancement tick — used for bias decay. */
        volatile Instant lastOiTickTime = null;
        // ── Re-entry Boost ───────────────────────────────────────────────────
        /** Time of the last profitable exit — used to reduce confirmation ticks for same-direction re-entry. */
        volatile Instant lastProfitableExitTime = null;
        /** Direction of the last profitable exit (+1 or -1). */
        volatile int lastProfitableExitDirection = 0;
        // ────────────────────────────────────────────────────────────────────
        final AtomicInteger tradesToday = new AtomicInteger(0);
        final AtomicInteger reversalsToday = new AtomicInteger(0);
        final AtomicInteger consecutiveLosses = new AtomicInteger(0);
        volatile double dailyPnl = 0;
        // ── v3 Quality (feature-flagged) ─────────────────────────────────────
        /** Strike of currently-active position (0 when flat). Set at entry. */
        volatile int activeStrike = 0;
        /** Total of negative PnL closes today (for adaptive circuit-breaker). */
        volatile double totalLossesPnl = 0;
        /** Count of negative PnL closes today (for adaptive circuit-breaker). */
        final AtomicInteger totalLossesCount = new AtomicInteger(0);
        /** Trip-once flag — once true, no further entries this trading day. */
        volatile boolean haltedForDay = false;
        /** Per-strike last-loss timestamp for anti-pyramid check. */
        final ConcurrentHashMap<Integer, Instant> lastLossExitByStrike =
                new ConcurrentHashMap<>();
    }

    /** P1 #8: Cached config per underlying to avoid DB hit every tick. */
    private final ConcurrentHashMap<IndexType, com.algo.trade.strategy.StrategyConfig> cachedConfigs = new ConcurrentHashMap<>();
    private volatile Instant cachedConfigTime = null;
    private static final Duration CONFIG_CACHE_TTL = Duration.ofSeconds(30);

    /** P1 #10: Consecutive error tracking. */
    private final AtomicInteger consecutiveErrors = new AtomicInteger(0);
    private static final int MAX_CONSECUTIVE_ERRORS = 10;
    /** Real pause: strategy stops ticking until this time passes. */
    private volatile Instant pausedUntil = null;
    private static final Duration ERROR_PAUSE_DURATION = Duration.ofMinutes(5);

    /** Counter for periodic summary log (every 60 ticks = ~60 seconds). */
    private final AtomicInteger tickCounter = new AtomicInteger(0);

    /** Evaluation counters for summary log. */
    private final AtomicInteger evalCount = new AtomicInteger(0);
    private final AtomicInteger momentumSignalCount = new AtomicInteger(0);
    private final AtomicInteger rejectedCount = new AtomicInteger(0);
    private final AtomicInteger enteredCount = new AtomicInteger(0);

    /** P4: per-reason reject counters (reset at IST day rollover). */
    private final ConcurrentHashMap<String, AtomicInteger> rejectReasonCounters = new ConcurrentHashMap<>();

    /** Parsed midday times (P2 #20: avoid parsing every tick). */
    private volatile LocalTime middayStart = null;
    private volatile LocalTime middayEnd = null;

    /** Parsed entry window boundaries — re-parsed once per day when config.entryWindowStart/End changes. */
    private volatile LocalTime entryWindowStart = null;
    private volatile LocalTime entryWindowEnd = null;

    private volatile LocalDate currentDay = null;

    // ── Executor ──
    private ScheduledExecutorService executor;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.monitoring.SchedulerRegistry schedulerRegistry;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.notification.TelegramAlertService telegramAlertService;

    /**
     * Operator Framework — detects institutional OI accumulation from chain snapshots
     * before price breakouts. Optional: falls back to existing behaviour if not wired.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private OperatorFrameworkService operatorFrameworkService;

    @org.springframework.beans.factory.annotation.Autowired
    private com.algo.trade.strategy.StrategyConfigService strategyConfigService;

    @org.springframework.beans.factory.annotation.Autowired
    private com.algo.trade.underlying.UnderlyingConfigService underlyingConfigService;

    /** V3 OPERATOR pipeline — optional, only used when {@code v3-enabled} is true. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.strategy.oimomentum.v3.V3EntryPipeline v3EntryPipeline;

    /** V3 market context — optional, populated by the OptionChainSnapshotScheduler. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.strategy.oimomentum.v3.MarketContextService v3MarketContext;

    /** CASE 0 OI-led entry detector (P0-1 — 29 May 2026, data-validated). Optional. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private Case0OiLedDetector case0Detector;

    /**
     * R2 — Range-edge fade detector (29 May 2026, data-validated 54% 30m / 56% 60m
     * win, ~13.6 fires/day). Addresses range-bound gap. Optional.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private RangeEdgeFadeDetector rangeEdgeFadeDetector;

    /**
     * D2 — SUSTAINED_DRIFT detector (2 Jun 2026 — addresses slow-grind days like
     * 1 Jun where CASE 1-5 + CASE 0 + range-fade all stay silent). Replay-validated
     * 69.6% 60m win, ~5 fires/day/index. Optional so unit tests can construct
     * without the full Spring context.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SustainedDriftDetector sustainedDriftDetector;

    /**
     * OPERATOR_SQUEEZE detector (2 Jun 2026 — added after the 12:30–13:30 NIFTY
     * +225-pt move that every existing detector missed). Coil → ignition →
     * chain-confirmation gates. Needs per-tick {@code tick(index)} from the
     * strategy loop to populate its 30-min sample ring. Optional; null in tests.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private OperatorSqueezeDetector operatorSqueezeDetector;

    /**
     * OPERATOR INTENT RADAR (5 Jun 2026) — anticipatory layer that detects institutional
     * positioning BEFORE price breakouts. Adds [+0..25] bonus to bias scoring based on:
     * OI magnet detection, hourly cycle confirmation, cross-index radar, max pain shifts,
     * and reversal zone flagging.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private OperatorIntentRadar operatorIntentRadar;

    /**
     * PCR Momentum Reversal — detects put-writer unwinding from high PCR peaks.
     * Provides bias bonus for bearish entries when PCR is declining.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.strategy.PcrMomentumReversalStrategy pcrMomentumReversalStrategy;

    /**
     * Option-Leads-Index Detector — detects when option premiums break out
     * before the index moves, providing early directional signals.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.strategy.OptionLeadsIndexDetector optionLeadsIndexDetector;

    /**
     * Universal Call Orchestrator — centralized signal aggregation.
     * Receives signals from all strategies for lot scaling and conflict resolution.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.strategy.UniversalCallOrchestrator universalCallOrchestrator;

    /**
     * Expiry Operator Trap Detector — detects post-2PM manipulation patterns.
     * Provides max pain direction bias and trap warnings on expiry days.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.strategy.ExpiryOperatorTrapDetector expiryTrapDetector;

    /**
     * T5 — Capture / entry-path heartbeat (2 Jun 2026 — addresses 1 Jun silent
     * 13:51 stop). Optional; when wired, every detectEntry tick records a
     * liveness ping so the scheduled checker can alert on staleness.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private EntryPathHeartbeatService entryPathHeartbeat;

    /**
     * Source of the {@code maxLotsPerTrade} cap used for conviction-based lot sizing
     * on legacy CASE 1-5 + CASE 0 entries. Optional so unit-test wirings without the
     * full Spring context can construct OIMomentumStrategy. When null, conviction
     * sizing falls back to 1 lot (legacy behavior).
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.config.GlobalConfigService globalConfigService;

    /**
     * Capital-based lot ceiling — caps lots based on available margin, open trades,
     * and volatility regime. Integrated from algo-trading friend's CapitalAllocator.
     * When null, only conviction-based sizing applies (no capital ceiling).
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.risk.CapitalAllocator capitalAllocator;

    /**
     * Phase 6: unified tuning pipeline is now the sole capture path. When the
     * recorder + adapter beans are present, OI Momentum evaluations / signals /
     * exits emit events to {@code tuning/<event>/...} CSVs. Capture is gated on
     * {@code tuning_capture_config.OI_MOMENTUM} → no events land on disk until
     * the operator flips the toggle via the UI. (Replaces the legacy
     * {@code LegacyDetectionRecorder}, {@code SpikeEpisodeRecorder} and
     * {@code OiMomentumTuneRecorder} CSV recorders deleted in Phase 6.)
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.tuning.recorder.TuningEventRecorder tuningEventRecorder;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.tuning.adapter.strategies.OiMomentumCaptureAdapter oiMomentumCaptureAdapter;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.tuning.infra.MaeMfeTracker maeMfeTracker;

    /**
     * Phase 2 dual-write: episode aggregator for evaluation rejects.
     * Gap-window AND max-age cap come from config.getRejectEpisodeWindowSeconds()
     * (default 60). The max-age cap forces a CSV row every N seconds even when
     * ticks keep arriving — fixes the historic "rejects vanish" gap.
     */
    private final com.algo.trade.tuning.infra.EpisodeAggregator<IndexType, String, OiMomentumEntryDiagnostics>
            evaluationAggregator;

    /** Latched once we log the dual-write wiring status on the first reject. */
    private final AtomicBoolean firstRejectLogged =
            new AtomicBoolean(false);

    /**
     * CASE 4 watch-list state (P1-3): when CASE 4 (OI conflicts momentum) fires, we
     * remember the OI direction + timestamp per index. If the NEXT signal within 20 min
     * aligns with that remembered OI direction, the bias score gets a +5 bonus.
     */
    private static class Case4WatchEntry {
        final int oiDirection;
        final Instant atTime;
        Case4WatchEntry(int oiDir, Instant at) { this.oiDirection = oiDir; this.atTime = at; }
    }
    private final ConcurrentHashMap<IndexType, Case4WatchEntry> case4Watch =
            new ConcurrentHashMap<>();
    private static final Duration CASE4_WATCH_TTL = Duration.ofMinutes(20);

    public OIMomentumStrategy(OIMomentumConfig config,
                               TickMomentumDetector momentumDetector,
                               PremiumVelocityTracker premiumVelocityTracker,
                               LiveInstrumentCache liveInstrumentCache,
                               InstrumentCache instrumentCache,
                               MarketDataService marketDataService,
                               ExecutionEngine executionEngine,
                               TradingStateService tradingStateService,
                               TradeRepository tradeRepository,
                               MarketGuard marketGuard,
                               ExpiryCalendar expiryCalendar) {
        this.config = config;
        this.momentumDetector = momentumDetector;
        this.premiumVelocityTracker = premiumVelocityTracker;
        this.liveInstrumentCache = liveInstrumentCache;
        this.instrumentCache = instrumentCache;
        this.marketDataService = marketDataService;
        this.executionEngine = executionEngine;
        this.tradingStateService = tradingStateService;
        this.tradeRepository = tradeRepository;
        this.marketGuard = marketGuard;
        this.expiryCalendar = expiryCalendar;
        int windowSec = Math.max(1, config.getRejectEpisodeWindowSeconds());
        Duration win = Duration.ofSeconds(windowSec);
        this.evaluationAggregator = new com.algo.trade.tuning.infra.EpisodeAggregator<>(win, win);
    }

    /** Hot-update the reject-episode max-age window from the runtime config service. */
    public void updateRejectEpisodeWindow(int newWindowSeconds) {
        if (newWindowSeconds < 1) return;
        evaluationAggregator.setMaxAge(Duration.ofSeconds(newWindowSeconds));
        log.info("[OIMomentum] reject episode max-age updated to {}s", newWindowSeconds);
    }

    @jakarta.annotation.PostConstruct
    public void start() {
        // Initialize per-index state for all candidates (enablement checked at runtime)
        for (IndexType idx : CANDIDATE_INDICES) {
            indexStates.put(idx, new IndexState());
        }
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "oi-momentum-loop");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleAtFixedRate(this::tick, 5, 1, TimeUnit.SECONDS);
        if (schedulerRegistry != null) {
            schedulerRegistry.register("oiMomentum", "OI Momentum 1-sec strategy loop (NIFTY+BANKNIFTY+SENSEX)", 1000, () -> {});
        }
        // Reconcile state from DB on startup
        reconcileFromDb();
        // Parse session boundary times at startup so isEntryWindow() / isMidday() don't
        // fall back to repeated LocalTime.parse() on every tick before the first day-rollover.
        // (The day-change block in tick() also sets these, but never fires on day 1 because
        //  reconcileFromDb() already sets currentDay = today.)
        entryWindowStart = LocalTime.parse(config.getEntryWindowStart());
        entryWindowEnd   = LocalTime.parse(config.getEntryWindowEnd());
        middayStart      = LocalTime.parse(config.getMiddayStart());
        middayEnd        = LocalTime.parse(config.getMiddayEnd());
        log.info("[OIMomentum] Started 1-second execution loop — candidates: {}, enabled from DB at runtime, entryWindow={}-{}",
                CANDIDATE_INDICES, entryWindowStart, entryWindowEnd);
    }

    /**
     * Restore in-memory state from DB after restart:
     * - Find any open OI_MOMENTUM trade → set activeTradeId per index
     * - Count today's closed OI_MOMENTUM trades → set tradesToday per index
     * - Count today's consecutive losses → set consecutiveLosses per index
     */
    private void reconcileFromDb() {
        try {
            LocalDate today = LocalDate.now(IST);
            currentDay = today;

            // Find open OI_MOMENTUM trades and assign to correct index.
            // PRIMARY-OWNED trades only: in multi-user mode, signal-copied trades for
            // secondary users also carry strategyType=OI_MOMENTUM. Adopting one of THOSE
            // as activeTradeId would leave the primary's own position orphaned (the
            // exit monitor skips primary OI_MOMENTUM trades, expecting this loop to
            // manage them). Secondary copies are managed by LivePositionExitMonitor.
            var openTrades = tradeRepository.findByStatus(TradeStatus.OPEN).stream()
                    .filter(t -> StrategyType.OI_MOMENTUM.name().equals(t.getStrategyType()))
                    .filter(t -> t.getUserId() == null
                            || t.getUserId().equals(com.algo.trade.multiuser.UserContext.DEFAULT_USER_ID))
                    .toList();
            for (TradeEntity openTrade : openTrades) {
                IndexType idx = resolveIndexFromInstrumentKey(openTrade.getInstrumentKey());
                IndexState state = indexStates.get(idx);
                if (state == null) continue;
                if (state.activeTradeId != null) continue; // Already has an active trade
                state.activeTradeId = openTrade.getTradeId();
                state.activeDirection = "CE".equals(openTrade.getOptionType()) ? 1 : -1;
                state.peakPrice = openTrade.getPeakPrice() != null ? openTrade.getPeakPrice().doubleValue() : openTrade.getEntryPrice().doubleValue();
                state.lastEntryTime = openTrade.getEntryTime();
                log.info("[OIMomentum] Reconciled open trade: {} index={} direction={}",
                        state.activeTradeId, idx, state.activeDirection);
            }

            // Count today's OI_MOMENTUM trades per index
            Instant dayStart = today.atStartOfDay(IST).toInstant();
            Instant dayEnd = today.plusDays(1).atStartOfDay(IST).toInstant();
            var todayTrades = tradeRepository.findByEntryTimeBetween(dayStart, dayEnd).stream()
                    .filter(t -> StrategyType.OI_MOMENTUM.name().equals(t.getStrategyType()))
                    .toList();

            for (TradeEntity t : todayTrades) {
                IndexType idx = resolveIndexFromInstrumentKey(t.getInstrumentKey());
                IndexState state = indexStates.get(idx);
                if (state != null) state.tradesToday.incrementAndGet();
            }

            // Count consecutive losses per index (from most recent trades backwards)
            for (IndexType idx : CANDIDATE_INDICES) {
                IndexState state = indexStates.get(idx);
                var closedForIdx = todayTrades.stream()
                    .filter(t -> t.getStatus() == TradeStatus.CLOSED)
                        .filter(t -> resolveIndexFromInstrumentKey(t.getInstrumentKey()) == idx)
                    .sorted((a, b) -> b.getExitTime().compareTo(a.getExitTime()))
                    .toList();
                int losses = 0;
                for (var t : closedForIdx) {
                if (t.getRealizedPnl() != null && t.getRealizedPnl().signum() < 0) losses++;
                else break;
            }
                state.consecutiveLosses.set(losses);
            }

            int totalTrades = todayTrades.size();
            int totalActive = (int) CANDIDATE_INDICES.stream()
                    .filter(idx -> indexStates.get(idx).activeTradeId != null).count();
            if (totalTrades > 0 || totalActive > 0) {
                log.info("[OIMomentum] Reconciled: totalTradesToday={}, activePositions={}",
                        totalTrades, totalActive);
            }
        } catch (Exception e) {
            log.warn("[OIMomentum] Reconcile failed: {}", e.getMessage());
        }
    }

    /** Resolve IndexType from instrument key prefix (e.g. "NFO:NIFTY26..." → NIFTY, "NFO:BANKNIFTY26..." → BANKNIFTY, "BFO:SENSEX26..." → SENSEX). */
    private IndexType resolveIndexFromInstrumentKey(String instrumentKey) {
        if (instrumentKey == null) return IndexType.NIFTY;
        String upper = instrumentKey.toUpperCase();
        // Strip exchange prefix if present (e.g. "NFO:", "BFO:")
        int colonIdx = upper.indexOf(':');
        if (colonIdx >= 0) upper = upper.substring(colonIdx + 1);
        if (upper.startsWith("BANKNIFTY")) return IndexType.BANKNIFTY;
        if (upper.startsWith("SENSEX")) return IndexType.SENSEX;
        if (upper.startsWith("FINNIFTY")) return IndexType.FINNIFTY;
        if (upper.startsWith("MIDCPNIFTY")) return IndexType.MIDCPNIFTY;
        return IndexType.NIFTY;
    }

    @jakarta.annotation.PreDestroy
    public void stop() {
        shuttingDown = true;
        if (executor != null) {
            executor.shutdown(); // Let in-flight tick finish
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            log.info("[OIMomentum] Stopped");
        }
    }

    private volatile boolean shuttingDown = false;

    /**
     * Main tick — runs every 1 second. Non-blocking, exception-safe.
     * Loops over all enabled indices (NIFTY, BANKNIFTY, SENSEX).
     */
    private void tick() {
        try {
            if (shuttingDown) return;
            if (!config.isEnabled()) return; // runtime/DB master switch (UI + kill)
            if (!isMarketHours()) return;
            if (tradingStateService.killSwitchEnabled()) return;
            if (!tradingStateService.running()) return;

            // Multi-user: set default user context for the strategy loop.
            // All analysis runs once (shared market data). At execution time,
            // the UserContext determines which user's trade/order gets tagged.
            // For multi-user execution (sending same signal to multiple users),
            // the MultiUserStrategyLoop handles per-user dispatching separately.
            if (!com.algo.trade.multiuser.UserContext.isSet()) {
                com.algo.trade.multiuser.UserContext.setUserId(
                        com.algo.trade.multiuser.UserContext.DEFAULT_USER_ID);
            }

            // P1 #10: Real pause — strategy stops until pausedUntil passes
            if (pausedUntil != null) {
                if (Instant.now().isBefore(pausedUntil)) {
                    return; // Still paused
                }
                pausedUntil = null; // Pause expired, resume
                log.info("[OIMomentum] Error pause expired — resuming");
            }

            // Reset daily counters on new day
            LocalDate today = LocalDate.now(IST);
            if (!today.equals(currentDay)) {
                currentDay = today;
                for (IndexType idx : CANDIDATE_INDICES) {
                    IndexState state = indexStates.get(idx);
                    state.tradesToday.set(0);
                    state.reversalsToday.set(0);
                    state.consecutiveLosses.set(0);
                    state.dailyPnl = 0;
                    // v3 quality: reset halt + loss-tracker for new trading day
                    state.haltedForDay = false;
                    state.totalLossesPnl = 0;
                    state.totalLossesCount.set(0);
                    state.lastLossExitByStrike.clear();
                    // Reset bias engine state for new day
                    state.confirmationCount = 0;
                    state.lastConfirmedDir = 0;
                    state.lastOiTickTime = null;
                    if (state.activeTradeId == null) {
                        state.activeDirection = 0;
                        state.peakPrice = 0;
                    }
                }
                // Parse session boundary times once per day (P2 #20)
                middayStart = LocalTime.parse(config.getMiddayStart());
                middayEnd = LocalTime.parse(config.getMiddayEnd());
                entryWindowStart = LocalTime.parse(config.getEntryWindowStart());
                entryWindowEnd = LocalTime.parse(config.getEntryWindowEnd());
                // Reset Operator Framework baseline for new trading day
                if (operatorFrameworkService != null) {
                    operatorFrameworkService.resetForNewDay();
                }
                rejectReasonCounters.clear();
            }

            // Process each enabled index independently (driven by UNDERLYING_CONFIGS table)
            for (IndexType indexType : getEnabledIndices()) {
                tickIndex(indexType);
            }

            if (schedulerRegistry != null) schedulerRegistry.recordRun("oiMomentum");

            // Periodic summary log every 60 seconds for visibility
            if (tickCounter.incrementAndGet() % 60 == 0) {
                logPeriodicSummary();
            }
            consecutiveErrors.set(0); // Reset on success
        } catch (Exception e) {
            int errors = consecutiveErrors.incrementAndGet();
            log.warn("[OIMomentum] Tick error (consecutive={}): {}", errors, e.getMessage(), e);
            if (errors >= MAX_CONSECUTIVE_ERRORS) {
                pausedUntil = Instant.now().plus(ERROR_PAUSE_DURATION);
                consecutiveErrors.set(0);
                log.error("[OIMomentum] {} consecutive errors — PAUSING for {} minutes",
                        MAX_CONSECUTIVE_ERRORS, ERROR_PAUSE_DURATION.toMinutes());
                if (telegramAlertService != null) {
                    telegramAlertService.systemAlert(String.format(
                            "🚨 OIMomentum: %d consecutive errors — PAUSED for %d min\nLast: %s",
                            MAX_CONSECUTIVE_ERRORS, ERROR_PAUSE_DURATION.toMinutes(), e.getMessage()));
                }
            }
        }
    }

    /** Process one index per tick — feed momentum, check OI, manage/detect. */
    private void tickIndex(IndexType indexType) {
        IndexState state = indexStates.get(indexType);
        if (state == null) return;

        // P1 #8: Check per-index config
        var dbConfig = getCachedConfig(indexType);
        if (dbConfig == null || !dbConfig.isEnabled()) return;

        // P2 #24: Daily P&L cap check per index — pause if daily loss exceeds ₹5000
        if (state.dailyPnl < -5000) {
            return;
        }

        // Feed momentum detector for this index
        momentumDetector.tick(indexType);

        // P0 #1: Check if OI has advanced since last evaluation
        double spot = liveInstrumentCache.getFuturesPrice(indexType);
        if (spot > 0) {
            int atm = indexType.roundToATM(spot);
            long[] oiChange = liveInstrumentCache.getAtmOiChange(indexType, atm, 3, 3);
            state.oiAdvanced = (oiChange[0] != state.lastOiCeChange || oiChange[1] != state.lastOiPeChange);
            if (state.oiAdvanced) {
                state.lastOiCeChange = oiChange[0];
                state.lastOiPeChange = oiChange[1];
                state.lastOiTickTime = Instant.now(); // bias decay anchor
            }
        }

        // Resolve pending entry if order was accepted but tradeId not yet available
        if (state.activeTradeId == null && state.pendingEntryInstrumentKey != null) {
            var found = tradeRepository.findByStatus(TradeStatus.OPEN).stream()
                    .filter(t -> state.pendingEntryInstrumentKey.equals(t.getInstrumentKey()))
                    .filter(t -> StrategyType.OI_MOMENTUM.name().equals(t.getStrategyType()))
                    .findFirst();
            if (found.isPresent()) {
                state.activeTradeId = found.get().getTradeId();
                state.peakPrice = found.get().getEntryPrice().doubleValue();
                state.pendingEntryInstrumentKey = null;
                log.info("[OIMomentum][{}] Pending entry resolved: tradeId={}", indexType, state.activeTradeId);
            }
            // If still not found after 30s, give up
            if (state.pendingEntryInstrumentKey != null && state.lastEntryTime != null
                    && Duration.between(state.lastEntryTime, Instant.now()).getSeconds() > 30) {
                log.warn("[OIMomentum][{}] Pending entry timed out for {} — giving up",
                        indexType, state.pendingEntryInstrumentKey);
                state.pendingEntryInstrumentKey = null;
                state.activeDirection = 0;
            }
        }

        if (state.activeTradeId != null) {
            managePosition(indexType, state);
        } else if (isEntryWindow()) {
            detectEntry(indexType, state);
        }
    }

    /** Periodic summary log showing all indices. */
    private void logPeriodicSummary() {
        StringBuilder sb = new StringBuilder("[OIMomentum] Status:");
        int pending = 0;
        for (IndexType idx : getEnabledIndices()) {
            IndexState s = indexStates.get(idx);
            double spot = liveInstrumentCache.getFuturesPrice(idx);
            double pcr = liveInstrumentCache.getRealtimePcr(idx);
            // Use live 30-min range from detector rather than s.lastRangePct30m (which is only
            // updated inside detectEntry and stays 0 until the entry window opens at 09:25).
            double liveRange30m = computeRangePct30m(idx);
            sb.append(String.format(" | %s: spot=%.0f pcr=%.2f range30m=%.2f%% trades=%d active=%s lastReject=%s pnl=%.0f",
                    idx.name(), spot, pcr, liveRange30m, s.tradesToday.get(),
                    s.activeTradeId != null ? s.activeTradeId.substring(0, Math.min(8, s.activeTradeId.length())) : "-",
                    s.lastRejectReason != null && !s.lastRejectReason.isBlank() ? s.lastRejectReason : "-",
                    s.dailyPnl));
            pending += s.confirmationCount;
            appendRegimeCsv(idx, spot, pcr, liveRange30m, s);
        }
        // Invariant: momentum == entered + rejected + pending. Each momentum tick must
        // resolve to exactly one of:
        //   (a) matrix-skip / low-bias  -> rejected++
        //   (b) bias passed, still accumulating confirmation ticks -> confirmationCount++
        //   (c) bias passed and confirmation complete -> entered++ (and confirmationCount reset to 0)
        // Drift here points at a missed counter increment somewhere in detectEntry; warn
        // loudly so it doesn't go unnoticed.
        int momentum = momentumSignalCount.get();
        int entered = enteredCount.get();
        int rejected = rejectedCount.get();
        int expected = entered + rejected + pending;
        sb.append(String.format(" | evals=%d momentum=%d rejected=%d entered=%d pending=%d",
                evalCount.get(), momentum, rejected, entered, pending));
        String topRejects = formatTopRejectReasons();
        if (!topRejects.isEmpty()) {
            sb.append(" | topRejects=").append(topRejects);
        }
        log.info(sb.toString());
        if (momentum != expected) {
            // Diagnostic only — small drift occurs when entries are reclassified after
            // the fact (external/manual closes, copy rejections, shutdown closes).
            // Log ONCE per drift value instead of every 60s status cycle (was spamming
            // an identical warning every minute all afternoon on 2026-06-12).
            int drift = expected - momentum;
            if (drift != lastInvariantDrift) {
                lastInvariantDrift = drift;
                log.warn("[OIMomentum] Counter invariant drift changed: momentum={} != entered({}) + rejected({}) + pending({}) = {} (drift={})",
                        momentum, entered, rejected, pending, expected, drift);
            }
        }
    }

    /** Last observed counter drift — used to avoid repeating the invariant warning every status cycle. */
    private volatile int lastInvariantDrift = 0;

    /**
     * Momentum-regime time series (#1 of the 2026-06-12 tuning-data additions).
     * One CSV row per index per status cycle (60s) during market hours:
     * data/tuning/momentum-regime-&lt;date&gt;.csv — the offline dataset for validating
     * TREND_RIDE thresholds (range30m sat at 0.26-0.28% vs the spike-oriented gates
     * for the entire 2026-06-12 afternoon rally; this captures that systematically).
     */
    private void appendRegimeCsv(IndexType idx, double spot, double pcr, double range30m, IndexState s) {
        try {
            java.time.LocalTime now = java.time.LocalTime.now(IST);
            if (now.isBefore(java.time.LocalTime.of(9, 15)) || now.isAfter(java.time.LocalTime.of(15, 31))) return;
            java.nio.file.Path dir = java.nio.file.Path.of("data", "tuning");
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Path file = dir.resolve("momentum-regime-" + java.time.LocalDate.now(IST) + ".csv");
            boolean newFile = !java.nio.file.Files.exists(file);
            try (java.io.FileWriter w = new java.io.FileWriter(file.toFile(), true)) {
                if (newFile) w.write("time,index,spot,pcr,range30mPct,tradesToday,active,lastReject\n");
                w.write(String.format("%s,%s,%.2f,%.3f,%.3f,%d,%s,%s%n",
                        now.toString().substring(0, 8), idx.name(), spot, pcr, range30m,
                        s.tradesToday.get(),
                        s.activeTradeId != null ? "Y" : "N",
                        s.lastRejectReason != null ? s.lastRejectReason.replace(',', ';') : ""));
            }
        } catch (Exception e) {
            log.debug("[OIMomentum] regime CSV write failed: {}", e.getMessage());
        }
    }

    /** P1 #8: Cache strategy config per underlying for 30 seconds to avoid DB hit every tick. */
    private com.algo.trade.strategy.StrategyConfig getCachedConfig(IndexType indexType) {
        Instant now = Instant.now();
        if (cachedConfigTime != null && Duration.between(cachedConfigTime, now).compareTo(CONFIG_CACHE_TTL) < 0) {
            var cached = cachedConfigs.get(indexType);
            if (cached != null) return cached;
        }
        // Refresh all configs at once
        for (IndexType idx : CANDIDATE_INDICES) {
            cachedConfigs.put(idx, strategyConfigService.getConfig(StrategyType.OI_MOMENTUM, idx.underlyingSymbol()));
        }
        cachedConfigTime = now;
        return cachedConfigs.get(indexType);
    }

    /**
     * Get enabled indices from UNDERLYING_CONFIGS table (cached 30s).
     * Only returns indices that are enabled in the underlying_configs table AND
     * have OI_MOMENTUM strategy config enabled.
     */
    /**
     * Returns the indices currently enabled in the underlying-config DB. Public
     * so collaborators (e.g. EntryPathHeartbeatService A3 filter) can suppress
     * alerts for operator-disabled indices.
     */
    public java.util.List<IndexType> getEnabledIndices() {
        Instant now = Instant.now();
        if (enabledIndicesCacheTime != null && Duration.between(enabledIndicesCacheTime, now).compareTo(CONFIG_CACHE_TTL) < 0) {
            return enabledIndices;
        }
        // Refresh from DB
        var dbEnabled = underlyingConfigService.getEnabledSymbols();
        enabledIndices = CANDIDATE_INDICES.stream()
                .filter(idx -> dbEnabled.contains(UnderlyingSymbol.valueOf(idx.name())))
                .toList();
        enabledIndicesCacheTime = now;
        return enabledIndices;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ENTRY DETECTION
    // ═══════════════════════════════════════════════════════════════════════════

    private void detectEntry(IndexType indexType, IndexState state) {
        evalCount.incrementAndGet();
        // T5 — record entry-path liveness so the heartbeat checker can detect
        // a silent halt (1 Jun 2026 went dark from 13:51 without an alert).
        if (entryPathHeartbeat != null) {
            entryPathHeartbeat.recordEntryPathTick(indexType);
        }
        state.lastRangePct30m = computeRangePct30m(indexType);

        // ── v3 Quality Gates (feature-flagged, default OFF) ──
        // Circuit-breaker halt (tripped once, locks for the day).
        if (state.haltedForDay) {
            recordThrottleReject(indexType, state, "halted_for_day");
            return;
        }
        // Consecutive-loss HARD halt (distinct from soft consecutiveLossPause).
        if (config.getConsecutiveLossHaltCount() > 0
                && state.consecutiveLosses.get() >= config.getConsecutiveLossHaltCount()) {
            state.haltedForDay = true;
            recordThrottleReject(indexType, state,
                    "consecutive_loss_halt:" + state.consecutiveLosses.get());
            return;
        }
        // Expiry-day OTM late cutoff: on resolved expiry day, block entries past cutoff.
        if (config.isExpiryOtmCutoffEnabled() && expiryCalendar.isExpiryDay(indexType)) {
            try {
                LocalTime cutoff = LocalTime.parse(config.getExpiryOtmCutoffTime());
                if (LocalTime.now(IST).isAfter(cutoff)) {
                    recordThrottleReject(indexType, state, "expiry_otm_late_cutoff");
                    return;
                }
            } catch (Exception ex) {
                log.warn("[OIMomentum] Invalid expiryOtmCutoffTime '{}': {}",
                        config.getExpiryOtmCutoffTime(), ex.getMessage());
            }
        }

        // ── Throttle checks ──
        if (state.tradesToday.get() >= config.getMaxTradesPerDay()) {
            recordThrottleReject(indexType, state, "max_trades_day");
            return;
        }
        if (state.consecutiveLosses.get() >= config.getConsecutiveLossPause()) {
            recordThrottleReject(indexType, state, "consecutive_loss_pause");
            return;
        }
        if (state.lastSlTime != null && Duration.between(state.lastSlTime, Instant.now()).getSeconds() < config.getCooldownAfterSlSeconds()) {
            long remaining = config.getCooldownAfterSlSeconds()
                    - Duration.between(state.lastSlTime, Instant.now()).getSeconds();
            recordThrottleReject(indexType, state, "sl_cooldown:" + remaining + "s");
            return;
        }

        // ── Midday reduction ──
        // middayTradeReductionPercent (default 50) = fraction of softTarget allowed before
        // throttling during the midday window. e.g. 50% of 15 = 7 trades max before midday throttle.
        // Previously this was hardcoded as softTargetTradesPerDay / 2 (ignoring the config value).
        LocalTime now = LocalTime.now(IST);
        int middayThreshold = Math.max(1,
                (int) Math.round(config.getSoftTargetTradesPerDay() * config.getMiddayTradeReductionPercent() / 100.0));
        if (isMidday(now) && state.tradesToday.get() >= middayThreshold) {
            recordThrottleReject(indexType, state, "midday_reduction");
            return;
        }

        // ── Squareoff window — no new entries ──
        if (now.isAfter(LocalTime.of(config.getSquareoffHour(), config.getSquareoffMinute()).minusMinutes(10))) {
            recordThrottleReject(indexType, state, "squareoff_window");
            return;
        }

        // ── MarketGuard safety: VIX, circuit breaker, event day ──
        String mgBlock = marketGuard.longPremiumBlockReason();
        if (mgBlock != null) {
            recordThrottleReject(indexType, state,
                    MarketGuard.normalizeLongPremiumRejectToken("market_guard", mgBlock));
            return;
        }

        // ── CASE 0 — OI-led entry (P0-1, 29 May 2026 data-validated) ──
        // Evaluated BEFORE event spike + price-momentum. Catches setups where the
        // chain has tilted but spot is still coiling. See OI_MOMENTUM_EMPIRICAL_REPLAY_RESULTS.md
        // (replay: 91% 30m win on strict thresholds across 12 days).
        //
        // Binding only when:  case0Enabled && !case0ShadowMode && !(v3Enabled && !v3ShadowMode)
        // Otherwise: record-only (CSV captured for end-of-day validation).
        Case0OiLedDetector.Decision case0Decision = (case0Detector != null)
                ? case0Detector.evaluate(indexType, config)
                : new Case0OiLedDetector.Decision(false, 0, 0, 0, 0, "detector_not_wired");
        boolean v3BindingLive = config.isV3Enabled() && !config.isV3ShadowMode();
        boolean case0BindingLive = case0Decision.fires()
                && config.isCase0Enabled() && !config.isCase0ShadowMode() && !v3BindingLive;

        if (case0Decision.fires()) {
            String label = case0BindingLive ? "CASE0_LIVE" : "CASE0_SHADOW";
            log.info("[OIMomentum][{}] {} dir={} opScore={} range20m={}% pcrSlope5m={}",
                    indexType, label, case0Decision.direction(), case0Decision.opScore(),
                    String.format("%.3f", case0Decision.rangePct()),
                    String.format("%+.3f", case0Decision.pcrSlope5Min()));
        }

        if (case0BindingLive) {
            double case0Spot = momentumDetector.getSpot(indexType);
            if (case0Spot > 0) {
                int case0Atm = indexType.roundToATM(case0Spot);
                long[] case0Oi = liveInstrumentCache.getAtmOiChange(indexType, case0Atm, 3, 3);
                double case0Pcr = liveInstrumentCache.getRealtimePcr(indexType);
                int case0PcrDir = pcrDir(case0Pcr);
                int case0OiDir = oiDir(case0Oi[0], case0Oi[1]);
                boolean case0OiAvail = case0Oi[0] != 0 || case0Oi[1] != 0;
                TickMomentumDetector.MomentumSignal pseudoMom =
                        new TickMomentumDetector.MomentumSignal(case0Decision.direction(),
                                "CASE0_OI_LED", 0, case0Spot);
                OiMomentumEntryDiagnostics case0Diag = OiMomentumEntryDiagnostics.forSpike(
                        indexType, pseudoMom, case0Pcr, case0PcrDir, case0Oi[0], case0Oi[1],
                        case0OiAvail, case0OiDir, state.oiAdvanced,
                        marketGuard.getCurrentVix(), expiryCalendar.daysToExpiry(indexType),
                        expiryCalendar.isExpiryDay(indexType), paperTrading(indexType));
                String case0Reason = String.format(
                        "CASE0_OI_LED opScore=%d range20m=%.3f%% pcrSlope=%+.3f",
                        case0Decision.opScore(), case0Decision.rangePct(),
                        case0Decision.pcrSlope5Min());
                enter(indexType, state, case0Decision.direction(), case0Reason, case0Spot, case0Diag);
                return;
            }
        }

        // ── D2 SUSTAINED_DRIFT (2 Jun 2026 — addresses slow-grind days like 1 Jun) ──
        // Fires when spot has drifted ≥0.20% over the last 60 min AND the operator
        // chain is aligned with the drift direction (score ≥ 50). No coil required
        // (CASE 0's domain), no breakout required (CASE 1-5's domain). Replay:
        // 69.6% 60m win, ~5 fires/day/index. Binding live when enabled and not in
        // shadow mode, gated by the same V3-binding precedence as CASE 0.
        SustainedDriftDetector.Decision driftDecision = (sustainedDriftDetector != null)
                ? sustainedDriftDetector.evaluate(indexType, config)
                : new SustainedDriftDetector.Decision(false, 0, 0, 0, 0, "detector_not_wired");
        // Explicit D2-vs-V3 precedence (14 Jun 2026). Previously D2 was hard-gated
        // by !v3BindingLive, so it could never bind live while V3 was on (it logged
        // 2,582 D2_DRIFT_SHADOW fires on 2026-06-12 and zero live). The override
        // flag lets D2 bind live alongside V3; the entry still passes through the V3
        // pipeline below for sizing and can be vetoed. Default false = legacy "V3 wins".
        boolean driftBindingLive = driftDecision.fires()
                && config.isSustainedDriftEnabled()
                && !config.isSustainedDriftShadowMode()
                && (!v3BindingLive || config.isSustainedDriftOverridesV3());
        if (driftDecision.fires()) {
            String label = driftBindingLive ? "D2_DRIFT_LIVE" : "D2_DRIFT_SHADOW";
            log.info("[OIMomentum][{}] {} dir={} opScore={} drift60m={}% window={}min",
                    indexType, label, driftDecision.direction(), driftDecision.opScore(),
                    String.format("%+.3f", driftDecision.driftPct()),
                    driftDecision.driftMinutes());
        }
        if (driftBindingLive) {
            double driftSpot = momentumDetector.getSpot(indexType);
            if (driftSpot > 0) {
                int driftAtm = indexType.roundToATM(driftSpot);
                long[] driftOi = liveInstrumentCache.getAtmOiChange(indexType, driftAtm, 3, 3);
                double driftPcr = liveInstrumentCache.getRealtimePcr(indexType);
                int driftPcrDir = pcrDir(driftPcr);
                int driftOiDir = oiDir(driftOi[0], driftOi[1]);
                boolean driftOiAvail = driftOi[0] != 0 || driftOi[1] != 0;
                double driftSlope = (v3MarketContext != null)
                        ? v3MarketContext.pcrSlope5Min(indexType) : 0.0;
                TickMomentumDetector.MomentumSignal pseudoMom =
                        new TickMomentumDetector.MomentumSignal(driftDecision.direction(),
                                "SUSTAINED_DRIFT", 0, driftSpot);
                OiMomentumEntryDiagnostics driftDiag = OiMomentumEntryDiagnostics.forSpike(
                                indexType, pseudoMom, driftPcr, driftPcrDir,
                                driftOi[0], driftOi[1], driftOiAvail, driftOiDir,
                                state.oiAdvanced, marketGuard.getCurrentVix(),
                                expiryCalendar.daysToExpiry(indexType),
                                expiryCalendar.isExpiryDay(indexType), paperTrading(indexType))
                        .withSustainedDrift(driftDecision.driftPct(), driftDecision.driftMinutes())
                        .withPcrSlope(driftSlope);
                String driftReason = String.format(
                        "SUSTAINED_DRIFT opScore=%d drift60m=%+.3f%% window=%dmin",
                        driftDecision.opScore(), driftDecision.driftPct(),
                        driftDecision.driftMinutes());
                enter(indexType, state, driftDecision.direction(), driftReason, driftSpot, driftDiag);
                return;
            }
        }

        // ── OPERATOR_SQUEEZE (2 Jun 2026 — catches the coil → ignition → CE/PE
        //     short-squeeze pattern. Three gates: preceding 20-min coil, 5-min
        //     ignition bar with OI collapse + VIX expansion, chain confirmation
        //     via IV expansion + PCR rotation. Verified against 2 Jun NIFTY
        //     12:30 tape — every gate would have passed at 12:35 close). ──
        if (operatorSqueezeDetector != null) {
            operatorSqueezeDetector.tick(indexType);
        }
        // OPERATOR INTENT RADAR (5 Jun 2026) — update magnet tracking + reversal detection
        if (operatorIntentRadar != null) {
            operatorIntentRadar.tick(indexType);
        }
        OperatorSqueezeDetector.Decision squeezeDecision = (operatorSqueezeDetector != null)
                ? operatorSqueezeDetector.evaluate(indexType, config)
                : new OperatorSqueezeDetector.Decision(false, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                        "detector_not_wired");
        boolean squeezeBindingLive = squeezeDecision.fires()
                && config.isOperatorSqueezeEnabled()
                && !v3BindingLive;
        if (squeezeDecision.fires()) {
            log.info("[OIMomentum][{}] OPERATOR_SQUEEZE LIVE dir={} ignition={}% oiΔ={} "
                    + "ivExp={}% pcrRot={} coilRange={}%",
                    indexType, squeezeDecision.direction(),
                    String.format("%+.3f", squeezeDecision.ignitionReturnPct()),
                    String.format("%+,d", squeezeDecision.oiCollapseAbs()),
                    String.format("%+.2f", squeezeDecision.ivExpansionPct()),
                    String.format("%.3f", squeezeDecision.pcrRotation()),
                    String.format("%.3f", squeezeDecision.coilRangePct()));
        }
        if (squeezeBindingLive) {
            double squeezeSpot = momentumDetector.getSpot(indexType);
            if (squeezeSpot > 0) {
                int squeezeAtm = indexType.roundToATM(squeezeSpot);
                long[] sqzOi = liveInstrumentCache.getAtmOiChange(indexType, squeezeAtm, 3, 3);
                double sqzPcr = liveInstrumentCache.getRealtimePcr(indexType);
                int sqzPcrDir = pcrDir(sqzPcr);
                int sqzOiDir = oiDir(sqzOi[0], sqzOi[1]);
                boolean sqzOiAvail = sqzOi[0] != 0 || sqzOi[1] != 0;
                TickMomentumDetector.MomentumSignal pseudoMom =
                        new TickMomentumDetector.MomentumSignal(squeezeDecision.direction(),
                                "OPERATOR_SQUEEZE", Math.abs(squeezeDecision.ignitionReturnPct()),
                                squeezeSpot);
                OiMomentumEntryDiagnostics sqzDiag = OiMomentumEntryDiagnostics.forSpike(
                        indexType, pseudoMom, sqzPcr, sqzPcrDir,
                        sqzOi[0], sqzOi[1], sqzOiAvail, sqzOiDir,
                        state.oiAdvanced, marketGuard.getCurrentVix(),
                        expiryCalendar.daysToExpiry(indexType),
                        expiryCalendar.isExpiryDay(indexType), paperTrading(indexType));
                // E3 (2026-06-02): on expiry day, gamma exposure is roughly
                // double the non-expiry case. Tag the reason string with
                // EXPIRY_HALF_SIZE so downstream sizing (or operator visibility)
                // can apply a 0.5× multiplier. Logged as WARN so it's prominent.
                boolean sqzExpiryDay = expiryCalendar.isExpiryDay(indexType);
                if (sqzExpiryDay) {
                    log.warn("[OIMomentum][{}] OPERATOR_SQUEEZE on EXPIRY DAY — "
                            + "recommend reduced size (config.operatorSqueezeExpiryLotMultiplier={})",
                            indexType, config.getOperatorSqueezeExpiryLotMultiplier());
                }
                String sqzReason = String.format(
                        "OPERATOR_SQUEEZE%s dir=%+d ignition=%+.3f%% oiCollapse=%+,d "
                        + "ivExp=%+.2f%% pcrRot=%.3f coilRange=%.3f%%",
                        sqzExpiryDay ? "[EXPIRY_HALF_SIZE]" : "",
                        squeezeDecision.direction(), squeezeDecision.ignitionReturnPct(),
                        squeezeDecision.oiCollapseAbs(), squeezeDecision.ivExpansionPct(),
                        squeezeDecision.pcrRotation(), squeezeDecision.coilRangePct());
                enter(indexType, state, squeezeDecision.direction(), sqzReason, squeezeSpot, sqzDiag);
                return;
            }
        }

        // ── R2: Range-edge fade (29 May 2026 — data-validated for range-bound markets) ──
        // Fires on tight ranges (30M < 0.30%) when spot is at top/bottom edge with
        // OI confirmation. V3 evaluates later in this method and requires a
        // momentum candidate; fade and V3 conditions are mutually exclusive in
        // practice (tight range vs. momentum burst), so fade is free to bind
        // live whenever its detector fires. 10-min dedupe prevents rapid re-entry.
        RangeEdgeFadeDetector.Decision fadeDecision = (rangeEdgeFadeDetector != null)
                ? rangeEdgeFadeDetector.evaluate(indexType, config)
                : new RangeEdgeFadeDetector.Decision(false, 0, 0, 0, 0, 0, 0, "detector_not_wired");
        boolean fadeDedupeBlocked = state.lastFadeEntryTime != null
                && Duration.between(state.lastFadeEntryTime, Instant.now()).toMinutes() < 10;
        boolean fadeBindingLive = fadeDecision.fires() && !fadeDedupeBlocked;
        if (fadeDecision.fires()) {
            String label = fadeBindingLive ? "LIVE" : (fadeDedupeBlocked ? "DEDUPE" : "SHADOW");
            log.info("[OIMomentum][{}] RANGE_EDGE_FADE {} dir={} pos={} range30m={}% pcr={} theta_cost={}%",
                    indexType, label,
                    fadeDecision.direction(),
                    String.format("%.3f", fadeDecision.positionInRange()),
                    String.format("%.3f", fadeDecision.range30mPct()),
                    String.format("%.2f", fadeDecision.pcr()),
                    String.format("%.2f", fadeDecision.thetaCostPct()));
            // Tuning capture — record every fade fire (LIVE or dedupe-blocked) so
            // the validation report shows full fade-decision history. Aggregator
            // folds repeated same-(index,blocker) ticks into one episode.
            String fadeBlocker = fadeBindingLive
                    ? "range_edge_fade_fired"
                    : "range_edge_fade_dedupe_blocked";
            state.lastRejectSampleTime = recordReject(indexType, state, fadeBlocker,
                    buildThrottleDiagnostics(indexType, state, fadeBlocker));
        }
        if (fadeBindingLive) {
            double fadeSpot = momentumDetector.getSpot(indexType);
            if (fadeSpot > 0) {
                int fadeAtm = indexType.roundToATM(fadeSpot);
                long[] fadeOi = liveInstrumentCache.getAtmOiChange(indexType, fadeAtm, 3, 3);
                double fadePcr = liveInstrumentCache.getRealtimePcr(indexType);
                int fadePcrDir = pcrDir(fadePcr);
                int fadeOiDir = oiDir(fadeOi[0], fadeOi[1]);
                boolean fadeOiAvail = fadeOi[0] != 0 || fadeOi[1] != 0;
                TickMomentumDetector.MomentumSignal pseudoMom =
                        new TickMomentumDetector.MomentumSignal(fadeDecision.direction(),
                                "RANGE_EDGE_FADE", 0, fadeSpot);
                OiMomentumEntryDiagnostics fadeDiag = OiMomentumEntryDiagnostics.forSpike(
                        indexType, pseudoMom, fadePcr, fadePcrDir, fadeOi[0], fadeOi[1],
                        fadeOiAvail, fadeOiDir, state.oiAdvanced,
                        marketGuard.getCurrentVix(), expiryCalendar.daysToExpiry(indexType),
                        expiryCalendar.isExpiryDay(indexType), paperTrading(indexType));
                String fadeReason = String.format(
                        "RANGE_EDGE_FADE dir=%d pos=%.3f range30m=%.3f%% pcr=%.2f theta=%.2f%%",
                        fadeDecision.direction(), fadeDecision.positionInRange(),
                        fadeDecision.range30mPct(), fadeDecision.pcr(),
                        fadeDecision.thetaCostPct());
                state.lastFadeEntryTime = Instant.now();
                enter(indexType, state, fadeDecision.direction(), fadeReason, fadeSpot, fadeDiag);
                return;
            }
        }

        // ── Event Spike Detection (highest priority) ──
        TickMomentumDetector.MomentumSignal spike = momentumDetector.detectSpike(
                indexType, config.getSpikeThresholdPercent());
        if (spike.isPresent()) {
            synchronized (state) {
                // Spike dedupe: only 1 entry per spike episode (10-min window)
                if (state.lastSpikeEntryTime != null
                        && Duration.between(state.lastSpikeEntryTime, Instant.now()).toMinutes() < 10) {
                    int atm = indexType.roundToATM(spike.spotPrice());
                    long[] oi = liveInstrumentCache.getAtmOiChange(indexType, atm, 3, 3);
                    double pcr = liveInstrumentCache.getRealtimePcr(indexType);
                    int pcrDir = pcrDir(pcr);
                    int oiDir = oiDir(oi[0], oi[1]);
                    boolean oiAvail = oi[0] != 0 || oi[1] != 0;
                    OiMomentumEntryDiagnostics partial = OiMomentumEntryDiagnostics.forSpike(
                            indexType, spike, pcr, pcrDir, oi[0], oi[1], oiAvail, oiDir, state.oiAdvanced,
                            marketGuard.getCurrentVix(), expiryCalendar.daysToExpiry(indexType),
                            expiryCalendar.isExpiryDay(indexType), paperTrading(indexType));
                    state.lastRejectReason = "spike_dedupe";
                    state.lastRejectSampleTime = recordReject(indexType, state, "spike_dedupe", partial);
                } else {
                    state.lastSpikeEntryTime = Instant.now();
                    log.info("[OIMomentum][{}] EVENT SPIKE detected: direction={}, magnitude={}%, spot={}",
                            indexType, spike.direction(), spike.magnitude(), spike.spotPrice());
                    int atm = indexType.roundToATM(spike.spotPrice());
                    long[] oi = liveInstrumentCache.getAtmOiChange(indexType, atm, 3, 3);
                    double pcr = liveInstrumentCache.getRealtimePcr(indexType);
                    int pcrDir = pcrDir(pcr);
                    int oiDir = oiDir(oi[0], oi[1]);
                    boolean oiAvailSpike = oi[0] != 0 || oi[1] != 0;
                    OiMomentumEntryDiagnostics diag = OiMomentumEntryDiagnostics.forSpike(
                            indexType, spike, pcr, pcrDir, oi[0], oi[1], oiAvailSpike, oiDir, state.oiAdvanced,
                            marketGuard.getCurrentVix(), expiryCalendar.daysToExpiry(indexType),
                            expiryCalendar.isExpiryDay(indexType), paperTrading(indexType));
                    enter(indexType, state, spike.direction(), "SPIKE:" + spike.type(), spike.spotPrice(), diag);
                }
            }
            return;
        }

        // ── Momentum Detection — primary 30M, then 15M, then 5M ──
        TickMomentumDetector.MomentumSignal momentum = momentumDetector.detect(
                indexType, config.getMomentumThresholdPercent());

        if (!momentum.isPresent() && config.isMultiTimeframeEnabled()) {
            // 15-min window — catches intraday trends not yet visible on 30M range
            momentum = momentumDetector.detectInWindow(indexType, config.getMomentumThresholdPercent(), 15);
        }
        if (!momentum.isPresent() && config.isMultiTimeframeEnabled()) {
            // 5-min window — short burst early signal; uses shorter threshold to catch quick moves
            momentum = momentumDetector.detectInWindow(indexType, config.getShortTimeframeThresholdPct(), 5);
        }

        // ── Premium Velocity: gamma-regime alternate momentum path ──
        // On expiry days, spot may move 0.2% while ATM premiums swing 15-20%.
        // Sample premiums every tick and check if we're in a gamma regime.
        double spot0 = liveInstrumentCache.getFuturesPrice(indexType);
        int atm0 = spot0 > 0 ? indexType.roundToATM(spot0) : 0;
        if (atm0 > 0) {
            premiumVelocityTracker.sample(indexType, atm0, spot0);
        }

        if (!momentum.isPresent()) {
            // No spot momentum — check premium velocity as alternate signal.
            // This catches gamma-driven moves on expiry where spot barely moves
            // but ATM options swing 5-20% in under a minute.
            PremiumVelocityTracker.PremiumVelocity premVel = premiumVelocityTracker.getVelocity(indexType);
            if (premVel.hasSignal() && premVel.gammaRegime()) {
                // Synthesize a momentum signal from premium velocity direction
                momentum = new TickMomentumDetector.MomentumSignal(
                        premVel.direction(), "PREMIUM_VELOCITY", premVel.maxPremiumVelocityPct(),
                        spot0);
                log.info("[OIMomentum][{}] GAMMA_REGIME: premium velocity {}% (spot only {}%) — " +
                                "using premium direction as momentum signal (amplification={}x)",
                        indexType,
                        String.format("%.1f", premVel.maxPremiumVelocityPct()),
                        String.format("%.3f", premVel.spotVelocityPct()),
                        String.format("%.1f", premVel.amplification()));
            }
        }

        if (!momentum.isPresent()) {
            // Calm-market diagnostic — record "no momentum on any timeframe" so the
            // tuning report can distinguish "strategy was running but markets flat"
            // from "strategy was off". Aggregator folds repeated ticks into one
            // 60s episode row per index, so cost is ~1 row/index/min.
            state.lastRejectReason = "no_momentum_detected";
            state.lastRejectSampleTime = recordReject(indexType, state, "no_momentum_detected",
                    buildThrottleDiagnostics(indexType, state, "no_momentum_detected"));
            return;
        }
        momentumSignalCount.incrementAndGet();

        // ── OI Analysis (only when OI has actually advanced — P0 #1) ──
        double spot = momentum.spotPrice();
        int atm = indexType.roundToATM(spot);
        long ceOiChange = 0;
        long peOiChange = 0;
        boolean oiAvailable = false;
        int oiDirection = 0;

        if (state.oiAdvanced) {
            long[] oiChange = liveInstrumentCache.getAtmOiChange(indexType, atm, 3, 3);
            ceOiChange = oiChange[0];
            peOiChange = oiChange[1];
            oiAvailable = (ceOiChange != 0 || peOiChange != 0);
            if (oiAvailable) {
                oiDirection = oiDir(ceOiChange, peOiChange); // includes buildup AND squeeze detection
            }
        }

        // ── PCR Analysis ──
        double pcr = liveInstrumentCache.getRealtimePcr(indexType);
        int pcrDirection = 0;
        if (pcr >= config.getPcrBullishThreshold()) pcrDirection = 1;
        else if (pcr <= config.getPcrBearishThreshold()) pcrDirection = -1;

        // ── Entry Decision Matrix ──
        String entryCase = describeCase(momentum.direction(), oiDirection, pcrDirection, oiAvailable);
        EntryCaseEvaluation eval = evaluateEntryCaseDetail(indexType, momentum.direction(), oiDirection,
                pcrDirection, oiAvailable, momentum.type(), ceOiChange, peOiChange);
        entryCase = entryCaseLabel(entryCase, eval);
        String diagBlockDetail = diagnosticsBlockDetail(entryCase, eval.blockDetail());
        com.algo.trade.strategy.oimomentum.v3.TimeOfDayMode todMode =
                com.algo.trade.strategy.oimomentum.v3.TimeOfDayMode.classify(LocalTime.now(IST));
        String matrixCase = matrixCaseLabel(entryCase);
        // Capture the REAL operator framework score into the eval diagnostics.
        // Previously hardcoded to 0 here, which made every captured operatorScore
        // read 0 and falsely looked like the operator gate was always failing —
        // the live logs show actual scores of 50-95. Best-effort, null-safe.
        int diagOperatorScore = 0;
        if (operatorFrameworkService != null) {
            var opSig = operatorFrameworkService.getOperatorSignal(indexType);
            if (opSig != null) diagOperatorScore = opSig.getScore();
        }
        OiMomentumEntryDiagnostics diag = buildDiagnostics(
                indexType, state, momentum, oiDirection, pcrDirection, pcr, ceOiChange, peOiChange,
                oiAvailable, spot, atm, entryCase, null, diagBlockDetail,
                todMode.name(), matrixCase, "LEGACY", diagOperatorScore, 0);

        // ── P0-2: time-of-day mode gate (29 May 2026, data-validated) ──
        // Replay: filtering out AFTERNOON_POSITION + LAST_HOUR + EOD_SQUEEZE_ONLY
        // lifts 30m win rate from 48% → 58%. MIDDAY_DISCIPLINE requires 4-of-4
        // (CASE 1) or CASE 3 + operator-aligned confluence — sustained-loss window.
        if (config.isLegacyTimeOfDayModeEnabled() && eval.direction() != 0) {
            String todSkip = checkTimeOfDayGate(indexType, todMode, entryCase, oiDirection, momentum.direction());
            if (todSkip != null) {
                rejectedCount.incrementAndGet();
                state.lastRejectReason = todSkip;
                state.lastRejectSampleTime = recordReject(indexType, state, todSkip, diag);
                return;
            }
        }

        if (eval.direction() != 0) {
            // ── Adaptive Bias Engine (Stage 1) ────────────────────────────
            BiasScore bias = computeBiasScore(indexType, state, momentum.direction(),
                    oiDirection, pcrDirection, oiAvailable, ceOiChange, peOiChange, atm);

            // ── T3 (2 Jun 2026): Conditional bias-floor lowering ──
            // When BOTH (a) a confirmed coil break (20m range < relaxRangePct) AND
            // (b) PCR slope agrees with momentum at ≥ relaxSlopeMinAbs hold, lower
            // the entry floor from biasFloorDefault to biasFloorRelaxed. This
            // catches setups like 1 Jun 11:55 CASE 1 BEAR (bias ~55 under 65 floor).
            int defaultFloor = config.isBiasFloorRelaxEnabled()
                    ? config.getBiasFloorDefault() : config.getBiasConfidenceThreshold();
            int effectiveFloor = defaultFloor;
            boolean floorRelaxed = false;
            double slope5m = (v3MarketContext != null)
                    ? v3MarketContext.pcrSlope5Min(indexType) : 0.0;
            if (config.isBiasFloorRelaxEnabled()) {
                double range20m = compute20mRangePctSafe(indexType);
                boolean coilBreakConfirmed = range20m > 0
                        && range20m <= config.getBiasFloorRelaxCoilBreakRangePct();
                boolean slopeAgrees = (momentum.direction() > 0
                                && slope5m >= +config.getBiasFloorRelaxPcrSlopeMinAbs())
                        || (momentum.direction() < 0
                                && slope5m <= -config.getBiasFloorRelaxPcrSlopeMinAbs());
                if (coilBreakConfirmed && slopeAgrees) {
                    effectiveFloor = config.getBiasFloorRelaxed();
                    floorRelaxed = true;
                    log.info("[OIMomentum][{}] BIAS_FLOOR_RELAXED {}→{} (range20m={}%, slope={})",
                            indexType, defaultFloor, effectiveFloor,
                            String.format("%.3f", range20m),
                            String.format("%+.3f", slope5m));
                }
            }
            // Stamp slope + floor onto the diagnostics so the tune CSV
            // can quantify which fires used the relaxed path.
            diag = diag.withPcrSlope(slope5m).withBiasFloor(floorRelaxed, effectiveFloor);

            if (bias.score() >= effectiveFloor) {
                // Accumulate confirmation ticks in same direction
                if (momentum.direction() == state.lastConfirmedDir) {
                    state.confirmationCount++;
                } else {
                    // Direction changed or first signal — reset streak.
                    // Count the discarded streak as rejected (those momentum ticks were
                    // never resolved to entered/rejected, breaking the counter invariant).
                    if (state.confirmationCount > 0 && state.lastConfirmedDir != 0) {
                        rejectedCount.addAndGet(state.confirmationCount);
                        log.debug("[OIMomentum][{}] Confirmation streak discarded: dir={} ticks={} (direction flip)",
                                indexType, state.lastConfirmedDir, state.confirmationCount);
                    }
                    state.confirmationCount = 1;
                    state.lastConfirmedDir = momentum.direction();
                }

                // Re-entry boost: after a profitable exit in the same direction within the
                // boost window, reduce required confirmation ticks to 1. Rationale: a just-profitable
                // trade proves the direction is live; operators don't reverse instantly.
                boolean reEntryBoost = config.isReEntryBoostEnabled()
                        && state.lastProfitableExitTime != null
                        && state.lastProfitableExitDirection == momentum.direction()
                        && Duration.between(state.lastProfitableExitTime, Instant.now()).getSeconds()
                                < config.getReEntryBoostWindowSeconds();
                int requiredTicks = reEntryBoost ? 1 : config.getBiasConfirmationTicks();

                log.debug("[OIMomentum][{}] Bias OK: score={} ticks={}/{} case={} reEntryBoost={} signals={}",
                        indexType, bias.score(), state.confirmationCount,
                        requiredTicks, entryCase, reEntryBoost, bias.primarySignal());

                if (state.confirmationCount >= requiredTicks) {
                    // Full confirmation — enter
                    state.confirmationCount = 0;
                    state.lastConfirmedDir = 0;
                    String operatorTag = formatOperatorEntryTag(entryCase, eval.blockDetail());
                    String reason = String.format("M:%s OI:%d PCR:%.2f(%d) case=%s bias=%.0f ticks=%d%s",
                            momentum.type(), oiDirection, pcr, pcrDirection, entryCase,
                            bias.score(), config.getBiasConfirmationTicks(), operatorTag);
                    // CASE 4 watchlist consumed: this aligned entry just took the bonus
                    clearCase4Watch(indexType, eval.direction());
                    enter(indexType, state, eval.direction(), reason, spot, diag);
                    // NOTE: enteredCount is incremented inside enterWithGates only on a
                    // confirmed entry (paper fill / live fill / pending order). Internal
                    // gate rejections (max_trades_day, sl_cooldown, etc.) no longer
                    // produce a spurious entered++ here.
                }
                // else: still accumulating — no entry yet, no reject record
            } else {
                // Bias score below threshold — reset confirmation, record low-confidence reject
                state.confirmationCount = 0;
                state.lastConfirmedDir = 0;
                rejectedCount.incrementAndGet();
                String rejectReason = String.format("low_bias:%.0f<%d [%s] case=%s",
                        bias.score(), config.getBiasConfidenceThreshold(), bias.primarySignal(), entryCase);
                state.lastRejectReason = rejectReason;
                state.lastRejectSampleTime = recordReject(indexType, state, rejectReason,
                        diag.withScores(0, (int) bias.score()));
            }
            // ──────────────────────────────────────────────────────────────
        } else {
            // Matrix blocked — reset confirmation streak
            state.confirmationCount = 0;
            state.lastConfirmedDir = 0;
            rejectedCount.incrementAndGet();
            String rejectReason = eval.blockDetail().isBlank()
                    ? "matrix_skip:" + entryCase
                    : "matrix_skip:" + entryCase + "|" + eval.blockDetail();
            state.lastRejectReason = rejectReason;
            state.lastRejectSampleTime = recordReject(indexType, state, rejectReason, diag);
            // P1-3: CASE 4 watch-list (OI flip vs momentum = potential reversal lead)
            if (config.isCase4WatchlistBonusEnabled() && "CASE4".equals(entryCase)
                    && oiDirection != 0) {
                case4Watch.put(indexType,
                        new Case4WatchEntry(oiDirection, Instant.now()));
                log.debug("[OIMomentum][{}] CASE4 watchlist set: oiDir={} (TTL=20m)",
                        indexType, oiDirection);
            }
        }
    }

    // ── Conviction-based lot sizing for legacy + CASE 0 paths ─────────────────
    //
    // Both paths produce a single "conviction score" (op_score for CASE 0, bias_score
    // for legacy) at the time enter() is called, and that score is embedded in the
    // reason string. computeLegacyLotCount() recovers it and maps to a lot count in
    // [1, maxLotsPerTrade] using {@link #convictionToLotCount}.

    /**
     * Recover the conviction score embedded in {@code reason}, classify the path, and
     * return the lot count to use. SPIKE / REVERSE entries (no embedded score) default
     * to 1 lot — those paths are event-driven and shouldn't conviction-scale.
     */
    private int computeLegacyLotCount(String reason) {
        if (reason == null) return 1;
        int maxLots = globalConfigService != null
                ? Math.max(1, globalConfigService.getMaxLotsPerTrade())
                : 1;
        if (maxLots == 1) return 1; // operator wants single-lot only — skip the math.

        // CASE 0 path: "CASE0_OI_LED opScore=NN ..."  — threshold = case0OpScoreThreshold.
        if (reason.startsWith("CASE0_OI_LED")) {
            int op = parseScoreToken(reason, "opScore=");
            if (op > 0) {
                int thr = config.getCase0OpScoreThreshold();
                return convictionToLotCount(op, thr, maxLots);
            }
            return 1;
        }

        // SPIKE + REVERSE: event-driven, no conviction score → single lot.
        if (reason.startsWith("SPIKE:") || reason.startsWith("REVERSE")) {
            return 1;
        }

        // Legacy CASE 1-5: "M:... bias=NN ticks=..." — threshold = biasConfidenceThreshold.
        int bias = parseScoreToken(reason, "bias=");
        if (bias > 0) {
            int thr = config.getBiasConfidenceThreshold();
            return convictionToLotCount(bias, thr, maxLots);
        }
        return 1;
    }

    /**
     * Parse "{token}NN" or "{token}NN.NN" out of {@code text}. Returns -1 when not found
     * or unparseable.
     */
    private static int parseScoreToken(String text, String token) {
        int idx = text.indexOf(token);
        if (idx < 0) return -1;
        int start = idx + token.length();
        int end = start;
        while (end < text.length()) {
            char c = text.charAt(end);
            if ((c >= '0' && c <= '9') || c == '.' || c == '-') end++;
            else break;
        }
        if (end == start) return -1;
        try {
            // bias is a double in the reason string; cast is fine since we only need an integer score.
            return (int) Math.round(Double.parseDouble(text.substring(start, end)));
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    /**
     * Linear conviction → lot-count scaler.
     *
     * <pre>
     *   delta = clamp((score - threshold) / (100 - threshold), 0, 1)
     *   lots  = 1 + round((maxLotsPerTrade - 1) × delta)
     * </pre>
     *
     * <p>Examples (maxLotsPerTrade = 10, threshold = 80 for CASE 0):
     *   score=80 → 1 lot (threshold floor);
     *   score=90 → ~6 lots;
     *   score=100 → 10 lots.</p>
     *
     * <p>For legacy CASE 1-5 (threshold = 65 = biasConfidenceThreshold):
     *   bias=65 → 1 lot;
     *   bias=82 → ~5 lots;
     *   bias=100 → 10 lots.</p>
     *
     * <p>Score values below {@code threshold} can't reach this code path (the entry would
     * have been skipped upstream), but the clamp keeps the formula safe regardless.</p>
     */
    private static int convictionToLotCount(double score, int threshold, int maxLotsPerTrade) {
        if (maxLotsPerTrade <= 1) return 1;
        double room = 100.0 - threshold;
        if (room <= 0) return maxLotsPerTrade;       // misconfigured threshold; fall back to cap
        double delta = (score - threshold) / room;
        if (delta < 0) delta = 0;
        if (delta > 1) delta = 1;
        int lots = 1 + (int) Math.round((maxLotsPerTrade - 1) * delta);
        if (lots < 1) lots = 1;
        if (lots > maxLotsPerTrade) lots = maxLotsPerTrade;
        return lots;
    }

    /**
     * P0-2 time-of-day gate. Returns a skip reason (null = pass through).
     *
     * <ul>
     *   <li>AFTERNOON_POSITION (13:30–14:45): skip all baseline entries.</li>
     *   <li>LAST_HOUR (14:45–15:10), EOD_SQUEEZE_ONLY (15:10–15:20): skip all
     *       baseline entries. Last-hour squeezes are V3-only.</li>
     *   <li>MIDDAY_DISCIPLINE (11:30–13:30): require CASE 1 (all three align) OR
     *       CASE 3 with operator-aligned OI direction. CASE 2 (no OI) is rejected.</li>
     *   <li>OPENING_DRIVE + TREND_FOLLOW: allow everything (these are the windows
     *       where the strategy actually wins).</li>
     * </ul>
     */
    private String checkTimeOfDayGate(IndexType ix,
                                      com.algo.trade.strategy.oimomentum.v3.TimeOfDayMode mode,
                                      String entryCase, int oiDirection, int momentumDir) {
        switch (mode) {
            case AFTERNOON_POSITION:
                return "tod_gate:AFTERNOON_POSITION blocked";
            case LAST_HOUR:
                return "tod_gate:LAST_HOUR blocked";
            case EOD_SQUEEZE_ONLY:
                return "tod_gate:EOD_SQUEEZE_ONLY blocked";
            case MIDDAY_DISCIPLINE:
                if ("CASE2".equals(entryCase)) {
                    return "tod_gate:MIDDAY_DISCIPLINE requires_oi";
                }
                if ("CASE3".equals(entryCase) && oiDirection != momentumDir) {
                    return "tod_gate:MIDDAY_DISCIPLINE requires_oi_aligned";
                }
                return null;
            default:
                return null;
        }
    }

    /** Clear stale CASE 4 watch state, but only when aligned (preserves the bonus path). */
    private void clearCase4Watch(IndexType ix, int enteredDir) {
        Case4WatchEntry e = case4Watch.get(ix);
        if (e == null) return;
        if (e.oiDirection == enteredDir) case4Watch.remove(ix);
    }

    /**
     * Returns the +5 CASE 4 watch bonus if there is an active watchlist entry on
     * this index whose oiDirection matches {@code momentumDir} and is younger than
     * {@link #CASE4_WATCH_TTL}. Used by {@link #computeBiasScore}.
     */
    private int case4WatchBonus(IndexType ix, int momentumDir) {
        if (!config.isCase4WatchlistBonusEnabled()) return 0;
        Case4WatchEntry e = case4Watch.get(ix);
        if (e == null) return 0;
        if (Duration.between(e.atTime, Instant.now()).compareTo(CASE4_WATCH_TTL) > 0) {
            case4Watch.remove(ix);
            return 0;
        }
        return (e.oiDirection == momentumDir) ? 5 : 0;
    }

    private record EntryCaseEvaluation(int direction, String blockDetail) {}

    private Instant recordReject(IndexType indexType, IndexState state, String reason,
                                 OiMomentumEntryDiagnostics partial) {
        incrementRejectReason(reason);

        // One-shot diagnostic on the first reject — exposes whether the
        // dual-write hooks were actually wired by Spring. Subsequent rejects
        // stay silent; the recorder's own throttled drop log takes over.
        if (firstRejectLogged.compareAndSet(false, true)) {
            log.info("[OIMomentum] dual-write status: recorder={} adapter={} recordEveryReject={}",
                    tuningEventRecorder != null ? "WIRED" : "NULL",
                    oiMomentumCaptureAdapter != null ? "WIRED" : "NULL",
                    config.isRecordEveryReject());
        }
        if (tuningEventRecorder != null && oiMomentumCaptureAdapter != null) {
            try {
                String normalized = oiMomentumCaptureAdapter.normalizeBlocker(reason);
                if (config.isRecordEveryReject()) {
                    Instant now = Instant.now();
                    var singleton = new com.algo.trade.tuning.infra.EpisodeAggregator.EpisodeRow<
                            IndexType, String, OiMomentumEntryDiagnostics>(
                            indexType, normalized, now, now, 1, partial);
                    tuningEventRecorder.record(
                            oiMomentumCaptureAdapter.buildEvaluationEvent(singleton));
                } else {
                    var flushed = evaluationAggregator.record(indexType, normalized, partial, Instant.now());
                    for (var row : flushed) {
                        tuningEventRecorder.record(oiMomentumCaptureAdapter.buildEvaluationEvent(row));
                    }
                }
            } catch (Exception ex) {
                log.warn("[OIMomentum] dual-write evaluation episode failed (non-fatal): {}",
                        ex.getMessage());
            }
        }

        if (shouldSampleReject(state.lastRejectSampleTime, reason)) {
            return Instant.now();
        }
        return state.lastRejectSampleTime;
    }

    private boolean shouldSampleReject(Instant lastSampleTime, String rejectReason) {
        if (config.isRecordEveryReject()) {
            return true;
        }
        Duration interval = rejectReason != null && rejectReason.startsWith("matrix_skip:")
                ? Duration.ofSeconds(Math.max(1, config.getMatrixRejectSampleIntervalSeconds()))
                : Duration.ofSeconds(Math.max(1, config.getRejectSampleIntervalSeconds()));
        Instant now = Instant.now();
        return lastSampleTime == null || Duration.between(lastSampleTime, now).compareTo(interval) >= 0;
    }

    /**
     * Phase 2 dual-write: periodic flush of expired evaluation episodes that haven't
     * received a new tick within the dedup window. Fires every minute.
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void flushEvaluationEpisodes() {
        if (tuningEventRecorder == null || oiMomentumCaptureAdapter == null) {
            return;
        }
        try {
            var expired = evaluationAggregator.flushExpired(Instant.now());
            for (var row : expired) {
                tuningEventRecorder.record(oiMomentumCaptureAdapter.buildEvaluationEvent(row));
            }
        } catch (Exception ex) {
            log.debug("[OIMomentum] flush expired evaluation episodes failed: {}", ex.getMessage());
        }
    }

    private void incrementRejectReason(String reason) {
        String key = normalizeRejectReason(reason);
        rejectReasonCounters.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
    }

    private static String normalizeRejectReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "unknown";
        }
        if (reason.startsWith("matrix_skip:")) {
            return "matrix_skip";
        }
        if (reason.startsWith("low_bias:")) {
            return "low_bias";
        }
        if (reason.startsWith("sl_cooldown:")) {
            return "sl_cooldown";
        }
        if (reason.startsWith("tod_")) {
            return reason.split("\\|")[0];
        }
        int pipe = reason.indexOf('|');
        if (pipe > 0) {
            return reason.substring(0, pipe);
        }
        int colon = reason.indexOf(':');
        if (colon > 0 && !reason.startsWith("CASE")) {
            return reason.substring(0, colon);
        }
        return reason;
    }

    private String formatTopRejectReasons() {
        int topN = Math.max(1, config.getSummaryRejectTopN());
        return rejectReasonCounters.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue().get(), a.getValue().get()))
                .limit(topN)
                .map(e -> e.getKey() + "=" + e.getValue().get())
                .collect(Collectors.joining(","));
    }

    private static String matrixCaseLabel(String entryCase) {
        if (entryCase == null || entryCase.isBlank()) {
            return "";
        }
        if (entryCase.startsWith("SPIKE")) {
            return "SPIKE";
        }
        int idx = entryCase.indexOf('_');
        if (entryCase.startsWith("CASE") && idx > 0) {
            return entryCase.substring(0, idx);
        }
        return entryCase;
    }

    private void recordThrottleReject(IndexType indexType, IndexState state, String reason) {
        state.lastRejectReason = reason;
        state.lastRejectSampleTime = recordReject(indexType, state, reason,
                buildThrottleDiagnostics(indexType, state, reason));
    }

    private void recordGateReject(IndexType indexType, IndexState state, String reason,
                                  OiMomentumEntryDiagnostics diagnostics) {
        rejectedCount.incrementAndGet();
        state.lastRejectReason = reason;
        state.lastRejectSampleTime = recordReject(indexType, state, reason, diagnostics);
    }

    private OiMomentumEntryDiagnostics buildThrottleDiagnostics(IndexType indexType, IndexState state, String reason) {
        double spot = liveInstrumentCache.getFuturesPrice(indexType);
        int atm = spot > 0 ? indexType.roundToATM(spot) : 0;
        long[] oi = atm > 0 ? liveInstrumentCache.getAtmOiChange(indexType, atm, 3, 3) : new long[]{0, 0};
        double pcr = liveInstrumentCache.getRealtimePcr(indexType);
        double[] prem = atm > 0 ? atmOptionPremiums(indexType, atm) : new double[]{0, 0};
        double high30 = momentumDetector.getRolling30MinHigh(indexType);
        double low30 = momentumDetector.getRolling30MinLow(indexType);
        double rangePct = computeRangePct30m(indexType);
        // A6 (2026-06-02): expose pcrSlope5m in throttle path so the T2 signal
        // is visible in the 98% of evals that take the throttle branch. Today
        // PCR moved 1.05 → 1.45 in 25 min but every eval row showed 0.0
        // because the field was hardcoded here. Returns 0 until the
        // MarketContextService has >= 6 min of samples accumulated.
        double pcrSlope = (v3MarketContext != null)
                ? v3MarketContext.pcrSlope5Min(indexType) : 0.0;
        return new OiMomentumEntryDiagnostics(
                indexType, "", 0, "", 0, 0, pcrDir(pcr), pcr,
                oi[0], oi[1], oi[0] != 0 || oi[1] != 0, spot, atm,
                high30, low30, 0, "", marketGuard.getCurrentVix(),
                expiryCalendar.daysToExpiry(indexType), expiryCalendar.isExpiryDay(indexType),
                paperTrading(indexType), reason, state.oiAdvanced, rangePct, "", prem[0], prem[1],
                "", "", "THROTTLE", 0, 0)
                .withPcrSlope(pcrSlope);
    }

    private double computeRangePct30m(IndexType indexType) {
        double high30m = momentumDetector.getRolling30MinHigh(indexType);
        double low30m = momentumDetector.getRolling30MinLow(indexType);
        if (high30m > 0 && low30m > 0) {
            return (high30m - low30m) / low30m * 100;
        }
        return 0;
    }

    /** ATM CE/PE last prices from subscribed option cache (0 if missing). */
    private double[] atmOptionPremiums(IndexType indexType, int atm) {
        double ce = 0;
        double pe = 0;
        for (OptionInstrument opt : liveInstrumentCache.allOptions()) {
            if (opt.getIndexType() != indexType || opt.getStrikePrice() != atm) {
                continue;
            }
            if ("CE".equals(opt.getOptionType())) {
                ce = opt.getLastPrice();
            } else {
                pe = opt.getLastPrice();
            }
        }
        return new double[]{ce, pe};
    }

    private OiMomentumEntryDiagnostics buildDiagnostics(
            IndexType indexType,
            IndexState state,
            TickMomentumDetector.MomentumSignal momentum,
            int oiDir,
            int pcrDir,
            double pcr,
            long ceOiChange,
            long peOiChange,
            boolean oiAvailable,
            double spot,
            int atm,
            String entryCase,
            String spikeEpisodeId,
            String blockDetail,
            String timeOfDayMode,
            String matrixCase,
            String entryPath,
            int operatorScore,
            int biasScore) {
        double high30 = momentumDetector.getRolling30MinHigh(indexType);
        double low30 = momentumDetector.getRolling30MinLow(indexType);
        double distPct = 0;
        if (high30 > 0 && low30 > 0 && spot > 0) {
            if (momentum.direction() > 0) {
                distPct = (spot - high30) / high30 * 100;
            } else if (momentum.direction() < 0) {
                distPct = (low30 - spot) / low30 * 100;
            }
        }
        double rangePct30m = computeRangePct30m(indexType);
        state.lastRangePct30m = rangePct30m;
        double[] prem = atmOptionPremiums(indexType, atm);
        return new OiMomentumEntryDiagnostics(
                indexType,
                entryCase != null ? entryCase : "",
                momentum.direction(),
                momentum.type(),
                momentum.magnitude(),
                oiDir,
                pcrDir,
                pcr,
                ceOiChange,
                peOiChange,
                oiAvailable,
                spot,
                atm,
                high30,
                low30,
                distPct,
                spikeEpisodeId != null ? spikeEpisodeId : "",
                marketGuard.getCurrentVix(),
                expiryCalendar.daysToExpiry(indexType),
                expiryCalendar.isExpiryDay(indexType),
                paperTrading(indexType),
                "",
                state.oiAdvanced,
                rangePct30m,
                blockDetail != null ? blockDetail : "",
                prem[0],
                prem[1],
                timeOfDayMode != null ? timeOfDayMode : "",
                matrixCase != null ? matrixCase : "",
                entryPath != null ? entryPath : "LEGACY",
                operatorScore,
                biasScore);
    }

    private int pcrDir(double pcr) {
        if (pcr >= config.getPcrBullishThreshold()) return 1;
        if (pcr <= config.getPcrBearishThreshold()) return -1;
        return 0;
    }

    /**
     * Derive OI direction from CE/PE change vs opening baseline.
     *
     * Three signals are recognized:
     *
     * (A) Fresh buildup — strongest signal:
     *   PE growing faster than CE (peΔ > ceΔ, peΔ > 0) → operators writing PE support → bullish (+1)
     *   CE growing faster than PE (ceΔ > peΔ, ceΔ > 0) → operators writing CE resistance → bearish (-1)
     *
     * (B) OI Squeeze / short-covering — both negative (all holders closing):
     *   PE unwinding faster → put holders cashing out as spot falls → bearish continuation (-1, buy PE)
     *   CE unwinding faster → call holders exiting as spot rises → bullish continuation (+1, buy CE)
     *
     * (C) Asymmetry guard — distribution masquerading as accumulation:
     *   When one side is unwinding much faster (>2×) than the other side is building,
     *   the dominant signal is institutional distribution / closing, not fresh accumulation.
     *   Firing in this state has historically produced losing entries (e.g. 2026-05-22 11:46
     *   ceOiChange=-3.6M, peOiChange=+1.4M → false bullish, -10.9% loss).
     *   Return 0 (ambiguous) so the strategy skips rather than taking a bad trade.
     *   Guard only activates when the unwinding side exceeds 500k contracts (significance floor).
     *
     * Returns 0 when no clear directional bias exists (mixed, zero, or ambiguous changes).
     */
    private int oiDir(long ceOiChange, long peOiChange) {
        // (C) Asymmetry guard: large one-sided unwind swamps a small build on the other side.
        final long MIN_SIGNIFICANT = 500_000L;
        if (ceOiChange < 0 && peOiChange > 0
                && Math.abs(ceOiChange) >= MIN_SIGNIFICANT
                && Math.abs(ceOiChange) > 2 * peOiChange) {
            return 0; // CE mass exit dwarfs PE build — ambiguous distribution signal, skip
        }
        if (peOiChange < 0 && ceOiChange > 0
                && Math.abs(peOiChange) >= MIN_SIGNIFICANT
                && Math.abs(peOiChange) > 2 * ceOiChange) {
            return 0; // PE mass exit dwarfs CE build — ambiguous distribution signal, skip
        }

        // (A) Primary: fresh OI buildup
        if (peOiChange > ceOiChange && peOiChange > 0) return 1;
        if (ceOiChange > peOiChange && ceOiChange > 0) return -1;
        // (B) OI Squeeze: both negative — whichever unwinds faster dominates
        if (peOiChange < 0 && ceOiChange < 0 && peOiChange < ceOiChange) return -1; // PE squeeze → bearish continuation
        if (peOiChange < 0 && ceOiChange < 0 && ceOiChange < peOiChange) return 1;  // CE squeeze → bullish continuation
        return 0;
    }

    private boolean paperTrading(IndexType indexType) {
        var db = getCachedConfig(indexType);
        return db != null ? db.isPaperTrading() : config.isPaperTrading();
    }

    /**
     * Evaluate which entry case applies and whether to enter or skip.
     *
     * @param momentumType  e.g. "30M_LOW_BREAK", "30M_HIGH_BREAK" — used to relax
     *                      the CASE3 range guard for genuine breakouts.
     */
    private EntryCaseEvaluation evaluateEntryCaseDetail(IndexType indexType, int momentumDir, int oiDir,
                                                      int pcrDir, boolean oiAvailable, String momentumType,
                                                      long ceOiChange, long peOiChange) {
        // Case 1: All three align
        if (oiAvailable && oiDir == momentumDir && pcrDir == momentumDir) {
            return new EntryCaseEvaluation(momentumDir, "");
        }
        // Case 2: Momentum + PCR align, no OI
        if (!oiAvailable && pcrDir == momentumDir && pcrDir != 0) {
            return new EntryCaseEvaluation(momentumDir, "");
        }
        // Case 3: Momentum + OI align, PCR neutral
        if (oiAvailable && oiDir == momentumDir && pcrDir == 0) {
            double rangePct = computeRangePct30m(indexType);
            // Bypass narrow-range guard when:
            // (a) breakout momentum — tight range is the setup, not a skip reason
            // (b) dual-negative OI squeeze with material |Δ| on both legs (not WS noise)
            // Any timeframe breakout bypasses the narrow-range guard (30M, 15M, 5M)
            boolean isBreakout = momentumType != null &&
                    (momentumType.contains("HIGH_BREAK") || momentumType.contains("LOW_BREAK"));
            boolean isOiSqueeze = isMaterialOiSqueeze(ceOiChange, peOiChange);
            if (!isBreakout && !isOiSqueeze && rangePct > 0 && rangePct < 0.3) {
                return new EntryCaseEvaluation(0, "CASE3_RANGE_LT_0.3:" + String.format("%.3f", rangePct));
            }
            return new EntryCaseEvaluation(momentumDir, "");
        }
        // Case 4: Conflict (OI vs momentum) → SKIP
        if (oiAvailable && oiDir != 0 && oiDir != momentumDir) {
            return new EntryCaseEvaluation(0, "CASE4_OI_VS_MOMENTUM");
        }
        // Case 5: PCR conflicts, no OI → check operator framework before skipping
        if (!oiAvailable && pcrDir != 0 && pcrDir != momentumDir) {
            String operatorOverride = tryOperatorOverride(indexType, momentumDir, "CASE5_PCR_VS_MOMENTUM");
            if (operatorOverride != null) {
                return new EntryCaseEvaluation(momentumDir, operatorOverride);
            }
            return new EntryCaseEvaluation(0, "CASE5_PCR_VS_MOMENTUM");
        }
        if (!oiAvailable) {
            String operatorOverride = tryOperatorOverride(indexType, momentumDir, "CASE5_OI_UNAVAILABLE");
            if (operatorOverride != null) {
                return new EntryCaseEvaluation(momentumDir, operatorOverride);
            }
            return new EntryCaseEvaluation(0, "CASE5_OI_UNAVAILABLE");
        }
        // Fallback Case 5
        String operatorOverride = tryOperatorOverride(indexType, momentumDir, "CASE5_NO_RULE");
        if (operatorOverride != null) {
            return new EntryCaseEvaluation(momentumDir, operatorOverride);
        }
        return new EntryCaseEvaluation(0, "CASE5_NO_RULE");
    }

    /**
     * Ask the Operator Framework if it can upgrade a CASE5 block to an actionable entry.
     * Returns the upgrade label to store in blockDetail (for reporting), or null to keep blocking.
     */
    private String tryOperatorOverride(IndexType indexType, int momentumDir, String case5Reason) {
        if (operatorFrameworkService == null) return null;
        return operatorFrameworkService.evaluateCase5Override(indexType, momentumDir, case5Reason);
    }

    /** Use operator override label in tune CSV when matrix would still say CASE5_SKIP. */
    private String entryCaseLabel(String matrixCase, EntryCaseEvaluation eval) {
        if (eval.direction() == 0) {
            return matrixCase;
        }
        String detail = eval.blockDetail();
        if (detail == null || detail.isBlank() || !detail.startsWith("CASE")) {
            return matrixCase;
        }
        int bracket = detail.indexOf('[');
        return bracket > 0 ? detail.substring(0, bracket) : detail;
    }

    private boolean isMaterialOiSqueeze(long ceOiChange, long peOiChange) {
        long floor = config.getMinSqueezeOiDelta();
        return ceOiChange < 0 && peOiChange < 0
                && Math.abs(ceOiChange) >= floor
                && Math.abs(peOiChange) >= floor;
    }

    /** Reason suffix: opScore=72 when entryCase already carries the operator label. */
    private String formatOperatorEntryTag(String entryCase, String blockDetail) {
        if (blockDetail == null || blockDetail.isBlank() || !blockDetail.startsWith("CASE")) {
            return "";
        }
        String scoreSuffix = operatorScoreSuffix(blockDetail);
        if (scoreSuffix.isEmpty()) {
            return " operator=" + blockDetail;
        }
        if (entryCase != null && blockDetail.startsWith(entryCase + "[")) {
            return " opScore=" + scoreSuffix;
        }
        return " operator=" + blockDetail;
    }

    /** Tune CSV blockDetail: score only when entryCase column already has the operator label. */
    private String diagnosticsBlockDetail(String entryCase, String blockDetail) {
        if (blockDetail == null || blockDetail.isBlank()) {
            return "";
        }
        if (entryCase != null && blockDetail.startsWith(entryCase + "[")) {
            String scoreSuffix = operatorScoreSuffix(blockDetail);
            return scoreSuffix.isEmpty() ? blockDetail : "score=" + scoreSuffix;
        }
        return blockDetail;
    }

    /** Extract numeric score from CASE2_OPERATOR[score=72]. */
    private String operatorScoreSuffix(String blockDetail) {
        int scoreIdx = blockDetail.indexOf("score=");
        if (scoreIdx < 0) {
            return "";
        }
        int start = scoreIdx + "score=".length();
        int end = blockDetail.indexOf(']', start);
        if (end < 0) {
            end = blockDetail.length();
        }
        String score = blockDetail.substring(start, end).trim();
        return score.isEmpty() ? "" : score;
    }

    private String describeCase(int mDir, int oiDir, int pcrDir, boolean oiAvail) {
        if (oiAvail && oiDir == mDir && pcrDir == mDir) return "CASE1_ALL_ALIGN";
        if (!oiAvail && pcrDir == mDir) return "CASE2_M+PCR";
        if (oiAvail && oiDir == mDir && pcrDir == 0) return "CASE3_M+OI";
        if (oiAvail && oiDir != 0 && oiDir != mDir) return "CASE4_CONFLICT";
        return "CASE5_SKIP";
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // POSITION MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════════

    private void managePosition(IndexType indexType, IndexState state) {
        TradeEntity trade = tradeRepository.findById(state.activeTradeId).orElse(null);
        if (trade == null || trade.getStatus() != TradeStatus.OPEN) {
            // ── Fixes #2 / #5 / #6 / #8 (2026-06-02) ──────────────────────────
            // External-close auto-heal: when a trade closes via a path that
            // bypasses closePosition() — OrderFillWatchdog (manual UI close),
            // FailSafeSquareoffDaemon, GracefulShutdownHandler — this branch
            // discovers the close on the next tick. Previously it only nulled
            // out activeTradeId/Direction/peakPrice and re-entry boost / daily
            // P&L / decisionKey leaked because nothing else fired. Now we also:
            //   - log a WARN so the operator can see external close happened
            //   - record lastProfitableExitTime if the trade made money (so
            //     re-entry boost actually fires on the same-direction next
            //     entry, like it does on closePosition exits)
            //   - update state.dailyPnl from trade.realizedPnl so the daily
            //     loss circuit-breaker counts external-close losses too
            //   - clear lastEntryDecisionKey and lastEntryDiagnostics so the
            //     next entry's tune-CSV row gets fresh fields
            if (trade != null) {
                BigDecimal entryPx = trade.getEntryPrice();
                BigDecimal exitPx = trade.getExitPrice();
                BigDecimal pnl = trade.getRealizedPnl();
                String exitReason = trade.getExitReason();
                log.warn("[OIMomentum][{}] activeTradeId={} closed externally (reason={}, exit={}, pnl={}) — auto-healing state",
                        indexType, trade.getTradeId(), exitReason, exitPx, pnl);
                // Re-entry boost: trade was profitable iff entry/exit signed
                // P&L is positive. For longs (current OI Momentum only buys
                // calls/puts) that's exit > entry.
                if (entryPx != null && exitPx != null
                        && exitPx.compareTo(entryPx) > 0
                        && state.activeDirection != 0) {
                    state.lastProfitableExitTime = Instant.now();
                    state.lastProfitableExitDirection = state.activeDirection;
                    log.info("[OIMomentum][{}] external close was profitable — reEntryBoost armed (dir={})",
                            indexType, state.activeDirection);
                }
                // Daily P&L from realisedPnl (broker-confirmed, not estimated).
                if (pnl != null) {
                    state.dailyPnl += pnl.doubleValue();
                    if (pnl.signum() < 0) {
                        state.totalLossesPnl += pnl.doubleValue();
                        state.totalLossesCount.incrementAndGet();
                    }
                }
            }
            state.activeTradeId = null;
            state.activeDirection = 0;
            state.activeStrike = 0;
            state.peakPrice = 0;
            state.peakConfirmationTicks = 0;
            state.lastEntryDecisionKey = null;
            state.lastEntryDiagnostics = null;
            return;
        }

        // Get current price
        Optional<Quote> quoteOpt = marketDataService.quote(trade.getInstrumentKey());
        if (quoteOpt.isEmpty()) return;
        Quote quote = quoteOpt.get();
        double currentPrice = quote.lastPrice().doubleValue();
        if (currentPrice <= 0) return;

        // P0 #5: Quote staleness check — force exit if quote is older than 60s
        if (quote.timestamp() != null
                && Duration.between(quote.timestamp(), Instant.now()).getSeconds() > 60) {
            log.warn("[OIMomentum][{}] Stale quote ({}s old) for {} — force closing",
                    indexType, Duration.between(quote.timestamp(), Instant.now()).getSeconds(), trade.getInstrumentKey());
            closePosition(indexType, state, trade, currentPrice, "STALE_QUOTE_FORCE_EXIT");
            return;
        }

        double entryPrice = trade.getEntryPrice().doubleValue();

        // Regime-hold (trend-capture) exit profile — applies ONLY to SUSTAINED_DRIFT
        // (D2) origin trades. entryReason is persisted, so this classification
        // survives restarts and DB reconciliation. When the flag is off, or for any
        // non-drift trade, every exit below behaves exactly as before.
        String entryReason = trade.getEntryReason();
        boolean regimeHold = config.isRegimeHoldEnabled()
                && entryReason != null
                && entryReason.contains("SUSTAINED_DRIFT");

        // P0 #3: Track and persist peak price.
        // A2 (2026-06-02): also track peak-confirmation tick count — the BE-stop
        // can only arm once the peak has held for 2 consecutive ticks. This
        // blocks the 17-second flat-scalp pathology (today's 52.05→52.30 trade).
        if (currentPrice > state.peakPrice) {
            state.peakPrice = currentPrice;
            state.peakConfirmationTicks = Math.min(state.peakConfirmationTicks + 1, 99);
            if (trade.getPeakPrice() == null || BigDecimal.valueOf(state.peakPrice).compareTo(trade.getPeakPrice()) > 0) {
                trade.setPeakPrice(BigDecimal.valueOf(state.peakPrice));
                tradeRepository.save(trade);
            }
        } else if (currentPrice < state.peakPrice * 0.998) {
            // Retraced more than 0.2% from peak — reset confirmation streak so a
            // future new peak must re-confirm.
            state.peakConfirmationTicks = 0;
        }

        double profitPct = (currentPrice - entryPrice) / entryPrice * 100;
        double peakPct = (state.peakPrice - entryPrice) / entryPrice * 100;

        // P2 #18: Squareoff time
        LocalTime now = LocalTime.now(IST);
        if (!now.isBefore(LocalTime.of(config.getSquareoffHour(), config.getSquareoffMinute()))) {
            closePosition(indexType, state, trade, currentPrice, "SQUAREOFF_TIME");
            updateDailyPnl(state, profitPct, trade);
            return;
        }

        // P2 #17: Stop Loss fires REGARDLESS of minimum hold time
        // Use trade's applied SL if set (from DB/UI config at entry), fall back to YAML
        double slPercent = trade.getAppliedStopLossPercent() != null
                ? trade.getAppliedStopLossPercent().doubleValue()
                : config.getStopLossPercent();

        // Break-even stop: once peak profit ≥ trigger, floor SL to entry price (0%).
        // A2 (2026-06-02): require 2 consecutive peak confirmations before BE arms
        // — single-tick spike no longer triggers BE-then-immediate-exit.
        // E2 (2026-06-02): on expiry day, use +1% floor instead of 0% to account
        // for theta decay carrying premium back to entry within minutes.
        double beTrigger = config.getBreakEvenTriggerPercent();
        boolean expiryDay = expiryCalendar.isExpiryDay(indexType);
        boolean peakConfirmed = state.peakConfirmationTicks >= 2;
        boolean beArmed = beTrigger > 0 && peakPct >= beTrigger && peakConfirmed;
        if (beArmed) {
            double beFloor = expiryDay ? -1.0 : 0.0;  // expiry: SL no worse than +1% profit;
                                                       // non-expiry: SL no worse than entry
            slPercent = Math.min(slPercent, beFloor);
        }

        if (profitPct <= -slPercent) {
            // A2 (2026-06-02): tag exit reason with confirmation count + peak% so
            // tune CSV / Telegram shows whether BE armed on a deeply-confirmed
            // peak or a shallow one. Helps post-hoc tune the threshold.
            String reason = beArmed
                    ? String.format("BREAK_EVEN_STOP(confirms=%d,peak=%.2f%%)",
                            state.peakConfirmationTicks, peakPct)
                    : "STOP_LOSS";
            closePosition(indexType, state, trade, currentPrice, reason);
            if ("STOP_LOSS".equals(reason)) {
                state.lastSlTime = Instant.now();
                state.consecutiveLosses.incrementAndGet();
            }
            updateDailyPnl(state, profitPct, trade);
            return;
        }

        // Minimum hold time (gates target/trailing/reversal, NOT SL)
        if (state.lastEntryTime != null && Duration.between(state.lastEntryTime, Instant.now()).getSeconds() < config.getMinimumHoldTimeSeconds()) {
            return;
        }

        // No fixed target — let the trailing stop handle profit-taking.
        // The trade runs as long as it keeps moving up; only exits on retrace.

        // Trailing Stop — use trade's applied values (from DB/UI config at entry time), fall back to YAML
        double trailActivation = trade.getAppliedTrailingStopActivationPercent() != null
                ? trade.getAppliedTrailingStopActivationPercent().doubleValue()
                : config.getTrailingActivationPercent();
        double trailGap = trade.getAppliedTrailingGapPercent() != null
                ? trade.getAppliedTrailingGapPercent().doubleValue()
                : config.getTrailingGapPercent();
        // Regime-hold: trend-origin trades arm the trail later and ride a wider,
        // non-tightening gap so a steady grind isn't scalped on the first wobble.
        if (regimeHold) {
            trailActivation = config.getRegimeHoldTrailActivationPercent();
            trailGap = config.getRegimeHoldTrailGapPercent();
        }
        if (peakPct >= trailActivation) {
            double effectiveGap;
            if (regimeHold) {
                // No aggressive tightening for trend-rides — hold the full gap.
                effectiveGap = trailGap;
            } else {
                // Aggressive tightening: reduce gap as profit grows beyond activation
                // For every 1% above activation, shrink gap by 0.6%, floor at 40% of original gap
                double excessAboveActivation = peakPct - trailActivation;
                double tightenedGap = trailGap - (excessAboveActivation * 0.6);
                double minGap = trailGap * 0.4; // Never tighter than 40% of configured gap
                effectiveGap = Math.max(tightenedGap, minGap);
            }
            double trailLevel = peakPct - effectiveGap;
            if (profitPct < trailLevel) {
                closePosition(indexType, state, trade, currentPrice,
                        regimeHold ? "TRAILING_STOP_REGIME_HOLD" : "TRAILING_STOP");
                if (profitPct > 0) state.consecutiveLosses.set(0);
                else state.consecutiveLosses.incrementAndGet();
                updateDailyPnl(state, profitPct, trade);
                return;
            }
        }

        // Reverse on OI flip (only when OI actually advanced). Regime-hold trades
        // suppress this entirely: OI flip is the dominant churn source on trend days
        // (2026-06-12 OI_FLIP_REVERSE exits cut at ~+1% while held trends made ~+9%).
        // The hard SL and the (widened) trailing stop still protect the position.
        boolean suppressOiFlip = regimeHold && config.isRegimeHoldSuppressOiFlip();
        if (!suppressOiFlip && state.oiAdvanced && state.reversalsToday.get() < config.getMaxReversalsPerDay()) {
            // P3 #25: Reversal cooldown — min 60s between reversals
            if (state.lastReversalTime != null && Duration.between(state.lastReversalTime, Instant.now()).getSeconds() < 60) {
                return;
            }
            // OI-flip grace period (2026-06-12 churn fix): RANGE_EDGE_FADE entries are
            // COUNTER-flow by design — at a fade entry the opposing OI build-up already
            // exists, so the "flip" test passes almost immediately (two trades exited
            // 47s/56s after entry, both flat/negative). Give the thesis time to play out
            // before the flip detector may exit; the hard SL still applies meanwhile.
            if (state.lastEntryTime != null && Duration.between(state.lastEntryTime, Instant.now())
                    .getSeconds() < config.getOiFlipMinHoldSeconds()) {
                return;
            }

            double spot = momentumDetector.getSpot(indexType);
            int atm = indexType.roundToATM(spot);
            long[] oiChange = liveInstrumentCache.getAtmOiChange(indexType, atm, 3, 3);
            long ceOiChange = oiChange[0];
            long peOiChange = oiChange[1];

            boolean oiFlipped = false;
            if (state.activeDirection == 1 && ceOiChange > peOiChange && ceOiChange > 5000) {
                oiFlipped = true;
            } else if (state.activeDirection == -1 && peOiChange > ceOiChange && peOiChange > 5000) {
                oiFlipped = true;
            }

            if (oiFlipped && profitPct < 5) {
                closePosition(indexType, state, trade, currentPrice, "OI_FLIP_REVERSE");
                updateDailyPnl(state, profitPct, trade);
                state.reversalsToday.incrementAndGet();
                state.lastReversalTime = Instant.now();
                // P0 #2: Route reversal through full entry gates
                int newDirection = state.activeDirection * -1;
                double freshSpot = liveInstrumentCache.getFuturesPrice(indexType);
                if (freshSpot > 0) {
                    state.activeDirection = 0;
                    double revPcr = liveInstrumentCache.getRealtimePcr(indexType);
                    OiMomentumEntryDiagnostics revDiag = buildDiagnostics(
                            indexType, state,
                            new TickMomentumDetector.MomentumSignal(newDirection, "REVERSE:OI_FLIP", 0, freshSpot),
                            oiDir(ceOiChange, peOiChange), pcrDir(revPcr), revPcr,
                            ceOiChange, peOiChange, true, freshSpot,
                            indexType.roundToATM(freshSpot), "REVERSE:OI_FLIP", null, "",
                            "", "REVERSE", "REVERSE", 0, 0);
                    enterWithGates(indexType, state, newDirection, "REVERSE:OI_FLIP", freshSpot, revDiag);
                }
            }
        }
    }

    /** P2 #24: Track daily P&L for drawdown cap. */
    private void updateDailyPnl(IndexState state, double profitPct, TradeEntity trade) {
        double pnl = (profitPct / 100.0) * trade.getEntryPrice().doubleValue() * trade.getQuantity();
        state.dailyPnl += pnl;
        // v3 adaptive circuit-breaker: track loser stats for avg-loser threshold.
        if (pnl < 0) {
            state.totalLossesPnl += pnl;
            state.totalLossesCount.incrementAndGet();
        }
        checkDailyCircuitBreaker(state);
    }

    /**
     * v3 daily loss circuit-breaker. Two independent triggers, either trips the day-halt:
     *   (a) absolute rupee floor: dailyPnl < -dailyLossLimitRupees (when > 0).
     *   (b) adaptive multiple-of-avg-loser: dailyPnl < -(mult × avg-loser).
     * Once tripped, state.haltedForDay = true and detectEntry() short-circuits.
     */
    private void checkDailyCircuitBreaker(IndexState state) {
        if (state.haltedForDay) return;
        double limit = config.getDailyLossLimitRupees();
        if (limit > 0 && state.dailyPnl < -limit) {
            state.haltedForDay = true;
            log.warn("[OIMomentum] Day halted — dailyPnl={} < -{} (absolute floor)",
                    String.format("%.0f", state.dailyPnl), String.format("%.0f", limit));
            if (telegramAlertService != null) {
                telegramAlertService.systemAlert(String.format(
                        "🛑 OIMomentum: day halted — P&L ₹%.0f hit absolute floor ₹-%.0f",
                        state.dailyPnl, limit));
            }
            return;
        }
        double mult = config.getDailyLossMultiplierOfAvgLoser();
        int lossCount = state.totalLossesCount.get();
        if (mult > 0 && lossCount > 0) {
            double avgLoser = Math.abs(state.totalLossesPnl) / lossCount;
            if (state.dailyPnl < -(mult * avgLoser)) {
                state.haltedForDay = true;
                log.warn("[OIMomentum] Day halted — dailyPnl={} < -{}×avgLoser({})",
                        String.format("%.0f", state.dailyPnl), mult, String.format("%.0f", avgLoser));
                if (telegramAlertService != null) {
                    telegramAlertService.systemAlert(String.format(
                            "🛑 OIMomentum: day halted — P&L ₹%.0f below %.1f×avg-loser (₹%.0f)",
                            state.dailyPnl, mult, avgLoser));
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // EXECUTION
    // ═══════════════════════════════════════════════════════════════════════════

    private void enter(IndexType indexType, IndexState state, int direction, String reason, double spot,
                       OiMomentumEntryDiagnostics diagnostics) {
        if (state.activeTradeId != null) {
            rejectedCount.incrementAndGet();
            state.lastRejectReason = "active_trade_exists";
            return;
        }
        if (state.pendingEntryInstrumentKey != null) {
            rejectedCount.incrementAndGet();
            state.lastRejectReason = "pending_entry_exists";
            return;
        }
        enterWithGates(indexType, state, direction, reason, spot, diagnostics);
    }

    /**
     * P0 #2: All entry paths (including reversals) go through this method
     * which enforces all throttle/safety gates.
     */
    private void enterWithGates(IndexType indexType, IndexState state, int direction, String reason, double spot,
                                OiMomentumEntryDiagnostics diagnostics) {
        if (state.activeTradeId != null) return;

        // Re-check all entry gates
        if (state.tradesToday.get() >= config.getMaxTradesPerDay()) {
            recordGateReject(indexType, state, "max_trades_day", diagnostics);
            return;
        }
        if (state.consecutiveLosses.get() >= config.getConsecutiveLossPause()) {
            recordGateReject(indexType, state, "consecutive_loss_pause", diagnostics);
            return;
        }
        if (state.lastSlTime != null && Duration.between(state.lastSlTime, Instant.now()).getSeconds() < config.getCooldownAfterSlSeconds()) {
            long remaining = config.getCooldownAfterSlSeconds()
                    - Duration.between(state.lastSlTime, Instant.now()).getSeconds();
            recordGateReject(indexType, state, "sl_cooldown:" + remaining + "s", diagnostics);
            return;
        }
        // P2 #19: Allow event spikes to bypass MarketGuard
        String mgBlock = marketGuard.longPremiumBlockReason();
        if (!reason.startsWith("SPIKE:") && mgBlock != null) {
            recordGateReject(indexType, state,
                    MarketGuard.normalizeLongPremiumRejectToken("market_guard_entry", mgBlock), diagnostics);
            return;
        }
        LocalTime now = LocalTime.now(IST);
        if (!now.isBefore(LocalTime.of(config.getSquareoffHour(), config.getSquareoffMinute()).minusMinutes(10))) {
            recordGateReject(indexType, state, "squareoff_window", diagnostics);
            return;
        }

        // Check DB config for paper/live mode
        var dbConfig = getCachedConfig(indexType);
        boolean paperMode = (dbConfig != null) ? dbConfig.isPaperTrading() : config.isPaperTrading();

        int atm = indexType.roundToATM(spot);
        // Anti-pyramid is now checked AFTER V3 picks the actual entry strike (see
        // below) so the cooldown lookup uses the entry strike rather than rounded ATM.
        // Review fix #45.

        // ── V3 OPERATOR pipeline integration ──────────────────────────────
        // When v3Enabled, run the full operator pipeline and use its decision for
        // strike/lots. When v3ShadowMode, log the v3 decision but still use legacy.
        // Recording (review #51): the V3DecisionRecord is written to the CSV ONLY
        // when v3Enabled is true (the pipeline itself is gated by that flag). When
        // v3Enabled is false the recorder never sees a row.
        int v3OverrideStrike = atm;
        int v3OverrideLots = -1;  // -1 means "use legacy lots" (units: LOT COUNT, not shares)
        OptionType v3OverrideType = null;
        if (config.isV3Enabled() && v3EntryPipeline != null && v3MarketContext != null) {
            try {
                // baseLots semantically = LOT COUNT (not share count). The V3
                // ConvictionSizer scales from this baseline up to maxLotsPerTrade
                // (from GlobalConfig) based on conviction. We then multiply the
                // returned lot count by indexType.lotSize() below to convert to the
                // share quantity the executionEngine / broker expect — guaranteeing
                // the broker only sees exact multiples of the contract lot size.
                int baseLots = 1;
                var v3Snap = v3MarketContext.getLatestSnapshot(indexType);
                // P0a: pass a QuoteResolver so V3 can fetch the live quote for whatever
                // strike the multi-strike picker chooses, not just ATM.
                com.algo.trade.strategy.oimomentum.v3.V3EntryPipeline.QuoteResolver resolver =
                        (ix, strike, ot) -> {
                            try {
                                String key = instrumentCache.findOption(
                                        UnderlyingSymbol.valueOf(ix.name()),
                                        expiryCalendar.getCurrentExpiry(ix),
                                        BigDecimal.valueOf(strike), ot
                                ).map(i -> i.instrumentKey()).orElse(null);
                                if (key == null) return null;
                                return marketDataService.quote(key).orElse(null);
                            } catch (Exception qre) {
                                log.debug("[OIMomentum][{}] quote resolve failed for {} {}: {}",
                                        ix, strike, ot, qre.getMessage());
                                return null;
                            }
                        };

                var v3Decision = v3EntryPipeline.evaluate(
                        indexType, direction, reason,
                        diagnostics != null ? diagnostics.momentumMagnitudePct() : 0.0,
                        spot, diagnostics != null ? diagnostics.vix() : 0.0,
                        v3Snap, resolver, baseLots);

                if (config.isV3ShadowMode()) {
                    log.info("[OIMomentum][{}] V3 SHADOW: {} reason={} pattern={} gates={}/4 lots={}",
                            indexType, v3Decision.skip() ? "SKIP" : "ENTER", v3Decision.reason(),
                            v3Decision.pattern() != null ? v3Decision.pattern().name() : "-",
                            v3Decision.gateVerdict() != null ? v3Decision.gateVerdict().passedCount() : 0,
                            v3Decision.lots());
                } else {
                    // Live V3 — its decision is binding.
                    if (v3Decision.skip()) {
                        recordGateReject(indexType, state, "v3_skip:" + v3Decision.reason(), diagnostics);
                        return;
                    }
                    v3OverrideStrike = v3Decision.strike();
                    v3OverrideType = v3Decision.optionType();
                    v3OverrideLots = v3Decision.lots();
                    log.info("[OIMomentum][{}] V3 LIVE: ENTER strike={} type={} lots={} ({} shares) pattern={} gates={}/4",
                            indexType, v3OverrideStrike, v3OverrideType, v3OverrideLots,
                            v3OverrideLots * indexType.lotSize(),
                            v3Decision.pattern().name(),
                            v3Decision.gateVerdict().passedCount());
                }
            } catch (Exception v3ex) {
                log.warn("[OIMomentum][{}] V3 pipeline error (falling back to legacy): {}",
                        indexType, v3ex.getMessage());
            }
        }

        OptionType optType = v3OverrideType != null ? v3OverrideType
                : (direction > 0 ? OptionType.CE : OptionType.PE);
        if (v3OverrideStrike != atm) atm = v3OverrideStrike;

        // ── v3 Anti-pyramid (review fix #45) ──
        // Block same-strike re-entry if previous close on that strike was a loss
        // within antiPyramidCooldownMinutes. Lookup now uses the ENTRY strike (either
        // ATM in legacy mode, or the V3-picked strike when V3 is live), not just the
        // spot-rounded ATM.
        if (config.isAntiPyramidEnabled()) {
            Instant lastLoss = state.lastLossExitByStrike.get(atm);
            if (lastLoss != null) {
                long mins = Duration.between(lastLoss, Instant.now()).toMinutes();
                if (mins < config.getAntiPyramidCooldownMinutes()) {
                    recordGateReject(indexType, state,
                            "anti_pyramid_cooldown:strike=" + atm + ",mins=" + mins,
                            diagnostics);
                    return;
                }
            }
        }

        LocalDate expiry = expiryCalendar.getCurrentExpiry(indexType);
        UnderlyingSymbol underlying = UnderlyingSymbol.valueOf(indexType.name());

        Optional<Instrument> instOpt = instrumentCache.findOption(
                underlying, expiry, BigDecimal.valueOf(atm), optType);
        if (instOpt.isEmpty()) {
            log.debug("[OIMomentum][{}] Cannot resolve option: atm={}, type={}", indexType, atm, optType);
            return;
        }

        String instrumentKey = instOpt.get().instrumentKey();
        Optional<Quote> quoteOpt = marketDataService.quote(instrumentKey);
        if (quoteOpt.isEmpty() || quoteOpt.get().lastPrice().signum() <= 0) {
            log.debug("[OIMomentum][{}] No quote for {}", indexType, instrumentKey);
            return;
        }

        // P1 #7: Bid-ask spread / liquidity check
        Quote entryQuote = quoteOpt.get();
        if (entryQuote.bid().isPresent() && entryQuote.ask().isPresent()
                && entryQuote.ask().get().signum() > 0 && entryQuote.bid().get().signum() > 0) {
            double mid = entryQuote.bid().get().add(entryQuote.ask().get())
                    .divide(BigDecimal.valueOf(2), java.math.MathContext.DECIMAL64).doubleValue();
            double spreadPctVal = (entryQuote.ask().get().doubleValue() - entryQuote.bid().get().doubleValue()) / mid * 100;
            if (spreadPctVal > 5.0) {
                log.debug("[OIMomentum][{}] Entry rejected: bid-ask spread {}% > 5% for {}",
                        indexType, spreadPctVal, instrumentKey);
                recordGateReject(indexType, state, "spread_too_wide", diagnostics);
                return;
            }
        }

        BigDecimal premium = quoteOpt.get().lastPrice();
        // Compute final SHARE COUNT to send to the broker:
        //  • V3 live path → V3's ConvictionSizer already returns a LOT COUNT in
        //    [1, maxLotsPerTrade]; multiply by indexType.lotSize() for shares.
        //  • Legacy + CASE 0 path → convictionToLotCount() maps the entry's score
        //    (bias_score for CASE 1-5, op_score for CASE 0) to a lot count in
        //    [1, maxLotsPerTrade] using the same GlobalConfig cap. Multiply by
        //    indexType.lotSize() for shares.
        //  • SPIKE / REVERSE entries (no embedded score) → 1 lot (safety default).
        // This guarantees the broker always sees a quantity that is an exact
        // multiple of the contract lot size (a hard requirement at Zerodha / Upstox).
        int legacyLotCount = computeLegacyLotCount(reason);

        // CapitalAllocator ceiling: if wired, cap lots based on available capital + vol regime.
        // This ensures we never exceed capital limits regardless of conviction score.
        if (capitalAllocator != null && premium.doubleValue() > 0) {
            int openTrades = (int) indexStates.values().stream()
                    .filter(s -> s.activeTradeId != null).count();
            int capitalCap = capitalAllocator.getMaxLotsForTrade(
                    indexType, premium, openTrades);
            if (legacyLotCount > capitalCap) {
                log.debug("[OIMomentum][{}] CapitalAllocator cap applied: {} → {} lots (premium=₹{})",
                        indexType, legacyLotCount, capitalCap, premium);
                legacyLotCount = capitalCap;
            }
        }

        int lotSize = (v3OverrideLots > 0 && config.isV3Enabled() && !config.isV3ShadowMode())
                ? v3OverrideLots * indexType.lotSize()
                : legacyLotCount * indexType.lotSize();

        // Build decision for execution
        StrategyDecision decision = new StrategyDecision(
                Instant.now(), underlying,
                direction > 0 ? SignalType.BUY_CE : SignalType.BUY_PE,
                BigDecimal.valueOf(spot),
                Optional.of(premium), Optional.empty(),
                Optional.of(lotSize), Optional.of(premium.multiply(BigDecimal.valueOf(lotSize))),
                Optional.of(instrumentKey), Optional.of(BigDecimal.valueOf(atm)),
                Optional.of(optType), false, Optional.empty(), false,
                BigDecimal.ZERO,
                java.util.List.of("OI_MOMENTUM[" + indexType + "]: " + reason)
        );

        String decisionKey = com.algo.trade.reporting.SignalDecisionKey.from(decision);
        state.lastEntryDecisionKey = decisionKey;
        state.lastEntryDiagnostics = diagnostics;

        // Unified tuning pipeline. No-op when capture toggle is
        // off (default) or when adapter/recorder beans aren't present (legacy wiring).
        if (tuningEventRecorder != null && oiMomentumCaptureAdapter != null) {
            try {
                com.algo.trade.tuning.SignalEvent tuningSignal =
                        oiMomentumCaptureAdapter.buildSignalEvent(decision, diagnostics, indexType,
                                premium, decisionKey);
                tuningEventRecorder.record(tuningSignal);
            } catch (Exception ex) {
                log.warn("[OIMomentum] dual-write to TuningEventRecorder failed (non-fatal): {}",
                        ex.getMessage());
            }
        }

        if (paperMode) {
            var oiConfig = getCachedConfig(indexType);
            var result = executionEngine.executePaperEntry(decision, premium, lotSize, oiConfig);
            state.activeTradeId = result.tradeId().orElse(null);
        } else {
            // Publish signal to Universal Call Orchestrator for aggregation + lot scaling
            if (universalCallOrchestrator != null) {
                var orcSignal = com.algo.trade.strategy.UniversalCallOrchestrator.StrategySignal.withStrike(
                        "OI_MOMENTUM", indexType, direction,
                        70, // Entry already passed bias floor — 70 = confirmed conviction
                        atm, direction > 0 ? "CE" : "PE", reason);
                universalCallOrchestrator.submitSignal(orcSignal);
            }
            var oiConfig = getCachedConfig(indexType);
            var result = executionEngine.executeEntry(decision, premium, lotSize, oiConfig);
            state.activeTradeId = result.tradeId().orElse(null);
            if (state.activeTradeId == null && result.accepted()) {
                state.pendingEntryInstrumentKey = instrumentKey;
                state.activeDirection = direction;
                state.activeStrike = atm;
                state.lastEntryTime = Instant.now();
                state.peakPrice = premium.doubleValue();
                state.tradesToday.incrementAndGet();
                enteredCount.incrementAndGet();
                log.info("[OIMomentum][{}] ENTRY PENDING: order accepted, waiting for fill — instrument={}, reason={}",
                        indexType, instrumentKey, reason);
                return;
            }
        }

        if (state.activeTradeId != null) {
            state.activeDirection = direction;
            state.activeStrike = atm;
            state.lastEntryTime = Instant.now();
            state.peakPrice = premium.doubleValue();
            state.tradesToday.incrementAndGet();
            enteredCount.incrementAndGet();
            log.info("[OIMomentum][{}] ENTRY: direction={}, instrument={}, premium=₹{}, reason={}, trades={}",
                    indexType, direction > 0 ? "BULLISH" : "BEARISH", instrumentKey, premium, reason, state.tradesToday.get());

            // Phase 2 dual-write: register the trade with MaeMfeTracker so the
            // tracker's self-scheduled tick starts accumulating MAE/MFE. No-op when
            // tracker bean isn't present (tests / legacy wirings).
            if (maeMfeTracker != null) {
                try {
                    maeMfeTracker.onEntry(new com.algo.trade.tuning.infra.MaeMfeTracker.EntryContext(
                            state.activeTradeId,
                            StrategyType.OI_MOMENTUM,
                            indexType,
                            state.lastEntryDecisionKey,
                            com.algo.trade.tuning.infra.MaeMfeTracker.Direction.LONG,
                            instrumentKey,
                            atm,
                            direction > 0 ? OptionType.CE
                                          : OptionType.PE,
                            premium,
                            spot,
                            state.lastEntryTime));
                } catch (Exception ex) {
                    log.warn("[OIMomentum] MaeMfeTracker.onEntry failed (non-fatal): {}",
                            ex.getMessage());
                }
            }

            if (telegramAlertService != null) {
                telegramAlertService.systemAlert(String.format(
                        "🎯 OIMomentum[%s] Entry: %s %s | ₹%.2f | %s | Trade #%d",
                        indexType, direction > 0 ? "BUY CE" : "BUY PE", instrumentKey,
                        premium.doubleValue(), reason, state.tradesToday.get()));
            }
        }
    }

    private Double spreadPct(Quote quote) {
        try {
            if (quote.bid().isEmpty() || quote.ask().isEmpty()
                    || quote.bid().get().signum() <= 0 || quote.ask().get().signum() <= 0) {
                return null;
            }
            double mid = quote.bid().get().add(quote.ask().get())
                    .divide(BigDecimal.valueOf(2), java.math.MathContext.DECIMAL64).doubleValue();
            if (mid <= 0) {
                return null;
            }
            return (quote.ask().get().doubleValue() - quote.bid().get().doubleValue()) / mid * 100;
        } catch (Exception ex) {
            return null;
        }
    }

    private void closePosition(IndexType indexType, IndexState state, TradeEntity trade, double currentPrice, String reason) {
        boolean reversal = reason != null && reason.contains("REVERSE");
        // Track profitable exits for re-entry boost
        if (trade.getEntryPrice() != null && currentPrice > trade.getEntryPrice().doubleValue()) {
            state.lastProfitableExitTime = Instant.now();
            state.lastProfitableExitDirection = state.activeDirection;
        }
        // v3 Anti-pyramid: record losing strike + time for cooldown enforcement.
        // Computed pre-clear so we have entry price & quantity in hand.
        try {
            if (config.isAntiPyramidEnabled() && trade.getEntryPrice() != null
                    && state.activeStrike > 0) {
                double pnlForAntiPyramid = (currentPrice - trade.getEntryPrice().doubleValue()) * trade.getQuantity();
                if (pnlForAntiPyramid < 0) {
                    state.lastLossExitByStrike.put(state.activeStrike, Instant.now());
                }
            }
        } catch (Exception ex) {
            log.debug("[OIMomentum][{}] anti-pyramid record failed: {}", indexType, ex.getMessage());
        }
        try {
            executionEngine.closeTrade(trade.getTradeId(), BigDecimal.valueOf(currentPrice), reason);
            double pnl = (currentPrice - trade.getEntryPrice().doubleValue()) * trade.getQuantity();
            log.info("[OIMomentum][{}] EXIT: tradeId={}, reason={}, pnl=₹{}",
                    indexType, trade.getTradeId(), reason, pnl);

            // Phase 2 dual-write: pull the MAE/MFE snapshot from the tracker and emit
            // an ExitEvent. Tracker.onExit also removes the trade from the active set.
            if (tuningEventRecorder != null && oiMomentumCaptureAdapter != null
                    && maeMfeTracker != null) {
                try {
                    var snapshot = maeMfeTracker.onExit(trade.getTradeId()).orElse(null);
                    com.algo.trade.tuning.ExitEvent exitEvent = oiMomentumCaptureAdapter.buildExitEvent(
                            indexType, trade, snapshot,
                            state.lastEntryDecisionKey,
                            BigDecimal.valueOf(currentPrice),
                            reason,
                            reversal);
                    tuningEventRecorder.record(exitEvent);
                } catch (Exception ex) {
                    log.warn("[OIMomentum] dual-write ExitEvent failed (non-fatal): {}",
                            ex.getMessage());
                }
            }

            if (telegramAlertService != null) {
                telegramAlertService.systemAlert(String.format(
                        "📤 OIMomentum[%s] Exit: %s | ₹%.2f → ₹%.2f | P&L ₹%.0f | %s",
                        indexType, trade.getInstrumentKey(), trade.getEntryPrice().doubleValue(),
                        currentPrice, pnl, reason));
            }
        } catch (Exception e) {
            log.warn("[OIMomentum][{}] Close failed: {}", indexType, e.getMessage());
        }
        state.activeTradeId = null;
        state.activeDirection = 0;
        state.activeStrike = 0;
        state.peakPrice = 0;
        state.peakConfirmationTicks = 0;
        state.lastEntryDecisionKey = null;
        state.lastEntryDiagnostics = null;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ADAPTIVE BIAS ENGINE (Stage 1)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * 20-minute spot range as % of current spot. Returns 0 (not NaN) when the
     * detector hasn't warmed up so callers can use a simple {@code > 0} check.
     * Used by T3 conditional bias-floor lowering.
     */
    private double compute20mRangePctSafe(IndexType indexType) {
        try {
            double high = momentumDetector.getRollingHighInWindow(indexType, 20);
            double low = momentumDetector.getRollingLowInWindow(indexType, 20);
            double spot = momentumDetector.getSpot(indexType);
            if (high <= 0 || low <= 0 || spot <= 0 || high < low) return 0.0;
            return (high - low) / spot * 100.0;
        } catch (Exception e) {
            return 0.0;
        }
    }

    /**
     * Lightweight value type returned by computeBiasScore().
     *
     * @param direction    The direction the bias engine is scoring (+1 bullish, -1 bearish).
     * @param score        Composite confidence score 0–100. Threshold in OIMomentumConfig.
     * @param primarySignal Human-readable list of which signals contributed (for tune CSV).
     */
    private record BiasScore(int direction, float score, String primarySignal) {
        boolean isActionable(float threshold) { return score >= threshold; }
    }

    /**
     * Computes a composite bias confidence score (0–100) synthesising five Stage 1 signals:
     *
     *   [+30] Momentum signal fires (always true when called from detectEntry).
     *   [+25] OI direction aligns with momentum direction (–10 if actively opposes).
     *   [+20] PCR direction aligns (–5 if actively opposes).
     *   [+15] OI balance ratio confirms: PE OI dominant (>1.3×) = bullish; CE dominant = bearish.
     *   [–20] Opening noise dampener: before 09:30 IST, signals are unreliable.
     *   [–20] Bias decay: OI has not advanced for more than biasDecaySeconds.
     *
     * Phase 2 additions (OI wall +10, expiry buildup +15, VIX scaling) will extend this method.
     */
    private BiasScore computeBiasScore(IndexType indexType, IndexState state,
                                       int momentumDir, int oiDir, int pcrDir,
                                       boolean oiAvailable, long ceOiChange, long peOiChange,
                                       int atm) {
        float score = 0;
        StringBuilder sig = new StringBuilder();

        // ── [+30] Momentum base — always present when this method is called ──
        score += 30;
        sig.append("M(+30)");

        // ── [+25 / –10] OI direction ──────────────────────────────────────────
        if (oiAvailable) {
            if (oiDir == momentumDir) {
                score += 25;
                sig.append(" OI✓(+25)");
            } else if (oiDir != 0) {
                score -= 10;  // OI actively opposes momentum
                sig.append(" OI✗(-10)");
            }
            // oiDir == 0 (ambiguous) → no bonus, no penalty
        }

        // ── [+20 / –5] PCR direction ──────────────────────────────────────────
        if (pcrDir == momentumDir) {
            score += 20;
            sig.append(" PCR✓(+20)");
        } else if (pcrDir != 0) {
            score -= 5;
            sig.append(" PCR✗(-5)");
        }

        // ── [+15] OI balance ratio — absolute CE vs PE OI at ATM ± 5 strikes ──
        // PE OI dominant (ratio < 0.77) = operators writing puts = floor = bullish
        // CE OI dominant (ratio > 1.30) = operators writing calls = ceiling = bearish
        // Expanded from ±3 to ±5 strikes for better representation of operator positioning.
        long[] totalOi = getAtmTotalOi(indexType, atm, 5);
        if (totalOi[0] > 0 && totalOi[1] > 0) {
            double cePerPe = (double) totalOi[0] / totalOi[1];
            if (momentumDir > 0 && cePerPe < 0.77) {
                score += 15; // PE OI dominant = bullish confirmation
                sig.append(" BAL✓(+15)");
            } else if (momentumDir < 0 && cePerPe > 1.30) {
                score += 15; // CE OI dominant = bearish confirmation
                sig.append(" BAL✓(+15)");
            } else {
                sig.append(String.format(" BAL=%.2f", cePerPe));
            }
        }

        // ── [–10/–20] Opening noise dampener — first 10 min after market open ──
        // CASE3 (momentum + OI aligned) is a genuine signal even early; only penalise -10.
        // CASE5 / CASE2 (weak or ambiguous) get the full -20 to avoid first-candle fakeouts.
        // Previously applied -20 uniformly, which blocked all CASE3 setups until 09:30
        // even when OI was clearly building (e.g. today: PE OI +1.9M at ATM, score=75 from OperatorFW).
        LocalTime now = LocalTime.now(IST);
        if (now.isBefore(LocalTime.of(9, 30))) {
            boolean oiConfirmed = oiAvailable && oiDir == momentumDir;
            int noisePenalty = oiConfirmed ? 10 : 20; // CASE1/3 → -10; CASE2/5 → -20
            score -= noisePenalty;
            sig.append(String.format(" OPEN_NOISE(-%d)", noisePenalty));
        }

        // ── [–biasDecayPenalty] Bias decay — OI has been stale for longer than biasDecaySeconds ──
        if (state.lastOiTickTime != null) {
            long staleSecs = Duration.between(state.lastOiTickTime, Instant.now()).getSeconds();
            if (staleSecs > config.getBiasDecaySeconds()) {
                score -= config.getBiasDecayPenalty();
                sig.append(String.format(" DECAY(%ds,-%d)", staleSecs, config.getBiasDecayPenalty()));
            }
        } else if (!oiAvailable) {
            // No OI data at all — use configurable penalty (was hardcoded to 10)
            score -= config.getBiasDecayPenalty();
            sig.append(String.format(" NO_OI(-%d)", config.getBiasDecayPenalty()));
        }

        // ── [+5] CASE 4 watch-list bonus (P1-3, 29 May 2026 data-validated) ──
        // When a recent (≤20 min) CASE 4 fire had an OI direction matching this
        // momentum, treat that as an early reversal lead. Replay 60m win rate
        // when OI wins CASE 4: 53.6% (vs 46.4% if momentum wins).
        int c4Bonus = case4WatchBonus(indexType, momentumDir);
        if (c4Bonus > 0) {
            score += c4Bonus;
            sig.append(" C4_WATCH(+").append(c4Bonus).append(")");
        }

        // ── T2 (2 Jun 2026): PCR slope additive bonus ──────────────────────────
        // The binary level-threshold (pcr ≥ bullThreshold / pcr ≤ bearThreshold)
        // misreads a fast-rolling PCR as "neutral" while it's mid-flight. The
        // 1 Jun 2026 NIFTY tape: PCR ran 0.95 → 1.27 between 11:30 and 12:00 —
        // a clear bearish flip-in-progress that the level rule treated as flat.
        // Slope-based bonus catches the inflight signal while the level catches
        // sustained extremes. They are complementary, not redundant.
        if (config.isPcrSlopeBiasBonusEnabled() && v3MarketContext != null) {
            double slope = v3MarketContext.pcrSlope5Min(indexType);
            double minAbs = config.getPcrSlopeBiasMinAbs();
            boolean slopeAgrees = (momentumDir > 0 && slope >= +minAbs)
                    || (momentumDir < 0 && slope <= -minAbs);
            boolean slopeOpposes = (momentumDir > 0 && slope <= -minAbs)
                    || (momentumDir < 0 && slope >= +minAbs);
            if (slopeAgrees) {
                score += config.getPcrSlopeBiasBonusPoints();
                sig.append(String.format(" SLOPE✓(+%d,%+.3f)",
                        config.getPcrSlopeBiasBonusPoints(), slope));
            } else if (slopeOpposes) {
                score -= config.getPcrSlopeBiasOpposePenalty();
                sig.append(String.format(" SLOPE✗(-%d,%+.3f)",
                        config.getPcrSlopeBiasOpposePenalty(), slope));
            }
        }

        // ── [+0..+20] Operator Framework conviction bonus ─────────────────────────
        // Chain-level accumulation since open (5-min snapshot window) — bridges the gap
        // between institutional footprints visible in the chain and 1-second WS ticks.
        // May 22 example: PE OI at 23750 grew 619% by 11:30 — operator conviction was obvious
        // from chain data while WS ticks still showed zero delta.
        if (config.isOperatorBonusEnabled() && operatorFrameworkService != null) {
            int opBonus = operatorFrameworkService.getConfidenceBonus(indexType, momentumDir);
            if (opBonus > 0) {
                score += opBonus;
                sig.append(String.format(" OP(+%d)", opBonus));
            }
        }

        // ── [+0..30] Operator Intent Radar (5 Jun 2026) ───────────────────────────
        // Anticipatory detection: OI magnets, hourly cycle confirmation, cross-index
        // radar, max pain shifts, OI laddering, liquidity awareness, bias persistence.
        // Detects operator positioning BEFORE price breakouts.
        if (operatorIntentRadar != null) {
            OperatorIntentRadar.IntentSignal intent = operatorIntentRadar.evaluate(indexType, momentumDir);
            if (intent.bonus() > 0) {
                score += intent.bonus();
                sig.append(String.format(" RADAR(+%d%s)", intent.bonus(), intent.signals()));
            }
            if (intent.reversalReady()) {
                sig.append(" REVERSAL_ZONE");
            }
            if (intent.intentLocked()) {
                sig.append(" INTENT_LOCKED");
            }
            if (intent.probeEntryRecommended()) {
                sig.append(" PROBE_REC");
            }
            if (intent.scaleUpRecommended()) {
                sig.append(" SCALE_UP");
            }
            if (intent.flipRecommended()) {
                sig.append(" FLIP_REC");
            }
            if (intent.exitRecommended()) {
                sig.append(" EXIT_DECAY");
            }
        }

        // ── [+8] Bid-ask order book imbalance ─────────────────────────────────────
        // When the ATM option in the momentum direction has bid qty >> ask qty,
        // institutional buyers are lifting the ask — early accumulation footprint.
        if (config.isBidAskImbalanceEnabled()) {
            double bookImbalance = getAtmDirectionalBookImbalance(indexType, atm, momentumDir);
            if (bookImbalance >= config.getBidAskImbalanceThreshold()) {
                score += 8;
                sig.append(String.format(" BOOK(+8,%.2f)", bookImbalance));
            } else if (bookImbalance > 0) {
                sig.append(String.format(" BOOK=%.2f", bookImbalance));
            }
        }

        // ── [+8] IV Skew — implied volatility differential ────────────────────────
        // CE IV rising relative to PE IV = operators buying calls = early bullish signal.
        // PE IV rising relative to CE IV = put protection demand = bearish signal.
        // Normal skew (PE IV > CE IV) is baseline; deviation indicates fresh direction.
        if (config.isIvSkewEnabled()) {
            double ivSkew = getAtmIvSkew(indexType, atm); // positive = CE IV dominant
            if (momentumDir > 0 && ivSkew > config.getIvSkewThreshold()) {
                score += 8;
                sig.append(String.format(" SKEW_BULL(+8,%.2f)", ivSkew));
            } else if (momentumDir < 0 && ivSkew < -config.getIvSkewThreshold()) {
                score += 8;
                sig.append(String.format(" SKEW_BEAR(+8,%.2f)", ivSkew));
            } else if (Math.abs(ivSkew) > 0.05) {
                sig.append(String.format(" SKEW=%.2f", ivSkew));
            }
        }

        // ── [+10] OI velocity / acceleration ──────────────────────────────────────
        // Compares the 1-minute OI delta rate to the average 3-minute per-minute rate.
        // When operators are ramping up NOW (rate accelerating ≥ 1.5×), it signals
        // fresh institutional entry — a stronger early warning than a steady OI build.
        if (config.isOiVelocityEnabled() && oiAvailable && oiDir == momentumDir) {
            long[] oi1m = liveInstrumentCache.getAtmOiChange(indexType, atm, 1, 3);
            long oneMinTotal = Math.abs(oi1m[0]) + Math.abs(oi1m[1]);
            long threeMinTotal = Math.abs(ceOiChange) + Math.abs(peOiChange);
            long avgPerMin = threeMinTotal / 3;
            if (avgPerMin > 100_000 && oneMinTotal >= (long)(avgPerMin * config.getOiAccelerationMultiplier())) {
                score += 10;
                sig.append(String.format(" OI_ACCEL(+10,1m=%d,avg=%d)", oneMinTotal, avgPerMin));
            }
        }

        // ── [+10] Max pain proximity — operators have incentive to push toward max pain ──
        // Max pain = strike where total option-writer losses are minimised. Since operators
        // are net short options, they collectively push spot toward max pain before expiry.
        // Spot below max pain → bullish operator pressure; above → bearish.
        if (config.isMaxPainEnabled()) {
            int maxPainStrike = computeMaxPain(indexType);
            if (maxPainStrike > 0) {
                double spotNow = liveInstrumentCache.getFuturesPrice(indexType);
                if (spotNow > 0) {
                    double distPct = (maxPainStrike - spotNow) / spotNow * 100;
                    if (momentumDir > 0 && distPct >= config.getMaxPainMinDistancePct()) {
                        score += 10;
                        sig.append(String.format(" MAXPAIN↑(+10,mp=%d,dist=%.2f%%)", maxPainStrike, distPct));
                    } else if (momentumDir < 0 && distPct <= -config.getMaxPainMinDistancePct()) {
                        score += 10;
                        sig.append(String.format(" MAXPAIN↓(+10,mp=%d,dist=%.2f%%)", maxPainStrike, distPct));
                    } else {
                        sig.append(String.format(" MAXPAIN=%d", maxPainStrike));
                    }
                }
            }
        }

        // ── [+15/+20] PCR Momentum Reversal — put-writer unwinding signal ──────────
        // When PCR was high (>1.5, heavy put writing = support) and starts declining,
        // it signals put-writers are unwinding → support eroding → bearish reversal.
        // Jun 11 pattern: PCR 1.98 → 1.4, Sensex dropped 74,400 → 73,800.
        // Only boosts bearish direction (PE buys). Complementary to level + slope signals.
        // Bonus scaled by historical data: 82% confidence from 4 occurrences in Mar-Jun 2026.
        if (pcrMomentumReversalStrategy != null && momentumDir < 0) {
            double pcrDrop = pcrMomentumReversalStrategy.getPcrDropFromPeak(indexType);
            if (pcrDrop >= 0.15) {
                int reversalBonus = pcrDrop >= 0.3 ? 20 : 15;
                score += reversalBonus;
                sig.append(String.format(" PCR_UNWIND(+%d,drop=%.2f)", reversalBonus, pcrDrop));
            }
        }

        // ── [+10..+30 / −10] Option-Leads-Index — option premium breakout before spot ─────
        // Options move first (positioning, hedging, operator intent). When ATM option
        // breaks its session high with OI rising and index hasn't followed yet → early entry.
        // Index confirming later → confidence boost. Index diverging → reduce.
        // Confidence scaled by historical data: 78% for option-leads, 86% for cross-index.
        if (optionLeadsIndexDetector != null) {
            var optLead = optionLeadsIndexDetector.evaluate(indexType, atm,
                    liveInstrumentCache.getFuturesPrice(indexType));
            if (optLead.hasSignal() && optLead.direction() == momentumDir) {
                int leadBonus = optLead.confidenceBoost();
                if (optLead.isImmediate()) leadBonus = Math.max(leadBonus, 20); // abnormal spike → aggressive
                if (leadBonus > 0) {
                    score += leadBonus;
                    sig.append(String.format(" OPT_LEAD(+%d,%s)", leadBonus, optLead.phase().name()));
                }
            } else if (optLead.isDiverging() && optLead.direction() != momentumDir) {
                score -= 10;
                sig.append(" OPT_DIVERGE(-10)");
            }
        }

        // ── [+10 / −15] Expiry Max Pain Bias — post-2:30 PM pin toward max pain ──────
        // After 2:30 on expiry day, operators push spot toward max pain. If momentum
        // direction aligns with max pain direction → boost. If opposing → heavy penalty.
        if (expiryTrapDetector != null && expiryTrapDetector.isMaxPainBiasActive(indexType)) {
            int mpDir = expiryTrapDetector.getMaxPainDirection(indexType);
            if (mpDir != 0) {
                if (mpDir == momentumDir) {
                    score += 10;
                    sig.append(String.format(" MAXPAIN_ALIGN(+10,mp=%d)", expiryTrapDetector.getMaxPainTarget(indexType)));
                } else {
                    score -= 15;
                    sig.append(String.format(" MAXPAIN_OPPOSE(-15,mp=%d)", expiryTrapDetector.getMaxPainTarget(indexType)));
                }
            }
            // Extra penalty if OI divergence trap is active
            var trap = expiryTrapDetector.assess(indexType);
            if (trap.oiDivergenceTrap()) {
                score -= 10;
                sig.append(" OI_TRAP(-10)");
            }
        }

        return new BiasScore(momentumDir, Math.max(0f, score), sig.toString());
    }

    /**
     * Returns [totalCeOI, totalPeOI] — the sum of current open interest across
     * ATM ± strikesEachSide strikes. Used for the OI balance ratio signal.
     * Uses the live option cache (no external call). O(n) over subscribed options.
     */
    private long[] getAtmTotalOi(IndexType indexType, int atm, int strikesEachSide) {
        long ceTotal = 0;
        long peTotal = 0;
        int interval = indexType.strikeInterval();
        int maxDiff = strikesEachSide * interval;
        for (OptionInstrument opt : liveInstrumentCache.allOptions()) {
            if (opt.getIndexType() != indexType) continue;
            if (Math.abs(opt.getStrikePrice() - atm) > maxDiff) continue;
            long oi = opt.getOpenInterest();
            if (oi <= 0) continue;
            if ("CE".equals(opt.getOptionType())) ceTotal += oi;
            else if ("PE".equals(opt.getOptionType())) peTotal += oi;
        }
        return new long[]{ceTotal, peTotal};
    }

    /**
     * Bid/(bid+ask) ratio for the ATM option in the momentum direction.
     * Returns -1 if no book data available.
     * Direction +1 = CE option, -1 = PE option.
     * Ratio > 0.60 means buyers dominate (institutional accumulation pressure).
     */
    private double getAtmDirectionalBookImbalance(IndexType indexType, int atm, int momentumDir) {
        String targetType = momentumDir > 0 ? "CE" : "PE";
        for (OptionInstrument opt : liveInstrumentCache.allOptions()) {
            if (opt.getIndexType() != indexType) continue;
            if (opt.getStrikePrice() != atm) continue;
            if (!targetType.equals(opt.getOptionType())) continue;
            long bidQty = opt.getBestBidQty();
            long askQty = opt.getBestAskQty();
            if (bidQty <= 0 && askQty <= 0) return -1;
            return (double) bidQty / Math.max(1L, bidQty + askQty);
        }
        return -1;
    }

    /**
     * (CE IV − PE IV) / avg_IV for the ATM strike.
     * Positive = CE IV premium over PE IV = unusual call demand = bullish operator footprint.
     * Negative = PE IV premium = protective put buying = bearish.
     * Returns 0 if either IV is unavailable.
     */
    private double getAtmIvSkew(IndexType indexType, int atm) {
        double ceIv = 0, peIv = 0;
        for (OptionInstrument opt : liveInstrumentCache.allOptions()) {
            if (opt.getIndexType() != indexType || opt.getStrikePrice() != atm) continue;
            if ("CE".equals(opt.getOptionType())) ceIv = opt.getImpliedVolatility();
            else if ("PE".equals(opt.getOptionType())) peIv = opt.getImpliedVolatility();
        }
        if (ceIv <= 0 || peIv <= 0) return 0;
        double avg = (ceIv + peIv) / 2.0;
        return avg > 0 ? (ceIv - peIv) / avg : 0;
    }

    /**
     * Compute max pain strike: the spot price at which total option-writer losses are minimised.
     *
     * Formula: for each candidate strike S, compute:
     *   pain(S) = Σ_K [ max(0, S−K) × CE_OI(K) + max(0, K−S) × PE_OI(K) ]
     * Max pain = argmin(pain(S)) over all strikes in the chain.
     *
     * Operators are net short options (they wrote most of the OI), so they collectively
     * benefit from spot expiring at max pain — they nudge the market toward it,
     * especially in the final 2 hours before expiry.
     *
     * Returns 0 if chain data is insufficient (< 5 active strikes).
     */
    private int computeMaxPain(IndexType indexType) {
        // Collect ATM ± 10 strike OI from cache
        java.util.Map<Integer, long[]> strikeOi = new java.util.HashMap<>();
        for (OptionInstrument opt : liveInstrumentCache.allOptions()) {
            if (opt.getIndexType() != indexType) continue;
            if (opt.getOpenInterest() <= 0) continue;
            long[] oi = strikeOi.computeIfAbsent(opt.getStrikePrice(), k -> new long[2]);
            if ("CE".equals(opt.getOptionType())) oi[0] = opt.getOpenInterest();
            else if ("PE".equals(opt.getOptionType())) oi[1] = opt.getOpenInterest();
        }
        if (strikeOi.size() < 5) return 0; // Not enough chain data

        int minPainStrike = 0;
        long minPain = Long.MAX_VALUE;
        for (int testSpot : strikeOi.keySet()) {
            long totalPain = 0;
            for (java.util.Map.Entry<Integer, long[]> e : strikeOi.entrySet()) {
                int k = e.getKey();
                long ceOi = e.getValue()[0];
                long peOi = e.getValue()[1];
                if (testSpot > k) totalPain += (long)(testSpot - k) * ceOi; // call writer pain
                if (k > testSpot) totalPain += (long)(k - testSpot) * peOi; // put writer pain
            }
            if (totalPain < minPain) {
                minPain = totalPain;
                minPainStrike = testSpot;
            }
        }
        return minPainStrike;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    private boolean isMarketHours() {
        LocalTime now = LocalTime.now(IST);
        return now.isAfter(LocalTime.of(9, 15)) && now.isBefore(LocalTime.of(20, 30));
    }

    /**
     * Entry window — new entries permitted between entryWindowStart and entryWindowEnd (IST).
     * Defaults 09:25–14:55 from config; overrides the previous hardcoded 09:30–14:30.
     * The squareoffHour/squareoffMinute cutoff in detectEntry() provides the final gate
     * (no entries within 10 min of squareoff), so entryWindowEnd can safely reach 14:55.
     */
    private boolean isEntryWindow() {
        LocalTime now = LocalTime.now(IST);
        LocalTime start = (entryWindowStart != null) ? entryWindowStart
                : LocalTime.parse(config.getEntryWindowStart());
        LocalTime end = (entryWindowEnd != null) ? entryWindowEnd
                : LocalTime.parse(config.getEntryWindowEnd());
        return !now.isBefore(start) && now.isBefore(end);
    }

    private boolean isMidday(LocalTime now) {
        // Use cached parsed times (parsed once per day in tick())
        if (middayStart == null || middayEnd == null) {
            middayStart = LocalTime.parse(config.getMiddayStart());
            middayEnd = LocalTime.parse(config.getMiddayEnd());
        }
        return now.isAfter(middayStart) && now.isBefore(middayEnd);
    }

    // ── Status API ──

    public java.util.Map<String, Object> getStatus() {
        var result = new java.util.LinkedHashMap<String, Object>();
        result.put("enabled", config.isEnabled());
        result.put("paperTrading", config.isPaperTrading());
        result.put("maxTradesPerDay", config.getMaxTradesPerDay());
        result.put("enabledIndices", getEnabledIndices().stream().map(Enum::name).toList());

        for (IndexType idx : getEnabledIndices()) {
            IndexState s = indexStates.get(idx);
            if (s == null) continue;
            String prefix = idx.name().toLowerCase();
            result.put(prefix + "_tradesToday", s.tradesToday.get());
            result.put(prefix + "_reversalsToday", s.reversalsToday.get());
            result.put(prefix + "_consecutiveLosses", s.consecutiveLosses.get());
            result.put(prefix + "_activeTradeId", s.activeTradeId != null ? s.activeTradeId : "");
            result.put(prefix + "_activeDirection", s.activeDirection == 1 ? "BULLISH" : s.activeDirection == -1 ? "BEARISH" : "FLAT");
            result.put(prefix + "_dailyPnl", String.format("%.0f", s.dailyPnl));
            result.put(prefix + "_lastRejectReason", s.lastRejectReason != null ? s.lastRejectReason : "");
            result.put(prefix + "_lastRangePct30m", String.format("%.3f", s.lastRangePct30m));
            result.put(prefix + "_oiAdvanced", s.oiAdvanced);
            // Halt visibility (review: UI control)
            result.put(prefix + "_haltedForDay", s.haltedForDay);
            result.put(prefix + "_inSlCooldown", s.lastSlTime != null
                    && Duration.between(s.lastSlTime, Instant.now()).getSeconds()
                       < config.getCooldownAfterSlSeconds());
            result.put(prefix + "_inLossPause", s.consecutiveLosses.get()
                    >= config.getConsecutiveLossPause());
            result.put(prefix + "_atTradeCap", s.tradesToday.get() >= config.getMaxTradesPerDay());
        }
        return result;
    }

    // ── Halt control API ─────────────────────────────────────────────────

    /**
     * Snapshot of all halt states across enabled indices. Used by the UI to render
     * per-index halt badges and decide which Resume/Extend buttons to enable.
     */
    public java.util.Map<String, Object> getHaltStatus() {
        var out = new java.util.LinkedHashMap<String, Object>();
        out.put("strategyEnabled", config.isEnabled());
        var perIndex = new java.util.LinkedHashMap<String, Object>();
        for (IndexType idx : getEnabledIndices()) {
            IndexState s = indexStates.get(idx);
            if (s == null) continue;
            var detail = new java.util.LinkedHashMap<String, Object>();
            detail.put("haltedForDay", s.haltedForDay);
            detail.put("consecutiveLosses", s.consecutiveLosses.get());
            detail.put("consecutiveLossPauseThreshold", config.getConsecutiveLossPause());
            detail.put("consecutiveLossHaltThreshold", config.getConsecutiveLossHaltCount());
            detail.put("inLossPause", s.consecutiveLosses.get() >= config.getConsecutiveLossPause());
            long slCooldownRemaining = s.lastSlTime == null ? 0
                    : Math.max(0, config.getCooldownAfterSlSeconds()
                          - Duration.between(s.lastSlTime, Instant.now()).getSeconds());
            detail.put("slCooldownRemainingSeconds", slCooldownRemaining);
            detail.put("cooldownAfterSlSeconds", config.getCooldownAfterSlSeconds());
            detail.put("tradesToday", s.tradesToday.get());
            detail.put("maxTradesPerDay", config.getMaxTradesPerDay());
            detail.put("atTradeCap", s.tradesToday.get() >= config.getMaxTradesPerDay());
            detail.put("dailyPnl", s.dailyPnl);
            detail.put("totalLossesToday", s.totalLossesCount.get());
            detail.put("totalLossesPnl", s.totalLossesPnl);
            // Composite "is currently blocked from new entries?" flag — convenient for UI
            boolean blocked = s.haltedForDay
                    || s.consecutiveLosses.get() >= config.getConsecutiveLossPause()
                    || s.tradesToday.get() >= config.getMaxTradesPerDay()
                    || slCooldownRemaining > 0;
            detail.put("blocked", blocked);
            perIndex.put(idx.name(), detail);
        }
        out.put("indices", perIndex);
        return out;
    }

    /**
     * Resume trading for the named indices (empty/null = all) by clearing the halt
     * fields the operator asks about. Audited via WARN + Telegram.
     *
     * @param indices       indices to clear; null/empty resumes ALL enabled
     * @param clearHaltedForDay clear the haltedForDay flag
     * @param clearConsecutiveLosses reset consecutiveLosses counter to 0
     * @param clearSlCooldown skip the SL cooldown by clearing lastSlTime
     * @param resetTradesToday zero tradesToday counter (trade-cap recovery)
     * @param operatorEmail email from OAuth principal — recorded for audit
     * @param reason   min 5 chars
     * @return per-index summary of what was cleared
     */
    public synchronized java.util.Map<String, Object> resumeHalts(
            java.util.Set<IndexType> indices,
            boolean clearHaltedForDay, boolean clearConsecutiveLosses, boolean clearSlCooldown,
            boolean resetTradesToday,
            String operatorEmail, String reason) {
        if (reason == null || reason.trim().length() < 5) {
            throw new IllegalArgumentException("reason must be at least 5 characters");
        }
        var target = (indices == null || indices.isEmpty()) ? getEnabledIndices()
                : indices;
        var report = new java.util.LinkedHashMap<String, Object>();
        for (IndexType idx : target) {
            IndexState s = indexStates.get(idx);
            if (s == null) continue;
            var detail = new java.util.LinkedHashMap<String, Object>();
            if (clearHaltedForDay) {
                detail.put("haltedForDay.was", s.haltedForDay);
                s.haltedForDay = false;
                detail.put("haltedForDay.now", false);
            }
            if (clearConsecutiveLosses) {
                int prev = s.consecutiveLosses.getAndSet(0);
                detail.put("consecutiveLosses.was", prev);
                detail.put("consecutiveLosses.now", 0);
            }
            if (clearSlCooldown) {
                detail.put("lastSlTime.was", s.lastSlTime != null ? s.lastSlTime.toString() : "null");
                s.lastSlTime = null;
                detail.put("lastSlTime.now", "null");
            }
            if (resetTradesToday) {
                int prev = s.tradesToday.getAndSet(0);
                detail.put("tradesToday.was", prev);
                detail.put("tradesToday.now", 0);
            }
            report.put(idx.name(), detail);
        }
        log.warn("[OIMomentum] HALT RESUME by={} reason='{}' indices={} clearHaltedForDay={} "
                + "clearConsecutiveLosses={} clearSlCooldown={} resetTradesToday={}",
                operatorEmail, reason, target, clearHaltedForDay,
                clearConsecutiveLosses, clearSlCooldown, resetTradesToday);
        if (telegramAlertService != null) {
            try {
                telegramAlertService.systemAlert(String.format(
                        "▶️ OI Momentum HALT RESUMED by %s%n  Indices: %s%n  Cleared: %s%s%s%n  Reason: %s",
                        operatorEmail, target,
                        clearHaltedForDay ? "haltedForDay " : "",
                        clearConsecutiveLosses ? "consecutiveLosses " : "",
                        clearSlCooldown ? "slCooldown " : "",
                        resetTradesToday ? "tradesToday " : "",
                        reason));
            } catch (Exception ex) {
                log.debug("[OIMomentum] resume telegram failed: {}", ex.getMessage());
            }
        }
        return report;
    }

    /**
     * Extend the halt — proactively flip {@code haltedForDay} to true for the named
     * indices (empty = all). Use when the operator wants to stop trading on an index
     * for the rest of the session without disabling the whole strategy.
     */
    public synchronized java.util.Map<String, Object> extendHalts(
            java.util.Set<IndexType> indices, String operatorEmail, String reason) {
        if (reason == null || reason.trim().length() < 5) {
            throw new IllegalArgumentException("reason must be at least 5 characters");
        }
        var target = (indices == null || indices.isEmpty()) ? getEnabledIndices()
                : indices;
        var report = new java.util.LinkedHashMap<String, Object>();
        for (IndexType idx : target) {
            IndexState s = indexStates.get(idx);
            if (s == null) continue;
            boolean wasHalted = s.haltedForDay;
            s.haltedForDay = true;
            report.put(idx.name(), java.util.Map.of("wasHalted", wasHalted, "nowHalted", true));
        }
        log.warn("[OIMomentum] HALT EXTENDED by={} reason='{}' indices={}",
                operatorEmail, reason, target);
        if (telegramAlertService != null) {
            try {
                telegramAlertService.systemAlert(String.format(
                        "⛔ OI Momentum HALT EXTENDED by %s%n  Indices: %s halted for rest of day%n  Reason: %s",
                        operatorEmail, target, reason));
            } catch (Exception ex) {
                log.debug("[OIMomentum] extend-halt telegram failed: {}", ex.getMessage());
            }
        }
        return report;
    }
    // (D2 trend-capture precedence + operatorScore capture fix added 14 Jun 2026)
}
