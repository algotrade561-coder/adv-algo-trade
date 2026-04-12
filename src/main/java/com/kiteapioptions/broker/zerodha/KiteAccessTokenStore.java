package com.kiteapioptions.broker.zerodha;

import com.kiteapioptions.config.TradingProperties;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * Runtime holder for the daily Kite access token captured through manual login.
 */
@Component
public class KiteAccessTokenStore {

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
    }

    public void update(String accessToken, String publicToken, String userId) {
        this.accessToken.set(blankToNull(accessToken));
        this.publicToken.set(blankToNull(publicToken));
        this.userId.set(blankToNull(userId));
        this.updatedAt.set(Instant.now());
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

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
