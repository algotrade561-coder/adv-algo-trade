package com.algo.trade.util;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Calculates Zerodha F&O Options trading charges per executed order.
 *
 * Based on Zerodha's published fee structure (May 2026):
 * - Brokerage: Flat ₹20 per executed order
 * - STT: 0.15% on sell side (on premium)
 * - Transaction charges (NSE): 0.03553% on premium
 * - GST: 18% on (brokerage + SEBI charges + transaction charges)
 * - SEBI charges: ₹10 per crore
 * - Stamp duty: 0.003% on buy side (₹300 per crore)
 */
public final class ZerodhaChargesCalculator {

    private static final MathContext MC = MathContext.DECIMAL64;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal CRORE = BigDecimal.valueOf(10_000_000);

    // Zerodha F&O Options charges
    private static final BigDecimal BROKERAGE_PER_ORDER = BigDecimal.valueOf(20);
    private static final BigDecimal STT_SELL_PERCENT = BigDecimal.valueOf(0.15);       // 0.15% on sell premium
    private static final BigDecimal TRANSACTION_CHARGES_PERCENT = BigDecimal.valueOf(0.03553); // NSE
    private static final BigDecimal GST_PERCENT = BigDecimal.valueOf(18);              // 18% on brokerage + SEBI + txn
    private static final BigDecimal SEBI_PER_CRORE = BigDecimal.valueOf(10);           // ₹10 per crore
    private static final BigDecimal STAMP_BUY_PERCENT = BigDecimal.valueOf(0.003);     // 0.003% on buy side

    private ZerodhaChargesCalculator() {}

    /**
     * Calculate total charges for a single round-trip trade (buy + sell).
     *
     * @param buyPremium  Premium paid per unit on buy side
     * @param sellPremium Premium received per unit on sell side
     * @param quantity    Number of units (lot size × lots)
     * @return Total charges for the round trip
     */
    public static ChargesBreakdown calculateRoundTrip(BigDecimal buyPremium, BigDecimal sellPremium, int quantity) {
        BigDecimal qty = BigDecimal.valueOf(quantity);

        // Turnover
        BigDecimal buyTurnover = buyPremium.multiply(qty, MC);
        BigDecimal sellTurnover = sellPremium.multiply(qty, MC);
        BigDecimal totalTurnover = buyTurnover.add(sellTurnover);

        // Brokerage: ₹20 per order × 2 (buy + sell)
        BigDecimal brokerage = BROKERAGE_PER_ORDER.multiply(BigDecimal.valueOf(2));

        // STT: 0.15% on sell side premium only
        BigDecimal stt = sellTurnover.multiply(STT_SELL_PERCENT).divide(HUNDRED, MC);

        // Transaction charges: 0.03553% on both sides
        BigDecimal txnCharges = totalTurnover.multiply(TRANSACTION_CHARGES_PERCENT).divide(HUNDRED, MC);

        // SEBI charges: ₹10 per crore on total turnover
        BigDecimal sebiCharges = totalTurnover.multiply(SEBI_PER_CRORE).divide(CRORE, MC);

        // Stamp duty: 0.003% on buy side only
        BigDecimal stampDuty = buyTurnover.multiply(STAMP_BUY_PERCENT).divide(HUNDRED, MC);

        // GST: 18% on (brokerage + SEBI + transaction charges)
        BigDecimal gstBase = brokerage.add(sebiCharges).add(txnCharges);
        BigDecimal gst = gstBase.multiply(GST_PERCENT).divide(HUNDRED, MC);

        // Total
        BigDecimal total = brokerage.add(stt).add(txnCharges).add(gst).add(sebiCharges).add(stampDuty);

        return new ChargesBreakdown(
                brokerage.setScale(2, RoundingMode.HALF_UP),
                stt.setScale(2, RoundingMode.HALF_UP),
                txnCharges.setScale(2, RoundingMode.HALF_UP),
                gst.setScale(2, RoundingMode.HALF_UP),
                sebiCharges.setScale(2, RoundingMode.HALF_UP),
                stampDuty.setScale(2, RoundingMode.HALF_UP),
                total.setScale(2, RoundingMode.HALF_UP),
                totalTurnover.setScale(2, RoundingMode.HALF_UP)
        );
    }

    /**
     * Simplified: estimate charges for a trade given entry price, exit price, and quantity.
     */
    public static BigDecimal estimateCharges(BigDecimal entryPrice, BigDecimal exitPrice, int quantity) {
        if (entryPrice == null || exitPrice == null || quantity <= 0) return BigDecimal.ZERO;
        return calculateRoundTrip(entryPrice, exitPrice, quantity).total();
    }

    /**
     * Estimate total charges for multiple trades.
     *
     * @param tradeCount Number of round-trip trades
     * @param avgPremium Average option premium per unit
     * @param quantity   Quantity per trade
     * @return Total estimated charges
     */
    public static BigDecimal estimateMultipleTradesCharges(int tradeCount, BigDecimal avgPremium, int quantity) {
        if (tradeCount <= 0 || avgPremium == null || avgPremium.signum() <= 0) return BigDecimal.ZERO;
        ChargesBreakdown perTrade = calculateRoundTrip(avgPremium, avgPremium, quantity);
        return perTrade.total().multiply(BigDecimal.valueOf(tradeCount));
    }

    /**
     * Detailed breakdown of all charges.
     */
    public record ChargesBreakdown(
            BigDecimal brokerage,
            BigDecimal stt,
            BigDecimal transactionCharges,
            BigDecimal gst,
            BigDecimal sebiCharges,
            BigDecimal stampDuty,
            BigDecimal total,
            BigDecimal turnover
    ) {}
}
