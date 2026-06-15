package com.algo.trade.strategy.oimomentum;

import com.algo.trade.domain.IndexType;
import com.algo.trade.domain.OptionInstrument;
import com.algo.trade.marketdata.LiveInstrumentCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks ATM option premium velocity (% change per interval) to detect
 * gamma-driven moves that spot-momentum gates miss on expiry days.
 *
 * On near-zero-DTE, a 0.2% spot move can produce 15-20% premium swings
 * due to high gamma. This tracker captures that amplification and provides:
 *
 * 1. Premium velocity signals (CE/PE/straddle % change over rolling window)
 * 2. Gamma-regime detection (premium velocity >> spot velocity)
 * 3. A momentum boost that OI Momentum can use to relax spot-threshold gates
 *
 * Sampling frequency: called every tick from OIMomentumStrategy (1-second loop).
 * Storage: ring buffer of premium snapshots per index (5-minute rolling window).
 */
@Component
public class PremiumVelocityTracker {

    private static final Logger log = LoggerFactory.getLogger(PremiumVelocityTracker.class);

    /** Maximum samples to retain per index (5 min at 1 sample/sec = 300). */
    private static final int MAX_SAMPLES = 300;

    private final LiveInstrumentCache liveInstrumentCache;

    /** Minimum premium velocity % to consider a gamma signal (ATM CE or PE). */
    @Value("${oi-momentum.premium-velocity.signal-threshold-pct:3.0}")
    private double signalThresholdPct;

    /** Amplification ratio (premium velocity / spot velocity) to detect gamma regime. */
    @Value("${oi-momentum.premium-velocity.gamma-amplification-min:5.0}")
    private double gammaAmplificationMin;

    /** Window in seconds for velocity calculation. */
    @Value("${oi-momentum.premium-velocity.window-seconds:60}")
    private int windowSeconds;

    /** Per-index ring buffer of premium snapshots. */
    private final Map<IndexType, Deque<PremiumSnapshot>> snapshots = new ConcurrentHashMap<>();

    public PremiumVelocityTracker(LiveInstrumentCache liveInstrumentCache) {
        this.liveInstrumentCache = liveInstrumentCache;
    }

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Record current ATM premiums. Called every tick from OIMomentumStrategy.
     */
    public void sample(IndexType indexType, int atm, double spot) {
        double[] premiums = getAtmPremiums(indexType, atm);
        if (premiums[0] <= 0 && premiums[1] <= 0) return;

        Deque<PremiumSnapshot> ring = snapshots.computeIfAbsent(indexType, k -> new ArrayDeque<>(MAX_SAMPLES + 1));
        // ArrayDeque is not thread-safe; guard mutation against concurrent getVelocity()
        // reads (e.g. from status/diagnostics threads) to avoid ConcurrentModificationException.
        synchronized (ring) {
            ring.addLast(new PremiumSnapshot(Instant.now(), premiums[0], premiums[1], spot));
            while (ring.size() > MAX_SAMPLES) {
                ring.removeFirst();
            }
        }
    }

