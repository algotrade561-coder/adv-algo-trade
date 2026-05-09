package com.algo.trade.backtest.v2;

import com.algo.trade.data.ChainSnapshot;
import com.algo.trade.strategy.StrategyConfig;
import com.algo.trade.strategy.StrategyType;

import java.util.List;
import java.util.Optional;

/**
 * Adapter interface for strategy-level backtesting using option chain snapshots.
 *
 * Each strategy implements this to evaluate entry/exit signals against
 * full option chain data (OI, Greeks, IV, volume) rather than just candles.
 */
public interface StrategyAdapter {

    /** Which strategy this adapter handles. */
    StrategyType strategyType();

    /**
     * Evaluate whether to enter a trade at the current snapshot.
     *
     * @param current  Current option chain snapshot
     * @param history  Previous snapshots (most recent first, up to 50)
     * @param config   Strategy configuration parameters
     * @return Entry signal if conditions are met, empty otherwise
     */
    Optional<StrategySignal> evaluateEntry(
            ChainSnapshot current,
            List<ChainSnapshot> history,
            StrategyConfig config
    );

    /**
     * Evaluate whether to exit an open position.
     *
     * @param position Open position to evaluate
     * @param current  Current option chain snapshot
     * @param config   Strategy configuration parameters
     * @return Exit signal if exit conditions are met, empty otherwise
     */
    Optional<ExitSignal> evaluateExit(
            Position position,
            ChainSnapshot current,
            StrategyConfig config
    );
}
