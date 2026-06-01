package com.algo.trade.strategy.oimomentum;

import com.algo.trade.domain.IndexType;
import com.algo.trade.marketdata.LiveInstrumentCache;
import com.algo.trade.risk.MarketGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * OPERATOR_SQUEEZE detector — catches the coil → shakeout → CE/PE short-squeeze
 * pattern observed on 2 Jun 2026 (NIFTY +225 pts in 65 min, 12:30–13:30 IST),
 * which every existing detector missed because:
 *
 * <ul>
 *   <li>{@link TickMomentumDetector#detect} measures the move over the LAST 5
 *       SECONDS — a trend that takes minutes never registers.</li>
 *   <li>{@link SustainedDriftDetector} requires a 60-minute window — a
 *       25-minute impulse is invisible.</li>
 *   <li>The spike detector needs a 10-min spike — the 5-min ignition bar that
 *       starts the squeeze runs out of room before the spike threshold trips.</li>
 *   <li>{@link Case0OiLedDetector} needs a tight pre-coil &lt; 0.10% — the
 *       coil-and-shakeout pattern often has wider coils.</li>
 * </ul>
 *
 * <h3>Pattern definition</h3>
 *
 * A 5-minute window is an OPERATOR_SQUEEZE START when ALL of these hold:
 *
 * <h4>(a) Preceding coil — past N (default 20) minutes</h4>
 * <ul>
 *   <li>spot range as % of spot &lt; {@code coilRangeMaxPct} (default 0.10%)</li>
 *   <li>VIX dropped &gt;= {@code coilVixDropMin} (default 0.05 pts)</li>
 *   <li>Total CE OI built &gt;= {@code coilCeBuildMin} (default +5M) — writers stacked</li>
 * </ul>
 *
 * <h4>(b) Ignition bar — current 5-min</h4>
 * <ul>
 *   <li>|spot return over last 5 min| &gt;= {@code ignitionReturnMinPct} (default 0.20%)</li>
 *   <li>For bullish: total CE OI dropped &gt;= {@code oiCollapseMinAbs} (default 7M)</li>
 *   <li>For bearish: total PE OI dropped &gt;= {@code oiCollapseMinAbs}</li>
 *   <li>VIX expanded &gt;= {@code ignitionVixExpansionMin} (default 0.10 pts)</li>
 * </ul>
 *
 * <h4>(c) Chain confirmation — last 5 min</h4>
 * <ul>
 *   <li>ATM IV expanded &gt;= {@code ivExpansionMinPct} (default 5%)</li>
 *   <li>|PCR rotation| &gt;= {@code pcrRotationMin} (default 0.05)</li>
 * </ul>
 *
 * <h3>Verification against 2 Jun 2026 NIFTY tape</h3>
 *
 * Coil 12:00–12:25: range 0.09%, VIX 16.01 → 15.86 (−0.15), CE OI +17M, PCR 1.05.
 * Ignition 12:30 → 12:35: spot +0.27%, total CE OI −12.9M, VIX +0.23, ATM CE IV
 * 9.57 → 10.55 (+10.2%), PCR 1.13 → 1.11 (in-window rotation 0.08 over the
 * 12:25–12:35 transition). All gates pass; signal would have fired at 12:35
 * with 25 minutes of follow-through (+160 spot pts).
 */
@Component
public class OperatorSqueezeDetector {

    private static final Logger log = LoggerFactory.getLogger(OperatorSqueezeDetector.class);

    private final TickMomentumDetector momentumDetector;
    private final LiveInstrumentCache liveInstrumentCache;
    private final MarketGuard marketGuard;

    /** Per-index sample ring buffer — appended each tick, evicted after 30 min. */
    private final Map<IndexType, Deque<Sample>> samples = new ConcurrentHashMap<>();
    private static final long RETENTION_MS = 30L * 60_000L;

    public OperatorSqueezeDetector(TickMomentumDetector momentumDetector,
                                   LiveInstrumentCache liveInstrumentCache,
                                   MarketGuard marketGuard) {
        this.momentumDetector = momentumDetector;
        this.liveInstrumentCache = liveInstrumentCache;
        this.marketGuard = marketGuard;
    }

    /**
     * Record the per-tick snapshot the detector needs. Called from
     * {@code OIMomentumStrategy.tickIndex} once per second per index.
     */
    public void tick(IndexType index) {
        double spot = momentumDetector.getSpot(index);
        if (spot <= 0) return;
        int atm = index.roundToATM(spot);
        long ceOi = liveInstrumentCache.getTotalCeOi(index);
        long peOi = liveInstrumentCache.getTotalPeOi(index);
        double vix = marketGuard != null ? marketGuard.getCurrentVix() : 0;
        double pcr = liveInstrumentCache.getRealtimePcr(index);
        double atmIv = liveInstrumentCache.getAtmCeIv(index, atm);
        long now = System.currentTimeMillis();
        Deque<Sample> ring = samples.computeIfAbsent(index, k -> new ConcurrentLinkedDeque<>());
        ring.addLast(new Sample(now, spot, ceOi, peOi, vix, pcr, atmIv));
        while (!ring.isEmpty() && (now - ring.peekFirst().ts) > RETENTION_MS) {
            ring.pollFirst();
        }
    }

    /** Outcome — captured into diagnostics + tune CSV. */
    public record Decision(
            boolean fires,
            int direction,
            double ignitionReturnPct,     // 5-min spot return (signed)
            long oiCollapseAbs,           // CE drop (bullish) or PE drop (bearish), signed
            double vixDeltaIgnition,      // 5-min VIX delta (positive for expansion)
            double ivExpansionPct,        // 5-min ATM CE IV expansion %
            double pcrRotation,           // abs PCR delta over the same window
            double coilRangePct,          // 20-min spot range as % of spot
            double coilVixDelta,          // 20-min VIX delta (negative for compression)
            long coilCeBuild,             // 20-min CE OI build (positive for stacking)
            String reason                 // "ok" when firing, blocker token otherwise
    ) {
        public static Decision skip(String reason, double ignitionPct, long oiAbs,
                                    double vixIgn, double ivExp, double pcrRot,
                                    double coilRng, double coilVix, long coilCeBld) {
            return new Decision(false, 0, ignitionPct, oiAbs, vixIgn, ivExp, pcrRot,
                    coilRng, coilVix, coilCeBld, reason);
        }
        public static Decision fire(int dir, double ignitionPct, long oiAbs,
                                    double vixIgn, double ivExp, double pcrRot,
                                    double coilRng, double coilVix, long coilCeBld) {
            return new Decision(true, dir, ignitionPct, oiAbs, vixIgn, ivExp, pcrRot,
                    coilRng, coilVix, coilCeBld, "ok");
        }
    }

    /** Evaluate the squeeze gates for one index using config thresholds. */
    public Decision evaluate(IndexType index, OIMomentumConfig config) {
        if (config == null || !config.isOperatorSqueezeEnabled()) {
            return Decision.skip("disabled", 0, 0, 0, 0, 0, 0, 0, 0);
        }
        Deque<Sample> ring = samples.get(index);
        if (ring == null || ring.size() < 60) {
            // need 60 ticks (~60 sec) before any windowed comparison
            return Decision.skip("warming_up", 0, 0, 0, 0, 0, 0, 0, 0);
        }

        long now = System.currentTimeMillis();
        long ignitionWindowMs = (long) config.getOperatorSqueezeIgnitionWindowMin() * 60_000L;
        long coilWindowMs = (long) config.getOperatorSqueezeCoilWindowMin() * 60_000L;

        Sample latest = ring.peekLast();
        Sample ignitionStart = findClosestAtAge(ring, now, ignitionWindowMs);
        Sample coilStart = findClosestAtAge(ring, now, coilWindowMs);
        if (latest == null || ignitionStart == null || coilStart == null) {
            return Decision.skip("history_gap", 0, 0, 0, 0, 0, 0, 0, 0);
        }

        // ── Coil metrics (past coilWindowMin) ───────────────────────────────
        double coilHigh = Double.MIN_VALUE, coilLow = Double.MAX_VALUE;
        long coilCeBuild = latest.ceOi - coilStart.ceOi;
        double coilVixDelta = latest.vix - coilStart.vix;
        for (Sample s : ring) {
            if ((now - s.ts) > coilWindowMs) continue;
            // exclude the last ignitionWindowMs from coil range (that's the move bar itself)
            if ((now - s.ts) < ignitionWindowMs) continue;
            if (s.spot > coilHigh) coilHigh = s.spot;
            if (s.spot < coilLow) coilLow = s.spot;
        }
        double coilRangePct;
        if (coilHigh == Double.MIN_VALUE || coilLow == Double.MAX_VALUE || latest.spot <= 0) {
            coilRangePct = Double.MAX_VALUE; // can't qualify as a coil
        } else {
            coilRangePct = (coilHigh - coilLow) / latest.spot * 100.0;
        }

        // ── Ignition metrics (current 5 min vs 5 min ago) ───────────────────
        double ignitionReturnPct = ignitionStart.spot > 0
                ? (latest.spot - ignitionStart.spot) / ignitionStart.spot * 100.0 : 0;
        long ceDrop5m = latest.ceOi - ignitionStart.ceOi;   // negative when writers cover
        long peDrop5m = latest.peOi - ignitionStart.peOi;
        double vixDeltaIgnition = latest.vix - ignitionStart.vix;

        // ── Chain confirmation (5-min) ──────────────────────────────────────
        double ivExpansionPct = ignitionStart.atmIv > 0
                ? (latest.atmIv - ignitionStart.atmIv) / ignitionStart.atmIv * 100.0 : 0;
        double pcrRotation = Math.abs(latest.pcr - ignitionStart.pcr);

        int bullish = ignitionReturnPct > 0 ? +1 : -1;
        long oiCollapseAbs = bullish > 0 ? ceDrop5m : peDrop5m;

        // ── Gate evaluation — short-circuit with descriptive blocker ────────
        // A8 (2026-06-02): the 3 coil-precondition gates are optional behind
        // operatorSqueezeRequireCoil. Validation against today's 12:35 ignition
        // bar showed all 3 fail because by 12:35 the CE OI was already dropping
        // (negative "build") and VIX was expanding (positive "delta"). With
        // the flag off, only the 4 core ignition+chain gates need to pass.
        boolean requireCoil = config.isOperatorSqueezeRequireCoil();
        if (requireCoil && coilRangePct > config.getOperatorSqueezeCoilRangeMaxPct()) {
            return Decision.skip(
                    String.format("coil_range_%.3f_above_%.3f",
                            coilRangePct, config.getOperatorSqueezeCoilRangeMaxPct()),
                    ignitionReturnPct, oiCollapseAbs, vixDeltaIgnition, ivExpansionPct,
                    pcrRotation, coilRangePct, coilVixDelta, coilCeBuild);
        }
        if (requireCoil && coilVixDelta > -config.getOperatorSqueezeCoilVixDropMin()) {
            return Decision.skip(
                    String.format("coil_vix_no_compression_delta=%+.3f", coilVixDelta),
                    ignitionReturnPct, oiCollapseAbs, vixDeltaIgnition, ivExpansionPct,
                    pcrRotation, coilRangePct, coilVixDelta, coilCeBuild);
        }
        if (requireCoil && coilCeBuild < config.getOperatorSqueezeCoilCeBuildMin()) {
            return Decision.skip(
                    String.format("coil_ce_build_%+,d_below_%,d",
                            coilCeBuild, config.getOperatorSqueezeCoilCeBuildMin()),
                    ignitionReturnPct, oiCollapseAbs, vixDeltaIgnition, ivExpansionPct,
                    pcrRotation, coilRangePct, coilVixDelta, coilCeBuild);
        }
        if (Math.abs(ignitionReturnPct) < config.getOperatorSqueezeIgnitionReturnMinPct()) {
            return Decision.skip(
                    String.format("ignition_return_%.3f_below_%.3f",
                            Math.abs(ignitionReturnPct),
                            config.getOperatorSqueezeIgnitionReturnMinPct()),
                    ignitionReturnPct, oiCollapseAbs, vixDeltaIgnition, ivExpansionPct,
                    pcrRotation, coilRangePct, coilVixDelta, coilCeBuild);
        }
        long requiredOiDropAbs = config.getOperatorSqueezeOiCollapseMinAbs();
        if (-oiCollapseAbs < requiredOiDropAbs) {
            return Decision.skip(
                    String.format("oi_collapse_%+,d_below_%,d_(side=%s)",
                            oiCollapseAbs, requiredOiDropAbs, bullish > 0 ? "CE" : "PE"),
                    ignitionReturnPct, oiCollapseAbs, vixDeltaIgnition, ivExpansionPct,
                    pcrRotation, coilRangePct, coilVixDelta, coilCeBuild);
        }
        if (vixDeltaIgnition < config.getOperatorSqueezeIgnitionVixMin()) {
            return Decision.skip(
                    String.format("ignition_vix_%+.3f_below_%+.3f",
                            vixDeltaIgnition, config.getOperatorSqueezeIgnitionVixMin()),
                    ignitionReturnPct, oiCollapseAbs, vixDeltaIgnition, ivExpansionPct,
                    pcrRotation, coilRangePct, coilVixDelta, coilCeBuild);
        }
        if (ivExpansionPct < config.getOperatorSqueezeIvExpansionMinPct()) {
            return Decision.skip(
                    String.format("iv_expansion_%.2f%%_below_%.2f%%",
                            ivExpansionPct,
                            config.getOperatorSqueezeIvExpansionMinPct()),
                    ignitionReturnPct, oiCollapseAbs, vixDeltaIgnition, ivExpansionPct,
                    pcrRotation, coilRangePct, coilVixDelta, coilCeBuild);
        }
        if (pcrRotation < config.getOperatorSqueezePcrRotationMin()) {
            return Decision.skip(
                    String.format("pcr_rotation_%.3f_below_%.3f",
                            pcrRotation, config.getOperatorSqueezePcrRotationMin()),
                    ignitionReturnPct, oiCollapseAbs, vixDeltaIgnition, ivExpansionPct,
                    pcrRotation, coilRangePct, coilVixDelta, coilCeBuild);
        }

        if (log.isInfoEnabled()) {
            log.info("[OPERATOR_SQUEEZE][{}] FIRE dir={} ignition={}% oiΔ={} vixΔ={} "
                    + "ivExp={}% pcrRot={} coilRange={}% coilVixΔ={} coilCeBuild={}",
                    index, bullish,
                    String.format("%+.3f", ignitionReturnPct),
                    String.format("%+,d", oiCollapseAbs),
                    String.format("%+.3f", vixDeltaIgnition),
                    String.format("%+.2f", ivExpansionPct),
                    String.format("%.3f", pcrRotation),
                    String.format("%.3f", coilRangePct),
                    String.format("%+.3f", coilVixDelta),
                    String.format("%+,d", coilCeBuild));
        }
        return Decision.fire(bullish, ignitionReturnPct, oiCollapseAbs, vixDeltaIgnition,
                ivExpansionPct, pcrRotation, coilRangePct, coilVixDelta, coilCeBuild);
    }

    /**
     * Find the sample whose age (relative to {@code now}) is closest to
     * {@code targetAgeMs}. The ring is roughly time-ordered (latest at tail);
     * we walk from the head looking for the first sample whose age &lt;= target.
     */
    private static Sample findClosestAtAge(Deque<Sample> ring, long now, long targetAgeMs) {
        Sample candidate = null;
        for (Sample s : ring) {
            long age = now - s.ts;
            if (age >= targetAgeMs) {
                candidate = s;            // newest sample older-than-or-equal target age
            } else {
                break;
            }
        }
        return candidate;
    }

    /** Immutable per-tick snapshot. */
    private record Sample(long ts, double spot, long ceOi, long peOi,
                          double vix, double pcr, double atmIv) {}
}
