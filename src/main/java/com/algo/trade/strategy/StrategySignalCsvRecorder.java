package com.algo.trade.strategy;

import com.algo.trade.config.TradingProperties;
import com.algo.trade.domain.Candle;
import com.algo.trade.domain.Quote;
import com.algo.trade.domain.StrategyDecision;
import com.algo.trade.reporting.SignalDecisionKey;
import com.algo.trade.util.IstDateTimes;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Appends live strategy evaluation inputs and derived decisions for later tuning.
 * All strategies (DIRECTIONAL_BUY, SCALPING, VOLATILITY_BREAKOUT, etc.) write to
 * the same entry-signals.csv with a strategyType column for filtering.
 */
@Service
public class StrategySignalCsvRecorder {

    private static final Logger log = LoggerFactory.getLogger(StrategySignalCsvRecorder.class);
    private static final Path OUTPUT = Path.of("reports", "entry-signals", "entry-signals.csv");
    private static final Path CANDLES = Path.of("reports", "entry-signals", "entry-candles.csv");
    private static final Path OPTION_CHAIN_LEVELS = Path.of("reports", "entry-signals", "option-chain-levels.csv");

    private static final String HEADER = String.join(",",
            "decisionKey", "signalId", "timestamp", "marketTime",
            "strategyType",
            "underlying", "signalType", "optionType",
            "marketDataMode", "executionMode",
            "trendFilterEnabled", "trendTimeframe", "rsiFilterEnabled",
            "stopLossPercent", "targetPercent", "trailingStopActivationPercent", "trailingGapPercent",
            "maxRiskPerTradePercent", "totalCapital", "maxOpenTrades",
            "minSignalScorePercent", "volumeSpikeMultiplier", "breakoutBufferPercent",
            "breakoutLookback", "volumeLookback", "minLiquidityVolume", "maxIvPercent",
            "selectedInstrumentKey", "selectedStrike",
            "underlyingPrice", "underlyingTrendReference",
            "optionLastPrice", "optionVolume", "optionOpenInterest", "optionImpliedVolatility",
            "previousOptionLastPrice", "previousOptionOpenInterest",
            "underlyingCandleCount", "trendUnderlyingCandleCount", "optionCandleCount",
            "latestUnderlyingOpen", "latestUnderlyingHigh", "latestUnderlyingLow",
            "latestUnderlyingClose", "latestUnderlyingVolume",
            "latestOptionOpen", "latestOptionHigh", "latestOptionLow",
            "latestOptionClose", "latestOptionVolume",
            "resistanceStrike", "supportStrike",
            "nearbyPutCallOiImbalance", "nearbyCallOpenInterest", "nearbyPutOpenInterest",
            "resistanceCallOiChange", "supportPutOiChange",
            "vwapPassed", "breakoutPassed", "volumeSpike", "oiPassed",
            "ivPassed", "liquidityPassed", "timePassed",
            "scalpEma9", "scalpEma21", "scalpCrossType", "scalpConfirmCount",
            "bbUpperBand", "bbLowerBand", "bbBandwidth", "bbSqueeze", "ivRank",
            "confidenceScore", "firstFailedFilter", "reasons",
            "rsiValue", "atrValue", "ema9Ema21Gap", "bidAskSpread", "vixLevel", "daysToExpiry",
            "delta", "gamma", "theta", "vega", "realizedVol5d", "ivRvSpread", "ivSkew",
            "oiPriceActionConfirmed",
            // ── Crude oil snapshot (observational; not wired into entry logic) ─────────
            "crudeAvailable", "crudePriceINR", "crudePriceUSD",
            "crudePreviousDayCloseINR", "crudeTodayOpenINR",
            "crudeDailyChangePct", "crudeOvernightGapPct", "crudeLast30MinChangePct",
            "crudeRegime", "crudeMomentum",
            "crudeOvernightShock", "crudeIntradayShock"
    ) + System.lineSeparator();

    private static final String CANDLES_HEADER = String.join(",",
            "decisionKey", "evaluationTimestamp", "underlying", "optionType",
            "selectedInstrumentKey", "candleRole", "candleTimestamp", "timeframe",
            "open", "high", "low", "close", "volume", "openInterest"
    ) + System.lineSeparator();

