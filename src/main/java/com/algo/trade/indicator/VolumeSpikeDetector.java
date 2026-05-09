package com.algo.trade.indicator;

import com.algo.trade.domain.Candle;
import java.math.BigDecimal;
import java.util.List;

/**
 * Detects whether the latest candle volume exceeds recent average volume by a multiplier.
 */
public class VolumeSpikeDetector {

    public boolean hasSpike(List<Candle> candles, int lookback, BigDecimal multiplier) {
        if (candles == null || candles.size() < 2) {
            return false;
        }
        if (lookback <= 0) {
            throw new IllegalArgumentException("lookback must be positive");
        }

        int latestIndex = candles.size() - 1;
        int start = Math.max(0, latestIndex - lookback);
        List<Candle> recent = candles.subList(start, latestIndex);
        if (recent.isEmpty()) {
            return false;
        }

        double average = recent.stream().mapToLong(Candle::volume).average().orElse(0);
        return average > 0 && BigDecimal.valueOf(candles.getLast().volume())
                .compareTo(BigDecimal.valueOf(average).multiply(multiplier)) > 0;
    }
}
