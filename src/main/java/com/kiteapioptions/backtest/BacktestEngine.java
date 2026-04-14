package com.kiteapioptions.backtest;

import com.kiteapioptions.config.TradingProperties;
import com.kiteapioptions.domain.Candle;
import com.kiteapioptions.domain.OptionChainLevel;
import com.kiteapioptions.domain.OptionChainSnapshot;
import com.kiteapioptions.domain.OptionType;
import com.kiteapioptions.domain.Quote;
import com.kiteapioptions.domain.SignalType;
import com.kiteapioptions.domain.StrategyDecision;
import com.kiteapioptions.domain.Timeframe;
import com.kiteapioptions.domain.UnderlyingSymbol;
import com.kiteapioptions.execution.TrailingStopService;
import com.kiteapioptions.strategy.RuleBasedOptionsStrategy;
import com.kiteapioptions.strategy.StrategyEvaluationRequest;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Replays historical candles and simulates a simple long-option strategy with configured exits.
 */
@Service
public class BacktestEngine {

    private static final MathContext MATH_CONTEXT = MathContext.DECIMAL64;
    private static final DateTimeFormatter RUN_TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final Logger log = LoggerFactory.getLogger(BacktestEngine.class);
    private final TradingProperties properties;
    private final CandleCsvReader candleCsvReader;
    private final BacktestCsvExporter csvExporter;
    private final RuleBasedOptionsStrategy strategy;
    private final TrailingStopService trailingStopService;

    public BacktestEngine(TradingProperties properties, RuleBasedOptionsStrategy strategy,
                          TrailingStopService trailingStopService) {
        this.properties = properties;
        this.candleCsvReader = new CandleCsvReader();
        this.csvExporter = new BacktestCsvExporter();
        this.strategy = strategy;
        this.trailingStopService = trailingStopService;
    }

    public BacktestRunResult run() {
        return run(defaultUnderlying(), properties.backtest().candleTimeframe(), OptionType.CE,
                properties.backtest().from(), properties.backtest().to());
    }

    public BacktestRunResult run(OptionType optionType) {
        return run(defaultUnderlying(), properties.backtest().candleTimeframe(), optionType,
                properties.backtest().from(), properties.backtest().to());
    }

    public BacktestRunResult run(UnderlyingSymbol underlying, OptionType optionType) {
        return run(underlying, properties.backtest().candleTimeframe(), optionType,
                properties.backtest().from(), properties.backtest().to());
    }

    public BacktestRunResult run(Timeframe candleTimeframe) {
        return run(defaultUnderlying(), candleTimeframe, OptionType.CE, properties.backtest().from(),
                properties.backtest().to());
    }

    public BacktestRunResult run(Timeframe candleTimeframe, OptionType optionType) {
        return run(defaultUnderlying(), candleTimeframe, optionType, properties.backtest().from(),
                properties.backtest().to());
    }

    public BacktestRunResult run(UnderlyingSymbol underlying, Timeframe candleTimeframe, OptionType optionType) {
        return run(underlying, candleTimeframe, optionType, properties.backtest().from(), properties.backtest().to());
    }

    public BacktestRunResult run(UnderlyingSymbol underlying, Timeframe candleTimeframe, OptionType optionType,
                                 LocalDate fromDate, LocalDate toDate) {
        return run(underlying, candleTimeframe, optionType, fromDate, toDate, null);
    }

