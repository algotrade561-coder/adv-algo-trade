package com.algo.trade.commodity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Tracks MCX Crude Oil futures price in real-time via Kite WebSocket.
 * 
 * MCX Crude Oil tracks WTI crude (US benchmark), priced in INR per barrel.
 * Correlation with Brent crude: 95%+
 * 
 * Used for:
 * - Market regime detection (oil spikes → risk-off sentiment)
 * - Entry/exit filters (block entries on oil spikes >5%)
 * - Dashboard display (show current oil price and regime)
 */
@Component
public class OilPriceTracker {

    private static final Logger log = LoggerFactory.getLogger(OilPriceTracker.class);

    // Regime thresholds (USD per barrel)
    private static final double LOW_THRESHOLD_USD = 60.0;
    private static final double NORMAL_THRESHOLD_USD = 85.0;
    private static final double HIGH_THRESHOLD_USD = 100.0;

    // Assumed USD/INR rate for conversion (update periodically)
    private static final double USD_INR_RATE = 83.5;

    // Current state
    private volatile double currentPriceINR = 0.0;
    private volatile double previousDayCloseINR = 0.0;
    private volatile Instant lastUpdateTime;
    private volatile long instrumentToken = 0L;
    private volatile String tradingSymbol = "";
    private volatile LocalDate contractExpiry;

    // Historical tracking for momentum
    private volatile double ma5 = 0.0;  // 5-period moving average
    private volatile double ma20 = 0.0; // 20-period moving average
    private final double[] priceHistory = new double[20]; // Last 20 prices
    private int historyIndex = 0;
    private int historyCount = 0;

    /**
     * Update current oil price from WebSocket tick.
     * Called by KiteWebSocketClient when MCX crude oil tick arrives.
     */
    public void updatePrice(double priceINR) {
        this.currentPriceINR = priceINR;
        this.lastUpdateTime = Instant.now();

        // Update price history for moving averages
        priceHistory[historyIndex] = priceINR;
        historyIndex = (historyIndex + 1) % 20;
        if (historyCount < 20) historyCount++;

        // Recalculate moving averages
        calculateMovingAverages();

        log.debug("[OilPrice] Updated: ₹{} (${}) | Regime: {} | Change: {}%",
                String.format("%.0f", priceINR),
                String.format("%.2f", convertToUSD(priceINR)),
                getRegime(),
                String.format("%.2f", getDailyChangePct()));
    }

    /**
     * Set previous day's closing price (called at market open).
     */
    public void setPreviousDayClose(double closeINR) {
        this.previousDayCloseINR = closeINR;
        log.info("[OilPrice] Previous day close set: ₹{}", String.format("%.0f", closeINR));
    }

    /**
     * Set instrument details (token, symbol, expiry).
     * Called when MCX crude oil contract is identified from instruments CSV.
     */
    public void setInstrumentDetails(long token, String symbol, LocalDate expiry) {
        this.instrumentToken = token;
        this.tradingSymbol = symbol;
        this.contractExpiry = expiry;
        log.info("[OilPrice] Instrument set: {} (token: {}, expiry: {})", symbol, token, expiry);
    }

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Get current oil price in INR per barrel.
     */
    public double getCurrentPriceINR() {
        return currentPriceINR;
    }

    /**
     * Get current oil price in USD per barrel (converted from INR).
     */
    public double getCurrentPriceUSD() {
        return convertToUSD(currentPriceINR);
    }

    /**
     * Get daily change in INR.
     */
    public double getDailyChangeINR() {
        if (previousDayCloseINR == 0) return 0.0;
        return currentPriceINR - previousDayCloseINR;
    }

    /**
     * Get daily change percentage.
     */
    public double getDailyChangePct() {
        if (previousDayCloseINR == 0) return 0.0;
        return ((currentPriceINR - previousDayCloseINR) / previousDayCloseINR) * 100;
    }

