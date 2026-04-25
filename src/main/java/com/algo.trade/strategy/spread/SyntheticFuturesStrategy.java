package com.algo.trade.strategy.spread;

import com.algo.trade.domain.Candle;
import com.algo.trade.domain.IndexType;
import com.algo.trade.domain.OptionType;
import com.algo.trade.domain.OrderSide;
import com.algo.trade.domain.PositionGroup;
import com.algo.trade.domain.SpreadEvaluationContext;
import com.algo.trade.domain.SpreadLeg;
import com.algo.trade.domain.StrategyDecision;
import com.algo.trade.execution.ExecutionEngine;
import com.algo.trade.indicator.AtrIndicator;
import com.algo.trade.indicator.EmaIndicator;
import com.algo.trade.marketdata.ExpiryCalendar;
import com.algo.trade.marketdata.InstrumentCache;
import com.algo.trade.marketdata.MarketDataService;
import com.algo.trade.strategy.StrategyConfig;
import com.algo.trade.strategy.StrategySignalCsvRecorder;
import com.algo.trade.strategy.StrategyType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Synthetic Futures — replicate futures with ATM CE + PE options.
 * Synthetic long: BUY ATM CE + SELL ATM PE.
 * Synthetic short: SELL ATM CE + BUY ATM PE.
 * Entry: EMA-9 crosses above/below EMA-21 on 5-min candles.
 * Exit: target points, SL points, expiry danger zone, or square-off time.
 */
@Component
public class SyntheticFuturesStrategy extends AbstractSpreadStrategy {

    private static final MathContext MC = MathContext.DECIMAL64;
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private List<Candle> candles;
    private boolean syntheticLong = true;

    public SyntheticFuturesStrategy(ExpiryCalendar expiryCalendar,
                                    InstrumentCache instrumentCache,
                                    MarketDataService marketDataService,
                                    ExecutionEngine executionEngine,
                                    StrategySignalCsvRecorder signalRecorder,
                                    EmaIndicator emaIndicator,
                                    AtrIndicator atrIndicator) {
        super(expiryCalendar, instrumentCache, marketDataService,
              executionEngine, signalRecorder, emaIndicator, atrIndicator);
    }

    /**
     * Evaluate with 5-min candle data for EMA crossover detection.
     */
    public Optional<StrategyDecision> evaluate(SpreadEvaluationContext ctx, List<Candle> fiveMinCandles) {
        this.candles = fiveMinCandles;
        return evaluateAndEnter(ctx);
    }

    @Override
    protected boolean shouldEnter(SpreadEvaluationContext ctx) {
        if (candles == null || candles.size() < 22) {
            log.debug("SyntheticFutures: insufficient candles for EMA crossover detection");
            return false;
        }

        List<BigDecimal> closes = candles.stream()
                .map(Candle::close)
                .collect(Collectors.toList());

        // Current EMA values
        BigDecimal ema9Current = emaIndicator.calculate(closes, 9);
        BigDecimal ema21Current = emaIndicator.calculate(closes, 21);

        // Previous EMA values (exclude last candle)
        List<BigDecimal> prevCloses = closes.subList(0, closes.size() - 1);
        BigDecimal ema9Prev = emaIndicator.calculate(prevCloses, 9);
        BigDecimal ema21Prev = emaIndicator.calculate(prevCloses, 21);

        // Detect crossover
        boolean crossAbove = ema9Prev.compareTo(ema21Prev) <= 0 && ema9Current.compareTo(ema21Current) > 0;
        boolean crossBelow = ema9Prev.compareTo(ema21Prev) >= 0 && ema9Current.compareTo(ema21Current) < 0;

        if (crossAbove) {
            syntheticLong = true;
            log.debug("SyntheticFutures: EMA9 crossed above EMA21 — synthetic long signal");
            return true;
        }
        if (crossBelow) {
            syntheticLong = false;
            log.debug("SyntheticFutures: EMA9 crossed below EMA21 — synthetic short signal");
            return true;
        }

        log.debug("SyntheticFutures: no EMA crossover detected");
        return false;
    }

    @Override
    protected List<SpreadLeg> constructLegs(SpreadEvaluationContext ctx) {
        IndexType indexType = ctx.indexType();
        int atm = computeATMStrike(ctx.underlyingPrice(), indexType);
        LocalDate expiry = currentWeeklyExpiry(indexType);
        int qty = ctx.config().getLots() * indexType.lotSize();

        String ceKey = instrumentCache.findOption(ctx.underlying(), expiry,
                BigDecimal.valueOf(atm), OptionType.CE)
                .map(i -> i.instrumentKey()).orElse(null);
        String peKey = instrumentCache.findOption(ctx.underlying(), expiry,
                BigDecimal.valueOf(atm), OptionType.PE)
                .map(i -> i.instrumentKey()).orElse(null);

        if (ceKey == null || peKey == null) {
            log.warn("SyntheticFutures: could not find CE or PE instruments for ATM={}", atm);
            return List.of();
        }

        if (syntheticLong) {
            return List.of(
                    new SpreadLeg(ceKey, atm, OptionType.CE, OrderSide.BUY, qty, expiry),
                    new SpreadLeg(peKey, atm, OptionType.PE, OrderSide.SELL, qty, expiry)
            );
        } else {
            return List.of(
                    new SpreadLeg(ceKey, atm, OptionType.CE, OrderSide.SELL, qty, expiry),
                    new SpreadLeg(peKey, atm, OptionType.PE, OrderSide.BUY, qty, expiry)
            );
        }
    }

    @Override
    protected boolean shouldExit(PositionGroup group, Map<String, BigDecimal> currentPrices) {
        StrategyConfig config = new StrategyConfig(strategyType());
        BigDecimal entryNet = netDebit(group.legs(), group.entryPrices());
        BigDecimal currentNet = netDebit(group.legs(), currentPrices);

        // Target and SL based on points (using SL% and target% as point proxies)
        if (slHit(entryNet, currentNet, config.getStopLossPercent())) {
            log.info("SyntheticFutures: SL hit for group {}", group.groupId());
            return true;
        }
        if (targetHit(entryNet, currentNet, config.getTargetPercent())) {
            log.info("SyntheticFutures: target hit for group {}", group.groupId());
            return true;
        }

        // Expiry danger zone
        IndexType indexType = IndexType.from(group.underlying());
        if (expiryCalendar.isExpiryDangerZone(indexType)) {
            log.info("SyntheticFutures: expiry danger zone for group {}", group.groupId());
            return true;
        }

        // Square-off time check
        LocalTime now = LocalTime.now(IST);
        LocalTime squareOff = LocalTime.of(config.getSquareoffHour(), config.getSquareoffMinute());
        if (now.isAfter(squareOff)) {
            log.info("SyntheticFutures: square-off time reached for group {}", group.groupId());
            return true;
        }

        return false;
    }

    @Override
    protected StrategyType strategyType() {
        return StrategyType.SYNTHETIC_FUTURES;
    }
}
