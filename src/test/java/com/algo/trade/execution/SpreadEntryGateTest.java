package com.algo.trade.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.algo.trade.domain.UnderlyingSymbol;
import com.algo.trade.strategy.StrategyType;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SpreadEntryGateTest {

    @Test
    void acquireSucceedsOnFreeSlot() {
        SpreadEntryGate gate = new SpreadEntryGate();
        assertThat(gate.tryAcquire(UnderlyingSymbol.NIFTY, StrategyType.LONG_STRADDLE)).isTrue();
        assertThat(gate.isEntryInFlight(UnderlyingSymbol.NIFTY)).isTrue();
    }

    @Test
    void secondAcquireOnSameUnderlyingIsBlocked() {
        SpreadEntryGate gate = new SpreadEntryGate();
        gate.tryAcquire(UnderlyingSymbol.NIFTY, StrategyType.LONG_STRADDLE);
        // A different strategy on the same underlying must be blocked while the permit is held.
        assertThat(gate.tryAcquire(UnderlyingSymbol.NIFTY, StrategyType.LONG_STRANGLE)).isFalse();
    }

    @Test
    void releaseAllowsReacquire() {
        SpreadEntryGate gate = new SpreadEntryGate();
        gate.tryAcquire(UnderlyingSymbol.NIFTY, StrategyType.LONG_STRADDLE);
        gate.release(UnderlyingSymbol.NIFTY);
        assertThat(gate.isEntryInFlight(UnderlyingSymbol.NIFTY)).isFalse();
        // After release the next strategy can acquire — this is the regression guard for the
        // leak where shouldEnter=false returned without releasing and gated the underlying forever.
        assertThat(gate.tryAcquire(UnderlyingSymbol.NIFTY, StrategyType.LONG_STRANGLE)).isTrue();
    }

    @Test
    void differentUnderlyingsAreIndependent() {
        SpreadEntryGate gate = new SpreadEntryGate();
        assertThat(gate.tryAcquire(UnderlyingSymbol.NIFTY, StrategyType.LONG_STRADDLE)).isTrue();
        assertThat(gate.tryAcquire(UnderlyingSymbol.BANKNIFTY, StrategyType.LONG_STRADDLE)).isTrue();
    }

    @Test
    void stalePermitIsReclaimedAfterTtl() throws Exception {
        SpreadEntryGate gate = new SpreadEntryGate();
        gate.tryAcquire(UnderlyingSymbol.NIFTY, StrategyType.LONG_STRADDLE);
        // Simulate a leaked permit by back-dating its acquire timestamp beyond the TTL.
        seedHeldSince(gate, UnderlyingSymbol.NIFTY,
                Instant.now().minus(SpreadEntryGate.STALE_TTL).minusSeconds(1));
        // A fresh acquire must reclaim the stale permit rather than stay blocked forever.
        assertThat(gate.tryAcquire(UnderlyingSymbol.NIFTY, StrategyType.LONG_STRANGLE)).isTrue();
    }

    @Test
    void freshPermitIsNotReclaimed() throws Exception {
        SpreadEntryGate gate = new SpreadEntryGate();
        gate.tryAcquire(UnderlyingSymbol.NIFTY, StrategyType.LONG_STRADDLE);
        // Just under the TTL — must still be held.
        seedHeldSince(gate, UnderlyingSymbol.NIFTY,
                Instant.now().minus(SpreadEntryGate.STALE_TTL).plusSeconds(30));
        assertThat(gate.tryAcquire(UnderlyingSymbol.NIFTY, StrategyType.LONG_STRANGLE)).isFalse();
    }

    @SuppressWarnings("unchecked")
    private static void seedHeldSince(SpreadEntryGate gate, UnderlyingSymbol underlying, Instant at)
            throws Exception {
        Field f = SpreadEntryGate.class.getDeclaredField("entriesInFlight");
        f.setAccessible(true);
        Map<String, Instant> map = (Map<String, Instant>) f.get(gate);
        map.put(underlying.name(), at);
    }
}
