package com.algo.trade.execution.exit;

import static org.assertj.core.api.Assertions.assertThat;
import com.algo.trade.strategy.DynamicExitManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ExitParamResolverTest {

    private ExitParamResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new ExitParamResolver(new DynamicExitManager());
    }

    @Test
    void hybridUsesWiderSlAndTighterTarget() {
        var resolved = resolver.resolveSingleLeg(
                ExitMode.HYBRID,
                20, 60, 15, 8,
                100, 50, 0, 0.5, 3);
        assertThat(resolved.stopLossPercent()).isGreaterThanOrEqualTo(20);
        assertThat(resolved.targetPercent()).isLessThanOrEqualTo(60);
        assertThat(resolved.atrUsed()).isTrue();
    }

    @Test
    void configModeIgnoresAtr() {
        var resolved = resolver.resolveSingleLeg(
                ExitMode.CONFIG,
                25, 50, 10, 5,
                100, 80, 0, 0.5, 0);
        assertThat(resolved.stopLossPercent()).isEqualTo(25);
        assertThat(resolved.targetPercent()).isEqualTo(50);
        assertThat(resolved.atrUsed()).isFalse();
    }

    @Test
    void creditSpreadHybridUsesTighterSl() {
        var resolved = resolver.resolveSpreadCredit(
                ExitMode.HYBRID, 50, 30, 120, 5000, 2);
        // HYBRID uses min(config, ATR) — tighter wins. ATR-derived SL < config 50%.
        assertThat(resolved.stopLossPercent()).isLessThanOrEqualTo(50);
        assertThat(resolved.atrUsed()).isTrue();
    }
}
