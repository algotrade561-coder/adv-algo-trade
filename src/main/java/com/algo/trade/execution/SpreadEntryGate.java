package com.algo.trade.execution;

import com.algo.trade.domain.UnderlyingSymbol;
import com.algo.trade.strategy.StrategyType;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Cross-strategy entry gate that prevents concurrent spread entries on the same underlying.
 *
 * <p>Problem: Two different strategies (e.g., IronCondor + ShortStrangle) on NIFTY can both
 * pass margin preflight on the same equity balance; the second margin-rejects mid-build
 * causing a partial unwind.</p>
 *
 * <p>Solution: Acquire a per-underlying lock before margin preflight. Release after entry
 * completes (success or failure). This serializes entries per underlying across all strategies.</p>
 */
@Component
public class SpreadEntryGate {

    private static final Logger log = LoggerFactory.getLogger(SpreadEntryGate.class);

    /** Set of underlyings currently in the entry pipeline. */
    private final Set<String> entriesInFlight = ConcurrentHashMap.newKeySet();

    /**
     * Attempt to acquire the entry gate for the given underlying.
     *
     * @return true if acquired (caller must call {@link #release} when done), false if another
     *         strategy is already entering on this underlying
     */
    public boolean tryAcquire(UnderlyingSymbol underlying, StrategyType strategy) {
        String key = underlying.name();
        if (entriesInFlight.add(key)) {
            log.debug("[SpreadEntryGate] Acquired for {} by {}", underlying, strategy);
            return true;
        }
        log.info("[SpreadEntryGate] BLOCKED: {} already has entry in-flight — {} rejected",
                underlying, strategy);
        return false;
    }

    /**
     * Release the entry gate for the given underlying.
     */
    public void release(UnderlyingSymbol underlying) {
        entriesInFlight.remove(underlying.name());
        log.debug("[SpreadEntryGate] Released for {}", underlying);
    }

    /**
     * Check if an entry is currently in-flight for the given underlying.
     */
    public boolean isEntryInFlight(UnderlyingSymbol underlying) {
        return entriesInFlight.contains(underlying.name());
    }
}
