package com.algo.trade.domain;

import com.algo.trade.strategy.StrategyConfig;

import java.math.BigDecimal;
import java.util.List;

/**
 * Context passed to spread strategy evaluation — contains all market data
 * and configuration needed to decide whether to enter a spread position.
 * trendCandles: the candle series (typically 15-min) used for EMA trend computation.
 */
public record SpreadEvaluationContext(
        BigDecimal underlyingPrice,
        double ivRank,
        OptionChainSnapshot optionChain,
        StrategyConfig config,
        UnderlyingSymbol underlying,
        IndexType indexType,
        List<Candle> trendCandles
) {}
