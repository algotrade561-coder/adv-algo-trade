package com.algo.trade.strategy.oimomentum.v3;

import com.algo.trade.domain.IndexType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the lot-count-based ConvictionSizer.
 *
 * <p>Recall the new contract: {@code size()} takes a {@code baseLotCount} (typically 1)
 * and a {@code maxLotsPerTrade} cap (typically 10 from GlobalConfig), and returns a
 * lot count in {@code [1, maxLotsPerTrade]} — or 0 when gates/pattern/time fail. The
 * conviction multiplier scales the headroom between the baseline and the cap: since the
 * 2026-07-02 recalibration (c3fa554), conviction at/above {@code conviction-strong (0.90)}
 * reaches the full cap, conviction at/below {@code conviction-min (0.50)} stays at the
 * baseline, and values in between interpolate linearly across the headroom.</p>
 */
class ConvictionSizerTest {

    /** Mirror go-live calibration: 1 lot baseline, 10 lots hard ceiling. */
    private static final int BASE = 1;
    private static final int CAP  = 10;

    private ConvictionSizer sizer;

    @BeforeEach
    void setUp() throws Exception {
        sizer = new ConvictionSizer();
        // The sizer is a Spring bean; direct construction skips @Value injection, leaving the
        // conviction-min/strong calibration at 0.0/0.0 (which collapses every signal to the cap).
        // Mirror the production defaults (application.yml trading.v3.sizing.*) so the tests
        // exercise the REAL 2026-07-02 calibration: baseline at conviction<=0.50, FULL ceiling
        // at conviction>=0.90, linear between.
        setField(sizer, "convMin", 0.50);
        setField(sizer, "convStrong", 0.90);
    }

    private static void setField(Object target, String name, double value) throws Exception {
        var f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.setDouble(target, value);
    }

    @Test
    void zeroLots_whenGatesBelowThreshold() {
        ConvictionSizer.SizingResult r = sizer.size(IndexType.NIFTY, BASE, CAP, 2, 3,
                OiPattern.WRITER_SQUEEZE, TimeOfDayMode.OPENING_DRIVE,
                Set.of(Regime.NORMAL), 50);
        assertEquals(0, r.lots());
    }

    @Test
    void zeroLots_whenPatternIsConflicting() {
        ConvictionSizer.SizingResult r = sizer.size(IndexType.NIFTY, BASE, CAP, 4, 4,
                OiPattern.CONFLICTING, TimeOfDayMode.OPENING_DRIVE,
                Set.of(Regime.NORMAL), 50);
        assertEquals(0, r.lots());
    }

    @Test
    void highConviction_writerSqueeze_openingDrive_scalesTowardCap() {
        // base=1, cap=10, 4/4 with required=3 → gatesScore=1.0; WRITER_SQUEEZE=1.0;
        // OPENING_DRIVE=1.0; NORMAL+ivPct=50 → volMult=1.0 → conviction=1.0.
        // 2026-07-02 calibration (c3fa554): conviction >= conviction-strong (0.90) reaches
        // the FULL maxLotsPerTrade ceiling — that fix exists precisely because the old
        // /1.5 normalization made the ceiling unreachable. strength=1 → 1 + 9 = 10.
        ConvictionSizer.SizingResult r = sizer.size(IndexType.NIFTY, BASE, CAP, 4, 3,
                OiPattern.WRITER_SQUEEZE, TimeOfDayMode.OPENING_DRIVE,
                EnumSet.of(Regime.NORMAL), 50);
        assertTrue(r.lots() <= CAP, "must respect maxLotsPerTrade cap");
        assertTrue(r.lots() > BASE, "high conviction must size above 1-lot baseline");
        assertEquals(CAP, r.lots(), "conviction >= conviction-strong must reach the FULL ceiling");
    }

    @Test
    void perfectConviction_withIvExpansionBonus_reachesCap() {
        // ivPct < 30 → volMult=1.1 → conviction=1.1, also >= conviction-strong (0.90) →
        // full ceiling, same as the 1.0 case: once past the strong threshold the ceiling is
        // reached and the kicker cannot exceed the hard cap.
        ConvictionSizer.SizingResult ivExpand = sizer.size(IndexType.NIFTY, BASE, CAP, 4, 3,
                OiPattern.WRITER_SQUEEZE, TimeOfDayMode.OPENING_DRIVE,
                EnumSet.of(Regime.NORMAL), 20);  // ivPct<30 → 1.1× vol kicker
        assertEquals(CAP, ivExpand.lots(),
                "conviction above the strong threshold sizes at the ceiling (kicker cannot exceed cap)");
        assertTrue(ivExpand.lots() <= CAP, "must respect maxLotsPerTrade cap");
    }

