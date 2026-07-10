package com.algo.trade.strategy.spread;

import static org.assertj.core.api.Assertions.assertThat;

import com.algo.trade.domain.PositionGroup;
import com.algo.trade.domain.SpreadEvaluationContext;
import com.algo.trade.domain.SpreadLeg;
import com.algo.trade.domain.UnderlyingSymbol;
import com.algo.trade.execution.SpreadEntryGate;
import com.algo.trade.strategy.StrategyConfig;
import com.algo.trade.strategy.StrategyType;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * FIX-1 regression guard at the CALL SITE.
 *
 * <p>{@code SpreadEntryGateTest} proves the gate primitive (acquire / release / TTL reclaim) in
 * isolation. This test proves the thing that actually leaked: that
 * {@link AbstractSpreadStrategy#evaluateAndEnter} acquires the cross-strategy permit (step 1c)
 * and then <b>releases</b> it on the {@code shouldEnter=false} early-return path
 * (AbstractSpreadStrategy ~line 311). Before the fix, that path returned
 * {@code Optional.empty()} without releasing, which gated the underlying permanently for every
 * spread strategy — the ~100% {@code crossStrategyGate} pattern in the tuning data.</p>
 *
 * <p>Pure unit test: no Spring context. The optional risk gates (correlation, MarketGuard,
 * portfolio Greeks) are left unwired (null) so they are skipped, and a recording
 * {@link SpreadEntryGate} subclass is injected into the private field via reflection so we can
 * assert that exactly one acquire and one release happened.</p>
 */
class AbstractSpreadStrategyGateReleaseTest {

    @Test
    void acquiresThenReleasesPermitWhenShouldEnterIsFalse() throws Exception {
        RecordingGate gate = new RecordingGate();
        TestSpread strategy = new TestSpread();
        inject(strategy, "spreadEntryGate", gate);

        StrategyConfig config = new StrategyConfig(StrategyType.LONG_STRADDLE);
        config.setEnabled(true); // so isDisabled(config) == false and we reach the gate + shouldEnter

        SpreadEvaluationContext ctx = new SpreadEvaluationContext(
                BigDecimal.valueOf(23500), // underlyingPrice (unused before shouldEnter)
                0.0,                        // ivRank
                null,                       // optionChain
                config,                     // config (enabled)
                UnderlyingSymbol.NIFTY,     // underlying (gate key)
                null,                       // indexType
                null,                       // marketTime
                null);                      // trendCandles

        var result = strategy.evaluateAndEnter(ctx);

        // shouldEnter=false -> no entry
        assertThat(result).isEmpty();
        // The gate WAS exercised on this path...
        assertThat(gate.acquires).as("permit acquired at step 1c").isEqualTo(1);
        // ...and crucially RELEASED (the FIX-1 leak fix).
        assertThat(gate.releases).as("permit released on shouldEnter=false path").isEqualTo(1);
        // Post-condition: the underlying is not gated, so another spread strategy can acquire it.
        assertThat(gate.isEntryInFlight(UnderlyingSymbol.NIFTY)).isFalse();
        assertThat(gate.tryAcquire(UnderlyingSymbol.NIFTY, StrategyType.LONG_STRANGLE)).isTrue();
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /** Records acquire/release counts while delegating to the real gate behaviour. */
    private static final class RecordingGate extends SpreadEntryGate {
        int acquires;
        int releases;

        @Override
        public boolean tryAcquire(UnderlyingSymbol underlying, StrategyType strategy) {
            acquires++;
            return super.tryAcquire(underlying, strategy);
        }

        @Override
        public void release(UnderlyingSymbol underlying) {
            releases++;
            super.release(underlying);
        }
    }

    /**
     * Minimal concrete spread strategy whose entry condition is always false, so
     * {@code evaluateAndEnter} takes the {@code shouldEnter=false} early-return path.
     * All constructor collaborators are null — none are dereferenced before that path.
     */
    private static final class TestSpread extends AbstractSpreadStrategy {
        TestSpread() {
            super(null, null, null, null, null, null, null, null);
        }

        @Override
        protected boolean shouldEnter(SpreadEvaluationContext ctx) {
            return false;
        }

        @Override
        protected List<SpreadLeg> constructLegs(SpreadEvaluationContext ctx) {
            return List.of();
        }

        @Override
        protected boolean shouldExit(PositionGroup group, Map<String, BigDecimal> currentPrices,
                                     StrategyConfig config) {
            return false;
        }

        @Override
        public StrategyType strategyType() {
            return StrategyType.LONG_STRADDLE;
        }
    }

    private static void inject(Object target, String fieldName, Object value) throws Exception {
        Field f = AbstractSpreadStrategy.class.getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }
}
