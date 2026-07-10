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
        // P0-2: propagate the user into the logging MDC so logs emitted under runAs show the real
        // owner (key "userId", consumed by logback's %X{userId:-sys}) instead of "[u:sys]".
        String previousMdc = org.slf4j.MDC.get("userId");
        try {
            setUserId(userId);
            // Clear any inherited email so getUserEmail() can't leak the previous
            // user's email while running as a different userId. Callers that need
            // the email under runAs should set it explicitly inside the action.
            CURRENT_USER_EMAIL.remove();
            if (userId != null) {
                org.slf4j.MDC.put("userId", String.valueOf(userId));
            }
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
            if (previousMdc != null) {
                org.slf4j.MDC.put("userId", previousMdc);
            } else {
                org.slf4j.MDC.remove("userId");
            }
        }
    }

    /**
     * Run a value-returning action with a specific user context, then restore. Same save/restore
     * semantics as {@link #runAs(Long, Runnable)} — use this to resolve a user's per-profile config
     * (e.g. {@code callAs(userId, globalConfigService::getMaxOpenTrades)}) off that user's thread.
     */
    public static <T> T callAs(Long userId, java.util.function.Supplier<T> action) {
        java.util.concurrent.atomic.AtomicReference<T> ref = new java.util.concurrent.atomic.AtomicReference<>();
        runAs(userId, () -> ref.set(action.get()));
        return ref.get();
    }
}
