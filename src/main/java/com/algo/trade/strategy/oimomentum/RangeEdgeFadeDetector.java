package com.algo.trade.strategy.oimomentum;

import com.algo.trade.data.ChainSnapshot;
import com.algo.trade.domain.IndexType;
import com.algo.trade.domain.OptionType;
import com.algo.trade.marketdata.LiveInstrumentCache;
import com.algo.trade.strategy.oimomentum.v3.MarketContextService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * R2 — Range-edge fade detector (29 May 2026, data-validated).
 *
 * <p>Addresses the gap that OI Momentum is breakout-first: on range-bound days
 * where no 30M/15M/5M breakout fires, the strategy stays silent for the entire
 * session. This detector catches mean-reversion opportunities at the edges of
 * a tight range with OI confirmation.</p>
 *
 * <p><b>Fire conditions</b> (all required):</p>
 * <ol>
 *   <li>30-min spot range ≤ {@code rangeEdgeFadeRangeMaxPct} (default 0.30%) — confirms "ranging" regime.</li>
 *   <li>Spot's position within the range is in the top {@code rangeEdgeFadeEdgePct}
 *       (default 20%) — fade DOWN with PE — OR bottom {@code rangeEdgeFadeEdgePct} —
 *       fade UP with CE.</li>
 *   <li>5-min OI buildup on the trapping side ≥ {@code rangeEdgeFadeOiBuildMin}
 *       (default 3000 contracts) — confirms operators positioning for reversal.</li>
 *   <li>PCR direction agrees with the fade direction.</li>
 * </ol>
 *
 * <p><b>Replay performance</b> (12 days × NIFTY+SENSEX): 163 fires (~13.6/day),
 * 53.8% 30-min win rate, 56.5% 60-min win rate, median +0.028% per trade.
 * Specifically fires on the quiet/range-bound days where current OI Momentum is silent.</p>
 *
 * <p>Theta-decay check (when enabled in config) further filters by computing the
 * expected theta cost over the expected 45-min hold time vs the expected gain.</p>
 */
@Component
public class RangeEdgeFadeDetector {

    private static final Logger log = LoggerFactory.getLogger(RangeEdgeFadeDetector.class);

    /** Expected hold time for range-edge fade trades (minutes) — used for theta calculation. */
    private static final double EXPECTED_HOLD_MIN = 45.0;
    /** Expected target gain on a successful range-edge fade (%) — used as theta-cost reference. */
    private static final double EXPECTED_GAIN_PCT = 25.0;
    /** Approximate trading minutes per day for theta normalization. */
    private static final double TRADING_MINUTES_PER_DAY = 375.0;

    private final TickMomentumDetector momentumDetector;
    private final LiveInstrumentCache liveInstrumentCache;

    /**
     * V3 market context — provides the latest chain snapshot for theta lookup.
     * Optional: when unavailable, the theta-decay check is gracefully bypassed.
     */
    @Autowired(required = false)
    private MarketContextService marketContext;

    public RangeEdgeFadeDetector(TickMomentumDetector momentumDetector,
                                  LiveInstrumentCache liveInstrumentCache) {
        this.momentumDetector = momentumDetector;
        this.liveInstrumentCache = liveInstrumentCache;
    }

    /** Result of a range-edge fade evaluation. */
    public record Decision(
            boolean fires,
            int direction,                // +1 fade-up with CE; -1 fade-down with PE
            double positionInRange,       // 0..1 where in 30M range spot sits
            double range30mPct,           // 30-min range as % of spot
            double pcr,
            long trappedOiBuild,          // CE OI for PE fade, PE OI for CE fade
            double thetaCostPct,          // expected theta cost over hold, % of premium
            String reason
    ) {
        public static Decision skip(String reason) {
            return new Decision(false, 0, 0, 0, 0, 0, 0, reason);
        }
        public static Decision fire(int dir, double pos, double rng, double pcr,
                                    long oiBuild, double thetaCost) {
            return new Decision(true, dir, pos, rng, pcr, oiBuild, thetaCost, "ok");
        }
    }

