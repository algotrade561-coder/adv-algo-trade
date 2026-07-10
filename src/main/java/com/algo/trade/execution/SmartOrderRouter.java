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

    @Value("${smart-order.market-protection-percent:1.0}")
    private double marketProtectionPercent;

    /**
     * Anticipatory discount for BUY limit orders — places below current price
     * to catch the typical operator shakeout dip before the real move.
     * Configurable: 0 disables (uses current price), 2-10% typical range.
     * The actual discount is dynamic: higher in volatile conditions, lower near expiry.
     */
    @Value("${smart-order.anticipatory-discount-percent:5.0}")
    private double anticipatoryDiscountPercent;

    /** Minimum discount — never go below this even in low-vol conditions */
    @Value("${smart-order.min-discount-percent:2.0}")
    private double minDiscountPercent;

    /** Maximum discount — cap to avoid placing too far below market */
    @Value("${smart-order.max-discount-percent:10.0}")
    private double maxDiscountPercent;

    /** Enable/disable the anticipatory discount feature */
    @Value("${smart-order.anticipatory-discount-enabled:true}")
    private boolean anticipatoryDiscountEnabled;

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
                // Marketable LIMIT — fills instantly like MARKET but with price protection.
                // BUY at lastPrice + protection%, SELL at lastPrice - protection%.
                BigDecimal protectedPrice = applyProtection(lastPrice, side);
                log.info("[SmartRouter] LIQUID: {} spread={}% vol={} → LIMIT at {} (protection {}%)",
                        instrumentKey, String.format("%.2f", spreadPct), volume,
                        protectedPrice, marketProtectionPercent);
                yield new RoutingDecision(OrderType.LIMIT, Optional.of(protectedPrice),
                        "Liquid (spread " + String.format("%.1f", spreadPct) + "%) — marketable LIMIT +" + marketProtectionPercent + "%",
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
                // No bid/ask data — use marketable LIMIT at lastPrice + protection%.
                // Fills instantly like MARKET but won't exceed protection threshold.
                BigDecimal basePrice = lastPrice.signum() > 0 ? lastPrice : fallbackPrice;
                BigDecimal protectedPrice = applyProtection(basePrice, side);
                log.info("[SmartRouter] UNKNOWN liquidity: {} → marketable LIMIT at {} (protection {}% from {})",
                        instrumentKey, protectedPrice, marketProtectionPercent, basePrice);
                yield new RoutingDecision(OrderType.LIMIT, Optional.of(protectedPrice),
                        "Unknown liquidity — marketable LIMIT +" + marketProtectionPercent + "% protection",
                        spreadPct, liquidity);
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
     * Apply market protection percentage — creates a marketable LIMIT that fills instantly
     * but won't exceed the protection threshold from current price.
     * BUY: price × (1 + protection%) — willing to pay up to X% above current
     * SELL: price × (1 - protection%) — willing to accept up to X% below current
     */
    private BigDecimal applyProtection(BigDecimal price, com.algo.trade.domain.OrderSide side) {
        if (price == null || price.signum() <= 0) return price;
        BigDecimal protection = price.multiply(BigDecimal.valueOf(marketProtectionPercent / 100), MC);
        BigDecimal raw = side == com.algo.trade.domain.OrderSide.BUY
                ? price.add(protection)
                : price.subtract(protection).max(BigDecimal.ONE);
        return roundToTickSize(raw, side);
    }

    /**
     * Round price to the nearest valid tick size (₹0.05 for NSE/BSE F&O).
     * BUY: round UP to nearest tick (willing to pay more for fill)
     * SELL: round DOWN to nearest tick (willing to accept less for fill)
     */
    private static BigDecimal roundToTickSize(BigDecimal price, com.algo.trade.domain.OrderSide side) {
        java.math.RoundingMode mode = side == com.algo.trade.domain.OrderSide.BUY
                ? java.math.RoundingMode.UP
                : java.math.RoundingMode.DOWN;
        return roundToTickSize(price, mode);
    }

    /**
     * Round price to the nearest valid tick (₹0.05) using an explicit rounding direction.
     * Used where the side-default direction is NOT what we want — e.g. the anticipatory BUY
     * discount, which must round DOWN (toward the lower/cheaper price) even though BUY normally
     * rounds UP for fill probability.
     */
    private static BigDecimal roundToTickSize(BigDecimal price, java.math.RoundingMode mode) {
        BigDecimal tickSize = new BigDecimal("0.05");
        return price.divide(tickSize, 0, mode).multiply(tickSize).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ANTICIPATORY DISCOUNT ENGINE
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Calculate the optimal discounted entry price for BUY limit orders.
     *
     * Instead of placing at current LTP (which often dips before the real move),
     * this calculates a discount below current price to catch the operator shakeout.
     *
     * Factors:
     * 1. SPREAD-BASED: In semi-liquid options, place at mid or slightly below mid
     * 2. VOLATILITY-ADAPTIVE: Higher discount when recent price swings are large
     * 3. PREMIUM-SCALED: Higher-premium options get smaller % discount (₹10 on ₹200
     *    is more significant than ₹10 on ₹50)
     * 4. TIME-CONTEXT: During operator cycle windows, expect bigger shakeouts
     *
     * @param currentPrice current option premium (LTP or ask)
     * @param bid current best bid
     * @param ask current best ask
     * @param spreadPct bid-ask spread percentage
     * @return discounted limit price (always below currentPrice for BUY)
     */
    public BigDecimal calculateAnticipatoryPrice(BigDecimal currentPrice, BigDecimal bid,
                                                   BigDecimal ask, double spreadPct) {
        if (!anticipatoryDiscountEnabled || currentPrice == null || currentPrice.signum() <= 0) {
            return currentPrice;
        }

        // Base discount from configuration
        double discountPct = anticipatoryDiscountPercent;

        // Factor 1: SPREAD-BASED adjustment — wider spreads mean more room to discount
        if (spreadPct > 1.5) {
            // Semi-liquid/illiquid: can discount more aggressively (price is softer)
            discountPct = Math.min(discountPct + 2.0, maxDiscountPercent);
        } else if (spreadPct < 0.5) {
            // Very tight spread: less room, reduce discount
            discountPct = Math.max(discountPct - 1.5, minDiscountPercent);
        }

        // Factor 2: PREMIUM-SCALED — cheaper options get smaller absolute discount
        double premium = currentPrice.doubleValue();
        if (premium < 50) {
            // Cheap options (< ₹50): reduce discount to avoid going too low
            discountPct = Math.max(minDiscountPercent, discountPct * 0.6);
        } else if (premium > 300) {
            // Expensive options (> ₹300): can afford fuller discount
            discountPct = Math.min(discountPct * 1.2, maxDiscountPercent);
        }

        // Factor 3: BID-FLOOR — never go below the current best bid
        // The bid is where buyers are sitting; going below it means no immediate takers
        BigDecimal discount = currentPrice.multiply(BigDecimal.valueOf(discountPct / 100), MC);
        BigDecimal discountedPrice = currentPrice.subtract(discount);

        // Floor at bid price (if available) — ensures our order is at or above the bid
        if (bid != null && bid.signum() > 0) {
            // Place slightly above bid (bid + 25% of spread) to get priority in queue
            BigDecimal spreadAmount = ask.subtract(bid);
            BigDecimal aboveBid = bid.add(spreadAmount.multiply(BigDecimal.valueOf(0.25), MC));
            // Use whichever is HIGHER: our discounted price or slightly-above-bid
            // This way we never go below where buyers are, but still get a discount from ask
            discountedPrice = discountedPrice.max(aboveBid);
        }

        // Absolute floor: never go below 80% of current price (prevent extreme orders)
        BigDecimal absoluteFloor = currentPrice.multiply(BigDecimal.valueOf(0.80), MC);
        discountedPrice = discountedPrice.max(absoluteFloor);

        // Round to tick size (round DOWN for buy discount — we want the lower price)
        discountedPrice = roundToTickSize(discountedPrice, java.math.RoundingMode.FLOOR);

        double actualDiscountPct = currentPrice.subtract(discountedPrice)
                .divide(currentPrice, MC).multiply(BigDecimal.valueOf(100)).doubleValue();
        log.info("[SmartRouter] Anticipatory discount: current={} → entry={} (discount={}%, spread={}%)",
                currentPrice, discountedPrice, String.format("%.1f", actualDiscountPct), String.format("%.1f", spreadPct));

        return discountedPrice;
    }

    /**
     * Route with anticipatory discount — for strategy entries that expect a dip.
     * Returns a limit order placed BELOW current price to catch the shakeout.
     *
     * Use this for:
     * - OI Momentum entries (operators shake out before moving)
     * - Operator Intent Radar "probe" entries (entering early, expecting dip first)
     * - Non-spike entries where you have time to wait for fill
     *
     * Do NOT use for:
     * - SPIKE entries (need instant fill, use MARKET)
     * - Exit orders (need to get out, use protection not discount)
     */
    public RoutingDecision routeWithDiscount(String instrumentKey, com.algo.trade.domain.OrderSide side,
                                              BigDecimal fallbackPrice) {
        if (!anticipatoryDiscountEnabled || side != com.algo.trade.domain.OrderSide.BUY) {
            return route(instrumentKey, side, fallbackPrice);
        }

        Optional<Quote> quoteOpt = marketDataService.quote(instrumentKey);
        if (quoteOpt.isEmpty()) {
            // No quote — apply simple discount to fallback (round DOWN to keep the discount)
            BigDecimal discounted = fallbackPrice.multiply(
                    BigDecimal.valueOf(1.0 - anticipatoryDiscountPercent / 100), MC);
            discounted = roundToTickSize(discounted.max(BigDecimal.ONE), java.math.RoundingMode.FLOOR);
            return new RoutingDecision(OrderType.LIMIT, Optional.of(discounted),
                    "No quote — anticipatory LIMIT at -" + anticipatoryDiscountPercent + "%",
                    0, LiquidityClass.UNKNOWN);
        }

        Quote quote = quoteOpt.get();
        BigDecimal bid = quote.bid().orElse(BigDecimal.ZERO);
        BigDecimal ask = quote.ask().orElse(BigDecimal.ZERO);
        BigDecimal lastPrice = quote.lastPrice();

        double spreadPct = 0;
        if (bid.signum() > 0 && ask.signum() > 0) {
            BigDecimal mid = bid.add(ask).divide(BigDecimal.valueOf(2), MC);
            if (mid.signum() > 0) {
                spreadPct = ask.subtract(bid).divide(mid, MC).multiply(BigDecimal.valueOf(100)).doubleValue();
            }
        }

        BigDecimal basePrice = lastPrice.signum() > 0 ? lastPrice : fallbackPrice;
        BigDecimal discountedPrice = calculateAnticipatoryPrice(basePrice, bid, ask, spreadPct);

        double actualDiscount = basePrice.subtract(discountedPrice)
                .divide(basePrice, MC).multiply(BigDecimal.valueOf(100)).doubleValue();

        return new RoutingDecision(OrderType.LIMIT, Optional.of(discountedPrice),
                String.format("Anticipatory LIMIT at -%.1f%% (bid=%.2f ask=%.2f spread=%.1f%%)",
                        actualDiscount, bid.doubleValue(), ask.doubleValue(), spreadPct),
                spreadPct, spreadPct < liquidSpreadPercent ? LiquidityClass.LIQUID : LiquidityClass.SEMI_LIQUID);
    }
}
