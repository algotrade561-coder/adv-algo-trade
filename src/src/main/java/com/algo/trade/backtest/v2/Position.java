package com.algo.trade.backtest.v2;

import com.algo.trade.domain.OptionType;
import com.algo.trade.strategy.StrategyType;

import java.time.Instant;

/**
 * Tracks an open position during snapshot-based backtesting.
 */
public class Position {

    private final String id;
    private final StrategyType strategyType;
    private final OptionType optionType;
    private final int strike;
    private final double entryPrice;
    private final double spotAtEntry;
    private final Instant entryTime;
    private final String entryReason;
    private final int quantity;

    private double highWaterMark;
    private boolean trailingStopActive;

    public Position(String id, StrategyType strategyType, OptionType optionType,
                    int strike, double entryPrice, double spotAtEntry,
                    Instant entryTime, String entryReason, int quantity) {
        this.id = id;
        this.strategyType = strategyType;
        this.optionType = optionType;
        this.strike = strike;
        this.entryPrice = entryPrice;
        this.spotAtEntry = spotAtEntry;
        this.entryTime = entryTime;
        this.entryReason = entryReason;
        this.quantity = quantity;
        this.highWaterMark = entryPrice;
        this.trailingStopActive = false;
    }

    public String id() { return id; }
    public StrategyType strategyType() { return strategyType; }
    public OptionType optionType() { return optionType; }
    public int strike() { return strike; }
    public double entryPrice() { return entryPrice; }
    public double spotAtEntry() { return spotAtEntry; }
    public Instant entryTime() { return entryTime; }
    public String entryReason() { return entryReason; }
    public int quantity() { return quantity; }
    public double highWaterMark() { return highWaterMark; }
    public boolean isTrailingStopActive() { return trailingStopActive; }

    /** Update high water mark and trailing stop state. */
    public void updatePrice(double currentPrice, double trailingActivationPercent) {
        if (currentPrice > highWaterMark) {
            highWaterMark = currentPrice;
        }
        double gainPercent = ((highWaterMark - entryPrice) / entryPrice) * 100;
        if (gainPercent >= trailingActivationPercent) {
            trailingStopActive = true;
        }
    }

    /** Calculate unrealized P&L at current price. */
    public double unrealizedPnl(double currentPrice) {
        return (currentPrice - entryPrice) * quantity;
    }

    /** Calculate P&L percentage. */
    public double pnlPercent(double currentPrice) {
        if (entryPrice == 0) return 0;
        return ((currentPrice - entryPrice) / entryPrice) * 100;
    }

    /** Calculate hold duration in minutes. */
    public long holdMinutes(Instant currentTime) {
        return java.time.Duration.between(entryTime, currentTime).toMinutes();
    }

    /** Calculate trailing stop price. */
    public double trailingStopPrice(double trailingGapPercent) {
        if (!trailingStopActive) return 0;
        return highWaterMark * (1 - trailingGapPercent / 100);
    }
}
