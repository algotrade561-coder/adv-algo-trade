package com.kiteapioptions.risk;

import com.kiteapioptions.config.TradingProperties;
import com.kiteapioptions.domain.SignalType;
import com.kiteapioptions.domain.StrategyDecision;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Central risk gate for entries and position sizing.
 */
@Service
public class RiskEngine {

    private static final MathContext MATH_CONTEXT = MathContext.DECIMAL64;

    private final TradingProperties properties;

    public RiskEngine(TradingProperties properties) {
        this.properties = properties;
    }

    public RiskCheckResult evaluateEntry(
            StrategyDecision decision,
            int openTradeCount,
            int tradesToday,
            BigDecimal dailyPnl,
            int consecutiveLosses,
            boolean killSwitchEnabled
    ) {
        List<String> rejections = new ArrayList<>();
        if (killSwitchEnabled || properties.safety().killSwitchEnabled()) {
            rejections.add("Kill switch is enabled");
        }
        if (decision.signalType() != SignalType.BUY_CE && decision.signalType() != SignalType.BUY_PE) {
            rejections.add("Decision is not an entry signal");
        }
        if (properties.risk().oneOpenTradeAtATime() && openTradeCount > 0) {
            rejections.add("One-open-trade-at-a-time limit reached");
        }
        if (tradesToday >= properties.risk().maxTradesPerDay()) {
            rejections.add("Max trades per day reached");
        }
        if (dailyPnl.compareTo(maxDailyLossAmount().negate()) <= 0) {
            rejections.add("Max daily loss reached");
        }
        if (consecutiveLosses >= properties.risk().maxConsecutiveLosses()) {
            rejections.add("Max consecutive losses reached");
        }
        if (!decision.selectedInstrumentKey().isPresent() || !decision.optionType().isPresent()) {
            rejections.add("Decision does not contain selected option instrument details");
        }
        return rejections.isEmpty()
                ? RiskCheckResult.allowed("Entry risk checks passed")
                : RiskCheckResult.rejected(rejections);
    }

    public PositionSizingResult calculateQuantity(BigDecimal optionPremium, int lotSize) {
        if (optionPremium == null || optionPremium.signum() <= 0) {
            return new PositionSizingResult(false, 0, BigDecimal.ZERO, BigDecimal.ZERO,
                    "Option premium must be positive");
        }
        if (lotSize <= 0) {
            return new PositionSizingResult(false, 0, BigDecimal.ZERO, BigDecimal.ZERO,
                    "Lot size must be positive");
        }

        BigDecimal riskAmount = properties.risk().totalCapital()
                .multiply(properties.risk().maxRiskPerTradePercent(), MATH_CONTEXT)
                .divide(BigDecimal.valueOf(100), MATH_CONTEXT);
        BigDecimal lossPerUnit = optionPremium
                .multiply(properties.exit().stopLossPercent(), MATH_CONTEXT)
                .divide(BigDecimal.valueOf(100), MATH_CONTEXT);
        if (lossPerUnit.signum() <= 0) {
            return new PositionSizingResult(false, 0, riskAmount, BigDecimal.ZERO,
                    "Configured stop loss produces zero risk per unit");
        }

        int rawQuantity = riskAmount.divide(lossPerUnit, MATH_CONTEXT).intValue();
        int lots = rawQuantity / lotSize;
        int quantity = lots * lotSize;
        BigDecimal estimatedCost = optionPremium.multiply(BigDecimal.valueOf(quantity), MATH_CONTEXT);

        if (quantity <= 0) {
            return new PositionSizingResult(false, 0, riskAmount, estimatedCost,
                    "Premium is too high for the risk budget");
        }
        if (estimatedCost.compareTo(properties.risk().totalCapital()) > 0) {
            int affordableLots = properties.risk().totalCapital()
                    .divide(optionPremium.multiply(BigDecimal.valueOf(lotSize), MATH_CONTEXT), MATH_CONTEXT)
                    .intValue();
            quantity = affordableLots * lotSize;
            estimatedCost = optionPremium.multiply(BigDecimal.valueOf(quantity), MATH_CONTEXT);
        }
        if (quantity <= 0) {
            return new PositionSizingResult(false, 0, riskAmount, estimatedCost,
                    "Estimated cost exceeds available capital");
        }
        return new PositionSizingResult(true, quantity, riskAmount, estimatedCost,
                "Quantity sized within risk and capital limits");
    }

    private BigDecimal maxDailyLossAmount() {
        return properties.risk().totalCapital()
                .multiply(properties.risk().maxDailyLossPercent(), MATH_CONTEXT)
                .divide(BigDecimal.valueOf(100), MATH_CONTEXT);
    }
}
