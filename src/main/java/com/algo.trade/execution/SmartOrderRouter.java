package com.algo.trade.execution;

import com.algo.trade.domain.OrderRequest;
import com.algo.trade.domain.OrderType;
import com.algo.trade.domain.Quote;
import com.algo.trade.marketdata.MarketDataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Optional;

/**
 * Smart Order Router — decides optimal order type for each trade.
 *
 * Strategy:
 *   1. LIQUID options (spread < 1%, volume > 1000): MARKET order
 *      → Fastest fill, Zerodha auto-protection prevents freak trades
 *   2. SEMI-LIQUID (spread 1-3%): LIMIT at mid-price with buffer
 *      → Good fill, reasonable speed
 *   3. ILLIQUID (spread > 3% or no depth): LIMIT at bid/ask with buffer
 *      → Safe, may not fill immediately
 *
 * The router returns an optimized OrderRequest with the best order type
 * and price for the given instrument's current liquidity.
 */
@Component
public class SmartOrderRouter {

    private static final Logger log = LoggerFactory.getLogger(SmartOrderRouter.class);
    private static final MathContext MC = MathContext.DECIMAL64;

    private final MarketDataService marketDataService;

    @Value("${smart-order.liquid-spread-percent:1.0}")
    private double liquidSpreadPercent;

    @Value("${smart-order.illiquid-spread-percent:3.0}")
    private double illiquidSpreadPercent;

    @Value("${smart-order.min-volume-for-market:1000}")
    private long minVolumeForMarket;

    @Value("${smart-order.limit-buffer-percent:0.5}")
    private double limitBufferPercent;

    public SmartOrderRouter(MarketDataService marketDataService) {
        this.marketDataService = marketDataService;
    }

    public record RoutingDecision(
            OrderType orderType,
            Optional<BigDecimal> limitPrice,
            String reason,
            double spreadPercent,
            LiquidityClass liquidityClass
    ) {}

    public enum LiquidityClass { LIQUID, SEMI_LIQUID, ILLIQUID, UNKNOWN }

