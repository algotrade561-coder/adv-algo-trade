package com.algo.trade.execution.exit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.algo.trade.config.GlobalConfigService;
import com.algo.trade.config.LiquidityExitProperties;
import com.algo.trade.domain.Quote;
import com.algo.trade.persistence.TradeEntity;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LiquidityExitEvaluatorTest {

    private LiquidityExitEvaluator evaluator;
    private LiquidityExitStateTracker stateTracker;

    @BeforeEach
    void setUp() {
        GlobalConfigService global = mock(GlobalConfigService.class);
        when(global.getMinLiquidityVolume()).thenReturn(5000L);
        stateTracker = new LiquidityExitStateTracker();
        LiquidityExitProperties props = new LiquidityExitProperties(
                true,
                Duration.ofSeconds(60),
                Duration.ofSeconds(120),
                5, 8, 2.0, 0.35, 0.35,
                true, 3, false, false);
        evaluator = new LiquidityExitEvaluator(props, global, stateTracker);
    }

    @Test
    void forceExitOnVeryStaleQuote() {
        TradeEntity trade = mock(TradeEntity.class);
        when(trade.getInstrumentKey()).thenReturn("NFO:TEST");
        when(trade.getEntryBidAskSpreadPercent()).thenReturn(2.0);
        when(trade.getEntryVolume()).thenReturn(10_000L);
        when(trade.getEntryOpenInterest()).thenReturn(50_000L);
        when(trade.getEntryTime()).thenReturn(Instant.now());

        Quote stale = new Quote("NFO:TEST", Instant.now().minus(Duration.ofMinutes(5)),
                BigDecimal.valueOf(100), 1000, 5000,
                Optional.empty(), Optional.of(BigDecimal.valueOf(99)), Optional.of(BigDecimal.valueOf(101)));

        Optional<LiquidityExitEvaluator.LiquidityExitSignal> signal = evaluator.evaluateSingleLeg(trade, stale);
        assertThat(signal).isPresent();
        assertThat(signal.get().reason()).isEqualTo(LiquidityExitReasons.STALE_FORCE);
    }

    @Test
    void exitWhenSpreadDoublesVsEntryUsingMid() {
        TradeEntity trade = mock(TradeEntity.class);
        when(trade.getInstrumentKey()).thenReturn("NFO:TEST");
        when(trade.getEntryBidAskSpreadPercent()).thenReturn(3.0);
        when(trade.getEntryVolume()).thenReturn(10_000L);
        when(trade.getEntryOpenInterest()).thenReturn(null);
        when(trade.getEntryTime()).thenReturn(Instant.now());

        Quote quote = new Quote("NFO:TEST", Instant.now(),
                BigDecimal.valueOf(100), 8000, 4000,
                Optional.empty(), Optional.of(BigDecimal.valueOf(97)), Optional.of(BigDecimal.valueOf(103)));

        Optional<LiquidityExitEvaluator.LiquidityExitSignal> signal = evaluator.evaluateSingleLeg(trade, quote);
        assertThat(signal).isPresent();
        assertThat(signal.get().reason()).isEqualTo(LiquidityExitReasons.SPREAD_VS_ENTRY);
    }

    @Test
    void debounceMissingBidAsk() {
        TradeEntity trade = mock(TradeEntity.class);
        when(trade.getInstrumentKey()).thenReturn("NFO:DEBOUNCE");
        when(trade.getEntryBidAskSpreadPercent()).thenReturn(1.0);
        when(trade.getEntryVolume()).thenReturn(10_000L);
        when(trade.getEntryOpenInterest()).thenReturn(null);
        when(trade.getEntryTime()).thenReturn(Instant.now());

        Quote ltpOnly = new Quote("NFO:DEBOUNCE", Instant.now(),
                BigDecimal.valueOf(50), 5000, 1000,
                Optional.empty(), Optional.empty(), Optional.empty());

        assertThat(evaluator.evaluateSingleLeg(trade, ltpOnly)).isEmpty();
        assertThat(evaluator.evaluateSingleLeg(trade, ltpOnly)).isEmpty();
        assertThat(evaluator.evaluateSingleLeg(trade, ltpOnly)).isPresent();
    }
}
