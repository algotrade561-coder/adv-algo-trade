package com.algo.trade.marketdata;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Lee-Ready signed order flow reconstruction.
 * 
 * Reconstructs the aggressor side (buy vs sell) from price + depth data alone,
 * without a per-trade aggressor feed. Uses the Lee-Ready tick rule:
 *
 * ΔV = cumVolume[t] − cumVolume[t−1]            // qty traded this tick
 * if      ltp[t] > bestAsk[t-1]  → BUY aggressor  → +ΔV
 * else if ltp[t] < bestBid[t-1]  → SELL aggressor → −ΔV
 * else (inside spread)            → tick rule: uptick=+ΔV, downtick=−ΔV, else carry prior
 *
 * Enables:
 * - Order Flow Imbalance (OFI) per tick
 * - Absorption detection (large volume, no price move = resting order absorbing flow)
 * - Volume delta profile (signed volume at each price)
 * - Validated spoofing detection (order appears then vanishes before price reaches it)
 *
 * This is used only as a shadow/diagnostic overlay on OiMomentumEntryDiagnostics;
 * do not enforce trades on unsigned flags alone until validated against historical backfill.
 */
@Component
public class OrderFlowReconstructor {
    
    private static final Logger log = LoggerFactory.getLogger(OrderFlowReconstructor.class);
    
    private static class PriorTick {
        double bestAsk;
        double bestBid;
        long cumVolume;
        double ltp;
        int sign; // +1 for uptick, -1 for downtick, 0 if equal
        
        PriorTick(double bid, double ask, long vol, double price) {
            this.bestBid = bid;
            this.bestAsk = ask;
            this.cumVolume = vol;
            this.ltp = price;
            this.sign = 0;
        }
    }
    
    private final ConcurrentHashMap<Long, PriorTick> priorByToken = new ConcurrentHashMap<>();
    
    /**
     * Called from websocket hot path with every new option tick.
     * Must be fast and non-blocking; any exception is logged and swallowed.
     *
     * @param token Kite token
     * @param ltp Last traded price
     * @param cumVolume Cumulative daily volume (Kite's field)
     * @param bestBid Best bid price
     * @param bestAsk Best ask price
     * @return OrderFlowSnapshot with signed volume, aggressor direction, absorption flag
     */
    public OrderFlowSnapshot onTick(long token, double ltp, long cumVolume, double bestBid, double bestAsk) {
        try {
            PriorTick prior = priorByToken.get(token);
            if (prior == null) {
                // First tick: initialize prior state, return neutral
                priorByToken.put(token, new PriorTick(bestBid, bestAsk, cumVolume, ltp));
                return OrderFlowSnapshot.neutral(cumVolume, ltp);
            }
            
            long deltaVolume = cumVolume - prior.cumVolume;
            if (deltaVolume < 0) deltaVolume = 0; // Handle volume reset (day boundary)
            
            // Lee-Ready: determine aggressor
            int aggressor;
            if (ltp > prior.bestAsk) {
                aggressor = 1; // BUY aggressor (market order hit ask)
            } else if (ltp < prior.bestBid) {
                aggressor = -1; // SELL aggressor (market order hit bid)
            } else if (ltp > prior.ltp) {
                aggressor = 1; // Uptick = last trade at higher price = buy
                prior.sign = 1;
            } else if (ltp < prior.ltp) {
                aggressor = -1; // Downtick = sell
                prior.sign = -1;
            } else {
                // No price change; use prior sign (carry forward)
                aggressor = prior.sign;
            }
            
            long signedVolume = aggressor * deltaVolume;
            
            // Absorption heuristic: large signed volume but best bid/ask unchanged
            // (a resting order on one side absorbed all the flow).
            boolean isAbsorption = Math.abs(signedVolume) >= 1000 
                    && prior.bestBid == bestBid 
                    && prior.bestAsk == bestAsk;
            
            OrderFlowSnapshot result = new OrderFlowSnapshot(
                    deltaVolume, aggressor, signedVolume, isAbsorption, ltp
            );
            
            // Update prior for next tick
            prior.bestBid = bestBid;
            prior.bestAsk = bestAsk;
            prior.cumVolume = cumVolume;
            prior.ltp = ltp;
            
            return result;
            
        } catch (Exception e) {
            log.debug("[OrderFlow] onTick skipped: {}", e.toString());
            return OrderFlowSnapshot.neutral(cumVolume, ltp);
        }
    }
    
    /**
     * Lee-Ready-reconstructed order flow for a single tick.
     */
    public record OrderFlowSnapshot(
            long deltaVolume,        // unsigned qty traded
            int aggressor,           // +1 buy, -1 sell, 0 neutral
            long signedVolume,       // deltaVolume * aggressor
            boolean isAbsorption,    // large volume, no best bid/ask change
            double ltp               // price at this tick
    ) {
        public static OrderFlowSnapshot neutral(long vol, double price) {
            return new OrderFlowSnapshot(vol, 0, 0, false, price);
        }
    }
    
    /**
     * Batch backfill for historical microstructure CSVs.
     * Applies Lee-Ready to every row in chronological order, appending signed-volume columns.
     */
    public static class BackfillRecord {
        public long recvEpochMs;
        public int exchangeTsEpochSec;
        public String index;
        public double strike;
        public String optionType;
        public String tradingSymbol;
        public double ltp;
        public double bestBid;
        public double bestAsk;
        public long bidQty;
        public long askQty;
        public long cumVolume;
        public long oi;
        
        // Lee-Ready reconstructed fields
        public long deltaVolume;
        public int aggressor;        // +1 buy, -1 sell, 0 neutral
        public long signedVolume;
        public boolean isAbsorption;
        
        // Spoof detection overlay
        public double bidSpoofScore;  // [0..1] how suspicious is the best bid level?
        public double askSpoofScore;
    }
}
