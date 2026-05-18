package com.algo.trade.execution;

import com.algo.trade.domain.OrderResponse;
import java.util.List;
import java.util.Optional;

public record ExecutionResult(
        boolean accepted,
        Optional<OrderResponse> order,
        List<String> reasons,
        /** Trade ID created by this execution (empty if rejected or not yet materialized). */
        Optional<String> tradeId
) {

    /** Backward-compatible constructor without tradeId. */
    public ExecutionResult(boolean accepted, Optional<OrderResponse> order, List<String> reasons) {
        this(accepted, order, reasons, Optional.empty());
    }

    public static ExecutionResult rejected(List<String> reasons) {
        return new ExecutionResult(false, Optional.empty(), List.copyOf(reasons), Optional.empty());
    }

    public static ExecutionResult accepted(OrderResponse order, List<String> reasons) {
        return new ExecutionResult(true, Optional.of(order), List.copyOf(reasons), Optional.empty());
    }

    public static ExecutionResult accepted(OrderResponse order, List<String> reasons, String tradeId) {
        return new ExecutionResult(true, Optional.of(order), List.copyOf(reasons), Optional.ofNullable(tradeId));
    }
}
