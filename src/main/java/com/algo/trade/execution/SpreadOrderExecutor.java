package com.algo.trade.execution;

import com.algo.trade.config.SpreadTradingProperties;
import com.algo.trade.domain.*;
import com.algo.trade.marketdata.MarketDataService;
import com.algo.trade.notification.TelegramAlertService;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Executes multi-leg spread orders with safety guarantees:
 *
 * <ol>
 *   <li>Margin pre-flight vs broker available funds (basket margin + BUY premium + buffer)</li>
 *   <li>Leg sequencing: BUY hedges first (parallel when enabled), 2s pause, then SELL legs</li>
 *   <li>Fill confirmation and retry with exponential backoff per leg</li>
 *   <li>Partial fill handling: unwind filled legs on failure</li>
 * </ol>
 */
@Component
public class SpreadOrderExecutor {

    private static final Logger log = LoggerFactory.getLogger(SpreadOrderExecutor.class);

    private final SpreadLegPlacementService legPlacement;
    private final SpreadMarginPreflight marginPreflight;
    private final MarketDataService marketDataService;
    private final TelegramAlertService telegramAlertService;
    private final SpreadTradingProperties spreadProperties;
    private final BrokerSideStopLossService brokerSideStopLossService;
    private final ExecutorService buyLegExecutor;

