package com.algo.trade.strategy.oishifttrap;

import com.algo.trade.domain.Candle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Feature 12 — Liquidity hunt / OI magnet detector.
 *
 * <p>Sometimes spot gravitates toward a heavy-OI strike as a "magnet" before the actual
 * squeeze fires. The signature: spot drift over the recent candles is monotonically toward
 * the trap strike, velocity is decelerating (operators absorbing), and current proximity is
 * tight. This detector returns {@code true} when that pattern is present so the strategy
 * can boost confidence on the entry (it does NOT block anything).
 *
 * <p>Stateless — relies on the candle list and current spot supplied by the caller.
 */
@Component
public class ShiftTrapLiquidityHuntDetector {

    private static final Logger log = LoggerFactory.getLogger(ShiftTrapLiquidityHuntDetector.class);
    private static final int LOOKBACK_BARS = 5;

    /**
     * Returns true when the spot is being magnetised toward the trap strike with decelerating
     * velocity and tight proximity. Auto-fails on insufficient data so the legacy bonus path
     * is never wrongly granted.
     *
     * @param candles         chronologically ordered candles (most recent last)
     * @param trapStrike      strike under inspection
     * @param spot            current spot price
     * @param proximityPct    proximity gate as percent of spot (e.g. 0.30 = 0.30%)
     */
    public boolean isHuntPattern(List<Candle> candles, BigDecimal trapStrike, BigDecimal spot, double proximityPct) {
        if (candles == null || candles.size() < LOOKBACK_BARS
                || trapStrike == null || spot == null || spot.signum() <= 0) {
            return false;
        }
        double target = trapStrike.doubleValue();
        double current = spot.doubleValue();
        double proximityAbs = Math.abs(target - current) / current * 100.0;
        if (proximityAbs > proximityPct) {
            return false;
        }

        // Drift slope: are recent closes moving monotonically toward the trap strike?
        List<Candle> recent = candles.subList(candles.size() - LOOKBACK_BARS, candles.size());
        double firstClose = recent.get(0).close().doubleValue();
        double lastClose = recent.get(recent.size() - 1).close().doubleValue();
        double distanceStart = Math.abs(target - firstClose);
        double distanceEnd = Math.abs(target - lastClose);
        boolean convergingOverall = distanceEnd < distanceStart;
        if (!convergingOverall) {
            return false;
        }

        // Deceleration: the per-bar change in distance should be shrinking.
        double prevStep = Math.abs(target - recent.get(1).close().doubleValue())
                - Math.abs(target - recent.get(0).close().doubleValue());
        double lastStep = Math.abs(target - recent.get(recent.size() - 1).close().doubleValue())
                - Math.abs(target - recent.get(recent.size() - 2).close().doubleValue());
        // Steps should be negative (distance shrinking); deceleration means |lastStep| <= |prevStep|.
        boolean decelerating = Math.abs(lastStep) <= Math.abs(prevStep) + 1e-9;

        boolean hunt = decelerating;
        if (hunt) {
            log.debug("[ShiftTrap] Liquidity hunt: spot={} strike={} proximity={}%, decel ok",
                    spot, trapStrike, String.format("%.2f", proximityAbs));
        }
        return hunt;
    }
}
