package com.algo.trade.backtest.v2.adapter;

import com.algo.trade.backtest.v2.ExitSignal;
import com.algo.trade.backtest.v2.Position;
import com.algo.trade.backtest.v2.StrategySignal;
import com.algo.trade.data.ChainSnapshot;
import com.algo.trade.domain.OptionType;
import com.algo.trade.strategy.StrategyConfig;
import com.algo.trade.strategy.StrategyType;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

/**
 * Premium Scalp adapter for V2 backtesting.
 *
 * Mirrors the live PremiumScalpService logic:
 * - Sell straddle at open (simulated as single short leg)
 * - Buy scalps on 0.15% spot momentum throughout the day
 * - Target ₹25, SL ₹15, max hold 15 min
 * - Max 20 scalps/day, 2-snapshot cooldown
 *
 * Since V2 BacktestRunner manages one position at a time, this adapter
 * simulates the BUY SCALP component only (the sell straddle theta is
 * calculated separately as a fixed daily income estimate).
 */
@Component
public class PremiumScalpAdapter extends AbstractStrategyAdapter {

    private static final double MIN_SPOT_MOVE_PERCENT = 0.15; // 0.15% trigger
    private static final int SCALP_TARGET_POINTS = 25;
    private static final int SCALP_STOPLOSS_POINTS = 15;
    private static final int MAX_SCALPS_PER_DAY = 20;
    private static final int COOLDOWN_SNAPSHOTS = 2; // 10 min cooldown at 5-min intervals
    private static final LocalTime SCALP_START = LocalTime.of(9, 25);
    private static final LocalTime SCALP_END = LocalTime.of(14, 45);

    private int scalpsToday = 0;
    private int snapshotsSinceLastTrade = Integer.MAX_VALUE;
    private LocalDate currentDay = null;

    @Override
    public StrategyType strategyType() {
        return StrategyType.PREMIUM_SCALP;
    }

    @Override
    public Optional<StrategySignal> evaluateEntry(ChainSnapshot current, List<ChainSnapshot> history,
                                                   StrategyConfig config) {
        if (history.isEmpty()) return Optional.empty();

        // Reset daily counter
        LocalDate today = current.timestamp().atZone(IST).toLocalDate();
        if (!today.equals(currentDay)) {
            currentDay = today;
            scalpsToday = 0;
        }

        // Max scalps per day
        if (scalpsToday >= MAX_SCALPS_PER_DAY) return Optional.empty();

        // Cooldown between trades
        snapshotsSinceLastTrade++;
        if (snapshotsSinceLastTrade < COOLDOWN_SNAPSHOTS) return Optional.empty();

        // Time window
        LocalTime marketTime = getMarketTime(current);
        if (marketTime.isBefore(SCALP_START) || marketTime.isAfter(SCALP_END)) {
            return Optional.empty();
        }

        // Momentum detection: spot moved 0.15% since last snapshot
        double movePercent = spotChangePercent(current, history.get(0));
        if (Math.abs(movePercent) < MIN_SPOT_MOVE_PERCENT) return Optional.empty();

        boolean bullish = movePercent > 0;
        OptionType optionType = bullish ? OptionType.CE : OptionType.PE;
        int targetStrike = current.atmStrike();

        double entryPrice = getOptionPrice(current, targetStrike, optionType);
        if (entryPrice <= 0 || entryPrice < 5) return Optional.empty();

        double iv = getOptionIV(current, targetStrike, optionType);
        double confidence = Math.min(1.0, 0.4 + Math.abs(movePercent) * 3);

        scalpsToday++;
        snapshotsSinceLastTrade = 0;

        return Optional.of(new StrategySignal(
                current.timestamp(), StrategyType.PREMIUM_SCALP, optionType,
                targetStrike, entryPrice, current.spot(),
                String.format("Premium scalp BUY #%d: %s, move=%.3f%%, strike=%d",
                        scalpsToday, optionType, movePercent, targetStrike),
                confidence, iv, getOptionDelta(current, targetStrike, optionType),
                getOptionOI(current, targetStrike, optionType),
                getOptionVolume(current, targetStrike, optionType)
        ));
    }

    @Override
    protected Optional<ExitSignal> evaluateStrategyExit(Position position, ChainSnapshot current,
                                                         StrategyConfig config, double currentPrice,
                                                         double pnlPercent) {
        // Fixed-point target/SL (not percentage-based)
        double pnlPoints = currentPrice - position.entryPrice();

        // Target hit: ₹25 profit
        if (pnlPoints >= SCALP_TARGET_POINTS) {
            return Optional.of(new ExitSignal(
                    current.timestamp(), currentPrice, ExitSignal.ExitReason.TARGET_HIT,
                    String.format("Scalp target: +%.1f pts (target %d)", pnlPoints, SCALP_TARGET_POINTS)));
        }

        // Stop loss: ₹15 loss
        if (pnlPoints <= -SCALP_STOPLOSS_POINTS) {
            return Optional.of(new ExitSignal(
                    current.timestamp(), currentPrice, ExitSignal.ExitReason.STOP_LOSS,
                    String.format("Scalp SL: %.1f pts (limit -%d)", pnlPoints, SCALP_STOPLOSS_POINTS)));
        }

        // Time stop: 15 min (3 snapshots at 5-min intervals)
        long holdMinutes = position.holdMinutes(current.timestamp());
        if (holdMinutes >= 15) {
            return Optional.of(new ExitSignal(
                    current.timestamp(), currentPrice, ExitSignal.ExitReason.TIME_BASED,
                    String.format("Scalp time stop: held %d min, pnl=%.1f pts", holdMinutes, pnlPoints)));
        }

        return Optional.empty();
    }
}
