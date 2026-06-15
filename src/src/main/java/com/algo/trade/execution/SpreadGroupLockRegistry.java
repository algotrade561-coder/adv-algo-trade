package com.algo.trade.execution;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Component;

/**
 * Shared per-group lock registry used by both {@code SpreadPositionExitMonitor} and
 * {@code SpreadPositionReconciler} to prevent interleaving operations on the same group.
 *
 * <p>Uses striped {@link ReentrantLock} instances keyed by groupId. Locks are created
 * lazily and never removed (groups are finite and short-lived).</p>
 */
@Component
public class SpreadGroupLockRegistry {

    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    /**
     * Get the lock for a given group ID. Creates one if it doesn't exist.
     */
    public ReentrantLock getLock(String groupId) {
        return locks.computeIfAbsent(groupId, id -> new ReentrantLock());
    }

    /**
     * Remove the lock for a closed group (optional cleanup).
     */
    public void remove(String groupId) {
        locks.remove(groupId);
    }
}
