package com.kiteapioptions.controller;

import com.kiteapioptions.broker.BrokerClient;
import java.sql.Connection;
import java.time.Instant;
import java.util.Map;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);

    private final BrokerClient brokerClient;
    private final DataSource dataSource;

    public HealthController(BrokerClient brokerClient, DataSource dataSource) {
        this.brokerClient = brokerClient;
        this.dataSource = dataSource;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Instant timestamp = Instant.now();
        log.debug("Health endpoint called: timestamp={}", timestamp);
        return Map.of("status", "UP", "timestamp", timestamp);
    }

    @GetMapping("/health/broker")
    public Map<String, Object> brokerHealth() {
        try {
            var session = brokerClient.session();
            return Map.of(
                    "status", session.authenticated() ? "UP" : "DOWN",
                    "broker", session.brokerName(),
                    "userId", session.userId() == null ? "" : session.userId(),
                    "authenticatedAt", session.authenticatedAt() == null ? "" : session.authenticatedAt()
            );
        } catch (Exception ex) {
            log.warn("Broker health check failed: {}", ex.getMessage());
            return Map.of("status", "DOWN", "error", ex.getMessage());
        }
    }

    @GetMapping("/health/database")
    public Map<String, Object> databaseHealth() {
        try (Connection connection = dataSource.getConnection()) {
            return Map.of(
                    "status", connection.isValid(2) ? "UP" : "DOWN",
                    "databaseProduct", connection.getMetaData().getDatabaseProductName()
            );
        } catch (Exception ex) {
            log.warn("Database health check failed: {}", ex.getMessage());
            return Map.of("status", "DOWN", "error", ex.getMessage());
        }
    }
}
