package com.algo.trade.execution;

import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.math.MathContext;

/**
 * Calculates actual net P&L after all Zerodha charges for NSE F&O options.
 *
 * Charges per order:
 * - Brokerage:        ₹20 flat per executed order
 * - STT:              0.0625% of premium on SELL side only (updated 2024)
 * - Exchange charges:  0.0495% of premium (NSE)
 * - SEBI charges:     ₹10 per crore of turnover
 * - GST:              18% on (brokerage + exchange charges)
 * - Stamp duty:       0.003% on BUY side only
 *
 * For a round trip (buy + sell), total charges ≈ ₹80–150 per lot.
 */
@Component
public class BrokerageCalculator {

    private static final MathContext MC = MathContext.DECIMAL64;
    private static final BigDecimal BROKERAGE_PER_ORDER = BigDecimal.valueOf(20);
    private static final BigDecimal STT_RATE = new BigDecimal("0.000625");
    private static final BigDecimal EXCHANGE_RATE = new BigDecimal("0.000495");
    private static final BigDecimal SEBI_RATE = new BigDecimal("0.000001");
    private static final BigDecimal GST_RATE = new BigDecimal("0.18");
    private static final BigDecimal STAMP_DUTY_RATE = new BigDecimal("0.00003");

    /** Calculate total charges for a single option order. */
    public BigDecimal calculateCharges(BigDecimal premium, int quantity, boolean isBuy) {
        BigDecimal turnover = premium.multiply(BigDecimal.valueOf(quantity), MC);
        BigDecimal stt = isBuy ? BigDecimal.ZERO : turnover.multiply(STT_RATE, MC);
        BigDecimal exchangeCharge = turnover.multiply(EXCHANGE_RATE, MC);
        BigDecimal sebiCharge = turnover.multiply(SEBI_RATE, MC);
        BigDecimal gst = BROKERAGE_PER_ORDER.add(exchangeCharge).multiply(GST_RATE, MC);
        BigDecimal stampDuty = isBuy ? turnover.multiply(STAMP_DUTY_RATE, MC) : BigDecimal.ZERO;
        return BROKERAGE_PER_ORDER.add(stt).add(exchangeCharge).add(sebiCharge).add(gst).add(stampDuty);
    }

    /** Calculate net P&L for a complete round trip (entry + exit). */
    public BigDecimal netPnl(BigDecimal entryPremium, BigDecimal exitPremium, int quantity, boolean isBuyEntry) {
        BigDecimal grossPnl = isBuyEntry
                ? exitPremium.subtract(entryPremium).multiply(BigDecimal.valueOf(quantity), MC)
                : entryPremium.subtract(exitPremium).multiply(BigDecimal.valueOf(quantity), MC);
        BigDecimal entryCharges = calculateCharges(entryPremium, quantity, isBuyEntry);
        BigDecimal exitCharges = calculateCharges(exitPremium, quantity, !isBuyEntry);
        return grossPnl.subtract(entryCharges).subtract(exitCharges);
    }

    /** Estimate round-trip charges for a given premium and quantity. */
    public BigDecimal estimateRoundTripCharges(BigDecimal premium, int quantity) {
        return calculateCharges(premium, quantity, true).add(calculateCharges(premium, quantity, false));
    }

    /** Minimum premium move needed to break even (cover charges). */
    public BigDecimal breakEvenMove(BigDecimal premium, int quantity) {
        if (quantity <= 0) return BigDecimal.ZERO;
        return estimateRoundTripCharges(premium, quantity).divide(BigDecimal.valueOf(quantity), MC);
    }
}
