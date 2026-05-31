package com.algo.trade.tuning.infra;

import com.algo.trade.domain.IndexType;
import com.algo.trade.domain.OptionType;
import com.algo.trade.domain.TradeStatus;
import com.algo.trade.marketdata.LiveInstrumentCache;
import com.algo.trade.persistence.TradeEntity;
import com.algo.trade.persistence.TradeRepository;
import com.algo.trade.strategy.StrategyType;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Per-open-trade MAE / MFE accumulator. Replaces {@code ShiftTrapMaeMfeTracker}, and
 * fills the same gap that OI Momentum and every other buying strategy never had a
 * tracker for.
 *
 * <h2>Phase 1 wiring</h2>
 * Self-scheduled — no invasive changes to {@code LivePositionExitMonitor}. The tracker
 * polls its own active set every 60 seconds and pulls the current option premium + spot
 * from {@link LiveInstrumentCache}; a separate 5-minute cleanup task drops entries for
 * trades that have moved to {@link TradeStatus#CLOSED} so memory doesn't grow if a
 * future adapter forgets to call {@link #onExit}.
 *
 * <h2>Direction-aware MAE / MFE</h2>
 * {@link Direction#LONG} uses {@code pnlPct = (current − entry) / entry × 100}; {@link
 * Direction#SHORT} uses {@code (entry − current) / entry × 100}. In both, the maximum
 * observed {@code pnlPct} is MFE and the minimum is MAE. This means the call sites
 * (Phase 2 adapters) don't have to reason about sign conventions — they just declare
 * the direction at {@link #onEntry}.
 *
 * <h2>API for Phase 2 adapters</h2>
 * <ul>
 *   <li>{@link #onEntry} — called when the strategy successfully opens a trade.</li>
 *   <li>{@link #onTick} — optional explicit tick. The self-scheduled task covers this
 *       for most callers; only needed if a strategy wants to record sub-minute
 *       extremes (e.g. OI Momentum's 1 Hz loop).</li>
 *   <li>{@link #onExit} — called when the trade closes; returns the final snapshot
 *       for inclusion in an {@code ExitEvent}.</li>
 *   <li>{@link #peek} — read-only access for diagnostics / dashboards.</li>
 * </ul>
 *
 * <h2>Failure mode</h2>
 * Tracker failures never propagate. A bad tick swallows the exception and logs at
 * DEBUG; the next scheduled tick recovers. There's no scenario in which a tracker
 * issue can block trading.
 */
@Component
public class MaeMfeTracker {

    private static final Logger log = LoggerFactory.getLogger(MaeMfeTracker.class);

    public enum Direction { LONG, SHORT }

    /** Context captured at trade open by the adapter. */
    public record EntryContext(
            String tradeId,
            StrategyType strategy,
            IndexType index,
            String correlationKey,    // decisionKey of the originating signal
            Direction direction,
            String instrumentKey,
            int strike,
            OptionType optionType,
            BigDecimal entryPremium,
            double entrySpot,
            Instant entryAt
    ) {
        public EntryContext {
            Objects.requireNonNull(tradeId, "tradeId");
            Objects.requireNonNull(strategy, "strategy");
            Objects.requireNonNull(index, "index");
            Objects.requireNonNull(direction, "direction");
            Objects.requireNonNull(entryPremium, "entryPremium");
            Objects.requireNonNull(entryAt, "entryAt");
            if (entryPremium.signum() <= 0) {
                throw new IllegalArgumentException("entryPremium must be > 0");
            }
        }
    }

    /** Immutable read-only view returned to callers. */
    public record Snapshot(
            EntryContext entry,
            double maePct,
            Instant maeAt,
            double spotAtMae,
            double mfePct,
            Instant mfeAt,
            double spotAtMfe,
            int tickCount,
            BigDecimal lastPremium
    ) {
        public long timeToMaeSec() {
            return maeAt == null ? 0 : Duration.between(entry.entryAt(), maeAt).getSeconds();
        }
        public long timeToMfeSec() {
            return mfeAt == null ? 0 : Duration.between(entry.entryAt(), mfeAt).getSeconds();
        }
    }

    /** Internal mutable state per active trade. */
    private static final class ActiveState {
        final EntryContext entry;
        double maePct = 0.0;       // ≤ 0 (adverse)
        double mfePct = 0.0;       // ≥ 0 (favorable)
        Instant maeAt;
        Instant mfeAt;
        double spotAtMae;
        double spotAtMfe;
        int tickCount;
        BigDecimal lastPremium;

        ActiveState(EntryContext entry) { this.entry = entry; }

        Snapshot snapshot() {
            return new Snapshot(entry, maePct, maeAt, spotAtMae,
                    mfePct, mfeAt, spotAtMfe, tickCount, lastPremium);
        }
    }

    private final TradeRepository tradeRepository;
    private final LiveInstrumentCache liveInstrumentCache;
    private final Map<String, ActiveState> active = new ConcurrentHashMap<>();

    public MaeMfeTracker(TradeRepository tradeRepository,
                          LiveInstrumentCache liveInstrumentCache) {
        this.tradeRepository = tradeRepository;
        this.liveInstrumentCache = liveInstrumentCache;
    }

    // ── Adapter-facing API ────────────────────────────────────────────────

    public void onEntry(EntryContext ctx) {
        if (ctx == null) return;
        ActiveState state = new ActiveState(ctx);
        state.lastPremium = ctx.entryPremium();
        active.put(ctx.tradeId(), state);
        log.debug("[MaeMfeTracker] start tracking trade={} strategy={} dir={} entry={}",
                ctx.tradeId(), ctx.strategy(), ctx.direction(), ctx.entryPremium());
    }

    /**
     * Explicit tick — optional. Most callers can rely on the self-scheduled
     * {@link #scheduledTick()} which fires once a minute. Sub-minute tickers (1 Hz
     * trading loops) can call this directly for finer-grained MAE/MFE resolution.
     */
    public void onTick(String tradeId, BigDecimal currentPremium, double currentSpot, Instant at) {
        if (tradeId == null || currentPremium == null || currentPremium.signum() <= 0) {
            return;
        }
        ActiveState state = active.get(tradeId);
        if (state == null) {
            return;
        }
        updateState(state, currentPremium, currentSpot, at);
    }

    public Optional<Snapshot> onExit(String tradeId) {
        ActiveState state = active.remove(tradeId);
        if (state == null) {
            return Optional.empty();
        }
        log.debug("[MaeMfeTracker] exit trade={} mae={} mfe={} ticks={}",
                tradeId, state.maePct, state.mfePct, state.tickCount);
        return Optional.of(state.snapshot());
    }

    public Optional<Snapshot> peek(String tradeId) {
        ActiveState state = active.get(tradeId);
        return state == null ? Optional.empty() : Optional.of(state.snapshot());
    }

    public int activeTradeCount() {
        return active.size();
    }

    // ── Self-scheduled tick (every 60s) ────────────────────────────────────

    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void scheduledTick() {
        if (active.isEmpty()) {
            return;
        }
        Instant now = Instant.now();
        for (Map.Entry<String, ActiveState> e : active.entrySet()) {
            try {
                tickFromCache(e.getValue(), now);
            } catch (Exception ex) {
                log.debug("[MaeMfeTracker] tick failed for {}: {}", e.getKey(), ex.getMessage());
            }
        }
    }

    /** Fetches current premium + spot from {@link LiveInstrumentCache} and updates state. */
    private void tickFromCache(ActiveState state, Instant at) {
        double currentSpot = liveInstrumentCache.getFuturesPrice(state.entry.index());
        if (currentSpot <= 0) {
            return;
        }
        // Resolve the exact instrument by its symbol/key — this is what the adapter
        // already knows it traded. Avoids needing strike+expiry combos to uniquely
        // identify the option.
        Optional<com.algo.trade.domain.OptionInstrument> opt =
                liveInstrumentCache.getBySymbol(state.entry.instrumentKey());
        if (opt.isEmpty()) {
            return;
        }
        double ltp = opt.get().getLastPrice();
        if (ltp <= 0) {
            return;
        }
        updateState(state, BigDecimal.valueOf(ltp), currentSpot, at);
    }

    // ── Cleanup of orphans (every 5 min) ──────────────────────────────────

    /**
     * Drops tracker entries for trades that the {@link TradeRepository} no longer
     * reports as {@link TradeStatus#OPEN}. This protects against adapters that forget
     * to call {@link #onExit} — memory cannot grow unbounded.
     */
    @Scheduled(fixedDelay = 300_000, initialDelay = 120_000)
    public void scheduledCleanup() {
        if (active.isEmpty()) {
            return;
        }
        try {
            Set<String> openIds = tradeRepository.findByStatus(TradeStatus.OPEN).stream()
                    .map(TradeEntity::getTradeId)
                    .collect(Collectors.toSet());
            int removed = 0;
            for (String tid : Set.copyOf(active.keySet())) {
                if (!openIds.contains(tid)) {
                    active.remove(tid);
                    removed++;
                }
            }
            if (removed > 0) {
                log.info("[MaeMfeTracker] cleanup removed {} orphaned tracker entries", removed);
            }
        } catch (Exception ex) {
            log.debug("[MaeMfeTracker] cleanup failed: {}", ex.getMessage());
        }
    }

    // ── Core min/max update logic ─────────────────────────────────────────

    private static void updateState(ActiveState state, BigDecimal currentPremium,
                                     double currentSpot, Instant at) {
        state.lastPremium = currentPremium;
        state.tickCount++;
        double entry = state.entry.entryPremium().doubleValue();
        double cur = currentPremium.doubleValue();
        double pnlPct = state.entry.direction() == Direction.LONG
                ? (cur - entry) / entry * 100.0
                : (entry - cur) / entry * 100.0;
        if (pnlPct < state.maePct) {
            state.maePct = pnlPct;
            state.maeAt = at;
            state.spotAtMae = currentSpot;
        }
        if (pnlPct > state.mfePct) {
            state.mfePct = pnlPct;
            state.mfeAt = at;
            state.spotAtMfe = currentSpot;
        }
    }
}
