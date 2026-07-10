package com.algo.trade.strategy.oimomentum;

import com.algo.trade.domain.IndexType;
import com.algo.trade.marketdata.LiveInstrumentCache;
import com.algo.trade.risk.MarketGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Time Bias Engine — detects scheduled operator moves at clock anchor times.
 *
 * <p>Operators move the market at predictable intervals (every 20 minutes: 9:20, 9:40,
 * 10:00, 10:20, etc.). This engine:</p>
 * <ol>
 *   <li>Monitors operator footprints (OI surge, volume spike, VIX movement) in a 3-minute
 *       window around each anchor time</li>
 *   <li>Requires ≥3 footprints to confirm a scheduled directional push</li>
 *   <li>Issues a direction lock (15 min minimum) when confirmed — the strategy holds direction</li>
 *   <li>Learns from recent sessions which anchors are consistently directional</li>
 * </ol>
 *
 * <h2>Direction Lock</h2>
 * When a time-based move is confirmed, the engine issues a lock:
 * <ul>
 *   <li>Minimum 15 minutes — no opposite entries allowed until lock expires</li>
 *   <li>The strategy's OI_FLIP_REVERSE is suppressed during a lock</li>
 *   <li>Only SL can exit during a lock (capital protection always overrides)</li>
 * </ul>
 */
@Component
public class TimeBiasEngine {

