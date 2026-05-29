package com.algo.trade.strategy.oimomentum.v3;

import com.algo.trade.data.ChainSnapshot;
import com.algo.trade.domain.IndexType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ChainSignalAnalyzer} — pattern detection logic.
 */
class ChainSignalAnalyzerTest {

    private ChainSignalAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        analyzer = new ChainSignalAnalyzer();
    }

    @Test
    void writerSqueeze_heavyCeUnwindPlusPeBuilding_returnsBullish() {
        ChainSnapshot snap = build(IndexType.NIFTY, 23950, 23950,
                strikes(23800, 24100, 50, -600_000, +200_000));
        OiSignal sig = analyzer.analyze(IndexType.NIFTY, snap);
        assertEquals(OiPattern.WRITER_SQUEEZE.name(), sig.label());
        assertEquals(+1, sig.direction());
        assertTrue(sig.strength() > 0);
    }

    @Test
    void peSqueeze_heavyPeUnwindPlusCeBuilding_returnsBearish() {
        ChainSnapshot snap = build(IndexType.NIFTY, 23950, 23950,
                strikes(23800, 24100, 50, +200_000, -600_000));
        OiSignal sig = analyzer.analyze(IndexType.NIFTY, snap);
        assertEquals(OiPattern.PE_SQUEEZE.name(), sig.label());
        assertEquals(-1, sig.direction());
    }

    @Test
    void peSupport_peDominantBelowAtm_returnsBullish() {
        // Higher PE build below ATM, only modest CE on top
        List<ChainSnapshot.StrikeData> data = new ArrayList<>();
        for (int k = 23800; k <= 24100; k += 50) {
            long ceChg = 10_000;
            long peChg = (k <= 23950) ? 100_000 : 5_000;
            data.add(strike(k, ceChg, peChg));
        }
        OiSignal sig = analyzer.analyze(IndexType.NIFTY, build(IndexType.NIFTY, 23950, 23950, data));
        assertEquals(OiPattern.PE_SUPPORT.name(), sig.label());
        assertEquals(+1, sig.direction());
    }

    @Test
    void ceResistance_ceDominantAboveAtm_returnsBearish() {
        List<ChainSnapshot.StrikeData> data = new ArrayList<>();
        for (int k = 23800; k <= 24100; k += 50) {
            long ceChg = (k >= 23950) ? 100_000 : 5_000;
            long peChg = 10_000;
            data.add(strike(k, ceChg, peChg));
        }
        OiSignal sig = analyzer.analyze(IndexType.NIFTY, build(IndexType.NIFTY, 23950, 23950, data));
        assertEquals(OiPattern.CE_RESISTANCE.name(), sig.label());
        assertEquals(-1, sig.direction());
    }

    @Test
    void conflicting_bothSidesBuilding_returnsZeroDirection() {
        // Both sums positive and above significance/2 → CONFLICTING
        ChainSnapshot snap = build(IndexType.NIFTY, 23950, 23950,
                strikes(23800, 24100, 50, +400_000, +400_000));
        OiSignal sig = analyzer.analyze(IndexType.NIFTY, snap);
        assertEquals(OiPattern.CONFLICTING.name(), sig.label());
        assertEquals(0, sig.direction());
    }

    @Test
    void insufficient_chainTooSmall_returnsEmpty() {
        OiSignal sig = analyzer.analyze(IndexType.NIFTY, null);
        assertEquals(OiSignal.EMPTY.label(), sig.label());
    }

    @Test
    void trapApproach_heavyImbalanceWithSpotMovingTowardStrike_returnsTrap() {
        // First call: spot 23950, fills lastSpot for trend detection.
        ChainSnapshot snap1 = build(IndexType.NIFTY, 23950, 23950, strikes(23800, 24100, 50, 0, 0));
        analyzer.analyze(IndexType.NIFTY, snap1);
        // Second call: spot rises to 23970 and 24000 strike has heavy CE imbalance.
        List<ChainSnapshot.StrikeData> data = new ArrayList<>();
        for (int k = 23800; k <= 24100; k += 50) {
            long ceOi = (k == 24000) ? 200_000 : 20_000;
            long peOi = (k == 24000) ? 80_000 : 20_000;
            long ceChg = (k == 24000) ? 50_000 : 10_000;
            data.add(strikeWithOi(k, ceOi, peOi, ceChg, 5_000));
        }
        ChainSnapshot snap2 = build(IndexType.NIFTY, 23970, 23950, data);
        OiSignal sig = analyzer.analyze(IndexType.NIFTY, snap2);
        // Should detect TRAP_APPROACH since spot moved up (23950 → 23970) and 24000 CE is trapped.
        assertEquals(OiPattern.TRAP_APPROACH.name(), sig.label());
        assertEquals(+1, sig.direction());
        assertEquals(24000, sig.resistanceStrike());
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private static ChainSnapshot build(IndexType ix, double spot, int atm,
                                         List<ChainSnapshot.StrikeData> strikes) {
        return new ChainSnapshot(Instant.now(), ix.name(), spot, 15.0,
                "2026-06-02", atm, strikes);
    }

    private static List<ChainSnapshot.StrikeData> strikes(int from, int to, int step,
                                                            long ceChg, long peChg) {
        List<ChainSnapshot.StrikeData> out = new ArrayList<>();
        for (int k = from; k <= to; k += step) {
            out.add(strike(k, ceChg, peChg));
        }
        return out;
    }

    private static ChainSnapshot.StrikeData strike(int k, long ceChg, long peChg) {
        return new ChainSnapshot.StrikeData(k,
                100.0, 80_000L, 1000L, 12.0, 0.5, 0.001, -0.5, 5.0,
                99.0, 101.0, ceChg, 0, 0,
                100.0, 80_000L, 1000L, 12.0, -0.5, 0.001, -0.5, 5.0,
                99.0, 101.0, peChg, 0, 0);
    }

    private static ChainSnapshot.StrikeData strikeWithOi(int k, long ceOi, long peOi,
                                                          long ceChg, long peChg) {
        return new ChainSnapshot.StrikeData(k,
                100.0, ceOi, 1000L, 12.0, 0.5, 0.001, -0.5, 5.0,
                99.0, 101.0, ceChg, 0, 0,
                100.0, peOi, 1000L, 12.0, -0.5, 0.001, -0.5, 5.0,
                99.0, 101.0, peChg, 0, 0);
    }
}
