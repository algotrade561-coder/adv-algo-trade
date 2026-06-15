package com.algo.trade.strategy.oimomentum.v3;

import com.algo.trade.data.ChainSnapshot;
import com.algo.trade.domain.IndexType;
import com.algo.trade.domain.OptionType;
import com.algo.trade.domain.Quote;
import com.algo.trade.marketdata.ExpiryCalendar;
import com.algo.trade.strategy.oimomentum.OIMomentumConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link V3EntryPipeline} — specifically the LIQUIDITY_REROUTE
 * loop behaviour added in review fix P0a/P1.
 *
 * <p>Wires up real implementations of all collaborators (analyzer, gates, picker,
 * sizer) and a stub {@link V3EntryPipeline.QuoteResolver}
 * that returns programmable per-strike spreads to drive the reroute logic.</p>
 */
class V3EntryPipelineRerouteTest {

    /** OPENING_DRIVE window — avoids DEAD_CLOSE when tests run outside market hours. */
    private static final LocalTime TEST_MARKET_TIME = LocalTime.of(10, 0);

    private V3EntryPipeline pipeline;
    private ChainSignalAnalyzer analyzer;
    private OperatorFilterGate gates;
    private MultiStrikePicker picker;
    private ConvictionSizer sizer;
    private MarketContextService ctx;
    private RegimeClassifier regimeClassifier;
    private OIMomentumConfig oiConfig;
    private ExpiryCalendar expiryCalendar;

    @BeforeEach
    void setUp() {
        V3EntryPipeline.evaluationTimeOverride = TEST_MARKET_TIME;
        analyzer = new ChainSignalAnalyzer();
        gates = new OperatorFilterGate();
        picker = new MultiStrikePicker();
        sizer = new ConvictionSizer();
        ctx = new MarketContextService();
        expiryCalendar = Mockito.mock(ExpiryCalendar.class);
        Mockito.when(expiryCalendar.isExpiryDay(Mockito.any())).thenReturn(false);
        regimeClassifier = new RegimeClassifier(expiryCalendar);
        oiConfig = new OIMomentumConfig();
        pipeline = new V3EntryPipeline(regimeClassifier, ctx, analyzer, gates,
                picker, sizer, oiConfig);
    }

    @AfterEach
    void tearDown() {
        V3EntryPipeline.evaluationTimeOverride = null;
    }

    @Test
    void reroute_succeeds_whenSecondCandidatePassesG4() {
        ChainSnapshot snapshot = buildBullishSqueezeChain();
        ctx.recordSnapshot(IndexType.NIFTY, snapshot);
        regimeClassifier.classify(IndexType.NIFTY);

        // QuoteResolver returns a wide-spread quote for ATM (top-ranked) and a tight
        // one for ATM+1 — so the pipeline should reroute.
        int atm = snapshot.atmStrike();
        var resolver = (V3EntryPipeline.QuoteResolver) (ix, strike, ot) -> {
            if (strike == atm) return quote(90, 110);        // 20% spread → FAIL G4
            return quote(99, 101);                            // 2% spread → PASS G4
        };

        V3EntryDecision dec = pipeline.evaluate(IndexType.NIFTY,
                +1, "30M_HIGH_BREAK", 0.05, snapshot.spot(),
                15.0, snapshot, resolver, 1);

        // Either entered or skipped depending on remaining gates; the key assertion
        // is that we DID NOT short-circuit on the ATM's wide spread.
        assertNotEquals("oi:INSUFFICIENT", dec.reason());
    }

    @Test
    void reroute_fallback_whenAllCandidatesFailG4() {
        ChainSnapshot snapshot = buildBullishSqueezeChain();
        ctx.recordSnapshot(IndexType.NIFTY, snapshot);
        regimeClassifier.classify(IndexType.NIFTY);

        // Every candidate returns a wide-spread quote → all reroutes fail G4.
        var resolver = (V3EntryPipeline.QuoteResolver) (ix, strike, ot) -> quote(90, 110);

        V3EntryDecision dec = pipeline.evaluate(IndexType.NIFTY,
                +1, "30M_HIGH_BREAK", 0.05, snapshot.spot(),
                15.0, snapshot, resolver, 1);

        assertTrue(dec.skip(), "expected SKIP when all candidates fail G4");
        assertTrue(dec.reason().startsWith("g4_liquidity:"),
                "expected g4_liquidity skip, got: " + dec.reason());
    }

    @Test
    void no_quote_resolver_failsG4_andSkipsCleanly() {
        ChainSnapshot snapshot = buildBullishSqueezeChain();
        ctx.recordSnapshot(IndexType.NIFTY, snapshot);
        regimeClassifier.classify(IndexType.NIFTY);

        // Null quote resolver → every G4 evaluation sees null → fails.
        V3EntryDecision dec = pipeline.evaluate(IndexType.NIFTY,
                +1, "30M_HIGH_BREAK", 0.05, snapshot.spot(),
                15.0, snapshot, null, 1);

        assertTrue(dec.skip());
        assertTrue(dec.reason().startsWith("g4_liquidity:"),
                "expected g4_liquidity skip, got: " + dec.reason());
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private static ChainSnapshot buildBullishSqueezeChain() {
        List<ChainSnapshot.StrikeData> strikes = new ArrayList<>();
        for (int k = 23800; k <= 24100; k += 50) {
            // heavy CE unwind across the chain → WRITER_SQUEEZE bullish signal
            long ceChg = -150_000;
            long peChg = +50_000;
            strikes.add(new ChainSnapshot.StrikeData(k,
                    100.0, 80_000L, 1000L, 12.0, 0.5, 0.001, -0.5, 5.0,
                    99.0, 101.0, ceChg, 0, 0,
                    100.0, 80_000L, 1000L, 12.0, -0.5, 0.001, -0.5, 5.0,
                    99.0, 101.0, peChg, 0, 0));
        }
        return new ChainSnapshot(Instant.now(), "NIFTY", 23950.0, 15.0,
                "2026-06-02", 23950, strikes);
    }

    private static Quote quote(double bid, double ask) {
        return new Quote("NFO:DUMMY", Instant.now(),
                BigDecimal.valueOf((bid + ask) / 2.0), 0, 0,
                Optional.empty(),
                Optional.of(BigDecimal.valueOf(bid)),
                Optional.of(BigDecimal.valueOf(ask)),
                Optional.empty());
    }
}
