package com.algo.trade.api;

import com.algo.trade.broker.BrokerException;
import com.algo.trade.monitoring.ErrorEventService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.time.Instant;
import java.util.Map;

/**
 * Global exception handler for all REST endpoints.
 *
 * Catches unhandled exceptions, records them via ErrorEventService,
 * and returns structured JSON error responses.
 */
@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final ErrorEventService errorEventService;

    public GlobalExceptionHandler(ErrorEventService errorEventService) {
        this.errorEventService = errorEventService;
    }

    @ExceptionHandler(BrokerException.class)
    public ResponseEntity<Map<String, Object>> handleBrokerException(BrokerException ex) {
        errorEventService.critical("REST-API", "Broker error in API call: " + ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(errorBody("BROKER_ERROR", ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        errorEventService.low("REST-API", "Bad request: " + ex.getMessage());
        return ResponseEntity.badRequest().body(errorBody("BAD_REQUEST", ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneral(Exception ex) {
        errorEventService.high("REST-API", "Unhandled exception in API: " + ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(errorBody("INTERNAL_ERROR", ex.getMessage()));
    }

    private Map<String, Object> errorBody(String code, String message) {
        return Map.of(
                "error", code,
                "message", message != null ? message : "Unknown error",
                "timestamp", Instant.now().toString()
        );
    }
}
