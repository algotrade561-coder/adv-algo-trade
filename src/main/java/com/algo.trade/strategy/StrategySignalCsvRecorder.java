package com.algo.trade.strategy;

import com.algo.trade.config.TradingProperties;
import com.algo.trade.domain.Candle;
import com.algo.trade.domain.Quote;
import com.algo.trade.domain.StrategyDecision;
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
            "confidenceScore", "reasons"
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

    public StrategySignalCsvRecorder(TradingProperties properties, StrategyConfigService strategyConfigService) {
        this.properties = properties;
        this.strategyConfigService = strategyConfigService;
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
            boolean timePassed
    ) {
        try {
            Files.createDirectories(OUTPUT.getParent());
            String decisionKey = decisionKey(request);
            String signalId = java.util.UUID.randomUUID().toString().substring(0, 12);
            append(OUTPUT, HEADER, fullRow("DIRECTIONAL_BUY", decisionKey, signalId, request, decision, chain, vwap,
                    breakoutPassed, oiPassed, ivPassed, liquidityPassed, timePassed));
            append(CANDLES, CANDLES_HEADER, candleRows(decisionKey, request));
            append(OPTION_CHAIN_LEVELS, OPTION_CHAIN_LEVELS_HEADER, optionChainRows(decisionKey, request));
        } catch (IOException ex) {
            log.warn("Strategy signal CSV write failed: {}", ex.getMessage());
        }
    }

    /**
     * Records a signal from any additional strategy (SCALPING, VOLATILITY_BREAKOUT, etc.)
     * into the same entry-signals.csv. Fields not available are written as empty.
     */
    public synchronized void recordAdditionalStrategy(
            String strategyType,
            StrategyDecision decision,
            boolean executed,
            String executionStage,
            Integer positionQuantity,
            BigDecimal estimatedCost,
            List<Candle> underlyingCandles,
            Double scalpEma9, Double scalpEma21, String scalpCrossType, Integer scalpConfirmCount,
            Double bbUpperBand, Double bbLowerBand, Double bbBandwidth, Boolean bbSqueeze, Double ivRank
    ) {
        try {
            Files.createDirectories(OUTPUT.getParent());
            Candle latestUnderlying = last(underlyingCandles);
            StrategyConfig config = resolveConfig(strategyType);
            String decisionKey = additionalDecisionKey(strategyType, decision);
            String signalId = java.util.UUID.randomUUID().toString().substring(0, 12);
            String row = String.join(",",
                    csv(decisionKey),
                    csv(signalId),
                    csv(IstDateTimes.formatInstant(decision.timestamp())),
                    csv(java.time.LocalTime.now(java.time.ZoneId.of("Asia/Kolkata")).toString()),
                    csv(strategyType),
                    csv(decision.underlying()),
                    csv(decision.signalType()),
                    csv(decision.optionType().map(Enum::name).orElse(null)),
                    csv(properties.marketDataMode()),
                    csv(properties.executionMode()),
                    csv(null), csv(null), csv(null),
                    csv(config.getStopLossPercent()),
                    csv(config.getTargetPercent()),
                    csv(config.getTrailingStopActivationPercent()),
                    csv(config.getTrailingGapPercent()),
                    csv(properties.risk().maxRiskPerTradePercent()),
                    csv(properties.risk().totalCapital()),
                    csv(properties.risk().maxOpenTrades()),
                    csv(null), csv(null), csv(null),
                    csv(null), csv(null), csv(null), csv(null),
                    csv(decision.selectedInstrumentKey().orElse(null)),
                    csv(decision.selectedStrike().orElse(null)),
                    csv(decision.underlyingPrice()),
                    csv(null),
                    csv(decision.optionPrice().orElse(null)),
                    csv(null),
                    csv(decision.optionOpenInterest().orElse(null)),
                    csv(null),
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
                    csv(scalpEma9), csv(scalpEma21), csv(scalpCrossType), csv(scalpConfirmCount),
                    csv(bbUpperBand), csv(bbLowerBand), csv(bbBandwidth), csv(bbSqueeze), csv(ivRank),
                    csv(decision.confidenceScore()),
                    csv(String.join("; ", decision.reasons()))
            ) + System.lineSeparator();
            append(OUTPUT, HEADER, row);
        } catch (IOException ex) {
            log.warn("Additional strategy signal CSV write failed: {}", ex.getMessage());
        }
    }

    /**
     * Records a NO_TRADE from any additional strategy into the same entry-signals.csv.
     */
    public synchronized void recordAdditionalNoTrade(
            String strategyType,
            String underlying,
            BigDecimal spotPrice,
            String reason,
            List<Candle> underlyingCandles
    ) {
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
                    csv(null), csv(null),
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
                    csv(null), csv(null), csv(null), csv(null), // scalp
                    csv(null), csv(null), csv(null), csv(null), csv(null), // BB/IV
                    csv(null),
                    csv(reason)
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
            boolean timePassed
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
                csv(null), csv(null), csv(null), csv(null), csv(null), // BB/IV fields
                csv(decision.confidenceScore()),
                csv(String.join("; ", decision.reasons()))
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

    private StrategyConfig resolveConfig(String strategyType) {
        try {
            StrategyType type = StrategyType.valueOf(strategyType);
            List<StrategyConfig> configs = strategyConfigService.getEnabled();
            return configs.stream()
                    .filter(c -> c.getStrategyType() == type)
                    .findFirst()
                    .orElse(strategyConfigService.getDirectionalBuyConfig());
        } catch (Exception e) {
            return strategyConfigService.getDirectionalBuyConfig();
        }
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
}
