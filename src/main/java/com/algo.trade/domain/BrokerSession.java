package com.algo.trade.domain;

import java.time.Instant;

/**
 * Broker session metadata without exposing credentials.
 */
public record BrokerSession(
        BrokerName brokerName,
        String userId,
        boolean authenticated,
        Instant authenticatedAt,
        Instant expiresAt
) {
}
