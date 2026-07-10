package com.algo.trade.execution;

import com.algo.trade.domain.IndexType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Lot Size Validator — ensures order quantities are valid multiples of the exchange lot size.
 *
 * Lot sizes are sourced from {@link IndexType} (the single source of truth, updated per the
 * NSE circular effective Jan 2026: NIFTY=65, BANKNIFTY=30, FINNIFTY=60, MIDCPNIFTY=120, SENSEX=20).
 *
 * Auto-adjusts quantity to the nearest valid lot multiple (rounds down).
 */
@Component
public class LotSizeValidator {

    private static final Logger log = LoggerFactory.getLogger(LotSizeValidator.class);

    public record ValidationResult(boolean valid, int adjustedQuantity, int lotSize, String reason) {}

    /**
     * Validate and auto-adjust quantity to valid lot multiple.
     */
    public ValidationResult validate(String tradingSymbol, int quantity) {
        int lotSize = resolveLotSize(tradingSymbol);
        if (lotSize <= 0) {
            return new ValidationResult(true, quantity, 0, "Unknown lot size — passing through");
        }

        if (quantity <= 0) {
            return new ValidationResult(false, 0, lotSize, "Quantity must be positive");
        }

        if (quantity % lotSize == 0) {
            return new ValidationResult(true, quantity, lotSize, "Valid lot multiple");
        }

        // Round down to nearest lot multiple
        int adjustedQty = (quantity / lotSize) * lotSize;
        if (adjustedQty <= 0) adjustedQty = lotSize; // minimum 1 lot

        log.warn("[LotSize] Auto-adjusted: {} → {} (lot size={})", quantity, adjustedQty, lotSize);
        return new ValidationResult(false, adjustedQty, lotSize, "Adjusted to valid lot multiple");
    }

    /**
     * Get lot size for a given index — delegates to {@link IndexType} (single source of truth).
     */
    public int getLotSize(IndexType indexType) {
        return indexType.lotSize();
    }

    /** Lot size for a trading symbol / instrument key (prefix match); 0 = unknown. */
    public int lotSizeFor(String tradingSymbol) {
        return resolveLotSize(tradingSymbol);
    }

    private int resolveLotSize(String tradingSymbol) {
        if (tradingSymbol == null) return 0;
        String upper = tradingSymbol.toUpperCase();
        // Order matters: most specific prefixes first (BANKNIFTY/MIDCPNIFTY/FINNIFTY before NIFTY).
        if (upper.startsWith("BANKNIFTY")) return IndexType.BANKNIFTY.lotSize();
        if (upper.startsWith("MIDCPNIFTY")) return IndexType.MIDCPNIFTY.lotSize();
        if (upper.startsWith("FINNIFTY")) return IndexType.FINNIFTY.lotSize();
        if (upper.startsWith("NIFTY")) return IndexType.NIFTY.lotSize();
        if (upper.startsWith("SENSEX")) return IndexType.SENSEX.lotSize();
        return 0;
    }
}
