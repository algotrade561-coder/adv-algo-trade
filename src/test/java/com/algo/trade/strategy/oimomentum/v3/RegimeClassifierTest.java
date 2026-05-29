package com.algo.trade.strategy.oimomentum.v3;

import com.algo.trade.domain.IndexType;
import com.algo.trade.marketdata.ExpiryCalendar;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RegimeClassifierTest {

    private RegimeClassifier classifier;
    private ExpiryCalendar expiryCalendar;

    @BeforeEach
    void setUp() {
        expiryCalendar = Mockito.mock(ExpiryCalendar.class);
        Mockito.when(expiryCalendar.isExpiryDay(Mockito.any())).thenReturn(false);
        classifier = new RegimeClassifier(expiryCalendar);
    }

    @Test
    void normal_default_whenNoAtrSet() {
        Set<Regime> regimes = classifier.classify(IndexType.NIFTY,
                LocalDate.of(2026, 5, 29), LocalTime.of(10, 0));
        assertTrue(regimes.contains(Regime.NORMAL));
    }

    @Test
    void lowVol_whenAtrBelow06() {
        classifier.setDailyAtrPct(IndexType.NIFTY, 0.5);
        Set<Regime> regimes = classifier.classify(IndexType.NIFTY,
                LocalDate.of(2026, 5, 29), LocalTime.of(10, 0));
        assertTrue(regimes.contains(Regime.LOW_VOL));
        assertFalse(regimes.contains(Regime.NORMAL));
    }

    @Test
    void highVol_whenAtrAbove12() {
        classifier.setDailyAtrPct(IndexType.NIFTY, 1.5);
        Set<Regime> regimes = classifier.classify(IndexType.NIFTY,
                LocalDate.of(2026, 5, 29), LocalTime.of(10, 0));
        assertTrue(regimes.contains(Regime.HIGH_VOL));
    }

    @Test
    void expiryDay_setWhenCalendarSaysSo() {
        Mockito.when(expiryCalendar.isExpiryDay(IndexType.NIFTY)).thenReturn(true);
        Set<Regime> regimes = classifier.classify(IndexType.NIFTY,
                LocalDate.of(2026, 5, 26), LocalTime.of(10, 0));
        assertTrue(regimes.contains(Regime.EXPIRY_DAY));
    }

    @Test
    void gapOpen_whenSessionOpenDivergesFromPrevClose() {
        classifier.setPreviousClose(IndexType.NIFTY, 23800);
        classifier.setSessionOpen(IndexType.NIFTY, 23950);  // 0.63% gap up
        Set<Regime> regimes = classifier.classify(IndexType.NIFTY,
                LocalDate.of(2026, 5, 29), LocalTime.of(10, 0));
        assertTrue(regimes.contains(Regime.GAP_OPEN));
    }

    @Test
    void gapOpen_notSet_whenGapBelowThreshold() {
        classifier.setPreviousClose(IndexType.NIFTY, 23900);
        classifier.setSessionOpen(IndexType.NIFTY, 23950);  // 0.21% — below 0.5%
        Set<Regime> regimes = classifier.classify(IndexType.NIFTY,
                LocalDate.of(2026, 5, 29), LocalTime.of(10, 0));
        assertFalse(regimes.contains(Regime.GAP_OPEN));
    }
}
