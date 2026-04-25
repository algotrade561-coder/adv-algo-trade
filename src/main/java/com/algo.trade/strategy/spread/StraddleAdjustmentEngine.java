package com.algo.trade.strategy.spread;

import com.algo.trade.domain.IndexType;
import com.algo.trade.domain.Instrument;
import com.algo.trade.domain.OptionType;
import com.algo.trade.domain.OrderRequest;
import com.algo.trade.domain.OrderSide;
import com.algo.trade.domain.OrderType;
import com.algo.trade.domain.PositionGroup;
import com.algo.trade.domain.ProductType;
import com.algo.trade.domain.Quote;
import com.algo.trade.domain.SpreadLeg;
import com.algo.trade.domain.UnderlyingSymbol;
import com.algo.trade.execution.ExecutionEngine;
import com.algo.trade.marketdata.ExpiryCalendar;
import com.algo.trade.marketdata.InstrumentCache;
import com.algo.trade.marketdata.MarketDataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * Adjusts short straddle positions when the underlying moves significantly
 * away from the straddle strike. Shifts the tested leg to the current ATM
 * and can add far-OTM hedges.
 */
@Component
public class StraddleAdjustmentEngine {

    private static final Logger log = LoggerFactory.getLogger(StraddleAdjustmentEngine.class);
    private static final MathContext MC = MathContext.DECIMAL64;
    private static final BigDecimal LIMIT_BUFFER = new BigDecimal("1.005");

    private final InstrumentCache instrumentCache;
    private final MarketDataService marketDataService;
    private final ExpiryCalendar expiryCalendar;
    private final ExecutionEngine executionEngine;

    public StraddleAdjustmentEngine(InstrumentCache instrumentCache,
                                     MarketDataService marketDataService,
                                     ExpiryCalendar expiryCalendar,
                                     ExecutionEngine executionEngine) {
        this.instrumentCache = instrumentCache;
        this.marketDataService = marketDataService;
        this.expiryCalendar = expiryCalendar;
        this.executionEngine = executionEngine;
    }

    public record AdjustmentResult(
            boolean adjusted,
            String adjustmentType,
            String newInstrumentKey,
            String replacedInstrumentKey
    ) {}

    /**
     * Evaluate whether a straddle position needs adjustment based on spot movement.
     * Triggers if |currentSpot − straddleStrike| > triggerPoints.
     * Shifts CE leg if spot moved up, PE leg if spot moved down.
     * Skips if the tested leg is already at the current ATM strike.
     */
    public AdjustmentResult evaluateAdjustment(PositionGroup straddlePosition,
                                                BigDecimal currentSpot,
                                                int triggerPoints,
                                                IndexType indexType) {
        // Determine the straddle strike from the position legs (both legs share the same strike)
        int straddleStrike = straddlePosition.legs().stream()
                .mapToInt(SpreadLeg::strike)
                .findFirst()
                .orElse(0);

        double spotDiff = currentSpot.doubleValue() - straddleStrike;
        double absDiff = Math.abs(spotDiff);

        // No adjustment needed if within trigger range
        if (absDiff <= triggerPoints) {
            log.debug("Straddle adjustment not triggered: |spot {} - strike {}| = {} <= trigger {}",
                    currentSpot, straddleStrike, absDiff, triggerPoints);
            return new AdjustmentResult(false, "NONE", null, null);
        }

        int currentATM = indexType.roundToATM(currentSpot.doubleValue());
        boolean spotMovedUp = spotDiff > 0;
        OptionType legToShift = spotMovedUp ? OptionType.CE : OptionType.PE;
        String adjustmentType = spotMovedUp ? "CE_SHIFT" : "PE_SHIFT";

        // Find the leg to be replaced
        SpreadLeg testedLeg = straddlePosition.legs().stream()
                .filter(leg -> leg.optionType() == legToShift)
                .findFirst()
                .orElse(null);

        if (testedLeg == null) {
            log.warn("No {} leg found in straddle position {}", legToShift, straddlePosition.groupId());
            return new AdjustmentResult(false, "NONE", null, null);
        }

        // Skip if tested leg is already at current ATM
        if (testedLeg.strike() == currentATM) {
            log.debug("Tested {} leg already at current ATM {} — skipping adjustment", legToShift, currentATM);
            return new AdjustmentResult(false, "NONE", null, null);
        }

        // Look up the new instrument at current ATM
        UnderlyingSymbol underlying = straddlePosition.underlying();
        LocalDate expiry = testedLeg.expiry();
        Optional<Instrument> newInstrumentOpt = instrumentCache.findOption(
                underlying, expiry, BigDecimal.valueOf(currentATM), legToShift);

        if (newInstrumentOpt.isEmpty()) {
            log.warn("Could not find {} option at strike {} for adjustment", legToShift, currentATM);
            return new AdjustmentResult(false, "NONE", null, null);
        }

        Instrument newInstrument = newInstrumentOpt.get();
        String newInstrumentKey = newInstrument.instrumentKey();
        String replacedInstrumentKey = testedLeg.instrumentKey();

        // Get LTP for the new instrument to place LIMIT order with buffer
        Optional<Quote> quoteOpt = marketDataService.quote(newInstrumentKey);
        if (quoteOpt.isEmpty()) {
            log.warn("Could not fetch quote for new instrument {}", newInstrumentKey);
            return new AdjustmentResult(false, "NONE", null, null);
        }

        BigDecimal ltp = quoteOpt.get().lastPrice();
        BigDecimal limitPrice = ltp.multiply(LIMIT_BUFFER, MC).setScale(2, RoundingMode.HALF_UP);

        log.info("Straddle adjustment: {} leg shift from strike {} to ATM {} | old={}, new={}, limitPrice={}",
                legToShift, testedLeg.strike(), currentATM, replacedInstrumentKey, newInstrumentKey, limitPrice);

        // Buy back old leg (BUY to close the short)
        placeOrder(replacedInstrumentKey, OrderSide.BUY, testedLeg.quantity(), limitPrice, "straddle-adj-close");

        // Sell new leg at current ATM
        placeOrder(newInstrumentKey, OrderSide.SELL, testedLeg.quantity(), limitPrice, "straddle-adj-open");

        return new AdjustmentResult(true, adjustmentType, newInstrumentKey, replacedInstrumentKey);
    }

