package com.algo.trade.strategy;

import com.algo.trade.domain.*;
import com.algo.trade.marketdata.LiveCandleBuilder;
import com.algo.trade.marketdata.PCRMaxPainTracker;
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
 * Hedge Mode Strategy — enters both sides when signals conflict.
 *
 * Activation: OI bias and PCR disagree, OR momentum direction is unclear.
 * Entry: Buy ATM CE + Buy ATM PE simultaneously (straddle-like).
 * Resolution: Once one side gains >15% and other side loses → close losing leg,
 *             converting to a directional trade with the winning leg.
 * Exit: Per-leg SL (20%), time cutoff (30 min), or EOD.
 *
 * This ensures no missed trades when direction is uncertain.
 * The losing leg acts as a hedge cost — kept small through tight SL.
 *
 * Note: In adv-algo-trade's architecture, this creates two StrategyDecisions
 * (one for CE, one for PE) that get executed as a position group.
 */
@Component
public class HedgeModeStrategy implements StrategyEvaluator, TimeBoundedStrategy, com.algo.trade.execution.DailyResettable {

    private static final Logger log = LoggerFactory.getLogger(HedgeModeStrategy.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final LiveCandleBuilder candleBuilder;
    private final PCRMaxPainTracker pcrTracker;

    @Value("${trading.strategy.hedge-mode.enabled:true}")
    private boolean enabled;

    @Value("${trading.strategy.hedge-mode.resolve-threshold-percent:15}")
    private double resolveThreshold;

    @Value("${trading.strategy.hedge-mode.stop-loss-percent:20}")
    private double stopLossPercent;

    @Value("${trading.strategy.hedge-mode.max-hold-minutes:30}")
    private int maxHoldMinutes;

    @Value("${trading.strategy.hedge-mode.max-trades-per-day:5}")
    private int maxTradesPerDay;

    private volatile int tradesToday = 0;

    public HedgeModeStrategy(LiveCandleBuilder candleBuilder, PCRMaxPainTracker pcrTracker) {
        this.candleBuilder = candleBuilder;
        this.pcrTracker = pcrTracker;
    }

    @Override
    public String strategyName() { return "HEDGE_MODE"; }

    @Override
    public boolean isEnabled() { return enabled; }

    @Override
    public LocalTime entryStartTime() { return LocalTime.of(9, 30); }

    @Override
    public LocalTime entryCutoffTime() { return LocalTime.of(14, 0); }

    /**
     * Evaluate whether conflicting signals warrant a hedge entry.
     * Returns a CE buy decision — the execution pipeline should also place a PE buy
     * when this strategy's signal has confidence < 50 (indicates hedge mode).
     */
    public Optional<StrategyDecision> evaluate(UnderlyingSymbol underlying, StrategyEvaluationRequest request) {
        if (!enabled) return Optional.empty();
        if (tradesToday >= maxTradesPerDay) return Optional.empty();

        LocalTime now = LocalTime.now(IST);
        if (now.isBefore(entryStartTime()) || now.isAfter(entryCutoffTime())) return Optional.empty();

        // Detect conflicting signals
        long token = resolveToken(underlying);
        List<Candle> candles = candleBuilder.getHistory(token, Timeframe.FIVE_MINUTE);
        if (candles.size() < 10) return Optional.empty();

        // Check: EMA says one direction, PCR says another
        double ema9 = candleBuilder.calculateEMA(token, Timeframe.FIVE_MINUTE, 9);
        double ema21 = candleBuilder.calculateEMA(token, Timeframe.FIVE_MINUTE, 21);
        if (ema9 == 0 || ema21 == 0) return Optional.empty();

        boolean emaBullish = ema9 > ema21;
        boolean pcrBullish = pcrTracker.isBullish(underlying);
        boolean pcrBearish = pcrTracker.isBearish(underlying);

        // Conflict detection: EMA and PCR disagree
        boolean isConflicting = (emaBullish && pcrBearish) || (!emaBullish && pcrBullish);

        if (!isConflicting) return Optional.empty(); // No conflict — let directional strategies handle

        // Additional filter: there should be meaningful movement (not flat)
        Candle latest = candles.get(candles.size() - 1);
        Candle earlier = candles.get(candles.size() - 5);
        double movePct = Math.abs((latest.close().doubleValue() - earlier.close().doubleValue())
                / earlier.close().doubleValue()) * 100;
        if (movePct < 0.1) return Optional.empty(); // Too flat — no point hedging

        log.info("[HEDGE_MODE] Conflicting signals on {}: EMA={}, PCR={} — entering hedge",
                underlying, emaBullish ? "BULL" : "BEAR", pcrBullish ? "BULL" : (pcrBearish ? "BEAR" : "NEUTRAL"));

        tradesToday++;

        // Signal with low confidence (30) to indicate this is a hedge entry
        // The execution pipeline uses this to understand it's a dual-side trade
        return Optional.of(new StrategyDecision(
                Instant.now(),
                underlying,
                SignalType.BUY_CE, // Start with CE, PE follows as part of hedge group
                request.underlyingCandles().getLast().close(),
                Optional.of(request.selectedOptionQuote().lastPrice()),
                Optional.empty(),
                Optional.of(request.selectedLotSize()),
                Optional.empty(),
                Optional.of(request.selectedInstrumentKey()),
                Optional.of(request.selectedStrike()),
                Optional.of(OptionType.CE),
                true,
                Optional.empty(),
                false,
                BigDecimal.valueOf(30), // Low confidence = hedge mode indicator
                List.of("HEDGE_MODE: Conflicting signals — entering both sides",
                        "EMA direction: " + (emaBullish ? "BULLISH" : "BEARISH"),
                        "PCR direction: " + (pcrBullish ? "BULLISH" : (pcrBearish ? "BEARISH" : "NEUTRAL")),
                        "Resolve threshold: " + resolveThreshold + "%",
                        "Max hold: " + maxHoldMinutes + " min",
                        "Per-leg SL: " + stopLossPercent + "%")
        ));
    }

    public void resetDaily() {
        tradesToday = 0;
    }

    private long resolveToken(UnderlyingSymbol underlying) {
        return switch (underlying) {
            case BANKNIFTY -> 260105L;
            case SENSEX -> 265L;
            default -> 256265L;
        };
    }
}
