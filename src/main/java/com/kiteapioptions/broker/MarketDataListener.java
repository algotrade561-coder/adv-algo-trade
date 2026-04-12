package com.kiteapioptions.broker;

import com.kiteapioptions.domain.Quote;

/**
 * Callback used by broker adapters to push streaming market data into the application.
 */
@FunctionalInterface
public interface MarketDataListener {

    void onQuote(Quote quote);
}
