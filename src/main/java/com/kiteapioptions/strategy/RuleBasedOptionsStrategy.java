package com.kiteapioptions.strategy;

import com.kiteapioptions.config.TradingProperties;
import com.kiteapioptions.domain.Candle;
import com.kiteapioptions.domain.OptionType;
import com.kiteapioptions.domain.SignalType;
import com.kiteapioptions.domain.StrategyDecision;
import com.kiteapioptions.indicator.BreakoutDetector;
import com.kiteapioptions.indicator.EmaIndicator;
import com.kiteapioptions.indicator.OiChangeTracker;
import com.kiteapioptions.indicator.VolatilityFilter;
import com.kiteapioptions.indicator.VolumeSpikeDetector;
import com.kiteapioptions.indicator.VwapIndicator;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Rule-based option buying entry strategy. This phase only evaluates signals; it never places orders.
 */
public class RuleBasedOptionsStrategy {

    private static final Logger log = LoggerFactory.getLogger(RuleBasedOptionsStrategy.class);

    private final TradingProperties properties;
    private final VwapIndicator vwapIndicator;
    private final EmaIndicator emaIndicator;
    private final VolumeSpikeDetector volumeSpikeDetector;
    private final BreakoutDetector breakoutDetector;
    private final VolatilityFilter volatilityFilter;
    private final OiChangeTracker oiChangeTracker;
    private final OptionChainAnalyzer optionChainAnalyzer;
    private final StrategySignalCsvRecorder signalCsvRecorder;

    public RuleBasedOptionsStrategy(
            TradingProperties properties,
            VwapIndicator vwapIndicator,
            EmaIndicator emaIndicator,
            VolumeSpikeDetector volumeSpikeDetector,
            BreakoutDetector breakoutDetector,
            VolatilityFilter volatilityFilter,
            OiChangeTracker oiChangeTracker,
            OptionChainAnalyzer optionChainAnalyzer,
            StrategySignalCsvRecorder signalCsvRecorder
    ) {
        this.properties = properties;
        this.vwapIndicator = vwapIndicator;
        this.emaIndicator = emaIndicator;
        this.volumeSpikeDetector = volumeSpikeDetector;
        this.breakoutDetector = breakoutDetector;
        this.volatilityFilter = volatilityFilter;
        this.oiChangeTracker = oiChangeTracker;
        this.optionChainAnalyzer = optionChainAnalyzer;
        this.signalCsvRecorder = signalCsvRecorder;
    }

    public RuleBasedOptionsStrategy(
            TradingProperties properties,
            VwapIndicator vwapIndicator,
            VolumeSpikeDetector volumeSpikeDetector,
            BreakoutDetector breakoutDetector,
            VolatilityFilter volatilityFilter,
            OiChangeTracker oiChangeTracker,
            OptionChainAnalyzer optionChainAnalyzer
    ) {
        this(properties, vwapIndicator, new EmaIndicator(), volumeSpikeDetector, breakoutDetector, volatilityFilter, oiChangeTracker,
                optionChainAnalyzer, null);
    }

