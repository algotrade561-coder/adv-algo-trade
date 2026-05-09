package com.algo.trade.backtest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Payoff Chart Simulator — calculates option strategy payoff curves across a price range.
 *
 * Shows: max profit, max loss, breakeven points, risk/reward ratio, and the full payoff curve
 * for visualization in the Angular UI.
 *
 * Supports all strategy types we trade: long/short calls/puts, straddles, strangles,
 * iron condors, butterflies, bull call spreads, bear put spreads, and custom multi-leg.
 *
 * Improvements over the external project version:
 * - Fixed iron condor payoff calculation (external version had incorrect leg signs)
 * - Fixed butterfly payoff (external version used wrong wing calculation)
 * - Added jade lizard and synthetic futures (strategies we actually trade)
 * - Custom multi-leg endpoint validates inputs
 * - Price range is configurable (not hardcoded ±10%)
 *
 * GET /advalgotrade/analytics/payoff?strategy=short_straddle&spot=24200&premium=250&lotSize=75
 * POST /advalgotrade/analytics/payoff/custom — multi-leg payoff
 */
@RestController
@RequestMapping("/analytics/payoff")
public class PayoffSimulator {

    private static final Logger log = LoggerFactory.getLogger(PayoffSimulator.class);

    @GetMapping
    public ResponseEntity<Map<String, Object>> simulatePayoff(
            @RequestParam String strategy,
            @RequestParam double spot,
            @RequestParam(defaultValue = "0") double strike,
            @RequestParam(defaultValue = "0") double strike2,
            @RequestParam(defaultValue = "100") double premium,
            @RequestParam(defaultValue = "0") double premium2,
            @RequestParam(defaultValue = "1") int lots,
            @RequestParam(defaultValue = "75") int lotSize,
            @RequestParam(defaultValue = "10") double rangePct) {

        if (spot <= 0) return badRequest("spot must be positive");
        if (premium <= 0) return badRequest("premium must be positive");
        if (lots <= 0 || lotSize <= 0) return badRequest("lots and lotSize must be positive");
        rangePct = Math.max(2, Math.min(rangePct, 30));

        if (strike <= 0) strike = Math.round(spot / 50.0) * 50; // ATM
        int qty = lots * lotSize;

        List<Map<String, Object>> payoff = new ArrayList<>();
        double rangeStart = spot * (1 - rangePct / 100.0);
        double rangeEnd = spot * (1 + rangePct / 100.0);
        double step = (rangeEnd - rangeStart) / 200.0; // 200 data points

        double maxProfit = -Double.MAX_VALUE;
        double maxLoss = Double.MAX_VALUE;
        double breakeven1 = 0, breakeven2 = 0;
        Double prevPnl = null;

        for (double price = rangeStart; price <= rangeEnd; price += step) {
            double pnl = calculatePnl(strategy, price, strike, strike2, premium, premium2, qty, lotSize);
            maxProfit = Math.max(maxProfit, pnl);
            maxLoss = Math.min(maxLoss, pnl);

            // Detect breakeven crossings
            if (prevPnl != null && ((prevPnl < 0 && pnl >= 0) || (prevPnl >= 0 && pnl < 0))) {
                double be = round2(price);
                if (breakeven1 == 0) breakeven1 = be;
                else breakeven2 = be;
            }
            prevPnl = pnl;

            payoff.add(Map.of(
                    "price", round2(price),
                    "pnl", round2(pnl),
                    "distanceFromSpot", round2(price - spot),
                    "distancePct", round2((price - spot) / spot * 100)
            ));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("strategy", strategy);
        result.put("spot", spot);
        result.put("strike", strike);
        if (strike2 > 0) result.put("strike2", strike2);
        result.put("premium", premium);
        if (premium2 > 0) result.put("premium2", premium2);
        result.put("lots", lots);
        result.put("lotSize", lotSize);
        result.put("qty", qty);
        result.put("maxProfit", round2(maxProfit));
        result.put("maxLoss", round2(maxLoss));
        result.put("breakeven1", breakeven1 > 0 ? round2(breakeven1) : "N/A");
        result.put("breakeven2", breakeven2 > 0 ? round2(breakeven2) : "N/A");
        result.put("riskRewardRatio", maxLoss != 0
                ? round2(Math.abs(maxProfit / maxLoss)) : "Unlimited");
        result.put("payoffCurve", payoff);
        return ResponseEntity.ok(result);
    }

    /**
     * Custom multi-leg payoff simulation.
     * POST body: [{"type":"CE","strike":24200,"premium":250,"side":"SELL","qty":75}, ...]
     */
    @PostMapping("/custom")
    public ResponseEntity<Map<String, Object>> customPayoff(
            @RequestBody List<Map<String, Object>> legs,
            @RequestParam(defaultValue = "24200") double spot,
            @RequestParam(defaultValue = "10") double rangePct) {

        if (legs == null || legs.isEmpty()) return badRequest("legs array is required");
        if (spot <= 0) return badRequest("spot must be positive");
        rangePct = Math.max(2, Math.min(rangePct, 30));

        List<Map<String, Object>> payoff = new ArrayList<>();
        double rangeStart = spot * (1 - rangePct / 100.0);
        double rangeEnd = spot * (1 + rangePct / 100.0);
        double step = (rangeEnd - rangeStart) / 200.0;
        double maxProfit = -Double.MAX_VALUE;
        double maxLoss = Double.MAX_VALUE;

        for (double price = rangeStart; price <= rangeEnd; price += step) {
            double totalPnl = 0;
            for (Map<String, Object> leg : legs) {
                String type = String.valueOf(leg.getOrDefault("type", "CE"));
                double legStrike = toDouble(leg.getOrDefault("strike", spot));
                double prem = toDouble(leg.getOrDefault("premium", 0));
                String side = String.valueOf(leg.getOrDefault("side", "BUY"));
                int legQty = toInt(leg.getOrDefault("qty", 75));

                double intrinsic = "CE".equalsIgnoreCase(type)
                        ? Math.max(0, price - legStrike)
                        : Math.max(0, legStrike - price);

                double legPnl = "BUY".equalsIgnoreCase(side)
                        ? (intrinsic - prem) * legQty
                        : (prem - intrinsic) * legQty;

                totalPnl += legPnl;
            }
            maxProfit = Math.max(maxProfit, totalPnl);
            maxLoss = Math.min(maxLoss, totalPnl);
            payoff.add(Map.of(
                    "price", round2(price),
                    "pnl", round2(totalPnl)
            ));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("legs", legs.size());
        result.put("spot", spot);
        result.put("maxProfit", round2(maxProfit));
        result.put("maxLoss", round2(maxLoss));
        result.put("riskRewardRatio", maxLoss != 0
                ? round2(Math.abs(maxProfit / maxLoss)) : "Unlimited");
        result.put("payoffCurve", payoff);
        return ResponseEntity.ok(result);
    }

    private double calculatePnl(String strategy, double price, double strike,
                                 double strike2, double premium, double premium2,
                                 int qty, int lotSize) {
        double p2 = premium2 > 0 ? premium2 : premium * 0.4;

        return switch (strategy.toLowerCase()) {
            case "long_call" -> (Math.max(0, price - strike) - premium) * qty;
            case "long_put" -> (Math.max(0, strike - price) - premium) * qty;
            case "short_call" -> (premium - Math.max(0, price - strike)) * qty;
            case "short_put" -> (premium - Math.max(0, strike - price)) * qty;

            case "short_straddle", "straddle" -> {
                double halfPrem = premium / 2.0;
                yield (halfPrem - Math.max(0, price - strike)
                        + halfPrem - Math.max(0, strike - price)) * qty;
            }
            case "long_straddle" -> {
                double halfPrem = premium / 2.0;
                yield (Math.max(0, price - strike) - halfPrem
                        + Math.max(0, strike - price) - halfPrem) * qty;
            }

            case "short_strangle", "strangle" -> {
                double ceStrike = strike2 > 0 ? strike2 : strike + 200;
                double halfPrem = premium / 2.0;
                yield (halfPrem - Math.max(0, price - ceStrike)
                        + halfPrem - Math.max(0, strike - price)) * qty;
            }
            case "long_strangle" -> {
                double ceStrike = strike2 > 0 ? strike2 : strike + 200;
                double halfPrem = premium / 2.0;
                yield (Math.max(0, price - ceStrike) - halfPrem
                        + Math.max(0, strike - price) - halfPrem) * qty;
            }

            case "bull_call_spread" -> {
                double s2 = strike2 > 0 ? strike2 : strike + 200;
                double netDebit = premium - p2;
                yield (Math.max(0, price - strike) - Math.max(0, price - s2) - netDebit) * qty;
            }
            case "bear_put_spread" -> {
                double s2 = strike2 > 0 ? strike2 : strike - 200;
                double netDebit = premium - p2;
                yield (Math.max(0, strike - price) - Math.max(0, s2 - price) - netDebit) * qty;
            }

            case "iron_condor" -> {
                // Sell CE at strike+200, buy CE at strike+400
                // Sell PE at strike-200, buy PE at strike-400
                // Net credit = premium (total received)
                double netCredit = premium;
                double sellCeLoss = -Math.max(0, price - (strike + 200));
                double buyCeGain = Math.max(0, price - (strike + 400));
                double sellPeLoss = -Math.max(0, (strike - 200) - price);
                double buyPeGain = Math.max(0, (strike - 400) - price);
                yield (netCredit + sellCeLoss + buyCeGain + sellPeLoss + buyPeGain) * qty;
            }

            case "butterfly" -> {
                // Buy 1 lower, sell 2 middle, buy 1 upper
                int wing = 100;
                double netDebit = premium;
                double lower = Math.max(0, price - (strike - wing));
                double middle = -2 * Math.max(0, price - strike);
                double upper = Math.max(0, price - (strike + wing));
                yield (lower + middle + upper - netDebit) * qty;
            }

            case "jade_lizard" -> {
                // Short OTM call spread + short OTM put
                double shortPut = premium * 0.3 - Math.max(0, (strike - 200) - price);
                double shortCall = premium * 0.4 - Math.max(0, price - (strike + 200));
                double longCall = Math.max(0, price - (strike + 400)) - premium * 0.1;
                yield (shortPut + shortCall + longCall) * qty;
            }

            case "synthetic_long" -> {
                // Buy CE + Sell PE at same strike
                double cePnl = Math.max(0, price - strike) - premium / 2.0;
                double pePnl = premium / 2.0 - Math.max(0, strike - price);
                yield (cePnl + pePnl) * qty;
            }
            case "synthetic_short" -> {
                // Sell CE + Buy PE at same strike
                double cePnl = premium / 2.0 - Math.max(0, price - strike);
                double pePnl = Math.max(0, strike - price) - premium / 2.0;
                yield (cePnl + pePnl) * qty;
            }

            default -> 0;
        };
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static double toDouble(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(String.valueOf(o)); } catch (Exception e) { return 0; }
    }

    private static int toInt(Object o) {
        if (o instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(o)); } catch (Exception e) { return 0; }
    }

    private static ResponseEntity<Map<String, Object>> badRequest(String msg) {
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }
}
