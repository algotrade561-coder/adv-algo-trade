package com.algo.trade.backtest;

import java.math.BigDecimal;
import java.time.Instant;

public record EquityCurvePoint(Instant timestamp, BigDecimal equity, BigDecimal drawdown) {
}
