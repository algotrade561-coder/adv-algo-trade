package com.algo.trade.strategy.oimomentum;

import com.algo.trade.config.usersettings.RiskProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Profile Gate Scaler — applies per-profile multipliers to dynamic gate values.
 *
 * <p>Profiles act as scaling layers on top of the dynamic engine, NOT as fixed configs.
 * Each profile defines multipliers per parameter, so:
 * <ul>
 *   <li><b>CONSERVATIVE</b>: tighter floors (×1.15), wider SL (×1.2), reduced lots (×0.6)</li>
 *   <li><b>BALANCED</b>: neutral (×1.0 on everything)</li>
 *   <li><b>AGGRESSIVE</b>: looser floors (×0.85), tighter SL (×0.8), more lots (×1.3)</li>
 * </ul>
 *
 * <p>This means profiles scale the DYNAMIC engine rather than hardcoding values. A CONSERVATIVE
 * user in a HIGH_VOL regime still sees the dynamic relaxation, but attenuated. An AGGRESSIVE
 * user sees it amplified.</p>
 */
@Component
public class ProfileGateScaler {

    private static final Logger log = LoggerFactory.getLogger(ProfileGateScaler.class);

    /** Per-profile scaling multipliers for each dynamic gate parameter. */
    public record ProfileMultipliers(
            double signalScoreFloorMult,    // applied to the effective floor after dynamic adjustment
            double stopLossMult,            // scales SL%
            double targetMult,              // scales target%
            double trailingActivationMult,  // scales trailing activation
            double trailingGapMult,         // scales trailing gap
            double lotSizeMult              // scales lot multiplier
    ) {
        public static ProfileMultipliers neutral() {
            return new ProfileMultipliers(1.0, 1.0, 1.0, 1.0, 1.0, 1.0);
        }
    }

    /**
     * Get the profile multipliers for a given risk profile.
     */
    public ProfileMultipliers getMultipliers(RiskProfile profile) {
        if (profile == null) return ProfileMultipliers.neutral();
        return switch (profile) {
            case CONSERVATIVE -> new ProfileMultipliers(
                    1.15,  // 15% higher signal score floor (stricter entry)
                    1.2,   // 20% wider stop loss (more breathing room)
                    0.85,  // 15% smaller targets (book earlier)
                    0.9,   // trailing activates earlier
                    1.1,   // slightly wider trailing gap
                    0.6    // 40% fewer lots
            );
            case BALANCED -> ProfileMultipliers.neutral();
            case AGGRESSIVE -> new ProfileMultipliers(
                    0.78,  // 22% lower floor (much more entries)
                    0.75,  // 25% tighter SL (cut fast, try again)
                    1.0,   // targets stay neutral (quick exits via trailing)
                    0.8,   // trailing activates sooner (lock profit fast)
                    0.7,   // much tighter trailing gap (₹2000 lock style)
                    1.4    // 40% more lots (higher conviction sizing)
            );
            case CUSTOM -> ProfileMultipliers.neutral();
        };
    }

    /**
     * Apply profile scaling to dynamic gate values.
     * Returns a new DynamicValues record with profile-scaled parameters.
     */
    public DynamicGateEngine.DynamicValues scale(DynamicGateEngine.DynamicValues base, RiskProfile profile) {
        if (profile == null || profile == RiskProfile.BALANCED || profile == RiskProfile.CUSTOM) {
            return base; // no scaling for BALANCED/CUSTOM
        }
        ProfileMultipliers m = getMultipliers(profile);
        return new DynamicGateEngine.DynamicValues(
                clamp(base.signalScoreMin() * m.signalScoreFloorMult(), 30, 80),
                clamp(base.stopLossPercent() * m.stopLossMult(), 3, 25),
                clamp(base.targetPercent() * m.targetMult(), 5, 70),
                clamp(base.trailingActivationPercent() * m.trailingActivationMult(), 2, 20),
                clamp(base.trailingGapPercent() * m.trailingGapMult(), 1, 10),
                clamp(base.lotSizeMultiplier() * m.lotSizeMult(), 0.3, 2.0),
                base.regime()
        );
    }

    /**
     * Apply profile scaling to the signal score floor specifically (for use in OIMomentumStrategy).
     */
    public double scaleSignalFloor(double dynamicFloor, RiskProfile profile) {
        ProfileMultipliers m = getMultipliers(profile);
        return clamp(dynamicFloor * m.signalScoreFloorMult(), 25, 80);
    }

    /**
     * Apply profile scaling to the lot multiplier specifically.
     */
    public double scaleLotMultiplier(double dynamicLotMult, RiskProfile profile) {
        ProfileMultipliers m = getMultipliers(profile);
        return clamp(dynamicLotMult * m.lotSizeMult(), 0.3, 2.0);
    }

    private double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
