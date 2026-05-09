package com.algo.trade.backtest;

import com.algo.trade.domain.OptionChainSnapshot;
import java.time.Instant;

/**
 * An option chain snapshot associated with a specific point in time,
 * used for intraday chain aggregation.
 */
public record TimestampedOptionChain(
        Instant timestamp,
        OptionChainSnapshot chain
) {
    public TimestampedOptionChain {
        if (timestamp == null) throw new IllegalArgumentException("timestamp must not be null");
        if (chain == null) throw new IllegalArgumentException("chain must not be null");
    }
}
