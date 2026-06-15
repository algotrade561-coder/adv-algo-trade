package com.algo.trade.strategy;

import java.time.LocalTime;

/**
 * Interface for strategies that only operate within a specific time window.
 * The execution pipeline respects these boundaries and skips evaluation outside the window.
 */
public interface TimeBoundedStrategy {
    LocalTime entryStartTime();
    LocalTime entryCutoffTime();
}
