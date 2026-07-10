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
        /** Orphan-adoption sweep throttle: last time this index checked the DB for an unmanaged open trade. */
        volatile Instant lastOrphanSweepTime = Instant.EPOCH;
        volatile Instant lastSlTime = null;
        volatile Instant lastReversalTime = null;
        /** True when this entry pass bypassed the SL cooldown via a reversal signal — tagged into the
         *  entry reason so the report can measure whether SL-override re-entries are net-positive. */
        volatile boolean lastEntrySlOverride = false;
        volatile double peakPrice = 0;
        /** A2 (2026-06-02): consecutive ticks at a new peak. BE-stop arms only at >=2. */
        volatile int peakConfirmationTicks = 0;
        /** Fast-gap reaction: short rolling buffer of recent premium [epochMs, price] for this trade. */
        final java.util.Deque<double[]> gapPremHist = new java.util.ArrayDeque<>();
        volatile String gapPremTradeId = null;
        /** Last time gap-reaction fired an exit for the active trade — throttles re-fire while a close is blocked
         *  (e.g. pending manual sell), so it does NOT re-log "exiting" or re-increment consecutiveLosses per tick. */
        volatile long gapReactionLastFireMs = 0;
        volatile long lastOiCeChange = Long.MIN_VALUE;
        volatile long lastOiPeChange = Long.MIN_VALUE;
        /** Cached last non-zero OI direction — used as fallback when live OI deltas are temporarily zero. */
        volatile int lastKnownOiDir = 0;
        /** Hysteresis flag for OPERATOR_OI_LED — fires at 75, resets below 65. Prevents whipsaw. */
        volatile boolean operatorLedActive = false;
        volatile boolean oiAdvanced = false;
        volatile String pendingEntryInstrumentKey = null;
        /** Spike dedupe: time of last spike entry — blocks re-entry for 10 min (one entry per spike episode). */
        volatile Instant lastSpikeEntryTime = null;
        /** DATA-6: episode dedupe for tuning SIGNAL emission — last recorded decision key + time, so a
         *  SUSTAINED_DRIFT burst (enter() every tick) writes ONE signal per episode, not one per tick. */
        volatile String lastSignalEpisodeKey = null;
        volatile Instant lastSignalEpisodeTime = null;
        /** Range-edge fade dedupe: time of last fade entry — blocks re-entry for 10 min. */
        volatile Instant lastFadeEntryTime = null;
        /** Chop fade-mode day counter — capped by oi-momentum.fade-mode.max-fades-per-day; reset daily. */
        final AtomicInteger fadesToday = new AtomicInteger(0);
        /** V5 MEMORY_AVALANCHE branch: last entry time (cooldown) + day counter; reset daily. */
        volatile Instant lastAvalancheEntryTime = null;
        final AtomicInteger avalanchesToday = new AtomicInteger(0);
        /** Validated budget scope (§3.1 rule E / V5Memory replay): ≤N non-deep entries PER INSTRUMENT
         *  per day — one hyperactive strike must not exhaust the whole index's budget. Key "strike|CE". */
        final ConcurrentHashMap<String, AtomicInteger> avalanchesTodayByInstrument = new ConcurrentHashMap<>();
        /** Review-3 issue 1 (2026-07-09): stacked avalanche positions on OTHER strikes while the primary
         *  slot is occupied. Keyed "strike|CE"; managed by the adaptive ladder, NOT by activeTradeId. */
        final ConcurrentHashMap<String, AvalancheStack> avalancheStacks = new ConcurrentHashMap<>();
        /** Replay-faithful per-INSTRUMENT re-entry cooldown, armed at EXIT (epoch ms), key "strike|CE". */
        final ConcurrentHashMap<String, Long> avalancheCooldownUntilMs = new ConcurrentHashMap<>();
        /** volUnit% captured at entry for the PRIMARY avalanche position (the adaptive ladder's unit). */
        volatile double avVolUnitAtEntry = 0;
        /** TRUE while the ACTIVE primary position is a MEMORY_AVALANCHE entry. Authoritative identity —
         *  trade.entryReason is NOT reliable (watchdog-materialized trades store the ORDER text, which
         *  silently disabled the ladder/cooldown/episode hooks on every LIMIT-filled avalanche, 07-09). */
        volatile boolean avalancheEntryActive = false;
        /** Throttle for the once-a-minute expiry-unwind AVALANCHE_SKIP audit event. */
        volatile long lastExpirySkipEventMs = 0;
        /** V5 strike fidelity: when set, enterWithGates trades EXACTLY this strike (bypasses the V3
         *  picker and the ITM/ATM clamp) — the avalanche signal is strike-specific. Read-and-cleared
         *  at the top of enterWithGates; only the avalanche block sets it. */
        volatile Integer forcedEntryStrike = null;
        /** Throttle for the exit-grace deferral INFO log (at most every 30s per index) to avoid per-tick spam. */
        volatile long lastExitGraceLogMs = 0;
        /** Throttle for the ride-winners deferral INFO log (at most every 30s per index) to avoid per-tick spam. */
        volatile long lastRideWinnerLogMs = 0;
        /** Drift entry throttle: time of last SUSTAINED_DRIFT entry attempt — blocks re-fire for 60s. */
        volatile Instant lastDriftAttemptTime = null;
        /** Price-rule rejection throttle (2026-07-03): when the execution engine rejects an entry for
         *  "Re-entry above last buy/sell price", record the FINAL entry strike → time so the strategy stops
         *  hammering it every tick. PER-STRIKE map (review fix): the old single-slot version was defeated
         *  by ATM oscillation at a strike boundary (each flip cleared the other strike's suppression) and
         *  by V3 overriding the strike between the check and the record. Entries expire after 120s. */
        final ConcurrentHashMap<Integer, Instant> priceRuleRejectByStrike = new ConcurrentHashMap<>();
        volatile String lastEntryDecisionKey = null;
        volatile OiMomentumEntryDiagnostics lastEntryDiagnostics = null;
        volatile Instant lastRejectSampleTime = null;
        volatile String lastRejectReason = "";
        // P0.2 (2026-06-26): throttle for the post-entry-window capture heartbeat, so the last ~35 min
        // of the session (14:55→15:30, previously blind because detectEntry isn't called past the entry
        // window) is still observed. Capture-only; never gates or triggers a trade.
        volatile Instant lastCutoffHeartbeatAt = null;
        volatile double lastRangePct30m = 0;
        // ── Decision Aggregator cached features (updated each tick) ──────────
        volatile double lastOiDeltaPct = 0;
        volatile double lastSpreadChange = 0;
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
        /** Trade IDs already folded into dailyPnl today — guarantees the self-close and external-close
         *  auto-heal paths can never double-count the same trade into the daily-loss circuit breaker. */
        final java.util.Set<String> dailyPnlCountedTrades = java.util.concurrent.ConcurrentHashMap.newKeySet();
        /** Trip-once flag — once true, no further entries until haltExpiresAt passes or day resets. */
        volatile boolean haltedForDay = false;
        /** When the halt expires (auto-resume after 30 min). Null = permanent halt until day reset. */
        volatile Instant haltExpiresAt = null;
        /** Count of 30-min consecutive-loss pause cycles triggered today. At MAX_CONSEC_LOSS_PAUSES_PER_DAY the
         *  halt is made permanent for the day (haltExpiresAt=null) so the bot stops re-entering a losing regime. */
        final AtomicInteger consecLossPausesToday = new AtomicInteger(0);
        /** Per-strike last-loss timestamp for anti-pyramid check. */
        final ConcurrentHashMap<Integer, Instant> lastLossExitByStrike =
                new ConcurrentHashMap<>();
    }

    /** P1 #8: Cached config per underlying to avoid DB hit every tick. */
    private final ConcurrentHashMap<IndexType, com.algo.trade.strategy.StrategyConfig> cachedConfigs = new ConcurrentHashMap<>();
    private volatile Instant cachedConfigTime = null;
    private static final Duration CONFIG_CACHE_TTL = Duration.ofSeconds(30);

    /** P1 #10: Consecutive error tracking with exponential backoff. */
    private final AtomicInteger consecutiveErrors = new AtomicInteger(0);
    private static final int MAX_CONSECUTIVE_ERRORS = 10;
    /** After this many 30-min consecutive-loss pause cycles in one day, the halt becomes PERMANENT for the day
     *  (no auto-resume) — stops the pause→resume→lose-5→pause bleed into a no-edge regime (06-29 ran ~6 cycles
     *  all afternoon, losing each time). A win resets consecutiveLosses, so reaching N cycles means N separate
     *  5-loss streaks — strong evidence the day is hostile. */
    private static final int MAX_CONSEC_LOSS_PAUSES_PER_DAY = 3;
    /** Minimum SustainedDrift operator-score to RIDE a position as a trend (instead of scalping it). The drift
     *  detector ENTERS at opScore≥40; riding is a stronger commitment, so we require high conviction (≥70).
     *  06-29 the live down-drift ran opScore 91–100 — well above this — yet the position was scalped at +3%. */
    private static final int DRIFT_RIDE_MIN_OPSCORE = 70;
    /** Min gap between gap-reaction re-fires for the SAME trade (ms) — suppresses per-tick re-log/re-increment
     *  while a close is blocked (pending manual sell). 60s: long enough to stop spam, short enough to re-attempt
     *  once the block clears. */
    private static final long GAP_REACTION_REFIRE_THROTTLE_MS = 60_000L;
    /** D (2026-07-01): min FRESH fast-OI operator score to ride a winner (vs scalp at +3%). 65 = the
     *  CASE5-override band; the fast operator refresh keeps this signal ~15s-fresh so it leads price. */
    private static final int OPERATOR_RIDE_MIN_SCORE = 65;
    /** Pause: strategy stops ticking until this time passes. Uses exponential backoff
     *  instead of a flat 5-min pause — 30s, 60s, 120s, 300s based on error frequency. */
    private volatile Instant pausedUntil = null;
    private final AtomicInteger pauseCount = new AtomicInteger(0);
    private static final Duration[] BACKOFF_DURATIONS = {
            Duration.ofSeconds(30), Duration.ofSeconds(60),
            Duration.ofSeconds(120), Duration.ofMinutes(5)
    };

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

    /**
     * P0.2 — observe the post-entry-window tail of the session (entryWindowEnd→15:30, ~14:55→close),
     * which was previously blind because {@code detectEntry()} isn't called past the entry window.
     * Capture-only: records a BLOCKED eval with blocker={@code entry_cutoff} carrying the full feature
     * snapshot, sampled every {@code captureAfterEntryWindowIntervalSec}. Never triggers a trade.
     */
    @org.springframework.beans.factory.annotation.Value("${oi-momentum.capture-after-entry-window:true}")
    private boolean captureAfterEntryWindow = true;
    @org.springframework.beans.factory.annotation.Value("${oi-momentum.capture-after-entry-window-interval-sec:30}")
    private int captureAfterEntryWindowIntervalSec = 30;

    /**
     * FAST-OI (2026-07-01): use a fresher, TRUE trailing OI window instead of the legacy call that
     * effectively reached back ~5 minutes (getOiChangeSince picks the oldest ring-buffer sample, so
     * the "3-minute" argument was inert). Empirical microstructure analysis of 355MB of live ticks
     * (data/tuning/atm-microstructure-2026-06-24.csv) shows the coverage knee is at 60s: a trailing
     * 60s OI delta is non-zero ~71% of the session, matching the exchange's ~57s OI refresh heartbeat,
     * while a 10s window is blind ~84% of the time and windows beyond 60s add pure lag (60s→180s buys
     * ~1% coverage for 3× the staleness). Three effects when enabled (default LIVE):
     *   1. every band OI-change read routes through {@link #oiChangeWindowed} → a genuine 60s window
     *      ({@code getAtmOiChangeSeconds}) instead of the ~5-min oldest-sample reach-back;
     *   2. the operator framework is refreshed from live-cache OI on the 1-sec loop (see
     *      OperatorFrameworkService.refreshFromLiveCache), not just off the 5-min ChainSnapshot;
     *   3. the OI significance floor {@link #oiSignificanceFloor()} scales 500k→200k, because band
     *      |Δ| at 60s is ~0.4× the ~5-min magnitude the old 500k floor was tuned against — without
     *      this rescale a shorter window would silently starve every OI gate.
     * Flag-gated for instant revert: {@code oi-momentum.fast-oi.enabled=false} restores the exact
     * legacy behaviour (oldest-sample window + 500k floor). Grep "[FastOI]" on EC2 to verify.
     */
    @org.springframework.beans.factory.annotation.Value("${oi-momentum.fast-oi.enabled:true}")
    private boolean fastOiEnabled = true;
    /** True trailing OI-change window in seconds (empirical knee = 60s; ring buffer resolves ~60s). */
    @org.springframework.beans.factory.annotation.Value("${oi-momentum.fast-oi.oi-window-seconds:60}")
    private int fastOiWindowSeconds = 60;
    /** Band-aggregate OI significance floor when fast-OI on (0.4× the legacy 500k, per magnitude scaling). */
    @org.springframework.beans.factory.annotation.Value("${oi-momentum.fast-oi.oi-significance-floor:200000}")
    private long fastOiSignificanceFloor = 200_000L;
    /** ATM±N band width used to build the live operator strike map (detector scans ATM±6). */
    @org.springframework.beans.factory.annotation.Value("${oi-momentum.fast-oi.operator-band-strikes:8}")
    private int fastOiOperatorBandStrikes = 8;

    /**
     * Phase 0 regime stand-down: block new entries when the realised 30-min range is below
     * {@code min-range-pct} (dead/chop tape, where momentum entries proved to go nowhere). Default OFF so
     * deploy doesn't change live behaviour unexpectedly — flip {@code enabled=true} as the deliberate Phase-0
     * step. 0.15% matches the system's own trendScore "no-trend" boundary and the captured hit-rate cliff.
     */
    @org.springframework.beans.factory.annotation.Value("${oi-momentum.regime-standdown.enabled:false}")
    private boolean regimeStandDownEnabled = false;
    @org.springframework.beans.factory.annotation.Value("${oi-momentum.regime-standdown.min-range-pct:0.15}")
    private double regimeStandDownMinRangePct = 0.15;

    // ── Chop-regime conditional OI-momentum (2026-07-06) — see docs/CHOP-REGIME-FADE-FIX.md ─────
    // A LEADING regime classifier that flips OI-momentum from "chase" to "fade" on quiet, low-VIX,
    // tight-range tape (where the chase entries bleed). chopRegime = (0 < VIX < vix-max) AND
    // (0 < range30m < range30m-max). Every downstream piece (chase-off, exit-grace, fade-mode) is
    // INDEPENDENTLY flag-gated AND gated ON chopRegime, so when VIX >= vix-max OR range >= range30m-max
    // (trend/expansion tape) chopRegime=false and NONE of these paths alter behaviour — trend-day
    // behaviour is byte-identical. VIX is the live India VIX (marketGuard); a missing/zero VIX or a
    // zero 30-min range forces chopRegime=false (fails toward TREND behaviour), so a stale input can
    // never spuriously activate the chop paths.
    @org.springframework.beans.factory.annotation.Value("${oi-momentum.chop-regime.enabled:true}")
    private boolean chopRegimeEnabled = true;
    @org.springframework.beans.factory.annotation.Value("${oi-momentum.chop-regime.vix-max:13.0}")
    private double chopVixMax = 13.0;
    @org.springframework.beans.factory.annotation.Value("${oi-momentum.chop-regime.range30m-max:0.40}")
    private double chopRange30mMax = 0.40;
    /** Chase-OFF in chop: suppress the EARLY_OI_VELOCITY + SUSTAINED_DRIFT entry triggers when chopRegime.
     *  Other triggers (OPERATOR_OI_LED, breakout/DIRECTIONAL_BUY, RANGE_EDGE_FADE) are unaffected. */
    @org.springframework.beans.factory.annotation.Value("${oi-momentum.chop-regime.suppress-chase:true}")
    private boolean chopSuppressChaseEnabled = true;
    /** Chase-off conviction BYPASS (2026-07-07): when a strong operator move is detected in chop, do NOT
     *  suppress the chase entries — a genuine operator move is exactly what we want to ride. Signal-driven
     *  (op-score + oiVel), NO clock. Off / not-met → chopSuppressChase unchanged (byte-identical). */
    @org.springframework.beans.factory.annotation.Value("${oi-momentum.chop-regime.chase-bypass-on-conviction:true}")
    private boolean chaseBypassOnConviction = true;
    @org.springframework.beans.factory.annotation.Value("${oi-momentum.chop-regime.chase-bypass-oivel-min:3.0}")
    private double chaseBypassOiVelMin = 3.0;
    @org.springframework.beans.factory.annotation.Value("${oi-momentum.chop-regime.chase-bypass-op-score-min:65}")
    private int chaseBypassOpScoreMin = 65;

    // ── OPERATOR-MOVE MODE (2026-07-07) — signal-driven, NO time window ──────────────────────────
    // Master switch for the "operator move" relaxations. isOperatorMoveActive(index) is true WHENEVER the
    // background data shows a strong operator move — high conviction (operator score + oiVel) OR a
    // capitulation-flip — regardless of the clock. When active it (2a) lets near-spot/ITM expiry entries
    // past the OTM cutoff, (2b) bypasses chop chase-off, and (3) admits the ITM strike in V3. When
    // inactive, every one of those paths is inert/identical to before. There is deliberately NO 12:15 /
    // 15:10 / hourly time gate — those clock windows are only where operator moves TEND to appear.
    @org.springframework.beans.factory.annotation.Value("${oi-momentum.operator-move-mode.enabled:true}")
    private boolean operatorMoveModeEnabled = true;
    /** ITM-on-conviction: admit an ITM strike into the V3 picker on high conviction (G4 still filters). */
    @org.springframework.beans.factory.annotation.Value("${oi-momentum.v3.strike.itm-on-conviction-enabled:true}")
    private boolean itmOnConvictionEnabled = true;
    @org.springframework.beans.factory.annotation.Value("${oi-momentum.v3.strike.itm-depth:1}")
    private int itmDepth = 1;
    @org.springframework.beans.factory.annotation.Value("${oi-momentum.v3.strike.itm-op-score-min:70}")
    private int itmOpScoreMin = 70;
    @org.springframework.beans.factory.annotation.Value("${oi-momentum.v3.strike.itm-oivel-min:3.0}")
    private double itmOiVelMin = 3.0;

    // ── Early-dip exit grace on OI_WRITER_STOP (chop only) ────────────────────────────────────
    // Within the first window-sec after entry, DEFER an OI_WRITER_STOP that has NOT breached the hard
    // floor, so a fresh chop-fade isn't knocked out by first-minute writer noise. Gated to chopRegime →
    // trend days unaffected. This NEVER weakens the real stop: the hard SL (−slPercent), flash-crash,
    // daily-loss, consecutive-loss and kill-switch all still fire, and once the loss reaches
    // hard-floor-pct the writer-stop is NOT deferred. Only the *additional* OI_WRITER_STOP protective
    // stop is deferred, and only while the loss is smaller than the hard floor.
    @org.springframework.beans.factory.annotation.Value("${oi-momentum.exit-grace.enabled:true}")
    private boolean exitGraceEnabled = true;
    @org.springframework.beans.factory.annotation.Value("${oi-momentum.exit-grace.window-sec:240}")
    private long exitGraceWindowSec = 240;
    @org.springframework.beans.factory.annotation.Value("${oi-momentum.exit-grace.hard-floor-pct:8.0}")
    private double exitGraceHardFloorPct = 8.0;

    // ── Ride confirmed winners (2026-07-07) — let a CONFIRMED WINNER run, never a loser ──────────
    // Defers the two "cut the winner early" fast exits (OI_WRITER_STOP + OI_FLIP_REVERSE) ONLY while the
    // position is comfortably IN PROFIT (>= min-profit-pct) AND the operator direction STILL confirms this
    // side. In that state the trade rides on the trailing-stop + hard-SL instead of being knocked out on a
    // micro-pullback OI dip. NOT regime/clock-gated (works on trend/grind days too — unlike exit-grace).
    // CRITICAL: this NEVER touches the loss side. It cannot fire at/near a loss (profit < min-profit-pct) or
    // when direction has flipped against the position (operator no longer aligns) — in those states the fast
    // exits fire exactly as today. The hard SL (−slPercent), flash-crash, COE microstructure reversal exit
    // (fires ABOVE, undeferred), trailing-stop, daily-loss/consecutive-loss caps, kill-switch and the 15:20
    // force square-off all remain fully active and unchanged. Flag off => today's exact behaviour.
    @org.springframework.beans.factory.annotation.Value("${oi-momentum.ride-winners.enabled:true}")
    private boolean rideWinnersEnabled = true;
    /** Min unrealized profit% before a winner is allowed to ride (defer the fast cuts). Must be > 0 so this
     *  can never engage on a flat/losing trade. */
    @org.springframework.beans.factory.annotation.Value("${oi-momentum.ride-winners.min-profit-pct:3.0}")
    private double rideWinnersMinProfitPct = 3.0;
    /** Require the operator/momentum direction to STILL confirm the held side before riding. Default ON. When
     *  false, profit alone qualifies (opt-in, looser) — but still profit-only, never a loss. */
    @org.springframework.beans.factory.annotation.Value("${oi-momentum.ride-winners.require-direction-confirm:true}")
    private boolean rideWinnersRequireDirectionConfirm = true;
    /** Operator conviction bar for the direction-confirm check (fresh + aligned + score >= this). */
    @org.springframework.beans.factory.annotation.Value("${oi-momentum.ride-winners.op-score-min:65}")
    private int rideWinnersOpScoreMin = 65;

    // ── Fade mode LIVE (chop only, INDEPENDENTLY killable) ────────────────────────────────────
    // In chopRegime, let RANGE_EDGE_FADE place LIVE orders beyond the legacy 10-min dedupe — drift-aligned
    // side only, a tight target/stop bracket, COE-managed, capped per day. Independently toggleable via
    // fade-mode.enabled (kill this ALONE without touching chase-off/exit-grace). Trend-regime fade
    // behaviour is byte-identical: this relaxed path is chop-gated, and the tight bracket keys on a
    // CHOP_FADE tag that only chop fades carry. All risk gates (max-open, daily-loss, notional ceiling,
    // lot sizing) remain fully intact — fade operates WITHIN them.
    @org.springframework.beans.factory.annotation.Value("${oi-momentum.fade-mode.enabled:true}")
    private boolean fadeModeEnabled = true;
    @org.springframework.beans.factory.annotation.Value("${oi-momentum.fade-mode.target-pct:8.0}")
    private double fadeTargetPct = 8.0;
    @org.springframework.beans.factory.annotation.Value("${oi-momentum.fade-mode.stop-pct:5.0}")
    private double fadeStopPct = 5.0;
    @org.springframework.beans.factory.annotation.Value("${oi-momentum.fade-mode.max-fades-per-day:6}")
    private int fadeMaxPerDay = 6;
    /** Min seconds between chop fades — replaces the 10-min dedupe in chop (prevents per-tick re-fire spam). */
    @org.springframework.beans.factory.annotation.Value("${oi-momentum.fade-mode.min-gap-sec:120}")
    private long fadeMinGapSec = 120;
    /** Drift deadband (%): |signed 60m drift| below this = neutral chop → fade either edge; at/above this,
     *  a fade is allowed ONLY on the side that agrees with the drift (never fade against a clear drift). */
    @org.springframework.beans.factory.annotation.Value("${oi-momentum.fade-mode.drift-deadband-pct:0.12}")
    private double fadeDriftDeadbandPct = 0.12;
    /** Bypass the V3 directional-conviction pipeline for chop fades (default ON). A fade is a mean-reversion
     *  trade, not a directional-momentum trade — the V3 operator gates require a momentum candidate and so
     *  always veto a fade (v3_skip), which kept fade-mode from ever placing a live order. When true, a
     *  RANGE_EDGE_FADE entry skips ONLY the V3 directional pipeline and falls through to the legacy ATM-strike
     *  + legacy 1-lot sizing and its CHOP_FADE bracket; every genuine downstream risk gate (charges, spread,
     *  capital, hard lot-cap, kill-switch, entry-window) still applies. Killable/tunable: set false to revert
     *  to today's behaviour (fade routed through V3 → vetoed). Mirrors the SPIKE MarketGuard exemption idiom. */
    @org.springframework.beans.factory.annotation.Value("${oi-momentum.fade-mode.bypass-v3:true}")
    private boolean fadeBypassV3 = true;

    private volatile LocalDate currentDay = null;

    // ── Executor ──
    private ScheduledExecutorService executor;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.monitoring.SchedulerRegistry schedulerRegistry;

    /** §3.7: rolling realized-quality per source, for MFE/PnL-aware sizing. Optional; no-op unless enabled. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.risk.SourcePerformanceTracker sourcePerformanceTracker;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.notification.TelegramAlertService telegramAlertService;

    /**
     * Operator Framework — detects institutional OI accumulation from chain snapshots
     * before price breakouts. Optional: falls back to existing behaviour if not wired.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private OperatorFrameworkService operatorFrameworkService;

    /** MULTI-TIMEFRAME CONTEXT (2026-07-01): day/week/month trend + regime + levels. LIVE, config-gated.
     *  Used to (a) bias the entry score toward the higher-timeframe lean / penalise counter-trend, and
     *  (b) ride winners that align with the HTF trend. Captured into tuning for a-week validation. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.marketdata.MultiTimeframeContextService mtfContextService;
    @org.springframework.beans.factory.annotation.Value("${mtf.oi-momentum.enabled:true}")
    private boolean mtfLiveEnabled = true;
    /** Bias-score bonus added when the entry direction aligns with the day+week lean (penalty when counter). */
    @org.springframework.beans.factory.annotation.Value("${mtf.oi-momentum.align-bonus:6}")
    private int mtfAlignBonus = 6;
    @org.springframework.beans.factory.annotation.Value("${mtf.oi-momentum.counter-trend-penalty:8}")
    private int mtfCounterPenalty = 8;
    /** When true, an MTF-aligned entry in a TRENDING regime is allowed to ride (like a drift-ride) not scalp. */

    /** Neutral-MTF floor lift: when MTF doesn't confirm direction, raise entry floor by this amount. */
    @org.springframework.beans.factory.annotation.Value("${mtf.oi-momentum.neutral-floor-lift-enabled:true}")
    private boolean mtfNeutralFloorLiftEnabled = true;
    @org.springframework.beans.factory.annotation.Value("${mtf.oi-momentum.neutral-floor-lift:10}")
    private int mtfNeutralFloorLift = 10;
    @org.springframework.beans.factory.annotation.Value("${mtf.oi-momentum.ride-when-aligned:true}")
    private boolean mtfRideWhenAligned = true;

    /** OI Surge Detector — detects early operator footprints via OI velocity spikes. Optional. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private OiSurgeDetector oiSurgeDetector;

    /** Module performance tracker — pauses noisy signal sources intraday. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ModulePerformanceTracker modulePerformanceTracker;

    /** Pre-market bias scanner — provides Gift Nifty gap + US futures + crude bias at open. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private PreMarketBiasScanner preMarketBiasScanner;

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
     * SYNTHETIC OI VELOCITY (20 Jun 2026) — tick-resolution build/unwind proxy that fills the
     * gap between NSE's ~3-minute OI prints, derived from cumulative volume + premium direction
     * (not OI, which is structurally low-cadence). Optional; adds a small flag-gated bias bonus
     * when its flow agrees with momentum. Default OFF until validated on captured snapshots.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SyntheticOiVelocityDetector syntheticOiVelocityDetector;

    /**
     * PCR Momentum Reversal — detects put-writer unwinding from high PCR peaks.
     * Provides bias bonus for bearish entries when PCR is declining.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.strategy.PcrMomentumReversalStrategy pcrMomentumReversalStrategy;

    /**
     * DYNAMIC OI-shift baseline (2026-07-08) — the adaptive replacement for the fixed absolute OI floors.
     * Optional collaborator: null (unit tests) or {@code enabled=false} → every dynamic gate below falls
     * back to its exact legacy fixed constant. See {@link DynamicOiFloor}. Kill switch:
     * {@code oi-momentum.dynamic-floor.enabled=false}.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private DynamicOiFloor dynamicOiFloor;

    /**
     * PART 2 (2026-07-08): PCR must NOT decide direction. When true (default, LIVE), PCR is stripped of
     * its entry-matrix veto/gate power — direction is driven by OI-shift/operator, PCR is at most a weak
     * secondary conviction contributor. Kill switch: {@code oi-momentum.pcr-nondecisive.enabled=false}
     * restores the legacy PCR-in-the-matrix behaviour.
     */
    @org.springframework.beans.factory.annotation.Value("${oi-momentum.pcr-nondecisive.enabled:true}")
    private boolean pcrNonDecisive = true;

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

    /** Volatility-conditioned dynamic gates (LIVE when active). Applied to the entry bias-floor and lot
     *  sizing below. Optional + neutral-when-inactive, so absent/disabled/stale ⇒ static behaviour. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private DynamicGateEngine dynamicGateEngine;

    /** Outcome-feedback engine — guarded live recalibration of entry gates. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private OutcomeFeedbackEngine outcomeFeedbackEngine;

    /** Profile gate scaler — per-profile multipliers for dynamic gate values. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ProfileGateScaler profileGateScaler;

    /** Feature normalizer — z-score normalization per regime. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private FeatureNormalizer featureNormalizer;

    /** Decision aggregator — normalized feature combiner (direction bias + size multiplier). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private DecisionAggregator decisionAggregator;

    /** Operator reversal detector — detects ignition footprints before directional flips. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private OperatorReversalDetector operatorReversalDetector;

    /** Time bias engine — detects scheduled operator moves at clock anchor times. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private TimeBiasEngine timeBiasEngine;
    /** Bias-score bonus/penalty when momentum agrees/conflicts with the learned per-anchor historical bias. */
    @org.springframework.beans.factory.annotation.Value("${oi-momentum.time-bias.historical-bias-weight:5}")
    private int timeBiasHistoricalWeight = 5;

    /** OI Ladder detector — sequential OI builds across adjacent strikes. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private OiLadderDetector oiLadderDetector;

    /** Gamma scalp detector — alternating premium velocity bursts on expiry days. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private GammaScalpDetector gammaScalpDetector;

    /** OI velocity early detector — fires BEFORE price momentum when OI builds directionally. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private OiVelocityEarlyDetector oiVelocityEarlyDetector;

    /** OI level bounce detector — fires at support/resistance OI levels when price rejects. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private OiLevelBounceDetector oiLevelBounceDetector;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.tuning.adapter.strategies.OiMomentumCaptureAdapter oiMomentumCaptureAdapter;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.tuning.infra.MaeMfeTracker maeMfeTracker;

    // ── Exit-parity wiring (data-analysis gaps fixed 2026-06-25) ──────────────────
    // The PRIMARY OI Momentum trade is managed by this class's own 1-sec managePosition()
    // loop; the central LivePositionExitMonitor deliberately skips it (it only manages
    // copied/secondary-user OI trades). That left three data-analysis exit features —
    // tiered trailing (R6), progressive partial booking, and the Tier-1 liquidity gate —
    // applied to copied trades but NOT to the primary's. These optional beans + flags wire
    // the SAME validated components onto the primary path. All flags default OFF so deploy
    // changes nothing until each is shadow/paper-validated and explicitly enabled.
    /** Tier-1 liquidity emergency gate (stale quote / bid-ask blowout / volume collapse). Optional; null in tests. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.execution.exit.LiquidityEmergencyGate liquidityEmergencyGate;
    /** Progressive partial profit-booking ladder, shared with the central monitor. Optional; null in tests. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.strategy.DynamicExitManager dynamicExitManager;
    /** OI–price divergence monitor (OI↑ + own-premium↓ = writers). Shadow-first exit + entry capture. Optional; null in tests. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private OiDivergenceMonitor oiDivergenceMonitor;

    /** Conviction Override Engine — microstructure fast EXIT override (live, config-gated). Optional; null in tests. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.marketdata.ConvictionOverrideEngine convictionOverrideEngine;
    @org.springframework.beans.factory.annotation.Value("${conviction-override.exit.fast-reversal:true}")
    private boolean coeExitFastReversal;
    @org.springframework.beans.factory.annotation.Value("${conviction-override.exit.min-hold-sec:3}")
    private long coeExitMinHoldSec;

    // ── Market-Memory V5 (docs/MARKET-MEMORY-V5-DESIGN.md §4) ────────────────────────────────────
    /** Per-strike baseline/state memory engine. Optional; null in tests. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.marketdata.MarketMemoryEngine marketMemoryEngine;
    /** OI_WRITER_STOP as a LIVE exit — default OFF (V5: 22 exits, 0% win, price rebounded ≥2% within
     *  5 min after 11/22 of them; it sells local bottoms). Condition still evaluated + audited. */
    @org.springframework.beans.factory.annotation.Value("${oi-momentum.exit.writer-stop-live:false}")
    private boolean writerStopLive;
    /** OI_FLIP_REVERSE as a LIVE exit — default OFF (V5: 7 exits, 0% win, 5/7 rebounds). Suppressing
     *  it also suppresses the flip re-entry that rides on it (capitulation-flip is a separate path). */
    @org.springframework.beans.factory.annotation.Value("${oi-momentum.exit.flip-reverse-live:false}")
    private boolean flipReverseLive;
    /** V5 WPRESS-ACCEL: in-loss + WRITER_PRESS state (OI building vs falling price at z>=+2) → exit
     *  within seconds instead of waiting for the slower OI windows (drop continues ×1.35–1.55). */
    @org.springframework.beans.factory.annotation.Value("${oi-momentum.exit.wpress-accel-enabled:true}")
    private boolean wpressAccelEnabled;
    @org.springframework.beans.factory.annotation.Value("${oi-momentum.exit.wpress-min-held-sec:30}")
    private long wpressMinHeldSec;
    /** Don't scalp a winner while its strike is in AVALANCHE / SHORT_COVER_RUN — panic covering keeps
     *  running (rally ×1.5–1.7); the trailing-stop manages the ride. */
    @org.springframework.beans.factory.annotation.Value("${oi-momentum.exit.ride-avalanche-scalp-defer:true}")
    private boolean rideAvalancheScalpDefer;

    // ── V5 MEMORY_AVALANCHE entry branch (docs/MARKET-MEMORY-V5-DESIGN.md §3) ────────────────────
    // The one entry pattern that survived its own memory selection in the 3-day replay
    // (+₹105,238/3d; IMPULSE −₹36.9k and PRESSFLIP −₹19.3k were rejected and are NOT implemented):
    // panic writer-covering (dOI5m avalanche) with price turning up off the 10-min low. Deep tier
    // (dOI5m ≤ −8%) bypasses the suspension + day budget — rare, high-value events are not rationed
    // by rules built for routine ones (the 07-07 24500 PE lesson). Entries go through enter() so
    // EVERY risk gate (daily-loss, max-open, kill-switch, profile, charges, veto rail) applies.
    @org.springframework.beans.factory.annotation.Value("${oi-momentum.avalanche.enabled:true}")
    private boolean avalancheEnabled;
    @org.springframework.beans.factory.annotation.Value("${oi-momentum.avalanche.max-per-day:5}")
    private int avalancheMaxPerDay;   // non-deep entries PER INSTRUMENT per day (validated: 5)
    @org.springframework.beans.factory.annotation.Value("${oi-momentum.avalanche.cooldown-sec:120}")
    private long avalancheCooldownSec;
    @org.springframework.beans.factory.annotation.Value("${oi-momentum.avalanche.strike-span:10}")
    private int avalancheStrikeSpan;
    /** Review-3 issue 3: replay-faithful vol-unit exit ladder (SL/WPRESS/SCALP/TRAIL/MAXHOLD/EOD in
     *  volUnit terms) for MEMORY_AVALANCHE trades. Runs BEFORE the legacy fixed-%% cascade each tick;
     *  legacy remains as an independent backstop (safe direction). Kill: set false. */
    @org.springframework.beans.factory.annotation.Value("${oi-momentum.avalanche.adaptive-exits-enabled:true}")
    private boolean avalancheAdaptiveExits;
    /** Review-3 issue 1: simultaneous avalanches on DIFFERENT strikes stack additional 1-lot positions
     *  beside the primary (the replay's main P&L source — peak 8-14 concurrent/index). Kill: set false. */
    @org.springframework.beans.factory.annotation.Value("${oi-momentum.avalanche.stack-enabled:true}")
    private boolean avalancheStackEnabled;
    /** Hard ceiling on concurrent avalanche positions per index (primary + stacks). */
    @org.springframework.beans.factory.annotation.Value("${oi-momentum.avalanche.max-concurrent-per-index:3}")
    private int avalancheMaxConcurrent;
    /** C1 (2026-07-09, replay-validated): max dP5m already elapsed at entry — buy the TURN, not the
     *  spike. Global 75 costs only −2.3% on the 3-day replay while blocking the +170..+227% madness;
     *  the ceiling sweep REJECTED 25 globally (−34%, it cuts the crash harvest). ALL tiers incl deep. */
    @org.springframework.beans.factory.annotation.Value("${oi-momentum.avalanche.max-dp5m-pct:75}")
    private double avalancheMaxDp5mPct;
    /** C1b: tighter ceiling for the EXPIRING index on expiry day — the replay is silent there (zero
     *  expiry days) and 07-09's evidence is unanimous: every expiring-index entry above +15% lost. */
    @org.springframework.beans.factory.annotation.Value("${oi-momentum.avalanche.expiry-max-dp5m-pct:25}")
    private double avalancheExpiryMaxDp5mPct;
    /** Two-phase AV_TRAIL: widened gap (in vu) once peak >= 3vu. Replay-validated 07-09 (+26% on the
     *  3-day tape at 4; today's live population flips positive). 0 = single-phase 1.2vu (old). */
    @org.springframework.beans.factory.annotation.Value("${oi-momentum.avalanche.wide-trail-mult:4.0}")
    private double avalancheWideTrailMult;
    /** Afternoon forensic 07-09: avalanche-only pass-through of the dynamic day-cap + midday gates
     *  (bias-churn throttles, never present in the validated replay). false = old blanket behavior. */
    @org.springframework.beans.factory.annotation.Value("${oi-momentum.avalanche.exempt-day-cap:true}")
    private boolean avalancheExemptDayCap;
    /** F10-1 (2026-07-10): episode-memory dedupe. A stack that gets picked up by the primary state
     *  machine records its close via BOTH closePosition and finalizeAvalancheStack (76800PE recorded
     *  twice, 990ms apart → n=3 from 2 trades). Both call sites check-and-add here; cleared at the
     *  IST day rollover. Double-counted LOSERS would suspend a pattern after ~2 real losses. */
    private final java.util.Set<String> episodeRecordedTradeIds =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** True exactly once per tradeId — the second caller loses the race and skips recordEpisode. */
    private boolean episodeNotYetRecorded(TradeEntity trade) {
        return trade != null && trade.getTradeId() != null && episodeRecordedTradeIds.add(trade.getTradeId());
    }

    /** One stacked avalanche position (review-3 issue 1). Tracked by instrument; resolved to a tradeId
     *  on first sight of the OPEN trade (LIMIT entries materialize via the OrderFillWatchdog). */
    private static final class AvalancheStack {
        final String instrumentKey; final int strike; final boolean ce; final double vuAtEntry;
        final long placedMs = System.currentTimeMillis();
        volatile String tradeId = null; volatile double entry = 0; volatile double peak = 0;
        AvalancheStack(String instrumentKey, int strike, boolean ce, double vuAtEntry) {
            this.instrumentKey = instrumentKey; this.strike = strike; this.ce = ce; this.vuAtEntry = vuAtEntry;
        }
    }
    // Lots: NO avalanche-specific lot config. The sizer treats MEMORY_AVALANCHE as an event-driven
    // single-lot entry (computeLegacyLotCount, same as SPIKE/REVERSE) and the per-user risk-profile
    // maxLotsPerTrade remains the ceiling like every other trade. Future sizing promotion = teach the
    // sizer to conviction-scale avalanches UP TO THE PROFILE MAX — never a separate knob.

    /** Best AVALANCHE-state strike near ATM (deepest dOI5m), or null. [0]=strike [1]=dir(+1 CE/−1 PE). */
    private Object[] detectAvalanche(IndexType indexType, IndexState state, double spot) {
        // UI master switch (2026-07-10): FALSE = no NEW avalanche signals (entries, stacks, and the
        // OTM-cutoff bypass all read through here). OPEN avalanche trades keep their ladder exits —
        // avalancheEntryActive / avalancheStacks are untouched, so flipping never orphans a position.
        if (globalConfigService != null && !globalConfigService.isAvalancheTradingEnabled()) {
            return null;
        }
        if (!avalancheEnabled || marketMemoryEngine == null || !marketMemoryEngine.isEnabled() || spot <= 0) return null;
        // EXPIRY-UNWIND suppression (2026-07-09, forward-path verified): when the index-wide expiry
        // trap is latched (>30% OI unwind at key strikes), the "avalanche" signature is everyone
        // closing INTO expiry, not panic covering — the 10:43 entries bought a blow-off top that
        // collapsed 31-67% in 30 min. No new avalanche entries while the trap is on; the replay never
        // contained an expiry day, so this regime was never in the pattern's validation set.
        if (expiryTrapDetector != null && expiryCalendar.isExpiryDay(indexType)
                && expiryTrapDetector.shouldForceExit(indexType)) {
            long nowMs = System.currentTimeMillis();
            if (nowMs - state.lastExpirySkipEventMs > 60_000) {   // once/min audit, not per tick
                state.lastExpirySkipEventMs = nowMs;
                marketMemoryEngine.event("AVALANCHE_SKIP", indexType.name(), indexType.name(),
                        "expiry-unwind latched (index-wide OI collapse) — new avalanche entries suppressed");
            }
            return null;
        }
        int atm = indexType.roundToATM(spot);
        long nowMs = System.currentTimeMillis();
        boolean expiryToday = expiryCalendar.isExpiryDay(indexType);
        // C1 two-tier dP5m ceiling (replay-validated 75 global; 25 for the expiring index where the
        // replay is silent and 07-09 evidence is unanimous). Applies to ALL tiers including deep.
        double dp5Ceil = expiryToday
                ? Math.min(avalancheExpiryMaxDp5mPct, avalancheMaxDp5mPct)
                : avalancheMaxDp5mPct;
        com.algo.trade.marketdata.MarketMemoryEngine.MemorySnapshot best = null;
        int bestStrike = 0; String bestType = null;
        for (int k = -avalancheStrikeSpan; k <= avalancheStrikeSpan; k++) {
            int strike = atm + k * indexType.strikeInterval();
            for (String ty : new String[]{"CE", "PE"}) {
                String instKey = strike + "|" + ty;
                // Replay-faithful per-INSTRUMENT cooldown, armed at EXIT (was per-index from entry —
                // that serialized the simultaneous-avalanche harvest the replay's P&L came from).
                Long coolUntil = state.avalancheCooldownUntilMs.get(instKey);
                if (coolUntil != null && coolUntil > nowMs) continue;
                // Never re-signal an instrument we already hold (primary or stack).
                if (state.avalancheStacks.containsKey(instKey)) continue;
                if (state.activeStrike == strike && ((state.activeDirection > 0) == "CE".equals(ty))) continue;
                var mem = marketMemoryEngine.get(indexType, strike, ty);
                if (mem == null || mem.state() != com.algo.trade.marketdata.MarketMemoryEngine.MarketState.AVALANCHE) continue;
                if (dp5Ceil > 0 && mem.dP5mPct() > dp5Ceil) continue;      // C1: the move already happened
                // C2 (07-09): deep-tier privileges assume deep is RARE. On the expiring index's expiry
                // day, everyone unwinding makes -8%+ collapses routine — deep loses its exemptions
                // there (suspension + budget apply); it keeps them everywhere else.
                boolean deep = mem.deepAvalanche() && !expiryToday;
                if (!deep && marketMemoryEngine.patternSuspended(indexType.name(), ty, "AVALANCHE")) continue;
                // Budget is PER INSTRUMENT (validated §3.1 rule E) — deep exempt (non-expiry only).
                var instCnt = state.avalanchesTodayByInstrument.get(instKey);
                if (!deep && instCnt != null && instCnt.get() >= avalancheMaxPerDay) continue;
                if (best == null || mem.dOi5mPct() < best.dOi5mPct()) { best = mem; bestStrike = strike; bestType = ty; }
            }
        }
        if (best == null) return null;
        return new Object[]{bestStrike, "CE".equals(bestType) ? 1 : -1, best};
    }

    // ── V5 review-3 fixes (2026-07-09): replay-faithful adaptive exit ladder + multi-strike stacking ──

    private static long istSecOfDay() {
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now(IST);
        return now.getHour() * 3600L + now.getMinute() * 60L + now.getSecond();
    }

    /**
     * V5 MEMORY_AVALANCHE entry branch (design doc §3) — the validated squeeze-harvester. Scans
     * ATM±span for the AVALANCHE state, deepest dOI5m wins, trades THE signal strike. Returns true
     * iff a position/pending entry resulted (caller returns). Extracted 2026-07-09 (afternoon
     * forensic) so the day-cap/midday gates can offer an avalanche-ONLY pass-through: on 07-09 the
     * dynamic day-cap (69/54, a bias-churn throttle NEVER present in any validated replay — dayCap=0
     * throughout) silently ate the entire 14:30+ SENSEX collapse harvest while the engine held live
     * DEEP signals with dP5m<2%. The avalanche flow carries its OWN validated anti-churn stack
     * (5/instrument/day budget, 60s cooldown, episode suspension, C1 dP5m ceilings).
     */
    private boolean tryAvalancheEntry(IndexType indexType, IndexState state) {
        double avSpot = momentumDetector.getSpot(indexType);
        Object[] av = detectAvalanche(indexType, state, avSpot);
        if (av == null) return false;
        int avStrike = (Integer) av[0];
        int avDir = (Integer) av[1];
        var avMem = (com.algo.trade.marketdata.MarketMemoryEngine.MemorySnapshot) av[2];
        // C3: vu rides in the reason string so a restart can restore the ladder's exit unit from the DB.
        String avReason = String.format("MEMORY_AVALANCHE strike=%d %s dOI5m=%.1f%% zOi=%.1f dP5m=%.1f%% tier=%s vu=%.1f",
                avStrike, avDir > 0 ? "CE" : "PE", avMem.dOi5mPct(), avMem.zOi(), avMem.dP5mPct(), avMem.tier(), avMem.volUnitPct());
        log.info("[OIMomentum][{}] {} — attempting entry (avalanchesToday={}/{})",
                indexType, avReason, state.avalanchesToday.get(), avalancheMaxPerDay);
        long[] avOi = oiChangeWindowed(indexType, avStrike, 3);
        double avPcr = liveInstrumentCache.getRealtimePcr(indexType);
        TickMomentumDetector.MomentumSignal avPseudo =
                new TickMomentumDetector.MomentumSignal(avDir, "MEMORY_AVALANCHE", 0, avSpot);
        OiMomentumEntryDiagnostics avDiag = OiMomentumEntryDiagnostics.forSpike(
                indexType, avPseudo, avPcr, pcrDir(avPcr), avOi[0], avOi[1],
                avOi[0] != 0 || avOi[1] != 0, oiDir(indexType, avOi[0], avOi[1]), state.oiAdvanced,
                marketGuard.getCurrentVix(), expiryCalendar.daysToExpiry(indexType),
                expiryCalendar.isExpiryDay(indexType), paperTrading(indexType));
        boolean avHadPosition = state.activeTradeId != null || state.pendingEntryInstrumentKey != null;
        state.forcedEntryStrike = avStrike; // V5 strike fidelity: trade THE avalanche strike
        enter(indexType, state, avDir, avReason, avSpot, avDiag);
        state.forcedEntryStrike = null;     // safety: never leak into a later non-avalanche entry
        boolean avEntered = !avHadPosition
                && (state.activeTradeId != null || state.pendingEntryInstrumentKey != null);
        if (marketMemoryEngine != null) {   // mid-market observability: outcome of every attempt
            marketMemoryEngine.event(avEntered ? "AVALANCHE_ENTRY" : "AVALANCHE_NO_ENTRY",
                    indexType.name(), indexType + " " + avStrike + " " + (avDir > 0 ? "CE" : "PE"),
                    avEntered
                        ? String.format("dOI5m=%.1f%% zOi=%.1f tier=%s pain=%.1f%% (#%d today)",
                            avMem.dOi5mPct(), avMem.zOi(), avMem.tier(), avMem.painPct(),
                            state.avalanchesToday.get() + 1)
                        : "downstream gate rejected — see lastRejectReason=" + state.lastRejectReason);
        }
        if (avEntered) {
            state.lastAvalancheEntryTime = Instant.now();
            state.avalanchesToday.incrementAndGet();   // index-level total (UI/logs)
            state.avVolUnitAtEntry = avMem.volUnitPct(); // the adaptive ladder's exit unit
            state.avalancheEntryActive = true;           // authoritative avalanche identity
            // Cooldown FLOOR from ENTRY: survives ANY exit path (COE/monitor/watchdog closes
            // bypass closePosition's exit-armed cooldown — the 10:32 4-second flip-flop).
            state.avalancheCooldownUntilMs.put(avStrike + "|" + (avDir > 0 ? "CE" : "PE"),
                    System.currentTimeMillis() + avalancheCooldownSec * 1000L);
            if (avMem.dOi5mPct() > com.algo.trade.marketdata.MarketMemoryEngine.DEEP_DOI5M_PCT) {
                // replay semantics: only NON-deep entries consume the routine budget
                state.avalanchesTodayByInstrument
                        .computeIfAbsent(avStrike + "|" + (avDir > 0 ? "CE" : "PE"),
                                k -> new AtomicInteger(0)).incrementAndGet();
            }
        }
        return avEntered;
    }

    /**
     * The REPLAY's exit ladder verbatim (V5Memory: SL → WPRESS → SCALP → TRAIL → MAXHOLD → EOD), in
     * volUnit terms captured at entry. Returns an exit reason or null. For MEMORY_AVALANCHE trades it
     * runs BEFORE the legacy fixed-% cascade; legacy stays live as an independent backstop.
     */
    private String avalancheExitReason(IndexType indexType, int strike, boolean ce, double entry,
                                       double peak, double ltp, long heldSec, double vuAtEntry) {
        if (entry <= 0 || ltp <= 0) return null;
        double vu = vuAtEntry > 0 ? vuAtEntry : 3.0;                       // mid-range fallback
        double profit = (ltp - entry) / entry * 100;
        double slPct = Math.min(12, Math.max(6, 2.2 * vu));
        var mem = marketMemoryEngine != null ? marketMemoryEngine.get(indexType, strike, ce ? "CE" : "PE") : null;
        boolean stillCovering = mem != null && mem.zOi() <= -2 && mem.dP5mPct() >= 0; // ride, don't scalp
        if (profit <= -slPct) return "AV_SL";
        if (mem != null && heldSec >= 30 && profit < 0 && mem.zOi() >= 2 && mem.dP5mPct() <= -vu) return "AV_WPRESS";
        if (heldSec >= 20 && profit >= 2 * vu && !stillCovering && avalancheWeakening(indexType, strike, ce)) return "AV_SCALP";
        // Two-phase trail (2026-07-09 give-back study, replay-validated): 1.2vu gap while the trade
        // is small; once peak >= 3vu ("earned room") widen to wideTrailMult*vu so runners breathe.
        // 3-day replay: 137,146 -> 172,674 (+26%) at mult=4; 07-09 live population: -790 -> +1,333
        // while keeping the give-back rescue. Sweep plateaus ~5-6; beyond that the trail stops
        // protecting runners at all (win% decays via round-trips). 0 disables (single-phase 1.2vu).
        double peakPct = (peak - entry) / entry * 100;
        double gapVu = (avalancheWideTrailMult > 0 && peakPct >= 3 * vu) ? avalancheWideTrailMult : 1.2;
        if (peak >= entry * (1 + 1.5 * vu / 100) && ltp <= peak * (1 - gapVu * vu / 100)) return "AV_TRAIL";
        if (heldSec >= 2400) return "AV_MAXHOLD";
        if (istSecOfDay() >= 55200) return "AV_EOD";                       // 15:20 IST, matches replay
        return null;
    }

    /** Replay SCALP "weakening" proxy: heavy offer-side book (imb ≤ −0.10). The replay also used 30s
     *  signed flow ≤ 0; book imbalance is the component we have live per strike. No data → keep riding. */
    private boolean avalancheWeakening(IndexType indexType, int strike, boolean ce) {
        try {
            if (convictionOverrideEngine == null) return false;
            var os = convictionOverrideEngine.get(indexType, strike, ce ? "CE" : "PE");
            return os != null && os.bookImbalance() <= -0.10;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Review-3 issue 1: place an ADDITIONAL 1-lot avalanche position on a different strike while the
     * primary slot is occupied. Deliberately invisible to the singular activeTradeId state machine —
     * tracked in state.avalancheStacks, exit-managed by the adaptive ladder each tick, with the central
     * exit monitor (stamped SL/target/trail) as the independent backstop. EVERY ExecutionEngine risk
     * gate applies (maxOpenTrades, §LOT-CAP, premium bounds, veto rail, margin) — the per-underlying
     * concentration cap alone is widened to avalanche.max-concurrent-per-index for these entries.
     */
    private void tryPlaceAvalancheStack(IndexType indexType, IndexState state, int strike, int dir,
                                        com.algo.trade.marketdata.MarketMemoryEngine.MemorySnapshot mem,
                                        double spot) {
        try {
            if (1 + state.avalancheStacks.size() >= avalancheMaxConcurrent) return;  // primary + stacks
            OptionType optType = dir > 0 ? OptionType.CE : OptionType.PE;
            String instKey = strike + "|" + optType.name();
            if (state.avalancheStacks.containsKey(instKey)) return;
            if (state.activeStrike == strike && ((state.activeDirection > 0) == (dir > 0))) return;
            LocalDate expiry = expiryCalendar.getCurrentExpiry(indexType);
            UnderlyingSymbol underlying = UnderlyingSymbol.valueOf(indexType.name());
            Optional<Instrument> instOpt = instrumentCache.findOption(
                    underlying, expiry, BigDecimal.valueOf(strike), optType);
            if (instOpt.isEmpty()) return;
            String instrumentKey = instOpt.get().instrumentKey();
            Optional<Quote> q = marketDataService.quote(instrumentKey);
            if (q.isEmpty() || q.get().lastPrice().signum() <= 0) return;
            BigDecimal premium = q.get().lastPrice();
            int lotSize = indexType.lotSize();
            String reason = String.format("MEMORY_AVALANCHE_STACK strike=%d %s dOI5m=%.1f%% zOi=%.1f tier=%s",
                    strike, optType, mem.dOi5mPct(), mem.zOi(), mem.tier());
            StrategyDecision decision = new StrategyDecision(
                    Instant.now(), underlying,
                    dir > 0 ? SignalType.BUY_CE : SignalType.BUY_PE,
                    BigDecimal.valueOf(spot),
                    Optional.of(premium), Optional.empty(),
                    Optional.of(lotSize), Optional.of(premium.multiply(BigDecimal.valueOf(lotSize))),
                    Optional.of(instrumentKey), Optional.of(BigDecimal.valueOf(strike)),
                    Optional.of(optType), false, Optional.empty(), false,
                    BigDecimal.ZERO,
                    java.util.List.of("OI_MOMENTUM[" + indexType + "]: " + reason));
            var result = executionEngine.executeEntry(decision, premium, lotSize);
            boolean accepted = result != null && result.accepted();
            if (!accepted) {
                // Rejection backoff (2026-07-09 live finding): without this the stack retries EVERY tick
                // while capacity is full — 771 reject events + repeated fan-out spam that tripped u:8's
                // breaker. One attempt per instrument per backoff window is plenty.
                state.avalancheCooldownUntilMs.put(instKey, System.currentTimeMillis() + 60_000L);
            }
            if (accepted) {
                state.avalancheStacks.put(instKey, new AvalancheStack(instrumentKey, strike, dir > 0, mem.volUnitPct()));
                // Cooldown FLOOR from entry (same rationale as the primary — survives any exit path).
                state.avalancheCooldownUntilMs.put(instKey, System.currentTimeMillis() + avalancheCooldownSec * 1000L);
                state.avalanchesToday.incrementAndGet();
                if (mem.dOi5mPct() > com.algo.trade.marketdata.MarketMemoryEngine.DEEP_DOI5M_PCT) { // non-deep consumes budget
                    state.avalanchesTodayByInstrument.computeIfAbsent(instKey, k -> new AtomicInteger(0)).incrementAndGet();
                }
                log.info("[OIMomentum][{}] AVALANCHE STACK entered {} ({} concurrent incl. primary)",
                        indexType, instrumentKey, 1 + state.avalancheStacks.size());
            }
            if (marketMemoryEngine != null) {
                marketMemoryEngine.event(accepted ? "AVALANCHE_STACK_ENTRY" : "AVALANCHE_STACK_REJECTED",
                        indexType.name(), indexType + " " + strike + " " + optType,
                        accepted
                            ? String.format("dOI5m=%.1f%% zOi=%.1f tier=%s concurrent=%d",
                                mem.dOi5mPct(), mem.zOi(), mem.tier(), 1 + state.avalancheStacks.size())
                            : "gates rejected: " + (result != null ? String.join("; ", result.reasons()) : "null"));
            }
        } catch (Exception e) {
            log.warn("[OIMomentum][{}] avalanche stack placement failed: {}", indexType, e.getMessage());
        }
    }

    /** Per-tick exit management for stacked avalanche positions + reconciliation of externally-closed
     *  or never-filled stacks. Finalization (episode + per-instrument cooldown) runs on the tick after
     *  the trade is seen CLOSED, so watchdog/manual closes are honored identically to ladder closes. */
    private void manageAvalancheStacks(IndexType indexType, IndexState state) {
        if (state.avalancheStacks.isEmpty()) return;
        for (var entry : state.avalancheStacks.entrySet()) {
            String instKey = entry.getKey();
            AvalancheStack st = entry.getValue();
            try {
                TradeEntity trade;
                if (st.tradeId != null) {
                    trade = tradeRepository.findById(st.tradeId).orElse(null);
                } else {
                    // Match by instrument + freshness, NOT entryReason — watchdog-materialized trades
                    // carry the ORDER text as entryReason (07-09 finding), which made stacks unresolvable.
                    trade = tradeRepository.findByInstrumentKeyAndStatus(st.instrumentKey, TradeStatus.OPEN).stream()
                            .filter(t -> t.getUserId() == null
                                    || t.getUserId().equals(com.algo.trade.multiuser.UserContext.DEFAULT_USER_ID))
                            .filter(t -> t.getEntryTime() == null
                                    || t.getEntryTime().toEpochMilli() >= st.placedMs - 60_000L)
                            .findFirst().orElse(null);
                    if (trade == null) {
                        if (System.currentTimeMillis() - st.placedMs > 180_000) {
                            state.avalancheStacks.remove(instKey);   // never filled (cancelled) — forget
                        }
                        continue;                                     // LIMIT pending — watchdog will materialize
                    }
                    st.tradeId = trade.getTradeId();
                    st.entry = trade.getEntryPrice() != null ? trade.getEntryPrice().doubleValue() : 0;
                    st.peak = st.entry;
                }
                if (trade == null || trade.getStatus() != TradeStatus.OPEN) {
                    finalizeAvalancheStack(indexType, state, instKey, st, trade);
                    continue;
                }
                double ltp = marketMemoryEngine != null
                        ? marketMemoryEngine.lastPremium(indexType, st.strike, st.ce ? "CE" : "PE") : 0;
                if (ltp <= 0) continue;
                if (st.entry <= 0 && trade.getEntryPrice() != null) st.entry = trade.getEntryPrice().doubleValue();
                st.peak = Math.max(st.peak, ltp);
                long heldSec = trade.getEntryTime() != null
                        ? Duration.between(trade.getEntryTime(), Instant.now()).getSeconds() : 0;
                String exit = avalancheExitReason(indexType, st.strike, st.ce, st.entry, st.peak, ltp, heldSec, st.vuAtEntry);
                if (exit != null) {
                    var res = executionEngine.closeTrade(trade.getTradeId(), BigDecimal.valueOf(ltp), exit);
                    log.info("[OIMomentum][{}] AVALANCHE STACK exit {} {} accepted={}",
                            indexType, st.instrumentKey, exit, res != null && res.accepted());
                }
            } catch (Exception ex) {
                log.debug("[OIMomentum][{}] stack manage {} failed: {}", indexType, instKey, ex.getMessage());
            }
        }
    }

    private void finalizeAvalancheStack(IndexType indexType, IndexState state, String instKey,
                                        AvalancheStack st, TradeEntity trade) {
        state.avalancheStacks.remove(instKey);
        // Replay semantics: the re-entry cooldown arms at EXIT, per instrument.
        state.avalancheCooldownUntilMs.put(instKey, System.currentTimeMillis() + avalancheCooldownSec * 1000L);
        try {
            if (trade != null && trade.getEntryPrice() != null && trade.getExitPrice() != null
                    && marketMemoryEngine != null && episodeNotYetRecorded(trade)) {
                double netPct = (trade.getExitPrice().doubleValue() - trade.getEntryPrice().doubleValue())
                        / trade.getEntryPrice().doubleValue() * 100;
                marketMemoryEngine.recordEpisode(indexType.name(), st.ce ? "CE" : "PE", "AVALANCHE", st.strike, netPct);
                marketMemoryEngine.event("AVALANCHE_STACK_CLOSED", indexType.name(),
                        indexType + " " + st.strike + " " + (st.ce ? "CE" : "PE"),
                        String.format("netPct=%.1f exit=%s", netPct, trade.getExitReason()));
            }
        } catch (Exception ignore) { /* observability only */ }
    }

    /** Latest Market-Memory snapshot for the HELD strike, or null (engine off / no position / hiccup). */
    private com.algo.trade.marketdata.MarketMemoryEngine.MemorySnapshot heldMemory(IndexType indexType, IndexState state) {
        if (marketMemoryEngine == null || !marketMemoryEngine.isEnabled()
                || state.activeStrike <= 0 || state.activeDirection == 0) return null;
        try {
            return marketMemoryEngine.get(indexType, state.activeStrike, state.activeDirection >= 0 ? "CE" : "PE");
        } catch (Exception e) {
            return null;
        }
    }

    /** Futures basis tracker — samples futures premium/discount vs spot once per minute.
     *  Feeds OperatorIntentRadar Module 12 (basis expansion = directional lead signal). Optional; null in tests. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private FuturesBasisTracker basisTracker;

    /** Per-index last basis sample timestamp — rate-limits basisTracker.sample() to once per minute. */
    private final ConcurrentHashMap<IndexType, Long> lastBasisSampleMs = new ConcurrentHashMap<>();

    /** Fix 1 — tiered trailing on the primary OI path. Tier thresholds shared with exit.tiered-trailing.*. */
    @org.springframework.beans.factory.annotation.Value("${exit.oi.tiered-trailing-enabled:false}")
    private boolean oiTieredTrailingEnabled;
    @org.springframework.beans.factory.annotation.Value("${exit.tiered-trailing.tier1-peak:8.0}")
    private double tieredTier1Peak;
    @org.springframework.beans.factory.annotation.Value("${exit.tiered-trailing.tier1-gap:3.0}")
    private double tieredTier1Gap;
    @org.springframework.beans.factory.annotation.Value("${exit.tiered-trailing.tier2-peak:15.0}")
    private double tieredTier2Peak;
    @org.springframework.beans.factory.annotation.Value("${exit.tiered-trailing.tier2-gap:2.0}")
    private double tieredTier2Gap;
    /** Fix 2 — progressive partial profit-booking on the primary OI path. */
    @org.springframework.beans.factory.annotation.Value("${exit.oi.progressive-booking-enabled:false}")
    private boolean oiProgressiveBookingEnabled;
    /**
     * Research-driven firm profit-target (2026-07-01). The exit-rule study over 28 snapshot days found
     * booking a winner at a fixed target BEATS trailing it — every TRAIL rule had the worst median net
     * and the highest give-back (13-14%), while TARGET was best. The live OI-momentum book loses via
     * give-back (55% win yet net<0). So cap a running winner at a firm target instead of letting the
     * ride/trail hand it back. Config-gated + captured (PROFIT_TARGET exit reason) so the tuning loop can
     * A/B it against ride-and-trail. Only affects RIDING positions — scalpMode already books at 3-8%.
     */
    @org.springframework.beans.factory.annotation.Value("${oi-momentum.profit-target.enabled:true}")
    private boolean profitTargetEnabled = true;
    @org.springframework.beans.factory.annotation.Value("${oi-momentum.profit-target.pct:15.0}")
    private double profitTargetPct = 15.0;
    /** Fix 3 — Tier-1 liquidity emergency exit on the primary OI path. */
    @org.springframework.beans.factory.annotation.Value("${exit.oi.liquidity-gate-enabled:false}")
    private boolean oiLiquidityGateEnabled;

    // ── Fast adverse-gap reaction (2026-06-28) ──────────────────────────────────────────────
    // Velocity-based exit that catches a VIOLENT move against the position within a few seconds —
    // the gap between flash-crash (−40% in 60s) and the level SL. Research
    // (docs/SPIKE-FOOTPRINT-ANALYSIS-2026-06-28.md): option spikes erupt in ~5s, driven by the
    // UNDERLYING. We detect a fast premium drop AND (optionally) confirm an adverse spot move over
    // the same window. SHADOW-FIRST: default logs would-exits with NO behaviour change; only acts
    // once gap-reaction-enabled=true (calibrate from a week of shadow output first).
    @org.springframework.beans.factory.annotation.Value("${exit.oi.gap-reaction-enabled:false}")
    private boolean gapReactionEnabled;
    @org.springframework.beans.factory.annotation.Value("${exit.oi.gap-reaction-shadow:true}")
    private boolean gapReactionShadow;
    @org.springframework.beans.factory.annotation.Value("${exit.oi.gap-reaction-window-sec:5}")
    private int gapReactionWindowSec;
    @org.springframework.beans.factory.annotation.Value("${exit.oi.gap-reaction-premium-drop-pct:8.0}")
    private double gapReactionPremiumDropPct;
    @org.springframework.beans.factory.annotation.Value("${exit.oi.gap-reaction-spot-confirm:true}")
    private boolean gapReactionSpotConfirm;
    @org.springframework.beans.factory.annotation.Value("${exit.oi.gap-reaction-spot-move-pct:0.12}")
    private double gapReactionSpotMovePct;

    // ── Theta-decay-aware scalp exit (2026-07-03) ────────────────────────────────────────────
    // The naive "premium > ₹300 → scalp faster" idea is only half right: a deep-ITM high-₹ option is
    // mostly INTRINSIC value and barely decays, while an ATM/OTM option (any ₹) is mostly EXTRINSIC and
    // bleeds theta fast. So we key the faster time cap off the TIME-VALUE FRACTION (extrinsic / premium),
    // NOT raw premium. For a mostly-extrinsic option held past a shorter cap while profitable in a choppy
    // (scalp-mode) regime, book it before theta erodes the gain instead of waiting out the flat 15-min cap.
    // Purely additive: same guards as the existing SCALP time cap (scalpMode + profit > 0), so it never
    // touches SL / trailing / profit-lock / trend-rides. Only ever makes a profitable scalp exit SOONER.
    @org.springframework.beans.factory.annotation.Value("${exit.oi.theta-decay-exit-enabled:true}")
    private boolean thetaDecayExitEnabled = true;
    /** Extrinsic (time-value) fraction ≥ this ⇒ option is theta-heavy ⇒ use the shorter cap. Deep-ITM sits below this. */
    @org.springframework.beans.factory.annotation.Value("${exit.oi.theta-decay-extrinsic-fraction:0.6}")
    private double thetaDecayExtrinsicFraction = 0.6;
    /** Shorter hold cap (minutes) for a theta-heavy profitable scalp — vs the flat 15-min cap below it. */
    @org.springframework.beans.factory.annotation.Value("${exit.oi.theta-decay-max-hold-min:8}")
    private int thetaDecayMaxHoldMin = 8;

    /**
     * Fast adverse-gap reaction (config-gated, SHADOW-first). Detects a violent move AGAINST the
     * open position inside {@code gapReactionWindowSec}: the option premium falling
     * ≥ {@code gapReactionPremiumDropPct}%, optionally confirmed by the underlying moving against
     * the position by ≥ {@code gapReactionSpotMovePct}% over the same window. Catches the move
     * before the level SL deepens the fill. Returns true ONLY when it actually closed the position
     * (enforce mode); in shadow mode it logs the would-exit and returns false so normal checks run.
     */
    private boolean checkFastGapReaction(IndexType indexType, IndexState state,
                                         com.algo.trade.persistence.TradeEntity trade,
                                         double currentPrice, double entryPrice) {
        if ((!gapReactionEnabled && !gapReactionShadow) || currentPrice <= 0 || state.activeDirection == 0) {
            return false;
        }
        // Reset the per-position premium buffer when the trade changes (no cross-position contamination).
        String tid = trade.getTradeId() == null ? null : String.valueOf(trade.getTradeId());
        if (state.gapPremTradeId == null || !state.gapPremTradeId.equals(tid)) {
            state.gapPremHist.clear();
            state.gapPremTradeId = tid;
            state.gapReactionLastFireMs = 0; // fresh trade — clear the re-fire throttle
        }
        long now = System.currentTimeMillis();
        long boundary = now - (long) gapReactionWindowSec * 1000L;
        state.gapPremHist.addLast(new double[]{now, currentPrice});
        while (!state.gapPremHist.isEmpty() && state.gapPremHist.peekFirst()[0] < boundary - 2000) {
            state.gapPremHist.pollFirst();
        }
        // Premium at/just before the window boundary.
        double startPrice = 0;
        for (double[] p : state.gapPremHist) {
            if (p[0] <= boundary) startPrice = p[1];
        }
        if (startPrice <= 0) return false;   // window not yet spanned by history
        double premDropPct = (currentPrice - startPrice) / startPrice * 100.0;   // negative = falling
        if (premDropPct > -gapReactionPremiumDropPct) return false;              // not fast enough

        // Underlying confirmation: spot moving against the position over the same window.
        double spotMove = gapReactionSpotConfirm
                ? momentumDetector.getShortWindowMovePct(indexType, gapReactionWindowSec) : 0;
        double adverseSpot = -state.activeDirection * spotMove;                  // >0 = against us
        if (gapReactionSpotConfirm && adverseSpot < gapReactionSpotMovePct) return false;

        // Re-fire throttle (2026-07-03): once gap-reaction has fired for this trade, don't re-log "— exiting"
        // or re-increment consecutiveLosses/dailyPnl every time the violent-drop condition re-triggers while
        // the close is BLOCKED (pending manual sell / already in-flight). On 2026-06-30 one stuck trade fired
        // 46× — pure noise (and a latent consecutiveLosses inflation). One reaction per trade per window is
        // enough; the position won't close via a re-fire while it's blocked anyway.
        if (state.gapReactionLastFireMs != 0
                && now - state.gapReactionLastFireMs < GAP_REACTION_REFIRE_THROTTLE_MS) {
            return false;
        }
        double profitPct = (currentPrice - entryPrice) / entryPrice * 100.0;
        String reason = String.format("FAST_GAP_REACTION(prem=%.1f%%/%ds,spot=%.2f%%,pnl=%.1f%%)",
                premDropPct, gapReactionWindowSec, adverseSpot, profitPct);
        if (gapReactionEnabled) {
            state.gapReactionLastFireMs = now;
            log.warn("[OIMomentum][{}] {} — exiting tradeId={}", indexType, reason, trade.getTradeId());
            closePosition(indexType, state, trade, currentPrice, reason);
            state.lastSlTime = Instant.now();
            state.consecutiveLosses.incrementAndGet();
            updateDailyPnl(state, profitPct, trade);
            return true;
        }
        // Shadow: record only — no behaviour change. Calibrate thresholds from these before enforcing.
        log.info("[OIMomentum][{}] SHADOW {} would-exit tradeId={} entry={} now={}",
                indexType, reason, trade.getTradeId(), entryPrice, currentPrice);
        return false;
    }

    /** Tiered trail gap (R6): never WIDENS the gap — only tightens it as the peak grows, so a
     *  large winner gives back less. Mirrors LivePositionExitMonitor.tieredTrailGap. */
    private double tieredTrailGap(double baseGap, double peakPct) {
        if (!oiTieredTrailingEnabled) return baseGap;
        double gap = baseGap;
        if (peakPct >= tieredTier1Peak) gap = Math.min(gap, tieredTier1Gap);
        if (peakPct >= tieredTier2Peak) gap = Math.min(gap, tieredTier2Gap);
        return gap;
    }

    /** Load already-fired progressive-booking layers from the persisted CSV (survives restarts). */
    private java.util.Set<String> oiLoadFiredLayers(TradeEntity trade) {
        java.util.Set<String> set = java.util.concurrent.ConcurrentHashMap.newKeySet();
        String layers = trade.getPartialExitLayers();
        if (layers != null && !layers.isBlank()) {
            set.addAll(java.util.Arrays.asList(layers.split(",")));
        }
        return set;
    }

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
        log.info("[FastOI] CONFIG enabled={} oiWindow={}s oiSignificanceFloor={} operatorBand=ATM±{} operatorRefresh=via OperatorFrameworkService | "
                + "when enabled: band OI-change uses a TRUE {}s window (was ~5-min oldest-sample) and the operator "
                + "framework refreshes from live-cache OI each ~15s vs the 09:15 baseline. Set oi-momentum.fast-oi.enabled=false to revert.",
                fastOiEnabled, fastOiWindowSeconds, oiSignificanceFloor(), fastOiOperatorBandStrikes, fastOiWindowSeconds);
    }

    /**
     * Restore in-memory state from DB after restart:
     * - Find any open OI_MOMENTUM trade → set activeTradeId per index
     * - Count today's closed OI_MOMENTUM trades → set tradesToday per index
     * - Count today's consecutive losses → set consecutiveLosses per index
     */
    /**
     * Trade IDs this loop is actively managing (has adopted into an IndexState). LivePositionExitMonitor
     * uses this as a safety net: a primary OI_MOMENTUM trade NOT in this set is unmanaged here, so the
     * monitor must protect it instead of blanket-skipping all primary OI_MOMENTUM trades.
     */
    public java.util.Set<String> activeManagedTradeIds() {
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (IndexState s : indexStates.values()) {
            String id = s.activeTradeId;
            if (id != null) ids.add(id);
        }
        return ids;
    }

    /** Bind a DB-open trade to the in-memory IndexState so managePosition (trail/BE/SL) protects it. */
    private void adoptOpenTrade(IndexType idx, IndexState state, TradeEntity openTrade, String tag) {
        state.activeTradeId = openTrade.getTradeId();
        state.activeDirection = "CE".equals(openTrade.getOptionType()) ? 1 : -1;
        state.peakPrice = openTrade.getPeakPrice() != null
                ? openTrade.getPeakPrice().doubleValue()
                : openTrade.getEntryPrice().doubleValue();
        state.lastEntryTime = openTrade.getEntryTime();
        state.pendingEntryInstrumentKey = null;
        // C3/F10-5 (2026-07-10): restore the avalanche identity across restarts. Without this the
        // adaptive ladder lost custody of every open avalanche trade on ANY restart (the 78200CE
        // trade bled −4.8% for 27 min under legacy-only management while AV_TRAIL/AV_SL sat idle).
        // The signal reason (now persisted through OrderEntity → entryReason) carries strike + vu.
        String er = openTrade.getEntryReason();
        if (er != null && er.contains("MEMORY_AVALANCHE") && !er.contains("MEMORY_AVALANCHE_STACK")) {
            state.avalancheEntryActive = true;
            try {
                var mS = java.util.regex.Pattern.compile("strike=(\\d+)").matcher(er);
                if (mS.find()) state.activeStrike = Integer.parseInt(mS.group(1));
                var mV = java.util.regex.Pattern.compile("vu=([0-9.]+)").matcher(er);
                if (mV.find()) state.avVolUnitAtEntry = Double.parseDouble(mV.group(1));
            } catch (Exception ignore) { /* partial restore is still better than none */ }
            log.info("[OIMomentum] {} — avalanche identity RESTORED: strike={} vu={} (ladder resumes custody)",
                    tag, state.activeStrike, state.avVolUnitAtEntry);
        }
        log.info("[OIMomentum] {} open trade: {} index={} direction={} entry={} peak={}",
                tag, state.activeTradeId, idx, state.activeDirection,
                openTrade.getEntryPrice(), state.peakPrice);
    }

    /**
     * ORPHAN SAFETY (2026-07-01): mid-session, adopt any DB-OPEN primary OI_MOMENTUM trade for this index
     * that the strategy isn't tracking. Without this, a filled entry that the pending-entry resolver missed
     * (e.g. the pending key timed out, or OrderFillWatchdog created the trade as an orphan) stays UNMANAGED —
     * no trailing/BE/SL runs (LivePositionExitMonitor skips primary OI_MOMENTUM), AND the strategy, blind to
     * its own position, re-buys the same strike at a higher price and piles onto one index. reconcileFromDb
     * only runs at startup; this closes the mid-session gap. Throttled per index. Returns true if it adopted.
     */
    private boolean sweepOrphanedOpenTrade(IndexType indexType, IndexState state) {
        if (state.activeTradeId != null) return false;
        if (Instant.now().isBefore(state.lastOrphanSweepTime.plusSeconds(10))) return false;
        state.lastOrphanSweepTime = Instant.now();
        try {
            var orphan = tradeRepository.findByStatus(TradeStatus.OPEN).stream()
                    .filter(t -> StrategyType.OI_MOMENTUM.name().equals(t.getStrategyType()))
                    .filter(t -> !t.isPaperTrade())
                    .filter(t -> t.getTradeId() == null || !t.getTradeId().startsWith("SYNC-"))
                    .filter(t -> t.getUserId() == null
                            || t.getUserId().equals(com.algo.trade.multiuser.UserContext.DEFAULT_USER_ID))
                    .filter(t -> indexType == resolveIndexFromInstrumentKey(t.getInstrumentKey()))
                    // STACK trades are not orphans: the stack ladder manages them (and after a restart
                    // the central exit monitor does) — adopting one as primary would double-manage it.
                    .filter(t -> t.getEntryReason() == null || !t.getEntryReason().contains("MEMORY_AVALANCHE_STACK"))
                    .findFirst();
            if (orphan.isPresent()) {
                log.warn("[OIMomentum][{}] ORPHAN ADOPTED mid-session — an OPEN OI_MOMENTUM trade was unmanaged "
                        + "(no trailing/SL was running); binding it now so managePosition protects it", indexType);
                adoptOpenTrade(indexType, state, orphan.get(), "Orphan-adopted");
                return true;
            }
        } catch (Exception ex) {
            log.debug("[OIMomentum][{}] orphan sweep failed (non-fatal): {}", indexType, ex.getMessage());
        }
        return false;
    }

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
                    // STACK trades restart as monitor-protected positions (stamped SL/target/trail),
                    // never as the primary — the singular state machine must not claim them.
                    .filter(t -> t.getEntryReason() == null || !t.getEntryReason().contains("MEMORY_AVALANCHE_STACK"))
                    .toList();
            for (TradeEntity openTrade : openTrades) {
                IndexType idx = resolveIndexFromInstrumentKey(openTrade.getInstrumentKey());
                IndexState state = indexStates.get(idx);
                if (state == null) continue;
                if (state.activeTradeId != null) continue; // Already has an active trade
                adoptOpenTrade(idx, state, openTrade, "Reconciled");
            }

            // Count today's OI_MOMENTUM trades per index
            Instant dayStart = today.atStartOfDay(IST).toInstant();
            Instant dayEnd = today.plusDays(1).atStartOfDay(IST).toInstant();
            var todayTrades = tradeRepository.findByEntryTimeBetween(dayStart, dayEnd).stream()
                    .filter(t -> StrategyType.OI_MOMENTUM.name().equals(t.getStrategyType()))
                    // C9 (2026-07-10): count PRIMARY trades only. Copies made the per-index counter
                    // scale with user count (69 by 12:47 on 07-09 with 2 users), halving the dynamic
                    // day-cap per added user — the cap that silently ate the 07-09 afternoon harvest.
                    // The live-path increments fire once per SIGNAL (u:sys), so reconcile must match.
                    .filter(t -> !t.isPaperTrade())
                    .filter(t -> t.getTradeId() == null || !t.getTradeId().startsWith("SYNC-"))
                    .filter(t -> t.getUserId() == null
                            || t.getUserId().equals(com.algo.trade.multiuser.UserContext.DEFAULT_USER_ID))
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
            // Capture-when-stopped: keep evaluating + recording tuning data while stopped. Order placement is
            // still blocked downstream — executeEntry/executePaperEntry reject when !running() (no order, clean
            // state). Kill switch above always stops everything.
            if (!tradingStateService.scanForCaptureAllowed()) return;

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
                    state.fadesToday.set(0);
                    state.avalanchesToday.set(0);
                    state.avalanchesTodayByInstrument.clear();
                    state.avalancheCooldownUntilMs.clear();
                    state.avalancheStacks.clear(); // AV_EOD closed them yesterday; drop any stale refs
                    episodeRecordedTradeIds.clear(); // F10-1 dedupe set is intraday
                    state.reversalsToday.set(0);
                    state.consecutiveLosses.set(0);
                    state.dailyPnl = 0;
                    // v3 quality: reset halt + loss-tracker for new trading day
                    state.haltedForDay = false;
                    state.haltExpiresAt = null;
                    state.consecLossPausesToday.set(0);
                    state.totalLossesPnl = 0;
                    state.totalLossesCount.set(0);
                    state.dailyPnlCountedTrades.clear();
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
                // Reset the DYNAMIC OI-shift rolling baseline so it re-calibrates from this session's churn.
                if (dynamicOiFloor != null) {
                    dynamicOiFloor.resetSession(null);
                }
                rejectReasonCounters.clear();
            }

            // Process each enabled index independently (driven by UNDERLYING_CONFIGS table).
            // Per-index try/catch (2026-07-02): a persistent exception on one index must not starve the
            // OTHERS' entry/exit management for the tick, nor count toward the loop-wide error breaker that
            // pauses ALL indices. Each index's management (incl. its open-trade exits) is isolated.
            for (IndexType indexType : getEnabledIndices()) {
                try {
                    tickIndex(indexType);
                } catch (Exception ixe) {
                    log.warn("[OIMomentum] tickIndex({}) failed this tick (other indices unaffected): {}",
                            indexType, ixe.toString());
                }
            }

            if (schedulerRegistry != null) schedulerRegistry.recordRun("oiMomentum");

            // Periodic summary log every 60 seconds for visibility
            if (tickCounter.incrementAndGet() % 60 == 0) {
                logPeriodicSummary();
            }
            consecutiveErrors.set(0); // Reset on success
            pauseCount.set(0); // Reset backoff level on successful tick
        } catch (Exception e) {
            int errors = consecutiveErrors.incrementAndGet();
            log.warn("[OIMomentum] Tick error (consecutive={}): {}", errors, e.getMessage(), e);
            if (errors >= MAX_CONSECUTIVE_ERRORS) {
                int level = Math.min(pauseCount.getAndIncrement(), BACKOFF_DURATIONS.length - 1);
                Duration pause = BACKOFF_DURATIONS[level];
                pausedUntil = Instant.now().plus(pause);
                consecutiveErrors.set(0);
                log.error("[OIMomentum] {} consecutive errors — PAUSING for {}s (backoff level {})",
                        MAX_CONSECUTIVE_ERRORS, pause.getSeconds(), level);
                if (telegramAlertService != null) {
                    telegramAlertService.systemAlert(String.format(
                            "🚨 OIMomentum: %d consecutive errors — PAUSED for %ds (level %d)\nLast: %s",
                            MAX_CONSECUTIVE_ERRORS, pause.getSeconds(), level, e.getMessage()));
                }
            }
        }
    }

    /** Process one index per tick — feed momentum, check OI, manage/detect. */
    /**
     * The single window-aware entry point for band OI-change reads. FAST-OI on (default) → a genuine
     * trailing {@code fast-oi.oi-window-seconds} (60s) window via {@code getAtmOiChangeSeconds}. Off →
     * byte-for-byte legacy behaviour: {@code getAtmOiChange(...,3)} (oldest-sample ~5-min reach-back).
     */
    private long[] oiChangeWindowed(IndexType indexType, int atmStrike, int strikesAround) {
        if (!fastOiEnabled) {
            return liveInstrumentCache.getAtmOiChange(indexType, atmStrike, strikesAround, 3);
        }
        return liveInstrumentCache.getAtmOiChangeSeconds(indexType, atmStrike, strikesAround,
                Math.max(15, fastOiWindowSeconds));
    }

    /**
     * OI-band significance floor for one-sided-unwind guards. FAST-OI on → {@code fastOiSignificanceFloor}
     * (200k), scaled 0.4× from the legacy 500k because a true 60s window captures ~0.4× the |Δ| of the
     * old ~5-min reach-back (measured band |Δ| median: 60s≈54k CE / 86k PE vs 180s≈134k / 215k).
     */
    private long oiSignificanceFloor() {
        return fastOiEnabled ? fastOiSignificanceFloor : 500_000L;
    }

    /**
     * SLOT 1 (2026-07-08) — DYNAMIC OI-band significance floor, per index. This single method re-bases the
     * {@code oiDir()} asymmetry guard, so it fixes both the expiry over-rejection (fixed 200k = noise on a
     * violent expiry minute → false CASE4 conflicts) and the thin-day under-detection (200k = a meaningful
     * shift on a fresh Wednesday → real shifts miss the guard). When the dynamic baseline is disabled or
     * still warming up, this returns the EXACT legacy floor ({@link #oiSignificanceFloor()}), so warm-up and
     * flag-off are byte-identical to today. See {@link DynamicOiFloor#minSignificant}.
     */
    private long oiSignificanceFloor(IndexType indexType) {
        long legacy = oiSignificanceFloor();
        if (dynamicOiFloor != null && indexType != null) {
            return dynamicOiFloor.minSignificant(indexType, legacy);
        }
        return legacy;
    }

    /** OI-change window (sec) in effect — for tuning capture: true 60s window when fast, ~300s oldest-sample legacy. */
    private int captureOiWindowSec() {
        return fastOiEnabled ? Math.max(15, fastOiWindowSeconds) : 300;
    }

    /**
     * Age (sec) of the current operator signal = its freshness/lead-time at decision, for tuning capture.
     * With FAST-OI the operator signal refreshes ~every 15s (vs the 5-min snapshot), so this should read
     * low when the fast path is working. -1 = no signal / never computed.
     */
    private long operatorSignalAgeSec(IndexType indexType) {
        if (operatorFrameworkService == null) return -1L;
        var sig = operatorFrameworkService.getOperatorSignal(indexType);
        if (sig == null) return -1L;
        Instant c = sig.getComputedAt();
        if (c == null || c.getEpochSecond() <= 0) return -1L;
        return Math.max(0L, Instant.now().getEpochSecond() - c.getEpochSecond());
    }

    /**
     * Build a strike→OI map from the LIVE cache (ATM±{@code fastOiOperatorBandStrikes}) for the operator
     * framework's fast refresh. IV fields are 0 — OperatorAccumulationDetector.analyze() uses OI only.
     * Returns an empty map if the futures/expiry aren't resolvable yet.
     */
    private java.util.Map<Integer, OperatorAccumulationDetector.StrikeSnapshot> buildLiveOperatorStrikeMap(
            IndexType indexType, int atm) {
        java.util.Map<Integer, OperatorAccumulationDetector.StrikeSnapshot> map = new java.util.HashMap<>();
        LocalDate expiry = expiryCalendar.getCurrentExpiry(indexType);
        if (expiry == null) return map;
        int interval = indexType.strikeInterval();
        Instant now = Instant.now();
        for (int i = -fastOiOperatorBandStrikes; i <= fastOiOperatorBandStrikes; i++) {
            int strike = atm + (i * interval);
            var ceOpt = liveInstrumentCache.getOption(indexType, strike, "CE", expiry);
            var peOpt = liveInstrumentCache.getOption(indexType, strike, "PE", expiry);
            long ceOi = ceOpt.map(OptionInstrument::getOpenInterest).orElse(0L);
            long peOi = peOpt.map(OptionInstrument::getOpenInterest).orElse(0L);
            if (ceOi <= 0 && peOi <= 0) continue; // strike not yet populated in cache
            map.put(strike, new OperatorAccumulationDetector.StrikeSnapshot(
                    strike, ceOi, peOi, 0.0, 0.0, now));
        }
        return map;
    }

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
            long[] oiChange = oiChangeWindowed(indexType, atm, 3); // FAST-OI window (2min) when enabled
            state.oiAdvanced = (oiChange[0] != state.lastOiCeChange || oiChange[1] != state.lastOiPeChange);
            if (state.oiAdvanced) {
                state.lastOiCeChange = oiChange[0];
                state.lastOiPeChange = oiChange[1];
                state.lastOiTickTime = Instant.now(); // bias decay anchor
                // Cache normalized OI delta for DecisionAggregator. Divisor scales with the window so
                // the feature keeps its tuned magnitude: legacy ~5-min reach-back → ÷100k; FAST-OI 60s
                // window captures ~0.3× the |Δ| → ÷30k, preserving the same normalized scale.
                long netOiDelta = oiChange[0] - oiChange[1]; // CE buildup - PE buildup
                double oiNorm = fastOiEnabled ? 30_000.0 : 100_000.0;
                state.lastOiDeltaPct = netOiDelta == 0 ? 0 : netOiDelta / oiNorm; // normalize to ±units
                // Sample OI surge detector on every OI advancement
                if (oiSurgeDetector != null) oiSurgeDetector.sample(indexType);
            }

            // FAST-OI: refresh the operator framework from per-tick OI (vs waiting for the 5-min
            // ChainSnapshot). No-op unless fast-oi.enabled AND the 09:15 baseline is registered;
            // internally throttled (fast-oi.operator-refresh-seconds). Feeds the shared operator
            // signal that CASE0/CASE5-override/confidence-bonus consume. See [FastOI] logs.
            if (fastOiEnabled && operatorFrameworkService != null) {
                var liveStrikeMap = buildLiveOperatorStrikeMap(indexType, atm);
                operatorFrameworkService.refreshFromLiveCache(indexType, liveStrikeMap, spot, atm);
            }
        }

        // Resolve pending entry if order was accepted but tradeId not yet available
        if (state.activeTradeId == null && state.pendingEntryInstrumentKey != null) {
            // P0-6 FIX: adopt our entry's OPEN trade. Prefer an OI_MOMENTUM trade, but fall back to
            // ANY untracked OPEN trade for the instrument. A fill reconciled by OrderFillWatchdog
            // can be created as an orphan with a different/defaulted strategy type; requiring the
            // strict OI_MOMENTUM type previously let a FILLED position be dropped by the timeout
            // below and left unmanaged through its peak (the 24150 PE incident).
            java.util.Set<String> trackedByOtherStates = indexStates.values().stream()
                    .map(s -> s.activeTradeId)
                    .filter(java.util.Objects::nonNull)
                    .collect(java.util.stream.Collectors.toSet());
            var openForInstrument = tradeRepository.findByStatus(TradeStatus.OPEN).stream()
                    .filter(t -> state.pendingEntryInstrumentKey.equals(t.getInstrumentKey()))
                    .filter(t -> !t.isPaperTrade())
                    // NEVER adopt a SYNC- (manual / broker-synced) position as our entry. A user's own
                    // manual order on the SAME strike would otherwise be claimed as activeTradeId and then
                    // scalp-exited by the strategy — the 2026-06-30 incident where the bot sold u:8's manual
                    // 650-qty 23950PE at +7% within 8 min. Manual positions are the user's to manage.
                    .filter(t -> t.getTradeId() == null || !t.getTradeId().startsWith("SYNC-"))
                    // PRIMARY-ONLY (2026-07-01): only adopt the PRIMARY's own trade. This loop runs in the
                    // primary context and its IndexState is the primary account; secondary users' copied
                    // OI_MOMENTUM trades are managed by LivePositionExitMonitor. Without this, when an aligned-
                    // fire copy (u:8) filled BEFORE the primary's own order, the primary adopted u:8's trade and
                    // its OWN fill then orphaned (11:06 today). Matches sweepOrphanedOpenTrade's user filter.
                    .filter(t -> t.getUserId() == null
                            || t.getUserId().equals(com.algo.trade.multiuser.UserContext.DEFAULT_USER_ID))
                    .filter(t -> !trackedByOtherStates.contains(t.getTradeId()))
                    .toList();
            var found = openForInstrument.stream()
                    .filter(t -> StrategyType.OI_MOMENTUM.name().equals(t.getStrategyType()))
                    .findFirst()
                    .or(() -> openForInstrument.stream().findFirst());
            if (found.isPresent()) {
                state.activeTradeId = found.get().getTradeId();
                state.peakPrice = found.get().getEntryPrice().doubleValue();
                state.pendingEntryInstrumentKey = null;
                log.info("[OIMomentum][{}] Pending entry resolved: tradeId={} (strategyType={})",
                        indexType, state.activeTradeId, found.get().getStrategyType());
            }
            // If still pending after 120s, only clear when the entry did NOT fill. An empty
            // openForInstrument means no live position exists for the instrument (genuine no-fill),
            // so it is safe to unblock. If a position DID fill, the watchdog's per-cycle orphan
            // reconciliation creates the trade within seconds and the adoption above picks it up —
            // we must never clear a FILLED position (P0-6).
            if (state.pendingEntryInstrumentKey != null) {
                long pendingSecs = state.lastEntryTime != null
                        ? Duration.between(state.lastEntryTime, Instant.now()).getSeconds()
                        : 999; // safety: if lastEntryTime is somehow null, force clear
                if (pendingSecs > 120 && openForInstrument.isEmpty()) {
                    log.warn("[OIMomentum][{}] Pending entry timed out after {}s for {} (no fill detected) — clearing to unblock",
                            indexType, pendingSecs, state.pendingEntryInstrumentKey);
                    state.pendingEntryInstrumentKey = null;
                    state.activeDirection = 0;
                } else if (pendingSecs > 120) {
                    log.error("[OIMomentum][{}] Pending entry {} still unadopted after {}s but an OPEN position exists — keeping pending (NOT dropping a live position)",
                            indexType, state.pendingEntryInstrumentKey, pendingSecs);
                }
            }
        }

        // ORPHAN SAFETY: before treating this index as flat, adopt any DB-open OI_MOMENTUM trade the
        // strategy isn't tracking (filled entry the pending-resolver missed / watchdog orphan). Throttled.
        if (state.activeTradeId == null && state.pendingEntryInstrumentKey == null) {
            sweepOrphanedOpenTrade(indexType, state);
        }

        // OI-divergence monitor: sample the ATM band each tick so candidate (entry) and held (exit) strikes
        // accumulate ~window history. Shadow-first; never affects trading here — only feeds the buffers.
        if (oiDivergenceMonitor != null && oiDivergenceMonitor.isEnabled()) {
            double divSpot = liveInstrumentCache.getFuturesPrice(indexType);
            if (divSpot > 0) oiDivergenceMonitor.sampleBand(indexType, indexType.roundToATM(divSpot));
        }

        // Futures basis tracker: sample once per minute (internal rate-limit via lastBasisSampleMs).
        // Feeds FuturesBasisTracker → OperatorIntentRadar Module 12. Non-blocking; never affects trading.
        if (basisTracker != null) {
            long nowMs = System.currentTimeMillis();
            Long lastSample = lastBasisSampleMs.get(indexType);
            if (lastSample == null || nowMs - lastSample >= 60_000L) {
                lastBasisSampleMs.put(indexType, nowMs);
                basisTracker.sample(indexType);
            }
        }

        // V5 stacking tick (review-3 issue 1): manage stacked avalanche exits every tick, and while the
        // primary slot is occupied keep SCANNING — simultaneous avalanches on other strikes may stack
        // (the replay's main P&L source). The primary state machine below is untouched by all of this.
        if (avalancheStackEnabled) {
            manageAvalancheStacks(indexType, state);
            if (state.activeTradeId != null && isEntryWindow()
                    && marketMemoryEngine != null && marketMemoryEngine.isEnabled()) {
                double stackSpot = momentumDetector.getSpot(indexType);
                Object[] avs = detectAvalanche(indexType, state, stackSpot);
                if (avs != null) {
                    tryPlaceAvalancheStack(indexType, state, (Integer) avs[0], (Integer) avs[1],
                            (com.algo.trade.marketdata.MarketMemoryEngine.MemorySnapshot) avs[2], stackSpot);
                }
            }
        }

        if (state.activeTradeId != null) {
            managePosition(indexType, state);
        } else if (isEntryWindow()) {
            detectEntry(indexType, state);
        } else if (captureAfterEntryWindow && isAfterEntryWindowDuringMarket()) {
            // P0.2: capture-only heartbeat for the post-cutoff window (no detectEntry → no new entry).
            recordEntryCutoffHeartbeat(indexType, state);
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
                // Diagnostic only (expected when external/manual/watchdog closes reclassify trades after the
                // fact) — DEBUG, not WARN, so it doesn't read as an actionable alert. See comment above.
                log.debug("[OIMomentum] Counter invariant drift changed: momentum={} != entered({}) + rejected({}) + pending({}) = {} (drift={})",
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

        // ── Chop-regime chase-OFF (2026-07-06) ──────────────────────────────────────────────────────
        // On quiet chop tape, suppress the CHASE entry triggers (EARLY_OI_VELOCITY, SUSTAINED_DRIFT) — they
        // bleed there. Every OTHER trigger and the entire remaining path is untouched: on trend/expansion
        // tape isChopRegime()=false → chopSuppressChase=false → no code path below changes. See
        // docs/CHOP-REGIME-FADE-FIX.md. (Fade-mode + exit-grace re-derive chop at their own sites.)
        // Conviction BYPASS (2026-07-07, item 2b): if a strong operator move is live (op-score + oiVel),
        // do NOT suppress the chase — that's exactly the move we want. Signal-driven, NO clock. When the
        // bypass flag is off OR conviction isn't met, chopSuppressChase is unchanged (byte-identical).
        boolean chaseBypass = operatorMoveModeEnabled && chaseBypassOnConviction
                && operatorScoreOf(indexType) >= chaseBypassOpScoreMin
                && oiVelPctOf(indexType) >= chaseBypassOiVelMin;
        boolean chopSuppressChase = chopSuppressChaseEnabled && isChopRegime(indexType) && !chaseBypass;
        if (chaseBypass && chopSuppressChaseEnabled && isChopRegime(indexType)) {
            log.info("[OIMomentum][{}] CHASE_BYPASS (operator-move): chop chase-off suppressed by conviction "
                    + "opScore={} oiVel={}%", indexType,
                    String.format("%.0f", operatorScoreOf(indexType)),
                    String.format("%.2f", oiVelPctOf(indexType)));
        }

        // ── Phase 0 regime stand-down — DEFAULT OFF, and DO NOT enable as a standalone hard block ──
        // CAUTION (2026-06-23 analysis): rangePct30m is a TRAILING measure — it only expands AFTER a move.
        // On 06-23 a real 0.57% NIFTY move ignited while the trailing range was still 0.10–0.19% (below this
        // floor), so a hard block here would have suppressed the entry at the exact moment we wanted it. A
        // trailing-range floor cannot distinguish "dead chop that stays dead" (06-22) from "quiet before a
        // move" (06-23) — they look identical until too late. So this is NOT the capital-preservation win it
        // first appeared to be. Kept default-OFF and intended only as a future input to a *leading* regime
        // model (e.g. range-expansion / VIX-percentile) or as a conviction/size DAMPER — never a blanket
        // entry block. The real missed-entry causes on moving days were no_momentum_detected (detector misses
        // ignition) and matrix_skip (over-rejection), not a missing gate. Recorded as a reject for visibility.
        if (regimeStandDownEnabled
                && state.lastRangePct30m > 0
                && state.lastRangePct30m < regimeStandDownMinRangePct) {
            recordThrottleReject(indexType, state, String.format(java.util.Locale.ROOT,
                    "regime_standdown:dead_range(%.2f<%.2f)", state.lastRangePct30m, regimeStandDownMinRangePct));
            return;
        }

        // ── v3 Quality Gates (feature-flagged, default OFF) ──
        // Circuit-breaker halt (timed pause: auto-resumes after 30 min, or locks for day if severe).
        if (state.haltedForDay) {
            // Check if timed halt has expired → auto-resume
            if (state.haltExpiresAt != null && Instant.now().isAfter(state.haltExpiresAt)) {
                state.haltedForDay = false;
                state.haltExpiresAt = null;
                state.consecutiveLosses.set(0); // reset streak on resume
                log.info("[OIMomentum][{}] Timed halt expired — auto-resuming entries", indexType);
            } else {
                recordThrottleReject(indexType, state, "halted_for_day");
                return;
            }
        }
        // Consecutive-loss halt: timed 30-min pause (not permanent). Allows recovery.
        if (config.getConsecutiveLossHaltCount() > 0
                && state.consecutiveLosses.get() >= config.getConsecutiveLossHaltCount()) {
            state.haltedForDay = true;
            int cycles = state.consecLossPausesToday.incrementAndGet();
            boolean permanent = cycles >= MAX_CONSEC_LOSS_PAUSES_PER_DAY;
            // After N pause cycles the regime is clearly hostile — halt for the DAY (null expiry = no auto-resume,
            // per haltExpiresAt semantics) instead of resuming into another 5-loss streak (06-29 resumed ~6×).
            state.haltExpiresAt = permanent ? null : Instant.now().plus(Duration.ofMinutes(30));
            if (permanent) {
                log.warn("[OIMomentum][{}] Consecutive-loss HALT FOR DAY — {} losses in a row, {} pause cycles "
                        + "(>= {}). No auto-resume; entries stop until tomorrow.",
                        indexType, state.consecutiveLosses.get(), cycles, MAX_CONSEC_LOSS_PAUSES_PER_DAY);
                if (telegramAlertService != null) {
                    telegramAlertService.systemAlert(String.format(
                            "🛑 OIMomentum [%s]: %d pause cycles — HALTED FOR THE DAY (no auto-resume). Regime hostile.",
                            indexType, cycles));
                }
                recordThrottleReject(indexType, state,
                        "consecutive_loss_halt:" + state.consecutiveLosses.get() + "(day_halt,cycles=" + cycles + ")");
            } else {
                log.warn("[OIMomentum][{}] Consecutive loss PAUSE 30min — {} losses in a row (cycle {}/{}). Auto-resumes at {}",
                        indexType, state.consecutiveLosses.get(), cycles, MAX_CONSEC_LOSS_PAUSES_PER_DAY, state.haltExpiresAt);
                if (telegramAlertService != null) {
                    telegramAlertService.systemAlert(String.format(
                            "⏸️ OIMomentum [%s]: %d consecutive losses — PAUSED 30min (cycle %d/%d). Auto-resumes.",
                            indexType, state.consecutiveLosses.get(), cycles, MAX_CONSEC_LOSS_PAUSES_PER_DAY));
                }
                recordThrottleReject(indexType, state,
                        "consecutive_loss_halt:" + state.consecutiveLosses.get() + "(30min_pause,cycle=" + cycles + ")");
            }
            return;
        }
        // Expiry-day OTM late cutoff: on resolved expiry day, block entries past cutoff.
        // §3.5: skip when atm-exempt is set — OI Momentum buys ATM, not the deep-OTM theta-cliff this
        // guards against, and the blanket block was killing the expiry-gamma window (the highest-move bucket).
        // Item 2a (2026-07-07): also bypass when a strong operator move is live — the theta-cliff protection
        // is REPLACED by a conviction requirement (not removed blindly). Signal-driven, NO clock. When
        // operator-move-mode is off / no move detected, the cutoff behaves exactly as before.
        boolean otmCutoffBypass = isOperatorMoveActive(indexType);
        // V5 (2026-07-09): a LIVE avalanche is a conviction signal of the same class as an operator
        // move — the 14:45 theta-cliff cutoff (built for clock-based OTM chases) must not blanket-
        // block the expiry-afternoon squeeze harvest. Same replace-clock-with-conviction pattern as
        // Item 2a above. The avalanche flow runs to the real expiry entry cutoff (15:15).
        if (!otmCutoffBypass && avalancheEnabled
                && expiryCalendar.isExpiryDay(indexType)
                && marketMemoryEngine != null && marketMemoryEngine.isEnabled()) {
            double avCkSpot = momentumDetector.getSpot(indexType);
            otmCutoffBypass = avCkSpot > 0 && detectAvalanche(indexType, state, avCkSpot) != null;
            if (otmCutoffBypass) {
                log.info("[OIMomentum][{}] expiry OTM cutoff BYPASSED — live AVALANCHE signal (conviction replaces the clock)",
                        indexType);
            }
        }
        if (config.isExpiryOtmCutoffEnabled() && !config.isExpiryOtmCutoffAtmExempt()
                && !otmCutoffBypass
                && expiryCalendar.isExpiryDay(indexType)) {
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
        // Dynamic max trades: base cap scales with regime, win rate, and expiry day.
        // On strong operator-footprint days the cap expands; on weak days it contracts.
        int dynamicMaxTrades = computeDynamicMaxTrades(indexType, state);
        if (state.tradesToday.get() >= dynamicMaxTrades) {
            // V5 (2026-07-09 afternoon forensic): avalanche-ONLY pass-through. The day-cap blocked
            // the whole 14:30+ SENSEX collapse harvest (69/54) while live DEEP dP5m<2% signals sat
            // in the engine. Only the avalanche branch bypasses (its own validated budget applies);
            // bias/fade/case0 entries stay capped. Engine-side per-user gates still apply inside.
            if (avalancheExemptDayCap && tryAvalancheEntry(indexType, state)) {
                return;
            }
            recordThrottleReject(indexType, state, "max_trades_day:" + state.tradesToday.get() + "/" + dynamicMaxTrades);
            return;
        }
        if (state.consecutiveLosses.get() >= config.getConsecutiveLossPause()) {
            recordThrottleReject(indexType, state, "consecutive_loss_pause");
            return;
        }
        state.lastEntrySlOverride = false; // reset each pass; set true only if the override fires below
        if (state.lastSlTime != null && Duration.between(state.lastSlTime, Instant.now()).getSeconds() < config.getCooldownAfterSlSeconds()) {
            // Override: skip SL cooldown if reversal detector has an active opposing signal.
            // The SL was expected (operator flip in progress) — re-enter in new direction immediately.
            boolean reversalOverride = operatorReversalDetector != null
                    && operatorReversalDetector.isEnabled()
                    && operatorReversalDetector.getSignal(indexType).isActive();
            if (!reversalOverride) {
                long remaining = config.getCooldownAfterSlSeconds()
                        - Duration.between(state.lastSlTime, Instant.now()).getSeconds();
                recordThrottleReject(indexType, state, "sl_cooldown:" + remaining + "s");
                return;
            }
            // Reversal signal active → skip cooldown, allow immediate re-entry in new direction
            state.lastEntrySlOverride = true; // captured into the entry reason for the report
            log.debug("[OIMomentum][{}] SL_COOLDOWN_OVERRIDE: reversal signal active, allowing immediate re-entry",
                    indexType);
        }

        // ── Midday reduction ──
        // middayTradeReductionPercent (default 50) = fraction of softTarget allowed before
        // throttling during the midday window. e.g. 50% of 15 = 7 trades max before midday throttle.
        // Previously this was hardcoded as softTargetTradesPerDay / 2 (ignoring the config value).
        LocalTime now = LocalTime.now(IST);
        int middayThreshold = Math.max(1,
                (int) Math.round(config.getSoftTargetTradesPerDay() * config.getMiddayTradeReductionPercent() / 100.0));
        if (isMidday(now) && state.tradesToday.get() >= middayThreshold) {
            // Same avalanche-only pass-through as the day-cap above (midday is a bias-churn gate).
            if (avalancheExemptDayCap && tryAvalancheEntry(indexType, state)) {
                return;
            }
            recordThrottleReject(indexType, state, "midday_reduction");
            return;
        }

        // ── Squareoff window — no new entries (relaxed on expiry day for gamma scalps) ──
        // Item 2c (2026-07-07): expiry entry cutoff now the dedicated expiryEntryCutoffTime (15:15) so late
        // operator moves aren't blocked; non-expiry keeps squareoff − 10 min. The square-off EXIT timing and
        // the hard 15:20 force square-off are untouched; late entries carry a time-stop (see manage-exit).
        LocalTime squareoffCutoff = entryCutoffTime(indexType);
        if (now.isAfter(squareoffCutoff)) {
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
                long[] case0Oi = oiChangeWindowed(indexType, case0Atm, 3);
                double case0Pcr = liveInstrumentCache.getRealtimePcr(indexType);
                int case0PcrDir = pcrDir(case0Pcr);
                int case0OiDir = oiDir(indexType, case0Oi[0], case0Oi[1]);
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
        // Drift throttle: once a drift entry is attempted, don't re-fire for 60s (prevents the
        // CONCURRENT_ENTRY_BLOCKED spam when the drift condition stays true for minutes).
        if (driftBindingLive && state.lastDriftAttemptTime != null
                && Duration.between(state.lastDriftAttemptTime, Instant.now()).getSeconds() < 60) {
            driftBindingLive = false;
        }
        // Chop chase-OFF: suppress the SUSTAINED_DRIFT (chase) entry on quiet chop tape. Shadow logging
        // below still records the fire; only the LIVE bind is withheld. Trend tape: chopSuppressChase=false.
        if (driftBindingLive && chopSuppressChase) {
            log.info("[OIMomentum][{}] SUSTAINED_DRIFT entry suppressed — chop regime (chase-off)", indexType);
            driftBindingLive = false;
        }
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
                long[] driftOi = oiChangeWindowed(indexType, driftAtm, 3);
                double driftPcr = liveInstrumentCache.getRealtimePcr(indexType);
                int driftPcrDir = pcrDir(driftPcr);
                int driftOiDir = oiDir(indexType, driftOi[0], driftOi[1]);
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
                state.lastDriftAttemptTime = Instant.now(); // throttle: don't re-fire for 60s
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
        // OPERATOR REVERSAL DETECTOR — update footprint observations for direction flip detection
        if (operatorReversalDetector != null && operatorReversalDetector.isEnabled()) {
            double pcrSlopeForReversal = (v3MarketContext != null)
                    ? v3MarketContext.pcrSlope5Min(indexType) : 0.0;
            double spotForReversal = liveInstrumentCache.getFuturesPrice(indexType);
            operatorReversalDetector.tick(indexType, state.activeDirection,
                    state.lastOiCeChange, state.lastOiPeChange, pcrSlopeForReversal, spotForReversal);
        }
        // OI LADDER DETECTOR — sample every tick (internal rate-limiting at 30s intervals)
        if (oiLadderDetector != null && oiLadderDetector.isEnabled()) {
            oiLadderDetector.sample(indexType);
        }
        // GAMMA SCALP DETECTOR — tick every second on expiry days
        if (gammaScalpDetector != null && gammaScalpDetector.isEnabled()) {
            gammaScalpDetector.tick(indexType);
        }
        // OI LEVEL BOUNCE DETECTOR — identifies support/resistance entries on range-bound days
        if (oiLevelBounceDetector != null && oiLevelBounceDetector.isEnabled()) {
            oiLevelBounceDetector.tick(indexType);
        }
        // TIME BIAS ENGINE — detects scheduled operator moves at 20-min anchor times
        if (timeBiasEngine != null && timeBiasEngine.isEnabled()) {
            double pcrSlopeForTime = (v3MarketContext != null) ? v3MarketContext.pcrSlope5Min(indexType) : 0.0;
            timeBiasEngine.tick(indexType, state.lastOiCeChange, state.lastOiPeChange,
                    pcrSlopeForTime, liveInstrumentCache.getFuturesPrice(indexType));
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
                long[] sqzOi = oiChangeWindowed(indexType, squeezeAtm, 3);
                double sqzPcr = liveInstrumentCache.getRealtimePcr(indexType);
                int sqzPcrDir = pcrDir(sqzPcr);
                int sqzOiDir = oiDir(indexType, sqzOi[0], sqzOi[1]);
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
        // ── Fade mode LIVE in chop (2026-07-06, INDEPENDENTLY killable) ──────────────────────────────
        // On chop tape, relax the 10-min dedupe to a short min-gap + a per-day cap and require the fade side
        // to be drift-aligned. TREND tape is byte-identical: chopFadeActive=false there (isChopRegime=false),
        // so fadeBindingLive falls through to the exact legacy expression. Killing fade-mode.enabled also
        // reverts to legacy behaviour even in chop. The tight target/stop bracket (managePosition) keys on
        // the CHOP_FADE tag below, so only these fades get it — legacy fades are untouched.
        // ── V5 MEMORY_AVALANCHE branch (docs/MARKET-MEMORY-V5-DESIGN.md §3) — checked BEFORE fade:
        // the validated squeeze-harvester (+₹105k/3d replay). Scans ATM±span strikes for the AVALANCHE
        // state (panic writer-covering, price turning off the low); deepest dOI5m wins; CE→long-CE /
        // PE→long-PE. All risk gates + the EntryPipeline veto rail apply inside enter().
        if (tryAvalancheEntry(indexType, state)) {
            return;
        }

        boolean chopFadeActive = fadeModeEnabled && fadeDecision.fires() && isChopRegime(indexType);
        boolean chopFade = false;
        boolean fadeBindingLive;
        if (chopFadeActive) {
            boolean fadeCapReached = state.fadesToday.get() >= fadeMaxPerDay;
            boolean fadeGapBlocked = state.lastFadeEntryTime != null
                    && Duration.between(state.lastFadeEntryTime, Instant.now()).getSeconds() < fadeMinGapSec;
            boolean fadeDriftAligned = fadeDriftAligned(indexType, fadeDecision.direction());
            fadeBindingLive = !fadeCapReached && !fadeGapBlocked && fadeDriftAligned;
            chopFade = fadeBindingLive;
            if (!fadeBindingLive) {
                log.info("[OIMomentum][{}] CHOP_FADE gated — capReached={}({}/{}) gapBlocked={} driftAligned={}",
                        indexType, fadeCapReached, state.fadesToday.get(), fadeMaxPerDay,
                        fadeGapBlocked, fadeDriftAligned);
            }
        } else {
            fadeBindingLive = fadeDecision.fires() && !fadeDedupeBlocked;
        }
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
                long[] fadeOi = oiChangeWindowed(indexType, fadeAtm, 3);
                double fadePcr = liveInstrumentCache.getRealtimePcr(indexType);
                int fadePcrDir = pcrDir(fadePcr);
                int fadeOiDir = oiDir(indexType, fadeOi[0], fadeOi[1]);
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
                        "RANGE_EDGE_FADE%s dir=%d pos=%.3f range30m=%.3f%% pcr=%.2f theta=%.2f%%",
                        chopFade ? "[CHOP_FADE]" : "",
                        fadeDecision.direction(), fadeDecision.positionInRange(),
                        fadeDecision.range30mPct(), fadeDecision.pcr(),
                        fadeDecision.thetaCostPct());
                // Only burn the per-day cap / arm the min-gap timer AFTER a confirmed entry. A fade that is
                // vetoed or blocked by a downstream gate (V3, charges, spread, capital, kill-switch, …) must
                // not consume the 6/day budget or arm the gap-timer, or a single blocked fade would starve
                // the rest of the day. A confirmed entry sets activeTradeId (filled) or pendingEntryInstrumentKey
                // (order accepted, awaiting fill); measure the transition so a pre-existing position never
                // false-counts.
                boolean fadeHadPosition = state.activeTradeId != null || state.pendingEntryInstrumentKey != null;
                enter(indexType, state, fadeDecision.direction(), fadeReason, fadeSpot, fadeDiag);
                boolean fadeEntered = !fadeHadPosition
                        && (state.activeTradeId != null || state.pendingEntryInstrumentKey != null);
                if (fadeEntered) {
                    state.lastFadeEntryTime = Instant.now();
                    if (chopFade) state.fadesToday.incrementAndGet();
                }
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
                    long[] oi = oiChangeWindowed(indexType, atm, 3);
                    double pcr = liveInstrumentCache.getRealtimePcr(indexType);
                    int pcrDir = pcrDir(pcr);
                    int oiDir = oiDir(indexType, oi[0], oi[1]);
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
                    long[] oi = oiChangeWindowed(indexType, atm, 3);
                    double pcr = liveInstrumentCache.getRealtimePcr(indexType);
                    int pcrDir = pcrDir(pcr);
                    int oiDir = oiDir(indexType, oi[0], oi[1]);
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

        // ── OI Velocity Early Detection: LIVE alternate momentum source ──
        // When no spot momentum AND no gamma regime, check if the early detector has a
        // fresh signal (OI building directionally before price prints it). This is the
        // "detect the move before it happens" channel — 56% win rate on 0.5–2.0% band.
        if (!momentum.isPresent() && oiVelocityEarlyDetector != null) {
            var earlySignal = oiVelocityEarlyDetector.getLatest(indexType);
            if (earlySignal != null && earlySignal.direction() != 0) {
                // Only use if the signal is fresh (< 30 seconds old)
                long ageMs = System.currentTimeMillis() - earlySignal.at().toEpochMilli();
                if (ageMs < 30_000 && earlySignal.oiVelocityPct() >= 0.5
                        && earlySignal.oiVelocityPct() <= 2.0) { // sweet-spot band from backtest
                    if (chopSuppressChase) {
                        // Chop chase-OFF: do NOT synthesise the EARLY_OI_VELOCITY momentum signal on chop
                        // tape. momentum stays not-present so this chase trigger cannot open an entry.
                        log.info("[OIMomentum][{}] EARLY_OI_VELOCITY entry suppressed — chop regime (chase-off): "
                                        + "dir={} oiVel={}%", indexType, earlySignal.direction(),
                                String.format("%.2f", earlySignal.oiVelocityPct()));
                    } else {
                        momentum = new TickMomentumDetector.MomentumSignal(
                                earlySignal.direction(), "EARLY_OI_VELOCITY",
                                earlySignal.oiVelocityPct(), spot0);
                        log.info("[OIMomentum][{}] EARLY_OI_VELOCITY: dir={} oiVel={}% coil={}% (before price) — " +
                                        "using OI velocity as momentum signal",
                                indexType, earlySignal.direction(),
                                String.format("%.2f", earlySignal.oiVelocityPct()),
                                String.format("%.3f", earlySignal.coilRangePct()));
                    }
                }
            }
        }

        // ── OI Level Bounce: support/resistance entries on range-bound days ──
        // When no momentum AND no early OI velocity, check if price is bouncing off a
        // max-OI support/resistance level. This captures mean-reversion entries that
        // breakout-only logic misses (e.g., Nifty bouncing off 23,900 put OI support).
        if (!momentum.isPresent() && oiLevelBounceDetector != null && oiLevelBounceDetector.isEnabled()) {
            var bounceSignal = oiLevelBounceDetector.getSignal(indexType);
            if (bounceSignal.isActive()) {
                momentum = new TickMomentumDetector.MomentumSignal(
                        bounceSignal.direction(), "LEVEL_BOUNCE:" + bounceSignal.type(),
                        bounceSignal.levelOiPct(), spot0);
                log.info("[OIMomentum][{}] LEVEL_BOUNCE: {} at strike={} (OI={}% of total, dist={}%) — " +
                                "using as momentum signal",
                        indexType, bounceSignal.type(), bounceSignal.levelStrike(),
                        String.format("%.1f", bounceSignal.levelOiPct()),
                        String.format("%.2f", bounceSignal.distancePct()));
            }
        }

        // ── Operator-led momentum fallback: OI conviction overrides flat spot ──
        // Hysteresis: fires at score ≥ 75 (strong conviction), resets at ≤ 65.
        // Prevents whipsaw entries when score oscillates around 70.
        if (!momentum.isPresent() && operatorFrameworkService != null) {
            var opSignal = operatorFrameworkService.getOperatorSignal(indexType);
            if (opSignal != null && opSignal.getDirection() != 0) {
                int score = opSignal.getScore();
                boolean wasActive = state.operatorLedActive;
                // Hysteresis: activate at 75, deactivate only below 65
                if (score >= 75) state.operatorLedActive = true;
                else if (score < 65) state.operatorLedActive = false;

                if (state.operatorLedActive) {
                    momentum = new TickMomentumDetector.MomentumSignal(
                            opSignal.getDirection(), "OPERATOR_OI_LED",
                            score / 10.0, spot0);
                    if (!wasActive) {
                        log.info("[OIMomentum][{}] OPERATOR_OI_LED activated: opScore={} dir={} — "
                                        + "spot flat but OI conviction strong, using as momentum signal",
                                indexType, score, opSignal.getDirection());
                    }
                }
            }
        }

        // ── OI Surge Detector: catch early operator footprints via velocity spike ──
        // Fires when OI at ATM±3 is building at 2× the rolling average rate — signals
        // institutional entry BEFORE price reacts. Additive path; does not disturb
        // existing OPERATOR_OI_LED or CASE 0 logic.
        if (!momentum.isPresent() && oiSurgeDetector != null) {
            OiSurgeDetector.SurgeSignal surge = oiSurgeDetector.detect(indexType);
            if (surge.isPresent()) {
                momentum = new TickMomentumDetector.MomentumSignal(
                        surge.direction(), "OI_SURGE:" + surge.reason(),
                        surge.surgeRatio(), spot0);
                log.info("[OIMomentum][{}] OI_SURGE: {} ratio={}x delta={} — early operator footprint",
                        indexType, surge.reason(),
                        String.format("%.1f", surge.surgeRatio()), surge.oiDelta());
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

        // ── Module performance pause check — skip if this signal source is underperforming today ──
        if (modulePerformanceTracker != null && modulePerformanceTracker.isPaused(momentum.type())) {
            state.lastRejectReason = "module_paused:" + momentum.type();
            recordReject(indexType, state, state.lastRejectReason,
                    buildThrottleDiagnostics(indexType, state, state.lastRejectReason));
            return;
        }

        // ── OI Analysis (only when OI has actually advanced — P0 #1) ──
        double spot = momentum.spotPrice();
        int atm = indexType.roundToATM(spot);
        long ceOiChange = 0;
        long peOiChange = 0;
        boolean oiAvailable = false;
        int oiDirection = 0;

        if (state.oiAdvanced) {
            long[] oiChange = oiChangeWindowed(indexType, atm, 3);
            ceOiChange = oiChange[0];
            peOiChange = oiChange[1];
            oiAvailable = (ceOiChange != 0 || peOiChange != 0);
            // DYNAMIC OI-shift: feed the per-index adaptive baseline with this tick's 60s band velocity
            // (throttled to ~1 sample/window inside record()). Only when the OI path is genuinely live.
            if (oiAvailable && dynamicOiFloor != null) {
                dynamicOiFloor.record(indexType, ceOiChange, peOiChange);
            }
            if (oiAvailable) {
                oiDirection = oiDir(indexType, ceOiChange, peOiChange); // includes buildup AND squeeze detection
                state.lastKnownOiDir = oiDirection; // cache for fallback
            }
        }
        // OI unavailable fallback: if live OI deltas are zero but we had a recent
        // directional reading (within biasDecaySeconds), use the cached direction.
        // Prevents CASE5_OI_UNAVAILABLE skips due to transient zero-delta ticks
        // (e.g., opening minutes when OI flows are intermittent).
        if (!oiAvailable && state.lastKnownOiDir != 0 && state.lastOiTickTime != null) {
            long staleSecs = Duration.between(state.lastOiTickTime, Instant.now()).getSeconds();
            // DATA-2 (2026-06-20): when the opening-window gate is on, only trust the cached
            // direction if the ATM band currently has a real OI baseline. During warm-up
            // (no baseline) a single fluky early reading could otherwise re-mark OI
            // "available" and route a CASE1/CASE3 OI-confirmed entry on data that doesn't
            // exist yet. Flag default OFF → unchanged behavior.
            // FAST-OI on → require a baseline ≥1 min old (matches the 60s window); off → legacy 3 min.
            boolean baselineOk = !config.isOiAvailabilityGateEnabled()
                    || liveInstrumentCache.isAtmOiChangeAvailable(indexType, atm, 3, fastOiEnabled ? 1 : 3);
            if (staleSecs <= config.getBiasDecaySeconds() && baselineOk) {
                oiDirection = state.lastKnownOiDir;
                oiAvailable = true; // treat cached OI as available for matrix evaluation
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
            // The per-user SIGNAL-SCORE cap (risk profile) is a HARD floor — OI's own threshold may be
            // stricter but must NEVER drop below the user's configured min signal score (was using only the
            // hardcoded OIMomentumConfig threshold, ignoring the per-user profile).
            int perUserSignalFloor = globalConfigService != null
                    ? (int) Math.round(globalConfigService.getMinSignalScorePercent().doubleValue())
                    : config.getBiasConfidenceThreshold();
            int baseFloor = config.isBiasFloorRelaxEnabled()
                    ? config.getBiasFloorDefault() : config.getBiasConfidenceThreshold();
            int defaultFloor = Math.max(baseFloor, perUserSignalFloor);
            int effectiveFloor = defaultFloor;
            boolean floorRelaxed = false;
            double slope5m = (v3MarketContext != null)
                    ? v3MarketContext.pcrSlope5Min(indexType) : 0.0;

            // ── Opening Drive floor relaxation: lower floor to 35 when operator has
            // modest conviction (≥ 25) and we're in the first 15 min of the session.
            // May 29 logs: bias=30-38 skipped despite PCR alignment because operator
            // was only 18-27 and the floor was 40. With operator ≥ 25, allow 35.
            LocalTime nowForFloor = LocalTime.now(IST);
            if (nowForFloor.isAfter(LocalTime.of(9, 15)) && nowForFloor.isBefore(LocalTime.of(9, 30))) {
                int opScore = (operatorFrameworkService != null)
                        ? operatorFrameworkService.getConfidenceBonus(indexType, momentum.direction())
                        : 0;
                // getConfidenceBonus returns 0 when score < 45 or misaligned;
                // opScore > 0 means operator has at least some directional conviction
                if (opScore > 0 || (operatorFrameworkService != null
                        && operatorFrameworkService.getOperatorSignal(indexType) != null
                        && operatorFrameworkService.getOperatorSignal(indexType).getScore() >= 25)) {
                    effectiveFloor = Math.min(effectiveFloor, 35);
                    floorRelaxed = true;
                    log.debug("[OIMomentum][{}] OPENING_DRIVE floor relaxed to 35 (operator score >= 25)",
                            indexType);
                }
            }

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
            // ── Dynamic gates (LIVE): regime-conditioned bias-floor nudge ──
            // Applied as a DELTA from the engine's NORMAL baseline so the NORMAL regime leaves the floor
            // unchanged; LOW_VOL tightens (fewer entries in chop), HIGH_VOL/EXTREME loosen (catch real
            // moves). Bounded so it can never collapse or spike the floor. Inactive engine ⇒ no change.
            if (dynamicGateEngine != null && dynamicGateEngine.isActive()) {
                var dyn = dynamicGateEngine.getValues(indexType);
                int floorDelta = (int) Math.round(dyn.signalScoreMin() - DynamicGateEngine.NEUTRAL_SIGNAL_SCORE);
                if (floorDelta != 0) {
                    int adjusted = Math.max(25, Math.min(75, effectiveFloor + floorDelta));
                    if (adjusted != effectiveFloor) {
                        log.debug("[OIMomentum][{}] DYNAMIC_FLOOR {}→{} (regime={}, delta={})",
                                indexType, effectiveFloor, adjusted, dyn.regime(), floorDelta);
                        effectiveFloor = adjusted;
                        floorRelaxed = floorRelaxed || floorDelta < 0;
                    }
                }
                // ── Outcome-feedback: additional floor delta from realized trade expectancy ──
                // Guarded by sample size + rate limits inside OutcomeFeedbackEngine.
                if (outcomeFeedbackEngine != null && outcomeFeedbackEngine.isEnabled()) {
                    double ofDelta = outcomeFeedbackEngine.getSignalScoreDelta(dyn.regime());
                    if (ofDelta != 0) {
                        int ofAdjusted = Math.max(25, Math.min(75, effectiveFloor + (int) Math.round(ofDelta)));
                        if (ofAdjusted != effectiveFloor) {
                            log.debug("[OIMomentum][{}] OUTCOME_FLOOR {}→{} (regime={}, ofDelta={})",
                                    indexType, effectiveFloor, ofAdjusted, dyn.regime(), String.format("%.1f", ofDelta));
                            effectiveFloor = ofAdjusted;
                            floorRelaxed = floorRelaxed || ofDelta < 0;
                        }
                    }
                }
            }

            // Stamp slope + floor onto the diagnostics so the tune CSV
            // can quantify which fires used the relaxed path.
            diag = diag.withPcrSlope(slope5m).withBiasFloor(floorRelaxed, effectiveFloor);

            // ── Decision Aggregator: normalized ignition feature bonus ──
            // Adds direction-weighted bias points from z-scored OI/premium/microstructure features.
            // Only contributes when features are warmed up (≥20 observations per regime).
            int effectiveBiasScore = (int) bias.score();
            // Per-detector bonus trackers — stamped into the entry reason so a week of data can attribute
            // outcomes to EACH feature (aggregator vs reversal vs ladder vs gamma), not just the net boost.
            int aggBonusApplied = 0, revBonusApplied = 0, ladderBonusApplied = 0, gammaBonusApplied = 0;

            // ── VWAP counter-trend penalty (2026-07-03) ──────────────────────────────────────
            // If the option premium is trading BELOW VWAP (for a bullish entry) or ABOVE VWAP (for a
            // bearish entry), that's a counter-trend signal — reduce the effective bias score. Not a
            // hard block (strong signals still pass), but makes marginal signals fall below the floor.
            int vwapPenalty = 0;
            if (v3MarketContext != null) {
                double spotForVwap = liveInstrumentCache.getFuturesPrice(indexType);
                if (spotForVwap > 0) {
                    int vwapAlign = v3MarketContext.spotVsVwap(indexType, spotForVwap);
                    // vwapAlign: +1 = above VWAP (bullish), -1 = below VWAP (bearish), 0 = neutral
                    if (vwapAlign != 0 && vwapAlign != momentum.direction()) {
                        // Opposing VWAP — penalize. More severe in LOW_VOL (range-bound = VWAP matters more).
                        String regime = (dynamicGateEngine != null && dynamicGateEngine.isActive())
                                ? dynamicGateEngine.getValues(indexType).regime() : "NEUTRAL";
                        vwapPenalty = "LOW_VOL".equals(regime) ? 15 : 10;
                        effectiveBiasScore -= vwapPenalty;
                        log.debug("[OIMomentum][{}] VWAP_PENALTY −{} (spot {} VWAP, dir={}, regime={}, bias→{})",
                                indexType, vwapPenalty, vwapAlign < 0 ? "below" : "above",
                                momentum.direction(), regime, effectiveBiasScore);
                    }
                }
            }

            // ── Multi-Timeframe Context: align the fresh OI signal with day/week/month structure ──
            // Intraday-weighted. An ignition that agrees with the higher-timeframe lean earns a small
            // bias bonus; one fighting a counter-trend backdrop is penalised. Config-gated and fully
            // stamped into diagnostics so a week of live data can validate whether MTF alignment
            // actually predicts edge before we lean on it harder.
            int mtfBiasDir = 0, mtfAlignedFlag = 0, mtfBonusApplied = 0;
            boolean mtfAvailable = false;
            String mtfRegimeTag = "NEUTRAL";
            if (mtfContextService != null && mtfLiveEnabled) {
                try {
                    var mtf = mtfContextService.getContext(indexType);
                    if (mtf != null && mtf.available()) {
                        mtfAvailable = true;
                        mtfBiasDir = mtf.htfBias();
                        mtfRegimeTag = mtf.regime();
                        int dir = momentum.direction();
                        boolean aligned = mtf.alignsWith(dir);
                        boolean counter = mtf.isCounterTrend(dir);
                        mtfAlignedFlag = aligned ? 1 : (counter ? -1 : 0);
                        if (aligned) {
                            effectiveBiasScore += mtfAlignBonus;
                            mtfBonusApplied = mtfAlignBonus;
                        } else if (counter) {
                            effectiveBiasScore -= mtfCounterPenalty;
                            mtfBonusApplied = -mtfCounterPenalty;
                        }
                        if (mtfBonusApplied != 0) {
                            log.debug("[OIMomentum][{}] MTF {}{} (bias {}→{}, htfBias={}, regime={}, dir={})",
                                    indexType, mtfBonusApplied > 0 ? "+" : "", mtfBonusApplied,
                                    (int) bias.score(), effectiveBiasScore, mtfBiasDir, mtfRegimeTag, dir);
                        }
                    }
                } catch (Exception e) {
                    log.debug("[OIMomentum][{}] MTF context unavailable: {}", indexType, e.toString());
                }
            }
            diag = diag.withMtf(mtfBiasDir, mtfRegimeTag, mtfAlignedFlag);

            if (decisionAggregator != null && dynamicGateEngine != null && dynamicGateEngine.isActive()) {
                String regime = dynamicGateEngine.getValues(indexType).regime();
                double oiDeltaPct = state.lastOiDeltaPct; // cached from OI snapshot eval
                double spreadChange = state.lastSpreadChange; // cached from microstructure
                var aggSignal = decisionAggregator.compute(indexType, regime, oiDeltaPct, slope5m, spreadChange);
                if (aggSignal.hasSignal()) {
                    // Only add if direction matches momentum (don't contradict main signal)
                    boolean sameDirection = (aggSignal.directionBias() > 0 && momentum.direction() > 0)
                            || (aggSignal.directionBias() < 0 && momentum.direction() < 0);
                    if (sameDirection) {
                        int bonus = (int) Math.round(Math.abs(aggSignal.directionBias()) * aggSignal.confidence());
                        bonus = Math.min(15, bonus); // cap at +15 points
                        effectiveBiasScore += bonus;
                        aggBonusApplied = bonus;
                        if (bonus >= 3) {
                            log.debug("[OIMomentum][{}] AGG_BONUS +{} (bias {}→{}, conf={}%, regime={})",
                                    indexType, bonus, bias.score(), effectiveBiasScore,
                                    (int)(aggSignal.confidence() * 100), regime);
                        }
                    }
                }
            }

            // ── Operator Reversal Detector: boost bias when reversal footprints confirm ──
            // If the reversal detector has an active signal in the same direction as momentum,
            // add a moderate bias boost. On expiry days, reversal signals are stronger (June 23
            // analysis showed expiry reversals as the strongest edge) → higher cap.
            if (operatorReversalDetector != null && operatorReversalDetector.isEnabled()) {
                var revSignal = operatorReversalDetector.getSignal(indexType);
                if (revSignal.isActive() && revSignal.direction() == momentum.direction()) {
                    boolean isExpiry = expiryCalendar.isExpiryDay(indexType);
                    int maxRevBonus = isExpiry ? 15 : 12; // expiry-day priority: higher cap
                    int revBonus = (int) Math.round(maxRevBonus * revSignal.confidence());
                    revBonus = Math.min(maxRevBonus, revBonus);
                    effectiveBiasScore += revBonus;
                    revBonusApplied = revBonus;
                    log.debug("[OIMomentum][{}] REVERSAL_BOOST +{} (footprints={}/5, expiry={}, bias→{})",
                            indexType, revBonus, revSignal.footprintsConfirmed(), isExpiry, effectiveBiasScore);
                }
            }

            // ── OI Ladder Detector: boost bias when staircase OI build confirms direction ──
            if (oiLadderDetector != null && oiLadderDetector.isEnabled()) {
                var ladderSignal = oiLadderDetector.getSignal(indexType);
                if (ladderSignal.isActive() && ladderSignal.direction() == momentum.direction()) {
                    int ladderBonus = (int) Math.round(10 * ladderSignal.confidence());
                    ladderBonus = Math.min(10, ladderBonus);
                    effectiveBiasScore += ladderBonus;
                    ladderBonusApplied = ladderBonus;
                    log.debug("[OIMomentum][{}] LADDER_BOOST +{} (steps={}, bias→{})",
                            indexType, ladderBonus, ladderSignal.steps(), effectiveBiasScore);
                }
            }

            // ── Gamma Scalp Detector: boost on expiry-day gamma bursts ──
            if (gammaScalpDetector != null && gammaScalpDetector.isEnabled()) {
                var gammaSignal = gammaScalpDetector.getSignal(indexType);
                if (gammaSignal.isActive() && gammaSignal.direction() == momentum.direction()) {
                    int gammaBonus = (int) Math.round(8 * gammaSignal.confidence());
                    gammaBonus = Math.min(8, gammaBonus);
                    effectiveBiasScore += gammaBonus;
                    gammaBonusApplied = gammaBonus;
                    log.debug("[OIMomentum][{}] GAMMA_SCALP_BOOST +{} (bursts={}, amp={}x, bias→{})",
                            indexType, gammaBonus, gammaSignal.burstCount(),
                            String.format("%.1f", gammaSignal.amplification()), effectiveBiasScore);
                }
            }

            // ── Neutral-MTF floor lift: require stronger conviction without structure alignment ──
            // When the higher timeframe is AVAILABLE but doesn't confirm the direction (neutral), raise
            // the effective floor by mtfNeutralFloorLift so only high-conviction entries pass. When MTF
            // aligns, the regular floor applies (+ the align bonus). Gated on mtfAvailable so an MTF
            // outage does NOT silently tighten every entry. Config-gated, default +10 (floor 50→60);
            // clamped to 75 to match the DynamicGateEngine ceiling and avoid runaway over-rejection.
            if (mtfNeutralFloorLiftEnabled && mtfAvailable && mtfAlignedFlag == 0) {
                effectiveFloor = Math.min(75, effectiveFloor + mtfNeutralFloorLift);
                // Re-stamp so the tuning loop records the ACTUAL floor used (the earlier withBiasFloor
                // capture predated this lift), keeping the MTF validation data honest.
                diag = diag.withBiasFloor(floorRelaxed, effectiveFloor);
            }

            if (effectiveBiasScore >= effectiveFloor) {
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

                // ── LOW_VOL regime tightening (2026-07-03) ────────────────────────────────────
                // In a tight/choppy range (LOW_VOL), a single tick of alignment is noise more often than
                // signal. The reEntryBoost was designed for trending markets where a profitable exit proves
                // direction is still live — in LOW_VOL, that assumption fails (the trend doesn't persist).
                // (a) Force minimum 2 ticks even with reEntryBoost active.
                // (b) Require at least 1 booster (aggregator/reversal/ladder/gamma) to have fired — entries
                //     with zero boosts in LOW_VOL historically have lower win rates since the ONLY evidence
                //     is the base bias score, which is easily fooled by range noise.
                String currentRegime = (dynamicGateEngine != null && dynamicGateEngine.isActive())
                        ? dynamicGateEngine.getValues(indexType).regime() : "NEUTRAL";
                if ("LOW_VOL".equals(currentRegime)) {
                    // (a) Minimum 2 confirmation ticks in LOW_VOL regardless of reEntryBoost
                    if (requiredTicks < 2) {
                        requiredTicks = 2;
                    }
                    // (b) Require at least 1 boost in LOW_VOL — zero-boost entries are too low-conviction
                    int totalBoosts = aggBonusApplied + revBonusApplied + ladderBonusApplied + gammaBonusApplied;
                    if (totalBoosts == 0 && state.confirmationCount >= requiredTicks) {
                        // Would have entered, but zero boosts in LOW_VOL → block
                        log.info("[OIMomentum][{}] LOW_VOL_NO_BOOST: entry blocked — zero boosts (agg/rev/lad/gam all 0) "
                                + "in LOW_VOL regime. bias={} eff={} ticks={}/{}",
                                indexType, bias.score(), effectiveBiasScore, state.confirmationCount, requiredTicks);
                        rejectedCount.incrementAndGet();
                        state.lastRejectReason = "low_vol_no_boost";
                        state.confirmationCount = 0;
                        state.lastConfirmedDir = 0;
                        return;
                    }
                }
                // (c) OI-building-against-direction block: if the V5 memory engine shows OI RISING
                // (writers pressing) on the exact strike we'd enter, block the entry. Evidence: the
                // 07-09 NIFTY 24000 CE entry at dOI5m=+1.53% and NIFTY 24050 PE at dOI5m=+1.04% both
                // lost because writers were actively building against the position. This fires
                // regardless of regime — OI building against you is bad in any market.
                if (marketMemoryEngine != null && marketMemoryEngine.isEnabled()) {
                    int entryStrike = indexType.roundToATM(liveInstrumentCache.getFuturesPrice(indexType));
                    String entryType = momentum.direction() > 0 ? "CE" : "PE";
                    var entryMem = marketMemoryEngine.get(indexType, entryStrike, entryType);
                    if (entryMem != null && !entryMem.warming() && entryMem.dOi5mPct() > 0.5
                            && entryMem.tier() == com.algo.trade.marketdata.MarketMemoryEngine.Tier.WEAK) {
                        // OI is building on the entry strike (>+0.5%) AND the signal is only WEAK tier
                        // = writers pressing against a low-conviction entry → block
                        log.info("[OIMomentum][{}] OI_AGAINST_ENTRY: blocked — OI building +{}% on {} {} (tier=WEAK, writers pressing against entry)",
                                indexType, String.format("%.1f", entryMem.dOi5mPct()), entryStrike, entryType);
                        rejectedCount.incrementAndGet();
                        state.lastRejectReason = "oi_against_entry";
                        state.confirmationCount = 0;
                        state.lastConfirmedDir = 0;
                        return;
                    }
                }
                // NOTE: OperatorReversalDetector confirmation-tick reduction DISABLED until
                // shadow validation proves positive expectancy. The +12 bias boost is sufficient
                // to get borderline signals over the floor; removing the confirmation delay
                // on top of that was flagged as too aggressive on unproven signals.

                log.debug("[OIMomentum][{}] Bias OK: score={} ticks={}/{} case={} reEntryBoost={} signals={}",
                        indexType, bias.score(), state.confirmationCount,
                        requiredTicks, entryCase, reEntryBoost, bias.primarySignal());

                if (state.confirmationCount >= requiredTicks) {
                    // Full confirmation — enter
                    state.confirmationCount = 0;
                    state.lastConfirmedDir = 0;
                    String operatorTag = formatOperatorEntryTag(entryCase, eval.blockDetail());
                    // Measurement loopback: stamp the regime + the dynamic-layer effect into the entry reason.
                    // (a) regime=X lets OutcomeFeedbackEngine bucket this trade correctly (it parses the reason;
                    //     without a tag every trade fell into the NORMAL bucket). (b) eff/floor/boost make the
                    //     dynamic floor + aggregator/reversal bonuses measurable per trade in the report &
                    //     decision-record, so a week of data can attribute outcomes to each feature.
                    String dynRegime = (dynamicGateEngine != null && dynamicGateEngine.isActive())
                            ? dynamicGateEngine.getValues(indexType).regime() : "NEUTRAL";
                    int biasBoost = effectiveBiasScore - (int) bias.score();
                    // Per-detector boost breakdown → attribute outcomes to each feature after a week.
                    String boostBreakdown = String.format("boosts=[agg:%d,rev:%d,lad:%d,gam:%d]%s",
                            aggBonusApplied, revBonusApplied, ladderBonusApplied, gammaBonusApplied,
                            state.lastEntrySlOverride ? " slOverride=1" : "");
                    String reason = String.format(
                            "M:%s OI:%d PCR:%.2f(%d) case=%s bias=%.0f eff=%d floor=%d regime=%s boost=%d %s ticks=%d%s",
                            momentum.type(), oiDirection, pcr, pcrDirection, entryCase,
                            bias.score(), effectiveBiasScore, effectiveFloor, dynRegime, biasBoost,
                            boostBreakdown, config.getBiasConfirmationTicks(), operatorTag);
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
        // MEMORY_AVALANCHE (V5 §8): fixed 1 lot until the out-of-sample scoring promotes sizing.
        if (reason.startsWith("SPIKE:") || reason.startsWith("REVERSE") || reason.startsWith("MEMORY_AVALANCHE")) {
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

        // FAST-OI: overlay the fast-OI capture context onto every evaluation/reject row so the tuning
        // loop can segment blocked entries by regime + operator freshness too (not just fills).
        if (partial != null) {
            partial = partial.withFastOi(fastOiEnabled, captureOiWindowSec(), operatorSignalAgeSec(indexType));
            // MTF: most rejects short-circuit BEFORE the entry bias-assembly overlay, so their diag would
            // carry default NEUTRAL context. Stamp the live multi-timeframe context here too — the tuning
            // loop can then segment BLOCKED entries by day/week structure, not just fills. Alignment uses
            // the captured momentum direction.
            if (mtfContextService != null && mtfLiveEnabled) {
                try {
                    var mtf = mtfContextService.getContext(indexType);
                    if (mtf != null && mtf.available()) {
                        int dir = partial.momentumDir();
                        int aligned = mtf.alignsWith(dir) ? 1 : (mtf.isCounterTrend(dir) ? -1 : 0);
                        partial = partial.withMtf(mtf.htfBias(), mtf.regime(), aligned);
                    }
                } catch (Exception ignore) { /* non-fatal: capture only */ }
            }
        }

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
        long[] oi = atm > 0 ? oiChangeWindowed(indexType, atm, 3) : new long[]{0, 0};
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

    /**
     * Leading chop-regime classifier (2026-07-06). chopRegime = (0 < VIX < vix-max) AND
     * (0 < range30m < range30m-max). Returns false when the feature is disabled, when VIX is unavailable
     * (&le;0), or when the 30-min range is unavailable (&le;0) — i.e. it fails toward TREND behaviour so a
     * missing/stale input can never spuriously activate the chop paths. When this returns false EVERY
     * chop-conditional path (chase-off, exit-grace, fade-mode) is inert and trend-day behaviour is
     * byte-identical. See docs/CHOP-REGIME-FADE-FIX.md.
     */
    private boolean isChopRegime(IndexType indexType) {
        if (!chopRegimeEnabled) return false;
        double vix = marketGuard.getCurrentVix();
        if (vix <= 0 || vix >= chopVixMax) return false;
        double range30m = computeRangePct30m(indexType);
        return range30m > 0 && range30m < chopRange30mMax;
    }

    // ── Operator-move signal helpers (2026-07-07, signal-driven — NO clock) ──────────────────────

    /** Fresh operator-framework score (0 if unavailable/stale). */
    private double operatorScoreOf(IndexType indexType) {
        if (operatorFrameworkService == null) return 0;
        var s = operatorFrameworkService.getOperatorSignal(indexType);
        return (s != null && s.isFresh()) ? s.getScore() : 0;
    }

    /** Fresh (&lt;30s) OI-velocity % from the early detector (0 if none/stale/flat). */
    private double oiVelPctOf(IndexType indexType) {
        if (oiVelocityEarlyDetector == null) return 0;
        var e = oiVelocityEarlyDetector.getLatest(indexType);
        if (e == null || e.direction() == 0 || e.at() == null) return 0;
        long ageMs = System.currentTimeMillis() - e.at().toEpochMilli();
        return ageMs < 30_000 ? e.oiVelocityPct() : 0;
    }

    /** High-conviction predicate (item 3/4): operator score AND oiVel both clear their bars. */
    private boolean highConvictionActive(IndexType indexType) {
        return operatorScoreOf(indexType) >= itmOpScoreMin && oiVelPctOf(indexType) >= itmOiVelMin;
    }

    /** True while the latest fresh operator signal is a capitulation-flip. */
    private boolean capitulationFlipActive(IndexType indexType) {
        if (operatorFrameworkService == null) return false;
        var s = operatorFrameworkService.getOperatorSignal(indexType);
        return s != null && s.isFresh() && s.isCapitulationFlip();
    }

    /**
     * SIGNAL-DRIVEN operator-move mode — true whenever the background data shows a strong operator move
     * (high conviction OR a capitulation-flip), regardless of the clock. Gates the OTM-cutoff bypass (2a),
     * chase-off bypass (2b) and ITM strike (3). Inert (false) when the master flag is off or no move is
     * detected → every dependent path reverts to today's behaviour.
     */
    private boolean isOperatorMoveActive(IndexType indexType) {
        if (!operatorMoveModeEnabled) return false;
        return highConvictionActive(indexType) || capitulationFlipActive(indexType);
    }

    /**
     * Entry cutoff time (IST) for new entries. Expiry day → the dedicated {@code expiryEntryCutoffTime}
     * (15:15), so late operator moves aren't blocked; non-expiry → the legacy squareoff − 10 min. The
     * square-off EXIT timing and the hard 15:20 force square-off are unaffected. Falls back to the legacy
     * squareoff − 5 min on expiry if the configured value can't be parsed.
     */
    private LocalTime entryCutoffTime(IndexType indexType) {
        LocalTime soff = LocalTime.of(config.getSquareoffHour(), config.getSquareoffMinute());
        if (expiryCalendar.isExpiryDay(indexType)) {
            try {
                return LocalTime.parse(config.getExpiryEntryCutoffTime());
            } catch (Exception ex) {
                return soff.minusMinutes(5);
            }
        }
        return soff.minusMinutes(10);
    }

    /**
     * Fade drift-alignment gate (chop fade-mode). Allows a chop fade only when the signed 60-min drift is
     * neutral (|drift| &lt; deadband — typical of genuine chop, so either edge may be faded) OR points the
     * SAME way as the fade side. Blocks fading directly against a clear drift (a falling-knife fade).
     * Fails OPEN (returns true) when the detector is absent or hiccups — the per-day cap, min-gap and all
     * risk gates still bound the fade, so a detector outage can't disable fade-mode outright.
     */
    private boolean fadeDriftAligned(IndexType indexType, int fadeDir) {
        if (sustainedDriftDetector == null) return true;
        double driftPct;
        try {
            driftPct = sustainedDriftDetector.evaluate(indexType, config).driftPct();
        } catch (Exception ex) {
            return true;
        }
        if (Math.abs(driftPct) < fadeDriftDeadbandPct) return true; // neutral chop → fade either edge
        int driftDir = driftPct > 0 ? 1 : -1;
        return driftDir == fadeDir;
    }

    /**
     * Extract the signal module name from a trade's entry reason.
     * Examples: "OPERATOR_SQUEEZE dir=+1..." → "OPERATOR_SQUEEZE"
     *           "OI_MOMENTUM[NIFTY]: CASE3..." → "CASE3"
     *           "SPIKE:EVENT_SPIKE..." → "EVENT_SPIKE"
     */
    private static String extractModuleFromReason(String reason) {
        if (reason == null || reason.isBlank()) return "UNKNOWN";
        // Strip prefix
        if (reason.startsWith("OI_MOMENTUM[")) {
            int close = reason.indexOf(']');
            reason = close > 0 && close + 2 < reason.length() ? reason.substring(close + 2).trim() : reason;
        }
        // Common patterns
        if (reason.contains("OPERATOR_SQUEEZE")) return "OPERATOR_SQUEEZE";
        if (reason.contains("SUSTAINED_DRIFT")) return "SUSTAINED_DRIFT";
        if (reason.contains("RANGE_EDGE_FADE")) return "RANGE_EDGE_FADE";
        if (reason.contains("CASE0_OI_LED")) return "CASE0_OI_LED";
        if (reason.startsWith("SPIKE:")) return reason.substring(6).split(" ")[0];
        // Extract CASE from matrix labels
        if (reason.contains("CASE1")) return "CASE1";
        if (reason.contains("CASE2")) return "CASE2";
        if (reason.contains("CASE3")) return "CASE3";
        // Momentum type from the signal
        for (String tag : new String[]{"OPERATOR_OI_LED", "OI_SURGE", "PREMIUM_VELOCITY",
                "30M_HIGH_BREAK", "30M_LOW_BREAK", "LARGE_MOVE", "15M_HIGH_BREAK", "15M_LOW_BREAK"}) {
            if (reason.contains(tag)) return tag;
        }
        return "LEGACY";
    }

    /**
     * Dynamic max trades per day — adapts based on:
     * 1. Win rate today: winning → expand cap; losing → contract
     * 2. Regime: HIGH_VOL/EXTREME = more opportunities → higher cap
     * 3. Expiry day: more gamma opportunities → higher cap
     *
     * Base: config.getMaxTradesPerDay() (e.g., 50)
     * Range: [10, 3×base] — never lower than 10 (always gives a chance)
     */
    private int computeDynamicMaxTrades(IndexType indexType, IndexState state) {
        int base = config.getMaxTradesPerDay();
        int trades = state.tradesToday.get();

        // If few trades taken, don't restrict yet
        if (trades < 5) return base;

        // Win rate factor
        double scaleFactor = 1.0;
        int totalLosses = state.totalLossesCount.get();
        int wins = trades - totalLosses;
        double winRate = (double) wins / trades;

        if (winRate >= 0.6) scaleFactor = 1.5;       // winning 60%+ → let it run
        else if (winRate >= 0.4) scaleFactor = 1.0;  // normal → base cap
        else if (winRate >= 0.25) scaleFactor = 0.7; // struggling → reduce
        else scaleFactor = 0.5;                       // <25% win → heavily reduce

        // Regime bonus
        if (dynamicGateEngine != null && dynamicGateEngine.isActive()) {
            String regime = dynamicGateEngine.getValues(indexType).regime();
            if ("HIGH_VOL".equals(regime) || "EXTREME".equals(regime)) {
                scaleFactor *= 1.3;
            }
        }

        // Expiry day bonus
        if (expiryCalendar.isExpiryDay(indexType)) {
            scaleFactor *= 1.2;
        }

        return Math.max(10, Math.min(base * 3, (int) Math.round(base * scaleFactor)));
    }

    /**
     * Compute trend strength score (0–100) for dynamic exit mode switching.
     * High score (≥60) = TREND mode (let trailing run). Low (<60) = SCALP mode (take profit quick).
     *
     * Components:
     * - Range breadth: wide 30m range = trending (0–30 pts)
     * - Profit momentum: trade already profitable and growing = trend (0–30 pts)
     * - OI direction alignment: OI confirms trade direction = trend (0–20 pts)
     * - VIX expansion: rising VIX = expanding moves, favor trend (0–20 pts)
     */
    private int computeTrendScore(IndexType indexType, IndexState state, double profitPct, double rangePct30m) {
        int score = 0;

        // Range breadth: > 0.3% = trending market
        if (rangePct30m >= 0.5) score += 30;
        else if (rangePct30m >= 0.3) score += 20;
        else if (rangePct30m >= 0.15) score += 10;

        // Profit momentum: already in profit = trend developing
        if (profitPct >= 8) score += 30;
        else if (profitPct >= 5) score += 25;
        else if (profitPct >= 3) score += 15;
        else if (profitPct > 0) score += 5;

        // OI alignment: cached direction matches trade direction
        if (state.lastKnownOiDir != 0 && state.lastKnownOiDir == state.activeDirection) {
            score += 20;
        }

        // VIX: expanding VIX = bigger moves likely
        double vix = marketGuard.getCurrentVix();
        if (vix > 16) score += 20;
        else if (vix > 14) score += 10;

        return Math.min(100, score);
    }

    /** ATM CE/PE last prices from subscribed option cache (0 if missing). */
    private double[] atmOptionPremiums(IndexType indexType, int atm) {
        // Key on the CURRENT expiry. byToken holds EVERY expiry for a strike, but only the current
        // weekly's ATM band is subscribed/ticking — the other expiries sit at lastPrice=0. The old
        // strike-only scan below overwrote ce/pe on EACH match (no expiry filter), so a non-current
        // (0-priced) expiry iterated last zeroed the ATM premium ~100% of the time → NO_LTP in the
        // tuning capture (atmCeLast/atmPeLast=0 on 99.8% of 07-03 rows; regressed 63%→~100% as more
        // expiries accumulated). Mirror the chain-snapshot's expiry-keyed getOption() lookup, which is
        // proven to return the live subscribed price (07-03 15:25 ATM 24250: ceLTP 107.35, peLTP 72.95).
        java.time.LocalDate expiry = expiryCalendar.getCurrentExpiry(indexType);
        double ce = liveInstrumentCache.getOption(indexType, atm, "CE", expiry)
                .map(OptionInstrument::getLastPrice).orElse(0.0);
        double pe = liveInstrumentCache.getOption(indexType, atm, "PE", expiry)
                .map(OptionInstrument::getLastPrice).orElse(0.0);
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
    private int oiDir(IndexType indexType, long ceOiChange, long peOiChange) {
        // (C) Asymmetry guard: large one-sided unwind swamps a small build on the other side.
        // SLOT 2 (2026-07-08): the significance floor is now DYNAMIC per index — "significant" means
        // "≥ ~0.75× a typical minute TODAY", not a fixed 200k/500k. This re-bases the guard so it neither
        // trips on ordinary expiry churn nor sleeps through a genuine thin-day shift. Warm-up / flag-off →
        // legacy fixed floor (byte-identical). Direction detection (A/B below) is unchanged.
        final long MIN_SIGNIFICANT = oiSignificanceFloor(indexType);
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
        // ── PART 2 (2026-07-08): PCR is NOT a direction decider ───────────────────────────────────────
        // Audited legacy behaviour: PCR could (a) VETO an otherwise-valid OI+momentum-aligned entry — when
        // OI agreed with momentum but PCR opposed, the matrix fell through to CASE5_NO_RULE and SKIPPED; and
        // (b) be the SOLE gate that greenlit an OI-unavailable entry (CASE2 entered purely on PCR agreement)
        // or vetoed it (CASE5_PCR_VS_MOMENTUM). That makes PCR decisive for direction. When this flag is on
        // (default, LIVE), PCR is stripped of all veto/gate power here: direction is driven ONLY by the
        // OI-shift (when available) or the operator framework (when not). Direction is always momentumDir;
        // OI still resolves genuine conflicts (CASE4). PCR never inverts the side and never blocks/greenlights
        // an entry — it survives solely as a weak secondary conviction contributor in computeBiasScore.
        if (pcrNonDecisive) {
            if (oiAvailable) {
                if (oiDir == momentumDir) {
                    // OI confirms momentum → enter, subject to the SAME narrow-range guard as legacy CASE3
                    // (PCR-independent), keeping the breakout / OI-squeeze bypasses.
                    double rangePct = computeRangePct30m(indexType);
                    boolean isBreakout = momentumType != null
                            && (momentumType.contains("HIGH_BREAK") || momentumType.contains("LOW_BREAK"));
                    boolean isOiSqueeze = isMaterialOiSqueeze(ceOiChange, peOiChange);
                    if (!isBreakout && !isOiSqueeze && rangePct > 0 && rangePct < 0.3) {
                        return new EntryCaseEvaluation(0, "CASE3_RANGE_LT_0.3:" + String.format("%.3f", rangePct));
                    }
                    return new EntryCaseEvaluation(momentumDir, "");
                }
                if (oiDir != 0 && oiDir != momentumDir) {
                    // OI genuinely opposes momentum → skip. OI decides, not PCR.
                    return new EntryCaseEvaluation(0, "CASE4_OI_VS_MOMENTUM");
                }
                // oiDir == 0 (ambiguous) → defer to the operator framework, never to PCR (same override
                // path legacy used at the CASE5 fallback; only the label differs).
                String ov = tryOperatorOverride(indexType, momentumDir, "CASE5_OI_AMBIGUOUS");
                return ov != null ? new EntryCaseEvaluation(momentumDir, ov)
                                  : new EntryCaseEvaluation(0, "CASE5_OI_AMBIGUOUS");
            }
            // OI unavailable → the operator framework decides (not PCR). Preserves the operator-override
            // path legacy CASE5 already used; only the PCR-agree→enter / PCR-oppose→skip shortcuts are gone.
            String ov = tryOperatorOverride(indexType, momentumDir, "CASE5_OI_UNAVAILABLE");
            return ov != null ? new EntryCaseEvaluation(momentumDir, ov)
                              : new EntryCaseEvaluation(0, "CASE5_OI_UNAVAILABLE");
        }

        // ── Legacy PCR-in-the-matrix path (oi-momentum.pcr-nondecisive.enabled=false) ─────────────────
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

    /**
     * FIX 1 (2026-07-07) — "let winners run" state. Returns TRUE only when the position is comfortably IN
     * PROFIT (profitPct &gt;= min-profit-pct) AND the operator direction STILL confirms this side (fresh,
     * aligned, score &gt;= op-score-min). Callers use it to DEFER the two "cut the winner early" fast exits
     * (OI_WRITER_STOP + OI_FLIP_REVERSE) so a confirmed winner rides on the trailing-stop + hard-SL.
     *
     * <p>Safety-critical invariants (proving a loser can never be allowed to run):
     * <ul>
     *   <li>Returns false whenever {@code profitPct < min-profit-pct} (min-profit-pct is &gt; 0), so it can
     *       NEVER engage at, near, or below break-even — the loss side is untouched.</li>
     *   <li>Returns false whenever the operator no longer aligns with {@code activeDirection} (direction
     *       flipped against the position) when direction-confirm is required.</li>
     *   <li>Fails safe: any missing service / stale (non-fresh) operator signal / exception ⇒ false ⇒ the
     *       fast exits fire exactly as today.</li>
     *   <li>Flag off ({@code ride-winners.enabled=false}) ⇒ always false ⇒ byte-identical to today.</li>
     * </ul>
     */
    private boolean rideConfirmedWinner(IndexType indexType, IndexState state, double profitPct) {
        if (!rideWinnersEnabled) return false;
        if (profitPct < rideWinnersMinProfitPct) return false;   // must be a real winner — never at/near a loss
        if (state.activeDirection == 0) return false;
        if (!rideWinnersRequireDirectionConfirm) return true;    // profit-only mode (opt-in); still never a loss
        try {
            if (operatorFrameworkService == null) return false;  // no confirmation available ⇒ don't defer
            var opSig = operatorFrameworkService.getOperatorSignal(indexType);
            return opSig != null && opSig.isFresh()
                    && opSig.alignsWith(state.activeDirection)
                    && opSig.getScore() >= rideWinnersOpScoreMin;
        } catch (Exception ignore) {
            return false;                                        // any hiccup ⇒ fail safe (fast exits fire)
        }
    }

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
                // Daily P&L from realisedPnl (broker-confirmed, not estimated). Deduped by tradeId so this
                // can never double-count with the self-close updateDailyPnl path.
                if (pnl != null) {
                    accumulateDailyPnl(state, trade.getTradeId(), pnl.doubleValue());
                }
                // P1.3 (2026-06-26): guarantee an ExitEvent even when the close bypassed closePosition()
                // (central exit monitor / watchdog / fail-safe squareoff / shutdown). Without this, ~19 of
                // 30 executions on 06-22 had NO exit row — breaking P&L / MAE-MFE / exit attribution.
                // Emitted here (before state is cleared) so every execution gets exactly one exit event.
                if (tuningEventRecorder != null && oiMomentumCaptureAdapter != null && maeMfeTracker != null) {
                    try {
                        // Dedup against other emitters (OrderFillWatchdog / closePosition also call onExit
                        // and emit). onExit atomically REMOVES the trade and returns its snapshot on the
                        // FIRST call only — so a present snapshot means no one has emitted this exit yet and
                        // we are the sole emitter; a null means it was already handled+emitted elsewhere,
                        // so we skip to avoid a double exit event. Also correct under the watchdog/tick race.
                        var snapshot = maeMfeTracker.onExit(trade.getTradeId()).orElse(null);
                        if (snapshot != null) {
                            BigDecimal exitForEvent = exitPx != null ? exitPx
                                    : (entryPx != null ? entryPx : BigDecimal.ZERO);
                            com.algo.trade.tuning.ExitEvent exitEvent = oiMomentumCaptureAdapter.buildExitEvent(
                                    indexType, trade, snapshot,
                                    // Prefer the persisted entry key (survives restart); fall back to in-memory state.
                                    trade.getEntryCorrelationKey() != null ? trade.getEntryCorrelationKey() : state.lastEntryDecisionKey,
                                    exitForEvent,
                                    (exitReason != null && !exitReason.isBlank()) ? exitReason : "external_close",
                                    false);
                            tuningEventRecorder.record(exitEvent);
                        }
                    } catch (Exception ex) {
                        log.debug("[OIMomentum] external-close ExitEvent failed (non-fatal): {}", ex.getMessage());
                    }
                }
            }
            state.activeTradeId = null;
            state.activeDirection = 0;
            state.activeStrike = 0;
            state.peakPrice = 0;
            state.peakConfirmationTicks = 0;
            state.avalancheEntryActive = false;
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

        // Fix 3 (2026-06-25): Tier-1 liquidity emergency — bid/ask blowout, volume collapse vs
        // entry, and (redundant safety) stale quotes. Parity with the central monitor, which
        // already applied this to copied trades; the primary path previously had only the 60s
        // stale-quote check above. Flag-gated (exit.oi.liquidity-gate-enabled), default OFF.
        if (oiLiquidityGateEnabled && liquidityEmergencyGate != null) {
            try {
                var liq = liquidityEmergencyGate.checkSingleLegEmergency(trade, quote);
                if (liq.isPresent()) {
                    var sig = liq.get();
                    log.warn("[OIMomentum][{}] LIQUIDITY EMERGENCY {} — tradeId={} {}",
                            indexType, sig.reason(), trade.getTradeId(), sig.detail());
                    if (telegramAlertService != null) {
                        telegramAlertService.systemAlert(String.format(
                                "🚨 OIMomentum[%s] %s: %s | %s",
                                indexType, sig.reason(), trade.getInstrumentKey(), sig.detail()));
                    }
                    closePosition(indexType, state, trade, currentPrice, sig.reason());
                    return;
                }
            } catch (Exception ex) {
                log.debug("[OIMomentum][{}] liquidity gate check failed: {}", indexType, ex.getMessage());
            }
        }

        double entryPrice = trade.getEntryPrice().doubleValue();

        // Fast adverse-gap reaction (velocity-based; SHADOW-first, default off). Sits between the
        // stale/liquidity safety checks above and the level SL below: catches a violent move against
        // the position within seconds. In shadow mode this only logs and returns false.
        if (checkFastGapReaction(indexType, state, trade, currentPrice, entryPrice)) {
            return;
        }

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

        // ── V5 adaptive exit ladder (review-3 issue 3): replay-faithful vol-unit exits for
        // MEMORY_AVALANCHE trades, evaluated BEFORE the legacy fixed-% cascade below (which stays live
        // as an independent backstop — safe direction). Kill: avalanche.adaptive-exits-enabled=false.
        // Identity = the state FLAG (watchdog-materialized trades carry the ORDER text as entryReason,
        // which silently disabled this on every LIMIT-filled avalanche until 07-09 midday).
        boolean avalancheTrade = state.avalancheEntryActive
                || (entryReason != null && entryReason.contains("MEMORY_AVALANCHE"));
        if (avalancheAdaptiveExits && avalancheTrade
                && marketMemoryEngine != null && marketMemoryEngine.isEnabled() && state.activeStrike > 0) {
            long heldSecAv = state.lastEntryTime != null
                    ? Duration.between(state.lastEntryTime, Instant.now()).getSeconds()
                    : (trade.getEntryTime() != null
                        ? Duration.between(trade.getEntryTime(), Instant.now()).getSeconds() : 0);
            String avExit = avalancheExitReason(indexType, state.activeStrike, state.activeDirection > 0,
                    entryPrice, Math.max(state.peakPrice, currentPrice), currentPrice, heldSecAv,
                    state.avVolUnitAtEntry);
            if (avExit != null) {
                log.info("[OIMomentum][{}] ADAPTIVE ladder exit {} (vu={}%) — tradeId={}",
                        indexType, avExit, String.format("%.1f", state.avVolUnitAtEntry), trade.getTradeId());
                closePosition(indexType, state, trade, currentPrice, avExit);
                return;
            }
        }

        double profitPct = (currentPrice - entryPrice) / entryPrice * 100;
        double peakPct = (state.peakPrice - entryPrice) / entryPrice * 100;

        // P2 #18: Squareoff time
        LocalTime now = LocalTime.now(IST);
        LocalTime soff = LocalTime.of(config.getSquareoffHour(), config.getSquareoffMinute());
        // Item 2c (2026-07-07): on EXPIRY, a position ENTERED in the extended 15:10→15:15 window (i.e. after
        // the square-off time) is NOT killed by the 15:10 square-off — it rides to the late-entry time-stop
        // (15:18) so it can work, but is force-exited before the 15:20 thin-book settlement. The hard 15:20
        // force square-off (expiry-trap service) remains the ultimate backstop. Everything else unchanged:
        // trades entered before the square-off time, and ALL non-expiry trades, square off exactly as before.
        LocalTime effectiveSquareoff = soff;
        String squareoffReason = "SQUAREOFF_TIME";
        if (expiryCalendar.isExpiryDay(indexType) && state.lastEntryTime != null) {
            LocalTime entryT = state.lastEntryTime.atZone(IST).toLocalTime();
            if (entryT.isAfter(soff)) {
                try {
                    effectiveSquareoff = LocalTime.parse(config.getExpiryLateEntryTimeStopTime());
                    squareoffReason = "LATE_ENTRY_TIMESTOP";
                } catch (Exception ex) {
                    effectiveSquareoff = soff; // bad config → fall back to the normal square-off
                }
            }
        }
        if (!now.isBefore(effectiveSquareoff)) {
            closePosition(indexType, state, trade, currentPrice, squareoffReason);
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
        // VIX-adaptive: widen trigger in high-VIX regimes (options swing more, need more room).
        double beTrigger = config.getBreakEvenTriggerPercent();
        double vix = marketGuard.getCurrentVix();
        if (vix > 18) beTrigger = Math.max(beTrigger, 7.0);  // high VIX: wider BE trigger
        else if (vix > 15) beTrigger = Math.max(beTrigger, 6.0); // moderate VIX: slightly wider
        boolean expiryDay = expiryCalendar.isExpiryDay(indexType);
        boolean peakConfirmed = state.peakConfirmationTicks >= 2;
        boolean beArmed = beTrigger > 0 && peakPct >= beTrigger && peakConfirmed;
        if (beArmed) {
            double beFloor = expiryDay ? -1.0 : 0.0;  // expiry: SL no worse than +1% profit;
                                                       // non-expiry: SL no worse than entry
            // Profit-lock ratchet: as peak grows, lock progressively more profit (not just breakeven).
            // Works for ALL lot sizes (unlike partial booking which needs ≥4 lots).
            // SIGN: the SL fires at `profitPct <= -slPercent` and slPercent=min(slPercent, beFloor), so a
            // MORE-NEGATIVE beFloor locks a HIGHER positive-profit floor (same convention as the -1.0 expiry
            // line = "+1% floor"). Use NEGATIVE targets + Math.min — a positive value here would LOOSEN the
            // stop below breakeven and widen give-back (the exact opposite of the intent).
            if (peakPct >= 10.0) beFloor = Math.min(beFloor, -5.0);      // peak ≥10% → lock +5% profit
            else if (peakPct >= 7.0) beFloor = Math.min(beFloor, -3.0);  // peak ≥7%  → lock +3% profit
            else if (peakPct >= 5.0) beFloor = Math.min(beFloor, -1.5);  // peak ≥5%  → lock +1.5% profit
            // Original BE: peak ≥ beTrigger → floor at 0% (or -1% on expiry)
            slPercent = Math.min(slPercent, beFloor);
        }

        // ── Stale-price guard: reject exit decisions based on zero/stale premiums ──
        // A stale or zero currentPrice would compute profitPct as ~−100%, triggering
        // spurious exits at garbage prices. Gate all drawdown-based exits on fresh quote.
        if (currentPrice <= 0) {
            log.debug("[OIMomentum][{}] managePosition: currentPrice={} (stale/zero) — skipping exit checks this tick",
                    indexType, currentPrice);
            return;
        }

        // ── Flash-Crash Protection: exit immediately if drawdown >40% in <60s ──
        // MUST be before SL check — a −40% flash in <60s should exit immediately
        // with tagged reason, not be silently consumed by the normal −10% SL path.
        if (profitPct < -40 && state.lastEntryTime != null
                && Duration.between(state.lastEntryTime, Instant.now()).getSeconds() < 60) {
            closePosition(indexType, state, trade, currentPrice,
                    String.format("FLASH_CRASH_EXIT(drawdown=%.1f%%,holdSec=%d)",
                            profitPct, Duration.between(state.lastEntryTime, Instant.now()).getSeconds()));
            state.consecutiveLosses.incrementAndGet();
            state.lastSlTime = Instant.now();
            updateDailyPnl(state, profitPct, trade);
            return;
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

        // ── Chop fade-mode tight bracket (2026-07-06) — chop fades only ─────────────────────────────
        // A chop fade is a quick mean-reversion scalp: book at a tight target, cut at a tight stop. Keyed
        // on the CHOP_FADE tag so ONLY fades entered under the relaxed chop path get this bracket — legacy
        // (trend-regime) fades are byte-identical. Sits AFTER the hard SL/flash-crash/squareoff (those fire
        // first) and BEFORE the COE fast-reversal + OI_WRITER_STOP, so those remain active WITHIN the
        // bracket band (COE-managed exit). The tight stop only tightens protection (never loosens it).
        if (fadeModeEnabled && entryReason != null && entryReason.contains("CHOP_FADE")) {
            if (fadeTargetPct > 0 && profitPct >= fadeTargetPct) {
                closePosition(indexType, state, trade, currentPrice,
                        String.format("FADE_TARGET(profit=%.1f%%,target=%.1f%%)", profitPct, fadeTargetPct));
                state.consecutiveLosses.set(0);
                updateDailyPnl(state, profitPct, trade);
                return;
            }
            if (fadeStopPct > 0 && profitPct <= -fadeStopPct) {
                closePosition(indexType, state, trade, currentPrice,
                        String.format("FADE_STOP(loss=%.1f%%,stop=%.1f%%)", profitPct, fadeStopPct));
                state.lastSlTime = Instant.now();
                state.consecutiveLosses.incrementAndGet();
                updateDailyPnl(state, profitPct, trade);
                return;
            }
        }

        // ── Conviction Override — fast microstructure reversal exit (PRIMARY exit override) ─────────
        // The mirror of the CTO entry: the same order-book/flow evidence, reversed. If the liquidity
        // carrying the position is spoofed/pulled or the up-move is being absorbed (a resting seller
        // eating the buying), exit IMMEDIATELY — ahead of the slower OI_WRITER_STOP/giveback checks and
        // before the round-trip completes. Bounded: only closes earlier, never opens; config-gated;
        // a small min-hold avoids exiting on entry-tick noise. Bypasses the min-hold-time grace below.
        if (coeExitFastReversal && convictionOverrideEngine != null && convictionOverrideEngine.isEnabled()
                && state.activeStrike > 0 && trade.getEntryPrice() != null && state.lastEntryTime != null) {
            long coeHeldSec = Duration.between(state.lastEntryTime, Instant.now()).getSeconds();
            String coeType = state.activeDirection >= 0 ? "CE" : "PE"; // matches OiDivergenceMonitor convention
            if (coeHeldSec >= coeExitMinHoldSec
                    && convictionOverrideEngine.isReversing(indexType, state.activeStrike, coeType)) {
                // V5 (07-09 live finding): on an IN-PROFIT avalanche trade whose strike is STILL in
                // panic covering, the spoof/absorption detector misreads the covering itself as a
                // reversal (same evidence class as the entry-side deep override) — 10:32 it cut a
                // running winner at 43s. Defer while the ride is confirmed; the LOSS side is untouched.
                if (state.avalancheEntryActive && profitPct >= 0 && marketMemoryEngine != null
                        && (marketMemoryEngine.recentAvalanche(indexType, state.activeStrike, coeType, 30))) {
                    log.info("[ExitArbiter][{}] COE fast-reversal SUPPRESSED — in-profit avalanche ride "
                            + "(strike still covering, profit={}%)", indexType, String.format("%.1f", profitPct));
                    if (marketMemoryEngine != null) {
                        marketMemoryEngine.event("EXIT_SUPPRESSED", indexType.name(),
                                indexType + " " + state.activeStrike + " " + coeType,
                                String.format("COE_FAST_REVERSAL deferred — in-profit avalanche %.1f%%", profitPct));
                    }
                } else {
                log.warn("[COE] CONVICTION_EXIT_OVERRIDE — microstructure reversal (spoof/absorption) on held {} {}{} "
                        + "heldSec={} — exiting fast to avoid the round-trip", indexType, state.activeStrike, coeType, coeHeldSec);
                closePosition(indexType, state, trade, currentPrice, "CONVICTION_EXIT_OVERRIDE");
                if (currentPrice < trade.getEntryPrice().doubleValue()) state.consecutiveLosses.incrementAndGet();
                else state.consecutiveLosses.set(0);
                updateDailyPnl(state, profitPct, trade);
                return;
                }
            }
        }

        // ── V5 WPRESS-ACCEL (Market-Memory, docs/MARKET-MEMORY-V5-DESIGN.md §4 rule 2) ──────────────
        // IN-LOSS ONLY: when the held strike's memory says WRITER_PRESS at strength (OI building at
        // z>=+2 while its price falls more than one vol-unit), the drop continues ×1.35–1.55 — exit
        // NOW instead of waiting for the slower OI windows. Replay on the 49 real trades: this rule's
        // saves were +₹439…+₹656 per trade exactly where OI_WRITER_STOP used to wait then sell bottoms.
        // Never touches a profitable trade; hard SL / flash-crash / daily-loss all remain above.
        if (wpressAccelEnabled && profitPct < 0 && state.lastEntryTime != null) {
            long wpHeldSec = Duration.between(state.lastEntryTime, Instant.now()).getSeconds();
            if (wpHeldSec >= wpressMinHeldSec) {
                var mem = heldMemory(indexType, state);
                if (mem != null && !mem.warming()
                        && mem.zOi() >= 2.0 && mem.dOi5mPct() > 0
                        && mem.dP5mPct() <= -mem.volUnitPct()) {
                    log.info("[ExitArbiter][{}] WPRESS_ACCEL — in-loss {}% + writers pressing (zOi={}, dOI5m={}%, dP5m={}%, volUnit={}%)",
                            indexType, String.format("%.1f", profitPct), String.format("%.1f", mem.zOi()),
                            String.format("%.2f", mem.dOi5mPct()), String.format("%.1f", mem.dP5mPct()),
                            String.format("%.1f", mem.volUnitPct()));
                    if (marketMemoryEngine != null) {
                        marketMemoryEngine.event("EXIT_WPRESS", indexType.name(),
                                indexType + " " + state.activeStrike + " " + (state.activeDirection >= 0 ? "CE" : "PE"),
                                String.format("in-loss %.1f%% zOi=%.1f dOI5m=%.2f%% dP5m=%.1f%% heldSec=%d",
                                        profitPct, mem.zOi(), mem.dOi5mPct(), mem.dP5mPct(), wpHeldSec));
                    }
                    closePosition(indexType, state, trade, currentPrice,
                            String.format("WPRESS_ACCEL(zOi=%.1f,dOI5m=%.1f%%,dP5m=%.1f%%)",
                                    mem.zOi(), mem.dOi5mPct(), mem.dP5mPct()));
                    state.consecutiveLosses.incrementAndGet();
                    updateDailyPnl(state, profitPct, trade);
                    return;
                }
            }
        }

        // ── OI-divergence protective stop (OI_WRITER_STOP) — shadow-first (2026-07-03) ──────────────
        // If the HELD strike develops OI↑ + its own premium↓ (writers stacking into our strike), cut
        // earlier than the −SL and without the trailing give-back. The monitor also captures the entry-
        // time divergence (first-sight) for the tuning loop. Own arming (arm-sec) + confirmation
        // (confirm-ticks) gates live inside the monitor; it never throws. In SHADOW it only records and
        // returns false; only when enforced does it return true to authorise this exit. Symmetric for
        // CE/PE — always reads the held option's own OI + premium.
        if (oiDivergenceMonitor != null && oiDivergenceMonitor.isEnabled() && state.activeStrike > 0
                && trade.getEntryPrice() != null && state.lastEntryTime != null) {
            long divHeldSec = Duration.between(state.lastEntryTime, Instant.now()).getSeconds();
            boolean divEnforceExit = oiDivergenceMonitor.evaluateHeldExit(
                    indexType, state.activeStrike, state.activeDirection, currentPrice,
                    trade.getTradeId(), trade.getEntryPrice().doubleValue(), divHeldSec);
            // ── Early-dip exit grace, COE-deferred (2026-07-06, chop only) ──────────────────────────
            // OI_WRITER_STOP is an OI *proxy* for reversal that fires on ~1-min held-strike OI dips. COE
            // (ConvictionOverrideEngine) already runs a DIRECT 500ms book-imbalance + signed-flow reversal
            // check on the same held strike (consumed by the CONVICTION_EXIT_OVERRIDE fast exit above). So
            // inside the grace window, in chop, DEFER the OI-dip writer-stop to COE: hold the position UNLESS
            //   (a) COE confirms the reversal (same isReversing() signal the fast exit uses), or
            //   (b) the loss has breached the hard floor (profitPct <= −hard-floor).
            // Genuine reversals still exit fast via COE / the hard floor; the premature OI-dip cut is gated
            // off. Gated to chopRegime so TREND days are byte-identical (writer-stop fires exactly as before,
            // no COE dependency there). Does NOT weaken the real stop: hard SL (−slPercent), flash-crash,
            // daily-loss/consecutive-loss and kill-switch all remain above/independent. COE + isChopRegime()
            // are evaluated LAST (short-circuit) so they only run when a writer-stop actually wants to fire
            // inside the window and above the floor.
            boolean coeConfirmsReversal = false;
            if (convictionOverrideEngine != null && convictionOverrideEngine.isEnabled()
                    && state.activeStrike > 0) {
                try {
                    String coeType = state.activeDirection >= 0 ? "CE" : "PE"; // OiDivergenceMonitor convention
                    coeConfirmsReversal = convictionOverrideEngine.isReversing(indexType, state.activeStrike, coeType);
                } catch (Exception ignore) { /* engine hiccup → treat as NOT confirming (stay deferred) */ }
            }
            if (divEnforceExit && exitGraceEnabled && divHeldSec < exitGraceWindowSec
                    && profitPct > -exitGraceHardFloorPct && !coeConfirmsReversal && isChopRegime(indexType)) {
                long nowMs = System.currentTimeMillis();
                if (nowMs - state.lastExitGraceLogMs > 30_000) {
                    log.info("[OIMomentum][{}] OI_WRITER_STOP DEFERRED to COE (exit-grace) — heldSec={}<{} profit={}% > -{}% floor, coeReversal=false (chop)",
                            indexType, divHeldSec, exitGraceWindowSec,
                            String.format("%.1f", profitPct), exitGraceHardFloorPct);
                    state.lastExitGraceLogMs = nowMs;
                }
                divEnforceExit = false;
            }
            // ── Ride confirmed winners (2026-07-07) — defer OI_WRITER_STOP on a confirmed WINNER ──────────
            // NOT chop/clock-gated (works on trend + grind days too). Only defers when the trade is comfortably
            // in profit AND the operator direction still confirms this side (rideConfirmedWinner guards both) —
            // a confirmed winner rides on the trailing-stop + hard-SL rather than being cut on a first-minute
            // writer-stop OI dip. Can NEVER defer at/near a loss or on a flipped direction. Independent of the
            // COE reversal exit (CONVICTION_EXIT_OVERRIDE) which already fired ABOVE and is NOT deferred — so a
            // genuine microstructure reversal still exits fast even while this would otherwise ride.
            if (divEnforceExit && rideConfirmedWinner(indexType, state, profitPct)) {
                long nowMs = System.currentTimeMillis();
                if (nowMs - state.lastRideWinnerLogMs > 30_000) {
                    log.info("[OIMomentum][{}] OI_WRITER_STOP DEFERRED (ride-winners) — profit={}% >= {}% + operator confirms dir={} — riding on trailing-stop/hard-SL",
                            indexType, String.format("%.1f", profitPct), rideWinnersMinProfitPct, state.activeDirection);
                    state.lastRideWinnerLogMs = nowMs;
                }
                divEnforceExit = false;
            }
            // ── V5 removal (docs/MARKET-MEMORY-V5-DESIGN.md §4): OI_WRITER_STOP OFF as a live exit ────
            // 3-day evidence: 22 exits, 0% win, −₹7.8k; price rebounded ≥2% within 5 min after 11/22 —
            // it sells local bottoms. Coverage stays: SL, WPRESS_ACCEL (above), COE reversal, trailing,
            // scalp, max-hold. The condition is still evaluated and AUDITED here so the 07-11 scoring can
            // prove the removal with live data. Re-enable = oi-momentum.exit.writer-stop-live: true.
            if (divEnforceExit && !writerStopLive) {
                log.info("[ExitArbiter][{}] OI_WRITER_STOP SUPPRESSED (writer-stop-live=false) — wouldFire strike={} dir={} profit={}% (V5 removal audit)",
                        indexType, state.activeStrike, state.activeDirection, String.format("%.1f", profitPct));
                if (marketMemoryEngine != null) {
                    marketMemoryEngine.event("EXIT_SUPPRESSED", indexType.name(),
                            indexType + " " + state.activeStrike + " " + (state.activeDirection >= 0 ? "CE" : "PE"),
                            String.format("OI_WRITER_STOP wouldFire profit=%.1f%% (removal audit)", profitPct));
                }
                divEnforceExit = false;
            }
            if (divEnforceExit) {
                closePosition(indexType, state, trade, currentPrice, "OI_WRITER_STOP");
                if (currentPrice < trade.getEntryPrice().doubleValue()) state.consecutiveLosses.incrementAndGet();
                else state.consecutiveLosses.set(0);
                updateDailyPnl(state, profitPct, trade);
                return;
            }
        }

        // ── OI-confirmed peak-giveback exit (PEAK_GIVEBACK) — shadow-first (2026-07-03) ────────────
        // Distinct from OI_WRITER_STOP above: fires only on a STILL-PROFITABLE trade that has peaked
        // and pulled back, and requires BOTH the held-strike OI/premium divergence AND a same-strike
        // volume surge before treating the pullback as confirmed (vs. noise). When unconfirmed, this
        // does nothing — the existing trailing-stop/profit-lock ratchet below remains the sole
        // backstop, completely undisturbed. Same tier as OI_WRITER_STOP: bypasses the min-hold-time
        // gate below because a confirmed reversal on a real winner should act fast, not wait out the
        // grace period meant for fresh entries.
        if (oiDivergenceMonitor != null && oiDivergenceMonitor.isEnabled() && state.activeStrike > 0
                && trade.getEntryPrice() != null && state.lastEntryTime != null && state.peakPrice > 0) {
            long givebackHeldSec = Duration.between(state.lastEntryTime, Instant.now()).getSeconds();
            boolean givebackEnforceExit = oiDivergenceMonitor.evaluatePeakGivebackExit(
                    indexType, state.activeStrike, state.activeDirection, currentPrice,
                    trade.getTradeId(), trade.getEntryPrice().doubleValue(), state.peakPrice, givebackHeldSec);
            if (givebackEnforceExit) {
                closePosition(indexType, state, trade, currentPrice, "PEAK_GIVEBACK");
                state.consecutiveLosses.set(0); // this path only fires while still profitable
                updateDailyPnl(state, profitPct, trade);
                return;
            }
        }

        // Minimum hold time (gates target/trailing/scalp/OI-flip below, NOT SL/flash-crash/squareoff
        // above). Keep this SHORT (grace period, default 60s) — it is NOT the place to enforce "hold
        // direction for 5 minutes" intent; that belongs to OI-flip's own oi-flip-min-hold-seconds gate
        // further down, which fires independently of this one. A long value here blocks ALL profit-
        // taking (target/trailing/scalp) on the primary account while LivePositionExitMonitor (used for
        // signal-copied secondary-user trades) only waits 60s — causing primary and secondary copies of
        // the same trade to exit on very different timelines (2026-07-02 incident).
        if (state.lastEntryTime != null && Duration.between(state.lastEntryTime, Instant.now()).getSeconds() < config.getMinimumHoldTimeSeconds()) {
            return;
        }

        // ── Quick-Profit Floor: exit when per-lot profit exceeds 1R and momentum stalls ──
        // Uses R-multiple (profit / initial-risk-per-lot) instead of flat ₹ — scales correctly
        // regardless of lot count. 1R = entry × SL% × lotSize. Exit when profit ≥ 1R per lot
        // and premium velocity has stalled, OR hard exit at 2R regardless (lock the win).
        if (profitPct > 0 && trade.getEntryPrice() != null && trade.getQuantity() > 0) {
            double ep = trade.getEntryPrice().doubleValue();
            int qty = trade.getQuantity();
            int lotSize = indexType.lotSize();
            int lots = Math.max(1, qty / lotSize);
            // R = risk per lot = entry × SL% × lotSize
            double riskPerLot = ep * (slPercent / 100.0) * lotSize;
            double totalRisk = riskPerLot * lots;
            double absPnl = ep * qty * profitPct / 100.0;
            double rMultiple = totalRisk > 0 ? absPnl / totalRisk : 0;

            if (rMultiple >= 1.0) {
                // At 1R+: exit only if momentum is stalling (no acceleration left)
                PremiumVelocityTracker.PremiumVelocity premVel = premiumVelocityTracker.getVelocity(indexType);
                boolean momentumStalling = premVel.maxPremiumVelocityPct() < 1.0;
                // Hard exit at 2R regardless (don't risk round-trip on large winners)
                if (momentumStalling || rMultiple >= 2.0) {
                    closePosition(indexType, state, trade, currentPrice,
                            String.format("QUICK_PROFIT(R=%.1f,pnl=%.0f,profit=%.1f%%,premVel=%.1f%%)",
                                    rMultiple, absPnl, profitPct, premVel.maxPremiumVelocityPct()));
                    state.consecutiveLosses.set(0);
                    updateDailyPnl(state, profitPct, trade);
                    return;
                }
            }
        }

        // ── Dynamic Exit Mode: SCALP vs TREND ──────────────────────────────────
        // Assess real-time trend strength to decide exit behavior:
        //   TREND mode: let trailing stop handle exit (current behavior)
        //   SCALP mode: take profit at a dynamic target based on ATR + entry premium
        // trendScore = momentum magnitude + range breadth + OI velocity alignment
        double rangePct30m = computeRangePct30m(indexType);
        int trendScore = computeTrendScore(indexType, state, profitPct, rangePct30m);

        // ── Firm profit-target book (2026-07-01, research-driven A/B) ─────────────────────────────
        // Book a winner at a fixed target instead of letting the ride/trail give it back. Runs BEFORE the
        // ride logic below so it caps a running winner (scalpMode already books earlier at 3-8%, so in
        // practice this bounds the drift/operator/MTF rides). Config-gated (oi-momentum.profit-target.*)
        // and captured via the PROFIT_TARGET exit reason so the tuning exit-attribution can measure
        // whether target-book actually beats ride-and-trail. Hard SL/flash/squareoff already handled above.
        // V5 review-3 #3: an adaptive-ladder-managed MEMORY_AVALANCHE trade is exempt from the firm
        // target — the fixed 15% cap would book exactly the monster covers the pattern exists for
        // (replay winners ran +50–150%); the ladder's vol-scaled AV_TRAIL manages the give-back.
        boolean avalancheLadderManaged = avalancheAdaptiveExits
                && (state.avalancheEntryActive
                    || (entryReason != null && entryReason.contains("MEMORY_AVALANCHE")))
                && marketMemoryEngine != null && marketMemoryEngine.isEnabled();
        if (profitTargetEnabled && profitTargetPct > 0 && profitPct >= profitTargetPct
                && !avalancheLadderManaged) {
            closePosition(indexType, state, trade, currentPrice,
                    String.format("PROFIT_TARGET(profit=%.1f%%,target=%.1f%%,trendScore=%d)",
                            profitPct, profitTargetPct, trendScore));
            state.consecutiveLosses.set(0);
            updateDailyPnl(state, profitPct, trade);
            return;
        }

        // ── D2 TREND-RIDE (2026-06-29) ──────────────────────────────────────────
        // When a STRONG sustained drift is live in THIS position's direction, ride it as a TREND (let the
        // trailing stop / R-multiple work) instead of scalping at +3%. Evidence: 06-29 the bot correctly bought
        // PEs into a −0.68% drift (D2_DRIFT_LIVE dir=-1 opScore 91–100) but the burst-based trendScore stayed <60
        // → scalpMode booked +3% bites while retraces hit the stop → net −3.7% on a CORRECT directional read.
        // Re-evaluated each tick so the ride ENDS the moment the drift weakens/reverses (reverts to scalp). The
        // hard SL / flash-crash / squareoff above are independent of scalpMode, so a drift-ride that goes wrong is
        // still cut at the stop. Conservative: requires a live fire + high opScore + direction match.
        boolean driftRiding = false;
        if (sustainedDriftDetector != null && config.isSustainedDriftEnabled()
                && !config.isSustainedDriftShadowMode() && state.activeDirection != 0) {
            try {
                SustainedDriftDetector.Decision d = sustainedDriftDetector.evaluate(indexType, config);
                driftRiding = d.fires() && d.direction() == state.activeDirection
                        && d.opScore() >= DRIFT_RIDE_MIN_OPSCORE;
            } catch (Exception ignore) { /* detector hiccup → fall back to normal scalp/trend */ }
        }

        // D (2026-07-01): FRESH fast-OI operator score as an additional ride trigger. Post fast-OI, the operator
        // framework refreshes ~every 15s (vs the old 5-min snapshot), so getOperatorSignal is genuinely fresh.
        // A strong, fresh, direction-aligned operator conviction = "real institutional move in progress" → ride
        // it (don't scalp at +3%). This catches the case the lagging trendScore + the ≥70 SustainedDrift bar miss
        // (e.g. today's NIFTY winner entered at opScore 57 but the fresh operator score crossed 65→70 as it ran).
        // Never forces an exit — only KEEPS a winner running; the hard SL/flash-crash/squareoff still apply.
        boolean operatorRiding = false;
        if (fastOiEnabled && operatorFrameworkService != null && state.activeDirection != 0) {
            try {
                var opSig = operatorFrameworkService.getOperatorSignal(indexType);
                operatorRiding = opSig != null && opSig.isFresh()
                        && opSig.alignsWith(state.activeDirection)
                        && opSig.getScore() >= OPERATOR_RIDE_MIN_SCORE;
            } catch (Exception ignore) { /* signal hiccup → fall back to normal scalp/trend */ }
        }

        // MTF ride: when the winner is running WITH the day+week structure (aligned, trending regime),
        // don't scalp a 3% bite out of a move the higher timeframe supports — hold it as a trend. Same
        // spirit as operatorRiding: never forces an exit, only keeps a winner running; all hard exits apply.
        boolean mtfRiding = false;
        if (mtfRideWhenAligned && mtfContextService != null && mtfLiveEnabled && state.activeDirection != 0) {
            try {
                var mtf = mtfContextService.getContext(indexType);
                mtfRiding = mtf != null && mtf.available()
                        && mtf.alignsWith(state.activeDirection)
                        && mtf.isTrending();
            } catch (Exception ignore) { /* context hiccup → fall back to normal scalp/trend */ }
        }

        // V5 (Market-Memory §4 rule 3): don't scalp while the held strike is in AVALANCHE or
        // SHORT_COVER_RUN — panic covering keeps running (rally ×1.5–1.7); the trailing-stop rides it.
        // Replay evidence: BATTLE-aware riding turned a +423 trail into +1,476 on 07-07 NIFTY 24450 CE.
        boolean memoryRiding = false;
        if (rideAvalancheScalpDefer) {
            var mem = heldMemory(indexType, state);
            memoryRiding = mem != null
                    && (mem.state() == com.algo.trade.marketdata.MarketMemoryEngine.MarketState.AVALANCHE
                        || mem.state() == com.algo.trade.marketdata.MarketMemoryEngine.MarketState.SHORT_COVER_RUN);
        }
        boolean scalpMode = !regimeHold && trendScore < 60 && !driftRiding && !operatorRiding && !mtfRiding && !memoryRiding;
        if ((driftRiding || operatorRiding || mtfRiding || memoryRiding) && profitPct > 0) {
            log.debug("[OIMomentum][{}] RIDE — holding as TREND (dir={}, profit={}%, driftRide={}, operatorRide={}, mtfRide={}, memoryRide={}) instead of scalping",
                    indexType, state.activeDirection, String.format("%.1f", profitPct), driftRiding, operatorRiding, mtfRiding, memoryRiding);
        }

        // Scalp exit: dynamic target based on ATR-scaled profit
        // Target = max(3%, min(8%, 0.7 × ATR / entryPrice × 100))
        // In choppy markets (trendScore < 60), take profit quickly and recycle
        if (scalpMode && profitPct > 0) {
            double spot = liveInstrumentCache.getFuturesPrice(indexType);
            double atr = spot > 0 ? momentumDetector.getRolling30MinHigh(indexType)
                    - momentumDetector.getRolling30MinLow(indexType) : 0;
            double atrPct = spot > 0 && atr > 0 ? (atr / spot) * 100 : 0.3;
            double scalpTarget = Math.max(3.0, Math.min(8.0, atrPct * 2.5));
            if (profitPct >= scalpTarget) {
                closePosition(indexType, state, trade, currentPrice,
                        String.format("SCALP_TARGET(profit=%.1f%%,target=%.1f%%,trendScore=%d)",
                                profitPct, scalpTarget, trendScore));
                state.consecutiveLosses.set(0);
                updateDailyPnl(state, profitPct, trade);
                return;
            }
            // Theta-decay-aware time cap: an option that is mostly EXTRINSIC (time value) bleeds theta
            // fast, so book a profitable-but-stalling scalp on a SHORTER cap than the flat 15-min below.
            // Keys off the time-value FRACTION (extrinsic / premium), NOT raw premium ₹ — a deep-ITM
            // high-₹ option (low extrinsic fraction) keeps the normal cap; an ATM/OTM option (high
            // extrinsic fraction, any ₹) gets the faster one. Uses the OPTION's own strike vs spot for
            // intrinsic, so it is correct for both CE and PE. Only ever exits a profitable scalp SOONER;
            // never touches SL/trailing/trend-rides (already gated by scalpMode + profit > 0 above).
            if (thetaDecayExitEnabled && state.lastEntryTime != null && state.activeStrike > 0
                    && state.activeDirection != 0) {
                double spotForIntrinsic = liveInstrumentCache.getFuturesPrice(indexType);
                if (spotForIntrinsic > 0 && currentPrice > 0) {
                    double intrinsic = state.activeDirection > 0
                            ? Math.max(0.0, spotForIntrinsic - state.activeStrike)   // CE
                            : Math.max(0.0, state.activeStrike - spotForIntrinsic);  // PE
                    double extrinsicFrac = (currentPrice - intrinsic) / currentPrice;
                    long heldMin = Duration.between(state.lastEntryTime, Instant.now()).toMinutes();
                    if (extrinsicFrac >= thetaDecayExtrinsicFraction && heldMin >= thetaDecayMaxHoldMin) {
                        closePosition(indexType, state, trade, currentPrice,
                                String.format("THETA_DECAY_EXIT(profit=%.1f%%,extFrac=%.2f,hold=%dmin,trendScore=%d)",
                                        profitPct, extrinsicFrac, heldMin, trendScore));
                        state.consecutiveLosses.set(0);
                        updateDailyPnl(state, profitPct, trade);
                        return;
                    }
                }
            }

            // In scalp mode, also enforce a time cap: max 15 min hold
            if (state.lastEntryTime != null
                    && Duration.between(state.lastEntryTime, Instant.now()).toMinutes() >= 15
                    && profitPct > 0) {
                closePosition(indexType, state, trade, currentPrice,
                        String.format("SCALP_TIME_EXIT(profit=%.1f%%,hold=15min,trendScore=%d)",
                                profitPct, trendScore));
                state.consecutiveLosses.set(0);
                updateDailyPnl(state, profitPct, trade);
                return;
            }
        }

        // Fix 2 (2026-06-25): progressive partial profit-booking (R6). Books a fraction of the
        // CURRENT remaining quantity at rising profit triggers via the shared DynamicExitManager
        // ladder (PARTIAL_1 +30%/25%, PARTIAL_2 +50%/33%, PARTIAL_3 +80%/50%), so big trend winners
        // lock in gains instead of round-tripping. Reuses the validated closePartialTrade path and
        // the persisted partialExitLayers field for cross-tick/restart dedupe. Whole-lot multiples
        // only. Flag-gated (exit.oi.progressive-booking-enabled), default OFF. Returns after a
        // partial so the next tick re-reads the reduced quantity (this in-memory entity is now stale).
        if (oiProgressiveBookingEnabled && dynamicExitManager != null && profitPct > 0) {
            java.util.Set<String> fired = oiLoadFiredLayers(trade);
            var layerOpt = dynamicExitManager.nextExitLayer(profitPct, fired);
            if (layerOpt.isPresent()) {
                var layer = layerOpt.get();
                int contractLot = indexType.lotSize();
                int rawQty = (int) Math.round(trade.getQuantity() * layer.exitFraction());
                int partialQty = contractLot > 0 ? (rawQty / contractLot) * contractLot : 0;
                if (partialQty > 0 && partialQty < trade.getQuantity()) {
                    log.info("[OIMomentum][{}] PROGRESSIVE BOOKING {}: tradeId={} profit={}% partialQty={} lot={}",
                            indexType, layer.name(), trade.getTradeId(),
                            String.format("%.1f", profitPct), partialQty, contractLot);
                    try {
                        executionEngine.closePartialTrade(trade.getTradeId(), partialQty,
                                BigDecimal.valueOf(currentPrice), layer.name());
                    } catch (Exception ex) {
                        log.error("[OIMomentum][{}] partial booking failed for tradeId={}: {}",
                                indexType, trade.getTradeId(), ex.getMessage());
                    }
                    return;
                }
            }
        }

        // No fixed target in TREND mode — let the trailing stop handle profit-taking.
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

            // ── Composite Exit: reversal footprints → emergency trail tightening ──
            // When trailing is armed AND the reversal detector fires AGAINST our position,
            // immediately tighten the gap to 50% of current gap (lock the gain before the flip).
            // This prevents giving back large gains when operator footprints signal a reversal.
            boolean revTightened = false; // tagged into the exit reason so the report can measure this path
            if (!regimeHold && operatorReversalDetector != null && operatorReversalDetector.isEnabled()) {
                var revSig = operatorReversalDetector.getSignal(indexType);
                if (revSig.isActive() && revSig.direction() != state.activeDirection && profitPct > 3) {
                    double emergencyGap = effectiveGap * 0.5;
                    if (emergencyGap < effectiveGap) {
                        log.info("[OIMomentum][{}] COMPOSITE_EXIT_TIGHTEN: reversal signal AGAINST position (profit={}%), gap {}→{}",
                                indexType, String.format("%.1f", profitPct),
                                String.format("%.2f", effectiveGap), String.format("%.2f", emergencyGap));
                        effectiveGap = emergencyGap;
                        revTightened = true;
                    }
                }
            }
            // Fix 1 (2026-06-25): tiered trailing (R6) — once the peak crosses tier1/tier2, floor the
            // gap to the configured tier gap so large winners give back less. Only ever TIGHTENS the
            // gap (min). Applies to both regime-hold and default profiles. Flag-gated, default OFF.
            effectiveGap = tieredTrailGap(effectiveGap, peakPct);
            double trailLevel = peakPct - effectiveGap;
            if (profitPct < trailLevel) {
                closePosition(indexType, state, trade, currentPrice,
                        regimeHold ? "TRAILING_STOP_REGIME_HOLD"
                                : (revTightened ? "TRAILING_STOP(rev_tightened)" : "TRAILING_STOP"));
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
        // Dynamic reversal cap: allow more reversals on high-VIX days (more opportunities)
        int effectiveMaxReversals = config.getMaxReversalsPerDay();
        double currentVix = marketGuard.getCurrentVix();
        if (currentVix > 18) effectiveMaxReversals = Math.max(effectiveMaxReversals, 5);
        else if (currentVix > 15) effectiveMaxReversals = Math.max(effectiveMaxReversals, 4);
        if (!suppressOiFlip && state.oiAdvanced && state.reversalsToday.get() < effectiveMaxReversals) {
            // Direction flip cooldown — no flip within 5 minutes of a previous flip.
            // Operators hold direction for 30-60 min; 2-min flips are noise-driven churn.
            if (state.lastReversalTime != null && Duration.between(state.lastReversalTime, Instant.now()).getSeconds() < 300) {
                return;
            }
            // No flip within 5 minutes of ENTRY either — give the trade thesis time to develop.
            if (state.lastEntryTime != null && Duration.between(state.lastEntryTime, Instant.now())
                    .getSeconds() < config.getOiFlipMinHoldSeconds()) {
                return;
            }

            // Time-bias direction lock: if a confirmed operator move is active, don't let
            // OI_FLIP_REVERSE flip AWAY from the locked direction — only SL/trailing (checked
            // above, before this block) can still exit. Reuses the same gate as new entries.
            int wouldBeNewDirection = state.activeDirection * -1;
            if (timeBiasEngine != null && timeBiasEngine.isEnabled()
                    && !timeBiasEngine.isDirectionAllowed(indexType, wouldBeNewDirection)) {
                return;
            }

            double spot = momentumDetector.getSpot(indexType);
            int atm = indexType.roundToATM(spot);
            long[] oiChange = oiChangeWindowed(indexType, atm, 3);
            long ceOiChange = oiChange[0];
            long peOiChange = oiChange[1];

            // SLOT 6a (2026-07-08): the OI_FLIP_REVERSE trigger is DYNAMIC. The fixed 5,000-contract trigger
            // is pure noise on a high-churn tape (pre-expiry median band churn ~839k/min) — an ordinary tick
            // flipped winners out and, on 2026-07-06, cascaded into the daily-loss halt. Re-base it on the
            // dynamic significance floor so only a genuine opposing shift (≥ ~0.75× a typical minute today,
            // clamp-floored at 50k) can reverse an open position. Warm-up / flag-off → legacy 5,000 trigger.
            long flipFloor = 5000L;
            if (dynamicOiFloor != null && dynamicOiFloor.isWarmedUp(indexType)) {
                flipFloor = oiSignificanceFloor(indexType);
            }
            boolean oiFlipped = false;
            if (state.activeDirection == 1 && ceOiChange > peOiChange && ceOiChange > flipFloor) {
                oiFlipped = true;
            } else if (state.activeDirection == -1 && peOiChange > ceOiChange && peOiChange > flipFloor) {
                oiFlipped = true;
            }

            // ── V5 removal (docs/MARKET-MEMORY-V5-DESIGN.md §4): OI_FLIP_REVERSE OFF as a live exit ───
            // 3-day evidence: 7 exits, 0% win, −₹1.35k; price rebounded after 5/7. Suppression also
            // suppresses the flip re-entry that rode on this exit (capitulation-flip is its own path
            // and is unaffected). Audited for the 07-11 scoring. Re-enable = flip-reverse-live: true.
            if (oiFlipped && !flipReverseLive) {
                long nowMs = System.currentTimeMillis();
                if (nowMs - state.lastRideWinnerLogMs > 30_000) {
                    log.info("[ExitArbiter][{}] OI_FLIP_REVERSE SUPPRESSED (flip-reverse-live=false) — wouldFire dir={} profit={}% (V5 removal audit)",
                            indexType, state.activeDirection, String.format("%.1f", profitPct));
                    if (marketMemoryEngine != null) {
                        marketMemoryEngine.event("EXIT_SUPPRESSED", indexType.name(),
                                indexType + " " + state.activeStrike + " " + (state.activeDirection >= 0 ? "CE" : "PE"),
                                String.format("OI_FLIP_REVERSE wouldFire profit=%.1f%% (removal audit)", profitPct));
                    }
                    state.lastRideWinnerLogMs = nowMs;
                }
                oiFlipped = false;
            }
            if (oiFlipped && profitPct < 5) {
                // ── Ride confirmed winners (2026-07-07) — don't flip AWAY from a still-confirmed winner ──────
                // OI_FLIP_REVERSE only fires below +5% (winners above +5% are already never flipped). When the
                // trade is a confirmed winner (profit >= min-profit-pct AND operator still confirms this side),
                // hold it and let the trailing-stop/hard-SL manage rather than churning out on an OI dip. Only
                // ever defers a WINNER — a flat/losing trade (profit < min-profit-pct) or a genuinely flipped
                // direction is unaffected and flips/exits exactly as today.
                if (rideConfirmedWinner(indexType, state, profitPct)) {
                    long nowMs = System.currentTimeMillis();
                    if (nowMs - state.lastRideWinnerLogMs > 30_000) {
                        log.info("[OIMomentum][{}] OI_FLIP_REVERSE DEFERRED (ride-winners) — profit={}% + operator confirms dir={} — holding the confirmed winner",
                                indexType, String.format("%.1f", profitPct), state.activeDirection);
                        state.lastRideWinnerLogMs = nowMs;
                    }
                    return;
                }
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
                            oiDir(indexType, ceOiChange, peOiChange), pcrDir(revPcr), revPcr,
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
        accumulateDailyPnl(state, trade != null ? trade.getTradeId() : null, pnl);
        // §3.7: feed the source-quality tracker (realized P&L%) for MFE/PnL-aware sizing (no-op unless enabled).
        if (sourcePerformanceTracker != null) sourcePerformanceTracker.record("oi_momentum", profitPct);
        checkDailyCircuitBreaker(state);
    }

    /**
     * Fold a trade's P&L into the daily total + loser stats EXACTLY ONCE, deduped by tradeId. The
     * self-close path ({@link #updateDailyPnl}) and the external-close auto-heal path both route through
     * here, so the daily-loss circuit breaker can never see the same trade counted twice (or, with the
     * estimate vs broker-realized sources, counted by both).
     */
    private void accumulateDailyPnl(IndexState state, String tradeId, double pnl) {
        if (tradeId != null && !state.dailyPnlCountedTrades.add(tradeId)) {
            log.debug("[OIMomentum] dailyPnl already counted for tradeId={} — skipping duplicate ({})", tradeId, pnl);
            return;
        }
        state.dailyPnl += pnl;
        // v3 adaptive circuit-breaker: track loser stats for avg-loser threshold.
        if (pnl < 0) {
            state.totalLossesPnl += pnl;
            state.totalLossesCount.incrementAndGet();
        }
    }

    /**
     * v3 daily loss circuit-breaker. Two independent triggers, either trips a timed pause:
     *   (a) absolute rupee floor: dailyPnl < -dailyLossLimitRupees (when > 0) → PERMANENT halt.
     *   (b) adaptive multiple-of-avg-loser: dailyPnl < -(mult × avg-loser) → 30-MIN pause.
     *   (c) consecutive-loss-halt-count → 30-MIN pause (allows recovery trades).
     * Adaptive triggers use 30-min timed pause so recovery is possible on volatile days.
     * Absolute floor remains a permanent day halt (catastrophic protection).
     */
    private void checkDailyCircuitBreaker(IndexState state) {
        if (state.haltedForDay) return;
        double limit = config.getDailyLossLimitRupees();
        if (limit > 0 && state.dailyPnl < -limit) {
            state.haltedForDay = true;
            state.haltExpiresAt = null; // permanent — absolute floor is catastrophic
            log.warn("[OIMomentum] Day halted PERMANENTLY — dailyPnl={} < -{} (absolute floor)",
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
                state.haltExpiresAt = Instant.now().plus(Duration.ofMinutes(30)); // timed: 30 min
                log.warn("[OIMomentum] Day PAUSED 30min — dailyPnl={} < -{}×avgLoser({}). Auto-resumes at {}",
                        String.format("%.0f", state.dailyPnl), mult, String.format("%.0f", avgLoser),
                        state.haltExpiresAt);
                if (telegramAlertService != null) {
                    telegramAlertService.systemAlert(String.format(
                            "⏸️ OIMomentum: PAUSED 30min — P&L ₹%.0f below %.1f×avg-loser (₹%.0f). Auto-resumes.",
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
        if (state.tradesToday.get() >= computeDynamicMaxTrades(indexType, state)) {
            recordGateReject(indexType, state, "max_trades_day", diagnostics);
            return;
        }
        // Time-bias direction lock: if a confirmed operator move is active in the opposite direction, block.
        if (timeBiasEngine != null && timeBiasEngine.isEnabled()
                && !timeBiasEngine.isDirectionAllowed(indexType, direction)) {
            recordGateReject(indexType, state, "time_bias_lock:opposite_to_confirmed_move", diagnostics);
            return;
        }
        if (state.consecutiveLosses.get() >= config.getConsecutiveLossPause()) {
            recordGateReject(indexType, state, "consecutive_loss_pause", diagnostics);
            return;
        }
        if (state.lastSlTime != null && Duration.between(state.lastSlTime, Instant.now()).getSeconds() < config.getCooldownAfterSlSeconds()) {
            // Override: skip SL cooldown if reversal detector has active opposing signal
            boolean reversalOverride = operatorReversalDetector != null
                    && operatorReversalDetector.isEnabled()
                    && operatorReversalDetector.getSignal(indexType).isActive();
            if (!reversalOverride) {
                long remaining = config.getCooldownAfterSlSeconds()
                        - Duration.between(state.lastSlTime, Instant.now()).getSeconds();
                recordGateReject(indexType, state, "sl_cooldown:" + remaining + "s", diagnostics);
                return;
            }
        }
        // P2 #19: Allow event spikes to bypass MarketGuard
        String mgBlock = marketGuard.longPremiumBlockReason();
        if (!reason.startsWith("SPIKE:") && mgBlock != null) {
            recordGateReject(indexType, state,
                    MarketGuard.normalizeLongPremiumRejectToken("market_guard_entry", mgBlock), diagnostics);
            return;
        }
        LocalTime now = LocalTime.now(IST);
        // Item 2c: expiry entry cutoff = expiryEntryCutoffTime (15:15); non-expiry = squareoff − 10 min.
        if (!now.isBefore(entryCutoffTime(indexType))) {
            recordGateReject(indexType, state, "squareoff_window", diagnostics);
            return;
        }

        // Check DB config for paper/live mode
        var dbConfig = getCachedConfig(indexType);
        boolean paperMode = (dbConfig != null) ? dbConfig.isPaperTrading() : config.isPaperTrading();

        int atm = indexType.roundToATM(spot);
        // For directional entries, prefer ITM/ATM — never OTM.
        // CE entry: pick strike AT or BELOW spot (more delta, less theta decay)
        // PE entry: pick strike AT or ABOVE spot
        // This prevents the "77100 CE when spot is 77030" problem (OTM = guaranteed decay).
        int interval = indexType.strikeInterval();
        if (direction > 0 && atm > spot) {
            atm -= interval; // CE: step down to ITM
        } else if (direction < 0 && atm < spot) {
            atm += interval; // PE: step up to ITM
        }

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
        // Fade exemption (mirrors the SPIKE MarketGuard exemption above at line ~4186): a chop fade is a
        // mean-reversion trade, not a directional-conviction trade, so the V3 directional pipeline — which
        // requires a momentum candidate — would always veto it (v3_skip). When bypass-v3 is on we skip ONLY
        // the V3 directional pipeline; the fade then falls through to legacy ATM-strike + legacy 1-lot sizing
        // and its CHOP_FADE bracket, still passing every genuine downstream risk gate below (charges, spread,
        // capital, hard lot-cap, kill-switch, entry-window). Flag-gated + killable via fade-mode.bypass-v3.
        boolean isFadeEntry = reason != null && reason.contains("RANGE_EDGE_FADE");
        // V5 strike fidelity (MEMORY_AVALANCHE): read-and-clear the forced strike for this attempt.
        final Integer avForcedStrike = state.forcedEntryStrike;
        state.forcedEntryStrike = null;

        boolean fadeBypassingV3 = isFadeEntry && fadeBypassV3;
        if (fadeBypassingV3) {
            log.info("[OIMomentum][{}] V3 BYPASS (fade): RANGE_EDGE_FADE routed past V3 directional pipeline "
                    + "→ legacy ATM/1-lot + downstream risk gates. reason={}", indexType, reason);
        }
        if (config.isV3Enabled() && v3EntryPipeline != null && v3MarketContext != null && !fadeBypassingV3
                && avForcedStrike == null) { // V5: avalanche entries keep their own strike — skip the V3 picker
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

                // Item 3/4: on a strong operator move (high conviction), let V3's picker also consider an
                // ITM strike. Gated by the operator-move master + the itm-on-conviction sub-flag; when
                // either is off or conviction isn't met, highConvItm=false → V3 picks exactly as before.
                boolean highConvItm = operatorMoveModeEnabled && itmOnConvictionEnabled
                        && highConvictionActive(indexType);
                if (highConvItm) {
                    log.info("[OIMomentum][{}] OPERATOR_MOVE: high-conviction ITM strike enabled for V3 "
                            + "(opScore={} oiVel={}% flip={})", indexType,
                            String.format("%.0f", operatorScoreOf(indexType)),
                            String.format("%.2f", oiVelPctOf(indexType)),
                            capitulationFlipActive(indexType));
                }
                var v3Decision = v3EntryPipeline.evaluate(
                        indexType, direction, reason,
                        diagnostics != null ? diagnostics.momentumMagnitudePct() : 0.0,
                        spot, diagnostics != null ? diagnostics.vix() : 0.0,
                        v3Snap, resolver, baseLots, highConvItm, itmDepth);

                if (config.isV3ShadowMode()) {
                    log.info("[OIMomentum][{}] V3 SHADOW: {} reason={} pattern={} gates={}/4 lots={}",
                            indexType, v3Decision.skip() ? "SKIP" : "ENTER", v3Decision.reason(),
                            v3Decision.pattern() != null ? v3Decision.pattern().name() : "-",
                            v3Decision.gateVerdict() != null ? v3Decision.gateVerdict().passedCount() : 0,
                            v3Decision.lots());
                } else {
                    // Live V3 — its decision is binding.
                    if (v3Decision.skip()) {
                        // Mirror the SHADOW branch's visibility: a live v3 veto was previously silent (only a
                        // recordGateReject), making "why didn't this entry fire?" invisible in the logs.
                        log.info("[OIMomentum][{}] V3 LIVE: SKIP reason={} pattern={} gates={}/4",
                                indexType, v3Decision.reason(),
                                v3Decision.pattern() != null ? v3Decision.pattern().name() : "-",
                                v3Decision.gateVerdict() != null ? v3Decision.gateVerdict().passedCount() : 0);
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

        // ── Final ITM/ATM guard (applies AFTER V3 override) ──
        // Regardless of which path picked the strike (legacy ATM rounding or V3 multi-strike picker),
        // never enter an OTM option for a directional trade. OTM decays faster and has less delta.
        // FULL clamp (2026-07-02): step ALL the way to the nearest ITM/ATM strike, not just one interval —
        // a strike 2+ intervals OTM (fast-moving underlying / stale spot snapshot) was previously left 1
        // interval OTM. Bounded: strikeInterval() is always > 0, and each step moves strictly toward spot.
        int guardInterval = Math.max(1, indexType.strikeInterval());
        while (optType == OptionType.CE && atm > spot) {
            atm -= guardInterval; // CE above spot = OTM → step toward ITM until at/below spot
        }
        while (optType == OptionType.PE && atm < spot) {
            atm += guardInterval; // PE below spot = OTM → step toward ITM until at/above spot
        }
        // ── V5 MEMORY_AVALANCHE strike fidelity (docs/MARKET-MEMORY-V5-DESIGN.md §3) ──────────────
        // The avalanche signal is STRIKE-SPECIFIC: the replay's +₹105k traded the exact strike whose
        // OI avalanched, ITM or OTM alike (deep-ITM 23800 CE and far strikes were among the winners).
        // So a forced strike wins over the V3 picker AND the ITM/ATM clamp above. Premium sanity
        // (15-700, matching the replay) is enforced at the quote below.
        if (avForcedStrike != null && avForcedStrike > 0) {
            atm = avForcedStrike;
        }

        // ── Price-rule rejection throttle (2026-07-03, review-fixed) ────────────────────────
        // Placed AFTER the V3 override + final ITM/ATM clamp so the check uses the SAME final strike the
        // record site stores (the earlier pre-V3 placement never matched when V3 overrode the strike, so
        // the throttle silently never engaged). Per-strike map — ATM oscillation at a boundary no longer
        // clears the other strike's suppression. If the engine rejected THIS strike for "re-entry above
        // last buy/sell" within the last 120s, skip silently: the premium hasn't moved enough, we'd just
        // re-run the same rejection (order attempt + decision persist). Non-price rejections not suppressed.
        {
            Instant rejAt = state.priceRuleRejectByStrike.get(atm);
            if (rejAt != null) {
                // TAPE-AWARE throttle (2026-07-03): normally suppress re-attempts on a just-rejected strike
                // for 120s (kills the flat-market reject-spam). BUT when a genuine VOLUME SURGE is live on
                // this strike, shorten the suppression to 10s so the Conviction-Trend Override (evaluated
                // downstream in ExecutionEngine) actually GETS attempts during the surge windows it fires
                // on. Without this the 120s throttle starves CTO of the very moves it exists for (proven on
                // the 24350PE +18% move: CTO's gate fired at ~11 windows, but the 120s throttle blocked the
                // re-attempts). Still bounded (<=1 attempt / 10s during a surge) — not spam.
                boolean moveLive = false;
                if (oiDivergenceMonitor != null && oiDivergenceMonitor.isEnabled()) {
                    try {
                        var vol = oiDivergenceMonitor.evaluateVolumeSurge(indexType, atm, optType.name());
                        moveLive = vol.valid() && vol.surge();
                    } catch (Exception ignore) { /* tape hiccup -> keep the full 120s suppression */ }
                }
                long throttleSec = moveLive ? 10 : 120;
                if (Duration.between(rejAt, Instant.now()).getSeconds() < throttleSec) {
                    return; // within the (shortened-if-surging) suppression window — skip silently
                }
                state.priceRuleRejectByStrike.remove(atm); // window elapsed — allow a fresh attempt
            }
        }

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
        // V5 note: avalanche premium bounds are NOT special-cased here — the existing PER-UNDERLYING
        // min/max entry premium (Index Config UI, enforced in ExecutionEngine's entry gates) governs
        // avalanche entries exactly like every other entry. One knob, one place.
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

        // ── Dynamic gates (LIVE): regime-conditioned lot scaling ──
        // Scale the lot COUNT by the regime conviction multiplier (0.5–1.5), then re-expand to shares.
        // Bounded to ≥1 lot; the charges gate (below) and RiskEngine/LotSizeValidator (downstream) still
        // apply their caps, so this only ever sizes WITHIN existing limits. Inactive engine ⇒ no change.
        if (dynamicGateEngine != null && dynamicGateEngine.isActive()) {
            double lotMult = dynamicGateEngine.getValues(indexType).lotSizeMultiplier();
            // Layer outcome-feedback lot multiplier (guarded, live)
            if (outcomeFeedbackEngine != null && outcomeFeedbackEngine.isEnabled()) {
                String regime = dynamicGateEngine.getValues(indexType).regime();
                double ofLotMult = outcomeFeedbackEngine.getLotMultiplier(regime);
                lotMult = Math.max(0.5, Math.min(1.5, lotMult * ofLotMult));
            }
            if (lotMult != 1.0) {
                int contractsPerLot = indexType.lotSize();
                int baseLots = Math.max(1, lotSize / contractsPerLot);
                int scaledLots = Math.max(1, (int) Math.round(baseLots * lotMult));
                if (scaledLots != baseLots) {
                    log.debug("[OIMomentum][{}] DYNAMIC_LOTS {}→{} lots (mult={})",
                            indexType, baseLots, scaledLots, String.format("%.2f", lotMult));
                    lotSize = scaledLots * contractsPerLot;
                }
            }
        }

        // ── Liquidity/Slippage-Aware Entry Sizing ──────────────────────────────────
        // Scale lots DOWN when bid/ask depth is thin (wide spread or low depth ratio).
        // Prevents large orders from moving the market against themselves (slippage).
        // Uses the ATM option's bid/ask spread and depth imbalance as a liquidity proxy.
        String liqTag = ""; // stamped into the entry reason so the report can measure liquidity-sized trades
        if (premium != null && premium.doubleValue() > 0) {
            int contractsPerLot = indexType.lotSize();
            int currentLots = Math.max(1, lotSize / contractsPerLot);
            if (currentLots > 1) { // Only scale down multi-lot trades
                double liquidityMult = computeLiquidityMultiplier(indexType, direction);
                if (liquidityMult < 1.0) {
                    int liquidityLots = Math.max(1, (int) Math.round(currentLots * liquidityMult));
                    if (liquidityLots < currentLots) {
                        log.debug("[OIMomentum][{}] LIQUIDITY_SIZING {}→{} lots (liqMult={})",
                                indexType, currentLots, liquidityLots, String.format("%.2f", liquidityMult));
                        lotSize = liquidityLots * contractsPerLot;
                        liqTag = String.format(" liqMult=%.2f", liquidityMult);
                    }
                }
            }
        }

        // ── P1.2 (2026-06-26): separate "no option price" from "trade too small" ──
        // If the ATM option LTP is missing/stale (premium<=0), the charges gate below computes
        // net = 0 − charges < floor and rejects as "charges_filter:net_below_floor" — masking a FEED
        // problem as a cost decision and zero-filling atmCeLast in the captured features (this was 92.7%
        // of 06-23's charges rejections). Surface it as its own blocker so the report can tell the two
        // apart, and so a stale-LTP setup isn't silently discarded as "unprofitable".
        if (premium == null || premium.doubleValue() <= 0) {
            recordGateReject(indexType, state, "premium_unavailable", diagnostics);
            return;
        }

        // ── Falling-knife guard: don't buy into a >5% premium drop in the last 5 minutes ──
        // If the option premium has been falling sharply, entering now means buying into a
        // losing trend. Wait for a bounce/confirmation instead of catching the knife.
        if (instrumentKey != null && premium.doubleValue() > 0) {
            double recentDrop = premiumVelocityTracker.getVelocity(indexType).maxPremiumVelocityPct();
            int premDir = premiumVelocityTracker.getVelocity(indexType).direction();
            // Premium velocity is negative when premiums are falling in our entry direction
            // For CE entry (direction > 0): premium falling = premDir < 0
            // For PE entry (direction < 0): premium falling = premDir > 0 (PE rises when market falls)
            boolean fallingKnife = (direction > 0 && premDir < 0 && recentDrop >= 5.0)
                    || (direction < 0 && premDir > 0 && recentDrop >= 5.0);
            if (fallingKnife) {
                recordGateReject(indexType, state, "falling_knife:premDrop=" + String.format("%.1f", recentDrop) + "%", diagnostics);
                return;
            }
        }

        // ── Charges-aware entry filter ──
        if (!config.isChargesGateV2Enabled()) {
            // LEGACY fallback — R1 FIX: gate on NET of REAL round-trip charges, not a flat ₹500.
            // The old flat ₹500 floor rejected ~all entries (1-lot NIFTY ATM nets ~₹122 on a 3% scalp
            // while real charges are ~₹55). Now require gross(3%) − realCharges ≥ chargesGateMinNetProfit.
            double gross = premium.doubleValue() * lotSize * config.getChargesGateTargetPct();
            double charges = OptionsChargesEstimator.roundTripCharges(premium.doubleValue(), lotSize);
            double net = gross - charges;
            if (net < config.getChargesGateMinNetProfit()) {
                log.debug("[OIMomentum][{}] Charges filter: net ₹{} < ₹{} (gross ₹{} − charges ₹{}) "
                        + "premium={} qty={}, skipping (not cost-effective)",
                        indexType, String.format("%.0f", net), String.format("%.0f", config.getChargesGateMinNetProfit()),
                        String.format("%.0f", gross), String.format("%.0f", charges), premium, lotSize);
                recordGateReject(indexType, state, "charges_filter:net_below_floor", diagnostics);
                return;
            }
        } else {
            // v2: gate on NET of REAL round-trip charges. If the trade is just under the floor,
            // size lots UP to clear it (fixed costs amortise as lots grow) — but never above the
            // capital cap — before rejecting. This stops good low-premium signals being dropped.
            int contractsPerLot = indexType.lotSize();
            int currentLots = Math.max(1, lotSize / contractsPerLot);
            int maxLots = currentLots;
            if (config.isChargesGateResizeUp() && capitalAllocator != null && premium.doubleValue() > 0) {
                int openTradesNow = (int) indexStates.values().stream()
                        .filter(s -> s.activeTradeId != null).count();
                int capLots = capitalAllocator.getMaxLotsForTrade(indexType, premium, openTradesNow);
                maxLots = Math.max(currentLots, capLots); // never below current, never above capital cap
            }
            double targetPct = config.getChargesGateTargetPct();
            double minNet = config.getChargesGateMinNetProfit();
            int chosenLots = -1;
            double chosenNet = 0.0;
            for (int lots = currentLots; lots <= maxLots; lots++) {
                int qty = lots * contractsPerLot;
                double gross = premium.doubleValue() * qty * targetPct;
                double charges = OptionsChargesEstimator.roundTripCharges(premium.doubleValue(), qty);
                double net = gross - charges;
                // Dynamic floor: if minNet is 0, require net ≥ multiplier × charges (adapts to premium)
                double effectiveFloor = minNet > 0 ? minNet
                        : charges * config.getChargesGateMinNetMultiplier();
                if (net >= effectiveFloor) { chosenLots = lots; chosenNet = net; break; }
            }
            if (chosenLots < 0) {
                // Even at the max allowed lots the trade can't clear the net floor — skip.
                double charges = OptionsChargesEstimator.roundTripCharges(premium.doubleValue(), maxLots * contractsPerLot);
                double effectiveFloor = minNet > 0 ? minNet : charges * config.getChargesGateMinNetMultiplier();
                log.debug("[OIMomentum][{}] Charges gate v2: net < ₹{} even at {} lots "
                        + "(premium={}, target={}%, floor={}×charges) — skipping", indexType,
                        String.format("%.0f", effectiveFloor), maxLots, premium,
                        String.format("%.1f", targetPct * 100),
                        String.format("%.1f", config.getChargesGateMinNetMultiplier()));
                recordGateReject(indexType, state, "charges_filter:net_below_floor", diagnostics);
                return;
            }
            if (chosenLots > currentLots) {
                log.info("[OIMomentum][{}] Charges gate v2: resized {}→{} lots to clear ₹{} net "
                        + "(premium={})", indexType, currentLots, chosenLots,
                        String.format("%.0f", chosenNet), premium);
            }
            lotSize = chosenLots * contractsPerLot; // resized quantity flows to decision + execution
        }

        // ── HARD LOT CAP (2026-07-01) ──────────────────────────────────────────
        // Final, placement-time clamp of the lot count to the FRESHLY-resolved per-user maxLotsPerTrade.
        // Defends against ANY upstream sizing path (V3 ConvictionSizer, liquidity resize, charges-gate reroute)
        // and any transient profile mis-resolution placing MORE than the current profile allows — the 12:11
        // incident sized 3 lots for a BALANCED (2-lot) primary via a V3 WRITER_SQUEEZE while the config had
        // briefly resolved AGGRESSIVE. This guarantees the order never exceeds the profile's lot ceiling.
        {
            int perLot = indexType.lotSize();
            int maxLotsCap = Math.max(1, globalConfigService.getMaxLotsPerTrade());
            int finalLots = perLot > 0 ? lotSize / perLot : lotSize;
            if (finalLots > maxLotsCap) {
                log.warn("[OIMomentum][{}] HARD LOT-CAP: clamping {} lots → {} (per-user maxLotsPerTrade) qty {}→{}",
                        indexType, finalLots, maxLotsCap, lotSize, maxLotsCap * perLot);
                lotSize = maxLotsCap * perLot;
            }
        }

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
                java.util.List.of("OI_MOMENTUM[" + indexType + "]: " + reason + liqTag)
        );

        // FAST-OI: overlay the fast-OI capture context so the tuning SIGNAL row (and lastEntryDiagnostics,
        // which the exit reuses) records the regime + operator lead-time for the loop-back analysis.
        if (diagnostics != null) {
            diagnostics = diagnostics.withFastOi(fastOiEnabled, captureOiWindowSec(), operatorSignalAgeSec(indexType));
        }
        // MTF: guarantee the multi-timeframe context lands on the recorded SIGNAL row + lastEntryDiagnostics
        // (which the exit reuses) even if this enter() path was reached with a diag that missed the overlay.
        if (diagnostics != null && mtfContextService != null && mtfLiveEnabled) {
            try {
                var mtf = mtfContextService.getContext(indexType);
                if (mtf != null && mtf.available()) {
                    int aligned = mtf.alignsWith(direction) ? 1 : (mtf.isCounterTrend(direction) ? -1 : 0);
                    diagnostics = diagnostics.withMtf(mtf.htfBias(), mtf.regime(), aligned);
                }
            } catch (Exception ignore) { /* non-fatal: capture only */ }
        }

        String decisionKey = com.algo.trade.reporting.SignalDecisionKey.from(decision);
        state.lastEntryDecisionKey = decisionKey;
        state.lastEntryDiagnostics = diagnostics;

        // A3 (2026-07-03): for OI-UNAVAILABLE entries (created from the operator signal with no OI to
        // confirm), require SUBSTITUTE tape confirmation — a same-strike volume surge + directional price
        // thrust. When enforcing (entry-confirm.shadow=false) and the strike is liquid enough to judge yet
        // shows NO confirmation, ABORT the entry before the order is placed (executeEntry is below). On a
        // cold buffer / illiquid strike it never blocks (A1's score gate stays the control). Exception-safe.
        if (oiDivergenceMonitor != null && oiDivergenceMonitor.isEnabled()
                && diagnostics != null && !diagnostics.oiAvailable()) {
            boolean blockNoConfirm = oiDivergenceMonitor.evaluateEntryConfirmation(indexType, atm, direction,
                    premium != null ? premium.doubleValue() : 0.0, instrumentKey, diagnostics.entryCase());
            if (blockNoConfirm) {
                log.warn("[OIMomentum][{}] ENTRY BLOCKED (A3 no-OI): {} strike={} — OI unavailable AND no tape "
                        + "confirmation (volume surge + price thrust). reason={}", indexType, optType, atm, reason);
                // Emit a named gate-reject so the A3 block shows in the tuning report's blocker list +
                // opportunity-cost (did A3 throw winners?). Captured like any other strategy-level reject.
                recordGateReject(indexType, state, "a3_no_oi_tape_confirm:strike=" + atm, diagnostics);
                return;
            }
        }

        // Unified tuning pipeline. No-op when capture toggle is
        // off (default) or when adapter/recorder beans aren't present (legacy wiring).
        if (tuningEventRecorder != null && oiMomentumCaptureAdapter != null) {
            // DATA-6: one tuning SIGNAL per episode. SUSTAINED_DRIFT (and re-fires of the same setup)
            // call enter() every tick — without dedupe each tick wrote a signal row (06-19: 22 signals
            // for ~3 episodes). Dedupe on the STABLE spikeEpisodeId (INDEX-SPIKE-{strikeBucket}-{dir}-
            // {date}) — NOT the decision key, which rehashes every tick via decision.timestamp()=now and
            // would never match. Fall back to decisionKey only when no episode id is present (non-drift
            // single-shot paths, which don't burst). Collapse repeats within a 10-min window.
            String episodeId = (diagnostics != null && diagnostics.spikeEpisodeId() != null
                    && !diagnostics.spikeEpisodeId().isBlank())
                    ? diagnostics.spikeEpisodeId()
                    : decisionKey;
            boolean sameEpisode = episodeId != null
                    && episodeId.equals(state.lastSignalEpisodeKey)
                    && state.lastSignalEpisodeTime != null
                    && Duration.between(state.lastSignalEpisodeTime, Instant.now()).toMinutes() < 10;
            if (sameEpisode) {
                log.debug("[OIMomentum][{}] DATA-6: suppressing duplicate signal for episode {}",
                        indexType, episodeId);
            } else {
                try {
                    com.algo.trade.tuning.SignalEvent tuningSignal =
                            oiMomentumCaptureAdapter.buildSignalEvent(decision, diagnostics, indexType,
                                    premium, decisionKey);
                    tuningEventRecorder.record(tuningSignal);
                    state.lastSignalEpisodeKey = episodeId;
                    state.lastSignalEpisodeTime = Instant.now();
                } catch (Exception ex) {
                    log.warn("[OIMomentum] dual-write to TuningEventRecorder failed (non-fatal): {}",
                            ex.getMessage());
                }
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
            // Price-rule rejection throttle: if the execution engine rejected with a price-rule reason,
            // record the FINAL strike (same value the check compares) so we don't re-attempt every tick
            // for the next 120s. Per-strike map — coexists with other strikes' suppressions.
            if (state.activeTradeId == null && !result.accepted()) {
                boolean priceRuleReject = result.reasons().stream()
                        .anyMatch(r -> r.contains("Re-entry above last buy") || r.contains("Re-entry above last sell"));
                // C7 (2026-07-09): veto-rail rejections re-attempt every tick just like price-rule
                // ones did (24 DEAD vetoes on one strike in a burst, each persisting a decision row).
                // Same throttle map — the state veto won't clear within seconds either.
                // F10-6 (2026-07-10): premium-cap rejections spam identically (₹466-vs-cap-397 logged
                // every second for minutes) — the cap won't move within the 120s window either.
                boolean vetoReject = result.reasons().stream()
                        .anyMatch(r -> r.contains("EntryPipeline veto") || r.contains("exceeds adjusted max"));
                if (priceRuleReject || vetoReject) {
                    state.priceRuleRejectByStrike.put(atm, Instant.now());
                }
            }
        }

        if (state.activeTradeId != null) {
            state.activeDirection = direction;
            state.activeStrike = atm;
            state.lastEntryTime = Instant.now();
            state.peakPrice = premium.doubleValue();
            state.tradesToday.incrementAndGet();
            enteredCount.incrementAndGet();
            // Notify DynamicGateEngine of trade for intraday activity monitoring
            if (dynamicGateEngine != null) dynamicGateEngine.recordTrade(indexType);
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

    /**
     * Compute liquidity multiplier for entry sizing based on bid/ask depth imbalance.
     *
     * <p>Logic:</p>
     * <ul>
     *   <li>Spread > 3% → 0.5× lots (wide spread = low liquidity, risk slippage)</li>
     *   <li>Spread 2–3% → 0.75× lots (moderate concern)</li>
     *   <li>Depth ratio < 0.3 (thin side) → 0.7× lots</li>
     *   <li>Normal liquidity → 1.0× (no reduction)</li>
     * </ul>
     *
     * @return multiplier [0.5, 1.0] to scale down lot count
     */
    private double computeLiquidityMultiplier(IndexType indexType, int direction) {
        try {
            double spot = liveInstrumentCache.getFuturesPrice(indexType);
            if (spot <= 0) return 1.0;
            int atm = indexType.roundToATM(spot);

            // Get the ATM option in our entry direction
            String optType = direction > 0 ? "CE" : "PE";
            var expiry = expiryCalendar.getCurrentExpiry(indexType);
            var optOpt = liveInstrumentCache.getOption(indexType, atm, optType, expiry);
            if (optOpt.isEmpty()) return 1.0;
            var opt = optOpt.get();

            double bid = opt.getBestBid();
            double ask = opt.getBestAsk();
            if (bid <= 0 || ask <= 0) return 1.0;

            // Spread-based scaling
            double mid = (bid + ask) / 2.0;
            double spreadPctVal = (ask - bid) / mid * 100;

            double spreadMult = 1.0;
            if (spreadPctVal > 3.0) spreadMult = 0.5;
            else if (spreadPctVal > 2.0) spreadMult = 0.75;

            // Depth-based scaling: bid qty / (bid qty + ask qty)
            long bidQty = opt.getBestBidQty();
            long askQty = opt.getBestAskQty();
            double depthMult = 1.0;
            if (bidQty > 0 || askQty > 0) {
                // For BUY entries, we want ask-side depth (we're lifting asks)
                // For SELL entries, we want bid-side depth (we're hitting bids)
                double ourSideRatio = direction > 0
                        ? (double) askQty / Math.max(1, bidQty + askQty)  // buying: ask depth matters
                        : (double) bidQty / Math.max(1, bidQty + askQty); // selling: bid depth matters
                if (ourSideRatio < 0.2) depthMult = 0.5;       // very thin on our side
                else if (ourSideRatio < 0.3) depthMult = 0.7;  // somewhat thin
            }

            // Also update the cached spread change for DecisionAggregator
            // (approximation: spread vs a 1% baseline)
            IndexState state = indexStates.get(indexType);
            if (state != null) {
                state.lastSpreadChange = spreadPctVal - 1.0; // delta from "normal" 1% spread
            }

            return Math.min(spreadMult, depthMult); // take the more conservative
        } catch (Exception e) {
            return 1.0; // fail-safe: normal sizing
        }
    }

    private void closePosition(IndexType indexType, IndexState state, TradeEntity trade, double currentPrice, String reason) {
        if (oiDivergenceMonitor != null && trade != null) oiDivergenceMonitor.onPositionClosed(trade.getTradeId());
        boolean reversal = reason != null && reason.contains("REVERSE");
        boolean profitable = trade.getEntryPrice() != null && currentPrice > trade.getEntryPrice().doubleValue();

        // V5 episode memory (docs/MARKET-MEMORY-V5-DESIGN.md §2.3): every closed MEMORY_AVALANCHE trade
        // updates the pattern's intraday record; 3 closed losers on an index+side suspend the pattern
        // for the rest of the day (deep-tier events override the suspension at the entry side).
        if (marketMemoryEngine != null && trade != null && trade.getEntryPrice() != null
                && trade.getEntryPrice().signum() > 0
                && (state.avalancheEntryActive
                    || (trade.getEntryReason() != null && trade.getEntryReason().contains("MEMORY_AVALANCHE")))
                && episodeNotYetRecorded(trade)) {
            double netPct = (currentPrice - trade.getEntryPrice().doubleValue())
                    / trade.getEntryPrice().doubleValue() * 100.0;
            marketMemoryEngine.recordEpisode(indexType.name(),
                    state.activeDirection >= 0 ? "CE" : "PE", "AVALANCHE", state.activeStrike, netPct);
            // Replay semantics: the avalanche re-entry cooldown is PER INSTRUMENT and arms at EXIT.
            if (state.activeStrike > 0) {
                state.avalancheCooldownUntilMs.put(
                        state.activeStrike + "|" + (state.activeDirection >= 0 ? "CE" : "PE"),
                        System.currentTimeMillis() + avalancheCooldownSec * 1000L);
            }
        }

        // Time-bias adaptive learning: record this trade's outcome against the anchor time
        // it was entered near, so getHistoricalBias() can eventually preload conviction for
        // anchors that are consistently directional across sessions.
        if (timeBiasEngine != null && timeBiasEngine.isEnabled() && state.lastEntryTime != null
                && state.activeDirection != 0) {
            try {
                LocalTime entryLocalTime = LocalTime.ofInstant(state.lastEntryTime, IST);
                timeBiasEngine.recordOutcome(indexType, entryLocalTime, state.activeDirection, profitable);
            } catch (Exception ex) {
                log.debug("[OIMomentum][{}] time-bias outcome recording failed: {}", indexType, ex.getMessage());
            }
        }

        // Module performance tracking: record win/loss by signal source
        if (modulePerformanceTracker != null && trade.getEntryReason() != null) {
            String module = extractModuleFromReason(trade.getEntryReason());
            modulePerformanceTracker.recordTrade(module, profitable);
        }

        // Track profitable exits for re-entry boost
        if (profitable) {
            state.lastProfitableExitTime = Instant.now();
            state.lastProfitableExitDirection = state.activeDirection;
            // Cross-index consecutive loss reset: a profitable trade on ANY index proves the
            // market is tradeable — reset ALL indices' consecutive loss counters so a losing
            // streak on NIFTY doesn't permanently block SENSEX/BANKNIFTY entries (and vice versa).
            for (IndexState otherState : indexStates.values()) {
                if (otherState.consecutiveLosses.get() > 0) {
                    otherState.consecutiveLosses.set(0);
                }
            }
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
        // P0-5 FIX: an exit must follow a CONFIRMED broker outcome. closeTrade marks the trade
        // CLOSED only on a broker fill (or hands a pending exit to the watchdog); on placement
        // failure it returns rejected and the broker position stays OPEN. Previously this method
        // ignored the result and ALWAYS cleared internal state, so a failed exit left the bot
        // believing it was flat while the real position ran unprotected. Now we only clear state
        // when the close was accepted; on failure we keep managing the position so it is retried.
        com.algo.trade.execution.ExecutionResult exitResult;
        try {
            exitResult = executionEngine.closeTrade(trade.getTradeId(), BigDecimal.valueOf(currentPrice), reason);
        } catch (Exception e) {
            log.error("[OIMomentum][{}] Close threw for tradeId={}: {} — keeping position OPEN for retry",
                    indexType, trade.getTradeId(), e.getMessage());
            exitResult = null;
        }
        if (exitResult == null || !exitResult.accepted()) {
            String why = exitResult == null ? "exception" : String.join("; ", exitResult.reasons());
            // P0-5b benign case: an exit is already placed and in flight (the engine's close guard
            // de-dupes repeat attempts). Keep managing quietly — do NOT alert or clear state.
            if (why != null && why.toLowerCase().contains("close already in progress")) {
                log.info("[OIMomentum][{}] Exit already in progress for tradeId={} — keeping position managed",
                        indexType, trade.getTradeId());
                return;
            }
            log.error("[OIMomentum][{}] EXIT FAILED for tradeId={} reason={} ({}) — position STILL OPEN; "
                    + "NOT clearing internal state, will retry on next tick",
                    indexType, trade.getTradeId(), reason, why);
            if (telegramAlertService != null) {
                telegramAlertService.systemAlert(String.format(
                        "🚨 OIMomentum[%s] EXIT FAILED for %s (%s) — position STILL OPEN, will retry. Check broker.",
                        indexType, trade.getInstrumentKey(), why));
            }
            return; // keep activeTradeId so the position remains managed and the exit is retried
        }
        // P0-5b: "accepted" does NOT mean filled. A pending LIMIT exit is accepted (watchdog tracking)
        // while the broker position is still OPEN. Clear internal state ONLY when the exit is CONFIRMED
        // filled; otherwise keep managing so the position stays protected. The watchdog closes it on
        // fill (managePosition then auto-heals the state), and releases the close guard if the exit is
        // cancelled unfilled so a fresh exit can be placed.
        boolean exitFilled = exitResult.order()
                .map(o -> o.status() == com.algo.trade.domain.OrderStatus.COMPLETE)
                .orElse(false);
        if (!exitFilled) {
            log.warn("[OIMomentum][{}] Exit order placed but PENDING fill for tradeId={} (status={}) — "
                    + "keeping position managed until the fill is confirmed",
                    indexType, trade.getTradeId(),
                    exitResult.order().map(o -> o.status().name()).orElse("?"));
            return; // do NOT clear activeTradeId
        }
        try {
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
                            // Prefer the persisted entry key (survives restart); fall back to in-memory state.
                            trade.getEntryCorrelationKey() != null ? trade.getEntryCorrelationKey() : state.lastEntryDecisionKey,
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
        state.avalancheEntryActive = false;
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
        // PART 2 (2026-07-08): when PCR is non-decisive (default), it is demoted to a WEAK secondary
        // confirmation — a small +8 when it agrees and NO penalty when it opposes, so PCR can never swing
        // an already-directional trade over/under the conviction floor. Legacy weighting (+20 / −5) only
        // when oi-momentum.pcr-nondecisive.enabled=false.
        if (pcrNonDecisive) {
            if (pcrDir == momentumDir) {
                score += 8;
                sig.append(" PCR✓(+8)");
            } else if (pcrDir != 0) {
                sig.append(" PCR✗(0)");
            }
        } else if (pcrDir == momentumDir) {
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

        // ── [+10] VWAP alignment — spot vs session VWAP confirms directional bias ──
        // Price above VWAP + bullish momentum = strong. Below VWAP + bearish = strong.
        // Opposing (bullish but below VWAP) = no penalty, just no bonus.
        if (v3MarketContext != null) {
            int vwapAlign = v3MarketContext.spotVsVwap(indexType,
                    liveInstrumentCache.getFuturesPrice(indexType));
            if (vwapAlign == momentumDir) {
                score += 10;
                sig.append(" VWAP✓(+10)");
            } else if (vwapAlign != 0 && vwapAlign != momentumDir) {
                sig.append(" VWAP✗(0)");
            }
        }

        // ── [±timeBiasHistoricalWeight] Time-anchor historical bias — learned per-clock-time lean ──
        // getHistoricalBias() only returns non-zero once ≥3 sessions have recorded a ≥70% skew for the
        // nearest 20-min anchor (see TimeBiasEngine.AnchorHistory#dominantDirection). Small bonus/penalty,
        // same shape as the MTF alignment block above.
        if (timeBiasEngine != null && timeBiasEngine.isEnabled()) {
            int histBias = timeBiasEngine.getHistoricalBias(indexType);
            if (histBias == momentumDir && histBias != 0) {
                score += timeBiasHistoricalWeight;
                sig.append(String.format(" TIMEBIAS✓(+%d)", timeBiasHistoricalWeight));
            } else if (histBias != 0 && histBias != momentumDir) {
                score -= timeBiasHistoricalWeight;
                sig.append(String.format(" TIMEBIAS✗(-%d)", timeBiasHistoricalWeight));
            }
        }

        // ── [±10] Pre-market bias — Gift Nifty gap + US futures + crude cues ──
        // Active only during opening drive (9:15–9:45). Adds directional conviction
        // from overnight global moves. After 9:45, pre-market data is stale.
        if (preMarketBiasScanner != null && preMarketBiasScanner.hasFreshData()) {
            LocalTime nowForPmBias = LocalTime.now(IST);
            if (nowForPmBias.isBefore(LocalTime.of(9, 45))) {
                int pmBonus = preMarketBiasScanner.getBiasBonus();
                // Only apply if pre-market bias ALIGNS with momentum direction
                // Bullish pre-market + bullish momentum → bonus. Opposing → penalty.
                if ((pmBonus > 0 && momentumDir > 0) || (pmBonus < 0 && momentumDir < 0)) {
                    score += Math.abs(pmBonus);
                    sig.append(String.format(" PM_BIAS(+%d)", Math.abs(pmBonus)));
                } else if ((pmBonus > 0 && momentumDir < 0) || (pmBonus < 0 && momentumDir > 0)) {
                    score -= Math.abs(pmBonus) / 2; // half penalty for opposing (don't veto, just reduce)
                    sig.append(String.format(" PM_BIAS(-%d)", Math.abs(pmBonus) / 2));
                }
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
            if (!oiAvailable) {
                // First 5 min (9:15–9:20): block entirely — auction noise, no OI data yet.
                // 9:20–9:30: allow with heavy penalty (-30) for momentum+PCR aligned (CASE2).
                // This permits strong opening momentum setups while still gating weak signals.
                if (now.isBefore(LocalTime.of(9, 20))) {
                    score = 0;
                    sig.append(" OPEN_AUCTION_BLOCK(=0)");
                } else {
                    score -= 30;
                    sig.append(" OPEN_NO_OI(-30)");
                }
            } else {
                int noisePenalty = oiConfirmed ? 10 : 20; // CASE1/3 → -10; CASE2/5 → -20
                score -= noisePenalty;
                sig.append(String.format(" OPEN_NOISE(-%d)", noisePenalty));
            }
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

        // ── [+0..N] Synthetic OI velocity (20 Jun 2026) ───────────────────────────
        // Tick-resolution build/unwind proxy from cumulative volume + premium direction,
        // bridging the ~3-minute gap between NSE OI prints (NSE rebroadcasts true OI only
        // every ~3 min, so real OI-change is structurally late). Adds a small bonus only when
        // the synthetic flow agrees with momentum. Both the detector's sampling and this bonus
        // are flag-gated (default OFF) — no effect until enabled and validated.
        if (syntheticOiVelocityDetector != null
                && syntheticOiVelocityDetector.isEnabled()
                && config.isSyntheticOiVelocityBonusEnabled()) {
            int synthBonus = syntheticOiVelocityDetector.alignmentBonus(
                    indexType, momentumDir, config.getSyntheticOiVelocityBonusPoints());
            if (synthBonus > 0) {
                var synth = syntheticOiVelocityDetector.getLatest(indexType);
                score += synthBonus;
                sig.append(String.format(" SYNTH_OI(+%d,conf=%d,vol=%d)",
                        synthBonus, synth.confidence(), synth.intervalVolume()));
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
            long[] oi1m = oiChangeWindowed(indexType, atm, 1);
            long oneMinTotal = Math.abs(oi1m[0]) + Math.abs(oi1m[1]);
            long threeMinTotal = Math.abs(ceOiChange) + Math.abs(peOiChange);
            long avgPerMin = threeMinTotal / 3;
            // SLOT 3 (2026-07-08): the magnitude gate is DYNAMIC — the band velocity must be "unusually
            // large for today" (robust z ≥ Z_HI) rather than clearing a fixed 100k. On a thin fresh-week day
            // the ATM band often sits below 100k, which killed this +10 acceleration bonus and left genuine
            // early trends under-sized; the z-gate revives it. The acceleration RATIO (≥ multiplier) is
            // already self-normalizing and is unchanged. Warm-up / flag-off → legacy fixed 100k floor.
            boolean magnitudeGate = (dynamicOiFloor != null && dynamicOiFloor.isWarmedUp(indexType))
                    ? dynamicOiFloor.isAnomalous(indexType, threeMinTotal)
                    : avgPerMin > 100_000;
            if (magnitudeGate && oneMinTotal >= (long)(avgPerMin * config.getOiAccelerationMultiplier())) {
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

    /**
     * P0.2 — true from the entry-window end through the 15:30 close. Used only to drive the capture-only
     * heartbeat so the last ~35 min is observed; it never affects entry/exit decisions.
     */
    private boolean isAfterEntryWindowDuringMarket() {
        LocalTime now = LocalTime.now(IST);
        LocalTime end = (entryWindowEnd != null) ? entryWindowEnd
                : LocalTime.parse(config.getEntryWindowEnd());
        return !now.isBefore(end) && now.isBefore(LocalTime.of(15, 30));
    }

    /**
     * P0.2 — capture-only heartbeat for the post-entry-window tail. Reuses the same full feature snapshot
     * ({@link #buildThrottleDiagnostics}) and recording path as a normal reject, tagged blocker={@code
     * entry_cutoff}, throttled to once per {@code captureAfterEntryWindowIntervalSec}. Does NOT call
     * {@code detectEntry}, set {@code lastRejectReason}, or open any position — it purely fills the
     * decision record for 14:55→15:30 so the close is no longer a blind spot.
     */
    private void recordEntryCutoffHeartbeat(IndexType indexType, IndexState state) {
        if (tuningEventRecorder == null || oiMomentumCaptureAdapter == null) {
            return;
        }
        Instant now = Instant.now();
        long everySec = Math.max(10, captureAfterEntryWindowIntervalSec);
        if (state.lastCutoffHeartbeatAt != null
                && Duration.between(state.lastCutoffHeartbeatAt, now).getSeconds() < everySec) {
            return;
        }
        state.lastCutoffHeartbeatAt = now;
        try {
            recordReject(indexType, state, "entry_cutoff",
                    buildThrottleDiagnostics(indexType, state, "entry_cutoff"));
        } catch (Exception ex) {
            log.debug("[OIMomentum] entry-cutoff heartbeat failed (non-fatal): {}", ex.getMessage());
        }
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
