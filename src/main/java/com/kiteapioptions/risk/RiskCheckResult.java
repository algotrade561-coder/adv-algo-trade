package com.kiteapioptions.risk;

import java.util.List;

public record RiskCheckResult(boolean allowed, List<String> reasons) {

    public static RiskCheckResult allowed(String reason) {
        return new RiskCheckResult(true, List.of(reason));
    }

    public static RiskCheckResult rejected(List<String> reasons) {
        return new RiskCheckResult(false, List.copyOf(reasons));
    }
}
