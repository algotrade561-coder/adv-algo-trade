package com.algo.trade.strategy.oimomentum;

/**
 * Estimates the round-trip (buy + sell) transaction cost of an Indian index-options trade on
 * Zerodha, used by the charges-aware entry gate to decide whether a trade's expected net is
 * worth taking.
 *
 * <p>This is a deliberately conservative <b>cost-floor</b> estimate: the exit premium is
 * approximated by the entry premium (we don't know the exit yet), which is good enough for a
 * go/no-go gate. It is not a settlement-grade contract-note calculation.</p>
 *
 * <p>Rate sources (NSE / Zerodha, India). These change with budgets and exchange circulars, so
 * they live here as named constants for a one-line update when rates move:</p>
 * <ul>
 *   <li>Brokerage: flat ₹20 per executed order (₹20 × 2 legs).</li>
 *   <li>STT: 0.10% on the SELL-side option premium.</li>
 *   <li>Exchange transaction charge (NSE options): 0.03503% of premium turnover, each leg.</li>
 *   <li>SEBI turnover fee: ₹10 per crore = 0.0001% of turnover, each leg.</li>
 *   <li>Stamp duty: 0.003% on the BUY-side premium.</li>
 *   <li>GST: 18% on (brokerage + exchange txn + SEBI).</li>
 * </ul>
 */
public final class OptionsChargesEstimator {

    private OptionsChargesEstimator() {}

    /** ₹ per executed order (Zerodha flat options brokerage). */
    public static final double BROKERAGE_PER_ORDER = 20.0;
    /** STT on the sell-side option premium (0.10%). */
    public static final double STT_SELL_RATE = 0.0010;
    /** NSE options exchange transaction charge per leg (0.03503% of premium turnover). */
    public static final double EXCHANGE_TXN_RATE = 0.0003503;
    /** SEBI turnover fee per leg (₹10 / crore). */
    public static final double SEBI_RATE = 0.000001;
    /** Stamp duty on the buy-side premium (0.003%). */
    public static final double STAMP_BUY_RATE = 0.00003;
    /** GST on (brokerage + exchange txn + SEBI). */
    public static final double GST_RATE = 0.18;

    /**
     * Estimated total round-trip charges (₹) to buy then sell {@code qty} shares of an option
     * at {@code premiumPerShare}. The exit premium is approximated by the entry premium, so the
     * result is a stable lower-bound-style cost estimate for the entry gate.
     *
     * @param premiumPerShare option premium per share (LTP), in ₹
     * @param qty             total shares (lots × contract size); must be &gt; 0
     * @return estimated round-trip cost in ₹, or 0 for non-positive inputs
     */
    public static double roundTripCharges(double premiumPerShare, int qty) {
        if (premiumPerShare <= 0 || qty <= 0) return 0.0;
        double legTurnover = premiumPerShare * qty;      // one side's premium value
        double brokerage = BROKERAGE_PER_ORDER * 2.0;    // entry + exit
        double stt = legTurnover * STT_SELL_RATE;        // sell side only
        double exchTxn = (legTurnover * 2.0) * EXCHANGE_TXN_RATE;
        double sebi = (legTurnover * 2.0) * SEBI_RATE;
        double stamp = legTurnover * STAMP_BUY_RATE;     // buy side only
        double gst = GST_RATE * (brokerage + exchTxn + sebi);
        return brokerage + stt + exchTxn + sebi + stamp + gst;
    }
}
