package com.algo.trade.backtest.v2;

import com.algo.trade.data.ChainSnapshot;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Generates synthetic ChainSnapshot data for testing.
 * 
 * Creates realistic option chain snapshots with:
 * - ATM ± 10 strikes
 * - Realistic OI, volume, IV, Greeks
 * - Price movements and OI changes
 */
public class TestSnapshotGenerator {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /**
     * Generate a series of snapshots for a single day.
     * 
     * @param underlying Underlying symbol (e.g., "NIFTY")
     * @param date Date for snapshots
     * @param startSpot Starting spot price
     * @param trend Price trend: 1 = bullish, -1 = bearish, 0 = sideways
     * @param volatility Volatility level (affects price swings)
     * @return List of snapshots from 9:20 to 15:25 (5-min intervals)
     */
    public static List<ChainSnapshot> generateDay(
            String underlying,
            LocalDate date,
            double startSpot,
            int trend,
            double volatility
    ) {
        List<ChainSnapshot> snapshots = new ArrayList<>();
        
        // Market hours: 9:20 to 15:25 (5-min intervals)
        LocalTime start = LocalTime.of(9, 20);
        LocalTime end = LocalTime.of(15, 25);
        
        double spot = startSpot;
        double vix = 15.0 + (volatility * 5.0); // Base VIX 15-25
        
        for (LocalTime time = start; !time.isAfter(end); time = time.plusMinutes(5)) {
            Instant timestamp = date.atTime(time).atZone(IST).toInstant();
            
            // Simulate price movement
            double priceChange = (Math.random() - 0.5) * volatility * 50;
            if (trend > 0) {
                priceChange += volatility * 10; // Bullish bias
            } else if (trend < 0) {
                priceChange -= volatility * 10; // Bearish bias
            }
            spot += priceChange;
            
            // Simulate VIX changes
            vix += (Math.random() - 0.5) * 2.0;
            vix = Math.max(10.0, Math.min(30.0, vix));
            
            ChainSnapshot snapshot = generateSnapshot(
                    underlying,
                    timestamp,
                    spot,
                    vix,
                    date.plusDays(7) // Weekly expiry
            );
            
            snapshots.add(snapshot);
        }
        
        return snapshots;
    }

    /**
     * Generate a single snapshot with realistic option chain data.
     */
    public static ChainSnapshot generateSnapshot(
            String underlying,
            Instant timestamp,
            double spot,
            double vix,
            LocalDate expiry
    ) {
        int atmStrike = roundToStrike(spot, getStrikeInterval(underlying));
        int interval = getStrikeInterval(underlying);
        
        List<ChainSnapshot.StrikeData> strikes = new ArrayList<>();
        
        // Generate ATM ± 10 strikes
        for (int i = -10; i <= 10; i++) {
            int strike = atmStrike + (i * interval);
            strikes.add(generateStrikeData(strike, spot, vix, expiry, timestamp));
        }
        
        return new ChainSnapshot(
                timestamp,
                underlying,
                spot,
                vix,
                expiry.toString(),
                atmStrike,
                strikes
        );
    }

    /**
     * Generate realistic strike data with Greeks, OI, volume.
     */
    private static ChainSnapshot.StrikeData generateStrikeData(
            int strike,
            double spot,
            double vix,
            LocalDate expiry,
            Instant timestamp
    ) {
        double moneyness = (strike - spot) / spot;
        
        // CE data
        double ceLTP = calculateOptionPrice(strike, spot, vix, true, moneyness);
        long ceOI = calculateOI(moneyness, true);
        long ceVolume = (long) (ceOI * 0.05 * (1 + Math.random())); // 5% of OI
        double ceIV = vix + (Math.abs(moneyness) * 5); // IV smile
        double ceDelta = calculateDelta(moneyness, true);
        double ceGamma = calculateGamma(moneyness);
        double ceTheta = -ceLTP * 0.05; // Time decay
        double ceVega = ceLTP * 0.1;
        double ceBid = ceLTP * 0.995;
        double ceAsk = ceLTP * 1.005;
        long ceOiChange = (long) ((Math.random() - 0.5) * ceOI * 0.1); // ±10% change
        double ceHigh5m = ceLTP * (1 + Math.random() * 0.02);
        double ceLow5m = ceLTP * (1 - Math.random() * 0.02);
        
        // PE data
        double peLTP = calculateOptionPrice(strike, spot, vix, false, moneyness);
        long peOI = calculateOI(moneyness, false);
        long peVolume = (long) (peOI * 0.05 * (1 + Math.random()));
        double peIV = vix + (Math.abs(moneyness) * 5);
        double peDelta = calculateDelta(moneyness, false);
        double peGamma = calculateGamma(moneyness);
        double peTheta = -peLTP * 0.05;
        double peVega = peLTP * 0.1;
        double peBid = peLTP * 0.995;
        double peAsk = peLTP * 1.005;
        long peOiChange = (long) ((Math.random() - 0.5) * peOI * 0.1);
        double peHigh5m = peLTP * (1 + Math.random() * 0.02);
        double peLow5m = peLTP * (1 - Math.random() * 0.02);
        
        return new ChainSnapshot.StrikeData(
                strike,
                ceLTP, ceOI, ceVolume, ceIV, ceDelta, ceGamma, ceTheta, ceVega,
                ceBid, ceAsk, ceOiChange, ceHigh5m, ceLow5m,
                peLTP, peOI, peVolume, peIV, peDelta, peGamma, peTheta, peVega,
                peBid, peAsk, peOiChange, peHigh5m, peLow5m
        );
    }

