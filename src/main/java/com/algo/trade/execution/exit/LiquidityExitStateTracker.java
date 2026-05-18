package com.algo.trade.execution.exit;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * Per-instrument debounce for missing bid/ask ticks (LTP-only frames between depth updates).
 */
@Component
public class LiquidityExitStateTracker {

    private final Map<String, AtomicInteger> missingBidAskStreak = new ConcurrentHashMap<>();

    public int recordMissingBidAsk(String instrumentKey) {
        return missingBidAskStreak.computeIfAbsent(instrumentKey, k -> new AtomicInteger(0))
                .incrementAndGet();
    }

    public void clearMissingBidAsk(String instrumentKey) {
        missingBidAskStreak.remove(instrumentKey);
    }
}