    private static final String OPTION_CHAIN_LEVELS_HEADER = String.join(",",
            "decisionKey", "evaluationTimestamp", "underlying", "optionType",
            "selectedInstrumentKey", "spotPrice", "strike",
            "callOpenInterest", "putOpenInterest",
            "callOpenInterestChange", "putOpenInterestChange",
            "callLastPrice", "putLastPrice"
    ) + System.lineSeparator();

    private final TradingProperties properties;
    private final StrategyConfigService strategyConfigService;
    private final com.algo.trade.indicator.OIPriceActionFilter oiPriceActionFilter;
    private final com.algo.trade.reporting.SignalTuningProperties tuningProperties;

    public StrategySignalCsvRecorder(TradingProperties properties, StrategyConfigService strategyConfigService,
                                      com.algo.trade.indicator.OIPriceActionFilter oiPriceActionFilter,
                                      com.algo.trade.reporting.SignalTuningProperties tuningProperties) {
        this.properties = properties;
        this.strategyConfigService = strategyConfigService;
        this.oiPriceActionFilter = oiPriceActionFilter;
        this.tuningProperties = tuningProperties;
    }

    /**
     * Unified signal recording — single method for ALL strategies.
     * Records full 38+ ML features regardless of strategy type.
     */
    public synchronized void recordUnified(SignalRecordContext ctx) {
        try {
            Files.createDirectories(OUTPUT.getParent());
            String decisionKey = unifiedDecisionKey(ctx);
            String signalId = java.util.UUID.randomUUID().toString().substring(0, 12);

            Candle underlying = last(ctx.underlyingCandles());
            Candle option = last(ctx.optionCandles());
            Quote quote = ctx.selectedOptionQuote();
            Quote prevQuote = ctx.previousOptionQuote();
            OptionChainAnalysis chain = ctx.chainAnalysis();
            StrategyDecision decision = ctx.decision();
            StrategyConfig config = resolveConfig(ctx.strategyType());

            String row = String.join(",",
                    csv(decisionKey),
                    csv(signalId),
                    csv(decision != null ? IstDateTimes.formatInstant(decision.timestamp()) : IstDateTimes.formatInstant(java.time.Instant.now())),
                    csv(IstDateTimes.formatLocalTime(java.time.LocalTime.now(java.time.ZoneId.of("Asia/Kolkata")))),
                    csv(ctx.strategyType()),
                    csv(ctx.underlying()),
                    csv(decision != null ? decision.signalType() : "NO_TRADE"),
                    csv(decision != null ? decision.optionType().map(Enum::name).orElse(null) : null),
                    csv(properties.marketDataMode()),
                    csv(properties.executionMode()),
                    csv(null), csv(null), csv(null), // trendFilterEnabled, trendTimeframe, rsiFilterEnabled
                    csv(config.getStopLossPercent()),
                    csv(config.getTargetPercent()),
                    csv(config.getTrailingStopActivationPercent()),
                    csv(config.getTrailingGapPercent()),
                    csv(properties.risk() != null ? properties.risk().maxRiskPerTradePercent() : null),
                    csv(properties.risk() != null ? properties.risk().totalCapital() : null),
                    csv(properties.risk() != null ? properties.risk().maxOpenTrades() : null),
                    csv(null), csv(null), csv(null), // minSignalScore, volumeSpike, breakoutBuffer
                    csv(null), csv(null), csv(null), csv(null), // breakoutLookback, volumeLookback, minLiquidity, maxIv
                    csv(ctx.selectedInstrumentKey() != null ? ctx.selectedInstrumentKey()
                            : (decision != null ? decision.selectedInstrumentKey().orElse(null) : null)),
                    csv(ctx.selectedStrike() != null ? ctx.selectedStrike()
                            : (decision != null ? decision.selectedStrike().orElse(null) : null)),
                    csv(decision != null ? decision.underlyingPrice() : (underlying != null ? underlying.close() : null)),
                    csv(ctx.vwap()),
                    csv(quote != null ? quote.lastPrice() : (decision != null ? decision.optionPrice().orElse(null) : null)),
                    csv(quote != null ? quote.volume() : null),
                    csv(quote != null ? quote.openInterest() : null),
                    csv(quote != null ? quote.impliedVolatility().orElse(null) : null),
                    csv(prevQuote != null ? prevQuote.lastPrice() : null),
                    csv(prevQuote != null ? prevQuote.openInterest() : null),
                    csv(ctx.underlyingCandles().size()),
                    csv(ctx.trendCandles().size()),
                    csv(ctx.optionCandles().size()),
                    csv(underlying != null ? underlying.open() : null),
                    csv(underlying != null ? underlying.high() : null),
                    csv(underlying != null ? underlying.low() : null),
                    csv(underlying != null ? underlying.close() : null),
                    csv(underlying != null ? underlying.volume() : null),
                    csv(option != null ? option.open() : null),
                    csv(option != null ? option.high() : null),
                    csv(option != null ? option.low() : null),
                    csv(option != null ? option.close() : null),
                    csv(option != null ? option.volume() : null),
                    csv(chain != null ? chain.resistanceStrike().orElse(null) : null),
                    csv(chain != null ? chain.supportStrike().orElse(null) : null),
                    csv(chain != null ? chain.nearbyPutCallOiImbalance() : null),
                    csv(chain != null ? chain.nearbyCallOpenInterest() : null),
                    csv(chain != null ? chain.nearbyPutOpenInterest() : null),
                    csv(chain != null ? chain.resistanceCallOiChange() : null),
                    csv(chain != null ? chain.supportPutOiChange() : null),
                    csv(decision != null ? decision.vwapConditionPassed() : null),
                    csv(ctx.breakoutPassed()),
                    csv(decision != null ? decision.volumeSpike() : null),
                    csv(ctx.oiPassed()),
                    csv(ctx.ivPassed()),
                    csv(ctx.liquidityPassed()),
                    csv(ctx.timePassed()),
                    csv(ctx.scalpEma9()), csv(ctx.scalpEma21()), csv(ctx.scalpCrossType()), csv(ctx.scalpConfirmCount()),
                    csv(ctx.bbUpperBand()), csv(ctx.bbLowerBand()), csv(ctx.bbBandwidth()), csv(ctx.bbSqueeze()),
                    csv(ctx.ivRank() > 0 ? ctx.ivRank() : null),
                    csv(decision != null ? decision.confidenceScore() : null),
                    csv(ctx.firstFailedFilter()),
                    csv(decision != null ? String.join("; ", decision.reasons()) : ctx.executionStage()),
                    csv(ctx.rsiValue()), csv(ctx.atrValue()), csv(ctx.ema9Ema21Gap()),
                    csv(ctx.bidAskSpread()), csv(ctx.vixLevel()), csv(ctx.daysToExpiry()),
                    csv(ctx.delta()), csv(ctx.gamma()), csv(ctx.theta()), csv(ctx.vega()),
                    csv(ctx.realizedVol5d()),
                    csv(ctx.realizedVol5d() != null && ctx.realizedVol5d() > 0 && quote != null
                            ? quote.impliedVolatility().map(iv -> iv.doubleValue() - ctx.realizedVol5d()).orElse(null)
                            : null),
                    csv(ctx.ivSkew()),
                    csv(resolveOiPriceActionForContext(ctx)),
                    // ── Crude oil columns ────────────────────────────────────────────
                    csv(ctx.crudeContext() != null && ctx.crudeContext().available()),
                    csv(ctx.crudeContext() != null ? ctx.crudeContext().priceINR() : null),
                    csv(ctx.crudeContext() != null ? ctx.crudeContext().priceUSD() : null),
                    csv(ctx.crudeContext() != null ? ctx.crudeContext().previousDayCloseINR() : null),
                    csv(ctx.crudeContext() != null ? ctx.crudeContext().todayOpenINR() : null),
                    csv(ctx.crudeContext() != null ? ctx.crudeContext().dailyChangePct() : null),
                    csv(ctx.crudeContext() != null ? ctx.crudeContext().overnightGapPct() : null),
                    csv(ctx.crudeContext() != null ? ctx.crudeContext().last30MinChangePct() : null),
                    csv(ctx.crudeContext() != null ? ctx.crudeContext().regime() : null),
                    csv(ctx.crudeContext() != null ? ctx.crudeContext().momentum() : null),
                    csv(ctx.crudeContext() != null && ctx.crudeContext().overnightShock()),
                    csv(ctx.crudeContext() != null && ctx.crudeContext().intradayShock())
            ) + System.lineSeparator();

            append(OUTPUT, HEADER, row);
        } catch (IOException ex) {
            log.warn("Unified signal CSV write failed: {}", ex.getMessage());
        }
    }