    public BacktestRunResult run(UnderlyingSymbol underlying, Timeframe candleTimeframe, OptionType optionType,
                                 LocalDate fromDate, LocalDate toDate, RunOptions options) {
        TradingProperties activeProperties = options != null && options.properties() != null
                ? options.properties()
                : properties;
        RuleBasedOptionsStrategy activeStrategy = options != null && options.properties() != null
                ? strategyFor(activeProperties)
                : strategy;
        TrailingStopService activeTrailingStopService = options != null && options.properties() != null
                ? new TrailingStopService(activeProperties)
                : trailingStopService;
        try {
            log.info("Backtest started: underlying={}, from={}, to={}, timeframe={}, optionType={}, csvImportPath={}, outputDirectory={}",
                    underlying, fromDate, toDate, candleTimeframe, optionType,
                    inputPath(activeProperties, underlying, candleTimeframe, optionType),
                    outputDirectoryBase(activeProperties, options));
            List<Candle> candles = loadCandles(activeProperties, underlying, candleTimeframe, optionType, fromDate, toDate);
            validateTradableCandles(activeProperties, candles);
            log.info("Backtest candles loaded: count={}", candles.size());
            String id = runId(activeProperties, underlying, candleTimeframe, optionType, fromDate, toDate, options);
            List<BacktestTrade> trades = replay(activeProperties, activeStrategy, activeTrailingStopService,
                    underlying, candles, optionType);
            BacktestMetrics metrics = metrics(trades);
            List<EquityCurvePoint> equityCurve = equityCurve(trades);
            Path outputDirectory = Path.of(outputDirectoryBase(activeProperties, options), id);
            BacktestRunResult result = new BacktestRunResult(id, Instant.now(), metrics, trades, equityCurve,
                    outputDirectory, outputDirectory.resolve("trades.csv"),
                    outputDirectory.resolve("metrics.csv"), outputDirectory.resolve("equity-curve.csv"),
                    outputDirectory.resolve("report.html"));
            csvExporter.export(result);
            log.info("Backtest completed: id={}, totalTrades={}, winRatePercent={}, cumulativePnl={}, maxDrawdown={}, outputDirectory={}",
                    id, metrics.totalTrades(), metrics.winRatePercent(), metrics.cumulativePnl(),
                    metrics.maxDrawdown(), outputDirectory);
            return result;
        } catch (IOException ex) {
            log.warn("Backtest failed: {}", ex.getMessage());
            throw new IllegalStateException("Backtest failed", ex);
        }
    }

    private List<Candle> loadCandles(TradingProperties activeProperties, UnderlyingSymbol underlying,
                                     Timeframe candleTimeframe, OptionType optionType,
                                     LocalDate fromDate, LocalDate toDate) throws IOException {
        Path csvPath = inputPath(activeProperties, underlying, candleTimeframe, optionType);
        Instant fromInstant = fromDate.atStartOfDay(activeProperties.timezone()).toInstant();
        Instant toInstant = toDate.plusDays(1).atStartOfDay(activeProperties.timezone()).toInstant();
        if (Files.exists(csvPath)) {
            log.info("Backtest loading candles from CSV: path={}, requestedTimeframe={}, from={}, to={}", csvPath,
                    candleTimeframe, fromDate, toDate);
            List<Candle> all = candleCsvReader.read(csvPath, candleTimeframe);
            List<Candle> filtered = all.stream()
                    .filter(c -> c.timeframe() == candleTimeframe)
                    .filter(c -> !c.timestamp().isBefore(fromInstant) && c.timestamp().isBefore(toInstant))
                    .toList();
            log.info("Backtest CSV candles loaded: path={}, total={}, filtered={}", csvPath, all.size(),
                    filtered.size());
            return filtered;
        }
        throw new IllegalStateException("Backtest option CSV input is missing: " + csvPath
                + ". Download matching option data first for underlying=" + underlying
                + ", optionType=" + optionType + ", timeframe=" + candleTimeframe
                + ", from=" + fromDate + ", to=" + toDate + ".");
    }

    private Path inputPath(TradingProperties activeProperties, UnderlyingSymbol underlying, Timeframe candleTimeframe,
                           OptionType optionType) {
        return BacktestDataFileResolver.forSelection(activeProperties.backtest().csvImportPath(), underlying, optionType,
                candleTimeframe);
    }

    private UnderlyingSymbol defaultUnderlying() {
        return properties.symbols().underlyings().getFirst();
    }