    /**
     * Calculate option price using simplified Black-Scholes approximation.
     */
    private static double calculateOptionPrice(int strike, double spot, double vix, boolean isCall, double moneyness) {
        double intrinsic = isCall 
                ? Math.max(0, spot - strike)
                : Math.max(0, strike - spot);
        
        double timeValue = Math.abs(moneyness) * spot * (vix / 100) * 0.5;
        double price = intrinsic + timeValue;
        
        return Math.max(0.05, price); // Minimum 0.05
    }

    /**
     * Calculate realistic OI based on moneyness.
     * ATM has highest OI, decreases as we move away.
     */
    private static long calculateOI(double moneyness, boolean isCall) {
        double distance = Math.abs(moneyness);
        long baseOI = 100_000;
        
        if (distance < 0.01) {
            // ATM
            return baseOI * 3;
        } else if (distance < 0.02) {
            // Near ATM
            return baseOI * 2;
        } else if (distance < 0.05) {
            // Moderate OTM/ITM
            return baseOI;
        } else {
            // Far OTM/ITM
            return (long) (baseOI * 0.5);
        }
    }

    /**
     * Calculate delta (simplified).
     */
    private static double calculateDelta(double moneyness, boolean isCall) {
        if (isCall) {
            if (moneyness < -0.05) return 0.9;  // Deep ITM
            if (moneyness < -0.02) return 0.7;  // ITM
            if (moneyness < 0.02) return 0.5;   // ATM
            if (moneyness < 0.05) return 0.3;   // OTM
            return 0.1;  // Deep OTM
        } else {
            if (moneyness > 0.05) return -0.9;  // Deep ITM
            if (moneyness > 0.02) return -0.7;  // ITM
            if (moneyness > -0.02) return -0.5; // ATM
            if (moneyness > -0.05) return -0.3; // OTM
            return -0.1;  // Deep OTM
        }
    }

    /**
     * Calculate gamma (simplified).
     * Highest at ATM, decreases as we move away.
     */
    private static double calculateGamma(double moneyness) {
        double distance = Math.abs(moneyness);
        if (distance < 0.01) return 0.015;  // ATM
        if (distance < 0.02) return 0.010;
        if (distance < 0.05) return 0.005;
        return 0.001;
    }

    /**
     * Round spot price to nearest strike.
     */
    private static int roundToStrike(double spot, int interval) {
        return (int) (Math.round(spot / interval) * interval);
    }

    /**
     * Get strike interval for underlying.
     */
    private static int getStrikeInterval(String underlying) {
        return switch (underlying) {
            case "NIFTY" -> 50;
            case "BANKNIFTY" -> 100;
            case "FINNIFTY" -> 50;
            case "SENSEX" -> 100;
            default -> 50;
        };
    }

    /**
     * Generate bullish trend snapshots (price moving up).
     */
    public static List<ChainSnapshot> generateBullishTrend(String underlying, LocalDate date, double startSpot) {
        return generateDay(underlying, date, startSpot, 1, 1.0);
    }

    /**
     * Generate bearish trend snapshots (price moving down).
     */
    public static List<ChainSnapshot> generateBearishTrend(String underlying, LocalDate date, double startSpot) {
        return generateDay(underlying, date, startSpot, -1, 1.0);
    }

    /**
     * Generate sideways market snapshots (range-bound).
     */
    public static List<ChainSnapshot> generateSidewaysMarket(String underlying, LocalDate date, double startSpot) {
        return generateDay(underlying, date, startSpot, 0, 0.5);
    }

    /**
     * Generate high volatility snapshots (large price swings).
     */
    public static List<ChainSnapshot> generateHighVolatility(String underlying, LocalDate date, double startSpot) {
        return generateDay(underlying, date, startSpot, 0, 2.0);
    }
}
