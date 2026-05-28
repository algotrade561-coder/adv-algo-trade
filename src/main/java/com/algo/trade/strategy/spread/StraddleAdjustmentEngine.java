package com.algo.trade.strategy.spread;

import com.algo.trade.domain.IndexType;
import com.algo.trade.domain.Instrument;
import com.algo.trade.domain.OptionType;
import com.algo.trade.domain.OrderSide;
import com.algo.trade.domain.PositionGroup;
import com.algo.trade.domain.SpreadLeg;
import com.algo.trade.domain.UnderlyingSymbol;
import com.algo.trade.execution.SpreadLegPlacementService;
import com.algo.trade.marketdata.ExpiryCalendar;
import com.algo.trade.marketdata.InstrumentCache;
import com.algo.trade.marketdata.MarketDataService;
import com.algo.trade.strategy.StrategyType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Adjusts short straddle positions when the underlying moves significantly
 * away from the straddle strike. Uses {@link SpreadLegPlacementService} for
 * fill-confirmed leg orders and syncs {@link PositionGroup} legs to DB.
 */
@Component
public class StraddleAdjustmentEngine {

    private static final Logger log = LoggerFactory.getLogger(StraddleAdjustmentEngine.class);

    private final InstrumentCache instrumentCache;
    private final MarketDataService marketDataService;
    private final ExpiryCalendar expiryCalendar;
    private final SpreadLegPlacementService legPlacement;
    private final SpreadStrategyRegistry strategyRegistry;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.monitoring.ErrorEventService errorEventService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.execution.SpreadMarginPreflight marginPreflight;

    /** Tracks adjustment count per group to prevent whipsaw re-triggering. */
    private final java.util.concurrent.ConcurrentHashMap<String, Integer> adjustmentCounts =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Tracks last adjustment time per group for cooldown enforcement. */
    private final java.util.concurrent.ConcurrentHashMap<String, java.time.Instant> lastAdjustmentTime =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Maximum adjustments per group per session. */
    private static final int MAX_ADJUSTMENTS_PER_GROUP = 3;

    /** Minimum cooldown between adjustments (5 minutes). */
    private static final java.time.Duration ADJUSTMENT_COOLDOWN = java.time.Duration.ofMinutes(5);

    public StraddleAdjustmentEngine(InstrumentCache instrumentCache,
                                     MarketDataService marketDataService,
                                     ExpiryCalendar expiryCalendar,
                                     SpreadLegPlacementService legPlacement,
                                     SpreadStrategyRegistry strategyRegistry) {
        this.instrumentCache = instrumentCache;
        this.marketDataService = marketDataService;
        this.expiryCalendar = expiryCalendar;
        this.legPlacement = legPlacement;
        this.strategyRegistry = strategyRegistry;
    }

    public record AdjustmentResult(
            boolean adjusted,
            String adjustmentType,
            String newInstrumentKey,
            String replacedInstrumentKey
    ) {}

