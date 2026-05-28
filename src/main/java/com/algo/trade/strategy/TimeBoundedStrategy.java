package com.algo.trade.strategy;

import java.time.LocalTime;

/**
 * Optional hook for strategies that are only meaningful in specific intraday windows.
 * Used to avoid evaluating strategies thousands of times outside their own time gates.
 */
public interface TimeBoundedStrategy {
    boolean validNow(LocalTime marketTime);
}