    private String runId(TradingProperties activeProperties, UnderlyingSymbol underlying, Timeframe timeframe,
                         OptionType optionType, LocalDate fromDate, LocalDate toDate, RunOptions options) {
        String timestamp = RUN_TIMESTAMP_FORMAT.withZone(activeProperties.timezone()).format(Instant.now());
        String prefix = options == null || options.runLabel() == null || options.runLabel().isBlank()
                ? "BT"
                : sanitize(options.runLabel(), 24);
        return prefix
                + "-" + optionType.name().toLowerCase()
                + "-" + timeframeCode(timeframe)
                + "-" + compactDate(fromDate)
                + "-" + compactDate(toDate)
                + "-" + timestamp
                + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private void validateTradableCandles(TradingProperties activeProperties, List<Candle> candles) {
        if (candles.isEmpty()) {
            throw new IllegalStateException("Backtest input has no candles after date/timeframe filtering.");
        }

        BigDecimal riskAmount = riskAmount(activeProperties);
        BigDecimal stopLossFactor = activeProperties.exit().stopLossPercent().movePointLeft(2);
        if (riskAmount.signum() <= 0 || stopLossFactor.signum() <= 0) {
            throw new IllegalStateException("Backtest risk settings must allow positive risk per trade and stop loss.");
        }
        BigDecimal maxTradablePremium = riskAmount.divide(stopLossFactor.multiply(
                BigDecimal.valueOf(activeProperties.backtest().lotSize()), MATH_CONTEXT), MATH_CONTEXT);

        // Data quality summary
        BigDecimal minClose = candles.stream().map(Candle::close).min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        BigDecimal maxClose = candles.stream().map(Candle::close).max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        BigDecimal avgClose = candles.stream().map(Candle::close).reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(candles.size()), MATH_CONTEXT);
        long zeroVolumeCount = candles.stream().filter(c -> c.volume() == 0).count();
        long zeroPriceCount  = candles.stream().filter(c -> c.close().signum() == 0).count();
        long tradableCount   = candles.stream().filter(c -> quantity(activeProperties, c.close()) > 0).count();
        log.info("Backtest data validation: totalCandles={}, tradableCandles={}, minClose={}, maxClose={}, avgClose={}, maxTradablePremium={}, zeroVolumeCandles={}, zeroPriceCandles={}, lotSize={}, totalCapital={}, maxRiskPercent={}, stopLossPercent={}",
                candles.size(), tradableCount, minClose.setScale(2, java.math.RoundingMode.HALF_UP),
                maxClose.setScale(2, java.math.RoundingMode.HALF_UP),
                avgClose.setScale(2, java.math.RoundingMode.HALF_UP),
                maxTradablePremium.setScale(2, java.math.RoundingMode.HALF_UP),
                zeroVolumeCount, zeroPriceCount,
                activeProperties.backtest().lotSize(),
                activeProperties.risk().totalCapital(),
                activeProperties.risk().maxRiskPerTradePercent(),
                activeProperties.exit().stopLossPercent());

        if (tradableCount == 0) {
            throw new IllegalStateException("Backtest input cannot produce a tradable lot with current risk settings. "
                    + "Candle close range: [" + minClose.setScale(2, java.math.RoundingMode.HALF_UP)
                    + " - " + maxClose.setScale(2, java.math.RoundingMode.HALF_UP)
                    + "], max tradable premium for one lot: " + maxTradablePremium.setScale(2, java.math.RoundingMode.HALF_UP)
                    + ". This usually means the CSV contains underlying index/futures prices "
                    + "instead of option-premium candles, or risk capital is too small.");
        }
    }

