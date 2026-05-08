package com.algo.trade.strategy;

import com.algo.trade.config.GlobalConfigService;
import com.algo.trade.config.TradingProperties;
import com.algo.trade.domain.Candle;
import com.algo.trade.domain.OptionType;
import com.algo.trade.domain.SignalType;
import com.algo.trade.domain.StrategyDecision;
import com.algo.trade.indicator.BreakoutDetector;
import com.algo.trade.indicator.EmaIndicator;
import com.algo.trade.indicator.OiChangeTracker;
import com.algo.trade.indicator.RsiIndicator;
import com.algo.trade.indicator.VolatilityFilter;
import com.algo.trade.indicator.VolumeSpikeDetector;
import com.algo.trade.indicator.VwapIndicator;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Rule-based option buying entry strategy. This phase only evaluates signals; it never places orders.
 * Reads all entry/exit/risk parameters from GlobalConfigService (DB-backed, runtime-editable)
 * with fallback to TradingProperties (YAML) for backtest/test use.
 */
public class RuleBasedOptionsStrategy {

    private static final Logger log = LoggerFactory.getLogger(RuleBasedOptionsStrategy.class);
    private static final MathContext MATH_CONTEXT = MathContext.DECIMAL64;
    private static final BigDecimal MIN_RESISTANCE_HEADROOM_PERCENT = BigDecimal.valueOf(0.20);

    private final TradingProperties properties;
    private final GlobalConfigService globalConfigService;
    private final VwapIndicator vwapIndicator;
    private final EmaIndicator emaIndicator;
    private final VolumeSpikeDetector volumeSpikeDetector;
    private final BreakoutDetector breakoutDetector;
    private final VolatilityFilter volatilityFilter;
    private final OiChangeTracker oiChangeTracker;
    private final OptionChainAnalyzer optionChainAnalyzer;
    private final StrategySignalCsvRecorder signalCsvRecorder;
    private final RsiIndicator rsiIndicator;
    private final com.algo.trade.underlying.UnderlyingConfigService underlyingConfigService;

    public RuleBasedOptionsStrategy(
            TradingProperties properties,
            GlobalConfigService globalConfigService,
            VwapIndicator vwapIndicator,
            EmaIndicator emaIndicator,
            VolumeSpikeDetector volumeSpikeDetector,
            BreakoutDetector breakoutDetector,
            VolatilityFilter volatilityFilter,
            OiChangeTracker oiChangeTracker,
            OptionChainAnalyzer optionChainAnalyzer,
            StrategySignalCsvRecorder signalCsvRecorder,
            com.algo.trade.underlying.UnderlyingConfigService underlyingConfigService
    ) {
        this.properties = properties;
        this.globalConfigService = globalConfigService;
        this.vwapIndicator = vwapIndicator;
        this.emaIndicator = emaIndicator;
        this.volumeSpikeDetector = volumeSpikeDetector;
        this.breakoutDetector = breakoutDetector;
        this.volatilityFilter = volatilityFilter;
        this.oiChangeTracker = oiChangeTracker;
        this.optionChainAnalyzer = optionChainAnalyzer;
        this.signalCsvRecorder = signalCsvRecorder;
        this.rsiIndicator = new RsiIndicator();
        this.underlyingConfigService = underlyingConfigService;
    }

    /** Backtest/test constructor — no GlobalConfigService, falls back to YAML properties. */
    public RuleBasedOptionsStrategy(
            TradingProperties properties,
            VwapIndicator vwapIndicator,
            VolumeSpikeDetector volumeSpikeDetector,
            BreakoutDetector breakoutDetector,
            VolatilityFilter volatilityFilter,
            OiChangeTracker oiChangeTracker,
            OptionChainAnalyzer optionChainAnalyzer
    ) {
        this(properties, null, vwapIndicator, new EmaIndicator(), volumeSpikeDetector, breakoutDetector, volatilityFilter, oiChangeTracker,
                optionChainAnalyzer, null, null);
    }