    /**
     * Compute premium velocity over the configured window.
     * Returns the maximum of |CE velocity| and |PE velocity|.
     */
    public PremiumVelocity getVelocity(IndexType indexType) {
        Deque<PremiumSnapshot> ring = snapshots.get(indexType);
        if (ring == null) {
            return PremiumVelocity.NONE;
        }

        // Snapshot latest + baseline under the ring's lock (fast), then compute outside it.
        PremiumSnapshot latest;
        PremiumSnapshot baseline = null;
        synchronized (ring) {
            if (ring.size() < 5) {
                return PremiumVelocity.NONE;
            }
            latest = ring.peekLast();
            Instant cutoff = latest.timestamp().minusSeconds(windowSeconds);
            // Find the oldest sample within the window
            for (PremiumSnapshot s : ring) {
                if (!s.timestamp().isBefore(cutoff)) {
                    baseline = s;
                    break;
                }
            }
        }
        if (baseline == null || baseline == latest) {
            return PremiumVelocity.NONE;
        }

        double ceVelocity = baseline.cePremium() > 0
                ? (latest.cePremium() - baseline.cePremium()) / baseline.cePremium() * 100.0
                : 0;
        double peVelocity = baseline.pePremium() > 0
                ? (latest.pePremium() - baseline.pePremium()) / baseline.pePremium() * 100.0
                : 0;
        double spotVelocity = baseline.spot() > 0
                ? Math.abs(latest.spot() - baseline.spot()) / baseline.spot() * 100.0
                : 0;

        // Straddle velocity (combined premium change)
        double baselineStraddle = baseline.cePremium() + baseline.pePremium();
        double latestStraddle = latest.cePremium() + latest.pePremium();
        double straddleVelocity = baselineStraddle > 0
                ? (latestStraddle - baselineStraddle) / baselineStraddle * 100.0
                : 0;

        // Determine direction from the RISING leg only. Comparing absolute magnitudes
        // would let the *crashing* leg dominate — near expiry the cheaper leg often moves
        // a larger %, so an abs-comparison can suppress a valid signal (both branches fail
        // when the losing leg's |move| exceeds the winning leg's). Use signed velocities:
        // whichever leg is rising above threshold (and rising at least as much) sets dir.
        int direction = 0;
        if (ceVelocity > signalThresholdPct && ceVelocity >= peVelocity) {
            direction = 1;  // CE rising = bullish premium move
        } else if (peVelocity > signalThresholdPct && peVelocity >= ceVelocity) {
            direction = -1; // PE rising = bearish premium move
        }

        // Gamma regime: premium is amplifying spot moves significantly. Floor the spot
        // velocity denominator (don't zero it) so the PUREST gamma case — premium flying
        // while spot is nearly flat — yields a LARGE amplification, not 0. The previous
        // "spotVelocity > 0.01 ? ratio : 0" turned the detector OFF exactly when spot was
        // flattest, which is the scenario this is meant to catch.
        double maxPremVelocity = Math.max(Math.abs(ceVelocity), Math.abs(peVelocity));
        double amplification = maxPremVelocity / Math.max(spotVelocity, 0.01);
        boolean gammaRegime = amplification >= gammaAmplificationMin && maxPremVelocity >= signalThresholdPct;

        return new PremiumVelocity(ceVelocity, peVelocity, straddleVelocity,
                spotVelocity, amplification, direction, gammaRegime, maxPremVelocity);
    }

    /**
     * Check if we're in a gamma regime where premium velocity signals should
     * be used instead of (or in addition to) spot momentum.
     */
    public boolean isGammaRegime(IndexType indexType) {
        return getVelocity(indexType).gammaRegime();
    }

    /**
     * Get directional premium signal: +1 = CE flying (bullish premium move),
     * -1 = PE flying (bearish), 0 = no signal.
     */
    public int getPremiumDirection(IndexType indexType) {
        return getVelocity(indexType).direction();
    }

    // ── Internal ──────────────────────────────────────────────────────────

    private double[] getAtmPremiums(IndexType indexType, int atm) {
        double ce = 0, pe = 0;
        for (OptionInstrument opt : liveInstrumentCache.allOptions()) {
            if (opt.getIndexType() != indexType || opt.getStrikePrice() != atm) continue;
            if ("CE".equals(opt.getOptionType())) ce = opt.getLastPrice();
            else pe = opt.getLastPrice();
        }
        return new double[]{ce, pe};
    }

    // ── Records ───────────────────────────────────────────────────────────

    private record PremiumSnapshot(Instant timestamp, double cePremium, double pePremium, double spot) {}

    /**
     * Premium velocity result — encapsulates all velocity metrics for a single index.
     */
    public record PremiumVelocity(
            double ceVelocityPct,
            double peVelocityPct,
            double straddleVelocityPct,
            double spotVelocityPct,
            double amplification,
            int direction,
            boolean gammaRegime,
            double maxPremiumVelocityPct
    ) {
        public static final PremiumVelocity NONE = new PremiumVelocity(0, 0, 0, 0, 0, 0, false, 0);

        public boolean hasSignal() { return direction != 0; }
        public boolean isBullish() { return direction > 0; }
        public boolean isBearish() { return direction < 0; }
    }
}
