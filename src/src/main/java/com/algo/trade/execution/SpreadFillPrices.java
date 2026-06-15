package com.algo.trade.execution;

import com.algo.trade.execution.SpreadOrderExecutor.LegResult;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Collects per-instrument fill prices from spread leg placement results. */
public final class SpreadFillPrices {

    private SpreadFillPrices() {
    }

    public static Map<String, BigDecimal> fromLegResults(List<LegResult> results) {
        Map<String, BigDecimal> prices = new HashMap<>();
        if (results == null) {
            return prices;
        }
        for (LegResult result : results) {
            if (!result.success()) {
                continue;
            }
            result.fillPrice()
                    .filter(p -> p.signum() > 0)
                    .ifPresent(p -> prices.put(result.leg().instrumentKey(), p));
        }
        return prices;
    }

    public static Map<String, BigDecimal> merge(Map<String, BigDecimal> base, Map<String, BigDecimal> overlay) {
        Map<String, BigDecimal> merged = new HashMap<>();
        if (base != null) {
            merged.putAll(base);
        }
        if (overlay != null) {
            merged.putAll(overlay);
        }
        return merged;
    }

    public static Optional<BigDecimal> priceFor(Map<String, BigDecimal> prices, String instrumentKey) {
        if (prices == null || instrumentKey == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(prices.get(instrumentKey)).filter(p -> p.signum() > 0);
    }
}
