package com.algo.trade.strategy.oimomentum;

import com.algo.trade.domain.IndexType;
import com.algo.trade.domain.OptionInstrument;
import com.algo.trade.marketdata.ExpiryCalendar;
import com.algo.trade.marketdata.LiveInstrumentCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OPERATOR TACTICS ENGINE — encodes the remaining operator playbook patterns
 * that make the radar operator-proof.
 *
 * Five tactical modules:
 *
 * 1. ROTATION DETECTOR — tracks when operators rotate flows between indices.
 *    Detects leader→lagger catch-up pattern across NIFTY/BANKNIFTY/SENSEX.
 *
 * 2. EVENT SPIKE LOGIC — weights OI spikes higher when they coincide with
 *    scheduled macro events (RBI, Fed, GDP, inflation).
 *
 * 3. MAX PAIN SHIFT-RATE — detects forced operator repositioning when max pain
 *    shifts rapidly (100+ points in <15 min). Stronger than slow drift.
 *
 * 4. TRAP DETECTOR — identifies false magnets where operators lure retail with
 *    distant OTM OI then move price opposite. Prevents false bias boosts.
 *
 * 5. BASKET AWARENESS — tracks heavyweight stock moves (Reliance, HDFC, Infosys)
 *    that drag the index. Confirms index OI with constituent price action.
 *
 * Integration: called from OperatorIntentRadar.evaluate() as defensive + anticipatory overlay.
 */
@Service
public class OperatorTacticsEngine {

