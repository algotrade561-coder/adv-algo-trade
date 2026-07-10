package com.algo.trade.backtest.v2;

import com.algo.trade.backtest.BacktestTrade;
import com.algo.trade.data.ChainSnapshot;
import com.algo.trade.domain.OptionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Simulates trade execution with realistic slippage and spread costs.
 * Uses bid/ask from the option chain snapshot for realistic fill prices.
 */
public class TradeSimulator {

    private static final double DEFAULT_SLIPPAGE_PERCENT = 0.1; // 0.1% slippage
    private static final int DEFAULT_LOT_SIZE_NIFTY = 65; // NSE circular Jan 2026 (was 75)
    private static final int DEFAULT_LOT_SIZE_BANKNIFTY = 30;

    private final double slippagePercent;

    public TradeSimulator() {
        this(DEFAULT_SLIPPAGE_PERCENT);
    }

    public TradeSimulator(double slippagePercent) {
        this.slippagePercent = slippagePercent;
    }

    /**
     * Simulate entry fill using ask price + slippage (buying at ask).
     */
    public double simulateEntryFill(ChainSnapshot snapshot, int strike, OptionType optionType) {
        Optional<ChainSnapshot.StrikeData> strikeData = findStrike(snapshot, strike);
        if (strikeData.isEmpty()) return 0;

        double askPrice = optionType == OptionType.CE
                ? strikeData.get().ceAsk()
                : strikeData.get().peAsk();

        // Add slippage for entry (buying at slightly above ask)
        return askPrice * (1 + slippagePercent / 100);
    }

    /**
     * Simulate exit fill using bid price - slippage (selling at bid).
     */
    public double simulateExitFill(ChainSnapshot snapshot, int strike, OptionType optionType) {
        Optional<ChainSnapshot.StrikeData> strikeData = findStrike(snapshot, strike);
        if (strikeData.isEmpty()) return 0;

        double bidPrice = optionType == OptionType.CE
                ? strikeData.get().ceBid()
                : strikeData.get().peBid();

        // Subtract slippage for exit (selling at slightly below bid)
        return bidPrice * (1 - slippagePercent / 100);
    }

    /**
     * Get current LTP for a strike/option type from snapshot.
     */
    public double getCurrentPrice(ChainSnapshot snapshot, int strike, OptionType optionType) {
        Optional<ChainSnapshot.StrikeData> strikeData = findStrike(snapshot, strike);
        if (strikeData.isEmpty()) return 0;

        return optionType == OptionType.CE
                ? strikeData.get().ceLTP()
                : strikeData.get().peLTP();
    }

    /**
     * Create a BacktestTrade from a completed position.
     */
    public BacktestTrade createTrade(Position position, double exitPrice, Instant exitTime, String exitReason) {
        int lotSize = getLotSize(position);
        double pnl = (exitPrice - position.entryPrice()) * lotSize;

        return new BacktestTrade(
                position.id(),
                buildInstrumentKey(position),
                position.entryTime(),
                exitTime,
                lotSize,
                BigDecimal.valueOf(position.entryPrice()),
                BigDecimal.valueOf(exitPrice),
                BigDecimal.valueOf(pnl),
                position.entryReason(),
                exitReason
        );
    }

    /**
     * Open a new position from a signal.
     */
    public Position openPosition(StrategySignal signal, ChainSnapshot snapshot, int lotSize) {
        double fillPrice = simulateEntryFill(snapshot, signal.strike(), signal.optionType());
        if (fillPrice <= 0) {
            fillPrice = signal.entryPrice(); // Fallback to signal price
        }

        return new Position(
                UUID.randomUUID().toString().substring(0, 8),
                signal.strategyType(),
                signal.optionType(),
                signal.strike(),
                fillPrice,
                snapshot.spot(),
                signal.timestamp(),
                signal.reason(),
                lotSize
        );
    }

    private Optional<ChainSnapshot.StrikeData> findStrike(ChainSnapshot snapshot, int strike) {
        return snapshot.strikes().stream()
                .filter(s -> s.strike() == strike)
                .findFirst();
    }

    private int getLotSize(Position position) {
        return position.quantity() > 0 ? position.quantity() : DEFAULT_LOT_SIZE_NIFTY;
    }

    private String buildInstrumentKey(Position position) {
        return "NFO:" + position.strategyType().name() + "_" +
                position.strike() + position.optionType().name();
    }
}