    @Test
    void capIsHardCeiling_whenBaseExceedsCap() {
        // If a caller mis-configures baseLotCount > maxLotsPerTrade, the sizer must
        // clamp the baseline to the cap rather than silently exceed it.
        ConvictionSizer.SizingResult r = sizer.size(IndexType.NIFTY, /*base*/20, /*cap*/5,
                4, 3, OiPattern.WRITER_SQUEEZE, TimeOfDayMode.OPENING_DRIVE,
                EnumSet.of(Regime.NORMAL), 50);
        assertEquals(5, r.lots(), "cap must dominate over misconfigured base");
    }

    @Test
    void capOfOne_alwaysReturnsOneOrZero() {
        // When the operator sets maxLotsPerTrade=1, the sizer cannot upsize regardless
        // of conviction — every successful entry is exactly 1 lot.
        ConvictionSizer.SizingResult r = sizer.size(IndexType.NIFTY, BASE, /*cap*/1,
                4, 3, OiPattern.WRITER_SQUEEZE, TimeOfDayMode.OPENING_DRIVE,
                EnumSet.of(Regime.NORMAL), 50);
        assertEquals(1, r.lots(), "cap=1 forces single-lot sizing");
    }

    @Test
    void gatesScore_linearInterpolation_from06To10() {
        // required=3, passed=3 → gatesScore=0.6, conv=0.6 → strength=(0.6−0.5)/0.4=0.25
        //   → scaled = 1 + round(9 × 0.25) = 1+2 = 3
        ConvictionSizer.SizingResult r3 = sizer.size(IndexType.NIFTY, BASE, CAP, 3, 3,
                OiPattern.WRITER_SQUEEZE, TimeOfDayMode.OPENING_DRIVE,
                EnumSet.of(Regime.NORMAL), 50);
        // required=3, passed=4 → gatesScore=1.0, conv=1.0 ≥ conviction-strong → full cap = 10
        ConvictionSizer.SizingResult r4 = sizer.size(IndexType.NIFTY, BASE, CAP, 4, 3,
                OiPattern.WRITER_SQUEEZE, TimeOfDayMode.OPENING_DRIVE,
                EnumSet.of(Regime.NORMAL), 50);
        assertTrue(r4.lots() > r3.lots(), "4/4 must size higher than 3/3");
        assertEquals(3, r3.lots());
        assertEquals(CAP, r4.lots());
    }

    @Test
    void midDay_4of4_squeeze_lowerSizeThanOpeningDrive() {
        ConvictionSizer.SizingResult opening = sizer.size(IndexType.NIFTY, BASE, CAP, 4, 3,
                OiPattern.WRITER_SQUEEZE, TimeOfDayMode.OPENING_DRIVE,
                EnumSet.of(Regime.NORMAL), 50);
        // MIDDAY_DISCIPLINE timeMult=0.7 → conv=0.7 → strength=0.5 → scaled = 1 + round(4.5) = 6
        ConvictionSizer.SizingResult midday = sizer.size(IndexType.NIFTY, BASE, CAP, 4, 4,
                OiPattern.WRITER_SQUEEZE, TimeOfDayMode.MIDDAY_DISCIPLINE,
                EnumSet.of(Regime.NORMAL), 50);
        assertTrue(midday.lots() < opening.lots());
    }

    @Test
    void highVolRegime_reducesLots() {
        ConvictionSizer.SizingResult normal = sizer.size(IndexType.NIFTY, BASE, CAP, 4, 3,
                OiPattern.WRITER_SQUEEZE, TimeOfDayMode.OPENING_DRIVE,
                EnumSet.of(Regime.NORMAL), 50);
        ConvictionSizer.SizingResult highVol = sizer.size(IndexType.NIFTY, BASE, CAP, 4, 3,
                OiPattern.WRITER_SQUEEZE, TimeOfDayMode.OPENING_DRIVE,
                EnumSet.of(Regime.HIGH_VOL), 50);
        assertTrue(highVol.lots() <= normal.lots(),
                "HIGH_VOL should never size above NORMAL");
        assertTrue(highVol.lots() < normal.lots(),
                "HIGH_VOL × 0.85 should round to fewer lots than NORMAL");
    }
}
