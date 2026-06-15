package com.algo.trade.monitoring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Structured Logger — adds correlation IDs and consistent format for production observability.
 *
 * Usage:
 *   structuredLogger.startRequest("trade-execution");
 *   // ... do work (all log statements within this thread get the correlationId in MDC)
 *   structuredLogger.endRequest();
 *
 * Enables tracing a complete execution flow across multiple services/methods in logs.
 */
@Component
public class StructuredLogger {

    private static final Logger log = LoggerFactory.getLogger(StructuredLogger.class);
    private static final String CORRELATION_ID_KEY = "correlationId";
    private static final String OPERATION_KEY = "operation";

    /**
     * Start a correlated operation — sets correlation ID in MDC for all subsequent logs.
     */
    public String startRequest(String operation) {
        String correlationId = UUID.randomUUID().toString().substring(0, 8);
        MDC.put(CORRELATION_ID_KEY, correlationId);
        MDC.put(OPERATION_KEY, operation);
        log.debug("[{}] Operation started: {}", correlationId, operation);
        return correlationId;
    }

    /**
     * End a correlated operation — clears MDC.
     */
    public void endRequest() {
        String cid = MDC.get(CORRELATION_ID_KEY);
        String op = MDC.get(OPERATION_KEY);
        if (cid != null) {
            log.debug("[{}] Operation completed: {}", cid, op);
        }
        MDC.remove(CORRELATION_ID_KEY);
        MDC.remove(OPERATION_KEY);
    }

    /**
     * Log a structured event within the current correlation context.
     */
    public void logEvent(String category, String message, Object... args) {
        String cid = MDC.get(CORRELATION_ID_KEY);
        if (cid != null) {
            log.info("[{}][{}] {}", cid, category, String.format(message, args));
        } else {
            log.info("[{}] {}", category, String.format(message, args));
        }
    }

    /**
     * Log an order-related event.
     */
    public void logOrder(String action, String orderId, String symbol, String side, int qty, double price) {
        logEvent("ORDER", "%s orderId=%s %s %s x%d @%.2f", action, orderId, side, symbol, qty, price);
    }

    /**
     * Log a risk-related event.
     */
    public void logRisk(String event, String details) {
        logEvent("RISK", "%s — %s", event, details);
    }

    /**
     * Log a fill-related event.
     */
    public void logFill(String orderId, String symbol, double fillPrice, int filledQty) {
        logEvent("FILL", "orderId=%s %s filled @%.2f qty=%d", orderId, symbol, fillPrice, filledQty);
    }

    /**
     * Get current correlation ID (null if not in a correlated context).
     */
    public String getCurrentCorrelationId() {
        return MDC.get(CORRELATION_ID_KEY);
    }
}