    /**
     * Evaluate range-edge fade conditions for the given index.
     *
     * @param index  index to evaluate
     * @param config current OIMomentumConfig (thresholds + enable flag)
     * @return Decision (may be a skip; never null)
     */
    public Decision evaluate(IndexType index, OIMomentumConfig config) {
        if (config == null || !config.isRangeEdgeFadeEnabled()) {
            return Decision.skip("disabled");
        }
        // 1. Compute 30-min range
        double high30 = momentumDetector.getRollingHighInWindow(index, 30);
        double low30 = momentumDetector.getRollingLowInWindow(index, 30);
        double spot = momentumDetector.getSpot(index);
        if (high30 <= 0 || low30 <= 0 || spot <= 0 || high30 <= low30) {
            return Decision.skip("range_unavailable");
        }
        double range30Pct = (high30 - low30) / spot * 100.0;
        if (range30Pct > config.getRangeEdgeFadeRangeMaxPct()) {
            return Decision.skip(String.format("not_ranging_30m=%.3f%%", range30Pct));
        }

        // 2. Position within the range
        double positionInRange = (spot - low30) / (high30 - low30);
        double edgeBand = config.getRangeEdgeFadeEdgePct();
        int direction = 0;
        if (positionInRange >= (1.0 - edgeBand)) {
            direction = -1;   // top of range → fade DOWN with PE
        } else if (positionInRange <= edgeBand) {
            direction = +1;   // bottom of range → fade UP with CE
        } else {
            return Decision.skip(String.format("mid_range_pos=%.3f", positionInRange));
        }

        // 3. OI confirmation on the trapping side
        int atm = index.roundToATM(spot);
        long[] oiChg = liveInstrumentCache.getAtmOiChange(index, atm, 3, 3);
        long ceOiBuild = oiChg[0]; // CE OI change in last window
        long peOiBuild = oiChg[1]; // PE OI change in last window
        long requiredOi = config.getRangeEdgeFadeOiBuildMin();
        long trappedBuild;
        if (direction == -1) {
            // Fading down → CE writers piling on at top resistance
            trappedBuild = ceOiBuild;
            if (ceOiBuild < requiredOi) {
                return Decision.skip(String.format("ce_oi_build_below_%d=%d", requiredOi, ceOiBuild));
            }
        } else {
            // Fading up → PE writers piling on at bottom support
            trappedBuild = peOiBuild;
            if (peOiBuild < requiredOi) {
                return Decision.skip(String.format("pe_oi_build_below_%d=%d", requiredOi, peOiBuild));
            }
        }

        // 4. PCR direction agrees
        double pcr = liveInstrumentCache.getRealtimePcr(index);
        if (direction == -1 && pcr >= 1.0) {
            return Decision.skip(String.format("pcr_disagrees_fade_down_pcr=%.2f", pcr));
        }
        if (direction == +1 && pcr <= 1.0) {
            return Decision.skip(String.format("pcr_disagrees_fade_up_pcr=%.2f", pcr));
        }

        // 5. Theta-decay check (optional, on by default)
        double thetaCostPct = computeThetaCostPct(index, atm,
                direction > 0 ? OptionType.CE : OptionType.PE);
        if (config.isThetaDecayCheckEnabled() && thetaCostPct > 0) {
            double allowedCostPct = config.getThetaDecayMaxCostPct() * EXPECTED_GAIN_PCT / 100.0;
            if (thetaCostPct > allowedCostPct) {
                return Decision.skip(String.format(
                        "theta_too_high=%.2f%%>allowed=%.2f%% (over %.0fmin hold)",
                        thetaCostPct, allowedCostPct, EXPECTED_HOLD_MIN));
            }
        }

        if (log.isInfoEnabled()) {
            log.info("[RANGE_EDGE_FADE][{}] FIRE dir={} pos={} range30m={}% pcr={} ce_build={} pe_build={} theta_cost={}%",
                    index, direction,
                    String.format("%.3f", positionInRange),
                    String.format("%.3f", range30Pct),
                    String.format("%.2f", pcr), ceOiBuild, peOiBuild,
                    String.format("%.2f", thetaCostPct));
        }
        return Decision.fire(direction, positionInRange, range30Pct, pcr, trappedBuild, thetaCostPct);
    }

    /**
     * Expected theta cost as % of premium over {@link #EXPECTED_HOLD_MIN} minutes.
     * Uses the latest chain snapshot from V3's MarketContextService (per-strike
     * theta is captured in ChainSnapshot.StrikeData). Returns 0 (i.e. "check
     * bypassed") if the snapshot, strike, or theta is unavailable.
     */
    private double computeThetaCostPct(IndexType ix, int strike, OptionType type) {
        if (marketContext == null) return 0;
        try {
            ChainSnapshot snap = marketContext.getLatestSnapshot(ix);
            if (snap == null) return 0;
            ChainSnapshot.StrikeData strikeData = null;
            for (ChainSnapshot.StrikeData s : snap.strikes()) {
                if (s.strike() == strike) { strikeData = s; break; }
            }
            if (strikeData == null) return 0;
            double theta = (type == OptionType.CE) ? strikeData.ceTheta() : strikeData.peTheta();
            double ltp = (type == OptionType.CE) ? strikeData.ceLTP() : strikeData.peLTP();
            if (theta == 0 || ltp <= 0) return 0;
            double thetaPerMin = Math.abs(theta) / TRADING_MINUTES_PER_DAY;
            return thetaPerMin * EXPECTED_HOLD_MIN / ltp * 100.0;
        } catch (Throwable t) {
            log.debug("[RANGE_EDGE_FADE][{}] theta lookup failed: {}", ix, t.getMessage());
            return 0;
        }
    }
}
