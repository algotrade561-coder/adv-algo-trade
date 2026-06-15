package com.algo.trade.execution;

/**
 * Interface for components that need to reset their state at midnight.
 * Implement this and the {@link DailyResetService} will call {@code resetDaily()} at 00:00.
 */
public interface DailyResettable {
    void resetDaily();
}
