package com.algo.trade.domain;

/**
 * Published by LiveCandleBuilder when a candle period closes.
 * Strategies listen to this event instead of a fixed timer,
 * firing the moment a candle closes — no polling delay.
 */
public record CandleClosedEvent(Candle candle, Timeframe timeframe, long instrumentToken) {}
