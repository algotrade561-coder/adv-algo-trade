package com.algo.trade.domain;

public enum OrderType {
    MARKET,
    LIMIT,
    /** Stop-Loss Market — broker-side trigger at specified price, executes as market order. */
    SL_M
}