    private String unifiedDecisionKey(SignalRecordContext ctx) {
        if (ctx.decision() != null) {
            return SignalDecisionKey.from(ctx.decision());
        }
        String raw = ctx.strategyType() + "|" + ctx.underlying() + "|" + java.time.Instant.now();
        return Integer.toUnsignedString(raw.hashCode(), 16);
    }

    private Boolean resolveOiPriceActionForContext(SignalRecordContext ctx) {
        try {
            if (ctx.underlying() == null || ctx.decision() == null) return null;
            com.algo.trade.domain.IndexType idx = com.algo.trade.domain.IndexType.from(ctx.underlying());
            boolean isBullish = ctx.decision().optionType()
                    .map(ot -> ot == com.algo.trade.domain.OptionType.CE).orElse(true);
            return oiPriceActionFilter.isBreakoutConfirmed(idx, isBullish);
        } catch (Exception e) {
            return null;
        }
    }

    private StrategyConfig resolveConfig(String strategyType) {
        try {
            return strategyConfigService.getConfig(
                    StrategyType.valueOf(strategyType), "NIFTY");
        } catch (Exception e) {
            return strategyConfigService.getDirectionalBuyConfig();
        }
    }

    /**
     * Records a full DIRECTIONAL_BUY evaluation with all filter details, option chain, and candles.
     */
    public synchronized void record(
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
            Double bidAskSpread,
            Double vixLevel,
            Long daysToExpiry,
            Double delta,
            Double gamma,
            Double theta,
            Double vega,
            Double realizedVol5d,
            Double ivSkew
    ) {
        try {
            Files.createDirectories(OUTPUT.getParent());
            String decisionKey = decisionKey(request);
            String signalId = java.util.UUID.randomUUID().toString().substring(0, 12);
            append(OUTPUT, HEADER, fullRow("DIRECTIONAL_BUY", decisionKey, signalId, request, decision, chain, vwap,
                    breakoutPassed, oiPassed, ivPassed, liquidityPassed, timePassed,
                    rsiValue, atrValue, ema9Ema21Gap, bidAskSpread, vixLevel, daysToExpiry,
                    delta, gamma, theta, vega, realizedVol5d, ivSkew));
            // Interim OOM mitigation: entry-candles.csv grows to 750+ MB and is the dominant
            // heap pressure for report generation. Skipped by default; flip signal-tuning.write-candles=true
            // for an ad-hoc investigation. See important/SIGNAL_CAPTURE_TUNING_REDESIGN.md.
            if (tuningProperties.isWriteCandles()) {
                append(CANDLES, CANDLES_HEADER, candleRows(decisionKey, request));
            }
            append(OPTION_CHAIN_LEVELS, OPTION_CHAIN_LEVELS_HEADER, optionChainRows(decisionKey, request));
        } catch (IOException ex) {
            log.warn("Strategy signal CSV write failed: {}", ex.getMessage());
        }
    }

