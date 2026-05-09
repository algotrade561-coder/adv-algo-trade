package com.algo.trade.backtest.v2.adapter;

import com.algo.trade.backtest.v2.ExitSignal;
import com.algo.trade.backtest.v2.Position;
import com.algo.trade.backtest.v2.StrategyAdapter;
import com.algo.trade.data.ChainSnapshot;
import com.algo.trade.domain.OptionType;
import com.algo.trade.strategy.StrategyConfig;

import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Optional;

/**
 * Base class for strategy adapters with common exit logic
 * (stop loss, target, trailing stop, time-based exit).
 */
public abstract class AbstractStrategyAdapter implements StrategyAdapter {

    protected static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /**
     * Standard exit evaluation: stop loss, target, trailing stop, max hold time.
     */
    @Override
    public Optional<ExitSignal> evaluateExit(Position position, ChainSnapshot current, StrategyConfig config) {
        double currentPrice = getOptionPrice(current, position.strike(), position.optionType());
        if (currentPrice <= 0) return Optional.empty();

        double pnlPercent = position.pnlPercent(currentPrice);
        double stopLoss = config.getStopLossPercent().doubleValue();
        double target = config.getTargetPercent().doubleValue();
        double trailingActivation = config.getTrailingStopActivationPercent().doubleValue();
        double trailingGap = config.getTrailingGapPercent().doubleValue();
        int maxHoldMinutes = config.getMaxHoldMinutes();

        // Stop loss
        if (pnlPercent <= -stopLoss) {
            return Optional.of(new ExitSignal(
                    current.timestamp(), currentPrice, ExitSignal.ExitReason.STOP_LOSS,
                    String.format("P&L %.1f%% hit stop loss -%.1f%%", pnlPercent, stopLoss)));
        }

        // Target hit
        if (pnlPercent >= target) {
            return Optional.of(new ExitSignal(
                    current.timestamp(), currentPrice, ExitSignal.ExitReason.TARGET_HIT,
                    String.format("P&L %.1f%% hit target +%.1f%%", pnlPercent, target)));
        }

        // Trailing stop
        position.updatePrice(currentPrice, trailingActivation);
        if (position.isTrailingStopActive()) {
            double trailingStopPrice = position.trailingStopPrice(trailingGap);
            if (currentPrice <= trailingStopPrice) {
                return Optional.of(new ExitSignal(
                        current.timestamp(), currentPrice, ExitSignal.ExitReason.TRAILING_STOP,
                        String.format("Price %.2f below trailing stop %.2f (HWM: %.2f)",
                                currentPrice, trailingStopPrice, position.highWaterMark())));
            }
        }

        // Max hold time
        if (maxHoldMinutes > 0) {
            long holdMinutes = position.holdMinutes(current.timestamp());
            if (holdMinutes >= maxHoldMinutes) {
                return Optional.of(new ExitSignal(
                        current.timestamp(), currentPrice, ExitSignal.ExitReason.TIME_BASED,
                        String.format("Held %d min, max %d min", holdMinutes, maxHoldMinutes)));
            }
        }

        // Strategy-specific exit logic
        return evaluateStrategyExit(position, current, config, currentPrice, pnlPercent);
    }

    /**
     * Override for strategy-specific exit conditions beyond standard SL/target/trailing.
     */
    protected Optional<ExitSignal> evaluateStrategyExit(Position position, ChainSnapshot current,
                                                         StrategyConfig config, double currentPrice,
                                                         double pnlPercent) {
        return Optional.empty();
    }

    // ── Helper methods ──────────────────────────────────────────────────────────

    protected double getOptionPrice(ChainSnapshot snapshot, int strike, OptionType optionType) {
        return snapshot.strikes().stream()
                .filter(s -> s.strike() == strike)
                .findFirst()
                .map(s -> optionType == OptionType.CE ? s.ceLTP() : s.peLTP())
                .orElse(0.0);
    }

    protected double getOptionIV(ChainSnapshot snapshot, int strike, OptionType optionType) {
        return snapshot.strikes().stream()
                .filter(s -> s.strike() == strike)
                .findFirst()
                .map(s -> optionType == OptionType.CE ? s.ceIV() : s.peIV())
                .orElse(0.0);
    }

    protected double getOptionDelta(ChainSnapshot snapshot, int strike, OptionType optionType) {
        return snapshot.strikes().stream()
                .filter(s -> s.strike() == strike)
                .findFirst()
                .map(s -> optionType == OptionType.CE ? s.ceDelta() : s.peDelta())
                .orElse(0.0);
    }

    protected long getOptionOI(ChainSnapshot snapshot, int strike, OptionType optionType) {
        return snapshot.strikes().stream()
                .filter(s -> s.strike() == strike)
                .findFirst()
                .map(s -> optionType == OptionType.CE ? s.ceOI() : s.peOI())
                .orElse(0L);
    }

    protected long getOptionVolume(ChainSnapshot snapshot, int strike, OptionType optionType) {
        return snapshot.strikes().stream()
                .filter(s -> s.strike() == strike)
                .findFirst()
                .map(s -> optionType == OptionType.CE ? s.ceVolume() : s.peVolume())
                .orElse(0L);
    }

    protected long getOiChange(ChainSnapshot snapshot, int strike, OptionType optionType) {
        return snapshot.strikes().stream()
                .filter(s -> s.strike() == strike)
                .findFirst()
                .map(s -> optionType == OptionType.CE ? s.ceOiChange() : s.peOiChange())
                .orElse(0L);
    }

    protected Optional<ChainSnapshot.StrikeData> getAtmStrike(ChainSnapshot snapshot) {
        return snapshot.strikes().stream()
                .filter(s -> s.strike() == snapshot.atmStrike())
                .findFirst();
    }

    protected int getStrikeInterval(String underlying) {
        return switch (underlying) {
            case "BANKNIFTY" -> 100;
            case "SENSEX" -> 100;
            default -> 50;
        };
    }

    protected LocalTime getMarketTime(ChainSnapshot snapshot) {
        return snapshot.timestamp().atZone(IST).toLocalTime();
    }

    /** Total CE OI across all strikes. */
    protected long totalCeOI(ChainSnapshot snapshot) {
        return snapshot.strikes().stream().mapToLong(ChainSnapshot.StrikeData::ceOI).sum();
    }

    /** Total PE OI across all strikes. */
    protected long totalPeOI(ChainSnapshot snapshot) {
        return snapshot.strikes().stream().mapToLong(ChainSnapshot.StrikeData::peOI).sum();
    }

    /** Put-Call Ratio based on OI. */
    protected double pcr(ChainSnapshot snapshot) {
        long ceOI = totalCeOI(snapshot);
        return ceOI > 0 ? (double) totalPeOI(snapshot) / ceOI : 1.0;
    }

    /** Spot price change between two snapshots. */
    protected double spotChange(ChainSnapshot current, ChainSnapshot previous) {
        if (previous == null) return 0;
        return current.spot() - previous.spot();
    }

    /** Spot price change percentage. */
    protected double spotChangePercent(ChainSnapshot current, ChainSnapshot previous) {
        if (previous == null || previous.spot() == 0) return 0;
        return ((current.spot() - previous.spot()) / previous.spot()) * 100;
    }
}