    /**
     * Add a far-OTM hedge by buying 1 lot 3 strikes OTM from current ATM on the specified side.
     */
    public AdjustmentResult addHedge(PositionGroup straddlePosition,
                                      BigDecimal currentSpot,
                                      String side,
                                      IndexType indexType) {
        int currentATM = indexType.roundToATM(currentSpot.doubleValue());
        int strikeInterval = indexType.strikeInterval();
        OptionType hedgeType = "CE".equalsIgnoreCase(side) ? OptionType.CE : OptionType.PE;

        // 3 strikes OTM from current ATM
        int hedgeStrike = hedgeType == OptionType.CE
                ? currentATM + 3 * strikeInterval
                : currentATM - 3 * strikeInterval;

        UnderlyingSymbol underlying = straddlePosition.underlying();
        LocalDate expiry = straddlePosition.legs().stream()
                .map(SpreadLeg::expiry)
                .findFirst()
                .orElse(expiryCalendar.getCurrentWeeklyExpiry(indexType));

        Optional<Instrument> hedgeInstrumentOpt = instrumentCache.findOption(
                underlying, expiry, BigDecimal.valueOf(hedgeStrike), hedgeType);

        if (hedgeInstrumentOpt.isEmpty()) {
            log.warn("Could not find hedge {} option at strike {}", hedgeType, hedgeStrike);
            return new AdjustmentResult(false, "NONE", null, null);
        }

        Instrument hedgeInstrument = hedgeInstrumentOpt.get();
        String hedgeKey = hedgeInstrument.instrumentKey();

        Optional<Quote> quoteOpt = marketDataService.quote(hedgeKey);
        if (quoteOpt.isEmpty()) {
            log.warn("Could not fetch quote for hedge instrument {}", hedgeKey);
            return new AdjustmentResult(false, "NONE", null, null);
        }

        BigDecimal ltp = quoteOpt.get().lastPrice();
        BigDecimal limitPrice = ltp.multiply(LIMIT_BUFFER, MC).setScale(2, RoundingMode.HALF_UP);
        int lotSize = indexType.lotSize();

        log.info("Adding hedge: {} at strike {} | instrument={}, limitPrice={}, qty={}",
                hedgeType, hedgeStrike, hedgeKey, limitPrice, lotSize);

        placeOrder(hedgeKey, OrderSide.BUY, lotSize, limitPrice, "straddle-hedge");

        return new AdjustmentResult(true, "HEDGE_ADDED", hedgeKey, null);
    }

    private void placeOrder(String instrumentKey, OrderSide side, int quantity,
                            BigDecimal limitPrice, String tag) {
        try {
            String clientOrderId = tag.toUpperCase() + "-" + UUID.randomUUID().toString().substring(0, 8);
            OrderRequest request = new OrderRequest(
                    clientOrderId, instrumentKey, side, OrderType.LIMIT,
                    ProductType.MIS, quantity, Optional.of(limitPrice), tag);
            executionEngine.executeEntry(null, limitPrice, quantity);
            log.info("Adjustment order placed: clientOrderId={}, instrument={}, side={}, qty={}, price={}",
                    clientOrderId, instrumentKey, side, quantity, limitPrice);
        } catch (Exception ex) {
            log.error("Failed to place adjustment order for {}: {}", instrumentKey, ex.getMessage(), ex);
        }
    }
}
