package com.algo.trade.execution.exit;

import com.algo.trade.strategy.DynamicExitManager;
import org.springframework.stereotype.Component;

/**
 * Resolves effective SL/target % from config, ATR, or hybrid rules.
 */
@Component
public class ExitParamResolver {

    private final DynamicExitManager dynamicExitManager;

    public ExitParamResolver(DynamicExitManager dynamicExitManager) {
        this.dynamicExitManager = dynamicExitManager;
    }

    public record ResolvedExits(
            double stopLossPercent,
            double targetPercent,
            double trailActivationPercent,
            double trailGapPercent,
            boolean atrUsed,
            ExitMode mode
    ) {}

    public ResolvedExits resolveSingleLeg(
            ExitMode mode,
            double configSl,
            double configTarget,
            double configTrailActivation,
            double configTrailGap,
            double entryPremium,
            double underlyingAtr,
            double optionAtr,
            double delta,
            int daysToExpiry) {
        return resolveSingleLeg(mode, configSl, configTarget, configTrailActivation, configTrailGap,
                entryPremium, underlyingAtr, optionAtr, delta, daysToExpiry, false);
    }

    public ResolvedExits resolveSingleLeg(
            ExitMode mode,
            double configSl,
            double configTarget,
            double configTrailActivation,
            double configTrailGap,
            double entryPremium,
            double underlyingAtr,
            double optionAtr,
            double delta,
            int daysToExpiry,
            boolean preferConfigTrail) {

        boolean hasUnderlying = underlyingAtr > 0 && delta > 0 && entryPremium > 0;
        boolean hasOptionAtr = optionAtr > 0 && entryPremium > 0;

        double atrSl;
        double atrTarget;
        double atrTrailAct;
        double atrTrailGap;

        if (hasUnderlying) {
            atrSl = dynamicExitManager.calculateDeltaAdjustedSL(entryPremium, underlyingAtr, delta);
            atrTarget = dynamicExitManager.calculateDeltaAdjustedTarget(entryPremium, underlyingAtr, delta, daysToExpiry);
            atrTrailAct = dynamicExitManager.calculateDeltaAdjustedTrailActivation(entryPremium, underlyingAtr, delta);
            atrTrailGap = dynamicExitManager.calculateDeltaAdjustedTrailGap(entryPremium, underlyingAtr, delta, 0);
        } else if (hasOptionAtr) {
            atrSl = dynamicExitManager.calculateDynamicSL(entryPremium, optionAtr, daysToExpiry);
            atrTarget = dynamicExitManager.calculateDynamicTarget(entryPremium, optionAtr, daysToExpiry);
            atrTrailAct = clamp((1.5 * optionAtr / entryPremium) * 100, 5, 50);
            atrTrailGap = clamp((1.0 * optionAtr / entryPremium) * 100, 3, 25);
        } else {
            return new ResolvedExits(configSl, configTarget, configTrailActivation, configTrailGap, false, mode);
        }

        return switch (mode) {
            case CONFIG -> new ResolvedExits(configSl, configTarget, configTrailActivation, configTrailGap, false, mode);
            case ATR -> new ResolvedExits(atrSl, atrTarget, atrTrailAct, atrTrailGap, true, mode);
            case HYBRID -> new ResolvedExits(
                    Math.min(configSl, atrSl),
                    Math.min(configTarget, atrTarget),
                    preferConfigTrail ? configTrailActivation
                            : Math.min(hasUnderlying ? atrTrailAct : configTrailActivation, configTrailActivation),
                    preferConfigTrail ? configTrailGap
                            : Math.min(hasUnderlying ? atrTrailGap : configTrailGap, configTrailGap),
                    true,
                    mode);
        };
    }

    public ResolvedExits resolveSpreadCredit(
            ExitMode mode,
            double configSl,
            double configTarget,
            double underlyingAtr,
            double entryCredit,
            int daysToExpiry) {
        if (entryCredit <= 0 || underlyingAtr <= 0) {
            return new ResolvedExits(configSl, configTarget, 0, 0, false, mode);
        }
        // Map underlying move to % of credit at risk (approximate short straddle delta ~1.0)
        double atrSl = clamp((2.0 * underlyingAtr / entryCredit) * 100, 15, 100);
        double atrTarget = clamp((2.5 * underlyingAtr / entryCredit) * 100, 10, 80);
        double timeMult = daysToExpiry <= 0 ? 0.5 : daysToExpiry == 1 ? 0.7 : 1.0;
        atrTarget *= timeMult;
        // Compute trail params for spreads (activation at 60% of target, gap at 30% of target)
        double trailActivation = clamp(atrTarget * 0.6, 5, 50);
        double trailGap = clamp(atrTarget * 0.3, 3, 25);

        return switch (mode) {
            case CONFIG -> new ResolvedExits(configSl, configTarget, 0, 0, false, mode);
            case ATR -> new ResolvedExits(atrSl, atrTarget, trailActivation, trailGap, true, mode);
            case HYBRID -> new ResolvedExits(
                    Math.min(configSl, atrSl),
                    Math.min(configTarget, atrTarget),
                    trailActivation, trailGap, true, mode);
        };
    }

    public ResolvedExits resolveSpreadDebit(
            ExitMode mode,
            double configSl,
            double configTarget,
            double underlyingAtr,
            double entryDebit,
            int daysToExpiry) {
        if (entryDebit <= 0 || underlyingAtr <= 0) {
            return new ResolvedExits(configSl, configTarget, 0, 0, false, mode);
        }
        double atrSl = clamp((2.0 * underlyingAtr / entryDebit) * 100, 15, 80);
        double atrTarget = clamp((3.0 * underlyingAtr / entryDebit) * 100, 20, 150);
        // Compute trail params for debit spreads (activation at 50% of target, gap at 25% of target)
        double trailActivation = clamp(atrTarget * 0.5, 5, 60);
        double trailGap = clamp(atrTarget * 0.25, 3, 30);

        return switch (mode) {
            case CONFIG -> new ResolvedExits(configSl, configTarget, 0, 0, false, mode);
            case ATR -> new ResolvedExits(atrSl, atrTarget, trailActivation, trailGap, true, mode);
            case HYBRID -> new ResolvedExits(
                    Math.min(configSl, atrSl),
                    Math.min(configTarget, atrTarget),
                    trailActivation, trailGap, true, mode);
        };
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