    /**
     * Determine the optimal order type and price for an instrument.
     *
     * @param instrumentKey the option instrument key
     * @param side BUY or SELL — affects limit price direction
     * @param fallbackPrice price to use if no quote available
     * @return routing decision with order type, price, and reason
     */
    public RoutingDecision route(String instrumentKey, com.algo.trade.domain.OrderSide side,
                                  BigDecimal fallbackPrice) {
        Optional<Quote> quoteOpt = marketDataService.quote(instrumentKey);
        if (quoteOpt.isEmpty()) {
            log.debug("[SmartRouter] No quote for {} — using LIMIT at fallback price", instrumentKey);
            BigDecimal buffered = applyBuffer(fallbackPrice, side);
            return new RoutingDecision(OrderType.LIMIT, Optional.of(buffered),
                    "No quote — LIMIT at fallback", 0, LiquidityClass.UNKNOWN);
        }

        Quote quote = quoteOpt.get();
        BigDecimal bid = quote.bid().orElse(BigDecimal.ZERO);
        BigDecimal ask = quote.ask().orElse(BigDecimal.ZERO);
        BigDecimal lastPrice = quote.lastPrice();
        long volume = quote.volume();

        // Calculate spread
        double spreadPct = 0;
        if (bid.signum() > 0 && ask.signum() > 0) {
            BigDecimal mid = bid.add(ask).divide(BigDecimal.valueOf(2), MC);
            if (mid.signum() > 0) {
                spreadPct = ask.subtract(bid).divide(mid, MC).multiply(BigDecimal.valueOf(100)).doubleValue();
            }
        }

        // Determine liquidity class
        LiquidityClass liquidity;
        if (spreadPct > 0 && spreadPct < liquidSpreadPercent && volume >= minVolumeForMarket) {
            liquidity = LiquidityClass.LIQUID;
        } else if (spreadPct > 0 && spreadPct < illiquidSpreadPercent) {
            liquidity = LiquidityClass.SEMI_LIQUID;
        } else if (spreadPct > 0) {
            liquidity = LiquidityClass.ILLIQUID;
        } else {
            liquidity = LiquidityClass.UNKNOWN;
        }

        return switch (liquidity) {
            case LIQUID -> {
                // Market order — fastest fill
                log.info("[SmartRouter] LIQUID: {} spread={}% vol={} → MARKET",
                        instrumentKey, String.format("%.2f", spreadPct), volume);
                yield new RoutingDecision(OrderType.MARKET, Optional.empty(),
                        "Liquid (spread " + String.format("%.1f", spreadPct) + "%, vol " + volume + ")",
                        spreadPct, liquidity);
            }
            case SEMI_LIQUID -> {
                // Limit at mid-price with buffer
                BigDecimal mid = bid.add(ask).divide(BigDecimal.valueOf(2), MC);
                BigDecimal limitPrice = applyBuffer(mid, side);
                log.info("[SmartRouter] SEMI-LIQUID: {} spread={}% → LIMIT at {} (mid={} bid={} ask={})",
                        instrumentKey, String.format("%.2f", spreadPct), limitPrice, mid, bid, ask);
                yield new RoutingDecision(OrderType.LIMIT, Optional.of(limitPrice),
                        "Semi-liquid (spread " + String.format("%.1f", spreadPct) + "%) — LIMIT at mid+" + limitBufferPercent + "%",
                        spreadPct, liquidity);
            }
            case ILLIQUID -> {
                // Limit at bid (for BUY) or ask (for SELL) with buffer
                BigDecimal basePrice = side == com.algo.trade.domain.OrderSide.BUY ? ask : bid;
                if (basePrice.signum() <= 0) basePrice = lastPrice;
                BigDecimal limitPrice = applyBuffer(basePrice, side);
                log.info("[SmartRouter] ILLIQUID: {} spread={}% → LIMIT at {} (base={})",
                        instrumentKey, String.format("%.2f", spreadPct), limitPrice, basePrice);
                yield new RoutingDecision(OrderType.LIMIT, Optional.of(limitPrice),
                        "Illiquid (spread " + String.format("%.1f", spreadPct) + "%) — LIMIT at " + side + " side",
                        spreadPct, liquidity);
            }
            case UNKNOWN -> {
                BigDecimal buffered = applyBuffer(lastPrice.signum() > 0 ? lastPrice : fallbackPrice, side);
                log.debug("[SmartRouter] UNKNOWN liquidity: {} → LIMIT at {}", instrumentKey, buffered);
                yield new RoutingDecision(OrderType.LIMIT, Optional.of(buffered),
                        "Unknown liquidity — LIMIT at last price", spreadPct, liquidity);
            }
        };
    }

    /**
     * Apply a buffer to the limit price to improve fill probability.
     * BUY: price + buffer (willing to pay slightly more)
     * SELL: price - buffer (willing to accept slightly less)
     * Result is rounded to the exchange tick size (₹0.05 for NSE/BSE options).
     */
    private BigDecimal applyBuffer(BigDecimal price, com.algo.trade.domain.OrderSide side) {
        if (price == null || price.signum() <= 0) return price;
        BigDecimal buffer = price.multiply(BigDecimal.valueOf(limitBufferPercent / 100), MC);
        BigDecimal raw = side == com.algo.trade.domain.OrderSide.BUY
                ? price.add(buffer)
                : price.subtract(buffer);
        return roundToTickSize(raw, side);
    }

    /**
     * Round price to the nearest valid tick size (₹0.05 for NSE/BSE F&O).
     * BUY: round UP to nearest tick (willing to pay more for fill)
     * SELL: round DOWN to nearest tick (willing to accept less for fill)
     */
    private static BigDecimal roundToTickSize(BigDecimal price, com.algo.trade.domain.OrderSide side) {
        BigDecimal tickSize = new BigDecimal("0.05");
        java.math.RoundingMode mode = side == com.algo.trade.domain.OrderSide.BUY
                ? java.math.RoundingMode.UP
                : java.math.RoundingMode.DOWN;
        return price.divide(tickSize, 0, mode).multiply(tickSize).setScale(2, java.math.RoundingMode.HALF_UP);
    }
}
