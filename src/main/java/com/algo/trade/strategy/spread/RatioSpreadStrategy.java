package com.algo.trade.strategy.spread;

import com.algo.trade.domain.*;
import com.algo.trade.marketdata.ExpiryCalendar;
import com.algo.trade.marketdata.LiveCandleBuilder;
import com.algo.trade.marketdata.LiveInstrumentCache;
import com.algo.trade.marketdata.MarketDataService;
import com.algo.trade.marketdata.PCRMaxPainTracker;
import com.algo.trade.risk.MarketGuard;
import com.algo.trade.strategy.MarketOpenAnalyser;
import com.algo.trade.strategy.StrategyEvaluator;
import com.algo.trade.strategy.StrategyEvaluationRequest;
import com.algo.trade.strategy.TimeBoundedStrategy;
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
 * Ratio Spread Strategy — Buy 1 ATM CE + Sell 2 OTM CE (or PE equivalent).
 *
 * Structure:
 *   Buy 1 ATM (or slightly OTM)
 *   Sell 2 OTM (N strikes above/below)
 *
 * Characteristics:
 * - Zero or negative net cost (credit received or breakeven)
 * - Max profit: at the short strike on expiry
 * - Best used: mildly bullish/bearish market, expecting moderate move
 *
 * Entry signal: 15m EMA bullish crossover + PCR > 1.0 (bullish bias)
 *
 * Implements StrategyEvaluator for candle-driven execution pipeline.
 */
@Component
public class RatioSpreadStrategy implements StrategyEvaluator, TimeBoundedStrategy {

    private static final Logger log = LoggerFactory.getLogger(RatioSpreadStrategy.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final LiveCandleBuilder candleBuilder;
    private final LiveInstrumentCache instrumentCache;
    private final ExpiryCalendar expiryCalendar;
    private final MarketDataService marketDataService;
    private final PCRMaxPainTracker pcrTracker;
    private final MarketGuard marketGuard;
    private final MarketOpenAnalyser marketOpenAnalyser;

    @Value("${strategy.ratio-spread.enabled:false}") private boolean enabled;
    @Value("${strategy.ratio-spread.index:NIFTY}") private String indexName;
    @Value("${strategy.ratio-spread.option-type:CE}") private String optionType;
    @Value("${strategy.ratio-spread.buy-strikes:0}") private int buyStrikes;
    @Value("${strategy.ratio-spread.sell-strikes:2}") private int sellStrikes;

    public RatioSpreadStrategy(LiveCandleBuilder candleBuilder,
                                LiveInstrumentCache instrumentCache,
                                ExpiryCalendar expiryCalendar,
                                MarketDataService marketDataService,
                                PCRMaxPainTracker pcrTracker,
                                MarketGuard marketGuard,
                                MarketOpenAnalyser marketOpenAnalyser) {
        this.candleBuilder = candleBuilder;
        this.instrumentCache = instrumentCache;
        this.expiryCalendar = expiryCalendar;
        this.marketDataService = marketDataService;
        this.pcrTracker = pcrTracker;
        this.marketGuard = marketGuard;
        this.marketOpenAnalyser = marketOpenAnalyser;
    }

    @Override
    public String strategyName() { return "RATIO_SPREAD_" + indexName + "_" + optionType; }

    @Override
    public boolean isEnabled() { return enabled; }

    @Override
    public LocalTime entryStartTime() { return LocalTime.of(9, 45); }

    @Override
    public LocalTime entryCutoffTime() { return LocalTime.of(14, 30); }

    public Optional<StrategyDecision> evaluate(UnderlyingSymbol underlying, StrategyEvaluationRequest request) {
        if (!enabled) return Optional.empty();

        IndexType indexType = getIndexType();
        if (indexType == null) return Optional.empty();

        // Only evaluate for the configured index
        if (!underlying.name().equalsIgnoreCase(indexName)) return Optional.empty();

        LocalTime now = LocalTime.now(IST);
        if (now.isBefore(entryStartTime()) || now.isAfter(entryCutoffTime())) return Optional.empty();

        if (!marketOpenAnalyser.isPostOpenWindow()) return Optional.empty();

        long token = resolveToken(indexType);
        List<Candle> candles15m = candleBuilder.getHistory(token, Timeframe.FIFTEEN_MINUTE);
        if (candles15m.size() < 22) return Optional.empty();

        // EMA crossover detection
        double ema9 = calculateEMA(candles15m, 9);
        double ema21 = calculateEMA(candles15m, 21);
        double prevEma9 = calculateEMA(candles15m.subList(0, candles15m.size() - 1), 9);
        double prevEma21 = calculateEMA(candles15m.subList(0, candles15m.size() - 1), 21);

        double pcr = pcrTracker.getPCR(underlying);
        boolean entry = false;
        String direction;

        // CE ratio spread: bullish EMA crossover + PCR > 1.0
        if ("CE".equals(optionType) && prevEma9 <= prevEma21 && ema9 > ema21 && pcr > 1.0) {
            entry = true;
            direction = "BULLISH";
        }
        // PE ratio spread: bearish EMA crossover + PCR < 0.8
        else if ("PE".equals(optionType) && prevEma9 >= prevEma21 && ema9 < ema21 && pcr < 0.8) {
            entry = true;
            direction = "BEARISH";
        } else {
            return Optional.empty();
        }

        double spot = instrumentCache.getFuturesPrice(indexType);
        if (spot <= 0) return Optional.empty();

        int atm = indexType.roundToATM(spot);
        int interval = indexType.strikeInterval();
        int buyStrike = "CE".equals(optionType) ? atm + buyStrikes * interval : atm - buyStrikes * interval;
        int sellStrike = "CE".equals(optionType) ? atm + sellStrikes * interval : atm - sellStrikes * interval;

        SignalType signalType = "BULLISH".equals(direction) ? SignalType.BUY_CE : SignalType.BUY_PE;
        OptionType optType = "CE".equals(optionType) ? OptionType.CE : OptionType.PE;

        log.info("[RATIO_SPREAD] Entry signal: {} EMA crossover, PCR={}, buy={}, sell=2x{}",
                direction, String.format("%.2f", pcr), buyStrike, sellStrike);

        StrategyDecision decision = new StrategyDecision(
                Instant.now(), underlying, signalType,
                request.underlyingCandles().getLast().close(), Optional.of(request.selectedOptionQuote().lastPrice()),
                Optional.empty(), Optional.of(request.selectedLotSize()), Optional.empty(),
                Optional.of(request.selectedInstrumentKey()), Optional.of(request.selectedStrike()),
                Optional.of(optType), true, Optional.empty(), false,
                BigDecimal.valueOf(70),
                List.of("RATIO_SPREAD: Buy 1x" + buyStrike + optionType + " Sell 2x" + sellStrike + optionType,
                        "EMA9/21 crossover " + direction + ", PCR=" + String.format("%.2f", pcr),
                        "Structure: 1:2 ratio (credit/breakeven)")
        );
        return Optional.of(decision);
    }

    private double calculateEMA(List<Candle> candles, int period) {
        if (candles.size() < period) return 0;
        double multiplier = 2.0 / (period + 1);
        double ema = candles.get(0).close().doubleValue();
        for (int i = 1; i < candles.size(); i++) {
            ema = (candles.get(i).close().doubleValue() - ema) * multiplier + ema;
        }
        return ema;
    }

    private long resolveToken(IndexType idx) {
        return idx == IndexType.BANKNIFTY ? 260105L : 256265L;
    }

    private IndexType getIndexType() {
        try { return IndexType.valueOf(indexName.toUpperCase()); } catch (Exception e) { return null; }
    }
}
