package com.kiteapioptions.strategy;

import com.kiteapioptions.config.TradingProperties;
import com.kiteapioptions.domain.Candle;
import com.kiteapioptions.domain.Quote;
import com.kiteapioptions.domain.StrategyDecision;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Appends live strategy evaluation inputs and derived decisions for later tuning.
 */
@Service
public class StrategySignalCsvRecorder {

    private static final Logger log = LoggerFactory.getLogger(StrategySignalCsvRecorder.class);
    private static final Path OUTPUT = Path.of("reports", "entry-signals", "entry-signals.csv");
    private static final Path EVALUATIONS = Path.of("reports", "entry-signals", "entry-evaluations.csv");
    private static final Path CANDLES = Path.of("reports", "entry-signals", "entry-candles.csv");
    private static final Path OPTION_CHAIN_LEVELS = Path.of("reports", "entry-signals", "option-chain-levels.csv");
    private static final String HEADER = String.join(",",
            "decisionKey",
            "timestamp",
            "marketTime",
            "underlying",
            "signalType",
            "optionType",
            "marketDataMode",
            "executionMode",
            "trendFilterEnabled",
            "trendTimeframe",
            "rsiFilterEnabled",
            "stopLossPercent",
            "targetPercent",
            "trailingStopActivationPercent",
            "trailingGapPercent",
            "maxRiskPerTradePercent",
            "minSignalScorePercent",
            "volumeSpikeMultiplier",
            "breakoutBufferPercent",
            "breakoutLookback",
            "volumeLookback",
            "minLiquidityVolume",
            "maxIvPercent",
            "selectedInstrumentKey",
            "selectedStrike",
            "underlyingPrice",
            "underlyingTrendReference",
            "optionLastPrice",
            "optionVolume",
            "optionOpenInterest",
            "optionImpliedVolatility",
            "previousOptionLastPrice",
            "previousOptionOpenInterest",
            "underlyingCandleCount",
            "trendUnderlyingCandleCount",
            "optionCandleCount",
            "latestUnderlyingOpen",
            "latestUnderlyingHigh",
            "latestUnderlyingLow",
            "latestUnderlyingClose",
            "latestUnderlyingVolume",
            "latestOptionOpen",
            "latestOptionHigh",
            "latestOptionLow",
            "latestOptionClose",
            "latestOptionVolume",
            "resistanceStrike",
            "supportStrike",
            "nearbyPutCallOiImbalance",
            "nearbyCallOpenInterest",
            "nearbyPutOpenInterest",
            "resistanceCallOiChange",
            "supportPutOiChange",
            "vwapPassed",
            "breakoutPassed",
            "volumeSpike",
            "oiPassed",
            "ivPassed",
            "liquidityPassed",
            "timePassed",
            "confidenceScore",
            "reasons"
    ) + System.lineSeparator();
    private static final String EVALUATIONS_HEADER = String.join(",",
            "decisionKey",
            "timestamp",
            "marketTime",
            "underlying",
            "signalType",
            "optionType",
            "marketDataMode",
            "executionMode",
            "trendFilterEnabled",
            "trendTimeframe",
            "rsiFilterEnabled",
            "stopLossPercent",
            "targetPercent",
            "trailingStopActivationPercent",
            "trailingGapPercent",
            "maxRiskPerTradePercent",
            "minSignalScorePercent",
            "volumeSpikeMultiplier",
            "breakoutBufferPercent",
            "breakoutLookback",
            "volumeLookback",
            "minLiquidityVolume",
            "maxIvPercent",
            "selectedInstrumentKey",
            "selectedStrike",
            "underlyingPrice",
            "underlyingTrendReference",
            "optionLastPrice",
            "optionVolume",
            "optionOpenInterest",
            "optionImpliedVolatility",
            "previousOptionLastPrice",
            "previousOptionOpenInterest",
            "underlyingCandleCount",
            "trendUnderlyingCandleCount",
            "optionCandleCount",
            "resistanceStrike",
            "supportStrike",
            "nearbyPutCallOiImbalance",
            "nearbyCallOpenInterest",
            "nearbyPutOpenInterest",
            "resistanceCallOiChange",
            "supportPutOiChange",
            "vwapPassed",
            "breakoutPassed",
            "volumeSpike",
            "oiPassed",
            "ivPassed",
            "liquidityPassed",
            "timePassed",
            "confidenceScore",
            "reasons"
    ) + System.lineSeparator();
    private static final String CANDLES_HEADER = String.join(",",
            "decisionKey",
            "evaluationTimestamp",
            "underlying",
            "optionType",
            "selectedInstrumentKey",
            "candleRole",
            "candleTimestamp",
            "timeframe",
            "open",
            "high",
            "low",
            "close",
            "volume",
            "openInterest"
    ) + System.lineSeparator();
    private static final String OPTION_CHAIN_LEVELS_HEADER = String.join(",",
            "decisionKey",
            "evaluationTimestamp",
            "underlying",
            "optionType",
            "selectedInstrumentKey",
            "spotPrice",
            "strike",
            "callOpenInterest",
            "putOpenInterest",
            "callOpenInterestChange",
            "putOpenInterestChange",
            "callLastPrice",
            "putLastPrice"
    ) + System.lineSeparator();
    private final TradingProperties properties;