    private List<BacktestTrade> replay(TradingProperties activeProperties, RuleBasedOptionsStrategy activeStrategy,
                                       TrailingStopService activeTrailingStopService, UnderlyingSymbol underlying,
                                       List<Candle> candles, OptionType optionType) {
        List<BacktestTrade> trades = new ArrayList<>();
        OpenTrade openTrade = null;
        List<Candle> history = new ArrayList<>();
        int zeroQuantitySignals = 0;

        for (Candle candle : candles) {
            history.add(candle);
            if (history.size() <= lookback(activeProperties)) {
                continue;
            }

            if (openTrade == null && withinEntryWindow(activeProperties, candle)
                    && entrySignal(activeProperties, activeStrategy, underlying, history, candle, optionType)) {
                int quantity = quantity(activeProperties, candle.close());
                if (quantity > 0) {
                    openTrade = new OpenTrade(candle.instrumentKey(), candle.timestamp(), candle.close(), quantity,
                            candle.close(), Optional.empty(), "VWAP + breakout + volume spike");
                    log.info("Backtest trade opened: instrument={}, entryTime={}, entryPrice={}, quantity={}",
                            openTrade.instrumentKey(), openTrade.entryTime(), openTrade.entryPrice(), openTrade.quantity());
                } else {
                    zeroQuantitySignals++;
                    log.warn("Backtest entry skipped because position size is zero: instrument={}, entryTime={}, price={}, lotSize={}, totalCapital={}, maxRiskPerTradePercent={}, stopLossPercent={}",
                            candle.instrumentKey(), candle.timestamp(), candle.close(), activeProperties.backtest().lotSize(),
                            activeProperties.risk().totalCapital(), activeProperties.risk().maxRiskPerTradePercent(),
                            activeProperties.exit().stopLossPercent());
                }
                continue;
            }

            if (openTrade != null) {
                openTrade = openTrade.withHigh(openTrade.highestPrice().max(candle.high()));
                openTrade = openTrade.withTrailingStop(activeTrailingStopService.nextStop(openTrade.entryPrice(),
                        openTrade.highestPrice(), openTrade.trailingStop()));
                Optional<String> exitReason = forcedExitDue(activeProperties, candle)
                        ? Optional.of("Configured forced square-off")
                        : maxHoldExceeded(activeProperties, openTrade, candle)
                        ? Optional.of("Max hold time exceeded")
                        : exitReason(activeProperties, activeTrailingStopService, openTrade, candle);
                if (exitReason.isPresent()) {
                    log.info("Backtest trade closed: instrument={}, entryTime={}, exitTime={}, exitPrice={}, reason={}",
                            openTrade.instrumentKey(), openTrade.entryTime(), candle.timestamp(), candle.close(),
                            exitReason.get());
                    trades.add(toClosedTrade(activeProperties, openTrade, candle, exitReason.get()));
                    openTrade = null;
                }
            }
        }

        if (openTrade != null && !candles.isEmpty()) {
            Candle last = candles.getLast();
            log.info("Backtest trade closed at end of data: instrument={}, entryTime={}, exitTime={}, exitPrice={}",
                    openTrade.instrumentKey(), openTrade.entryTime(), last.timestamp(), last.close());
            trades.add(toClosedTrade(activeProperties, openTrade, last, "End of data square-off"));
        }
        if (trades.isEmpty() && zeroQuantitySignals > 0) {
            throw new IllegalStateException("Backtest produced no trades because " + zeroQuantitySignals
                    + " entry signals had zero quantity. The CSV likely contains underlying index/futures prices "
                    + "instead of option-premium candles, or risk capital is too small for the configured lot size.");
        }
        return List.copyOf(trades);
    }

