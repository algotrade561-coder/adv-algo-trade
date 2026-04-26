package com.algo.trade.backtest;

import com.algo.trade.config.GlobalConfigService;
import com.algo.trade.config.TradingProperties;
import com.algo.trade.domain.Candle;
import com.algo.trade.domain.OptionChainSnapshot;
import com.algo.trade.domain.OptionType;
import com.algo.trade.domain.Quote;
import com.algo.trade.domain.SignalType;
import com.algo.trade.domain.StrategyDecision;
import com.algo.trade.domain.Timeframe;
import com.algo.trade.domain.UnderlyingSymbol;
import com.algo.trade.execution.TrailingStopService;
import com.algo.trade.strategy.RuleBasedOptionsStrategy;
import com.algo.trade.strategy.StrategyConfig;
import com.algo.trade.strategy.StrategyConfigService;
import com.algo.trade.strategy.StrategyEvaluationRequest;
import com.algo.trade.strategy.StrategyType;
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
    private final GlobalConfigService globalConfigService;
    private final StrategyConfigService strategyConfigService;
    private final CandleCsvReader candleCsvReader;
    private final BacktestCsvExporter csvExporter;
    private final RuleBasedOptionsStrategy strategy;
    private final TrailingStopService trailingStopService;
    private final com.algo.trade.strategy.ScalpingStrategy scalpingStrategy;
    private final com.algo.trade.strategy.VolatilityBreakoutStrategy volatilityBreakoutStrategy;

    public BacktestEngine(TradingProperties properties, GlobalConfigService globalConfigService,
                          StrategyConfigService strategyConfigService,
                          RuleBasedOptionsStrategy strategy,
                          TrailingStopService trailingStopService,
                          com.algo.trade.strategy.ScalpingStrategy scalpingStrategy,
                          com.algo.trade.strategy.VolatilityBreakoutStrategy volatilityBreakoutStrategy) {
        this.properties = properties;
        this.globalConfigService = globalConfigService;
        this.strategyConfigService = strategyConfigService;
        this.candleCsvReader = new CandleCsvReader();
        this.csvExporter = new BacktestCsvExporter();
        this.strategy = strategy;
        this.trailingStopService = trailingStopService;
        this.scalpingStrategy = scalpingStrategy;
        this.volatilityBreakoutStrategy = volatilityBreakoutStrategy;
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
                : propertiesFromGlobalConfig();

        // Override exit params with per-strategy StrategyConfig values
        activeProperties = overrideExitParams(activeProperties, resolveStrategyConfig(options));

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
            List<Candle> optionCandles = loadOptionCandles(activeProperties, underlying, candleTimeframe, optionType,
                    fromDate, toDate);
            List<Candle> underlyingCandles = loadUnderlyingCandles(activeProperties, candleTimeframe, fromDate, toDate);
            validateTradableCandles(activeProperties, optionCandles);
            log.info("Backtest candles loaded: optionCount={}, underlyingCount={}", optionCandles.size(),
                    underlyingCandles.size());
            String id = runId(activeProperties, underlying, candleTimeframe, optionType, fromDate, toDate, options);
            ReplayResult replayResult = replay(activeProperties, activeStrategy, activeTrailingStopService,
                    underlying, underlyingCandles, optionCandles, optionType,
                    options != null ? options.strategyType() : null);
            BacktestMetrics metrics = metrics(replayResult);
            List<EquityCurvePoint> equityCurve = equityCurve(replayResult.trades());
            Path outputDirectory = Path.of(outputDirectoryBase(activeProperties, options), id);
            BacktestRunResult result = new BacktestRunResult(id, Instant.now(), metrics, replayResult.trades(), equityCurve,
                    outputDirectory, outputDirectory.resolve("trades.csv"),
                    outputDirectory.resolve("metrics.csv"), outputDirectory.resolve("equity-curve.csv"),
                    outputDirectory.resolve("report.html"));
            csvExporter.export(result);
            log.info("Backtest completed: id={}, totalTrades={}, totalSignals={}, rejectedSignals={}, skippedWhileInTrade={}, winRatePercent={}, cumulativePnl={}, maxDrawdown={}, outputDirectory={}",
                    id, metrics.totalTrades(), metrics.totalSignals(), metrics.rejectedSignals(),
                    metrics.skippedWhileInTrade(), metrics.winRatePercent(), metrics.cumulativePnl(),
                    metrics.maxDrawdown(), outputDirectory);
            return result;
        } catch (IOException ex) {
            log.warn("Backtest failed: {}", ex.getMessage());
            throw new IllegalStateException("Backtest failed", ex);
        }
    }

    private List<Candle> loadOptionCandles(TradingProperties activeProperties, UnderlyingSymbol underlying,
                                           Timeframe candleTimeframe, OptionType optionType,
                                           LocalDate fromDate, LocalDate toDate) throws IOException {
        Path csvPath = inputPath(activeProperties, underlying, candleTimeframe, optionType);
        return loadCandles(activeProperties, csvPath, candleTimeframe, fromDate, toDate, "option");
    }

    private List<Candle> loadUnderlyingCandles(TradingProperties activeProperties, Timeframe candleTimeframe,
                                               LocalDate fromDate, LocalDate toDate) throws IOException {
        Path csvPath = BacktestDataFileResolver.forTimeframe(activeProperties.backtest().csvImportPath(),
                candleTimeframe);
        return loadCandles(activeProperties, csvPath, candleTimeframe, fromDate, toDate, "underlying");
    }

    private List<Candle> loadCandles(TradingProperties activeProperties, Path csvPath, Timeframe candleTimeframe,
                                     LocalDate fromDate, LocalDate toDate,
                                     String datasetLabel) throws IOException {
        Instant fromInstant = fromDate.atStartOfDay(activeProperties.timezone()).toInstant();
        Instant toInstant = toDate.plusDays(1).atStartOfDay(activeProperties.timezone()).toInstant();
        if (Files.exists(csvPath)) {
            log.info("Backtest loading {} candles from CSV: path={}, requestedTimeframe={}, from={}, to={}",
                    datasetLabel, csvPath, candleTimeframe, fromDate, toDate);
            List<Candle> all = candleCsvReader.read(csvPath, candleTimeframe);
            List<Candle> filtered = all.stream()
                    .filter(c -> c.timeframe() == candleTimeframe)
                    .filter(c -> !c.timestamp().isBefore(fromInstant) && c.timestamp().isBefore(toInstant))
                    .toList();
            log.info("Backtest {} CSV candles loaded: path={}, total={}, filtered={}", datasetLabel, csvPath,
                    all.size(), filtered.size());
            return filtered;
        }
        throw new IllegalStateException("Backtest " + datasetLabel + " CSV input is missing: " + csvPath
                + ". Download matching " + datasetLabel + " data first for timeframe=" + candleTimeframe
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

    private record ReplayResult(List<BacktestTrade> trades, int totalSignals, int rejectedSignals,
                                   int skippedWhileInTrade) {}

    private ReplayResult replay(TradingProperties activeProperties, RuleBasedOptionsStrategy activeStrategy,
                                       TrailingStopService activeTrailingStopService, UnderlyingSymbol underlying,
                                       List<Candle> underlyingCandles, List<Candle> optionCandles,
                                       OptionType optionType) {
        return replay(activeProperties, activeStrategy, activeTrailingStopService, underlying,
                underlyingCandles, optionCandles, optionType, null);
    }

    private ReplayResult replay(TradingProperties activeProperties, RuleBasedOptionsStrategy activeStrategy,
                                       TrailingStopService activeTrailingStopService, UnderlyingSymbol underlying,
                                       List<Candle> underlyingCandles, List<Candle> optionCandles,
                                       OptionType optionType, String strategyType) {
        List<BacktestTrade> trades = new ArrayList<>();
        OpenTrade openTrade = null;
        List<Candle> optionHistory = new ArrayList<>();
        int zeroQuantitySignals = 0;
        int totalSignals = 0;
        int skippedWhileInTrade = 0;
        Quote previousSelectedQuote = null;
        String entryLabel = strategyType != null ? strategyType : "DIRECTIONAL_BUY";

        for (Candle candle : optionCandles) {
            optionHistory.add(candle);
            List<Candle> underlyingHistory = historyUpTo(underlyingCandles, candle.timestamp());
            if (optionHistory.size() <= lookback(activeProperties) || underlyingHistory.size() <= lookback(activeProperties)) {
                previousSelectedQuote = quoteFromCandle(candle);
                continue;
            }

            boolean signalFired = withinEntryWindow(activeProperties, candle)
                    && entrySignalForStrategy(activeProperties, activeStrategy, underlying, underlyingHistory, optionHistory,
                    candle, optionType, previousSelectedQuote, strategyType);

            if (signalFired) {
                totalSignals++;
            }

            if (openTrade == null && signalFired) {
                int quantity = quantity(activeProperties, candle.close());
                if (quantity > 0) {
                    openTrade = new OpenTrade(candle.instrumentKey(), candle.timestamp(), candle.close(), quantity,
                            candle.close(), Optional.empty(), entryLabel);
                    log.info("Backtest trade opened: instrument={}, entryTime={}, entryPrice={}, quantity={}",
                            openTrade.instrumentKey(), openTrade.entryTime(), openTrade.entryPrice(), openTrade.quantity());
                } else {
                    zeroQuantitySignals++;
                    log.warn("Backtest entry skipped because position size is zero: instrument={}, entryTime={}, price={}, lotSize={}, totalCapital={}, maxRiskPerTradePercent={}, stopLossPercent={}",
                            candle.instrumentKey(), candle.timestamp(), candle.close(), activeProperties.backtest().lotSize(),
                            activeProperties.risk().totalCapital(), activeProperties.risk().maxRiskPerTradePercent(),
                            activeProperties.exit().stopLossPercent());
                }
                previousSelectedQuote = quoteFromCandle(candle);
                continue;
            }

            if (openTrade != null && signalFired) {
                skippedWhileInTrade++;
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
            previousSelectedQuote = quoteFromCandle(candle);
        }

        if (openTrade != null && !optionCandles.isEmpty()) {
            Candle last = optionCandles.getLast();
            log.info("Backtest trade closed at end of data: instrument={}, entryTime={}, exitTime={}, exitPrice={}",
                    openTrade.instrumentKey(), openTrade.entryTime(), last.timestamp(), last.close());
            trades.add(toClosedTrade(activeProperties, openTrade, last, "End of data square-off"));
        }
        if (trades.isEmpty() && zeroQuantitySignals > 0) {
            throw new IllegalStateException("Backtest produced no trades because " + zeroQuantitySignals
                    + " entry signals had zero quantity. The CSV likely contains underlying index/futures prices "
                    + "instead of option-premium candles, or risk capital is too small for the configured lot size.");
        }
        return new ReplayResult(List.copyOf(trades), totalSignals, zeroQuantitySignals, skippedWhileInTrade);
    }

    /**
     * Routes entry evaluation through the same RuleBasedOptionsStrategy used by the live scanner.
     * Synthetic OI/IV/liquidity values keep missing market-depth fields from blocking price-action checks.
     */
    private boolean entrySignal(TradingProperties activeProperties, RuleBasedOptionsStrategy activeStrategy,
                                UnderlyingSymbol underlying, List<Candle> underlyingHistory, List<Candle> optionHistory,
                                Candle candle, OptionType optionType, Quote previousSelectedQuote) {
        return entrySignalForStrategy(activeProperties, activeStrategy, underlying, underlyingHistory, optionHistory,
                candle, optionType, previousSelectedQuote, null);
    }

    private boolean entrySignalForStrategy(TradingProperties activeProperties, RuleBasedOptionsStrategy activeStrategy,
                                UnderlyingSymbol underlying, List<Candle> underlyingHistory, List<Candle> optionHistory,
                                Candle candle, OptionType optionType, Quote previousSelectedQuote, String strategyType) {
        if ("SCALPING".equals(strategyType)) {
            return scalpingEntrySignal(underlying, underlyingHistory, candle, optionType);
        }
        if ("VOLATILITY_BREAKOUT".equals(strategyType)) {
            return volatilityBreakoutEntrySignal(underlying, underlyingHistory, candle, optionType);
        }
        // Default: DIRECTIONAL_BUY
        return directionalBuyEntrySignal(activeProperties, activeStrategy, underlying, underlyingHistory, optionHistory,
                candle, optionType, previousSelectedQuote);
    }

    private boolean directionalBuyEntrySignal(TradingProperties activeProperties, RuleBasedOptionsStrategy activeStrategy,
                                UnderlyingSymbol underlying, List<Candle> underlyingHistory, List<Candle> optionHistory,
                                Candle candle, OptionType optionType, Quote previousSelectedQuote) {
        LocalTime marketTime = LocalTime.ofInstant(candle.timestamp(), activeProperties.timezone());
        // For index candles (e.g. NIFTY 50), volume can be 0 from Zerodha.
        boolean hasRealVolume = underlyingHistory.stream().anyMatch(c -> c.volume() > 0);
        long syntheticVolume = hasRealVolume ? candle.volume() : syntheticLatestVolume(activeProperties);
        Quote syntheticQuote = new Quote(candle.instrumentKey(), candle.timestamp(), candle.close(),
                syntheticVolume, candle.openInterest(), Optional.empty(), Optional.empty(), Optional.empty());
        List<Candle> adjustedUnderlyingHistory = hasRealVolume
                ? underlyingHistory
                : syntheticVolumeHistory(activeProperties, underlyingHistory);
        List<Candle> trendHistory = trendHistory(activeProperties, adjustedUnderlyingHistory);
        OptionChainSnapshot neutralChain = neutralOptionChain(underlying, adjustedUnderlyingHistory.getLast().close(),
                candle.timestamp());
        StrategyEvaluationRequest request = new StrategyEvaluationRequest(
                candle.timestamp(), marketTime, underlying,
                adjustedUnderlyingHistory, trendHistory, optionHistory,
                neutralChain,
                candle.instrumentKey(), null,
                activeProperties.backtest().lotSize(),
                optionType,
                syntheticQuote, Optional.ofNullable(previousSelectedQuote));
        StrategyDecision decision = activeStrategy.evaluateEntryWithoutRecording(request);
        return decision.signalType() == (optionType == OptionType.CE ? SignalType.BUY_CE : SignalType.BUY_PE);
    }

    /** Scalping: EMA 9/21 crossover on underlying candles. */
    private boolean scalpingEntrySignal(UnderlyingSymbol underlying, List<Candle> underlyingHistory,
                                        Candle candle, OptionType optionType) {
        LocalTime marketTime = LocalTime.ofInstant(candle.timestamp(), properties.timezone());
        // ScalpingStrategy needs a StrategyConfig — create a default one for backtest
        var config = new com.algo.trade.strategy.StrategyConfig(
                com.algo.trade.strategy.StrategyType.SCALPING);
        Optional<StrategyDecision> signal = scalpingStrategy.evaluate(underlyingHistory, marketTime, config, underlying);
        if (signal.isEmpty()) return false;
        return signal.get().signalType() == (optionType == OptionType.CE ? SignalType.BUY_CE : SignalType.BUY_PE);
    }

    /** Volatility Breakout: Bollinger Band squeeze on underlying 15-min candles. */
    private boolean volatilityBreakoutEntrySignal(UnderlyingSymbol underlying, List<Candle> underlyingHistory,
                                                   Candle candle, OptionType optionType) {
        var config = resolveStrategyConfigByType(com.algo.trade.strategy.StrategyType.VOLATILITY_BREAKOUT);
        // IV rank approximation — use 30 (neutral-low) for backtest since we don't have live IV
        Optional<StrategyDecision> signal = volatilityBreakoutStrategy.evaluate(underlyingHistory, 30.0, config, underlying);
        if (signal.isEmpty()) return false;
        return signal.get().signalType() == (optionType == OptionType.CE ? SignalType.BUY_CE : SignalType.BUY_PE);
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

    private OptionChainSnapshot neutralOptionChain(UnderlyingSymbol underlying, BigDecimal underlyingPrice,
                                                   Instant timestamp) {
        return new OptionChainSnapshot(underlying, timestamp, underlyingPrice, List.of());
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

    private BacktestMetrics metrics(ReplayResult replayResult) {
        List<BacktestTrade> trades = replayResult.trades();
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

        // Profit factor: gross wins / |gross losses|
        BigDecimal grossWins = winners.stream().map(BacktestTrade::pnl).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal grossLosses = losers.stream().map(BacktestTrade::pnl).reduce(BigDecimal.ZERO, BigDecimal::add).abs();
        BigDecimal profitFactor = grossLosses.signum() == 0 ? BigDecimal.ZERO
                : grossWins.divide(grossLosses, MATH_CONTEXT);

        // Average hold duration in minutes
        BigDecimal avgHoldMinutes = trades.isEmpty() ? BigDecimal.ZERO
                : BigDecimal.valueOf(trades.stream()
                .mapToLong(t -> java.time.Duration.between(t.entryTime(), t.exitTime()).toMinutes())
                .sum())
                .divide(BigDecimal.valueOf(trades.size()), MATH_CONTEXT);

        // Max consecutive wins and losses
        int maxConsecWins = 0, maxConsecLosses = 0, consecWins = 0, consecLosses = 0;
        for (BacktestTrade trade : trades) {
            if (trade.pnl().signum() > 0) {
                consecWins++;
                consecLosses = 0;
                maxConsecWins = Math.max(maxConsecWins, consecWins);
            } else if (trade.pnl().signum() < 0) {
                consecLosses++;
                consecWins = 0;
                maxConsecLosses = Math.max(maxConsecLosses, consecLosses);
            }
        }

        // Risk-reward ratio: |avgWin| / |avgLoss|
        BigDecimal riskRewardRatio = averageLoss.abs().signum() == 0 ? BigDecimal.ZERO
                : averageWin.abs().divide(averageLoss.abs(), MATH_CONTEXT);

        // Signal-to-trade conversion rate
        BigDecimal signalConversion = replayResult.totalSignals() == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(trades.size()).multiply(BigDecimal.valueOf(100), MATH_CONTEXT)
                .divide(BigDecimal.valueOf(replayResult.totalSignals()), MATH_CONTEXT);

        return new BacktestMetrics(trades.size(), winRate, averageWin, averageLoss, expectancy,
                maxDrawdown(equityCurve(trades)), cumulativePnl, dailyPnl(trades),
                replayResult.totalSignals(), replayResult.rejectedSignals(), replayResult.skippedWhileInTrade(),
                profitFactor, avgHoldMinutes, maxConsecWins, maxConsecLosses, riskRewardRatio, signalConversion);
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

    /**
     * Resolves the per-strategy {@link StrategyConfig} for the given run options.
     * Falls back to DIRECTIONAL_BUY when strategyType is null.
     */
    private StrategyConfig resolveStrategyConfig(RunOptions options) {
        if (strategyConfigService == null) {
            // Test/backtest path without DB — return defaults
            StrategyType type = options != null && options.strategyType() != null
                    ? StrategyType.valueOf(options.strategyType())
                    : StrategyType.DIRECTIONAL_BUY;
            return new StrategyConfig(type);
        }
        StrategyType type = options != null && options.strategyType() != null
                ? StrategyType.valueOf(options.strategyType())
                : StrategyType.DIRECTIONAL_BUY;
        return strategyConfigService.getAll().stream()
                .filter(c -> c.getStrategyType() == type)
                .findFirst()
                .orElse(new StrategyConfig(type));
    }

    /** Resolve a StrategyConfig by explicit type — loads from DB if available, else defaults. */
    private StrategyConfig resolveStrategyConfigByType(StrategyType type) {
        if (strategyConfigService == null) {
            return new StrategyConfig(type);
        }
        return strategyConfigService.getAll().stream()
                .filter(c -> c.getStrategyType() == type)
                .findFirst()
                .orElse(new StrategyConfig(type));
    }

    /**
     * Creates a new {@link TradingProperties} with exit params (SL, target, maxHold, trailing,
     * forced exit time) overridden from the per-strategy {@link StrategyConfig}.
     */
    private TradingProperties overrideExitParams(TradingProperties base, StrategyConfig config) {
        // Use per-strategy squareoff time instead of GlobalConfig forcedExitTime
        LocalTime squareoffTime = LocalTime.of(config.getSquareoffHour(), config.getSquareoffMinute());
        var exit = new TradingProperties.Exit(
                config.getStopLossPercent(),
                config.getTargetPercent(),
                config.getTrailingStopActivationPercent(),
                config.getTrailingGapPercent(),
                squareoffTime,
                base.exit().partialProfitBookingEnabled(),
                config.getMaxHoldMinutes());
        return new TradingProperties(
                base.mode(), base.marketDataMode(), base.executionMode(),
                base.liveTradingEnabled(), base.timezone(),
                base.broker(), base.symbols(), base.strike(),
                base.entry(), exit, base.risk(),
                base.paper(), base.safety(), base.telegram(),
                base.algo(), base.backtest());
    }

    /**
     * Builds a {@link TradingProperties} that uses entry/exit/risk values from the DB-backed
     * {@link GlobalConfigService} while keeping infrastructure config (broker, symbols, paths, etc.)
     * from the YAML-bound {@code properties}. Used for the default backtest path (no RunOptions overrides).
     */
    private TradingProperties propertiesFromGlobalConfig() {
        var gc = globalConfigService.getCached();
        var entry = new TradingProperties.Entry(
                gc.getTimeframe(), gc.getTrendTimeframe(), gc.getEnabledOptionTypesAsList(),
                gc.isVwapFilterEnabled(), gc.isTrendFilterEnabled(),
                gc.getVolumeSpikeMultiplier(), gc.getBreakoutBufferPercent(),
                gc.getBreakoutLookback(), gc.getVolumeLookback(),
                gc.getBullishImbalanceThreshold(), gc.getBearishImbalanceThreshold(),
                gc.getMinLiquidityVolume(), gc.getMaxIvPercent(), gc.getMinSignalScorePercent(),
                gc.isCeOiSupportRequired(), gc.isPeOiSupportRequired(),
                gc.isCeOiDivergenceFilterEnabled(), gc.isPeOiDivergenceFilterEnabled(),
                gc.getOiDivergenceMultiplier(), gc.getOiDivergenceMinChange(),
                gc.getCeBreakoutConfirmationCandles(), gc.getPeBreakoutConfirmationCandles(),
                gc.getEntryStartTimeAsLocalTime(), gc.getEntryCutoffTimeAsLocalTime(),
                gc.isAllowFirstMinutesEntry(), gc.getNoEntryFirstMinutes(),
                gc.isRsiFilterEnabled(), gc.getRsiPeriod(),
                gc.getRsiCeBuyThreshold(), gc.getRsiPeSellThreshold());
        var exit = new TradingProperties.Exit(
                gc.getStopLossPercent(), gc.getTargetPercent(),
                gc.getTrailingStopActivationPercent(), gc.getTrailingGapPercent(),
                gc.getForcedExitTimeAsLocalTime(), gc.isPartialProfitBookingEnabled(),
                gc.getMaxHoldMinutes());
        var risk = new TradingProperties.Risk(
                gc.getTotalCapital(), gc.getMaxRiskPerTradePercent(), gc.getMaxDailyLossPercent(),
                gc.getMaxTradesPerDay(), gc.getMaxOrdersPerDay(), gc.getMaxConsecutiveLosses(),
                gc.getMaxOpenTrades(), gc.getSameInstrumentReentryMinPriceMovePercent(),
                gc.getCooldownMinutes(), gc.getDailyProfitTarget());
        return new TradingProperties(
                properties.mode(), properties.marketDataMode(), properties.executionMode(),
                properties.liveTradingEnabled(), properties.timezone(),
                properties.broker(), properties.symbols(), properties.strike(),
                entry, exit, risk,
                properties.paper(), properties.safety(), properties.telegram(),
                properties.algo(), properties.backtest());
    }

    public record RunOptions(
            TradingProperties properties,
            String outputDirectoryBase,
            String runLabel,
            String strategyType  // null = DIRECTIONAL_BUY, or "SCALPING", "VOLATILITY_BREAKOUT"
    ) {
        public RunOptions(TradingProperties properties, String outputDirectoryBase, String runLabel) {
            this(properties, outputDirectoryBase, runLabel, null);
        }
    }

    private RuleBasedOptionsStrategy strategyFor(TradingProperties activeProperties) {
        return new RuleBasedOptionsStrategy(activeProperties,
                new com.algo.trade.indicator.VwapIndicator(),
                new com.algo.trade.indicator.EmaIndicator(),
                new com.algo.trade.indicator.VolumeSpikeDetector(),
                new com.algo.trade.indicator.BreakoutDetector(),
                new com.algo.trade.indicator.VolatilityFilter(),
                new com.algo.trade.indicator.OiChangeTracker(),
                new com.algo.trade.strategy.OptionChainAnalyzer(),
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
            case ONE_HOUR -> "1h";
        };
    }

    private String sanitize(String text, int maxLength) {
        String sanitized = text.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return sanitized.length() <= maxLength ? sanitized : sanitized.substring(0, maxLength).replaceAll("-+$", "");
    }
}
