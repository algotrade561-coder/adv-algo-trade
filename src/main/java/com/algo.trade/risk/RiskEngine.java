package com.algo.trade.risk;

import com.algo.trade.config.TradingProperties;
import com.algo.trade.domain.SignalType;
import com.algo.trade.domain.StrategyDecision;
import com.algo.trade.execution.TradingStateService;
import com.algo.trade.risk.HaltMode;
import com.algo.trade.strategy.StrategyConfig;
import com.algo.trade.strategy.StrategyConfigService;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

/**
 * Central risk gate for entries and position sizing.
 */
@Service
public class RiskEngine {

    private static final MathContext MATH_CONTEXT = MathContext.DECIMAL64;
    private static final Logger log = LoggerFactory.getLogger(RiskEngine.class);

    private final TradingProperties properties;
    private final StrategyConfigService strategyConfigService;
    private final TradingStateService tradingStateService;

    public RiskEngine(TradingProperties properties, StrategyConfigService strategyConfigService,
                      @Lazy TradingStateService tradingStateService) {
        this.properties = properties;
        this.strategyConfigService = strategyConfigService;
        this.tradingStateService = tradingStateService;
    }

    public RiskCheckResult evaluateEntry(
            StrategyDecision decision,
            int openTradeCount,
            int tradesToday,
            BigDecimal dailyPnl,
            int consecutiveLosses,
            boolean killSwitchEnabled
    ) {
        log.info("Risk check started: signalType={}, openTradeCount={}, tradesToday={}, dailyPnl={}, consecutiveLosses={}, runtimeKillSwitch={}, configuredKillSwitch={}",
                decision.signalType(), openTradeCount, tradesToday, dailyPnl, consecutiveLosses, killSwitchEnabled,
                properties.safety().killSwitchEnabled());
        List<String> rejections = new ArrayList<>();
        if (killSwitchEnabled || properties.safety().killSwitchEnabled()) {
            rejections.add("Kill switch is enabled");
        }
        if (tradingStateService.haltMode() == HaltMode.HARD) {
            rejections.add("Hard halt is active");
        }
        if (tradingStateService.haltMode() == HaltMode.SOFT) {
            rejections.add("Soft halt is active — no new entries allowed");
        }
        if (!tradingStateService.isDailyApproved()) {
            rejections.add("Daily trading not approved yet");
        }
        if (decision.signalType() != SignalType.BUY_CE && decision.signalType() != SignalType.BUY_PE) {
            rejections.add("Decision is not an entry signal");
        }
        if (openTradeCount >= properties.risk().maxOpenTrades()) {
            rejections.add("Max open trades limit reached (" + openTradeCount + "/" + properties.risk().maxOpenTrades() + ")");
        }
        if (tradesToday >= properties.risk().maxTradesPerDay()) {
            rejections.add("Max trades per day reached");
        }
        if (dailyPnl.compareTo(effectiveDailyLossLimit().negate()) <= 0) {
            rejections.add(String.format("Max daily loss reached (limit: \u20b9%.0f)",
                    effectiveDailyLossLimit().doubleValue()));
        }
        if (consecutiveLosses >= properties.risk().maxConsecutiveLosses()) {
            rejections.add("Max consecutive losses reached");
        }
        if (!decision.selectedInstrumentKey().isPresent() || !decision.optionType().isPresent()) {
            rejections.add("Decision does not contain selected option instrument details");
        }
        if (rejections.isEmpty()) {
            log.info("Risk check accepted");
            return RiskCheckResult.allowed("Entry risk checks passed");
        }
        log.warn("Risk check rejected: reasons={}", rejections);
        return RiskCheckResult.rejected(rejections);
    }

