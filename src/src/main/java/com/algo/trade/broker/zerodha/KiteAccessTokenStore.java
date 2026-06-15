package com.algo.trade.broker.zerodha;

import com.algo.trade.config.TradingProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Runtime holder for the daily Kite access token captured through manual login.
 */
@Component
public class KiteAccessTokenStore {

    private static final Logger log = LoggerFactory.getLogger(KiteAccessTokenStore.class);
    private static final Path TOKEN_FILE = Path.of("data", "kite-access-token.properties");
    private static final Path TRADING_SECRETS_FILE = Path.of("data", "trading-secrets.properties");
    private static final String ACCESS_TOKEN_PROPERTY = "trading.broker.access-token";

    private final TradingProperties properties;
    private final AtomicReference<String> accessToken = new AtomicReference<>();
    private final AtomicReference<String> publicToken = new AtomicReference<>();
    private final AtomicReference<String> userId = new AtomicReference<>();
    private final AtomicReference<Instant> updatedAt = new AtomicReference<>();

    /** Optional multi-user token resolver — provides per-user access tokens. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private MultiUserTokenResolver multiUserTokenResolver;

    /**
     * Optional selector for the PRIMARY account whose credentials drive the shared
     * market-data session. Used as the fallback source for the shared token so threads
     * without a per-user context (startup, manual reconnect, watchdog) can always resolve
     * the primary account's token even if it lives only in the DB config.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.auth.PrimaryAccountSelector primaryAccountSelector;

    /**
     * Interface for resolving per-user access tokens. Implemented by UserBrokerSessionManager.
     */
    public interface MultiUserTokenResolver {
        String getAccessTokenForUser(Long userId);
    }

    public KiteAccessTokenStore(TradingProperties properties) {
        this.properties = properties;
        // Token caching DISABLED — never load from file/env on startup.
        // A fresh Kite login is required every session. This prevents stale overnight
        // tokens from silently causing 403 TokenException on order placement.
        this.userId.set(blankToNull(properties.broker().userId()));
        cleanupStaleTokenFiles();
        log.info("KiteAccessTokenStore initialized: token caching DISABLED — fresh login required each session");
    }

    public void update(String accessToken, String publicToken, String userId) {
        this.accessToken.set(blankToNull(accessToken));
        this.publicToken.set(blankToNull(publicToken));
        this.userId.set(blankToNull(userId));
        this.updatedAt.set(Instant.now());
        // Token caching DISABLED — token lives only in memory for this JVM session.
        log.info("Kite access token updated in-memory only (no file persistence): userId={}",
                valueOrMissing(userId));
    }

    public void clear() {
        this.accessToken.set(null);
        this.publicToken.set(null);
        this.updatedAt.set(null);
        cleanupStaleTokenFiles();
    }

    /**
     * Removes any leftover token cache files from previous caching-enabled versions.
     * Called on startup and on clear() to ensure no stale tokens survive on disk.
     */
    private void cleanupStaleTokenFiles() {
        try {
            if (Files.deleteIfExists(TOKEN_FILE)) {
                log.info("Deleted stale token cache file: {}", TOKEN_FILE);
            }
            // Remove the access-token entry from trading-secrets.properties (keep other secrets intact)
            if (Files.exists(TRADING_SECRETS_FILE)) {
                Properties secrets = new Properties();
                try (var input = Files.newInputStream(TRADING_SECRETS_FILE)) {
                    secrets.load(input);
                }
                if (secrets.containsKey(ACCESS_TOKEN_PROPERTY)) {
                    secrets.remove(ACCESS_TOKEN_PROPERTY);
                    try (var output = Files.newOutputStream(TRADING_SECRETS_FILE)) {
                        secrets.store(output, "Local trading secrets. Do not commit this file.");
                    }
                    log.info("Removed cached access-token from {}", TRADING_SECRETS_FILE);
                }
            }
        } catch (IOException ex) {
            log.warn("Failed to cleanup stale token files: {}", ex.getMessage());
        }
    }

    public Optional<String> accessToken() {
        // Multi-user: if UserContext is set and UserBrokerSessionManager has a token for this user,
        // use that. This allows per-user Zerodha sessions while maintaining backward compatibility
        // with the single-user token file approach.
        if (multiUserTokenResolver != null && com.algo.trade.multiuser.UserContext.isSet()) {
            Long userId = com.algo.trade.multiuser.UserContext.getUserId();
            if (userId != null && !userId.equals(com.algo.trade.multiuser.UserContext.DEFAULT_USER_ID)) {
                String perUserToken = multiUserTokenResolver.getAccessTokenForUser(userId);
                if (perUserToken != null && !perUserToken.isBlank()) {
                    return Optional.of(perUserToken);
                }
            }
        }
        // Shared/default path: the in-memory token (pushed on login or loaded from file).
        String shared = accessToken.get();
        if (shared != null) {
            return Optional.of(shared);
        }
        // Fallback: the SHARED market-data session is driven by the PRIMARY account. When the
        // primary's token lives only in its DB config (e.g. after a daily re-login that didn't
        // push into this store), resolve it here so the WebSocket connect — which runs on a
        // no-context / default-user thread and therefore skips the per-user branch above — does
        // not fail with "no access token". The primary api_key (KiteCredentialResolver) comes
        // from the same account, so key/token stay consistent.
        if (primaryAccountSelector != null) {
            Optional<String> primaryToken = primaryAccountSelector.accessToken();
            if (primaryToken.isPresent()) {
                return primaryToken;
            }
        }
        return Optional.empty();
    }

    public Optional<String> publicToken() {
        return Optional.ofNullable(publicToken.get());
    }

    public Optional<String> userId() {
        return Optional.ofNullable(userId.get());
    }

    public Optional<Instant> updatedAt() {
        return Optional.ofNullable(updatedAt.get());
    }

    public boolean authenticated() {
        return accessToken().isPresent();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String emptyIfNull(String value) {
        return value == null ? "" : value;
    }

    private String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? blankToNull(second) : first;
    }

    private String valueOrMissing(String value) {
        return value == null || value.isBlank() ? "<missing>" : value;
    }
}
