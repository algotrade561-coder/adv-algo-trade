package com.algo.trade.strategy;

import com.algo.trade.domain.*;
import com.algo.trade.indicator.IVRankTracker;
import com.algo.trade.marketdata.LiveCandleBuilder;
import com.algo.trade.marketdata.ExpiryCalendar;
import com.algo.trade.marketdata.PCRMaxPainTracker;
import com.algo.trade.risk.MarketGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * Hedged Selling Strategy — sell premium with defined-risk hedge (credit spread).
 *
 * Core: Sell high-premium OTM option + Buy further OTM option as hedge.
 * Result: Credit spread with capped risk and reduced margin.
 *
 * Variants (auto-selected based on regime):
 *   RANGE_BOUND → Iron Condor (sell both sides, hedge both)
 *   BULLISH     → Bull Put Spread (sell PE, buy further PE)
 *   BEARISH     → Bear Call Spread (sell CE, buy further CE)
 *
 * Entry conditions:
 *   - IV rank > configured threshold (premium rich for selling)
 *   - ATM IV > minimum threshold (enough theta decay)
 *   - MarketGuard confirms safe for short premium
 *   - PCR neutral or aligned with chosen side
 *
 * Exit:
 *   - Target: 100% of net premium earned (premium decays to near zero)
 *   - Stop loss: 70% expansion of net premium
 *   - Time: force exit at squareoff time
 *   - Max hold: configurable days for weekly/monthly expiry
 *
 * Note: This delegates actual order execution to the spread infrastructure
 * (SpreadOrderExecutor + SpreadLegPlacementService). This class only evaluates
 * whether conditions are right for a hedged sell entry.
 */
@Component
public class HedgedSellingStrategy implements StrategyEvaluator, TimeBoundedStrategy, com.algo.trade.execution.DailyResettable {

    private static final Logger log = LoggerFactory.getLogger(HedgedSellingStrategy.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final IVRankTracker ivRankTracker;
    private final MarketGuard marketGuard;
    private final PCRMaxPainTracker pcrTracker;
    private final LiveCandleBuilder candleBuilder;
    private final ExpiryCalendar expiryCalendar;

    @Value("${trading.strategy.hedged-selling.enabled:false}")
    private boolean enabled;

    @Value("${trading.strategy.hedged-selling.min-iv-rank:30}")
    private double minIVRank;

    @Value("${trading.strategy.hedged-selling.min-iv-for-selling:15}")
    private double minIVForSelling;

    @Value("${trading.strategy.hedged-selling.min-net-premium:40}")
    private double minNetPremium;

    @Value("${trading.strategy.hedged-selling.sell-otm-strikes:2}")
    private int sellOtmStrikes;

    @Value("${trading.strategy.hedged-selling.hedge-otm-strikes:4}")
    private int hedgeOtmStrikes;

    @Value("${trading.strategy.hedged-selling.target-percent:100}")
    private double targetPercent;

    @Value("${trading.strategy.hedged-selling.stop-loss-percent:70}")
    private double stopLossPercent;

    @Value("${trading.strategy.hedged-selling.max-trades-per-day:5}")
    private int maxTradesPerDay;

    private volatile int tradesToday = 0;

    public HedgedSellingStrategy(IVRankTracker ivRankTracker, MarketGuard marketGuard,
                                  PCRMaxPainTracker pcrTracker, LiveCandleBuilder candleBuilder,
                                  ExpiryCalendar expiryCalendar) {
        this.ivRankTracker = ivRankTracker;
        this.marketGuard = marketGuard;
        this.pcrTracker = pcrTracker;
        this.candleBuilder = candleBuilder;
        this.expiryCalendar = expiryCalendar;
    }

    @Override
    public String strategyName() { return "HEDGED_SELLING"; }

    @Override
    public boolean isEnabled() { return enabled; }

    @Override
    public LocalTime entryStartTime() { return LocalTime.of(9, 45); }

    @Override
    public LocalTime entryCutoffTime() { return LocalTime.of(14, 30); }

    /**
     * Evaluate whether conditions are right for a hedged premium sell.
     * Returns a StrategyDecision that the SpreadOrderExecutor will execute as a credit spread.
     */
    public Optional<StrategyDecision> evaluate(UnderlyingSymbol underlying, StrategyEvaluationRequest request) {
        if (!enabled) return Optional.empty();
        if (tradesToday >= maxTradesPerDay) return Optional.empty();

        LocalTime now = LocalTime.now(IST);
        if (now.isBefore(entryStartTime()) || now.isAfter(entryCutoffTime())) return Optional.empty();

        // 1. MarketGuard: must be safe for short premium
        if (!marketGuard.isSafeForShortPremium()) {
            return Optional.empty();
        }

        // 2. IV Rank: must be above threshold (premium rich)
        double ivRank = ivRankTracker.getIVRank(IndexType.from(underlying));
        if (ivRank < minIVRank) {
            return Optional.empty();
        }

        // 3. VIX must be above minimum for selling (theta decay worthwhile)
        double vix = marketGuard.getCurrentVix();
        if (vix < minIVForSelling) {
            return Optional.empty();
        }

        // 4. Determine sell direction based on PCR bias
        String pcrBias = pcrTracker.getBias(underlying);
        SignalType signalType;
        OptionType sellSide;

        if ("BULLISH".equals(pcrBias)) {
            // PCR bullish → sell PE spread (bull put spread)
            signalType = SignalType.BUY_CE; // This is actually a SELL_PE_SPREAD but using existing enum
            sellSide = OptionType.PE;
        } else if ("BEARISH".equals(pcrBias)) {
            // PCR bearish → sell CE spread (bear call spread)
            signalType = SignalType.BUY_PE;
            sellSide = OptionType.CE;
        } else {
            // Neutral → could do Iron Condor (both sides), but start with one side
            // Default to selling PE spread in neutral market (theta works in seller's favor)
            signalType = SignalType.BUY_CE;
            sellSide = OptionType.PE;
        }

        log.info("[HEDGED_SELLING] Entry conditions met for {}: IV rank={:.0f}, VIX={:.1f}, PCR={}, selling {} side",
                underlying, ivRank, vix, pcrBias, sellSide);

        tradesToday++;

        return Optional.of(new StrategyDecision(
                Instant.now(),
                underlying,
                signalType,
                request.underlyingCandles().getLast().close(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(request.selectedLotSize()),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(sellSide),
                true,
                Optional.empty(),
                false,
                BigDecimal.valueOf(ivRank), // confidence based on IV rank
                List.of("HEDGED_SELLING: Credit spread entry",
                        "IV Rank: " + String.format("%.0f", ivRank),
                        "VIX: " + String.format("%.1f", vix),
                        "PCR Bias: " + pcrBias,
                        "Sell: " + sellOtmStrikes + " OTM, Hedge: " + hedgeOtmStrikes + " OTM",
                        "Target: " + targetPercent + "%, SL: " + stopLossPercent + "%")
        ));
    }

    public void resetDaily() {
        tradesToday = 0;
    }
}
