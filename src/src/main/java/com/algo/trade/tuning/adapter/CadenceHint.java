package com.algo.trade.tuning.adapter;

/**
 * Frequency hint declared by each {@link TuningCaptureAdapter}. Drives:
 *
 * <ul>
 *   <li>Episode dedup defaults — HIGH-cadence strategies benefit most from aggressive
 *       dedup; MEDIUM rarely needs it; LOW essentially never triggers it.</li>
 *   <li>Summary-table granularity — the analyzer may choose coarser aggregations for
 *       HIGH-cadence strategies to keep summary file sizes bounded.</li>
 *   <li>Per-strategy CSV partitioning policy — Phase 6 hourly sub-files (see design
 *       § 4.2.2) apply only to HIGH-cadence strategies.</li>
 * </ul>
 *
 * <p>See design §3 principle 6 and § 12.1 for sizing rationale.</p>
 */
public enum CadenceHint {

    /** 1 Hz or faster — e.g. OI Momentum. Episode dedup essential. */
    HIGH,

    /** Per-candle (1m / 5m / 15m) or per-scan — e.g. Momentum, Scalping, OI Shift Trap. */
    MEDIUM,

    /** Event-driven, ~1 per day or per window — e.g. Gap-and-Go, Event Driven, Expiry Gamma. */
    LOW
}
