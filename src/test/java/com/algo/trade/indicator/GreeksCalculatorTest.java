package com.algo.trade.indicator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Verifies the Black-76 (forward-price) IV wiring introduced 2026-06-27 and the forward helpers.
 * For index options the correct underlying is the FORWARD, not spot (Sensibull/NSE use futures
 * directly). Black-76 is obtained from the BSM engine by setting q := r.
 */
class GreeksCalculatorTest {

    private final GreeksCalculator gc = new GreeksCalculator();
    private static final double R = 0.065;

    // Reference normal CDF / Black-76 call price (independent of the calculator's internals).
    private static double n(double x) { return 0.5 * (1 + erf(x / Math.sqrt(2))); }
    private static double erf(double x) {
        double t = 1.0 / (1.0 + 0.3275911 * Math.abs(x));
        double y = 1.0 - (((((1.061405429 * t - 1.453152027) * t)
                + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * Math.exp(-x * x);
        return x >= 0 ? y : -y;
    }
    private static double black76Call(double f, double k, double t, double r, double sig) {
        double d1 = (Math.log(f / k) + 0.5 * sig * sig * t) / (sig * Math.sqrt(t));
        double d2 = d1 - sig * Math.sqrt(t);
        return Math.exp(-r * t) * (f * n(d1) - k * n(d2));
    }

    @Test
    void black76IvRoundTrip() {
        // Price an option under Black-76 at a known vol, then confirm calculateIV(q=r) recovers it.
        double f = 24050, k = 24000, t = 7.0 / 252, sig = 0.14;
        double price = black76Call(f, k, t, R, sig);
        double iv = gc.calculateIV(f, k, t, R, R, price, true);
        assertEquals(sig, iv, 1e-3, "Black-76 IV should round-trip the input vol");
    }

    @Test
    void forwardFromParityMatchesPutCallParity() {
        LocalDate exp = LocalDate.now().plusDays(3);
        double f = gc.forwardFromParity(24000, 120, 70, exp); // C - P = 50
        double t = gc.timeToExpiry(exp);
        assertEquals(24000 + Math.exp(R * t) * 50, f, 1e-6);
        assertTrue(f > 24000, "call richer than put → forward above strike");
    }

    @Test
    void forwardFromParityRejectsInvalidInputs() {
        assertTrue(Double.isNaN(gc.forwardFromParity(24000, 0, 70, LocalDate.now().plusDays(3))));
        assertTrue(Double.isNaN(gc.forwardFromParity(0, 120, 70, LocalDate.now().plusDays(3))));
    }

    @Test
    void forwardFromSpotAddsCarry() {
        LocalDate exp = LocalDate.now().plusDays(7);
        double f = gc.forwardFromSpot(24000, exp);
        assertTrue(f > 24000, "forward = spot·e^{rT} must exceed spot for positive carry");
        assertEquals(24000 * Math.exp(R * gc.timeToExpiry(exp)), f, 1e-6);
    }

    // ── 2026-07-06 IV-drift fix: calendar/365 T convention + mid-price IV source ──

    /**
     * The default (calendar/365) clock must reproduce the live-confirmed reference: for a NIFTY 1-DTE snapshot
     * at 11:20 IST on 2026-07-06 (expiry 07-07 15:30), wall-clock time to expiry is ~28.17h. T ≈ 28.17/24/365.
     */
    @Test
    void calendar365IsWallClockToExpiryClose() {
        GreeksCalculator g = new GreeksCalculator(); // fields default to CALENDAR_365
        LocalDateTime now = LocalDateTime.of(2026, 7, 6, 11, 20);
        LocalDate expiry = LocalDate.of(2026, 7, 7);
        double hours = 28.0 + (10.0 / 60.0); // 11:20 → next-day 15:30
        double expected = hours / 24.0 / 365.0;
        assertEquals(expected, g.timeToExpiry(expiry, now), 1e-6,
                "calendar/365 T must be continuous wall-clock seconds to the 15:30 expiry close over a 365d year");
    }

    /**
     * The legacy trading/252 clock stays available behind the flag and gives a LARGER T than calendar/365 for
     * short-dated options (a whole trading day counts as 1/252 ≈ 0.4% of a year vs ~1 calendar day / 365). A
     * larger T is exactly why the old bot IV read LOW.
     */
    @Test
    void trading252IsSelectableAndLargerThanCalendarForShortDated() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 6, 11, 20);
        LocalDate expiry = LocalDate.of(2026, 7, 7);

        GreeksCalculator cal = new GreeksCalculator();
        GreeksCalculator trad = new GreeksCalculator();
        ReflectionTestUtils.setField(trad, "timeConvention", "TRADING_252");

        double tCal = cal.timeToExpiry(expiry, now);
        double tTrad = trad.timeToExpiry(expiry, now);
        assertTrue(tTrad > tCal,
                "trading/252 T (" + tTrad + ") should exceed calendar/365 T (" + tCal + ") for a 1-DTE option");
        // √(tCal/tTrad) is the IV-ratio the fix restores; live NIFTY data gave ≈0.70 (8.9% → 12.8%).
        double ivRatio = Math.sqrt(tCal / tTrad);
        assertTrue(ivRatio > 0.6 && ivRatio < 0.8,
                "1-DTE IV ratio calendar-vs-trading should be ≈0.70 (observed live), was " + ivRatio);
    }

    /** MID (default) uses the bid/ask midpoint; a crossed/one-sided/missing book falls back to LTP. */
    @Test
    void ivPriceSourceMidUsesMidWithLtpFallback() {
        GreeksCalculator g = new GreeksCalculator(); // default MID
        com.algo.trade.domain.OptionInstrument o = new com.algo.trade.domain.OptionInstrument(
                1L, "NIFTY24400CE", "NFO", com.algo.trade.domain.IndexType.NIFTY,
                24400, "CE", LocalDate.now().plusDays(2), 75);
        o.setLastPrice(70.0);
        o.setBestBid(69.0);
        o.setBestAsk(71.0);
        assertEquals(70.0, g.resolveIvMarketPrice(o), 1e-9, "healthy two-sided book → mid (69+71)/2 = 70");
        o.setBestBid(60.0);
        o.setBestAsk(80.0); // (80-60)/70 = 28.6% spread ≤ 50% → still mid
        assertEquals(70.0, g.resolveIvMarketPrice(o), 1e-9);
        o.setBestBid(0.0);
        o.setBestAsk(0.0); // no book → LTP
        assertEquals(70.0, g.resolveIvMarketPrice(o), 1e-9, "missing book → LTP fallback");
        o.setBestBid(75.0);
        o.setBestAsk(65.0); // crossed → LTP
        assertEquals(70.0, g.resolveIvMarketPrice(o), 1e-9, "crossed book → LTP fallback");

        ReflectionTestUtils.setField(g, "ivPriceSource", "LTP");
        o.setBestBid(69.0);
        o.setBestAsk(71.0);
        o.setLastPrice(68.0);
        assertEquals(68.0, g.resolveIvMarketPrice(o), 1e-9, "LTP mode ignores the book");
    }
}
