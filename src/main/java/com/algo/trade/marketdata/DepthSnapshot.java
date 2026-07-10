package com.algo.trade.marketdata;

/**
 * Full 5-level order book snapshot from Kite full-mode (184-byte) packets.
 * 
 * Captured from the websocket hot path — used for microstructure analysis,
 * spoofing detection (via order-count patterns), and order flow reconstruction.
 */
public record DepthSnapshot(
        long[] bidQtys,        // [0] = best bid qty, [1-4] = deeper levels
        double[] bidPrices,
        int[] bidOrders,       // order count per level
        long[] askQtys,
        double[] askPrices,
        int[] askOrders,
        long totalBuyQty,      // aggregate qty on buy side (from Kite packet)
        long totalSellQty      // aggregate qty on sell side (from Kite packet)
) {
    public DepthSnapshot {
        if (bidQtys == null || bidQtys.length != 5) 
            throw new IllegalArgumentException("bidQtys must be 5-element array");
        if (bidPrices == null || bidPrices.length != 5)
            throw new IllegalArgumentException("bidPrices must be 5-element array");
        if (bidOrders == null || bidOrders.length != 5)
            throw new IllegalArgumentException("bidOrders must be 5-element array");
        if (askQtys == null || askQtys.length != 5) 
            throw new IllegalArgumentException("askQtys must be 5-element array");
        if (askPrices == null || askPrices.length != 5)
            throw new IllegalArgumentException("askPrices must be 5-element array");
        if (askOrders == null || askOrders.length != 5)
            throw new IllegalArgumentException("askOrders must be 5-element array");
    }
    
    /**
     * 5-level imbalance: sum(bid qty across 5 levels) vs sum(ask qty across 5 levels).
     * Positive = book-weighted bullish, negative = bearish.
     */
    public double bookImbalance() {
        long bidSum = bidQtys[0] + bidQtys[1] + bidQtys[2] + bidQtys[3] + bidQtys[4];
        long askSum = askQtys[0] + askQtys[1] + askQtys[2] + askQtys[3] + askQtys[4];
        return (bidSum - askSum) / (double)(bidSum + askSum + 1);
    }
    
    /**
     * Spoof heuristic: large qty at a level but low order count suggests one large order.
     * If that level vanishes before price reaches it, it was likely fake.
     * Returns an array of suspicion scores [0..1] per bid level.
     */
    public double[] bidSpoofScores() {
        double[] scores = new double[5];
        for (int i = 0; i < 5; i++) {
            if (bidOrders[i] == 0) {
                scores[i] = 0;
            } else {
                double avgQtyPerOrder = bidQtys[i] / (double) bidOrders[i];
                // High avg qty per order (e.g., one massive order) at deeper levels = suspicious
                scores[i] = Math.min(1.0, (avgQtyPerOrder - 100) / 500.0);
            }
        }
        return scores;
    }
    
    /**
     * Same as bidSpoofScores but for ask side.
     */
    public double[] askSpoofScores() {
        double[] scores = new double[5];
        for (int i = 0; i < 5; i++) {
            if (askOrders[i] == 0) {
                scores[i] = 0;
            } else {
                double avgQtyPerOrder = askQtys[i] / (double) askOrders[i];
                scores[i] = Math.min(1.0, (avgQtyPerOrder - 100) / 500.0);
            }
        }
        return scores;
    }
}