    private static final Logger log = LoggerFactory.getLogger(TimeBiasEngine.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** Anchor interval in minutes (every 20 min: 9:20, 9:40, 10:00, ...). */
    private static final int ANCHOR_INTERVAL_MIN = 20;
    /** Window (seconds) around an anchor to check for footprints. */
    private static final int ANCHOR_WINDOW_SEC = 180; // ±3 minutes
    /** Minimum footprints to confirm a scheduled move. */
    private static final int MIN_FOOTPRINTS = 3;
    /** Direction lock duration after confirmation (minutes). */
    private static final int LOCK_DURATION_MIN = 15;
    /** Minimum absolute VIX change within the anchor window to count as a "VIX movement" footprint. */
    private static final double VIX_MOVEMENT_THRESHOLD = 0.3;
    /** Minimum ATM premium velocity (%) to count as an independent "volume spike" footprint. */
    private static final double PREMIUM_VELOCITY_SPIKE_PCT = 4.0;

    private final LiveInstrumentCache liveInstrumentCache;
    private final MarketGuard marketGuard;
    private final PremiumVelocityTracker premiumVelocityTracker;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private AnchorBiasHistoryRepository biasRepo;

    /** DYNAMIC OI-shift (2026-07-08): optional; null (tests) / disabled → the fixed 30k surge floor stays. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private DynamicOiFloor dynamicOiFloor;

    /** Legacy net-OI surge floor for the direction-lock footprint (scaled up on high-OI days when dynamic). */
    @org.springframework.beans.factory.annotation.Value("${oi-momentum.time-bias.oi-surge-floor:30000}")
    private long oiSurgeFloor = 30_000L;

    /** Exponential decay constant — yesterday counts ~70%, 5-day-old counts ~17%. */
    private static final double DECAY_LAMBDA = 0.35;
    private static final int MIN_SESSIONS_FOR_BIAS = 3;
    private static final double BIAS_HIT_RATE_THRESHOLD = 0.65;
    /** How many days of dated outcomes to retain per anchor (matches the DB load window). */
    private static final int HISTORY_LOOKBACK_DAYS = 5;

    @Value("${oi-momentum.time-bias.enabled:true}")
    private boolean enabled;

    // Per-index direction locks
    private final Map<IndexType, DirectionLock> activeLocks = new ConcurrentHashMap<>();
    // Per-index footprint accumulators around anchor windows
    private final Map<IndexType, AnchorFootprints> anchorData = new ConcurrentHashMap<>();
    // Historical bias per anchor time (learned from recent sessions)
    private final Map<String, AnchorHistory> biasHistory = new ConcurrentHashMap<>();

    public record DirectionLock(
            int direction,       // +1 bullish, -1 bearish
            LocalTime anchorTime,
            Instant lockedAt,
            Instant expiresAt,
            int footprintsConfirmed,
            String evidence
    ) {
        public boolean isActive() {
            return Instant.now().isBefore(expiresAt);
        }
    }

    private static class AnchorFootprints {
        volatile LocalTime lastAnchorChecked = null;
        volatile int oiSurgeDirection = 0;
        volatile boolean volumeSpike = false;
        volatile boolean vixMovement = false;
        volatile boolean spotBreakout = false;
        volatile boolean pcrShift = false;
        volatile double spotAtAnchor = 0;
        volatile double vixAtAnchor = 0;
    }

    /**
     * Per-anchor outcome history, keyed by trade date so we can apply exponential decay —
     * a repeat pattern from yesterday matters a lot more than one from 5 days ago.
     */
    private static class AnchorHistory {
        record DatedOutcome(LocalDate date, boolean bullish) {}

        private final List<DatedOutcome> outcomes = new CopyOnWriteArrayList<>();

        /** Add one outcome (one trade's directional vote) and prune anything past the lookback window. */
        void add(LocalDate date, boolean bullish) {
            outcomes.add(new DatedOutcome(date, bullish));
            LocalDate cutoff = LocalDate.now(IST).minusDays(HISTORY_LOOKBACK_DAYS);
            outcomes.removeIf(o -> o.date().isBefore(cutoff));
        }

        int totalSessions() {
            return outcomes.size();
        }

        /**
         * Exponentially-decayed dominant direction. Requires at least {@link #MIN_SESSIONS_FOR_BIAS}
         * outcomes and a decayed hit-rate of at least {@link #BIAS_HIT_RATE_THRESHOLD} on one side.
         * Weight of an outcome N days old is {@code exp(-DECAY_LAMBDA * N)} (yesterday ≈ 70%, 5 days ≈ 17%).
         */
        int dominantDirection() {
            if (outcomes.size() < MIN_SESSIONS_FOR_BIAS) return 0;
            LocalDate today = LocalDate.now(IST);
            double weightedBull = 0, weightedBear = 0, totalWeight = 0;
            for (DatedOutcome o : outcomes) {
                long daysAgo = Math.max(0, ChronoUnit.DAYS.between(o.date(), today));
                double weight = Math.exp(-DECAY_LAMBDA * daysAgo);
                totalWeight += weight;
                if (o.bullish()) weightedBull += weight;
                else weightedBear += weight;
            }
            if (totalWeight <= 0) return 0;
            if (weightedBull / totalWeight >= BIAS_HIT_RATE_THRESHOLD) return 1;
            if (weightedBear / totalWeight >= BIAS_HIT_RATE_THRESHOLD) return -1;
            return 0;
        }
    }

    public TimeBiasEngine(LiveInstrumentCache liveInstrumentCache, MarketGuard marketGuard,
                           PremiumVelocityTracker premiumVelocityTracker) {
        this.liveInstrumentCache = liveInstrumentCache;
        this.marketGuard = marketGuard;
        this.premiumVelocityTracker = premiumVelocityTracker;
    }

    // ── Public API ──────────────────────────────────────────────────────

    /** Get the active direction lock for an index. Returns null if no lock active. */
    public DirectionLock getActiveLock(IndexType indexType) {
        if (!enabled) return null;
        DirectionLock lock = activeLocks.get(indexType);
        if (lock != null && lock.isActive()) return lock;
        return null;
    }

    /** Check if a direction is allowed (not locked in the opposite direction). */
    public boolean isDirectionAllowed(IndexType indexType, int direction) {
        DirectionLock lock = getActiveLock(indexType);
        if (lock == null) return true; // no lock = all directions allowed
        return lock.direction() == direction; // only same direction allowed during lock
    }

    /** Get historical bias for the nearest anchor time (learned from past sessions). */
    public int getHistoricalBias(IndexType indexType) {
        LocalTime anchor = nearestAnchor(LocalTime.now(IST));
        String key = indexType.name() + ":" + anchor;
        AnchorHistory history = biasHistory.get(key);
        return history != null ? history.dominantDirection() : 0;
    }

    public boolean isEnabled() { return enabled; }

    // ── Tick — called every second from OIMomentumStrategy ──────────────

    /**
     * Tick evaluation — checks if we're near an anchor time and accumulates footprints.
     * When enough footprints confirm at an anchor, issues a direction lock.
     */
    public void tick(IndexType indexType, long ceOiChange, long peOiChange, double pcrSlope, double spot) {
        if (!enabled) return;
        LocalTime now = LocalTime.now(IST);
        if (!isMarketHours(now)) return;

        // Nearest anchor (floor OR ceiling, whichever is closer) — makes the window truly
        // ±3 minutes around each anchor, not just the 3 minutes AFTER it.
        LocalTime anchor = nearestAnchor(now);
        long secsFromAnchor = Math.abs(secondsBetween(now, anchor));

        // Only evaluate within the anchor window (±3 minutes)
        if (secsFromAnchor > ANCHOR_WINDOW_SEC) return;

        AnchorFootprints fp = anchorData.computeIfAbsent(indexType, k -> new AnchorFootprints());

        // Reset if we moved to a new anchor
        if (!anchor.equals(fp.lastAnchorChecked)) {
            fp.lastAnchorChecked = anchor;
            fp.oiSurgeDirection = 0;
            fp.volumeSpike = false;
            fp.vixMovement = false;
            fp.spotBreakout = false;
            fp.pcrShift = false;
            fp.spotAtAnchor = spot;
            fp.vixAtAnchor = marketGuard.getCurrentVix();
        }

        // Accumulate footprints
        // 1. OI surge direction — DYNAMIC floor (2026-07-08). This footprint's SIGN sets the direction lock
        // that later gates entries/flips, so a fixed 30k net-OI floor let ordinary expiry churn register a
        // surge every anchor. Raise it in proportion to today's tape (clamp-low at 30k). Warm-up / off → 30k.
        long netOi = ceOiChange - peOiChange;
        long surgeFloor = dynamicOiFloor != null ? dynamicOiFloor.scaleThreshold(indexType, oiSurgeFloor) : oiSurgeFloor;
        if (Math.abs(netOi) > surgeFloor) {
            fp.oiSurgeDirection = netOi > 0 ? 1 : -1;
        }

        // 2. VIX movement — real delta from the VIX reading captured at anchor entry,
        // not just "VIX data is available" (which would be true almost every tick).
        double vix = marketGuard.getCurrentVix();
        if (vix > 0 && fp.vixAtAnchor > 0 && Math.abs(vix - fp.vixAtAnchor) >= VIX_MOVEMENT_THRESHOLD) {
            fp.vixMovement = true;
        }

        // 3. Spot breakout from anchor point
        if (fp.spotAtAnchor > 0 && spot > 0) {
            double movePct = Math.abs(spot - fp.spotAtAnchor) / fp.spotAtAnchor * 100;
            if (movePct >= 0.1) { // 0.1% move from anchor
                fp.spotBreakout = true;
            }
        }

        // 4. PCR shift
        if (Math.abs(pcrSlope) >= 0.03) {
            fp.pcrShift = true;
        }

        // 5. Volume spike — independent signal from ATM premium velocity (options order-flow
        // proxy), NOT the same OI delta used for footprint #1. Reusing netOi here would let a
        // single OI reading double-count as two footprints.
        if (premiumVelocityTracker != null) {
            double premVel = premiumVelocityTracker.getVelocity(indexType).maxPremiumVelocityPct();
            if (premVel >= PREMIUM_VELOCITY_SPIKE_PCT) {
                fp.volumeSpike = true;
            }
        }

        // Count confirmed footprints
        int footprints = 0;
        if (fp.oiSurgeDirection != 0) footprints++;
        if (fp.volumeSpike) footprints++;
        if (fp.vixMovement) footprints++;
        if (fp.spotBreakout) footprints++;
        if (fp.pcrShift) footprints++;

        // Confirm and lock if enough footprints
        if (footprints >= MIN_FOOTPRINTS && getActiveLock(indexType) == null) {
            // Determine direction from OI surge + spot move
            int direction = fp.oiSurgeDirection;
            if (direction == 0 && spot > fp.spotAtAnchor) direction = 1;
            else if (direction == 0 && spot < fp.spotAtAnchor) direction = -1;

            if (direction != 0) {
                Instant now2 = Instant.now();
                DirectionLock lock = new DirectionLock(
                        direction, anchor, now2,
                        now2.plus(Duration.ofMinutes(LOCK_DURATION_MIN)),
                        footprints,
                        String.format("OI=%d vol=%b vix=%b spot=%b pcr=%b",
                                fp.oiSurgeDirection, fp.volumeSpike, fp.vixMovement,
                                fp.spotBreakout, fp.pcrShift));
                activeLocks.put(indexType, lock);
                log.info("[TimeBias][{}] DIRECTION_LOCK {} at anchor {} ({}/5 footprints, lock {}min) evidence=[{}]",
                        indexType, direction > 0 ? "BULL" : "BEAR", anchor,
                        footprints, LOCK_DURATION_MIN, lock.evidence());
            }
        }
    }

    /**
     * Record a trade outcome for bias learning (called after each trade closes).
     *
     * <p>Every closed trade votes for a market direction, not just profitable ones — a
     * losing trade in one direction is treated as evidence the market actually leaned the
     * other way. (Only counting wins would leave an anchor's history silent whenever we
     * happened to trade the wrong side there, defeating the point of learning it.)</p>
     */
    public void recordOutcome(IndexType indexType, LocalTime entryTime, int direction, boolean profitable) {
        LocalTime anchor = nearestAnchor(entryTime);
        String key = indexType.name() + ":" + anchor;
        AnchorHistory history = biasHistory.computeIfAbsent(key, k -> new AnchorHistory());
        boolean marketWentBullish = profitable == (direction > 0);
        history.add(LocalDate.now(IST), marketWentBullish);

        // Persist to DB for cross-session memory
        if (biasRepo != null) {
            try {
                biasRepo.save(new AnchorBiasHistory(
                        indexType.name(), anchor, direction, profitable, LocalDate.now(IST)));
            } catch (Exception e) {
                log.debug("[TimeBias] Failed to persist anchor outcome: {}", e.getMessage());
            }
        }
    }

    /** Load historical bias from DB on startup — gives cross-day memory. */
    @jakarta.annotation.PostConstruct
    void loadHistoricalBias() {
        if (biasRepo == null || !enabled) return;
        try {
            LocalDate cutoff = LocalDate.now(IST).minusDays(HISTORY_LOOKBACK_DAYS);
            List<AnchorBiasHistory> all = biasRepo.findByTradeDateAfter(cutoff);
            if (!all.isEmpty()) {
                log.info("[TimeBias] Loaded {} historical anchor outcomes from DB (last {} days)",
                        all.size(), HISTORY_LOOKBACK_DAYS);
                for (AnchorBiasHistory row : all) {
                    String key = row.getIndexType() + ":" + row.getAnchorTime();
                    AnchorHistory h = biasHistory.computeIfAbsent(key, k -> new AnchorHistory());
                    // Same "every trade votes" rule as recordOutcome() — a loss is evidence the
                    // market leaned the other way, not silence. Keep these consistent or the
                    // in-memory bias diverges from the persisted history after every restart.
                    boolean marketWentBullish = row.isProfitable() == (row.getDirection() > 0);
                    h.add(row.getTradeDate(), marketWentBullish);
                }
            }
        } catch (Exception e) {
            log.debug("[TimeBias] Failed to load historical bias: {}", e.getMessage());
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    /**
     * Nearest 20-min anchor to the given time — whichever of the floor/ceiling marks is
     * closer. This makes the anchor window genuinely symmetric (±3 min), instead of only
     * ever matching the 3 minutes AFTER the last passed anchor.
     */
    private LocalTime nearestAnchor(LocalTime time) {
        int totalMin = time.getHour() * 60 + time.getMinute();
        int floorTotal = (totalMin / ANCHOR_INTERVAL_MIN) * ANCHOR_INTERVAL_MIN;
        int ceilTotal = floorTotal + ANCHOR_INTERVAL_MIN;
        int chosenTotal = (ceilTotal - totalMin < totalMin - floorTotal) ? ceilTotal : floorTotal;
        chosenTotal = chosenTotal % (24 * 60);
        return LocalTime.of(chosenTotal / 60, chosenTotal % 60);
    }

    /** Signed seconds from {@code anchor} to {@code now}, using seconds-of-day (no LocalTime wraparound surprises). */
    private long secondsBetween(LocalTime now, LocalTime anchor) {
        return now.toSecondOfDay() - anchor.toSecondOfDay();
    }

    private boolean isMarketHours(LocalTime t) {
        return t.isAfter(LocalTime.of(9, 15)) && t.isBefore(LocalTime.of(15, 30));
    }
}
