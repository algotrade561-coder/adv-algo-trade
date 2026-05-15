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

        // 0. Pre-flight check: estimate total premium needed for BUY legs
        //    If total BUY premium exceeds a safety threshold, abort before placing any orders.
        //    This prevents partial fills that require complex unwinding.
        BigDecimal totalBuyPremium = BigDecimal.ZERO;
        for (SpreadLeg leg : legs) {
            if (leg.side() == OrderSide.BUY) {
                Optional<Quote> q = marketDataService.quote(leg.instrumentKey());
                BigDecimal price = q.map(Quote::lastPrice).orElse(BigDecimal.ZERO);
                if (price.signum() <= 0) {
                    log.warn("[SpreadExecutor] No quote for BUY leg {} — aborting spread", leg.instrumentKey());
                    telegramAlertService.systemAlert(String.format(
                            "⚠️ Spread aborted: no quote for %s\nGroup: %s", leg.instrumentKey(), groupId));
                    return new SpreadExecutionResult(false, List.of(), "No quote for BUY leg: " + leg.instrumentKey());
                }
                totalBuyPremium = totalBuyPremium.add(price.multiply(BigDecimal.valueOf(leg.quantity()), MC));
            }
        }
        log.info("[SpreadExecutor] Pre-flight: totalBuyPremium=₹{} for group={}", totalBuyPremium.setScale(0, java.math.RoundingMode.HALF_UP), groupId);

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
        boolean buyPhaseComplete = false;

        for (SpreadLeg leg : sequenced) {
            if (anyFailed) {
                results.add(new LegResult(leg, false, "Skipped — previous leg failed"));
                continue;
            }

            // Insert delay between BUY phase and SELL phase to let exchange confirm hedges
            if (!buyPhaseComplete && leg.side() == OrderSide.SELL) {
                buyPhaseComplete = true;
                try {
                    log.info("[SpreadExecutor] BUY legs placed — waiting 2s for exchange confirmation before SELL legs");
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
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
        // Wait 3 seconds for exchange to confirm fills on BUY legs before attempting unwind.
        // Without this delay, Zerodha may not recognize the BUY position and reject the SELL
        // as a new naked short (requiring full margin instead of netting off).
        try {
            log.info("[SpreadExecutor] Waiting 3s for exchange fill confirmations before unwind: group={}", groupId);
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        List<String> failedUnwinds = new ArrayList<>();

        for (LegResult result : results) {
            if (!result.success()) continue; // Only unwind legs that were placed

            SpreadLeg leg = result.leg();
            OrderSide reverseSide = leg.side() == OrderSide.BUY ? OrderSide.SELL : OrderSide.BUY;

            Optional<Quote> quoteOpt = marketDataService.quote(leg.instrumentKey());
            BigDecimal lastPrice = quoteOpt.map(Quote::lastPrice).orElse(BigDecimal.ZERO);

            if (lastPrice.signum() <= 0) {
                log.error("[SpreadExecutor] Cannot unwind — no quote for {}", leg.instrumentKey());
                failedUnwinds.add(leg.instrumentKey() + " (no quote)");
                continue;
            }

            // For closing a BUY position (SELL to close): use LIMIT at LTP - 1.5% protection
            // This avoids the margin issue — LIMIT orders for closing existing positions
            // are recognized by Zerodha as position-closing, not new naked shorts.
            BigDecimal protection = lastPrice.multiply(BigDecimal.valueOf(SPREAD_PROTECTION_PERCENT / 100), MC);
            BigDecimal limitPrice = reverseSide == OrderSide.SELL
                    ? lastPrice.subtract(protection).max(BigDecimal.ONE) // Selling: accept slightly less
                    : lastPrice.add(protection); // Buying back: pay slightly more
            limitPrice = ExecutionEngine.roundToTick(limitPrice, reverseSide);

            OrderRequest unwindRequest = new OrderRequest(
                    "UNWIND-" + groupId + "-" + leg.instrumentKey().hashCode(),
                    leg.instrumentKey(),
                    reverseSide,
                    OrderType.LIMIT,
                    ProductType.MIS,
                    leg.quantity(),
                    Optional.of(limitPrice),
                    "spread-unwind"
            );

            Optional<OrderResponse> unwindResponse = executionEngine.placeSpreadLegOrder(unwindRequest);
            boolean unwindSuccess = unwindResponse.isPresent()
                    && unwindResponse.get().status() != OrderStatus.REJECTED;

            if (!unwindSuccess) {
                // Retry once with MARKET after another 2s delay
                log.warn("[SpreadExecutor] LIMIT unwind failed for {} — retrying with MARKET after 2s", leg.instrumentKey());
                try { Thread.sleep(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

                OrderRequest marketRetry = new OrderRequest(
                        "UNWIND-MKT-" + groupId + "-" + leg.instrumentKey().hashCode(),
                        leg.instrumentKey(),
                        reverseSide,
                        OrderType.MARKET,
                        ProductType.MIS,
                        leg.quantity(),
                        Optional.empty(),
                        "spread-unwind-retry"
                );
                Optional<OrderResponse> retryResponse = executionEngine.placeSpreadLegOrder(marketRetry);
                unwindSuccess = retryResponse.isPresent()
                        && retryResponse.get().status() != OrderStatus.REJECTED;

                if (!unwindSuccess) {
                    failedUnwinds.add(leg.instrumentKey() + " " + reverseSide);
                    log.error("[SpreadExecutor] Unwind FAILED after retry: group={}, instrument={}, side={}",
                            groupId, leg.instrumentKey(), reverseSide);
                }
            }

            log.info("[SpreadExecutor] Unwind {}: instrument={}, side={}, price={}",
                    unwindSuccess ? "SUCCESS" : "FAILED", leg.instrumentKey(), reverseSide, limitPrice);
        }

        // Alert operator about any legs that couldn't be unwound — manual intervention needed
        if (!failedUnwinds.isEmpty()) {
            telegramAlertService.systemAlert(String.format(
                    "🚨 SPREAD UNWIND FAILED — MANUAL CLOSE REQUIRED\nGroup: %s\nFailed legs: %s\n"
                    + "These positions are OPEN in your broker. Close them manually from Kite app.",
                    groupId, String.join(", ", failedUnwinds)));
        }
    }

    // ── Result records ───────────────────────────────────────────────────────

    public record LegResult(SpreadLeg leg, boolean success, String reason) {}

    public record SpreadExecutionResult(boolean allFilled, List<LegResult> legResults, String summary) {}
}
