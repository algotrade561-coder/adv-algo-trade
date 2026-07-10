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
            attrs.put("userId", com.algo.trade.multiuser.UserContext.getUserId());
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

    /**
     * Emit an entry <b>FILL</b> execution event (stage {@code ORDER_FILLED}) from a context that has no
     * {@link StrategyDecision} — specifically the async {@code OrderFillWatchdog} path, which materialises
     * the trade on fill but previously wrote no execution event. Without this, the ONLY execution row for a
     * watchdog-filled trade was the earlier {@code ORDER_OPEN} placeholder (filledQty=0), so:
     *   (a) {@code fill_ratio} was stuck at 0.0, and
     *   (b) the decision-record counted the {@code ORDER_OPEN} placeholder as "executed", making
     *       exits-vs-executed look permanently broken.
     * Keyed by the entry {@code correlationKey} stamped on the order/trade so the fill joins signal↔exit.
     */
    public void recordFill(String strategyType, String underlying, String correlationKey, String orderId,
                           int requestedQty, int filledQty, BigDecimal avgFill) {
        if (tuningEventRecorder == null || strategyType == null || strategyType.isBlank()
                || correlationKey == null || correlationKey.isBlank()) {
            return;
        }
        try {
            StrategyType strategy = StrategyType.valueOf(strategyType);
            IndexType index = IndexType.fromName(underlying);
            Map<String, Object> attrs = new LinkedHashMap<>();
            attrs.put("accepted", true);
            attrs.put("userId", com.algo.trade.multiuser.UserContext.getUserId());
            attrs.put("origin", "watchdog_fill");
            tuningEventRecorder.record(new ExecutionEvent(
                    Instant.now(),
                    Instant.now(),
                    strategy,
                    index,
                    correlationKey,
                    orderId,
                    "ORDER_FILLED",
                    Math.max(requestedQty, filledQty),
                    filledQty,
                    avgFill,
                    null,
                    null,
                    attrs));
        } catch (Exception ex) {
            log.warn("Fill execution tuning record failed (non-fatal): {}", ex.getMessage());
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
            // JOIN FIX (2026-07-02): key the exit by the ENTRY signal's correlationKey (stamped on the trade
            // at entry) so exits join back to signal↔fill for every pipeline strategy — was trade.getTradeId(),
            // which never matched any signal key → 0% exit→signal join for ~10 single-leg strategies. Keep the
            // tradeId in the ExitEvent.tradeId column. Falls back to tradeId only when no entry key exists
            // (legacy / broker-reconciled trades). Mirrors OrderFillWatchdog's OIM/trap emitter.
            String entryKey = trade.getEntryCorrelationKey() != null && !trade.getEntryCorrelationKey().isBlank()
                    ? trade.getEntryCorrelationKey() : trade.getTradeId();
            Map<String, Object> attrs = new LinkedHashMap<>();
            attrs.put("userId", trade.getUserId());
            attrs.put("tradeId", trade.getTradeId());
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
                    entryKey,
                    order != null ? order.clientOrderId() : trade.getTradeId(),
                    "EXIT_" + (exitReason != null ? exitReason : "UNKNOWN"),
                    requestedQty,
                    filledQty,
                    order != null ? order.averageFillPrice().orElse(exitPrice) : exitPrice,
                    null,
                    null,
                    attrs));

            // Also emit a proper EXIT event so exit-attribution / decision-record populate for these
            // strategies. OI_MOMENTUM / OI_SHIFT_TRAP self-emit a richer (MAE/MFE) ExitEvent inline, so
            // exclude them here to avoid double-counting. Pipeline single-leg strategies have NO other
            // ExitEvent emitter (TuningCaptureBridge.recordExit is unwired), so this is their only one.
            // MAE/MFE are 0 here (no per-trade tracker on this path) — best-effort, still gives exit
            // reason + realized %, which is what the exit-attribution table needs.
            if (strategy != StrategyType.OI_MOMENTUM && strategy != StrategyType.OI_SHIFT_TRAP) {
                Instant exitTime = trade.getExitTime() != null ? trade.getExitTime() : Instant.now();
                long holdSec = trade.getEntryTime() != null
                        ? Math.max(0, java.time.Duration.between(trade.getEntryTime(), exitTime).getSeconds()) : 0L;
                BigDecimal entryPx = trade.getEntryPrice();
                double realizedPct = (entryPx != null && entryPx.signum() > 0 && trade.getQuantity() > 0 && realizedPnl != null)
                        ? realizedPnl.doubleValue() / (entryPx.doubleValue() * trade.getQuantity()) * 100.0 : 0.0;
                boolean reversal = exitReason != null
                        && (exitReason.toUpperCase().contains("REVERS") || exitReason.toUpperCase().contains("FLIP"));
                tuningEventRecorder.record(new com.algo.trade.tuning.ExitEvent(
                        Instant.now(), Instant.now(), strategy, index,
                        entryKey, trade.getTradeId(),
                        exitReason != null ? exitReason : "UNKNOWN",
                        entryPx != null ? entryPx : BigDecimal.ZERO,
                        exitPrice != null ? exitPrice : (entryPx != null ? entryPx : BigDecimal.ZERO),
                        realizedPct, holdSec, 0.0, 0.0, 0L, 0L, reversal, attrs));
            }
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