    public AdjustmentResult evaluateAdjustment(PositionGroup straddlePosition,
                                                BigDecimal currentSpot,
                                                int triggerPoints,
                                                IndexType indexType) {
        String groupId = straddlePosition.groupId();

        // Cooldown check: reject if adjusted too recently
        java.time.Instant lastAdj = lastAdjustmentTime.get(groupId);
        if (lastAdj != null && java.time.Instant.now().isBefore(lastAdj.plus(ADJUSTMENT_COOLDOWN))) {
            log.debug("Straddle adjustment cooldown active for group {} — skipping", groupId);
            return new AdjustmentResult(false, "COOLDOWN", null, null);
        }

        // Count check: reject if max adjustments exceeded
        int count = adjustmentCounts.getOrDefault(groupId, 0);
        if (count >= MAX_ADJUSTMENTS_PER_GROUP) {
            log.warn("Straddle adjustment count exceeded for group {} ({}/{}) — skipping",
                    groupId, count, MAX_ADJUSTMENTS_PER_GROUP);
            return new AdjustmentResult(false, "MAX_COUNT", null, null);
        }

        int straddleStrike = straddlePosition.legs().stream()
                .mapToInt(SpreadLeg::strike)
                .findFirst()
                .orElse(0);

        double spotDiff = currentSpot.doubleValue() - straddleStrike;
        double absDiff = Math.abs(spotDiff);

        if (absDiff <= triggerPoints) {
            return new AdjustmentResult(false, "NONE", null, null);
        }

        int currentATM = indexType.roundToATM(currentSpot.doubleValue());
        boolean spotMovedUp = spotDiff > 0;
        OptionType legToShift = spotMovedUp ? OptionType.CE : OptionType.PE;
        String adjustmentType = spotMovedUp ? "CE_SHIFT" : "PE_SHIFT";

        SpreadLeg testedLeg = straddlePosition.legs().stream()
                .filter(leg -> leg.optionType() == legToShift)
                .findFirst()
                .orElse(null);

        if (testedLeg == null || testedLeg.strike() == currentATM) {
            return new AdjustmentResult(false, "NONE", null, null);
        }

        UnderlyingSymbol underlying = straddlePosition.underlying();
        LocalDate expiry = testedLeg.expiry();
        Optional<Instrument> newInstrumentOpt = instrumentCache.findOption(
                underlying, expiry, BigDecimal.valueOf(currentATM), legToShift);

        if (newInstrumentOpt.isEmpty()) {
            return new AdjustmentResult(false, "NONE", null, null);
        }

        String newInstrumentKey = newInstrumentOpt.get().instrumentKey();
        String replacedInstrumentKey = testedLeg.instrumentKey();

        SpreadLeg closeLeg = new SpreadLeg(replacedInstrumentKey, testedLeg.strike(), testedLeg.optionType(),
                OrderSide.BUY, testedLeg.quantity(), testedLeg.expiry());
        SpreadLegPlacementService.PlacementResult closeResult = legPlacement.placeLeg(
                closeLeg, straddlePosition.groupId(), "straddle-adj-close", true);
        if (!closeResult.success()) {
            log.warn("Straddle adjustment close leg failed: {}", closeResult.reason());
            return new AdjustmentResult(false, "NONE", null, null);
        }

        SpreadLeg openLeg = new SpreadLeg(newInstrumentKey, currentATM, legToShift,
                OrderSide.SELL, testedLeg.quantity(), expiry);

        // Margin recheck before placing new short leg
        if (marginPreflight != null) {
            var marginCheck = marginPreflight.evaluate(List.of(openLeg), 1, testedLeg.quantity(), false);
            if (!marginCheck.allowed()) {
                log.warn("Straddle adjustment margin recheck failed for group {}: {}",
                        straddlePosition.groupId(), marginCheck.reason());
                // Re-open the closed leg since we can't place the new one
                SpreadLeg reopenLeg = new SpreadLeg(replacedInstrumentKey, testedLeg.strike(), testedLeg.optionType(),
                        OrderSide.SELL, testedLeg.quantity(), testedLeg.expiry());
                legPlacement.placeLeg(reopenLeg, straddlePosition.groupId(), "straddle-adj-reopen", true);
                return new AdjustmentResult(false, "MARGIN_REJECTED", null, null);
            }
        }

        SpreadLegPlacementService.PlacementResult openResult = legPlacement.placeLeg(
                openLeg, straddlePosition.groupId(), "straddle-adj-open", true);
        if (!openResult.success()) {
            log.error("Straddle adjustment open leg failed after close: {}", openResult.reason());
            if (errorEventService != null) {
                errorEventService.critical("StraddleAdjustment",
                        "Open leg failed after close for group " + straddlePosition.groupId());
            }
            // Re-open the closed leg to avoid naked position
            SpreadLeg reopenLeg = new SpreadLeg(replacedInstrumentKey, testedLeg.strike(), testedLeg.optionType(),
                    OrderSide.SELL, testedLeg.quantity(), testedLeg.expiry());
            legPlacement.placeLeg(reopenLeg, straddlePosition.groupId(), "straddle-adj-reopen", true);
            return new AdjustmentResult(false, "NONE", null, null);
        }

        List<SpreadLeg> updatedLegs = new ArrayList<>();
        Map<String, BigDecimal> priceUpdates = new HashMap<>(straddlePosition.entryPrices());
        for (SpreadLeg leg : straddlePosition.legs()) {
            if (leg.instrumentKey().equals(replacedInstrumentKey)) {
                updatedLegs.add(openLeg);
                openResult.fillPrice().ifPresent(p -> priceUpdates.put(newInstrumentKey, p));
            } else {
                updatedLegs.add(leg);
            }
        }
        closeResult.fillPrice().ifPresent(p -> priceUpdates.put(replacedInstrumentKey, p));

        syncToStrategy(straddlePosition.groupId(), updatedLegs, priceUpdates);

        // Track adjustment count and time
        adjustmentCounts.merge(groupId, 1, Integer::sum);
        lastAdjustmentTime.put(groupId, java.time.Instant.now());

        log.info("Straddle adjustment complete: {} shift {} → {} (count={})",
                legToShift, replacedInstrumentKey, newInstrumentKey, adjustmentCounts.get(groupId));
        return new AdjustmentResult(true, adjustmentType, newInstrumentKey, replacedInstrumentKey);
    }

