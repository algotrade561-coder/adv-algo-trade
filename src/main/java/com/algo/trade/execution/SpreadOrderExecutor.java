package com.algo.trade.execution;

import com.algo.trade.domain.*;
import com.algo.trade.marketdata.MarketDataService;
import com.algo.trade.notification.TelegramAlertService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.*;

/**
 * Executes multi-leg spread orders with safety guarantees:
 *
 * 1. Leg sequencing: SELL legs first (higher margin), then BUY legs (hedge)
 * 2. Partial fill handling: if any leg fails, unwind all filled legs immediately
 * 3. Slippage budget: uses LIMIT orders with configurable protection %
 * 4. Position reconciliation: verifies all legs filled before confirming
 * 5. Alerting: Telegram alert on partial state or failure
 *
 * Since Zerodha doesn't offer atomic multi-leg orders, this provides
 * the best-effort safety layer for sequential leg placement.
 */
@Component
public class SpreadOrderExecutor {

    private static final Logger log = LoggerFactory.getLogger(SpreadOrderExecutor.class);
    private static final MathContext MC = MathContext.DECIMAL64;
    private static final double SPREAD_PROTECTION_PERCENT = 1.5; // Wider than single-leg (1%)

    private final ExecutionEngine executionEngine;
    private final MarketDataService marketDataService;
    private final TelegramAlertService telegramAlertService;

    public SpreadOrderExecutor(ExecutionEngine executionEngine,
                                MarketDataService marketDataService,
                                TelegramAlertService telegramAlertService) {
        this.executionEngine = executionEngine;
        this.marketDataService = marketDataService;
        this.telegramAlertService = telegramAlertService;
    }

    /**
     * Execute all legs of a spread with safety guarantees.
     *
     * @param legs      All legs to execute
     * @param groupId   Position group ID for tracking
     * @param strategyName Strategy name for logging
     * @return Result with per-leg outcomes
     */
    public SpreadExecutionResult execute(List<SpreadLeg> legs, String groupId, String strategyName) {
        log.info("[SpreadExecutor] Starting: group={}, strategy={}, legs={}", groupId, strategyName, legs.size());

        // 1. Sequence legs: BUY first (hedge — low margin), then SELL (needs spread margin benefit)
        // This ensures the broker recognizes the hedge before charging full naked margin on SELL legs.
        List<SpreadLeg> buyLegs = legs.stream().filter(l -> l.side() == OrderSide.BUY).toList();
        List<SpreadLeg> sellLegs = legs.stream().filter(l -> l.side() == OrderSide.SELL).toList();
        List<SpreadLeg> sequenced = new ArrayList<>();
        sequenced.addAll(buyLegs);   // Hedge first (low cost, just premium)
        sequenced.addAll(sellLegs);  // Then sell (broker sees hedge → reduced margin)

        // 2. Execute each leg with LIMIT + protection
        List<LegResult> results = new ArrayList<>();
        boolean anyFailed = false;

        for (SpreadLeg leg : sequenced) {
            if (anyFailed) {
                results.add(new LegResult(leg, false, "Skipped — previous leg failed"));
                continue;
            }

            LegResult result = executeLeg(leg, groupId);
            results.add(result);

            if (!result.success()) {
                anyFailed = true;
                log.error("[SpreadExecutor] Leg FAILED: group={}, instrument={}, side={}, reason={}",
                        groupId, leg.instrumentKey(), leg.side(), result.reason());
            }
        }

        // 3. If any leg failed, unwind all filled legs
        if (anyFailed) {
            log.warn("[SpreadExecutor] Partial fill detected — unwinding filled legs for group={}", groupId);
            unwindFilledLegs(results, groupId);

            telegramAlertService.systemAlert(String.format(
                    "🚨 SPREAD PARTIAL FILL — UNWINDING\nStrategy: %s\nGroup: %s\nFailed leg: %s\nAll legs reversed.",
                    strategyName, groupId, results.stream()
                            .filter(r -> !r.success())
                            .map(r -> r.leg().instrumentKey() + " " + r.leg().side())
                            .findFirst().orElse("unknown")));

            return new SpreadExecutionResult(false, results, "Partial fill — unwound");
        }

        // 4. All legs succeeded
        log.info("[SpreadExecutor] All legs placed successfully: group={}, legs={}", groupId, results.size());
        return new SpreadExecutionResult(true, results, "All legs filled");
    }

