package com.algo.trade.broker.zerodha;

import com.algo.trade.config.TradingProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalTime;
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
    private static final LocalTime DAILY_EXPIRY_TIME = LocalTime.of(6, 0);

    private final TradingProperties properties;
    private final AtomicReference<String> accessToken = new AtomicReference<>();
    private final AtomicReference<String> publicToken = new AtomicReference<>();
    private final AtomicReference<String> userId = new AtomicReference<>();
    private final AtomicReference<Instant> updatedAt = new AtomicReference<>();

    public KiteAccessTokenStore(TradingProperties properties) {
        this.properties = properties;
        this.accessToken.set(blankToNull(properties.broker().accessToken()));
        this.userId.set(blankToNull(properties.broker().userId()));
        this.updatedAt.set(this.accessToken.get() == null ? null : Instant.now());
        loadPersistedTokenIfAvailable();
    }

    public void update(String accessToken, String publicToken, String userId) {
        this.accessToken.set(blankToNull(accessToken));
        this.publicToken.set(blankToNull(publicToken));
        this.userId.set(blankToNull(userId));
        Instant authenticatedAt = Instant.now();
        this.updatedAt.set(authenticatedAt);
        persist(authenticatedAt);
    }

    public void clear() {
        this.accessToken.set(null);
        this.publicToken.set(null);
        this.updatedAt.set(null);
        try {
            Files.deleteIfExists(TOKEN_FILE);
            syncTradingSecrets(null);
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to clear Kite access token file", ex);
        }
    }

    public Optional<String> accessToken() {
        return Optional.ofNullable(accessToken.get());
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

    private void loadPersistedTokenIfAvailable() {
        if (this.accessToken.get() != null) {
            return;
        }
        if (Files.notExists(TOKEN_FILE)) {
            return;
        }
        try (var input = Files.newInputStream(TOKEN_FILE)) {
            Properties token = new Properties();
            token.load(input);
            Instant expiresAt = Instant.parse(token.getProperty("expiresAt"));
            if (!expiresAt.isAfter(Instant.now())) {
                log.info("Persisted Kite access token expired. Clearing token file: expiresAt={}", expiresAt);
                clear();
                return;
            }
            this.accessToken.set(blankToNull(token.getProperty("accessToken")));
            this.publicToken.set(blankToNull(token.getProperty("publicToken")));
            this.userId.set(firstNonBlank(token.getProperty("userId"), properties.broker().userId()));
            this.updatedAt.set(Instant.parse(token.getProperty("authenticatedAt")));
            log.info("Loaded persisted Kite access token: userId={}, authenticatedAt={}, expiresAt={}",
                    valueOrMissing(this.userId.get()), this.updatedAt.get(), expiresAt);
        } catch (Exception ex) {
            log.warn("Failed to load persisted Kite access token. Clearing token file: {}", ex.getMessage());
            clear();
        }
    }

    private void persist(Instant authenticatedAt) {
        String currentAccessToken = accessToken.get();
        if (currentAccessToken == null) {
            return;
        }
        Instant expiresAt = nextDailyExpiry(authenticatedAt);
        Properties token = new Properties();
        token.setProperty("userId", emptyIfNull(userId.get()));
        token.setProperty("accessToken", currentAccessToken);
        token.setProperty("publicToken", emptyIfNull(publicToken.get()));
        token.setProperty("authenticatedAt", authenticatedAt.toString());
        token.setProperty("expiresAt", expiresAt.toString());
        try {
            Files.createDirectories(TOKEN_FILE.getParent());
            try (var output = Files.newOutputStream(TOKEN_FILE)) {
                token.store(output, "Kite access token cache. Do not commit this file.");
            }
            syncTradingSecrets(currentAccessToken);
            log.info("Persisted Kite access token metadata: file={}, userId={}, authenticatedAt={}, expiresAt={}",
                    TOKEN_FILE, valueOrMissing(userId.get()), authenticatedAt, expiresAt);
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to persist Kite access token file", ex);
        }
    }

    private Instant nextDailyExpiry(Instant authenticatedAt) {
        var zoneId = properties.timezone();
        var localAuthenticatedAt = authenticatedAt.atZone(zoneId);
        var expiryDate = localAuthenticatedAt.toLocalTime().isBefore(DAILY_EXPIRY_TIME)
                ? localAuthenticatedAt.toLocalDate()
                : localAuthenticatedAt.toLocalDate().plusDays(1);
        return expiryDate.atTime(DAILY_EXPIRY_TIME).atZone(zoneId).toInstant();
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

    private void syncTradingSecrets(String currentAccessToken) throws IOException {
        Properties secrets = new Properties();
        if (Files.exists(TRADING_SECRETS_FILE)) {
            try (var input = Files.newInputStream(TRADING_SECRETS_FILE)) {
                secrets.load(input);
            }
        } else {
            Files.createDirectories(TRADING_SECRETS_FILE.getParent());
        }

        if (currentAccessToken == null || currentAccessToken.isBlank()) {
            secrets.remove(ACCESS_TOKEN_PROPERTY);
        } else {
            secrets.setProperty(ACCESS_TOKEN_PROPERTY, currentAccessToken);
        }

        try (var output = Files.newOutputStream(TRADING_SECRETS_FILE)) {
            secrets.store(output, "Local trading secrets. Do not commit this file.");
        }
        log.info("Synchronized Kite access token into trading secrets: file={}, updated={}",
                TRADING_SECRETS_FILE, currentAccessToken != null && !currentAccessToken.isBlank());
    }
}
