package com.algo.trade.monitoring;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * Centralized Micrometer metrics for trading operations.
 * Exposes counters and timers for broker orders, exit evaluations, and spread lifecycle.
 * Scraped via /actuator/prometheus.
 */
@Component
public class TradingMetrics {

    // ── Order metrics ─────────────────────────────────────────────────────
    private final Counter ordersPlaced;
    private final Counter ordersFilled;
    private final Counter ordersRejected;
    private final Counter ordersCancelled;
    private final Counter ordersPartialFill;
    private final Timer orderPlacementTimer;

    // ── Spread lifecycle metrics ──────────────────────────────────────────
    private final Counter spreadEntriesAttempted;
    private final Counter spreadEntriesBlocked;
    private final Counter spreadExitsTriggered;
    private final Counter spreadExitsByReason;
    private final Timer spreadExitEvaluationTimer;

    // ── Risk metrics ──────────────────────────────────────────────────────
    private final Counter riskGateBlocked;
    private final Counter correlationGateBlocked;
    private final Counter marginPreflightRejected;
    private final Counter rateLimitExceeded;

    public TradingMetrics(MeterRegistry registry) {
        // Orders
        this.ordersPlaced = Counter.builder("trading.orders.placed")
                .description("Total orders placed at broker")
                .register(registry);
        this.ordersFilled = Counter.builder("trading.orders.filled")
                .description("Orders that filled completely")
                .register(registry);
        this.ordersRejected = Counter.builder("trading.orders.rejected")
                .description("Orders rejected by broker")
                .register(registry);
        this.ordersCancelled = Counter.builder("trading.orders.cancelled")
                .description("Orders cancelled")
                .register(registry);
        this.ordersPartialFill = Counter.builder("trading.orders.partial_fill")
                .description("Orders with partial fill then terminal")
                .register(registry);
        this.orderPlacementTimer = Timer.builder("trading.orders.placement_duration")
                .description("Time to place order at broker")
                .register(registry);

        // Spreads
        this.spreadEntriesAttempted = Counter.builder("trading.spreads.entries_attempted")
                .description("Spread entry evaluations that passed shouldEnter")
                .register(registry);
        this.spreadEntriesBlocked = Counter.builder("trading.spreads.entries_blocked")
                .description("Spread entries blocked by gates")
                .register(registry);
        this.spreadExitsTriggered = Counter.builder("trading.spreads.exits_triggered")
                .description("Spread exits triggered")
                .register(registry);
        this.spreadExitsByReason = Counter.builder("trading.spreads.exits_by_reason")
                .description("Spread exits by reason")
                .register(registry);
        this.spreadExitEvaluationTimer = Timer.builder("trading.spreads.exit_evaluation_duration")
                .description("Time to evaluate spread exit policy")
                .register(registry);

        // Risk
        this.riskGateBlocked = Counter.builder("trading.risk.gate_blocked")
                .description("Entries blocked by risk gates")
                .register(registry);
        this.correlationGateBlocked = Counter.builder("trading.risk.correlation_blocked")
                .description("Entries blocked by correlation gate")
                .register(registry);
        this.marginPreflightRejected = Counter.builder("trading.risk.margin_rejected")
                .description("Entries rejected by margin preflight")
                .register(registry);
        this.rateLimitExceeded = Counter.builder("trading.risk.rate_limit_exceeded")
                .description("Order attempts that hit rate limit")
                .register(registry);
    }

    // ── Public increment methods ──────────────────────────────────────────

    public void recordOrderPlaced() { ordersPlaced.increment(); }
    public void recordOrderFilled() { ordersFilled.increment(); }
    public void recordOrderRejected() { ordersRejected.increment(); }
    public void recordOrderCancelled() { ordersCancelled.increment(); }
    public void recordPartialFill() { ordersPartialFill.increment(); }
    public Timer.Sample startOrderTimer() { return Timer.start(); }
    public void stopOrderTimer(Timer.Sample sample) { sample.stop(orderPlacementTimer); }

    public void recordSpreadEntryAttempted() { spreadEntriesAttempted.increment(); }
    public void recordSpreadEntryBlocked() { spreadEntriesBlocked.increment(); }
    public void recordSpreadExit() { spreadExitsTriggered.increment(); }
    public void recordSpreadExitReason(String reason) { spreadExitsByReason.increment(); }
    public Timer.Sample startExitEvalTimer() { return Timer.start(); }
    public void stopExitEvalTimer(Timer.Sample sample) { sample.stop(spreadExitEvaluationTimer); }

    public void recordRiskGateBlocked() { riskGateBlocked.increment(); }
    public void recordCorrelationBlocked() { correlationGateBlocked.increment(); }
    public void recordMarginRejected() { marginPreflightRejected.increment(); }
    public void recordRateLimitExceeded() { rateLimitExceeded.increment(); }
}
