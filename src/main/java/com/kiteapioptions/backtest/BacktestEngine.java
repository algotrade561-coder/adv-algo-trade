package com.kiteapioptions.backtest;

import com.kiteapioptions.config.TradingProperties;
import com.kiteapioptions.domain.Candle;
import com.kiteapioptions.execution.TrailingStopService;
import com.kiteapioptions.indicator.BreakoutDetector;
import com.kiteapioptions.indicator.VolumeSpikeDetector;
import com.kiteapioptions.indicator.VwapIndicator;
import com.kiteapioptions.marketdata.MockMarketDataGenerator;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
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
    private static final Logger log = LoggerFactory.getLogger(BacktestEngine.class);
    private final TradingProperties properties;
    private final CandleCsvReader candleCsvReader;
    private final BacktestCsvExporter csvExporter;
    private final MockMarketDataGenerator mockMarketDataGenerator;
    private final VwapIndicator vwapIndicator;
    private final VolumeSpikeDetector volumeSpikeDetector;
    private final BreakoutDetector breakoutDetector;
    private final TrailingStopService trailingStopService;

    public BacktestEngine(TradingProperties properties, MockMarketDataGenerator mockMarketDataGenerator,
                          VwapIndicator vwapIndicator, VolumeSpikeDetector volumeSpikeDetector,
                          BreakoutDetector breakoutDetector, TrailingStopService trailingStopService) {
        this.properties = properties;
        this.candleCsvReader = new CandleCsvReader();
        this.csvExporter = new BacktestCsvExporter();
        this.mockMarketDataGenerator = mockMarketDataGenerator;
        this.vwapIndicator = vwapIndicator;
        this.volumeSpikeDetector = volumeSpikeDetector;
        this.breakoutDetector = breakoutDetector;
        this.trailingStopService = trailingStopService;
    }

    public BacktestRunResult run() {
        try {
            log.info("Backtest started: from={}, to={}, timeframe={}, csvImportPath={}, outputDirectory={}",
                    properties.backtest().from(), properties.backtest().to(), properties.backtest().candleTimeframe(),
                    properties.backtest().csvImportPath(), properties.backtest().outputDirectory());
            List<Candle> candles = loadCandles();
            log.info("Backtest candles loaded: count={}", candles.size());
            String id = "BT-" + UUID.randomUUID();
            List<BacktestTrade> trades = replay(candles);
            BacktestMetrics metrics = metrics(trades);
            List<EquityCurvePoint> equityCurve = equityCurve(trades);
            Path outputDirectory = Path.of(properties.backtest().outputDirectory(), id);
            BacktestRunResult result = new BacktestRunResult(id, Instant.now(), metrics, trades, equityCurve,
                    outputDirectory, outputDirectory.resolve("trades.csv"),
                    outputDirectory.resolve("metrics.csv"), outputDirectory.resolve("equity-curve.csv"));
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

    private List<Candle> loadCandles() throws IOException {
        Path csvPath = Path.of(properties.backtest().csvImportPath());
        if (Files.exists(csvPath)) {
            log.info("Backtest loading candles from CSV: path={}", csvPath);
            List<Candle> candles = candleCsvReader.read(csvPath, properties.backtest().candleTimeframe());
            log.info("Backtest CSV candles loaded: path={}, count={}", csvPath, candles.size());
            return candles;
        }
        log.warn("Backtest CSV input missing. Falling back to generated mock candles: path={}, mockInstrumentKey={}, mockCandleCount={}",
                csvPath, properties.backtest().mockInstrumentKey(), properties.backtest().mockCandleCount());
        Instant from = properties.backtest().from().atTime(properties.entry().entryStartTime())
                .atZone(properties.timezone()).toInstant();
        return mockMarketDataGenerator.candles(properties.backtest().mockInstrumentKey(), from,
                properties.backtest().candleTimeframe(), properties.backtest().mockCandleCount());
    }

    private List<BacktestTrade> replay(List<Candle> candles) {
        List<BacktestTrade> trades = new ArrayList<>();
        OpenTrade openTrade = null;
        List<Candle> history = new ArrayList<>();

        for (Candle candle : candles) {
            history.add(candle);
            if (history.size() <= lookback()) {
                continue;
            }

            if (openTrade == null && withinEntryWindow(candle) && entrySignal(history)) {
                int quantity = quantity(candle.close());
                if (quantity > 0) {
                    openTrade = new OpenTrade(candle.instrumentKey(), candle.timestamp(), candle.close(), quantity,
                            candle.close(), Optional.empty(), "VWAP + breakout + volume spike");
                    log.info("Backtest trade opened: instrument={}, entryTime={}, entryPrice={}, quantity={}",
                            openTrade.instrumentKey(), openTrade.entryTime(), openTrade.entryPrice(), openTrade.quantity());
                }
                continue;
            }

            if (openTrade != null) {
                openTrade = openTrade.withHigh(openTrade.highestPrice().max(candle.high()));
                openTrade = openTrade.withTrailingStop(trailingStopService.nextStop(openTrade.entryPrice(),
                        openTrade.highestPrice(), openTrade.trailingStop()));
                Optional<String> exitReason = forcedExitDue(candle)
                        ? Optional.of("Configured forced square-off")
                        : exitReason(openTrade, candle);
                if (exitReason.isPresent()) {
                    log.info("Backtest trade closed: instrument={}, entryTime={}, exitTime={}, exitPrice={}, reason={}",
                            openTrade.instrumentKey(), openTrade.entryTime(), candle.timestamp(), candle.close(),
                            exitReason.get());
                    trades.add(toClosedTrade(openTrade, candle, exitReason.get()));
                    openTrade = null;
                }
            }
        }

        if (openTrade != null && !candles.isEmpty()) {
            Candle last = candles.getLast();
            log.info("Backtest trade closed at end of data: instrument={}, entryTime={}, exitTime={}, exitPrice={}",
                    openTrade.instrumentKey(), openTrade.entryTime(), last.timestamp(), last.close());
            trades.add(toClosedTrade(openTrade, last, "End of data square-off"));
        }
        return List.copyOf(trades);
    }

    private boolean entrySignal(List<Candle> history) {
        BigDecimal close = history.getLast().close();
        BigDecimal vwap = vwapIndicator.calculate(history);
        return close.compareTo(vwap) > 0
                && breakoutDetector.breaksAboveSwingHigh(history, properties.entry().breakoutLookback(),
                properties.entry().breakoutBufferPercent())
                && volumeSpikeDetector.hasSpike(history, properties.entry().volumeLookback(),
                properties.entry().volumeSpikeMultiplier());
    }

    private Optional<String> exitReason(OpenTrade openTrade, Candle candle) {
        BigDecimal stopPrice = openTrade.entryPrice().multiply(BigDecimal.ONE.subtract(
                properties.exit().stopLossPercent().movePointLeft(2)), MATH_CONTEXT);
        BigDecimal targetPrice = openTrade.entryPrice().multiply(BigDecimal.ONE.add(
                properties.exit().targetPercent().movePointLeft(2)), MATH_CONTEXT);
        if (candle.low().compareTo(stopPrice) <= 0) {
            return Optional.of("Hard stop loss");
        }
        if (candle.high().compareTo(targetPrice) >= 0) {
            return Optional.of("Target hit");
        }
        if (openTrade.trailingStop().isPresent()
                && trailingStopService.isStopHit(candle.close(), openTrade.trailingStop().get())) {
            return Optional.of("Trailing stop hit");
        }
        return Optional.empty();
    }

    private BacktestTrade toClosedTrade(OpenTrade openTrade, Candle exitCandle, String exitReason) {
        BigDecimal exitPrice = switch (exitReason) {
            case "Hard stop loss" -> openTrade.entryPrice().multiply(BigDecimal.ONE.subtract(
                    properties.exit().stopLossPercent().movePointLeft(2)), MATH_CONTEXT);
            case "Target hit" -> openTrade.entryPrice().multiply(BigDecimal.ONE.add(
                    properties.exit().targetPercent().movePointLeft(2)), MATH_CONTEXT);
            case "Trailing stop hit" -> openTrade.trailingStop().orElse(exitCandle.close());
            default -> exitCandle.close();
        };
        BigDecimal pnl = exitPrice.subtract(openTrade.entryPrice())
                .multiply(BigDecimal.valueOf(openTrade.quantity()), MATH_CONTEXT);
        return new BacktestTrade("BT-TRD-" + UUID.randomUUID(), openTrade.instrumentKey(), openTrade.entryTime(),
                exitCandle.timestamp(), openTrade.quantity(), openTrade.entryPrice(), exitPrice, pnl,
                openTrade.entryReason(), exitReason);
    }

    private int quantity(BigDecimal premium) {
        BigDecimal riskAmount = properties.risk().totalCapital()
                .multiply(properties.risk().maxRiskPerTradePercent(), MATH_CONTEXT)
                .divide(BigDecimal.valueOf(100), MATH_CONTEXT);
        BigDecimal lossPerUnit = premium.multiply(properties.exit().stopLossPercent(), MATH_CONTEXT)
                .divide(BigDecimal.valueOf(100), MATH_CONTEXT);
        if (lossPerUnit.signum() <= 0) {
            return 0;
        }
        int lotSize = properties.backtest().lotSize();
        int rawQuantity = riskAmount.divide(lossPerUnit, MATH_CONTEXT).intValue();
        return Math.max(0, (rawQuantity / lotSize) * lotSize);
    }

    private boolean withinEntryWindow(Candle candle) {
        LocalTime marketTime = LocalTime.ofInstant(candle.timestamp(), properties.timezone());
        return !marketTime.isBefore(properties.entry().entryStartTime())
                && !marketTime.isAfter(properties.entry().entryCutoffTime());
    }

    private boolean forcedExitDue(Candle candle) {
        LocalTime marketTime = LocalTime.ofInstant(candle.timestamp(), properties.timezone());
        return !marketTime.isBefore(properties.exit().forcedExitTime());
    }

    private int lookback() {
        return Math.max(properties.entry().breakoutLookback(), properties.entry().volumeLookback());
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
        return Map.copyOf(daily);
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
}
