package com.algo.trade.strategy.oimomentum;

import com.algo.trade.domain.IndexType;
import com.algo.trade.marketdata.ExpiryCalendar;
import com.algo.trade.marketdata.LiveInstrumentCache;
import com.algo.trade.domain.OptionInstrument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CROSS-SEGMENT LAG DETECTOR — exploits the microstructure sequencing:
 *
 *   Options OI spike → Market Maker hedge → Futures/ETF move → Spot index prints
 *
 * When OI has spiked but spot hasn't moved yet, there's a predictive window
 * where the lag will close. This service detects that window and provides
 * a timing bonus for earlier entries.
 *
 * Three detection modes:
 *
 * 1. INTRA-INDEX LAG: Options OI spiked for NIFTY but NIFTY spot hasn't moved yet.
 *    The futures/ETF hedge is pending — enter before spot catches up.
 *
 * 2. CROSS-INDEX LAG: NIFTY options spiked, BANKNIFTY options followed, but
 *    SENSEX hasn't moved. Sensex typically lags 2-5 minutes — enter Sensex options
 *    before the calculated index reflects the move.
 *
 * 3. LEAD-LAG DETECTION: Track which index consistently leads (usually NIFTY due to
 *    highest options liquidity) and which lags (usually SENSEX). When the leader
 *    signals, pre-enter the lagger.
 *
 * Integration:
 *   Called from OperatorIntentRadar.evaluate() as an additional signal.
 *   Also exposes getLagWindow() for the strategy to decide entry timing.
 *
 * Data sources:
 *   - LiveInstrumentCache: real-time OI, price, bid/ask for options
 *   - TickMomentumDetector (via spot prices): spot movement detection
 *   - ExpiryCalendar: expiry-day sensitivity adjustments
 */
@Service
public class CrossSegmentLagDetector {

