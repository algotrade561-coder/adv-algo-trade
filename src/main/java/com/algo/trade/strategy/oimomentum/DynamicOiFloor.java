package com.algo.trade.strategy.oimomentum;

import com.algo.trade.domain.IndexType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DYNAMIC OI-shift baseline (2026-07-08).
 *
 * <p>Replaces the FIXED absolute OI/ΔOI floors that misfire across the weekly expiry cycle with an
 * intraday, self-calibrating baseline. A fixed 750k |ΔOI|/min fires ~8% of minutes on a fresh
 * Wednesday but ~98% on expiry Tuesday (measured on the tape, 2026-07-01..07): any hard number means
 * something completely different at each end of the cycle. Instead, a genuine OI shift is detected as
 * an <b>anomaly versus the DAY'S OWN churn distribution</b> — self-calibrating on thin early-week days
 * AND violent expiry days.</p>
 *
 * <p>Per index, a rolling ring buffer of recent per-window band-OI velocity samples
 * <pre>  v = |ΔOI_CE| + |ΔOI_PE|   (already computed by OIMomentumStrategy.oiChangeWindowed)</pre>
 * yields a robust median {@code m} and MAD, giving:
 * <pre>
 *   MIN_SIGNIFICANT_dyn = clamp(round(K * m), FLOOR_LO, FLOOR_HI)   // "≥ ~0.75× a typical minute today"
 *   robust z            = (v - m) / (scale + eps)                   // "unusually large for today"
 * </pre>
 * where {@code scale = max(1.4826*MAD, 0.10*m)} guards a perfectly-flat buffer from flagging a tiny
 * wiggle as an anomaly.</p>
 *
 * <p><b>Safe degradation (live-money critical):</b>
 * <ul>
 *   <li>warmup (&lt; {@code min-samples}): the getters return the caller's LEGACY fixed floor / NaN z,
 *       so the open behaves byte-identically to today until the buffer warms (~8 min).</li>
 *   <li>clamp bounds prevent a data gap driving the floor to 0/∞.</li>
 *   <li>NaN/degenerate stats → warmup semantics (caller legacy).</li>
 *   <li>master flag {@code oi-momentum.dynamic-floor.enabled} (default true = LIVE); false restores
 *       byte-identical legacy behaviour everywhere (callers gate on {@link #isEnabled()}).</li>
 * </ul>
 * Sampling is throttled to one independent sample per {@code sample-interval-sec} so the ring buffer
 * spans ~{@code window-samples} minutes rather than ~{@code window-samples} ticks.</p>
 *
 * <p>Thread-safe: samples arrive on the single OIMomentum strategy tick; reads take a lock-free array
 * snapshot. Sub-parameters are {@code @Value} config so window / Z_HI / clamp bounds are tunable and
 * killable without redeploy. This bean is an OPTIONAL collaborator — unit tests that {@code new} a
 * consumer without it get the pure legacy path.</p>
 */
@Component
public class DynamicOiFloor {

    private static final Logger log = LoggerFactory.getLogger(DynamicOiFloor.class);

    /** Master kill-switch. Default true = LIVE. false → every consumer falls back to its legacy fixed floor. */
    @Value("${oi-momentum.dynamic-floor.enabled:true}")
    private boolean enabled = true;

    /** Rolling window length in samples (~one sample per {@code sample-interval-sec} → ~20 min at 60s). */
    @Value("${oi-momentum.dynamic-floor.window-samples:20}")
    private int windowSamples = 20;

    /** Minimum samples before the dynamic stats are trusted; below this the getters return legacy/NaN. */
    @Value("${oi-momentum.dynamic-floor.min-samples:8}")
    private int minSamples = 8;

    /** K in MIN_SIGNIFICANT_dyn = round(K * median). 0.75 = "≥ ~0.75× a typical minute today". */
    @Value("${oi-momentum.dynamic-floor.median-k:0.75}")
    private double medianK = 0.75;

    /** Robust-z "unusually large for today" threshold (Z_HI). */
    @Value("${oi-momentum.dynamic-floor.z-hi:2.0}")
    private double zHi = 2.0;

    /** Clamp lower bound (contracts) for the dynamic floor. */
    @Value("${oi-momentum.dynamic-floor.clamp-lo:50000}")
    private long clampLo = 50_000L;

    /** Clamp upper bound (contracts) for the dynamic floor. */
    @Value("${oi-momentum.dynamic-floor.clamp-hi:3000000}")
    private long clampHi = 3_000_000L;

    /** Minimum seconds between accepted samples (one independent 60s-window observation per minute). */
    @Value("${oi-momentum.dynamic-floor.sample-interval-sec:60}")
    private long sampleIntervalSec = 60;

    /** Fresh-day reference median band velocity (ATM±3, 60s) that {@link #conditionScale} normalises against. */
    @Value("${oi-momentum.dynamic-floor.velocity-ref:350000}")
    private long velocityRef = 350_000L;

    /** Max multiple {@link #conditionScale} may raise a threshold on a violent (expiry) day. */
    @Value("${oi-momentum.dynamic-floor.condition-scale-max:8.0}")
    private double conditionScaleMax = 8.0;

    private final ConcurrentHashMap<IndexType, Deque<Long>> samples = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<IndexType, Long> lastSampleMs = new ConcurrentHashMap<>();

    public boolean isEnabled() { return enabled; }

    public double zHi() { return zHi; }

    /**
     * Push one band-velocity sample {@code v = |ceΔ| + |peΔ|} for this index. Called once per strategy
     * tick with the 60s-window band delta; throttled to one accepted sample per {@code sample-interval-sec}
     * so the buffer spans real minutes. No-op when disabled, null index, or a degenerate/overflowing value.
     */
    public void record(IndexType idx, long ceDelta, long peDelta) {
        if (!enabled || idx == null) return;
        long v = Math.abs(ceDelta) + Math.abs(peDelta);
        if (v <= 0) return;                       // no movement / overflow guard
        long now = System.currentTimeMillis();
        Long last = lastSampleMs.get(idx);
        if (last != null && now - last < sampleIntervalSec * 1000L) return;  // throttle to ~1 sample/window
        lastSampleMs.put(idx, now);
        Deque<Long> q = samples.computeIfAbsent(idx, k -> new ArrayDeque<>());
        synchronized (q) {
            q.addLast(v);
            int cap = Math.max(4, windowSamples);
            while (q.size() > cap) q.removeFirst();
        }
    }

    /** Reset the rolling buffer at session open (IST day rollover). Null index clears every index. */
    public void resetSession(IndexType idx) {
        if (idx == null) {
            samples.clear();
            lastSampleMs.clear();
            log.info("[DynamicOiFloor] session reset (all indices) — enabled={} window={} minSamples={} K={} zHi={} clamp[{},{}]",
                    enabled, windowSamples, minSamples, medianK, zHi, clampLo, clampHi);
            return;
        }
        Deque<Long> q = samples.get(idx);
        if (q != null) synchronized (q) { q.clear(); }
        lastSampleMs.remove(idx);
    }

    /** True once ≥ min-samples have accumulated for this index (dynamic stats trustworthy). */
    public boolean isWarmedUp(IndexType idx) {
        if (!enabled) return false;
        Deque<Long> q = samples.get(idx);
        if (q == null) return false;
        synchronized (q) { return q.size() >= effectiveMinSamples(); }
    }

    /** Current dynamic median-of-velocity for this index (contracts), or -1 when disabled/warming. */
    public long medianVelocity(IndexType idx) {
        double m = medianV(idx);
        return Double.isNaN(m) ? -1L : Math.round(m);
    }

    /**
     * Dynamic significance floor for this index. Returns {@code legacyFloor} when disabled / warming /
     * degenerate — so callers are byte-identical to today until the buffer warms. Otherwise
     * {@code clamp(round(K * median), clampLo, clampHi)}.
     */
    public long minSignificant(IndexType idx, long legacyFloor) {
        if (!enabled) return legacyFloor;
        double m = medianV(idx);
        if (Double.isNaN(m) || m <= 0) return legacyFloor;
        long raw = Math.round(medianK * m);
        long lo = Math.min(clampLo, clampHi);
        long hi = Math.max(clampLo, clampHi);
        return Math.max(lo, Math.min(hi, raw));
    }

    /**
     * Robust z-score of the given velocity {@code v} versus today's buffer. NaN when disabled / warming /
     * degenerate, so callers fall back to their legacy magnitude gate.
     */
    public double zScore(IndexType idx, long v) {
        if (!enabled) return Double.NaN;
        long[] s = sortedSnapshot(idx);
        if (s.length < effectiveMinSamples()) return Double.NaN;
        double m = median(s);
        long mRound = Math.round(m);
        long[] dev = new long[s.length];
        for (int i = 0; i < s.length; i++) dev[i] = Math.abs(s[i] - mRound);
        Arrays.sort(dev);
        double mad = median(dev);
        double scale = Math.max(1.4826 * mad, 0.10 * m);   // flat-buffer guard: never smaller than 10% of median
        double z = (v - m) / (scale + 1.0);
        return Double.isFinite(z) ? z : Double.NaN;
    }

    /**
     * True if {@code v} is an "unusually large for today" shift (z ≥ Z_HI). Warmup / degenerate → false,
     * so callers must OR-in their legacy gate when this returns false during warmup.
     */
    public boolean isAnomalous(IndexType idx, long v) {
        double z = zScore(idx, v);
        return !Double.isNaN(z) && z >= zHi;
    }

    /**
     * Market-condition multiplier ≥ 1.0: how much more violent today's median band churn is than a fresh-day
     * reference, clamped to {@code [1.0, condition-scale-max]}. Returns 1.0 when disabled / warming up / thin,
     * so a consumer that multiplies its own legacy threshold by this stays byte-identical to today except on a
     * genuinely high-OI day, where it RAISES the threshold (never lowers it) in proportion to the tape. Use
     * this for direction classifiers whose quantity is NOT the ATM±3 band velocity (e.g. a net-OI surge gate).
     */
    public double conditionScale(IndexType idx) {
        if (!enabled) return 1.0;
        double m = medianV(idx);
        if (Double.isNaN(m) || m <= 0 || velocityRef <= 0) return 1.0;
        double s = m / (double) velocityRef;
        return Math.max(1.0, Math.min(conditionScaleMax, s));
    }

    /** A legacy absolute threshold scaled UP by {@link #conditionScale} (clamp-low at the legacy value). */
    public long scaleThreshold(IndexType idx, long legacy) {
        return Math.round(legacy * conditionScale(idx));
    }

    // ── internals ────────────────────────────────────────────────────────────────────────────────

    private int effectiveMinSamples() { return Math.max(2, minSamples); }

    private long[] sortedSnapshot(IndexType idx) {
        Deque<Long> q = samples.get(idx);
        if (q == null) return new long[0];
        long[] a;
        synchronized (q) {
            a = new long[q.size()];
            int i = 0;
            for (long x : q) a[i++] = x;
        }
        Arrays.sort(a);
        return a;
    }

    private double medianV(IndexType idx) {
        long[] s = sortedSnapshot(idx);
        if (s.length < effectiveMinSamples()) return Double.NaN;
        return median(s);
    }

    private static double median(long[] sorted) {
        int n = sorted.length;
        if (n == 0) return Double.NaN;
        return (n % 2 == 1) ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0;
    }
}