    /**
     * Get oil price regime: LOW, NORMAL, HIGH, CRISIS.
     */
    public String getRegime() {
        double priceUSD = convertToUSD(currentPriceINR);
        if (priceUSD < LOW_THRESHOLD_USD) return "LOW";
        if (priceUSD < NORMAL_THRESHOLD_USD) return "NORMAL";
        if (priceUSD < HIGH_THRESHOLD_USD) return "HIGH";
        return "CRISIS";
    }

    /**
     * Get oil momentum: FALLING, STABLE, RISING, SPIKING.
     */
    public String getMomentum() {
        double changePct = getDailyChangePct();

        // Spiking: daily change > 3%
        if (Math.abs(changePct) >= 3.0) {
            return "SPIKING";
        }

        // Use MA20 for trend detection
        if (ma20 > 0 && historyCount >= 20) {
            double deviation = ((currentPriceINR - ma20) / ma20) * 100;
            if (deviation > 0.5) return "RISING";
            if (deviation < -0.5) return "FALLING";
        }

        return "STABLE";
    }

    /**
     * Check if oil spiked today (absolute change > threshold).
     */
    public boolean isSpiking(double thresholdPct) {
        return Math.abs(getDailyChangePct()) >= thresholdPct;
    }

    /**
     * Check if oil is in CRISIS regime (>$100/barrel).
     */
    public boolean isCrisisRegime() {
        return "CRISIS".equals(getRegime());
    }

    /**
     * Check if oil is in HIGH or CRISIS regime (>$85/barrel).
     */
    public boolean isHighRegime() {
        String regime = getRegime();
        return "HIGH".equals(regime) || "CRISIS".equals(regime);
    }

    /**
     * Get instrument token for WebSocket subscription.
     */
    public long getInstrumentToken() {
        return instrumentToken;
    }

    /**
     * Get trading symbol (e.g., "CRUDEOIL26JUNFUT").
     */
    public String getTradingSymbol() {
        return tradingSymbol;
    }

    /**
     * Get contract expiry date.
     */
    public LocalDate getContractExpiry() {
        return contractExpiry;
    }

    /**
     * Get last update timestamp.
     */
    public Instant getLastUpdateTime() {
        return lastUpdateTime;
    }

    /**
     * Check if data is available and recent (updated within last 5 minutes).
     */
    public boolean isDataAvailable() {
        if (currentPriceINR == 0 || lastUpdateTime == null) return false;
        return lastUpdateTime.plusSeconds(300).isAfter(Instant.now());
    }

    /**
     * Get snapshot for API/dashboard.
     */
    public OilPriceSnapshot getSnapshot() {
        return new OilPriceSnapshot(
                currentPriceINR,
                convertToUSD(currentPriceINR),
                getDailyChangeINR(),
                getDailyChangePct(),
                getRegime(),
                getMomentum(),
                tradingSymbol,
                contractExpiry,
                lastUpdateTime
        );
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private double convertToUSD(double priceINR) {
        return priceINR / USD_INR_RATE;
    }

    private void calculateMovingAverages() {
        if (historyCount < 5) return;

        // MA5
        double sum5 = 0;
        for (int i = 0; i < Math.min(5, historyCount); i++) {
            int idx = (historyIndex - 1 - i + 20) % 20;
            sum5 += priceHistory[idx];
        }
        ma5 = sum5 / Math.min(5, historyCount);

        // MA20
        if (historyCount >= 20) {
            double sum20 = 0;
            for (int i = 0; i < 20; i++) {
                sum20 += priceHistory[i];
            }
            ma20 = sum20 / 20;
        }
    }

    // ── Snapshot record ───────────────────────────────────────────────────

    public record OilPriceSnapshot(
            double priceINR,
            double priceUSD,
            double dailyChangeINR,
            double dailyChangePct,
            String regime,
            String momentum,
            String tradingSymbol,
            LocalDate contractExpiry,
            Instant lastUpdate
    ) {}
}
