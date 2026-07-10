package com.algo.trade.execution;

import com.algo.trade.domain.UnderlyingSymbol;
import com.algo.trade.strategy.StrategyType;
import java.time.Duration;
import java.time.Instant;
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
 *
 * <p><b>Self-healing TTL (added 2026-06-19):</b> a held permit is reclaimed automatically once
 * it is older than {@link #STALE_TTL}. This is a defensive backstop: if any caller fails to
 * {@link #release} a permit (e.g. an early-return path, or an order-cancel path that forgets to
 * release), the underlying would otherwise be gated forever and block every spread strategy on
 * it for the rest of the session. The TTL is set well above the legitimate PENDING&rarr;OPEN
 * window (limit-order cancel is ~1 minute, configurable), so it never fires during normal
 * operation — when it does fire it logs a WARN so the leak becomes visible and can be traced.</p>
 */
@Component
public class SpreadEntryGate {

    private static final Logger log = LoggerFactory.getLogger(SpreadEntryGate.class);

    /**
     * Maximum time a permit may be held before it is treated as leaked and reclaimed.
     * Must comfortably exceed the longest legitimate PENDING phase (limit-order cancel
     * window + broker latency), which is on the order of a couple of minutes.
     */
    static final Duration STALE_TTL = Duration.ofMinutes(15);

    /** Underlyings currently in the entry pipeline, mapped to the instant the permit was acquired. */
    private final ConcurrentHashMap<String, Instant> entriesInFlight = new ConcurrentHashMap<>();

    /**
     * Attempt to acquire the entry gate for the given underlying.
     *
     * @return true if acquired (caller must call {@link #release} when done), false if another
     *         strategy is already entering on this underlying
     */
    public boolean tryAcquire(UnderlyingSymbol underlying, StrategyType strategy) {
        String key = underlying.name();
        Instant now = Instant.now();
        // Atomic check-and-set per key. Grant the permit when the slot is free OR when the
        // existing permit is older than STALE_TTL (treated as leaked and reclaimed).
        boolean[] acquired = {false};
        entriesInFlight.compute(key, (k, heldSince) -> {
            if (heldSince == null) {
                acquired[0] = true;
                return now;
            }
            if (Duration.between(heldSince, now).compareTo(STALE_TTL) >= 0) {
                log.warn("[SpreadEntryGate] Reclaiming STALE permit for {} (held {}s, TTL {}s) — "
                                + "previous holder never released; granting to {}",
                        underlying, Duration.between(heldSince, now).toSeconds(),
                        STALE_TTL.toSeconds(), strategy);
                acquired[0] = true;
                return now;
            }
            acquired[0] = false;
            return heldSince; // keep existing holder
        });
        if (acquired[0]) {
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
        return entriesInFlight.containsKey(underlying.name());
    }
}
