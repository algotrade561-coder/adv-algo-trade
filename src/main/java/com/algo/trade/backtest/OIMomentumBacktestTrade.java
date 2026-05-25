package com.algo.trade.backtest;

/**
 * Immutable record representing a single simulated trade produced by
 * {@link OIMomentumBacktestService}.
 *
 * <p>All monetary values are in INR and assume a lot size of 65 (NIFTY default).
 * Percentage values are expressed as plain numbers (e.g. 5.0 = 5%).
 */
public record OIMomentumBacktestTrade(
        /** Calendar date of the trade — "YYYY-MM-DD". */
        String date,

        /** Entry bar timestamp (IST, HH:mm). */
        String entryTime,

        /** Exit bar timestamp (IST, HH:mm). */
        String exitTime,

        /** "BUY_CE" for bullish trades, "BUY_PE" for bearish. */
        String direction,

        /** ATM strike used for entry (e.g. 24000). */
        int strike,

        /** Entry price per unit including 0.20% slippage. */
        double entryPrice,

        /** Exit price per unit. */
        double exitPrice,

        /** Profit/loss as a percentage of entry price. */
        double pnlPct,

        /** Profit/loss in INR (pnlPct × lot size 65). */
        double pnlInr,

        /**
         * Reason the position was closed.
         * One of: STOP_LOSS, BREAK_EVEN_STOP, TRAILING_STOP, SQUAREOFF,
         * SQUAREOFF_NO_QUOTE.
         */
        String exitReason,

        /** Duration the trade was open, in minutes. */
        int holdMinutes,

        /** True when pnlInr > 0. */
        boolean win
) {}
