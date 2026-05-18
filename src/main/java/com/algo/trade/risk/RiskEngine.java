package com.algo.trade.risk;

import com.algo.trade.config.GlobalConfigService;
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

    private final GlobalConfigService globalConfigService;
    private final TradingProperties properties;
    private final StrategyConfigService strategyConfigService;
    private final TradingStateService tradingStateService;
    private final SafeWeekPredictor safeWeekPredictor;

    public RiskEngine(GlobalConfigService globalConfigService, TradingProperties properties, StrategyConfigService strategyConfigService,
                      @Lazy TradingStateService tradingStateService, SafeWeekPredictor safeWeekPredictor) {
        this.globalConfigService = globalConfigService;
        this.properties = properties;
        this.strategyConfigService = strategyConfigService;
        this.tradingStateService = tradingStateService;
        this.safeWeekPredictor = safeWeekPredictor;
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
        if (openTradeCount >= globalConfigService.getMaxOpenTrades()) {
            rejections.add("Max open trades limit reached (" + openTradeCount + "/" + globalConfigService.getMaxOpenTrades() + ")");
        }
        if (tradesToday >= globalConfigService.getMaxTradesPerDay()) {
            rejections.add("Max trades per day reached");
        }
        if (dailyPnl.compareTo(effectiveDailyLossLimit().negate()) <= 0) {
            rejections.add(String.format("Max daily loss reached (limit: \u20b9%.0f)",
                    effectiveDailyLossLimit().doubleValue()));
            // Auto-halt on daily loss breach — prevents repeated evaluation + log spam
            if (tradingStateService.haltMode() == com.algo.trade.risk.HaltMode.NONE) {
                tradingStateService.softHalt("Daily loss limit breached: P&L ₹" + dailyPnl.setScale(0, java.math.RoundingMode.HALF_UP));
                log.warn("[RiskEngine] SOFT HALT triggered: daily loss limit breached (P&L=₹{})", dailyPnl.setScale(0, java.math.RoundingMode.HALF_UP));
            }
        }
        if (consecutiveLosses >= globalConfigService.getMaxConsecutiveLosses()) {
            rejections.add("Max consecutive losses reached");
        }

        // Rolling win-rate auto-pause — pause if recent win rate drops too low
        double rollingWinRate = tradingStateService.rollingWinRate();
        if (tradesToday >= 5 && rollingWinRate < 25.0) {
            rejections.add(String.format("Rolling win rate too low (%.0f%% on %d trades) — auto-paused", rollingWinRate, tradesToday));
        }

        // Correlation note: NIFTY/BANKNIFTY/FINNIFTY/MIDCPNIFTY are highly correlated.
        // The maxOpenTrades config above already limits concurrent positions.

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
        return calculateQuantity(optionPremium, lotSize, dirConfig.getStopLossPercent());
    }

    /**
     * ATR-based position sizing: uses ATR to determine stop distance, then sizes position
     * so that max loss per trade stays within risk budget.
     *
     * @param optionPremium entry price per unit
     * @param lotSize       units per lot
     * @param atr           current ATR in points (from 15-min candles)
     * @return sizing result with quantity capped by risk and maxLotsPerTrade
     */
    public PositionSizingResult calculateQuantityWithATR(BigDecimal optionPremium, int lotSize, double atr) {
        if (atr <= 0 || optionPremium == null || optionPremium.signum() <= 0) {
            return calculateQuantity(optionPremium, lotSize);
        }
        // ATR-based SL: 2× ATR as stop distance (same formula as DynamicExitManager)
        double atrSlPercent = (2 * atr / optionPremium.doubleValue()) * 100;
        atrSlPercent = Math.max(15, Math.min(60, atrSlPercent)); // Clamp same as DynamicExitManager
        log.info("ATR-based position sizing: atr={}, entry={}, atrSL={}%",
                String.format("%.1f", atr), optionPremium, String.format("%.1f", atrSlPercent));
        return calculateQuantity(optionPremium, lotSize, BigDecimal.valueOf(atrSlPercent));
    }

    /**
     * Position sizing with explicit stop-loss percent (for per-strategy sizing).
     */
    public PositionSizingResult calculateQuantity(BigDecimal optionPremium, int lotSize, BigDecimal stopLossPercent) {
        log.info("Position sizing started: optionPremium={}, lotSize={}, totalCapital={}, maxRiskPerTradePercent={}, stopLossPercent={}",
                optionPremium, lotSize, globalConfigService.getTotalCapital(), globalConfigService.getMaxRiskPerTradePercent(),
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

        BigDecimal riskAmount = globalConfigService.getTotalCapital()
                .multiply(globalConfigService.getMaxRiskPerTradePercent(), MATH_CONTEXT)
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
        // Cap at maxLotsPerTrade from global config (configurable from UI).
        // lotSize = config.getLots() × indexLotSize, so maxLotsPerTrade=1 means exactly config.getLots() lots.
        int maxLots = globalConfigService.getMaxLotsPerTrade();
        if (maxLots > 0 && lots > maxLots) {
            log.info("Position sizing capped by maxLotsPerTrade: lots={} → {}", lots, maxLots);
            lots = maxLots;
        }
        // Apply safe week multiplier: SAFE=1.0x, MODERATE=0.5x, RISKY=0.25x
        double weekMultiplier = safeWeekPredictor != null ? safeWeekPredictor.getSizeMultiplier() : 1.0;
        if (weekMultiplier < 1.0 && lots > 1) {
            int adjustedLots = Math.max(1, (int) (lots * weekMultiplier));
            log.info("Position sizing adjusted by SafeWeek: lots={} → {} (multiplier={}, risk={})",
                    lots, adjustedLots, weekMultiplier, safeWeekPredictor.getRisk());
            lots = adjustedLots;
        }
        int quantity = lots * lotSize;
        BigDecimal estimatedCost = optionPremium.multiply(BigDecimal.valueOf(quantity), MATH_CONTEXT);

        if (quantity <= 0) {
            log.warn("Position sizing rejected: premium too high for risk budget, riskAmount={}, estimatedCost={}",
                    riskAmount, estimatedCost);
            return new PositionSizingResult(false, 0, riskAmount, estimatedCost,
                    "Premium is too high for the risk budget");
        }
        if (estimatedCost.compareTo(globalConfigService.getTotalCapital()) > 0) {
            log.info("Position sizing capped by total capital: originalQuantity={}, originalEstimatedCost={}",
                    quantity, estimatedCost);
            int affordableLots = globalConfigService.getTotalCapital()
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
        return globalConfigService.getTotalCapital()
                .multiply(globalConfigService.getMaxDailyLossPercent(), MATH_CONTEXT)
                .divide(BigDecimal.valueOf(100), MATH_CONTEXT);
    }

    /** Effective limit = base + any extensions approved via UI. */
    public BigDecimal effectiveDailyLossLimit() {
        return baseDailyLossLimit().add(BigDecimal.valueOf(tradingStateService.dailyLossExtension()));
    }
}
