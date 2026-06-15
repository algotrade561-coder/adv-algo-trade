package com.algo.trade.risk;

public enum HaltMode {
    /** Normal — all trading allowed. */
    NONE,
    /** Soft halt — no new entries, existing positions still managed. */
    SOFT,
    /** Hard halt — no orders at all. */
    HARD
}
