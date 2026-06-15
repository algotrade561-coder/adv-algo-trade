package com.algo.trade.broker;

import com.algo.trade.domain.MarginSnapshot;
import com.algo.trade.domain.OrderRequest;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Broker margin queries for spread pre-flight checks.
 */
public interface BrokerMarginClient {

    /** Equity segment funds available for new positions. */
    Optional<MarginSnapshot> equityMargins();

    /**
     * SPAN + exposure for a basket of orders (Kite {@code /margins/basket}).
     */
    Optional<BigDecimal> basketOrderMargin(List<OrderRequest> orders, boolean considerPositions);
}
