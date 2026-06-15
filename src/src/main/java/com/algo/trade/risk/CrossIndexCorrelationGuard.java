package com.algo.trade.risk;

import com.algo.trade.domain.SignalType;
import com.algo.trade.domain.UnderlyingSymbol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cross-index correlation block. NIFTY / BANKNIFTY / SENSEX are 0.90+ correlated
 * on intraday moves — if a strategy just placed a BUY_PE on NIFTY, taking the
 * same BUY_PE on SENSEX within 60 seconds doubles directional exposure on the
 * same bet. Today's 09:20 losses were exactly this: NIFTY 23300 PE @ 09:20:01 +
 * SENSEX 73900 PE @ 09:20:05, both wrong direction, both lost.
 *
 * <p>Usage from any entry path before placing an order:</p>
 * <pre>
 *   if (correlationGuard.shouldBlock(underlying, signalType)) {
 *       // skip this entry
 *   } else {
 *       correlationGuard.recordEntry(underlying, signalType);
 *       // proceed with order
 *   }
 * </pre>
 *
 * <p>Records: per-direction (CE-side / PE-side) timestamps of the most recent
 * entry across all indices. A new same-direction entry within the cooldown
 * window on ANY index is blocked.</p>
 *
 * <p>4 Jun 2026 — added in response to today's correlated PE BUY losses.</p>
 */
@Component
public class CrossIndexCorrelationGuard {

    private static final Logger log = LoggerFactory.getLogger(CrossIndexCorrelationGuard.class);

    /** How long after a same-direction entry on ANY index to block new entries. */
    private static final long COOLDOWN_MILLIS = 60_000L;

    /** Side enum collapses BUY_CE / BUY_PE into a single direction key. */
    private enum Side { CE, PE }

    /** Most recent same-side entry timestamp per side + the underlying that placed it. */
    private final Map<Side, Entry> lastBySide = new ConcurrentHashMap<>(new EnumMap<>(Side.class));

    private record Entry(UnderlyingSymbol underlying, Instant at) {}

    /**
     * Returns true if a same-side entry was recorded on a DIFFERENT index within
     * the cooldown window. Returns false if cooldown expired or the prior entry
     * was on the same underlying (re-fire from same strategy on same index is
     * controlled by per-strategy one-shot logic).
     */
    public boolean shouldBlock(UnderlyingSymbol underlying, SignalType signalType) {
        Side side = sideOf(signalType);
        if (side == null) return false;
        Entry prior = lastBySide.get(side);
        if (prior == null) return false;
        if (prior.underlying() == underlying) return false; // same index — not cross-index
        long ageMs = Instant.now().toEpochMilli() - prior.at().toEpochMilli();
        if (ageMs >= COOLDOWN_MILLIS) return false;
        log.warn("[CrossIndexCorrelation] BLOCKED {} on {} — prior {} entry on {} just {}ms ago "
                + "(cooldown {}ms)", signalType, underlying, side, prior.underlying(), ageMs, COOLDOWN_MILLIS);
        return true;
    }

    /** Record a fresh entry so subsequent cross-index same-side entries are blocked. */
    public void recordEntry(UnderlyingSymbol underlying, SignalType signalType) {
        Side side = sideOf(signalType);
        if (side == null) return;
        lastBySide.put(side, new Entry(underlying, Instant.now()));
    }

    /** Diagnostic — most recent entry on a side, or empty. */
    public Optional<String> describe(SignalType signalType) {
        Side side = sideOf(signalType);
        if (side == null) return Optional.empty();
        Entry e = lastBySide.get(side);
        if (e == null) return Optional.empty();
        long ageSec = (Instant.now().toEpochMilli() - e.at().toEpochMilli()) / 1000;
        return Optional.of(String.format("%s last fired on %s %ds ago", side, e.underlying(), ageSec));
    }

    private static Side sideOf(SignalType st) {
        if (st == null) return null;
        return switch (st) {
            case BUY_CE -> Side.CE;
            case BUY_PE -> Side.PE;
            default -> null; // sells, exits, etc. not correlated
        };
    }
}
