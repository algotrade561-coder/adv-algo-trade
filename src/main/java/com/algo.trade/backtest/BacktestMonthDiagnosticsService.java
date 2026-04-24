package com.algo.trade.backtest;

import com.algo.trade.config.TradingProperties;
import com.algo.trade.domain.Candle;
import com.algo.trade.domain.OptionChainSnapshot;
import com.algo.trade.domain.OptionType;
import com.algo.trade.domain.Quote;
import com.algo.trade.domain.SignalType;
import com.algo.trade.domain.StrategyDecision;
import com.algo.trade.domain.Timeframe;
import com.algo.trade.domain.UnderlyingSymbol;
import com.algo.trade.strategy.RuleBasedOptionsStrategy;
import com.algo.trade.strategy.StrategyEvaluationRequest;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class BacktestMonthDiagnosticsService {

    private final TradingProperties properties;
    private final RuleBasedOptionsStrategy strategy;
    private final CandleCsvReader csvReader = new CandleCsvReader();

    public BacktestMonthDiagnosticsService(TradingProperties properties, RuleBasedOptionsStrategy strategy) {
        this.properties = properties;
        this.strategy = strategy;
    }

    public DiagnosticsResult diagnose(UnderlyingSymbol underlying, List<OptionType> optionTypes,
                                      Timeframe timeframe, LocalDate from, LocalDate to) {
        return diagnose(underlying, optionTypes, timeframe, from, to, properties);
    }

    public DiagnosticsResult diagnose(UnderlyingSymbol underlying, List<OptionType> optionTypes,
                                      Timeframe timeframe, LocalDate from, LocalDate to,
                                      TradingProperties activeProperties) {
        try {
            List<Candle> underlyingCandles = loadUnderlyingCandles(activeProperties, timeframe, from, to);
            Map<OptionType, SideDiagnostics> sides = new LinkedHashMap<>();
            for (OptionType optionType : optionTypes) {
                List<Candle> optionCandles = loadOptionCandles(activeProperties, underlying, optionType, timeframe, from, to);
                sides.put(optionType, diagnoseSide(activeProperties, underlying, optionType, underlyingCandles, optionCandles));
            }
            return new DiagnosticsResult(underlying, timeframe, from, to, sides);
        } catch (IOException ex) {
            throw new IllegalStateException("Backtest diagnostics failed", ex);
        }
    }

    private SideDiagnostics diagnoseSide(TradingProperties activeProperties, UnderlyingSymbol underlying,
                                         OptionType optionType, List<Candle> underlyingCandles,
                                         List<Candle> optionCandles) {
        int lookback = Math.max(activeProperties.entry().breakoutLookback(), activeProperties.entry().volumeLookback());
        Summary summary = new Summary();
        Map<LocalDate, DaySummary> days = new LinkedHashMap<>();
        Quote previousQuote = null;

        List<Candle> optionHistory = new ArrayList<>();
        for (Candle candle : optionCandles) {
            optionHistory.add(candle);
            List<Candle> underlyingHistory = historyUpTo(underlyingCandles, candle.timestamp());
            if (optionHistory.size() <= lookback || underlyingHistory.size() <= lookback) {
                previousQuote = quoteFromCandle(candle);
                continue;
            }

            StrategyDecision decision = evaluate(activeProperties, underlying, optionType, underlyingHistory,
                    optionHistory, candle, previousQuote);
            LocalDate tradingDate = LocalDate.ofInstant(candle.timestamp(), activeProperties.timezone());
            DaySummary day = days.computeIfAbsent(tradingDate, ignored -> new DaySummary());
            summary.evaluations++;
            day.evaluations++;

            if (decision.signalType() == (optionType == OptionType.CE ? SignalType.BUY_CE : SignalType.BUY_PE)) {
                summary.accepted++;
                day.accepted++;
            } else {
                summary.rejected++;
                day.rejected++;
            }
            for (String reason : decision.reasons()) {
                summary.reasonCounts.merge(reason, 1, Integer::sum);
                day.reasonCounts.merge(reason, 1, Integer::sum);
            }
            previousQuote = quoteFromCandle(candle);
        }

        return new SideDiagnostics(optionType, summary.evaluations, summary.accepted, summary.rejected,
                Map.copyOf(summary.reasonCounts), freezeDays(days));
    }

    private StrategyDecision evaluate(TradingProperties activeProperties, UnderlyingSymbol underlying, OptionType optionType,
                                      List<Candle> underlyingHistory, List<Candle> optionHistory, Candle candle,
                                      Quote previousQuote) {
        LocalTime marketTime = LocalTime.ofInstant(candle.timestamp(), activeProperties.timezone());
        boolean hasRealVolume = underlyingHistory.stream().anyMatch(c -> c.volume() > 0);
        List<Candle> adjustedUnderlying = hasRealVolume ? underlyingHistory : syntheticVolumeHistory(activeProperties, underlyingHistory);
        Quote selectedQuote = new Quote(candle.instrumentKey(), candle.timestamp(), candle.close(),
                candle.volume(), candle.openInterest(), Optional.empty(), Optional.empty(), Optional.empty());
        OptionChainSnapshot neutralChain = new OptionChainSnapshot(underlying, candle.timestamp(),
                adjustedUnderlying.getLast().close(), List.of());
        StrategyEvaluationRequest request = new StrategyEvaluationRequest(
                candle.timestamp(),
                marketTime,
                underlying,
                adjustedUnderlying,
                trendHistory(activeProperties, adjustedUnderlying),
                optionHistory,
                neutralChain,
                candle.instrumentKey(),
                null,
                activeProperties.backtest().lotSize(),
                optionType,
                selectedQuote,
                Optional.ofNullable(previousQuote)
        );
        return strategy.evaluateEntryWithoutRecording(request);
    }

    private List<Candle> loadUnderlyingCandles(TradingProperties activeProperties, Timeframe timeframe,
                                               LocalDate from, LocalDate to) throws IOException {
        return loadCandles(BacktestDataFileResolver.forTimeframe(activeProperties.backtest().csvImportPath(), timeframe),
                timeframe, from, to, activeProperties.timezone());
    }

    private List<Candle> loadOptionCandles(TradingProperties activeProperties, UnderlyingSymbol underlying,
                                           OptionType optionType, Timeframe timeframe, LocalDate from,
                                           LocalDate to) throws IOException {
        return loadCandles(BacktestDataFileResolver.forSelection(activeProperties.backtest().csvImportPath(),
                underlying, optionType, timeframe), timeframe, from, to, activeProperties.timezone());
    }

    private List<Candle> loadCandles(java.nio.file.Path path, Timeframe timeframe, LocalDate from, LocalDate to,
                                     ZoneId timezone) throws IOException {
        Instant fromInstant = from.atStartOfDay(timezone).toInstant();
        Instant toInstant = to.plusDays(1).atStartOfDay(timezone).toInstant();
        return csvReader.read(path, timeframe).stream()
                .filter(candle -> !candle.timestamp().isBefore(fromInstant) && candle.timestamp().isBefore(toInstant))
                .toList();
    }

    private List<Candle> historyUpTo(List<Candle> candles, Instant timestamp) {
        int endExclusive = 0;
        while (endExclusive < candles.size() && !candles.get(endExclusive).timestamp().isAfter(timestamp)) {
            endExclusive++;
        }
        return endExclusive == 0 ? List.of() : candles.subList(0, endExclusive);
    }

    private Quote quoteFromCandle(Candle candle) {
        return new Quote(candle.instrumentKey(), candle.timestamp(), candle.close(), candle.volume(),
                candle.openInterest(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    private List<Candle> syntheticVolumeHistory(TradingProperties activeProperties, List<Candle> history) {
        long latestVolume = Math.max(activeProperties.entry().minLiquidityVolume(), 1L);
        int latestIndex = history.size() - 1;
        List<Candle> adjusted = new ArrayList<>(history.size());
        for (int i = 0; i < history.size(); i++) {
            Candle c = history.get(i);
            adjusted.add(new Candle(c.instrumentKey(), c.timestamp(), c.timeframe(), c.open(), c.high(), c.low(),
                    c.close(), i == latestIndex ? latestVolume : 1L, c.openInterest()));
        }
        return List.copyOf(adjusted);
    }

    private List<Candle> trendHistory(TradingProperties activeProperties, List<Candle> history) {
        Timeframe trendTimeframe = activeProperties.entry().trendTimeframe();
        if (!activeProperties.entry().trendFilterEnabled()
                || history.isEmpty()
                || history.getFirst().timeframe() == trendTimeframe) {
            return history;
        }
        return aggregateCandles(history, trendTimeframe);
    }

    private List<Candle> aggregateCandles(List<Candle> candles, Timeframe targetTimeframe) {
        List<Candle> aggregated = new ArrayList<>();
        long targetSeconds = targetTimeframe.duration().toSeconds();
        String instrumentKey = null;
        Instant bucketStart = null;
        BigDecimal open = null;
        BigDecimal high = null;
        BigDecimal low = null;
        BigDecimal close = null;
        long volume = 0L;
        long openInterest = 0L;
        for (Candle candle : candles) {
            long bucketEpoch = (candle.timestamp().getEpochSecond() / targetSeconds) * targetSeconds;
            Instant currentBucket = Instant.ofEpochSecond(bucketEpoch);
            if (bucketStart == null || !bucketStart.equals(currentBucket)) {
                if (bucketStart != null) {
                    aggregated.add(new Candle(instrumentKey, bucketStart, targetTimeframe, open, high, low, close,
                            volume, openInterest));
                }
                instrumentKey = candle.instrumentKey();
                bucketStart = currentBucket;
                open = candle.open();
                high = candle.high();
                low = candle.low();
                close = candle.close();
                volume = candle.volume();
                openInterest = candle.openInterest();
                continue;
            }
            high = high.max(candle.high());
            low = low.min(candle.low());
            close = candle.close();
            volume += candle.volume();
            openInterest = candle.openInterest();
        }
        if (bucketStart != null) {
            aggregated.add(new Candle(instrumentKey, bucketStart, targetTimeframe, open, high, low, close, volume,
                    openInterest));
        }
        return List.copyOf(aggregated);
    }

    private Map<LocalDate, Map<String, Object>> freezeDays(Map<LocalDate, DaySummary> days) {
        Map<LocalDate, Map<String, Object>> output = new LinkedHashMap<>();
        for (Map.Entry<LocalDate, DaySummary> entry : days.entrySet()) {
            Map<String, Object> day = new LinkedHashMap<>();
            day.put("evaluations", entry.getValue().evaluations);
            day.put("accepted", entry.getValue().accepted);
            day.put("rejected", entry.getValue().rejected);
            day.put("reasonCounts", Map.copyOf(entry.getValue().reasonCounts));
            output.put(entry.getKey(), day);
        }
        return Map.copyOf(output);
    }

    public record DiagnosticsResult(
            UnderlyingSymbol underlying,
            Timeframe timeframe,
            LocalDate from,
            LocalDate to,
            Map<OptionType, SideDiagnostics> sides
    ) {
    }

    public record SideDiagnostics(
            OptionType optionType,
            int evaluations,
            int accepted,
            int rejected,
            Map<String, Integer> reasonCounts,
            Map<LocalDate, Map<String, Object>> days
    ) {
    }

    private static final class Summary {
        int evaluations;
        int accepted;
        int rejected;
        Map<String, Integer> reasonCounts = new LinkedHashMap<>();
    }

    private static final class DaySummary {
        int evaluations;
        int accepted;
        int rejected;
        Map<String, Integer> reasonCounts = new LinkedHashMap<>();
    }
}