    /**
     * Routes entry evaluation through the same RuleBasedOptionsStrategy used by the live scanner.
     * Synthetic OI/IV/liquidity values keep missing market-depth fields from blocking price-action checks.
     */
    private boolean entrySignal(TradingProperties activeProperties, RuleBasedOptionsStrategy activeStrategy,
                                UnderlyingSymbol underlying, List<Candle> history, Candle candle,
                                OptionType optionType) {
        LocalTime marketTime = LocalTime.ofInstant(candle.timestamp(), activeProperties.timezone());
        // For index candles (e.g. NIFTY 50), volume can be 0 from Zerodha.
        boolean hasRealVolume = history.stream().anyMatch(c -> c.volume() > 0);
        long syntheticVolume = hasRealVolume ? candle.volume() : syntheticLatestVolume(activeProperties);
        Quote syntheticQuote = new Quote(candle.instrumentKey(), candle.timestamp(), candle.close(),
                syntheticVolume, candle.openInterest(), Optional.empty(), Optional.empty(), Optional.empty());
        List<Candle> adjustedHistory = hasRealVolume ? history : syntheticVolumeHistory(activeProperties, history);
        OptionChainSnapshot neutralChain = neutralOptionChain(activeProperties, underlying, candle);
        StrategyEvaluationRequest request = new StrategyEvaluationRequest(
                candle.timestamp(), marketTime, underlying,
                adjustedHistory, adjustedHistory,
                neutralChain,
                candle.instrumentKey(), null,
                optionType,
                syntheticQuote, Optional.empty());
        StrategyDecision decision = activeStrategy.evaluateEntryWithoutRecording(request);
        return decision.signalType() == (optionType == OptionType.CE ? SignalType.BUY_CE : SignalType.BUY_PE);
    }

    private long syntheticLatestVolume(TradingProperties activeProperties) {
        BigDecimal requiredForSpike = BigDecimal.ONE.multiply(activeProperties.entry().volumeSpikeMultiplier())
                .setScale(0, java.math.RoundingMode.UP);
        return Math.max(activeProperties.entry().minLiquidityVolume(), requiredForSpike.longValue() + 1);
    }

    private List<Candle> syntheticVolumeHistory(TradingProperties activeProperties, List<Candle> history) {
        long latestVolume = syntheticLatestVolume(activeProperties);
        int latestIndex = history.size() - 1;
        List<Candle> adjusted = new ArrayList<>(history.size());
        for (int i = 0; i < history.size(); i++) {
            Candle c = history.get(i);
            adjusted.add(new Candle(c.instrumentKey(), c.timestamp(), c.timeframe(), c.open(), c.high(), c.low(),
                    c.close(), i == latestIndex ? latestVolume : 1L, c.openInterest()));
        }
        return List.copyOf(adjusted);
    }

    private OptionChainSnapshot neutralOptionChain(TradingProperties activeProperties, UnderlyingSymbol underlying,
                                                   Candle candle) {
        long callOi = 1000L;
        long putOi = Math.max(callOi, activeProperties.entry().bullishImbalanceThreshold()
                .multiply(BigDecimal.valueOf(callOi), MATH_CONTEXT)
                .setScale(0, java.math.RoundingMode.CEILING)
                .longValue());
        OptionChainLevel neutralLevel = new OptionChainLevel(candle.close(), callOi, putOi, 0L, 1L,
                candle.close(), candle.close());
        return new OptionChainSnapshot(underlying, candle.timestamp(), candle.close(), List.of(neutralLevel));
    }

    private Optional<String> exitReason(TradingProperties activeProperties, TrailingStopService activeTrailingStopService,
                                        OpenTrade openTrade, Candle candle) {
        BigDecimal stopPrice = openTrade.entryPrice().multiply(BigDecimal.ONE.subtract(
                activeProperties.exit().stopLossPercent().movePointLeft(2)), MATH_CONTEXT);
        BigDecimal targetPrice = openTrade.entryPrice().multiply(BigDecimal.ONE.add(
                activeProperties.exit().targetPercent().movePointLeft(2)), MATH_CONTEXT);
        if (candle.low().compareTo(stopPrice) <= 0) {
            return Optional.of("Hard stop loss");
        }
        if (candle.high().compareTo(targetPrice) >= 0) {
            return Optional.of("Target hit");
        }
        if (openTrade.trailingStop().isPresent()
                && activeTrailingStopService.isStopHit(candle.close(), openTrade.trailingStop().get())) {
            return Optional.of("Trailing stop hit");
        }
        return Optional.empty();
    }