    public AdjustmentResult addHedge(PositionGroup straddlePosition,
                                      BigDecimal currentSpot,
                                      String side,
                                      IndexType indexType) {
        int currentATM = indexType.roundToATM(currentSpot.doubleValue());
        int strikeInterval = indexType.strikeInterval();
        OptionType hedgeType = "CE".equalsIgnoreCase(side) ? OptionType.CE : OptionType.PE;
        int hedgeStrike = hedgeType == OptionType.CE
                ? currentATM + 3 * strikeInterval
                : currentATM - 3 * strikeInterval;

        UnderlyingSymbol underlying = straddlePosition.underlying();
        LocalDate expiry = straddlePosition.legs().stream()
                .map(SpreadLeg::expiry)
                .findFirst()
                .orElse(expiryCalendar.getCurrentExpiry(indexType));

        Optional<Instrument> hedgeInstrumentOpt = instrumentCache.findOption(
                underlying, expiry, BigDecimal.valueOf(hedgeStrike), hedgeType);
        if (hedgeInstrumentOpt.isEmpty()) {
            return new AdjustmentResult(false, "NONE", null, null);
        }

        String hedgeKey = hedgeInstrumentOpt.get().instrumentKey();
        int qty = straddlePosition.legs().stream().mapToInt(SpreadLeg::quantity).max().orElse(indexType.lotSize());

        SpreadLeg hedgeLeg = new SpreadLeg(hedgeKey, hedgeStrike, hedgeType, OrderSide.BUY, qty, expiry);
        SpreadLegPlacementService.PlacementResult hedgeResult = legPlacement.placeLeg(
                hedgeLeg, straddlePosition.groupId(), "straddle-hedge", true);
        if (!hedgeResult.success()) {
            return new AdjustmentResult(false, "NONE", null, null);
        }

        List<SpreadLeg> updatedLegs = new ArrayList<>(straddlePosition.legs());
        updatedLegs.add(hedgeLeg);
        Map<String, BigDecimal> priceUpdates = new HashMap<>(straddlePosition.entryPrices());
        hedgeResult.fillPrice().ifPresent(p -> priceUpdates.put(hedgeKey, p));
        syncToStrategy(straddlePosition.groupId(), updatedLegs, priceUpdates);

        return new AdjustmentResult(true, "HEDGE_ADDED", hedgeKey, null);
    }

    private void syncToStrategy(String groupId, List<SpreadLeg> legs, Map<String, BigDecimal> priceUpdates) {
        strategyRegistry.get(StrategyType.SHORT_STRADDLE).ifPresent(strategy ->
                strategy.syncGroupLegs(groupId, legs, priceUpdates));
        strategyRegistry.get(StrategyType.SHORT_STRANGLE).ifPresent(strategy ->
                strategy.syncGroupLegs(groupId, legs, priceUpdates));
    }
}