    public StrategyDecision evaluateEntry(StrategyEvaluationRequest request) {
        log.info("Strategy evaluation started: timestamp={}, marketTime={}, underlying={}, optionType={}, selectedInstrument={}, selectedStrike={}, underlyingCandles={}, optionCandles={}",
                request.timestamp(), request.marketTime(), request.underlying(), request.optionType(),
                request.selectedInstrumentKey(), request.selectedStrike(), request.underlyingCandles().size(),
                request.selectedOptionCandles().size());
        if (request.underlyingCandles().isEmpty()) {
            log.warn("Strategy evaluation rejected: no underlying candles available");
            return noTrade(request, Optional.empty(), false, "No underlying candles available");
        }
        if (request.selectedOptionCandles().isEmpty()) {
            log.warn("Strategy evaluation rejected: no selected option candles available");
            return noTrade(request, Optional.empty(), false, "No selected option candles available");
        }

        OptionChainAnalysis chain = optionChainAnalyzer.analyze(request.optionChainSnapshot(),
                properties.strike().nearbyStrikes());
        BigDecimal underlyingPrice = request.underlyingCandles().getLast().close();
        BigDecimal trendReference = trendReference(request.underlyingCandles());
        boolean vwapPassed = !properties.entry().vwapFilterEnabled()
                || vwapConditionPassed(request.optionType(), underlyingPrice, trendReference);
        boolean breakoutPassed = breakoutPassed(request, chain);
        boolean volumeSpike = volumeSpikeDetector.hasSpike(request.selectedOptionCandles(),
                properties.entry().volumeLookback(),
                properties.entry().volumeSpikeMultiplier());
        boolean oiPassed = oiConditionPassed(request, chain);
        boolean ivPassed = volatilityFilter.isAcceptable(request.selectedOptionQuote().impliedVolatility(),
                properties.entry().maxIvPercent());
        boolean liquidityPassed = request.selectedOptionQuote().volume() >= properties.entry().minLiquidityVolume();
        boolean timePassed = withinEntryWindow(request.marketTime());
        BigDecimal confidenceScore = confidenceScore(vwapPassed, breakoutPassed, volumeSpike, oiPassed,
                ivPassed, liquidityPassed);

        List<String> reasons = new ArrayList<>();
        addReason(reasons, vwapPassed, "Trend condition passed", "Trend condition failed");
        addReason(reasons, breakoutPassed, "Breakout condition passed", "Breakout condition failed");
        addReason(reasons, volumeSpike, "Volume spike confirmed", "Volume spike missing");
        addReason(reasons, oiPassed, "OI behavior supports entry", "OI behavior does not support entry");
        addReason(reasons, ivPassed, "IV filter passed", "IV filter failed");
        addReason(reasons, liquidityPassed, "Liquidity filter passed", "Liquidity filter failed");
        addReason(reasons, timePassed, "Entry time window passed", "Entry time window failed");
        addReason(reasons, confidenceScore.compareTo(properties.entry().minSignalScorePercent()) >= 0,
                "Signal score passed: " + confidenceScore + "%",
                "Signal score failed: " + confidenceScore + "%");

        boolean entry = timePassed && ivPassed && liquidityPassed
                && confidenceScore.compareTo(properties.entry().minSignalScorePercent()) >= 0;
        SignalType signalType = entry
                ? (request.optionType() == OptionType.CE ? SignalType.BUY_CE : SignalType.BUY_PE)
                : SignalType.NO_TRADE;

        log.info("Strategy evaluation completed: signalType={}, underlyingPrice={}, trendReference={}, vwapPassed={}, breakoutPassed={}, volumeSpike={}, oiPassed={}, ivPassed={}, liquidityPassed={}, timePassed={}, confidenceScore={}, minSignalScore={}, imbalance={}, reasons={}",
                signalType, underlyingPrice, trendReference, vwapPassed, breakoutPassed, volumeSpike, oiPassed, ivPassed,
                liquidityPassed, timePassed, confidenceScore, properties.entry().minSignalScorePercent(),
                chain.nearbyPutCallOiImbalance(), reasons);
        StrategyDecision decision = new StrategyDecision(request.timestamp(), request.underlying(), signalType, underlyingPrice,
                Optional.ofNullable(request.selectedInstrumentKey()), Optional.ofNullable(request.selectedStrike()),
                Optional.ofNullable(request.optionType()), vwapPassed, Optional.of(chain.nearbyPutCallOiImbalance()),
                volumeSpike, confidenceScore, reasons);
        recordSignal(request, decision, chain, trendReference, breakoutPassed, oiPassed, ivPassed, liquidityPassed, timePassed);
        return decision;
    }

