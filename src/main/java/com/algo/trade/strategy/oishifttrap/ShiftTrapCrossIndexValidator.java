package com.algo.trade.strategy.oishifttrap;

import com.algo.trade.domain.IndexType;
import com.algo.trade.domain.UnderlyingSymbol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;

/**
 * Feature 10 — Cross-index validation.
 *
 * <p>The OI Shift Trap strategy fires per-underlying independently. A single-index signal in
 * isolation is weaker than the same signal observed concurrently across multiple indices.
 * This validator records every candidate trap as the strategy emits it, evicts entries older
 * than {@link #WINDOW}, and answers a yes/no question: do at least {@code minAgreement} of
 * the cross-index set agree on the same trap side within the active window?
 *
 * <p>2026-06-01: the validator scans all known indices (NIFTY, BANKNIFTY, SENSEX,
 * FINNIFTY, MIDCPNIFTY). With the default {@code crossIndexMinAgreement: 1} this is a
 * no-op gate (single-index-safe). Raise the threshold to enforce peer agreement when
 * multiple indices co-evaluate.
 */
@Component
public class ShiftTrapCrossIndexValidator {

    private static final Logger log = LoggerFactory.getLogger(ShiftTrapCrossIndexValidator.class);
    private static final Duration WINDOW = Duration.ofSeconds(30);

    private record RecentSignal(String trapSide, int score, Instant at) { }

    private final Map<IndexType, RecentSignal> recent = new EnumMap<>(IndexType.class);

    public void registerCandidate(UnderlyingSymbol underlying, String trapSide, int score) {
        if (underlying == null || trapSide == null) {
            return;
        }
        IndexType ix;
        try {
            ix = IndexType.from(underlying);
        } catch (Exception e) {
            return;
        }
        synchronized (recent) {
            recent.put(ix, new RecentSignal(trapSide, score, Instant.now()));
        }
    }

    /**
     * Does the requested side have at least {@code minAgreement} of {NIFTY, BANKNIFTY, SENSEX}
     * registering the same trap side within the active window? The caller's own underlying
     * counts toward the agreement set.
     */
    public boolean hasMinimumAgreement(UnderlyingSymbol underlying, String trapSide, int minAgreement) {
        if (underlying == null || trapSide == null) {
            return false;
        }
        Instant cutoff = Instant.now().minus(WINDOW);
        int agree = 0;
        synchronized (recent) {
            // Scan all known indices, not just NIFTY/BANKNIFTY/SENSEX, so FINNIFTY and
            // MIDCPNIFTY co-validate when they're actively evaluating.
            for (IndexType ix : IndexType.values()) {
                RecentSignal sig = recent.get(ix);
                if (sig == null) continue;
                if (sig.at().isBefore(cutoff)) continue;
                if (trapSide.equals(sig.trapSide())) {
                    agree++;
                }
            }
        }
        boolean pass = agree >= minAgreement;
        if (!pass) {
            log.debug("[ShiftTrap] Cross-index disagreement for {} side={} need={} have={}",
                    underlying, trapSide, minAgreement, agree);
        }
        return pass;
    }

    /** Manual reset (test hook). */
    public void clear() {
        synchronized (recent) {
            recent.clear();
        }
    }
}
