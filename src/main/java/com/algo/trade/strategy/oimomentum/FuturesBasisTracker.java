package com.algo.trade.strategy.oimomentum;

import com.algo.trade.domain.IndexType;
import com.algo.trade.marketdata.LiveInstrumentCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Futures Basis Tracker.
 *
 * <p>Tracks the futures premium/discount (basis) over time:
 * {@code basis = futures_price − spot_price}
 *
 * <p>Basis dynamics are a directional lead signal, especially for BANKNIFTY
 * where futures lead options by 30-60 seconds:
 * <ul>
 *   <li>Basis expanding (futures premium widening) → institutional buying in futures
 *       → bullish lead signal before options OI builds</li>
 *   <li>Basis contracting (futures discount deepening) → institutional selling
 *       → bearish lead signal</li>
 *   <li>Basis collapsing from premium → arbitrage unwind → spot about to catch up</li>
 * </ul>
 *
 * <p>Data source: {@link LiveInstrumentCache#getFuturesPrice(IndexType)} already stores
 * the futures/spot price (the field is named "futuresPrice" but is populated from the
 * index spot token — see the comment in LiveInstrumentCache). To get a true futures price
 * we need the futures instrument token subscribed separately. Until then, this tracker
 * uses the put-call-parity forward from {@code MarketContextService} as a proxy for the
 * futures price, which is the correct Black-76 forward and captures the real basis.
 *
 * <p>Integration: called from {@link CrossSegmentLagDetector#tick} and exposed to
 * {@link OperatorIntentRadar} as an additional signal module.
 */
@Service
public class FuturesBasisTracker {

    private static final Logger log = LoggerFactory.getLogger(FuturesBasisTracker.class);

    private final LiveInstrumentCache liveInstrumentCache;
    private final com.algo.trade.strategy.oimomentum.v3.MarketContextService marketContextService;

    /** Rolling basis samples per index (last 30 minutes at 1-sample/minute). */
    private final Map<IndexType, Deque<BasisSample>> history = new ConcurrentHashMap<>();
    private static final int MAX_SAMPLES = 30;

    /** Minimum absolute basis change (points) to qualify as expansion/contraction. */
    private static final double MIN_BASIS_CHANGE_NIFTY = 3.0;
    private static final double MIN_BASIS_CHANGE_BANKNIFTY = 10.0;
    private static final double MIN_BASIS_CHANGE_SENSEX = 5.0;

    public FuturesBasisTracker(LiveInstrumentCache liveInstrumentCache,
                                com.algo.trade.strategy.oimomentum.v3.MarketContextService marketContextService) {
        this.liveInstrumentCache = liveInstrumentCache;
        this.marketContextService = marketContextService;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Record a basis sample. Call once per minute from the strategy tick loop.
     * Uses the put-call-parity forward as the futures proxy.
     */
    public void sample(IndexType indexType) {
        double spot = liveInstrumentCache.getFuturesPrice(indexType);
        if (spot <= 0) return;

        // The MarketContextService stores the latest chain snapshot which contains
        // the put-call-parity forward (the real futures proxy). We use spot here
        // as a fallback — the basis will be 0 until a real futures token is subscribed,
        // but the slope logic still works once the forward is available.
        com.algo.trade.data.ChainSnapshot snap = marketContextService.getLatestSnapshot(indexType);
        double computedForward = spot; // default: no basis
        if (snap != null && snap.strikes() != null && !snap.strikes().isEmpty()) {
            // Use the ATM strike's CE/PE to compute put-call-parity forward
            int atm = snap.atmStrike();
            var atmStrike = snap.strikes().stream()
                    .filter(s -> s.strike() == atm)
                    .findFirst();
            if (atmStrike.isPresent()) {
                var s = atmStrike.get();
                if (s.ceLTP() > 0 && s.peLTP() > 0) {
                    // F = K + e^{rT}(C - P) — simplified: F ≈ K + (C - P) for short T
                    computedForward = atm + (s.ceLTP() - s.peLTP());
                }
            }
        }

        final double forward = computedForward;
        double basis = forward - spot;
        long now = System.currentTimeMillis();

        history.compute(indexType, (k, v) -> {
            Deque<BasisSample> d = v == null ? new ArrayDeque<>() : v;
            synchronized (d) {
                d.addLast(new BasisSample(now, spot, forward, basis));
                while (d.size() > MAX_SAMPLES) d.pollFirst();
            }
            return d;
        });
    }

    /**
     * Evaluate the basis signal for an index.
     *
     * @return BasisSignal with direction and strength
     */
    public BasisSignal evaluate(IndexType indexType) {
        Deque<BasisSample> samples = history.get(indexType);
        if (samples == null || samples.size() < 3) return BasisSignal.neutral();

        // Snapshot the deque to avoid ConcurrentModificationException if sample() runs concurrently.
        List<BasisSample> snapshot;
        synchronized (samples) {
            snapshot = new java.util.ArrayList<>(samples);
        }
        if (snapshot.size() < 3) return BasisSignal.neutral();

        BasisSample oldest = snapshot.get(0);
        BasisSample latest = snapshot.get(snapshot.size() - 1);

        double basisChange = latest.basis - oldest.basis;
        double minChange = minBasisChange(indexType);

        if (Math.abs(basisChange) < minChange) return BasisSignal.neutral();

        // Basis expanding (more positive) → bullish futures lead
        // Basis contracting (more negative) → bearish futures lead
        int direction = basisChange > 0 ? +1 : -1;

        // Strength: how many points of change per minute
        long minutes = Math.max(1, (latest.timestampMs - oldest.timestampMs) / 60_000);
        double changePerMin = Math.abs(basisChange) / minutes;

        // Bonus: 3-8 points based on rate of change
        int bonus;
        if (changePerMin > minChange * 2) bonus = 8;
        else if (changePerMin > minChange) bonus = 5;
        else bonus = 3;

        // Extra signal: basis collapsing from premium (arbitrage unwind → spot catch-up)
        boolean collapsingFromPremium = oldest.basis > minChange && latest.basis < oldest.basis * 0.3;

        log.debug("[Basis][{}] change={}pts over {}min dir={} bonus={} collapsing={}",
                indexType, String.format("%.1f", basisChange), minutes,
                direction > 0 ? "BULL" : "BEAR", bonus, collapsingFromPremium);

        return new BasisSignal(direction, bonus, basisChange, latest.basis, collapsingFromPremium);
    }

    /** Current basis (forward − spot). 0 if no data. */
    public double currentBasis(IndexType indexType) {
        Deque<BasisSample> samples = history.get(indexType);
        if (samples == null || samples.isEmpty()) return 0;
        BasisSample latest = samples.peekLast();
        return latest != null ? latest.basis : 0;
    }

    /** 5-minute basis slope (points per minute). Positive = expanding premium. */
    public double basisSlope5Min(IndexType indexType) {
        Deque<BasisSample> samples = history.get(indexType);
        if (samples == null || samples.size() < 2) return 0;

        // Snapshot to avoid iteration-during-modification.
        List<BasisSample> snapshot;
        synchronized (samples) {
            snapshot = new java.util.ArrayList<>(samples);
        }
        if (snapshot.size() < 2) return 0;

        long cutoff = System.currentTimeMillis() - 5 * 60_000L;
        BasisSample old = null;
        BasisSample latest = snapshot.get(snapshot.size() - 1);
        for (BasisSample s : snapshot) {
            if (s.timestampMs <= cutoff) old = s;
        }
        if (old == null) return 0;
        long mins = Math.max(1, (latest.timestampMs - old.timestampMs) / 60_000);
        return (latest.basis - old.basis) / mins;
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private double minBasisChange(IndexType indexType) {
        return switch (indexType) {
            case BANKNIFTY -> MIN_BASIS_CHANGE_BANKNIFTY;
            case SENSEX -> MIN_BASIS_CHANGE_SENSEX;
            default -> MIN_BASIS_CHANGE_NIFTY;
        };
    }

    // ── Data types ────────────────────────────────────────────────────────────

    private record BasisSample(long timestampMs, double spot, double forward, double basis) {}

    public record BasisSignal(
            int direction,          // +1 bullish, -1 bearish, 0 neutral
            int bonus,              // 0-8 additive points for strategy bias
            double basisChange,     // total change over the sample window
            double currentBasis,    // latest basis value
            boolean collapsingFromPremium  // arbitrage unwind signal
    ) {
        static BasisSignal neutral() {
            return new BasisSignal(0, 0, 0, 0, false);
        }

        public boolean hasSignal() { return direction != 0 && bonus > 0; }
    }
}
