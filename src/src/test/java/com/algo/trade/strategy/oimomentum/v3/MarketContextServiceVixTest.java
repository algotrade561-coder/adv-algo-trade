package com.algo.trade.strategy.oimomentum.v3;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MarketContextServiceVixTest {

    private MarketContextService ctx;

    @BeforeEach
    void setUp() {
        ctx = new MarketContextService();
    }

    @Test
    void vixSessionPercentile_unknownUntilThreeSamples() {
        ctx.recordVix(12.0);
        assertEquals(-1.0, ctx.vixSessionPercentile());
        ctx.recordVix(12.5);
        assertEquals(-1.0, ctx.vixSessionPercentile());
        ctx.recordVix(13.0);
        assertTrue(ctx.vixSessionPercentile() >= 0);
    }

    @Test
    void vixSessionPercentile_lowWhenVixAtSessionLow() {
        ctx.recordVix(15.0);
        ctx.recordVix(14.0);
        ctx.recordVix(13.0);
        ctx.recordVix(12.0);
        assertTrue(ctx.vixSessionPercentile() < MarketContextService.VIX_SESSION_ELEVATED_PERCENTILE);
    }

    @Test
    void vixSessionPercentile_highWhenVixAtSessionHigh() {
        ctx.recordVix(12.0);
        ctx.recordVix(13.0);
        ctx.recordVix(14.0);
        ctx.recordVix(16.0);
        assertTrue(ctx.vixSessionPercentile() >= MarketContextService.VIX_SESSION_ELEVATED_PERCENTILE);
    }

    @Test
    void vixChangePctSinceSessionOpen() {
        ctx.recordVix(10.0);
        ctx.recordVix(10.5);
        assertEquals(5.0, ctx.vixChangePctSinceSessionOpen(), 0.01);
    }
}
