package com.kiteapioptions.strategy;

import com.kiteapioptions.config.TradingProperties;
import com.kiteapioptions.domain.Candle;
import com.kiteapioptions.domain.OptionType;
import com.kiteapioptions.domain.SignalType;
import com.kiteapioptions.domain.StrategyDecision;
import com.kiteapioptions.indicator.BreakoutDetector;
import com.kiteapioptions.indicator.OiChangeTracker;
import com.kiteapioptions.indicator.VolatilityFilter;
import com.kiteapioptions.indicator.VolumeSpikeDetector;
import com.kiteapioptions.indicator.VwapIndicator;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Rule-based option buying entry strategy. This phase only evaluates signals; it never places orders.
 */
public class RuleBasedOptionsStrategy {

    private static final int BREAKOUT_LOOKBACK = 5;
    private static final int VOLUME_LOOKBACK = 5;
    private static final BigDecimal BULLISH_IMBALANCE_THRESHOLD = BigDecimal.valueOf(1.05);
    private static final BigDecimal BEARISH_IMBALANCE_THRESHOLD = BigDecimal.valueOf(0.95);

    private final TradingProperties properties;
    private final VwapIndicator vwapIndicator;
    private final VolumeSpikeDetector volumeSpikeDetector;
    private final BreakoutDetector breakoutDetector;
    private final VolatilityFilter volatilityFilter;
    private final OiChangeTracker oiChangeTracker;
    private final OptionChainAnalyzer optionChainAnalyzer;

    public RuleBasedOptionsStrategy(
            TradingProperties properties,
            VwapIndicator vwapIndicator,
            VolumeSpikeDetector volumeSpikeDetector,
            BreakoutDetector breakoutDetector,
            VolatilityFilter volatilityFilter,
            OiChangeTracker oiChangeTracker,
            OptionChainAnalyzer optionChainAnalyzer
    ) {
        this.properties = properties;
        this.vwapIndicator = vwapIndicator;
        this.volumeSpikeDetector = volumeSpikeDetector;
        this.breakoutDetector = breakoutDetector;
        this.volatilityFilter = volatilityFilter;
        this.oiChangeTracker = oiChangeTracker;
        this.optionChainAnalyzer = optionChainAnalyzer;
    }

    public StrategyDecision evaluateEntry(StrategyEvaluationRequest request) {
        if (request.underlyingCandles().isEmpty()) {
            return noTrade(request, Optional.empty(), false, "No underlying candles available");
        }
        if (request.selectedOptionCandles().isEmpty()) {
            return noTrade(request, Optional.empty(), false, "No selected option candles available");
        }

        OptionChainAnalysis chain = optionChainAnalyzer.analyze(request.optionChainSnapshot(),
                properties.strike().nearbyStrikes());
        BigDecimal underlyingPrice = request.underlyingCandles().getLast().close();
        BigDecimal vwap = vwapIndicator.calculate(request.underlyingCandles());
        boolean vwapPassed = !properties.entry().vwapFilterEnabled()
                || vwapConditionPassed(request.optionType(), underlyingPrice, vwap);
        boolean breakoutPassed = breakoutPassed(request, chain);
        boolean volumeSpike = volumeSpikeDetector.hasSpike(request.selectedOptionCandles(), VOLUME_LOOKBACK,
                properties.entry().volumeSpikeMultiplier());
        boolean oiPassed = oiConditionPassed(request, chain);
        boolean ivPassed = volatilityFilter.isAcceptable(request.selectedOptionQuote().impliedVolatility(),
                properties.entry().maxIvPercent());
        boolean liquidityPassed = request.selectedOptionQuote().volume() >= properties.entry().minLiquidityVolume();
        boolean timePassed = withinEntryWindow(request.marketTime());

        List<String> reasons = new ArrayList<>();
        addReason(reasons, vwapPassed, "VWAP condition passed", "VWAP condition failed");
        addReason(reasons, breakoutPassed, "Breakout condition passed", "Breakout condition failed");
        addReason(reasons, volumeSpike, "Volume spike confirmed", "Volume spike missing");
        addReason(reasons, oiPassed, "OI behavior supports entry", "OI behavior does not support entry");
        addReason(reasons, ivPassed, "IV filter passed", "IV filter failed");
        addReason(reasons, liquidityPassed, "Liquidity filter passed", "Liquidity filter failed");
        addReason(reasons, timePassed, "Entry time window passed", "Entry time window failed");

        boolean entry = vwapPassed && breakoutPassed && volumeSpike && oiPassed && ivPassed && liquidityPassed && timePassed;
        SignalType signalType = entry
                ? (request.optionType() == OptionType.CE ? SignalType.BUY_CE : SignalType.BUY_PE)
                : SignalType.NO_TRADE;

        return new StrategyDecision(request.timestamp(), request.underlying(), signalType, underlyingPrice,
                Optional.ofNullable(request.selectedInstrumentKey()), Optional.ofNullable(request.selectedStrike()),
                Optional.ofNullable(request.optionType()), vwapPassed, Optional.of(chain.nearbyPutCallOiImbalance()),
                volumeSpike, reasons);
    }

