package com.algo.trade.marketdata;

import com.algo.trade.domain.Candle;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Fixed-size ring buffer of completed candles per instrument+timeframe.
 * Thread-safe via synchronized methods.
 */
public class CandleHistory {

    private final int maxSize;
    private final Deque<Candle> candles;

    public CandleHistory(int maxSize) {
        this.maxSize = maxSize;
        this.candles = new ArrayDeque<>(maxSize);
    }

    public synchronized void add(Candle candle) {
        candles.addLast(candle);
        while (candles.size() > maxSize) candles.pollFirst();
    }

    public synchronized List<Candle> getAll() {
        return new ArrayList<>(candles);
    }

    public synchronized Candle getLast() {
        return candles.peekLast();
    }

    public synchronized int size() { return candles.size(); }
    public synchronized boolean isEmpty() { return candles.isEmpty(); }
}
