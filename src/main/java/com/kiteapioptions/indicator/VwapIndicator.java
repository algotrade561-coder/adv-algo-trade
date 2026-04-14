package com.kiteapioptions.indicator;

import com.kiteapioptions.domain.Candle;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

/**
 * Volume weighted average price calculator.
 * Supports both rolling-window and session-anchored (9:15 IST) VWAP.
 */
public class VwapIndicator {

    private static final MathContext MATH_CONTEXT = MathContext.DECIMAL64;
    private static final LocalTime SESSION_START = LocalTime.of(9, 15);

    public BigDecimal calculate(List<Candle> candles) {
        return calculateFrom(candles, 0);
    }

    /**
     * Session-anchored VWAP: uses only candles from 9:15 IST of the latest candle's date.
     * This is the institutionally correct VWAP — resets every trading day at open.
     */
    public BigDecimal calculateSessionAnchored(List<Candle> candles, ZoneId zoneId) {
        if (candles == null || candles.isEmpty()) {
            return BigDecimal.ZERO;
        }
        LocalDate latestDate = LocalDate.ofInstant(candles.getLast().timestamp(), zoneId);
        int sessionStartIndex = candles.size();
        for (int i = 0; i < candles.size(); i++) {
            LocalDate candleDate = LocalDate.ofInstant(candles.get(i).timestamp(), zoneId);
            LocalTime candleTime = LocalTime.ofInstant(candles.get(i).timestamp(), zoneId);
            if (candleDate.equals(latestDate) && !candleTime.isBefore(SESSION_START)) {
                sessionStartIndex = i;
                break;
            }
        }
        if (sessionStartIndex >= candles.size()) {
            return BigDecimal.ZERO;
        }
        return calculateFrom(candles, sessionStartIndex);
    }

    private BigDecimal calculateFrom(List<Candle> candles, int fromIndex) {
        if (candles == null || candles.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal priceVolume = BigDecimal.ZERO;
        long totalVolume = 0;
        for (int i = fromIndex; i < candles.size(); i++) {
            Candle candle = candles.get(i);
            if (candle.volume() <= 0) {
                continue;
            }
            BigDecimal typicalPrice = candle.high().add(candle.low()).add(candle.close())
                    .divide(BigDecimal.valueOf(3), MATH_CONTEXT);
            priceVolume = priceVolume.add(typicalPrice.multiply(BigDecimal.valueOf(candle.volume()), MATH_CONTEXT));
            totalVolume += candle.volume();
        }
        if (totalVolume == 0) {
            return candles.getLast().close();
        }
        return priceVolume.divide(BigDecimal.valueOf(totalVolume), MATH_CONTEXT);
    }
}
