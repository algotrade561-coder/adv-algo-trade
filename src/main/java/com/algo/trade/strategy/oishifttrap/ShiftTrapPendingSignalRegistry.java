package com.algo.trade.strategy.oishifttrap;

import com.algo.trade.domain.UnderlyingSymbol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Feature 9 — Pending signal registry.
 *
 * <p>Captures the "OI-first" case: operators position writers on a strike before price has
 * arrived — the OI imbalance + buildup gate passes, but the proximity gate doesn't yet.
 * Rather than discard the candidate, the strategy registers it here and revisits on each
 * subsequent tick. If spot moves within proximity before the TTL expires, the candidate is
 * "ready" and the strategy fires the signal.
 *
 * <p>State is in-memory only. The TTL used by the scheduled sweeper is read from
 * {@link OiShiftTrapConfig#getPendingSignalTtlMinutes()}.
 *
 * <p>2026-06-01 fix: key is now {@code underlying:side:strike} so multiple distinct strikes
 * can be tracked simultaneously per (underlying, side). Previous version overwrote earlier
 * entries when a second strike registered. {@link #checkReady} now iterates all entries
 * for the (underlying, side) prefix and picks the highest-imbalance candidate that's in
 * proximity.
 */
@Component
public class ShiftTrapPendingSignalRegistry {

    private static final Logger log = LoggerFactory.getLogger(ShiftTrapPendingSignalRegistry.class);
    private static final int FALLBACK_TTL_MINUTES = 5;

    private final OiShiftTrapConfig config;

    public ShiftTrapPendingSignalRegistry(@Autowired(required = false) OiShiftTrapConfig config) {
        this.config = config;
    }

    public record PendingEntry(
            UnderlyingSymbol underlying,
            String trapSide,
            BigDecimal targetStrike,
            double imbalance,
            long trappedOi,
            double proximityPercent,
            Instant recordedAt
    ) { }

    private final ConcurrentHashMap<String, PendingEntry> entries = new ConcurrentHashMap<>();

    public void registerPending(UnderlyingSymbol underlying, String trapSide,
                                 BigDecimal targetStrike, double imbalance, long trappedOi,
                                 double proximityPercent) {
        if (underlying == null || trapSide == null || targetStrike == null) {
            return;
        }
        String key = keyFor(underlying, trapSide, targetStrike);
        PendingEntry entry = new PendingEntry(underlying, trapSide, targetStrike,
                imbalance, trappedOi, proximityPercent, Instant.now());
        PendingEntry prev = entries.put(key, entry);
        if (prev == null) {
            log.debug("[ShiftTrap-Pending] Registered {} {} @ strike={} imb={}x prox={}%",
                    underlying, trapSide, targetStrike,
                    String.format("%.1f", imbalance), String.format("%.2f", proximityPercent));
        }
    }

    /**
     * Iterate all pending entries for {@code (underlying, side)}. For each that's now
     * within its recorded proximity AND not TTL-expired, this is a candidate. Returns the
     * highest-imbalance ready candidate (and removes it). Expired entries are pruned in
     * the same pass.
     */
    public Optional<PendingEntry> checkReady(UnderlyingSymbol underlying, String trapSide,
                                              BigDecimal spot, int ttlMinutes) {
        if (underlying == null || trapSide == null || spot == null || spot.signum() <= 0) {
            return Optional.empty();
        }
        String prefix = underlying.name() + ":" + trapSide + ":";
        double current = spot.doubleValue();
        PendingEntry best = null;
        String bestKey = null;

        Iterator<Map.Entry<String, PendingEntry>> it = entries.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, PendingEntry> e = it.next();
            if (!e.getKey().startsWith(prefix)) continue;
            PendingEntry entry = e.getValue();
            if (isExpired(entry, ttlMinutes)) {
                it.remove();
                continue;
            }
            double strike = entry.targetStrike().doubleValue();
            double proximityPct = Math.abs(strike - current) / current * 100.0;
            if (proximityPct > entry.proximityPercent()) continue;
            if (best == null || entry.imbalance() > best.imbalance()) {
                best = entry;
                bestKey = e.getKey();
            }
        }
        if (best != null) {
            entries.remove(bestKey);
            log.debug("[ShiftTrap-Pending] Ready {} {} @ strike={} imb={}x",
                    underlying, trapSide, best.targetStrike(), String.format("%.1f", best.imbalance()));
            return Optional.of(best);
        }
        return Optional.empty();
    }

    /** Periodic sweeper — drops pending entries whose TTL has elapsed. */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void sweepExpired() {
        int ttl = config != null ? Math.max(1, config.getPendingSignalTtlMinutes()) : FALLBACK_TTL_MINUTES;
        long before = entries.size();
        entries.entrySet().removeIf(e -> isExpired(e.getValue(), ttl));
        long after = entries.size();
        if (before != after) {
            log.debug("[ShiftTrap-Pending] Sweeper (ttl={}m) dropped {} expired entries (remaining={})",
                    ttl, before - after, after);
        }
    }

    public int size() {
        return entries.size();
    }

    public void clear() {
        entries.clear();
    }

    private static boolean isExpired(PendingEntry entry, int ttlMinutes) {
        return Duration.between(entry.recordedAt(), Instant.now()).toMinutes() >= Math.max(1, ttlMinutes);
    }

    private static String keyFor(UnderlyingSymbol underlying, String trapSide, BigDecimal strike) {
        return underlying.name() + ":" + trapSide + ":" + strike.toPlainString();
    }
}
