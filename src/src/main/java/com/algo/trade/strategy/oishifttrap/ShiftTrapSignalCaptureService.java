package com.algo.trade.strategy.oishifttrap;

import com.algo.trade.domain.OptionChainLevel;
import com.algo.trade.domain.OptionChainSnapshot;
import com.algo.trade.domain.StrategyDecision;
import com.algo.trade.marketdata.LiveInstrumentCache;
import com.algo.trade.reporting.SignalDecisionKey;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Orchestrates all shift-trap signal-time capture (forward, confirmations, MAE context).
 */
@Component
public class ShiftTrapSignalCaptureService {

    private final OiShiftTrapConfig config;
    private final OiShiftTrapForwardRecorder forwardRecorder;
    private final OiShiftTrapConfirmationRecorder confirmationRecorder;
    private final ShiftTrapMaeMfeTracker maeMfeTracker;

    public ShiftTrapSignalCaptureService(
            OiShiftTrapConfig config,
            OiShiftTrapForwardRecorder forwardRecorder,
            OiShiftTrapConfirmationRecorder confirmationRecorder,
            ShiftTrapMaeMfeTracker maeMfeTracker) {
        this.config = config;
        this.forwardRecorder = forwardRecorder;
        this.confirmationRecorder = confirmationRecorder;
        this.maeMfeTracker = maeMfeTracker;
    }

    public String captureSignal(
            StrategyDecision decision,
            OiShiftTrapDiagnostics diag,
            OptionChainSnapshot snapshot,
            List<com.algo.trade.domain.Candle> underlyingCandles,
            LiveInstrumentCache cache) {

        String decisionKey = SignalDecisionKey.from(decision);
        if (!config.isCaptureEnabled()) {
            return decisionKey;
        }

        OiShiftTrapDiagnostics.CandidateSnapshot snap = "CE".equals(diag.trapSide())
                ? diag.bestCe() : diag.bestPe();
        BigDecimal strike = decision.selectedStrike().orElse(diag.signalStrike());
        long trappedOi = snap != null ? snap.trappedOi() : 0;
        double proximity = snap != null ? snap.proximityPct() : 0;
        double imbalance = snap != null ? snap.imbalance() : 0;

        ShiftTrapMaeMfeTracker.EntryContext ctx = new ShiftTrapMaeMfeTracker.EntryContext(
                decisionKey,
                decision.underlying().name(),
                diag.trapSide(),
                strike,
                decision.underlyingPrice(),
                diag.signalScore(),
                imbalance,
                proximity,
                trappedOi
        );
        maeMfeTracker.registerSignalContext(ctx);

        if (config.isForwardCheckpointsEnabled()) {
            forwardRecorder.registerSignal(
                    decisionKey,
                    decision.timestamp(),
                    decision.underlying().name(),
                    diag.trapSide(),
                    strike,
                    decision.underlyingPrice(),
                    trappedOi);
        }

        if (config.isConfirmationShadowEnabled()) {
            ShiftTrapVelocityCalculator.Velocity velocity =
                    ShiftTrapVelocityCalculator.compute(underlyingCandles, decision.underlyingPrice());
            OptionChainLevel trapLevel = ShiftTrapConfirmationEvaluator.findTrapLevel(snapshot, strike);
            ShiftTrapConfirmationEvaluator.Confirmations confirmations =
                    ShiftTrapConfirmationEvaluator.evaluate(
                            diag.trapSide(), trapLevel, decision.underlyingPrice(),
                            underlyingCandles, velocity, cache, decision.underlying().name());
            confirmationRecorder.record(
                    decisionKey,
                    decision.timestamp(),
                    decision.underlying().name(),
                    diag.trapSide(),
                    strike != null ? strike.intValue() : 0,
                    confirmations,
                    velocity);
        }

        return decisionKey;
    }
}
