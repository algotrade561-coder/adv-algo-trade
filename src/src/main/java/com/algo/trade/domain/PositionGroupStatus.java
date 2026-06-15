package com.algo.trade.domain;

/**
 * Lifecycle state for a multi-leg spread {@link PositionGroup}.
 */
public enum PositionGroupStatus {
    /** Built and persisted; broker legs not yet confirmed. */
    PENDING,
    /** All entry legs filled at broker. */
    OPEN,
    /** Entry failed or aborted; no active spread. */
    FAILED,
    /** Exited normally. */
    CLOSED
}
