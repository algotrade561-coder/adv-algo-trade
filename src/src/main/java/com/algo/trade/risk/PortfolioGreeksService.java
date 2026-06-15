package com.algo.trade.risk;

import com.algo.trade.domain.IndexType;
import com.algo.trade.domain.OrderSide;
import com.algo.trade.domain.PositionGroupStatus;
import com.algo.trade.indicator.GreeksCalculator;
import com.algo.trade.marketdata.LiveInstrumentCache;
import com.algo.trade.marketdata.MarketDataService;
import com.algo.trade.persistence.PositionGroupEntity;
import com.algo.trade.persistence.PositionGroupRepository;
import com.algo.trade.persistence.SpreadLegEntity;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Aggregates portfolio-level Greeks across all open spread positions.
 * Provides net delta, gamma, vega, theta per underlying and total.
 */
@Service
public class PortfolioGreeksService {

    private static final Logger log = LoggerFactory.getLogger(PortfolioGreeksService.class);

    private final PositionGroupRepository positionGroupRepository;
    private final GreeksCalculator greeksCalculator;
    private final MarketDataService marketDataService;
    private final LiveInstrumentCache liveInstrumentCache;

    public PortfolioGreeksService(PositionGroupRepository positionGroupRepository,
                                  GreeksCalculator greeksCalculator,
                                  MarketDataService marketDataService,
                                  LiveInstrumentCache liveInstrumentCache) {
        this.positionGroupRepository = positionGroupRepository;
        this.greeksCalculator = greeksCalculator;
        this.marketDataService = marketDataService;
        this.liveInstrumentCache = liveInstrumentCache;
    }

    public record PortfolioGreeks(
            double netDelta,
            double netGamma,
            double netTheta,
            double netVega,
            Map<String, PerUnderlyingGreeks> byUnderlying
    ) {}

    public record PerUnderlyingGreeks(
            String underlying,
            double netDelta,
            double netGamma,
            double netTheta,
            double netVega,
            int openGroups
    ) {}

    /**
     * Compute aggregate Greeks across all open spread positions.
     */
    public PortfolioGreeks compute() {
        List<PositionGroupEntity> openGroups = positionGroupRepository.findByOpenTrue();
        Map<String, double[]> byUnderlying = new HashMap<>();
        Map<String, Integer> groupCounts = new HashMap<>();

        double totalDelta = 0, totalGamma = 0, totalTheta = 0, totalVega = 0;

        for (PositionGroupEntity group : openGroups) {
            if (group.getStatus() != PositionGroupStatus.OPEN) continue;

            String underlying = group.getUnderlying().name();
            IndexType indexType = IndexType.from(group.getUnderlying());
            double spot = getSpotPrice(indexType);
            if (spot <= 0) continue;

            groupCounts.merge(underlying, 1, Integer::sum);

            for (SpreadLegEntity leg : group.getLegs()) {
                if (!leg.isActive()) continue;
                if (leg.getExpiry() == null) continue;

                double T = greeksCalculator.timeToExpiry(leg.getExpiry());
                if (T <= 0) continue;

                boolean isCall = leg.getOptionType() == com.algo.trade.domain.OptionType.CE;
                double lastPrice = getLegPrice(leg.getInstrumentKey());
                if (lastPrice <= 0) continue;

                double iv = greeksCalculator.calculateIV(spot, leg.getStrike(), T,
                        0.065, 0.0, lastPrice, isCall);
                if (iv <= 0) continue;

                double[] greeks = greeksCalculator.calculateGreeks(spot, leg.getStrike(), T,
                        0.065, 0.0, iv, isCall);

                // Sign convention: BUY = +1, SELL = -1
                double sign = leg.getOrderSide() == OrderSide.BUY ? 1.0 : -1.0;
                double qty = leg.getQuantity();

                double legDelta = greeks[0] * sign * qty;
                double legGamma = greeks[1] * sign * qty;
                double legTheta = greeks[2] * sign * qty;
                double legVega = greeks[3] * sign * qty;

                totalDelta += legDelta;
                totalGamma += legGamma;
                totalTheta += legTheta;
                totalVega += legVega;

                byUnderlying.merge(underlying,
                        new double[]{legDelta, legGamma, legTheta, legVega},
                        (a, b) -> new double[]{a[0] + b[0], a[1] + b[1], a[2] + b[2], a[3] + b[3]});
            }
        }

        Map<String, PerUnderlyingGreeks> perUnderlying = new HashMap<>();
        for (Map.Entry<String, double[]> entry : byUnderlying.entrySet()) {
            double[] g = entry.getValue();
            perUnderlying.put(entry.getKey(), new PerUnderlyingGreeks(
                    entry.getKey(), g[0], g[1], g[2], g[3],
                    groupCounts.getOrDefault(entry.getKey(), 0)));
        }

        return new PortfolioGreeks(totalDelta, totalGamma, totalTheta, totalVega, perUnderlying);
    }

    private double getSpotPrice(IndexType indexType) {
        try {
            return liveInstrumentCache.getFuturesPrice(indexType);
        } catch (Exception e) {
            return 0.0;
        }
    }

    private double getLegPrice(String instrumentKey) {
        try {
            var quote = marketDataService.quote(instrumentKey);
            return quote.map(q -> q.lastPrice().doubleValue()).orElse(0.0);
        } catch (Exception e) {
            return 0.0;
        }
    }
}