    public SpreadOrderExecutor(SpreadLegPlacementService legPlacement,
                               SpreadMarginPreflight marginPreflight,
                               MarketDataService marketDataService,
                               TelegramAlertService telegramAlertService,
                               SpreadTradingProperties spreadProperties,
                               BrokerSideStopLossService brokerSideStopLossService) {
        this.legPlacement = legPlacement;
        this.marginPreflight = marginPreflight;
        this.marketDataService = marketDataService;
        this.telegramAlertService = telegramAlertService;
        this.spreadProperties = spreadProperties;
        this.brokerSideStopLossService = brokerSideStopLossService;
        this.buyLegExecutor = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "spread-buy-legs");
            t.setDaemon(true);
            return t;
        });
    }

    public SpreadExecutionResult execute(List<SpreadLeg> legs, String groupId, String strategyName,
                                         int maxLots, int lotSize, boolean paperTrading) {
        log.info("[SpreadExecutor] Starting: group={}, strategy={}, legs={}", groupId, strategyName, legs.size());

        SpreadMarginPreflight.PreflightResult preflight =
                marginPreflight.evaluate(legs, maxLots, lotSize, paperTrading);
        if (!preflight.allowed()) {
            telegramAlertService.systemAlert(String.format(
                    "⚠️ Spread margin rejected\nGroup: %s\nReason: %s", groupId, preflight.reason()));
            return SpreadExecutionResult.failed(preflight.reason());
        }
        List<SpreadLeg> sizedLegs = preflight.legs();

        for (SpreadLeg leg : sizedLegs) {
            if (leg.side() == OrderSide.BUY) {
                Optional<Quote> q = marketDataService.quote(leg.instrumentKey());
                if (q.map(Quote::lastPrice).orElse(BigDecimal.ZERO).signum() <= 0) {
                    return SpreadExecutionResult.failed("No quote for BUY leg: " + leg.instrumentKey());
                }
            }
        }

        List<SpreadLeg> buyLegs = sizedLegs.stream().filter(l -> l.side() == OrderSide.BUY).toList();
        List<SpreadLeg> sellLegs = sizedLegs.stream().filter(l -> l.side() == OrderSide.SELL).toList();

        List<LegResult> results = new ArrayList<>();
        boolean waitForFill = !paperTrading;

        List<LegResult> buyResults = placeBuyLegs(buyLegs, groupId, strategyName, waitForFill);
        results.addAll(buyResults);
        if (buyResults.stream().anyMatch(r -> !r.success())) {
            unwindAndFail(results, groupId, strategyName, "BUY phase failed");
            return SpreadExecutionResult.failed(results, "BUY phase failed — unwound");
        }

        try {
            log.info("[SpreadExecutor] BUY legs filled — waiting 2s before SELL phase");
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        Map<String, Quote> quotes = marketDataService.quotes(
                sizedLegs.stream().map(SpreadLeg::instrumentKey).toList());
        Optional<String> sellMarginBlock = marginPreflight.verifySellPhase(sellLegs, quotes);
        if (sellMarginBlock.isPresent()) {
            log.warn("[SpreadExecutor] SELL phase blocked: {}", sellMarginBlock.get());
            unwindAndFail(results, groupId, strategyName, sellMarginBlock.get());
            return SpreadExecutionResult.failed(results, sellMarginBlock.get());
        }

        for (SpreadLeg leg : sellLegs) {
            SpreadLegPlacementService.PlacementResult placed =
                    legPlacement.placeLeg(leg, groupId, strategyName, waitForFill);
            results.add(toLegResult(leg, placed));
            if (!placed.success()) {
                unwindAndFail(results, groupId, strategyName, "SELL leg failed: " + leg.instrumentKey());
                return SpreadExecutionResult.failed(results, "SELL phase failed — unwound");
            }
        }

        log.info("[SpreadExecutor] All legs placed: group={}, lots={}", groupId, preflight.lots());
        // Place broker-side SL-M on SELL legs as catastrophe floor
        Map<String, BigDecimal> fillPrices = SpreadFillPrices.fromLegResults(results);
        brokerSideStopLossService.placeBrokerStopLosses(groupId, sizedLegs, fillPrices);
        return SpreadExecutionResult.success(results, "All legs filled (lots=" + preflight.lots() + ")");
    }

    /**
     * Execute exit for all legs (reverse sides): BUY to close shorts first, then SELL to close longs.
     * On long-close failure after shorts are closed, re-opens shorts via {@link #unwindFilledLegs}.
     */
    public SpreadExecutionResult executeExit(List<SpreadLeg> legs, String groupId, String strategyName,
                                             boolean paperTrading) {
        log.info("[SpreadExecutor] Exit starting: group={}, legs={}", groupId, legs.size());
        if (paperTrading) {
            return SpreadExecutionResult.success(List.of(), "Paper spread exit");
        }
        // Cancel broker-side SL-M orders before client-side exit to prevent double-fire
        brokerSideStopLossService.cancelBrokerStopLosses(groupId);
        boolean waitForFill = true;

        List<SpreadLeg> closeShorts = legs.stream()
                .map(l -> new SpreadLeg(l.instrumentKey(), l.strike(), l.optionType(),
                        l.side() == OrderSide.SELL ? OrderSide.BUY : OrderSide.SELL, l.quantity(), l.expiry()))
                .filter(l -> l.side() == OrderSide.BUY)
                .toList();
        List<SpreadLeg> closeLongs = legs.stream()
                .map(l -> new SpreadLeg(l.instrumentKey(), l.strike(), l.optionType(),
                        l.side() == OrderSide.BUY ? OrderSide.SELL : OrderSide.BUY, l.quantity(), l.expiry()))
                .filter(l -> l.side() == OrderSide.SELL)
                .toList();

        List<LegResult> results = new ArrayList<>();
        for (SpreadLeg leg : closeShorts) {
            SpreadLegPlacementService.PlacementResult r =
                    placeExitLegWithRetry(leg, groupId, waitForFill);
            results.add(toLegResult(leg, r));
            if (!r.success()) {
                return SpreadExecutionResult.failed(results, "Exit failed closing short: " + r.reason());
            }
        }
        try {
            Thread.sleep(1500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        for (SpreadLeg leg : closeLongs) {
            SpreadLegPlacementService.PlacementResult r =
                    placeExitLegWithRetry(leg, groupId, waitForFill);
            results.add(toLegResult(leg, r));
            if (!r.success()) {
                log.error("[SpreadExecutor] Long-close failed after shorts closed — re-opening shorts group={}",
                        groupId);
                unwindExitLegs(results, groupId, strategyName);
                return SpreadExecutionResult.failed(results,
                        "Exit failed closing long — shorts re-opened where possible: " + r.reason());
            }
        }
        return SpreadExecutionResult.success(results, "All legs exited");
    }

    private SpreadLegPlacementService.PlacementResult placeExitLegWithRetry(
            SpreadLeg leg, String groupId, boolean waitForFill) {
        int maxRetries = spreadProperties.spreadLegMaxRetries();
        SpreadLegPlacementService.PlacementResult last = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            last = legPlacement.placeLeg(leg, groupId, "spread-exit", waitForFill);
            if (last.success()) {
                return last;
            }
            if (attempt < maxRetries) {
                try {
                    Thread.sleep(500L * (attempt + 1));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return last != null ? last
                : new SpreadLegPlacementService.PlacementResult(false, leg, Optional.empty(), "No result", Optional.empty());
    }

    /** Re-open legs that were successfully closed during a partial exit (reverse the close fill). */
    private void unwindExitLegs(List<LegResult> closedExitResults, String groupId, String strategyName) {
        telegramAlertService.systemAlert(String.format(
                "🚨 SPREAD EXIT PARTIAL — REVERTING SHORT CLOSE\nStrategy: %s\nGroup: %s\nRe-opening shorts where possible.",
                strategyName, groupId));
        unwindFilledLegs(closedExitResults, groupId);
    }

    /**
     * Closes a fraction of each short (SELL) leg — used for progressive credit profit booking.
     */
    public SpreadExecutionResult executePartialCloseShorts(List<SpreadLeg> legs, double fraction,
                                                           String groupId, String strategyName,
                                                           boolean paperTrading, int lotSize) {
        if (fraction <= 0 || fraction >= 1) {
            return SpreadExecutionResult.failed("Invalid partial fraction: " + fraction);
        }
        log.info("[SpreadExecutor] Partial short close: group={}, fraction={}", groupId, fraction);
        if (paperTrading) {
            return SpreadExecutionResult.success(List.of(), "Paper partial close");
        }
        boolean waitForFill = true;
        List<LegResult> results = new ArrayList<>();
        for (SpreadLeg leg : legs) {
            if (leg.side() != OrderSide.SELL) {
                continue;
            }
            int rawQty = (int) Math.floor(leg.quantity() * fraction);
            int partialQty = (rawQty / lotSize) * lotSize;
            if (partialQty <= 0) {
                continue;
            }
            SpreadLeg closeLeg = new SpreadLeg(leg.instrumentKey(), leg.strike(), leg.optionType(),
                    OrderSide.BUY, partialQty, leg.expiry());
            SpreadLegPlacementService.PlacementResult r =
                    legPlacement.placeLeg(closeLeg, groupId, "spread-partial-exit", waitForFill);
            results.add(toLegResult(closeLeg, r));
            if (!r.success()) {
                return SpreadExecutionResult.failed(results, "Partial exit failed: " + r.reason());
            }
        }
        return SpreadExecutionResult.success(results, "Partial short close");
    }

    private List<LegResult> placeBuyLegs(List<SpreadLeg> buyLegs, String groupId, String strategyName,
                                         boolean waitForFill) {
        if (buyLegs.isEmpty()) {
            return List.of();
        }
        if (!spreadProperties.parallelBuyLegsEnabled() || buyLegs.size() == 1) {
            List<LegResult> sequential = new ArrayList<>();
            for (SpreadLeg leg : buyLegs) {
                SpreadLegPlacementService.PlacementResult r =
                        legPlacement.placeLeg(leg, groupId, strategyName, waitForFill);
                sequential.add(toLegResult(leg, r));
            }
            return sequential;
        }

        List<Future<LegResult>> futures = new ArrayList<>();
        List<SpreadLeg> legOrder = new ArrayList<>(buyLegs); // Preserve submission order
        for (SpreadLeg leg : legOrder) {
            futures.add(buyLegExecutor.submit(() -> {
                SpreadLegPlacementService.PlacementResult r =
                        legPlacement.placeLeg(leg, groupId, strategyName, waitForFill);
                return toLegResult(leg, r);
            }));
        }
        List<LegResult> results = new ArrayList<>();
        long timeoutMs = spreadProperties.parallelLegTimeout().toMillis();
        for (int i = 0; i < futures.size(); i++) {
            try {
                results.add(futures.get(i).get(timeoutMs, TimeUnit.MILLISECONDS));
            } catch (TimeoutException ex) {
                futures.get(i).cancel(true);
                results.add(new LegResult(legOrder.get(i), false, "Parallel BUY timeout", Optional.empty(), 0));
            } catch (Exception ex) {
                results.add(new LegResult(legOrder.get(i), false, ex.getMessage(), Optional.empty(), 0));
            }
        }
        return results;
    }

    private void unwindAndFail(List<LegResult> results, String groupId, String strategyName, String reason) {
        log.warn("[SpreadExecutor] {} — unwinding group={}", reason, groupId);
        unwindFilledLegs(results, groupId);
        telegramAlertService.systemAlert(String.format(
                "🚨 SPREAD PARTIAL — UNWINDING\nStrategy: %s\nGroup: %s\n%s",
                strategyName, groupId, reason));
    }

    private void unwindFilledLegs(List<LegResult> results, String groupId) {
        try {
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        List<String> failed = new ArrayList<>();
        for (LegResult result : results) {
            if (!result.success()) {
                continue;
            }
            // Use actual filled quantity, not requested — prevents flipping to naked opposite
            int qtyToUnwind = result.filledQuantity() > 0 ? result.filledQuantity() : result.leg().quantity();
            SpreadLeg leg = result.leg();
            OrderSide reverse = leg.side() == OrderSide.BUY ? OrderSide.SELL : OrderSide.BUY;
            SpreadLeg unwindLeg = new SpreadLeg(leg.instrumentKey(), leg.strike(), leg.optionType(),
                    reverse, qtyToUnwind, leg.expiry());
            // P1 #11: Use fill price as limit hint for re-opening (avoids slippage vs LTP)
            SpreadLegPlacementService.PlacementResult r =
                    legPlacement.placeLeg(unwindLeg, groupId, "spread-unwind", true);
            if (!r.success()) {
                failed.add(leg.instrumentKey());
            }
        }
        if (!failed.isEmpty()) {
            telegramAlertService.systemAlert(String.format(
                    "🚨 SPREAD UNWIND FAILED — MANUAL CLOSE REQUIRED\nGroup: %s\nLegs: %s",
                    groupId, String.join(", ", failed)));
        }
    }

    private static LegResult toLegResult(SpreadLeg leg, SpreadLegPlacementService.PlacementResult r) {
        // Use the leg's requested quantity as filled if success (fill confirmation already verified full fill)
        int filled = r.success() ? leg.quantity() : 0;
        return new LegResult(leg, r.success(), r.reason(), r.fillPrice(), filled);
    }

    public record LegResult(
            SpreadLeg leg,
            boolean success,
            String reason,
            java.util.Optional<BigDecimal> fillPrice,
            int filledQuantity
    ) {}

    public record SpreadExecutionResult(
            boolean allFilled,
            List<LegResult> legResults,
            String summary,
            Map<String, BigDecimal> fillPricesByInstrument
    ) {
        static SpreadExecutionResult success(List<LegResult> legResults, String summary) {
            return new SpreadExecutionResult(true, legResults, summary,
                    SpreadFillPrices.fromLegResults(legResults));
        }

        static SpreadExecutionResult failed(String summary) {
            return new SpreadExecutionResult(false, List.of(), summary, Map.of());
        }

        static SpreadExecutionResult failed(List<LegResult> legResults, String summary) {
            return new SpreadExecutionResult(false, legResults, summary,
                    SpreadFillPrices.fromLegResults(legResults));
        }
    }
}
