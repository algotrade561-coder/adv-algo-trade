package com.algo.trade.config;

import com.algo.trade.broker.zerodha.KiteWebSocketClient;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * WebSocket health indicator — reports DOWN if:
 * 1. WebSocket is not connected during market hours
 * 2. No tick received in the last 60 seconds during market hours
 *
 * This ensures ALB/Docker healthcheck detects a non-functional app even if
 * the HTTP layer is still responding.
 *
 * Outside market hours (before 9:15 or after 15:35 IST), always reports UP
 * since WebSocket is expected to be idle.
 */
@Component
public class WebSocketHealthIndicator implements HealthIndicator {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final int MAX_TICK_AGE_SECONDS = 60;

    private final KiteWebSocketClient webSocketClient;

    public WebSocketHealthIndicator(KiteWebSocketClient webSocketClient) {
        this.webSocketClient = webSocketClient;
    }

    @Override
    public Health health() {
        LocalTime now = LocalTime.now(IST);

        // Outside market hours — always healthy (WS expected to be idle)
        if (now.isBefore(LocalTime.of(9, 15)) || now.isAfter(LocalTime.of(15, 35))) {
            return Health.up()
                    .withDetail("status", "IDLE")
                    .withDetail("reason", "Outside market hours")
                    .build();
        }

        // During market hours — check connection + tick freshness
        boolean connected = webSocketClient.isConnected();
        Instant lastTick = webSocketClient.getLastTickTime();
        int subscribedTokens = webSocketClient.getSubscribedTokenCount();

        if (!connected) {
            return Health.down()
                    .withDetail("status", "DISCONNECTED")
                    .withDetail("subscribedTokens", subscribedTokens)
                    .build();
        }

        if (lastTick == null) {
            // Connected but no tick ever received
            Instant connectTime = webSocketClient.getLastConnectTime();
            if (connectTime != null && Duration.between(connectTime, Instant.now()).getSeconds() > 30) {
                return Health.down()
                        .withDetail("status", "NO_TICKS")
                        .withDetail("connectedSince", connectTime.toString())
                        .withDetail("subscribedTokens", subscribedTokens)
                        .build();
            }
            // Just connected — give it time
            return Health.up()
                    .withDetail("status", "CONNECTING")
                    .withDetail("subscribedTokens", subscribedTokens)
                    .build();
        }

        long tickAge = Duration.between(lastTick, Instant.now()).getSeconds();
        if (tickAge > MAX_TICK_AGE_SECONDS) {
            return Health.down()
                    .withDetail("status", "STALE_TICKS")
                    .withDetail("lastTickAge", tickAge + "s")
                    .withDetail("lastTick", lastTick.toString())
                    .withDetail("subscribedTokens", subscribedTokens)
                    .build();
        }

        return Health.up()
                .withDetail("status", "RECEIVING_TICKS")
                .withDetail("lastTickAge", tickAge + "s")
                .withDetail("subscribedTokens", subscribedTokens)
                .build();
    }
}