    private boolean breakoutPassed(StrategyEvaluationRequest request, OptionChainAnalysis chain) {
        List<Candle> candles = request.underlyingCandles();
        BigDecimal price = candles.getLast().close();
        BigDecimal buffer = properties.entry().breakoutBufferPercent();
        if (request.optionType() == OptionType.CE) {
            boolean resistanceBreak = chain.resistanceStrike()
                    .map(resistance -> price.compareTo(applyPositiveBuffer(resistance, buffer)) > 0)
                    .orElse(false);
            return resistanceBreak || breakoutDetector.breaksAboveSwingHigh(candles,
                    properties.entry().breakoutLookback(), buffer);
        }
        boolean supportBreak = chain.supportStrike()
                .map(support -> price.compareTo(applyNegativeBuffer(support, buffer)) < 0)
                .orElse(false);
        return supportBreak || breakoutDetector.breaksBelowSwingLow(candles,
                properties.entry().breakoutLookback(), buffer);
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
                ? chain.nearbyPutCallOiImbalance().compareTo(properties.entry().bullishImbalanceThreshold()) >= 0
                : chain.nearbyPutCallOiImbalance().compareTo(properties.entry().bearishImbalanceThreshold()) <= 0;
        return priceOiBuildUp || chainBuildUp || imbalanceSupports;
    }

    private boolean vwapConditionPassed(OptionType optionType, BigDecimal price, BigDecimal vwap) {
        return optionType == OptionType.CE ? price.compareTo(vwap) > 0 : price.compareTo(vwap) < 0;
    }

    private BigDecimal trendReference(List<Candle> candles) {
        boolean hasVolume = candles.stream().anyMatch(candle -> candle.volume() > 0);
        if (hasVolume) {
            return vwapIndicator.calculate(candles);
        }
        List<BigDecimal> closes = candles.stream().map(Candle::close).toList();
        return emaIndicator.calculate(closes, Math.min(properties.entry().breakoutLookback(), closes.size()));
    }

    private boolean withinEntryWindow(LocalTime marketTime) {
        return !marketTime.isBefore(properties.entry().entryStartTime())
                && !marketTime.isAfter(properties.entry().entryCutoffTime());
    }

    private StrategyDecision noTrade(StrategyEvaluationRequest request, Optional<BigDecimal> imbalance,
                                     boolean volumeSpike, String reason) {
        StrategyDecision decision = new StrategyDecision(request.timestamp(), request.underlying(), SignalType.NO_TRADE,
                request.underlyingCandles().isEmpty() ? BigDecimal.ZERO : request.underlyingCandles().getLast().close(),
                Optional.ofNullable(request.selectedInstrumentKey()), Optional.ofNullable(request.selectedStrike()),
                Optional.ofNullable(request.optionType()), false, imbalance, volumeSpike, BigDecimal.ZERO, List.of(reason));
        recordSignal(request, decision, null, BigDecimal.ZERO, false, false, false, false, false);
        return decision;
    }

    private void addReason(List<String> reasons, boolean passed, String passReason, String failReason) {
        reasons.add(passed ? passReason : failReason);
    }

    private BigDecimal confidenceScore(
            boolean vwapPassed,
            boolean breakoutPassed,
            boolean volumeSpike,
            boolean oiPassed,
            boolean ivPassed,
            boolean liquidityPassed
    ) {
        int score = 0;
        score += vwapPassed ? 15 : 0;
        score += breakoutPassed ? 25 : 0;
        score += volumeSpike ? 20 : 0;
        score += oiPassed ? 25 : 0;
        score += liquidityPassed ? 10 : 0;
        score += ivPassed ? 5 : 0;
        return BigDecimal.valueOf(score);
    }

    private BigDecimal applyPositiveBuffer(BigDecimal value, BigDecimal bufferPercent) {
        return value.multiply(BigDecimal.ONE.add(bufferPercent.movePointLeft(2)));
    }

    private BigDecimal applyNegativeBuffer(BigDecimal value, BigDecimal bufferPercent) {
        return value.multiply(BigDecimal.ONE.subtract(bufferPercent.movePointLeft(2)));
    }

    private void recordSignal(
            StrategyEvaluationRequest request,
            StrategyDecision decision,
            OptionChainAnalysis chain,
            BigDecimal vwap,
            boolean breakoutPassed,
            boolean oiPassed,
            boolean ivPassed,
            boolean liquidityPassed,
            boolean timePassed
    ) {
        if (signalCsvRecorder == null) {
            return;
        }
        signalCsvRecorder.record(request, decision, chain, vwap, breakoutPassed, oiPassed, ivPassed, liquidityPassed,
                timePassed);
    }
}
