package com.algo.trade.execution.exit;

import com.algo.trade.config.GlobalConfigService;
import com.algo.trade.config.SpreadTradingProperties;
import com.algo.trade.domain.Candle;
import com.algo.trade.domain.IndexType;
import com.algo.trade.domain.PositionGroup;
import com.algo.trade.domain.Quote;
import com.algo.trade.domain.Timeframe;
import com.algo.trade.marketdata.ExpiryCalendar;
import com.algo.trade.marketdata.LiveCandleBuilder;
import com.algo.trade.persistence.PositionGroupEntity;
import com.algo.trade.strategy.DynamicExitManager;
import com.algo.trade.strategy.StrategyConfig;
import com.algo.trade.strategy.StrategyType;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Shared spread exit rules: time, expiry, stale quotes, ATR-adjusted SL/target, partial booking.
 */
@Component
public class SpreadExitPolicy {

    private static final Logger log = LoggerFactory.getLogger(SpreadExitPolicy.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    public enum Action { CONTINUE, EXIT, SKIP }

    public record Result(Action action, String reason, ExitEvaluationSnapshot snapshot) {
        public static Result continueWith(ExitEvaluationSnapshot snapshot) {
            return new Result(Action.CONTINUE, null, snapshot);
        }

        public static Result exit(String reason, ExitEvaluationSnapshot snapshot) {
            return new Result(Action.EXIT, reason, snapshot);
        }

        public static Result skip(ExitEvaluationSnapshot snapshot) {
            return new Result(Action.SKIP, null, snapshot);
        }
    }

    private final ExpiryCalendar expiryCalendar;
    private final GlobalConfigService globalConfigService;
    private final SpreadTradingProperties spreadProperties;
    private final ExitParamResolver exitParamResolver;
    private final LiveCandleBuilder liveCandleBuilder;
    private final DynamicExitManager dynamicExitManager;
    private final LiquidityEmergencyGate liquidityEmergencyGate;
    private final com.algo.trade.indicator.IVRankTracker ivRankTracker;
    private final Map<String, Set<String>> firedPartialLayers = new ConcurrentHashMap<>();

    public SpreadExitPolicy(ExpiryCalendar expiryCalendar,
                            GlobalConfigService globalConfigService,
                            SpreadTradingProperties spreadProperties,
                            ExitParamResolver exitParamResolver,
                            LiveCandleBuilder liveCandleBuilder,
                            DynamicExitManager dynamicExitManager,
                            LiquidityEmergencyGate liquidityEmergencyGate,
                            com.algo.trade.indicator.IVRankTracker ivRankTracker) {
        this.expiryCalendar = expiryCalendar;
        this.globalConfigService = globalConfigService;
        this.spreadProperties = spreadProperties;
        this.exitParamResolver = exitParamResolver;
        this.liveCandleBuilder = liveCandleBuilder;
        this.dynamicExitManager = dynamicExitManager;
        this.liquidityEmergencyGate = liquidityEmergencyGate;
        this.ivRankTracker = ivRankTracker;
    }

    public Result evaluate(PositionGroupEntity entity,
                           PositionGroup group,
                           Map<String, BigDecimal> currentPrices,
                           Map<String, Quote> quotes,
                           StrategyConfig config) {
        StrategyConfig effective = applyGlobalOverride(config);
        IndexType indexType = IndexType.from(group.underlying());

        var liquiditySignal = liquidityEmergencyGate.checkSpreadEmergency(entity, quotes);
        if (liquiditySignal.isPresent()) {
            var signal = liquiditySignal.get();
            return Result.exit(signal.reason(),
                    snapshot(entity, group, currentPrices, effective, 0, 0, signal.reason()));
        }

        LocalTime now = LocalTime.now(IST);
        if (effective.getMaxHoldMinutes() > 0 && group.entryTime() != null) {
            long holdMin = Duration.between(group.entryTime(), Instant.now()).toMinutes();
            if (holdMin >= effective.getMaxHoldMinutes()) {
                return Result.exit("MAX_HOLD_TIME",
                        snapshot(entity, group, currentPrices, effective, 0, 0, "MAX_HOLD_TIME"));
            }
        }

        LocalTime squareoff = LocalTime.of(effective.getSquareoffHour(), effective.getSquareoffMinute());
        if (!now.isBefore(squareoff)) {
            return Result.exit("SQUAREOFF_TIME",
                    snapshot(entity, group, currentPrices, effective, 0, 0, "SQUAREOFF_TIME"));
        }

        if (expiryCalendar.isExpiryDangerZone(indexType)) {
            return Result.exit("EXPIRY_DANGER_ZONE",
                    snapshot(entity, group, currentPrices, effective, 0, 0, "EXPIRY_DANGER_ZONE"));
        }

        if (expiryCalendar.isExpiryAfternoon(indexType)) {
            return Result.exit("EXPIRY_AFTERNOON_EXIT",
                    snapshot(entity, group, currentPrices, effective, 0, 0, "EXPIRY_AFTERNOON_EXIT"));
        }

        if ((group.strategyType() == StrategyType.CALENDAR_SPREAD
                || group.strategyType() == StrategyType.DIAGONAL_SPREAD)
                && expiryCalendar.isNearExpiry(indexType, 1)) {
            return Result.exit("NEAR_EXPIRY_LEG",
                    snapshot(entity, group, currentPrices, effective, 0, 0, "NEAR_EXPIRY_LEG"));
        }

        // Event-passed exit for long-vol structures — catalyst is over, theta starts winning.
        // Fires regardless of P&L once the post-event window has elapsed.
        if (isLongVolStructure(group.strategyType())
                && entity.getExpectedEventEndTime() != null
                && Instant.now().isAfter(entity.getExpectedEventEndTime())) {
            return Result.exit("EVENT_PASSED",
                    snapshot(entity, group, currentPrices, effective, 0, 0,
                            "EVENT_PASSED: post-event window ended at " + entity.getExpectedEventEndTime()));
        }

        // Vega-collapse exit for long-vol structures (LONG_STRADDLE, LONG_STRANGLE)
        // Exit if current IV rank has dropped significantly from entry IV rank
        if (isLongVolStructure(group.strategyType()) && entity.getEntryIvRank() != null) {
            double entryIv = entity.getEntryIvRank().doubleValue();
            double currentIvRank = ivRankTracker.getIVRank(indexType);
            double ivDrop = entryIv - currentIvRank;
            // Exit if IV rank dropped by more than 15 points (significant crush)
            if (ivDrop >= 15) {
                return Result.exit("VEGA_COLLAPSE",
                        snapshot(entity, group, currentPrices, effective, 0, 0,
                                "VEGA_COLLAPSE: IV rank dropped " + String.format("%.0f", ivDrop)
                                        + " pts (entry=" + String.format("%.0f", entryIv)
                                        + " current=" + String.format("%.0f", currentIvRank) + ")"));
            }
        }

        if (group.strategyType().isSellingStrategy()
                && SpreadPremiumExitHelper.anyShortLegDoubled(group, currentPrices)) {
            return Result.exit("SHORT_LEG_DOUBLED",
                    snapshot(entity, group, currentPrices, effective, 0, 0, "SHORT_LEG_DOUBLED"));
        }

        double underlyingAtr = resolveUnderlyingAtr(indexType);
        ExitMode mode = spreadProperties.exitMode();
        boolean atrEnabled = spreadProperties.spreadAtrExitsEnabled() && underlyingAtr > 0;

        double slPct = effective.getStopLossPercent().doubleValue();
        double targetPct = effective.getTargetPercent().doubleValue();
        boolean atrUsed = false;

        if (atrEnabled) {
            if (group.strategyType().isSellingStrategy()) {
                BigDecimal entryCredit = SpreadPremiumExitHelper.netCredit(group.legs(), group.entryPrices());
                var resolved = exitParamResolver.resolveSpreadCredit(
                        mode, slPct, targetPct, underlyingAtr, entryCredit.doubleValue(),
                        (int) expiryCalendar.daysToExpiry(indexType));
                slPct = resolved.stopLossPercent();
                targetPct = resolved.targetPercent();
                atrUsed = resolved.atrUsed();
                persistEffectiveParams(entity, resolved);

                BigDecimal currentCredit = SpreadPremiumExitHelper.netCredit(group.legs(), currentPrices);
                double decay = SpreadPremiumExitHelper.creditDecayPercent(entryCredit, currentCredit);
                updatePeakProfit(entity, decay);

                if (SpreadPremiumExitHelper.creditTargetHit(decay, targetPct)) {
                    return Result.exit("TARGET",
                            snapshot(entity, group, currentPrices, effective, slPct, targetPct, "TARGET"));
                }
                if (SpreadPremiumExitHelper.creditStopLossHit(decay, slPct)) {
                    return Result.exit("STOP_LOSS",
                            snapshot(entity, group, currentPrices, effective, slPct, targetPct, "STOP_LOSS"));
                }

                Optional<String> partial = checkPartialCredit(entity, group, decay);
                if (partial.isPresent()) {
                    return Result.exit(partial.get(),
                            snapshot(entity, group, currentPrices, effective, slPct, targetPct, partial.get()));
                }
            } else {
                BigDecimal entryNet = SpreadPremiumExitHelper.netDebit(group.legs(), group.entryPrices());
                BigDecimal currentNet = SpreadPremiumExitHelper.netDebit(group.legs(), currentPrices);
                var resolved = exitParamResolver.resolveSpreadDebit(
                        mode, slPct, targetPct, underlyingAtr, entryNet.abs().doubleValue(),
                        (int) expiryCalendar.daysToExpiry(indexType));
                slPct = resolved.stopLossPercent();
                targetPct = resolved.targetPercent();
                atrUsed = resolved.atrUsed();
                persistEffectiveParams(entity, resolved);

                double debitProfit = computeDisplayProfit(group, currentPrices);
                updatePeakProfit(entity, debitProfit);

                if (SpreadPremiumExitHelper.debitStopLossHit(entryNet, currentNet, BigDecimal.valueOf(slPct))) {
                    return Result.exit("STOP_LOSS",
                            snapshot(entity, group, currentPrices, effective, slPct, targetPct, "STOP_LOSS"));
                }
                if (SpreadPremiumExitHelper.debitTargetHit(entryNet, currentNet, BigDecimal.valueOf(targetPct))) {
                    return Result.exit("TARGET",
                            snapshot(entity, group, currentPrices, effective, slPct, targetPct, "TARGET"));
                }
            }
        } else {
            persistEffectiveParams(entity, new ExitParamResolver.ResolvedExits(
                    slPct, targetPct, 0, 0, false, ExitMode.CONFIG));
            if (group.strategyType().isSellingStrategy()) {
                BigDecimal entryCredit = SpreadPremiumExitHelper.netCredit(group.legs(), group.entryPrices());
                BigDecimal currentCredit = SpreadPremiumExitHelper.netCredit(group.legs(), currentPrices);
                double decay = SpreadPremiumExitHelper.creditDecayPercent(entryCredit, currentCredit);
                updatePeakProfit(entity, decay);
                if (SpreadPremiumExitHelper.creditTargetHit(decay, targetPct)) {
                    return Result.exit("TARGET",
                            snapshot(entity, group, currentPrices, effective, slPct, targetPct, "TARGET"));
                }
                if (SpreadPremiumExitHelper.creditStopLossHit(decay, slPct)) {
                    return Result.exit("STOP_LOSS",
                            snapshot(entity, group, currentPrices, effective, slPct, targetPct, "STOP_LOSS"));
                }
            } else {
                BigDecimal entryNet = SpreadPremiumExitHelper.netDebit(group.legs(), group.entryPrices());
                BigDecimal currentNet = SpreadPremiumExitHelper.netDebit(group.legs(), currentPrices);
                updatePeakProfit(entity, computeDisplayProfit(group, currentPrices));
                if (SpreadPremiumExitHelper.debitStopLossHit(entryNet, currentNet, effective.getStopLossPercent())) {
                    return Result.exit("STOP_LOSS",
                            snapshot(entity, group, currentPrices, effective, slPct, targetPct, "STOP_LOSS"));
                }
                if (SpreadPremiumExitHelper.debitTargetHit(entryNet, currentNet, effective.getTargetPercent())) {
                    return Result.exit("TARGET",
                            snapshot(entity, group, currentPrices, effective, slPct, targetPct, "TARGET"));
                }
            }
        }

        if (atrEnabled && group.strategyType().isSellingStrategy()) {
            List<Candle> candles15m = liveCandleBuilder.getHistory(indexType.spotToken(), Timeframe.FIFTEEN_MINUTE);
            if (candles15m.size() >= 15 && dynamicExitManager.isBreakout(candles15m, 14)) {
                return Result.exit("MOMENTUM_BREAKOUT_EXIT",
                        snapshot(entity, group, currentPrices, effective, slPct, targetPct, "MOMENTUM_BREAKOUT_EXIT"));
            }
        }

        Optional<Result> trailing = maybeTrailingExit(entity, group, currentPrices, effective, slPct, targetPct);
        if (trailing.isPresent()) {
            return trailing.get();
        }

        double profitPct = computeDisplayProfit(group, currentPrices);
        return Result.continueWith(snapshot(entity, group, currentPrices, effective, slPct, targetPct,
                "HOLD profit=" + String.format("%.1f", profitPct) + (atrUsed ? " atr" : " config")));
    }

    private Optional<Result> maybeTrailingExit(PositionGroupEntity entity,
                                               PositionGroup group,
                                               Map<String, BigDecimal> currentPrices,
                                               StrategyConfig effective,
                                               double slPct,
                                               double targetPct) {
        double profitPct = computeDisplayProfit(group, currentPrices);
        updatePeakProfit(entity, profitPct);

        double trailAct = entity.getEffectiveTrailActivationPercent() != null
                ? entity.getEffectiveTrailActivationPercent().doubleValue()
                : effective.getTrailingStopActivationPercent().doubleValue();
        double trailGap = entity.getEffectiveTrailGapPercent() != null
                ? entity.getEffectiveTrailGapPercent().doubleValue()
                : effective.getTrailingGapPercent().doubleValue();
        if (trailAct <= 0 || trailGap <= 0) {
            return Optional.empty();
        }

        BigDecimal peak = entity.getPeakProfitPercent();
        double peakVal = peak != null ? peak.doubleValue() : profitPct;
        if (peakVal >= trailAct && profitPct < peakVal - trailGap) {
            return Optional.of(Result.exit("TRAILING_STOP",
                    snapshot(entity, group, currentPrices, effective, slPct, targetPct, "TRAILING_STOP")));
        }
        return Optional.empty();
    }

    public void clearPartialLayers(String groupId) {
        firedPartialLayers.remove(groupId);
    }

    /**
     * Hydrates the in-memory firedPartialLayers from a persisted entity's partialExitLayers field.
     * Called during startup recovery to prevent progressive layers from double-firing.
     */
    public void hydratePartialLayers(String groupId, String persistedLayers) {
        if (persistedLayers == null || persistedLayers.isBlank()) {
            return;
        }
        Set<String> layers = ConcurrentHashMap.newKeySet();
        for (String layer : persistedLayers.split(",")) {
            String trimmed = layer.trim();
            if (!trimmed.isEmpty()) {
                layers.add(trimmed);
            }
        }
        if (!layers.isEmpty()) {
            firedPartialLayers.put(groupId, layers);
        }
    }

    private Optional<String> checkPartialCredit(PositionGroupEntity entity, PositionGroup group, double decayPercent) {
        if (!spreadProperties.partialProfitEnabled()) {
            return Optional.empty();
        }
        Set<String> fired = firedPartialLayers.computeIfAbsent(group.groupId(), id -> ConcurrentHashMap.newKeySet());
        var layer = dynamicExitManager.nextExitLayer(decayPercent, fired);
        if (layer.isEmpty()) {
            return Optional.empty();
        }
        fired.add(layer.get().name());
        entity.setPartialExitLayers(String.join(",", fired));
        return Optional.of("PROGRESSIVE_" + layer.get().name());
    }

    private double computeDisplayProfit(PositionGroup group, Map<String, BigDecimal> currentPrices) {
        if (group.strategyType().isSellingStrategy()) {
            BigDecimal ec = SpreadPremiumExitHelper.netCredit(group.legs(), group.entryPrices());
            BigDecimal cc = SpreadPremiumExitHelper.netCredit(group.legs(), currentPrices);
            return SpreadPremiumExitHelper.creditDecayPercent(ec, cc);
        }
        BigDecimal en = SpreadPremiumExitHelper.netDebit(group.legs(), group.entryPrices());
        BigDecimal cn = SpreadPremiumExitHelper.netDebit(group.legs(), currentPrices);
        if (en.signum() == 0) {
            return 0;
        }
        return cn.subtract(en).divide(en.abs(), java.math.MathContext.DECIMAL64)
                .multiply(BigDecimal.valueOf(100)).doubleValue();
    }

    private void updatePeakProfit(PositionGroupEntity entity, double profitPct) {
        BigDecimal peak = entity.getPeakProfitPercent();
        BigDecimal profit = BigDecimal.valueOf(profitPct);
        if (peak == null || profit.compareTo(peak) > 0) {
            entity.setPeakProfitPercent(profit);
        }
    }

    private void persistEffectiveParams(PositionGroupEntity entity, ExitParamResolver.ResolvedExits resolved) {
        entity.setEffectiveStopLossPercent(BigDecimal.valueOf(resolved.stopLossPercent()));
        entity.setEffectiveTargetPercent(BigDecimal.valueOf(resolved.targetPercent()));
        if (resolved.trailActivationPercent() > 0) {
            entity.setEffectiveTrailActivationPercent(BigDecimal.valueOf(resolved.trailActivationPercent()));
        }
        if (resolved.trailGapPercent() > 0) {
            entity.setEffectiveTrailGapPercent(BigDecimal.valueOf(resolved.trailGapPercent()));
        }
    }

    private double resolveUnderlyingAtr(IndexType indexType) {
        List<Candle> candles = liveCandleBuilder.getHistory(indexType.spotToken(), Timeframe.FIFTEEN_MINUTE);
        return candles.size() >= 15 ? dynamicExitManager.calculateATR(candles, 14) : 0;
    }

    /** Returns true for strategies that are long volatility (benefit from IV expansion). */
    private static boolean isLongVolStructure(StrategyType type) {
        return type == StrategyType.LONG_STRADDLE || type == StrategyType.LONG_STRANGLE;
    }

    private StrategyConfig applyGlobalOverride(StrategyConfig original) {
        if (!globalConfigService.isGlobalExitOverride()) {
            return original;
        }
        StrategyConfig c = new StrategyConfig(original.getStrategyType());
        c.setUnderlying(original.getUnderlying());
        c.setEnabled(original.isEnabled());
        c.setStopLossPercent(globalConfigService.getStopLossPercent());
        c.setTargetPercent(globalConfigService.getTargetPercent());
        c.setTrailingStopActivationPercent(globalConfigService.getTrailingStopActivationPercent());
        c.setTrailingGapPercent(globalConfigService.getTrailingGapPercent());
        c.setMaxHoldMinutes(globalConfigService.getMaxHoldMinutes());
        LocalTime forced = globalConfigService.getForcedExitTime();
        c.setSquareoffHour(forced.getHour());
        c.setSquareoffMinute(forced.getMinute());
        return c;
    }

    private ExitEvaluationSnapshot snapshot(PositionGroupEntity entity,
                                            PositionGroup group,
                                            Map<String, BigDecimal> prices,
                                            StrategyConfig config,
                                            double sl,
                                            double target,
                                            String decision) {
        double profit = computeDisplayProfit(group, prices);
        BigDecimal peak = entity.getPeakProfitPercent();
        return new ExitEvaluationSnapshot(
                entity.getGroupId(),
                "SPREAD",
                group.strategyType().name(),
                group.underlying().name(),
                Instant.now(),
                profit,
                peak != null ? peak.doubleValue() : profit,
                sl > 0 ? sl : config.getStopLossPercent().doubleValue(),
                target > 0 ? target : config.getTargetPercent().doubleValue(),
                entity.getEffectiveTrailActivationPercent() != null
                        ? entity.getEffectiveTrailActivationPercent().doubleValue() : 0,
                entity.getEffectiveTrailGapPercent() != null
                        ? entity.getEffectiveTrailGapPercent().doubleValue() : 0,
                spreadProperties.spreadAtrExitsEnabled(),
                spreadProperties.exitMode(),
                decision,
                decision
        );
    }
}