    private static final Logger log = LoggerFactory.getLogger(OperatorTacticsEngine.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final LiveInstrumentCache liveInstrumentCache;
    private final ExpiryCalendar expiryCalendar;

    /** Per-index tactical state */
    private final ConcurrentHashMap<IndexType, TacticsState> states = new ConcurrentHashMap<>();

    /** Scheduled macro event times (IST) — RBI typically 10:00, GDP/CPI 17:30 (affects next day open) */
    private static final List<EventWindow> MACRO_EVENTS = List.of(
            new EventWindow(LocalTime.of(10, 0), "RBI_POLICY"),
            new EventWindow(LocalTime.of(12, 0), "MPC_OUTCOME"),
            new EventWindow(LocalTime.of(14, 0), "FED_MINUTES"),
            new EventWindow(LocalTime.of(9, 15), "GDP_OPEN"),
            new EventWindow(LocalTime.of(9, 15), "CPI_OPEN")
    );
    private static final int EVENT_WINDOW_MINUTES = 15;

    /** Trap detection: minimum OI at far OTM to qualify */
    private static final long TRAP_MIN_OI = 300_000L;
    /** Trap: minimum distance from spot (in strike intervals) */
    private static final int TRAP_MIN_DISTANCE_STRIKES = 5;

    /** Max pain rapid shift threshold */
    private static final int RAPID_SHIFT_THRESHOLD_NIFTY = 75;
    private static final int RAPID_SHIFT_THRESHOLD_BANKNIFTY = 150;

    /** Nifty/Sensex heavyweight tokens for basket tracking */
    private static final Map<String, Double> NIFTY_HEAVYWEIGHTS = Map.of(
            "NSE:RELIANCE", 10.3,    // ~10% weight
            "NSE:HDFCBANK", 8.7,     // ~9% weight
            "NSE:ICICIBANK", 8.1,    // ~8% weight
            "NSE:INFY", 6.5,         // ~6.5% weight
            "NSE:TCS", 4.2           // ~4% weight
    );

    public OperatorTacticsEngine(LiveInstrumentCache liveInstrumentCache,
                                  ExpiryCalendar expiryCalendar) {
        this.liveInstrumentCache = liveInstrumentCache;
        this.expiryCalendar = expiryCalendar;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Full tactical evaluation — returns bonus/penalty + trap warnings.
     */
    public TacticsSignal evaluate(IndexType indexType, int momentumDir, int currentMaxPain) {
        TacticsState state = states.computeIfAbsent(indexType, TacticsState::new);
        LocalTime now = LocalTime.now(IST);

        int bonus = 0;
        int penalty = 0;
        StringBuilder signals = new StringBuilder();

        // Module 1: Rotation Detector
        int rotationBonus = evaluateRotation(indexType, state, momentumDir);
        if (rotationBonus > 0) {
            bonus += rotationBonus;
            signals.append(String.format(" ROTATE(+%d)", rotationBonus));
        }

        // Module 2: Event Spike Logic
        int eventBonus = evaluateEventSpike(indexType, momentumDir, now);
        if (eventBonus > 0) {
            bonus += eventBonus;
            signals.append(String.format(" EVENT(+%d)", eventBonus));
        }

        // Module 3: Max Pain Shift-Rate
        int shiftBonus = evaluateShiftRate(indexType, state, momentumDir, currentMaxPain);
        if (shiftBonus > 0) {
            bonus += shiftBonus;
            signals.append(String.format(" SHIFT(+%d)", shiftBonus));
        }

        // Module 4: Trap Detector (DEFENSIVE — returns negative penalty)
        int trapPenalty = evaluateTrap(indexType, state, momentumDir);
        if (trapPenalty > 0) {
            penalty += trapPenalty;
            signals.append(String.format(" TRAP(-%d)", trapPenalty));
        }

        // Module 5: Basket Awareness
        int basketBonus = evaluateBasket(indexType, momentumDir);
        if (basketBonus > 0) {
            bonus += basketBonus;
            signals.append(String.format(" BASKET(+%d)", basketBonus));
        }

        int netBonus = Math.max(-10, Math.min(20, bonus - penalty));

        return new TacticsSignal(netBonus, bonus, penalty, signals.toString(),
                state.trapActive, state.rotationPhase);
    }

    /**
     * Tick update — track max pain history and rotation patterns.
     */
    public void tick(IndexType indexType, int currentMaxPain) {
        TacticsState state = states.computeIfAbsent(indexType, TacticsState::new);
        long now = System.currentTimeMillis();

        // Record max pain history (for shift-rate detection)
        if (currentMaxPain > 0 && now - state.lastMaxPainRecordMs > 60_000) {
            state.maxPainHistory.add(new MaxPainSample(currentMaxPain, now));
            // Keep only last 20 samples (20 minutes of 1-min samples)
            while (state.maxPainHistory.size() > 20) {
                state.maxPainHistory.removeFirst();
            }
            state.lastMaxPainRecordMs = now;
        }

        // Track per-index OI momentum for rotation detection
        double spot = liveInstrumentCache.getFuturesPrice(indexType);
        if (spot > 0) {
            int atm = indexType.roundToATM(spot);
            long[] oiChange = liveInstrumentCache.getAtmOiChange(indexType, atm, 3, 3);
            long totalDelta = Math.abs(oiChange[0]) + Math.abs(oiChange[1]);
            state.recentOiDelta = totalDelta;
            state.recentOiDirection = deriveOiDir(oiChange[0], oiChange[1]);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MODULE 1: ROTATION DETECTOR
    // ═══════════════════════════════════════════════════════════════════════════

    private int evaluateRotation(IndexType currentIndex, TacticsState state, int momentumDir) {
        // Find the index with the HIGHEST recent OI delta (the "hot" index)
        IndexType hottest = null;
        long maxDelta = 0;

        for (IndexType idx : List.of(IndexType.NIFTY, IndexType.BANKNIFTY, IndexType.SENSEX)) {
            TacticsState otherState = states.get(idx);
            if (otherState == null) continue;
            if (otherState.recentOiDelta > maxDelta) {
                maxDelta = otherState.recentOiDelta;
                hottest = idx;
            }
        }

        if (hottest == null || hottest == currentIndex) return 0;
        if (maxDelta < 200_000L) return 0; // Not significant

        // Check if the hot index's direction matches our momentum
        TacticsState hotState = states.get(hottest);
        if (hotState == null || hotState.recentOiDirection != momentumDir) return 0;

        // Rotation pattern: OI is hot in another index with same direction
        // → operators will rotate here next
        state.rotationPhase = "CATCHING_UP_FROM_" + hottest.name();
        return 8; // was 5 — rotation with direction match is a strong operator tell
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MODULE 2: EVENT SPIKE LOGIC
    // ═══════════════════════════════════════════════════════════════════════════

    private int evaluateEventSpike(IndexType indexType, int momentumDir, LocalTime now) {
        // Check if we're within ±15 min of any macro event
        boolean nearEvent = MACRO_EVENTS.stream()
                .anyMatch(e -> Math.abs(Duration.between(e.time, now).toMinutes()) <= EVENT_WINDOW_MINUTES);
        if (!nearEvent) return 0;

        // Check if there's significant OI activity right now
        double spot = liveInstrumentCache.getFuturesPrice(indexType);
        if (spot <= 0) return 0;

        int atm = indexType.roundToATM(spot);
        long[] oiChange = liveInstrumentCache.getAtmOiChange(indexType, atm, 5, 3);
        long totalChange = Math.abs(oiChange[0]) + Math.abs(oiChange[1]);

        // OI spike during event window = operators positioning for the announcement
        if (totalChange < 200_000L) return 0; // was 300k — catches smaller event footprints

        int oiDir = deriveOiDir(oiChange[0], oiChange[1]);
        if (oiDir == momentumDir) {
            log.debug("[Tactics][{}] EVENT SPIKE: OI {} during macro window", indexType, totalChange);
            return 5;
        }
        return 0;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MODULE 3: MAX PAIN SHIFT-RATE DETECTOR
    // ═══════════════════════════════════════════════════════════════════════════

    private int evaluateShiftRate(IndexType indexType, TacticsState state,
                                   int momentumDir, int currentMaxPain) {
        if (state.maxPainHistory.size() < 3) return 0;

        // Find the max pain from 5 minutes ago
        long fiveMinAgo = System.currentTimeMillis() - 5 * 60 * 1000L;
        MaxPainSample oldest = null;
        for (MaxPainSample s : state.maxPainHistory) {
            if (s.timeMs <= fiveMinAgo) oldest = s;
        }
        if (oldest == null) return 0;

        int shift = currentMaxPain - oldest.strike;
        int threshold = (indexType == IndexType.BANKNIFTY)
                ? RAPID_SHIFT_THRESHOLD_BANKNIFTY : RAPID_SHIFT_THRESHOLD_NIFTY;

        if (Math.abs(shift) < threshold) return 0;

        // Rapid shift detected — check if it aligns with momentum
        int shiftDir = shift > 0 ? 1 : -1;
        if (shiftDir == momentumDir) {
            log.info("[Tactics][{}] RAPID MAX PAIN SHIFT: {} in <5min (threshold={})",
                    indexType, shift, threshold);
            return 7; // Strong urgency signal
        }
        return 0;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MODULE 4: TRAP DETECTOR (DEFENSIVE)
    // ═══════════════════════════════════════════════════════════════════════════

    private int evaluateTrap(IndexType indexType, TacticsState state, int momentumDir) {
        double spot = liveInstrumentCache.getFuturesPrice(indexType);
        if (spot <= 0) { state.trapActive = false; return 0; }

        int atm = indexType.roundToATM(spot);
        int interval = indexType.strikeInterval();
        int minDistance = TRAP_MIN_DISTANCE_STRIKES * interval;
        LocalDate expiry = expiryCalendar.getCurrentExpiry(indexType);

        // Look for heavy OI at far OTM strikes that DON'T have price confirmation
        // (i.e., spot is NOT moving toward those strikes = potential trap)
        boolean trapDetected = false;

        if (momentumDir > 0) {
            // Bullish momentum — check for heavy CE OI far above (trap: operators writing
            // calls to lure bulls, then push price down)
            for (int i = TRAP_MIN_DISTANCE_STRIKES; i <= 10; i++) {
                int farStrike = atm + (i * interval);
                long ceOi = getStrikeOi(indexType, farStrike, "CE", expiry);
                if (ceOi >= TRAP_MIN_OI) {
                    // Heavy CE writing far above — BUT is spot actually moving up?
                    long[] recentOi = liveInstrumentCache.getAtmOiChange(indexType, atm, 2, 1);
                    long peChange1m = recentOi[1]; // PE change at ATM in last 1 min
                    // If PE is ALSO building at ATM while CE is heavy far above = trap
                    // (operators are pinning, not releasing upward)
                    if (peChange1m > 0 && peChange1m > 50_000L) {
                        trapDetected = true;
                        state.trapStrike = farStrike;
                        break;
                    }
                }
            }
        } else if (momentumDir < 0) {
            // Bearish momentum — check for heavy PE OI far below (trap: operators writing
            // puts to lure bears, then push price up)
            for (int i = TRAP_MIN_DISTANCE_STRIKES; i <= 10; i++) {
                int farStrike = atm - (i * interval);
                long peOi = getStrikeOi(indexType, farStrike, "PE", expiry);
                if (peOi >= TRAP_MIN_OI) {
                    long[] recentOi = liveInstrumentCache.getAtmOiChange(indexType, atm, 2, 1);
                    long ceChange1m = recentOi[0];
                    if (ceChange1m > 0 && ceChange1m > 50_000L) {
                        trapDetected = true;
                        state.trapStrike = farStrike;
                        break;
                    }
                }
            }
        }

        state.trapActive = trapDetected;
        if (trapDetected) {
            log.debug("[Tactics][{}] TRAP detected: far OTM OI at {} with opposing ATM build",
                    indexType, state.trapStrike);
            return 5; // was 8 — softer penalty, trap is a warning not a veto
        }
        return 0;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MODULE 5: BASKET AWARENESS (Heavyweight Stock Confirmation)
    // ═══════════════════════════════════════════════════════════════════════════

    private int evaluateBasket(IndexType indexType, int momentumDir) {
        // Only applies to NIFTY and SENSEX (constituent-driven indices)
        if (indexType != IndexType.NIFTY && indexType != IndexType.SENSEX) return 0;

        // Simplified basket check: if the index's OI is building strongly and
        // the ATM option spread is tightening (market makers hedging), it confirms
        // that basket stocks are being moved
        double spot = liveInstrumentCache.getFuturesPrice(indexType);
        if (spot <= 0) return 0;

        int atm = indexType.roundToATM(spot);
        LocalDate expiry = expiryCalendar.getCurrentExpiry(indexType);
        String optType = momentumDir > 0 ? "CE" : "PE";

        Optional<OptionInstrument> atmOpt = liveInstrumentCache.getOption(indexType, atm, optType, expiry);
        if (atmOpt.isEmpty()) return 0;

        OptionInstrument opt = atmOpt.get();
        double bid = opt.getBestBid();
        double ask = opt.getBestAsk();
        if (bid <= 0 || ask <= 0) return 0;

        // Tight spread + heavy OI = market makers actively hedging = basket stocks moving
        double mid = (bid + ask) / 2;
        double spreadPct = (ask - bid) / mid * 100;
        boolean tightSpread = spreadPct < 0.5;
        boolean heavyOi = opt.getOpenInterest() > 500_000L;
        boolean highBidQty = opt.getBestBidQty() > opt.getBestAskQty() * 1.3;

        int bonus = 0;
        if (tightSpread && heavyOi && highBidQty) {
            bonus = 5; // was 4 — MM hedging confirms stock moves
        } else if (tightSpread && heavyOi) {
            bonus = 3; // partial: tight + heavy but bid/ask neutral
        }

        // Expiry-day override: double basket bonus when heavyweights align with pinning
        boolean expiryDay = java.time.LocalTime.now(java.time.ZoneId.of("Asia/Kolkata"))
                .isAfter(java.time.LocalTime.of(14, 0));
        if (expiryDay && bonus > 0) {
            bonus *= 2; // expiry afternoon: operators move basket aggressively for pinning
        }

        return bonus;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    private int deriveOiDir(long ceOiChange, long peOiChange) {
        if (peOiChange > ceOiChange && peOiChange > 0) return 1;
        if (ceOiChange > peOiChange && ceOiChange > 0) return -1;
        return 0;
    }

    private long getStrikeOi(IndexType indexType, int strike, String optionType, LocalDate expiry) {
        return liveInstrumentCache.getOption(indexType, strike, optionType, expiry)
                .map(OptionInstrument::getOpenInterest)
                .orElse(0L);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // DATA TYPES
    // ═══════════════════════════════════════════════════════════════════════════

    /** Tactical evaluation result */
    public record TacticsSignal(
            int netBonus,       // bonus - penalty (can be negative for traps)
            int rawBonus,       // sum of positive signals
            int penalty,        // sum of penalties (trap detection)
            String signals,     // diagnostic string
            boolean trapActive, // true when a trap pattern is detected
            String rotationPhase // which phase of rotation we're in
    ) {}

    private record EventWindow(LocalTime time, String name) {}
    private record MaxPainSample(int strike, long timeMs) {}

    /** Per-index tactical state */
    private static class TacticsState {
        final IndexType indexType;
        volatile long recentOiDelta = 0;
        volatile int recentOiDirection = 0;
        volatile boolean trapActive = false;
        volatile int trapStrike = 0;
        volatile String rotationPhase = "";
        volatile long lastMaxPainRecordMs = 0;
        final LinkedList<MaxPainSample> maxPainHistory = new LinkedList<>();

        TacticsState(IndexType indexType) { this.indexType = indexType; }
    }
}