    public StrategySignalCsvRecorder(TradingProperties properties) {
        this.properties = properties;
    }

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
            append(OUTPUT, HEADER, row(request, decision, chain, vwap, breakoutPassed, oiPassed, ivPassed,
                    liquidityPassed, timePassed));
            append(EVALUATIONS, EVALUATIONS_HEADER, evaluationRow(decisionKey, request, decision, chain, vwap,
                    breakoutPassed, oiPassed, ivPassed, liquidityPassed, timePassed));
            append(CANDLES, CANDLES_HEADER, candleRows(decisionKey, request));
            append(OPTION_CHAIN_LEVELS, OPTION_CHAIN_LEVELS_HEADER, optionChainRows(decisionKey, request));
        } catch (IOException ex) {
            log.warn("Strategy signal CSV write failed: directory={}, message={}", OUTPUT.getParent(), ex.getMessage());
        }
    }

    private void append(Path path, String header, String rows) throws IOException {
        if (rows.isEmpty()) {
            return;
        }
        if (Files.notExists(path) || Files.size(path) == 0) {
            Files.writeString(path, header, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        Files.writeString(path, rows, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private String row(
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
        Candle underlying = request.underlyingCandles().isEmpty() ? null : request.underlyingCandles().getLast();
        Candle option = request.selectedOptionCandles().isEmpty() ? null : request.selectedOptionCandles().getLast();
        Quote quote = request.selectedOptionQuote();
        Quote previousQuote = request.previousSelectedOptionQuote().orElse(null);
        return String.join(",",
                csv(decisionKey(request)),
                csv(request.timestamp()),
                csv(request.marketTime()),
                csv(request.underlying()),
                csv(decision.signalType()),
                csv(request.optionType()),
                csv(properties.marketDataMode()),
                csv(properties.executionMode()),
                csv(properties.entry().trendFilterEnabled()),
                csv(properties.entry().trendTimeframe()),
                csv(properties.entry().rsiFilterEnabled()),
                csv(properties.exit().stopLossPercent()),
                csv(properties.exit().targetPercent()),
                csv(properties.exit().trailingStopActivationPercent()),
                csv(properties.exit().trailingGapPercent()),
                csv(properties.risk().maxRiskPerTradePercent()),
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
                csv(decision.confidenceScore()),
                csv(String.join("; ", decision.reasons()))
        ) + System.lineSeparator();
    }

    private String evaluationRow(
            String decisionKey,
            StrategyEvaluationRequest request,
            StrategyDecision decision,
            OptionChainAnalysis chain,
            BigDecimal trendReference,
            boolean breakoutPassed,
            boolean oiPassed,
            boolean ivPassed,
            boolean liquidityPassed,
            boolean timePassed
    ) {
        Quote quote = request.selectedOptionQuote();
        Quote previousQuote = request.previousSelectedOptionQuote().orElse(null);
        return String.join(",",
                csv(decisionKey),
                csv(request.timestamp()),
                csv(request.marketTime()),
                csv(request.underlying()),
                csv(decision.signalType()),
                csv(request.optionType()),
                csv(properties.marketDataMode()),
                csv(properties.executionMode()),
                csv(properties.entry().trendFilterEnabled()),
                csv(properties.entry().trendTimeframe()),
                csv(properties.entry().rsiFilterEnabled()),
                csv(properties.exit().stopLossPercent()),
                csv(properties.exit().targetPercent()),
                csv(properties.exit().trailingStopActivationPercent()),
                csv(properties.exit().trailingGapPercent()),
                csv(properties.risk().maxRiskPerTradePercent()),
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
                csv(trendReference),
                csv(quote.lastPrice()),
                csv(quote.volume()),
                csv(quote.openInterest()),
                csv(quote.impliedVolatility().orElse(null)),
                csv(previousQuote == null ? null : previousQuote.lastPrice()),
                csv(previousQuote == null ? null : previousQuote.openInterest()),
                csv(request.underlyingCandles().size()),
                csv(request.trendUnderlyingCandles().size()),
                csv(request.selectedOptionCandles().size()),
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
                csv(decision.confidenceScore()),
                csv(String.join("; ", decision.reasons()))
        ) + System.lineSeparator();
    }

    private String candleRows(String decisionKey, StrategyEvaluationRequest request) {
        StringBuilder rows = new StringBuilder();
        appendCandleRows(rows, decisionKey, request, "UNDERLYING", request.underlyingCandles());
        appendCandleRows(rows, decisionKey, request, "OPTION", request.selectedOptionCandles());
        return rows.toString();
    }

    private void appendCandleRows(
            StringBuilder rows,
            String decisionKey,
            StrategyEvaluationRequest request,
            String candleRole,
            Iterable<Candle> candles
    ) {
        for (Candle candle : candles) {
            rows.append(String.join(",",
                    csv(decisionKey),
                    csv(request.timestamp()),
                    csv(request.underlying()),
                    csv(request.optionType()),
                    csv(request.selectedInstrumentKey()),
                    csv(candleRole),
                    csv(candle.timestamp()),
                    csv(candle.timeframe()),
                    csv(candle.open()),
                    csv(candle.high()),
                    csv(candle.low()),
                    csv(candle.close()),
                    csv(candle.volume()),
                    csv(candle.openInterest())
            )).append(System.lineSeparator());
        }
    }

    private String optionChainRows(String decisionKey, StrategyEvaluationRequest request) {
        if (request.optionChainSnapshot() == null || request.optionChainSnapshot().levels().isEmpty()) {
            return "";
        }
        StringBuilder rows = new StringBuilder();
        for (var level : request.optionChainSnapshot().levels()) {
            rows.append(String.join(",",
                    csv(decisionKey),
                    csv(request.timestamp()),
                    csv(request.underlying()),
                    csv(request.optionType()),
                    csv(request.selectedInstrumentKey()),
                    csv(request.optionChainSnapshot().underlyingPrice()),
                    csv(level.strike()),
                    csv(level.callOpenInterest()),
                    csv(level.putOpenInterest()),
                    csv(level.callOpenInterestChange()),
                    csv(level.putOpenInterestChange()),
                    csv(level.callLastPrice()),
                    csv(level.putLastPrice())
            )).append(System.lineSeparator());
        }
        return rows.toString();
    }

    private String decisionKey(StrategyEvaluationRequest request) {
        String raw = request.timestamp() + "|" + request.underlying() + "|" + request.optionType() + "|"
                + request.selectedInstrumentKey();
        return Integer.toUnsignedString(raw.hashCode(), 16);
    }

    private String csv(Object value) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value);
        return '"' + text.replace("\"", "\"\"") + '"';
    }
}
