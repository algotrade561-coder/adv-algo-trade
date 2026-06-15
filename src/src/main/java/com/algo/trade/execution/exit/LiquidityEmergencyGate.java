package com.algo.trade.execution.exit;

import com.algo.trade.domain.Quote;
import com.algo.trade.persistence.PositionGroupEntity;
import com.algo.trade.persistence.SpreadLegEntity;
import com.algo.trade.persistence.TradeEntity;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Tier-1 liquidity emergency gate for single-leg and spread positions.
 *
 * <p>Tiers 2–5 (time, SL/target, trailing, regime) are evaluated in
 * {@link com.algo.trade.execution.LivePositionExitMonitor} and
 * {@link SpreadExitPolicy}.
 */
@Component
public class LiquidityEmergencyGate {

    private final LiquidityExitEvaluator liquidityExitEvaluator;

    public LiquidityEmergencyGate(LiquidityExitEvaluator liquidityExitEvaluator) {
        this.liquidityExitEvaluator = liquidityExitEvaluator;
    }

    public Optional<LiquidityExitEvaluator.LiquidityExitSignal> checkSingleLegEmergency(
            TradeEntity trade, Quote quote) {
        return liquidityExitEvaluator.evaluateSingleLeg(trade, quote);
    }

    public Optional<LiquidityExitEvaluator.LiquidityExitSignal> checkSpreadEmergency(
            Iterable<SpreadLegEntity> legs, Map<String, Quote> quotes, Instant groupEntryTime) {
        return liquidityExitEvaluator.evaluateSpreadGroup(legs, quotes, groupEntryTime);
    }

    public Optional<LiquidityExitEvaluator.LiquidityExitSignal> checkSpreadEmergency(
            PositionGroupEntity group, Map<String, Quote> quotes) {
        if (group == null) {
            return Optional.empty();
        }
        return liquidityExitEvaluator.evaluateSpreadGroup(group.getLegs(), quotes, group.getEntryTime());
    }

    public Optional<LiquidityExitEvaluator.LiquidityExitSignal> checkSingleLegMissingQuote(
            TradeEntity trade) {
        return liquidityExitEvaluator.evaluateMissingQuote(
                trade.getInstrumentKey(), trade.getEntryTime());
    }

    public Optional<LiquidityExitEvaluator.LiquidityExitSignal> checkSpreadMissingQuote(
            PositionGroupEntity group) {
        return liquidityExitEvaluator.evaluateSpreadMissingQuote(group);
    }
}