    private BacktestTrade toClosedTrade(TradingProperties activeProperties, OpenTrade openTrade, Candle exitCandle,
                                        String exitReason) {
        BigDecimal exitPrice = switch (exitReason) {
            case "Hard stop loss" -> openTrade.entryPrice().multiply(BigDecimal.ONE.subtract(
                    activeProperties.exit().stopLossPercent().movePointLeft(2)), MATH_CONTEXT);
            case "Target hit" -> openTrade.entryPrice().multiply(BigDecimal.ONE.add(
                    activeProperties.exit().targetPercent().movePointLeft(2)), MATH_CONTEXT);
            case "Trailing stop hit" -> openTrade.trailingStop().orElse(exitCandle.close());
            default -> exitCandle.close();
        };
        BigDecimal pnl = exitPrice.subtract(openTrade.entryPrice())
                .multiply(BigDecimal.valueOf(openTrade.quantity()), MATH_CONTEXT);
        return new BacktestTrade("BT-TRD-" + UUID.randomUUID(), openTrade.instrumentKey(), openTrade.entryTime(),
                exitCandle.timestamp(), openTrade.quantity(), openTrade.entryPrice(), exitPrice, pnl,
                openTrade.entryReason(), exitReason);
    }

    private int quantity(TradingProperties activeProperties, BigDecimal premium) {
        BigDecimal riskAmount = riskAmount(activeProperties);
        BigDecimal lossPerUnit = premium.multiply(activeProperties.exit().stopLossPercent(), MATH_CONTEXT)
                .divide(BigDecimal.valueOf(100), MATH_CONTEXT);
        if (lossPerUnit.signum() <= 0) {
            return 0;
        }
        int lotSize = activeProperties.backtest().lotSize();
        int rawQuantity = riskAmount.divide(lossPerUnit, MATH_CONTEXT).intValue();
        return Math.max(0, (rawQuantity / lotSize) * lotSize);
    }

    private BigDecimal riskAmount(TradingProperties activeProperties) {
        return activeProperties.risk().totalCapital()
                .multiply(activeProperties.risk().maxRiskPerTradePercent(), MATH_CONTEXT)
                .divide(BigDecimal.valueOf(100), MATH_CONTEXT);
    }

    private boolean withinEntryWindow(TradingProperties activeProperties, Candle candle) {
        LocalTime marketTime = LocalTime.ofInstant(candle.timestamp(), activeProperties.timezone());
        return !marketTime.isBefore(activeProperties.entry().entryStartTime())
                && !marketTime.isAfter(activeProperties.entry().entryCutoffTime());
    }

    private boolean forcedExitDue(TradingProperties activeProperties, Candle candle) {
        LocalTime marketTime = LocalTime.ofInstant(candle.timestamp(), activeProperties.timezone());
        return !marketTime.isBefore(activeProperties.exit().forcedExitTime());
    }

    private boolean maxHoldExceeded(TradingProperties activeProperties, OpenTrade openTrade, Candle candle) {
        int maxHold = activeProperties.exit().maxHoldMinutes();
        if (maxHold <= 0) {
            return false;
        }
        return java.time.Duration.between(openTrade.entryTime(), candle.timestamp()).toMinutes() >= maxHold;
    }

    private int lookback(TradingProperties activeProperties) {
        return Math.max(activeProperties.entry().breakoutLookback(), activeProperties.entry().volumeLookback());
    }

    private BacktestMetrics metrics(List<BacktestTrade> trades) {
        BigDecimal cumulativePnl = trades.stream().map(BacktestTrade::pnl).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<BacktestTrade> winners = trades.stream().filter(trade -> trade.pnl().signum() > 0).toList();
        List<BacktestTrade> losers = trades.stream().filter(trade -> trade.pnl().signum() < 0).toList();
        BigDecimal winRate = trades.isEmpty() ? BigDecimal.ZERO
                : BigDecimal.valueOf(winners.size()).multiply(BigDecimal.valueOf(100), MATH_CONTEXT)
                .divide(BigDecimal.valueOf(trades.size()), MATH_CONTEXT);
        BigDecimal averageWin = average(winners);
        BigDecimal averageLoss = average(losers);
        BigDecimal expectancy = trades.isEmpty() ? BigDecimal.ZERO
                : cumulativePnl.divide(BigDecimal.valueOf(trades.size()), MATH_CONTEXT);
        return new BacktestMetrics(trades.size(), winRate, averageWin, averageLoss, expectancy,
                maxDrawdown(equityCurve(trades)), cumulativePnl, dailyPnl(trades));
    }

