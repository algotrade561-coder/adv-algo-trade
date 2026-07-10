package com.algo.trade.strategy.oimomentum;

import com.algo.trade.domain.IndexType;
import com.algo.trade.domain.OptionInstrument;
import com.algo.trade.marketdata.LiveInstrumentCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link SyntheticOiVelocityDetector}'s build/unwind classification.
 *
 * <p>The detector infers underlying direction from tick-resolution cumulative-volume growth +
 * premium direction (it deliberately does NOT use OI, which is only refreshed by NSE every ~3
 * minutes). These tests lock the directional matrix:</p>
 * <pre>
 *   CE premium ↑ on volume → BULLISH      PE premium ↑ on volume → BEARISH
 *   CE premium ↓ on volume → BEARISH      PE premium ↓ on volume → BULLISH
 * </pre>
 *
 * <p>Each test drives two {@code sample()} passes: the first establishes the per-token baseline,
 * the second grows volume + moves premium so the window has a measurable interval. Both passes
 * occur within the rolling window so the baseline is retained.</p>
 */
class SyntheticOiVelocityDetectorTest {

    private static final IndexType IDX = IndexType.NIFTY;
    private static final int ATM = 24000;

    private LiveInstrumentCache cache;
    private SyntheticOiVelocityDetector det;

    @BeforeEach
    void setUp() {
        cache = mock(LiveInstrumentCache.class);
        when(cache.getFuturesPrice(IDX)).thenReturn(24000.0);
        det = new SyntheticOiVelocityDetector(cache);
        // @Value fields aren't injected in a plain unit test — set them explicitly.
        ReflectionTestUtils.setField(det, "enabled", true);
        ReflectionTestUtils.setField(det, "underlyings", java.util.Set.of("NIFTY"));
        ReflectionTestUtils.setField(det, "windowSec", 90);
        ReflectionTestUtils.setField(det, "strikesAround", 3);
        ReflectionTestUtils.setField(det, "minIntervalVolume", 0L);
        ReflectionTestUtils.setField(det, "minConfidence", 55);
    }

    /** Mutable fake leg so we can grow cumVolume / move premium between sample passes. */
    private static final class Leg {
        final OptionInstrument inst = mock(OptionInstrument.class);
        Leg(long token, int strike, String type, long vol, double ltp) {
            when(inst.getInstrumentToken()).thenReturn(token);
            when(inst.getStrikePrice()).thenReturn(strike);
            when(inst.getOptionType()).thenReturn(type);
            when(inst.getIndexType()).thenReturn(IDX);
            set(vol, ltp);
        }
        void set(long vol, double ltp) {
            when(inst.getVolume()).thenReturn(vol);
            when(inst.getLastPrice()).thenReturn(ltp);
        }
    }

    private void feed(List<Leg> legs) {
        List<OptionInstrument> opts = new ArrayList<>();
        for (Leg l : legs) opts.add(l.inst);
        when(cache.allOptions()).thenReturn(opts);
        det.sample();
    }

    @Test
    void ceDemand_isBullish() {
        // ATM CE + neighbours; PE flat. Pass 1 baseline, pass 2: CE volume + premium rise.
        Leg ce = new Leg(1, ATM, "CE", 10_000, 100.0);
        Leg pe = new Leg(2, ATM, "PE", 10_000, 100.0);
        feed(List.of(ce, pe));                 // baseline
        ce.set(13_000, 112.0);                 // +3000 contracts, premium +12%
        feed(List.of(ce, pe));                 // measure

        SyntheticOiVelocityDetector.Signal s = det.getLatest(IDX);
        assertEquals(1, s.direction(), "CE bought up on volume → bullish");
        assertTrue(s.confidence() >= 55);
        assertTrue(s.intervalVolume() >= 3000);
    }

    @Test
    void peDemand_isBearish() {
        Leg ce = new Leg(1, ATM, "CE", 10_000, 100.0);
        Leg pe = new Leg(2, ATM, "PE", 10_000, 100.0);
        feed(List.of(ce, pe));
        pe.set(14_000, 115.0);                 // PE bought up → bearish on underlying
        feed(List.of(ce, pe));

        assertEquals(-1, det.getLatest(IDX).direction(), "PE bought up on volume → bearish");
    }

    @Test
    void ceWriting_isBearish() {
        Leg ce = new Leg(1, ATM, "CE", 10_000, 100.0);
        Leg pe = new Leg(2, ATM, "PE", 10_000, 100.0);
        feed(List.of(ce, pe));
        ce.set(13_000, 88.0);                  // CE premium falls on volume → call writing → bearish
        feed(List.of(ce, pe));

        assertEquals(-1, det.getLatest(IDX).direction(), "CE written down on volume → bearish");
    }

    @Test
    void peWriting_isBullish() {
        Leg ce = new Leg(1, ATM, "CE", 10_000, 100.0);
        Leg pe = new Leg(2, ATM, "PE", 10_000, 100.0);
        feed(List.of(ce, pe));
        pe.set(13_000, 88.0);                  // PE premium falls on volume → put writing → bullish
        feed(List.of(ce, pe));

        assertEquals(1, det.getLatest(IDX).direction(), "PE written down on volume → bullish");
    }

    @Test
    void noFreshVolume_yieldsNeutral() {
        Leg ce = new Leg(1, ATM, "CE", 10_000, 100.0);
        Leg pe = new Leg(2, ATM, "PE", 10_000, 100.0);
        feed(List.of(ce, pe));
        feed(List.of(ce, pe));                 // no volume growth, no premium move

        assertEquals(0, det.getLatest(IDX).direction(), "no fresh trading → no signal");
    }

    @Test
    void alignmentBonus_zeroWhenSignalOpposesMomentum() {
        Leg ce = new Leg(1, ATM, "CE", 10_000, 100.0);
        Leg pe = new Leg(2, ATM, "PE", 10_000, 100.0);
        feed(List.of(ce, pe));
        ce.set(13_000, 112.0);                 // bullish synthetic flow
        feed(List.of(ce, pe));

        assertEquals(0, det.alignmentBonus(IDX, -1, 10), "bullish flow must not reward bearish momentum");
        assertTrue(det.alignmentBonus(IDX, +1, 10) > 0, "bullish flow rewards bullish momentum");
    }

    @Test
    void disabled_doesNothing() {
        ReflectionTestUtils.setField(det, "enabled", false);
        assertEquals(0, det.alignmentBonus(IDX, +1, 10));
    }
}
