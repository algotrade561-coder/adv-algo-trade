package com.kiteapioptions.execution;

import com.kiteapioptions.domain.OrderResponse;
import java.util.List;
import java.util.Optional;

public record ExecutionResult(boolean accepted, Optional<OrderResponse> order, List<String> reasons) {

    public static ExecutionResult rejected(List<String> reasons) {
        return new ExecutionResult(false, Optional.empty(), List.copyOf(reasons));
    }

    public static ExecutionResult accepted(OrderResponse order, List<String> reasons) {
        return new ExecutionResult(true, Optional.of(order), List.copyOf(reasons));
    }
}
