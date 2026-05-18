package com.algo.trade.execution.exit;

import com.algo.trade.domain.Quote;
import com.algo.trade.marketdata.MarketDataService;
import com.algo.trade.persistence.SpreadLegEntity;
import com.algo.trade.persistence.TradeEntity;
import com.algo.trade.strategy.StrategyConfig;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Captures bid–ask / volume / OI baselines at entry for later liquidity exits.
 */
@Component
public class EntryLiquidityRecorder {

    private final MarketDataService marketDataService;

    public EntryLiquidityRecorder(MarketDataService marketDataService) {
        this.marketDataService = marketDataService;
    }

    public void recordTradeEntry(TradeEntity trade, StrategyConfig config) {
        if (trade == null) {
            return;
        }
        if (config != null) {
            trade.setAppliedStopLossPercent(config.getStopLossPercent());
            trade.setAppliedTargetPercent(config.getTargetPercent());
        }
        marketDataService.quote(trade.getInstrumentKey()).ifPresent(q -> apply(trade, q));
    }

    public void apply(TradeEntity trade, Quote quote) {
        EntryLiquiditySnapshot snap = EntryLiquiditySnapshot.fromQuote(quote);
        trade.setEntryBidAskSpreadPercent(snap.bidAskSpreadPercent());
        trade.setEntryVolume(snap.volume());
        trade.setEntryOpenInterest(snap.openInterest());
    }

    public void recordSpreadLegs(Iterable<SpreadLegEntity> legs, Map<String, Quote> quotes) {
        for (SpreadLegEntity leg : legs) {
            Quote q = quotes.get(leg.getInstrumentKey());
            if (q == null) {
                marketDataService.quote(leg.getInstrumentKey()).ifPresent(quote -> apply(leg, quote));
            } else {
                apply(leg, q);
            }
        }
    }

    public void apply(SpreadLegEntity leg, Quote quote) {
        EntryLiquiditySnapshot snap = EntryLiquiditySnapshot.fromQuote(quote);
        leg.setEntryBidAskSpreadPercent(snap.bidAskSpreadPercent());
        leg.setEntryVolume(snap.volume());
        leg.setEntryOpenInterest(snap.openInterest());
    }
}