    public PositionSizingResult calculateQuantity(BigDecimal optionPremium, int lotSize) {
        StrategyConfig dirConfig = strategyConfigService.getDirectionalBuyConfig();
        BigDecimal stopLossPercent = dirConfig.getStopLossPercent();
        log.info("Position sizing started: optionPremium={}, lotSize={}, totalCapital={}, maxRiskPerTradePercent={}, stopLossPercent={}",
                optionPremium, lotSize, properties.risk().totalCapital(), properties.risk().maxRiskPerTradePercent(),
                stopLossPercent);
        if (optionPremium == null || optionPremium.signum() <= 0) {
            log.warn("Position sizing rejected: option premium must be positive");
            return new PositionSizingResult(false, 0, BigDecimal.ZERO, BigDecimal.ZERO,
                    "Option premium must be positive");
        }
        if (lotSize <= 0) {
            log.warn("Position sizing rejected: lot size must be positive");
            return new PositionSizingResult(false, 0, BigDecimal.ZERO, BigDecimal.ZERO,
                    "Lot size must be positive");
        }

        BigDecimal riskAmount = properties.risk().totalCapital()
                .multiply(properties.risk().maxRiskPerTradePercent(), MATH_CONTEXT)
                .divide(BigDecimal.valueOf(100), MATH_CONTEXT);
        BigDecimal lossPerUnit = optionPremium
                .multiply(stopLossPercent, MATH_CONTEXT)
                .divide(BigDecimal.valueOf(100), MATH_CONTEXT);
        if (lossPerUnit.signum() <= 0) {
            log.warn("Position sizing rejected: configured stop loss produces zero risk per unit");
            return new PositionSizingResult(false, 0, riskAmount, BigDecimal.ZERO,
                    "Configured stop loss produces zero risk per unit");
        }

        int rawQuantity = riskAmount.divide(lossPerUnit, MATH_CONTEXT).intValue();
        int lots = rawQuantity / lotSize;
        int quantity = lots * lotSize;
        BigDecimal estimatedCost = optionPremium.multiply(BigDecimal.valueOf(quantity), MATH_CONTEXT);

        if (quantity <= 0) {
            log.warn("Position sizing rejected: premium too high for risk budget, riskAmount={}, estimatedCost={}",
                    riskAmount, estimatedCost);
            return new PositionSizingResult(false, 0, riskAmount, estimatedCost,
                    "Premium is too high for the risk budget");
        }
        if (estimatedCost.compareTo(properties.risk().totalCapital()) > 0) {
            log.info("Position sizing capped by total capital: originalQuantity={}, originalEstimatedCost={}",
                    quantity, estimatedCost);
            int affordableLots = properties.risk().totalCapital()
                    .divide(optionPremium.multiply(BigDecimal.valueOf(lotSize), MATH_CONTEXT), MATH_CONTEXT)
                    .intValue();
            quantity = affordableLots * lotSize;
            estimatedCost = optionPremium.multiply(BigDecimal.valueOf(quantity), MATH_CONTEXT);
        }
        if (quantity <= 0) {
            log.warn("Position sizing rejected: estimated cost exceeds available capital, estimatedCost={}", estimatedCost);
            return new PositionSizingResult(false, 0, riskAmount, estimatedCost,
                    "Estimated cost exceeds available capital");
        }
        log.info("Position sizing accepted: quantity={}, riskAmount={}, estimatedCost={}",
                quantity, riskAmount, estimatedCost);
        return new PositionSizingResult(true, quantity, riskAmount, estimatedCost,
                "Quantity sized within risk and capital limits");
    }

    /** Base daily loss limit = maxDailyLossPercent % of totalCapital (e.g. 3% of ₹60,000 = ₹1,800). */
    public BigDecimal baseDailyLossLimit() {
        return properties.risk().totalCapital()
                .multiply(properties.risk().maxDailyLossPercent(), MATH_CONTEXT)
                .divide(BigDecimal.valueOf(100), MATH_CONTEXT);
    }

    /** Effective limit = base + any extensions approved via UI. */
    public BigDecimal effectiveDailyLossLimit() {
        return baseDailyLossLimit().add(BigDecimal.valueOf(tradingStateService.dailyLossExtension()));
    }
}
