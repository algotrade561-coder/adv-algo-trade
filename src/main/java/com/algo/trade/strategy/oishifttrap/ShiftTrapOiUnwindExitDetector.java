package com.algo.trade.strategy.oishifttrap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Feature 11 — OI unwind exit.
 *
 * <p>For an OI Shift Trap trade the entry thesis is "writers are trapped on this strike;
 * they will be squeezed". If the trapped-side OI <em>drops sharply</em> after entry, the
 * writers are unwinding voluntarily — there is no squeeze coming. The trade should exit
 * before the option premium follows the OI down.
 *
 * <p>State per open trade — set by the entry path via {@link #recordEntryOi}, queried by
 * the exit monitor via {@link #checkUnwind}. The detector also accepts a caller-provided
 * fallback entry OI so that restarts (which clear in-memory state) still get the unwind
 * check using {@code TradeEntity.getEntryOpenInterest()} as a backstop.
 */
@Component
public class ShiftTrapOiUnwindExitDetector {

    private static final Logger log = LoggerFactory.getLogger(ShiftTrapOiUnwindExitDetector.class);

    public record EntryOiSnapshot(
            String tradeId,
            String trapSide,
            BigDecimal strike,
            long entryCallOi,
            long entryPutOi,
            Instant entryTime
    ) { }

    public record UnwindEvidence(
            String tradeId,
            long entryOi,
            long currentOi,
            double dropPercent,
            long minutesSinceEntry
    ) { }

    private final ConcurrentHashMap<String, EntryOiSnapshot> entrySnapshots = new ConcurrentHashMap<>();

    public void recordEntryOi(String tradeId, String trapSide, BigDecimal strike,
                               long entryCallOi, long entryPutOi, Instant entryTime) {
        if (tradeId == null || trapSide == null) {
            return;
        }
        Instant now = entryTime != null ? entryTime : Instant.now();
        entrySnapshots.put(tradeId, new EntryOiSnapshot(tradeId, trapSide, strike,
                entryCallOi, entryPutOi, now));
        log.debug("[ShiftTrap-Unwind] Recorded entry: trade={} side={} strike={} ceOi={} peOi={}",
                tradeId, trapSide, strike, entryCallOi, entryPutOi);
    }

    /**
     * Test whether the trap-side OI for {@code tradeId} has dropped by more than
     * {@code dropPercentThreshold} within {@code windowMinutes} of entry.
     *
     * <p>{@code currentOi} is the current OI on the trap strike's trapped side (CE OI for a
     * call trap, PE OI for a put trap) — caller supplies this from a live quote or chain
     * snapshot.
     *
     * <p>{@code fallbackEntryOi} is consulted only when this detector has no in-memory
     * snapshot for the trade (e.g., after a restart). When fallback is also zero or
     * negative, the detector cannot decide and returns empty.
     *
     * @return {@link UnwindEvidence} when the drop exceeded the threshold within the window,
     *         otherwise {@link Optional#empty()}.
     */
    public Optional<UnwindEvidence> checkUnwind(String tradeId, long currentOi,
                                                  double dropPercentThreshold, int windowMinutes,
                                                  long fallbackEntryOi, Instant fallbackEntryTime) {
        if (tradeId == null || currentOi < 0) {
            return Optional.empty();
        }
        long entryOi;
        Instant entryTime;
        String trapSide = "?";
        EntryOiSnapshot snap = entrySnapshots.get(tradeId);
        if (snap != null) {
            entryOi = "CE".equals(snap.trapSide()) ? snap.entryCallOi() : snap.entryPutOi();
            entryTime = snap.entryTime();
            trapSide = snap.trapSide();
        } else if (fallbackEntryOi > 0 && fallbackEntryTime != null) {
            entryOi = fallbackEntryOi;
            entryTime = fallbackEntryTime;
        } else {
            return Optional.empty();
        }
        if (entryOi <= 0) {
            return Optional.empty();
        }
        long minutesSinceEntry = Duration.between(entryTime, Instant.now()).toMinutes();
        if (minutesSinceEntry > windowMinutes) {
            // Outside the unwind detection window — return empty so the standard exit ladder takes over.
            return Optional.empty();
        }
        double dropPct = (entryOi - currentOi) * 100.0 / entryOi;
        if (dropPct < dropPercentThreshold) {
            return Optional.empty();
        }
        log.info("[ShiftTrap-Unwind] Unwind detected: trade={} side={} entryOi={} now={} drop={}% within {}m",
                tradeId, trapSide, entryOi, currentOi, String.format("%.1f", dropPct), minutesSinceEntry);
        return Optional.of(new UnwindEvidence(tradeId, entryOi, currentOi, dropPct, minutesSinceEntry));
    }

    /** Drop tracking state when a trade closes. */
    public void onTradeClosed(String tradeId) {
        if (tradeId == null) {
            return;
        }
        entrySnapshots.remove(tradeId);
    }

    /** Test/diagnostic accessor. */
    public int size() {
        return entrySnapshots.size();
    }

    /** Test hook. */
    public void clear() {
        entrySnapshots.clear();
    }
}