    private static final Logger log = LoggerFactory.getLogger(CrossSegmentLagDetector.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final LiveInstrumentCache liveInstrumentCache;
    private final ExpiryCalendar expiryCalendar;

    /** Per-index tracking state */
    private final ConcurrentHashMap<IndexType, LagState> states = new ConcurrentHashMap<>();

    /** Historical lead-lag tracking: which index moves first most often */
    private final ConcurrentHashMap<IndexType, Integer> leadCount = new ConcurrentHashMap<>();

    /** Minimum OI change (contracts) to qualify as a "spike" */
    private static final long MIN_OI_SPIKE = 300_000L;

    /** Maximum spot movement (%) that still qualifies as "hasn't moved yet" */
    private static final double MAX_SPOT_MOVE_PCT = 0.05;

    /** Lag window expiry: if spot hasn't moved within this time, signal decays */
    private static final int LAG_WINDOW_SECONDS = 300; // 5 minutes

    /** Minimum spot movement in the leading index to confirm it has moved */
    private static final double LEADER_MIN_MOVE_PCT = 0.10;

    public CrossSegmentLagDetector(LiveInstrumentCache liveInstrumentCache,
                                    ExpiryCalendar expiryCalendar) {
        this.liveInstrumentCache = liveInstrumentCache;
        this.expiryCalendar = expiryCalendar;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Evaluate lag opportunity for the given index.
     *
     * @param indexType the index being evaluated
     * @param momentumDir the momentum direction (+1 bullish, -1 bearish)
     * @return lag signal with bonus and metadata
     */
    public LagSignal evaluate(IndexType indexType, int momentumDir) {
        LagState state = states.computeIfAbsent(indexType, LagState::new);
        int bonus = 0;
        StringBuilder signals = new StringBuilder();

        // Mode 1: Intra-index lag (OI spiked, spot flat)
        int intraLag = evaluateIntraIndexLag(indexType, state, momentumDir);
        if (intraLag > 0) {
            bonus += intraLag;
            signals.append(String.format(" INTRA_LAG(+%d)", intraLag));
        }

        // Mode 2: Cross-index lag (leader moved, this index hasn't)
        int crossLag = evaluateCrossIndexLag(indexType, state, momentumDir);
        if (crossLag > 0) {
            bonus += crossLag;
            signals.append(String.format(" CROSS_LAG(+%d)", crossLag));
        }

        // Mode 3: Historical lead-lag pattern
        int leadLag = evaluateLeadLagPattern(indexType, momentumDir);
        if (leadLag > 0) {
            bonus += leadLag;
            signals.append(String.format(" LEAD_LAG(+%d)", leadLag));
        }

        bonus = Math.min(15, bonus); // Cap lag bonus at 15

        boolean lagWindowOpen = intraLag > 0 || crossLag > 0;
        int lagRemainingSec = lagWindowOpen ? computeRemainingLagSeconds(state) : 0;

        return new LagSignal(bonus, signals.toString(), lagWindowOpen, lagRemainingSec,
                state.leadingIndex, state.lagDirection);
    }

    /**
     * Tick update — call every second to track OI spikes and spot movements.
     */
    public void tick(IndexType indexType) {
        LagState state = states.computeIfAbsent(indexType, LagState::new);
        double spot = liveInstrumentCache.getFuturesPrice(indexType);
        if (spot <= 0) return;

        // Track spot price at the time OI spike was detected
        long now = System.currentTimeMillis();

        // Detect fresh OI spike
        int atm = indexType.roundToATM(spot);
        long[] oiChange = liveInstrumentCache.getAtmOiChange(indexType, atm, 3, 1);
        long totalOiChange = Math.abs(oiChange[0]) + Math.abs(oiChange[1]);

        if (totalOiChange >= MIN_OI_SPIKE && !state.oiSpikeActive) {
            // New OI spike detected — record spot at this moment
            state.oiSpikeActive = true;
            state.oiSpikeTimeMs = now;
            state.spotAtOiSpike = spot;
            state.oiSpikeDirection = deriveOiDir(oiChange[0], oiChange[1]);
            log.info("[LagDetector][{}] OI spike detected: Δ={} dir={} spot={}",
                    indexType, totalOiChange, state.oiSpikeDirection, spot);

            // Record this index as a "leader" for lead-lag tracking
            leadCount.merge(indexType, 1, Integer::sum);
        }

        // Check if lag window has expired
        if (state.oiSpikeActive && (now - state.oiSpikeTimeMs) > LAG_WINDOW_SECONDS * 1000L) {
            state.oiSpikeActive = false;
            log.debug("[LagDetector][{}] Lag window expired ({}s)", indexType, LAG_WINDOW_SECONDS);
        }

        // Check if spot has caught up (lag closed)
        if (state.oiSpikeActive && state.spotAtOiSpike > 0) {
            double movePct = Math.abs(spot - state.spotAtOiSpike) / state.spotAtOiSpike * 100;
            if (movePct > LEADER_MIN_MOVE_PCT) {
                state.oiSpikeActive = false; // Lag has closed
                log.debug("[LagDetector][{}] Spot caught up: move={}%, lag closed", indexType, String.format("%.2f", movePct));
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MODE 1: INTRA-INDEX LAG (OI spiked, spot flat)
    // ═══════════════════════════════════════════════════════════════════════════

    private int evaluateIntraIndexLag(IndexType indexType, LagState state, int momentumDir) {
        if (!state.oiSpikeActive) return 0;
        if (state.oiSpikeDirection != momentumDir) return 0;

        // Check if spot is still flat since the OI spike
        double spot = liveInstrumentCache.getFuturesPrice(indexType);
        if (spot <= 0 || state.spotAtOiSpike <= 0) return 0;

        double movePct = Math.abs(spot - state.spotAtOiSpike) / state.spotAtOiSpike * 100;
        if (movePct > MAX_SPOT_MOVE_PCT) return 0; // Spot already moved — no lag

        // Lag confirmed: OI spiked but spot hasn't moved
        // Bonus scales with freshness (newer = higher bonus)
        long ageMs = System.currentTimeMillis() - state.oiSpikeTimeMs;
        if (ageMs < 60_000) return 8;  // First minute — strongest signal
        if (ageMs < 180_000) return 5; // 1-3 minutes — still valid
        return 3;                       // 3-5 minutes — weakening
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MODE 2: CROSS-INDEX LAG (leader moved, this index flat)
    // ═══════════════════════════════════════════════════════════════════════════

    private int evaluateCrossIndexLag(IndexType currentIndex, LagState state, int momentumDir) {
        // Check if another index has an active OI spike in the same direction
        // but THIS index hasn't moved yet
        for (IndexType other : List.of(IndexType.NIFTY, IndexType.BANKNIFTY, IndexType.SENSEX)) {
            if (other == currentIndex) continue;

            LagState otherState = states.get(other);
            if (otherState == null || !otherState.oiSpikeActive) continue;
            if (otherState.oiSpikeDirection != momentumDir) continue;

            // Other index has an active OI spike in same direction
            // Check if the other index's spot HAS moved (confirming the leader moved)
            double otherSpot = liveInstrumentCache.getFuturesPrice(other);
            if (otherSpot <= 0 || otherState.spotAtOiSpike <= 0) continue;

            double otherMovePct = Math.abs(otherSpot - otherState.spotAtOiSpike) / otherState.spotAtOiSpike * 100;
            if (otherMovePct < LEADER_MIN_MOVE_PCT) continue; // Leader hasn't confirmed yet

            // Now check if THIS index's spot is still flat
            double mySpot = liveInstrumentCache.getFuturesPrice(currentIndex);
            if (mySpot <= 0) continue;

            // Use a recent price anchor (if we have one)
            double myAnchor = state.spotAtOiSpike > 0 ? state.spotAtOiSpike : mySpot;
            double myMovePct = Math.abs(mySpot - myAnchor) / myAnchor * 100;

            if (myMovePct < MAX_SPOT_MOVE_PCT) {
                // Cross-index lag confirmed: leader moved, we haven't
                state.leadingIndex = other;
                state.lagDirection = momentumDir;
                log.debug("[LagDetector][{}] Cross-lag: {} moved {}% in dir={}, we're flat",
                        currentIndex, other, String.format("%.2f", otherMovePct), momentumDir);

                // Bonus based on how far the leader has moved
                if (otherMovePct > 0.20) return 8; // Strong leader move
                return 5; // Moderate leader move
            }
        }
        return 0;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MODE 3: HISTORICAL LEAD-LAG PATTERN
    // ═══════════════════════════════════════════════════════════════════════════

    private int evaluateLeadLagPattern(IndexType currentIndex, int momentumDir) {
        // If this index historically LAGS and a leader has an active spike, small bonus
        int myLeadCount = leadCount.getOrDefault(currentIndex, 0);
        int totalLeads = leadCount.values().stream().mapToInt(Integer::intValue).sum();
        if (totalLeads < 10) return 0; // Need minimum history

        double myLeadRatio = (double) myLeadCount / totalLeads;

        // This index rarely leads (< 20% of the time) — it's a lagger
        if (myLeadRatio < 0.20) {
            // Check if any leader currently has an active spike in our direction
            for (IndexType other : List.of(IndexType.NIFTY, IndexType.BANKNIFTY, IndexType.SENSEX)) {
                if (other == currentIndex) continue;
                LagState otherState = states.get(other);
                if (otherState != null && otherState.oiSpikeActive
                        && otherState.oiSpikeDirection == momentumDir) {
                    return 3; // Historical pattern: we tend to follow
                }
            }
        }
        return 0;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    private int deriveOiDir(long ceOiChange, long peOiChange) {
        if (peOiChange > ceOiChange && peOiChange > 0) return 1;
        if (ceOiChange > peOiChange && ceOiChange > 0) return -1;
        return 0;
    }

    private int computeRemainingLagSeconds(LagState state) {
        if (!state.oiSpikeActive) return 0;
        long elapsedMs = System.currentTimeMillis() - state.oiSpikeTimeMs;
        int remaining = LAG_WINDOW_SECONDS - (int) (elapsedMs / 1000);
        return Math.max(0, remaining);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // DATA TYPES
    // ═══════════════════════════════════════════════════════════════════════════

    /** Lag detection result */
    public record LagSignal(
            int bonus,
            String signals,
            boolean lagWindowOpen,
            int lagRemainingSeconds,
            IndexType leadingIndex,
            int lagDirection
    ) {}

    /** Per-index state for lag tracking */
    private static class LagState {
        final IndexType indexType;
        volatile boolean oiSpikeActive = false;
        volatile long oiSpikeTimeMs = 0;
        volatile double spotAtOiSpike = 0;
        volatile int oiSpikeDirection = 0;
        volatile IndexType leadingIndex = null;
        volatile int lagDirection = 0;

        LagState(IndexType indexType) { this.indexType = indexType; }
    }
}
