package com.algo.trade.strategy.oimomentum.v3;

import com.algo.trade.data.ChainSnapshot;
import com.algo.trade.domain.IndexType;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * V3 OPERATOR — Market context single source of truth.
 *
 * <p>Provides eight context variables used by the operator gates and downstream sizing:</p>
 * <ol>
 *   <li>IV percentile (30-day rolling) of ATM IV.</li>
 *   <li>India VIX trend (rising / flat / falling) over 15-min slope.</li>
 *   <li>Spot vs VWAP (above / below / at).</li>
 *   <li>Gamma wall (highest-gamma strike each side of ATM).</li>
 *   <li>Max-pain strike.</li>
 *   <li>FII / DII net (yesterday's close, externally fed).</li>
 *   <li>PCR rate-of-change (5-min slope).</li>
 *   <li>Spread % &amp; depth for the candidate strike (queried at entry).</li>
 * </ol>
 *
 * <p>This service deliberately holds rolling history for the time-series metrics so they
 * survive without a database hit on every tick.</p>
 */
@Component
public class MarketContextService {

    /** ATM IV history per index — used for IV percentile. */
    private final Map<IndexType, Deque<Double>> atmIvHistory = new ConcurrentHashMap<>();
    private static final int IV_HISTORY_SIZE = 30;

    /** Recent VIX samples (every minute) used for slope. */
    private final Deque<VixSample> vixHistory = new ArrayDeque<>();
    private static final int VIX_HISTORY_MINUTES = 15;

    /** Latest snapshot per index — used for gamma wall + max-pain. */
    private final Map<IndexType, ChainSnapshot> latestSnapshot = new ConcurrentHashMap<>();

    /** PCR history per index — used for rate-of-change. */
    private final Map<IndexType, Deque<PcrSample>> pcrHistory = new ConcurrentHashMap<>();

    /** VWAP per index — updated by the strategy's VWAP calculator. */
    private final Map<IndexType, Double> vwap = new ConcurrentHashMap<>();

    /** FII/DII net for today (positive = net long). External feed. */
    private double fiiNetCrore = 0;
    private double diiNetCrore = 0;

    /** VIX at 09:15 IST — used for VIX-relative-vs-session G3 gate. */
    private volatile double sessionOpenVix = 0;
    private volatile java.time.LocalDate sessionOpenVixDay = null;

    /** Record a fresh ATM IV reading; pushes through the rolling window. */
    public void recordAtmIv(IndexType ix, double atmIv) {
        atmIvHistory.compute(ix, (k, v) -> {
            Deque<Double> d = v == null ? new ArrayDeque<>() : v;
            d.addLast(atmIv);
            while (d.size() > IV_HISTORY_SIZE) d.pollFirst();
            return d;
        });
    }

    /** Record a VIX sample (once per minute). Also captures session-open VIX once per day. */
    public synchronized void recordVix(double vix) {
        vixHistory.addLast(new VixSample(Instant.now(), vix));
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(VIX_HISTORY_MINUTES * 2));
        while (!vixHistory.isEmpty() && vixHistory.peekFirst().time.isBefore(cutoff)) {
            vixHistory.pollFirst();
        }
        // G3 session-anchor: capture the first VIX sample of each trading day.
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"));
        if (sessionOpenVixDay == null || !sessionOpenVixDay.equals(today)) {
            sessionOpenVix = vix;
            sessionOpenVixDay = today;
        }
    }

    /** Session-open VIX captured at 09:15 IST. 0 if not yet captured. */
    public double getSessionOpenVix() { return sessionOpenVix; }

    /** Percentile rank at-or-above which VIX is considered session-elevated. */
    public static final double VIX_SESSION_ELEVATED_PERCENTILE = 70.0;

    /** VIX % change vs session open. Positive = VIX expanding intraday. */
    public synchronized double vixPctVsSessionOpen() {
        return vixChangePctSinceSessionOpen();
    }

    /** VIX % change vs session open — alias used by tests. */
    public synchronized double vixChangePctSinceSessionOpen() {
        if (sessionOpenVix <= 0 || vixHistory.isEmpty()) return 0.0;
        VixSample latest = vixHistory.peekLast();
        if (latest == null) return 0.0;
        return (latest.vix - sessionOpenVix) / sessionOpenVix * 100.0;
    }

    /**
     * Percentile rank of current VIX vs all VIX samples in today's session.
     * Returns −1 if fewer than 3 samples (insufficient data).
     * 0 = lowest, 100 = highest.
     */
    public synchronized double vixSessionPercentile() {
        if (vixHistory.size() < 3) return -1.0;
        VixSample latest = vixHistory.peekLast();
        if (latest == null) return -1.0;
        long below = vixHistory.stream().filter(s -> s.vix < latest.vix).count();
        return below * 100.0 / vixHistory.size();
    }

    public void recordSnapshot(IndexType ix, ChainSnapshot snap) {
        latestSnapshot.put(ix, snap);
    }

    public void recordPcr(IndexType ix, double pcr) {
        pcrHistory.compute(ix, (k, v) -> {
            Deque<PcrSample> d = v == null ? new ArrayDeque<>() : v;
            d.addLast(new PcrSample(Instant.now(), pcr));
            Instant cutoff = Instant.now().minus(Duration.ofMinutes(30));
            while (!d.isEmpty() && d.peekFirst().time.isBefore(cutoff)) d.pollFirst();
            return d;
        });
    }

    public void recordVwap(IndexType ix, double vwap) { this.vwap.put(ix, vwap); }
    public void recordFiiDii(double fii, double dii) { this.fiiNetCrore = fii; this.diiNetCrore = dii; }

    // ── Public read API ────────────────────────────────────────────────────

    /** IV percentile (0–100) over the rolling history. 50 if insufficient data. */
    public double ivPercentile(IndexType ix) {
        Deque<Double> hist = atmIvHistory.get(ix);
        if (hist == null || hist.size() < 3) return 50.0;
        Double current = ((ArrayDeque<Double>) hist).peekLast();
        if (current == null) return 50.0;
        long below = hist.stream().filter(v -> v < current).count();
        return below * 100.0 / hist.size();
    }

    /** VIX 15-min slope. Positive = rising, negative = falling. */
    public synchronized double vixSlope15Min() {
        if (vixHistory.size() < 2) return 0.0;
        VixSample first = vixHistory.peekFirst();
        VixSample last = vixHistory.peekLast();
        if (first == null || last == null) return 0.0;
        long mins = Duration.between(first.time, last.time).toMinutes();
        if (mins <= 0) return 0.0;
        return (last.vix - first.vix) / mins;  // VIX points per minute
    }

    /** Spot vs VWAP: +1 above, -1 below, 0 unknown. */
    public int spotVsVwap(IndexType ix, double spot) {
        Double v = vwap.get(ix);
        if (v == null || v <= 0) return 0;
        if (spot > v * 1.0005) return +1;
        if (spot < v * 0.9995) return -1;
        return 0;
    }

    /** Highest-gamma strike above and below ATM. {@code [aboveStrike, belowStrike]}. */
    public int[] gammaWalls(IndexType ix) {
        ChainSnapshot snap = latestSnapshot.get(ix);
        if (snap == null) return new int[]{0, 0};
        int atm = snap.atmStrike();
        int wallAbove = 0;
        int wallBelow = 0;
        double maxAbove = -1, maxBelow = -1;
        for (ChainSnapshot.StrikeData s : snap.strikes()) {
            double g = Math.abs(s.ceGamma()) + Math.abs(s.peGamma());
            if (s.strike() > atm && g > maxAbove) { maxAbove = g; wallAbove = s.strike(); }
            if (s.strike() < atm && g > maxBelow) { maxBelow = g; wallBelow = s.strike(); }
        }
        return new int[]{wallAbove, wallBelow};
    }

    /** Max-pain strike — strike that minimises total OI × |spot−strike|. */
    public int maxPainStrike(IndexType ix) {
        ChainSnapshot snap = latestSnapshot.get(ix);
        if (snap == null) return 0;
        int best = snap.atmStrike();
        double bestPain = Double.MAX_VALUE;
        for (ChainSnapshot.StrikeData candidate : snap.strikes()) {
            double pain = 0;
            for (ChainSnapshot.StrikeData s : snap.strikes()) {
                if (s.strike() < candidate.strike()) {
                    pain += s.ceOI() * (candidate.strike() - s.strike());
                } else if (s.strike() > candidate.strike()) {
                    pain += s.peOI() * (s.strike() - candidate.strike());
                }
            }
            if (pain < bestPain) { bestPain = pain; best = candidate.strike(); }
        }
        return best;
    }

    /** PCR rate-of-change over the last 5 minutes (points/min). */
    public double pcrSlope5Min(IndexType ix) {
        Deque<PcrSample> hist = pcrHistory.get(ix);
        if (hist == null || hist.size() < 2) return 0.0;
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(5));
        PcrSample old = null;
        PcrSample latest = null;
        for (PcrSample s : hist) {
            if (s.time.isAfter(cutoff)) { if (latest == null || s.time.isAfter(latest.time)) latest = s; }
            else if (old == null || s.time.isAfter(old.time)) old = s;
        }
        if (old == null || latest == null) return 0.0;
        long mins = Duration.between(old.time, latest.time).toMinutes();
        if (mins <= 0) return 0.0;
        return (latest.pcr - old.pcr) / mins;
    }

    public double getVwap(IndexType ix) { return vwap.getOrDefault(ix, 0.0); }
    public double getFiiNetCrore() { return fiiNetCrore; }
    public double getDiiNetCrore() { return diiNetCrore; }
    public ChainSnapshot getLatestSnapshot(IndexType ix) { return latestSnapshot.get(ix); }
    public int historySize(IndexType ix) {
        Deque<Double> h = atmIvHistory.get(ix);
        return h == null ? 0 : h.size();
    }

    // ── Internal sample records ────────────────────────────────────────────
    private record VixSample(Instant time, double vix) {}
    private record PcrSample(Instant time, double pcr) {}
}
