package com.algo.trade.broker.zerodha;

/**
 * Published when Kite login completes successfully (manual or auto).
 * KiteStartupLogin listens to this to trigger WebSocket + scanner start.
 */
public record KiteLoginSuccessEvent(String userId) {}
