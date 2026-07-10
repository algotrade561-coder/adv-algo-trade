package com.algo.trade.strategy.oimomentum;

import com.algo.trade.domain.IndexType;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the D2 SUSTAINED_DRIFT entry decision after the 2026-06-14 drift-math fix.
 *
 * <p>Before the fix the detector approximated 60-min drift as distance from the
 * rolling-window midpoint, which reports only ~half a clean one-way move. On the
 * 2026-06-12 afternoon rally (~0.26-0.28% range) that midpoint read ~0.13% — below
 * the 0.20% gate — so D2 never fired despite a textbook sustained drift. The fix
 * feeds true endpoint-to-endpoint drift from {@link TickMomentumDetector}; these
 * tests lock in that a genuine 0.26% trend now fires.</p>
 */
class SustainedDriftDetectorTest {

    private static final IndexType IDX = IndexType.NIFTY;

    private OIMomentumConfig defaultConfig() {
        // Defaults: enabled, minPct=0.20, opScoreMin=50, window=60.
        return new OIMomentumConfig();
    }

    private OperatorAccumulationDetector.OperatorSignal opSignal(int score, int dir, boolean fresh) {
        OperatorAccumulationDetector.OperatorSignal sig =
                mock(OperatorAccumulationDetector.OperatorSignal.class);
        when(sig.getScore()).thenReturn(score);
        when(sig.getDirection()).thenReturn(dir);
        when(sig.isFresh()).thenReturn(fresh);
        return sig;
    }

    private SustainedDriftDetector detectorWith(
            OperatorAccumulationDetector.OperatorSignal sig,
            TickMomentumDetector.DriftSample drift) {
        OperatorFrameworkService ofs = mock(OperatorFrameworkService.class);
        when(ofs.getOperatorSignal(IDX)).thenReturn(sig);
        TickMomentumDetector tmd = mock(TickMomentumDetector.class);
        when(tmd.getSignedDriftPct(eq(IDX), anyInt())).thenReturn(drift);
        return new SustainedDriftDetector(ofs, tmd);
    }

    @Test
    void fires_onTrueTrendDrift_thatMidpointWouldHaveMissed() {
        // Real endpoint drift 0.26% — the exact regime that read ~0.13% via the old
        // midpoint approximation and was rejected all day on 2026-06-12.
        var det = detectorWith(opSignal(60, +1, true),
                new TickMomentumDetector.DriftSample(0.26, 60, true));

        SustainedDriftDetector.Decision d = det.evaluate(IDX, defaultConfig());

        assertTrue(d.fires(), "0.26% true drift with aligned operator should fire");
        assertEquals(+1, d.direction());
        assertEquals("ok", d.reason());
        assertEquals(0.26, d.driftPct(), 1e-9);
    }

    @Test
    void skips_whenDriftBelowThreshold() {
        // The old midpoint scale (~0.13%) must still be a skip on the true measure.
        var det = detectorWith(opSignal(60, +1, true),
                new TickMomentumDetector.DriftSample(0.13, 60, true));

        SustainedDriftDetector.Decision d = det.evaluate(IDX, defaultConfig());

        assertFalse(d.fires());
        assertTrue(d.reason().startsWith("drift_below"), "reason was: " + d.reason());
    }

    @Test
    void skips_whenDriftOpposesOperatorDirection() {
        // Spot drifting up but operator chain bearish — no agreement, no entry.
        var det = detectorWith(opSignal(70, -1, true),
                new TickMomentumDetector.DriftSample(+0.30, 60, true));

        SustainedDriftDetector.Decision d = det.evaluate(IDX, defaultConfig());

        assertFalse(d.fires());
        assertTrue(d.reason().startsWith("drift_dir"), "reason was: " + d.reason());
    }

    @Test
    void skips_whenOperatorScoreTooLow() {
        var det = detectorWith(opSignal(40, +1, true),
                new TickMomentumDetector.DriftSample(0.30, 60, true));

        SustainedDriftDetector.Decision d = det.evaluate(IDX, defaultConfig());

        assertFalse(d.fires());
        assertTrue(d.reason().contains("op_score_below"), "reason was: " + d.reason());
    }

    @Test
    void skips_whenDriftHistoryUnavailable() {
        var det = detectorWith(opSignal(60, +1, true), TickMomentumDetector.DriftSample.INVALID);

        SustainedDriftDetector.Decision d = det.evaluate(IDX, defaultConfig());

        assertFalse(d.fires());
        assertEquals("spot_history_unavailable", d.reason());
    }

    @Test
    void skips_whenDisabled() {
        OIMomentumConfig cfg = defaultConfig();
        cfg.setSustainedDriftEnabled(false);
        var det = detectorWith(opSignal(60, +1, true),
                new TickMomentumDetector.DriftSample(0.30, 60, true));

        SustainedDriftDetector.Decision d = det.evaluate(IDX, cfg);

        assertFalse(d.fires());
        assertEquals("disabled", d.reason());
    }
}
