package com.algo.trade.multiuser;

/**
 * Thread-local user context holder — makes the current user ID available
 * throughout the request processing chain without passing it explicitly.
 *
 * Set by:
 * - UserContextFilter (for HTTP requests — extracts from session)
 * - UserStrategyLoop (for scheduled tasks — sets before each user's evaluation)
 *
 * Read by:
 * - ExecutionEngine, BrokerSessionManager, TradingStateService, etc.
 */
public final class UserContext {

    private static final ThreadLocal<Long> CURRENT_USER_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> CURRENT_USER_EMAIL = new ThreadLocal<>();

    /** Default user ID when multi-user is disabled (backward compatibility) */
    public static final Long DEFAULT_USER_ID = 1L;

    private UserContext() {}

    public static void setUserId(Long userId) {
        CURRENT_USER_ID.set(userId);
    }

    public static void setUserEmail(String email) {
        CURRENT_USER_EMAIL.set(email);
    }

    /**
     * Get current user ID. Returns DEFAULT_USER_ID if not set (single-user mode).
     */
    public static Long getUserId() {
        Long id = CURRENT_USER_ID.get();
        return id != null ? id : DEFAULT_USER_ID;
    }

    public static String getUserEmail() {
        return CURRENT_USER_EMAIL.get();
    }

    /**
     * Check if a user context is explicitly set (multi-user active).
     */
    public static boolean isSet() {
        return CURRENT_USER_ID.get() != null;
    }

    /**
     * Clear the context — call in finally blocks to prevent leaks.
     */
    public static void clear() {
        CURRENT_USER_ID.remove();
        CURRENT_USER_EMAIL.remove();
    }

    /**
     * Run a Runnable with a specific user context, then restore.
     */
    public static void runAs(Long userId, Runnable action) {
        Long previousUser = CURRENT_USER_ID.get();
        String previousEmail = CURRENT_USER_EMAIL.get();
        try {
            setUserId(userId);
            // Clear any inherited email so getUserEmail() can't leak the previous
            // user's email while running as a different userId. Callers that need
            // the email under runAs should set it explicitly inside the action.
            CURRENT_USER_EMAIL.remove();
            action.run();
        } finally {
            if (previousUser != null) {
                setUserId(previousUser);
            } else {
                CURRENT_USER_ID.remove();
            }
            if (previousEmail != null) {
                CURRENT_USER_EMAIL.set(previousEmail);
            } else {
                CURRENT_USER_EMAIL.remove();
            }
        }
    }
}