    private boolean breakoutPassed(StrategyEvaluationRequest request, OptionChainAnalysis chain) {
        List<Candle> candles = request.underlyingCandles();
        BigDecimal price = candles.getLast().close();
        BigDecimal buffer = properties.entry().breakoutBufferPercent();
        if (request.optionType() == OptionType.CE) {
            boolean resistanceBreak = chain.resistanceStrike()
                    .map(resistance -> price.compareTo(applyPositiveBuffer(resistance, buffer)) > 0)
                    .orElse(false);
            return resistanceBreak || breakoutDetector.breaksAboveSwingHigh(candles, BREAKOUT_LOOKBACK, buffer);
        }
        boolean supportBreak = chain.supportStrike()
                .map(support -> price.compareTo(applyNegativeBuffer(support, buffer)) < 0)
                .orElse(false);
        return supportBreak || breakoutDetector.breaksBelowSwingLow(candles, BREAKOUT_LOOKBACK, buffer);
    }

    private boolean oiConditionPassed(StrategyEvaluationRequest request, OptionChainAnalysis chain) {
        boolean priceOiBuildUp = request.previousSelectedOptionQuote()
                .map(previous -> request.optionType() == OptionType.CE
                        ? oiChangeTracker.priceAndOiRising(previous, request.selectedOptionQuote())
                        : oiChangeTracker.priceFallingAndOiRising(previous, request.selectedOptionQuote()))
                .orElse(false);
        boolean chainBuildUp = request.optionType() == OptionType.CE
                ? chain.supportPutOiChange() > 0 || chain.resistanceCallOiChange() < 0
                : chain.resistanceCallOiChange() > 0 || chain.supportPutOiChange() < 0;
        boolean imbalanceSupports = request.optionType() == OptionType.CE
                ? chain.nearbyPutCallOiImbalance().compareTo(BULLISH_IMBALANCE_THRESHOLD) >= 0
                : chain.nearbyPutCallOiImbalance().compareTo(BEARISH_IMBALANCE_THRESHOLD) <= 0;
        return priceOiBuildUp || chainBuildUp || imbalanceSupports;
    }

    private boolean vwapConditionPassed(OptionType optionType, BigDecimal price, BigDecimal vwap) {
        return optionType == OptionType.CE ? price.compareTo(vwap) > 0 : price.compareTo(vwap) < 0;
    }

    private boolean withinEntryWindow(LocalTime marketTime) {
        return !marketTime.isBefore(properties.entry().entryStartTime())
                && !marketTime.isAfter(properties.entry().entryCutoffTime());
    }

    private StrategyDecision noTrade(StrategyEvaluationRequest request, Optional<BigDecimal> imbalance,
                                     boolean volumeSpike, String reason) {
        return new StrategyDecision(request.timestamp(), request.underlying(), SignalType.NO_TRADE,
                request.underlyingCandles().isEmpty() ? BigDecimal.ZERO : request.underlyingCandles().getLast().close(),
                Optional.ofNullable(request.selectedInstrumentKey()), Optional.ofNullable(request.selectedStrike()),
                Optional.ofNullable(request.optionType()), false, imbalance, volumeSpike, List.of(reason));
    }

    private void addReason(List<String> reasons, boolean passed, String passReason, String failReason) {
        reasons.add(passed ? passReason : failReason);
    }

    private BigDecimal applyPositiveBuffer(BigDecimal value, BigDecimal bufferPercent) {
        return value.multiply(BigDecimal.ONE.add(bufferPercent.movePointLeft(2)));
    }

    private BigDecimal applyNegativeBuffer(BigDecimal value, BigDecimal bufferPercent) {
        return value.multiply(BigDecimal.ONE.subtract(bufferPercent.movePointLeft(2)));
    }
}