    /** Backtest constructor with EmaIndicator and optional signalCsvRecorder — no GlobalConfigService. */
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
        this(properties, null, vwapIndicator, emaIndicator, volumeSpikeDetector, breakoutDetector, volatilityFilter, oiChangeTracker,
                optionChainAnalyzer, signalCsvRecorder, null);
    }

    // ── Config accessors: prefer GlobalConfigService (DB), fallback to YAML ───

    private boolean cfgVwapFilterEnabled() {
        return globalConfigService != null ? globalConfigService.isVwapFilterEnabled() : properties.entry().vwapFilterEnabled();
    }
    private boolean cfgTrendFilterEnabled() {
        return globalConfigService != null ? globalConfigService.isTrendFilterEnabled() : properties.entry().trendFilterEnabled();
    }
    private BigDecimal cfgVolumeSpikeMultiplier() {
        return globalConfigService != null ? globalConfigService.getVolumeSpikeMultiplier() : properties.entry().volumeSpikeMultiplier();
    }
    private BigDecimal cfgBreakoutBufferPercent() {
        return globalConfigService != null ? globalConfigService.getBreakoutBufferPercent() : properties.entry().breakoutBufferPercent();
    }
    private int cfgBreakoutLookback() {
        return globalConfigService != null ? globalConfigService.getBreakoutLookback() : properties.entry().breakoutLookback();
    }
    private int cfgVolumeLookback() {
        return globalConfigService != null ? globalConfigService.getVolumeLookback() : properties.entry().volumeLookback();
    }
    private BigDecimal cfgBullishImbalanceThreshold() {
        return globalConfigService != null ? globalConfigService.getBullishImbalanceThreshold() : properties.entry().bullishImbalanceThreshold();
    }
    private BigDecimal cfgBearishImbalanceThreshold() {
        return globalConfigService != null ? globalConfigService.getBearishImbalanceThreshold() : properties.entry().bearishImbalanceThreshold();
    }
    private long cfgMinLiquidityVolume() {
        return globalConfigService != null ? globalConfigService.getMinLiquidityVolume() : properties.entry().minLiquidityVolume();
    }
    private BigDecimal cfgMaxIvPercent() {
        return globalConfigService != null ? globalConfigService.getMaxIvPercent() : properties.entry().maxIvPercent();
    }
    private BigDecimal cfgMinSignalScorePercent() {
        return globalConfigService != null ? globalConfigService.getMinSignalScorePercent() : properties.entry().minSignalScorePercent();
    }
    private boolean cfgCeOiSupportRequired() {
        return globalConfigService != null ? globalConfigService.isCeOiSupportRequired() : properties.entry().ceOiSupportRequired();
    }
    private boolean cfgPeOiSupportRequired() {
        return globalConfigService != null ? globalConfigService.isPeOiSupportRequired() : properties.entry().peOiSupportRequired();
    }
    private boolean cfgCeOiDivergenceFilterEnabled() {
        return globalConfigService != null ? globalConfigService.isCeOiDivergenceFilterEnabled() : properties.entry().ceOiDivergenceFilterEnabled();
    }
    private boolean cfgPeOiDivergenceFilterEnabled() {
        return globalConfigService != null ? globalConfigService.isPeOiDivergenceFilterEnabled() : properties.entry().peOiDivergenceFilterEnabled();
    }
    private BigDecimal cfgOiDivergenceMultiplier() {
        return globalConfigService != null ? globalConfigService.getOiDivergenceMultiplier() : properties.entry().oiDivergenceMultiplier();
    }
    private long cfgOiDivergenceMinChange() {
        return globalConfigService != null ? globalConfigService.getOiDivergenceMinChange() : properties.entry().oiDivergenceMinChange();
    }
    private int cfgCeBreakoutConfirmationCandles() {
        return globalConfigService != null ? globalConfigService.getCeBreakoutConfirmationCandles() : properties.entry().ceBreakoutConfirmationCandles();
    }
    private int cfgPeBreakoutConfirmationCandles() {
        return globalConfigService != null ? globalConfigService.getPeBreakoutConfirmationCandles() : properties.entry().peBreakoutConfirmationCandles();
    }
    private LocalTime cfgEntryStartTime() {
        return globalConfigService != null ? globalConfigService.getEntryStartTime() : properties.entry().entryStartTime();
    }
    private LocalTime cfgEntryCutoffTime() {
        return globalConfigService != null ? globalConfigService.getEntryCutoffTime() : properties.entry().entryCutoffTime();
    }
    private boolean cfgRsiFilterEnabled() {
        return globalConfigService != null ? globalConfigService.isRsiFilterEnabled() : properties.entry().rsiFilterEnabled();
    }
    private int cfgRsiPeriod() {
        return globalConfigService != null ? globalConfigService.getRsiPeriod() : properties.entry().rsiPeriod();
    }
    private BigDecimal cfgRsiCeBuyThreshold() {
        return globalConfigService != null ? globalConfigService.getRsiCeBuyThreshold() : properties.entry().rsiCeBuyThreshold();
    }
    private BigDecimal cfgRsiPeSellThreshold() {
        return globalConfigService != null ? globalConfigService.getRsiPeSellThreshold() : properties.entry().rsiPeSellThreshold();
    }

    public StrategyDecision evaluateEntry(StrategyEvaluationRequest request) {
        return evaluateEntry(request, true);
    }

    public StrategyDecision evaluateEntryWithoutRecording(StrategyEvaluationRequest request) {
        return evaluateEntry(request, false);
    }

    private StrategyDecision evaluateEntry(StrategyEvaluationRequest request, boolean recordSignal) {
        log.info("Strategy evaluation started: timestamp={}, marketTime={}, underlying={}, optionType={}, selectedInstrument={}, selectedStrike={}, underlyingCandles={}, optionCandles={}",
                request.timestamp(), request.marketTime(), request.underlying(), request.optionType(),
                request.selectedInstrumentKey(), request.selectedStrike(), request.underlyingCandles().size(),
                request.selectedOptionCandles().size());
        if (request.underlyingCandles().isEmpty()) {
            log.warn("Strategy evaluation rejected: no underlying candles available");
            return noTrade(request, Optional.empty(), false, "No underlying candles available", recordSignal);
        }
        if (request.selectedOptionCandles().isEmpty()) {
            log.warn("Strategy evaluation rejected: no selected option candles available");
            return noTrade(request, Optional.empty(), false, "No selected option candles available", recordSignal);
        }

        OptionChainAnalysis chain = optionChainAnalyzer.analyze(request.optionChainSnapshot(),
                properties.strike().nearbyStrikes());
        BigDecimal underlyingPrice = request.underlyingCandles().getLast().close();
        BigDecimal optionPrice = request.selectedOptionQuote().lastPrice();
        long optionOpenInterest = request.selectedOptionQuote().openInterest();
        int lotSize = request.selectedLotSize();
        BigDecimal lotPrice = optionPrice.multiply(BigDecimal.valueOf(lotSize), MATH_CONTEXT);
        List<Candle> trendCandles = trendCandles(request);
        BigDecimal trendReference = trendReference(trendCandles);
        boolean vwapPassed = !cfgVwapFilterEnabled()
                || vwapConditionPassed(request.optionType(), underlyingPrice, trendReference);
        boolean breakoutPassed = breakoutPassed(request, chain);
        boolean breakoutConfirmed = breakoutConfirmed(request, chain);
        boolean volumeSpike = evaluateVolumeSpike(request);
        OiEvaluation oiEvaluation = oiEvaluation(request, chain);
        boolean oiPassed = oiEvaluation.passed();
        boolean ivPassed = volatilityFilter.isAcceptable(request.selectedOptionQuote().impliedVolatility(),
                cfgMaxIvPercent());
        boolean liquidityPassed = request.selectedOptionQuote().volume() >= cfgMinLiquidityVolume();
        boolean timePassed = withinEntryWindow(request.marketTime());
        boolean rsiPassed = rsiConditionPassed(request);

        // Compute ML-enrichment values for signal CSV
        Double rsiValue = computeRsiValue(request);
        Double bidAskSpread = computeBidAskSpread(request.selectedOptionQuote());
        Double ema9Ema21Gap = computeEmaGap(request.underlyingCandles());
        Double atrValue = computeAtr(request.underlyingCandles());

        BigDecimal confidenceScore = confidenceScore(vwapPassed, breakoutPassed, volumeSpike, oiPassed,
                ivPassed, liquidityPassed, rsiPassed,
                oiEvaluation.priceOiBuildUp(), oiEvaluation.chainBuildUp(), oiEvaluation.imbalanceSupports(),
                request.underlying());

        List<String> reasons = new ArrayList<>();
        addReason(reasons, vwapPassed, "Trend condition passed", "Trend condition failed");
        addReason(reasons, breakoutPassed, "Breakout condition passed", "Breakout condition failed");
        addReason(reasons, breakoutConfirmed, "Breakout confirmation passed", "Breakout confirmation failed");
        addReason(reasons, volumeSpike, "Volume spike confirmed", "Volume spike missing");
        addReason(reasons, oiPassed, "OI behavior supports entry", "OI behavior does not support entry");
        reasons.add("OI detail: priceOiBuildUp=" + oiEvaluation.priceOiBuildUp()
                + ", chainBuildUp=" + oiEvaluation.chainBuildUp()
                + ", imbalanceSupports=" + oiEvaluation.imbalanceSupports()
                + ", divergenceRejected=" + oiEvaluation.divergenceRejected());
        addReason(reasons, !oiEvaluation.divergenceRejected(), "OI divergence filter passed",
                "OI divergence rejected entry");
        addReason(reasons, ivPassed, "IV filter passed", "IV filter failed");
        addReason(reasons, liquidityPassed, "Liquidity filter passed", "Liquidity filter failed");
        addReason(reasons, timePassed, "Entry time window passed", "Entry time window failed");
        addReason(reasons, rsiPassed, "RSI momentum gate passed", "RSI momentum gate failed");
        addReason(reasons, confidenceScore.compareTo(cfgMinSignalScorePercent()) >= 0,
                "Signal score passed: " + confidenceScore + "%",
                "Signal score failed: " + confidenceScore + "%");
        boolean resistanceHeadroomPassed = resistanceHeadroomPassed(request.optionType(), underlyingPrice, chain);
        addReason(reasons, resistanceHeadroomPassed, "Resistance headroom passed", "Resistance headroom failed");
        boolean sideFilterPassed = sideFilterPassed(request.optionType(), vwapPassed, breakoutPassed, breakoutConfirmed,
                volumeSpike, oiPassed, oiEvaluation.divergenceRejected(), resistanceHeadroomPassed);
        addReason(reasons, sideFilterPassed, "Side-specific entry filter passed",
                "Side-specific entry filter failed");

        boolean entry = timePassed && ivPassed && liquidityPassed && rsiPassed
                && resistanceHeadroomPassed
                && breakoutConfirmed
                && confidenceScore.compareTo(cfgMinSignalScorePercent()) >= 0
                && sideFilterPassed;
        SignalType signalType = entry
                ? (request.optionType() == OptionType.CE ? SignalType.BUY_CE : SignalType.BUY_PE)
                : SignalType.NO_TRADE;

        log.info("Strategy evaluation completed: signalType={}, underlyingPrice={}, trendReference={}, vwapPassed={}, breakoutPassed={}, volumeSpike={}, oiPassed={}, ivPassed={}, liquidityPassed={}, timePassed={}, confidenceScore={}, minSignalScore={}, imbalance={}, reasons={}",
                signalType, underlyingPrice, trendReference, vwapPassed, breakoutPassed, volumeSpike, oiPassed, ivPassed,
                liquidityPassed, timePassed, confidenceScore, cfgMinSignalScorePercent(),
                chain.nearbyPutCallOiImbalance(), reasons);
        StrategyDecision decision = new StrategyDecision(request.timestamp(), request.underlying(), signalType,
                underlyingPrice, Optional.of(optionPrice), Optional.of(optionOpenInterest), Optional.of(lotSize),
                Optional.of(lotPrice),
                Optional.ofNullable(request.selectedInstrumentKey()), Optional.ofNullable(request.selectedStrike()),
                Optional.ofNullable(request.optionType()), vwapPassed, Optional.of(chain.nearbyPutCallOiImbalance()),
                volumeSpike, confidenceScore, reasons);
        if (recordSignal) {
            recordSignal(request, decision, chain, trendReference, breakoutPassed, oiPassed, ivPassed, liquidityPassed,
                    timePassed, rsiValue, atrValue, ema9Ema21Gap, bidAskSpread);
        }
        return decision;
    }

    private boolean breakoutPassed(StrategyEvaluationRequest request, OptionChainAnalysis chain) {
        List<Candle> candles = request.underlyingCandles();
        BigDecimal price = candles.getLast().close();
        // Per-underlying breakout buffer override (e.g., BANKNIFTY 0.15% vs global 0.05%)
        BigDecimal buffer = effectiveBreakoutBuffer(request.underlying());
        boolean breakout;
        if (request.optionType() == OptionType.CE) {
            boolean resistanceBreak = chain.resistanceStrike()
                    .map(resistance -> price.compareTo(applyPositiveBuffer(resistance, buffer)) > 0)
                    .orElse(false);
            breakout = resistanceBreak || breakoutDetector.breaksAboveSwingHigh(candles,
                    cfgBreakoutLookback(), buffer);
        } else {
            boolean supportBreak = chain.supportStrike()
                    .map(support -> price.compareTo(applyNegativeBuffer(support, buffer)) < 0)
                    .orElse(false);
            breakout = supportBreak || breakoutDetector.breaksBelowSwingLow(candles,
                    cfgBreakoutLookback(), buffer);
        }

        // Per-underlying minimum breakout points check (e.g., BANKNIFTY needs 80+ pts move)
        if (breakout && underlyingConfigService != null) {
            BigDecimal minPoints = underlyingConfigService.getOrDefault(request.underlying()).getMinBreakoutPoints();
            if (minPoints != null && minPoints.signum() > 0) {
                BigDecimal swingRef = request.optionType() == OptionType.CE
                        ? breakoutDetector.swingHigh(candles.subList(0, candles.size() - 1), cfgBreakoutLookback())
                        : breakoutDetector.swingLow(candles.subList(0, candles.size() - 1), cfgBreakoutLookback());
                BigDecimal movePoints = price.subtract(swingRef).abs();
                if (movePoints.compareTo(minPoints) < 0) {
                    log.debug("Breakout rejected: move {} pts < min {} pts for {}",
                            movePoints, minPoints, request.underlying());
                    return false;
                }
            }
        }
        return breakout;
    }

    /** Get effective breakout buffer: per-underlying override if set, otherwise global config. */
    private BigDecimal effectiveBreakoutBuffer(com.algo.trade.domain.UnderlyingSymbol underlying) {
        if (underlyingConfigService != null) {
            return underlyingConfigService.getEffectiveBreakoutBuffer(underlying, cfgBreakoutBufferPercent());
        }
        return cfgBreakoutBufferPercent();
    }

    /**
     * Evaluate volume spike based on per-underlying volumeSpikeMode.
     * NORMAL: standard option candle volume spike detection.
     * OI_PROXY: use OI change as proxy when underlying spot has no volume (BANKNIFTY/FINNIFTY).
     * DISABLED: always returns true (skip volume check).
     */
    private boolean evaluateVolumeSpike(StrategyEvaluationRequest request) {
        String mode = underlyingConfigService != null
                ? underlyingConfigService.getVolumeSpikeMode(request.underlying())
                : "NORMAL";
        return switch (mode) {
            case "DISABLED" -> true;
            case "OI_PROXY" -> {
                // Use OI change on the selected option as a volume proxy
                if (request.previousSelectedOptionQuote().isPresent()) {
                    long currentOi = request.selectedOptionQuote().openInterest();
                    long previousOi = request.previousSelectedOptionQuote().get().openInterest();
                    long oiChange = Math.abs(currentOi - previousOi);
                    yield oiChange > cfgOiDivergenceMinChange() / 2; // 50% of OI divergence threshold
                }
                // Fallback: try standard volume spike on option candles
                yield volumeSpikeDetector.hasSpike(request.selectedOptionCandles(),
                        cfgVolumeLookback(), cfgVolumeSpikeMultiplier());
            }
            default -> volumeSpikeDetector.hasSpike(request.selectedOptionCandles(),
                    cfgVolumeLookback(), cfgVolumeSpikeMultiplier());
        };
    }

    private OiEvaluation oiEvaluation(StrategyEvaluationRequest request, OptionChainAnalysis chain) {
        boolean priceOiBuildUp = request.previousSelectedOptionQuote()
                .map(previous -> request.optionType() == OptionType.CE
                        ? oiChangeTracker.priceAndOiRising(previous, request.selectedOptionQuote())
                        : oiChangeTracker.priceFallingAndOiRising(previous, request.selectedOptionQuote()))
                .orElse(false);
        boolean chainBuildUp = request.optionType() == OptionType.CE
                ? chain.supportPutOiChange() > 0 || chain.resistanceCallOiChange() < 0
                : chain.resistanceCallOiChange() > 0 || chain.supportPutOiChange() < 0;
        boolean imbalanceSupports = request.optionType() == OptionType.CE
                ? chain.nearbyPutCallOiImbalance().compareTo(cfgBullishImbalanceThreshold()) >= 0
                : chain.nearbyPutCallOiImbalance().compareTo(cfgBearishImbalanceThreshold()) <= 0;
        boolean divergenceRejected = oiDivergenceRejected(request.optionType(), chain);
        return new OiEvaluation(priceOiBuildUp, chainBuildUp, imbalanceSupports, divergenceRejected);
    }

    private boolean vwapConditionPassed(OptionType optionType, BigDecimal price, BigDecimal vwap) {
        return optionType == OptionType.CE ? price.compareTo(vwap) > 0 : price.compareTo(vwap) < 0;
    }

    // Fix: symmetric side filter — both CE and PE require VWAP + breakout + volume spike
    private boolean breakoutConfirmed(StrategyEvaluationRequest request, OptionChainAnalysis chain) {
        List<Candle> candles = request.underlyingCandles();
        int confirmationCandles = confirmationCandles(request.optionType());
        if (candles.size() < confirmationCandles) {
            return false;
        }
        BigDecimal buffer = cfgBreakoutBufferPercent();
        Candle latest = candles.getLast();
        List<Candle> thresholdHistory = candles.subList(0, Math.max(0, candles.size() - confirmationCandles));
        if (request.optionType() == OptionType.CE) {
            BigDecimal threshold = chain.resistanceStrike()
                    .filter(resistance -> latest.close().compareTo(applyPositiveBuffer(resistance, buffer)) > 0)
                    .map(resistance -> applyPositiveBuffer(resistance, buffer))
                    .orElseGet(() -> applyPositiveBuffer(highestClose(thresholdHistory), buffer));
            return consecutiveClosesMeetThreshold(candles, confirmationCandles,
                    close -> close.compareTo(threshold) > 0);
        }
        BigDecimal threshold = chain.supportStrike()
                .filter(support -> latest.close().compareTo(applyNegativeBuffer(support, buffer)) < 0)
                .map(support -> applyNegativeBuffer(support, buffer))
                .orElseGet(() -> applyNegativeBuffer(lowestClose(thresholdHistory), buffer));
        return consecutiveClosesMeetThreshold(candles, confirmationCandles,
                close -> close.compareTo(threshold) < 0);
    }

    private boolean resistanceHeadroomPassed(OptionType optionType, BigDecimal underlyingPrice, OptionChainAnalysis chain) {
        Optional<BigDecimal> referenceLevel = optionType == OptionType.CE ? chain.resistanceStrike() : chain.supportStrike();
        if (referenceLevel.isEmpty() || underlyingPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return true;
        }
        if (optionType == OptionType.CE && referenceLevel.get().compareTo(underlyingPrice) <= 0) {
            return true;
        }
        if (optionType == OptionType.PE && referenceLevel.get().compareTo(underlyingPrice) >= 0) {
            return true;
        }
        BigDecimal distancePercent = distancePercent(underlyingPrice, referenceLevel.get());
        return optionType == OptionType.CE
                ? distancePercent.compareTo(MIN_RESISTANCE_HEADROOM_PERCENT) >= 0
                : distancePercent.compareTo(MIN_RESISTANCE_HEADROOM_PERCENT.negate()) <= 0;
    }

    private boolean sideFilterPassed(
            OptionType optionType,
            boolean vwapPassed,
            boolean breakoutPassed,
            boolean breakoutConfirmed,
            boolean volumeSpike,
            boolean oiPassed,
            boolean oiDivergenceRejected,
            boolean resistanceHeadroomPassed
    ) {
        // Hard blocks: only reject on clear contradictions
        // 1. VWAP must confirm direction (price on right side)
        // 2. OI divergence must not be extreme (smart money opposing)
        // Everything else (breakout, volume, OI support) is already captured in the confidence score.
        // If the score passes the threshold without these, the other signals are strong enough.
        return vwapPassed && !oiDivergenceRejected;
    }

    private boolean oiSupportRequired(OptionType optionType) {
        return optionType == OptionType.CE
                ? cfgCeOiSupportRequired()
                : cfgPeOiSupportRequired();
    }

    private boolean oiDivergenceRejected(OptionType optionType, OptionChainAnalysis chain) {
        if (!oiDivergenceFilterEnabled(optionType)) {
            return false;
        }
        BigDecimal multiplier = cfgOiDivergenceMultiplier();
        long minChange = cfgOiDivergenceMinChange();
        if (optionType == OptionType.CE) {
            return chain.nearbyCallOiChange() > minChange
                    && BigDecimal.valueOf(chain.nearbyCallOiChange()).compareTo(
                    BigDecimal.valueOf(chain.nearbyPutOiChange()).multiply(multiplier, MATH_CONTEXT)) > 0;
        }
        return chain.nearbyPutOiChange() > minChange
                && BigDecimal.valueOf(chain.nearbyPutOiChange()).compareTo(
                BigDecimal.valueOf(chain.nearbyCallOiChange()).multiply(multiplier, MATH_CONTEXT)) > 0;
    }

    private boolean oiDivergenceFilterEnabled(OptionType optionType) {
        return optionType == OptionType.CE
                ? cfgCeOiDivergenceFilterEnabled()
                : cfgPeOiDivergenceFilterEnabled();
    }

    private int confirmationCandles(OptionType optionType) {
        return optionType == OptionType.CE
                ? cfgCeBreakoutConfirmationCandles()
                : cfgPeBreakoutConfirmationCandles();
    }

    private boolean consecutiveClosesMeetThreshold(List<Candle> candles, int consecutiveCandles,
                                                   java.util.function.Predicate<BigDecimal> predicate) {
        for (int index = candles.size() - consecutiveCandles; index < candles.size(); index++) {
            if (!predicate.test(candles.get(index).close())) {
                return false;
            }
        }
        return true;
    }

    private List<Candle> trendCandles(StrategyEvaluationRequest request) {
        return cfgTrendFilterEnabled()
                ? request.trendUnderlyingCandles()
                : request.underlyingCandles();
    }

    private BigDecimal trendReference(List<Candle> candles) {
        boolean hasVolume = candles.stream().anyMatch(candle -> candle.volume() > 0);
        if (hasVolume) {
            // Session-anchored VWAP: resets at 9:15 each day
            return vwapIndicator.calculateSessionAnchored(candles, properties.timezone());
        }
        List<BigDecimal> closes = candles.stream().map(Candle::close).toList();
        return emaIndicator.calculate(closes, Math.min(cfgBreakoutLookback(), closes.size()));
    }

    private boolean rsiConditionPassed(StrategyEvaluationRequest request) {
        if (!cfgRsiFilterEnabled()) {
            return true;
        }
        List<BigDecimal> closes = request.underlyingCandles().stream().map(Candle::close).toList();
        BigDecimal rsi = rsiIndicator.calculate(closes, cfgRsiPeriod());
        boolean passed = request.optionType() == OptionType.CE
                ? rsi.compareTo(cfgRsiCeBuyThreshold()) > 0
                : rsi.compareTo(cfgRsiPeSellThreshold()) < 0;
        log.debug("RSI gate: optionType={}, rsi={}, threshold={}, passed={}",
                request.optionType(), rsi.setScale(2, java.math.RoundingMode.HALF_UP),
                request.optionType() == OptionType.CE
                        ? cfgRsiCeBuyThreshold()
                        : cfgRsiPeSellThreshold(),
                passed);
        return passed;
    }

    private boolean withinEntryWindow(LocalTime marketTime) {
        return !marketTime.isBefore(cfgEntryStartTime())
                && !marketTime.isAfter(cfgEntryCutoffTime());
    }

    private StrategyDecision noTrade(StrategyEvaluationRequest request, Optional<BigDecimal> imbalance,
                                     boolean volumeSpike, String reason, boolean recordSignal) {
        BigDecimal underlyingPrice = request.underlyingCandles().isEmpty()
                ? BigDecimal.ZERO
                : request.underlyingCandles().getLast().close();
        BigDecimal optionPrice = request.selectedOptionQuote() == null ? BigDecimal.ZERO : request.selectedOptionQuote().lastPrice();
        long optionOpenInterest = request.selectedOptionQuote() == null ? 0L : request.selectedOptionQuote().openInterest();
        int lotSize = Math.max(request.selectedLotSize(), 0);
        BigDecimal lotPrice = optionPrice.multiply(BigDecimal.valueOf(lotSize), MATH_CONTEXT);
        StrategyDecision decision = new StrategyDecision(request.timestamp(), request.underlying(), SignalType.NO_TRADE,
                underlyingPrice, Optional.of(optionPrice), Optional.of(optionOpenInterest), Optional.of(lotSize),
                Optional.of(lotPrice),
                Optional.ofNullable(request.selectedInstrumentKey()), Optional.ofNullable(request.selectedStrike()),
                Optional.ofNullable(request.optionType()), false, imbalance, volumeSpike, BigDecimal.ZERO, List.of(reason));
        if (recordSignal) {
            recordSignal(request, decision, null, BigDecimal.ZERO, false, false, false, false, false,
                    null, null, null, null);
        }
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
            boolean liquidityPassed,
            boolean rsiPassed,
            boolean priceOiBuildUp,
            boolean chainBuildUp,
            boolean imbalanceSupports,
            com.algo.trade.domain.UnderlyingSymbol underlying
    ) {
        boolean normalizeForNoVolume = underlyingConfigService != null
                && underlyingConfigService.isNormalizeScoreForNoVolume(underlying);

        int score = 0;
        int maxScore = 100;
        score += vwapPassed ? 15 : 0;
        score += breakoutPassed ? 25 : 0;
        if (normalizeForNoVolume && !volumeSpike) {
            // Volume spike uses OI proxy but still failed — exclude from denominator
            maxScore -= 20;
        } else {
            score += volumeSpike ? 20 : 0;
        }
        // Graduated OI scoring: 9 + 8 + 8 = 25 max
        score += priceOiBuildUp ? 9 : 0;
        score += chainBuildUp ? 8 : 0;
        score += imbalanceSupports ? 8 : 0;
        score += liquidityPassed ? 10 : 0;
        score += ivPassed ? 5 : 0;
        score += cfgRsiFilterEnabled() && rsiPassed ? 10 : 0;

        // Normalize to 100 scale if volume weight was excluded
        if (normalizeForNoVolume && maxScore < 100 && maxScore > 0) {
            return BigDecimal.valueOf((long) score * 100 / maxScore);
        }
        return BigDecimal.valueOf(score);
    }

    private BigDecimal distancePercent(BigDecimal price, BigDecimal level) {
        return level.subtract(price)
                .multiply(BigDecimal.valueOf(100), MATH_CONTEXT)
                .divide(price, MATH_CONTEXT);
    }

    private BigDecimal highestClose(List<Candle> candles) {
        if (candles.isEmpty()) {
            return BigDecimal.ZERO;
        }
        // Use Candle::high for swing high reference — consistent with BreakoutDetector.swingHigh()
        return candles.stream().map(Candle::high).max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
    }

    private BigDecimal lowestClose(List<Candle> candles) {
        if (candles.isEmpty()) {
            return BigDecimal.ZERO;
        }
        // Use Candle::low for swing low reference — consistent with BreakoutDetector.swingLow()
        return candles.stream().map(Candle::low).min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
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
            boolean timePassed,
            Double rsiValue,
            Double atrValue,
            Double ema9Ema21Gap,
            Double bidAskSpread
    ) {
        if (signalCsvRecorder == null) {
            return;
        }
        signalCsvRecorder.record(request, decision, chain, vwap, breakoutPassed, oiPassed, ivPassed, liquidityPassed,
                timePassed, rsiValue, atrValue, ema9Ema21Gap, bidAskSpread,
                request.vixLevel() > 0 ? request.vixLevel() : null,
                request.daysToExpiry() > 0 ? request.daysToExpiry() : null,
                request.delta() != 0 ? request.delta() : null,
                request.gamma() != 0 ? request.gamma() : null,
                request.theta() != 0 ? request.theta() : null,
                request.vega() != 0 ? request.vega() : null,
                request.realizedVol5d() > 0 ? request.realizedVol5d() : null,
                request.ivSkew() != 0 ? request.ivSkew() : null);
    }

    private Double computeRsiValue(StrategyEvaluationRequest request) {
        try {
            List<BigDecimal> closes = request.underlyingCandles().stream().map(Candle::close).toList();
            if (closes.size() < cfgRsiPeriod() + 1) return null;
            return rsiIndicator.calculate(closes, cfgRsiPeriod()).doubleValue();
        } catch (Exception e) {
            return null;
        }
    }

    private Double computeBidAskSpread(com.algo.trade.domain.Quote quote) {
        try {
            if (quote.bid().isEmpty() || quote.ask().isEmpty()) return null;
            BigDecimal bid = quote.bid().get();
            BigDecimal ask = quote.ask().get();
            if (bid.signum() <= 0 || ask.signum() <= 0) return null;
            return ask.subtract(bid).doubleValue();
        } catch (Exception e) {
            return null;
        }
    }

    private Double computeEmaGap(List<Candle> candles) {
        try {
            List<BigDecimal> closes = candles.stream().map(Candle::close).toList();
            if (closes.size() < 21) return null;
            double ema9 = emaIndicator.calculate(closes, 9).doubleValue();
            double ema21 = emaIndicator.calculate(closes, 21).doubleValue();
            return ema9 - ema21;
        } catch (Exception e) {
            return null;
        }
    }

    private Double computeAtr(List<Candle> candles) {
        try {
            if (candles.size() < 15) return null;
            double atrSum = 0;
            int periods = 14;
            for (int i = candles.size() - periods; i < candles.size(); i++) {
                Candle c = candles.get(i);
                Candle prev = candles.get(i - 1);
                double tr = Math.max(
                        c.high().subtract(c.low()).doubleValue(),
                        Math.max(
                                Math.abs(c.high().subtract(prev.close()).doubleValue()),
                                Math.abs(c.low().subtract(prev.close()).doubleValue())
                        )
                );
                atrSum += tr;
            }
            return atrSum / periods;
        } catch (Exception e) {
            return null;
        }
    }

    private record OiEvaluation(
            boolean priceOiBuildUp,
            boolean chainBuildUp,
            boolean imbalanceSupports,
            boolean divergenceRejected
    ) {
        boolean passed() {
            return (priceOiBuildUp || chainBuildUp || imbalanceSupports) && !divergenceRejected;
        }
    }
}