    private BigDecimal average(List<BacktestTrade> trades) {
        if (trades.isEmpty()) {
            return BigDecimal.ZERO;
        }
        return trades.stream().map(BacktestTrade::pnl).reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(trades.size()), MATH_CONTEXT);
    }

    private Map<String, BigDecimal> dailyPnl(List<BacktestTrade> trades) {
        Map<String, BigDecimal> daily = new LinkedHashMap<>();
        ZoneId zoneId = properties.timezone();
        for (BacktestTrade trade : trades) {
            LocalDate date = LocalDate.ofInstant(trade.exitTime(), zoneId);
            daily.merge(date.toString(), trade.pnl(), BigDecimal::add);
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(daily));
    }

    private List<EquityCurvePoint> equityCurve(List<BacktestTrade> trades) {
        List<EquityCurvePoint> points = new ArrayList<>();
        BigDecimal equity = BigDecimal.ZERO;
        BigDecimal peak = BigDecimal.ZERO;
        for (BacktestTrade trade : trades) {
            equity = equity.add(trade.pnl());
            peak = peak.max(equity);
            points.add(new EquityCurvePoint(trade.exitTime(), equity, peak.subtract(equity)));
        }
        return List.copyOf(points);
    }

    private BigDecimal maxDrawdown(List<EquityCurvePoint> equityCurve) {
        return equityCurve.stream().map(EquityCurvePoint::drawdown).max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
    }

    private record OpenTrade(
            String instrumentKey,
            Instant entryTime,
            BigDecimal entryPrice,
            int quantity,
            BigDecimal highestPrice,
            Optional<BigDecimal> trailingStop,
            String entryReason
    ) {
        OpenTrade withHigh(BigDecimal high) {
            return new OpenTrade(instrumentKey, entryTime, entryPrice, quantity, high, trailingStop, entryReason);
        }

        OpenTrade withTrailingStop(Optional<BigDecimal> stop) {
            return new OpenTrade(instrumentKey, entryTime, entryPrice, quantity, highestPrice, stop, entryReason);
        }
    }

    public record RunOptions(
            TradingProperties properties,
            String outputDirectoryBase,
            String runLabel
    ) {
    }

    private RuleBasedOptionsStrategy strategyFor(TradingProperties activeProperties) {
        return new RuleBasedOptionsStrategy(activeProperties,
                new com.kiteapioptions.indicator.VwapIndicator(),
                new com.kiteapioptions.indicator.EmaIndicator(),
                new com.kiteapioptions.indicator.VolumeSpikeDetector(),
                new com.kiteapioptions.indicator.BreakoutDetector(),
                new com.kiteapioptions.indicator.VolatilityFilter(),
                new com.kiteapioptions.indicator.OiChangeTracker(),
                new com.kiteapioptions.strategy.OptionChainAnalyzer(),
                null);
    }

    private String outputDirectoryBase(TradingProperties activeProperties, RunOptions options) {
        if (options != null && options.outputDirectoryBase() != null && !options.outputDirectoryBase().isBlank()) {
            return options.outputDirectoryBase();
        }
        return activeProperties.backtest().outputDirectory();
    }

    private String compactDate(LocalDate date) {
        return date == null ? "na" : date.format(DateTimeFormatter.BASIC_ISO_DATE);
    }

    private String timeframeCode(Timeframe timeframe) {
        return switch (timeframe) {
            case ONE_MINUTE -> "1m";
            case FIVE_MINUTE -> "5m";
            case FIFTEEN_MINUTE -> "15m";
        };
    }

    private String sanitize(String text, int maxLength) {
        String sanitized = text.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return sanitized.length() <= maxLength ? sanitized : sanitized.substring(0, maxLength).replaceAll("-+$", "");
    }
}
