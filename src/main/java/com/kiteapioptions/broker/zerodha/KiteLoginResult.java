package com.kiteapioptions.broker.zerodha;

import java.time.Instant;

public record KiteLoginResult(
        boolean success,
        String userId,
        String accessToken,
        String publicToken,
        Instant authenticatedAt
) {
}
