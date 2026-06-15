package com.algo.trade.execution;

import com.algo.trade.domain.IndexType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Lot Size Validator — ensures order quantities are valid multiples of the exchange lot size.
 *
 * NSE lot sizes (as of Jan 2026):
 *   NIFTY: 75, BANKNIFTY: 30, FINNIFTY: 40, MIDCPNIFTY: 50, SENSEX: 20
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
     * Get lot size for a given index.
     */
    public int getLotSize(IndexType indexType) {
        return switch (indexType) {
            case NIFTY -> 75;
            case BANKNIFTY -> 30;
            case FINNIFTY -> 40;
            case MIDCPNIFTY -> 50;
            case SENSEX -> 20;
        };
    }

    private int resolveLotSize(String tradingSymbol) {
        if (tradingSymbol == null) return 0;
        String upper = tradingSymbol.toUpperCase();
        if (upper.startsWith("BANKNIFTY")) return 30;
        if (upper.startsWith("NIFTY")) return 75;
        if (upper.startsWith("FINNIFTY")) return 40;
        if (upper.startsWith("MIDCPNIFTY")) return 50;
        if (upper.startsWith("SENSEX")) return 20;
        return 0;
    }
}
