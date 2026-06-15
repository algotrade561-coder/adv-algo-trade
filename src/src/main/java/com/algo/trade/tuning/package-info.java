/**
 * Unified tuning capture pipeline.
 *
 * <p>This package replaces the legacy sprawl of strategy-specific recorders + analyzers
 * with a single canonical event model ({@link com.algo.trade.tuning.TuningEvent} and
 * its six sealed-permits subtypes), a single recorder, a single store, and a single
 * analyzer framework with per-strategy plugins.</p>
 *
 * <h2>Phase rollout</h2>
 * <ul>
 *   <li><b>Phase 1</b> (this code) — framework foundation: event model, recorder, store,
 *       capture toggle, shared infrastructure. No strategy migrated.</li>
 *   <li><b>Phase 2+</b> — per-strategy adapters and analyzer plugins.</li>
 * </ul>
 *
 * <h2>References</h2>
 * <ul>
 *   <li>Design: {@code important/SIGNAL_CAPTURE_TUNING_REDESIGN.md}</li>
 *   <li>Phased plan: {@code important/SIGNAL_TUNING_IMPLEMENTATION_PLAN.md}</li>
 *   <li>ML extension: {@code important/ML_AI_EXTENSION_PLAN.md}</li>
 * </ul>
 */
package com.algo.trade.tuning;
