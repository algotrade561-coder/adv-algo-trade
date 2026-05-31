package com.algo.trade.strategy.oishifttrap;

import org.springframework.stereotype.Service;

/**
 * Phase 6 stub — original legacy MAE/MFE tracker retired; the unified
 * {@link com.algo.trade.tuning.infra.MaeMfeTracker} now handles MAE/MFE for every
 * strategy via the adapter framework. This stub keeps the legacy bean wiring
 * intact so callers compile until they're migrated off.
 */
@Service
public class ShiftTrapMaeMfeTracker {

    /** Opaque context handle returned by {@link #resolveContext}. */
    public static final class Context {
        public Context(Object... args) { /* accept any constructor args */ }
    }

    /** Per-signal entry context (legacy name). */
    public static final class EntryContext {
        public EntryContext(Object... args) { /* accept any constructor args */ }
    }

    /** Per-trade snapshot exposed for compatibility with legacy exit logic. */
    public static final class State {
        public double maePct() { return 0.0; }
        public double mfePct() { return 0.0; }
        public long timeToMaeSec() { return 0L; }
        public long timeToMfeSec() { return 0L; }
        public int tickCount() { return 0; }
    }

    public Context resolveContext(Object... args) { return new Context(); }

    public void startTracking(Object... args) { /* no-op */ }

    public State get(String tradeId) { return null; }

    public void update(Object... args) { /* no-op */ }

    public State remove(String tradeId) { return null; }

    public void registerSignalContext(Object ctx) { /* no-op */ }
}
