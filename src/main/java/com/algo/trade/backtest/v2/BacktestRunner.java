package com.algo.trade.backtest.v2;

import com.algo.trade.backtest.BacktestMetrics;
import com.algo.trade.backtest.BacktestRunResult;
import com.algo.trade.backtest.BacktestTrade;
import com.algo.trade.backtest.EquityCurvePoint;
import com.algo.trade.data.ChainSnapshot;
import com.algo.trade.domain.OptionType;
import com.algo.trade.strategy.StrategyConfig;
import com.algo.trade.strategy.StrategyType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;

/**
 * Runs a single strategy backtest against a series of ChainSnapshots.
 * Manages position lifecycle: entry evaluation → position tracking → exit evaluation.
 */
public class BacktestRunner {

    private static final Logger log = LoggerFactory.getLogger(BacktestRunner.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final int MAX_HISTORY_SIZE = 50;
    private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 15);

    private final StrategyAdapter adapter;
    private final TradeSimulator simulator;
    private final SnapshotValidator validator;
    private final int lotSize;

    public BacktestRunner(StrategyAdapter adapter, TradeSimulator simulator,
                          SnapshotValidator validator, int lotSize) {
        this.adapter = adapter;
        this.simulator = simulator;
        this.validator = validator;
        this.lotSize = lotSize;
    }

    /**
     * Run the strategy against a list of snapshots.
     *
     * @param snapshots Chronologically ordered snapshots
     * @param config    Strategy configuration
     * @param runId     Unique run identifier
     * @param outputDir Output directory for results
     * @return Backtest result with trades, metrics, and equity curve
     */
    public BacktestRunResult run(List<ChainSnapshot> snapshots, StrategyConfig config,
                                  String runId, Path outputDir) {
        List<BacktestTrade> trades = new ArrayList<>();
        LinkedList<ChainSnapshot> history = new LinkedList<>();
        Position openPosition = null;
        int totalSignals = 0;
        int rejectedSignals = 0;

        for (ChainSnapshot snapshot : snapshots) {
            // Skip invalid snapshots
            if (!validator.isUsable(snapshot)) {
                continue;
            }

            // Check for end-of-day forced exit
            if (openPosition != null && isEndOfDay(snapshot)) {
                BacktestTrade trade = forceExit(openPosition, snapshot, "END_OF_DAY");
                if (trade != null) {
                    trades.add(trade);
                }
                openPosition = null;
            }

            // Evaluate exit if position is open
            if (openPosition != null) {
                double currentPrice = simulator.getCurrentPrice(
                        snapshot, openPosition.strike(), openPosition.optionType());
                if (currentPrice > 0) {
                    openPosition.updatePrice(currentPrice,
                            config.getTrailingStopActivationPercent().doubleValue());
                }

                Optional<ExitSignal> exitSignal = adapter.evaluateExit(openPosition, snapshot, config);
                if (exitSignal.isPresent()) {
                    double exitPrice = simulator.simulateExitFill(
                            snapshot, openPosition.strike(), openPosition.optionType());
                    if (exitPrice <= 0) exitPrice = exitSignal.get().exitPrice();

                    BacktestTrade trade = simulator.createTrade(
                            openPosition, exitPrice, exitSignal.get().timestamp(),
                            exitSignal.get().reason().name() + ": " + exitSignal.get().detail());
                    trades.add(trade);
                    openPosition = null;
                }
            }

            // Evaluate entry if no position is open
            if (openPosition == null && !isEndOfDay(snapshot)) {
                Optional<StrategySignal> entrySignal = adapter.evaluateEntry(snapshot, history, config);
                if (entrySignal.isPresent()) {
                    totalSignals++;
                    StrategySignal signal = entrySignal.get();

                    // Validate entry price is reasonable
                    if (signal.entryPrice() > 0) {
                        openPosition = simulator.openPosition(signal, snapshot, lotSize);
                        log.debug("[BacktestRunner] Opened position: strategy={}, strike={}, price={}, reason={}",
                                adapter.strategyType(), signal.strike(), openPosition.entryPrice(), signal.reason());
                    } else {
                        rejectedSignals++;
                    }
                }
            }

            // Maintain history window
            history.addFirst(snapshot);
            if (history.size() > MAX_HISTORY_SIZE) {
                history.removeLast();
            }
        }

        // Force close any remaining position at end of data
        if (openPosition != null && !snapshots.isEmpty()) {
            ChainSnapshot lastSnapshot = snapshots.get(snapshots.size() - 1);
            BacktestTrade trade = forceExit(openPosition, lastSnapshot, "END_OF_DATA");
            if (trade != null) {
                trades.add(trade);
            }
        }

        // Calculate metrics
        BacktestMetrics metrics = BacktestMetrics.compute(trades, totalSignals, rejectedSignals);
        List<EquityCurvePoint> equityCurve = buildEquityCurve(trades);

        Path tradesCsv = outputDir.resolve("trades.csv");
        Path metricsCsv = outputDir.resolve("metrics.csv");
        Path equityCurveCsv = outputDir.resolve("equity-curve.csv");
        Path reportHtml = outputDir.resolve("report.html");

        return new BacktestRunResult(runId, Instant.now(), metrics, trades, equityCurve,
                outputDir, tradesCsv, metricsCsv, equityCurveCsv, reportHtml);
    }

    private boolean isEndOfDay(ChainSnapshot snapshot) {
        LocalTime time = snapshot.timestamp().atZone(IST).toLocalTime();
        return time.isAfter(MARKET_CLOSE) || time.equals(MARKET_CLOSE);
    }

    private BacktestTrade forceExit(Position position, ChainSnapshot snapshot, String reason) {
        double exitPrice = simulator.simulateExitFill(
                snapshot, position.strike(), position.optionType());
        if (exitPrice <= 0) {
            exitPrice = simulator.getCurrentPrice(snapshot, position.strike(), position.optionType());
        }
        if (exitPrice <= 0) return null;

        return simulator.createTrade(position, exitPrice, snapshot.timestamp(), reason);
    }

    private List<EquityCurvePoint> buildEquityCurve(List<BacktestTrade> trades) {
        List<EquityCurvePoint> curve = new ArrayList<>();
        BigDecimal equity = BigDecimal.ZERO;
        BigDecimal peak = BigDecimal.ZERO;

        for (BacktestTrade trade : trades) {
            equity = equity.add(trade.pnl());
            peak = peak.max(equity);
            BigDecimal drawdown = peak.subtract(equity);
            curve.add(new EquityCurvePoint(trade.exitTime(), equity, drawdown));
        }

        return curve;
    }
}