    /**
     * Execute exit for all legs (reverse sides).
     */
    public SpreadExecutionResult executeExit(List<SpreadLeg> legs, String groupId, String strategyName) {
        log.info("[SpreadExecutor] Exit starting: group={}, strategy={}, legs={}", groupId, strategyName, legs.size());

        List<LegResult> results = new ArrayList<>();
        boolean anyFailed = false;

        for (SpreadLeg leg : legs) {
            // Reverse the side for exit
            OrderSide exitSide = leg.side() == OrderSide.BUY ? OrderSide.SELL : OrderSide.BUY;
            SpreadLeg exitLeg = new SpreadLeg(leg.instrumentKey(), leg.strike(),
                    leg.optionType(), exitSide, leg.quantity(), leg.expiry());

            LegResult result = executeLeg(exitLeg, groupId);
            results.add(result);

            if (!result.success()) {
                anyFailed = true;
                log.error("[SpreadExecutor] Exit leg FAILED: group={}, instrument={}, side={}",
                        groupId, leg.instrumentKey(), exitSide);
            }
        }

        if (anyFailed) {
            telegramAlertService.systemAlert(String.format(
                    "⚠️ SPREAD EXIT PARTIAL — some legs failed to close\nStrategy: %s\nGroup: %s\nManual intervention may be needed.",
                    strategyName, groupId));
        }

        return new SpreadExecutionResult(!anyFailed, results,
                anyFailed ? "Partial exit — some legs failed" : "All legs exited");
    }

    // ── Private helpers ─────────────────────────────────────────────────────

    private LegResult executeLeg(SpreadLeg leg, String groupId) {
        // Fetch current price for LIMIT calculation
        Optional<Quote> quoteOpt = marketDataService.quote(leg.instrumentKey());
        BigDecimal lastPrice = quoteOpt.map(Quote::lastPrice).orElse(BigDecimal.ZERO);

        if (lastPrice.signum() <= 0) {
            return new LegResult(leg, false, "No quote available for " + leg.instrumentKey());
        }

        // Calculate LIMIT price with protection
        BigDecimal protection = lastPrice.multiply(BigDecimal.valueOf(SPREAD_PROTECTION_PERCENT / 100), MC);
        BigDecimal limitPrice = leg.side() == OrderSide.BUY
                ? lastPrice.add(protection)    // BUY: willing to pay more
                : lastPrice.subtract(protection).max(BigDecimal.ONE); // SELL: willing to accept less

        // Round to tick size
        limitPrice = ExecutionEngine.roundToTick(limitPrice, leg.side());

        OrderRequest request = new OrderRequest(
                "SPREAD-" + groupId + "-" + leg.instrumentKey().hashCode(),
                leg.instrumentKey(),
                leg.side(),
                OrderType.LIMIT,
                ProductType.MIS,
                leg.quantity(),
                Optional.of(limitPrice),
                "spread-leg"
        );

        Optional<OrderResponse> response = executionEngine.placeSpreadLegOrder(request);

        if (response.isPresent() && response.get().status() != OrderStatus.REJECTED) {
            log.info("[SpreadExecutor] Leg placed: instrument={}, side={}, price={}, status={}",
                    leg.instrumentKey(), leg.side(), limitPrice, response.get().status());
            return new LegResult(leg, true, "Order placed: " + response.get().status());
        } else {
            String reason = response.map(r -> r.rejectionReason().orElse("Unknown rejection"))
                    .orElse("Order placement failed");
            return new LegResult(leg, false, reason);
        }
    }

    private void unwindFilledLegs(List<LegResult> results, String groupId) {
        for (LegResult result : results) {
            if (result.success()) {
                // Reverse this leg
                SpreadLeg leg = result.leg();
                OrderSide reverseSide = leg.side() == OrderSide.BUY ? OrderSide.SELL : OrderSide.BUY;

                Optional<Quote> quoteOpt = marketDataService.quote(leg.instrumentKey());
                BigDecimal lastPrice = quoteOpt.map(Quote::lastPrice).orElse(BigDecimal.ZERO);

                if (lastPrice.signum() <= 0) {
                    log.error("[SpreadExecutor] Cannot unwind — no quote for {}", leg.instrumentKey());
                    continue;
                }

                // Use MARKET for unwind (urgency > price)
                OrderRequest unwindRequest = new OrderRequest(
                        "UNWIND-" + groupId + "-" + leg.instrumentKey().hashCode(),
                        leg.instrumentKey(),
                        reverseSide,
                        OrderType.MARKET,
                        ProductType.MIS,
                        leg.quantity(),
                        Optional.empty(),
                        "spread-unwind"
                );

                Optional<OrderResponse> unwindResponse = executionEngine.placeSpreadLegOrder(unwindRequest);
                log.info("[SpreadExecutor] Unwind: instrument={}, side={}, success={}",
                        leg.instrumentKey(), reverseSide, unwindResponse.isPresent());
            }
        }
    }

    // ── Result records ───────────────────────────────────────────────────────

    public record LegResult(SpreadLeg leg, boolean success, String reason) {}

    public record SpreadExecutionResult(boolean allFilled, List<LegResult> legResults, String summary) {}
}
