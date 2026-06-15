package com.algo.trade.execution;

import com.algo.trade.domain.IndexType;
import com.algo.trade.domain.OrderResponse;
import com.algo.trade.domain.StrategyDecision;
import com.algo.trade.persistence.TradeEntity;
import com.algo.trade.reporting.SignalDecisionKey;
import com.algo.trade.strategy.StrategyConfig;
import com.algo.trade.strategy.StrategyType;
import com.algo.trade.tuning.ExecutionEvent;
import com.algo.trade.tuning.recorder.TuningEventRecorder;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Phase 6 — writes {@link ExecutionEvent} rows via the unified tuning recorder. */
@Service
public class ExecutionTuningRecorder {

    private static final Logger log = LoggerFactory.getLogger(ExecutionTuningRecorder.class);
    private static final MathContext MC = MathContext.DECIMAL64;

    @Autowired(required = false)
    private TuningEventRecorder tuningEventRecorder;

    public void recordEntry(
            StrategyDecision decision,
            BigDecimal optionPremium,
            int lotSize,
            String stage,
            boolean accepted,
            Integer quantity,
            BigDecimal riskAmount,
            BigDecimal estimatedCost,
            OrderResponse order,
            List<String> reasons,
            StrategyConfig strategyConfig
    ) {
        if (tuningEventRecorder == null || decision == null) {
            return;
        }
        try {
            StrategyType strategy = resolveStrategy(strategyConfig);
            IndexType index = IndexType.fromName(decision.underlying().name());
            String correlationKey = SignalDecisionKey.from(decision);
            int requestedQty = order != null ? order.requestedQuantity() : (quantity != null ? quantity : 0);
            int filledQty = order != null ? order.filledQuantity() : 0;
            BigDecimal avgFill = order != null ? order.averageFillPrice().orElse(null) : null;
            Double slippage = slippagePct(optionPremium, avgFill);

            Map<String, Object> attrs = new LinkedHashMap<>();
            attrs.put("accepted", accepted);
            attrs.put("lotSize", lotSize);
            attrs.put("signalType", decision.signalType().name());
            attrs.put("optionType", decision.optionType().map(Enum::name).orElse(null));
            attrs.put("riskAmount", riskAmount);
            attrs.put("estimatedCost", estimatedCost);
            if (reasons != null && !reasons.isEmpty()) {
                attrs.put("reasons", String.join("; ", reasons));
            }

            tuningEventRecorder.record(new ExecutionEvent(
                    Instant.now(),
                    Instant.now(),
                    strategy,
                    index,
                    correlationKey,
                    order != null ? order.clientOrderId() : null,
                    stage,
                    requestedQty,
                    filledQty,
                    avgFill,
                    slippage,
                    order != null ? order.rejectionReason().orElse(null) : null,
                    attrs));
        } catch (Exception ex) {
            log.warn("Execution tuning record failed (non-fatal): {}", ex.getMessage());
        }
    }

    public void recordExit(TradeEntity trade, BigDecimal exitPrice, BigDecimal realizedPnl,
                           String exitReason, OrderResponse order) {
        if (tuningEventRecorder == null || trade == null || trade.getStrategyType() == null) {
            return;
        }
        try {
            StrategyType strategy = StrategyType.valueOf(trade.getStrategyType());
            IndexType index = IndexType.fromName(trade.getUnderlying());
            Map<String, Object> attrs = new LinkedHashMap<>();
            attrs.put("instrumentKey", trade.getInstrumentKey());
            attrs.put("entryPrice", trade.getEntryPrice());
            attrs.put("exitPrice", exitPrice);
            attrs.put("realizedPnl", realizedPnl);
            attrs.put("exitReason", exitReason);
            attrs.put("quantity", trade.getQuantity());
            int requestedQty = trade.getQuantity();
            int filledQty = order != null ? order.filledQuantity() : requestedQty;
            tuningEventRecorder.record(new ExecutionEvent(
                    Instant.now(),
                    Instant.now(),
                    strategy,
                    index,
                    trade.getTradeId(),
                    order != null ? order.clientOrderId() : trade.getTradeId(),
                    "EXIT_" + (exitReason != null ? exitReason : "UNKNOWN"),
                    requestedQty,
                    filledQty,
                    order != null ? order.averageFillPrice().orElse(exitPrice) : exitPrice,
                    null,
                    null,
                    attrs));
        } catch (Exception ex) {
            log.warn("Exit execution tuning record failed (non-fatal): {}", ex.getMessage());
        }
    }

    private static StrategyType resolveStrategy(StrategyConfig config) {
        if (config != null && config.getStrategyType() != null) {
            return config.getStrategyType();
        }
        return StrategyType.DIRECTIONAL_BUY;
    }

    private static Double slippagePct(BigDecimal signalPremium, BigDecimal avgFill) {
        if (signalPremium == null || avgFill == null || signalPremium.signum() <= 0) {
            return null;
        }
        return avgFill.subtract(signalPremium, MC)
                .divide(signalPremium, MC)
                .doubleValue() * 100.0;
    }
}