    /**
     * Records a NO_TRADE from any additional strategy into the same entry-signals.csv.
     * Pass {@link StrategyDiagnostics#NONE} when a strategy doesn't compute indicators.
     */
    public synchronized void recordAdditionalNoTrade(
            String strategyType,
            String underlying,
            BigDecimal spotPrice,
            String reason,
            List<Candle> underlyingCandles,
            StrategyDiagnostics diagnostics,
            double ivRank
    ) {
        recordAdditionalNoTrade(strategyType, underlying, spotPrice, reason,
                underlyingCandles, diagnostics, ivRank, null, null);
    }

    /**
     * Records a NO_TRADE with ATM instrument context (selectedInstrumentKey + selectedStrike).
     * Use when the ATM option can be resolved even though no signal was generated.
     */
    public synchronized void recordAdditionalNoTrade(
            String strategyType,
            String underlying,
            BigDecimal spotPrice,
            String reason,
            List<Candle> underlyingCandles,
            StrategyDiagnostics diagnostics,
            double ivRank,
            String selectedInstrumentKey,
            BigDecimal selectedStrike
    ) {
        if (diagnostics == null) diagnostics = StrategyDiagnostics.NONE;
        try {
            Files.createDirectories(OUTPUT.getParent());
            Candle latestUnderlying = last(underlyingCandles);
            String decisionKey = Integer.toUnsignedString(
                    (strategyType + "|" + underlying + "|" + System.nanoTime()).hashCode(), 16);
            String signalId = java.util.UUID.randomUUID().toString().substring(0, 12);
            String row = String.join(",",
                    csv(decisionKey),
                    csv(signalId),
                    csv(IstDateTimes.formatInstant(java.time.Instant.now())),
                    csv(java.time.LocalTime.now(java.time.ZoneId.of("Asia/Kolkata")).toString()),
                    csv(strategyType),
                    csv(underlying),
                    csv("NO_TRADE"),
                    csv(null),
                    csv(properties.marketDataMode()),
                    csv(properties.executionMode()),
                    csv(null), csv(null), csv(null),
                    csv(null), csv(null), csv(null), csv(null),
                    csv(properties.risk().maxRiskPerTradePercent()),
                    csv(properties.risk().totalCapital()),
                    csv(properties.risk().maxOpenTrades()),
                    csv(null), csv(null), csv(null),
                    csv(null), csv(null), csv(null), csv(null),
                    csv(selectedInstrumentKey), csv(selectedStrike),
                    csv(spotPrice),
                    csv(null),
                    csv(null), csv(null), csv(null), csv(null),
                    csv(null), csv(null),
                    csv(underlyingCandles != null ? underlyingCandles.size() : 0),
                    csv(0), csv(0),
                    csv(latestUnderlying != null ? latestUnderlying.open() : null),
                    csv(latestUnderlying != null ? latestUnderlying.high() : null),
                    csv(latestUnderlying != null ? latestUnderlying.low() : null),
                    csv(latestUnderlying != null ? latestUnderlying.close() : null),
                    csv(latestUnderlying != null ? latestUnderlying.volume() : null),
                    csv(null), csv(null), csv(null), csv(null), csv(null),
                    csv(null), csv(null),
                    csv(null), csv(null), csv(null),
                    csv(null), csv(null),
                    csv(null), csv(null), csv(null), csv(null),
                    csv(null), csv(null), csv(null),
                    csv(diagnostics.ema9()), csv(diagnostics.ema21()),
                    csv(diagnostics.emaCrossType()), csv(diagnostics.emaCrossConfirmCount()),
                    csv(diagnostics.bbUpper()), csv(diagnostics.bbLower()),
                    csv(diagnostics.bbBandwidth()), csv(diagnostics.bbSqueeze()),
                    csv(ivRank > 0 ? ivRank : null),
                    csv(null),
                    csv(diagnostics.firstFailedFilter()),
                    csv(reason),
                    csv(null), csv(null), csv(null), csv(null), csv(null), csv(null), // ML columns
                    csv(null), csv(null), csv(null), csv(null), csv(null), csv(null), csv(null), // Greeks + RV + skew
                    csv(null) // oiPriceActionConfirmed
            ) + System.lineSeparator();
            append(OUTPUT, HEADER, row);
        } catch (IOException ex) {
            log.warn("Additional strategy no-trade CSV write failed: {}", ex.getMessage());
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────

    private String fullRow(
            String strategyType,
            String decisionKey,
            String signalId,
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
            Double bidAskSpread,
            Double vixLevel,
            Long daysToExpiry,
            Double delta,
            Double gamma,
            Double theta,
            Double vega,
            Double realizedVol5d,
            Double ivSkew
    ) {
        Candle underlying = last(request.underlyingCandles());
        Candle option = last(request.selectedOptionCandles());
        Quote quote = request.selectedOptionQuote();
        Quote previousQuote = request.previousSelectedOptionQuote().orElse(null);
        StrategyConfig config = strategyConfigService.getDirectionalBuyConfig();
        return String.join(",",
                csv(decisionKey),
                csv(signalId),
                csv(IstDateTimes.formatInstant(request.timestamp())),
                csv(IstDateTimes.formatLocalTime(request.marketTime())),
                csv(strategyType),
                csv(request.underlying()),
                csv(decision.signalType()),
                csv(request.optionType()),
                csv(properties.marketDataMode()),
                csv(properties.executionMode()),
                csv(properties.entry().trendFilterEnabled()),
                csv(properties.entry().trendTimeframe()),
                csv(properties.entry().rsiFilterEnabled()),
                csv(config.getStopLossPercent()),
                csv(config.getTargetPercent()),
                csv(config.getTrailingStopActivationPercent()),
                csv(config.getTrailingGapPercent()),
                csv(properties.risk().maxRiskPerTradePercent()),
                csv(properties.risk().totalCapital()),
                csv(properties.risk().maxOpenTrades()),
                csv(properties.entry().minSignalScorePercent()),
                csv(properties.entry().volumeSpikeMultiplier()),
                csv(properties.entry().breakoutBufferPercent()),
                csv(properties.entry().breakoutLookback()),
                csv(properties.entry().volumeLookback()),
                csv(properties.entry().minLiquidityVolume()),
                csv(properties.entry().maxIvPercent()),
                csv(request.selectedInstrumentKey()),
                csv(request.selectedStrike()),
                csv(decision.underlyingPrice()),
                csv(vwap),
                csv(quote.lastPrice()),
                csv(quote.volume()),
                csv(quote.openInterest()),
                csv(quote.impliedVolatility().orElse(null)),
                csv(previousQuote == null ? null : previousQuote.lastPrice()),
                csv(previousQuote == null ? null : previousQuote.openInterest()),
                csv(request.underlyingCandles().size()),
                csv(request.trendUnderlyingCandles().size()),
                csv(request.selectedOptionCandles().size()),
                csv(underlying == null ? null : underlying.open()),
                csv(underlying == null ? null : underlying.high()),
                csv(underlying == null ? null : underlying.low()),
                csv(underlying == null ? null : underlying.close()),
                csv(underlying == null ? null : underlying.volume()),
                csv(option == null ? null : option.open()),
                csv(option == null ? null : option.high()),
                csv(option == null ? null : option.low()),
                csv(option == null ? null : option.close()),
                csv(option == null ? null : option.volume()),
                csv(chain == null ? null : chain.resistanceStrike().orElse(null)),
                csv(chain == null ? null : chain.supportStrike().orElse(null)),
                csv(chain == null ? null : chain.nearbyPutCallOiImbalance()),
                csv(chain == null ? null : chain.nearbyCallOpenInterest()),
                csv(chain == null ? null : chain.nearbyPutOpenInterest()),
                csv(chain == null ? null : chain.resistanceCallOiChange()),
                csv(chain == null ? null : chain.supportPutOiChange()),
                csv(decision.vwapConditionPassed()),
                csv(breakoutPassed),
                csv(decision.volumeSpike()),
                csv(oiPassed),
                csv(ivPassed),
                csv(liquidityPassed),
                csv(timePassed),
                csv(null), csv(null), csv(null), csv(null), // scalp EMA fields
                csv(null), csv(null), csv(null), csv(null), // BB fields
                csv(request.ivRank() > 0 ? request.ivRank() : null),
                csv(decision.confidenceScore()),
                csv(null), // firstFailedFilter — N/A for DIRECTIONAL_BUY
                csv(String.join("; ", decision.reasons())),
                csv(rsiValue), csv(atrValue), csv(ema9Ema21Gap),
                csv(bidAskSpread), csv(vixLevel), csv(daysToExpiry),
                csv(delta), csv(gamma), csv(theta), csv(vega),
                csv(realizedVol5d),
                csv(realizedVol5d != null && realizedVol5d > 0
                        ? request.selectedOptionQuote().impliedVolatility()
                              .map(iv -> iv.doubleValue() - realizedVol5d).orElse(null)
                        : null),
                csv(ivSkew),
                csv(resolveOiPriceActionConfirmed(request))
        ) + System.lineSeparator();
    }

    private void append(Path path, String header, String rows) throws IOException {
        if (rows.isEmpty()) return;
        if (Files.notExists(path) || Files.size(path) == 0) {
            Files.writeString(path, header, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        Files.writeString(path, rows, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private String candleRows(String decisionKey, StrategyEvaluationRequest request) {
        StringBuilder rows = new StringBuilder();
        appendCandleRows(rows, decisionKey, request, "UNDERLYING", request.underlyingCandles());
        appendCandleRows(rows, decisionKey, request, "OPTION", request.selectedOptionCandles());
        return rows.toString();
    }

    private void appendCandleRows(StringBuilder rows, String decisionKey, StrategyEvaluationRequest request,
                                  String candleRole, Iterable<Candle> candles) {
        for (Candle candle : candles) {
            rows.append(String.join(",",
                    csv(decisionKey),
                    csv(IstDateTimes.formatInstant(request.timestamp())),
                    csv(request.underlying()),
                    csv(request.optionType()),
                    csv(request.selectedInstrumentKey()),
                    csv(candleRole),
                    csv(IstDateTimes.formatInstant(candle.timestamp())),
                    csv(candle.timeframe()),
                    csv(candle.open()), csv(candle.high()), csv(candle.low()), csv(candle.close()),
                    csv(candle.volume()), csv(candle.openInterest())
            )).append(System.lineSeparator());
        }
    }

    private String optionChainRows(String decisionKey, StrategyEvaluationRequest request) {
        if (request.optionChainSnapshot() == null || request.optionChainSnapshot().levels().isEmpty()) return "";
        StringBuilder rows = new StringBuilder();
        for (var level : request.optionChainSnapshot().levels()) {
            rows.append(String.join(",",
                    csv(decisionKey),
                    csv(IstDateTimes.formatInstant(request.timestamp())),
                    csv(request.underlying()), csv(request.optionType()),
                    csv(request.selectedInstrumentKey()),
                    csv(request.optionChainSnapshot().underlyingPrice()),
                    csv(level.strike()),
                    csv(level.callOpenInterest()), csv(level.putOpenInterest()),
                    csv(level.callOpenInterestChange()), csv(level.putOpenInterestChange()),
                    csv(level.callLastPrice()), csv(level.putLastPrice())
            )).append(System.lineSeparator());
        }
        return rows.toString();
    }

    private String decisionKey(StrategyEvaluationRequest request) {
        String raw = request.timestamp() + "|" + request.underlying() + "|" + request.optionType() + "|"
                + request.selectedInstrumentKey();
        return Integer.toUnsignedString(raw.hashCode(), 16);
    }

    private String additionalDecisionKey(String strategyType, StrategyDecision decision) {
        String raw = strategyType + "|" + decision.timestamp() + "|" + decision.underlying() + "|"
                + decision.optionType().map(Enum::name).orElse("");
        return Integer.toUnsignedString(raw.hashCode(), 16);
    }

    private static Candle last(List<Candle> candles) {
        return candles != null && !candles.isEmpty() ? candles.getLast() : null;
    }

    private String csv(Object value) {
        if (value == null) return "";
        String text = String.valueOf(value);
        return '"' + text.replace("\"", "\"\"") + '"';
    }

    /**
     * Query the OIPriceActionFilter for the current breakout confirmation status.
     * Returns true/false based on the option type (CE = bullish, PE = bearish).
     */
    private Boolean resolveOiPriceActionConfirmed(StrategyEvaluationRequest request) {
        try {
            com.algo.trade.domain.IndexType idx = com.algo.trade.domain.IndexType.fromName(request.underlying().name());
            boolean isBullish = request.optionType() == com.algo.trade.domain.OptionType.CE;
            return oiPriceActionFilter.isBreakoutConfirmed(idx, isBullish);
        } catch (Exception e) {
            return null;
        }
    }
}
