package com.algo.trade.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Picks WHICH account's Kite credentials drive the SHARED market-analysis feed
 * (WebSocket ticks, OI snapshots, instrument lookups).
 *
 * Resolution order:
 *   1. The UserBrokerConfig row flagged primaryAccount = true (if its API key
 *      and access token are present).
 *   2. (Caller's fallback) — data/trading-secrets.properties / env vars, via
 *      KiteCredentialResolver's existing file/env logic.
 *
 * Each individual user still places ORDERS through their OWN credentials via
 * UserBrokerSessionManager — this selector only governs the single shared
 * market-data session.
 */
@Component
public class PrimaryAccountSelector {

    private static final Logger log = LoggerFactory.getLogger(PrimaryAccountSelector.class);

    private final UserBrokerConfigRepository configRepository;

    public PrimaryAccountSelector(UserBrokerConfigRepository configRepository) {
        this.configRepository = configRepository;
    }

    public Optional<UserBrokerConfig> primaryConfig() {
        try {
            return configRepository.findAll().stream()
                    .filter(UserBrokerConfig::isPrimaryAccount)
                    .filter(c -> c.getApiKey() != null && !c.getApiKey().isBlank())
                    .filter(c -> c.getApiSecret() != null && !c.getApiSecret().isBlank())
                    .findFirst();
        } catch (Exception e) {
            log.debug("[PrimaryAccount] lookup failed (DB not ready?): {}", e.getMessage());
            return Optional.empty();
        }
    }

    public Optional<String> apiKey()    { return primaryConfig().map(UserBrokerConfig::getApiKey); }
    public Optional<String> apiSecret() { return primaryConfig().map(UserBrokerConfig::getApiSecret); }
    public Optional<String> accessToken() {
        return primaryConfig().map(UserBrokerConfig::getAccessToken)
                .filter(t -> t != null && !t.isBlank());
    }
    public Optional<Long> userId()      { return primaryConfig().map(UserBrokerConfig::getUserId); }
}
